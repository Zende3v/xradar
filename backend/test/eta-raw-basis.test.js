import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { applyEdgeTime, applyFallbackTime } from '../src/routing/eona-time.js';
import { shapeTrip } from '../src/accounts/store.js';
import { createTripLog } from '../src/accounts/trip-log.js';
import { report, timelineSamples, formatReport } from '../bin/eona-eta-report-v2.js';

const AT = Date.parse('2026-10-18T12:00:00Z'); // Samedi, hors pointe.
const coordinates = [[2.30, 48.80], [2.32, 48.80], [2.34, 48.80]];
const route = () => ({
  engine: 'valhalla', distanceM: 2940, durationS: 300, coordinates,
  steps: [
    { type: 'depart', location: coordinates[0], distanceM: 1470, durationS: 200 },
    { type: 'turn', location: coordinates[1], distanceM: 1470, durationS: 100 },
    { type: 'arrive', location: coordinates[2], distanceM: 0, durationS: 0 },
  ],
});
const matched = { coordinates, edges: [
  { length: 1.47, speed: 26.46, road_class: 'primary', begin_shape_index: 0, end_shape_index: 1, end_node: { traffic_signal: true } },
  { length: 1.47, speed: 52.92, road_class: 'residential', begin_shape_index: 1, end_shape_index: 2, end_node: {} },
] };

describe('contrat ETA : durées brutes route et étapes (19/10)', () => {
  it('arêtes : brut conservé par étape, distinct du corrigé, entrée intacte', () => {
    const input = route();
    const frozen = JSON.stringify(input);
    const out = applyEdgeTime(input, matched, AT);
    assert.equal(JSON.stringify(input), frozen);
    assert.equal(out.engineDurationS, 300);
    assert.ok(out.durationS > 300);
    assert.deepEqual(out.steps.map((s) => s.engineDurationS), [200, 100, 0]);
    assert.ok(out.steps[0].durationS > out.steps[0].engineDurationS);
    assert.equal(out.steps.reduce((n, s) => n + s.durationS, 0), out.durationS);
    assert.equal(out.eonaTime.mode, 'edges');
  });

  it('repli : brut conservé, aucune double correction', () => {
    const out = applyFallbackTime(route(), AT);
    assert.equal(out.engineDurationS, 300);
    assert.deepEqual(out.steps.map((s) => s.engineDurationS), [200, 100, 0]);
    assert.notEqual(out.steps[0].durationS, 200);
    assert.equal(out.steps.reduce((n, s) => n + s.durationS, 0), out.durationS);
  });
});

const TRIP = { id: 'trip-basis', startedAt: 1_790_000_000_000, distanceMeters: 4000, durationSeconds: 900, endReason: 'arrived' };

describe('engineBasis : forme serveur et compatibilité', () => {
  it('raw gardé seulement avec engineLeftS valide ; ancien payload décodé, base null', async () => {
    const shaped = shapeTrip({
      ...TRIP,
      etaChecks: [
        { at: 0, shownAt: 1_790_000_001_000, arrivalAt: 1_790_000_600_000, engineLeftS: 480, engineBasis: 'raw' },
        { at: 25, shownAt: 1_790_000_200_000, arrivalAt: 1_790_000_700_000, engineLeftS: null, engineBasis: 'raw' },
        { at: 50, shownAt: 1_790_000_300_000, arrivalAt: 1_790_000_800_000, engineLeftS: 300, engineBasis: 'eona' },
        { at: 75, shownAt: 1_790_000_400_000, arrivalAt: 1_790_000_850_000, engineLeftS: 200 },
      ],
      timeline: [
        { t: 1_790_000_060_000, drivenM: 300, leftS: 540, engineLeftS: 480, engineBasis: 'raw' },
        { t: 1_790_000_120_000, drivenM: 700, leftS: 500, engineLeftS: 'x', engineBasis: 'raw' },
        { t: 1_790_000_180_000, drivenM: 900, leftS: 450, engineLeftS: 400 },
      ],
    });
    assert.deepEqual(shaped.etaChecks.map((c) => c.engineBasis), ['raw', null, null, null]);
    assert.deepEqual(shaped.timeline.map((p) => p.engineBasis), ['raw', null, null]);
    // Renvoi depuis file ou reprise : même forme, marqueur conservé.
    const again = shapeTrip(JSON.parse(JSON.stringify(shaped)));
    assert.deepEqual(again.etaChecks, shaped.etaChecks);
    assert.deepEqual(again.timeline, shaped.timeline);
    let saved;
    await createTripLog({ query: async (_sql, params) => { saved = params; } }).save('account', shaped);
    assert.equal(JSON.parse(saved.at(-1)).timeline[0].engineBasis, 'raw');

    const legacy = shapeTrip({ ...TRIP, etaChecks: [{ at: 0, shownAt: 1_790_000_001_000, arrivalAt: 1_790_000_600_000, engineLeftS: 480 }] });
    assert.equal(legacy.etaChecks[0].engineLeftS, 480);
    assert.equal(legacy.etaChecks[0].engineBasis, null);
  });
});

const START = Date.parse('2026-10-15T06:00:00Z');
const reportTrip = (timeline) => ({
  id: 't', startedAt: START, durationSeconds: 1000, distanceMeters: 10000, departedAt: START,
  firstMoveAt: START + 60000, endReason: 'arrived', arrived: true, platform: 'ios', appVersion: '56',
  etaChecks: [{ at: 0, shownAt: START, arrivalAt: START + 800000, base: 'here' }], timeline,
});

describe('rapport v2 : réel/moteur seulement sur base brute', () => {
  it('ancien relevé exclu du ratio moteur et compté ; nouveau compte ; réel/affiché inchangé', () => {
    const legacy = { t: START, drivenM: 0, leftM: 1000, leftS: 500, engineLeftS: 250, base: 'here', kmh: 20 };
    const raw = { t: START + 500000, drivenM: 500, leftM: 500, leftS: 250, engineLeftS: 125, base: 'here', kmh: 20, engineBasis: 'raw' };
    const onlyLegacyEngine = { t: START + 750000, drivenM: 750, leftM: 250, engineLeftS: 100, base: 'engine', kmh: 20 };
    const counts = { raw: 0, unknown: 0 };
    const samples = timelineSamples(reportTrip([legacy, raw, onlyLegacyEngine]), counts);
    assert.deepEqual(samples.map((s) => [s.displayedRatio, s.engineRatio]), [[2, null], [2, 4]]);
    assert.deepEqual(counts, { raw: 1, unknown: 2 });

    const result = report([reportTrip([legacy, raw, onlyLegacyEngine])]);
    assert.deepEqual(result.timeline.engineBasis, { raw: 1, unknown: 2 });
    assert.equal(result.timeline.used, 2);
    const all = result.timeline.byBase.find((g) => g.base === 'here');
    assert.equal(all.displayedRatio.median, 2);
    assert.equal(all.displayedRatio.n, 2);
    assert.equal(all.engineRatio.n, 1);
    assert.equal(all.engineRatio.median, 4);
    assert.match(formatReport(result), /base moteur inconnue=2/);

    // Uniquement anciennes mesures : aucun ratio moteur, réel/affiché identique.
    const old = report([reportTrip([legacy])]);
    assert.equal(old.timeline.byBase[0].engineRatio.n, 0);
    assert.equal(old.timeline.byBase[0].displayedRatio.median, 2);
  });
});
