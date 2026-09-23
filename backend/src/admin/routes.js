import { Router } from 'express';
import { adminActor } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { reportStore } from '../reports/store.js';
import { adminAudit } from './audit.js';

/**
 * The console's own routes (`/api/admin`): every report with its author, and the journal of
 * what admins did. An admin account signed in with its session, or the ADMIN_TOKEN — nothing
 * else gets in.
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
