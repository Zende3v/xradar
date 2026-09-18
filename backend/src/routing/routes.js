import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { haversine } from '../radars/geo.js';
import { reportStore } from '../reports/store.js';
import { fasterRoute } from './faster.js';
import { ORS_AVOID, normalizeOrsFeature, postORS, square } from './ors.js';

export const routeRouter = Router();

/**
 * GET /api/route?from=lat,lon&to=lat,lon[&avoid=tolls,highways,traffic]
 * Returns a normalized car route. Uses OpenRouteService when ORS_API_KEY is set
 * (better quality + avoid options), otherwise falls back to the OSRM demo.
 * "traffic" keeps the route away from the traffic jams drivers reported (ORS only).
 */
routeRouter.get('/', async (req, res) => {
  // An expired trial (or lapsed subscription) keeps the map, not the navigation.
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  if (!accountStore.accessFor(account).canNavigate) {
    return res.status(403).json({ error: 'subscription required' });
  }
  const from = parseCoord(req.query.from);
  const to = parseCoord(req.query.to);
  if (!from || !to) {
    return res.status(400).json({ error: 'from and to are required as "lat,lon"' });
  }
  // A guest starts a limited number of trips a day; recalculations to the same place are free.
  const trip = accountStore.tripCheck(account, to);
  if (!trip.allowed) {
    return res.status(429).json({ error: 'daily trip limit', limit: config.guestTripsPerDay });
  }
  const avoid = String(req.query.avoid || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  try {
    const route = config.orsApiKey
      ? await routeViaORS(from, to, avoid)
      : await routeViaOSRM(from, to);
    if (route.error) {
      console.warn(`[route] ${route.error}${route.detail ? ' — ' + route.detail : ''}`);
      return res.status(route.status || 502).json({ error: route.error });
    }
    if (trip.isNew) accountStore.countTrip(account, to);
    res.json(route);
  } catch (e) {
    console.warn('[route] unavailable —', String(e.message || e));
    res.status(502).json({ error: 'routing unavailable', detail: String(e.message || e) });
  }
});

/**
 * POST /api/route/faster  { coordinates: [[lon, lat], …], avoid?: ["tolls", "highways"], sinceRerouteS? }
 * The rest of the route being followed (from the driver to the destination) against ORS
 * variants around its big traffic jams, all timed by TomTom with today's traffic: a variant comes
 * back (`better`) only when it saves enough time. See faster.js.
 */
routeRouter.post('/faster', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  if (!accountStore.accessFor(account).canNavigate) {
    return res.status(403).json({ error: 'subscription required' });
  }
  if (!config.orsApiKey || !config.tomtomApiKey) return res.status(503).json({ error: 'rerouting unavailable' });
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords) || coords.length < 2 || coords.length > config.trafficMaxPoints) {
    return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  }
  const points = [];
  for (const c of coords) {
    const lon = Number(c?.[0]);
    const lat = Number(c?.[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return res.status(400).json({ error: 'invalid coordinate' });
    points.push([lat, lon]);
  }
  const avoid = Array.isArray(req.body.avoid) ? req.body.avoid.map(String) : [];
  const since = Number(req.body.sinceRerouteS);
  try {
    res.json(await fasterRoute(points, { avoid, sinceRerouteS: Number.isFinite(since) && since >= 0 ? since : null }));
  } catch (e) {
    console.warn('[faster] unavailable —', String(e.message || e));
    res.status(502).json({ error: 'rerouting unavailable' });
  }
});

// ---- OpenRouteService --------------------------------------------------------

async function routeViaORS(from, to, avoid) {
  const body = {
    coordinates: [[from.lon, from.lat], [to.lon, to.lat]],
    instructions: true,
    maneuvers: true,
    geometry_simplify: false,
  };
  const avoidFeatures = avoid.map((a) => ORS_AVOID[a]).filter(Boolean);
  if (avoidFeatures.length) body.options = { avoid_features: avoidFeatures };
  const jams = avoid.includes('traffic')
    ? await trafficPolygons(from, to).catch((e) => {
      console.warn('[route] traffic jams unavailable —', String(e.message || e));
      return null;
    })
    : null;

  let r = await postORS(jams ? { ...body, options: { ...body.options, avoid_polygons: jams } } : body);
  // A route squeezed out by the reported jams can be impossible: the trip matters more.
  if (!r.ok && jams) r = await postORS(body);
  if (!r.ok) {
    const detail = await r.text().catch(() => '');
    return { error: `ORS ${r.status}`, status: 502, detail: detail.slice(0, 200) };
  }
  const json = await r.json();
  const feature = json.features && json.features[0];
  if (!feature) return { error: 'no route found', status: 404 };
  return normalizeOrsFeature(feature);
}

// Traffic jams the route goes around: a square around each one reported live near the trip.
/** Half the side of the square avoided around a jam. */
const JAM_HALF_SIDE_M = 250;
/** A jam this close to the start or the destination stays crossable: the trip must be possible. */
const JAM_KEEP_CLEAR_M = 500;
/** Jams farther than this outside the box of the trip do not matter. */
const JAM_BOX_MARGIN_M = 10000;
/** Enough for a long trip, far under ORS's area limit for avoided polygons (25 km²). */
const JAM_MAX = 100;

/** The jams to avoid between [from] and [to], as a GeoJSON MultiPolygon; null when there is none. */
async function trafficPolygons(from, to) {
  const padLat = JAM_BOX_MARGIN_M / 111320;
  const padLon = padLat / Math.max(Math.cos(((from.lat + to.lat) / 2) * Math.PI / 180), 0.1);
  const jams = await reportStore.liveInBox('traffic_jam', {
    south: Math.min(from.lat, to.lat) - padLat,
    north: Math.max(from.lat, to.lat) + padLat,
    west: Math.min(from.lon, to.lon) - padLon,
    east: Math.max(from.lon, to.lon) + padLon,
  }, JAM_MAX);
  const squares = jams
    .filter((jam) => haversine(jam.lat, jam.lon, from.lat, from.lon) > JAM_KEEP_CLEAR_M
      && haversine(jam.lat, jam.lon, to.lat, to.lon) > JAM_KEEP_CLEAR_M)
    .map((jam) => square(jam.lat, jam.lon, JAM_HALF_SIDE_M));
  return squares.length ? { type: 'MultiPolygon', coordinates: squares } : null;
}

// ---- OSRM (fallback) ---------------------------------------------------------

async function routeViaOSRM(from, to) {
  const coords = `${from.lon},${from.lat};${to.lon},${to.lat}`;
  const url = `${config.osrmUrl.replace(/\/$/, '')}/route/v1/driving/${coords}` +
    '?overview=full&geometries=geojson&steps=true&alternatives=false';
  const r = await fetch(url);
  if (!r.ok) return { error: `OSRM ${r.status}`, status: 502 };
  const json = await r.json();
  const route = json.routes && json.routes[0];
  if (!route) return { error: 'no route found', status: 404 };
  return {
    distanceM: Math.round(route.distance),
    durationS: Math.round(route.duration),
    coordinates: route.geometry.coordinates,
    steps: normalizeOsrmSteps(route),
  };
}

function normalizeOsrmSteps(route) {
  const out = [];
  for (const leg of route.legs || []) {
    for (const s of leg.steps || []) {
      const m = s.maneuver || {};
      out.push({
        type: m.type ?? null,
        modifier: m.modifier ?? null,
        location: m.location ?? null,
        exit: m.exit ?? null,
        name: s.name ?? '',
        distanceM: Math.round(s.distance ?? 0),
        durationS: Math.round(s.duration ?? 0),
      });
    }
  }
  return out;
}

function parseCoord(value) {
  const parts = String(value || '').split(',').map(Number);
  if (parts.length !== 2 || parts.some((n) => !Number.isFinite(n))) return null;
  return { lat: parts[0], lon: parts[1] };
}
