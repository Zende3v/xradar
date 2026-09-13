import { Router } from 'express';
import { config } from '../config.js';
import { fuelStore } from '../fuel/store.js';
import { haversine } from '../radars/geo.js';

export const placeRouter = Router();

/** Category → the OpenStreetMap tag that defines it. */
const KINDS = {
  fuel: ['amenity', 'fuel', 'Station-service'],
  charging: ['amenity', 'charging_station', 'Borne de recharge'],
  parking: ['amenity', 'parking', 'Parking'],
  tobacco: ['shop', 'tobacco', 'Tabac'],
  garage: ['shop', 'car_repair', 'Garage'],
  hotel: ['tourism', 'hotel', 'Hôtel'],
  atm: ['amenity', 'atm', 'Distributeur'],
};

/**
 * GET /api/places/near?lat&lon&kind=fuel[&limit=20][&pool=1]
 * The nearest places of a category — no fixed perimeter: the search widens until it
 * has enough of them, then returns the closest ones, nearest first.
 * `pool=1` (fuel only): up to `fuelPoolLimit` stations instead of `limit`, taken from the
 * same Overpass answer, so the app can prefer the nearest stations that show a price.
 */
placeRouter.get('/near', async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  const kind = String(req.query.kind || '');
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  if (!KINDS[kind]) {
    return res.status(400).json({ error: `unknown kind, expected one of ${Object.keys(KINDS).join(', ')}` });
  }
  const limit = Math.max(1, Math.min(Number(req.query.limit) || config.placeLimit, 50));
  const pool = kind === 'fuel' && req.query.pool === '1';

  const cached = readCache(kind, lat, lon, limit);
  if (cached) return res.json(respond(kind, cached, 'cache', limit, pool));

  try {
    const places = await search(kind, lat, lon, limit);
    writeCache(kind, lat, lon, limit, places);
    res.json(respond(kind, places, 'overpass', limit, pool));
  } catch (e) {
    console.warn(`[places] ${kind} failed —`, String(e.message || e));
    res.status(502).json({ error: 'places unavailable', detail: String(e.message || e) });
  }
});

/**
 * The response body. Fuel stations — and only they — also get the official prices,
 * matched at answer time so a cached list never serves stale prices; without `pool` the
 * list is cut back to `limit`, exactly as before. Every other kind is returned unchanged.
 */
function respond(kind, places, source, limit, pool) {
  if (kind !== 'fuel') return { count: places.length, places, source };
  const list = pool ? places : places.slice(0, limit);
  return {
    count: list.length,
    places: fuelStore.enrich(list),
    source,
    fuelPrices: {
      provider: 'prix-carburants.gouv.fr',
      ready: fuelStore.meta.ready,
      refreshedAt: fuelStore.meta.refreshedAt,
    },
  };
}

/** Widen the ring until we have enough results (or run out of rings). */
async function search(kind, lat, lon, limit) {
  const [key, value, fallbackName] = KINDS[kind];
  // Fuel keeps a bigger pool from the same answer; the ring still stops at `limit`.
  const keep = kind === 'fuel' ? Math.max(limit, config.fuelPoolLimit) : limit;
  let found = [];
  for (const radius of config.placeRadiiM) {
    const elements = await overpass(key, value, lat, lon, radius);
    found = elements
      .map((el) => {
        const pLat = el.lat ?? el.center?.lat;
        const pLon = el.lon ?? el.center?.lon;
        if (!Number.isFinite(pLat) || !Number.isFinite(pLon)) return null;
        const tags = el.tags || {};
        const name = tags.name || tags.brand || tags.operator || fallbackName;
        return {
          id: `${el.type}/${el.id}`,
          name,
          subtitle: [tags['addr:street'], tags['addr:city']].filter(Boolean).join(', '),
          lat: pLat,
          lon: pLon,
          distanceM: Math.round(haversine(lat, lon, pLat, pLon)),
          // The official station id OpenStreetMap carries, used to match the prices.
          ...(kind === 'fuel' ? { refId: tags['ref:FR:prix-carburants'] || null } : {}),
        };
      })
      .filter(Boolean)
      .sort((a, b) => a.distanceM - b.distanceM);
    if (found.length >= limit) break;
  }
  return found.slice(0, keep);
}

async function overpass(key, value, lat, lon, radius) {
  const around = `around:${radius},${lat},${lon}`;
  const query = `[out:json][timeout:${config.placeTimeoutS}];` +
    `(node["${key}"="${value}"](${around});way["${key}"="${value}"](${around}););` +
    'out center 200;';
  const r = await fetch(config.overpassUrl, {
    method: 'POST',
    // Overpass answers 406 to a request without a real User-Agent.
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'User-Agent': config.placeUserAgent,
    },
    body: `data=${encodeURIComponent(query)}`,
  });
  if (!r.ok) throw new Error(`overpass ${r.status}`);
  const json = await r.json();
  return Array.isArray(json.elements) ? json.elements : [];
}

// Small in-memory cache: the same driver asking twice in a row costs nothing, and
// Overpass stays happy. Keyed on a ~1 km grid.
const cache = new Map();

function cacheKey(kind, lat, lon, limit) {
  return `${kind}:${lat.toFixed(2)}:${lon.toFixed(2)}:${limit}`;
}

function readCache(kind, lat, lon, limit) {
  const hit = cache.get(cacheKey(kind, lat, lon, limit));
  if (!hit || hit.at + config.placeCacheTtlMs < Date.now()) return null;
  return hit.places;
}

function writeCache(kind, lat, lon, limit, places) {
  if (cache.size > 500) cache.clear();
  cache.set(cacheKey(kind, lat, lon, limit), { at: Date.now(), places });
}
