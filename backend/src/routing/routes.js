import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { hereAllows, hereAccountAllows } from '../traffic/budget.js';
import { hereTravel } from '../traffic/here.js';
import { routing as defaultRouting } from './engine.js';
import { checkFaster } from './faster.js';
import { MAX_STOPS } from './providers/valhalla.js';
import { cachedRoute, keepRoute, spendRoute } from './guard.js';
import { logRoute, routeFacts } from './log.js';
import { shadow as defaultShadow } from './shadow.js';

/**
 * The routing endpoints (`/api/route`), over the routing façade (engine.js). [deps] stand for
 * the backend's own pieces in the tests.
 */
export function createRouteRouter({
  routing = defaultRouting,
  shadow = defaultShadow,
  auth = authAccount,
  accounts = accountStore,
  guard = { cachedRoute, keepRoute, spendRoute },
  log = logRoute,
  faster = checkFaster,
  liveReady = () => hereAllows(),
  accountAllows = hereAccountAllows,
  travel = (points) => hereTravel(points, { use: 'eta' }),
} = {}) {
  const router = Router();
  /** When each account last had a faster-route check (rerouteCheckGapS). */
  const lastCheckAt = new Map();

  /**
   * Temps HERE avec trafic de [route] entière (Route Import), borné à routeTimingMs ; null si HERE
   * muet, budget atteint ou route non reconnue. Même tracé que premier rafraîchissement trafic de
   * l'app : cache HERE 60 s, aucun second appel pour la route choisie.
   */
  async function timeRoute(route) {
    const points = (route.coordinates ?? []).map(([lon, lat]) => [lat, lon]);
    let timer;
    const late = new Promise((resolve) => { timer = setTimeout(() => resolve(null), config.routeTimingMs); });
    try {
      const timing = await Promise.race([Promise.resolve().then(() => travel(points)).catch(() => null), late]);
      return timing?.travelS > 0 ? timing.travelS : null;
    } finally {
      clearTimeout(timer);
    }
  }

  /**
   * GET /api/route?from=lat,lon&to=lat,lon[&avoid=tolls,highways,ferries,traffic][&preference=fastest|shortest][&timed=1][&via=lat,lon;lat,lon]
   * [via] (multi-arrêts, 04/10) : étapes dans l'ordre, MAX_STOPS au plus ; route unique qui les
   * traverse, sans manœuvre d'arrivée intermédiaire.
   * [preference] (choix d'itinéraire, 02/10) : `shortest` = Éco, la plus courte en distance ;
   * `fastest` = Rapide, comme avant. Demandée : réponse la répète (`preference`). [timed=1] :
   * `travelS`, temps HERE avec trafic de la route entière, null sans HERE. Sans eux : JSON inchangé.
   * Returns a normalized car route, from the engine routingEngine gives this account (engine.js):
   * ORS, or Valhalla with ORS in fallback. "traffic" keeps the route away from the traffic jams
   * drivers reported. The route says which engine drew it (`engine`: ors | valhalla) and on
   * which map (`mapVersion`: ORS's graph date, Valhalla's tiles date; null when unknown). Every
   * answer to an account goes to the route log; once a computed answer is given, the other
   * engine computes the same route in shadow (shadow.js).
   */
  router.get('/', async (req, res) => {
    const facts = { kind: 'route' };
    const answer = logged(res, facts, log);
    // Not logged: without an account, anyone could fill the log.
    const account = auth(req);
    if (!account) return res.status(401).json({ error: 'account required' });
    const avoid = String(req.query.avoid || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);
    facts.avoid = avoid;
    // An expired trial (or lapsed subscription) keeps the map, not the navigation.
    if (account.banned) return answer(403, { error: 'banned' });
    if (!accounts.accessFor(account).canNavigate) {
      return answer(403, { error: 'subscription required' });
    }
    const from = parseCoord(req.query.from);
    const to = parseCoord(req.query.to);
    if (!from || !to) {
      return answer(400, { error: 'from and to are required as "lat,lon"' });
    }
    const preference = parsePreference(req.query.preference);
    if (preference === undefined) return answer(400, { error: 'preference must be fastest or shortest' });
    const shortest = preference === 'shortest';
    facts.preference = preference ?? 'fastest';
    const via = parseVia(req.query.via);
    if (!via) return answer(400, { error: `via takes ${MAX_STOPS} stops "lat,lon;lat,lon" at most` });
    facts.stops = via.length;
    const timed = req.query.timed === '1';
    /** La route, plus ce que l'app récente a demandé : preference, travelS. */
    const reply = async (route) => (!preference && !timed ? route : {
      ...route,
      ...(preference ? { preference } : {}),
      ...(timed ? { travelS: await timeRoute(route) } : {}),
    });
    // A guest starts a limited number of trips a day; recalculations to the same place are free.
    const trip = accounts.tripCheck(account, to);
    facts.isNew = trip.isNew;
    if (!trip.allowed) {
      return answer(429, { error: 'daily trip limit', limit: config.guestTripsPerDay });
    }

    // Who serves this account now: the cache below keeps each engine's answers apart, Éco aussi.
    const plan = routing.plan(account);
    const partition = [
      plan.primary,
      ...(shortest ? ['shortest'] : []),
      ...(via.length ? [`via:${via.map((p) => `${p.lat.toFixed(5)},${p.lon.toFixed(5)}`).join(';')}`] : []),
    ].join(':');
    // The same trip asked again within the minute costs nothing: an app looping on a recalculation
    // (a driver still off the road, a retry that keeps failing) never spends the day's routes.
    // The driver's course (D4.4), sent by the apps while moving: no U-turn at the start. A route asked
    // with it skips the cache, which knows nothing of the way the car points.
    const heading = parseHeading(req.query.heading);
    const known = heading == null ? guard.cachedRoute(from, to, avoid, partition) : null;
    if (known) {
      if (trip.isNew) accounts.countTrip(account, to);
      routing.noteServed(account.id, known.served);
      return answer(200, await reply(known.route), { cached: true, ...routeFacts(known.route) });
    }
    facts.cached = false;
    const allowance = guard.spendRoute(account.id);
    if (!allowance.ok) {
      return answer(429, { error: 'too many routes', retryAfterS: allowance.retryAfterS });
    }

    let outcome;
    try {
      outcome = await routing.route(from, to, avoid, { plan, heading, preference: preference ?? 'fastest', via });
    } catch (e) {
      // The façade answers its failures; this is a bug, still answered like one.
      console.warn('[route] unavailable —', String(e.message || e));
      return answer(502, { error: 'routing unavailable', detail: String(e.message || e) }, { error: String(e.message || e) });
    }
    if (outcome.route) {
      if (trip.isNew) accounts.countTrip(account, to);
      const served = { engine: outcome.engine, fallback: outcome.fallback };
      guard.keepRoute(from, to, avoid, outcome.route, { partition, served });
      routing.noteServed(account.id, served);
      answer(200, await reply(outcome.route), routeFacts(outcome.route));
    } else if (outcome.thrown) {
      console.warn('[route] unavailable —', outcome.detail);
      answer(outcome.status, { error: outcome.error, detail: outcome.detail }, { error: outcome.detail });
    } else {
      console.warn(`[route] ${outcome.error}${outcome.detail ? ' — ' + outcome.detail : ''}`);
      // The engine's own text may quote the coordinates: the log keeps the error alone.
      answer(outcome.status || 502, { error: outcome.error });
    }
    // Only once the answer is sent (shadow.js queues it on the response's end). Éco ou étapes :
    // pas d'ombre, comparaison des moteurs faite sur Rapide direct seulement.
    if (!shortest && !via.length) shadow.afterRoute(res, { plan, outcome, from, to, avoid, heading, admin: account.role === 'admin' });
  });

  /**
   * POST /api/route/faster  { coordinates: [[lon, lat], …], avoid?: ["tolls", "highways"], sinceRerouteS?, etaS?, preference?, via? }
   * [preference] `shortest` (trajet Éco) : détour seulement autour d'une route fermée, variantes
   * Éco, la plus courte retenue. [via] : étapes restantes [[lon, lat], …] ; détour avant la
   * première seulement, reste du trajet par les suivantes.
   * The rest of the route being followed (from the driver to the destination) against variants
   * around its big traffic jams, drawn by the engine that serves this account (ORS or Valhalla),
   * durations come from HERE on the same window, completed by EONA/data.gouv: a variant
   * comes back (`better`) only when it saves enough time ([etaS], the app's ETA, sets the share of
   * the time left). One check per account a rerouteCheckGapS at most (429). See faster.js. When this account's engine is Valhalla and Valhalla fails, or
   * served its last route through ORS, no detour: `reason: "fallback"` (D5.2). Its route carries
   * `engine` and `mapVersion` like /api/route. Every answer to an account goes to the route
   * log: why there is no detour (`reason`) as its error. Then the other engine draws the same
   * variants in shadow, without live traffic.
   */
  router.post('/faster', async (req, res) => {
    const facts = { kind: 'faster' };
    const answer = logged(res, facts, log);
    const account = auth(req);
    if (!account) return res.status(401).json({ error: 'account required' });
    if (account.banned) return answer(403, { error: 'banned' });
    if (!accounts.accessFor(account).canNavigate) {
      return answer(403, { error: 'subscription required' });
    }
    // The live traffic times the variants; the engine of this account draws them.
    const plan = routing.plan(account);
    if (!liveReady() || (plan.primary === 'ors' && !routing.orsConfigured())) {
      return answer(503, { error: 'rerouting unavailable' });
    }
    // One check at a time per account: a check asks the live traffic for each variant.
    const now = Date.now();
    if (now - (lastCheckAt.get(account.id) ?? 0) < config.rerouteCheckGapS * 1000) return answer(429, { error: 'too many checks' });
    lastCheckAt.set(account.id, now);
    const coords = req.body?.coordinates;
    if (!Array.isArray(coords) || coords.length < 2 || coords.length > config.trafficMaxPoints) {
      return answer(400, { error: 'coordinates [[lon,lat],...] required' });
    }
    const points = [];
    for (const c of coords) {
      const lon = Number(c?.[0]);
      const lat = Number(c?.[1]);
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) return answer(400, { error: 'invalid coordinate' });
      points.push([lat, lon]);
    }
    const avoid = Array.isArray(req.body.avoid) ? req.body.avoid.map(String) : [];
    facts.avoid = avoid;
    const preference = parsePreference(req.body.preference);
    if (preference === undefined) return answer(400, { error: 'preference must be fastest or shortest' });
    facts.preference = preference ?? 'fastest';
    const via = parseViaList(req.body.via);
    if (!via) return answer(400, { error: `via takes ${MAX_STOPS} stops [[lon,lat],...] at most` });
    facts.stops = via.length;
    const since = Number(req.body.sinceRerouteS);
    const etaS = Number(req.body.etaS);
    // A failing Valhalla, or a route ORS served in its place: no detour, nothing spent (D5.2).
    if (plan.primary === 'valhalla' && (routing.valhallaBlocked() || routing.servedFallback(account.id))) {
      return answer(200, { better: null, reason: 'fallback' }, { error: 'fallback' });
    }
    if (!accountAllows(account.id, now, 'faster')) return answer(429, { error: 'daily check limit' });
    try {
      const { answer: result, compare } = await faster(points, {
        avoid,
        sinceRerouteS: Number.isFinite(since) && since >= 0 ? since : null,
        etaS: Number.isFinite(etaS) && etaS > 0 ? etaS : null,
        draw: routing.drawer(plan.primary),
        preference: preference ?? 'fastest',
        via,
      });
      answer(200, result, result.better ? routeFacts(result.better.route) : { error: result.reason ?? null });
      if (result.better) routing.noteServed(account.id, { engine: result.better.route.engine, fallback: false });
      shadow.afterFaster(res, { plan, compare, avoid, admin: account.role === 'admin' });
    } catch (e) {
      console.warn('[faster] unavailable —', String(e.message || e));
      answer(502, { error: 'rerouting unavailable' }, { error: String(e.message || e) });
    }
  });

  return router;
}

/**
 * A reply to [res] that also goes to the route log (log.js), after the answer is sent: [facts]
 * (kind, avoid, isNew… filled in as the request is read), what [more] adds, the status, the
 * latency since the request came in, and the error given back when there is one.
 */
function logged(res, facts, log) {
  const startedAt = Date.now();
  return (status, body, more = {}) => {
    res.status(status).json(body);
    log({
      ...facts,
      ...more,
      status,
      latencyMs: Date.now() - startedAt,
      error: more.error ?? (status >= 400 ? body?.error ?? null : null),
    });
  };
}

function parseCoord(value) {
  const parts = String(value || '').split(',').map(Number);
  if (parts.length !== 2 || parts.some((n) => !Number.isFinite(n))) return null;
  return { lat: parts[0], lon: parts[1] };
}

/** Étapes "lat,lon;lat,lon" en { lat, lon } ; [] absentes ; null illisibles ou trop nombreuses. */
function parseVia(value) {
  if (value == null || value === '') return [];
  if (typeof value !== 'string') return null;
  const stops = value.split(';').map(parseCoord);
  return stops.length <= MAX_STOPS && stops.every((p) => p && Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180) ? stops : null;
}

/** Étapes [[lon, lat], …] du corps JSON en { lat, lon } ; [] absentes ; null illisibles. */
function parseViaList(value) {
  if (value == null) return [];
  if (!Array.isArray(value) || value.length > MAX_STOPS) return null;
  const stops = value.map((c) => ({ lat: Number(c?.[1]), lon: Number(c?.[0]) }));
  return stops.every((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon) && Math.abs(p.lat) <= 90 && Math.abs(p.lon) <= 180) ? stops : null;
}

/** Choix d'itinéraire : 'fastest' | 'shortest' ; null absent (anciennes apps) ; undefined illisible. */
function parsePreference(value) {
  if (value == null || value === '') return null;
  return value === 'fastest' || value === 'shortest' ? value : undefined;
}

/** The driver's course in degrees (0 to 360, from north), or null when absent or unreadable. */
function parseHeading(value) {
  if (value == null || value === '') return null;
  const heading = Number(value);
  return Number.isFinite(heading) && heading >= 0 && heading <= 360 ? heading : null;
}

export const routeRouter = createRouteRouter();
