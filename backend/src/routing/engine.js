import { config } from '../config.js';
import { haversine } from '../radars/geo.js';
import { reportStore } from '../reports/store.js';
import { ORS_AVOID, normalizeOrsFeature, orsMapVersion, postORS, square } from './ors.js';

/**
 * The routing engine behind /api/route and the bench (bench.js): OpenRouteService when
 * ORS_API_KEY is set (better quality + avoid options), otherwise the OSRM demo. A route comes
 * back in the app's shape, with the engine that drew it (`engine`: ors | osrm) and the date of
 * its map (`mapVersion`, null when unknown); a failure as { error, status, detail? }. "traffic"
 * in [avoid] keeps the route away from the traffic jams drivers reported (ORS only). Nothing
 * here counts a trip or spends an account's routes: the callers do.
 */
export async function computeRoute(from, to, avoid) {
  return config.orsApiKey ? routeViaORS(from, to, avoid) : routeViaOSRM(from, to);
}

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
  if (r.budgetSpent) return { error: 'routing budget reached', status: 503 };
  if (!r.ok) {
    const detail = await r.text().catch(() => '');
    return { error: `ORS ${r.status}`, status: 502, detail: detail.slice(0, 200) };
  }
  const json = await r.json();
  const feature = json.features && json.features[0];
  if (!feature) return { error: 'no route found', status: 404 };
  return normalizeOrsFeature(feature, orsMapVersion(json));
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
  const r = await fetch(url, { signal: AbortSignal.timeout(config.osrmTimeoutMs) });
  if (!r.ok) return { error: `OSRM ${r.status}`, status: 502 };
  const json = await r.json();
  const route = json.routes && json.routes[0];
  if (!route) return { error: 'no route found', status: 404 };
  return {
    distanceM: Math.round(route.distance),
    durationS: Math.round(route.duration),
    coordinates: route.geometry.coordinates,
    steps: normalizeOsrmSteps(route),
    engine: 'osrm',
    mapVersion: null,
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
