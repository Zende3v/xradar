import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, test } from 'node:test';
import express from 'express';
import { call } from './helpers/routing.js';

const dir = mkdtempSync(join(tmpdir(), 'eona-dashboard-'));
Object.assign(process.env, {
  ACCOUNTS_FILE: join(dir, 'accounts.json'), DEVICE_TRIALS_FILE: join(dir, 'trials.json'),
  SETTINGS_FILE: join(dir, 'settings.json'), HERE_USAGE_FILE: join(dir, 'here.json'),
  ADMIN_TOKEN: 'dashboard-script-token',
});
const { dashboardPresence, dashboardTrips, dashboardRoutingStats, createDashboardRouter } = await import('../src/admin/dashboard.js');
const { accountStore } = await import('../src/accounts/store.js');

const now = Date.now();
const accounts = [
  { id: 'guest', role: 'guest', plus: false, trips: [] },
  { id: 'free', role: 'client', plus: false, trips: [] },
  { id: 'trial', role: 'client', plus: true, trips: [] },
  { id: 'paid', role: 'client', plus: true, trips: [] },
  { id: 'admin', role: 'admin', plus: true, trips: [] },
  { id: 'banned', role: 'client', plus: false, banned: true, trips: [] },
  { id: 'suspended', role: 'client', plus: false, suspended: true, trips: [] },
];
const store = {
  list: () => accounts,
  hasPlus: (account) => account.plus,
  accessFor: (account) => ({ canNavigate: !account.banned && !account.suspended }),
  blockReason: (account) => account.banned ? 'banned' : account.suspended ? 'suspended' : null,
};

test('Présence répartie par droits, TTL et exclusions', () => {
  const presence = dashboardPresence(accounts, [
    ['guest', { at: now, inTrip: false }], ['trial', { at: now, inTrip: true }],
    ['paid', { at: now - 100_000, inTrip: true }], ['admin', { at: now, inTrip: true }],
    ['banned', { at: now, inTrip: true }], ['suspended', { at: now, inTrip: true }],
    ['deleted', { at: now, inTrip: true }],
  ], store);
  assert.deepEqual(presence.groups.map(({ id, total, online, offline, inTrip }) => ({ id, total, online, offline, inTrip })), [
    { id: 'free', total: 4, online: 1, offline: 3, inTrip: 0 },
    { id: 'client', total: 2, online: 1, offline: 1, inTrip: 1 },
    { id: 'admin', total: 1, online: 1, offline: 0, inTrip: 1 },
  ]);
  assert.equal(presence.online, 3);
  assert.equal(presence.offline, 4);
  assert.equal(presence.inTrip, 2);
  assert.equal(presence.ttlSeconds, 90);
});

test('Trajets anonymes, ordre stable et pagination', () => {
  const source = [{
    id: 'secret-account', email: 'secret@example.invalid', displayName: 'Secret', passwordHash: 'hash', platform: 'ios',
    trips: [
      { id: 'trip1', startedAt: 1000, fromLabel: 'Maison privée', toLabel: 'Adresse privée', distanceMeters: 100, durationSeconds: 10, arrived: true, engines: ['valhalla', 'secret-engine'], platform: 'android', coordinates: [[1, 2]] },
      { id: 'trip2', startedAt: 2000, distanceMeters: 200, durationSeconds: 20 },
    ],
  }, { id: 'other-account', trips: [{ id: 'trip2', startedAt: 2000, distanceMeters: 300, durationSeconds: 30 }] }];
  const full = dashboardTrips(source);
  assert.deepEqual(full.trips.map((trip) => trip.startedAt), [2000, 2000, 1000]);
  assert.equal(new Set(full.trips.map((trip) => trip.id)).size, 3);
  assert.deepEqual(dashboardTrips(source).trips, full.trips);
  assert.deepEqual(Object.keys(full.trips[0]).sort(), ['arrived', 'distanceMeters', 'durationSeconds', 'engines', 'id', 'platform', 'startedAt']);
  for (const privateValue of ['secret-account', 'other-account', 'secret@example.invalid', 'Secret', 'hash', 'Maison privée', 'Adresse privée', 'coordinates', 'endedAt']) {
    assert.equal(JSON.stringify(full).includes(privateValue), false, privateValue);
  }
  assert.deepEqual(full.trips[2].engines, ['valhalla']);
  assert.equal(full.trips[2].platform, 'android');
  assert.equal(full.trips[0].platform, null);
  assert.equal(full.trips[0].arrived, null);
  const first = dashboardTrips(source, { limit: 1 });
  assert.equal(first.total, 3);
  assert.equal(first.next, 1);
  const rest = dashboardTrips(source, { limit: 2, offset: first.next });
  assert.deepEqual([...first.trips, ...rest.trips], full.trips);
  assert.equal(rest.next, null);
  assert.equal(dashboardTrips(source, { limit: '-1', offset: '-10' }).offset, 0);
  assert.equal(full.retentionPerAccount, 200);
  const malformed = dashboardTrips([{ id: 'legacy', trips: [{ id: 'bad', startedAt: 1000, distanceMeters: 1e25, durationSeconds: Infinity }] }]);
  assert.equal(malformed.trips[0].distanceMeters, Number.MAX_SAFE_INTEGER);
  assert.equal(malformed.trips[0].durationSeconds, 0);
});

test('Mesures Valhalla réelles, semaine Paris, base indisponible distincte de zéro', async () => {
  const queries = [];
  const database = { query: async (sql, params) => {
    queries.push({ sql, params });
    return { rows: [{ total_requests: '12', total_errors: '2', cache_hits: '4', week_requests: '5', week_errors: '1', median_ms: 23.5, p95_ms: 71.7 }] };
  } };
  const measured = await dashboardRoutingStats(database, Date.parse('2026-10-05T10:00:00Z'));
  assert.equal(measured.available, true);
  assert.equal(measured.totalRequests, 12);
  assert.equal(measured.weekRequests, 5);
  assert.equal(measured.totalErrors, null);
  assert.equal(measured.weekErrors, null);
  assert.equal(measured.errorAttributionAvailable, false);
  assert.equal(measured.p95Ms, 71.7);
  assert.equal(measured.latencyIncludesCache, true);
  assert.match(queries[0].sql, /engine = 'valhalla'/);
  assert.deepEqual(queries[0].params, [90, '2026-10-04T22:00:00.000Z', '2026-10-05T10:00:00.000Z']);
  const unavailable = await dashboardRoutingStats({ query: async () => { throw new Error('offline'); } });
  assert.equal(unavailable.available, false);
  assert.equal(unavailable.totalRequests, null);
  const empty = await dashboardRoutingStats({ query: async () => ({ rows: [{ total_requests: '0', total_errors: '0', cache_hits: '0', week_requests: '0', week_errors: '0', median_ms: null, p95_ms: null }] }) });
  assert.equal(empty.available, true);
  assert.equal(empty.totalRequests, 0);
  assert.equal(empty.medianMs, null);
});

const admin = accountStore.auth('dashboard-admin-device');
admin.role = 'admin';
const adminToken = accountStore.issueToken(admin.id);
const guest = accountStore.auth('dashboard-guest-device');
const guestToken = accountStore.issueToken(guest.id);
const app = express();
app.use('/api/admin', createDashboardRouter({
  store, live: { entries: () => [] }, database: { query: async () => { throw new Error('offline'); } },
  engine: { health: () => ({ provider: 'valhalla', mode: 'all', valhalla: { state: 'ready' }, fallbacks: {} }) },
  usage: () => ({ monthUsed: 8, estimateOnly: true, history: { totalRequests: 8 } }),
}));
const server = app.listen(0, '127.0.0.1');
await once(server, 'listening');
after(() => {
  clearTimeout(accountStore.saveTimer);
  return new Promise((resolve) => server.close(resolve));
});

test('Panel refuse invité, faux Bearer et appareil admin ; accepte vraie session admin', async () => {
  for (const path of ['/api/admin/overview', '/api/admin/trips']) {
    for (const headers of [{}, { authorization: `Bearer ${guestToken}` }, { authorization: 'Bearer forged' }]) {
      assert.equal((await call(server, 'GET', `${path}?deviceId=dashboard-admin-device`, { headers })).status, 401);
    }
    const result = await call(server, 'GET', path, { headers: { authorization: `Bearer ${adminToken}` } });
    assert.equal(result.status, 200);
  }
  const result = await call(server, 'GET', '/api/admin/overview', { headers: { authorization: `Bearer ${adminToken}` } });
  assert.equal(result.json.accounts.total, 7);
  assert.equal(result.json.routingStats.available, false);
  assert.equal(result.json.routingStats.totalRequests, null);
  assert.equal(result.json.here.history.totalRequests, 8);
});
