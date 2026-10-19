import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  applyEdgeTime, applyFallbackTime, coherentSteps, createEonaTime, edgeTime, fallbackFactor,
  isPeak, periodAt, roadGroup, SIGNAL_EXTRA_S, stepShapeRanges, TIME_FACTORS,
} from '../src/routing/eona-time.js';
import { isPeak as historicalPeak } from '../bin/eona-eta-report.js';
import { splitTrace, traceAttributes, TRACE_MAX_DISTANCE_M, TRACE_MAX_SHAPE } from '../src/routing/trace-attributes.js';
import { createValhallaClient } from '../src/routing/providers/valhalla.js';
import { appRoute, encodePolyline6 } from './helpers/routing.js';

const OTHER = Date.parse('2026-10-18T12:00:00Z');
const PEAK = Date.parse('2026-10-19T06:00:00Z');
const coordinates = [[2, 48], [2.001, 48], [2.004, 48]];
const route = (changes = {}) => appRoute('valhalla', coordinates, { durationS: 100, distanceM: 1000, ...changes });
const edge = (changes = {}) => ({ length: 1, speed: 60, road_class: 'residential', use: 'road',
  begin_shape_index: 0, end_shape_index: 2, end_node: { traffic_signal: false }, ...changes });
const matched = (edges = [edge()]) => ({ coordinates, edges });
const near = (a, b) => assert.ok(Math.abs(a - b) < 1e-7, a + ' != ' + b);

describe('Lot 4 : table et période', () => {
  it('conserve exactement la table du 18/10 et les groupes', () => {
    assert.deepEqual(TIME_FACTORS, {
      autoroute: { autre: 1.15, pointe: 1.40 },
      'grands axes': { autre: 1.25, pointe: 1.45 },
      rues: { autre: 1.20, pointe: 1.35 },
    });
    assert.equal(SIGNAL_EXTRA_S, 12);
    for (const value of ['motorway', 'trunk']) assert.equal(roadGroup(value), 'autoroute');
    for (const value of ['primary', 'secondary']) assert.equal(roadGroup(value), 'grands axes');
    for (const value of ['tertiary', 'unclassified', 'residential', 'service_other', 'living_street', 'autre', null]) {
      assert.equal(roadGroup(value), 'rues');
    }
    assert.ok(Object.isFrozen(TIME_FACTORS));
    assert.ok(Object.values(TIME_FACTORS).every(Object.isFrozen));
  });

  it('réutilise isPeak v1 ; bornes Paris, été, hiver et changements d’heure', () => {
    assert.equal(isPeak, historicalPeak);
    for (const [date, expected] of [
      ['2026-10-19T04:59:59Z', false], ['2026-10-19T05:00:00Z', true],
      ['2026-10-19T07:59:59Z', true], ['2026-10-19T08:00:00Z', false],
      ['2026-10-19T14:00:00Z', true], ['2026-10-19T18:00:00Z', false],
      ['2026-10-17T08:00:00Z', true], ['2026-10-17T17:00:00Z', false],
      ['2026-10-18T12:00:00Z', false], ['2026-10-26T05:59:59Z', false],
      ['2026-10-26T06:00:00Z', true], ['2026-03-27T06:00:00Z', true],
      ['2026-03-30T05:00:00Z', true], ['2026-03-29T01:00:00Z', false],
      ['2026-10-25T01:00:00Z', false],
    ]) assert.equal(isPeak(Date.parse(date)), expected, date);
    assert.equal(periodAt(OTHER), 'autre');
    assert.equal(periodAt(PEAK), 'pointe');
  });
});

describe('Lot 4 : calcul et étapes', () => {
  it('additionne roulage et feux ; ne compte pas le nœud final non traversé', () => {
    near(edgeTime(edge({ speed: 50, end_node: { traffic_signal: true } }), OTHER).extraS, 26.4);
    near(edgeTime(edge({ speed: 50, end_node: { traffic_signal: true } }), PEAK).extraS, 37.2);
    assert.equal(edgeTime(edge({ target_percent_along: 0.5, end_node: { traffic_signal: true } }), OTHER).signals, 0);
    assert.equal(edgeTime(edge({ use: 'ramp', road_class: 'motorway' }), PEAK).extraS, 60 * (1.40 - 1));
    // Autoroute rapide (130 km/h) : jamais la pointe urbaine.
    near(edgeTime(edge({ road_class: 'motorway', speed: 130 }), PEAK).extraS, 3600 / 130 * (1.15 - 1));
    near(edgeTime(edge({ road_class: 'motorway', speed: 90 }), PEAK).extraS, 40 * (1.40 - 1));
  });

  it('prend la période à l\'heure de passage estimée de chaque arête', () => {
    // Lundi 09:59 Paris : première arête en pointe (60 s × 1,35), seconde après 10:00 (× 1,20).
    const lastPeakMinute = Date.parse('2026-10-19T07:59:00Z');
    const edges = [edge({ end_shape_index: 1 }), edge({ begin_shape_index: 1, end_shape_index: 2 })];
    const result = applyEdgeTime(route(), matched(edges), lastPeakMinute);
    assert.equal(result.durationS, 100 + Math.round(60 * 0.35 + 60 * 0.20));
  });

  it('ignore et compte les arêtes sans longueur ou vitesse', () => {
    const result = applyEdgeTime(route(), matched([edge(), edge({ speed: null }), edge({ length: null })]), OTHER);
    assert.equal(result.durationS, 112);
    assert.equal(result.eonaTime.skipped, 2);
    assert.throws(() => applyEdgeTime(route(), matched([edge({ speed: 0 })]), OTHER), /match_unusable/);
  });

  it('répartit une arête chevauchant deux étapes au prorata géométrique', () => {
    const raw = route();
    raw.steps[0].durationS = 25;
    raw.steps[0].distanceM = 250;
    raw.steps[1].durationS = 75;
    raw.steps[1].distanceM = 750;
    const before = structuredClone(raw);
    const result = applyEdgeTime(raw, matched([edge({ end_node: { traffic_signal: true } })]), OTHER);
    assert.deepEqual(result.steps.map((s) => s.durationS), [31, 93, 0]);
    assert.equal(result.durationS, 124);
    assert.equal(result.engineDurationS, 100);
    assert.deepEqual(result.eonaTime, { version: 1, mode: 'edges', signals: 1 });
    assert.deepEqual(raw, before);
  });

  it('projette une étape entre deux sommets, et distingue un retour au même point', () => {
    const steps = [{ type: 'depart', location: [2, 48], distanceM: 1 },
      { type: 'turn', location: [2.002, 48], distanceM: 1 },
      { type: 'arrive', location: [2.004, 48], distanceM: 0 }];
    near(stepShapeRanges(steps, [[2, 48], [2.004, 48]])[0][1], 0.5);
    assert.deepEqual(stepShapeRanges([
      { type: 'depart', location: [2, 48], distanceM: 2 },
      { type: 'turn', location: [2, 48], distanceM: 1 },
      { type: 'arrive', location: [2, 48.001], distanceM: 0 },
    ], [[2, 48], [2.001, 48], [2, 48], [2, 48.001]]), [[0, 2], [2, 3], [3, 3]]);
  });

  it('corrige les arrondis ; aucune étape négative, somme égale à la route', () => {
    for (let n = 1; n < 100; n++) {
      const raw = route({ durationS: n });
      raw.steps[0].durationS = Math.round(n / 2);
      raw.steps[1].durationS = Math.round(n / 2);
      const result = applyEdgeTime(raw, matched(), OTHER);
      assert.equal(result.steps.reduce((sum, s) => sum + s.durationS, 0), result.durationS);
      assert.ok(result.steps.every((s) => Number.isInteger(s.durationS) && s.durationS >= 0));
      assert.ok(result.durationS >= raw.durationS);
    }
    assert.deepEqual(coherentSteps([{}, {}, {}], [1.6, 1.6, 0], 3).map((s) => s.durationS), [2, 1, 0]);
  });

  it('repli aux seuils exacts 40 et 70 km/h ; mêmes facteurs sur chaque étape', () => {
    for (const [kmh, group] of [[0, 'rues'], [39.99, 'rues'], [40, 'grands axes'], [69.99, 'grands axes'], [70, 'autoroute']]) {
      const raw = route({ distanceM: kmh * 100 / 3.6 });
      raw.steps[0].durationS = 40;
      raw.steps[1].durationS = 60;
      for (const at of [OTHER, PEAK]) {
        assert.equal(fallbackFactor(raw, at), TIME_FACTORS[group][periodAt(at)]);
        const result = applyFallbackTime(raw, at);
        assert.ok(result.durationS >= raw.durationS);
        assert.equal(result.engineDurationS, 100);
        assert.equal(result.steps[0].durationS, Math.round(40 * TIME_FACTORS[group][periodAt(at)]));
        assert.equal(result.steps.reduce((n, s) => n + s.durationS, 0), result.durationS);
        assert.deepEqual(result.eonaTime, { version: 1, mode: 'fallback', signals: 0 });
      }
    }
  });

  it('échec ou absence Valhalla : repli sans échec de route ; compteurs distincts', async () => {
    const absent = createEonaTime();
    assert.equal((await absent.apply(route(), { at: OTHER })).eonaTime.mode, 'fallback');
    assert.equal(absent.health().failed, 0);
    const failed = createEonaTime({ trace: async () => { throw Error('indisponible'); } });
    assert.equal((await failed.apply(route(), { at: OTHER })).eonaTime.mode, 'fallback');
    assert.equal(failed.health().failed, 1);
    const disabled = createEonaTime({ trace: () => assert.fail('aucun appel'), available: () => false });
    await disabled.apply(route(), { at: OTHER });
    assert.equal(disabled.health().failed, 0);
  });

  it('borne une trace muette même quand son AbortSignal est ignoré', async () => {
    let signal;
    const model = createEonaTime({ trace: (_, options) => { signal = options.signal; return new Promise(() => {}); } });
    const result = await model.apply(route(), { at: OTHER, timeoutMs: 10 });
    assert.equal(result.eonaTime.mode, 'fallback');
    assert.equal(signal.aborted, true);
    assert.equal(model.health().failed, 1);
  });
});

describe('Lot 4 : trace locale découpée', () => {
  it('respecte max_shape et max_distance, sans trou ni double segment', () => {
    assert.equal(TRACE_MAX_SHAPE, 16000);
    assert.equal(TRACE_MAX_DISTANCE_M, 200000);
    const shape = Array.from({ length: 8 }, (_, i) => ({ lat: 48, lon: 2 + i * 0.001 }));
    const chunks = splitTrace(shape, { maxShape: 3, maxDistanceM: 200000 });
    assert.deepEqual(chunks.map((c) => c.start), [0, 2, 4, 6]);
    assert.deepEqual(chunks.flatMap((c, i) => c.shape.slice(i ? 1 : 0)), shape);
    assert.ok(splitTrace(shape, { maxDistanceM: 200 }).every((c) => c.shape.length <= 3));
    const dense = Array.from({ length: 16001 }, () => ({ lat: 48, lon: 2 }));
    assert.deepEqual(splitTrace(dense).map((c) => c.shape.length), [16000, 2]);
    const long = Array.from({ length: 30 }, (_, i) => ({ lat: 48, lon: 2 + i * 0.1 }));
    assert.ok(splitTrace(long).length > 1);
    assert.throws(() => splitTrace([{ lat: 48, lon: 2 }, { lat: 48, lon: 10 }]), /trace_limit/);
  });

  it('raccorde indices et feux de deux réponses consécutives', async () => {
    const shape = coordinates.map(([lon, lat]) => ({ lat, lon }));
    const calls = [];
    const result = await traceAttributes(shape, { maxShape: 2, shapeMatch: 'edge_walk',
      fetchImpl: async (url, init) => {
        const body = JSON.parse(init.body);
        calls.push(body);
        const line = body.shape.map((p) => [p.lon, p.lat]);
        return { ok: true, json: async () => ({ shape: encodePolyline6(line),
          edges: [edge({ length: calls.length === 1 ? 0.25 : 0.75, begin_shape_index: 0, end_shape_index: 1,
            target_percent_along: calls.length === 1 ? 0.25 : 1, end_node: { traffic_signal: true } })] }) };
      } });
    assert.equal(calls.length, 2);
    assert.equal(calls[0].shape_match, 'edge_walk');
    assert.ok(calls[0].filters.attributes.includes('node.traffic_signal'));
    assert.ok(calls[0].filters.attributes.includes('edge.use'));
    assert.deepEqual(result.coordinates, coordinates);
    assert.deepEqual(result.edges.map((e) => [e.begin_shape_index, e.end_shape_index]), [[0, 1], [1, 2]]);
    assert.equal(applyEdgeTime(route(), result, OTHER).eonaTime.signals, 1);
  });

  it('utilise exactement baseUrl et fetch du client Valhalla', async () => {
    const client = createValhallaClient({ baseUrl: 'http://127.0.0.1:8123/local/', timeoutMs: 100,
      maxSnapM: 350, searchCutoffM: 1000, fetchImpl: async (url, init) => {
        assert.equal(url, 'http://127.0.0.1:8123/local/trace_attributes');
        assert.equal(JSON.parse(init.body).shape_match, 'map_snap');
        return { ok: true, json: async () => ({ edges: [] }) };
      } });
    await client.traceAttributes(coordinates.map(([lon, lat]) => ({ lat, lon })));
  });

  it('borne fetch et lecture JSON, même avec un client qui ignore le signal', async () => {
    const shape = coordinates.map(([lon, lat]) => ({ lat, lon }));
    const never = () => new Promise(() => {});
    await assert.rejects(traceAttributes(shape, { fetchImpl: never, timeoutMs: 10 }), /match_timeout/);
    await assert.rejects(traceAttributes(shape, { timeoutMs: 10,
      fetchImpl: async () => ({ ok: true, json: never }) }), /match_timeout/);
  });
});


it('Lot 4 : latence locale du calcul sur 16 000 sommets, sans réseau', (t) => {
  const coordinates = Array.from({ length: 16000 }, (_, i) => [2 + i * 0.0001, 48]);
  const steps = Array.from({ length: 200 }, (_, i) => ({ type: i ? 'continue' : 'depart',
    location: coordinates[i * 80], distanceM: 600, durationS: 36 }));
  steps.push({ type: 'arrive', location: coordinates.at(-1), distanceM: 0, durationS: 0 });
  const raw = { engine: 'valhalla', distanceM: 120000, durationS: 7200, coordinates, steps };
  const edges = Array.from({ length: 1600 }, (_, i) => edge({ road_class: 'motorway', length: 0.075,
    speed: 80, begin_shape_index: i * 10, end_shape_index: Math.min(i * 10 + 10, coordinates.length - 1) }));
  const start = performance.now();
  const result = applyEdgeTime(raw, { coordinates, edges }, OTHER);
  t.diagnostic('Calcul seul, 16000 sommets, 1600 arêtes, 201 étapes : ' + (performance.now() - start).toFixed(2) + ' ms');
  assert.equal(result.steps.reduce((sum, s) => sum + s.durationS, 0), result.durationS);
  assert.ok(result.durationS >= result.engineDurationS);
});


it('Lot 4 : points GPS et indices d’arêtes restent continus après découpe', async () => {
  const points = coordinates.map(([lon, lat], i) => ({ lon, lat, time: 1000 + i * 30 }));
  let count = 0;
  const result = await traceAttributes(points, { maxShape: 2, fetchImpl: async (_, init) => {
    const body = JSON.parse(init.body);
    const first = ++count === 1;
    assert.equal(body.use_timestamps, true);
    return { ok: true, json: async () => ({ shape: encodePolyline6(body.shape.map((p) => [p.lon, p.lat])),
      edges: [edge({ begin_shape_index: 0, end_shape_index: 1, length: first ? 0.25 : 0.75,
        source_percent_along: first ? 0 : 0.25, target_percent_along: first ? 0.25 : 1 })],
      matched_points: [{ type: 'matched', edge_index: 0, distance_along_edge: first ? 0 : 0.25 },
        { type: 'matched', edge_index: 0, distance_along_edge: first ? 0.25 : 1 }],
    }) };
  } });
  assert.deepEqual(result.matched_points.map((p) => [p.edge_index, p.distance_along_edge]), [[0, 0], [0, 0.25], [1, 1]]);
  assert.equal(result.coordinates.length, 3);
});
