import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { haversine } from '../radars/geo.js';

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

  const results = merge(places, addresses, around);
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
      subtitle: p.context ? `${p.postcode ?? ''} ${p.city ?? ''} · ${p.context}`.trim() : (p.label ?? ''),
      lat,
      lon,
      named: false,
      score: Number(p.score) || 0,
      source: 'ban',
    };
  }).filter(Boolean);
}

/**
 * The two lists into one. A place named like what was typed comes first, then the addresses;
 * inside each, the nearest to the driver wins. The same spot found twice is kept once.
 */
function merge(places, addresses, around) {
  const words = [];
  const ranked = [...places, ...addresses].map((item) => ({
    ...item,
    distanceM: around ? Math.round(haversine(around.lat, around.lon, item.lat, item.lon)) : null,
  }));
  ranked.sort((a, b) => {
    if (a.named !== b.named) return a.named ? -1 : 1;
    if (a.source !== b.source) return a.source === 'osm' ? -1 : 1;
    if (a.distanceM != null && b.distanceM != null && a.distanceM !== b.distanceM) {
      return a.distanceM - b.distanceM;
    }
    return (b.score ?? 0) - (a.score ?? 0);
  });
  const out = [];
  for (const item of ranked) {
    const twin = out.find((kept) => haversine(kept.lat, kept.lon, item.lat, item.lon) < config.searchSameSpotM
      && similar(kept.name, item.name));
    if (twin) continue;
    out.push(item);
    if (out.length >= config.searchMaxLimit) break;
  }
  void words;
  return out.map(({ named, score, ...rest }) => rest);
}

/** Two names for the same thing: one contains the other, ignoring case and accents. */
function similar(a, b) {
  const clean = (text) => text.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
  const first = clean(a);
  const second = clean(b);
  return first.includes(second) || second.includes(first);
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
