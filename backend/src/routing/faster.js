import { config } from '../config.js';
import { bearingDeg, haversine } from '../radars/geo.js';
import { trafficAlong } from '../traffic/tomtom.js';
import { ORS_AVOID, normalizeOrsFeature, postORS, square } from './ors.js';

// Around a jam, ORS is kept off squares this wide on each side of the route...
const AVOID_HALF_SIDE_M = 60;
// ...one every so many metres along it (overlapping: a continuous corridor).
const AVOID_SPACING_M = 80;
// Enough squares for a long jam; beyond, they spread out and grow.
const AVOID_MAX_SQUARES = 300;
// The first and last metres of the route stay open: the driver is on them, the destination too.
const KEEP_OPEN_M = 300;
// A sample this close to a route lies on that road.
const SAME_ROAD_M = 40;
// Two routes sharing this much of each other are one route.
const SAME_ROUTE_SHARE = 0.9;
// Routes are compared at a sample every so many metres.
const SAMPLE_M = 200;
// Heading of the driver: the route's first metres.
const HEADING_M = 30;
// A variant ORS already times (without traffic) this much over the current time with traffic
// cannot win: TomTom is not asked.
const ORS_SLACK = 1.15;
// A pause between TomTom requests: its free tier refuses bursts.
const TOMTOM_GAP_MS = 250;
// Grid cell for "is this point on that route" (degrees, ~150-220 m).
const CELL_DEG = 0.002;

/**
 * Whether a faster way exists around the traffic on the rest of the route being followed
 * ([points], [lat, lon], from the driver to the destination). ORS keeps drawing the routes and
 * TomTom keeps timing them; this only compares:
 * 1. TomTom times the rest of the route with today's traffic and lists its slowdowns;
 * 2. slowdowns close together make one jam; only jams worth a detour are kept, and nothing is
 *    searched when all of them together could not save the minimum gain;
 * 3. ORS proposes variants: around every big jam, around the worst one alone, and its own
 *    alternatives;
 * 4. a variant that is the route itself, still goes through every jam, or that ORS alone
 *    already finds slower than the route with traffic, is dropped;
 * 5. TomTom times each variant left with today's traffic;
 * 6. the fastest replaces the route only for a real gain: rerouteMinGainS and
 *    rerouteMinGainRatio of the time left, twice that for a while after a reroute
 *    ([sinceRerouteS]), and nothing at all just after one.
 * A jam alone never moves the driver: only the time saved does.
 */
export async function fasterRoute(points, { avoid = [], sinceRerouteS = null } = {}) {
  if (sinceRerouteS != null && sinceRerouteS < config.rerouteCooldownS) return { better: null, reason: 'cooldown' };

  const path = measure(points);
  const current = await trafficAlong(points);
  const currentS = current.travelS;
  if (!(currentS > 0)) return { better: null, reason: 'no time' };
  const sticky = sinceRerouteS != null && sinceRerouteS < config.rerouteStickyS;
  const thresholdS = Math.round(
    Math.max(config.rerouteMinGainS, config.rerouteMinGainRatio * currentS) * (sticky ? config.rerouteStickyFactor : 1),
  );

  const jams = jamsOf(current.sections, path.total);
  const lostS = jams.reduce((sum, jam) => sum + jam.delayS, 0);
  const summary = { currentS, thresholdS, jams: jams.map(({ fromM, toM, delayS, closed }) => ({ fromM, toM, delayS, closed })) };
  if (!jams.length || (!jams.some((jam) => jam.closed) && lostS < thresholdS)) {
    return { ...summary, better: null, reason: 'no significant jam' };
  }

  // The variants, from the driver (heading kept: no U-turn) to the same destination.
  const [start, end] = [points[0], points[points.length - 1]];
  const ahead = pointAt(path, Math.min(HEADING_M, path.total));
  const heading = Math.round(bearingDeg(start[0], start[1], ahead[0], ahead[1]));
  const features = avoid.map((a) => ORS_AVOID[a]).filter(Boolean);
  const ask = (label, polygons, extra = {}) => {
    const options = {};
    if (features.length) options.avoid_features = features;
    if (polygons) options.avoid_polygons = polygons;
    return orsRoutes(label, {
      coordinates: [[start[1], start[0]], [end[1], end[0]]],
      bearings: [[heading, 45]],
      instructions: true,
      maneuvers: true,
      geometry_simplify: false,
      ...(Object.keys(options).length ? { options } : {}),
      ...extra,
    });
  };
  const worst = jams.reduce((a, b) => (weight(b) > weight(a) ? b : a));
  const asks = [ask('around', polygonsAround(path, jams))];
  if (jams.length > 1) asks.push(ask('around worst', polygonsAround(path, [worst])));
  asks.push(ask('alternatives', null, { alternative_routes: { target_count: 3, weight_factor: 1.6, share_factor: 0.6 } }));
  const found = (await Promise.all(asks)).flat();

  const routeGrid = gridOf(points);
  const routeSamples = samplesBetween(path, 0, path.total);
  const jamSamples = jams.map((jam) => samplesBetween(path, jam.fromM, jam.toM));
  const candidates = [];
  for (const route of found.sort((a, b) => a.durationS - b.durationS)) {
    if (route.durationS > currentS * ORS_SLACK) continue;
    const line = route.coordinates.map(([lon, lat]) => [lat, lon]);
    const grid = gridOf(line);
    const samples = samplesBetween(measure(line), 0, Infinity);
    const same = (other) => share(other.samples, grid) >= SAME_ROUTE_SHARE && share(samples, other.grid) >= SAME_ROUTE_SHARE;
    if (same({ samples: routeSamples, grid: routeGrid })) continue;
    if (jamSamples.every((jam) => share(jam, grid) >= SAME_ROUTE_SHARE)) continue;
    if (candidates.some(same)) continue;
    candidates.push({ route, line, grid, samples });
  }

  let best = null;
  let timed = 0;
  for (const candidate of candidates.slice(0, config.rerouteMaxVariants)) {
    await new Promise((resolve) => setTimeout(resolve, TOMTOM_GAP_MS));
    const traffic = await trafficAlong(candidate.line).catch((e) => {
      console.warn('[faster] TomTom variant —', String(e.message || e));
      return null;
    });
    if (!(traffic?.travelS > 0)) continue;
    timed += 1;
    if (!best || traffic.travelS < best.travelS) best = { route: candidate.route, travelS: traffic.travelS };
  }

  const gainS = best ? currentS - best.travelS : 0;
  const switching = best != null && gainS >= thresholdS;
  console.log(
    `[faster] now ${currentS}s, ${jams.length} jam(s) ${lostS}s lost, ${found.length} ORS route(s), ${timed} timed, ` +
      `best ${best ? best.travelS + 's' : '-'}, threshold ${thresholdS}s${sticky ? ' (sticky)' : ''} → ${switching ? 'switch' : 'keep'}`,
  );
  const result = { ...summary, variants: timed, bestS: best?.travelS ?? null };
  if (!switching) return { ...result, better: null, reason: best ? 'not enough gain' : 'no variant' };
  // The time shown for the new route is TomTom's, with traffic: the one the gain was measured on.
  return { ...result, better: { gainS, route: { ...best.route, durationS: best.travelS } } };
}

/** A closed road outweighs any delay. */
function weight(jam) {
  return jam.closed ? Infinity : jam.delayS;
}

/**
 * The jams on the route: slowdowns (TomTom's stretches, sorted) merged when closer than
 * rerouteJamGapM, kept when one is worth a detour (rerouteJamMinDelayS, or closed) and lies
 * beyond the route's first and last metres.
 */
function jamsOf(sections, totalM) {
  const jams = [];
  for (const section of sections) {
    const delayS = section.delayS ?? 0;
    const closed = section.level === 'closed';
    if (!(delayS > 0) && !closed) continue;
    const last = jams[jams.length - 1];
    if (last && section.fromM - last.toM <= config.rerouteJamGapM) {
      last.toM = Math.max(last.toM, section.toM);
      last.delayS += delayS;
      last.closed ||= closed;
    } else {
      jams.push({ fromM: section.fromM, toM: section.toM, delayS, closed });
    }
  }
  return jams.filter((jam) => (jam.closed || jam.delayS >= config.rerouteJamMinDelayS)
    && jam.toM > KEEP_OPEN_M && jam.fromM < totalM - KEEP_OPEN_M);
}

/** Squares along [jams] (the route's first and last metres left open), one MultiPolygon for ORS. */
function polygonsAround(path, jams) {
  const ranges = jams
    .map((jam) => [Math.max(jam.fromM, KEEP_OPEN_M), Math.min(jam.toM, path.total - KEEP_OPEN_M)])
    .filter(([from, to]) => to >= from);
  const length = ranges.reduce((sum, [from, to]) => sum + to - from, 0);
  const spacing = Math.max(AVOID_SPACING_M, length / AVOID_MAX_SQUARES);
  const half = Math.max(AVOID_HALF_SIDE_M, spacing * 0.75);
  const squares = ranges.flatMap(([from, to]) => samplesBetween(path, from, to, spacing).map(([lat, lon]) => square(lat, lon, half)));
  return squares.length ? { type: 'MultiPolygon', coordinates: squares } : null;
}

/** ORS's routes for [body] in the app's shape; none when ORS says no. */
async function orsRoutes(label, body) {
  try {
    const r = await postORS(body);
    if (!r.ok) {
      const detail = await r.text().catch(() => '');
      console.warn(`[faster] ORS ${label} ${r.status} — ${detail.slice(0, 160)}`);
      return [];
    }
    const json = await r.json();
    return (json.features ?? []).map(normalizeOrsFeature);
  } catch (e) {
    console.warn(`[faster] ORS ${label} —`, String(e.message || e));
    return [];
  }
}

// ---- Geometry ([lat, lon]) ----------------------------------------------------

/** [points] with their cumulative metres. */
function measure(points) {
  const cum = [0];
  for (let i = 1; i < points.length; i++) {
    cum.push(cum[i - 1] + haversine(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1]));
  }
  return { points, cum, total: cum[cum.length - 1] };
}

/** The point [d] metres along [path]. */
function pointAt(path, d) {
  const { points, cum } = path;
  if (points.length === 1) return points[0];
  let lo = 0;
  let hi = points.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] <= d) lo = mid;
    else hi = mid;
  }
  const length = cum[hi] - cum[lo];
  const t = length > 0 ? Math.min(Math.max((d - cum[lo]) / length, 0), 1) : 0;
  return [points[lo][0] + (points[hi][0] - points[lo][0]) * t, points[lo][1] + (points[hi][1] - points[lo][1]) * t];
}

/** Points every [step] metres from [fromM] to [toM] (clamped to the path), both ends included. */
function samplesBetween(path, fromM, toM, step = SAMPLE_M) {
  const end = Math.min(toM, path.total);
  const out = [];
  for (let d = Math.max(fromM, 0); d < end; d += step) out.push(pointAt(path, d));
  out.push(pointAt(path, end));
  return out;
}

/** [line]'s segments by grid cell, to find the road near a point without scanning it all. */
function gridOf(line) {
  const cells = new Map();
  for (let i = 0; i < line.length - 1; i++) {
    const [a, b] = [line[i], line[i + 1]];
    const x0 = Math.floor(Math.min(a[1], b[1]) / CELL_DEG);
    const x1 = Math.floor(Math.max(a[1], b[1]) / CELL_DEG);
    const y0 = Math.floor(Math.min(a[0], b[0]) / CELL_DEG);
    const y1 = Math.floor(Math.max(a[0], b[0]) / CELL_DEG);
    for (let x = x0; x <= x1; x++) {
      for (let y = y0; y <= y1; y++) {
        const key = `${x},${y}`;
        const list = cells.get(key);
        if (list) list.push(i);
        else cells.set(key, [i]);
      }
    }
  }
  return { line, cells };
}

/** Share of [samples] lying on the road of [grid]. */
function share(samples, grid) {
  if (!samples.length) return 0;
  let on = 0;
  for (const [lat, lon] of samples) if (onRoad(grid, lat, lon)) on++;
  return on / samples.length;
}

function onRoad(grid, lat, lon) {
  const cx = Math.floor(lon / CELL_DEG);
  const cy = Math.floor(lat / CELL_DEG);
  const mLat = 111_320;
  const mLon = 111_320 * Math.cos((lat * Math.PI) / 180);
  for (let x = cx - 1; x <= cx + 1; x++) {
    for (let y = cy - 1; y <= cy + 1; y++) {
      for (const i of grid.cells.get(`${x},${y}`) ?? []) {
        const [a, b] = [grid.line[i], grid.line[i + 1]];
        const ax = (a[1] - lon) * mLon;
        const ay = (a[0] - lat) * mLat;
        const dx = (b[1] - a[1]) * mLon;
        const dy = (b[0] - a[0]) * mLat;
        const length2 = dx * dx + dy * dy;
        const t = length2 === 0 ? 0 : Math.min(Math.max(-(ax * dx + ay * dy) / length2, 0), 1);
        if (Math.hypot(ax + t * dx, ay + t * dy) <= SAME_ROAD_M) return true;
      }
    }
  }
  return false;
}
