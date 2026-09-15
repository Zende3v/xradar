import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { haversine } from '../radars/geo.js';
import { reportStore } from '../reports/store.js';

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

// ---- OpenRouteService --------------------------------------------------------

const ORS_AVOID = { tolls: 'tollways', highways: 'highways', ferries: 'ferries' };

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
  const summary = feature.properties.summary || {};
  const coordinates = feature.geometry.coordinates; // [[lon, lat], ...]
  return {
    distanceM: Math.round(summary.distance ?? 0),
    durationS: Math.round(summary.duration ?? 0),
    coordinates,
    steps: normalizeOrsSteps(feature.properties.segments || [], coordinates),
  };
}

function postORS(body) {
  return fetch(`${config.orsUrl.replace(/\/$/, '')}/v2/directions/driving-car/geojson`, {
    method: 'POST',
    headers: { Authorization: config.orsApiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
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

/** A counter-clockwise square around a point, as one GeoJSON polygon ([lon, lat]). */
function square(lat, lon, halfM) {
  const dLat = halfM / 111320;
  const dLon = halfM / (111320 * Math.max(Math.cos(lat * Math.PI / 180), 0.1));
  return [[
    [lon - dLon, lat - dLat],
    [lon + dLon, lat - dLat],
    [lon + dLon, lat + dLat],
    [lon - dLon, lat + dLat],
    [lon - dLon, lat - dLat],
  ]];
}

/** ORS instruction type codes → OSRM-style {type, modifier} the app already parses. */
const ORS_TYPE = {
  0: ['turn', 'left'], 1: ['turn', 'right'], 2: ['turn', 'sharp left'], 3: ['turn', 'sharp right'],
  4: ['turn', 'slight left'], 5: ['turn', 'slight right'], 6: ['continue', 'straight'],
  7: ['roundabout', null], 8: ['continue', 'straight'], 9: ['turn', 'uturn'], 10: ['arrive', null],
  11: ['depart', null], 12: ['fork', 'left'], 13: ['fork', 'right'],
};

function normalizeOrsSteps(segments, coordinates) {
  const out = [];
  for (const seg of segments) {
    for (const s of seg.steps || []) {
      const [type, modifier] = ORS_TYPE[s.type] || ['continue', 'straight'];
      const at = Array.isArray(s.way_points) ? coordinates[s.way_points[0]] : null;
      out.push({
        type,
        modifier,
        location: s.maneuver?.location ?? at ?? null, // [lon, lat]
        exit: s.exit_number ?? null,
        name: s.name && s.name !== '-' ? s.name : '',
        distanceM: Math.round(s.distance ?? 0),
        durationS: Math.round(s.duration ?? 0),
      });
    }
  }
  return out;
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
