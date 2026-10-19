import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { once } from 'node:events';
import { fileURLToPath } from 'node:url';
import { after, before, describe, it } from 'node:test';
import express from 'express';
import { call, trapNetwork } from './helpers/routing.js';
import { alongRoute, ROUTE_MAX_CELLS } from '../src/radars/geo.js';
import { radarRouter } from '../src/radars/routes.js';
import { radarStore } from '../src/radars/store.js';

trapNetwork(before, after);
const GEO = new URL('../src/radars/geo.js', import.meta.url).href;
const ROOT = fileURLToPath(new URL('..', import.meta.url));

/** Enfant isolé : tas 64 Mo, chien de garde 2 s. Jamais le serveur réel. */
function isolated(coords) {
  const code = `import(${JSON.stringify(GEO)}).then((g) => {
    try { g.alongRoute([{ lat: 1, lon: 1 }], ${JSON.stringify(coords)}, 150, 10); console.log('done'); }
    catch (e) { console.log(e instanceof RangeError ? 'rejected' : 'error'); }
  });`;
  return spawnSync(process.execPath, ['--max-old-space-size=64', '--input-type=module', '-e', code],
    { cwd: ROOT, timeout: 2000, encoding: 'utf8' });
}

/** Paris → Lyon, un point tous les ~10 m. */
function longRoute(points) {
  const from = [2.35, 48.85], to = [4.83, 45.76];
  return Array.from({ length: points }, (_, i) => {
    const f = i / (points - 1);
    return [from[0] + (to[0] - from[0]) * f, from[1] + (to[1] - from[1]) * f];
  });
}

const app = express();
app.use(express.json({ limit: '3mb' }));
app.use('/api/radars', radarRouter);
const server = app.listen(0, '127.0.0.1');
await once(server, 'listening');
after(() => new Promise((done) => server.close(done)));

describe('radars sur tracé bornés (audit 19/10)', () => {
  it('entrées pathologiques : enfant 64 Mo termine sans OOM ni délai', () => {
    for (const coords of [[[0, 0], [1e12, 1e12]], [[-179, -84], [179, 84]], [[0, 0], [1e300, 0]]]) {
      const out = isolated(coords);
      assert.equal(out.signal, null, `délai dépassé pour ${JSON.stringify(coords)}`);
      assert.equal(out.status, 0, out.stderr);
      assert.match(out.stdout, /^(rejected|done)/);
    }
  });

  it('segment immense mais valide : refus avant toute allocation', () => {
    assert.throws(() => alongRoute([], [[-179, -84], [179, 84]], 150, 10), RangeError);
    assert.throws(() => alongRoute([], [[0, 0], [5, 5]], 150, 10, { maxCells: 100 }), RangeError);
    // Hors bornes ignorés par la fonction : aucune grille, aucun radar.
    assert.deepEqual(alongRoute([{ lat: 0, lon: 0 }], [[0, 0], [1e12, 1e12]], 150, 10), []);
  });

  it('itinéraire long dense préservé, radars dans l\'ordre', () => {
    const coords = longRoute(50_000);
    const radars = [
      { id: 'b', lat: 47.3, lon: 3.59 },
      { id: 'a', lat: 48.5, lon: 2.631 },
      { id: 'far', lat: 47.3, lon: 4.5 },
    ];
    const hits = alongRoute(radars, coords, 500, 10);
    assert.deepEqual(hits.map((r) => r.id), ['a', 'b']);
    assert.ok(hits.every((r) => r.offRouteM <= 500));
    assert.ok(ROUTE_MAX_CELLS >= 400_000);
  });

  it('API : 400 pour structure, type ou bornes invalides ; 200 route normale', async () => {
    radarStore.radars = [{ id: 'r1', lat: 48.5, lon: 2.631 }];
    const bad = [
      { coordinates: [[2, 48]] },
      { coordinates: 'x' },
      { coordinates: [[2, 48], ['3', 47]] },
      { coordinates: [[2, 48], [3]] },
      { coordinates: [[2, 48], [3, 47, 0, 0]] },
      { coordinates: [[2, 48], [181, 47]] },
      { coordinates: [[2, 48], [3, -91]] },
      { coordinates: [[2, 48], [3, 1e308 * 10]] },
      { coordinates: [[2, 48], null] },
      { coordinates: [[2, 48], [3, 47]], buffer: '100' },
      { coordinates: [[-179, -84], [179, 84]] },
      { coordinates: Array.from({ length: 100_001 }, () => [2, 48]) },
    ];
    for (const body of bad) {
      const res = await call(server, 'POST', '/api/radars/route', { body });
      assert.equal(res.status, 400, JSON.stringify(body).slice(0, 80));
    }
    const res = await call(server, 'POST', '/api/radars/route', { body: { coordinates: longRoute(2_000) } });
    assert.equal(res.status, 200);
    assert.deepEqual(res.json.radars.map((r) => r.id), ['r1']);
    assert.equal(res.json.bufferM, 150);
  });
});
