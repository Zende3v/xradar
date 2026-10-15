import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it, mock } from 'node:test';
import { createApp } from '../src/server.js';
import { accountStore, shapeTrip, tripSummary } from '../src/accounts/store.js';
import { createTripLog, traceShape, tripLog, TRACE_MAX_POINTS, TRIP_MIN_METERS, TRIP_MIN_SECONDS } from '../src/accounts/trip-log.js';
import { db } from '../src/db.js';
import { call, trapNetwork } from './helpers/routing.js';

const timestamp = 1_790_000_000_000;
const point = [timestamp, 48.85, 2.35, 32, 5, 120];
const base = { id: 'trip-log-long', startedAt: timestamp, distanceMeters: 4000, durationSeconds: 900, alertsCount: 2 };

describe('paquets GPS', () => {
  it('accepte bornes et conserve entrée intacte', () => {
    const body = { seq: 0, points: [[timestamp, -90, -180, 0, 0, 0], [timestamp + 1, 90, 180, 400, 5, 360]] };
    const before = structuredClone(body);
    assert.deepEqual(traceShape(body), before);
    assert.deepEqual(body, before);
    assert.equal(traceShape({ ...body, seq: 999 }).seq, 999);
    assert.equal(TRACE_MAX_POINTS, 1000);
    assert.equal(TRIP_MIN_METERS, 500);
    assert.equal(TRIP_MIN_SECONDS, 60);
  });

  it('remplace seulement métadonnées invalides par null', () => {
    for (const extra of [[-1, -1, -1], [401, Infinity, 361], ['30', '5', '120'], [null, null, null], []]) {
      assert.deepEqual(traceShape({ seq: 1, points: [[timestamp, 0, 0, ...extra]] }).points, [[timestamp, 0, 0, null, null, null]]);
    }
  });

  it('refuse séquence invalide et nombre de points hors limites', () => {
    for (const seq of [-1, 1000, 0.5, '0', null, undefined, NaN, Infinity]) {
      assert.ok(traceShape({ seq, points: [point] }).error);
    }
    assert.ok(traceShape(null).error);
    for (const points of [null, [], {}, 'points', Array.from({ length: TRACE_MAX_POINTS + 1 }, () => point)]) {
      assert.ok(traceShape({ seq: 0, points }).error);
    }
    assert.equal(traceShape({ seq: 0, points: Array.from({ length: TRACE_MAX_POINTS }, () => point) }).points.length, TRACE_MAX_POINTS);
  });

  it('ignore points invalides ; refuse paquet sans point valide', () => {
    const invalid = [null, {}, [], [0, 0, 0], [-1, 0, 0], [1.5, 0, 0], ['1', 0, 0], [Infinity, 0, 0],
      [timestamp, null, 0], [timestamp, '48', 0], [timestamp, NaN, 0], [timestamp, 90.1, 0],
      [timestamp, -90.1, 0], [timestamp, 0, 180.1], [timestamp, 0, -180.1], [timestamp, 0, Infinity]];
    assert.deepEqual(traceShape({ seq: 2, points: [...invalid, point] }), { seq: 2, points: [point] });
    assert.ok(traceShape({ seq: 2, points: invalid }).error);
  });
});

describe('journal PostgreSQL injectable', () => {
  it('écrit mesures et traces liées au compte, sans dépendre de leur ordre', async () => {
    const calls = [];
    const log = createTripLog({ query: async (sql, params) => { calls.push({ sql, params }); } });
    const record = shapeTrip({ ...base, platform: 'ios', appVersion: '55', arrived: true, endReason: 'near', lat: 48, lon: 2 });
    await log.saveTrace('owner', record.id, 4, [point]);
    await log.save('owner', record);
    assert.match(calls[0].sql, /INSERT INTO crowd.trip_trace/);
    assert.match(calls[0].sql, /ON CONFLICT \(account_id, trip_id, seq\) DO NOTHING/);
    assert.deepEqual(calls[0].params, ['owner', record.id, 4, JSON.stringify([point])]);
    assert.match(calls[1].sql, /INSERT INTO crowd.trip\s/);
    assert.match(calls[1].sql, /ON CONFLICT \(account_id, trip_id\) DO NOTHING/);
    assert.deepEqual(calls[1].params, ['owner', record.id, new Date(timestamp), 'ios', '55', 4000, 900, true, 'near', JSON.stringify(record)]);
    assert.equal('lat' in JSON.parse(calls[1].params[9]), false);
    assert.equal('lon' in JSON.parse(calls[1].params[9]), false);
    await log.save('other-owner', shapeTrip(base));
    assert.deepEqual(calls[2].params.slice(3, 9), [null, null, 4000, 900, null, null]);
  });

  it('attend écriture et propage erreurs de stockage', async () => {
    let release;
    const pending = new Promise((resolve) => { release = resolve; });
    const log = createTripLog({ query: () => pending });
    let done = false;
    const saving = log.save('owner', shapeTrip(base)).then(() => { done = true; });
    await Promise.resolve();
    assert.equal(done, false);
    release();
    await saving;
    assert.equal(done, true);
    const failure = new Error('base indisponible');
    const unavailable = createTripLog({ query: async () => { throw failure; } });
    await assert.rejects(unavailable.save('owner', shapeTrip(base)), (error) => error === failure);
    await assert.rejects(unavailable.saveTrace('owner', base.id, 0, [point]), (error) => error === failure);
  });
});

trapNetwork(before, after);
let server;
let sequence = 0;
before(async () => {
  mock.method(accountStore, 'scheduleSave', () => {});
  mock.method(accountStore, 'save', async () => {});
  mock.method(db, 'query', async () => { throw new Error('base réelle interdite'); });
  server = createApp().listen(0, '127.0.0.1');
  await once(server, 'listening');
});
after(async () => {
  if (server) await new Promise((resolve) => server.close(resolve));
  mock.restoreAll();
});

function fixture(t, { save = async () => {}, saveTrace = async () => {} } = {}) {
  const account = accountStore.claimGuest('phone-trip-log-' + ++sequence).account;
  const headers = { authorization: 'Bearer ' + accountStore.issueToken(account.id) };
  const saved = t.mock.method(tripLog, 'save', save);
  const traced = t.mock.method(tripLog, 'saveTrace', saveTrace);
  return { account, headers, saved, traced };
}

describe('collecte HTTP trajets et traces', () => {
  it('stocke tous trajets courts sans modifier historique ni totaux', async (t) => {
    const { account, headers, saved } = fixture(t);
    accountStore.recordTrip(account.id, { ...base, id: 'existing-trip' });
    const before = accountStore.statsFor(account.id);
    for (const [distanceMeters, durationSeconds] of [[499, 60], [500, 59], [0, 0]]) {
      const body = { ...base, id: 'short-' + distanceMeters, distanceMeters, durationSeconds,
        timeline: [{ t: timestamp, drivenM: 100, lat: 48, lon: 2 }], coordinates: [[2, 48]] };
      const response = await call(server, 'POST', '/api/accounts/me/trips', { headers, body });
      assert.equal(response.status, 201);
      assert.equal(response.json.counted, false);
      assert.deepEqual(response.json.trip, tripSummary(shapeTrip(body)));
      assert.deepEqual(response.json.stats, before.totals);
    }
    assert.equal(saved.mock.calls.length, 3);
    assert.equal(saved.mock.calls[0].arguments[0], account.id);
    assert.equal('coordinates' in saved.mock.calls[0].arguments[1], false);
    assert.equal('lat' in saved.mock.calls[0].arguments[1].timeline[0], false);
    assert.deepEqual(accountStore.statsFor(account.id), before);
  });

  it('compte trajet aux deux seuils après stockage ; doublon sans cumul', async (t) => {
    let owner;
    const { account, headers, saved } = fixture(t, { save: async () => {
      assert.equal(owner.stats.tripCount, 0);
      assert.equal(owner.trips.length, 0);
    } });
    owner = account;
    const body = { ...base, distanceMeters: 500, durationSeconds: 60 };
    const response = await call(server, 'POST', '/api/accounts/me/trips', { headers, body });
    assert.equal(response.status, 201);
    assert.equal(response.json.counted, true);
    assert.deepEqual(response.json.trip, tripSummary(shapeTrip(body)));
    assert.equal(response.json.stats.tripCount, 1);
    assert.equal(response.json.stats.alertsTraversed, 2);
    assert.equal(account.trips.length, 1);
    // Nouvelle réception : PostgreSQL ignore doublon, historique reste idempotent.
    saved.mock.mockImplementation(async () => {});
    const again = await call(server, 'POST', '/api/accounts/me/trips', { headers, body });
    assert.equal(again.status, 201);
    assert.deepEqual(again.json, response.json);
    assert.equal(saved.mock.calls.length, 2);
    assert.equal(account.trips.length, 1);
  });

  it('statistiques coupées : trajet long stocké, ni historique ni totaux', async (t) => {
    const { account, headers, saved } = fixture(t);
    const response = await call(server, 'POST', '/api/accounts/me/trips', { headers, body: { ...base, id: 'no-stats', countInStats: false } });
    assert.equal(response.status, 201);
    assert.equal(response.json.counted, false);
    assert.equal(saved.mock.calls.length, 1);
    assert.equal(account.trips.length, 0);
    assert.equal(account.stats.tripCount, 0);
  });

  it('collecte aussi membres et administrateurs', async (t) => {
    const { saved } = fixture(t);
    for (const role of ['client', 'admin']) {
      const name = 'LogOwner' + ++sequence;
      const { account } = accountStore.create({ role, username: name, email: name + '@example.test', password: 'password-123' });
      const headers = { authorization: 'Bearer ' + accountStore.issueToken(account.id) };
      assert.equal((await call(server, 'POST', '/api/accounts/me/trips', { headers, body: base })).status, 201);
      assert.equal(saved.mock.calls.at(-1).arguments[0], account.id);
    }
  });

  it('refuse sans compte et trajet illisible avant stockage', async (t) => {
    const { account, headers, saved } = fixture(t);
    assert.equal((await call(server, 'POST', '/api/accounts/me/trips', { body: base })).status, 401);
    const forged = { authorization: 'Bearer invalid' };
    assert.equal((await call(server, 'POST', '/api/accounts/me/trips', { headers: forged, body: { ...base, deviceId: account.deviceId } })).status, 401);
    for (const body of [null, {}, { ...base, id: '' }, { ...base, startedAt: 'illisible' }, { ...base, durationSeconds: 'illisible' },
      { ...base, startedAt: { valueOf: null, toString: null } }, { ...base, fromLabel: { toString: null } }]) {
      assert.equal((await call(server, 'POST', '/api/accounts/me/trips', { headers, body })).status, 400);
    }
    assert.equal(saved.mock.calls.length, 0);
    assert.equal(account.stats.tripCount, 0);
    assert.equal(account.trips.length, 0);
  });

  it('base indisponible : 503, aucun comptage, aucun détail privé journalisé', async (t) => {
    const failure = 'SQL privé [48.85,2.35]';
    const errors = t.mock.method(console, 'error', () => {});
    let unavailable = true;
    const { account, headers } = fixture(t, { save: async () => { if (unavailable) throw new Error(failure); } });
    const before = accountStore.statsFor(account.id);
    for (const distanceMeters of [4000, 10]) {
      const response = await call(server, 'POST', '/api/accounts/me/trips', { headers, body: { ...base, distanceMeters } });
      assert.deepEqual(response, { status: 503, json: { error: 'trip storage unavailable' } });
    }
    assert.deepEqual(accountStore.statsFor(account.id), before);
    assert.equal(errors.mock.calls.length, 2);
    assert.equal(JSON.stringify(errors.mock.calls.map((entry) => entry.arguments)).includes(failure), false);
    unavailable = false;
    assert.equal((await call(server, 'POST', '/api/accounts/me/trips', { headers, body: base })).status, 201);
    assert.equal(account.stats.tripCount, 1);
  });

  it('trace reçue avant ou après trajet ; nombre de points valides renvoyé', async (t) => {
    const { account, headers, saved, traced } = fixture(t);
    const uri = '/api/accounts/me/trips/' + base.id + '/trace';
    const response = await call(server, 'POST', uri, { headers, body: { seq: 999, points: [null, point, [timestamp + 1, 0, 0, -1, -1, 361]] } });
    assert.deepEqual(response, { status: 201, json: { stored: 2 } });
    assert.deepEqual(traced.mock.calls[0].arguments, [account.id, base.id, 999, [point, [timestamp + 1, 0, 0, null, null, null]]]);
    assert.equal(saved.mock.calls.length, 0);
    assert.equal(account.trips.length, 0);
    await call(server, 'POST', '/api/accounts/me/trips', { headers, body: base });
    assert.equal((await call(server, 'POST', uri, { headers, body: { seq: 0, points: [point] } })).status, 201);
    assert.equal(traced.mock.calls.length, 2);
    assert.equal(account.stats.tripCount, 1);
  });

  it('refuse identifiant ou trace invalide et compte absent', async (t) => {
    const { account, headers, traced } = fixture(t);
    const body = { seq: 0, points: [point] };
    const uri = '/api/accounts/me/trips/' + base.id + '/trace';
    assert.equal((await call(server, 'POST', uri, { body })).status, 401);
    assert.equal((await call(server, 'POST', uri, { headers: { authorization: 'Bearer invalid' }, body: { ...body, deviceId: account.deviceId } })).status, 401);
    for (const id of ['short', 'invalid.id', 'a'.repeat(129)]) {
      assert.equal((await call(server, 'POST', '/api/accounts/me/trips/' + id + '/trace', { headers, body })).status, 400);
    }
    for (const invalid of [{ ...body, seq: -1 }, { ...body, seq: '0' }, { seq: 0, points: [] }, { seq: 0, points: [[timestamp, 91, 0]] }]) {
      assert.equal((await call(server, 'POST', uri, { headers, body: invalid })).status, 400);
    }
    assert.equal(traced.mock.calls.length, 0);
  });

  it('trace : base indisponible renvoie 503 sans coordonnée dans logs', async (t) => {
    const errors = t.mock.method(console, 'error', () => {});
    const { account, headers } = fixture(t, { saveTrace: async () => { throw new Error(JSON.stringify(point)); } });
    const response = await call(server, 'POST', '/api/accounts/me/trips/' + base.id + '/trace', { headers, body: { seq: 0, points: [point] } });
    assert.deepEqual(response, { status: 503, json: { error: 'trip storage unavailable' } });
    assert.deepEqual(errors.mock.calls.map((entry) => entry.arguments), [['[accounts] stockage trace indisponible']]);
    assert.equal(account.stats.tripCount, 0);
  });

  it('accepte mesures et traces dépassant 16 ko ; limites ailleurs conservées', async (t) => {
    const { headers, saved, traced } = fixture(t);
    const body = { ...base, timeline: Array.from({ length: 240 }, (_, i) => ({
      t: timestamp + i * 60_000, drivenM: i * 20, leftM: 4000, leftS: 900, engineLeftS: 900, base: 'engine', kmh: 32,
    })) };
    assert.ok(Buffer.byteLength(JSON.stringify(body)) > 16 * 1024);
    assert.equal((await call(server, 'POST', '/api/accounts/me/trips', { headers, body })).status, 201);
    const trace = { seq: 0, points: Array.from({ length: TRACE_MAX_POINTS }, (_, i) => [timestamp + i, ...point.slice(1)]) };
    assert.ok(Buffer.byteLength(JSON.stringify(trace)) > 16 * 1024);
    const uri = '/api/accounts/me/trips/' + base.id + '/trace';
    assert.equal((await call(server, 'POST', uri, { headers, body: trace })).status, 201);
    const large = { padding: 'x'.repeat(1024 * 1024) };
    // Erreurs Express capturées localement, sans journal parasite.
    const errors = t.mock.method(console, 'error', () => {});
    for (const endpoint of ['/api/accounts/me/trips', uri]) {
      assert.equal((await call(server, 'POST', endpoint, { headers, body: large })).status, 413);
    }
    assert.equal((await call(server, 'POST', '/api/accounts/me/drive', { headers, body: { padding: 'x'.repeat(17_000) } })).status, 413);
    assert.equal(saved.mock.calls.length, 1);
    assert.equal(traced.mock.calls.length, 1);
    errors.mock.restore();
  });

  it('suppression propriétaire et admin efface journal et GPS dans transaction', async (t) => {
    const queries = [];
    const client = { query: async (sql, params) => { queries.push({ sql, params }); return { rows: [] }; }, release() {} };
    t.mock.method(db, 'connect', async () => client);
    t.mock.method(db, 'query', async () => ({ rows: [] }));
    const { account, headers } = fixture(t);
    assert.equal((await call(server, 'DELETE', '/api/accounts/me', { headers })).status, 200);
    assert.equal(accountStore.get(account.id), null);
    const target = accountStore.claimGuest('phone-delete-log-' + ++sequence).account;
    const name = 'DeleteLog' + sequence;
    const admin = accountStore.create({ role: 'admin', username: name, email: name + '@example.test', password: 'password-123' }).account;
    const adminHeaders = { authorization: 'Bearer ' + accountStore.issueToken(admin.id) };
    assert.equal((await call(server, 'DELETE', '/api/admin/accounts/' + target.id, { headers: adminHeaders })).status, 200);
    assert.equal(accountStore.get(target.id), null);
    for (const id of [account.id, target.id]) {
      for (const table of ['trip', 'trip_trace']) {
        assert.ok(queries.some((query) => query.sql === 'DELETE FROM crowd.' + table + ' WHERE account_id = $1' && query.params[0] === id));
      }
    }
    assert.equal(queries.filter((query) => query.sql === 'BEGIN').length, 2);
    assert.equal(queries.filter((query) => query.sql === 'COMMIT').length, 2);
  });
});
