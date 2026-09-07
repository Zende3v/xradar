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

function toRad(deg) {
  return (deg * Math.PI) / 180;
}
