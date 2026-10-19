const EARTH_RADIUS_M = 6371000;

/** Great-circle distance in metres. */
export function haversine(lat1, lon1, lat2, lon2) {
  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Initial course from the first point to the second, in degrees (0 = north, clockwise). */
export function bearingDeg(lat1, lon1, lat2, lon2) {
  const y = Math.sin(toRad(lon2 - lon1)) * Math.cos(toRad(lat2));
  const x = Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(toRad(lon2 - lon1));
  return ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

/** Smallest angle between two courses, in degrees (0..180). */
export function angleBetween(a, b) {
  const d = Math.abs((a - b) % 360);
  return d > 180 ? 360 - d : d;
}

/** Radars within [radiusM] of (lat, lon), each annotated with distanceM, nearest first. */
export function near(radars, lat, lon, radiusM, limit) {
  const out = [];
  for (const r of radars) {
    const distanceM = haversine(lat, lon, r.lat, r.lon);
    if (distanceM <= radiusM) out.push({ ...r, distanceM: Math.round(distanceM) });
  }
  out.sort((a, b) => a.distanceM - b.distanceM);
  return out.slice(0, limit);
}

/** Radars inside a bounding box (for the map viewport). */
export function inBbox(radars, minLon, minLat, maxLon, maxLat, limit) {
  const out = [];
  for (const r of radars) {
    if (r.lon >= minLon && r.lon <= maxLon && r.lat >= minLat && r.lat <= maxLat) {
      out.push(r);
      if (out.length >= limit) break;
    }
  }
  return out;
}

const M_PER_DEG = 111320;
const ROUTE_CELL_DEG = 0.02; // ~2.2 km grid for the route's segments
// Visites de cellules permises par tracé, comptées avant allocation (audit 19/10).
// Tracé dense de 1 000 km : très en dessous. Segment traversant un continent : refusé.
export const ROUTE_MAX_CELLS = 400_000;

/** [lon, lat] dans bornes terrestres, deux nombres réels : aucune chaîne, aucune conversion. */
export function validLonLat(c) {
  return Array.isArray(c) && (c.length === 2 || c.length === 3)
    && typeof c[0] === 'number' && typeof c[1] === 'number'
    && Number.isFinite(c[0]) && Number.isFinite(c[1])
    && Math.abs(c[0]) <= 180 && Math.abs(c[1]) <= 90;
}

/**
 * Radars within [bufferM] metres of a route ([[lon, lat], ...]), in the order the route
 * meets them, each annotated with offRouteM. The route's segments are bucketed on a
 * coarse grid first, so each radar is only measured against the segments passing near it.
 * Lève RangeError('route_too_large') si grille dépasse [maxCells] visites.
 */
export function alongRoute(radars, coords, bufferM, limit, { maxCells = ROUTE_MAX_CELLS } = {}) {
  const pts = Array.isArray(coords) ? coords.filter(validLonLat) : [];
  if (pts.length < 2 || !(bufferM >= 0)) return [];

  const padLat = bufferM / M_PER_DEG;
  // Bornes de chaque segment d'abord : budget vérifié avant toute mémoire de grille.
  const spans = [];
  let cells = 0;
  for (let i = 0; i < pts.length - 1; i++) {
    const [lonA, latA] = pts[i];
    const [lonB, latB] = pts[i + 1];
    const padLon = padLat / Math.max(0.1, Math.cos(toRad((latA + latB) / 2)));
    const r0 = Math.floor((Math.min(latA, latB) - padLat) / ROUTE_CELL_DEG);
    const r1 = Math.floor((Math.max(latA, latB) + padLat) / ROUTE_CELL_DEG);
    const c0 = Math.floor((Math.min(lonA, lonB) - padLon) / ROUTE_CELL_DEG);
    const c1 = Math.floor((Math.max(lonA, lonB) + padLon) / ROUTE_CELL_DEG);
    // r++ doit avancer : aucun indice hors entiers sûrs.
    if (![r0, r1, c0, c1].every(Number.isSafeInteger)) throw new RangeError('route_too_large');
    cells += (r1 - r0 + 1) * (c1 - c0 + 1);
    if (cells > maxCells) throw new RangeError('route_too_large');
    spans.push(r0, r1, c0, c1);
  }

  const grid = new Map(); // cellKey(ligne, colonne) -> indices segments
  for (let i = 0; i < pts.length - 1; i++) {
    for (let r = spans[i * 4]; r <= spans[i * 4 + 1]; r++) {
      for (let c = spans[i * 4 + 2]; c <= spans[i * 4 + 3]; c++) {
        const key = cellKey(r, c);
        let bucket = grid.get(key);
        if (!bucket) grid.set(key, (bucket = []));
        bucket.push(i);
      }
    }
  }

  const hits = [];
  for (const r of radars) {
    const bucket = grid.get(cellKey(Math.floor(r.lat / ROUTE_CELL_DEG), Math.floor(r.lon / ROUTE_CELL_DEG)));
    if (!bucket) continue;
    let best = Infinity;
    let segment = 0;
    for (const i of bucket) {
      const d = distanceToSegmentM(r.lat, r.lon, pts[i], pts[i + 1]);
      if (d < best) { best = d; segment = i; }
    }
    if (best <= bufferM) hits.push({ radar: r, segment, best });
  }
  hits.sort((a, b) => a.segment - b.segment);
  return hits.slice(0, limit).map(({ radar, best }) => ({ ...radar, offRouteM: Math.round(best) }));
}

/** Metres from (lat, lon) to the segment [a, b] ([lon, lat] each), local equirectangular. */
function distanceToSegmentM(lat, lon, a, b) {
  const mLon = M_PER_DEG * Math.cos(toRad(lat));
  const ax = (a[0] - lon) * mLon;
  const ay = (a[1] - lat) * M_PER_DEG;
  const dx = (b[0] - lon) * mLon - ax;
  const dy = (b[1] - lat) * M_PER_DEG - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 === 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
  const px = ax + t * dx;
  const py = ay + t * dy;
  return Math.sqrt(px * px + py * py);
}

/** Clé cellule numérique : lignes et colonnes dans ±10 000, coordonnées bornées. */
function cellKey(row, col) {
  return (row + 10_000) * 40_000 + (col + 20_000);
}

function toRad(deg) {
  return (deg * Math.PI) / 180;
}
