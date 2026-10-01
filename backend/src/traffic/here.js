import { config } from '../config.js';
import { gridOf, measure, project, samplesBetween } from '../routing/geometry.js';
import { reserveHere } from './budget.js';
import { hereCache, hereCacheKey } from './here-cache.js';

/**
 * HERE Traffic API v7 on our own route (the live traffic provider since 30/09, in place of
 * TomTom): its flow (speed, free-flow speed, jam factor per road segment, sub-segments included)
 * and its incidents (closures, accidents, works, events), asked in a corridor around the rest of
 * the route, then placed back on it in metres. A segment counts only when both its ends lie on
 * the route in its order: the other carriageway and the crossing roads fall out. The delay of a
 * slowed piece is its time at the measured speed less its time at the free-flow speed; an incident
 * says where, not how long (the flow carries the delay). Stretches carry `source: "here"`.
 * With HERE_DEEP_COVERAGE=1, the flow asks for Deep Coverage (more urban roads, the Advanced
 * Traffic price).
 */

const SOURCE = 'here';
const ENCODING = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
const importStatus = { evaluations: 0, valid: 0, lastReason: null, rejected: {} };
function rejectImport(reason) {
  importStatus.lastReason = reason;
  importStatus.rejected[reason] = (importStatus.rejected[reason] ?? 0) + 1;
  return null;
}
export const hereQuality = () => ({ import: { ...importStatus, rejected: { ...importStatus.rejected } } });

/**
 * Our own route ([points], [lat, lon], from the driver on) timed by HERE with the live traffic
 * (Route Import): { travelS, baseS, typicalS, lengthM }, or null when HERE matched another road
 * (its length off ours by more than hereImportMaxLengthGap). Throws when HERE does not answer.
 * The ETA's time: the engine's alone is far too quick in towns (config.js, 30/09).
 */
export async function hereTravel(points, { use = 'eta', fetchImpl = globalThis.fetch, reserve = reserveHere, cache = hereCache } = {}) {
  importStatus.evaluations++;
  const path = measure(points);
  const step = Math.max(config.hereImportStepM, path.total / (config.hereImportMaxPoints - 1));
  const sampled = samplesBetween(path, 0, path.total, step);
  const tracePoints = sampled.slice(0, config.hereImportMaxPoints);
  tracePoints[tracePoints.length - 1] = points[points.length - 1];
  const trace = tracePoints.map(([lat, lng]) => ({ lat, lng }));
  if (trace.length < 2 || !(path.total > 0)) return rejectImport('invalid trace');
  const query = new URLSearchParams({
    transportMode: 'car',
    return: 'summary,typicalDuration',
    departureTime: `${new Date().toISOString().slice(0, 19)}Z`,
    apiKey: config.hereApiKey,
  });
  const load = async () => {
  if (!reserve(use, 'import')) throw new Error('HERE budget unavailable');
  const res = await fetchImpl(`${config.hereRouterUrl.replace(/\/$/, '')}/import?${query}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ trace }),
    signal: AbortSignal.timeout(config.hereTimeoutMs),
  });
  if (!res.ok) throw new Error(`HERE import ${res.status}`);
  return await res.json();
  };
  let json;
  try { json = cache ? await cache.get(hereCacheKey('import', [config.hereRouterUrl, trace]), load) : await load(); }
  catch (error) { rejectImport(error.message === 'HERE budget unavailable' ? 'budget unavailable' : 'request unavailable'); throw error; }
  const route = json.routes?.[0];
  const sections = route?.sections ?? [];
  if ([...(route?.notices ?? []), ...sections.flatMap(s=>s.notices ?? [])].some(n=>n.severity === 'critical' || /^violated/i.test(n.code ?? ''))) return rejectImport('notice');
  if (!sections.every(s=>Number.isFinite(s.summary?.duration) && s.summary.duration > 0 && Number.isFinite(s.summary?.length) && s.summary.length > 0)) return rejectImport('invalid summary');
  if (!sections.length) return rejectImport('no sections');
  const sum = (key) => sections.reduce((total, section) => total + (Number(section.summary?.[key]) || 0), 0);
  const lengthM = sum('length');
  if (!(lengthM > 0) || Math.abs(lengthM - path.total) > path.total * config.hereImportMaxLengthGap) return rejectImport('length mismatch');
  importStatus.valid++;
  importStatus.lastReason = null;
  return {
    travelS: Math.round(sum('duration')),
    baseS: Math.round(sum('baseDuration')),
    typicalS: sections.every((section) => section.summary?.typicalDuration != null) ? Math.round(sum('typicalDuration')) : null,
    lengthM: Math.round(lengthM),
  };
}

/** HERE's Flexible Polyline (github.com/heremaps/flexible-polyline), 2D, for [lat, lon] points. */
export function flexiblePolyline(points, precision = 5) {
  const unsigned = (value) => {
    let out = '';
    let v = value;
    while (v > 0x1f) {
      out += ENCODING[(v & 0x1f) | 0x20];
      v = Math.floor(v / 32);
    }
    return out + ENCODING[v];
  };
  const signed = (value) => unsigned(value < 0 ? -2 * value - 1 : 2 * value);
  const factor = 10 ** precision;
  let out = unsigned(1) + unsigned(precision);
  let [lastLat, lastLon] = [0, 0];
  for (const [lat, lon] of points) {
    const [a, b] = [Math.round(lat * factor), Math.round(lon * factor)];
    out += signed(a - lastLat) + signed(b - lastLon);
    [lastLat, lastLon] = [a, b];
  }
  return out;
}

/** The corridor around the first hereCorridorMaxM of [path]: 300 points at most, as HERE takes them. */
export function corridorOf(path) {
  const endM = Math.min(path.total, config.hereCorridorMaxM);
  const step = Math.max(config.hereCorridorStepM, endM / (config.hereCorridorMaxPoints - 1));
  const points = samplesBetween(path, 0, endM, step).slice(0, config.hereCorridorMaxPoints);
  return { polyline: flexiblePolyline(points), endM };
}

/** One HERE Traffic request (flow | incidents) over [corridor], counted for [use]. */
async function ask(kind, corridor, use, fetchImpl = globalThis.fetch, reserve = reserveHere, cache = hereCache) {
  const body = {
    in: { type: 'corridor', corridor: corridor.polyline, radius: config.hereCorridorRadiusM },
    locationReferencing: ['shape'],
    ...(kind === 'flow' && config.hereDeepCoverage ? { advancedFeatures: ['deepCoverage'] } : {}),
  };
  const load = async () => {
  if (!reserve(use, kind)) throw new Error('HERE budget unavailable');
  const res = await fetchImpl(`${config.hereTrafficUrl.replace(/\/$/, '')}/${kind}?apiKey=${encodeURIComponent(config.hereApiKey)}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(config.hereTimeoutMs),
  });
  if (!res.ok) throw new Error(`HERE ${kind} ${res.status}`);
  const json = await res.json();
  if (!Array.isArray(json.results)) throw new Error('HERE invalid ' + kind);
  return json.results;
  };
  return cache ? cache.get(hereCacheKey(kind, [config.hereTrafficUrl, body]), load) : load();
}

/**
 * The live traffic on a route ([points], [lat, lon], from the driver on): { totalM, travelS: null,
 * delayS, sections } — flow slowdowns and incidents in metres along [points]. Throws when HERE
 * does not answer. [fetchImpl] stands for the network in the tests.
 */
export async function hereAlong(points, { use = 'eta', fetchImpl, reserve = reserveHere, cache = hereCache } = {}) {
  const path = measure(points);
  const corridor = corridorOf(path);
  const [flow, incidents] = await Promise.allSettled([
    ask('flow', corridor, use, fetchImpl, reserve, cache),
    ask('incidents', corridor, use, fetchImpl, reserve, cache),
  ]);
  if (flow.status === 'rejected' && incidents.status === 'rejected') throw flow.reason;
  const sections = placeOnRoute(path, flow.status === 'fulfilled' ? flow.value : [], incidents.status === 'fulfilled' ? incidents.value : []);
  return {
    totalM: Math.round(path.total),
    flowAvailable: flow.status === 'fulfilled',
    incidentsAvailable: incidents.status === 'fulfilled',
    coverageM: corridor.endM,
    travelS: null,
    delayS: sections.reduce((sum, s) => sum + (s.delayS ?? 0), 0),
    updatedAt: new Date().toISOString(),
    sections,
  };
}

/** HERE's flow and incidents on [path]: the slowed pieces and the incidents, in metres along it. */
export function placeOnRoute(path, flow, incidents) {
  const grid = gridOf(path.points);
  const along = (point) => project(path, grid, point.lat, point.lng, config.hereOnRouteM)?.along ?? null;
  // Where an item lies on the route: its first and last points, the way the route goes.
  const range = (location) => {
    const points = (location?.shape?.links ?? []).flatMap((link) => link.points ?? []);
    if (points.length < 2) return null;
    const [fromM, toM] = [along(points[0]), along(points[points.length - 1])];
    return fromM != null && toM != null && toM - fromM >= 1 ? [fromM, toM] : null;
  };

  const pieces = [];
  for (const item of flow) {
    const place = range(item.location);
    const current = item.currentFlow;
    if (!place || !current) continue;
    // Its sub-segments, in order, share the piece by their lengths; without them, the item is one.
    const parts = current.subSegments?.length ? current.subSegments : [{ ...current, length: item.location?.length }];
    const total = parts.reduce((sum, part) => sum + (part.length ?? 0), 0);
    let at = place[0];
    for (const part of parts) {
      const length = total > 0 ? ((place[1] - place[0]) * (part.length ?? 0)) / total : place[1] - place[0];
      const slowed = slowdownOf(part, length);
      if (slowed) pieces.push({ fromM: Math.round(at), toM: Math.round(at + length), ...slowed, source: SOURCE, kind: 'speed' });
      at += length;
    }
  }
  const sections = withoutOverlaps(pieces.filter((p) => p.toM > p.fromM));
  for (const item of incidents) {
    const place = range(item.location);
    const details = item.incidentDetails;
    if (!place || !details) continue;
    const kind = details.roadClosed ? 'closed' : INCIDENT_KIND[details.type] ?? 'incident';
    sections.push({
      fromM: Math.round(place[0]),
      toM: Math.round(place[1]),
      level: kind === 'closed' ? 'closed' : kind === 'queue' ? 'jam' : 'slow',
      delayS: 0,
      source: SOURCE,
      kind,
      label: details.typeDescription?.value ?? null,
    });
  }
  return sections.filter((s) => s.toM > s.fromM).sort((a, b) => a.fromM - b.fromM);
}

const LEVEL_RANK = { slow: 1, jam: 2, heavy: 3, closed: 4 };

/**
 * Flow pieces without overlaps: HERE sends one road under several items (a crossing under each
 * street's name, a quay under two descriptions), seen in Paris on 30/09. Each metre keeps the
 * worst piece over it only (level, then delay per metre), so no delay counts twice.
 */
function withoutOverlaps(pieces) {
  const worse = (a, b) =>
    LEVEL_RANK[a.level] - LEVEL_RANK[b.level] || a.delayS / (a.toM - a.fromM) - b.delayS / (b.toM - b.fromM);
  const cuts = [...new Set(pieces.flatMap((p) => [p.fromM, p.toM]))].sort((a, b) => a - b);
  const kept = [];
  for (let i = 0; i + 1 < cuts.length; i++) {
    const [fromM, toM] = [cuts[i], cuts[i + 1]];
    let worst = null;
    for (const p of pieces) if (p.fromM <= fromM && p.toM >= toM && (!worst || worse(p, worst) > 0)) worst = p;
    if (!worst) continue;
    const last = kept[kept.length - 1];
    if (last?.of === worst && last.toM === fromM) last.toM = toM;
    else kept.push({ of: worst, fromM, toM });
  }
  return kept.map(({ of, fromM, toM }) => ({
    ...of,
    fromM,
    toM,
    delayS: Math.round((of.delayS * (toM - fromM)) / (of.toM - of.fromM)),
  }));
}

/** HERE's incident types, as the apps name them (closures apart: roadClosed says so). */
const INCIDENT_KIND = {
  construction: 'works',
  laneRestriction: 'works',
  plannedEvent: 'event',
  congestion: 'queue',
  accident: 'incident',
  disabledVehicle: 'incident',
  roadHazard: 'incident',
  weather: 'incident',
  massTransit: 'event',
};

/**
 * A flow piece [length] metres long: { level, delayS, speedKmh }, or null when it flows. Closed
 * (traversability, or jam factor 10) always shows; otherwise the delay against the free-flow speed,
 * from hereMinJamFactor on.
 */
function slowdownOf(part, length) {
  const closed = part.traversability === 'closed' || part.jamFactor >= 10;
  if (closed) return { level: 'closed', delayS: 0, speedKmh: 0 };
  if (!(part.jamFactor >= config.hereMinJamFactor) || !(part.freeFlow > 0) || !(length > 0)) return null;
  const speed = Math.max(part.speedUncapped ?? part.speed ?? 0, config.hereMinSpeedMs);
  const delayS = Math.round(Math.max(0, length / speed - length / part.freeFlow));
  if (delayS <= 0) return null;
  const level = part.jamFactor >= 8 ? 'heavy' : part.jamFactor >= 5 ? 'jam' : 'slow';
  return { level, delayS, speedKmh: Math.round(speed * 3.6) };
}

/** Instantané partiel accepté pour l'ETA ; jamais suffisant pour imposer un détour. */
export async function hereSnapshot(points, { along = hereAlong, travel = hereTravel } = {}) {
  const [flow, timing] = await Promise.allSettled([along(points, { use: 'eta' }), travel(points, { use: 'eta' })]);
  const sections = flow.status === 'fulfilled' ? flow.value : null;
  const duration = timing.status === 'fulfilled' ? timing.value : null;
  return {
    live: Boolean(sections || duration),
    traffic: { totalM: Math.round(measure(points).total), delayS: null, sections: [], updatedAt: new Date().toISOString(),
      ...sections, travelS: duration?.travelS ?? null },
  };
}
