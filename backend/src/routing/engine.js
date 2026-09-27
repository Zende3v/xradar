import { ROUTING_ENGINES, settingsStore } from '../accounts/settings.js';
import { config } from '../config.js';
import { haversine } from '../radars/geo.js';
import { reportStore } from '../reports/store.js';
import { createRoutingAlert } from './alert.js';
import {
  normalizeOrsFeature, orsBody, orsBudgetLeft, orsKeysMeta, orsMapVersion, orsUsage, postORS, square,
} from './providers/ors.js';
import { MAX_ALTERNATES, VALHALLA_ERROR, createValhallaClient } from './providers/valhalla.js';

/**
 * The routing façade behind /api/route, /api/route/faster and the bench (D5.1, D7.2): which
 * engine serves a request, Valhalla then ORS in fallback, within one deadline.
 *
 * - routingEngine (settingsStore, D7.2): `ors` serves everyone with ORS, Valhalla in shadow;
 *   `admins` serves admin accounts with Valhalla, ORS in shadow for them; `all` serves everyone
 *   with Valhalla. Without Valhalla (config.valhallaEnabled off, or unusable settings), ORS
 *   serves every route whatever the setting, and nothing ever calls Valhalla.
 * - A route Valhalla should serve goes to ORS (a fallback, counted with its cause) when
 *   Valhalla fails (timeout, unreachable, refusal, a point out of its map: out_of_coverage), when
 *   its breaker is open (valhallaBreakerFailures outages in a row), when its last /status failed
 *   or when it has no tiles. The retry without the drivers' jams (D4.3) happens on Valhalla
 *   first. Valhalla's tries share valhallaTimeoutMs; ORS gets what is left of routeDeadlineMs,
 *   under the apps' 15 s. Both failing: 502 or 503, as ORS alone before (D5.3). No OSRM.
 * - Valhalla's /status (its map, has_live_traffic) is read every valhallaStatusEveryMs by a
 *   timer, never during a request; an outage lasting valhallaAlertAfterMs sends one mail (D6.4).
 * - Out of Valhalla's map: decided by the snap of each point (providers/valhalla.js:
 *   searchCutoffM, maxSnapM), never by a bounding box — France's box holds parts of Belgium,
 *   Switzerland, Italy and Spain, and the tiles' bbox is not a border either.
 *
 * A route comes back in the app's shape ({ distanceM, durationS, coordinates, steps, engine,
 * mapVersion }), inside an outcome saying who served it and what each engine did (for the
 * shadow mode, shadow.js); nothing here counts a trip nor spends an account's routes.
 */

export const ROUTING_MODES = ROUTING_ENGINES;

/** The app's avoid options Valhalla takes as strict exclusions (D4.2); "traffic" is the jams. */
const EXCLUSIONS = new Set(['tolls', 'highways', 'ferries']);
/** Valhalla failures that say Valhalla itself is unwell: they count for the breaker. */
const OUTAGES = new Set([VALHALLA_ERROR.TIMEOUT, VALHALLA_ERROR.UNAVAILABLE, VALHALLA_ERROR.INVALID_RESPONSE]);
/** A route impossible around the jams, or the jams refused: Valhalla tries again without them (D4.3). */
const WITHOUT_JAMS = new Set([VALHALLA_ERROR.NO_ROUTE, VALHALLA_ERROR.POLYGONS_REJECTED]);
const KNOWN_ERRORS = new Set(Object.values(VALHALLA_ERROR));
/** How far off the driver's course the start may leave (D4.4, same as /faster). */
const HEADING_TOLERANCE_DEG = 45;

/** The driver's course as bearings for the start only ([[heading, tolerance]]); null without one. */
function headingBearings(heading) {
  if (!Number.isFinite(heading)) return null;
  return [[((Math.round(heading) % 360) + 360) % 360, HEADING_TOLERANCE_DEG]];
}

/** The façade's own deadline, past which an answer is dropped. */
class DeadlineError extends Error {
  constructor() {
    super('routing deadline reached');
    this.name = 'DeadlineError';
    this.code = 'deadline';
  }
}

/**
 * [work] given a signal that fires in [ms], settled within [ms] at the latest: a late answer is
 * dropped, whatever the work does with its signal. Nothing is started without time left.
 */
async function within(ms, work) {
  if (!(ms >= 1)) throw new DeadlineError();
  const controller = new AbortController();
  let timer;
  const late = new Promise((_, reject) => {
    timer = setTimeout(() => {
      const error = new DeadlineError();
      controller.abort(error);
      reject(error);
    }, Math.floor(ms));
  });
  try {
    return await Promise.race([work(controller.signal), late]);
  } finally {
    clearTimeout(timer);
  }
}

const iso = (ms) => (ms == null ? null : new Date(ms).toISOString());

/**
 * A routing façade. [ors]: { configured(), post(body, { signal }), budgetLeft() };
 * [valhalla]: a client (providers/valhalla.js: routes, status) or null when Valhalla is off
 * ([valhallaProblem] says why); [mode]: routingEngine now; [jams]: the drivers' jams between two
 * points (a MultiPolygon or null); [alert]: { outage, recovered } or null; [now]: the clock of
 * the breaker, the outage and the drivers' memory; [settings]: the values of config.js by
 * default.
 */
export function createRoutingEngine({
  ors,
  valhalla = null,
  valhallaProblem = null,
  mode = () => 'ors',
  jams = async () => null,
  alert = null,
  now = Date.now,
  settings = {},
} = {}) {
  const s = {
    deadlineMs: config.routeDeadlineMs,
    valhallaTimeoutMs: config.valhallaTimeoutMs,
    jamsTimeoutMs: config.routeJamsTimeoutMs,
    breakerFailures: config.valhallaBreakerFailures,
    breakerOpenMs: config.valhallaBreakerOpenMs,
    statusEveryMs: config.valhallaStatusEveryMs,
    alertAfterMs: config.valhallaAlertAfterMs,
    fallbackMemoryMs: config.fasterFallbackMemoryMs,
    accountsMax: config.routeAccountsMax,
    shadowOrsMinBudgetLeft: config.shadowOrsMinBudgetLeft,
    ...settings,
  };

  /** Valhalla failures in a row (outages only), and until when everything goes to ORS. */
  const breaker = { failures: 0, openUntil: 0 };
  /** What the last /status said, and whether it failed. */
  const status = {
    at: null, error: null, down: false, version: null, mapVersion: null, tilesetLastModified: null,
    hasLiveTraffic: null, hasTiles: null, checking: false,
  };
  let lastError = null;
  /** Routes Valhalla should have served and ORS served (or tried to), since the start. */
  const fallbacks = { since: iso(now()), total: 0, byCause: {}, last: null };
  const warned = new Map();
  /** Valhalla unusable since, and whether the mail left. */
  const outage = { since: null, cause: null, alerted: false };
  /** The last route each driver got: served by ORS for a failing Valhalla, or not. */
  const served = new Map();
  let timer = null;

  function currentMode() {
    try {
      const value = mode();
      return ROUTING_MODES.includes(value) ? value : 'ors';
    } catch {
      return 'ors';
    }
  }

  /**
   * Who serves [account] now ({ role } or null) and who computes in shadow: { mode, primary,
   * shadow } — shadow null when Valhalla is off.
   */
  function plan(account) {
    const m = currentMode();
    if (!valhalla) return { mode: m, primary: 'ors', shadow: null };
    const onValhalla = m === 'all' || (m === 'admins' && account?.role === 'admin');
    return onValhalla ? { mode: m, primary: 'valhalla', shadow: 'ors' } : { mode: m, primary: 'ors', shadow: 'valhalla' };
  }

  /** Why Valhalla cannot be asked now; null when it can. */
  function valhallaBlocked() {
    if (!valhalla) return 'disabled';
    if (status.down) return 'status_down';
    if (status.hasTiles === false) return 'no_tiles';
    if (breaker.failures >= s.breakerFailures && now() < breaker.openUntil) return 'breaker_open';
    return null;
  }

  /** Why Valhalla is not serving normally (the breaker tripped, even half open); null when it is. */
  function valhallaDown() {
    if (!valhalla) return null;
    if (status.down) return 'status_down';
    if (status.hasTiles === false) return 'no_tiles';
    if (breaker.failures >= s.breakerFailures) return 'breaker_open';
    return null;
  }

  /** One Valhalla call: its value, or its error code; the breaker learns from both. */
  async function askValhalla(work) {
    try {
      const value = await work();
      breaker.failures = 0;
      return { ok: true, value };
    } catch (e) {
      const error = e instanceof DeadlineError || e?.code === VALHALLA_ERROR.ABORTED
        ? VALHALLA_ERROR.TIMEOUT
        : KNOWN_ERRORS.has(e?.code) ? e.code : VALHALLA_ERROR.UNAVAILABLE;
      if (!KNOWN_ERRORS.has(e?.code) && !(e instanceof DeadlineError)) {
        console.warn('[routing] Valhalla call failed —', String(e?.message || e));
      }
      if (OUTAGES.has(error)) {
        breaker.failures += 1;
        if (breaker.failures >= s.breakerFailures) breaker.openUntil = now() + s.breakerOpenMs;
      } else {
        breaker.failures = 0; // it answered: alive
      }
      lastError = { code: error, at: now() };
      return { ok: false, error };
    }
  }

  /**
   * Valhalla's route within [ms], the jams avoided first then not ([polygons], D4.3):
   * { ok, route, latencyMs } or { ok: false, error, latencyMs, skipped? }.
   */
  async function valhallaRoute(from, to, exclusions, polygons, ms, bearings = null) {
    const blocked = valhallaBlocked();
    if (blocked) return { ok: false, error: blocked, skipped: true, latencyMs: null };
    // No time left for it (the jams took it): not asked, and not Valhalla's fault.
    if (!(ms >= 1)) return { ok: false, error: VALHALLA_ERROR.TIMEOUT, skipped: true, latencyMs: null };
    const startedAt = Date.now();
    const until = startedAt + ms;
    const ask = (areas) => askValhalla(() => within(until - Date.now(), (signal) => valhalla.routes(from, to, {
      avoid: exclusions,
      polygons: areas,
      bearings,
      signal,
    })));
    let result = await ask(polygons);
    if (!result.ok && polygons && WITHOUT_JAMS.has(result.error) && until - Date.now() >= 1) result = await ask(null);
    const latencyMs = Date.now() - startedAt;
    return result.ok ? { ok: true, route: result.value[0], latencyMs } : { ok: false, error: result.error, latencyMs };
  }

  /**
   * ORS's route within [ms], the jams avoided first then not, as before phase 2:
   * { ok, route, latencyMs } or { ok: false, error, status, message, detail, thrown, latencyMs }.
   */
  async function orsRoute(from, to, avoid, polygons, ms, bearings = null) {
    if (!ors.configured()) {
      return { ok: false, error: 'not_configured', skipped: true, status: 503, message: 'routing unavailable', latencyMs: null };
    }
    const startedAt = Date.now();
    try {
      const out = await within(ms, async (signal) => {
        const body = orsBody({ from, to, avoid, bearings });
        let r = await ors.post(polygons ? orsBody({ from, to, avoid, polygons, bearings }) : body, { signal });
        // A route squeezed out by the reported jams can be impossible: the trip matters more.
        if (!r.ok && polygons) r = await ors.post(body, { signal });
        if (r.budgetSpent) return { ok: false, error: 'budget', status: 503, message: 'routing budget reached' };
        if (!r.ok) {
          const detail = await r.text().catch(() => '');
          return { ok: false, error: `http_${r.status}`, status: 502, message: `ORS ${r.status}`, detail: detail.slice(0, 200) };
        }
        const json = await r.json();
        const feature = json.features && json.features[0];
        if (!feature) return { ok: false, error: 'no_route', status: 404, message: 'no route found' };
        return { ok: true, route: normalizeOrsFeature(feature, orsMapVersion(json)) };
      });
      return { ...out, latencyMs: Date.now() - startedAt };
    } catch (e) {
      const late = e instanceof DeadlineError || e?.name === 'TimeoutError';
      return {
        ok: false,
        error: late ? 'timeout' : 'unavailable',
        status: 502,
        message: 'routing unavailable',
        detail: String(e?.message || e),
        thrown: true,
        latencyMs: Date.now() - startedAt,
      };
    }
  }

  /** The drivers' jams between [from] and [to] within [ms]; null when none, too slow or failing. */
  async function jamsWithin(from, to, ms) {
    try {
      return await within(ms, () => jams(from, to));
    } catch (e) {
      console.warn('[route] traffic jams unavailable —', String(e?.message || e));
      return null;
    }
  }

  /** One more route Valhalla left to ORS; said in the logs once a minute per cause at most. */
  function noteFallback(cause) {
    fallbacks.total += 1;
    fallbacks.byCause[cause] = (fallbacks.byCause[cause] ?? 0) + 1;
    fallbacks.last = { at: iso(now()), cause };
    if (!(now() - (warned.get(cause) ?? -Infinity) < 60_000)) {
      warned.set(cause, now());
      console.warn(`[route] Valhalla → ORS (${cause}), ${fallbacks.byCause[cause]} since ${fallbacks.since}`);
    }
  }

  /**
   * The route from [from] to [to] ({ lat, lon }) avoiding [avoid] (tolls, highways, ferries,
   * traffic), served as [plan] says. Never throws: { route, engine, primary, fallback, cause,
   * attempts, polygons } — route null with { status, error, detail, thrown } when nobody could.
   */
  async function route(from, to, avoid = [], { plan: chosen = plan(null), heading = null } = {}) {
    const until = Date.now() + s.deadlineMs;
    const wanted = [...new Set(avoid)];
    const bearings = headingBearings(heading);
    const polygons = wanted.includes('traffic')
      ? await jamsWithin(from, to, Math.min(s.jamsTimeoutMs, until - Date.now()))
      : null;
    const attempts = {};
    let cause = null;
    if (chosen.primary === 'valhalla') {
      const exclusions = wanted.filter((a) => EXCLUSIONS.has(a));
      const tried = await valhallaRoute(from, to, exclusions, polygons, Math.min(s.valhallaTimeoutMs, until - Date.now()), bearings);
      attempts.valhalla = tried;
      if (tried.ok) {
        return { route: tried.route, engine: 'valhalla', primary: 'valhalla', fallback: false, cause: null, attempts, polygons };
      }
      cause = tried.error;
      noteFallback(cause);
    }
    const tried = await orsRoute(from, to, wanted, polygons, until - Date.now(), bearings);
    attempts.ors = tried;
    const outcome = { primary: chosen.primary, fallback: cause != null, cause, attempts, polygons };
    if (tried.ok) return { ...outcome, route: tried.route, engine: 'ors' };
    return {
      ...outcome,
      route: null,
      engine: null,
      status: tried.status,
      error: tried.message,
      detail: tried.detail ?? null,
      thrown: Boolean(tried.thrown),
    };
  }

  /**
   * [engine]'s route for the shadow mode (shadow.js): no fallback, no count; Valhalla skipped
   * while unusable, ORS while its day's routes left are under shadowOrsMinBudgetLeft.
   */
  async function shadowRoute(engine, from, to, avoid = [], polygons = null, heading = null) {
    const wanted = [...new Set(avoid)];
    const bearings = headingBearings(heading);
    if (engine === 'valhalla') {
      return valhallaRoute(from, to, wanted.filter((a) => EXCLUSIONS.has(a)), polygons, Math.min(s.valhallaTimeoutMs, s.deadlineMs), bearings);
    }
    const blocked = orsShadowBlocked();
    if (blocked) return { ok: false, error: blocked, skipped: true, latencyMs: null };
    return orsRoute(from, to, wanted, polygons, s.deadlineMs, bearings);
  }

  function orsShadowBlocked() {
    if (!ors.configured()) return 'not_configured';
    if (!(ors.budgetLeft() >= s.shadowOrsMinBudgetLeft)) return 'budget_reserved';
    return null;
  }

  /**
   * How [engine] draws the variants of /faster (faster.js), whatever the engine: one ask
   * { label, from, to ({ lat, lon }), bearings ([[heading, tolerance], …]), avoid, polygons,
   * alternatives } → { engine, routes, error, outage, skipped?, latencyMs }. [shadow]: for the
   * shadow mode (ORS kept off the fallback's budget).
   */
  function drawer(engine, { shadow = false } = {}) {
    if (engine === 'valhalla') {
      return async (ask) => {
        const blocked = valhallaBlocked();
        if (blocked) return { engine, routes: [], error: blocked, outage: true, skipped: true, latencyMs: null };
        const startedAt = Date.now();
        const result = await askValhalla(() => valhalla.routes(ask.from, ask.to, {
          avoid: (ask.avoid ?? []).filter((a) => EXCLUSIONS.has(a)),
          polygons: ask.polygons ?? null,
          bearings: ask.bearings ?? null,
          alternates: ask.alternatives ? MAX_ALTERNATES : 0,
        }));
        const latencyMs = Date.now() - startedAt;
        if (result.ok) return { engine, routes: result.value, error: null, outage: false, latencyMs };
        console.warn(`[faster] Valhalla ${ask.label} — ${result.error}`);
        return { engine, routes: [], error: result.error, outage: OUTAGES.has(result.error), latencyMs };
      };
    }
    return async (ask) => {
      const blocked = !ors.configured() ? 'not_configured' : shadow ? orsShadowBlocked() : null;
      if (blocked) return { engine, routes: [], error: blocked, outage: false, skipped: true, latencyMs: null };
      const startedAt = Date.now();
      try {
        const r = await ors.post(orsBody(ask), {});
        if (!r.ok) {
          const detail = await r.text().catch(() => '');
          console.warn(`[faster] ORS ${ask.label} ${r.status} — ${detail.slice(0, 160)}`);
          return { engine, routes: [], error: r.budgetSpent ? 'budget' : `http_${r.status}`, outage: false, latencyMs: Date.now() - startedAt };
        }
        const json = await r.json();
        const routes = (json.features ?? []).map((feature) => normalizeOrsFeature(feature, orsMapVersion(json)));
        return { engine, routes, error: null, outage: false, latencyMs: Date.now() - startedAt };
      } catch (e) {
        console.warn(`[faster] ORS ${ask.label} —`, String(e?.message || e));
        return { engine, routes: [], error: 'unavailable', outage: false, latencyMs: Date.now() - startedAt };
      }
    };
  }

  /** Remembers what [accountId] was just served ([outcome]: { engine, fallback }), for /faster. */
  function noteServed(accountId, outcome) {
    if (!accountId || !outcome) return;
    served.delete(accountId);
    served.set(accountId, { fallback: outcome.engine === 'ors' && Boolean(outcome.fallback), at: now() });
    while (served.size > s.accountsMax) served.delete(served.keys().next().value);
  }

  /** Whether [accountId]'s last route was served by ORS for a failing Valhalla, lately. */
  function servedFallback(accountId) {
    const last = served.get(accountId);
    return Boolean(last && last.fallback && now() - last.at < s.fallbackMemoryMs);
  }

  /** Reads Valhalla's /status once (the timer's work): its map, then the outage and its mail. */
  async function refreshStatus() {
    if (!valhalla || status.checking) return;
    status.checking = true;
    try {
      const payload = await valhalla.status();
      Object.assign(status, {
        at: now(),
        error: null,
        down: false,
        version: payload.version,
        tilesetLastModified: payload.tileset_last_modified,
        mapVersion: payload.tileset_last_modified > 0
          ? new Date(payload.tileset_last_modified * 1000).toISOString().replace('.000Z', 'Z')
          : null,
        hasLiveTraffic: payload.has_live_traffic,
        hasTiles: payload.has_tiles,
      });
    } catch (e) {
      status.down = true;
      status.error = KNOWN_ERRORS.has(e?.code) ? e.code : VALHALLA_ERROR.UNAVAILABLE;
    } finally {
      status.checking = false;
    }
    checkOutage();
  }

  /** Valhalla down past alertAfterMs: one mail; back: one more, only after the first. */
  function checkOutage() {
    const cause = valhallaDown();
    if (cause) {
      if (outage.since == null) outage.since = now();
      outage.cause = cause;
      if (!outage.alerted && now() - outage.since >= s.alertAfterMs) {
        outage.alerted = true;
        alert?.outage({ since: iso(outage.since), cause, mode: currentMode() });
      }
      return;
    }
    if (outage.since != null && outage.alerted) alert?.recovered({ since: iso(outage.since), until: iso(now()) });
    Object.assign(outage, { since: null, cause: null, alerted: false });
  }

  /** What /health says of routing (no URL, no route, no account). */
  function health() {
    const down = valhallaDown();
    const primary = plan(null).primary;
    return {
      // The engine a driver gets; null when it cannot serve (ORS without a key).
      provider: primary === 'ors' && !ors.configured() ? null : primary,
      mode: currentMode(),
      valhalla: valhalla
        ? {
          enabled: true,
          state: down ? 'down' : status.at != null ? 'up' : 'unknown',
          cause: down,
          version: status.version,
          mapVersion: status.mapVersion,
          tilesetLastModified: status.tilesetLastModified,
          hasLiveTraffic: status.hasLiveTraffic,
          hasTiles: status.hasTiles,
          statusAt: iso(status.at),
          statusError: status.error,
          lastError: lastError && { code: lastError.code, at: iso(lastError.at) },
          breaker: {
            failures: breaker.failures,
            open: breaker.failures >= s.breakerFailures && now() < breaker.openUntil,
            until: breaker.failures >= s.breakerFailures ? iso(breaker.openUntil) : null,
          },
          downSince: iso(outage.since),
          alerted: outage.alerted,
        }
        : { enabled: false, state: 'disabled', cause: valhallaProblem ?? 'disabled' },
      fallbacks: { ...fallbacks, byCause: { ...fallbacks.byCause } },
    };
  }

  /** Starts reading Valhalla's /status now and every statusEveryMs; nothing without Valhalla. */
  function start() {
    if (!valhalla || timer) return;
    refreshStatus();
    timer = setInterval(refreshStatus, s.statusEveryMs);
    if (timer.unref) timer.unref();
  }

  function stop() {
    if (timer) clearInterval(timer);
    timer = null;
  }

  return {
    plan,
    route,
    shadowRoute,
    drawer,
    valhallaBlocked,
    orsConfigured: () => ors.configured(),
    noteServed,
    servedFallback,
    refreshStatus,
    health,
    start,
    stop,
  };
}

// ---- The backend's façade ------------------------------------------------------

// Traffic jams the route goes around: a square around each one reported live near the trip.
/** Half the side of the square avoided around a jam. */
const JAM_HALF_SIDE_M = 250;
/** A jam this close to the start or the destination stays crossable: the trip must be possible. */
const JAM_KEEP_CLEAR_M = 500;
/** Jams farther than this outside the box of the trip do not matter. */
const JAM_BOX_MARGIN_M = 10000;
/** Enough for a long trip, far under ORS's area limit for avoided polygons (25 km²). */
const JAM_MAX = 100;

/** The jams to avoid between [from] and [to], as a GeoJSON MultiPolygon; null when there is none. */
async function trafficPolygons(from, to) {
  const padLat = JAM_BOX_MARGIN_M / 111320;
  const padLon = padLat / Math.max(Math.cos(((from.lat + to.lat) / 2) * Math.PI / 180), 0.1);
  const jams = await reportStore.liveInBox('traffic_jam', {
    south: Math.min(from.lat, to.lat) - padLat,
    north: Math.max(from.lat, to.lat) + padLat,
    west: Math.min(from.lon, to.lon) - padLon,
    east: Math.max(from.lon, to.lon) + padLon,
  }, JAM_MAX);
  const squares = jams
    .filter((jam) => haversine(jam.lat, jam.lon, from.lat, from.lon) > JAM_KEEP_CLEAR_M
      && haversine(jam.lat, jam.lon, to.lat, to.lon) > JAM_KEEP_CLEAR_M)
    .map((jam) => square(jam.lat, jam.lon, JAM_HALF_SIDE_M));
  return squares.length ? { type: 'MultiPolygon', coordinates: squares } : null;
}

/** Valhalla's client when VALHALLA_ENABLED is on and its settings are usable. */
function valhallaClient() {
  if (!config.valhallaEnabled) return { client: null, problem: 'disabled' };
  try {
    return {
      client: createValhallaClient({
        baseUrl: config.valhallaUrl,
        timeoutMs: config.valhallaTimeoutMs,
        searchCutoffM: config.valhallaSearchCutoffM,
        maxSnapM: config.valhallaMaxSnapM,
      }),
      problem: null,
    };
  } catch (e) {
    console.error('[routing] Valhalla off —', String(e?.message || e));
    return { client: null, problem: 'invalid_config' };
  }
}

const valhallaSetup = valhallaClient();

export const routing = createRoutingEngine({
  ors: {
    configured: () => Boolean(config.orsApiKey),
    post: postORS,
    budgetLeft: orsBudgetLeft,
  },
  valhalla: valhallaSetup.client,
  valhallaProblem: valhallaSetup.problem,
  mode: () => settingsStore.routingEngine,
  jams: trafficPolygons,
  alert: createRoutingAlert(),
});

/** ORS's keys and what they spent today, for /health (as before phase 2). */
export function orsHealth() {
  return config.orsApiKey ? { ...orsKeysMeta(), usage: orsUsage() } : {};
}

/**
 * The bench's Valhalla route (bench.js): Valhalla alone, whatever routingEngine says, with the
 * same avoid options. Null when Valhalla is off. Throws its ValhallaError.
 */
export async function computeValhallaRoute(from, to, avoid = []) {
  if (!valhallaSetup.client) return null;
  const [route] = await valhallaSetup.client.routes(from, to, { avoid });
  return route ?? null;
}

/**
 * The bench's route (bench.js): served as a driver's would be (not an admin's), with the same
 * keys and budget; a failure as { error, status, detail? }.
 */
export async function computeRoute(from, to, avoid = []) {
  const outcome = await routing.route(from, to, avoid, { plan: routing.plan(null) });
  return outcome.route ?? { error: outcome.error, status: outcome.status, detail: outcome.detail ?? undefined };
}
