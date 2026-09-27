import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { haversine } from '../radars/geo.js';
import { fold, looksLikeAddress, score } from './rank.js';
import { tomtomPlaces } from './tomtom.js';

export const searchRouter = Router();

/**
 * GET /api/search?q=…&lat=…&lon=…&limit=…
 *
 * What a driver types is rarely an address. "Lycée Adolphe Chérioux vitry" is a place, not a
 * street, and the Base Adresse Nationale — which only knows addresses — answers beside the point.
 * So the sources are asked at once and their answers are merged:
 *
 *   • Photon (OpenStreetMap): places by name — schools, shops, stations, town halls…
 *   • Base Adresse Nationale: French addresses, official and precise to the house number.
 *   • TomTom POI Search, once turned on (search/tomtom.js): the shops and gyms OpenStreetMap
 *     misses, for what is not plainly an address, within its own daily and monthly budget.
 *
 * Photon and the BAN are free and take no key; their answers are kept a few minutes so a driver
 * typing letter by letter does not hammer them. TomTom's come without Cache-Control: never kept,
 * nor is the answer (no-store). Each account has a ceiling.
 */
searchRouter.get('/', async (req, res) => {
  res.set('Cache-Control', 'no-store');
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  const query = String(req.query.q ?? '').trim();
  if (query.length < config.searchMinChars) {
    return res.json({ count: 0, results: [] });
  }
  const around = point(req.query.lat, req.query.lon);
  const limit = Math.min(Math.max(Number(req.query.limit) || config.searchLimit, 1), config.searchMaxLimit);

  const found = await searchPlaces(query, around, () => spend(account.id));
  if (!found) return res.status(429).json({ error: 'too many searches' });
  const body = { count: Math.min(found.results.length, limit), results: found.results.slice(0, limit) };
  if (found.cached) body.cached = true;
  res.json(body);
});

/**
 * What [query] finds near [around] ({ lat, lon }, or null), best first, and whether every source
 * answered from its cache. [allow] is asked before any source is (the account's ceiling): if it
 * refuses, null.
 */
export async function searchPlaces(query, around, allow = () => true) {
  const key = cacheKey(query, around);
  const asked = [photon, ban];
  // TomTom for a place's name, long enough to mean something, if it may be asked now.
  if (query.length >= config.searchTomtomMinChars && !looksLikeAddress(query) && tomtomPlaces.available()) {
    asked.push(tomtom);
  }
  const known = asked.map((source) => source.cached(key));
  const cached = known.every(Boolean);
  if (!cached && !allow()) return null;
  const lists = await Promise.all(asked.map((source, i) => known[i] ?? source.ask(key, query, around)));
  return { results: merge(lists, around, query), cached };
}

/** Places by name, from OpenStreetMap through Photon. */
async function fromPhoton(query, around) {
  const url = new URL(`${config.photonUrl.replace(/\/$/, '')}/api/`);
  url.searchParams.set('q', query);
  url.searchParams.set('limit', String(config.searchSourceLimit));
  url.searchParams.set('lang', 'fr');
  if (around) {
    url.searchParams.set('lat', String(around.lat));
    url.searchParams.set('lon', String(around.lon));
    // Bias, not a filter: a place far away still comes back if it is the one being looked for.
    url.searchParams.set('location_bias_scale', '0.3');
  }
  const answer = await fetch(url, {
    headers: { 'User-Agent': config.placeUserAgent },
    signal: AbortSignal.timeout(config.searchTimeoutMs),
  });
  if (!answer.ok) throw Object.assign(new Error('HTTP error'), { status: answer.status });
  const json = await answer.json();
  return (json?.features ?? []).map(photonPlace).filter(Boolean);
}

/** One Photon feature as a result: its name, then what places it (street, town). */
function photonPlace(feature) {
  const p = feature?.properties ?? {};
  const [lon, lat] = feature?.geometry?.coordinates ?? [];
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
  const name = p.name || [p.housenumber, p.street].filter(Boolean).join(' ');
  if (!name) return null;
  const where = [
    p.name && p.street ? [p.housenumber, p.street].filter(Boolean).join(' ') : null,
    p.postcode,
    p.city || p.county,
  ].filter(Boolean).join(' ');
  return {
    id: `osm:${p.osm_type ?? ''}${p.osm_id ?? `${lat},${lon}`}`,
    name,
    subtitle: where,
    lat,
    lon,
    // Where it is, for the line under the name.
    city: p.city ?? p.county ?? null,
    address: [p.housenumber, p.street].filter(Boolean).join(' ') || null,
    postcode: p.postcode ?? null,
    // What kind of place it is, in the app's words ("Lycée", "Gare", "Supermarché"…).
    category: categoryLabel(p.osm_key, p.osm_value),
    osmKey: p.osm_key ?? null,
    osmValue: p.osm_value ?? null,
    // A named place (school, shop, station…) is what a driver usually means.
    named: Boolean(p.name),
    source: 'osm',
  };
}

/** Addresses from the Base Adresse Nationale. */
async function fromBAN(query, around) {
  const url = new URL(`${config.banUrl.replace(/\/$/, '')}/search/`);
  url.searchParams.set('q', query);
  url.searchParams.set('limit', String(config.searchSourceLimit));
  // Someone typing: the BAN answers on a half-written address too.
  url.searchParams.set('autocomplete', '1');
  if (around) {
    url.searchParams.set('lat', String(around.lat));
    url.searchParams.set('lon', String(around.lon));
  }
  const answer = await fetch(url, {
    headers: { 'User-Agent': config.placeUserAgent },
    signal: AbortSignal.timeout(config.searchTimeoutMs),
  });
  if (!answer.ok) throw Object.assign(new Error('HTTP error'), { status: answer.status });
  const json = await answer.json();
  return (json?.features ?? []).map((feature) => {
    const p = feature?.properties ?? {};
    const [lon, lat] = feature?.geometry?.coordinates ?? [];
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    const name = p.name || p.label;
    if (!name) return null;
    return {
      id: `ban:${p.id ?? `${lat},${lon}`}`,
      name,
      subtitle: [p.postcode, p.city].filter(Boolean).join(' '),
      lat,
      lon,
      city: p.city ?? null,
      address: p.name ?? null,
      postcode: p.postcode ?? null,
      category: p.type === 'municipality' ? 'Commune' : 'Adresse',
      osmKey: null,
      osmValue: null,
      named: false,
      // housenumber, street, locality, municipality: a street loses to a shop when a place is typed.
      banType: p.type ?? null,
      source: 'ban',
    };
  }).filter(Boolean);
}

/**
 * The sources' lists into one, best first. Each answer is scored on four things (see rank.js):
 * how close it is, how well its name matches, what kind of place it is, and where its own source
 * ranked it. The same spot found twice is kept once — the better-scored one.
 */
function merge(lists, around, query) {
  const scored = lists.flatMap((list) => list.map((item, index) => {
    const distanceM = around ? Math.round(haversine(around.lat, around.lon, item.lat, item.lon)) : null;
    const { score: value } = score({ ...item, distanceM }, { query, index, total: list.length });
    return { ...item, distanceM, score: value };
  }));
  scored.sort((a, b) => b.score - a.score);

  const out = [];
  for (const item of scored) {
    // The same place under two names — a school and its buildings, a station and its entrances —
    // counts once: near each other, and named the same for the first few words.
    const twin = out.find((kept) => {
      const metres = haversine(kept.lat, kept.lon, item.lat, item.lon);
      if (metres < config.searchSameSpotM && similar(kept.name, item.name)) return true;
      return metres < config.searchSameNameM && head(kept.name) === head(item.name);
    });
    if (twin) continue;
    out.push(item);
    if (out.length >= config.searchMaxLimit) break;
  }
  // The apps' fields, and only them, whatever the source.
  return out.map((item) => ({
    id: item.id,
    name: item.name,
    // One line under the name: what it is, its street, its town — and whose data (© TomTom).
    subtitle: [item.category, item.address, item.city, item.attribution].filter(Boolean).join(' · '),
    lat: item.lat,
    lon: item.lon,
    city: item.city,
    address: item.address,
    postcode: item.postcode,
    category: item.category,
    source: item.source,
    distanceM: item.distanceM,
  }));
}

/** What an OpenStreetMap tag is called in the app, under the name of the place. */
function categoryLabel(key, value) {
  if (!key || !value) return null;
  return CATEGORY.get(`${key}:${value}`) ?? CATEGORY.get(key) ?? null;
}

const CATEGORY = new Map(Object.entries({
  'amenity:school': 'École', 'amenity:college': 'Collège', 'amenity:university': 'Université',
  'amenity:kindergarten': 'Crèche', 'amenity:hospital': 'Hôpital', 'amenity:clinic': 'Clinique',
  'amenity:pharmacy': 'Pharmacie', 'amenity:doctors': 'Cabinet médical', 'amenity:townhall': 'Mairie',
  'amenity:police': 'Police', 'amenity:fire_station': 'Pompiers', 'amenity:post_office': 'Poste',
  'amenity:fuel': 'Station-service', 'amenity:charging_station': 'Borne de recharge',
  'amenity:parking': 'Parking', 'amenity:restaurant': 'Restaurant', 'amenity:cafe': 'Café',
  'amenity:bar': 'Bar', 'amenity:fast_food': 'Restauration rapide', 'amenity:bank': 'Banque',
  'amenity:library': 'Bibliothèque', 'amenity:theatre': 'Théâtre', 'amenity:cinema': 'Cinéma',
  'amenity:place_of_worship': 'Lieu de culte', 'amenity:bus_station': 'Gare routière',
  'amenity:marketplace': 'Marché', 'amenity:atm': 'Distributeur',
  'railway:station': 'Gare', 'railway:halt': 'Halte ferroviaire', 'railway:tram_stop': 'Arrêt de tram',
  'railway:subway_entrance': 'Métro', 'aeroway:aerodrome': 'Aéroport', 'aeroway:terminal': 'Terminal',
  'highway:bus_stop': 'Arrêt de bus', 'highway:services': 'Aire de service', 'highway:rest_area': 'Aire de repos',
  'tourism:hotel': 'Hôtel', 'tourism:museum': 'Musée', 'tourism:attraction': 'Site touristique',
  'tourism:camp_site': 'Camping', 'tourism:viewpoint': 'Point de vue',
  'leisure:sports_centre': 'Centre sportif', 'leisure:stadium': 'Stade', 'leisure:swimming_pool': 'Piscine',
  'leisure:park': 'Parc', 'shop:supermarket': 'Supermarché', 'shop:mall': 'Centre commercial',
  'shop:bakery': 'Boulangerie', 'shop:convenience': 'Supérette', 'shop:car_repair': 'Garage',
  'office:government': 'Administration',
  'place:city': 'Ville', 'place:town': 'Commune', 'place:village': 'Village', 'place:hamlet': 'Hameau',
  'place:locality': 'Lieu-dit', 'place:suburb': 'Quartier', 'place:neighbourhood': 'Quartier',
  'place:isolated_dwelling': 'Lieu-dit', 'place:farm': 'Ferme',
  amenity: 'Service', shop: 'Commerce', tourism: 'Tourisme', leisure: 'Loisirs', office: 'Bureau',
  railway: 'Transport', aeroway: 'Aéroport', place: 'Lieu', building: 'Bâtiment', highway: 'Route',
}));

/** The first words of a name, which is what tells two places apart ("lycee adolphe cherioux"). */
function head(name) {
  return fold(name).split(' ').slice(0, 3).join(' ');
}

/** Two names for the same thing: one contains the other, accents and punctuation aside. */
function similar(a, b) {
  const first = fold(a);
  const second = fold(b);
  return Boolean(first) && Boolean(second) && (first.includes(second) || second.includes(first));
}

// ---- Sources, cache and ceiling ----------------------------------------------------------

/**
 * One source: its answers kept searchCacheMs per question, and the same question asked twice at
 * once (a driver typing, two drivers nearby) sent once. Only a real answer is kept: a failure
 * (an HTTP error, a timeout, an unreadable answer) is not an empty result and is asked again the
 * next time.
 */
function source(label, ask) {
  const kept = new Map(); // key -> { at, items }
  const pending = new Map(); // key -> the request under way
  return {
    cached(key) {
      const entry = kept.get(key);
      if (!entry) return null;
      if (Date.now() - entry.at > config.searchCacheMs) {
        kept.delete(key);
        return null;
      }
      return entry.items;
    },
    ask(key, query, around) {
      if (pending.has(key)) return pending.get(key);
      const call = ask(query, around)
        .then((items) => {
          keep(kept, key, items);
          return items;
        }, (e) => failed(label, e))
        .finally(() => pending.delete(key));
      pending.set(key, call);
      return call;
    },
  };
}

const photon = source('photon', fromPhoton);
const ban = source('adresse', fromBAN);
/**
 * TomTom's answers come without Cache-Control: none is kept, none is shared between two searches.
 * Nothing sent (off, resting, budget spent): no places.
 */
const tomtom = {
  cached: () => null,
  ask: (key, query, around) => tomtomPlaces.search(query, around).then((items) => items ?? [], (e) => failed('tomtom', e)),
};

/** A source that failed: no places, and why in the logs. */
function failed(label, e) {
  console.warn(`[search] ${label} —`, reason(e));
  return [];
}

/** Why a source failed, for the logs: never its URL (the query, TomTom's key are in it), never its message. */
function reason(e) {
  if (e?.name === 'TimeoutError' || e?.name === 'AbortError') return 'timeout';
  if (Number.isInteger(e?.status)) return `HTTP ${e.status}`;
  if (e instanceof SyntaxError) return 'unreadable answer';
  if (e?.cause?.code) return `unreachable (${e.cause.code})`;
  return e?.name ?? 'failed';
}

const spent = new Map(); // account id -> [timestamps]

function cacheKey(query, around) {
  const cell = around ? `${around.lat.toFixed(2)},${around.lon.toFixed(2)}` : '-';
  return `${query.toLowerCase()}|${cell}`;
}

function keep(kept, key, items) {
  kept.set(key, { at: Date.now(), items });
  if (kept.size > config.searchCacheMax) {
    for (const [old, entry] of kept) {
      if (Date.now() - entry.at > config.searchCacheMs) kept.delete(old);
    }
    // Still too many: the oldest go.
    while (kept.size > config.searchCacheMax) kept.delete(kept.keys().next().value);
  }
}

/** One account may really ask this often; beyond that it is a loop, not someone typing. */
function spend(accountId) {
  const now = Date.now();
  const times = (spent.get(accountId) ?? []).filter((t) => now - t < 60_000);
  if (times.length >= config.searchPerMinute) {
    spent.set(accountId, times);
    return false;
  }
  times.push(now);
  spent.set(accountId, times);
  if (spent.size > 2000) {
    for (const [id, list] of spent) if (!list.length || now - list[list.length - 1] > 60_000) spent.delete(id);
  }
  return true;
}

function point(lat, lon) {
  const a = Number(lat);
  const b = Number(lon);
  return Number.isFinite(a) && Number.isFinite(b) && Math.abs(a) <= 90 && Math.abs(b) <= 180
    ? { lat: a, lon: b } : null;
}
