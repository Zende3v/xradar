import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { AccountStore } from '../src/accounts/store.js';

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
      { t: 1_790_000_060_000, drivenM: 300, leftM: 3700, leftS: 540, engineLeftS: 480, base: 'engine', kmh: 32 },
      { t: 1_790_000_120_000, drivenM: 700, leftM: null, leftS: null, engineLeftS: null, base: null, kmh: 300 },
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
});
