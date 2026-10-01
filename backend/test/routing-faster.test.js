import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { checkFaster as actualCheckFaster, closestTo, drawVariants } from '../src/routing/faster.js';
import { appRoute, line, trapNetwork } from './helpers/routing.js';

// Durées HERE indépendantes des moteurs : trajet 1500 s, détour 1000 s.
const checkFaster = (points, options) => actualCheckFaster(points, {
  travel: async line => ({ travelS: line.some(p=>p[0] > 48.00001) ? 1000 : 1500 }), ...options,
});

// /api/route/faster's check (faster.js) with a fake live traffic ([traffic]) and fake engines
// ([draw]): the same check whatever the engine; the shadow's drawing never times anything.
const net = trapNetwork(before, after);

const A = { lat: 48.0, lon: 2.0 };
const B = { lat: 48.0, lon: 2.2 };
/** The route followed, ~15 km due east, [lon, lat]; a detour bent 2 km north in its middle. */
const ROUTE = line(A, B, 150);
const DETOUR = line(A, B, 150, 0.02);
const POINTS = ROUTE.map(([lon, lat]) => [lat, lon]);

/** The live traffic: on the route now, a 10 min jam from 5 to 8 km; on any variant, nothing. */
function live() {
  const calls = [];
  const traffic = async (points) => {
    calls.push(points.length);
    if (calls.length === 1) return { reliable: true, delayS: 600, crowdS: 0, sections: [{ fromM: 5000, toM: 8000, delayS: 600, level: 'heavy' }] };
    return { reliable: true, delayS: 0, crowdS: 0, sections: [] };
  };
  return { traffic, calls };
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
  it('draws with any engine, times with the live traffic, and hands back the detour with its engine', async () => {
    for (const name of ['ors', 'valhalla']) {
      const t = live();
      const e = engine(name);
      const { answer, compare } = await checkFaster(POINTS, { avoid: ['tolls'], draw: e.draw, traffic: t.traffic });
      assert.equal(answer.better.route.engine, name);
      // The route over the window: its engine time (900 s) and the jam (600 s); the detour: 1000 s.
      assert.equal(answer.currentS, 1500);
      assert.equal(answer.better.gainS, 500);
      assert.equal(answer.better.route.durationS, 1000);
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
    const t = live();
    const e = engine('valhalla', { outage: 'unavailable' });
    const warn = console.warn;
    console.warn = () => {};
    try {
      const { answer, compare } = await checkFaster(POINTS, { draw: e.draw, traffic: t.traffic });
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
    const cool = await checkFaster(POINTS, { sinceRerouteS: 10, draw: e.draw, traffic: live().traffic });
    assert.deepEqual(cool, { answer: { better: null, reason: 'cooldown' }, compare: null });
    const calm = await checkFaster(POINTS, {
      draw: e.draw,
      traffic: async () => ({ reliable: true, delayS: 30, crowdS: 0, sections: [{ fromM: 5000, toM: 6000, delayS: 30, level: 'slow' }] }),
    });
    assert.equal(calm.answer.reason, 'no significant jam');
    assert.equal(calm.compare, null);
    assert.equal(e.asks.length, 0);
  });
});

describe('shadow drawing', () => {
  it('draws the same asks with the other engine and sifts them the same way, never asking the live traffic', async () => {
    const t = live();
    const served = engine('ors');
    const { compare } = await checkFaster(POINTS, { avoid: ['tolls'], draw: served.draw, traffic: t.traffic });
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
    const { compare } = await checkFaster(POINTS, { draw: engine('ors').draw, traffic: live().traffic });
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


describe('Détours fiables',()=>{
  it('refuse variante plus lente selon HERE malgré durée moteur favorable',async()=>{
    const result=await checkFaster(POINTS,{draw:engine('valhalla').draw,traffic:live().traffic,
      travel:async points=>({travelS:points.some(p=>p[0]>48.00001)?1600:1500})});
    assert.equal(result.answer.better,null);
    assert.equal(result.answer.reason,'not enough gain');
  });
  it('refuse nouvelle fermeture sur variante, même avec retard nul',async()=>{
    let n=0, imports=0;
    const first=live().traffic;
    const result=await checkFaster(POINTS,{draw:engine('valhalla').draw,
      traffic:async points=>++n===1?first(points):({reliable:true,delayS:0,crowdS:0,sections:[{fromM:1000,toM:1500,level:'closed',delayS:0}]}),
      travel:async()=>{imports++;return {travelS:1500};}});
    assert.equal(result.answer.better,null);assert.equal(imports,1);
  });
  it('aucun détour sans durée actuelle fiable ou sans incidents',async()=>{
    const e=engine('valhalla');
    const missing=await checkFaster(POINTS,{draw:e.draw,traffic:live().traffic,travel:async()=>null});
    assert.equal(missing.answer.reason,'HERE baseline unavailable');assert.equal(e.asks.length,0);
    let n=0;const first=live().traffic;
    const partial=await checkFaster(POINTS,{draw:e.draw,
      traffic:async points=>++n===1?first(points):({reliable:false,sections:[],delayS:0,crowdS:0})});
    assert.equal(partial.answer.better,null);
  });
});


testLongWindow();
function testLongWindow(){
  describe('Fenêtre sur trajet long',()=>{
    it('compare mêmes extrémités, conserve suite du trajet et tous évitements',async()=>{
      const far={lat:48,lon:4};
      const long=line(A,far,300).map(([lon,lat])=>[lat,lon]);
      const timings=[], constraints=[];
      const traffic=async points=>({reliable:true,coverageM:Infinity,crowdS:0,delayS:points===long?600:0,
        sections:points===long?[{fromM:5000,toM:8000,delayS:600,level:'heavy'}]:[]});
      const draw=async ask=>{
        constraints.push(ask.avoid);
        const shape=line(ask.from,ask.to,80,ask.label==='rest'?0:0.02);
        return {engine:'valhalla',routes:[appRoute('valhalla',shape,{durationS:1000})],outage:false};
      };
      const result=await actualCheckFaster(long,{traffic,draw,avoid:['tolls','highways','ferries'],
        travel:async points=>{timings.push(points);return {travelS:timings.length===1?1500:1000};}});
      assert.ok(result.answer.better);
      assert.equal(timings.length,2);
      assert.deepEqual(timings[0][0],timings[1][0]);
      assert.deepEqual(timings[0].at(-1),timings[1].at(-1));
      assert.ok(timings[0].at(-1)[1]<long.at(-1)[1]);
      assert.deepEqual(result.answer.better.route.coordinates.at(-1),[far.lon,far.lat]);
      assert.ok(constraints.every(a=>JSON.stringify(a)===JSON.stringify(['tolls','highways','ferries'])));
    });
    it('refuse variante partiellement couverte par les incidents',async()=>{
      let n=0;const first=live().traffic;
      const result=await checkFaster(POINTS,{draw:engine('valhalla').draw,
        traffic:async points=>++n===1?first(points):({reliable:true,coverageM:1000,sections:[],crowdS:0,delayS:0})});
      assert.equal(result.answer.better,null);
    });
  });
}
