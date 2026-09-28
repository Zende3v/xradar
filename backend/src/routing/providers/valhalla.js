import { haversine } from '../../radars/geo.js';

/**
 * Valhalla, EONA's own routing engine (D6.1), as a provider for the routing façade: its /route
 * asked in OSRM format and read back as the app's routes — the shape ORS's already have
 * (ors.js, normalizeOrsFeature) —, and its /status for the map it routes on. Nothing here reads
 * config.js: the façade gives the settings, the tests a fake fetch.
 *
 * Checked against Valhalla 3.9.0 (docs and sources: tyr/route_serializer_osrm.cc, exceptions.cc,
 * loki/worker.cc):
 * - `exclude_tolls`, `exclude_highways` and `exclude_ferries` are dropped by a server without
 *   `service_limits.allow_hard_exclusions` (warning 208), and unreadable `exclude_polygons` are
 *   dropped too (warning 204): both are failures here, never a route that ignores an avoid;
 * - `heading`, `heading_tolerance` and `search_cutoff` are read as integers only: anything else
 *   is dropped without a word, so they are sent rounded;
 * - an error in OSRM format is one of a few fixed answers ({ code: "NoRoute", message }), never
 *   the request's coordinates.
 *
 * Every failure is a ValhallaError: a constant `code` (VALHALLA_ERROR), the HTTP `status` to
 * answer the app with, Valhalla's own HTTP status when it answered (`upstreamStatus`) and what
 * it said (`valhallaCode`: its OSRM error code, or the number of the warning). No message quotes
 * a coordinate, a value the caller gave, nor Valhalla's own text.
 */

/** What went wrong, whatever the cause: the façade decides on the code alone. */
export const VALHALLA_ERROR = Object.freeze({
  /** createValhallaClient got an unusable setting. */
  INVALID_CONFIG: 'invalid_config',
  /** The caller asked something this provider refuses: nothing was sent. */
  INVALID_REQUEST: 'invalid_request',
  /** The caller's signal stopped the call. */
  ABORTED: 'aborted',
  /** No complete answer, its body read and parsed, within timeoutMs. */
  TIMEOUT: 'timeout',
  /** Valhalla unreachable, shutting down or failing (HTTP 5xx). */
  UNAVAILABLE: 'unavailable',
  /** Valhalla refused the request (HTTP 4xx other than the cases below). */
  REJECTED: 'rejected',
  /** No road links the two points. */
  NO_ROUTE: 'no_route',
  /** A point has no road near it: none within searchCutoffM, or the route starts or ends past maxSnapM. */
  OUT_OF_COVERAGE: 'out_of_coverage',
  /** The avoided areas were refused (perimeter or size over the server's limits) or ignored. */
  POLYGONS_REJECTED: 'polygons_rejected',
  /** The server dropped the strict exclusions (service_limits.allow_hard_exclusions off). */
  EXCLUSIONS_IGNORED: 'exclusions_ignored',
  /** The answer is not what Valhalla sends: not JSON, a field missing or wrong, a shape cut short. */
  INVALID_RESPONSE: 'invalid_response',
});

/** The HTTP status each failure gives the app. */
const HTTP_STATUS = {
  invalid_config: 500,
  invalid_request: 400,
  aborted: 499, // nginx's "client closed request": nobody waits for this answer
  timeout: 504,
  unavailable: 503,
  rejected: 502,
  no_route: 404, // as "no route found" (engine.js)
  out_of_coverage: 404,
  polygons_rejected: 502,
  exclusions_ignored: 502,
  invalid_response: 502,
};

export class ValhallaError extends Error {
  /**
   * [code]: one of VALHALLA_ERROR; [facts]: { upstreamStatus?, valhallaCode?, point?, snapM? }
   * (point: 'from' | 'to', snapM: metres, for a point snapped too far).
   */
  constructor(code, message, facts = {}) {
    super(message);
    this.name = 'ValhallaError';
    this.code = code;
    this.status = HTTP_STATUS[code] ?? 502;
    this.upstreamStatus = facts.upstreamStatus ?? null;
    this.valhallaCode = facts.valhallaCode ?? null;
    if (facts.point) this.point = facts.point;
    if (facts.snapM != null) this.snapM = facts.snapM;
  }
}

/** France's car profile (D4.1): Valhalla's `auto`, its top speed that of French motorways. */
const TOP_SPEED_KMH = 130;

/** Alternatives asked at most: max_alternates on EONA's server (D4.5). */
export const MAX_ALTERNATES = 3;

/** The app's avoid options → Valhalla's strict exclusions (D4.2). */
const EXCLUDE = { tolls: 'exclude_tolls', highways: 'exclude_highways', ferries: 'exclude_ferries' };

/** Valhalla's warnings for a part of the request it dropped (exceptions.cc). */
const WARNING_POLYGONS_IGNORED = 204;
const WARNING_EXCLUSIONS_IGNORED = 208;

/** Valhalla's OSRM error codes that say more than "refused" (exceptions.cc). */
const OSRM_ERROR = {
  NoRoute: VALHALLA_ERROR.NO_ROUTE,
  NoSegment: VALHALLA_ERROR.OUT_OF_COVERAGE,
  PerimeterExceeded: VALHALLA_ERROR.POLYGONS_REJECTED,
  ServiceUnavailable: VALHALLA_ERROR.UNAVAILABLE,
};

/** How each refusal reads. */
const REFUSAL_TEXT = {
  no_route: 'no route between the points',
  out_of_coverage: 'a point has no road near it',
  polygons_rejected: 'avoided areas refused',
  unavailable: 'unavailable',
  rejected: 'request refused',
};

/** OSRM step types the apps read (GuidanceText.kt / GuidanceText.swift), kept as Valhalla gives them. */
const STEP_TYPES = new Set([
  'depart', 'arrive', 'turn', 'new name', 'continue', 'merge', 'on ramp', 'off ramp', 'fork',
  'end of road', 'roundabout', 'notification',
]);
/** A named roundabout (Valhalla's "rotary") is a roundabout, as ORS gives it. */
const STEP_RENAMED = { rotary: 'roundabout', 'roundabout turn': 'roundabout' };
/**
 * Leaving a roundabout belongs to the step that entered it, as with ORS. roundabout_exits: false
 * already leaves these steps out; this only catches one that would come anyway.
 */
const STEP_FOLDED = new Set(['exit roundabout', 'exit rotary']);
const MODIFIERS = new Set(['uturn', 'sharp right', 'right', 'slight right', 'straight', 'slight left', 'left', 'sharp left']);

/** A route's ends and its first and last steps are the same points of its shape (6 decimals). */
const SAME_POINT_M = 1;
/** setTimeout's limit: past it, Node fires at once. */
const MAX_TIMER_MS = 2_147_483_647;
/** tileset_last_modified past the year 9999 is not a date. */
const LAST_SECOND = 253_402_300_799;

/**
 * @typedef {{ lat: number, lon: number }} Point
 * @typedef {{
 *   type: string, modifier: string | null, location: [number, number], exit: number | null,
 *   name: string, exitNumber: string | null, towardRefs: string[], toward: string[],
 *   distanceM: number, durationS: number,
 * }} Step
 * @typedef {{
 *   distanceM: number, durationS: number, coordinates: [number, number][], steps: Step[],
 *   engine: 'valhalla', mapVersion: string | null,
 * }} Route
 */

/**
 * A client for the Valhalla at [baseUrl]. Every call ends within [timeoutMs], body included, or
 * as soon as the caller's signal fires. [searchCutoffM]: how far Valhalla looks for a road around
 * each point (D5.1: far under its 35 km, so a point abroad finds no French road); [maxSnapM]:
 * how far a route may start or end from the point asked, past which the point is out of the map
 * (out_of_coverage: the façade then asks ORS).
 *
 * The client keeps the map Valhalla routes on — its date, from the last status() — and nothing
 * else: routes carry it as `mapVersion` (null until a status() has read it). The façade calls
 * status() at start and then regularly, a new map being switched in every week (D6.3).
 */
export function createValhallaClient({ baseUrl, timeoutMs, searchCutoffM, maxSnapM, fetchImpl = globalThis.fetch } = {}) {
  const base = checkedBaseUrl(baseUrl);
  const deadlineMs = setting(timeoutMs, 'timeoutMs', MAX_TIMER_MS);
  const cutoffM = Math.round(setting(searchCutoffM, 'searchCutoffM'));
  if (cutoffM < 1) throw misconfigured('searchCutoffM must be 1 m or more');
  const snapLimitM = setting(maxSnapM, 'maxSnapM');
  if (typeof fetchImpl !== 'function') throw misconfigured('fetchImpl must be a function');

  /** The map Valhalla routes on, from its last status(): { mapVersion, version, tilesetLastModified, osmChangeset }. */
  let map = null;

  /**
   * One POST to Valhalla, its answer read and parsed within the deadline: the JSON object of a
   * 2xx answer; a refusal, a silence past the deadline or the caller's abort as a ValhallaError.
   */
  async function exchange(path, payload, signal) {
    if (signal?.aborted) throw new ValhallaError(VALHALLA_ERROR.ABORTED, 'Valhalla: request aborted');
    const startedAt = Date.now();
    const controller = new AbortController();
    let stop;
    const stopped = new Promise((_, reject) => { stop = reject; });
    stopped.catch(() => {}); // read by the races below; never an unhandled rejection
    const end = (error) => {
      controller.abort(error);
      stop(error);
    };
    const timer = setTimeout(() => end(timedOut(deadlineMs)), deadlineMs);
    const onAbort = () => end(new ValhallaError(VALHALLA_ERROR.ABORTED, 'Valhalla: request aborted'));
    signal?.addEventListener('abort', onAbort, { once: true });

    const send = async () => {
      try {
        return await fetchImpl(`${base}${path}`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          // In the body, not the URL: coordinates never reach an access log.
          body: JSON.stringify(payload),
          signal: controller.signal,
        });
      } catch (e) {
        if (controller.signal.aborted) throw controller.signal.reason;
        throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, `Valhalla: unreachable${systemCode(e)}`);
      }
    };
    // Read by a race too: a fetch that ignores its signal is still cut at the deadline.
    const read = async (response) => {
      if (!response || typeof response.text !== 'function' || !Number.isInteger(response.status)) {
        throw badAnswer('answer unreadable');
      }
      try {
        return await response.text();
      } catch (e) {
        if (controller.signal.aborted) throw controller.signal.reason;
        throw new ValhallaError(VALHALLA_ERROR.UNAVAILABLE, 'Valhalla: answer cut off', { upstreamStatus: response.status });
      }
    };

    try {
      const response = await Promise.race([send(), stopped]);
      const text = await Promise.race([read(response), stopped]);
      const json = answerJson(response.status, text);
      // Parsing a long answer happens after the last race: the deadline holds for it too.
      if (Date.now() - startedAt > deadlineMs) throw timedOut(deadlineMs);
      return json;
    } finally {
      clearTimeout(timer);
      signal?.removeEventListener('abort', onAbort);
    }
  }

  /**
   * The routes from [from] to [to] ({ lat, lon }), best first, then up to [alternates]
   * alternatives. [avoid]: 'tolls', 'highways', 'ferries' (strict); [polygons]: areas to keep
   * off, a GeoJSON MultiPolygon as ORS's avoid_polygons, never with alternates (D4.3);
   * [bearings]: [[heading, tolerance], …] in degrees, for the start then the destination (null to
   * leave one free). Throws a ValhallaError.
   * @returns {Promise<Route[]>}
   */
  async function routes(from, to, options = {}) {
    const { signal, ...asked } = checkedOptions(options);
    const body = routeRequest(from, to, asked, cutoffM);
    checkedSignal(signal);
    const json = await exchange('/route', body, signal);
    return readRoutes(json, {
      from: body.locations[0],
      to: body.locations[1],
      polygons: body.exclude_polygons != null,
      maxSnapM: snapLimitM,
      mapVersion: map?.mapVersion ?? null,
    });
  }

  /**
   * Valhalla's detailed /status, checked: { version, tileset_last_modified, has_tiles,
   * has_admins, has_timezones, has_live_traffic, has_transit_tiles, osm_changeset,
   * available_actions, bbox, warnings } — what a server without
   * service_limits.status.allow_verbose leaves out is null. `bbox` is the GeoJSON of the tiles'
   * extent, not France's border: nothing here decides from it. Keeps the map's date for the
   * routes; a server without tiles clears it. Throws a ValhallaError.
   */
  async function status(options = {}) {
    const { signal } = checkedOptions(options);
    checkedSignal(signal);
    const json = await exchange('/status', { verbose: true }, signal);
    const payload = readStatus(json);
    map = mapOf(payload);
    return payload;
  }

  return { routes, status };
}

/**
 * A polyline6 shape (Valhalla's, 6 decimals) as [[lon, lat], …]. A shape cut short — a number
 * left unfinished, a latitude without its longitude —, unreadable, off the globe or of a single
 * point throws (invalid_response).
 */
export function decodePolyline6(encoded) {
  if (typeof encoded !== 'string' || !encoded) throw badAnswer('route shape missing');
  let index = 0;
  const next = () => {
    let result = 0;
    for (let shift = 0; ; shift += 5) {
      // 7 chunks at most: already far past 180° at 6 decimals.
      if (shift > 30) throw badAnswer('route shape unreadable');
      if (index >= encoded.length) throw badAnswer('route shape cut short');
      const chunk = encoded.charCodeAt(index++) - 63;
      if (chunk < 0 || chunk > 63) throw badAnswer('route shape unreadable');
      result += (chunk & 0x1f) * 2 ** shift;
      if (chunk < 0x20) break;
    }
    return result % 2 ? -(result + 1) / 2 : result / 2;
  };
  const points = [];
  let lat = 0;
  let lon = 0;
  while (index < encoded.length) {
    lat += next();
    lon += next();
    const point = [lon / 1e6, lat / 1e6];
    if (Math.abs(point[0]) > 180 || Math.abs(point[1]) > 90) throw badAnswer('route shape off the globe');
    points.push(point);
  }
  if (points.length < 2) throw badAnswer('route shape too short');
  return points;
}

// ---- Request ----------------------------------------------------------------

/** Valhalla's /route request for [from] → [to] with the caller's options, checked. */
function routeRequest(from, to, { avoid = [], polygons = null, bearings = null, alternates = 0 }, searchCutoffM) {
  const start = checkedPoint(from, 'from');
  const end = checkedPoint(to, 'to');
  const [startHeading, endHeading] = checkedBearings(bearings);
  const exclusions = checkedAvoid(avoid);
  const rings = excludePolygons(polygons);
  const count = alternates ?? 0;
  if (!Number.isInteger(count) || count < 0 || count > MAX_ALTERNATES) {
    throw refused(`alternates must be a whole number from 0 to ${MAX_ALTERNATES}`);
  }
  if (rings && count > 0) throw refused('polygons and alternates are asked separately');
  const body = {
    locations: [location(start, startHeading, searchCutoffM), location(end, endHeading, searchCutoffM)],
    costing: 'auto',
    costing_options: { auto: { top_speed: TOP_SPEED_KMH, ...exclusions } },
    format: 'osrm',
    shape_format: 'polyline6',
    roundabout_exits: false,
  };
  if (rings) body.exclude_polygons = rings;
  if (count > 0) body.alternates = count;
  return body;
}

/**
 * With a course, the roads this close all compete: the one running the way the car points wins.
 * With radius 0 (Valhalla's default) only the nearest road is a candidate, and a course it does
 * not match is ignored: on an avenue with a central reservation the route then starts the wrong
 * way and turns back (bench, 27/09/2026: no U-turn left from 30 m, 50 m for margin).
 */
const HEADING_RADIUS_M = 50;

function location(point, heading, searchCutoffM) {
  return {
    lat: point.lat,
    lon: point.lon,
    search_cutoff: searchCutoffM,
    ...(heading ? { ...heading, radius: HEADING_RADIUS_M } : {}),
  };
}

function checkedOptions(options) {
  const value = options ?? {};
  if (typeof value !== 'object' || Array.isArray(value)) throw refused('options must be an object');
  return value;
}

function checkedSignal(signal) {
  if (signal == null) return;
  if (typeof signal.aborted !== 'boolean' || typeof signal.addEventListener !== 'function') {
    throw refused('signal must be an AbortSignal');
  }
}

function checkedPoint(value, name) {
  const lat = value?.lat;
  const lon = value?.lon;
  if (!isNumber(lat) || !isNumber(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
    throw refused(`${name} must be { lat, lon } in degrees`);
  }
  return { lat, lon };
}

/** [[heading, tolerance] | null, …] → Valhalla's per-point fields, in whole degrees (it drops the rest). */
function checkedBearings(bearings) {
  if (bearings == null) return [null, null];
  if (!Array.isArray(bearings) || bearings.length > 2) {
    throw refused('bearings must be [[heading, tolerance]] for the start, then the destination');
  }
  return [bearings[0], bearings[1]].map((bearing) => {
    if (bearing == null) return null;
    const [heading, tolerance] = Array.isArray(bearing) && bearing.length === 2 ? bearing : [];
    if (!isNumber(heading) || heading < 0 || heading > 360 || !isNumber(tolerance) || tolerance < 0 || tolerance > 180) {
      throw refused('a bearing is [heading 0 to 360°, tolerance 0 to 180°]');
    }
    return { heading: Math.round(heading) % 360, heading_tolerance: Math.round(tolerance) };
  });
}

/** The app's avoid options as Valhalla's strict exclusions; anything else is refused, never dropped. */
function checkedAvoid(avoid) {
  if (avoid == null) return {};
  if (!Array.isArray(avoid)) throw refused('avoid must be a list');
  const out = {};
  for (const option of avoid) {
    if (typeof option !== 'string' || !Object.hasOwn(EXCLUDE, option)) {
      throw refused('avoid takes tolls, highways and ferries only');
    }
    out[EXCLUDE[option]] = true;
  }
  return out;
}

/**
 * An ORS avoid_polygons MultiPolygon (or Polygon) as Valhalla's exclude_polygons: one closed
 * outer ring of [lon, lat] per polygon. Valhalla knows no holes and drops rings it cannot read
 * (warning 204): anything it would not take whole is refused here.
 */
function excludePolygons(polygons) {
  if (polygons == null) return null;
  const { type, coordinates } = typeof polygons === 'object' ? polygons : {};
  const list = type === 'MultiPolygon' ? coordinates : type === 'Polygon' ? [coordinates] : null;
  if (!Array.isArray(list) || !list.length) throw refused('polygons must be a GeoJSON MultiPolygon');
  return list.map((polygon) => {
    if (!Array.isArray(polygon) || polygon.length !== 1) {
      throw refused('polygons take one outer ring each, without holes');
    }
    return closedRing(polygon[0]);
  });
}

function closedRing(ring) {
  if (!Array.isArray(ring)) throw refused('polygons take rings of [lon, lat]');
  const corners = ring.map((position) => {
    const point = lonLat(position);
    if (!point) throw refused('polygons take rings of [lon, lat]');
    return point;
  });
  if (corners.length > 1 && samePosition(corners[0], corners[corners.length - 1])) corners.pop();
  if (corners.length < 3) throw refused('a polygon ring needs 3 corners or more');
  return [...corners, corners[0]];
}

// ---- Answers ----------------------------------------------------------------

/** The JSON object of a 2xx answer; anything else as a ValhallaError. */
function answerJson(status, text) {
  if (status < 200 || status > 299) throw refusal(status, text);
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    // JSON.parse quotes the text it choked on: never passed on.
    throw badAnswer('answer is not JSON');
  }
  if (json === null || typeof json !== 'object' || Array.isArray(json)) throw badAnswer('answer is not a JSON object');
  return json;
}

/** A refusal, by Valhalla's OSRM error code when there is one, never by its text. */
function refusal(status, text) {
  let code = null;
  try {
    const json = JSON.parse(text);
    if (typeof json?.code === 'string' && /^[A-Za-z]{1,40}$/.test(json.code)) code = json.code;
  } catch {
    // not JSON: a proxy or a crash page
  }
  const kind = code && Object.hasOwn(OSRM_ERROR, code)
    ? OSRM_ERROR[code]
    : status >= 500 ? VALHALLA_ERROR.UNAVAILABLE : VALHALLA_ERROR.REJECTED;
  return new ValhallaError(kind, `Valhalla ${status}: ${REFUSAL_TEXT[kind]}${code ? ` (${code})` : ''}`, {
    upstreamStatus: status,
    valhallaCode: code,
  });
}

/**
 * The routes of an OSRM-format answer in the app's shape, after the checks: nothing asked was
 * dropped (warnings 208, 204), both points snapped within [maxSnapM], every route whole.
 */
function readRoutes(json, { from, to, polygons, maxSnapM, mapVersion }) {
  if (json.code !== 'Ok') throw badAnswer('route answer not Ok');
  const warnings = Array.isArray(json.warnings) ? json.warnings.map((w) => Number(w?.code)) : [];
  if (warnings.includes(WARNING_EXCLUSIONS_IGNORED)) {
    throw new ValhallaError(VALHALLA_ERROR.EXCLUSIONS_IGNORED, 'Valhalla: strict exclusions ignored by the server', {
      valhallaCode: WARNING_EXCLUSIONS_IGNORED,
    });
  }
  if (polygons && warnings.includes(WARNING_POLYGONS_IGNORED)) {
    throw new ValhallaError(VALHALLA_ERROR.POLYGONS_REJECTED, 'Valhalla: avoided areas ignored', {
      valhallaCode: WARNING_POLYGONS_IGNORED,
    });
  }
  checkWaypoints(json.waypoints, from, to, maxSnapM);
  if (!Array.isArray(json.routes) || !json.routes.length) throw badAnswer('no route in the answer');
  const routes = json.routes.map((route) => normalizeRoute(route, mapVersion));
  // The shape's own ends: where the driver really starts and arrives.
  for (const route of routes) {
    checkSnap('from', from, route.coordinates[0], maxSnapM);
    checkSnap('to', to, route.coordinates[route.coordinates.length - 1], maxSnapM);
  }
  return routes;
}

/** Where Valhalla snapped both points: its own distance and ours, each within [maxSnapM]. */
function checkWaypoints(waypoints, from, to, maxSnapM) {
  if (!Array.isArray(waypoints) || waypoints.length !== 2) throw badAnswer('waypoints missing');
  waypoints.forEach((waypoint, i) => {
    const at = lonLat(waypoint?.location);
    if (!at) throw badAnswer('waypoint location unreadable');
    const reported = waypoint.distance;
    if (reported != null && !(isNumber(reported) && reported >= 0)) throw badAnswer('waypoint distance unreadable');
    checkSnap(i ? 'to' : 'from', i ? to : from, at, maxSnapM, reported ?? 0);
  });
}

/** [asked] and where it snapped ([at], [lon, lat]) within [maxSnapM]; out_of_coverage otherwise. */
function checkSnap(name, asked, at, maxSnapM, reportedM = 0) {
  const snapM = Math.max(haversine(asked.lat, asked.lon, at[1], at[0]), reportedM);
  if (snapM <= maxSnapM) return;
  const which = name === 'from' ? 'start' : 'destination';
  throw new ValhallaError(
    VALHALLA_ERROR.OUT_OF_COVERAGE,
    `Valhalla: the ${which} snapped ${Math.round(snapM)} m away, over the ${Math.round(maxSnapM)} m allowed`,
    { point: name, snapM: Math.round(snapM) },
  );
}

/** One OSRM-format route as the app's route. */
function normalizeRoute(route, mapVersion) {
  if (route === null || typeof route !== 'object') throw badAnswer('route unreadable');
  const distance = amount(route.distance, 'route distance');
  const duration = amount(route.duration, 'route duration');
  const coordinates = decodePolyline6(route.geometry);
  // Two points asked, one leg.
  if (!Array.isArray(route.legs) || route.legs.length !== 1) throw badAnswer('route legs unreadable');
  const steps = normalizeSteps(route.legs[0]?.steps);
  // Every step starts on a point of the shape, the first on its first, the last on its last:
  // a shape that stops elsewhere was cut short.
  const [first, last] = [steps[0], steps[steps.length - 1]];
  if (first.type !== 'depart' || last.type !== 'arrive') throw badAnswer('route steps unreadable');
  if (!samePoint(coordinates[0], first.location) || !samePoint(coordinates[coordinates.length - 1], last.location)) {
    throw badAnswer('route shape cut short');
  }
  return {
    distanceM: Math.round(distance),
    durationS: Math.round(duration),
    coordinates,
    steps,
    engine: 'valhalla',
    mapVersion,
  };
}

/**
 * OSRM-format steps as the app's steps: { type, modifier, location: [lon, lat], exit, name,
 * distanceM, durationS }, as ORS's are. The street's name, else its number (`ref`); the exit
 * number on a roundabout only.
 */
function normalizeSteps(list) {
  if (!Array.isArray(list) || list.length < 2) throw badAnswer('route steps missing');
  const out = [];
  for (const step of list) {
    const maneuver = step?.maneuver;
    if (maneuver === null || typeof maneuver !== 'object' || typeof maneuver.type !== 'string') {
      throw badAnswer('step unreadable');
    }
    const location = lonLat(maneuver.location);
    if (!location) throw badAnswer('step location unreadable');
    const distance = amount(step.distance, 'step distance');
    const duration = amount(step.duration, 'step duration');
    if (STEP_FOLDED.has(maneuver.type) && out.length) {
      out[out.length - 1].distance += distance;
      out[out.length - 1].duration += duration;
      continue;
    }
    const type = STEP_TYPES.has(maneuver.type)
      ? maneuver.type
      : Object.hasOwn(STEP_RENAMED, maneuver.type) ? STEP_RENAMED[maneuver.type] : 'continue';
    const modifier = optional(maneuver.modifier, 'string', 'step modifier');
    const exit = maneuver.exit ?? null;
    if (exit !== null && !(Number.isInteger(exit) && exit > 0)) throw badAnswer('step exit unreadable');
    const name = (optional(step.name, 'string', 'step name') ?? '').trim();
    const ref = (optional(step.ref, 'string', 'step ref') ?? '').trim();
    out.push({
      type,
      modifier: MODIFIERS.has(modifier) ? modifier : null,
      location,
      exit: type === 'roundabout' ? exit : null,
      name: name || ref,
      ...signpost(step),
      distance,
      duration,
    });
  }
  return out.map(({ distance, duration, ...step }) => ({
    ...step,
    distanceM: Math.round(distance),
    durationS: Math.round(duration),
  }));
}

/** A road number as signs show it ("A 6", "N 104", "E 15", "D 2"): the start of a direction. */
const ROAD_REF = /^[A-Z]{1,3} ?\d+[A-Za-z]?$/;
/** Signpost names kept for a step: the banner and the voice use the first ones only. */
const TOWARD_MAX = 3;

/**
 * What the motorway signs say at a step (additive, 28/09): the exit number (`exitNumber`, "8",
 * "12a"), the roads (`towardRefs`, ["N 104", "A 4"]) and the places (`toward`, ["Sénart"]) the
 * branch leads to. Valhalla writes its destinations "N 104, A 4: Sénart, Corbeil-Essonnes".
 * Nothing when the signs say nothing: ORS never gives them.
 */
function signpost(step) {
  const exits = (optional(step.exits, 'string', 'step exits') ?? '').split(';')[0].trim();
  const destinations = (optional(step.destinations, 'string', 'step destinations') ?? '').trim();
  const colon = destinations.indexOf(': ');
  const listed = (text) => text.split(',').map((part) => part.trim()).filter(Boolean);
  let refs = colon >= 0 ? listed(destinations.slice(0, colon)) : [];
  let places = listed(colon >= 0 ? destinations.slice(colon + 2) : destinations);
  if (colon < 0) {
    refs = places.filter((part) => ROAD_REF.test(part));
    places = places.filter((part) => !ROAD_REF.test(part));
  }
  return {
    exitNumber: exits && exits.length <= 8 ? exits : null,
    towardRefs: [...new Set(refs)].slice(0, TOWARD_MAX),
    toward: [...new Set(places)].slice(0, TOWARD_MAX),
  };
}

/** /status checked, as Valhalla gives it; what a non-verbose server leaves out is null. */
function readStatus(json) {
  const { version, tileset_last_modified: modified } = json;
  if (typeof version !== 'string' || !/^[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}$/.test(version)) {
    throw badAnswer('status version unreadable');
  }
  if (!Number.isSafeInteger(modified) || modified < 0 || modified > LAST_SECOND) {
    throw badAnswer('status tileset_last_modified unreadable');
  }
  const payload = { version, tileset_last_modified: modified };
  for (const flag of ['has_tiles', 'has_admins', 'has_timezones', 'has_live_traffic', 'has_transit_tiles']) {
    payload[flag] = optional(json[flag], 'boolean', `status ${flag}`);
  }
  const changeset = json.osm_changeset ?? null;
  if (changeset !== null && !(Number.isSafeInteger(changeset) && changeset >= 0)) {
    throw badAnswer('status osm_changeset unreadable');
  }
  payload.osm_changeset = changeset;
  const actions = json.available_actions ?? null;
  if (actions !== null && !(Array.isArray(actions) && actions.every((a) => typeof a === 'string'))) {
    throw badAnswer('status available_actions unreadable');
  }
  payload.available_actions = actions && [...actions];
  const bbox = json.bbox ?? null;
  if (bbox !== null && (typeof bbox !== 'object' || Array.isArray(bbox) || typeof bbox.type !== 'string')) {
    throw badAnswer('status bbox unreadable');
  }
  payload.bbox = bbox;
  const warnings = json.warnings ?? null;
  if (warnings !== null && !Array.isArray(warnings)) throw badAnswer('status warnings unreadable');
  payload.warnings = warnings;
  return payload;
}

/**
 * What the client keeps of a status: the map's date (`mapVersion`, from tileset_last_modified,
 * ISO to the second), Valhalla's version and the OSM changeset. Null when no map is loaded.
 */
function mapOf(payload) {
  if (!(payload.tileset_last_modified > 0) || payload.has_tiles === false) return null;
  return {
    mapVersion: new Date(payload.tileset_last_modified * 1000).toISOString().replace('.000Z', 'Z'),
    version: payload.version,
    tilesetLastModified: payload.tileset_last_modified,
    osmChangeset: payload.osm_changeset,
  };
}

// ---- Helpers ----------------------------------------------------------------

const isNumber = (value) => typeof value === 'number' && Number.isFinite(value);

/** [lon, lat] from a position, or null when it is not a point of the globe. */
function lonLat(value) {
  if (!Array.isArray(value) || value.length < 2) return null;
  const [lon, lat] = value;
  return isNumber(lon) && isNumber(lat) && Math.abs(lon) <= 180 && Math.abs(lat) <= 90 ? [lon, lat] : null;
}

const samePosition = (a, b) => a[0] === b[0] && a[1] === b[1];

const samePoint = (a, b) => haversine(a[1], a[0], b[1], b[0]) <= SAME_POINT_M;

/** A distance or a duration: a number, 0 or more. */
function amount(value, what) {
  if (!isNumber(value) || value < 0) throw badAnswer(`${what} unreadable`);
  return value;
}

/** An optional field of the answer: null when absent, else of [type]. */
function optional(value, type, what) {
  if (value == null) return null;
  if (typeof value !== type) throw badAnswer(`${what} unreadable`);
  return value;
}

/** A system error's code (ECONNREFUSED…), the only part of a network failure worth passing on. */
function systemCode(e) {
  const code = e?.cause?.code ?? e?.code;
  return typeof code === 'string' && /^[A-Z][A-Z0-9_]{1,39}$/.test(code) ? ` (${code})` : '';
}

function checkedBaseUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw misconfigured('baseUrl must be an http(s) URL');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw misconfigured('baseUrl must be an http(s) URL');
  return url.href.replace(/\/+$/, '');
}

function setting(value, name, max = Number.MAX_SAFE_INTEGER) {
  if (!isNumber(value) || value <= 0 || value > max) throw misconfigured(`${name} must be a positive number`);
  return value;
}

const misconfigured = (message) => new ValhallaError(VALHALLA_ERROR.INVALID_CONFIG, `Valhalla client: ${message}`);
const refused = (message) => new ValhallaError(VALHALLA_ERROR.INVALID_REQUEST, `Valhalla: ${message}`);
const badAnswer = (message) => new ValhallaError(VALHALLA_ERROR.INVALID_RESPONSE, `Valhalla: ${message}`);
const timedOut = (ms) => new ValhallaError(VALHALLA_ERROR.TIMEOUT, `Valhalla: no complete answer within ${ms} ms`);
