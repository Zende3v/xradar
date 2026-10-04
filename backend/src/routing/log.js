import { config } from '../config.js';
import { db } from '../db.js';

/**
 * The route log (schema routing, D1.8): one line per /api/route answer — cache hits and errors
 * included — and per /api/route/faster answer: the status, the latency, the engine, a new trip
 * or a recalculation, the route's size and a U-turn among its first manoeuvres. No account, no
 * coordinates; kept config.routeLogKeepDays days, purged at most once an hour in passing. A line
 * never slows nor fails an answer: it is sent without waiting, and a failure only goes to the
 * logs.
 */

/** The avoid options a line keeps: the ones the apps send, nothing else a client could write. */
const AVOID = new Set(['tolls', 'highways', 'ferries', 'traffic']);
/** Choix d'itinéraire gardé : Rapide ou Éco, rien d'autre. */
const PREFERENCES = new Set(['fastest', 'shortest']);

let lastPurge = 0;

/**
 * One line: { kind: 'route' | 'faster', status, latencyMs, engine?, isNew?, cached?, distanceM?,
 * durationS?, steps?, uturnStart?, avoid?, preference?, stops?, error? } — what is not known stays null.
 */
export function logRoute(entry) {
  db.query(
    `INSERT INTO routing.route_log (kind, engine, status, latency_ms, is_new, cached, distance_m, duration_s, steps, uturn_start, avoid, error, preference, stops)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14)`,
    [
      entry.kind,
      text(entry.engine, 16),
      whole(entry.status),
      whole(entry.latencyMs),
      flag(entry.isNew),
      flag(entry.cached),
      whole(entry.distanceM),
      whole(entry.durationS),
      whole(entry.steps),
      flag(entry.uturnStart),
      Array.isArray(entry.avoid) ? [...new Set(entry.avoid.filter((a) => AVOID.has(a)))] : [],
      text(entry.error, 200),
      PREFERENCES.has(entry.preference) ? entry.preference : null,
      whole(entry.stops),
    ],
  ).catch((e) => console.warn('[route-log] not written —', String(e.message || e)));
  if (Date.now() - lastPurge > 60 * 60 * 1000) {
    lastPurge = Date.now();
    db.query('DELETE FROM routing.route_log WHERE at < now() - make_interval(days => $1)', [config.routeLogKeepDays])
      .catch((e) => console.warn('[route-log] purge —', String(e.message || e)));
  }
}

/** What the log and the bench keep of a route: its engine, its size, a U-turn among its first 2 steps. */
export function routeFacts(route) {
  const steps = Array.isArray(route?.steps) ? route.steps : [];
  return {
    engine: route?.engine ?? null,
    distanceM: route?.distanceM ?? null,
    durationS: route?.durationS ?? null,
    steps: steps.length,
    uturnStart: steps.slice(0, 2).some((step) => step?.modifier === 'uturn'),
  };
}

const whole = (value) => (typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : null);
const flag = (value) => (typeof value === 'boolean' ? value : null);
const text = (value, max) => (value == null || value === '' ? null : String(value).slice(0, max));
