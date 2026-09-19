import { randomUUID } from 'node:crypto';
import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { authAccount, isAdminRequest } from '../accounts/auth.js';
import { db } from '../db.js';

export const bugRouter = Router();

const CATEGORIES = new Set(['map', 'navigation', 'alerts', 'account', 'other']);
const STATUSES = new Set(['new', 'progress', 'resolved']);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[bugs]', e.message);
    res.status(503).json({ error: 'bug reports unavailable' });
  }
};

/** Trimmed text, cut at [max]; null when empty. */
const clean = (value, max) => {
  const text = String(value ?? '').trim().replace(/\s+\n/g, '\n');
  return text ? text.slice(0, max) : null;
};

/**
 * POST /api/bugs  { category, description, steps?, app: { platform, version, os, model } }  (Bearer)
 * "Signaler un bug", from any account (a guest's too): the author is the account, the rest is
 * what the app knows of itself. Flood limits from the table itself (no memory kept): a few per
 * account and hour and day, a cap for everyone per day; the same text again within a day is
 * the same report.
 */
bugRouter.post('/', guarded(async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const category = CATEGORIES.has(req.body?.category) ? req.body.category : 'other';
  const description = clean(req.body?.description, config.bugTextMax);
  if (!description || description.length < config.bugTextMin) return res.status(400).json({ error: 'description required' });
  const steps = clean(req.body?.steps, config.bugTextMax);
  const app = req.body?.app ?? {};

  const { rows: [use] } = await db.query(
    `SELECT count(*) FILTER (WHERE account_id = $1 AND created_at > now() - interval '1 hour')::int AS hour,
            count(*) FILTER (WHERE account_id = $1)::int AS day,
            count(*)::int AS everyone,
            bool_or(account_id = $1 AND description = $2) AS again
     FROM crowd.bug_report WHERE created_at > now() - interval '1 day'`,
    [account.id, description],
  );
  if (use.again) return res.json({ ok: true, duplicate: true });
  if (use.hour >= config.bugPerHour || use.day >= config.bugPerDay || use.everyone >= config.bugPerDayAll) {
    return res.status(429).json({ error: 'too many bug reports' });
  }
  await db.query(
    `INSERT INTO crowd.bug_report (id, category, description, steps, account_id, platform, app_version, os_version, device_model)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
    [randomUUID(), category, description, steps, account.id,
      clean(app.platform, 16), clean(app.version, 32), clean(app.os, 32), clean(app.model, 64)],
  );
  // Resolved reports go after a while: the table stays small without any timer.
  await db.query(
    `DELETE FROM crowd.bug_report WHERE status = 'resolved' AND updated_at < now() - make_interval(days => $1)`,
    [config.bugResolvedDays],
  );
  res.status(201).json({ ok: true });
}));

/**
 * GET /api/bugs?status=new|progress|resolved|all&before=<ISO>  (admins)
 * The most recent reports first, a page at a time (older ones with [before]), with the author's
 * pseudo and role when the account still exists.
 */
bugRouter.get('/', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const status = STATUSES.has(req.query.status) ? req.query.status : null;
  const before = Date.parse(String(req.query.before ?? ''));
  const { rows } = await db.query(
    `SELECT id, status, category, description, steps, account_id, platform, app_version, os_version, device_model,
            (extract(epoch FROM created_at) * 1000)::bigint AS created_at
     FROM crowd.bug_report
     WHERE ($1::text IS NULL OR status = $1) AND ($2::float8 IS NULL OR created_at < to_timestamp($2 / 1000.0))
     ORDER BY created_at DESC LIMIT $3`,
    [status, Number.isFinite(before) ? before : null, config.bugPage],
  );
  res.json({
    reports: rows.map((row) => {
      const author = row.account_id ? accountStore.get(row.account_id) : null;
      return {
        id: row.id,
        status: row.status,
        category: row.category,
        description: row.description,
        steps: row.steps,
        createdAt: new Date(Number(row.created_at)).toISOString(),
        author: author ? { username: author.username ?? null, role: author.role } : null,
        app: { platform: row.platform, version: row.app_version, os: row.os_version, model: row.device_model },
      };
    }),
  });
}));

/** PATCH /api/bugs/:id  { status }  (admins): Nouveau → En cours → Résolu. */
bugRouter.patch('/:id', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const status = req.body?.status;
  if (!UUID.test(req.params.id) || !STATUSES.has(status)) return res.status(400).json({ error: 'id and status required' });
  const { rowCount } = await db.query(
    'UPDATE crowd.bug_report SET status = $2, updated_at = now() WHERE id = $1',
    [req.params.id, status],
  );
  if (!rowCount) return res.status(404).json({ error: 'not found' });
  res.json({ ok: true, status });
}));
