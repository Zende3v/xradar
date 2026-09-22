import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { haversine } from '../radars/geo.js';
import { fold, score } from './rank.js';

export const searchRouter = Router();

/**
 * GET /api/search?q=…&lat=…&lon=…&limit=…
 *
 * What a driver types is rarely an address. "Lycée Adolphe Chérioux vitry" is a place, not a
 * street, and the Base Adresse Nationale — which only knows addresses — answers beside the point.
 * So two sources are asked at once and their answers are merged:
 *
 *   • Photon (OpenStreetMap): places by name — schools, shops, stations, town halls…
 *   • Base Adresse Nationale: French addresses, official and precise to the house number.
 *
 * Both are free and take no key. Answers are kept a few minutes so a driver typing letter by
 * letter does not hammer them, and each account has a ceiling.
 */
searchRouter.get('/', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  const query = String(req.query.q ?? '').trim();
  if (query.length < config.searchMinChars) {
    return res.json({ count: 0, results: [] });
  }
  const around = point(req.query.lat, req.query.lon);
  const limit = Math.min(Math.max(Number(req.query.limit) || config.searchLimit, 1), config.searchMaxLimit);

  const key = cacheKey(query, around);
  const known = cached(key);
  if (known) return res.json({ count: Math.min(known.length, limit), results: known.slice(0, limit), cached: true });

  if (!spend(account.id)) {
    return res.status(429).json({ error: 'too many searches' });
  }

  const [places, addresses] = await Promise.all([
    fromPhoton(query, around).catch((e) => {
      console.warn('[search] photon —', String(e.message || e));
      return [];
    }),
    fromBAN(query, around).catch((e) => {
      console.warn('[search] adresse —', String(e.message || e));
      return [];
    }),
  ]);

  const results = merge(places, addresses, around, query);
  keep(key, results);
  res.json({ count: Math.min(results.length, limit), results: results.slice(0, limit) });
});

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
  if (!answer.ok) return [];
  const json = await answer.json();
  return (json.features ?? []).map(photonPlace).filter(Boolean);
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
  if (!answer.ok) return [];
  const json = await answer.json();
  return (json.features ?? []).map((feature) => {
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
      source: 'ban',
    };
  }).filter(Boolean);
}

/**
 * The two lists into one, best first. Each answer is scored on four things (see rank.js): how
 * close it is, how well its name matches, what kind of place it is, and where its own source
 * ranked it. The same spot found twice is kept once — the better-scored one.
 */
function merge(places, addresses, around, query) {
  const scored = [places, addresses].flatMap((list) => list.map((item, index) => {
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
  return out.map(({ named, osmKey, osmValue, score: value, ...rest }) => ({
    ...rest,
    // One line under the name: what it is, its street, its town.
    subtitle: [rest.category, rest.address, rest.city].filter(Boolean).join(' · '),
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

// ---- Cache and ceiling -------------------------------------------------------------------

const recent = new Map(); // key -> { at, results }
const spent = new Map(); // account id -> [timestamps]

function cacheKey(query, around) {
  const cell = around ? `${around.lat.toFixed(2)},${around.lon.toFixed(2)}` : '-';
  return `${query.toLowerCase()}|${cell}`;
}

function cached(key) {
  const entry = recent.get(key);
  if (!entry) return null;
  if (Date.now() - entry.at > config.searchCacheMs) {
    recent.delete(key);
    return null;
  }
  return entry.results;
}

function keep(key, results) {
  recent.set(key, { at: Date.now(), results });
  if (recent.size > config.searchCacheMax) {
    for (const [old, entry] of recent) {
      if (Date.now() - entry.at > config.searchCacheMs) recent.delete(old);
    }
    // Still too many: the oldest go.
    while (recent.size > config.searchCacheMax) recent.delete(recent.keys().next().value);
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
  return Number.isFinite(a) && Number.isFinite(b) ? { lat: a, lon: b } : null;
}
