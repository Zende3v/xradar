import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { AccountStore, shapeTrip, tripSummary } from '../src/accounts/store.js';

const store = () => {
  const result = new AccountStore();
  result.scheduleSave = () => {};
  return result;
};

const base = { id: 'trip-1', startedAt: 1_790_000_000_000, distanceMeters: 4000, durationSeconds: 900, etaMode: 'dynamic' };

describe('collecte trajets lot 1', () => {
  it('garde fin, premier mouvement, relevés minute et arrêts, filtrés', () => {
    const accounts = store();
    const { account } = accounts.claimGuest('phone-collect');
    const result = accounts.recordTrip(account.id, {
      ...base,
      arrived: true,
      firstMoveAt: 1_790_000_030_000,
      lastFixAt: 1_790_000_900_000,
      endReason: 'near',
      endDistanceM: 182.6,
      etaChecks: [{ at: 0, shownAt: 1_790_000_001_000, arrivalAt: 1_790_000_600_000, base: 'here', engineLeftS: 512.4 }],
      timeline: [
        { t: 1_790_000_060_000, drivenM: 300, leftM: 3700, leftS: 540, engineLeftS: 480, base: 'engine', kmh: 32 },
        { t: 0, drivenM: 1 },
        { t: 1_790_000_120_000, drivenM: 700, base: 'lune', kmh: 999 },
      ],
      stopLog: [{ at: 1_790_000_200_000, seconds: 42, drivenM: 900, jam: true }, { at: 1, seconds: 0 }],
    });
    assert.equal(result.error, undefined);
    // Réponse légère : relevés et arrêts absents.
    assert.equal(result.trip.timeline, undefined);
    assert.equal(result.trip.stopLog, undefined);
    assert.equal(result.trip.endReason, 'near');
    assert.equal(result.trip.endDistanceM, 183);
    assert.equal(result.trip.firstMoveAt, 1_790_000_030_000);
    assert.deepEqual(result.trip.etaChecks[0].base, 'here');
    assert.equal(result.trip.etaChecks[0].engineLeftS, 512);

    const stored = accounts.get(account.id).trips[0];
    assert.deepEqual(stored.timeline, [
      { t: 1_790_000_060_000, drivenM: 300, leftM: 3700, leftS: 540, engineLeftS: 480, engineBasis: null, base: 'engine', kmh: 32 },
      { t: 1_790_000_120_000, drivenM: 700, leftM: null, leftS: null, engineLeftS: null, engineBasis: null, base: null, kmh: 300 },
    ]);
    assert.deepEqual(stored.stopLog, [{ at: 1_790_000_200_000, seconds: 42, drivenM: 900, jam: true }]);
    assert.equal(accounts.statsFor(account.id).trips[0].timeline, undefined);

    // Même identifiant renvoyé (file d'envoi) : rien ajouté.
    const again = accounts.recordTrip(account.id, { ...base, endReason: 'stopped' });
    assert.equal(again.trip.endReason, 'near');
    assert.equal(accounts.get(account.id).trips.length, 1);
  });

  it('valeurs illisibles : null, jamais inventées', () => {
    const accounts = store();
    const { account } = accounts.claimGuest('phone-collect-2');
    const { trip } = accounts.recordTrip(account.id, { ...base, endReason: 'crash', endDistanceM: 'loin', timeline: 'x', stopLog: null });
    assert.equal(trip.endReason, null);
    assert.equal(trip.endDistanceM, null);
    assert.equal(trip.firstMoveAt, null);
    assert.deepEqual(accounts.get(account.id).trips[0].timeline, []);
    assert.deepEqual(accounts.get(account.id).trips[0].stopLog, []);
  });
  it('mise en forme pure : contrat historique conservé, coordonnées exclues', () => {
    const accounts = store();
    const { account } = accounts.claimGuest('phone-pure-shape');
    const body = {
      ...base, distanceMeters: '4000.4', durationSeconds: '900.4', lat: 48, lon: 2, coordinates: [[2, 48]],
      timeline: [{ t: base.startedAt, drivenM: 10, lat: 48, lon: 2 }],
      stopLog: [{ at: base.startedAt, seconds: 12, drivenM: 10, jam: false, coordinates: [2, 48] }],
      etaChecks: [{ at: 0, shownAt: base.startedAt, arrivalAt: base.startedAt + 900_000, lat: 48, lon: 2 }],
    };
    const before = structuredClone(body);
    const record = shapeTrip(body);
    assert.deepEqual(body, before);
    assert.equal(account.trips.length, 0);
    assert.equal('coordinates' in record, false);
    for (const item of [record, record.timeline[0], record.stopLog[0], record.etaChecks[0]]) {
      assert.equal('lat' in item, false);
      assert.equal('lon' in item, false);
    }
    const result = accounts.recordTrip(account.id, body);
    assert.deepEqual(account.trips[0], record);
    assert.deepEqual(result.trip, tripSummary(record));
    assert.equal(result.stats.tripCount, 1);
    const legacy = shapeTrip({ id: 'old-trip', startedAt: base.startedAt, distanceMeters: 500, durationSeconds: 60 });
    assert.equal('arrived' in legacy, false);
    assert.equal('timeline' in legacy, false);
  });

  it('trajet illisible : même refus pur et historique inchangé', () => {
    const accounts = store();
    const { account } = accounts.claimGuest('phone-invalid-shape');
    for (const body of [null, {}, { ...base, id: '' }, { ...base, distanceMeters: 'loin' }]) {
      assert.deepEqual(shapeTrip(body), { error: 'invalid trip' });
      assert.deepEqual(accounts.recordTrip(account.id, body), { error: 'invalid trip' });
    }
    assert.equal(account.trips.length, 0);
    assert.equal(account.stats.tripCount, 0);
  });

});
