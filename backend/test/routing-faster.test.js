import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { checkFaster, closestTo, drawVariants } from '../src/routing/faster.js';
import { appRoute, line, trapNetwork } from './helpers/routing.js';

// /api/route/faster's check (faster.js) with a fake TomTom ([time]) and fake engines ([draw]):
// the same check whatever the engine; the shadow's drawing never times anything.
const net = trapNetwork(before, after);

const A = { lat: 48.0, lon: 2.0 };
const B = { lat: 48.0, lon: 2.2 };
/** The route followed, ~15 km due east, [lon, lat]; a detour bent 2 km north in its middle. */
const ROUTE = line(A, B, 150);
const DETOUR = line(A, B, 150, 0.02);
const POINTS = ROUTE.map(([lon, lat]) => [lat, lon]);
const noPause = async () => {};

/** TomTom: the route now (20 min, a 10 min jam from 5 to 8 km), then any variant (800 s). */
function tomtom() {
  const calls = [];
  const time = async (points) => {
    calls.push(points.length);
    if (calls.length === 1) return { travelS: 1200, crowdS: 0, sections: [{ fromM: 5000, toM: 8000, delayS: 600, level: 'heavy' }] };
    return { travelS: 800, crowdS: 0, sections: [] };
  };
  return { time, calls };
}

/** An engine drawing the detour around the jams, and the route itself plus the detour as alternatives. */
function engine(name, { outage = null } = {}) {
  const asks = [];
  const draw = async (ask) => {
    asks.push(ask);
    if (outage) return { engine: name, routes: [], error: outage, outage: true };
    if (ask.alternatives) {
      return { engine: name, routes: [appRoute(name, ROUTE, { durationS: 900 }), appRoute(name, DETOUR, { durationS: 1000 })], error: null, outage: false };
    }
    return { engine: name, routes: [appRoute(name, DETOUR, { durationS: 1000 })], error: null, outage: false };
  };
  return { draw, asks };
}

/** An ask without what varies between runs of the same check. */
const shapeOf = ({ label, from, to, bearings, avoid, polygons, alternatives }) => ({ label, from, to, bearings, avoid, polygons, alternatives });

describe('faster check', () => {
  it('draws with any engine, times with TomTom, and hands back the detour with its engine', async () => {
    for (const name of ['ors', 'valhalla']) {
      const t = tomtom();
      const e = engine(name);
      const { answer, compare } = await checkFaster(POINTS, { avoid: ['tolls'], draw: e.draw, time: t.time, pause: noPause });
      assert.equal(answer.better.route.engine, name);
      assert.equal(answer.better.gainS, 400);
      assert.equal(answer.better.route.durationS, 800);
      assert.equal(answer.variants, 1);
      assert.deepEqual(e.asks.map((ask) => ask.label), ['around', 'alternatives']);
      const [around, alternatives] = e.asks;
      assert.equal(around.polygons.type, 'MultiPolygon');
      assert.equal(around.alternatives, false);
      assert.equal(alternatives.polygons, null);
      assert.equal(alternatives.alternatives, true);
      assert.deepEqual(around.avoid, ['tolls']);
      assert.deepEqual(around.from, A);
      assert.ok(Math.abs(around.to.lon - B.lon) < 1e-9);
      assert.deepEqual(around.bearings, [[90, 45]]);
      assert.deepEqual(t.calls.length, 2); // the route, then its one viable variant
      assert.equal(compare.drawn.engine, name);
      assert.equal(compare.drawn.viable.length, 1);
      assert.equal(compare.drawn.baselineS, 900);
      assert.equal(compare.detour.route.durationS, 1000);
    }
  });

  it('answers "fallback" when the engine is down, before timing any variant (D5.2)', async () => {
    const t = tomtom();
    const e = engine('valhalla', { outage: 'unavailable' });
    const warn = console.warn;
    console.warn = () => {};
    try {
      const { answer, compare } = await checkFaster(POINTS, { draw: e.draw, time: t.time, pause: noPause });
      assert.equal(answer.better, null);
      assert.equal(answer.reason, 'fallback');
      assert.equal(compare, null);
    } finally {
      console.warn = warn;
    }
    assert.equal(t.calls.length, 1); // the route itself only
  });

  it('asks no engine during the cooldown nor without a jam worth it', async () => {
    const e = engine('ors');
    const cool = await checkFaster(POINTS, { sinceRerouteS: 10, draw: e.draw, time: tomtom().time, pause: noPause });
    assert.deepEqual(cool, { answer: { better: null, reason: 'cooldown' }, compare: null });
    const calm = await checkFaster(POINTS, {
      draw: e.draw,
      time: async () => ({ travelS: 1200, crowdS: 0, sections: [{ fromM: 5000, toM: 6000, delayS: 30, level: 'slow' }] }),
      pause: noPause,
    });
    assert.equal(calm.answer.reason, 'no significant jam');
    assert.equal(calm.compare, null);
    assert.equal(e.asks.length, 0);
  });
});

describe('shadow drawing', () => {
  it('draws the same asks with the other engine and sifts them the same way, never asking TomTom', async () => {
    const t = tomtom();
    const served = engine('ors');
    const { compare } = await checkFaster(POINTS, { avoid: ['tolls'], draw: served.draw, time: t.time, pause: noPause });
    const timed = t.calls.length;
    const other = engine('valhalla');
    const drawn = await drawVariants(compare.plan, other.draw);
    assert.equal(t.calls.length, timed);
    assert.deepEqual(other.asks.map(shapeOf), served.asks.map(shapeOf));
    assert.equal(drawn.engine, 'valhalla');
    assert.equal(drawn.found.length, 3);
    assert.equal(drawn.candidates.length, 1);
    assert.equal(drawn.viable.length, 1);
    assert.equal(drawn.baselineS, 900);
    const closest = closestTo(compare.detour, drawn.candidates);
    assert.equal(closest.share, 1);
    assert.equal(closest.candidate.route.engine, 'valhalla');
  });

  it('says how far the other engine\'s closest candidate is from the detour', async () => {
    const { compare } = await checkFaster(POINTS, { draw: engine('ors').draw, time: tomtom().time, pause: noPause });
    // The other engine only finds a detour bent the other way (south).
    const south = line(A, B, 150, -0.02);
    const drawn = await drawVariants(compare.plan, async () => ({ engine: 'valhalla', routes: [appRoute('valhalla', south, { durationS: 1000 })], error: null }));
    const closest = closestTo(compare.detour, drawn.candidates);
    assert.ok(closest.share < 0.7, String(closest.share));
    assert.deepEqual(closestTo(null, drawn.candidates), { share: null, candidate: null });
    assert.deepEqual(closestTo(compare.detour, []), { share: 0, candidate: null });
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
