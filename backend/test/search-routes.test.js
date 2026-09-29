import assert from 'node:assert/strict';
import { once } from 'node:events';
import { after, describe, it } from 'node:test';

// The search over the Base Adresse Nationale and Photon, which stands in for EONA's own index while
// it cannot answer — here, no database (local-search.test.js tests the index's side).
Object.assign(process.env, {
  PHOTON_URL: 'https://photon.test',
  BAN_URL: 'https://ban.test',
});

// ---- Fixtures ------------------------------------------------------------------------------------

const VILLEJUIF = { lat: 48.7921, lon: 2.3634 }; // the driver, 6 km from Orly
/** The fields the apps know (Android SearchApi.kt, iOS SearchAPI.swift read a part of them). */
const CONTRACT = ['id', 'name', 'subtitle', 'lat', 'lon', 'city', 'address', 'postcode', 'category', 'source', 'distanceM'];

const photon = (lon, lat, properties) => ({ geometry: { coordinates: [lon, lat] }, properties });
const ban = (lon, lat, properties) => ({ geometry: { coordinates: [lon, lat] }, properties });

/** What each source answers, by query: Photon and BAN hold the same-named streets. */
const ANSWERS = {
  'leclerc orly': {
    photon: [
      photon(2.358, 48.81, { name: 'E.Leclerc', street: 'Avenue de Fontainebleau', housenumber: '10', postcode: '94270', city: 'Le Kremlin-Bicêtre', osm_key: 'shop', osm_value: 'supermarket', osm_type: 'N', osm_id: 1 }),
      photon(2.366, 48.7955, { name: 'Avenue du Général Leclerc', postcode: '94800', city: 'Villejuif', osm_key: 'highway', osm_value: 'primary', osm_type: 'W', osm_id: 2 }),
    ],
    ban: [ban(2.396, 48.7475, { id: '94054_1000', name: 'Rue du Général Leclerc', postcode: '94310', city: 'Orly', type: 'street' })],
  },
  '8 place gaston viens orly': {
    photon: [photon(2.4077, 48.7436, { name: 'E.Leclerc', street: 'Place Gaston Viens', housenumber: '8', postcode: '94310', city: 'Orly', osm_key: 'shop', osm_value: 'supermarket', osm_type: 'W', osm_id: 4 })],
    ban: [ban(2.4074, 48.7438, { id: '94054_3000_00008', name: '8 Place Gaston Viens', postcode: '94310', city: 'Orly', type: 'housenumber' })],
  },
  'boulangerie paul orly': {
    ban: [],
    photon: [photon(2.401, 48.745, { name: 'Paul', postcode: '94310', city: 'Orly', osm_key: 'shop', osm_value: 'bakery', osm_type: 'N', osm_id: 5 })],
  },
};
const ELSEWHERE = [ban(2.35, 48.8, { id: '75000_1', name: 'Rue du Test', postcode: '75013', city: 'Paris', type: 'street' })];

// ---- Fake sources: no request leaves the machine ---------------------------------------------------

const realFetch = globalThis.fetch;
const calls = { photon: 0, ban: 0, stray: 0 };
const failures = { photon: 0 }; // Photon's next answers that are an HTTP 500
const reply = (status, json) => ({ ok: status === 200, status, json: async () => json });

globalThis.fetch = async (url, init) => {
  const at = new URL(String(url));
  if (at.hostname === '127.0.0.1') return realFetch(url, init);
  await new Promise((resolve) => setTimeout(resolve, 5)); // a network takes a moment
  if (at.hostname === 'photon.test') {
    calls.photon += 1;
    if (failures.photon > 0) {
      failures.photon -= 1;
      return reply(500, {});
    }
    return reply(200, { features: ANSWERS[at.searchParams.get('q')]?.photon ?? [] });
  }
  if (at.hostname === 'ban.test') {
    calls.ban += 1;
    return reply(200, { features: ANSWERS[at.searchParams.get('q')]?.ban ?? ELSEWHERE });
  }
  calls.stray += 1;
  throw new Error('network forbidden in these tests');
};

const { searchPlaces, searchRouter } = await import('../src/search/routes.js');

after(() => {
  globalThis.fetch = realFetch;
});

const names = (results) => results.map((place) => `${place.source}:${place.name}`);

// ---- Tests -----------------------------------------------------------------------------------------

describe('search', () => {
  it('merges Photon and the BAN, the apps\' fields only', async () => {
    const { results } = await searchPlaces('leclerc orly', VILLEJUIF);
    assert.ok(names(results).includes('ban:Rue du Général Leclerc'));
    assert.ok(names(results).includes('osm:Avenue du Général Leclerc'));
    assert.ok(names(results).includes('osm:E.Leclerc'));
    for (const place of results) assert.deepEqual(Object.keys(place), CONTRACT);
  });

  it('answers the apps in the same JSON as before, not to be stored', async () => {
    const { default: express } = await import('express');
    const { accountStore } = await import('../src/accounts/store.js');
    accountStore.byDevice.set('device-search-test', { id: 'account-search-test' });
    const app = express();
    app.use('/api/search', searchRouter);
    const server = app.listen(0, '127.0.0.1');
    await once(server, 'listening');
    try {
      const base = `http://127.0.0.1:${server.address().port}/api/search`;
      const refused = await realFetch(`${base}?q=leclerc%20orly`);
      assert.equal(refused.status, 401);

      const before = { ...calls };
      const answer = await realFetch(`${base}?q=Leclerc%20Orly&lat=${VILLEJUIF.lat}&lon=${VILLEJUIF.lon}&limit=2&deviceId=device-search-test`);
      assert.equal(answer.status, 200);
      assert.equal(answer.headers.get('cache-control'), 'no-store');
      const json = await answer.json();
      // Photon and the BAN from their cache: the answer says so.
      assert.deepEqual(calls, before);
      assert.deepEqual(Object.keys(json), ['count', 'results', 'cached']);
      assert.equal(json.count, 2);
      for (const place of json.results) assert.deepEqual(Object.keys(place), CONTRACT);
    } finally {
      server.close();
    }
  });

  it('keeps an address typed as an address first', async () => {
    const { results } = await searchPlaces('8 place gaston viens orly', VILLEJUIF);
    assert.deepEqual(names(results), ['ban:8 Place Gaston Viens', 'osm:E.Leclerc']);
  });

  it('keeps what Photon and the BAN answered, never a failure', async () => {
    const logs = [];
    const warn = console.warn;
    // The index missing (no database here) is logged each time; only the sources' failures count.
    console.warn = (...args) => { const line = args.join(' '); if (!line.startsWith('[search] index')) logs.push(line); };
    try {
      failures.photon = 1;
      const before = { ...calls };
      const asked = () => [calls.photon - before.photon, calls.ban - before.ban];
      const first = await searchPlaces('boulangerie paul orly', VILLEJUIF);
      assert.deepEqual(names(first.results), []);
      assert.equal(first.cached, false);
      assert.deepEqual(logs, ['[search] places — HTTP 500']);

      // Photon is asked again; the BAN answered: kept.
      const second = await searchPlaces('boulangerie paul orly', VILLEJUIF);
      assert.deepEqual(asked(), [2, 1]);
      assert.deepEqual(names(second.results), ['osm:Paul']);

      const third = await searchPlaces('boulangerie paul orly', VILLEJUIF);
      assert.deepEqual(asked(), [2, 1]);
      assert.equal(third.cached, true);
    } finally {
      console.warn = warn;
    }
  });

  it('asks Photon and the BAN once for the same question asked twice at once', async () => {
    const before = { ...calls };
    await Promise.all([searchPlaces('pizza hut orly', VILLEJUIF), searchPlaces('pizza hut orly', VILLEJUIF)]);
    assert.deepEqual([calls.photon - before.photon, calls.ban - before.ban], [1, 1]);
    assert.equal(calls.stray, 0);
  });
});
