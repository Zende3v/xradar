import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { call, trapNetwork } from './helpers/routing.js';

const directory = mkdtempSync(join(tmpdir(), 'eona-account-security-'));
process.env.ACCOUNTS_FILE = join(directory, 'http.json');
process.env.ADMIN_TOKEN = 'security-admin-token';
const { AccountStore, accountStore } = await import('../src/accounts/store.js');
const { accountRouter, adminAccountRouter } = await import('../src/accounts/routes.js');
const { adminActor, authAccount, publicView } = await import('../src/accounts/auth.js');
const { liveRouter } = await import('../src/live/routes.js');
const { liveStore } = await import('../src/live/store.js');
const { db } = await import('../src/db.js');
db.query = async () => ({ rows: [] });
accountStore.scheduleSave = () => {};
trapNetwork(before, after);

let sequence = 0;
function store() {
  const accounts = new AccountStore({ accountsFile: join(directory, `accounts-${sequence++}.json`) });
  accounts.scheduleSave = () => {};
  return accounts;
}
function member(accounts, name, deviceId = null, role = 'client') {
  const result = accounts.create({ role, username: name, email: `${name}@example.test`, password: 'password-123', deviceId });
  assert.equal(result.error, undefined);
  return result.account;
}
const request = ({ authorization, deviceId, adminToken } = {}) => ({
  body: { deviceId }, query: {}, get: (name) => ({ authorization, 'x-admin-token': adminToken })[name],
});

const app = express();
app.use(express.json());
app.use('/api/accounts', accountRouter);
app.use('/api/admin/accounts', adminAccountRouter);
app.use('/api/live', liveRouter);
const server = app.listen(0, '127.0.0.1');
await once(server, 'listening');
after(async () => {
  await new Promise((done) => server.close(done));
  const target = resolve(directory);
  assert.ok(target.startsWith(`${resolve(tmpdir())}${sep}`));
  await rm(target, { recursive: true, force: true });
});

describe('ban durable sans contournement identité', () => {
  it('conserve appareils anciens, email et fournisseur après suppression et redémarrage', async () => {
    const accounts = store();
    const account = member(accounts, 'BannedDriver', 'old-device');
    assert.equal(accounts.login('BannedDriver', 'password-123', 'new-device').account, account);
    accounts.linkProvider(account.id, { provider: 'google', subject: 'stable-google-id', email: ' BannedDriver@EXAMPLE.test ' });
    const tokens = [accounts.issueToken(account.id), accounts.issueToken(account.id)];
    liveStore.touch(account.id, true);
    assert.equal(accounts.actOnAccount(account.id, { action: 'ban', reason: 'abus' }).account, account);
    assert.deepEqual(account.knownDeviceIds, ['old-device', 'new-device']);
    assert.equal(accounts.hasPlus(account), false);
    assert.equal(accounts.accessFor(account).canNavigate, false);
    assert.equal(accounts.canReport(account), false);
    assert.equal(liveStore.byAccount.has(account.id), false);
    for (const token of tokens) assert.equal(accounts.resolveToken(token), null);
    assert.equal(accounts.startNavigation(account, { lat: 48.8, lon: 2.3 }, 'trip').allowed, false);
    assert.equal(accounts.remove(account.id).removed, true);
    await accounts.save();
    const restored = new AccountStore({ accountsFile: accounts.accountsFile });
    restored.scheduleSave = () => {};
    await restored.load();
    for (const deviceId of ['old-device', 'new-device']) {
      assert.equal(restored.auth(deviceId).error, 'banned');
      assert.equal(restored.claimGuest(deviceId).error, 'banned');
      assert.equal(restored.register({ email: 'fresh@example.test', username: 'FreshDriver', password: 'password-123', deviceId }).error, 'banned');
    }
    assert.equal(restored.register({ email: ' BANNEDDRIVER@EXAMPLE.TEST ', username: 'FreshDriver', password: 'password-123' }).error, 'banned');
    assert.equal(restored.signInWithProvider({ provider: 'google', subject: 'stable-google-id', email: 'changed@example.test', emailVerified: true }).error, 'banned');
    assert.equal(restored.signInWithProvider({ provider: 'google', subject: 'different-id', email: 'banneddriver@example.test', emailVerified: true }).error, 'banned');
    const good = member(restored, 'GoodDriver');
    assert.equal(restored.login(good.username, 'password-123', 'old-device').error, 'banned');
    assert.equal(restored.bindDevice(good, 'new-device').error, 'banned');
    const guest = restored.claimGuest('clean-conversion').account;
    assert.equal(restored.register({ guest, email: 'banneddriver@example.test', username: 'ConvertedDriver', password: 'password-123' }).error, 'banned');
    assert.equal(guest.role, 'guest');
    assert.equal(restored.actOnAccount(account.id, { action: 'unban' }).unbanned, true);
    assert.ok(restored.claimGuest('old-device').account);
    assert.equal(JSON.parse(readFileSync(restored.bans.file, 'utf8')).bans.length, 0);
  });

  it('migre ancien tableau JSON banni avant toute authentification', async () => {
    const accounts = store();
    writeFileSync(accounts.accountsFile, JSON.stringify([{ id: 'legacy-ban', role: 'client', email: ' Legacy@Example.test ', banned: true, deviceId: 'legacy-now', knownDeviceIds: ['legacy-before'] }]));
    await accounts.load();
    assert.equal(accounts.auth('legacy-now').error, 'banned');
    assert.equal(accounts.claimGuest('legacy-before').error, 'banned');
    assert.equal(accounts.register({ email: 'legacy@example.test', username: 'LegacyNew', password: 'password-123' }).error, 'banned');
    assert.equal(JSON.parse(readFileSync(accounts.bans.file, 'utf8')).bans.length, 1);
    await accounts.save();
    assert.equal(Array.isArray(JSON.parse(readFileSync(accounts.accountsFile, 'utf8'))), true);
  });

  it('refuse démarrage avec registre corrompu', async () => {
    const accounts = store();
    writeFileSync(accounts.bans.file, '{broken');
    await assert.rejects(() => accounts.load(), SyntaxError);
  });

  it('ban par identité partagée coupe toutes présences et sessions concernées', () => {
    const accounts = store();
    const first = member(accounts, 'FirstShared', 'shared-device');
    const second = member(accounts, 'SecondShared');
    accounts.bindDevice(second, 'shared-device');
    const firstToken = accounts.issueToken(first.id);
    const secondToken = accounts.issueToken(second.id);
    liveStore.touch(first.id); liveStore.touch(second.id);
    accounts.actOnAccount(first.id, { action: 'ban' });
    assert.equal(accounts.resolveToken(firstToken), null);
    assert.equal(accounts.resolveToken(secondToken), null);
    assert.equal(liveStore.byAccount.has(first.id), false);
    assert.equal(liveStore.byAccount.has(second.id), false);
    assert.equal(accounts.blockReason(second), 'banned');
  });
});

describe('suspension, sessions et protections administrateur', () => {
  it('promotion membre exige identité connectable ; inscription rend promotion admin possible', () => {
    const accounts = store();
    const guest = accounts.claimGuest('promotion-device').account;
    for (const role of ['client', 'admin']) {
      assert.equal(accounts.update(guest.id, { role }).error, 'member identity required');
      assert.equal(guest.role, 'guest');
    }
    const registered = accounts.register({ guest, email: 'promoted@example.test', username: 'PromotedDriver', password: 'password-123' });
    assert.equal(registered.error, undefined);
    assert.equal(accounts.update(guest.id, { role: 'admin' }).account, guest);
    assert.equal(accounts.login(guest.username, 'password-123').account, guest);
    assert.equal(guest.role, 'admin');
  });

  it('suspension réversible persiste ; révocation sessions permet reconnexion', async () => {
    const accounts = store();
    const account = member(accounts, 'SuspendedDriver', 'suspended-device');
    const token = accounts.issueToken(account.id);
    liveStore.touch(account.id, true);
    accounts.actOnAccount(account.id, { action: 'suspend' });
    assert.equal(accounts.resolveToken(token), null);
    assert.equal(accounts.blockedForToken(token), account);
    assert.equal(accounts.login(account.username, 'password-123').error, 'suspended');
    assert.equal(accounts.auth('suspended-device').error, 'suspended');
    assert.equal(accounts.claimGuest('suspended-device').error, 'suspended');
    assert.equal(accounts.issueToken(account.id), null);
    assert.equal(publicView(account).access, 'restricted');
    await accounts.save();
    const restored = new AccountStore({ accountsFile: accounts.accountsFile });
    restored.scheduleSave = () => {};
    await restored.load();
    assert.equal(restored.get(account.id).suspended, true);
    restored.actOnAccount(account.id, { action: 'restore' });
    const relogged = restored.login(account.username, 'password-123').account;
    const nextToken = restored.issueToken(account.id);
    assert.equal(restored.resolveToken(nextToken), relogged);
    assert.equal(restored.actOnAccount(account.id, { action: 'revokeSessions' }).revokedSessions, 1);
    assert.equal(restored.resolveToken(nextToken), null);
    assert.equal(restored.login(account.username, 'password-123').account, relogged);
    assert.equal(restored.accessFor(relogged).canNavigate, true);
    relogged.revoked = true;
    assert.equal(restored.hasPlus(relogged), false);
  });

  it('protège soi-même, dernier admin et identité admin partagée', () => {
    const accounts = store();
    const admin = member(accounts, 'OnlyAdmin', 'admin-device', 'admin');
    const actor = { kind: 'account', id: admin.id };
    assert.match(accounts.update(admin.id, { role: 'client' }, { actor }).error, /yourself/);
    assert.match(accounts.actOnAccount(admin.id, { action: 'ban' }, { actor }).error, /yourself/);
    assert.match(accounts.actOnAccount(admin.id, { action: 'suspend' }, { actor }).error, /yourself/);
    for (const patch of [{ role: 'client' }, { banned: true }, { suspended: true }]) {
      assert.equal(accounts.update(admin.id, patch).error, 'cannot disable last admin');
    }
    assert.equal(accounts.remove(admin.id).error, 'cannot delete last admin');
    assert.equal(accounts.update(admin.id, { banned: 'true' }).error, 'banned must be boolean');
    const guest = accounts.claimGuest('admin-device').account;
    assert.equal(accounts.actOnAccount(guest.id, { action: 'ban' }, { actor }).error, 'cannot ban your own identity');
    assert.equal(accounts.actOnAccount(guest.id, { action: 'ban' }).error, 'cannot disable last admin');
    member(accounts, 'OtherAdmin', null, 'admin');
    assert.equal(accounts.actOnAccount(admin.id, { action: 'suspend' }).account, admin);
    assert.equal(accounts.accessFor(admin).canNavigate, false);
  });
});

describe('contrat HTTP et Bearer strict', () => {
  it('Bearer invalide ne retombe jamais sur deviceId, ni clé admin auxiliaire', async () => {
    const guest = accountStore.claimGuest('http-guest').account;
    const admin = member(accountStore, 'HttpAdmin', 'http-admin-device', 'admin');
    assert.equal(authAccount(request({ authorization: 'Bearer invalid', deviceId: guest.deviceId })), null);
    assert.equal(authAccount(request({ authorization: 'Basic invalid', deviceId: guest.deviceId })), null);
    assert.equal(adminActor(request({ deviceId: admin.deviceId })), null);
    assert.equal(adminActor(request({ authorization: 'Bearer invalid', adminToken: 'security-admin-token' })), null);
    assert.equal(authAccount(request({ deviceId: admin.deviceId })), null);
    const denied = await call(server, 'GET', '/api/accounts/me?deviceId=http-guest', { headers: { authorization: 'Bearer invalid' } });
    assert.equal(denied.status, 401);
    const deviceAdmin = await call(server, 'POST', '/api/accounts/auth', { body: { deviceId: admin.deviceId } });
    assert.equal(deviceAdmin.status, 401);
    const adminAccess = await call(server, 'GET', '/api/admin/accounts', { headers: { authorization: 'Bearer invalid', 'x-admin-token': 'security-admin-token' } });
    assert.equal(adminAccess.status, 401);
  });

  it('DTO admin exclut tous secrets et exposes statut calculé, appareil et présence', async () => {
    const admin = accountStore.byUsername.get('httpadmin');
    const account = member(accountStore, 'DtoDriver', 'dto-device');
    Object.assign(account, { verifyCode: 'secret-verify', resetCode: 'secret-reset', resetExpiresAt: Date.now(), token: 'secret-token', privateExtra: 'secret-extra' });
    account.providers.push({ provider: 'google', subject: 'private-subject', email: account.email, linkedAt: new Date().toISOString() });
    account.app = { platform: 'ios', model: 'iPhone', appVersion: '36', secretExtra: 'secret-app' };
    liveStore.touch(account.id, true);
    const result = await call(server, 'GET', `/api/admin/accounts/${account.id}`, { headers: { authorization: `Bearer ${accountStore.issueToken(admin.id)}` } });
    assert.equal(result.status, 200);
    const dto = result.json.account;
    for (const field of ['passwordHash', 'verifyCode', 'resetCode', 'resetExpiresAt', 'token', 'privateExtra']) assert.equal(field in dto, false);
    assert.equal('subject' in dto.providers[0], false);
    assert.equal('secretExtra' in dto.app, false);
    assert.equal(dto.access, 'active');
    assert.equal(dto.tier, 'plus');
    assert.equal(dto.hasPlus, true);
    assert.equal(dto.suspended, false);
    assert.equal(dto.online, true);
    assert.equal(dto.inTrip, true);
    assert.deepEqual(dto.knownDeviceIds, ['dto-device']);
  });

  it('action suspend renvoie restriction au client révoqué, retire présence, puis restaure', async () => {
    const admin = accountStore.byUsername.get('httpadmin');
    const account = accountStore.byUsername.get('dtodriver');
    const adminToken = accountStore.issueToken(admin.id);
    const token = accountStore.issueToken(account.id);
    const result = await call(server, 'POST', `/api/admin/accounts/${account.id}/action`, { headers: { authorization: `Bearer ${adminToken}` }, body: { action: 'suspend', reason: 'contrôle' } });
    assert.equal(result.status, 200);
    assert.equal(result.json.account.suspended, true);
    assert.equal(result.json.account.access, 'restricted');
    assert.equal(result.json.account.online, false);
    const denied = await call(server, 'GET', '/api/accounts/me', { headers: { authorization: `Bearer ${token}` } });
    assert.equal(denied.status, 403);
    assert.equal(denied.json.account.access, 'restricted');
    const presence = await call(server, 'POST', '/api/live/presence', { headers: { authorization: `Bearer ${token}` }, body: { inTrip: true } });
    assert.equal(presence.status, 403);
    assert.equal(liveStore.byAccount.has(account.id), false);
    const restored = await call(server, 'POST', `/api/admin/accounts/${account.id}/action`, { headers: { authorization: `Bearer ${adminToken}` }, body: { action: 'restore' } });
    assert.equal(restored.status, 200);
    assert.equal(restored.json.account.suspended, false);
    assert.equal(accountStore.resolveToken(token), null);
  });

  it('PATCH historique ban alimente registre ; dernier admin protégé via API', async () => {
    const admin = accountStore.byUsername.get('httpadmin');
    const target = accountStore.byUsername.get('dtodriver');
    const headers = { authorization: `Bearer ${accountStore.issueToken(admin.id)}` };
    const self = await call(server, 'PATCH', `/api/admin/accounts/${admin.id}`, { headers, body: { banned: true } });
    assert.equal(self.status, 400);
    const lastAdmin = await call(server, 'POST', `/api/admin/accounts/${admin.id}/action`, { headers: { 'x-admin-token': 'security-admin-token' }, body: { action: 'suspend' } });
    assert.equal(lastAdmin.status, 400);
    const result = await call(server, 'PATCH', `/api/admin/accounts/${target.id}`, { headers, body: { banned: true } });
    assert.equal(result.status, 200);
    assert.ok(accountStore.bans.records.has(target.id));
    assert.equal(accountStore.claimGuest('dto-device').error, 'banned');
    const unbanned = await call(server, 'POST', `/api/admin/accounts/${target.id}/action`, { headers, body: { action: 'unban' } });
    assert.equal(unbanned.status, 200);
    assert.equal(accountStore.bans.records.has(target.id), false);
  });

  it('déconnexion retire présence de session résolue ; Bearer invalide ne touche aucun compte', async () => {
    const account = accountStore.byUsername.get('dtodriver');
    const token = accountStore.issueToken(account.id);
    liveStore.touch(account.id, true);
    const invalid = await call(server, 'POST', '/api/accounts/logout', { headers: { authorization: 'Bearer invalid' }, body: { deviceId: account.deviceId } });
    assert.equal(invalid.status, 200);
    assert.equal(liveStore.byAccount.has(account.id), true);
    const result = await call(server, 'POST', '/api/accounts/logout', { headers: { authorization: `Bearer ${token}` } });
    assert.equal(result.status, 200);
    assert.equal(accountStore.resolveToken(token), null);
    assert.equal(liveStore.byAccount.has(account.id), false);
  });

  it('device auth bloqué renvoie DTO restreint sans token ; suppression ne libère aucune identité', async () => {
    const admin = accountStore.byUsername.get('httpadmin') ?? member(accountStore, 'DeviceCheckAdmin', null, 'admin');
    const target = member(accountStore, 'DeviceBlockedDriver', 'http-blocked-device');
    const headers = { authorization: `Bearer ${accountStore.issueToken(admin.id)}` };
    const banned = await call(server, 'POST', `/api/admin/accounts/${target.id}/action`, { headers, body: { action: 'ban' } });
    assert.equal(banned.status, 200);
    const denied = await call(server, 'POST', '/api/accounts/auth', { body: { deviceId: target.deviceId } });
    assert.equal(denied.status, 403);
    assert.equal(denied.json.account.access, 'restricted');
    assert.equal(denied.json.account.canNavigate, false);
    assert.equal('token' in denied.json, false);
    accountStore.remove(target.id);
    const guest = await call(server, 'POST', '/api/accounts/guest', { body: { deviceId: 'http-blocked-device' } });
    assert.equal(guest.status, 403);
    const register = await call(server, 'POST', '/api/accounts/register', { body: { email: 'DEVICEBLOCKEDDRIVER@example.test', password: 'password-123', username: 'NewBlockedDriver' } });
    assert.equal(register.status, 403);
  });

  it('registre visible pour ban indirect et source supprimée, déban via banId', async () => {
    const admin = accountStore.byUsername.get('httpadmin') ?? member(accountStore, 'BanListAdmin', null, 'admin');
    const source = member(accountStore, 'BanSourceDriver', 'ban-source-device');
    const indirect = member(accountStore, 'BanIndirectDriver');
    accountStore.bindDevice(indirect, 'ban-source-device');
    const headers = { authorization: `Bearer ${accountStore.issueToken(admin.id)}` };
    const banned = await call(server, 'POST', `/api/admin/accounts/${source.id}/action`, { headers, body: { action: 'ban', reason: 'identité partagée' } });
    assert.equal(banned.status, 200);
    const view = await call(server, 'GET', `/api/admin/accounts/${indirect.id}`, { headers });
    assert.equal(view.status, 200);
    assert.equal(view.json.account.banned, true);
    assert.equal(view.json.account.banId, source.id);
    assert.equal(view.json.account.banReason, 'identité partagée');
    assert.ok(view.json.account.bannedAt);
    accountStore.remove(source.id);
    const listed = await call(server, 'GET', '/api/admin/accounts/bans', { headers });
    assert.equal(listed.status, 200);
    const entry = listed.json.bans.find((ban) => ban.accountId === source.id);
    assert.equal(entry.accountExists, false);
    assert.equal(entry.email, 'bansourcedriver@example.test');
    assert.equal(entry.reason, 'identité partagée');
    assert.equal(entry.deviceCount, 1);
    assert.equal('deviceIds' in entry, false);
    const restored = await call(server, 'POST', `/api/admin/accounts/${view.json.account.banId}/action`, { headers, body: { action: 'unban' } });
    assert.equal(restored.status, 200);
    assert.equal(restored.json.unbanned, true);
    assert.equal(accountStore.blockReason(indirect), null);
  });
});
