import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { call, trapNetwork } from './helpers/routing.js';

const directory = mkdtempSync(join(tmpdir(), 'eona-avatars-'));
process.env.ACCOUNTS_FILE = join(directory, 'http.json');
const { AccountStore, accountStore } = await import('../src/accounts/store.js');
const { accountRouter } = await import('../src/accounts/routes.js');
const { publicView } = await import('../src/accounts/auth.js');
const { safeAvatarUrl } = await import('../src/accounts/avatar-url.js');
const { config } = await import('../src/config.js');
const { groupStore, groupView } = await import('../src/trips/groups.js');
accountStore.scheduleSave = () => {};
trapNetwork(before, after);
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

describe('avatars : origine EONA seulement', () => {
  it('photo propre et cache admis ; origine, chemin, propriétaire et paramètres stricts', () => {
    const id = 'driver-123';
    const base = config.publicBaseUrl;
    for (const ext of ['jpg', 'png', 'webp']) {
      const url = `${base}/avatars/${id}.${ext}?v=1792430000`;
      assert.equal(safeAvatarUrl(url, id), url);
    }
    const host = new URL(base).host;
    for (const url of [null, {}, '', '//external.test/photo.png', 'https://external.test/photo.png',
      `https://${host}.external.test/avatars/${id}.jpg`, `${base}/avatars/another.jpg`,
      `${base}/avatars/${id}.svg`, `${base}/avatars/${id}.jpg/extra`, `${base}/api/avatar/${id}.jpg`,
      `${base}/avatars/${id}.jpg?url=https://external.test/`, `${base}/avatars/${id}.jpg?v=1&v=2`,
      `${base}/avatars/${id}.jpg#external`, `${base}/avatars/${id}.jpg?v=word`,
      `${new URL(base).protocol}//user:pass@${host}/avatars/${id}.jpg`]) {
      assert.equal(safeAvatarUrl(url, id), null);
    }
  });

  it('anciennes URLs externes effacées à la lecture ; ancienne origine EONA migrée', async () => {
    const file = join(directory, 'old.json');
    writeFileSync(file, JSON.stringify([
      { id: 'external', role: 'client', avatarUrl: 'https://tracker.test/image.jpg' },
      { id: 'malformed', role: 'client', avatarUrl: { bad: true } },
      { id: 'legacy', role: 'client', avatarUrl: `${config.legacyPublicBaseUrls[0]}/avatars/legacy.jpg?v=1` },
    ]));
    const accounts = new AccountStore({ accountsFile: file });
    accounts.scheduleSave = () => {};
    await accounts.load();
    assert.equal(accounts.get('external').avatarUrl, null);
    assert.equal(accounts.get('malformed').avatarUrl, null);
    assert.equal(accounts.get('legacy').avatarUrl, `${config.publicBaseUrl}/avatars/legacy.jpg?v=1`);
    await accounts.save();
  });

  it('HTTP : rejet avant toute modification ; photo EONA et suppression acceptées', async () => {
    const account = accountStore.register({ email: 'avatar@example.test', password: 'password-123', username: 'AvatarOwner' }).account;
    const headers = { authorization: `Bearer ${accountStore.issueToken(account.id)}` };
    const rejected = await call(server, 'PATCH', '/api/accounts/me', { headers, body: { username: 'NotChanged', avatarUrl: 'https://tracker.test/image.jpg' } });
    assert.equal(rejected.status, 400);
    assert.equal(account.username, 'AvatarOwner');
    assert.equal(account.avatarUrl, null);
    const avatarUrl = `${config.publicBaseUrl}/avatars/${account.id}.jpg?v=1`;
    const accepted = await call(server, 'PATCH', '/api/accounts/me', { headers, body: { avatarUrl } });
    assert.equal(accepted.status, 200);
    assert.equal(accepted.json.account.avatarUrl, avatarUrl);
    const removed = await call(server, 'PATCH', '/api/accounts/me', { headers, body: { avatarUrl: null } });
    assert.equal(removed.json.account.avatarUrl, null);
  });

  it('sorties publiques et groupes filtrent aussi les anciennes données déjà en mémoire', () => {
    const account = accountStore.register({ email: 'group-avatar@example.test', password: 'password-123', username: 'GroupAvatar' }).account;
    account.avatarUrl = 'https://tracker.test/photo.jpg';
    assert.equal(publicView(account).avatarUrl, null);
    const group = groupStore.open(account, { destination: { lat: 48.85, lon: 2.35 } });
    const member = group.members.get(account.id);
    member.avatarUrl = 'https://tracker.test/old.jpg';
    assert.equal(groupView(group, account.id).members[0].avatarUrl, null);
    assert.equal(accountStore.cardFor(account.id).avatarUrl, null);
    groupStore.forget(group);
  });
});
