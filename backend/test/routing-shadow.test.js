import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { after, before, describe, it } from 'node:test';
import { commonRoad, createShadow, createShadowQueue, createShadowStore, routeLine } from '../src/routing/shadow.js';
import { ETOILE, PARIS, appRoute, deferred, fakeDb, line, trapNetwork, wait } from './helpers/routing.js';

// The shadow mode (shadow.js): its queue, its comparisons, what it writes (a fake database) and
// what it never writes.
const net = trapNetwork(before, after);

const ROUTE = line(PARIS, ETOILE, 40);
const OTHER_ROAD = line(PARIS, ETOILE, 40, 0.02);
const ok = (engine, coordinates, options) => ({ ok: true, route: appRoute(engine, coordinates, options), latencyMs: 42 });
const THRESHOLDS = { durationRatio: 0.10, minShare: 0.70 };

/** Everything a stored line could leak: the trip's coordinates, as text. */
const COORDINATES = /48[.,]8[5-7]\d|2[.,]3[0-5]\d|2[.,]29\d/;

describe('queue', () => {
  it('runs a comparison only once the answer is finished', async () => {
    const queue = createShadowQueue({ maxQueue: 5, concurrency: 1 });
    const res = new EventEmitter();
    res.writableFinished = false;
    let ran = false;
    queue.after(res, async () => { ran = true; });
    await wait(15);
    assert.equal(ran, false);
    assert.equal(queue.stats().queued, 0);
    res.writableFinished = true;
    res.emit('finish');
    res.emit('close');
    await queue.idle();
    assert.equal(ran, true);
    assert.equal(queue.stats().done, 1);
    // Already finished: queued at once; a client gone before the end: queued on close.
    const sent = new EventEmitter();
    sent.writableFinished = true;
    queue.after(sent, async () => {});
    const gone = new EventEmitter();
    gone.writableFinished = false;
    queue.after(gone, async () => {});
    gone.emit('close');
    await queue.idle();
    assert.equal(queue.stats().done, 3);
  });

  it('is bounded: past maxQueue waiting, a comparison is dropped and counted', async () => {
    const queue = createShadowQueue({ maxQueue: 2, concurrency: 1 });
    const gate = deferred();
    assert.equal(queue.enqueue(() => gate.promise), true);
    assert.equal(queue.enqueue(async () => {}), true);
    assert.equal(queue.enqueue(async () => {}), true);
    assert.equal(queue.enqueue(async () => {}), false);
    assert.deepEqual(queue.stats(), { queued: 2, running: 1, done: 0, failed: 0, dropped: 1, max: 2 });
    gate.resolve();
    await queue.idle();
    assert.deepEqual(queue.stats(), { queued: 0, running: 0, done: 3, failed: 0, dropped: 1, max: 2 });
  });

  it('isolates errors and keeps to its concurrency', async () => {
    const queue = createShadowQueue({ maxQueue: 10, concurrency: 2 });
    let running = 0;
    let most = 0;
    const job = (fail) => async () => {
      running += 1;
      most = Math.max(most, running);
      await wait(5);
      running -= 1;
      if (fail) throw new Error('comparison broke');
    };
    const warn = console.warn;
    console.warn = () => {};
    try {
      queue.enqueue(job(true));
      queue.enqueue(() => { throw new Error('sync throw'); });
      for (let i = 0; i < 5; i++) queue.enqueue(job(false));
      await queue.idle();
    } finally {
      console.warn = warn;
    }
    assert.equal(most, 2);
    assert.equal(queue.stats().failed, 2);
    assert.equal(queue.stats().done, 5);
  });
});

describe('comparison', () => {
  it('finds the same road the same, and says when both diverge (D7.1: ±10 % or < 70 % common)', () => {
    assert.deepEqual(commonRoad(appRoute('ors', ROUTE), appRoute('valhalla', ROUTE)), { aOnB: 1, bOnA: 1 });
    const same = routeLine({
      mode: 'ors', served: 'ors', fallback: false, cause: null, avoid: ['tolls'],
      attempts: { ors: ok('ors', ROUTE, { durationS: 600 }), valhalla: ok('valhalla', ROUTE, { durationS: 650 }) }, ...THRESHOLDS,
    });
    assert.equal(same.divergent, false);
    assert.deepEqual(same.shares, { ors: 1, valhalla: 1 });
    assert.equal(same.ors.durationS, 600);
    assert.equal(same.valhalla.latencyMs, 42);
    const slower = routeLine({
      mode: 'ors', served: 'ors', avoid: [], attempts: { ors: ok('ors', ROUTE, { durationS: 600 }), valhalla: ok('valhalla', ROUTE, { durationS: 661 }) }, ...THRESHOLDS,
    });
    assert.equal(slower.divergent, true);
    const elsewhere = routeLine({
      mode: 'ors', served: 'ors', avoid: [], attempts: { ors: ok('ors', ROUTE), valhalla: ok('valhalla', OTHER_ROAD) }, ...THRESHOLDS,
    });
    assert.ok(elsewhere.shares.ors < 0.7);
    assert.equal(elsewhere.divergent, true);
  });

  it('keeps a fallback as it is: the failed engine\'s error, nothing compared', () => {
    const fallback = routeLine({
      mode: 'all', served: 'ors', fallback: true, cause: 'timeout', avoid: [],
      attempts: { valhalla: { ok: false, error: 'timeout', latencyMs: 4000 }, ors: ok('ors', ROUTE) }, ...THRESHOLDS,
    });
    assert.equal(fallback.divergent, null);
    assert.deepEqual(fallback.shares, { ors: null, valhalla: null });
    assert.equal(fallback.valhalla.ok, false);
    assert.equal(fallback.valhalla.error, 'timeout');
    assert.equal(fallback.fallback, true);
    const skipped = routeLine({
      mode: 'ors', served: 'ors', avoid: [], attempts: { ors: ok('ors', ROUTE), valhalla: { ok: false, error: 'breaker_open', skipped: true, latencyMs: null } }, ...THRESHOLDS,
    });
    assert.equal(skipped.valhalla.ok, null); // not asked
    assert.equal(skipped.valhalla.error, 'breaker_open');
  });
});

describe('store', () => {
  it('writes measures only: no coordinate, no account, known avoid options only', async () => {
    const db = fakeDb((sql) => ({ rows: sql.startsWith('INSERT INTO routing.shadow_run') ? [{ id: '7' }] : [] }));
    const store = createShadowStore({ db });
    const line = routeLine({
      mode: 'ors', served: 'ors', fallback: false, cause: null, avoid: ['tolls', 'x"; DROP TABLE', 'traffic', 'tolls'],
      attempts: { ors: ok('ors', ROUTE), valhalla: ok('valhalla', OTHER_ROAD, { uturn: true }) }, ...THRESHOLDS,
    });
    assert.equal(await store.recordRun(line), 7);
    const insert = db.queries.find((q) => q.sql.includes('INSERT INTO routing.shadow_run'));
    assert.equal(insert.params.length, 24);
    assert.deepEqual(insert.params[5], ['tolls', 'traffic']);
    assert.equal(insert.params[19], true); // valhalla_uturn_start
    const written = JSON.stringify(insert.params);
    assert.doesNotMatch(written, COORDINATES);
    assert.doesNotMatch(written, /driver-1|admin-1|Rivoli/);
  });

  it('purges on start, then every hour without a single trip, until stopped: traces past 30 days, measures past 90', async (t) => {
    t.mock.timers.enable({ apis: ['setInterval'] });
    const db = fakeDb();
    const store = createShadowStore({ db, keepDays: 90, traceKeepDays: 30 });
    const purges = () => db.queries.filter((q) => q.sql.startsWith('DELETE')).map((q) => [q.sql.match(/routing\.\w+/)[0], q.params]);
    // Built, as at import: nothing until start().
    t.mock.timers.tick(3 * 3600_000);
    await wait(0);
    assert.deepEqual(purges(), []);
    await store.start();
    await store.start(); // once only
    assert.deepEqual(purges(), [['routing.shadow_trace', [30]], ['routing.shadow_run', [90]]]);
    t.mock.timers.tick(3600_000 - 1);
    await wait(0);
    assert.equal(purges().length, 2);
    t.mock.timers.tick(1);
    await wait(0);
    assert.equal(purges().length, 4);
    t.mock.timers.tick(3600_000);
    await wait(0);
    assert.equal(purges().length, 6);
    store.stop();
    t.mock.timers.tick(5 * 3600_000);
    await wait(0);
    assert.equal(purges().length, 6);
    assert.equal(db.queries.length, 6); // purges only, no line written
  });

  it('only logs a failed purge, and tries again the next hour', async (t) => {
    t.mock.timers.enable({ apis: ['setInterval'] });
    let down = true;
    const db = fakeDb(() => {
      if (down) throw new Error('database down');
      return { rows: [] };
    });
    const warn = t.mock.method(console, 'warn', () => {});
    const store = createShadowStore({ db });
    await store.start(); // never rejects: nothing else waits on it
    assert.equal(warn.mock.callCount(), 1);
    assert.match(warn.mock.calls[0].arguments.join(' '), /purge — database down/);
    down = false;
    t.mock.timers.tick(3600_000);
    await wait(0);
    assert.equal(db.queries.filter((q) => q.sql.startsWith('DELETE FROM routing.shadow_run')).length, 1);
    assert.equal(warn.mock.callCount(), 1);
    store.stop();
  });

  it('schedules nothing at import; started, one hourly timer that never holds the process', async (t) => {
    const setInterval = t.mock.method(globalThis, 'setInterval');
    await import('../src/routing/shadow.js?import-only');
    assert.equal(setInterval.mock.callCount(), 0);
    const store = createShadowStore({ db: fakeDb() });
    await store.start();
    assert.equal(setInterval.mock.callCount(), 1);
    const [call] = setInterval.mock.calls;
    assert.equal(call.arguments[1], 3600_000);
    assert.equal(call.result.hasRef(), false);
    store.stop();
  });

  it('reads only what the purge keeps: measures 90 days, traces 30 days and their measure, as parameters', async () => {
    const db = fakeDb();
    const store = createShadowStore({ db, keepDays: 90, traceKeepDays: 30 });
    await store.runs({ kind: 'route', limit: 5 });
    await store.summary({ since: '2026-09-01T00:00:00.000Z' });
    await store.traces({ limit: 10 });
    assert.equal(await store.trace('12'), null);
    const [runs, summary, errors, traces, trace] = db.queries;
    const kept = (column, n) => new RegExp(`${column.replace('.', '\\.')} >= now\\(\\) - make_interval\\(days => \\$${n}\\)`, 'g');
    assert.deepEqual(runs.params, [null, 'route', 5, 90]);
    assert.match(runs.sql, kept('at', 4));
    assert.deepEqual(summary.params, ['2026-09-01T00:00:00.000Z', 90]);
    assert.match(summary.sql, kept('at', 2));
    assert.deepEqual(errors.params, ['2026-09-01T00:00:00.000Z', 90]);
    assert.equal(errors.sql.match(kept('at', 2)).length, 2); // both engines' errors
    assert.deepEqual(traces.params, [null, 10, 30, 90]);
    assert.match(traces.sql, kept('t.at', 3));
    assert.match(traces.sql, kept('r.at', 4));
    assert.deepEqual(trace.params, ['12', 30, 90]);
    assert.match(trace.sql, kept('t.at', 2));
    assert.match(trace.sql, kept('r.at', 3));
    // No day count written into the SQL itself.
    for (const q of db.queries) assert.doesNotMatch(q.sql, /days => \d/);
  });

  it('writes an admin trace as two LineStrings, and reads it back as GeoJSON for a map', async () => {
    const db = fakeDb((sql) => {
      if (!sql.includes('ST_AsGeoJSON')) return { rows: [] };
      return {
        rows: [{
          id: '3', at: new Date('2026-09-26T08:00:00Z'), kind: 'route', run_id: '9', mode: 'admins', served: 'valhalla', avoid: [],
          ors_distance_m: 5000, ors_duration_s: 600, valhalla_distance_m: 5100, valhalla_duration_s: 700, share_ors: 0.5, share_valhalla: 0.4,
          detail: null, ors_line: JSON.stringify({ type: 'LineString', coordinates: ROUTE }), valhalla_line: null,
        }],
      };
    });
    const store = createShadowStore({ db });
    await store.recordTrace({ runId: 9, kind: 'route', ors: ROUTE, valhalla: OTHER_ROAD });
    const [insert] = db.queries;
    assert.match(insert.sql, /INSERT INTO routing\.shadow_trace/);
    assert.equal(insert.params[0], 9);
    assert.deepEqual(JSON.parse(insert.params[2]), { type: 'LineString', coordinates: ROUTE });
    assert.deepEqual(JSON.parse(insert.params[3]).coordinates, OTHER_ROAD);
    const trace = await store.trace('3');
    assert.equal(trace.type, 'FeatureCollection');
    assert.equal(trace.properties.runId, 9);
    assert.deepEqual(trace.features.map((f) => [f.properties.engine, f.geometry.type]), [['ors', 'LineString']]);
    assert.equal(await createShadowStore({ db: fakeDb() }).trace('4'), null);
  });
});

describe('shadow', () => {
  /** A shadow over a fake routing façade and a real store over a fake database. */
  function setup({ other = ok('valhalla', ROUTE) } = {}) {
    const asked = [];
    const routing = {
      shadowRoute: async (...args) => {
        asked.push(args);
        return typeof other === 'function' ? other() : other;
      },
    };
    const db = fakeDb((sql) => ({ rows: sql.startsWith('INSERT INTO routing.shadow_run') ? [{ id: 11 }] : [] }));
    const queue = createShadowQueue({ maxQueue: 10, concurrency: 1 });
    const shadow = createShadow({ routing, queue, store: createShadowStore({ db }), ...THRESHOLDS });
    const res = new EventEmitter();
    res.writableFinished = true;
    return { asked, db, queue, shadow, res };
  }
  const outcome = (attempts, extra = {}) => ({ engine: 'ors', primary: 'ors', fallback: false, cause: null, attempts, polygons: null, ...extra });
  const plan = { mode: 'ors', primary: 'ors', shadow: 'valhalla' };

  it('asks the other engine the same trip, with the same jams, and keeps the measures', async () => {
    const s = setup();
    const jams = { type: 'MultiPolygon', coordinates: [] };
    s.shadow.afterRoute(s.res, { plan, outcome: outcome({ ors: ok('ors', ROUTE) }, { polygons: jams }), from: PARIS, to: ETOILE, avoid: ['traffic'], admin: false });
    await s.queue.idle();
    assert.deepEqual(s.asked, [['valhalla', PARIS, ETOILE, ['traffic'], jams]]);
    assert.equal(s.db.queries.filter((q) => q.sql.includes('INSERT INTO routing.shadow_run')).length, 1);
    assert.equal(s.db.queries.filter((q) => q.sql.includes('shadow_trace') && q.sql.startsWith('INSERT')).length, 0);
  });

  it('keeps both lines for an admin account only, and only when they diverge', async () => {
    const traces = (s) => s.db.queries.filter((q) => q.sql.startsWith('INSERT INTO routing.shadow_trace'));
    const driver = setup({ other: ok('valhalla', OTHER_ROAD) });
    driver.shadow.afterRoute(driver.res, { plan, outcome: outcome({ ors: ok('ors', ROUTE) }), from: PARIS, to: ETOILE, avoid: [], admin: false });
    await driver.queue.idle();
    assert.equal(traces(driver).length, 0);
    assert.doesNotMatch(JSON.stringify(driver.db.queries.map((q) => q.params)), COORDINATES);
    const calm = setup({ other: ok('valhalla', ROUTE) });
    calm.shadow.afterRoute(calm.res, { plan, outcome: outcome({ ors: ok('ors', ROUTE) }), from: PARIS, to: ETOILE, avoid: [], admin: true });
    await calm.queue.idle();
    assert.equal(traces(calm).length, 0);
    const admin = setup({ other: ok('valhalla', OTHER_ROAD) });
    admin.shadow.afterRoute(admin.res, { plan, outcome: outcome({ ors: ok('ors', ROUTE) }), from: PARIS, to: ETOILE, avoid: [], admin: true });
    await admin.queue.idle();
    assert.equal(traces(admin).length, 1);
    assert.equal(traces(admin)[0].params[0], 11);
  });

  it('asks nothing more for a fallback (both engines already tried), nothing at all without Valhalla', async () => {
    const s = setup();
    const fell = outcome({ valhalla: { ok: false, error: 'unavailable', latencyMs: 3 }, ors: ok('ors', ROUTE) }, { fallback: true, cause: 'unavailable', primary: 'valhalla' });
    s.shadow.afterRoute(s.res, { plan: { mode: 'all', primary: 'valhalla', shadow: 'ors' }, outcome: fell, from: PARIS, to: ETOILE, avoid: [], admin: true });
    await s.queue.idle();
    assert.equal(s.asked.length, 0);
    const insert = s.db.queries.find((q) => q.sql.includes('INSERT INTO routing.shadow_run'));
    assert.deepEqual(insert.params.slice(0, 5), ['route', 'all', 'ors', true, 'unavailable']);
    const off = setup();
    off.shadow.afterRoute(off.res, { plan: { mode: 'all', primary: 'ors', shadow: null }, outcome: outcome({ ors: ok('ors', ROUTE) }), from: PARIS, to: ETOILE, avoid: [], admin: true });
    off.shadow.afterFaster(off.res, { plan: { mode: 'all', primary: 'ors', shadow: null }, compare: {}, avoid: [], admin: true });
    await off.queue.idle();
    assert.deepEqual(off.queue.stats().done, 0);
    assert.equal(off.db.queries.length, 0);
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
