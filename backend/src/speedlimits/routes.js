import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { adminActor, authAccount, isAdminRequest } from '../accounts/auth.js';
import { adminAudit } from '../admin/audit.js';
import { speedLimitStore } from './store.js';

export const speedLimitRouter = Router();

const ROLE_RANK = { guest: 0, client: 1, admin: 2 };

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[speed-limits]', e.message);
    res.status(503).json({ error: 'speed limits unavailable' });
  }
};

/**
 * POST /api/speed-limits/reports
 *   { lat, lon, newKmh, bearing?, displayedKmh?, displayedSource?, deviceId? }
 * A driver proposes the limit the sign shows. Same gate as road reports; an identity
 * (account token or device) is required, since each person has a single voice per change.
 */
speedLimitRouter.post('/reports', guarded(async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  if (!accountStore.accessFor(account).canNavigate) {
    return res.status(403).json({ error: 'subscription required' });
  }
  const role = account.role ?? 'guest';
  if ((ROLE_RANK[role] ?? 0) < (ROLE_RANK[config.speedLimitMinRole] ?? 0)) {
    return res.status(403).json({ error: `requires role ${config.speedLimitMinRole}` });
  }

  const result = await speedLimitStore.report({
    lat: Number(req.body?.lat),
    lon: Number(req.body?.lon),
    newKmh: Number(req.body?.newKmh),
    bearing: optionalNumber(req.body?.bearing),
    displayedKmh: optionalNumber(req.body?.displayedKmh),
    displayedSource: req.body?.displayedSource === 'radar' ? 'radar' : 'map',
    reporterId: account.id,
    reporterRole: role,
  });
  if (result.error) return res.status(400).json({ error: result.error });
  if (result.firstVoice) accountStore.recordReportStat(account.id, 'reportsDeclared');
  res.status(result.change ? 201 : 200).json({ change: result.change });
}));

/** GET /api/speed-limits/near?lat=..&lon=..&radius=.. — pending and validated changes around a point. */
speedLimitRouter.get('/near', guarded(async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = Math.max(1, Math.min(
    Number(req.query.radius) || config.speedLimitDefaultNearRadiusM,
    config.speedLimitMaxNearRadiusM,
  ));
  const changes = await speedLimitStore.near(lat, lon, radius);
  res.json({ count: changes.length, radiusM: radius, changes });
}));

/** GET /api/speed-limits/:id — one change with its voices, events and zone (admins only). */
speedLimitRouter.get('/:id', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  if (!UUID.test(req.params.id)) return res.status(404).json({ error: 'not found' });
  const change = await speedLimitStore.history(req.params.id);
  if (!change) return res.status(404).json({ error: 'not found' });
  res.json({ change });
}));

/** DELETE /api/speed-limits/:id — moderation: stop applying / reject a change (admins only). */
speedLimitRouter.delete('/:id', guarded(async (req, res) => {
  const actor = adminActor(req);
  if (!actor) return res.status(403).json({ error: 'admin only' });
  if (!UUID.test(req.params.id)) return res.status(404).json({ error: 'not found' });
  const change = await speedLimitStore.remove(req.params.id);
  if (!change) return res.status(404).json({ error: 'not found' });
  adminAudit.log(actor, 'speedLimit.remove', 'speedLimit', req.params.id);
  res.json({ change });
}));

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function optionalNumber(value) {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}
