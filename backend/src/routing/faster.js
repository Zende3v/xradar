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
// Jams starting this far ahead at most are gone around now; farther ones are looked at again
// once closer (they may be gone by then).
const HORIZON_M = 60_000;
// The detour rejoins the route this far past the last jam it goes around...
const REJOIN_AFTER_M = 5_000;
// ...and never farther along than this: ORS takes its alternatives up to 100 km and avoided
// areas up to 150 km (straight line, which the distance along the route bounds).
const WINDOW_MAX_M = 85_000;
// A sample this close to a route lies on that road.
const SAME_ROAD_M = 40;
// Two routes sharing this much of each other are one route.
const SAME_ROUTE_SHARE = 0.9;
// A variant going through half a closed stretch or more still meets the closure.
const THROUGH_CLOSURE_SHARE = 0.5;
// Routes are compared at a sample every so many metres.
const SAMPLE_M = 200;
// Heading of the driver (and at the rejoin point): the route's next metres.
const HEADING_M = 30;
// A pause between TomTom requests: its free tier refuses bursts.
const TOMTOM_GAP_MS = 250;
// Grid cell for "is this point on that route" (degrees, ~150-220 m).
const CELL_DEG = 0.002;

/**
 * Whether a faster way exists around the traffic on the rest of the route being followed
 * ([points], [lat, lon], from the driver to the destination). ORS keeps drawing the routes and
 * TomTom keeps timing them; this only compares:
 * 1. TomTom times the rest of the route with today's traffic and lists its slowdowns;
 * 2. slowdowns close together make one jam; only jams worth a detour and near enough
 *    (HORIZON_M) are kept, and nothing is searched when all of them together could not save
 *    the minimum gain (a closed road always is);
 * 3. a local detour: ORS draws variants from the driver to a point of the route past the jams
 *    (within its distance limits) — around every jam, around the worst one alone, and its own
 *    alternatives —, each followed by the same rest of the route;
 * 4. a variant that is the route itself, still goes through every jam (or through a closure),
 *    or that ORS alone finds slower than the route by more than the time the jams cost, is
 *    dropped;
 * 5. TomTom times each variant left, whole, with today's traffic;
 * 6. the fastest replaces the route only for a real gain: rerouteMinGainS and
 *    rerouteMinGainRatio of the time left, twice that for a while after a reroute
 *    ([sinceRerouteS]), and nothing at all just after one. A closed road is gone around by
 *    the fastest variant that avoids it, whatever the gain.
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

  // The jams near enough to go around now, the detour rejoining the route past them.
  const short = path.total <= WINDOW_MAX_M;
  const jams = jamsOf(current.sections, path.total)
    .filter((jam) => jam.fromM <= HORIZON_M && (short || jam.toM + REJOIN_AFTER_M <= WINDOW_MAX_M));
  const closed = jams.some((jam) => jam.closed);
  const lostS = jams.reduce((sum, jam) => sum + jam.delayS, 0);
  const summary = { currentS, thresholdS, jams: jams.map(({ fromM, toM, delayS, closed: shut }) => ({ fromM, toM, delayS, closed: shut })) };
  if (!jams.length || (!closed && lostS < thresholdS)) {
    return { ...summary, better: null, reason: 'no significant jam' };
  }
  let rejoinM = short ? path.total : Math.max(...jams.map((jam) => jam.toM)) + REJOIN_AFTER_M;
  if (rejoinM >= path.total - KEEP_OPEN_M) rejoinM = path.total;
  const window = sliceOf(path, 0, rejoinM);
  const tail = rejoinM < path.total ? sliceOf(path, rejoinM, path.total) : null;

  // The variants over the window, from the driver (heading kept: no U-turn) to the rejoin point
  // (reached the way the route goes) or the destination.
  const [start, end] = [window[0], window[window.length - 1]];
  const bearings = [[headingAt(path, 0), 45]];
  if (tail) bearings.push([headingAt(path, rejoinM), 45]);
  const features = avoid.map((a) => ORS_AVOID[a]).filter(Boolean);
  const ask = (label, polygons, extra = {}) => {
    const options = {};
    if (features.length) options.avoid_features = features;
    if (polygons) options.avoid_polygons = polygons;
    return orsRoutes(label, {
      coordinates: [[start[1], start[0]], [end[1], end[0]]],
      bearings,
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

  // Compared over the window: the rest of the route is the same for all of them.
  const windowGrid = gridOf(window);
  const windowSamples = samplesBetween(measure(window), 0, Infinity);
  const jamSamples = jams.map((jam) => samplesBetween(path, jam.fromM, jam.toM));
  const closureSamples = jams.filter((jam) => jam.closed).map((jam) => samplesBetween(path, jam.fromM, jam.toM));
  // ORS's own time for the window as it is (the variant that is the route).
  let baselineS = null;
  const candidates = [];
  for (const route of found.sort((a, b) => a.durationS - b.durationS)) {
    const line = route.coordinates.map(([lon, lat]) => [lat, lon]);
    const grid = gridOf(line);
    const samples = samplesBetween(measure(line), 0, Infinity);
    const same = (other) => share(other.samples, grid) >= SAME_ROUTE_SHARE && share(samples, other.grid) >= SAME_ROUTE_SHARE;
    if (same({ samples: windowSamples, grid: windowGrid })) {
      baselineS = Math.min(baselineS ?? Infinity, route.durationS);
      continue;
    }
    if (closureSamples.some((closure) => share(closure, grid) >= THROUGH_CLOSURE_SHARE)) continue;
    if (!closed && jamSamples.every((jam) => share(jam, grid) >= SAME_ROUTE_SHARE)) continue;
    if (candidates.some(same)) continue;
    candidates.push({ route, line, grid, samples });
  }
  // Slower than the route by more than the jams cost, without traffic: it cannot win.
  const viable = candidates
    .filter((candidate) => closed || baselineS == null || candidate.route.durationS <= baselineS + lostS)
    .slice(0, config.rerouteMaxVariants);

  // Each variant timed whole (the same rest of the route after it), like the route now.
  let best = null;
  let timed = 0;
  for (const candidate of viable) {
    await new Promise((resolve) => setTimeout(resolve, TOMTOM_GAP_MS));
    const whole = tail ? candidate.line.concat(tail.slice(1)) : candidate.line;
    const traffic = await trafficAlong(whole).catch((e) => {
      console.warn('[faster] TomTom variant —', String(e.message || e));
      return null;
    });
    if (!(traffic?.travelS > 0)) continue;
    timed += 1;
    if (!best || traffic.travelS < best.travelS) best = { route: candidate.route, travelS: traffic.travelS };
  }

  const gainS = best ? currentS - best.travelS : 0;
  const switching = best != null && (closed || gainS >= thresholdS);
  console.log(
    `[faster] now ${currentS}s, ${jams.length} jam(s) ${lostS}s lost${closed ? ' + closed' : ''}, window ${Math.round(rejoinM / 1000)}/${Math.round(path.total / 1000)} km, ` +
      `${found.length} ORS route(s), ${timed} timed, best ${best ? best.travelS + 's' : '-'}, threshold ${thresholdS}s${sticky ? ' (sticky)' : ''} → ${switching ? 'switch' : 'keep'}`,
  );
  const result = { ...summary, variants: timed, bestS: best?.travelS ?? null };
  if (!switching) return { ...result, better: null, reason: best ? 'not enough gain' : 'no variant' };
  const route = tail ? await withRest(best.route, tail, headingAt(path, rejoinM), features) : best.route;
  if (!route) return { ...result, better: null, reason: 'rest of the route unavailable' };
  // The time shown for the new route is TomTom's, with traffic: the one the gain was measured on.
  return { ...result, better: { gainS: Math.max(0, gainS), closed, route: { ...route, durationS: best.travelS } } };
}

/**
 * The winning detour, then ORS's route from the rejoin point to the destination (the same road
 * as the route's rest): one route to follow, with its steps.
 */
async function withRest(detour, rest, heading, features) {
  const [from, to] = [rest[0], rest[rest.length - 1]];
  const [route] = await orsRoutes('rest', {
    coordinates: [[from[1], from[0]], [to[1], to[0]]],
    bearings: [[heading, 45]],
    instructions: true,
    maneuvers: true,
    geometry_simplify: false,
    ...(features.length ? { options: { avoid_features: features } } : {}),
  });
  if (!route) return null;
  return {
    distanceM: detour.distanceM + route.distanceM,
    durationS: detour.durationS + route.durationS,
    coordinates: detour.coordinates.concat(route.coordinates.slice(1)),
    // One trip: no "arrive" at the rejoin point, no "depart" from it.
    steps: detour.steps.filter((step) => step.type !== 'arrive').concat(route.steps.filter((step) => step.type !== 'depart')),
  };
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

/** The route's points from [fromM] to [toM], the cut ends interpolated. */
function sliceOf(path, fromM, toM) {
  const out = [pointAt(path, fromM)];
  for (let i = 0; i < path.points.length; i++) {
    if (path.cum[i] > fromM && path.cum[i] < toM) out.push(path.points[i]);
  }
  out.push(pointAt(path, toM));
  return out;
}

/** The route's heading at [d] metres along (the next metres; the last ones at the end). */
function headingAt(path, d) {
  const from = Math.min(d, Math.max(path.total - HEADING_M, 0));
  const a = pointAt(path, from);
  const b = pointAt(path, Math.min(from + HEADING_M, path.total));
  return Math.round(bearingDeg(a[0], a[1], b[0], b[1]));
}

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
