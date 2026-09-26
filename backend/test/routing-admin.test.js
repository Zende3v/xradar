import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, describe, it } from 'node:test';
import { call, trapNetwork } from './helpers/routing.js';

// routingEngine in settingsStore, the console's routing routes and /health, on the real
// Express app: settings in a temporary folder, a fake database, no network, Valhalla off.
const dir = mkdtempSync(join(tmpdir(), 'eona-routing-'));
Object.assign(process.env, {
  SETTINGS_FILE: join(dir, 'settings.json'),
  ACCOUNTS_FILE: join(dir, 'accounts.json'),
  DEVICE_TRIALS_FILE: join(dir, 'device-trials.json'),
  ORS_USAGE_FILE: join(dir, 'ors-usage.json'),
  TOMTOM_USAGE_FILE: join(dir, 'tomtom-usage.json'),
  ADMIN_TOKEN: 'test-admin-token',
});
delete process.env.VALHALLA_ENABLED;
const net = trapNetwork(before, after);

const { db } = await import('../src/db.js');
const queries = [];
db.query = async (sql, params) => {
  queries.push({ sql, params });
  return { rows: [] };
};
const { ROUTING_ENGINES, SettingsStore, settingsStore } = await import('../src/accounts/settings.js');
const { createApp } = await import('../src/server.js');

const app = createApp();
const server = app.listen(0, '127.0.0.1');
await once(server, 'listening');
after(() => new Promise((resolve) => server.close(resolve)));
const admin = { 'x-admin-token': 'test-admin-token' };

describe('routingEngine setting', () => {
  it('is ors by default, admins and all ready, anything else refused', () => {
    const store = new SettingsStore();
    assert.equal(store.routingEngine, 'ors');
    assert.deepEqual(ROUTING_ENGINES, ['ors', 'admins', 'all']);
    assert.equal(store.setRoutingEngine('valhalla', 'test'), null);
    assert.equal(store.setRoutingEngine('', 'test'), null);
    assert.equal(store.routingEngine, 'ors');
    assert.equal(store.history.length, 0);
    assert.deepEqual(store.setRoutingEngine('admins', 'arthur'), { before: 'ors', after: 'admins' });
    assert.equal(store.routingEngine, 'admins');
    assert.deepEqual({ ...store.history[0], at: null }, { action: 'routing-engine', before: 'ors', after: 'admins', by: 'arthur', at: null });
    assert.equal(store.meta.routingEngine, 'admins');
    assert.deepEqual(store.meta.routingEngines, ['ors', 'admins', 'all']);
    assert.equal(store.meta.referralValidityMonths, 3);
    clearTimeout(store.saveTimer);
  });

  it('survives a restart, and a damaged value keeps ORS', async () => {
    const file = process.env.SETTINGS_FILE;
    writeFileSync(file, JSON.stringify({ referralValidityMonths: 4, routingEngine: 'all', history: [] }));
    const saved = new SettingsStore();
    await saved.load();
    assert.equal(saved.routingEngine, 'all');
    assert.equal(saved.referralValidityMonths, 4);
    writeFileSync(file, JSON.stringify({ routingEngine: 'osrm' }));
    const damaged = new SettingsStore();
    await damaged.load();
    assert.equal(damaged.routingEngine, 'ors');
    damaged.setRoutingEngine('admins', 'arthur');
    clearTimeout(damaged.saveTimer);
    await damaged.save();
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).routingEngine, 'admins');
  });
});

describe('console routes', () => {
  it('keeps the routing routes behind an admin session or the ADMIN_TOKEN', async () => {
    for (const [method, path, body] of [
      ['GET', '/api/admin/routing'],
      ['PUT', '/api/admin/routing/engine', { engine: 'all' }],
      ['GET', '/api/admin/routing/shadow'],
      ['GET', '/api/admin/routing/shadow/summary'],
      ['GET', '/api/admin/routing/traces'],
      ['GET', '/api/admin/routing/traces/1'],
    ]) {
      const r = await call(server, method, path, { body, headers: { authorization: 'Bearer nobody' } });
      assert.equal(r.status, 401, `${method} ${path}`);
    }
    assert.equal(settingsStore.routingEngine, 'ors');
  });

  it('switches routingEngine live, writes it to the admins\' journal, and goes back to ors in one call', async () => {
    const r = await call(server, 'PUT', '/api/admin/routing/engine', { headers: admin, body: { engine: 'admins' } });
    assert.equal(r.status, 200);
    assert.equal(r.json.routingEngine, 'admins');
    assert.deepEqual(r.json.changed, { before: 'ors', after: 'admins' });
    assert.deepEqual(r.json.valhalla, { enabled: false, state: 'disabled', cause: 'disabled' });
    assert.equal(settingsStore.routingEngine, 'admins');
    const audit = queries.find((q) => q.sql.includes('INSERT INTO crowd.admin_action'));
    assert.deepEqual(audit.params.slice(0, 5), [null, 'ADMIN_TOKEN', 'routing.engine', 'setting', 'routingEngine']);
    const view = await call(server, 'GET', '/api/admin/routing', { headers: admin });
    assert.equal(view.json.routingEngine, 'admins');
    assert.equal(view.json.mode, 'admins');
    assert.equal(view.json.provider, null); // no ORS key here, Valhalla off: nobody serves
    assert.equal(view.json.shadow.max > 0, true);
    const wrong = await call(server, 'PUT', '/api/admin/routing/engine', { headers: admin, body: { engine: 'valhalla' } });
    assert.equal(wrong.status, 400);
    const back = await call(server, 'PUT', '/api/admin/routing/engine', { headers: admin, body: { engine: 'ors' } });
    assert.deepEqual(back.json.changed, { before: 'admins', after: 'ors' });
    assert.equal(settingsStore.routingEngine, 'ors');
    clearTimeout(settingsStore.saveTimer);
    settingsStore.saveTimer = null;
  });

  it('reads the shadow\'s measures and the admins\' traces', async () => {
    const shadow = await call(server, 'GET', '/api/admin/routing/shadow?kind=route&limit=5', { headers: admin });
    assert.deepEqual(shadow.json, { count: 0, runs: [] });
    const read = queries.find((q) => q.sql.includes('FROM routing.shadow_run') && q.sql.includes('LIMIT'));
    assert.deepEqual(read.params, [null, 'route', 5, 90]);
    assert.equal((await call(server, 'GET', '/api/admin/routing/shadow?kind=bench', { headers: admin })).status, 400);
    const summary = await call(server, 'GET', '/api/admin/routing/shadow/summary?since=2026-09-01', { headers: admin });
    assert.deepEqual(summary.json, { since: '2026-09-01T00:00:00.000Z', kinds: [], errors: [] });
    const traces = await call(server, 'GET', '/api/admin/routing/traces', { headers: admin });
    assert.deepEqual(traces.json, { count: 0, next: null, traces: [] });
    assert.equal((await call(server, 'GET', '/api/admin/routing/traces/abc', { headers: admin })).status, 404);
    assert.equal((await call(server, 'GET', '/api/admin/routing/traces/12', { headers: admin })).status, 404);
  });
});

describe('/health', () => {
  it('says which engine serves, Valhalla\'s state, the fallbacks and the shadow, as additions to phase 1', async () => {
    const r = await call(server, 'GET', '/health');
    assert.equal(r.status, 200);
    const { routing } = r.json;
    assert.equal(routing.provider, null); // no ORS key here, Valhalla off
    assert.equal(routing.mode, 'ors');
    assert.deepEqual(routing.valhalla, { enabled: false, state: 'disabled', cause: 'disabled' });
    assert.deepEqual(Object.keys(routing.fallbacks).sort(), ['byCause', 'last', 'since', 'total']);
    assert.deepEqual(Object.keys(routing.shadow).sort(), ['done', 'dropped', 'failed', 'max', 'queued', 'running']);
    assert.ok(r.json.traffic);
    assert.doesNotMatch(JSON.stringify(routing), /osrm|8002/);
  });

  it('never reached the network', () => {
    assert.equal(net.count(), 0);
  });
});
