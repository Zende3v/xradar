import assert from 'node:assert/strict';
import { once } from 'node:events';
import { get as httpGet } from 'node:http';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { AccountStore, accountStore } from '../src/accounts/store.js';
import { accountRouter } from '../src/accounts/routes.js';
import { createRouteRouter } from '../src/routing/routes.js';
import { createRoutingEngine } from '../src/routing/engine.js';
import { tripRouter } from '../src/trips/routes.js';
import { groupStore } from '../src/trips/groups.js';
import { call, fakeOrs, fakeValhalla, trapNetwork } from './helpers/routing.js';

trapNetwork(before, after);
const store = () => {
  const result = new AccountStore();
  result.scheduleSave = () => {};
  return result;
};

describe('statuts invité, gratuit, EONA+', () => {
  it('invité sans mot de passe : permanent, identité stable, aucun essai consommé', async () => {
    const accounts = store();
    const { account } = accounts.claimGuest('phone-guest');
    assert.match(account.username, /^Invite_[a-f0-9]{8}$/);
    assert.equal(account.passwordHash, null);
    account.createdAt = '2020-01-01T00:00:00Z';
    assert.equal(accounts.isExpiredGuest(account, Date.now()), false);
    assert.equal(await accounts.purge(), 0);
    assert.deepEqual(accounts.accessFor(account), { status: 'free', canNavigate: true, endsAt: null });
    assert.equal(accounts.claimGuest('phone-guest').account.id, account.id);
    assert.equal(accounts.limitsFor(account).tripsPerDay, 4);
  });

  it('inscription directe : sept jours complets, puis gratuit sans suppression', () => {
    const accounts = store();
    const { account } = accounts.register({ email: 'direct@example.test', password: 'pass12345', username: 'Direct' });
    assert.equal(accounts.hasPlus(account), true);
    assert.equal(Date.parse(account.trialEndsAt) - Date.parse(account.trialStartedAt), 7 * 86_400_000);
    assert.equal(accounts.limitsFor(account), null);
    account.trialEndsAt = new Date(Date.now() - 1).toISOString();
    assert.equal(accounts.accessFor(account).status, 'free');
    assert.equal(accounts.accessFor(account).canNavigate, true);
    assert.equal(accounts.get(account.id), account);
    assert.equal(accounts.limitsFor(account).tripsPerDay, 4);
    assert.equal(accounts.canReport(account), true);
  });

  it('conversion : conserve compte, statistiques, trajets, session ; essai non renouvelable', () => {
    const accounts = store();
    const guest = accounts.claimGuest('phone-convert').account;
    guest.createdAt = '2020-01-01T00:00:00Z';
    guest.trialEndsAt = '2020-01-08T00:00:00Z';
    guest.stats.tripCount = 9;
    guest.trips.push({ id: 'trip-kept' });
    const oldName = guest.username;
    const token = accounts.issueToken(guest.id);
    const result = accounts.register({ guest, email: 'convert@example.test', password: 'pass12345', username: 'Converted' });
    assert.equal(result.account, guest);
    assert.equal(result.account.stats.tripCount, 9);
    assert.equal(result.account.trips[0].id, 'trip-kept');
    assert.equal(accounts.resolveToken(token), guest);
    assert.equal(accounts.byUsername.has(oldName.toLowerCase()), false);
    assert.equal(accounts.hasPlus(guest), true);
    assert.equal(accounts.register({ guest, email: 'again@example.test', password: 'pass12345', username: 'Again' }).error, 'guest account required');
  });

  it('anciens comptes : migration sans prolongation ; accès permanent admin conservé', () => {
    const accounts = store();
    const legacy = { id: 'legacy', role: 'guest', emailLower: 'legacy@example.test', createdAt: '2020-01-01T00:00:00Z' };
    accounts.index(legacy);
    assert.equal(legacy.role, 'client');
    assert.equal(accounts.accessFor(legacy).status, 'free');
    const permanent = { id: 'permanent', role: 'client' };
    accounts.index(permanent);
    assert.equal(accounts.hasPlus(permanent), true);
    permanent.subscriptionEndsAt = '2020-01-01T00:00:00Z';
    assert.equal(accounts.accessFor(permanent).status, 'free');
    permanent.banned = true;
    assert.equal(accounts.accessFor(permanent).canNavigate, false);
  });

  it('Google : nouvel inscrit ou invité lié obtient sept jours', () => {
    const accounts = store();
    const identity = { provider: 'google', subject: 'g1', email: 'google@example.test', emailVerified: true, name: 'GoogleDriver' };
    const fresh = accounts.signInWithProvider(identity).account;
    assert.equal(accounts.accessFor(fresh).status, 'trial');
    const guest = accounts.claimGuest('phone-link').account;
    guest.createdAt = '2020-01-01T00:00:00Z';
    accounts.linkProvider(guest.id, { ...identity, subject: 'g2' });
    assert.equal(accounts.hasPlus(guest), true);
  });

  it('quatre départs même destination : limite exacte, appels répétés et recalculs gratuits', () => {
    const accounts = store();
    const guest = accounts.claimGuest('phone-quota').account;
    const to = { lat: 48.86, lon: 2.35 };
    for (let i = 0; i < 4; i++) assert.equal(accounts.startNavigation(guest, to, `trip-${i}`).allowed, true);
    assert.equal(accounts.startNavigation(guest, to, 'trip-extra').allowed, false);
    assert.equal(accounts.startNavigation(guest, to, 'trip-3').allowed, true);
    assert.equal(accounts.limitsFor(guest).tripsToday, 4);
    assert.equal(accounts.navigationMatches(guest, to, 'trip-3'), true);
    assert.equal(accounts.navigationMatches(guest, { lat: 49, lon: 2.35 }, 'trip-3'), false);
    guest.usage.day = '2020-01-01';
    assert.equal(accounts.navigationMatches(guest, to, 'trip-3'), true);
    assert.equal(accounts.limitsFor(guest).tripsToday, 0);
  });
});

const servers = [];
after(() => Promise.all(servers.map((s) => new Promise((resolve) => s.close(resolve)))));

async function setup() {
  const accounts = store();
  const guest = accounts.claimGuest('http-phone').account;
  const plus = accounts.register({ email: 'http@example.test', password: 'pass12345', username: 'HttpDriver' }).account;
  const valhalla = fakeValhalla();
  const routing = createRoutingEngine({ ors: fakeOrs(), valhalla, mode: () => 'all' });
  const app = express();
  app.use(express.json());
  app.use('/api/route', createRouteRouter({
    auth: (req) => req.get('x-plus') ? plus : guest,
    accounts, routing,
    shadow: { afterRoute() {}, afterFaster() {} },
    guard: { cachedRoute: () => null, keepRoute() {}, spendRoute: () => ({ ok: true }) },
    log() {}, liveReady: () => true,
  }));
  accountStore.scheduleSave = () => {};
  accountStore.index(guest);
  accountStore.index(plus);
  app.use('/api/accounts', accountRouter);
  app.use('/api/trips', tripRouter);
  const server = app.listen(0, '127.0.0.1');
  servers.push(server);
  await once(server, 'listening');
  return { accounts, guest, plus, routing, valhalla, server };
}

describe('droits serveur', () => {
  it('aperçus sans compteur ; départs concurrents bornés ; trajet admis continue au plafond', async () => {
    const ctx = await setup();
    const path = '/api/route?from=48.85,2.3&to=48.86,2.35&preview=1';
    assert.equal((await call(ctx.server, 'GET', path)).status, 200);
    assert.equal((await call(ctx.server, 'GET', path + '&preference=shortest')).status, 200);
    assert.equal(ctx.accounts.limitsFor(ctx.guest).tripsToday, 0);
    const to = { lat: 48.86, lon: 2.35 };
    const replies = await Promise.all(Array.from({ length: 6 }, (_, i) => call(ctx.server, 'POST', '/api/route/start', { body: { to, tripId: `trip-http-${i}` } })));
    assert.equal(replies.filter((r) => r.status === 200).length, 4);
    assert.equal(replies.filter((r) => r.status === 429).length, 2);
    const id = ctx.guest.navigation.id;
    assert.equal((await call(ctx.server, 'GET', path.replace('&preview=1', `&tripId=${id}`))).status, 200);
    assert.equal((await call(ctx.server, 'GET', path.replace('&preview=1', '&tripId=unknown-trip'))).status, 409);
    assert.equal(ctx.accounts.limitsFor(ctx.guest).tripsToday, 4);
  });

  it('Taxi refusé gratuit, accepté essai, profil transmis jusqu’au moteur', async () => {
    const ctx = await setup();
    const path = '/api/route?from=48.85,2.3&to=48.86,2.35&preview=1&vehicle=taxi';
    assert.equal((await call(ctx.server, 'GET', path)).status, 403);
    assert.equal(ctx.valhalla.calls.length, 0);
    assert.equal((await call(ctx.server, 'GET', path, { headers: { 'x-plus': '1' } })).status, 200);
    assert.equal(ctx.valhalla.calls[0].options.vehicle, 'taxi');
    ctx.plus.trialEndsAt = '2020-01-01T00:00:00Z';
    assert.equal((await call(ctx.server, 'GET', path, { headers: { 'x-plus': '1' } })).status, 403);
  });

  it('groupes refusés gratuit ; sortie autorisée après expiration', async () => {
    const ctx = await setup();
    const token = accountStore.issueToken(ctx.guest.id);
    const options = { headers: { Authorization: `Bearer ${token}` } };
    const refused = await call(ctx.server, 'POST', '/api/trips/group', { ...options, body: { destination: { lat: 48.86, lon: 2.35 } } });
    assert.equal(refused.status, 403);
    assert.equal(refused.json.feature, 'groups');
    assert.equal((await call(ctx.server, 'POST', '/api/trips/group/leave', options)).status, 200);
  });

  it('conversion exige session propriétaire ; deviceId seul ne suffit pas', async () => {
    const ctx = await setup();
    const body = { username: 'HttpConvert', email: 'owner@example.test', password: 'pass12345', deviceId: ctx.guest.deviceId };
    assert.equal((await call(ctx.server, 'POST', '/api/accounts/me/register', { body })).status, 401);
    const token = accountStore.issueToken(ctx.guest.id);
    const response = await call(ctx.server, 'POST', '/api/accounts/me/register', { body, headers: { Authorization: `Bearer ${token}` } });
    assert.equal(response.status, 200);
    assert.equal(response.json.account.id, ctx.guest.id);
    assert.equal(response.json.account.access, 'trial');
    assert.equal(response.json.account.tier, 'plus');
    assert.equal(response.json.token, token);
  });

  it('expiration ferme flux groupe déjà ouvert', async () => {
    const ctx = await setup();
    const token = accountStore.issueToken(ctx.plus.id);
    const headers = { Authorization: `Bearer ${token}` };
    assert.equal((await call(ctx.server, 'POST', '/api/trips/group', { headers, body: { destination: { lat: 48.86, lon: 2.35 } } })).status, 201);
    const request = httpGet(`http://127.0.0.1:${ctx.server.address().port}/api/trips/group/stream`, { headers });
    request.setTimeout(2000, () => request.destroy(new Error('stream did not close')));
    const [response] = await once(request, 'response');
    await once(response, 'data');
    const ended = once(response, 'end');
    response.resume();
    ctx.plus.trialEndsAt = '2020-01-01T00:00:00Z';
    groupStore.update(ctx.plus.id, { lat: 48.86, lon: 2.35 });
    await ended;
  });
});
