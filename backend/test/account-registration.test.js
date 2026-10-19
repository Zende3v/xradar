import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, readFileSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { call, deferred, trapNetwork } from './helpers/routing.js';

const directory = mkdtempSync(join(tmpdir(), 'eona-registration-'));
process.env.ACCOUNTS_FILE = join(directory, 'http.json');
const { AccountStore, accountStore } = await import('../src/accounts/store.js');
const { accountRouter } = await import('../src/accounts/routes.js');
const { mailer } = await import('../src/mailer.js');
accountStore.scheduleSave = () => {};
trapNetwork(before, after);
const sent = [];
const originalSend = mailer.sendVerify;
let delivers = true;
mailer.sendVerify = async (email, code) => { sent.push({ email, code }); return delivers; };
const app = express();
app.use(express.json());
app.use('/api/accounts', accountRouter);
const server = app.listen(0, '127.0.0.1');
await once(server, 'listening');
after(async () => {
  mailer.sendVerify = originalSend;
  await new Promise((done) => server.close(done));
  await accountStore.saveChain;
  const target = resolve(directory);
  assert.ok(target.startsWith(`${resolve(tmpdir())}${sep}`));
  await rm(target, { recursive: true, force: true });
});

let sequence = 0;
function store() {
  const accounts = new AccountStore({ accountsFile: join(directory, `accounts-${sequence++}.json`) });
  accounts.scheduleSave = () => {};
  return accounts;
}
const input = (name = 'NewDriver') => ({ email: `${name.toLowerCase()}@example.test`, password: 'private-password', username: name });
const wrong = (code) => String((Number(code) + 1) % 1_000_000).padStart(6, '0');
const google = (subject, email, emailVerified = true) => ({ provider: 'google', subject, email, emailVerified });

describe('inscription : email obligatoire, invités conservés', () => {
  it('aucun compte, index, session ou essai avant preuve ; création vérifiée et code à usage unique', () => {
    const accounts = store();
    const request = input();
    const pending = accounts.beginRegistration(request);
    assert.equal(pending.error, undefined);
    assert.equal(accounts.byId.size, 0);
    assert.equal(accounts.byEmail.size, 0);
    assert.equal(accounts.byUsername.size, 0);
    assert.equal(accounts.sessions.size, 0);
    assert.equal(accounts.login(request.email, request.password).error, 'invalid credentials');
    assert.equal(accounts.verifyEmail(request.email, pending.code).error, 'not found');
    assert.equal(pending.registration.code, undefined);
    assert.equal(pending.registration.passwordHash, undefined);
    const done = accounts.completeRegistration(request.email, pending.draft.token, pending.code);
    assert.equal(done.account.emailVerified, true);
    assert.equal(accounts.accessFor(done.account).status, 'trial');
    assert.equal(accounts.login(request.email, request.password).account, done.account);
    assert.equal(accounts.completeRegistration(request.email, pending.draft.token, pending.code).error, 'registration expired');
  });

  it('conversion : invité permanent, session, historique et statistiques préservés après validation', () => {
    const accounts = store();
    const guest = accounts.claimGuest('guest-phone').account;
    guest.stats.tripCount = 12;
    guest.trips.push({ id: 'old-trip' });
    const token = accounts.issueToken(guest.id);
    const request = { ...input('ConvertDriver'), guest };
    const pending = accounts.beginRegistration(request);
    assert.equal(guest.role, 'guest');
    assert.equal(accounts.claimGuest('guest-phone').account, guest);
    assert.equal(accounts.resolveToken(token), guest);
    assert.equal(accounts.completeRegistration(request.email, pending.draft.token, pending.code).error, 'guest session required');
    const other = accounts.claimGuest('other-phone').account;
    assert.equal(accounts.completeRegistration(request.email, pending.draft.token, pending.code, other).error, 'guest session required');
    const done = accounts.completeRegistration(request.email, pending.draft.token, pending.code, guest);
    assert.equal(done.account, guest);
    assert.equal(done.converted, true);
    assert.equal(accounts.resolveToken(token), guest);
    assert.equal(guest.emailVerified, true);
    assert.equal(guest.stats.tripCount, 12);
    assert.deepEqual(guest.trips, [{ id: 'old-trip' }]);
  });

  it('abandon et expiration ne changent jamais le compte invité', async () => {
    const accounts = store();
    const guest = accounts.claimGuest('guest-kept').account;
    guest.createdAt = '2020-01-01T00:00:00Z';
    const request = { ...input('ExpiredDriver'), guest };
    const pending = accounts.beginRegistration(request);
    assert.equal(accounts.completeRegistration(request.email, pending.draft.token, pending.code, guest, pending.draft.expiresAt).error, 'registration expired');
    assert.equal(guest.role, 'guest');
    assert.equal(await accounts.purge(), 0);
    assert.equal(accounts.claimGuest('guest-kept').account, guest);
    assert.equal(accounts.accessFor(guest).canNavigate, true);
  });

  it('cinq erreurs invalident le brouillon ; faux jeton ne consomme aucun essai', async () => {
    const accounts = store();
    const request = input('LimitedDriver');
    const pending = accounts.beginRegistration(request);
    assert.equal(accounts.completeRegistration(request.email, 'wrong-token', pending.code).error, 'registration expired');
    assert.equal(pending.draft.failures, 0);
    for (let n = 0; n < 5; n++) {
      assert.equal(accounts.completeRegistration(request.email, pending.draft.token, wrong(pending.code)).error, 'invalid code');
    }
    assert.equal(accounts.completeRegistration(request.email, pending.draft.token, pending.code).error, 'registration expired');
    assert.equal(accounts.byId.size, 0);
    await accounts.save();
    const restored = new AccountStore({ accountsFile: accounts.accountsFile });
    restored.scheduleSave = () => {};
    await restored.load();
    assert.equal(restored.registrations.size, 0);
  });

  it('renvoi limité, ancienne demande remplacée ; aucun mot de passe brut persistant', async () => {
    const accounts = store();
    const request = input('PersistDriver');
    const first = accounts.beginRegistration(request);
    assert.equal(accounts.resendRegistration(request.email, first.draft.token).error, 'verification code recently sent');
    assert.equal(accounts.beginRegistration(request).error, 'verification code recently sent');
    first.draft.sentAt -= 61_000;
    const second = accounts.beginRegistration({ ...request, password: 'owner-password' });
    assert.notEqual(second.draft.token, first.draft.token);
    assert.equal(accounts.completeRegistration(request.email, first.draft.token, first.code).error, 'registration expired');
    await accounts.save();
    const raw = readFileSync(`${accounts.accountsFile}.registrations.json`, 'utf8');
    assert.equal(raw.includes(request.password), false);
    assert.equal(raw.includes('owner-password'), false);
    const restored = new AccountStore({ accountsFile: accounts.accountsFile });
    restored.scheduleSave = () => {};
    await restored.load();
    const done = restored.completeRegistration(request.email, second.draft.token, second.code);
    assert.equal(done.account.emailVerified, true);
    assert.equal(restored.login(request.email, request.password).error, 'invalid credentials');
    assert.equal(restored.login(request.email, 'owner-password').account, done.account);
  });

  it('nom et email revérifiés avant création ; aucun écrasement de compte', () => {
    const accounts = store();
    const request = input('RaceDriver');
    const pending = accounts.beginRegistration(request);
    const existing = accounts.signInWithProvider({ ...google('race-owner', request.email), name: 'DifferentName' }).account;
    assert.equal(accounts.completeRegistration(request.email, pending.draft.token, pending.code).error, 'email already registered');
    assert.equal(accounts.byId.size, 1);
    assert.equal(accounts.byEmail.get(request.email), existing);
    const namePending = accounts.beginRegistration(input('NameDriver'));
    accounts.register({ ...input('NameDriver'), email: 'other@example.test' });
    assert.match(accounts.completeRegistration(namePending.draft.email, namePending.draft.token, namePending.code).error, /username/);
  });

  it('HTTP : 202 sans session, mauvais code refusé, bon code crée un membre vérifié', async () => {
    const request = input('HttpNewDriver');
    const pending = await call(server, 'POST', '/api/accounts/register', { body: { ...request, emailVerified: true, passwordHash: 'injected' } });
    assert.equal(pending.status, 202);
    assert.equal(pending.json.account, undefined);
    assert.equal(pending.json.token, undefined);
    assert.equal(accountStore.byEmail.has(request.email), false);
    assert.equal(sent.at(-1).email, request.email);
    const body = { email: request.email, registrationToken: pending.json.registration.registrationToken, code: 'not-a-code' };
    assert.equal((await call(server, 'POST', '/api/accounts/verify', { body })).status, 400);
    body.code = sent.at(-1).code;
    const done = await call(server, 'POST', '/api/accounts/verify', { body });
    assert.equal(done.status, 201);
    assert.equal(done.json.account.emailVerified, true);
    assert.ok(done.json.token);
    assert.equal(accountStore.login(request.email, request.password).account.id, done.json.account.id);
    assert.equal((await call(server, 'POST', '/api/accounts/verify', { body })).status, 400);
  });

  it('HTTP : entrées iOS dédiées partagent même vérification, sans création anticipée', async () => {
    const direct = await call(server, 'POST', '/api/accounts/registration', { body: input('IOSNewDriver') });
    assert.equal(direct.status, 202);
    assert.equal(accountStore.byEmail.has('iosnewdriver@example.test'), false);
    const guest = accountStore.claimGuest('ios-conversion-phone').account;
    const token = accountStore.issueToken(guest.id);
    const converted = await call(server, 'POST', '/api/accounts/me/registration', {
      headers: { authorization: `Bearer ${token}` }, body: input('IOSConvertDriver') });
    assert.equal(converted.status, 202);
    assert.equal(converted.json.registration.guestId, guest.id);
    assert.equal(guest.role, 'guest');
    assert.equal(accountStore.resolveToken(token), guest);
  });

  it('HTTP : email non livré, aucun faux succès ni compte créé', async () => {
    delivers = false;
    try {
      const request = input('MailFailure');
      const response = await call(server, 'POST', '/api/accounts/register', { body: request });
      assert.equal(response.status, 503);
      assert.equal(accountStore.byEmail.has(request.email), false);
      assert.equal(accountStore.registrations.has(request.email), false);
    } finally { delivers = true; }
  });

  it('HTTP : renvoi après 60 s, nouveau code seul accepté, compteur remis à zéro', async () => {
    const request = input('ResendNewDriver');
    const pending = await call(server, 'POST', '/api/accounts/register', { body: request });
    const body = { email: request.email, registrationToken: pending.json.registration.registrationToken };
    assert.equal((await call(server, 'POST', '/api/accounts/resend-verify', { body })).status, 429);
    const draft = accountStore.registrations.get(request.email);
    draft.sentAt -= 61_000;
    draft.code = '000000';
    draft.failures = 3;
    const resent = await call(server, 'POST', '/api/accounts/resend-verify', { body });
    assert.equal(resent.status, 202);
    assert.equal(resent.json.registration.registrationToken, body.registrationToken);
    assert.equal(draft.failures, 0);
    assert.equal(sent.at(-1).code, draft.code);
    assert.equal((await call(server, 'POST', '/api/accounts/verify', { body: { ...body, code: '000000' } })).status, 400);
    assert.equal((await call(server, 'POST', '/api/accounts/verify', { body: { ...body, code: sent.at(-1).code } })).status, 201);
  });

  it('parrainage consommé seulement après vérification ; plus aucun droit accordé au brouillon', () => {
    const accounts = store();
    const admin = accounts.create({ role: 'admin', username: 'ReferralOwner', email: 'ref-owner@example.test', password: 'password-123' }).account;
    const referral = accounts.createReferral(admin.id).referral;
    const pending = accounts.beginRegistration({ ...input('ReferredDriver'), referralCode: referral.code });
    assert.equal(referral.redemptions.length, 0);
    assert.equal(accounts.byId.size, 1);
    const done = accounts.completeRegistration(pending.draft.email, pending.draft.token, pending.code);
    assert.equal(done.account.emailVerified, true);
    assert.equal(referral.redemptions.length, 1);
    assert.equal(done.account.referredByCode, referral.code);
  });

  it('HTTP : anciens comptes non vérifiés gardent leur connexion ; invités toujours accessibles', async () => {
    const request = input('LegacyDriver');
    const legacy = accountStore.register(request).account;
    assert.equal(legacy.emailVerified, false);
    const login = await call(server, 'POST', '/api/accounts/login', { body: request });
    assert.equal(login.status, 200);
    assert.equal(login.json.account.emailVerified, false);
    const guest = await call(server, 'POST', '/api/accounts/guest', { body: { deviceId: 'http-permanent-guest' } });
    assert.equal(guest.status, 201);
    assert.equal(guest.json.account.role, 'guest');
    assert.equal(guest.json.account.canNavigate, true);
  });

  it('HTTP : session convertie révoquée pendant sauvegarde, aucun jeton périmé renvoyé', async () => {
    const guest = accountStore.claimGuest('revoked-conversion').account;
    const token = accountStore.issueToken(guest.id);
    const request = input('RevokedConvert');
    const pending = accountStore.beginRegistration({ ...request, guest });
    const entered = deferred();
    const release = deferred();
    const save = accountStore.save;
    accountStore.save = async () => { entered.resolve(); await release.promise; };
    try {
      const completing = call(server, 'POST', '/api/accounts/verify', { headers: { authorization: `Bearer ${token}` },
        body: { email: request.email, registrationToken: pending.draft.token, code: pending.code } });
      await entered.promise;
      accountStore.revokeToken(token);
      release.resolve();
      const response = await completing;
      assert.equal(response.status, 401);
      assert.equal(response.json.token, undefined);
    } finally { release.resolve(); accountStore.save = save; }
  });

  it('suppression invité efface aussi son inscription en attente', () => {
    const accounts = store();
    const guest = accounts.claimGuest('deleted-pending').account;
    const request = input('DeletedPending');
    accounts.beginRegistration({ ...request, guest });
    assert.equal(accounts.deleteAccount(guest.id).removed, true);
    assert.equal(accounts.registrations.has(request.email), false);
  });
});

describe('Google : email non vérifié et index stable', () => {
  it('inconnu sans email vérifié : rejet, aucun doublon ni perturbation du mot de passe', () => {
    const accounts = store();
    const request = input('OwnerDriver');
    const owner = accounts.register(request).account;
    for (const email of [request.email, null, 'new@example.test']) {
      assert.equal(accounts.signInWithProvider(google('unverified', email, false)).error, 'verified email required');
    }
    assert.equal(accounts.byId.size, 1);
    assert.equal(accounts.byEmail.get(request.email), owner);
    assert.equal(accounts.login(request.email, request.password).account, owner);
  });

  it('conversion Google : preuve obligatoire, même identité invité, email vérifié et indexé', () => {
    const accounts = store();
    const guest = accounts.claimGuest('google-guest').account;
    const token = accounts.issueToken(guest.id);
    guest.stats.tripCount = 8;
    assert.equal(accounts.linkProvider(guest.id, google('guest-google', 'guest@example.test', false)).error, 'verified email required');
    assert.equal(guest.role, 'guest');
    assert.equal(accounts.linkProvider(guest.id, google('guest-google', 'guest@example.test')).account, guest);
    assert.equal(guest.emailVerified, true);
    assert.equal(accounts.byEmail.get('guest@example.test'), guest);
    assert.equal(accounts.resolveToken(token), guest);
    assert.equal(guest.stats.tripCount, 8);
  });

  it('conversion Google ne double pas un email existant ; ancien fournisseur conserve son accès', () => {
    const accounts = store();
    const owner = accounts.register(input('ExistingOwner')).account;
    const guest = accounts.claimGuest('existing-guest').account;
    assert.equal(accounts.linkProvider(guest.id, google('new-provider', owner.email)).error, 'email already registered');
    assert.equal(guest.role, 'guest');
    accounts.linkProvider(owner.id, google('old-provider', owner.email, false));
    assert.equal(accounts.signInWithProvider(google('old-provider', owner.email, false)).account, owner);
  });

  it('anciens doublons non vérifiés : index ne remplace pas le compte email initial', () => {
    const accounts = store();
    const request = input('IndexedOwner');
    const owner = accounts.register(request).account;
    const duplicate = { id: 'old-duplicate', role: 'client', email: request.email, emailVerified: false,
      providers: [{ provider: 'google', subject: 'old-unverified', email: request.email }] };
    accounts.index(duplicate);
    assert.equal(accounts.byEmail.get(request.email), owner);
    accounts.unindex(duplicate);
    assert.equal(accounts.byEmail.get(request.email), owner);
    assert.equal(accounts.login(request.email, request.password).account, owner);
  });

  it('suppression du compte principal : ancien doublon restant toujours indexé, sans nouvel écrasement', () => {
    const accounts = store();
    const owner = accounts.register(input('RemainingOwner')).account;
    const duplicate = { id: 'remaining-duplicate', role: 'client', email: owner.email, emailVerified: false,
      providers: [{ provider: 'google', subject: 'remaining-google', email: owner.email }] };
    accounts.index(duplicate);
    accounts.remove(owner.id);
    assert.equal(accounts.byEmail.get(owner.email), duplicate);
    assert.equal(accounts.register({ ...input('NewOwner'), email: owner.email }).error, 'email already registered');
    assert.equal(accounts.signInWithProvider(google('remaining-google', owner.email, false)).account, duplicate);
  });
});
