import { config } from '../config.js';
import { crowdAlong, crowdExtraS, withCrowd } from '../traffic/crowd.js';
import { trafficAlong } from '../traffic/tomtom.js';
import { gridOf, headingAt, measure, samplesBetween, share, sliceOf } from './geometry.js';
import { square } from './providers/ors.js';

// Around a jam, the engine is kept off squares this wide on each side of the route...
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
// areas up to 150 km (straight line, which the distance along the route bounds). Kept for
// Valhalla too, until the window is opened step by step on the bench (D4.5).
const WINDOW_MAX_M = 85_000;
// Two routes sharing this much of each other are one route.
const SAME_ROUTE_SHARE = 0.9;
// A variant going through half a closed stretch or more still meets the closure.
const THROUGH_CLOSURE_SHARE = 0.5;
// A pause between TomTom requests: its free tier refuses bursts.
const TOMTOM_GAP_MS = 250;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Whether a faster way exists around the traffic on the rest of the route being followed
 * ([points], [lat, lon], from the driver to the destination). An engine keeps drawing the
 * routes — ORS or Valhalla, whichever [draw] asks (engine.js, drawer) — and TomTom keeps
 * timing them; this only compares:
 * 1. TomTom times the rest of the route with today's traffic and lists its slowdowns; the
 *    drivers' jams (confirmed reports, probes: traffic/crowd.js) add what TomTom misses;
 * 2. slowdowns close together make one jam; only jams worth a detour and near enough
 *    (HORIZON_M) are kept, and nothing is searched when all of them together could not save
 *    the minimum gain (a closed road always is);
 * 3. a local detour: the engine draws variants from the driver to a point of the route past
 *    the jams (within the window) — around every jam, around the worst one alone, and its own
 *    alternatives —, each followed by the same rest of the route;
 * 4. a variant that is the route itself, still goes through every jam (or through a closure),
 *    or that the engine alone finds slower than the route by more than the time the jams cost,
 *    is dropped;
 * 5. TomTom times each variant left, whole, with today's traffic, plus the drivers' jams on it;
 * 6. the fastest replaces the route only for a real gain: rerouteMinGainS and
 *    rerouteMinGainRatio of the time left, twice that for a while after a reroute
 *    ([sinceRerouteS]), and nothing at all just after one. A closed road is gone around by
 *    the fastest variant that avoids it, whatever the gain.
 * A jam alone never moves the driver: only the time saved does. When every variant the engine
 * was asked failed for an outage of it (Valhalla down: [draw] says `outage`), the answer is
 * `reason: "fallback"` (D5.2).
 *
 * Returns { answer, compare }: [answer], the JSON of /api/route/faster; [compare], what the
 * shadow mode needs to draw the same variants with the other engine (shadow.js) — null when no
 * engine was asked. [time] and [pause] stand for TomTom and its pause in the tests.
 */
export async function checkFaster(points, { avoid = [], sinceRerouteS = null, draw, time = timeRoute, pause = sleep } = {}) {
  if (sinceRerouteS != null && sinceRerouteS < config.rerouteCooldownS) {
    return { answer: { better: null, reason: 'cooldown' }, compare: null };
  }

  const path = measure(points);
  const current = await time(points, path);
  const currentS = current.travelS;
  if (!(currentS > 0)) return { answer: { better: null, reason: 'no time' }, compare: null };
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
  const summary = { currentS, crowdS: current.crowdS, thresholdS, jams: jams.map(({ fromM, toM, delayS, closed: shut }) => ({ fromM, toM, delayS, closed: shut })) };
  if (!jams.length || (!closed && lostS < thresholdS)) {
    return { answer: { ...summary, better: null, reason: 'no significant jam' }, compare: null };
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
  const base = { from: { lat: start[0], lon: start[1] }, to: { lat: end[0], lon: end[1] }, bearings, avoid };
  const worst = jams.reduce((a, b) => (weight(b) > weight(a) ? b : a));
  const asks = [{ ...base, label: 'around', polygons: polygonsAround(path, jams), alternatives: false }];
  if (jams.length > 1) asks.push({ ...base, label: 'around worst', polygons: polygonsAround(path, [worst]), alternatives: false });
  asks.push({ ...base, label: 'alternatives', polygons: null, alternatives: true });

  // Compared over the window: the rest of the route is the same for all of them.
  const plan = {
    asks,
    closed,
    lostS,
    windowGrid: gridOf(window),
    windowSamples: samplesBetween(measure(window), 0, Infinity),
    jamSamples: jams.map((jam) => samplesBetween(path, jam.fromM, jam.toM)),
    closureSamples: jams.filter((jam) => jam.closed).map((jam) => samplesBetween(path, jam.fromM, jam.toM)),
  };
  const drawn = await drawVariants(plan, draw);
  if (drawn.outage) {
    console.warn(`[faster] ${drawn.engine} unavailable (${drawn.errors.join(', ')}) → fallback`);
    return { answer: { ...summary, better: null, reason: 'fallback' }, compare: null };
  }

  // Each variant timed whole (the same rest of the route after it), like the route now.
  let best = null;
  let timed = 0;
  for (const candidate of drawn.viable) {
    await pause(TOMTOM_GAP_MS);
    const whole = tail ? candidate.line.concat(tail.slice(1)) : candidate.line;
    const traffic = await time(whole).catch((e) => {
      console.warn('[faster] TomTom variant —', String(e.message || e));
      return null;
    });
    if (!(traffic?.travelS > 0)) continue;
    timed += 1;
    if (!best || traffic.travelS < best.travelS) best = { candidate, route: candidate.route, travelS: traffic.travelS };
  }

  const gainS = best ? currentS - best.travelS : 0;
  const switching = best != null && (closed || gainS >= thresholdS);
  console.log(
    `[faster] now ${currentS}s (drivers +${current.crowdS}s), ${jams.length} jam(s) ${lostS}s lost${closed ? ' + closed' : ''}, window ${Math.round(rejoinM / 1000)}/${Math.round(path.total / 1000)} km, ` +
      `${drawn.found.length} ${drawn.engine ?? 'engine'} route(s), ${timed} timed, best ${best ? best.travelS + 's' : '-'}, threshold ${thresholdS}s${sticky ? ' (sticky)' : ''} → ${switching ? 'switch' : 'keep'}`,
  );
  const result = { ...summary, variants: timed, bestS: best?.travelS ?? null };
  const compare = { plan, drawn, detour: switching ? best.candidate : null };
  if (!switching) return { answer: { ...result, better: null, reason: best ? 'not enough gain' : 'no variant' }, compare };
  const route = tail ? await withRest(best.route, tail, headingAt(path, rejoinM), avoid, draw) : best.route;
  if (!route) return { answer: { ...result, better: null, reason: 'rest of the route unavailable' }, compare };
  // The time shown for the new route is TomTom's, with traffic: the one the gain was measured on.
  return { answer: { ...result, better: { gainS: Math.max(0, gainS), closed, route: { ...route, durationS: best.travelS } } }, compare };
}

/**
 * The variants of [plan] drawn by [draw] (every ask at once), then sifted without TomTom: what
 * the route itself is (its engine time: baselineS), what still goes through the jams, the
 * duplicates. { engine, found, baselineS, candidates, viable, errors, outage, skipped, latencyMs };
 * [outage]: every ask failed for an outage of the engine; [skipped]: none was sent.
 */
export async function drawVariants(plan, draw) {
  const startedAt = Date.now();
  const answers = await Promise.all(plan.asks.map((ask) => draw(ask)));
  const found = answers.flatMap((answer) => answer.routes ?? []);
  return {
    engine: answers[0]?.engine ?? null,
    ...sift(plan, found),
    errors: answers.map((answer) => answer.error).filter(Boolean),
    outage: answers.length > 0 && answers.every((answer) => answer.outage),
    skipped: answers.length > 0 && answers.every((answer) => answer.skipped),
    latencyMs: Date.now() - startedAt,
  };
}

/**
 * [routes] (the app's shape) against the window of [plan]: the route itself gives the engine's
 * time for the window (baselineS); a variant through a closure, through every jam, or like one
 * already kept is dropped; one slower, without traffic, than the route by more than the jams
 * cost cannot win. The first config.rerouteMaxVariants left are `viable` (one TomTom request
 * each).
 */
function sift(plan, routes) {
  let baselineS = null;
  const candidates = [];
  for (const route of [...routes].sort((a, b) => a.durationS - b.durationS)) {
    const line = route.coordinates.map(([lon, lat]) => [lat, lon]);
    const grid = gridOf(line);
    const samples = samplesBetween(measure(line), 0, Infinity);
    const same = (other) => share(other.samples, grid) >= SAME_ROUTE_SHARE && share(samples, other.grid) >= SAME_ROUTE_SHARE;
    if (same({ samples: plan.windowSamples, grid: plan.windowGrid })) {
      baselineS = Math.min(baselineS ?? Infinity, route.durationS);
      continue;
    }
    if (plan.closureSamples.some((closure) => share(closure, grid) >= THROUGH_CLOSURE_SHARE)) continue;
    if (!plan.closed && plan.jamSamples.every((jam) => share(jam, grid) >= SAME_ROUTE_SHARE)) continue;
    if (candidates.some(same)) continue;
    candidates.push({ route, line, grid, samples });
  }
  const viable = candidates
    .filter((candidate) => plan.closed || baselineS == null || candidate.route.durationS <= baselineS + plan.lostS)
    .slice(0, config.rerouteMaxVariants);
  return { found: routes, baselineS, candidates, viable };
}

/**
 * The candidate of [candidates] closest to [detour] (a candidate: { line, grid, samples }) and
 * their common road: the smaller of the two shares, 0 to 1. { share: null } without a detour.
 */
export function closestTo(detour, candidates) {
  if (!detour) return { share: null, candidate: null };
  let best = { share: 0, candidate: null };
  for (const candidate of candidates) {
    const common = Math.min(share(detour.samples, candidate.grid), share(candidate.samples, detour.grid));
    if (common > best.share || !best.candidate) best = { share: common, candidate };
  }
  return best;
}

/**
 * Whether a faster-route check is worth asking from [aheadM] metres along the route (the
 * driver): jams starting within the horizon lose rerouteMinGainS or more together, or a road
 * is closed there. Only spares useless checks: checkFaster decides.
 */
export function worthChecking(sections, totalM, aheadM) {
  const jams = jamsOf(sections.filter((section) => section.toM > aheadM), totalM)
    .filter((jam) => jam.toM > aheadM + KEEP_OPEN_M && jam.fromM - aheadM <= HORIZON_M);
  return jams.some((jam) => jam.closed) || jams.reduce((sum, jam) => sum + jam.delayS, 0) >= config.rerouteMinGainS;
}

/**
 * A route's time now: TomTom's with traffic, plus what the drivers' jams add ([crowdS]), and
 * its slowdowns (TomTom's and the drivers'). Without the drivers' jams (database down),
 * TomTom's alone.
 */
async function timeRoute(points, path = measure(points)) {
  const traffic = await trafficAlong(points, { use: 'faster' });
  const crowd = await crowdAlong(path).catch((e) => {
    console.warn('[faster] drivers\' jams unavailable —', String(e.message || e));
    return [];
  });
  const sections = withCrowd(traffic.sections, crowd);
  const crowdS = crowdExtraS(sections);
  return { travelS: traffic.travelS > 0 ? traffic.travelS + crowdS : traffic.travelS, crowdS, sections };
}

/**
 * The winning detour, then the engine's route from the rejoin point to the destination (the
 * same road as the route's rest): one route to follow, with its steps, its engine and its map.
 */
async function withRest(detour, rest, heading, avoid, draw) {
  const [from, to] = [rest[0], rest[rest.length - 1]];
  const { routes: [route] = [] } = await draw({
    label: 'rest',
    from: { lat: from[0], lon: from[1] },
    to: { lat: to[0], lon: to[1] },
    bearings: [[heading, 45]],
    avoid,
    polygons: null,
    alternatives: false,
  });
  if (!route) return null;
  return {
    distanceM: detour.distanceM + route.distanceM,
    durationS: detour.durationS + route.durationS,
    coordinates: detour.coordinates.concat(route.coordinates.slice(1)),
    // One trip: no "arrive" at the rejoin point, no "depart" from it.
    steps: detour.steps.filter((step) => step.type !== 'arrive').concat(route.steps.filter((step) => step.type !== 'depart')),
    // The detour is what changes: its engine and map describe the answer (spec §1).
    engine: detour.engine,
    mapVersion: detour.mapVersion ?? route.mapVersion ?? null,
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

/** Squares along [jams] (the route's first and last metres left open), one MultiPolygon to avoid. */
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
