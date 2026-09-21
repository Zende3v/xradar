import { config } from '../config.js';
import { reportStore } from '../reports/store.js';
import { angleDiff, gridOf, headingAt, project, samplesBetween } from '../routing/geometry.js';
import { probeStore } from './probes.js';

// Probes this far apart along the route (and no farther) belong to one slowdown...
const GROUP_GAP_M = 1000;
// ...which reaches this far before the first and past the last.
const GROUP_MARGIN_M = 200;
// The route sent to PostGIS: a point every so many metres, no more than this many.
const LINE_STEP_M = 30;
const LINE_MAX_POINTS = 2000;
// A crawl slower than this is still moving (a measured speed of 0 would mean an endless jam).
const MIN_SPEED_KMH = 3;

/**
 * What drivers say about the traffic on a route ([path], see routing/geometry.js), besides
 * TomTom: confirmed "Bouchon" reports on it, the same way (2 drivers, an admin's, or made from
 * probes), and slowdowns probeClusterMinDrivers drivers measured. Stretches in metres along
 * the route, with the time they cost: measured from the probes' speeds against the road's
 * limit when there are some, and from what the driver said otherwise (léger, important, à
 * l’arrêt: crowdJamDelayS).
 */
export async function crowdAlong(path) {
  if (path.points.length < 2) return [];
  const grid = gridOf(path.points);
  const onRoute = (lat, lon, course, maxDeg) => {
    const hit = project(path, grid, lat, lon, config.crowdOnRouteM);
    if (!hit || course == null) return null;
    return angleDiff(course, headingAt(path, hit.along)) <= maxDeg ? hit.along : null;
  };

  const reports = await reportStore.liveAlong('traffic_jam', lineOf(path), config.crowdOnRouteM);
  const confirmed = reports
    .filter((r) => r.reporters >= 2 || r.confirmations >= 1 || r.reporter_role === 'admin' || r.reporter_role === 'system')
    .map((r) => ({ along: onRoute(r.lat, r.lon, r.course, config.crowdSameWayDeg), severity: r.severity }))
    .filter((r) => r.along != null)
    .map((r) => ({
      fromM: r.along - config.crowdJamHalfLengthM,
      toM: r.along + config.crowdJamHalfLengthM,
      // What the driver saw decides the cost when no probe measured the jam.
      delayS: config.crowdJamDelayS[r.severity] ?? config.crowdJamDefaultDelayS,
    }));

  const probes = probeStore.recent()
    .map((p) => ({ ...p, along: onRoute(p.lat, p.lon, p.course, config.probeSameWayDeg) }))
    .filter((p) => p.along != null)
    .sort((a, b) => a.along - b.along);
  const groups = [];
  for (const probe of probes) {
    const last = groups[groups.length - 1];
    if (last && probe.along - last.lastM <= GROUP_GAP_M) {
      last.probes.push(probe);
      last.lastM = probe.along;
    } else {
      groups.push({ firstM: probe.along, lastM: probe.along, probes: [probe] });
    }
  }

  // A report measured by probes takes their extent and delay; enough drivers make a jam alone.
  const zones = [];
  for (const group of groups) {
    const fromM = group.firstM - GROUP_MARGIN_M;
    const toM = group.lastM + GROUP_MARGIN_M;
    const reported = confirmed.filter((zone) => zone.toM >= fromM && zone.fromM <= toM);
    const drivers = new Set(group.probes.map((p) => p.voter)).size;
    if (!reported.length && drivers < config.probeClusterMinDrivers) continue;
    const from = Math.min(fromM, ...reported.map((zone) => zone.fromM));
    const to = Math.max(toM, ...reported.map((zone) => zone.toM));
    zones.push({ fromM: from, toM: to, delayS: measuredDelay(group.probes, to - from) });
    for (const zone of reported) zone.measured = true;
  }
  for (const zone of confirmed) {
    if (!zone.measured) zones.push({ fromM: zone.fromM, toM: zone.toM, delayS: zone.delayS });
  }

  // Overlapping zones are one jam: its extent, the worst of their delays.
  zones.sort((a, b) => a.fromM - b.fromM);
  const merged = [];
  for (const zone of zones) {
    const fromM = Math.max(0, zone.fromM);
    const toM = Math.min(path.total, zone.toM);
    if (toM <= fromM) continue;
    const last = merged[merged.length - 1];
    if (last && fromM <= last.toM) {
      last.toM = Math.max(last.toM, toM);
      last.delayS = Math.max(last.delayS, zone.delayS);
    } else {
      merged.push({ fromM, toM, delayS: zone.delayS });
    }
  }
  return merged.map((zone) => ({
    fromM: Math.round(zone.fromM),
    toM: Math.round(zone.toM),
    level: 'jam',
    delayS: Math.round(zone.delayS),
    speedKmh: null,
    source: 'crowd',
  }));
}

/**
 * TomTom's stretches with the drivers' jams added where they cost more than TomTom says: the
 * extra time only (the worst of the two, never both), as 'crowd' stretches.
 */
export function withCrowd(sections, crowd) {
  const extra = [];
  for (const zone of crowd) {
    const known = sections.reduce((sum, s) => {
      const overlap = Math.min(s.toM, zone.toM) - Math.max(s.fromM, zone.fromM);
      return overlap > 0 && s.toM > s.fromM ? sum + ((s.delayS ?? 0) * overlap) / (s.toM - s.fromM) : sum;
    }, 0);
    const delayS = Math.round(zone.delayS - known);
    if (delayS > 0) extra.push({ ...zone, delayS });
  }
  return sections.concat(extra).sort((a, b) => a.fromM - b.fromM);
}

/** The time the drivers' jams add to TomTom's on a route (withCrowd's stretches). */
export function crowdExtraS(sections) {
  return sections.reduce((sum, s) => (s.source === 'crowd' ? sum + s.delayS : sum), 0);
}

/**
 * Time lost over [lengthM] at the probes' median speed rather than the road's limit (their
 * median too).
 */
function measuredDelay(probes, lengthM) {
  const speed = Math.max(median(probes.map((p) => p.speedKmh)), MIN_SPEED_KMH) / 3.6;
  const free = median(probes.map((p) => p.limitKmh)) / 3.6;
  return Math.max(0, lengthM / speed - lengthM / free);
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/** The route as a GeoJSON LineString ([lon, lat]), light enough for PostGIS. */
function lineOf(path) {
  const step = Math.max(LINE_STEP_M, path.total / LINE_MAX_POINTS);
  return { type: 'LineString', coordinates: samplesBetween(path, 0, path.total, step).map(([lat, lon]) => [lon, lat]) };
}
