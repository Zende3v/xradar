import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, describe, it } from 'node:test';

// No opt-in here, but a traffic key: the search must still send nothing to TomTom.
delete process.env.SEARCH_TOMTOM_ENABLED;
delete process.env.SEARCH_TOMTOM_DAILY_MAX;
delete process.env.SEARCH_TOMTOM_MONTHLY_MAX;
process.env.TOMTOM_API_KEY = 'traffic-key-for-tests';

// No request ever leaves the machine: the real fetch is swapped for a trap.
const realFetch = globalThis.fetch;
let strayRequests = 0;
globalThis.fetch = async () => {
  strayRequests += 1;
  throw new Error('network forbidden in these tests');
};

const { config } = await import('../src/config.js');
const { createSearchBudget } = await import('../src/search/budget.js');
const { createTomtomPlaces, tomtomPlace, tomtomPlaces } = await import('../src/search/tomtom.js');

const dir = mkdtempSync(join(tmpdir(), 'eona-search-'));
after(() => {
  globalThis.fetch = realFetch;
  rmSync(dir, { recursive: true, force: true });
});

// ---- Fixtures: what TomTom POI Search answered Codex on 27/09/2026 (the fields read) -----------

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

let files = 0;
const newPath = () => join(dir, `usage-${++files}.json`);
const at = (iso) => () => new Date(iso);
const saved = (path) => JSON.parse(readFileSync(path, 'utf8'));

function answer(status, json = {}) {
  return { ok: status >= 200 && status < 300, status, json: async () => json };
}

function client(overrides = {}) {
  const calls = [];
  const places = createTomtomPlaces({
    enabled: true,
    apiKey: 'search-key-for-tests',
    baseUrl: 'https://api.tomtom.com/',
    maxResults: 10,
    timeoutMs: 1000,
    pauseMs: 60_000,
    blockMs: 3_600_000,
    budget: createSearchBudget({ path: newPath(), dailyMax: 100, monthlyMax: 3000 }),
    fetch: async (url, init) => {
      calls.push({ url, init });
      return answer(200, { results: [LECLERC_ORLY] });
    },
    ...overrides,
  });
  return { places, calls };
}

// ---- Opt-in ------------------------------------------------------------------------------------

describe('TomTom POI Search opt-in', () => {
  it('is off by default, even with the traffic key: nothing sent, no budget touched', async () => {
    assert.equal(config.searchTomtomEnabled, false);
    // Under the 2500 free Search API requests a month.
    assert.deepEqual([config.searchTomtomDailyMax, config.searchTomtomMonthlyMax], [100, 2000]);
    assert.equal(tomtomPlaces.available(), false);
    assert.equal(await tomtomPlaces.search('leclerc orly', { lat: 48.79, lon: 2.36 }), null);

    let taken = 0;
    const budget = { left: () => true, take: async () => { taken += 1; return true; }, usage: () => null };
    for (const options of [{ enabled: false }, { apiKey: null }]) {
      const { places, calls } = client({ budget, ...options });
      assert.equal(places.available(), false);
      assert.equal(await places.search('leclerc orly', null), null);
      assert.equal(calls.length, 0);
    }
    assert.equal(taken, 0);
    assert.equal(strayRequests, 0);
  });

  it('reads 0 as a ceiling, not as unset', async () => {
    process.env.SEARCH_TOMTOM_DAILY_MAX = '0';
    const { config: fresh } = await import(`../src/config.js?zero=${Date.now()}`);
    assert.equal(fresh.searchTomtomDailyMax, 0);
    assert.equal(fresh.searchTomtomMonthlyMax, 2000);
    delete process.env.SEARCH_TOMTOM_DAILY_MAX;
  });
});

// ---- Budget ------------------------------------------------------------------------------------

describe('search budget', () => {
  it('keeps its ceilings on disk, per UTC day and per UTC month', async () => {
    const path = newPath();
    const first = createSearchBudget({ path, dailyMax: 2, monthlyMax: 3, now: at('2026-09-27T23:58:00Z') });
    assert.equal(await first.take(), true);
    assert.equal(await first.take(), true);
    assert.equal(await first.take(), false); // the day's 2 are spent
    assert.deepEqual(saved(path), { day: '2026-09-27', month: '2026-09', today: 2, thisMonth: 2 });

    // A restart hands nothing back.
    const again = createSearchBudget({ path, dailyMax: 2, monthlyMax: 3, now: at('2026-09-27T23:59:00Z') });
    assert.equal(again.left(), false);
    assert.equal(await again.take(), false);

    // A new day: 1 left for the month.
    const tomorrow = createSearchBudget({ path, dailyMax: 2, monthlyMax: 3, now: at('2026-09-28T00:01:00Z') });
    assert.equal(await tomorrow.take(), true);
    assert.equal(await tomorrow.take(), false);
    assert.deepEqual(saved(path), { day: '2026-09-28', month: '2026-09', today: 1, thisMonth: 3 });

    // A new month.
    const october = createSearchBudget({ path, dailyMax: 2, monthlyMax: 3, now: at('2026-10-01T00:00:01Z') });
    assert.equal(await october.take(), true);
    assert.deepEqual(saved(path), { day: '2026-10-01', month: '2026-10', today: 1, thisMonth: 1 });
  });

  it('never lets requests arriving together past the ceiling, and writes one at a time', async () => {
    let text = null;
    let writes = 0;
    let writing = 0;
    let mostAtOnce = 0;
    const store = {
      read: () => text,
      write: async (next) => {
        writes += 1;
        writing += 1;
        mostAtOnce = Math.max(mostAtOnce, writing);
        await new Promise((resolve) => setTimeout(resolve, 5));
        text = next;
        writing -= 1;
      },
    };
    const budget = createSearchBudget({ path: 'unused', dailyMax: 5, monthlyMax: 100, store });
    const pending = Array.from({ length: 2 }, () => budget.take()); // together
    for (let i = 0; i < 18; i++) {
      pending.push(budget.take()); // and while a write is under way
      await new Promise((resolve) => setTimeout(resolve, 1));
    }
    const taken = await Promise.all(pending);
    assert.equal(taken.filter(Boolean).length, 5);
    assert.ok(writes >= 2, `${writes} write(s)`);
    assert.equal(mostAtOnce, 1);
    assert.equal(JSON.parse(text).today, 5);
  });

  it('fails closed: a budget it cannot save or read sends nothing', async () => {
    const quiet = console.error;
    console.error = () => {};
    try {
      const unwritable = createSearchBudget({
        path: 'unused', dailyMax: 5, monthlyMax: 100,
        store: { read: () => null, write: async () => { throw new Error('disk full'); } },
      });
      const { places, calls } = client({ budget: unwritable });
      assert.equal(await places.search('fitness park orly', null), null);
      assert.equal(calls.length, 0);

      let writes = 0;
      const unreadable = createSearchBudget({
        path: 'unused', dailyMax: 5, monthlyMax: 100,
        store: { read: () => '{"day":', write: async () => { writes += 1; } },
      });
      assert.equal(unreadable.left(), false);
      assert.equal(await unreadable.take(), false);
      assert.equal(writes, 0);
    } finally {
      console.error = quiet;
    }
  });
});

// ---- The request ----------------------------------------------------------------------------------

describe('TomTom POI Search request', () => {
  it('counts on disk before sending, then sends the typeahead POI Search request', async () => {
    const path = newPath();
    let countedBefore = null;
    const { places, calls } = client({
      budget: createSearchBudget({ path, dailyMax: 100, monthlyMax: 2000 }),
      fetch: async (url, init) => {
        countedBefore = saved(path).today;
        calls.push({ url, init });
        return answer(200, { results: [LECLERC_ORLY, FITNESS_PARK_ORLY, { poi: { name: 'no position' } }] });
      },
    });
    const results = await places.search('leclerc orly', { lat: 48.792123, lon: 2.363456 });
    assert.equal(countedBefore, 1);
    assert.equal(calls.length, 1);
    const { url, init } = calls[0];
    // Around the driver to about 100 m.
    assert.equal(url, 'https://api.tomtom.com/search/2/poiSearch/leclerc%20orly.json'
      + '?key=search-key-for-tests&typeahead=true&limit=10&countrySet=FR&language=fr-FR&lat=48.792&lon=2.363');
    assert.equal(init.method, undefined); // GET
    assert.deepEqual(init.headers, { Accept: 'application/json' });
    assert.deepEqual(results.map((place) => place.name), ['E.Leclerc Orly', 'Fitness Park']);

    // Whatever is typed stays one path segment; no position, none sent.
    await places.search('café/bar #1', null);
    assert.equal(calls[1].url, 'https://api.tomtom.com/search/2/poiSearch/caf%C3%A9%2Fbar%20%231.json'
      + '?key=search-key-for-tests&typeahead=true&limit=10&countrySet=FR&language=fr-FR');
  });

  it('rests after a failure, longer after a refusal', async () => {
    let clock = 1_000_000;
    let status = 503;
    const { places, calls } = client({
      now: () => clock,
      fetch: async (url, init) => {
        calls.push({ url, init });
        return answer(status);
      },
    });
    await assert.rejects(places.search('fitness park orly', null), (e) => e.status === 503);
    assert.equal(places.available(), false);
    assert.equal(await places.search('fitness park orly', null), null);
    assert.equal(calls.length, 1);

    clock += 60_000;
    status = 403;
    await assert.rejects(places.search('fitness park orly', null), (e) => e.status === 403);
    clock += 59 * 60_000;
    assert.equal(await places.search('fitness park orly', null), null);
    clock += 60_000;
    assert.equal(places.available(), true);
    assert.equal(calls.length, 2);
  });

  it('turns both real answers into the search results the apps know', () => {
    assert.deepEqual(tomtomPlace(LECLERC_ORLY), {
      id: 'tomtom:at0XVH8WzRv1ZREAR6Lmow',
      name: 'E.Leclerc Orly',
      subtitle: '8 Place Gaston Viens 94310 Orly',
      lat: 48.7437,
      lon: 2.4075,
      city: 'Orly',
      address: '8 Place Gaston Viens',
      postcode: '94310',
      category: 'Supermarché',
      osmKey: null,
      osmValue: null,
      named: true,
      kind: 0.95,
      source: 'tomtom',
      attribution: '© TomTom',
    });
    assert.deepEqual(tomtomPlace(FITNESS_PARK_ORLY), {
      id: 'tomtom:QDI_NFNa6YE-zI12JWbcJA',
      name: 'Fitness Park',
      subtitle: 'Avenue des Martyrs de Châteaubriant 94310 Orly',
      lat: 48.748551,
      lon: 2.406266,
      city: 'Orly',
      address: 'Avenue des Martyrs de Châteaubriant',
      postcode: '94310',
      category: 'Salle de sport',
      osmKey: null,
      osmValue: null,
      named: true,
      kind: 0.9,
      source: 'tomtom',
      attribution: '© TomTom',
    });
    // Another category of a listed class: the class's label. A class not listed: no label, but it
    // still ranks as a place, above an unknown OSM kind.
    const kind = (poi) => {
      const place = tomtomPlace({ ...LECLERC_ORLY, poi: { name: 'Test', ...poi } });
      return [place.category, place.kind];
    };
    assert.deepEqual(kind({ classifications: [{ code: 'SPORTS_CENTER' }] }), ['Centre sportif', 0.85]);
    assert.deepEqual(kind({ classifications: [{ code: 'SOMETHING_NEW' }] }), [null, 0.85]);
  });
});
