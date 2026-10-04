import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { createRoutingEngine } from '../src/routing/engine.js';
import { checkFaster } from '../src/routing/faster.js';
import { cachedRoute, keepRoute } from '../src/routing/guard.js';
import { normalizeOrsFeature, orsBody } from '../src/routing/providers/ors.js';
import { MAX_STOPS, VALHALLA_ERROR, createValhallaClient } from '../src/routing/providers/valhalla.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { createShadow, createShadowQueue } from '../src/routing/shadow.js';
import { ETOILE, PARIS, appRoute, call, fakeOrs, fakeValhalla, line, trapNetwork, valhallaAnswer } from './helpers/routing.js';

// Multi-arrêts (04/10) : étapes dans l'ordre, une seule route, aucune arrivée intermédiaire.
// Faux moteurs, aucun réseau, aucune base.
const net = trapNetwork(before, after);

const STOP = { lat: 48.865, lon: 2.325 };

function client(answer) {
  const sent = [];
  const valhalla = createValhallaClient({
    baseUrl: 'http://127.0.0.1:8002', timeoutMs: 500, searchCutoffM: 1000, maxSnapM: 350,
    fetchImpl: async (url, init) => {
      sent.push(JSON.parse(init.body));
      return { status: 200, text: async () => JSON.stringify(answer()) };
    },
  });
  return { valhalla, sent };
}

describe('étapes dans les moteurs', () => {
  it('Valhalla : étapes `via` entre départ et arrivée, contrôlées si listées', async () => {
    const shape = line(PARIS, ETOILE);
    const { valhalla, sent } = client(() => valhallaAnswer(shape));
    await valhalla.routes(PARIS, ETOILE, { via: [STOP] });
    assert.equal(sent[0].locations.length, 3);
    assert.deepEqual(sent[0].locations[1], { lat: STOP.lat, lon: STOP.lon, search_cutoff: 1000, type: 'via' });
    assert.equal('type' in sent[0].locations[0], false);
    // Étape listée par le serveur : trop loin, refusée comme un bout.
    const listed = (stopAt) => () => {
      const answer = valhallaAnswer(shape);
      answer.waypoints = [answer.waypoints[0], { location: stopAt, distance: 1 }, answer.waypoints[1]];
      return answer;
    };
    await client(listed([STOP.lon, STOP.lat])).valhalla.routes(PARIS, ETOILE, { via: [STOP] });
    await assert.rejects(
      client(listed([STOP.lon + 0.05, STOP.lat])).valhalla.routes(PARIS, ETOILE, { via: [STOP] }),
      (e) => e.code === VALHALLA_ERROR.OUT_OF_COVERAGE && e.point === 'via',
    );
    const many = Array.from({ length: MAX_STOPS + 1 }, () => STOP);
    await assert.rejects(valhalla.routes(PARIS, ETOILE, { via: many }), (e) => e.code === VALHALLA_ERROR.INVALID_REQUEST);
    await assert.rejects(valhalla.routes(PARIS, ETOILE, { via: [STOP], alternates: 2 }), (e) => e.code === VALHALLA_ERROR.INVALID_REQUEST);
  });

  it('ORS : étapes dans les coordonnées, arrivée et départ intermédiaires retirés', () => {
    assert.deepEqual(orsBody({ from: PARIS, to: ETOILE, via: [STOP] }).coordinates, [[PARIS.lon, PARIS.lat], [STOP.lon, STOP.lat], [ETOILE.lon, ETOILE.lat]]);
    assert.equal(orsBody({ from: PARIS, to: ETOILE }).coordinates.length, 2);
    const coordinates = line(PARIS, ETOILE);
    const feature = {
      type: 'Feature',
      geometry: { type: 'LineString', coordinates },
      properties: {
        summary: { distance: 5000, duration: 600 },
        segments: [
          { steps: [{ type: 11, way_points: [0, 0], distance: 2000 }, { type: 1, way_points: [3, 3], distance: 500 }, { type: 10, way_points: [5, 5], distance: 0 }] },
          { steps: [{ type: 11, way_points: [5, 5], distance: 2500 }, { type: 10, way_points: [11, 11], distance: 0 }] },
        ],
      },
    };
    const route = normalizeOrsFeature(feature);
    assert.deepEqual(route.steps.map((step) => step.type), ['depart', 'turn', 'arrive']);
  });

  it('façade : étapes passées à Valhalla, à ORS en secours, au reste d\'un détour', async () => {
    const valhalla = fakeValhalla();
    const ors = fakeOrs();
    const routing = createRoutingEngine({ ors, valhalla, mode: () => 'all', settings: { deadlineMs: 2000, valhallaTimeoutMs: 500 } });
    await routing.route(PARIS, ETOILE, [], { plan: routing.plan({ id: 'd', role: 'client' }), via: [STOP] });
    assert.deepEqual(valhalla.calls[0].options.via, [STOP]);
    await routing.route(PARIS, ETOILE, [], { plan: routing.plan({ id: 'd', role: 'client' }) });
    assert.equal('via' in valhalla.calls[1].options, false);
    const onOrs = createRoutingEngine({ ors, valhalla, mode: () => 'ors', settings: { deadlineMs: 2000 } });
    await onOrs.route(PARIS, ETOILE, [], { plan: onOrs.plan(null), via: [STOP] });
    assert.equal(ors.bodies.at(-1).coordinates.length, 3);
    await routing.drawer('valhalla')({ label: 'rest', from: PARIS, to: ETOILE, bearings: null, avoid: [], polygons: null, alternatives: false, via: [STOP] });
    assert.deepEqual(valhalla.calls.at(-1).options.via, [STOP]);
  });
});

// ---- /api/route ---------------------------------------------------------------

const servers = [];
after(() => Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve)))));

async function setup({ faster } = {}) {
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
    travel: async () => null,
  });
  const app = express();
  app.use(express.json({ limit: '3mb' }));
  app.use('/api/route', router);
  const server = app.listen(0, '127.0.0.1');
  servers.push(server);
  await once(server, 'listening');
  return { server, valhalla, ors, queue, lines, logs, fasterCalls };
}

let tripNo = 0;
function trip() {
  tripNo += 1;
  const from = `${47 + tripNo * 0.01},5`;
  const to = `${47 + tripNo * 0.01},5.05`;
  return (extra = '') => `/api/route?from=${from}&to=${to}${extra}`;
}
const driver = (id = 'driver-stops') => ({ headers: { 'x-test-account': id } });

describe('/api/route avec étapes', () => {
  it('étapes dans l\'ordre, cache à part, aucune ombre, nombre journalisé', async () => {
    const ctx = await setup();
    const path = trip();
    const stops = '&via=47.5,5.01;47.6,5.02';
    const r = await call(ctx.server, 'GET', path(stops), driver());
    assert.equal(r.status, 200);
    assert.deepEqual(ctx.valhalla.calls[0].options.via, [{ lat: 47.5, lon: 5.01 }, { lat: 47.6, lon: 5.02 }]);
    await ctx.queue.idle();
    assert.equal(ctx.lines.length, 0);
    assert.equal(ctx.logs[0].stops, 2);
    // Même trajet sans étape : jamais la route avec étapes du cache.
    await call(ctx.server, 'GET', path(''), driver());
    assert.equal(ctx.valhalla.calls.length, 2);
    assert.equal('via' in ctx.valhalla.calls[1].options, false);
    // Autre ordre : autre route.
    await call(ctx.server, 'GET', path('&via=47.6,5.02;47.5,5.01'), driver());
    assert.equal(ctx.valhalla.calls.length, 3);
    // Même ordre : cache.
    await call(ctx.server, 'GET', path(stops), driver());
    assert.equal(ctx.valhalla.calls.length, 3);
  });

  it('refuse étapes illisibles ou trop nombreuses, avant tout calcul', async () => {
    const ctx = await setup();
    assert.equal((await call(ctx.server, 'GET', trip()('&via=47.5'), driver())).status, 400);
    const many = Array.from({ length: MAX_STOPS + 1 }, () => '47.5,5.01').join(';');
    assert.equal((await call(ctx.server, 'GET', trip()(`&via=${many}`), driver())).status, 400);
    assert.equal(ctx.valhalla.calls.length, 0);
  });

  it('/faster reçoit les étapes restantes', async () => {
    const ctx = await setup();
    const coordinates = line({ lat: 47, lon: 5 }, { lat: 47, lon: 5.1 });
    const ok = await call(ctx.server, 'POST', '/api/route/faster', { ...driver('f1'), body: { coordinates, via: [[5.05, 47]] } });
    assert.equal(ok.status, 200);
    assert.deepEqual(ctx.fasterCalls[0].via, [{ lat: 47, lon: 5.05 }]);
    const bad = await call(ctx.server, 'POST', '/api/route/faster', { ...driver('f2'), body: { coordinates, via: [['x', 47]] } });
    assert.equal(bad.status, 400);
  });
});

// ---- Détour avec étapes -------------------------------------------------------------

const A = { lat: 48.0, lon: 2.0 };
const B = { lat: 48.0, lon: 2.2 };
const ROUTE = line(A, B, 150);
const POINTS = ROUTE.map(([lon, lat]) => [lat, lon]);
/** Une étape à ~11 km, sur le trajet. */
const ON_ROUTE = { lat: 48.0, lon: 2.15 };

function engine() {
  const asks = [];
  const draw = async (ask) => {
    asks.push(ask);
    const shape = line(ask.from, ask.to, 60, ask.label === 'rest' ? 0 : 0.01);
    return { engine: 'valhalla', routes: [appRoute('valhalla', shape, { durationS: 400 })], error: null, outage: false };
  };
  return { draw, asks };
}

describe('détour avec étapes', () => {
  const jam = (fromM, toM) => async (points) => (points.length === POINTS.length
    ? { reliable: true, delayS: 600, crowdS: 0, coverageM: 20_000, sections: [{ fromM, toM, delayS: 600, level: 'heavy' }] }
    : { reliable: true, delayS: 0, crowdS: 0, sections: [] });

  it('bouchon avant la première étape : détour fini à l\'étape, reste par les suivantes', async () => {
    const e = engine();
    const later = { lat: 48.0, lon: 2.18 };
    const { answer } = await checkFaster(POINTS, {
      draw: e.draw,
      via: [ON_ROUTE, later],
      traffic: jam(4000, 6000),
      travel: async (points) => ({ travelS: points.some((p) => p[0] > 48.00001) ? 500 : 1500 }),
    });
    assert.ok(answer.better, answer.reason);
    const around = e.asks.find((ask) => ask.label === 'around');
    assert.ok(Math.abs(around.to.lon - ON_ROUTE.lon) < 0.001);
    const rest = e.asks.find((ask) => ask.label === 'rest');
    assert.deepEqual(rest.via, [later]);
  });

  it('bouchon après la première étape : aucun détour avant elle', async () => {
    const e = engine();
    const { answer } = await checkFaster(POINTS, { draw: e.draw, via: [ON_ROUTE], traffic: jam(12_000, 13_000) });
    assert.equal(answer.better, null);
    assert.equal(answer.reason, 'no significant jam');
    assert.equal(e.asks.length, 0);
  });

  it('étape hors du trajet : aucun détour, aucun appel trafic', async () => {
    let calls = 0;
    const { answer } = await checkFaster(POINTS, {
      draw: engine().draw,
      via: [{ lat: 48.2, lon: 2.1 }],
      traffic: async () => { calls += 1; return { reliable: true, sections: [] }; },
    });
    assert.equal(answer.reason, 'stop off route');
    assert.equal(calls, 0);
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
