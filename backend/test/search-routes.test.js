import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { once } from 'node:events';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';

// TomTom turned on, with a test key, a budget of DAILY a day in a temporary file; the traffic's
// counter in another one, to show the search never touches it.
const DAILY = 10;
const dir = mkdtempSync(join(tmpdir(), 'eona-search-'));
const usageFile = join(dir, 'search-tomtom-usage.json');
const trafficFile = join(dir, 'tomtom-usage.json');
Object.assign(process.env, {
  SEARCH_TOMTOM_ENABLED: '1',
  SEARCH_TOMTOM_API_KEY: 'search-key-for-tests',
  SEARCH_TOMTOM_USAGE_FILE: usageFile,
  SEARCH_TOMTOM_DAILY_MAX: String(DAILY),
  TOMTOM_USAGE_FILE: trafficFile,
  PHOTON_URL: 'https://photon.test',
  BAN_URL: 'https://ban.test',
});
delete process.env.TOMTOM_URL;

// ---- Fixtures ------------------------------------------------------------------------------------

const VILLEJUIF = { lat: 48.7921, lon: 2.3634 }; // the driver, 6 km from Orly
/** The fields the apps know (Android SearchApi.kt, iOS SearchAPI.swift read a part of them). */
const CONTRACT = ['id', 'name', 'subtitle', 'lat', 'lon', 'city', 'address', 'postcode', 'category', 'source', 'distanceM'];

const photon = (lon, lat, properties) => ({ geometry: { coordinates: [lon, lat] }, properties });
const ban = (lon, lat, properties) => ({ geometry: { coordinates: [lon, lat] }, properties });

// What TomTom POI Search answered Codex on 27/09/2026 (the fields read).
const LECLERC_ORLY = {
  id: 'at0XVH8WzRv1ZREAR6Lmow',
  poi: { name: 'E.Leclerc Orly', categorySet: [{ id: 7332005 }], classifications: [{ code: 'MARKET' }] },
  position: { lat: 48.7437, lon: 2.4075 },
  address: { streetNumber: '8', streetName: 'Place Gaston Viens', municipality: 'Orly', postalCode: '94310', countryCode: 'FR' },
};
const FITNESS_PARK_ORLY = {
  id: 'QDI_NFNa6YE-zI12JWbcJA',
  poi: { name: 'Fitness Park', categorySet: [{ id: 7320002 }], classifications: [{ code: 'SPORTS_CENTER' }] },
  position: { lat: 48.748551, lon: 2.406266 },
  address: { streetName: 'Avenue des Martyrs de Châteaubriant', municipality: 'Orly', postalCode: '94310', countryCode: 'FR' },
};

/** What each source answers, by query: Photon and BAN hold the same-named streets. */
const ANSWERS = {
  'leclerc orly': {
    photon: [
      photon(2.358, 48.81, { name: 'E.Leclerc', street: 'Avenue de Fontainebleau', housenumber: '10', postcode: '94270', city: 'Le Kremlin-Bicêtre', osm_key: 'shop', osm_value: 'supermarket', osm_type: 'N', osm_id: 1 }),
      photon(2.366, 48.7955, { name: 'Avenue du Général Leclerc', postcode: '94800', city: 'Villejuif', osm_key: 'highway', osm_value: 'primary', osm_type: 'W', osm_id: 2 }),
    ],
    ban: [ban(2.396, 48.7475, { id: '94054_1000', name: 'Rue du Général Leclerc', postcode: '94310', city: 'Orly', type: 'street' })],
    tomtom: [LECLERC_ORLY],
  },
  'fitness park orly': {
    photon: [photon(2.392, 48.744, { name: 'Rue du Parc', postcode: '94310', city: 'Orly', osm_key: 'highway', osm_value: 'residential', osm_type: 'W', osm_id: 3 })],
    ban: [ban(2.36, 48.79, { id: '94076_2000', name: 'Allée du Parc', postcode: '94800', city: 'Villejuif', type: 'street' })],
    tomtom: [FITNESS_PARK_ORLY],
  },
  '8 place gaston viens orly': {
    photon: [photon(2.4077, 48.7436, { name: 'E.Leclerc', street: 'Place Gaston Viens', housenumber: '8', postcode: '94310', city: 'Orly', osm_key: 'shop', osm_value: 'supermarket', osm_type: 'W', osm_id: 4 })],
    ban: [ban(2.4074, 48.7438, { id: '94054_3000_00008', name: '8 Place Gaston Viens', postcode: '94310', city: 'Orly', type: 'housenumber' })],
  },
  'boulangerie paul orly': {
    ban: [],
    photon: [photon(2.401, 48.745, { name: 'Paul', postcode: '94310', city: 'Orly', osm_key: 'shop', osm_value: 'bakery', osm_type: 'N', osm_id: 5 })],
    tomtom: [{ ...LECLERC_ORLY, id: 'tt-paul', poi: { name: 'Paul', classifications: [{ code: 'SHOP' }] } }],
  },
};
const ELSEWHERE = [ban(2.35, 48.8, { id: '75000_1', name: 'Rue du Test', postcode: '75013', city: 'Paris', type: 'street' })];

// ---- Fake sources: no request leaves the machine ---------------------------------------------------

const realFetch = globalThis.fetch;
const calls = { photon: 0, ban: 0, tomtom: 0, stray: 0 };
const failures = { photon: 0 }; // Photon's next answers that are an HTTP 500
const reply = (status, json) => ({ ok: status === 200, status, json: async () => json });
const POI_SEARCH = '/search/2/poiSearch/';

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
  if (at.hostname === 'api.tomtom.com' && at.pathname.startsWith(POI_SEARCH) && at.pathname.endsWith('.json')) {
    calls.tomtom += 1;
    const query = decodeURIComponent(at.pathname.slice(POI_SEARCH.length, -'.json'.length)).toLowerCase();
    if (query === 'panne tomtom') {
      // What undici throws for a URL it cannot read: the whole URL, key included, in its message.
      throw new TypeError(`Failed to parse URL from ${url}`, {
        cause: Object.assign(new TypeError(`Invalid URL: ${url}`), { code: 'ERR_INVALID_URL', input: String(url) }),
      });
    }
    return reply(200, { results: ANSWERS[query]?.tomtom ?? [] });
  }
  calls.stray += 1;
  throw new Error('network forbidden in these tests');
};

const { config } = await import('../src/config.js');
// A failure does not rest TomTom here (a minute otherwise): the tests after it still ask it.
config.searchTomtomPauseMs = 0;
const { searchPlaces, searchRouter } = await import('../src/search/routes.js');
const { tomtomUsage } = await import('../src/traffic/budget.js');

after(() => {
  globalThis.fetch = realFetch;
  rmSync(dir, { recursive: true, force: true });
});

const names = (results) => results.map((place) => `${place.source}:${place.name}`);

// ---- Tests -----------------------------------------------------------------------------------------

describe('search with TomTom POI Search', () => {
  it('puts the shop typed with its town before the streets of the same name', async () => {
    const { results } = await searchPlaces('leclerc orly', VILLEJUIF);
    assert.equal(calls.tomtom, 1);
    assert.deepEqual(names(results).slice(0, 1), ['tomtom:E.Leclerc Orly']);
    assert.ok(names(results).includes('ban:Rue du Général Leclerc'));
    assert.ok(names(results).includes('osm:Avenue du Général Leclerc'));
    assert.ok(names(results).includes('osm:E.Leclerc')); // closer, but not in Orly
    assert.deepEqual(results[0], {
      id: 'tomtom:at0XVH8WzRv1ZREAR6Lmow',
      name: 'E.Leclerc Orly',
      subtitle: 'Supermarché · 8 Place Gaston Viens · Orly · © TomTom',
      lat: 48.7437,
      lon: 2.4075,
      city: 'Orly',
      address: '8 Place Gaston Viens',
      postcode: '94310',
      category: 'Supermarché',
      source: 'tomtom',
      distanceM: results[0].distanceM,
    });
    assert.ok(results[0].distanceM > 5000 && results[0].distanceM < 8000);
    for (const place of results) assert.deepEqual(Object.keys(place), CONTRACT);
    // © TomTom under TomTom's results only.
    assert.ok(results.slice(1).every((place) => !place.subtitle.includes('TomTom')));

    const gym = await searchPlaces('fitness park orly', VILLEJUIF);
    assert.equal(calls.tomtom, 2);
    assert.deepEqual(names(gym.results), ['tomtom:Fitness Park', 'osm:Rue du Parc', 'ban:Allée du Parc']);
    assert.equal(gym.results[0].subtitle, 'Salle de sport · Avenue des Martyrs de Châteaubriant · Orly · © TomTom');
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
      // Photon and the BAN from their cache, TomTom asked again: never kept, so never "cached".
      assert.deepEqual(calls, { ...before, tomtom: before.tomtom + 1 });
      assert.deepEqual(Object.keys(json), ['count', 'results']);
      assert.equal(json.count, 2);
      assert.equal(json.results[0].source, 'tomtom');
      for (const place of json.results) assert.deepEqual(Object.keys(place), CONTRACT);
    } finally {
      server.close();
    }
  });

  it('keeps an address typed as an address, without asking TomTom', async () => {
    const before = calls.tomtom;
    const { results } = await searchPlaces('8 place gaston viens orly', VILLEJUIF);
    assert.deepEqual(names(results), ['ban:8 Place Gaston Viens', 'osm:E.Leclerc']);
    await searchPlaces('lec', VILLEJUIF); // too short
    assert.equal(calls.tomtom, before);
  });

  it('keeps what Photon and the BAN answered, never a failure, never TomTom', async () => {
    const logs = [];
    const warn = console.warn;
    console.warn = (...args) => logs.push(args.join(' '));
    try {
      failures.photon = 1;
      const before = { ...calls };
      const asked = () => [calls.photon - before.photon, calls.ban - before.ban, calls.tomtom - before.tomtom];
      const first = await searchPlaces('boulangerie paul orly', VILLEJUIF);
      assert.deepEqual(names(first.results), ['tomtom:Paul']);
      assert.equal(first.cached, false);
      assert.deepEqual(logs, ['[search] photon — HTTP 500']);

      // Photon is asked again; the BAN answered: kept. TomTom is asked each time.
      const second = await searchPlaces('boulangerie paul orly', VILLEJUIF);
      assert.deepEqual(asked(), [2, 1, 2]);
      assert.ok(names(second.results).includes('osm:Paul'));

      const third = await searchPlaces('boulangerie paul orly', VILLEJUIF);
      assert.deepEqual(asked(), [2, 1, 3]);
      assert.equal(third.cached, false);
    } finally {
      console.warn = warn;
    }
  });

  it('asks Photon and the BAN once for the same question asked twice at once, TomTom twice', async () => {
    const before = { ...calls };
    await Promise.all([searchPlaces('pizza hut orly', VILLEJUIF), searchPlaces('pizza hut orly', VILLEJUIF)]);
    assert.deepEqual([calls.photon - before.photon, calls.ban - before.ban, calls.tomtom - before.tomtom], [1, 1, 2]);
  });

  it('logs why TomTom failed, never its URL, its key nor the error itself', async () => {
    const logs = [];
    const { warn, error } = console;
    console.warn = (...args) => logs.push(args.join(' '));
    console.error = (...args) => logs.push(args.join(' '));
    try {
      const before = calls.tomtom;
      const { results } = await searchPlaces('panne tomtom', VILLEJUIF);
      assert.equal(calls.tomtom, before + 1);
      assert.deepEqual(names(results), ['ban:Rue du Test']); // Photon and the BAN still answer
    } finally {
      console.warn = warn;
      console.error = error;
    }
    assert.deepEqual(logs, ['[search] tomtom — unreachable (ERR_INVALID_URL)']);
  });

  it('stops at its own daily ceiling, kept on disk, and never counts as traffic', async () => {
    const warn = console.warn;
    console.warn = () => {};
    try {
      const rounds = DAILY - calls.tomtom + 2;
      for (let i = 1; i <= rounds; i++) {
        const { results } = await searchPlaces(`magasin ${i}`, VILLEJUIF);
        assert.deepEqual(names(results), ['ban:Rue du Test']); // Photon and the BAN still answer
      }
    } finally {
      console.warn = warn;
    }
    assert.equal(calls.tomtom, DAILY);
    assert.equal(JSON.parse(readFileSync(usageFile, 'utf8')).today, DAILY);
    assert.equal(tomtomUsage().used, 0);
    assert.equal(existsSync(trafficFile), false);
    assert.equal(calls.stray, 0);
  });
});
