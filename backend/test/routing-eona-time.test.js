import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { db } from '../src/db.js';
import { createRoutingEngine } from '../src/routing/engine.js';
import { applyFallbackTime } from '../src/routing/eona-time.js';
import { benchRuns } from '../src/routing/bench.js';
import { logRoute, routeFacts } from '../src/routing/log.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { routeLine } from '../src/routing/shadow.js';
import { checkFaster } from '../src/routing/faster.js';
import { appRoute, call, deferred, fakeOrs, fakeValhalla, line, orsAnswer, orsFeature, PARIS, ETOILE, trapNetwork } from './helpers/routing.js';

const net = trapNetwork(before, after);
const AT = Date.parse('2026-10-18T12:00:00Z');
const shape = line(PARIS, ETOILE);
const attributes = (coordinates) => ({ coordinates, edges: [{ length: 5, speed: 50, road_class: 'primary',
  use: 'road', begin_shape_index: 0, end_shape_index: coordinates.length - 1, end_node: { traffic_signal: true } }] });
const setup = ({ mode = 'all', trace, ors, routes } = {}) => {
  const valhalla = fakeValhalla({ routes });
  const traces = [];
  valhalla.traceAttributes = async (points, options) => {
    traces.push({ points, options });
    return trace ? trace(points, options) : attributes(points.map((p) => [p.lon, p.lat]));
  };
  const routing = createRoutingEngine({ ors: ors ?? fakeOrs(), valhalla, mode: () => mode, now: () => AT });
  return { routing, valhalla, traces };
};
const sum = (route) => route.steps.reduce((n, s) => n + s.durationS, 0);

const servers = [];
after(() => Promise.all(servers.map((server) => new Promise((resolve) => server.close(resolve)))));

describe('Lot 4 : façade et contrat JSON', () => {
  it('applique les arêtes après normalisation Valhalla et ORS ; tentatives brutes intactes', async () => {
    for (const mode of ['all', 'ors']) {
      const { routing, traces } = setup({ mode });
      const result = await routing.route(PARIS, ETOILE, [], { plan: routing.plan(null) });
      const raw = result.attempts[result.engine].route;
      assert.equal(raw.durationS, 600);
      assert.equal('engineDurationS' in raw, false);
      assert.equal(result.route.engineDurationS, 600);
      assert.equal(result.route.durationS, 702);
      assert.equal(sum(result.route), 702);
      assert.deepEqual(result.route.eonaTime, { version: 1, mode: 'edges', signals: 1 });
      for (const key of Object.keys(raw)) {
        if (!['durationS', 'steps'].includes(key)) assert.deepEqual(result.route[key], raw[key]);
      }
      assert.equal(traces[0].options.shapeMatch, mode === 'all' ? 'edge_walk' : 'map_snap');
      assert.deepEqual(traces[0].points[0], { lat: PARIS.lat, lon: PARIS.lon });
      assert.equal(routing.health().eonaTime.edges, 1);
      assert.equal(routing.health().eonaTime.fallback, 0);
      assert.equal(routing.health().eonaTime.failed, 0);
    }
  });

  it('voiture, taxi et 50cc utilisent le même modèle et leur costing', async () => {
    for (const [vehicle, moped, costing] of [['car', false, 'auto'], ['taxi', false, 'taxi'], ['moped', true, 'motor_scooter']]) {
      const { routing, traces } = setup();
      const result = await routing.route(PARIS, ETOILE, [], { plan: routing.plan(null), vehicle, moped });
      assert.equal(result.route.eonaTime.mode, 'edges');
      assert.equal(traces[0].options.costing, costing);
    }
  });

  it('ajuste toutes les alternatives des deux moteurs ; ombre conservée brute', async () => {
    for (const mode of ['all', 'ors']) {
      const { routing, traces } = setup({ mode,
        routes: () => [appRoute('valhalla', shape), appRoute('valhalla', shape, { durationS: 700 })],
        ors: fakeOrs({ respond: () => orsAnswer([orsFeature(shape), orsFeature(shape, { duration: 700 })]) }),
      });
      const engine = routing.plan(null).primary;
      const ask = { from: PARIS, to: ETOILE, alternatives: true };
      const result = await routing.drawer(engine)(ask);
      assert.deepEqual(result.routes.map((r) => r.durationS), [702, 802]);
      assert.deepEqual(result.routes.map((r) => r.engineDurationS), [600, 700]);
      assert.ok(result.routes.every((r) => sum(r) === r.durationS));
      const shadow = await routing.drawer(engine, { shadow: true })(ask);
      assert.deepEqual(shadow.routes.map((r) => r.durationS), [600, 700]);
      assert.equal(traces.length, 2);
      const comparison = await routing.shadowRoute(engine, PARIS, ETOILE);
      assert.equal(comparison.route.durationS, 600);
      assert.equal('eonaTime' in comparison.route, false);
    }
  });

  it('échec de trace : route servie en repli, sans déclencher secours moteur', async () => {
    const { routing } = setup({ trace: async () => { throw Error('trace coupée'); } });
    const result = await routing.route(PARIS, ETOILE, [], { plan: routing.plan(null) });
    assert.equal(result.engine, 'valhalla');
    assert.equal(result.fallback, false);
    assert.equal(result.route.durationS, 720);
    assert.equal(result.route.engineDurationS, 600);
    assert.equal(sum(result.route), 720);
    assert.deepEqual(routing.health().eonaTime, { edges: 0, fallback: 1, failed: 1, lastMs: routing.health().eonaTime.lastMs });
  });

  it('trace muette : repli à 1 500 ms au maximum du délai configuré', async (t) => {
    const { routing } = setup({ trace: () => new Promise(() => {}) });
    const result = await routing.route(PARIS, ETOILE, [], { plan: routing.plan(null) });
    assert.equal(result.route.eonaTime.mode, 'fallback');
    t.diagnostic('Repli trace muette : ' + routing.health().eonaTime.lastMs + ' ms');
    assert.ok(routing.health().eonaTime.lastMs >= 1490);
    assert.ok(routing.health().eonaTime.lastMs < 2500);
  });

  it('HTTP : HERE Import démarre pendant la trace ; null ne relance aucun Import', async () => {
    const gate = deferred(), started = deferred();
    let calls = 0;
    const { routing } = setup({ trace: async (points) => {
      started.resolve(); await gate.promise;
      return attributes(points.map((p) => [p.lon, p.lat]));
    } });
    const logs = [];
    const app = express();
    app.use('/api/route', createRouteRouter({ routing,
      shadow: { afterRoute: () => {} }, auth: () => ({ id: 'test', role: 'client' }),
      accounts: { accessFor: () => ({ canNavigate: true }), tripCheck: () => ({ allowed: true, isNew: false }) },
      guard: { cachedRoute: () => null, keepRoute: () => {}, spendRoute: () => ({ ok: true }) },
      log: (entry) => logs.push(entry), travel: async () => { calls++; return null; },
    }));
    const server = app.listen(0, '127.0.0.1'); servers.push(server);
    await once(server, 'listening');
    const answer = call(server, 'GET', '/api/route?from=48.856614,2.352222&to=48.873792,2.295028&timed=1');
    await started.promise;
    await new Promise(setImmediate);
    try { assert.equal(calls, 1); } finally { gate.resolve(); }
    const result = await answer;
    assert.equal(result.status, 200);
    assert.equal(result.json.travelS, null);
    assert.equal(calls, 1);
    assert.equal(result.json.engineDurationS, 600);
    assert.equal(logs[0].durationS, 702);
    assert.equal(logs[0].engineDurationS, 600);
  });
});

describe('Lot 4 : consommateurs', () => {
  it('shadow compare les durées brutes même sur une route enrichie', () => {
    const a = { ...appRoute('ors', shape, { durationS: 1000 }), engineDurationS: 600 };
    const b = { ...appRoute('valhalla', shape, { durationS: 1000 }), engineDurationS: 800 };
    const result = routeLine({ mode: 'all', served: 'valhalla', attempts: {
      ors: { ok: true, route: a }, valhalla: { ok: true, route: b },
    }, durationRatio: 0.1, minShare: 0.7 });
    assert.equal(result.ors.durationS, 600);
    assert.equal(result.valhalla.durationS, 800);
    assert.equal(result.divergent, true);
  });

  it('log écrit EONA et brut ; bench restitue ses mesures brutes historiques', async () => {
    const query = db.query;
    const calls = [];
    db.query = async (sql, values) => {
      calls.push({ sql, values });
      return { rows: sql.includes('FROM routing.bench_run') ? [{ id: 1, at: AT,
        our_duration_s: 600, valhalla_latency_ms: 10, valhalla_duration_s: 700 }] : [] };
    };
    try {
      logRoute({ kind: 'route', ...routeFacts({ ...appRoute('valhalla', shape), durationS: 800, engineDurationS: 600 }) });
      const insert = calls.find((c) => c.sql.includes('INSERT'));
      assert.match(insert.sql, /engine_duration_s/);
      assert.equal(insert.values[7], 800);
      assert.equal(insert.values[14], 600);
      const runs = await benchRuns();
      assert.equal(runs[0].ourDurationS, 600);
      assert.equal(runs[0].valhalla.durationS, 700);
    } finally { db.query = query; }
  });

  it('tri variantes EONA ; baseline diagnostique brute', async () => {
    const a = { lat: 48, lon: 2 }, b = { lat: 48, lon: 2.2 };
    const points = line(a, b, 100).map(([lon, lat]) => [lat, lon]);
    let first = true;
    const { compare } = await checkFaster(points, { etaS: 100000,
      traffic: async () => first ? (first = false, { reliable: true, crowdS: 0, sections: [{ fromM: 5000, toM: 8000, delayS: 10000, level: 'heavy' }] })
        : { reliable: true, crowdS: 0, sections: [] },
      travel: async () => ({ travelS: 1500 }),
      draw: async (ask) => ({ engine: 'valhalla', routes: [
        { ...appRoute('valhalla', line(ask.from, ask.to, 100)), durationS: 2000, engineDurationS: 900 },
        { ...appRoute('valhalla', line(ask.from, ask.to, 100, 0.02)), durationS: 2500, engineDurationS: 800 },
        { ...appRoute('valhalla', line(ask.from, ask.to, 100, 0.02)), durationS: 2100, engineDurationS: 950 },
      ] }),
    });
    assert.equal(compare.drawn.baselineS, 900);
    assert.equal(compare.drawn.viable[0].route.durationS, 2100);
    assert.equal(compare.drawn.viable[0].route.engineDurationS, 950);
  });

  it('raccord détour et suite : métadonnées et somme des étapes conservées', async () => {
    const a = { lat: 48, lon: 2 }, b = { lat: 48, lon: 4 };
    const points = line(a, b, 300).map(([lon, lat]) => [lat, lon]);
    const result = await checkFaster(points, {
      traffic: async (p) => ({ reliable: true, coverageM: Infinity, crowdS: 0,
        sections: p === points ? [{ fromM: 5000, toM: 8000, delayS: 600, level: 'heavy' }] : [] }),
      travel: async (p) => ({ travelS: p.some((v) => v[0] > 48.00001) ? 1000 : 1500 }),
      draw: async (ask) => ({ engine: 'valhalla', routes: [applyFallbackTime(appRoute('valhalla',
        line(ask.from, ask.to, 80, ask.label === 'rest' ? 0 : 0.02), { durationS: 1001 }), AT)] }),
    });
    const route = result.answer.better.route;
    assert.equal(route.engineDurationS, 2002);
    assert.equal(route.eonaTime.mode, 'fallback');
    assert.equal(sum(route), route.durationS);
    assert.ok(route.steps.some((s) => s.type === 'continue' && s.durationS > 0));
  });

  it('aucun accès réseau externe', () => assert.equal(net.count(), 0));
});
