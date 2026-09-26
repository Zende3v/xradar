import { Router } from 'express';
import { config } from '../config.js';
import { adminActor } from '../accounts/auth.js';
import { ROUTING_ENGINES, settingsStore } from '../accounts/settings.js';
import { accountStore } from '../accounts/store.js';
import { reportStore } from '../reports/store.js';
import { BENCH_SLOTS, benchRuns, runBench } from '../routing/bench.js';
import { routing } from '../routing/engine.js';
import { shadow, shadowStore } from '../routing/shadow.js';
import { adminAudit } from './audit.js';

/**
 * The console's own routes (`/api/admin`): every report with its author, the journal of what
 * admins did, the routing bench, and the routing engine (routingEngine, the shadow mode's
 * measures and the admins' divergent routes). An admin account signed in with its session, or
 * the ADMIN_TOKEN — nothing else gets in.
 */
export const adminRouter = Router();

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const STATUSES = new Set(['live', 'expired', 'denied', 'removed', 'closed']);

adminRouter.use((req, res, next) => {
  const actor = adminActor(req);
  if (!actor) return res.status(401).json({ error: 'admin session required' });
  req.actor = actor;
  next();
});

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[admin]', e.message);
    res.status(503).json({ error: 'admin data unavailable' });
  }
};

/**
 * GET /api/admin/reports?status=&type=&author=&from=&to=&bbox=west,south,east,north&before=&limit=
 * Every report, newest first, live or closed, with its author (pseudo, account, role). Page by
 * page: `before` = the `createdAt` of the last one received.
 */
adminRouter.get('/reports', guarded(async (req, res) => {
  const status = req.query.status ? String(req.query.status) : null;
  if (status && !STATUSES.has(status)) return res.status(400).json({ error: 'unknown status' });
  const box = parseBox(req.query.bbox);
  if (req.query.bbox && !box) return res.status(400).json({ error: 'bbox = west,south,east,north' });
  const limit = pageSize(req.query.limit);
  const reports = await reportStore.adminList({
    status,
    type: req.query.type ? String(req.query.type) : null,
    author: req.query.author ? String(req.query.author) : null,
    from: moment(req.query.from),
    to: moment(req.query.to),
    before: moment(req.query.before),
    box,
    limit,
  });
  res.json({
    count: reports.length,
    // Nothing more when the page is not full.
    next: reports.length === limit ? reports[reports.length - 1].createdAt : null,
    reports: reports.map(withAuthor),
  });
}));

/** GET /api/admin/reports/:id — one report, its author, and its voices counted by kind. */
adminRouter.get('/reports/:id', guarded(async (req, res) => {
  if (!UUID.test(req.params.id)) return res.status(404).json({ error: 'not found' });
  const report = await reportStore.adminGet(req.params.id);
  if (!report) return res.status(404).json({ error: 'not found' });
  res.json({ report: withAuthor(report) });
}));

/**
 * GET /api/admin/audit?before=&actor=&action=&targetType=&targetId=&limit=
 * What the admins did, newest first. `before` = the `at` of the last line received.
 */
adminRouter.get('/audit', guarded(async (req, res) => {
  const limit = pageSize(req.query.limit);
  const entries = await adminAudit.list({
    before: moment(req.query.before),
    actor: req.query.actor ? String(req.query.actor) : null,
    action: req.query.action ? String(req.query.action) : null,
    targetType: req.query.targetType ? String(req.query.targetType) : null,
    targetId: req.query.targetId ? String(req.query.targetId) : null,
    limit,
  });
  res.json({
    count: entries.length,
    next: entries.length === limit ? entries[entries.length - 1].at : null,
    entries,
  });
}));

/**
 * POST /api/admin/bench/run[?slot=matin|midi|soir|nuit] — one bench run (routing/bench.js):
 * the next trips of the rotation, each timed against TomTom and stored. Started by
 * deploy/eona-bench.cron (the slot says which), or by hand (no slot). Answers the run's summary;
 * 409 while another run goes, 503 without a TomTom key.
 */
adminRouter.post('/bench/run', guarded(async (req, res) => {
  const slot = req.query.slot ? String(req.query.slot) : null;
  if (slot && !BENCH_SLOTS.includes(slot)) return res.status(400).json({ error: 'slot = matin|midi|soir|nuit' });
  if (!config.tomtomApiKey) return res.status(503).json({ error: 'bench unavailable' });
  const summary = await runBench({ slot });
  if (!summary) return res.status(409).json({ error: 'bench already running' });
  res.json(summary);
}));

/**
 * GET /api/admin/bench/runs?since=&limit= — the bench's measures, newest first: from `since`
 * (ISO or milliseconds) on, `limit` of them (200 by default, 1000 at most).
 */
adminRouter.get('/bench/runs', guarded(async (req, res) => {
  const n = Number(req.query.limit);
  const limit = Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), 1000) : 200;
  const runs = await benchRuns({ since: moment(req.query.since), limit });
  res.json({ count: runs.length, runs });
}));

/**
 * GET /api/admin/routing — which engine serves the routes (`routingEngine`: ors | admins | all,
 * and the values it takes), what /health says of routing, and the shadow's queue.
 */
adminRouter.get('/routing', (req, res) => {
  res.json({ routingEngine: settingsStore.routingEngine, routingEngines: ROUTING_ENGINES, ...routing.health(), shadow: shadow.stats() });
});

/**
 * PUT /api/admin/routing/engine  { engine: "ors" | "admins" | "all" }
 * Which engine serves the routes from the next request on, without a restart (D7.2): the way
 * back is `ors`. Written to the settings' log and to the admins' journal. Without Valhalla
 * (VALHALLA_ENABLED off), ORS keeps serving whatever the value: `valhalla.enabled` says so.
 */
adminRouter.put('/routing/engine', (req, res) => {
  const engine = String(req.body?.engine ?? '');
  if (!ROUTING_ENGINES.includes(engine)) return res.status(400).json({ error: `engine = ${ROUTING_ENGINES.join('|')}` });
  const changed = settingsStore.setRoutingEngine(engine, req.actor.name);
  adminAudit.log(req.actor, 'routing.engine', 'setting', 'routingEngine', changed);
  res.json({ routingEngine: settingsStore.routingEngine, changed, valhalla: routing.health().valhalla });
});

/**
 * GET /api/admin/routing/shadow?since=&kind=route|faster&limit= — the shadow mode's measures,
 * newest first (no coordinates, no account): 200 by default, 1000 at most.
 */
adminRouter.get('/routing/shadow', guarded(async (req, res) => {
  const kind = req.query.kind ? String(req.query.kind) : null;
  if (kind && kind !== 'route' && kind !== 'faster') return res.status(400).json({ error: 'kind = route|faster' });
  const n = Number(req.query.limit);
  const limit = Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), 1000) : 200;
  const runs = await shadowStore.runs({ since: moment(req.query.since), kind, limit });
  res.json({ count: runs.length, runs });
}));

/**
 * GET /api/admin/routing/shadow/summary?since= — per kind: comparisons, fallbacks, divergences,
 * and per engine: asked, answered, median and p95 latency, U-turns at the start; the errors by
 * code. What the criteria of D7.3 are read on.
 */
adminRouter.get('/routing/shadow/summary', guarded(async (req, res) => {
  res.json(await shadowStore.summary({ since: moment(req.query.since) }));
}));

/**
 * GET /api/admin/routing/traces?before=&limit= — the divergent routes kept for admin accounts
 * (30 days), newest first, without their lines. `before` = the `at` of the last one received.
 */
adminRouter.get('/routing/traces', guarded(async (req, res) => {
  const limit = pageSize(req.query.limit);
  const traces = await shadowStore.traces({ before: moment(req.query.before), limit });
  res.json({ count: traces.length, next: traces.length === limit ? traces[traces.length - 1].at : null, traces });
}));

/** GET /api/admin/routing/traces/:id — one of them as GeoJSON: one line per engine, for a map. */
adminRouter.get('/routing/traces/:id', guarded(async (req, res) => {
  if (!/^\d{1,18}$/.test(req.params.id)) return res.status(404).json({ error: 'not found' });
  const trace = await shadowStore.trace(req.params.id);
  if (!trace) return res.status(404).json({ error: 'not found' });
  res.json(trace);
}));

/** GET /api/admin/me — who the console is signed in as, to show it and check it is an admin. */
adminRouter.get('/me', (req, res) => {
  res.json({ actor: req.actor });
});

/** The author as the console shows it: pseudo, account, role — or null once the account is gone. */
function withAuthor(report) {
  const { reporterId, ...rest } = report;
  const account = reporterId ? accountStore.get(reporterId) : null;
  return {
    ...rest,
    author: account
      ? { id: account.id, username: account.username ?? account.displayName ?? null, role: account.role, banned: Boolean(account.banned) }
      : null,
  };
}

/** 50 by default, 200 at most. */
function pageSize(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? Math.min(Math.floor(n), 200) : 50;
}

/** A moment given as ISO text or as milliseconds; null when absent or unreadable. */
function moment(value) {
  if (value == null || value === '') return null;
  const n = Number(value);
  const date = Number.isFinite(n) ? new Date(n) : new Date(String(value));
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function parseBox(value) {
  if (!value) return null;
  const [west, south, east, north] = String(value).split(',').map(Number);
  if (![west, south, east, north].every(Number.isFinite) || west >= east || south >= north) return null;
  return { west, south, east, north };
}
