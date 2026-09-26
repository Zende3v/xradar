import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { after, before, describe, it } from 'node:test';
import { config } from '../src/config.js';
import { createRoutingAlert } from '../src/routing/alert.js';
import { createRoutingEngine } from '../src/routing/engine.js';
import { VALHALLA_ERROR, ValhallaError, createValhallaClient } from '../src/routing/providers/valhalla.js';
import {
  BRUSSELS, ETOILE, ORS_SPENT, PARIS, appRoute, fakeOrs, fakeValhalla, line, orsAnswer, orsRefusal, trapNetwork,
  valhallaAnswer,
} from './helpers/routing.js';

// The routing façade (engine.js) with fake engines: no network, no database.
const net = trapNetwork(before, after);

const ADMIN = { id: 'admin-1', role: 'admin' };
const DRIVER = { id: 'driver-1', role: 'client' };
const JAMS = { type: 'MultiPolygon', coordinates: [[[[2.32, 48.86], [2.33, 48.86], [2.33, 48.87], [2.32, 48.87], [2.32, 48.86]]]] };
const STATUS = {
  version: '3.9.0', tileset_last_modified: 1789873200, has_tiles: true, has_admins: true, has_timezones: true,
  has_live_traffic: false, has_transit_tiles: false, osm_changeset: 1, available_actions: ['route', 'status'], bbox: null, warnings: null,
};

function engine({ ors = fakeOrs(), valhalla = fakeValhalla(), mode = 'ors', now, alert = null, jams, settings = {} } = {}) {
  let current = mode;
  const routing = createRoutingEngine({
    ors,
    valhalla,
    mode: () => current,
    now,
    alert,
    jams,
    settings: {
      deadlineMs: 2000, valhallaTimeoutMs: 500, jamsTimeoutMs: 200, breakerFailures: 3, breakerOpenMs: 10_000,
      alertAfterMs: 60_000, fallbackMemoryMs: 1000, shadowOrsMinBudgetLeft: 100, accountsMax: 100, ...settings,
    },
  });
  return Object.assign(routing, { setMode: (m) => { current = m; }, ors, valhalla });
}

/** A Valhalla client of providers/valhalla.js over a fake fetch answering [answer] (JSON) with [status]. */
function realClient(answer, { status = 200, sent = [] } = {}) {
  return createValhallaClient({
    baseUrl: 'http://127.0.0.1:8002',
    timeoutMs: 500,
    searchCutoffM: 1000,
    maxSnapM: 350,
    fetchImpl: async (url, init) => {
      sent.push({ url, body: JSON.parse(init.body) });
      return { status, text: async () => JSON.stringify(typeof answer === 'function' ? answer() : answer) };
    },
  });
}

const failing = (code) => fakeValhalla({ routes: () => { throw new ValhallaError(code, 'Valhalla: failed'); } });

describe('who serves', () => {
  it('mode ors: ORS serves everyone, Valhalla only in shadow, never asked for the route', async () => {
    const e = engine();
    assert.deepEqual(e.plan(DRIVER), { mode: 'ors', primary: 'ors', shadow: 'valhalla' });
    assert.deepEqual(e.plan(ADMIN), { mode: 'ors', primary: 'ors', shadow: 'valhalla' });
    const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(ADMIN) });
    assert.equal(out.engine, 'ors');
    assert.equal(out.route.engine, 'ors');
    assert.equal(out.fallback, false);
    assert.equal(e.valhalla.calls.length, 0);
    assert.equal(e.ors.bodies.length, 1);
  });

  it('mode admins: admin accounts on Valhalla (ORS in shadow), the others on ORS; all: everyone on Valhalla', async () => {
    const e = engine({ mode: 'admins' });
    assert.deepEqual(e.plan(ADMIN), { mode: 'admins', primary: 'valhalla', shadow: 'ors' });
    assert.deepEqual(e.plan(DRIVER), { mode: 'admins', primary: 'ors', shadow: 'valhalla' });
    assert.deepEqual(e.plan(null), { mode: 'admins', primary: 'ors', shadow: 'valhalla' });
    const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(ADMIN) });
    assert.equal(out.engine, 'valhalla');
    assert.equal(out.route.engine, 'valhalla');
    assert.equal(e.ors.bodies.length, 0);
    e.setMode('all');
    assert.deepEqual(e.plan(DRIVER), { mode: 'all', primary: 'valhalla', shadow: 'ors' });
    e.setMode('anything');
    assert.deepEqual(e.plan(ADMIN), { mode: 'ors', primary: 'ors', shadow: 'valhalla' });
  });

  it('without Valhalla (flag off): ORS serves in every mode, no shadow, nothing ever calls Valhalla', async () => {
    for (const mode of ['ors', 'admins', 'all']) {
      const e = engine({ valhalla: null, mode });
      assert.deepEqual(e.plan(ADMIN), { mode, primary: 'ors', shadow: null });
      const out = await e.route(PARIS, ETOILE, ['tolls'], { plan: e.plan(ADMIN) });
      assert.equal(out.engine, 'ors');
      assert.equal(out.fallback, false);
      e.start();
      await e.refreshStatus();
      e.stop();
      assert.equal(e.valhallaBlocked(), 'disabled');
      assert.deepEqual(e.health().valhalla, { enabled: false, state: 'disabled', cause: 'disabled' });
      assert.equal(e.health().fallbacks.total, 0);
    }
    // The backend's own switch: only an explicit VALHALLA_ENABLED turns it on.
    const explicit = /^(1|true)$/i.test(process.env.VALHALLA_ENABLED || '');
    assert.equal(config.valhallaEnabled, explicit);
    assert.equal(config.valhallaUrl, process.env.VALHALLA_URL || 'http://127.0.0.1:8002');
  });

  it('keeps the settings\' deadlines under the apps\' 15 s', () => {
    assert.ok(config.routeDeadlineMs < 15_000);
    assert.ok(config.valhallaTimeoutMs < config.routeDeadlineMs);
    assert.ok(config.orsTimeoutMs <= config.routeDeadlineMs);
    assert.ok(config.routeJamsTimeoutMs < config.routeDeadlineMs);
  });
});

describe('fallback to ORS', () => {
  it('serves with ORS when Valhalla fails, counting the fallback with its cause', async () => {
    const codes = [
      VALHALLA_ERROR.UNAVAILABLE, VALHALLA_ERROR.TIMEOUT, VALHALLA_ERROR.OUT_OF_COVERAGE, VALHALLA_ERROR.NO_ROUTE,
      VALHALLA_ERROR.EXCLUSIONS_IGNORED, VALHALLA_ERROR.INVALID_RESPONSE, VALHALLA_ERROR.REJECTED,
    ];
    for (const code of codes) {
      const e = engine({ mode: 'all', valhalla: failing(code) });
      const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
      assert.equal(out.engine, 'ors', code);
      assert.equal(out.route.engine, 'ors');
      assert.equal(out.fallback, true);
      assert.equal(out.cause, code);
      assert.equal(out.attempts.valhalla.ok, false);
      assert.equal(out.attempts.ors.ok, true);
      const { fallbacks } = e.health();
      assert.equal(fallbacks.total, 1);
      assert.deepEqual(fallbacks.byCause, { [code]: 1 });
      assert.equal(fallbacks.last.cause, code);
    }
  });

  it('sends a destination in Belgium to ORS: decided by the snap, never by a bounding box', async () => {
    // Valhalla (France only) finds no road near Brussels (NoSegment)...
    const none = engine({ mode: 'all', valhalla: realClient({ code: 'NoSegment', message: 'No suitable edges near location' }, { status: 400 }) });
    const a = await none.route(PARIS, BRUSSELS, [], { plan: none.plan(DRIVER) });
    assert.equal(a.engine, 'ors');
    assert.equal(a.cause, VALHALLA_ERROR.OUT_OF_COVERAGE);
    // ...or snaps it onto the nearest French road, far past maxSnapM: the same.
    const border = { lat: 50.7865, lon: 3.1201 };
    const far = engine({ mode: 'all', valhalla: realClient(valhallaAnswer(line(PARIS, border))) });
    const b = await far.route(PARIS, BRUSSELS, [], { plan: far.plan(DRIVER) });
    assert.equal(b.engine, 'ors');
    assert.equal(b.cause, VALHALLA_ERROR.OUT_OF_COVERAGE);
    // A French trip on the same client kind stays on Valhalla.
    const home = engine({ mode: 'all', valhalla: realClient(valhallaAnswer(line(PARIS, ETOILE))) });
    const c = await home.route(PARIS, ETOILE, [], { plan: home.plan(DRIVER) });
    assert.equal(c.engine, 'valhalla');
  });

  it('keeps every avoid option: strict exclusions on Valhalla, avoid_features on ORS, the jams on both', async () => {
    const sent = [];
    const e = engine({ mode: 'all', valhalla: realClient(valhallaAnswer(line(PARIS, ETOILE)), { sent }), jams: async () => JAMS });
    const out = await e.route(PARIS, ETOILE, ['tolls', 'highways', 'ferries', 'traffic'], { plan: e.plan(DRIVER) });
    assert.equal(out.engine, 'valhalla');
    assert.equal(sent[0].url, 'http://127.0.0.1:8002/route');
    assert.deepEqual(sent[0].body.costing_options.auto, { top_speed: 130, exclude_tolls: true, exclude_highways: true, exclude_ferries: true });
    assert.equal(sent[0].body.exclude_polygons.length, 1);
    // A server dropping the exclusions (warning 208): ORS serves, with the same avoid options.
    const dropped = { ...valhallaAnswer(line(PARIS, ETOILE)), warnings: [{ code: 208, text: 'hard exclusions not allowed' }] };
    const f = engine({ mode: 'all', valhalla: realClient(dropped), jams: async () => JAMS });
    const out2 = await f.route(PARIS, ETOILE, ['tolls', 'highways', 'ferries', 'traffic'], { plan: f.plan(DRIVER) });
    assert.equal(out2.engine, 'ors');
    assert.equal(out2.cause, VALHALLA_ERROR.EXCLUSIONS_IGNORED);
    assert.deepEqual(f.ors.bodies[0].options, { avoid_features: ['tollways', 'highways', 'ferries'], avoid_polygons: JAMS });
    // Mode ors: ORS's body as before phase 2.
    const g = engine({ jams: async () => JAMS });
    await g.route(PARIS, ETOILE, ['tolls', 'traffic'], { plan: g.plan(DRIVER) });
    assert.deepEqual(g.ors.bodies[0], {
      coordinates: [[PARIS.lon, PARIS.lat], [ETOILE.lon, ETOILE.lat]],
      instructions: true,
      maneuvers: true,
      geometry_simplify: false,
      options: { avoid_features: ['tollways'], avoid_polygons: JAMS },
    });
  });

  it('tries Valhalla again without the jams before ORS (D4.3), and ORS the same way', async () => {
    const v = fakeValhalla({
      routes: (from, to, options) => {
        if (options.polygons) throw new ValhallaError(VALHALLA_ERROR.NO_ROUTE, 'Valhalla: no route');
        return [appRoute('valhalla', line(from, to))];
      },
    });
    const e = engine({ mode: 'all', valhalla: v, jams: async () => JAMS });
    const out = await e.route(PARIS, ETOILE, ['traffic'], { plan: e.plan(DRIVER) });
    assert.equal(out.engine, 'valhalla');
    assert.deepEqual(v.calls.map((c) => c.options.polygons), [JAMS, null]);
    assert.equal(e.ors.bodies.length, 0);
    // An outage is no reason to try again: ORS at once, with the jams, then without.
    const ors = fakeOrs({ respond: (body) => (body.options?.avoid_polygons ? orsRefusal(400, 'no route') : orsAnswer([])) });
    const down = fakeValhalla({ routes: () => { throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, 'x'); } });
    const f = engine({ mode: 'all', ors, valhalla: down, jams: async () => JAMS });
    await f.route(PARIS, ETOILE, ['traffic'], { plan: f.plan(DRIVER) });
    assert.equal(down.calls.length, 1);
    assert.deepEqual(ors.bodies.map((b) => Boolean(b.options?.avoid_polygons)), [true, false]);
  });

  it('answers 503 without ORS budget, 502 on an ORS error, 404 without a route; never OSRM', async () => {
    const cases = [[ORS_SPENT, 503, 'routing budget reached'], [orsRefusal(500), 502, 'ORS 500'], [orsAnswer([]), 404, 'no route found']];
    for (const [answer, status, error] of cases) {
      const e = engine({ mode: 'all', ors: fakeOrs({ respond: () => answer }), valhalla: failing(VALHALLA_ERROR.UNAVAILABLE) });
      const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
      assert.equal(out.route, null);
      assert.equal(out.engine, null);
      assert.equal(out.status, status);
      assert.equal(out.error, error);
      assert.equal(out.thrown, false);
    }
    // Without an ORS key, a route ORS should serve fails: no OSRM demo behind it.
    const e = engine({ ors: fakeOrs({ configured: false }) });
    const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(out.status, 503);
    assert.equal(out.error, 'routing unavailable');
    assert.equal(e.ors.bodies.length, 0);
    assert.equal('osrmUrl' in config, false);
    assert.equal('osrmTimeoutMs' in config, false);
    for (const file of ['../src/routing/engine.js', '../src/routing/routes.js', '../src/routing/faster.js', '../src/server.js']) {
      assert.doesNotMatch(readFileSync(new URL(file, import.meta.url), 'utf8'), /project-osrm|osrmUrl|routeViaOSRM|provider: 'osrm'/);
    }
  });
});

describe('deadlines', { timeout: 5000 }, () => {
  it('cuts a silent Valhalla at valhallaTimeoutMs and gives ORS what is left', async () => {
    const e = engine({ mode: 'all', valhalla: fakeValhalla({ routes: () => new Promise(() => {}) }), settings: { deadlineMs: 700, valhallaTimeoutMs: 150 } });
    const startedAt = Date.now();
    const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    const ms = Date.now() - startedAt;
    assert.equal(out.engine, 'ors');
    assert.equal(out.cause, VALHALLA_ERROR.TIMEOUT);
    assert.ok(ms >= 140 && ms < 700, `${ms} ms`);
  });

  it('answers 502 within the deadline when ORS is silent too, and stops ORS through its signal', async () => {
    const signals = [];
    const ors = fakeOrs({ respond: (_body, signal) => { signals.push(signal); return new Promise(() => {}); } });
    const e = engine({ mode: 'all', ors, valhalla: fakeValhalla({ routes: () => new Promise(() => {}) }), settings: { deadlineMs: 400, valhallaTimeoutMs: 100 } });
    const startedAt = Date.now();
    const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    const ms = Date.now() - startedAt;
    assert.equal(out.route, null);
    assert.equal(out.status, 502);
    assert.equal(out.error, 'routing unavailable');
    assert.equal(out.attempts.ors.error, 'timeout');
    assert.ok(ms >= 380 && ms < 550, `${ms} ms`);
    assert.equal(signals.length, 1);
    assert.equal(signals[0].aborted, true);
  });

  it('holds the jams lookup to jamsTimeoutMs: past it, the route ignores them', async () => {
    const e = engine({ jams: () => new Promise(() => {}), settings: { jamsTimeoutMs: 80 } });
    const startedAt = Date.now();
    const out = await e.route(PARIS, ETOILE, ['traffic'], { plan: e.plan(DRIVER) });
    assert.ok(Date.now() - startedAt < 400);
    assert.equal(out.engine, 'ors');
    assert.equal(out.polygons, null);
    assert.equal(e.ors.bodies[0].options, undefined);
  });
});

describe('breaker', () => {
  it('opens after breakerFailures outages in a row: Valhalla left alone breakerOpenMs, then tried again', async () => {
    let clock = 1_000_000;
    let down = true;
    const v = fakeValhalla({
      routes: (from, to) => {
        if (down) throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, 'Valhalla: unreachable');
        return [appRoute('valhalla', line(from, to))];
      },
    });
    const e = engine({ mode: 'all', valhalla: v, now: () => clock });
    for (let i = 0; i < 3; i++) await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(v.calls.length, 3);
    assert.equal(e.valhallaBlocked(), 'breaker_open');
    const skipped = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(v.calls.length, 3);
    assert.equal(skipped.engine, 'ors');
    assert.equal(skipped.cause, 'breaker_open');
    assert.equal(skipped.attempts.valhalla.skipped, true);
    const health = e.health();
    assert.equal(health.valhalla.state, 'down');
    assert.equal(health.valhalla.cause, 'breaker_open');
    assert.equal(health.valhalla.breaker.open, true);
    assert.deepEqual(health.fallbacks.byCause, { unavailable: 3, breaker_open: 1 });
    // Past breakerOpenMs, half open: the next route tries Valhalla, and one answer closes it.
    clock += 10_000;
    down = false;
    assert.equal(e.valhallaBlocked(), null);
    const back = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(back.engine, 'valhalla');
    assert.equal(e.health().valhalla.breaker.failures, 0);
    assert.equal(e.health().valhalla.cause, null);
  });

  it('counts outages only: a refusal Valhalla answers (no route, out of the map) proves it alive', async () => {
    const codes = [VALHALLA_ERROR.TIMEOUT, VALHALLA_ERROR.UNAVAILABLE, VALHALLA_ERROR.NO_ROUTE, VALHALLA_ERROR.INVALID_RESPONSE, VALHALLA_ERROR.OUT_OF_COVERAGE];
    let i = 0;
    const v = fakeValhalla({ routes: () => { throw new ValhallaError(codes[i++ % codes.length], 'x'); } });
    const e = engine({ mode: 'all', valhalla: v });
    for (let n = 0; n < 10; n++) await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(v.calls.length, 10);
    assert.equal(e.valhallaBlocked(), null);
  });
});

describe('status and alert', () => {
  it('reads /status off the requests: map date, version, has_live_traffic (null without verbose)', async () => {
    const v = fakeValhalla({ status: () => ({ ...STATUS, has_tiles: null, has_live_traffic: null, has_admins: null }) });
    const e = engine({ mode: 'all', valhalla: v });
    await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(v.statuses.length, 0);
    assert.equal(e.health().valhalla.state, 'unknown');
    await e.refreshStatus();
    const h = e.health().valhalla;
    assert.equal(h.state, 'up');
    assert.equal(h.version, '3.9.0');
    assert.equal(h.mapVersion, '2026-09-20T03:00:00Z');
    assert.equal(h.tilesetLastModified, 1789873200);
    assert.equal(h.hasLiveTraffic, null);
    assert.equal(h.hasTiles, null);
    assert.equal(h.statusError, null);
  });

  it('sends routes to ORS while the last /status failed, or while Valhalla has no tiles', async () => {
    let answer = () => { throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, 'x'); };
    const v = fakeValhalla({ status: () => answer() });
    const e = engine({ mode: 'all', valhalla: v });
    await e.refreshStatus();
    assert.equal(e.valhallaBlocked(), 'status_down');
    const out = await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) });
    assert.equal(out.cause, 'status_down');
    assert.equal(v.calls.length, 0);
    assert.equal(e.health().valhalla.statusError, 'unavailable');
    answer = () => ({ ...STATUS, has_tiles: false });
    await e.refreshStatus();
    assert.equal(e.valhallaBlocked(), 'no_tiles');
    answer = () => STATUS;
    await e.refreshStatus();
    assert.equal(e.valhallaBlocked(), null);
    assert.equal((await e.route(PARIS, ETOILE, [], { plan: e.plan(DRIVER) })).engine, 'valhalla');
  });

  it('mails once when Valhalla stays down past alertAfterMs, once more when it is back; a short outage mails nothing', async () => {
    let clock = 0;
    let up = false;
    const mails = [];
    const alert = { outage: (m) => mails.push(['outage', m]), recovered: (m) => mails.push(['recovered', m]) };
    const v = fakeValhalla({ status: () => { if (!up) throw new ValhallaError(VALHALLA_ERROR.TIMEOUT, 'x'); return STATUS; } });
    const e = engine({ valhalla: v, alert, now: () => clock });
    await e.refreshStatus();
    clock = 59_999;
    await e.refreshStatus();
    assert.equal(mails.length, 0);
    clock = 60_000;
    await e.refreshStatus();
    assert.deepEqual(mails, [['outage', { since: '1970-01-01T00:00:00.000Z', cause: 'status_down', mode: 'ors' }]]);
    clock = 120_000;
    await e.refreshStatus();
    assert.equal(mails.length, 1);
    assert.equal(e.health().valhalla.alerted, true);
    up = true;
    clock = 130_000;
    await e.refreshStatus();
    assert.deepEqual(mails[1], ['recovered', { since: '1970-01-01T00:00:00.000Z', until: '1970-01-01T00:02:10.000Z' }]);
    up = false;
    clock = 140_000;
    await e.refreshStatus();
    up = true;
    clock = 150_000;
    await e.refreshStatus();
    assert.equal(mails.length, 2);
  });

  it('sends no mail without recipients; with them, a text without route nor coordinate', async () => {
    const sent = [];
    const send = async (...args) => { sent.push(args); return true; };
    const warn = console.warn;
    console.warn = () => {};
    try {
      const silent = createRoutingAlert({ to: [], send });
      assert.equal(await silent.outage({ since: 'x', cause: 'status_down', mode: 'ors' }), false);
      const loud = createRoutingAlert({ to: ['team@example.org'], send });
      await loud.outage({ since: '2026-09-26T08:00:00.000Z', cause: 'breaker_open', mode: 'all' });
      await loud.recovered({ since: '2026-09-26T08:00:00.000Z', until: '2026-09-26T08:20:00.000Z' });
      // A failing SMTP never throws.
      assert.equal(await createRoutingAlert({ to: ['team@example.org'], send: async () => { throw new Error('smtp down'); } })
        .recovered({ since: 'a', until: 'b' }).catch(() => 'threw'), false);
    } finally {
      console.warn = warn;
    }
    assert.equal(sent.length, 2);
    assert.deepEqual(sent[0][0], ['team@example.org']);
    assert.match(sent[0][1], /Valhalla indisponible/);
    assert.match(sent[0][2], /breaker_open/);
    assert.doesNotMatch(sent.map((s) => s.join(' ')).join(' '), /48[.,]\d|2[.,]3\d/);
  });
});

describe('drivers, shadow and detours', () => {
  it('remembers a route ORS served for a failing Valhalla (for /faster, D5.2) until the next route or fallbackMemoryMs', () => {
    let clock = 0;
    const e = engine({ now: () => clock });
    e.noteServed('a1', { engine: 'ors', fallback: true });
    assert.equal(e.servedFallback('a1'), true);
    assert.equal(e.servedFallback('d1'), false);
    clock = 1000;
    assert.equal(e.servedFallback('a1'), false);
    e.noteServed('a1', { engine: 'ors', fallback: true });
    e.noteServed('a1', { engine: 'valhalla', fallback: false });
    assert.equal(e.servedFallback('a1'), false);
    e.noteServed('a1', { engine: 'ors', fallback: false });
    assert.equal(e.servedFallback('a1'), false);
    for (let i = 0; i < 150; i++) e.noteServed(`x${i}`, { engine: 'ors', fallback: true });
    assert.equal(e.servedFallback('x0'), false); // pushed out: accountsMax
    assert.equal(e.servedFallback('x149'), true);
  });

  it('shadow: ORS kept off under shadowOrsMinBudgetLeft, Valhalla skipped while blocked, nothing counted as a fallback', async () => {
    const ors = fakeOrs({ budgetLeft: 99 });
    let clock = 0;
    const e = engine({ ors, valhalla: failing(VALHALLA_ERROR.UNAVAILABLE), now: () => clock });
    assert.deepEqual(await e.shadowRoute('ors', PARIS, ETOILE, []), { ok: false, error: 'budget_reserved', skipped: true, latencyMs: null });
    assert.equal(ors.bodies.length, 0);
    ors.budget = 100;
    assert.equal((await e.shadowRoute('ors', PARIS, ETOILE, ['tolls'])).ok, true);
    for (let i = 0; i < 3; i++) assert.equal((await e.shadowRoute('valhalla', PARIS, ETOILE, [])).error, 'unavailable');
    const skipped = await e.shadowRoute('valhalla', PARIS, ETOILE, []);
    assert.deepEqual(skipped, { ok: false, error: 'breaker_open', skipped: true, latencyMs: null });
    assert.equal(e.valhalla.calls.length, 3);
    assert.equal(e.health().fallbacks.total, 0);
  });

  it('draws /faster variants with either engine: ORS as in phase 1, Valhalla with alternates, polygons, headings, exclusions', async () => {
    const e = engine();
    const ask = { label: 'alternatives', from: PARIS, to: ETOILE, bearings: [[90, 45], [180, 45]], avoid: ['tolls', 'traffic', 'junk'], polygons: null, alternatives: true };
    const byOrs = await e.drawer('ors')(ask);
    assert.equal(byOrs.routes[0].engine, 'ors');
    assert.deepEqual(e.ors.bodies[0], {
      coordinates: [[PARIS.lon, PARIS.lat], [ETOILE.lon, ETOILE.lat]],
      bearings: [[90, 45], [180, 45]],
      instructions: true,
      maneuvers: true,
      geometry_simplify: false,
      options: { avoid_features: ['tollways'] },
      alternative_routes: { target_count: 3, weight_factor: 1.6, share_factor: 0.6 },
    });
    const byValhalla = await e.drawer('valhalla')(ask);
    assert.equal(byValhalla.routes[0].engine, 'valhalla');
    assert.deepEqual(e.valhalla.calls[0].options, { avoid: ['tolls'], polygons: null, bearings: [[90, 45], [180, 45]], alternates: 3 });
    const around = await e.drawer('valhalla')({ ...ask, label: 'around', polygons: JAMS, alternatives: false });
    assert.equal(around.error, null);
    assert.deepEqual(e.valhalla.calls[1].options, { avoid: ['tolls'], polygons: JAMS, bearings: [[90, 45], [180, 45]], alternates: 0 });
    // Down: an outage, said so; ORS in shadow kept off the fallback's budget.
    const warn = console.warn;
    console.warn = () => {};
    try {
      const f = engine({ valhalla: failing(VALHALLA_ERROR.TIMEOUT), ors: fakeOrs({ budgetLeft: 0 }) });
      const down = await f.drawer('valhalla')(ask);
      assert.deepEqual([down.routes, down.error, down.outage], [[], 'timeout', true]);
      const kept = await f.drawer('ors', { shadow: true })(ask);
      assert.deepEqual([kept.error, kept.skipped, f.ors.bodies.length], ['budget_reserved', true, 0]);
      assert.equal((await f.drawer('ors')(ask)).routes.length, 1);
    } finally {
      console.warn = warn;
    }
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
