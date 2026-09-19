import { config } from '../config.js';
import { haversine } from '../radars/geo.js';

/**
 * What protects the routing quota from a client that asks too much: recent answers are handed
 * back instead of being computed again, and one account can only spend so many routes an hour.
 * A bug in an app (a recalculation loop, a retry that never gives up) then costs a few calls,
 * never the day's quota of everyone.
 */

/** Recent answers, newest last. Small on purpose: it only catches a client asking again. */
const recent = [];

/** The answer given for the same trip a moment ago, or null. */
export function cachedRoute(from, to, avoid) {
  const key = avoid.slice().sort().join(',');
  const fresh = Date.now() - config.routeCacheMs;
  for (let i = recent.length - 1; i >= 0; i -= 1) {
    const e = recent[i];
    if (e.at < fresh) break; // everything before is older still
    if (e.key !== key) continue;
    if (haversine(e.to.lat, e.to.lon, to.lat, to.lon) > config.routeCacheToM) continue;
    if (haversine(e.from.lat, e.from.lon, from.lat, from.lon) > config.routeCacheFromM) continue;
    return e.route;
  }
  return null;
}

export function keepRoute(from, to, avoid, route) {
  recent.push({ at: Date.now(), key: avoid.slice().sort().join(','), from, to, route });
  const fresh = Date.now() - config.routeCacheMs;
  while (recent.length && (recent[0].at < fresh || recent.length > config.routeCacheMax)) recent.shift();
}

/** Route requests that really went to the routing engine, per account. */
const spent = new Map(); // account id -> [timestamps]
/** The last time an account was told it asks too much, so the logs stay readable. */
const warned = new Map();

/**
 * Whether [accountId] may spend one more route. A driver needs a handful an hour (the trip, the
 * recalculations on the way); way above that, it is a loop, and the answer says how long to wait.
 */
export function spendRoute(accountId) {
  const now = Date.now();
  const times = (spent.get(accountId) || []).filter((t) => now - t < 3600_000);
  const lastMinute = times.filter((t) => now - t < 60_000);
  const over = lastMinute.length >= config.routePerMinute
    ? { after: 60_000 - (now - lastMinute[0]), limit: 'minute' }
    : times.length >= config.routePerHour
      ? { after: 3600_000 - (now - times[0]), limit: 'heure' }
      : null;
  spent.set(accountId, times);
  if (over) {
    if (now - (warned.get(accountId) || 0) > 60_000) {
      warned.set(accountId, now);
      console.warn(`[route] plafond par ${over.limit} atteint — compte ${accountId}, ${times.length} itinéraires dans l'heure`);
    }
    return { ok: false, retryAfterS: Math.max(1, Math.ceil(over.after / 1000)) };
  }
  times.push(now);
  spent.set(accountId, times);
  // Accounts that stopped asking do not stay in memory.
  if (spent.size > config.routeAccountsMax) {
    for (const [id, list] of spent) {
      if (!list.length || now - list[list.length - 1] > 3600_000) spent.delete(id);
    }
  }
  return { ok: true };
}
