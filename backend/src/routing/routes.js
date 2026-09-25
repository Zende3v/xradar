import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { computeRoute } from './engine.js';
import { fasterRoute } from './faster.js';
import { cachedRoute, keepRoute, spendRoute } from './guard.js';
import { logRoute, routeFacts } from './log.js';

export const routeRouter = Router();

/**
 * GET /api/route?from=lat,lon&to=lat,lon[&avoid=tolls,highways,traffic]
 * Returns a normalized car route. Uses OpenRouteService when ORS_API_KEY is set
 * (better quality + avoid options), otherwise falls back to the OSRM demo (engine.js).
 * "traffic" keeps the route away from the traffic jams drivers reported (ORS only).
 * The route says which engine drew it (`engine`: ors | osrm) and on which map (`mapVersion`:
 * ORS's graph date, null when unknown). Every answer to an account goes to the route log.
 */
routeRouter.get('/', async (req, res) => {
  const facts = { kind: 'route' };
  const answer = logged(res, facts);
  // Not logged: without an account, anyone could fill the log.
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  const avoid = String(req.query.avoid || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  facts.avoid = avoid;
  // An expired trial (or lapsed subscription) keeps the map, not the navigation.
  if (account.banned) return answer(403, { error: 'banned' });
  if (!accountStore.accessFor(account).canNavigate) {
    return answer(403, { error: 'subscription required' });
  }
  const from = parseCoord(req.query.from);
  const to = parseCoord(req.query.to);
  if (!from || !to) {
    return answer(400, { error: 'from and to are required as "lat,lon"' });
  }
  // A guest starts a limited number of trips a day; recalculations to the same place are free.
  const trip = accountStore.tripCheck(account, to);
  facts.isNew = trip.isNew;
  if (!trip.allowed) {
    return answer(429, { error: 'daily trip limit', limit: config.guestTripsPerDay });
  }

  // The same trip asked again within the minute costs nothing: an app looping on a recalculation
  // (a driver still off the road, a retry that keeps failing) never spends the day's routes.
  const known = cachedRoute(from, to, avoid);
  if (known) {
    if (trip.isNew) accountStore.countTrip(account, to);
    return answer(200, known, { cached: true, ...routeFacts(known) });
  }
  facts.cached = false;
  const allowance = spendRoute(account.id);
  if (!allowance.ok) {
    return answer(429, { error: 'too many routes', retryAfterS: allowance.retryAfterS });
  }

  try {
    const route = await computeRoute(from, to, avoid);
    if (route.error) {
      console.warn(`[route] ${route.error}${route.detail ? ' — ' + route.detail : ''}`);
      // The engine's own text may quote the coordinates: the log keeps the error alone.
      return answer(route.status || 502, { error: route.error });
    }
    if (trip.isNew) accountStore.countTrip(account, to);
    keepRoute(from, to, avoid, route);
    answer(200, route, routeFacts(route));
  } catch (e) {
    console.warn('[route] unavailable —', String(e.message || e));
    answer(502, { error: 'routing unavailable', detail: String(e.message || e) }, { error: String(e.message || e) });
  }
});

/**
 * POST /api/route/faster  { coordinates: [[lon, lat], …], avoid?: ["tolls", "highways"], sinceRerouteS? }
 * The rest of the route being followed (from the driver to the destination) against ORS
 * variants around its big traffic jams, all timed by TomTom with today's traffic: a variant comes
 * back (`better`) only when it saves enough time. See faster.js. Its route carries `engine` and
 * `mapVersion` like /api/route. Every answer to an account goes to the route log: why there is
 * no detour (`reason`) as its error.
 */
routeRouter.post('/faster', async (req, res) => {
  const facts = { kind: 'faster' };
  const answer = logged(res, facts);
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return answer(403, { error: 'banned' });
  if (!accountStore.accessFor(account).canNavigate) {
    return answer(403, { error: 'subscription required' });
  }
  if (!config.orsApiKey || !config.tomtomApiKey) return answer(503, { error: 'rerouting unavailable' });
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
  const since = Number(req.body.sinceRerouteS);
  try {
    const result = await fasterRoute(points, { avoid, sinceRerouteS: Number.isFinite(since) && since >= 0 ? since : null });
    answer(200, result, result.better ? routeFacts(result.better.route) : { error: result.reason ?? null });
  } catch (e) {
    console.warn('[faster] unavailable —', String(e.message || e));
    answer(502, { error: 'rerouting unavailable' }, { error: String(e.message || e) });
  }
});

/**
 * A reply to [res] that also goes to the route log (log.js), after the answer is sent: [facts]
 * (kind, avoid, isNew… filled in as the request is read), what [more] adds, the status, the
 * latency since the request came in, and the error given back when there is one.
 */
function logged(res, facts) {
  const startedAt = Date.now();
  return (status, body, more = {}) => {
    res.status(status).json(body);
    logRoute({
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
