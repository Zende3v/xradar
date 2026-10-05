import { createHash } from 'node:crypto';
import { Router } from 'express';
import { config } from '../config.js';
import { db } from '../db.js';
import { adminActor } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { liveStore } from '../live/store.js';
import { routing } from '../routing/engine.js';
import { hereParisWeek, hereUsage } from '../traffic/budget.js';

/** Présence observée. Aucun changement des préférences de confidentialité. */
export function dashboardPresence(accounts, presence, store = accountStore, ttlMs = config.liveTtlMs) {
  const groups = [
    { id: 'free', label: 'Gratuit', total: 0, online: 0, offline: 0, inTrip: 0 },
    { id: 'client', label: 'Client', total: 0, online: 0, offline: 0, inTrip: 0 },
    { id: 'admin', label: 'Admin', total: 0, online: 0, offline: 0, inTrip: 0 },
  ];
  const live = new Map(presence);
  for (const account of accounts) {
    const id = account.role === 'admin' ? 'admin' : store.hasPlus(account) ? 'client' : 'free';
    const group = groups.find((item) => item.id === id);
    group.total += 1;
    const entry = live.get(account.id);
    const active = entry && entry.at >= Date.now() - ttlMs && store.accessFor(account).canNavigate;
    if (active) {
      group.online += 1;
      if (entry.inTrip) group.inTrip += 1;
    } else group.offline += 1;
  }
  return {
    ttlSeconds: ttlMs / 1000,
    groups,
    online: groups.reduce((sum, group) => sum + group.online, 0),
    offline: groups.reduce((sum, group) => sum + group.offline, 0),
    inTrip: groups.reduce((sum, group) => sum + group.inTrip, 0),
  };
}

const integer = (value) => {
  const number = Number(value);
  return Number.isFinite(number) ? Math.min(Number.MAX_SAFE_INTEGER, Math.max(0, Math.round(number))) : 0;
};
const paging = (value, fallback, max) => {
  const number = Number(value);
  return Number.isSafeInteger(number) && number >= 0 ? Math.min(number, max) : fallback;
};

/** Activité anonyme. Identités, coordonnées et libellés de lieux exclus. */
export function dashboardTrips(accounts, { limit = 40, offset = 0, retention = config.accountTripHistoryMax } = {}) {
  const trips = accounts.flatMap((account) => (account.trips ?? []).flatMap((trip) => {
    const startedAt = Number(trip.startedAt);
    if (!Number.isFinite(startedAt) || startedAt <= 0) return [];
    return [{
      id: createHash('sha256').update(`${account.id}\0${trip.id}`).digest('hex').slice(0, 24),
      startedAt,
      distanceMeters: integer(trip.distanceMeters),
      durationSeconds: integer(trip.durationSeconds),
      arrived: typeof trip.arrived === 'boolean' ? trip.arrived : null,
      engines: Array.isArray(trip.engines) ? trip.engines.filter((engine) => ['valhalla', 'ors', 'osrm'].includes(engine)) : [],
      platform: ['ios', 'android'].includes(trip.platform) ? trip.platform : null,
    }];
  })).sort((a, b) => b.startedAt - a.startedAt || a.id.localeCompare(b.id));
  const size = Math.max(1, paging(limit, 40, 100));
  const start = paging(offset, 0, Number.MAX_SAFE_INTEGER);
  const page = trips.slice(start, start + size);
  return {
    total: trips.length,
    count: page.length,
    offset: start,
    next: start + page.length < trips.length ? start + page.length : null,
    retentionPerAccount: retention,
    trips: page,
  };
}

/** Valhalla seul. Latence API, cache compris ; historique conservé 90 jours. */
export async function dashboardRoutingStats(database = db, at = Date.now(), retentionDays = config.routeLogKeepDays) {
  const base = {
    available: false, retentionDays, engine: 'valhalla', latencyIncludesCache: true, errorAttributionAvailable: false,
    totalRequests: null, totalErrors: null, cacheHits: null,
    weekRequests: null, weekErrors: null, medianMs: null, p95Ms: null,
  };
  try {
    const result = await database.query(`
      SELECT COUNT(*) AS total_requests,
        COUNT(*) FILTER (WHERE cached IS TRUE) AS cache_hits,
        COUNT(*) FILTER (WHERE at >= $2::timestamptz) AS week_requests,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS median_ms,
        percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95_ms
      FROM routing.route_log
      WHERE engine = 'valhalla' AND at >= $3::timestamptz - make_interval(days => $1::int)
    `, [retentionDays, new Date(hereParisWeek(at).startAt).toISOString(), new Date(at).toISOString()]);
    const row = result.rows[0];
    if (!row) return base;
    const latency = (value) => value != null && Number.isFinite(Number(value)) ? Number(value) : null;
    return {
      ...base, available: true,
      // Échecs sans route : engine nul dans journal. Aucun zéro d'erreur fabriqué.
      totalRequests: integer(row.total_requests), cacheHits: integer(row.cache_hits),
      weekRequests: integer(row.week_requests),
      medianMs: latency(row.median_ms), p95Ms: latency(row.p95_ms),
    };
  } catch {
    return base;
  }
}

/** Accès administrateur par session uniquement ; ADMIN_TOKEN reste réservé aux scripts. */
export function createDashboardRouter({ store = accountStore, live = liveStore, database = db, engine = routing, usage = hereUsage, actor = adminActor } = {}) {
  const router = Router();
  router.use((req, res, next) => {
    if (!actor(req)) return res.status(401).json({ error: 'admin session required' });
    res.set('Cache-Control', 'no-store');
    next();
  });
  router.get('/overview', async (_req, res) => {
    try {
      const accounts = store.list();
      const routingStats = await dashboardRoutingStats(database);
      res.json({
        generatedAt: new Date().toISOString(),
        presence: dashboardPresence(accounts, live.entries(), store),
        accounts: {
          total: accounts.length,
          banned: accounts.filter((account) => store.blockReason(account) === 'banned').length,
          suspended: accounts.filter((account) => account.suspended || account.revoked).length,
        },
        trips: {
          retainedCount: accounts.reduce((sum, account) => sum + (account.trips?.length ?? 0), 0),
          totalRecorded: accounts.reduce((sum, account) => sum + integer(account.stats?.tripCount), 0),
        },
        routing: engine.health(),
        routingStats,
        here: usage(),
      });
    } catch {
      res.status(503).json({ error: 'admin data unavailable' });
    }
  });
  router.get('/trips', (req, res) => {
    res.json(dashboardTrips(store.list(), { limit: req.query.limit, offset: req.query.offset }));
  });
  return router;
}

export const dashboardRouter = createDashboardRouter();
