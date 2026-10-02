import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { report } from '../bin/eona-eta-report.js';

// --since : bilan des seuls trajets partis après un correctif ETA.
describe('rapport ETA', () => {
  it('garde seulement les trajets partis depuis --since', () => {
    const at = Date.parse('2026-10-01T12:25:00Z');
    const trip = (startedAt) => ({ startedAt, durationSeconds: 600, arrived: false });
    const accounts = [{ trips: [trip(at - 60_000), trip(at), trip(at + 60_000)] }, { trips: [trip(at - 1)] }];
    assert.equal(report(accounts).trips.read, 4);
    const since = report(accounts, { since: at });
    assert.equal(since.trips.read, 2);
    assert.equal(since.since, '2026-10-01T12:25:00.000Z');
    assert.equal(report(accounts).since, null);
  });
});
