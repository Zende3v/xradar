import { config } from '../config.js';

/** The app's avoid options → ORS avoid_features. */
export const ORS_AVOID = { tolls: 'tollways', highways: 'highways', ferries: 'ferries' };

export function postORS(body) {
  return fetch(`${config.orsUrl.replace(/\/$/, '')}/v2/directions/driving-car/geojson`, {
    method: 'POST',
    headers: { Authorization: config.orsApiKey, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

/** A counter-clockwise square around a point, as one GeoJSON polygon ([lon, lat]) for avoid_polygons. */
export function square(lat, lon, halfM) {
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

/** One ORS feature as the app's route: [lon, lat] coordinates and OSRM-style steps. */
export function normalizeOrsFeature(feature) {
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
