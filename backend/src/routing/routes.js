import { Router } from 'express';
import { config } from '../config.js';

export const routeRouter = Router();

/**
 * GET /api/route?from=lat,lon&to=lat,lon[&avoid=tolls,highways]
 * Returns a normalized car route. Uses OpenRouteService when ORS_API_KEY is set
 * (better quality + avoid options), otherwise falls back to the OSRM demo.
 */
routeRouter.get('/', async (req, res) => {
  const from = parseCoord(req.query.from);
  const to = parseCoord(req.query.to);
  if (!from || !to) {
    return res.status(400).json({ error: 'from and to are required as "lat,lon"' });
  }
  const avoid = String(req.query.avoid || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  try {
    const route = config.orsApiKey
      ? await routeViaORS(from, to, avoid)
      : await routeViaOSRM(from, to);
    if (route.error) return res.status(route.status || 502).json({ error: route.error });
    res.json(route);
  } catch (e) {
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

  const r = await fetch(`${config.orsUrl.replace(/\/$/, '')}/v2/directions/driving-car/geojson`, {
    method: 'POST',
    headers: { Authorization: config.orsApiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
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
