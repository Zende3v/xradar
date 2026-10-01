import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { createRoutingEngine } from '../src/routing/engine.js';
import { checkFaster as actualCheckFaster } from '../src/routing/faster.js';
import { cachedRoute, keepRoute } from '../src/routing/guard.js';
import { VALHALLA_ERROR, ValhallaError } from '../src/routing/providers/valhalla.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { createShadow, createShadowQueue } from '../src/routing/shadow.js';
import {
  appRoute, call, deferred, fakeOrs, fakeValhalla, line, orsAnswer, orsFeature, trapNetwork, wait,
} from './helpers/routing.js';

// Durées HERE indépendantes des moteurs : trajet 1500 s, détour 1000 s.
const checkFaster = (points, options) => actualCheckFaster(points, {
  travel: async line => ({ travelS: line.some(p=>p[0] > 48.00001) ? 1000 : 1500 }), ...options,
});

// /api/route and /api/route/faster over HTTP (127.0.0.1, an ephemeral port), with the real
// façade, shadow and cache over fake engines, a fake live traffic and fake accounts: no network, no
// database.
const net = trapNetwork(before, after);

const USERS = {
  admin: { id: 'admin-1', role: 'admin' },
  driver: { id: 'driver-1', role: 'client' },
  other: { id: 'driver-2', role: 'client' },
};
const ROUTE_KEYS = ['coordinates', 'distanceM', 'durationS', 'engine', 'mapVersion', 'steps'];
// Phase 1 keys, plus the motorway signs (exitNumber, towardRefs, toward), additive since 28/09.
const STEP_KEYS = ['distanceM', 'durationS', 'exit', 'exitNumber', 'location', 'modifier', 'name', 'toward', 'towardRefs', 'type'];

const servers = [];
after(() => Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve)))));

async function setup({ mode = 'ors', ors = fakeOrs(), valhalla = fakeValhalla(), faster, traffic } = {}) {
  let current = mode;
  const routing = createRoutingEngine({
    ors,
    valhalla,
    mode: () => current,
    settings: { deadlineMs: 2000, valhallaTimeoutMs: 500, breakerFailures: 3, breakerOpenMs: 60_000, shadowOrsMinBudgetLeft: 100 },
  });
  const queue = createShadowQueue({ maxQueue: 20, concurrency: 1 });
  const lines = [];
  const traces = [];
  const events = [];
  const store = {
    recordRun: async (line) => { lines.push(line); return lines.length; },
    recordTrace: async (trace) => { traces.push(trace); },
  };
  const shadow = createShadow({ routing, queue, store });
  const logs = [];
  const router = createRouteRouter({
    routing,
    shadow,
    auth: (req) => USERS[req.get('x-test-account')] ?? null,
    accounts: { accessFor: () => ({ canNavigate: true }), tripCheck: () => ({ allowed: true, isNew: false }), countTrip: () => {} },
    guard: { cachedRoute, keepRoute, spendRoute: () => ({ ok: true }) },
    log: (entry) => { events.push('answered'); logs.push(entry); },
    faster: faster ?? ((points, options) => checkFaster(points, { ...options, traffic })),
    liveReady: () => true,
    accountAllows: () => true,
  });
  const app = express();
  app.use(express.json({ limit: '3mb' }));
  app.use('/api/route', router);
  const server = app.listen(0, '127.0.0.1');
  servers.push(server);
  await once(server, 'listening');
  return { server, routing, queue, lines, traces, logs, events, ors, valhalla, setMode: (m) => { current = m; } };
}

/** A trip of its own for each test: the cache is shared by the whole file. */
let tripNo = 0;
function trip() {
  tripNo += 1;
  const from = { lat: 45 + tripNo * 0.01, lon: 4 };
  const to = { lat: 45 + tripNo * 0.01, lon: 4.05 };
  return { from, to, path: `/api/route?from=${from.lat},${from.lon}&to=${to.lat},${to.lon}` };
}
const as = (who) => ({ headers: { 'x-test-account': who } });

describe('/api/route', () => {
  it('mode ors: the same JSON as before phase 2, from ORS; Valhalla only compared afterwards', async () => {
    const ctx = await setup();
    const { path } = trip();
    const r = await call(ctx.server, 'GET', path, as('driver'));
    assert.equal(r.status, 200);
    assert.deepEqual(Object.keys(r.json).sort(), ROUTE_KEYS);
    assert.deepEqual(Object.keys(r.json.steps[0]).sort(), STEP_KEYS);
    assert.equal(r.json.engine, 'ors');
    assert.equal(r.json.mapVersion, '2026-09-20T00:00:00Z');
    assert.equal(typeof r.json.distanceM, 'number');
    await ctx.queue.idle();
    assert.equal(ctx.valhalla.calls.length, 1);
    assert.equal(ctx.lines.length, 1);
    assert.equal(ctx.lines[0].served, 'ors');
    assert.equal(ctx.lines[0].valhalla.ok, true);
    assert.equal(ctx.logs[0].engine, 'ors');
    assert.equal(ctx.logs[0].cached, false);
  });

  it('answers before the shadow: the other engine starts after the answer and never holds it', async () => {
    const gate = deferred();
    const ctx = await setup({
      valhalla: fakeValhalla({
        routes: async (from, to) => {
          ctx.events.push('shadow');
          await gate.promise;
          return [appRoute('valhalla', line(from, to))];
        },
      }),
    });
    const r = await call(ctx.server, 'GET', trip().path, as('driver'));
    // The answer is in while the shadow is still blocked.
    assert.equal(r.status, 200);
    assert.equal(ctx.lines.length, 0);
    await wait(20);
    assert.deepEqual(ctx.events, ['answered', 'shadow']);
    gate.resolve();
    await ctx.queue.idle();
    assert.equal(ctx.lines.length, 1);
  });

  it('keeps the cache apart per engine: an admin\'s Valhalla route never reaches a driver on ORS, nor outlives a switch', async () => {
    const ctx = await setup({ mode: 'admins' });
    const { path } = trip();
    const a = await call(ctx.server, 'GET', path, as('admin'));
    assert.equal(a.json.engine, 'valhalla');
    assert.deepEqual(Object.keys(a.json).sort(), ROUTE_KEYS);
    const d = await call(ctx.server, 'GET', path, as('driver'));
    assert.equal(d.json.engine, 'ors');
    assert.equal(ctx.logs.at(-1).cached, false);
    const again = await call(ctx.server, 'GET', path, as('admin'));
    assert.equal(again.json.engine, 'valhalla');
    assert.equal(ctx.logs.at(-1).cached, true);
    ctx.setMode('ors');
    const back = await call(ctx.server, 'GET', path, as('admin'));
    assert.equal(back.json.engine, 'ors');
    assert.equal(ctx.logs.at(-1).cached, true); // the driver's ORS answer, a minute old at most
    await ctx.queue.idle();
  });

  it('falls back to ORS when Valhalla fails, compares without asking again, and answers 502 when both fail', async () => {
    const down = fakeValhalla({ routes: () => { throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, 'Valhalla: unreachable'); } });
    const ctx = await setup({ mode: 'all', valhalla: down });
    const r = await call(ctx.server, 'GET', trip().path, as('driver'));
    assert.equal(r.status, 200);
    assert.equal(r.json.engine, 'ors');
    await ctx.queue.idle();
    assert.equal(down.calls.length, 1);
    assert.equal(ctx.lines[0].fallback, true);
    assert.equal(ctx.lines[0].cause, 'unavailable');
    const both = await setup({ mode: 'all', valhalla: down, ors: fakeOrs({ respond: () => { throw new Error('fetch failed'); } }) });
    const warn = console.warn;
    console.warn = () => {};
    try {
      const f = await call(both.server, 'GET', trip().path, as('driver'));
      assert.equal(f.status, 502);
      assert.deepEqual(f.json, { error: 'routing unavailable', detail: 'fetch failed' });
    } finally {
      console.warn = warn;
    }
    await both.queue.idle();
  });

  it('computes nothing in shadow for a cached answer, nor without Valhalla', async () => {
    const ctx = await setup();
    const { path } = trip();
    await call(ctx.server, 'GET', path, as('driver'));
    await call(ctx.server, 'GET', path, as('other'));
    await ctx.queue.idle();
    assert.equal(ctx.lines.length, 1);
    const off = await setup({ valhalla: null, mode: 'all' });
    const r = await call(off.server, 'GET', trip().path, as('admin'));
    assert.equal(r.json.engine, 'ors');
    await off.queue.idle();
    assert.equal(off.lines.length, 0);
    assert.equal(off.queue.stats().done, 0);
  });
});

describe('/api/route/faster', () => {
  const A = { lat: 48.0, lon: 2.0 };
  const B = { lat: 48.0, lon: 2.2 };
  const ROUTE = line(A, B, 150);
  const DETOUR = line(A, B, 150, 0.02);
  const body = { coordinates: ROUTE, avoid: ['tolls'] };

  /** The live traffic: a 10 min jam from 5 to 8 km on the route, nothing on any variant. */
  function live() {
    const calls = [];
    const traffic = async (points) => {
      calls.push(points.length);
      if (calls.length === 1) return { reliable: true, delayS: 600, crowdS: 0, sections: [{ fromM: 5000, toM: 8000, delayS: 600, level: 'heavy' }] };
      return { reliable: true, delayS: 0, crowdS: 0, sections: [] };
    };
    return { traffic, calls };
  }
  const orsDrawing = () => fakeOrs({
    respond: (b) => (b.alternative_routes
      ? orsAnswer([orsFeature(ROUTE, { duration: 900 }), orsFeature(DETOUR, { duration: 1000 })])
      : orsAnswer([orsFeature(DETOUR, { duration: 1000 })])),
  });
  const valhallaDrawing = () => fakeValhalla({
    routes: (from, to, options) => (options.alternates
      ? [appRoute('valhalla', ROUTE, { durationS: 900 }), appRoute('valhalla', DETOUR, { durationS: 1000 })]
      : [appRoute('valhalla', DETOUR, { durationS: 1000 })]),
  });

  it('mode ors: ORS draws as in phase 1; Valhalla draws the same in shadow, without live traffic', async () => {
    const t = live();
    const ctx = await setup({ ors: orsDrawing(), valhalla: valhallaDrawing(), traffic: t.traffic });
    const r = await call(ctx.server, 'POST', '/api/route/faster', { ...as('driver'), body });
    assert.equal(r.status, 200);
    assert.equal(r.json.better.route.engine, 'ors');
    assert.equal(r.json.better.gainS, 500);
    assert.deepEqual(Object.keys(r.json.better.route).sort(), ROUTE_KEYS);
    assert.equal(ctx.ors.bodies.length, 2);
    assert.deepEqual(ctx.ors.bodies[0].options.avoid_features, ['tollways']);
    assert.equal(t.calls.length, 2);
    await ctx.queue.idle();
    assert.equal(t.calls.length, 2); // no live traffic for the shadow
    assert.deepEqual(ctx.valhalla.calls.map((c) => c.options.alternates), [0, 3]);
    assert.equal(ctx.lines.length, 1);
    const [measure] = ctx.lines;
    assert.equal(measure.kind, 'faster');
    assert.equal(measure.served, 'ors');
    assert.equal(measure.detail.switched, true);
    assert.equal(measure.detail.detourShare, 1);
    assert.deepEqual(measure.detail.valhalla, { routes: 3, candidates: 1, viable: 1, errors: [] });
    assert.equal(measure.divergent, false);
  });

  it('mode all: Valhalla draws, no ORS key needed; "fallback" when Valhalla fails or served the route through ORS', async () => {
    const t = live();
    const ctx = await setup({ mode: 'all', ors: fakeOrs({ configured: false }), valhalla: valhallaDrawing(), traffic: t.traffic });
    const r = await call(ctx.server, 'POST', '/api/route/faster', { ...as('admin'), body });
    assert.equal(r.status, 200);
    assert.equal(r.json.better.route.engine, 'valhalla');
    await ctx.queue.idle();
    assert.equal(ctx.lines[0].ors.error, 'not_configured');

    // A driver whose route ORS served for a failing Valhalla gets no detour, and the live traffic is not asked.
    let fail = true;
    const flaky = fakeValhalla({
      routes: (from, to) => {
        if (fail) throw new ValhallaError(VALHALLA_ERROR.OUT_OF_COVERAGE, 'x');
        return [appRoute('valhalla', line(from, to))];
      },
    });
    const t2 = live();
    const ctx2 = await setup({ mode: 'all', valhalla: flaky, traffic: t2.traffic });
    const route = await call(ctx2.server, 'GET', trip().path, as('driver'));
    assert.equal(route.json.engine, 'ors');
    fail = false;
    const f = await call(ctx2.server, 'POST', '/api/route/faster', { ...as('driver'), body });
    assert.deepEqual(f.json, { better: null, reason: 'fallback' });
    assert.equal(ctx2.logs.at(-1).error, 'fallback');
    assert.equal(t2.calls.length, 0);
    // Another driver, Valhalla fine: the check goes on.
    const other = await call(ctx2.server, 'POST', '/api/route/faster', { ...as('other'), body });
    assert.notEqual(other.json.reason, 'fallback');
    await ctx2.queue.idle();

    // Valhalla down (its breaker open): "fallback" for everyone, before the live traffic.
    const t3 = live();
    const down = fakeValhalla({ routes: () => { throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, 'x'); } });
    const ctx3 = await setup({ mode: 'all', valhalla: down, traffic: t3.traffic });
    for (let i = 0; i < 3; i++) await call(ctx3.server, 'GET', trip().path, as('driver'));
    const blocked = await call(ctx3.server, 'POST', '/api/route/faster', { ...as('other'), body });
    assert.deepEqual(blocked.json, { better: null, reason: 'fallback' });
    assert.equal(t3.calls.length, 0);
    await ctx3.queue.idle();
  });

  it('refuses as before: 503 without live traffic, or without ORS when ORS draws; 400 on bad coordinates', async () => {
    const ctx = await setup({ ors: fakeOrs({ configured: false }), valhalla: null });
    const r = await call(ctx.server, 'POST', '/api/route/faster', { ...as('driver'), body });
    assert.equal(r.status, 503);
    assert.deepEqual(r.json, { error: 'rerouting unavailable' });
    const bad = await setup();
    const b = await call(bad.server, 'POST', '/api/route/faster', { ...as('driver'), body: { coordinates: [[1, 'x'], [2, 3]] } });
    assert.equal(b.status, 400);
    const nobody = await call(bad.server, 'POST', '/api/route/faster', { body });
    assert.equal(nobody.status, 401);
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
