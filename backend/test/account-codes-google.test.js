import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { createSign, generateKeyPairSync } from 'node:crypto';
import { call, deferred, trapNetwork } from './helpers/routing.js';

const directory = mkdtempSync(join(tmpdir(), 'eona-account-codes-'));
process.env.ACCOUNTS_FILE = join(directory, 'http.json');
// Audience et JWKS de test : aucun appel Google réel, fetch simulé par test.
process.env.GOOGLE_CLIENT_IDS = 'eona-test-client';
process.env.GOOGLE_KEYS_URL = 'https://keys.invalid/oauth2/v3/certs';
const { AccountStore, accountStore } = await import('../src/accounts/store.js');
const { accountRouter } = await import('../src/accounts/routes.js');
const { config } = await import('../src/config.js');
accountStore.scheduleSave = () => {};
trapNetwork(before, after);

let sequence = 0;
function store() {
  const accounts = new AccountStore({ accountsFile: join(directory, `accounts-${sequence++}.json`) });
  accounts.scheduleSave = () => {};
  return accounts;
}
async function reload(accounts) {
  await accounts.save();
  const restored = new AccountStore({ accountsFile: accounts.accountsFile });
  restored.scheduleSave = () => {};
  await restored.load();
  return restored;
}
const google = (subject, email, emailVerified = true) => ({ provider: 'google', subject, email, emailVerified, name: 'Owner' });
const wrong = (code) => String((Number(code) + 1) % 1_000_000).padStart(6, '1');

const app = express();
app.use(express.json());
app.use('/api/accounts', accountRouter);
const server = app.listen(0, '127.0.0.1');
await once(server, 'listening');
after(async () => {
  await new Promise((done) => server.close(done));
  const target = resolve(directory);
  assert.ok(target.startsWith(`${resolve(tmpdir())}${sep}`));
  await rm(target, { recursive: true, force: true });
});

describe('Google : préinscription non vérifiée (audit 19/10)', () => {
  it('propriétaire Google garde compte ; mot de passe, sessions, codes et fournisseur tiers perdus', async () => {
    const accounts = store();
    const pre = accounts.register({ email: 'victim@example.test', password: 'attacker-pass', username: 'Squatter', deviceId: 'attacker-device' }).account;
    pre.stats.tripCount = 7;
    pre.trips = [{ id: 't1' }];
    const trialEndsAt = pre.trialEndsAt;
    const oldToken = accounts.issueToken(pre.id);
    accounts.setVerifyCode(pre.id);
    accounts.linkProvider(pre.id, { provider: 'google', subject: 'attacker-google', email: 'attacker@example.test' });
    accounts.linkProvider(pre.id, { provider: 'apple', subject: 'attacker-apple', email: null });

    const out = accounts.signInWithProvider(google('owner-google', 'Victim@Example.test'), { deviceId: 'owner-device' });
    assert.equal(out.error, undefined);
    assert.equal(out.account, pre);
    assert.equal(out.account.id, pre.id);
    assert.equal(pre.role, 'client');
    assert.equal(pre.trialEndsAt, trialEndsAt);
    assert.equal(pre.stats.tripCount, 7);
    assert.deepEqual(pre.trips, [{ id: 't1' }]);
    assert.equal(pre.emailVerified, true);
    assert.equal(pre.passwordHash, null);
    assert.equal(pre.verifyCode, undefined);
    assert.equal(pre.suspended, false);
    assert.equal(accounts.resolveToken(oldToken), null);
    assert.equal(accounts.login('victim@example.test', 'attacker-pass').error, 'invalid credentials');
    assert.deepEqual(pre.providers.map((l) => `${l.provider}:${l.subject}`), ['google:owner-google']);
    assert.equal(accounts.byProviderIdentity('google', 'attacker-google'), null);
    assert.equal(accounts.byProviderIdentity('apple', 'attacker-apple'), null);
    assert.equal(accounts.signInWithProvider(google('attacker-google', 'attacker@example.test')).account.id === pre.id, false);
    const token = accounts.issueToken(pre.id);
    assert.equal(accounts.resolveToken(token), pre);
    // Index propre après redémarrage.
    const restored = await reload(accounts);
    assert.equal(restored.byProviderIdentity('google', 'owner-google').id, pre.id);
    assert.equal(restored.byProviderIdentity('apple', 'attacker-apple'), null);
    assert.equal(restored.get(pre.id).passwordHash, null);
  });

  it('ancien compte déjà auto-lié, email local non vérifié : nettoyé au prochain passage Google', () => {
    const accounts = store();
    const pre = accounts.register({ email: 'old@example.test', password: 'attacker-pass', username: 'OldSquat' }).account;
    accounts.linkProvider(pre.id, { provider: 'google', subject: 'owner-google', email: 'old@example.test' });
    accounts.linkProvider(pre.id, { provider: 'apple', subject: 'third', email: null });
    const oldToken = accounts.issueToken(pre.id);
    const out = accounts.signInWithProvider(google('owner-google', 'old@example.test'));
    assert.equal(out.account, pre);
    assert.equal(out.linked, false);
    assert.equal(pre.passwordHash, null);
    assert.equal(pre.emailVerified, true);
    assert.equal(accounts.resolveToken(oldToken), null);
    assert.deepEqual(pre.providers.map((l) => l.subject), ['owner-google']);
  });

  it('compte vérifié ou preuve absente : comportement inchangé', () => {
    const accounts = store();
    const ok = accounts.register({ email: 'real@example.test', password: 'real-password', username: 'RealOne' }).account;
    ok.emailVerified = true;
    accounts.linkProvider(ok.id, { provider: 'apple', subject: 'legit-apple', email: null });
    const token = accounts.issueToken(ok.id);
    const out = accounts.signInWithProvider(google('g-real', 'real@example.test'));
    assert.equal(out.account, ok);
    assert.equal(out.linked, true);
    assert.equal(accounts.resolveToken(token), ok);
    assert.equal(accounts.login('real@example.test', 'real-password').account, ok);
    assert.equal(accounts.byProviderIdentity('apple', 'legit-apple'), ok);
    // Fournisseur connu légitime, compte vérifié : aucune révocation.
    assert.equal(accounts.signInWithProvider(google('g-real', 'real@example.test')).account, ok);
    assert.equal(accounts.resolveToken(token), ok);

    // Email Google non vérifié : aucun doublon, aucun rattachement, aucun nettoyage.
    const pre = accounts.register({ email: 'unproven@example.test', password: 'pre-password', username: 'Unproven' }).account;
    const other = accounts.signInWithProvider(google('g-x', 'unproven@example.test', false));
    assert.equal(other.error, 'verified email required');
    assert.equal(accounts.byEmail.get(pre.email), pre);
    assert.ok(pre.passwordHash);
    assert.deepEqual(pre.providers, []);
    assert.equal(pre.emailVerified, false);
  });
});

describe('codes compte : crypto, durée, échecs, usage unique (audit 19/10)', () => {
  it('vérification : cinq échecs tuent le code, rejeu refusé, persistance', async () => {
    const accounts = store();
    const a = accounts.register({ email: 'v@example.test', password: 'password-123', username: 'Verif' }).account;
    const code = accounts.setVerifyCode(a.id);
    assert.match(code, /^\d{6}$/);
    assert.equal(a.verifyExpiresAt - a.verifySentAt, 30 * 60 * 1000);
    for (let i = 0; i < 4; i++) assert.equal(accounts.verifyEmail('v@example.test', wrong(code)).error, 'invalid code');
    const restored = await reload(accounts);
    const b = restored.get(a.id);
    assert.equal(b.verifyFailures, 4);
    assert.equal(restored.verifyEmail('v@example.test', wrong(code)).error, 'invalid code');
    assert.equal(b.verifyCode, undefined);
    assert.equal(restored.verifyEmail('v@example.test', code).error, 'invalid code');
    assert.equal(b.emailVerified, false);
  });

  it('vérification : succès unique, expiration et anciens codes sans date invalidés', async () => {
    const accounts = store();
    const a = accounts.register({ email: 'w@example.test', password: 'password-123', username: 'Verif2' }).account;
    const code = accounts.setVerifyCode(a.id);
    assert.equal(accounts.verifyEmail('w@example.test', code).account, a);
    assert.equal(a.emailVerified, true);
    assert.equal(accounts.verifyEmail('w@example.test', code).error, 'invalid code');
    assert.equal(a.verifyFailures, undefined);

    const c = accounts.register({ email: 'x@example.test', password: 'password-123', username: 'Verif3' }).account;
    const late = accounts.setVerifyCode(c.id);
    c.verifyExpiresAt = Date.now() - 1;
    assert.equal(accounts.verifyEmail('x@example.test', late).error, 'code expired');
    assert.equal(c.verifyCode, undefined);

    const d = accounts.register({ email: 'y@example.test', password: 'password-123', username: 'Legacy' }).account;
    d.verifyCode = '123456';
    delete d.verifyExpiresAt;
    const restored = await reload(accounts);
    assert.equal(restored.get(d.id).verifyCode, undefined);
    assert.equal(restored.verifyEmail('y@example.test', '123456').error, 'invalid code');
  });

  it('renvoi : 60 s entre émissions par compte et type ; nouveau code remplace ancien', () => {
    const accounts = store();
    const a = accounts.register({ email: 'r@example.test', password: 'password-123', username: 'Resend' }).account;
    const first = accounts.setVerifyCode(a.id);
    assert.equal(accounts.resendVerify('r@example.test'), null);
    assert.ok(accounts.startReset('r@example.test'));
    assert.equal(accounts.startReset('r@example.test'), null);
    a.verifySentAt -= 61_000;
    const second = accounts.resendVerify('r@example.test');
    assert.ok(second);
    assert.equal(a.verifyFailures, 0);
    if (second.code !== first) assert.equal(accounts.verifyEmail('r@example.test', first).error, 'invalid code');
    assert.equal(accounts.verifyEmail('r@example.test', second.code).account, a);
    assert.equal(accounts.resendVerify('nobody@example.test'), null);
  });

  it('réinitialisation : mot de passe court ne consomme rien ; succès révoque sessions sans suspendre', () => {
    const accounts = store();
    const a = accounts.register({ email: 'p@example.test', password: 'old-password', username: 'Reset' }).account;
    const tokens = [accounts.issueToken(a.id), accounts.issueToken(a.id)];
    const { code } = accounts.startReset('p@example.test');
    assert.equal(accounts.resetPassword('p@example.test', code, 'short').error, 'password too short (min 8)');
    assert.equal(a.resetFailures, 0);
    assert.equal(accounts.resetPassword('p@example.test', wrong(code), 'new-password').error, 'invalid code');
    assert.equal(a.resetFailures, 1);
    assert.equal(accounts.resetPassword('p@example.test', code, 'new-password').account, a);
    for (const token of tokens) assert.equal(accounts.resolveToken(token), null);
    assert.equal(accounts.blockReason(a), null);
    assert.equal(a.suspended, false);
    assert.equal(a.role, 'client');
    for (const field of ['resetCode', 'resetExpiresAt', 'resetFailures', 'resetSentAt']) assert.equal(field in a, false);
    assert.equal(accounts.resetPassword('p@example.test', code, 'other-password').error, 'invalid code');
    assert.equal(accounts.login('p@example.test', 'old-password').error, 'invalid credentials');
    const login = accounts.login('p@example.test', 'new-password');
    assert.equal(login.account, a);
    assert.equal(accounts.resolveToken(accounts.issueToken(a.id)), a);
  });

  it('réinitialisation : plafond cinq échecs, expiration 30 min, compte inconnu', () => {
    const accounts = store();
    const a = accounts.register({ email: 'q@example.test', password: 'old-password', username: 'Reset2' }).account;
    const { code } = accounts.startReset('q@example.test');
    for (let i = 0; i < 5; i++) assert.equal(accounts.resetPassword('q@example.test', wrong(code), 'new-password').error, 'invalid code');
    assert.equal(a.resetCode, undefined);
    assert.equal(accounts.resetPassword('q@example.test', code, 'new-password').error, 'invalid code');
    a.resetSentAt -= 61_000;
    const again = accounts.startReset('q@example.test');
    assert.equal(a.resetExpiresAt - a.resetSentAt, config.resetCodeTtlMs);
    a.resetExpiresAt = Date.now() - 1;
    assert.equal(accounts.resetPassword('q@example.test', again.code, 'new-password').error, 'code expired');
    assert.equal(accounts.login('q@example.test', 'old-password').account, a);
    assert.equal(accounts.startReset('ghost@example.test'), null);
    assert.equal(accounts.resetPassword('ghost@example.test', '123456', 'new-password').error, 'invalid code');
  });

  it('HTTP : forgot et resend restent vagues ; reset puis reconnexion', async () => {
    const accounts = accountStore;
    // Compte ancien, conservé sans vérification obligatoire.
    const legacy = accounts.register({ email: 'h@example.test', password: 'old-password', username: 'HttpUser' }).account;
    const reg = { json: { token: accounts.issueToken(legacy.id) } };
    for (const email of ['h@example.test', 'none@example.test']) {
      for (const path of ['/api/accounts/forgot', '/api/accounts/forgot', '/api/accounts/resend-verify']) {
        const res = await call(server, 'POST', path, { body: { email } });
        assert.equal(res.status, 200);
        assert.deepEqual(res.json, { ok: true });
      }
    }
    const a = accounts.byEmail.get('h@example.test');
    const code = a.resetCode;
    assert.match(code, /^\d{6}$/);
    const me = (token) => call(server, 'GET', '/api/accounts/me', { headers: { authorization: `Bearer ${token}` } });
    assert.equal((await me(reg.json.token)).status, 200);
    const done = await call(server, 'POST', '/api/accounts/reset', { body: { email: 'h@example.test', code, password: 'new-password' } });
    assert.equal(done.status, 200);
    assert.equal((await me(reg.json.token)).status, 401);
    const login = await call(server, 'POST', '/api/accounts/login', { body: { email: 'h@example.test', password: 'new-password' } });
    assert.equal(login.status, 200);
    assert.equal((await me(login.json.token)).status, 200);
    const replay = await call(server, 'POST', '/api/accounts/reset', { body: { email: 'h@example.test', code, password: 'other-password' } });
    assert.equal(replay.status, 400);
  });
});

describe('HTTP liaison Google : session revérifiée après attente JWKS (revue 19/10)', () => {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const baseJwk = { ...publicKey.export({ format: 'jwk' }), alg: 'RS256', use: 'sig' };
  let kids = 0;

  // Jeton RS256 signé localement ; kid neuf force fetch JWKS, cache contourné.
  function idToken(sub, email) {
    const kid = `test-kid-${kids++}`;
    const now = Math.floor(Date.now() / 1000);
    const part = (value) => Buffer.from(JSON.stringify(value)).toString('base64url');
    const head = `${part({ alg: 'RS256', kid, typ: 'JWT' })}.${part({
      iss: 'https://accounts.google.com', aud: 'eona-test-client', sub, email, email_verified: true, iat: now, exp: now + 600,
    })}`;
    const signer = createSign('RSA-SHA256');
    signer.update(head);
    signer.end();
    return { kid, token: `${head}.${signer.sign(privateKey).toString('base64url')}` };
  }

  // Fetch JWKS suspendu jusqu'à `gate.resolve()` ; `reached` dit requête en attente.
  async function suspendedLink(bearer, sub, email, during) {
    const trap = globalThis.fetch;
    const { kid, token } = idToken(sub, email);
    const gate = deferred();
    const reached = deferred();
    const urls = [];
    globalThis.fetch = async (url) => {
      urls.push(String(url));
      reached.resolve();
      await gate.promise;
      return { ok: true, status: 200, json: async () => ({ keys: [{ ...baseJwk, kid }] }) };
    };
    try {
      const pending = call(server, 'POST', '/api/accounts/me/link/google', {
        headers: { authorization: `Bearer ${bearer}` },
        body: { idToken: token },
      });
      await reached.promise;
      await during();
      gate.resolve();
      const res = await pending;
      assert.deepEqual(urls, [config.googleKeysUrl]);
      return res;
    } finally {
      globalThis.fetch = trap;
    }
  }

  it('récupération propriétaire pendant attente : 401, aucun lien attaquant', async () => {
    const pre = accountStore.register({ email: 'victim-link@example.test', password: 'attacker-pass', username: 'LinkSquat' }).account;
    const oldBearer = accountStore.issueToken(pre.id);
    const res = await suspendedLink(oldBearer, 'attacker-link-google', 'attacker-link@example.test', () => {
      const owner = accountStore.signInWithProvider(google('owner-link-google', 'victim-link@example.test'));
      assert.equal(owner.account, pre);
      assert.equal(accountStore.resolveToken(oldBearer), null);
    });
    assert.equal(res.status, 401);
    assert.deepEqual(res.json, { error: 'unauthorized' });
    assert.equal(accountStore.byProviderIdentity('google', 'attacker-link-google'), null);
    assert.deepEqual(pre.providers.map((l) => `${l.provider}:${l.subject}`), ['google:owner-link-google']);
    assert.equal(accountStore.byProviderIdentity('google', 'owner-link-google'), pre);
  });

  it('reset mot de passe pendant attente : 401, aucun fournisseur ajouté', async () => {
    const a = accountStore.register({ email: 'reset-link@example.test', password: 'old-password', username: 'ResetLink' }).account;
    const stolen = accountStore.issueToken(a.id);
    const res = await suspendedLink(stolen, 'attacker-reset-google', 'attacker-reset@example.test', () => {
      const { code } = accountStore.startReset('reset-link@example.test');
      assert.equal(accountStore.resetPassword('reset-link@example.test', code, 'new-password').account, a);
    });
    assert.equal(res.status, 401);
    assert.deepEqual(res.json, { error: 'unauthorized' });
    assert.equal(accountStore.byProviderIdentity('google', 'attacker-reset-google'), null);
    assert.deepEqual(a.providers ?? [], []);
  });

  it('témoin : session toujours valide, liaison réussie, contrat JSON inchangé', async () => {
    const a = accountStore.register({ email: 'legit-link@example.test', password: 'legit-password', username: 'LegitLink' }).account;
    const bearer = accountStore.issueToken(a.id);
    const res = await suspendedLink(bearer, 'legit-link-google', 'legit-link@example.test', () => {});
    assert.equal(res.status, 200);
    assert.deepEqual(Object.keys(res.json), ['account']);
    assert.equal(res.json.account.id, a.id);
    assert.equal(res.json.account.hasPassword, true);
    assert.deepEqual(res.json.account.providers.map((l) => [l.provider, l.email, typeof l.linkedAt]), [['google', 'legit-link@example.test', 'string']]);
    assert.equal(accountStore.byProviderIdentity('google', 'legit-link-google'), a);
    assert.equal(accountStore.resolveToken(bearer), a);
  });
});
