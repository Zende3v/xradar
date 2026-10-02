import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { config } from '../src/config.js';
import { createRoutingEngine } from '../src/routing/engine.js';
import { checkFaster } from '../src/routing/faster.js';
import { cachedRoute, keepRoute } from '../src/routing/guard.js';
import { VALHALLA_ERROR, createValhallaClient } from '../src/routing/providers/valhalla.js';
import { orsBody } from '../src/routing/providers/ors.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { createShadow, createShadowQueue } from '../src/routing/shadow.js';
import { ETOILE, PARIS, appRoute, call, fakeOrs, fakeValhalla, line, trapNetwork, valhallaAnswer } from './helpers/routing.js';

// Choix d'itinéraire (02/10) : Éco = plus court en distance, sur les deux moteurs, sans casser
// les anciennes apps. Faux moteurs, aucun réseau, aucune base.
const net = trapNetwork(before, after);

const ROUTE_KEYS = ['coordinates', 'distanceM', 'durationS', 'engine', 'mapVersion', 'steps'];

describe('Éco dans les moteurs', () => {
  it('Valhalla : option auto shortest, exclusions gardées ; refuse valeur non booléenne', async () => {
    const sent = [];
    const valhalla = createValhallaClient({
      baseUrl: 'http://127.0.0.1:8002', timeoutMs: 500, searchCutoffM: 1000, maxSnapM: 350,
      fetchImpl: async (url, init) => {
        sent.push(JSON.parse(init.body));
        return { status: 200, text: async () => JSON.stringify(valhallaAnswer(line(PARIS, ETOILE))) };
      },
    });
    await valhalla.routes(PARIS, ETOILE, { avoid: ['tolls'], shortest: true });
    await valhalla.routes(PARIS, ETOILE, { avoid: ['tolls'] });
    assert.deepEqual(sent[0].costing_options.auto, { top_speed: 130, exclude_tolls: true, shortest: true });
    assert.deepEqual(sent[1].costing_options.auto, { top_speed: 130, exclude_tolls: true });
    await assert.rejects(valhalla.routes(PARIS, ETOILE, { shortest: 'yes' }), (e) => e.code === VALHALLA_ERROR.INVALID_REQUEST);
    assert.equal(sent.length, 2);
  });

  it('ORS : preference shortest seulement pour Éco, corps inchangé sinon', () => {
    assert.equal(orsBody({ from: PARIS, to: ETOILE, preference: 'shortest' }).preference, 'shortest');
    assert.equal('preference' in orsBody({ from: PARIS, to: ETOILE }), false);
    assert.equal('preference' in orsBody({ from: PARIS, to: ETOILE, preference: 'fastest' }), false);
  });

  it('façade : Éco sur Valhalla, sur ORS en secours, et dans les variantes de /faster', async () => {
    const valhalla = fakeValhalla();
    const ors = fakeOrs();
    const routing = createRoutingEngine({ ors, valhalla, mode: () => 'all', settings: { deadlineMs: 2000, valhallaTimeoutMs: 500 } });
    const plan = routing.plan({ id: 'd', role: 'client' });
    const eco = await routing.route(PARIS, ETOILE, ['tolls'], { plan, preference: 'shortest' });
    assert.equal(eco.engine, 'valhalla');
    assert.equal(valhalla.calls[0].options.shortest, true);
    await routing.route(PARIS, ETOILE, [], { plan });
    assert.equal('shortest' in valhalla.calls[1].options, false);
    const onOrs = createRoutingEngine({ ors, valhalla, mode: () => 'ors', settings: { deadlineMs: 2000 } });
    await onOrs.route(PARIS, ETOILE, [], { plan: onOrs.plan(null), preference: 'shortest' });
    assert.equal(ors.bodies.at(-1).preference, 'shortest');
    const ask = { label: 'around', from: PARIS, to: ETOILE, bearings: null, avoid: [], polygons: null, alternatives: false, preference: 'shortest' };
    await routing.drawer('valhalla')(ask);
    assert.equal(valhalla.calls.at(-1).options.shortest, true);
    await routing.drawer('ors')(ask);
    assert.equal(ors.bodies.at(-1).preference, 'shortest');
  });
});

// ---- /api/route ---------------------------------------------------------------

const servers = [];
after(() => Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve)))));

async function setup({ travel = async () => ({ travelS: 1800 }), faster } = {}) {
  const valhalla = fakeValhalla();
  const ors = fakeOrs();
  const routing = createRoutingEngine({
    ors, valhalla, mode: () => 'all',
    settings: { deadlineMs: 2000, valhallaTimeoutMs: 500, breakerFailures: 3, breakerOpenMs: 60_000, shadowOrsMinBudgetLeft: 100 },
  });
  const queue = createShadowQueue({ maxQueue: 20, concurrency: 1 });
  const lines = [];
  const shadow = createShadow({ routing, queue, store: { recordRun: async (l) => { lines.push(l); return lines.length; }, recordTrace: async () => {} } });
  const logs = [];
  const travelled = [];
  const fasterCalls = [];
  const router = createRouteRouter({
    routing,
    shadow,
    auth: (req) => (req.get('x-test-account') ? { id: req.get('x-test-account'), role: 'client' } : null),
    accounts: { accessFor: () => ({ canNavigate: true }), tripCheck: () => ({ allowed: true, isNew: false }), countTrip: () => {} },
    guard: { cachedRoute, keepRoute, spendRoute: () => ({ ok: true }) },
    log: (entry) => logs.push(entry),
    faster: faster ?? (async (points, options) => { fasterCalls.push(options); return { answer: { better: null, reason: 'no significant jam' }, compare: null }; }),
    liveReady: () => true,
    accountAllows: () => true,
    travel: async (points) => { travelled.push(points); return travel(points); },
  });
  const app = express();
  app.use(express.json({ limit: '3mb' }));
  app.use('/api/route', router);
  const server = app.listen(0, '127.0.0.1');
  servers.push(server);
  await once(server, 'listening');
  return { server, valhalla, ors, queue, lines, logs, travelled, fasterCalls };
}

let tripNo = 0;
function trip(query = '') {
  tripNo += 1;
  const from = `${46 + tripNo * 0.01},4`;
  const to = `${46 + tripNo * 0.01},4.05`;
  return (extra = query) => `/api/route?from=${from}&to=${to}${extra}`;
}
const driver = { headers: { 'x-test-account': 'driver-eco' } };

describe('/api/route avec choix d\'itinéraire', () => {
  it('Éco : route la plus courte, choix répété, aucune ombre ; ancien appel inchangé', async () => {
    const ctx = await setup();
    const path = trip();
    const eco = await call(ctx.server, 'GET', path('&preference=shortest'), driver);
    assert.equal(eco.status, 200);
    assert.deepEqual(Object.keys(eco.json).sort(), [...ROUTE_KEYS, 'preference'].sort());
    assert.equal(eco.json.preference, 'shortest');
    assert.equal(ctx.valhalla.calls[0].options.shortest, true);
    await ctx.queue.idle();
    assert.equal(ctx.lines.length, 0);
    assert.equal(ctx.ors.bodies.length, 0);
    assert.equal(ctx.logs[0].preference, 'shortest');
    // Même trajet sans choix : jamais servi par la route Éco en cache.
    const plain = await call(ctx.server, 'GET', path(''), driver);
    assert.deepEqual(Object.keys(plain.json).sort(), ROUTE_KEYS);
    assert.equal(ctx.valhalla.calls.length, 2);
    assert.equal('shortest' in ctx.valhalla.calls[1].options, false);
    assert.equal(ctx.logs[1].preference, 'fastest');
    // Éco redemandé : son cache à lui.
    const again = await call(ctx.server, 'GET', path('&preference=shortest'), driver);
    assert.equal(again.json.preference, 'shortest');
    assert.equal(ctx.logs[2].cached, true);
    assert.equal(ctx.valhalla.calls.length, 2);
  });

  it('timed=1 : temps HERE de la route entière, null si HERE muet ou trop lent', async () => {
    const ctx = await setup();
    const fast = await call(ctx.server, 'GET', trip()('&preference=fastest&timed=1'), driver);
    assert.equal(fast.json.preference, 'fastest');
    assert.equal(fast.json.travelS, 1800);
    // Points [lat, lon] de la route envoyée, tous.
    assert.equal(ctx.travelled[0].length, fast.json.coordinates.length);
    assert.deepEqual(ctx.travelled[0][0], [fast.json.coordinates[0][1], fast.json.coordinates[0][0]]);
    const mute = await setup({ travel: async () => { throw new Error('HERE budget unavailable'); } });
    assert.equal((await call(mute.server, 'GET', trip()('&timed=1'), driver)).json.travelS, null);
    const kept = config.routeTimingMs;
    config.routeTimingMs = 30;
    try {
      const slow = await setup({ travel: () => new Promise((resolve) => setTimeout(() => resolve({ travelS: 99 }), 300)) });
      const answer = await call(slow.server, 'GET', trip()('&timed=1'), driver);
      assert.equal(answer.status, 200);
      assert.equal(answer.json.travelS, null);
    } finally {
      config.routeTimingMs = kept;
    }
    // Sans timed : aucun appel HERE.
    await call(ctx.server, 'GET', trip()('&preference=shortest'), driver);
    assert.equal(ctx.travelled.length, 1);
  });

  it('refuse un choix inconnu, avant tout calcul', async () => {
    const ctx = await setup();
    const r = await call(ctx.server, 'GET', trip()('&preference=scenic'), driver);
    assert.equal(r.status, 400);
    assert.equal(ctx.valhalla.calls.length, 0);
  });

  it('/faster transmet Éco ; ancien corps : Rapide', async () => {
    const ctx = await setup();
    const coordinates = line({ lat: 46, lon: 4 }, { lat: 46, lon: 4.1 });
    const eco = await call(ctx.server, 'POST', '/api/route/faster', { ...driver, body: { coordinates, preference: 'shortest' } });
    assert.equal(eco.status, 200);
    assert.equal(ctx.fasterCalls[0].preference, 'shortest');
    const other = await setup();
    await call(other.server, 'POST', '/api/route/faster', { headers: { 'x-test-account': 'driver-eco-2' }, body: { coordinates } });
    assert.equal(other.fasterCalls[0].preference, 'fastest');
    const bad = await setup();
    const refused = await call(bad.server, 'POST', '/api/route/faster', { headers: { 'x-test-account': 'driver-eco-3' }, body: { coordinates, preference: 'scenic' } });
    assert.equal(refused.status, 400);
  });
});

// ---- Détour Éco -----------------------------------------------------------------

const A = { lat: 48.0, lon: 2.0 };
const B = { lat: 48.0, lon: 2.2 };
const ROUTE = line(A, B, 150);
const DETOUR = line(A, B, 150, 0.02);
const POINTS = ROUTE.map(([lon, lat]) => [lat, lon]);

function engine() {
  const asks = [];
  const draw = async (ask) => {
    asks.push(ask);
    if (ask.alternatives) {
      return { engine: 'valhalla', routes: [appRoute('valhalla', ROUTE, { durationS: 900 }), appRoute('valhalla', DETOUR, { durationS: 1000, distanceM: 16000 })], error: null, outage: false };
    }
    return { engine: 'valhalla', routes: [appRoute('valhalla', DETOUR, { durationS: 1000, distanceM: 16000 })], error: null, outage: false };
  };
  return { draw, asks };
}

describe('détour en Éco', () => {
  it('bouchon seul : aucun détour, aucun moteur, aucun chronométrage', async () => {
    const e = engine();
    let timed = 0;
    const { answer } = await checkFaster(POINTS, {
      draw: e.draw,
      preference: 'shortest',
      travel: async () => { timed += 1; return { travelS: 1 }; },
      traffic: async () => ({ reliable: true, delayS: 900, crowdS: 0, sections: [{ fromM: 5000, toM: 8000, delayS: 900, level: 'heavy' }] }),
    });
    assert.equal(answer.better, null);
    assert.equal(answer.reason, 'no significant jam');
    assert.equal(e.asks.length, 0);
    assert.equal(timed, 0);
  });

  it('route fermée : variante Éco ouverte la plus courte, sans HERE Route Import', async () => {
    const e = engine();
    let timed = 0;
    let liveCalls = 0;
    const { answer } = await checkFaster(POINTS, {
      draw: e.draw,
      preference: 'shortest',
      travel: async () => { timed += 1; return { travelS: 1 }; },
      traffic: async () => {
        liveCalls += 1;
        return liveCalls === 1
          ? { reliable: true, delayS: 0, crowdS: 0, coverageM: 20_000, sections: [{ fromM: 5000, toM: 8000, delayS: 0, level: 'closed' }] }
          : { reliable: true, delayS: 0, crowdS: 0, sections: [] };
      },
    });
    assert.equal(answer.better.closed, true);
    assert.equal(answer.better.gainS, 0);
    assert.equal(answer.better.route.distanceM, 16000);
    assert.equal(answer.currentS, null);
    assert.equal(timed, 0);
    assert.ok(e.asks.length > 0);
    assert.ok(e.asks.every((ask) => ask.preference === 'shortest'));
  });

  it('Rapide : variantes sans préférence, comme avant', async () => {
    const e = engine();
    await checkFaster(POINTS, {
      draw: e.draw,
      travel: async (points) => ({ travelS: points.some((p) => p[0] > 48.00001) ? 1000 : 1500 }),
      traffic: async () => ({ reliable: true, delayS: 600, crowdS: 0, sections: [{ fromM: 5000, toM: 8000, delayS: 600, level: 'heavy' }] }),
    });
    assert.ok(e.asks.length > 0);
    assert.ok(e.asks.every((ask) => !('preference' in ask)));
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
