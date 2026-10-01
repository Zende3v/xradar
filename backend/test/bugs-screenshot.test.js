import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { accountStore } from '../src/accounts/store.js';
import { db } from '../src/db.js';
import { bugRouter } from '../src/bugs/routes.js';
import { SCREENSHOT_MAX_BYTES, screenshotBytes } from '../src/bugs/screenshot.js';

const jpeg = Buffer.from("/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAAQABADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q==", 'base64');
const reportId = 'a0000000-0000-0000-0000-000000000001';
const users = { driver: { id: 'driver', role: 'client' }, admin: { id: 'admin', role: 'admin' }, banned: { id: 'banned', role: 'admin', banned: true } };
let stored = null;
let duplicate = false;
let queries = 0;
let server;
const realQuery = db.query;
const realResolve = accountStore.resolveToken;

before(async () => {
  accountStore.resolveToken = token => users[token] ?? null;
  db.query = async (sql, values) => {
    queries++;
    if (sql.includes('bool_or')) return { rows: [{ hour: 0, day: 0, everyone: 0, again: duplicate }] };
    if (sql.startsWith('INSERT')) { stored = values[10]; return { rowCount: 1 }; }
    if (sql.startsWith('UPDATE')) { stored = values[2] ?? stored; return { rows: [{ has_screenshot: stored !== null }] }; }
    if (sql.startsWith('SELECT screenshot')) return { rows: stored ? [{ screenshot: stored }] : [] };
    if (sql.startsWith('SELECT id')) return { rows: [{ id: reportId, status: 'new', category: 'other', description: 'Capture écran', created_at: Date.now(), has_screenshot: stored !== null }] };
    return { rows: [], rowCount: 0 };
  };
  const app = express();
  app.use(express.json({ limit: '2mb' }));
  app.use('/api/bugs', bugRouter);
  server = app.listen(0, '127.0.0.1');
  await once(server, 'listening');
});
after(async () => {
  db.query = realQuery;
  accountStore.resolveToken = realResolve;
  server.closeAllConnections();
  await new Promise(resolve => server.close(resolve));
});
const call = (method, path, token, body) => fetch(`http://127.0.0.1:${server.address().port}/api/bugs${path}`, {
  method, headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
  body: body == null ? undefined : JSON.stringify(body),
});
const report = screenshot => ({ category: 'map', description: 'Tracé manquant sur la carte', ...(screenshot === undefined ? {} : { screenshot }) });

describe('Capture de bug privée', () => {
  it('accepte absence et vrai JPEG ; refuse type, base64, contenu et poids invalides', () => {
    assert.equal(screenshotBytes(undefined), null);
    assert.equal(screenshotBytes(null), null);
    assert.deepEqual(screenshotBytes(jpeg.toString('base64')), jpeg);
    for (const bad of ['', '!!!!', 42, 'abcd', jpeg.subarray(0, -2).toString('base64'), Buffer.alloc(SCREENSHOT_MAX_BYTES + 1).toString('base64')]) {
      assert.throws(() => screenshotBytes(bad));
    }
    const huge = Buffer.from(jpeg);
    const frame = huge.indexOf(Buffer.from([0xff, 0xc0]));
    assert.ok(frame >= 0);
    huge.writeUInt16BE(6000, frame + 7);
    assert.throws(() => screenshotBytes(huge.toString('base64')));
  });
  it('envoie rapport sans capture', async () => {
    stored = null; duplicate = false;
    const response = await call('POST', '', 'driver', report());
    assert.equal(response.status, 201);
    assert.deepEqual(await response.json(), { ok: true, screenshotSaved: false });
    assert.equal(stored, null);
  });
  it('stocke capture et accuse réception', async () => {
    const response = await call('POST', '', 'driver', report(jpeg.toString('base64')));
    assert.equal(response.status, 201);
    assert.equal((await response.json()).screenshotSaved, true);
    assert.deepEqual(stored, jpeg);
  });
  it('refuse capture invalide avant base de données', async () => {
    const before = queries;
    const response = await call('POST', '', 'driver', report('pas une image'));
    assert.equal(response.status, 400);
    assert.equal(queries, before);
  });
  it('réessai conserve capture existante, ajoute capture manquante', async () => {
    duplicate = true; stored = null;
    let response = await call('POST', '', 'driver', report(jpeg.toString('base64')));
    assert.deepEqual(await response.json(), { ok: true, duplicate: true, screenshotSaved: true });
    assert.deepEqual(stored, jpeg);
    response = await call('POST', '', 'driver', report());
    assert.equal((await response.json()).screenshotSaved, true);
    assert.deepEqual(stored, jpeg);
    const replacement = Buffer.from(jpeg);
    replacement[12] ^= 1;
    assert.deepEqual(screenshotBytes(replacement.toString('base64')), replacement);
    response = await call('POST', '', 'driver', report(replacement.toString('base64')));
    assert.equal((await response.json()).screenshotSaved, true);
    assert.deepEqual(stored, replacement);
    duplicate = false;
  });
  it('refuse lecture anonyme, conducteur et administrateur banni', async () => {
    const before = queries;
    for (const who of [null, 'driver', 'banned']) {
      const response = await call('GET', '/' + reportId + '/screenshot', who);
      assert.equal(response.status, 403);
    }
    assert.equal(queries, before);
  });
  it('sert image seulement aux administrateurs, sans cache public', async () => {
    stored = jpeg;
    const response = await call('GET', '/' + reportId + '/screenshot', 'admin');
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'image/jpeg');
    assert.equal(response.headers.get('cache-control'), 'private, no-store');
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), jpeg);
    const listing = await call('GET', '', 'admin');
    const entry = (await listing.json()).reports[0];
    assert.equal(entry.hasScreenshot, true);
    assert.equal(Object.hasOwn(entry, 'screenshot'), false);
  });
  it('retourne 404 pour capture absente et 400 pour identifiant invalide', async () => {
    stored = null;
    assert.equal((await call('GET', '/' + reportId + '/screenshot', 'admin')).status, 404);
    assert.equal((await call('GET', '/invalid/screenshot', 'admin')).status, 400);
  });
});
