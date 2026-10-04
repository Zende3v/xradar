import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { createRoutingEngine } from '../src/routing/engine.js';
import { checkFaster } from '../src/routing/faster.js';
import { cachedRoute, keepRoute } from '../src/routing/guard.js';
import { createValhallaClient } from '../src/routing/providers/valhalla.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { createShadow, createShadowQueue } from '../src/routing/shadow.js';
import { ETOILE, PARIS, appRoute, call, fakeOrs, fakeValhalla, line, trapNetwork, valhallaAnswer } from './helpers/routing.js';

// Scooter 50 et sans permis (04/10) : motor_scooter 45 km/h, autoroutes exclues, pas de HERE.
// Faux moteurs, aucun réseau, aucune base.
trapNetwork(before, after);

describe('profil 45 km/h', () => {
  it('Valhalla : motor_scooter, 45 km/h, autoroutes exclues, Éco possible', async () => {
    const sent = [];
    const valhalla = createValhallaClient({
      baseUrl: 'http://127.0.0.1:8002', timeoutMs: 500, searchCutoffM: 1000, maxSnapM: 350,
      fetchImpl: async (url, init) => {
        sent.push(JSON.parse(init.body));
        return { status: 200, text: async () => JSON.stringify(valhallaAnswer(line(PARIS, ETOILE))) };
      },
    });
    await valhalla.routes(PARIS, ETOILE, { moped: true, shortest: true, avoid: ['tolls'] });
    assert.equal(sent[0].costing, 'motor_scooter');
    assert.deepEqual(sent[0].costing_options, { motor_scooter: { top_speed: 45, exclude_tolls: true, exclude_highways: true, shortest: true } });
    await valhalla.routes(PARIS, ETOILE, {});
    assert.equal(sent[1].costing, 'auto');
    assert.equal(sent[1].costing_options.auto.top_speed, 130);
  });

  it('façade : moped jusqu\'à Valhalla, ORS en secours sans autoroute', async () => {
    const valhalla = fakeValhalla();
    const ors = fakeOrs();
    const routing = createRoutingEngine({ ors, valhalla, mode: () => 'all', settings: { deadlineMs: 2000, valhallaTimeoutMs: 500 } });
    await routing.route(PARIS, ETOILE, [], { plan: routing.plan({ id: 'd', role: 'client' }), moped: true });
    assert.equal(valhalla.calls[0].options.moped, true);
    assert.deepEqual(valhalla.calls[0].options.avoid, ['highways']);
    const onOrs = createRoutingEngine({ ors, valhalla, mode: () => 'ors', settings: { deadlineMs: 2000 } });
    await onOrs.route(PARIS, ETOILE, [], { plan: onOrs.plan(null), moped: true });
    assert.deepEqual(ors.bodies.at(-1).options.avoid_features, ['highways']);
  });
});

const servers = [];
after(() => Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve)))));

async function setup() {
  const valhalla = fakeValhalla();
  const ors = fakeOrs();
  const routing = createRoutingEngine({
    ors, valhalla, mode: () => 'all',
    settings: { deadlineMs: 2000, valhallaTimeoutMs: 500, breakerFailures: 3, breakerOpenMs: 60_000, shadowOrsMinBudgetLeft: 100 },
  });
  const queue = createShadowQueue({ maxQueue: 20, concurrency: 1 });
  const lines = [];
  const shadow = createShadow({ routing, queue, store: { recordRun: async (l) => { lines.push(l); return lines.length; }, recordTrace: async () => {} } });
  const fasterCalls = [];
  let timings = 0;
  const router = createRouteRouter({
    routing,
    shadow,
    auth: (req) => (req.get('x-test-account') ? { id: req.get('x-test-account'), role: 'client' } : null),
    accounts: { accessFor: () => ({ canNavigate: true }), tripCheck: () => ({ allowed: true, isNew: false }), countTrip: () => {} },
    guard: { cachedRoute, keepRoute, spendRoute: () => ({ ok: true }) },
    log: () => {},
    faster: async (points, options) => { fasterCalls.push(options); return { answer: { better: null, reason: 'no significant jam' }, compare: null }; },
    liveReady: () => true,
    accountAllows: () => true,
    travel: async () => { timings += 1; return { travelS: 900 }; },
  });
  const app = express();
  app.use(express.json({ limit: '3mb' }));
  app.use('/api/route', router);
  const server = app.listen(0, '127.0.0.1');
  servers.push(server);
  await once(server, 'listening');
  return { server, valhalla, queue, lines, fasterCalls, timings: () => timings };
}

const driver = (id) => ({ headers: { 'x-test-account': id } });

describe('/api/route en 45 km/h', () => {
  it('moped : Valhalla motor_scooter, cache à part, aucun HERE, aucune ombre', async () => {
    const ctx = await setup();
    const path = (extra) => `/api/route?from=46.1,4.1&to=46.1,4.15&timed=1${extra}`;
    const r = await call(ctx.server, 'GET', path('&vehicle=moped'), driver('m1'));
    assert.equal(r.status, 200);
    assert.equal(r.json.travelS, null);
    assert.equal(ctx.timings(), 0);
    assert.equal(ctx.valhalla.calls[0].options.moped, true);
    await ctx.queue.idle();
    assert.equal(ctx.lines.length, 0);
    // Même trajet en voiture : jamais la route 45 km/h du cache, HERE chronomètre.
    const car = await call(ctx.server, 'GET', path(''), driver('m1'));
    assert.equal(car.json.travelS, 900);
    assert.equal(ctx.valhalla.calls.length, 2);
    assert.equal('moped' in ctx.valhalla.calls[1].options, false);
    assert.equal((await call(ctx.server, 'GET', path('&vehicle=bus'), driver('m1'))).status, 400);
  });

  it('/faster moped : autoroutes exclues, véhicule transmis', async () => {
    const ctx = await setup();
    const coordinates = line({ lat: 47, lon: 5 }, { lat: 47, lon: 5.1 });
    const ok = await call(ctx.server, 'POST', '/api/route/faster', { ...driver('m2'), body: { coordinates, vehicle: 'moped', avoid: ['tolls'] } });
    assert.equal(ok.status, 200);
    assert.equal(ctx.fasterCalls[0].moped, true);
    assert.deepEqual(ctx.fasterCalls[0].avoid, ['tolls', 'highways']);
  });
});

describe('détour 45 km/h', () => {
  const A = { lat: 48.0, lon: 2.0 };
  const B = { lat: 48.0, lon: 2.2 };
  const POINTS = line(A, B, 150).map(([lon, lat]) => [lat, lon]);
  const jam = (closed) => async (points) => (points.length === POINTS.length
    ? { reliable: true, crowdS: 0, sections: [{ fromM: 2000, toM: 4000, level: closed ? 'closed' : 'jam', kind: closed ? 'closed' : 'jam', delayS: 600, source: 'here' }] }
    : { reliable: true, crowdS: 0, sections: [] });

  it('bouchon : aucun détour ni temps HERE ; route fermée : détour motor_scooter', async () => {
    const asks = [];
    const draw = async (ask) => {
      asks.push(ask);
      const shape = line(ask.from, ask.to, 60, ask.label === 'rest' ? 0 : 0.01);
      return { engine: 'valhalla', routes: [appRoute('valhalla', shape, { durationS: 400 })], error: null, outage: false };
    };
    let timed = 0;
    const travel = async () => { timed += 1; return { travelS: 500 }; };
    const jammed = await checkFaster(POINTS, { draw, traffic: jam(false), travel, moped: true });
    assert.equal(jammed.answer.better, null);
    assert.equal(timed, 0);
    const closed = await checkFaster(POINTS, { draw, traffic: jam(true), travel, moped: true });
    assert.equal(timed, 0);
    assert.ok(asks.length > 0 && asks.every((ask) => ask.moped === true));
    assert.equal(closed.answer.better?.closed, true);
  });
});
