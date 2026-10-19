import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  aggregateRoads, assembleTrace, departureReference, exclusion, formatReport, judge, loadReport,
  parseArgs, quantile, report, roadSamples, speedClass, stopSummary, timelineSamples, traceAttributes,
} from '../bin/eona-eta-report-v2.js';

const START = Date.parse('2026-10-15T06:00:00Z'); // Jeudi, pointe Paris.
const trip = (changes = {}) => ({
  id: 'trajet-prive', accountId: 'compte-prive', fromLabel: 'adresse-privee', toLabel: 'destination-privee',
  startedAt: START, durationSeconds: 1000, distanceMeters: 10000, departedAt: START,
  firstMoveAt: START + 60000, endReason: 'arrived', arrived: true, platform: 'ios', appVersion: '56',
  pausedSeconds: 0, uncertainSeconds: 0, stops: 0, stoppedSeconds: 0, recalcCount: 0, fasterCount: 0,
  etaChecks: [
    { at: 0, shownAt: START, arrivalAt: START + 800000, base: 'here' },
    { at: 25, shownAt: START + 250000, arrivalAt: START + 900000, base: 'engine' },
  ],
  timeline: [], stopLog: [],
  ...changes,
});
const overall = (r, variant = 'uncertainKept') => r.variants[variant].slices.find((s) => s.dimension === 'all');
const slice = (r, dimension, value) => r.variants.uncertainKept.slices.find((s) => s.dimension === dimension && s.value === value);
const point = (seconds, lat = 48, lon = 2) => [START + seconds * 1000, lat, lon, 30, 5, 0];
const edge = (changes = {}) => ({
  road_class: 'residential', length: 1, speed: 50, speed_type: 'tagged', density: 10,
  end_node: { traffic_signal: true }, ...changes,
});
const match = (edge_index, distance_along_edge, changes = {}) => ({ type: 'matched', edge_index, distance_along_edge, ...changes });

describe('ETA v2 : référence et sélection', () => {
  it('décale seulement le départ ; conserve erreur brute et entrée intacte', () => {
    const input = trip();
    const before = structuredClone(input);
    const judged = judge(input);
    assert.deepEqual(judged.checks.map((c) => [c.at, c.rawErrorS, c.errorS]), [[0, 200, 140], [25, 100, 100]]);
    assert.equal(judged.durationS, 940);
    assert.deepEqual(report([input]).departure.raw.medianErrorS, 200);
    assert.equal(report([input]).departure.sinceMovement.medianErrorS, 140);
    assert.deepEqual(input, before);
  });

  it('premier mouvement absent, antérieur au relevé ou incohérent : aucun décalage négatif', () => {
    for (const firstMoveAt of [null, START - 1, START, START + 1000001]) {
      const input = trip({ firstMoveAt });
      assert.equal(departureReference(input).shiftS, 0);
      assert.equal(judge(input).checks[0].errorS, 200);
    }
    const input = trip({ firstMoveAt: START + 20000, etaChecks: [{ at: 0, shownAt: START + 30000, arrivalAt: START + 800000 }] });
    assert.equal(departureReference(input).waitS, 0);
    assert.equal(judge(input).checks[0].errorS, 200);
  });

  it('attente : médiane et quartiles interpolés ; absence distincte de zéro', () => {
    const result = report([0, 40, 80, 120].map((s) => trip({ firstMoveAt: START + s * 1000 })).concat(trip({ firstMoveAt: null })));
    assert.deepEqual(result.departureWaitS, { n: 4, median: 60, p25: 30, p75: 90, unavailable: 1 });
    assert.equal(quantile([], 0.5), null);
  });

  it('retient arrived et near, même arrived=false ou statistiques coupées ; compte chaque exclusion une fois', () => {
    const result = report([
      trip(), trip({ endReason: 'near', arrived: false, endDistanceM: 200, countInStats: false }),
      trip({ endReason: 'near', endDistanceM: 100 }),
      trip({ endReason: 'stopped' }), trip({ endReason: 'interrupted' }),
      trip({ retargeted: true, endReason: 'interrupted' }), trip({ manualStart: true }),
      trip({ etaChecks: [] }), trip({ endReason: null }), trip({ durationSeconds: 0 }),
    ]);
    assert.deepEqual(result.trips, {
      read: 10, kept: 3,
      excluded: { stopped: 1, interrupted: 1, retargeted: 1, manualStart: 1, sansMesures: 3 },
      arrived: 1, near: 2, nearEndDistanceM: { n: 2, median: 150, p25: 125, p75: 175 },
    });
    assert.equal(exclusion(trip({ durationSeconds: 10, distanceMeters: 10, firstMoveAt: null,
      etaChecks: [{ at: 0, shownAt: START, arrivalAt: START + 5000 }] })), null);
    assert.equal(exclusion(null), 'sansMesures');
    assert.equal(exclusion(trip({ startedAt: 9e15 })), 'sansMesures');
    assert.equal(exclusion(trip({ etaChecks: [{ at: 0, shownAt: 'date', arrivalAt: START }] })), 'sansMesures');
  });

  it('relevé 0 % absent : autres relevés conservés, aucune erreur de départ inventée', () => {
    const input = trip({ etaChecks: [trip().etaChecks[1]] });
    const result = report([input]);
    assert.equal(result.trips.kept, 1);
    assert.equal(result.departure.raw.n, 0);
    assert.equal(result.departure.raw.medianErrorS, null);
    assert.equal(result.departureWaitS.unavailable, 1);
    assert.equal(overall(result).checkpoints[1].raw.n, 1);
  });

  it('conserve variantes pauses, tolérance bornée et Wilson 95 %', () => {
    const input = trip({ firstMoveAt: null, pausedSeconds: 30, uncertainSeconds: 70 });
    const result = report([input]);
    const removed = overall(result, 'uncertainAsPause').checkpoints[0];
    const kept = overall(result).checkpoints[0];
    assert.equal(result.departure.raw.medianErrorS, 200);
    assert.equal(removed.raw.medianErrorS, 100);
    assert.equal(kept.raw.medianErrorS, 170);
    assert.equal(removed.sinceMovement.good, 1);
    assert.equal(kept.sinceMovement.good, 0);
    assert.ok(removed.sinceMovement.wilson95[0] > 0);
    assert.ok(removed.sinceMovement.wilson95[1] <= 1);
    const long = trip({ durationSeconds: 10000, firstMoveAt: null,
      etaChecks: [{ at: 0, shownAt: START, arrivalAt: START + 9519000 }] });
    assert.equal(report([long]).departure.raw.good, 0); // 481 s, plafond 480.
    long.etaChecks[0].arrivalAt += 1000;
    assert.equal(report([long]).departure.raw.good, 1);
  });

  it('découpes : version, base, durée réelle, vitesse, pointe et recalcul', () => {
    const input = trip({ firstMoveAt: null, durationSeconds: 3600, distanceMeters: 45000, fasterCount: 1 });
    const result = report([input]);
    for (const [dimension, value] of [
      ['platformVersion', 'ios 56'], ['departureBase', 'here'], ['duration', '20-60'],
      ['averageSpeed', '>=45'], ['peak', 'pointe'], ['recalculated', 'oui'],
    ]) assert.equal(slice(result, dimension, value)?.trips, 1);
    assert.ok(slice(report([trip({ firstMoveAt: null, durationSeconds: 1200 })]), 'duration', '20-60'));
    assert.ok(slice(report([trip({ durationSeconds: 3601, firstMoveAt: null })]), 'duration', '>60'));
    assert.ok(slice(report([trip()]), 'recalculated', 'non'));
    assert.equal(speedClass(25), '25-45');
    assert.equal(speedClass(45), '>=45');
    assert.equal(speedClass(null), 'inconnue');
    assert.ok(slice(report([trip({ startedAt: START + 5 * 3600000, firstMoveAt: null, departedAt: null,
      etaChecks: [{ at: 0, shownAt: START + 5 * 3600000, arrivalAt: START + 6 * 3600000 }] })]), 'peak', 'hors pointe'));
  });

  it('--since inclusif, arguments ISO stricts, aucune valeur fautive reproduite', () => {
    assert.equal(report([trip(), trip({ startedAt: START - 1 })], { since: START }).trips.read, 1);
    assert.equal(parseArgs(['--since=2026-10-15T06:00:00Z', '--json', '--roads']).since, START);
    assert.equal(parseArgs(['--roads']).roads, true);
    assert.throws(() => parseArgs(['--since=2026-02-30']), /ISO invalide/);
    assert.throws(() => parseArgs(['--since=donnee-privee']), (e) => !e.message.includes('donnee-privee'));
    assert.throws(() => parseArgs(['fichier-prive']), /Option inconnue/);
  });
});

describe('ETA v2 : profil minute et arrêts', () => {
  it('ratios indépendants, quatre tranches et bases ; utilise fin chronologique pour near', () => {
    const input = trip({ endReason: 'near', timeline: [
      { t: START, drivenM: 0, leftM: 1000, leftS: 500, engineLeftS: 250, base: 'here', kmh: 20, engineBasis: 'raw' },
      { t: START + 250000, drivenM: 250, leftM: 750, leftS: 500, engineLeftS: null, base: 'engine', kmh: 25, engineBasis: 'raw' },
      { t: START + 500000, drivenM: 500, leftM: 500, leftS: 0, engineLeftS: 250, base: 'engine', kmh: 45, engineBasis: 'raw' },
      { t: START + 750000, drivenM: 750, leftM: 250, leftS: 125, engineLeftS: 100, base: 'here', kmh: 5, engineBasis: 'raw' },
    ] });
    const samples = timelineSamples(input);
    assert.deepEqual(samples.map((s) => [s.progress, s.displayedRatio, s.engineRatio]), [
      ['0-25', 2, 4], ['25-50', 1.5, null], ['50-75', null, 2], ['75-100', 2, 2.5],
    ]);
    const result = report([input]).timeline;
    assert.equal(result.used, 4);
    assert.equal(result.byBase.find((g) => g.base === 'here').displayedRatio.median, 2);
    assert.equal(result.bySpeed.find((g) => g.speed === '<25').engineRatio.median, 3.25);
    assert.equal(result.byProgressBaseSpeed.length, 4);
  });

  it('ignore relevés hors trajet et valeurs invalides ; fallback distance finale, arrivée ratio zéro', () => {
    const input = trip({ timeline: [
      { t: START - 1, drivenM: 10, leftS: 100 },
      { t: START + 1000001, drivenM: 10, engineLeftS: 100, engineBasis: 'raw' },
      { t: START + 500000, drivenM: 5000, leftM: null, leftS: NaN, engineLeftS: 100, engineBasis: 'raw' },
      { t: START + 1000000, drivenM: 10000, leftM: 0, leftS: 100, engineLeftS: 0, engineBasis: 'raw' },
      { t: START + 200000, drivenM: -1, leftS: 100 },
    ] });
    assert.deepEqual(timelineSamples(input).map((s) => [s.progress, s.displayedRatio, s.engineRatio]), [
      ['50-75', null, 5], ['75-100', 0, null],
    ]);
    assert.equal(report([input]).timeline.read, 5);
    assert.equal(report([input]).timeline.used, 2);
  });

  it('arrêts : médianes par trajet, part arrêtée, seuil initial strict et arrêt déjà commencé', () => {
    const a = trip({ stops: 3, stoppedSeconds: 100, stopLog: [
      { at: START - 5000, seconds: 35, jam: false },
      { at: START + 14000, seconds: 20, jam: true },
      { at: START + 15000, seconds: 45, jam: false },
    ] });
    const b = trip({ stops: 1, stoppedSeconds: 20, stopLog: [{ at: START + 100000, seconds: 20 }] });
    const result = stopSummary([a, b]);
    assert.equal(result.countPerTrip.median, 2);
    assert.equal(result.secondsPerTrip.median, 60);
    assert.ok(Math.abs(result.stoppedSharePerTrip.median - 0.06) < 1e-12);
    assert.equal(result.initial.trips, 1);
    assert.equal(result.initial.seconds.median, 50);
    assert.equal(result.tripsWithLog, 2);
    const absent = stopSummary([trip({ stops: null, stoppedSeconds: null, stopLog: null })]);
    assert.equal(absent.countPerTrip.n, 0);
    assert.equal(absent.tripsWithoutLog, 1);
    assert.equal(absent.initial.tripsWithDepartureAndLog, 0);
    const outside = stopSummary([trip({ durationSeconds: 10, firstMoveAt: null,
      etaChecks: [{ at: 0, shownAt: START, arrivalAt: START + 5000 }],
      stopLog: [{ at: START + 14000, seconds: 20 }] })]);
    assert.equal(outside.initial.trips, 0);
  });

  it('sorties agrégées sans libellé, identifiant, coordonnée ni date individuelle', () => {
    const result = report([trip({
      platform: 'compte-prive', appVersion: 'adresse-privee',
      timeline: [{ t: START + 60000, drivenM: 1, leftM: 9, leftS: 20, engineLeftS: 10,
        base: 'destination-privee', kmh: 20, lat: 48.123456, lon: 2.654321 }],
    })]);
    for (const output of [JSON.stringify(result), formatReport(result)]) {
      for (const sensitive of ['trajet-prive', 'compte-prive', 'adresse-privee', 'destination-privee',
        String(START), '2026-10-15', '48.123456', '2.654321', '"accountId"', '"shownAt"', '"arrivalAt"']) {
        assert.equal(output.includes(sensitive), false, sensitive);
      }
    }
    assert.equal(report([]).departure.raw.wilson95, null);
  });
});

describe('ETA v2 : routes, sans service réel', () => {
  it('réassemble seq, accepte doublon exact de frontière, refuse paquet manquant et temps inversé', () => {
    assert.deepEqual(assembleTrace([
      { seq: 1, points: [point(10), point(20)] }, { seq: 0, points: [point(0), point(10)] },
    ]), [point(0), point(10), point(20)]);
    assert.throws(() => assembleTrace([{ seq: 1, points: [point(0), point(10)] }]), /trace_incomplete/);
    assert.throws(() => assembleTrace([{ seq: 0, points: [point(10), point(0)] }]), /trace_invalid/);
  });

  it('répartit temps GPS entre classes ; tronçons partiels, arrêt et feux sans durée moteur', () => {
    const points = [point(0), point(30), point(60), point(120)];
    const attributes = {
      edges: [
        edge({ length: 0.5, source_percent_along: 0.5 }),
        edge({ road_class: 'primary', density: 2, speed: 90, length: 0.5, target_percent_along: 0.5 }),
      ],
      matched_points: [match(0, 0.5), match(0, 1), match(0, 1), match(1, 0.5)],
    };
    const profile = roadSamples(points, attributes);
    const groups = aggregateRoads(profile.samples);
    const urban = groups.find((g) => g.group === 'rues');
    const rural = groups.find((g) => g.group === 'grands axes');
    assert.equal(urban.km, 0.5);
    assert.equal(urban.realS, 60);
    assert.equal(urban.realKmh.median, 30);
    assert.equal(urban.engineKmh.median, 50);
    assert.equal(urban.withStops.realToEngineRatio, 60 / 36);
    assert.equal(urban.edgeRatios.realToEngine.median, 60 / 36);
    assert.equal(urban.signals, 1);
    assert.equal(urban.signalsPerKm, 2);
    assert.equal(rural.km, 0.5);
    assert.equal(rural.realS, 60);
    assert.equal(rural.signals, 0); // Fin au milieu de l'arête.
    assert.equal(profile.observedIntervals, 3);
    assert.equal(profile.skippedIntervals, 0);
  });

  it('interpole un intervalle traversant plusieurs arêtes, sans compter un feu plusieurs fois', () => {
    const profile = roadSamples([point(0), point(60)], {
      edges: [edge({ length: 0.25 }), edge({ length: 0.75 })],
      matched_points: [match(0, 0), match(1, 1)],
    });
    assert.deepEqual(profile.samples.map((s) => [s.km, s.realS]), [[0.25, 15], [0.75, 45]]);
    const group = aggregateRoads(profile.samples)[0];
    assert.equal(group.realS, 60);
    assert.equal(group.signals, 2);
    assert.equal(group.realKmh.median, 60);
  });

  it('temps non apparié signalé, aucune vitesse moteur substituée ; groupe sans densité', () => {
    const profile = roadSamples([point(0), point(10), point(20)], {
      edges: [edge({ density: null, end_node: {} })],
      matched_points: [match(0, 0), match(0, 0.5), { type: 'unmatched' }],
    });
    assert.equal(profile.skippedIntervals, 1);
    assert.equal(profile.skippedS, 10);
    assert.equal(profile.samples[0].group, 'rues');
    assert.equal('density' in profile.samples[0], false);
    assert.equal(aggregateRoads(profile.samples)[0].signalsPerKm, null);
    assert.throws(() => roadSamples([point(0), point(10)], {
      edges: [edge()], matched_points: [match(0, 0), match(0, 1, { begin_route_discontinuity: true })],
    }), /match_unusable/);
  });

  it('requête locale POST auto, attributs utiles seuls et timestamps secondes', async () => {
    const response = await traceAttributes([point(0), point(10)], { fetchImpl: async (url, init) => {
      assert.equal(url, 'http://127.0.0.1:8002/trace_attributes');
      assert.equal(init.method, 'POST');
      const body = JSON.parse(init.body);
      assert.equal(body.costing, 'auto');
      assert.equal(body.shape_match, 'map_snap');
      assert.equal(body.shape[0].time, START / 1000);
      assert.ok(body.filters.attributes.includes('matched.edge_index'));
      assert.ok(body.filters.attributes.includes('edge.speed_type'));
      assert.equal(body.filters.attributes.includes('matched.point'), false);
      return { ok: true, json: async () => ({ edges: [] }) };
    } });
    assert.deepEqual(response, { edges: [] });
  });

  it('timeout couvre fetch et lecture JSON, même si injection ignore AbortSignal', async () => {
    const never = () => new Promise(() => {});
    await assert.rejects(traceAttributes([point(0), point(10)], { fetchImpl: never, timeoutMs: 10 }), /match_timeout/);
    await assert.rejects(traceAttributes([point(0), point(10)], {
      fetchImpl: async () => ({ ok: true, json: never }), timeoutMs: 10,
    }), /match_timeout/);
  });

  it('SQL SELECT seulement, paramètres --since ; sans --roads ne charge aucun tracé', async () => {
    const calls = [];
    const result = await loadReport({ since: START, query: async (sql, parameters) => {
      calls.push({ sql, parameters });
      assert.match(sql, /^SELECT .* FROM crowd\.trip WHERE /);
      return { rows: [{ record: trip() }] };
    }, matchTrace: () => assert.fail('aucun appel Valhalla') });
    assert.equal(calls.length, 1);
    assert.deepEqual(calls[0].parameters, ['2026-10-15T06:00:00.000Z']);
    assert.equal(result.roads, null);
  });

  it('tracés liés au compte exact ; erreurs isolées, absences distinctes et sortie privée', async () => {
    const calls = [];
    const records = [
      { account_id: 'compte-A', trip_id: 'meme-id', record: trip() },
      { account_id: 'compte-B', trip_id: 'meme-id', record: trip() },
      { account_id: 'compte-C', trip_id: 'absent', record: trip() },
      { account_id: 'compte-D', trip_id: 'exclu', record: trip({ endReason: 'stopped' }) },
    ];
    let matchedCount = 0;
    const result = await loadReport({
      roads: true,
      query: async (sql, parameters) => {
        assert.match(sql, /^SELECT /);
        assert.equal(/\b(INSERT|UPDATE|DELETE|CREATE|ALTER|SET|BEGIN)\b/i.test(sql), false);
        if (sql.includes('FROM crowd.trip WHERE')) return { rows: records };
        assert.match(sql, /FROM crowd\.trip_trace WHERE account_id = \$1 AND trip_id = \$2 ORDER BY seq$/);
        calls.push(parameters);
        return { rows: parameters[0] === 'compte-C' ? [] : [{ seq: 0, points: [point(60), point(100)] }] };
      },
      matchTrace: async () => {
        if (++matchedCount === 2) throw new Error('coord-privee 48.123456 cle-privee');
        return { edges: [edge()], matched_points: [match(0, 0), match(0, 1)] };
      },
    });
    assert.deepEqual(calls, [['compte-A', 'meme-id'], ['compte-B', 'meme-id'], ['compte-C', 'absent'], ['compte-D', 'exclu']]);
    assert.equal(result.roads.attempted, 3);
    assert.equal(result.roads.matched, 2);
    assert.equal(result.roads.failed, 1);
    assert.equal(result.roads.missing, 1);
    assert.equal(result.roads.expectedS, 3760);
    assert.equal(result.roads.matchedTraceS, 80);
    assert.equal(result.roads.assignedS, 80);
    assert.equal(result.roads.timeCoverage, 80 / 3760);
    assert.deepEqual(result.roads.rejected, { match_unavailable: 1 });
    for (const secret of ['compte-A', 'compte-B', 'meme-id', 'coord-privee', '48.123456', 'cle-privee']) {
      assert.equal(JSON.stringify(result).includes(secret), false);
    }
  });
});


describe('Lot 4 : rapport et calibrage', () => {
  it('interrupted à 300 m inclus compte comme proche ; autres exclusions gardées', () => {
    const result = report([0, 300, 300.001, -1, null].map((endDistanceM) => trip({ endReason: 'interrupted', endDistanceM })));
    assert.equal(result.trips.kept, 2);
    assert.equal(result.trips.near, 2);
    assert.equal(result.trips.arrived, 0);
    assert.equal(result.trips.excluded.interrupted, 3);
    assert.match(result.rules.selection, /interrupted à 300 m/);
    assert.equal(exclusion(trip({ endReason: 'interrupted', endDistanceM: 100, retargeted: true })), 'retargeted');
    assert.equal(exclusion(trip({ endReason: 'interrupted', endDistanceM: 100, manualStart: true })), 'manualStart');
  });

  it('groupe × période, sommes pondérées et EONA partagé ; retrait arrêts >= 10 s sans double compte', () => {
    const profile = roadSamples([point(0), point(60), point(90)], {
      edges: [edge({ road_class: 'primary', length: 1, speed: 60 }),
        edge({ road_class: 'secondary', length: 0.1, speed: 60, end_node: { traffic_signal: false } })],
      matched_points: [match(0, 0), match(0, 1), match(1, 1)],
    }, { stops: [{ at: START, seconds: 9 }, { at: START + 60000, seconds: 20 }, { at: START + 65000, seconds: 10 }] });
    const groups = aggregateRoads(profile.samples);
    assert.equal(groups.length, 1);
    const group = groups[0];
    assert.equal(group.group, 'grands axes');
    assert.equal(group.period, 'pointe');
    assert.ok(Math.abs(group.withStops.engineS - 66) < 1e-8);
    assert.ok(Math.abs(group.withStops.eonaS - 107.7) < 1e-8);
    assert.ok(Math.abs(group.withStops.realToEngineRatio - 90 / 66) < 1e-8);
    assert.ok(Math.abs(group.withStops.realToEonaRatio - 90 / 107.7) < 1e-8);
    assert.ok(Math.abs(group.withoutStops.realS - 70) < 1e-8);
    assert.ok(Math.abs(group.withoutStops.stoppedS - 20) < 1e-8);
    assert.ok(Math.abs(group.withoutStops.realToEngineRatio - 70 / 66) < 1e-8);
    assert.ok(Math.abs(group.withoutStops.realToEonaRatio - 70 / 107.7) < 1e-8);
    assert.ok(Math.abs(group.edgeRatios.realToEngine.median - 3) < 1e-8);
    assert.equal(group.signals, 1);
    assert.ok(Math.abs(group.signalsPerKm - 1 / 1.1) < 1e-8);
    assert.equal(aggregateRoads(profile.samples, { byClass: true }).length, 2);
    const separate = aggregateRoads([...profile.samples, { ...profile.samples[0], period: 'autre' }]);
    assert.equal(separate.length, 2);
  });

  it('arrêts inconnus et vitesses absentes restent distincts de zéro', () => {
    const profile = roadSamples([point(0), point(60)], {
      edges: [edge({ speed: null })], matched_points: [match(0, 0), match(0, 1)],
    });
    const group = aggregateRoads(profile.samples)[0];
    assert.equal(group.realS, 60);
    assert.equal(group.withStops.realToEngineRatio, null);
    assert.equal(group.withoutStops.samples, 0);
    assert.equal(group.withoutStops.realToEonaRatio, null);
  });

  it('routes et arrêts incluent exclus ETA ; premier mouvement et tracé obligatoires', async () => {
    const records = [
      { account_id: 'a', trip_id: '1', record: trip({ endReason: 'stopped' }), has_trace: true },
      { account_id: 'a', trip_id: '2', record: trip({ endReason: 'interrupted', endDistanceM: 1000 }), has_trace: true },
      { account_id: 'a', trip_id: '3', record: trip({ firstMoveAt: null }), has_trace: true },
      { account_id: 'a', trip_id: '4', record: trip(), has_trace: false },
    ];
    const query = async (sql, parameters) => {
      if (sql.includes('FROM crowd.trip WHERE')) return { rows: records };
      assert.ok(['1', '2'].includes(parameters[1]));
      return { rows: [{ seq: 0, points: [point(60), point(120)] }] };
    };
    const result = await loadReport({ query, roads: true,
      matchTrace: async () => ({ edges: [edge()], matched_points: [match(0, 0), match(0, 1)] }) });
    assert.equal(result.trips.kept, 2);
    assert.equal(result.roads.attempted, 2);
    assert.equal(result.roads.matched, 2);
    assert.equal(result.stops.tripsWithLog, 2);
    assert.equal(result.roads.classes[0].roadClass, 'residential');
    assert.ok(JSON.stringify(result).includes('realToEonaRatio'));
    const plain = await loadReport({ query });
    assert.equal(plain.stops.tripsWithLog, 2);
    assert.equal(plain.roads, null);
    assert.ok(formatReport(result).includes('réel/EONA='));
    assert.equal(formatReport(result).includes('Densité'), false);
  });
});
