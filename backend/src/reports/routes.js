import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { authAccount, isAdminRequest } from '../accounts/auth.js';
import { probeStore } from '../traffic/probes.js';
import { reportStore } from './store.js';

export const reportRouter = Router();

const ROLE_RANK = { guest: 0, client: 1, admin: 2 };
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[reports]', e.message);
    res.status(503).json({ error: 'reports unavailable' });
  }
};

/** DELETE /api/reports/:id — moderation, admins only (account or ADMIN_TOKEN). */
reportRouter.delete('/:id', guarded(async (req, res) => {
  if (!isAdminRequest(req)) {
    return res.status(403).json({ error: 'admin only' });
  }
  const removed = UUID.test(req.params.id) && await reportStore.removeById(req.params.id);
  if (!removed) return res.status(404).json({ error: 'not found' });
  res.json({ removed: true, id: req.params.id });
}));

/**
 * POST /api/reports  { type, lat, lon, deviceId?, plate?, street?, side?, direction?, bearing?, prompted? }
 * Report an event. Role-gated per type: voiture_radar = client, camera = admin, the rest =
 * everyone. The same event already reported close by is not duplicated: the report joins it
 * as one more voice (200, merged: true) instead of creating a new one (201). A "Bouchon" sent
 * as "Oui" to "Ralentissement du trafic ?" (`prompted`, the driver's own probe just nearby)
 * does not use up a guest's reports of the day.
 */
reportRouter.post('/', guarded(async (req, res) => {
  const { type } = req.body || {};
  const lat = Number(req.body?.lat);
  const lon = Number(req.body?.lon);
  const account = authAccount(req); // Bearer token (preferred) or deviceId fallback
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  if (!accountStore.accessFor(account).canNavigate) {
    return res.status(403).json({ error: 'subscription required' });
  }

  const minRole = config.reportMinRole[type];
  if (!minRole) return res.status(400).json({ error: 'unknown report type' });
  const role = account.role ?? 'guest';
  if ((ROLE_RANK[role] ?? 0) < (ROLE_RANK[minRole] ?? 0)) {
    return res.status(403).json({ error: `type "${type}" requires role ${minRole}` });
  }
  const prompted = type === 'traffic_jam' && req.body?.prompted === true && probeStore.sentNear(account.id, lat, lon);
  if (!prompted && !accountStore.canReport(account)) {
    return res.status(429).json({ error: 'daily report limit', limit: config.guestReportsPerDay });
  }

  const bearing = Number(req.body?.bearing);
  const result = await reportStore.add({
    type,
    lat,
    lon,
    reporterId: account.id,
    reporterRole: role,
    plate: req.body?.plate ? String(req.body.plate).trim() : null,
    street: req.body?.street ? String(req.body.street).trim() : null,
    side: req.body?.side === 'left' || req.body?.side === 'right' ? req.body.side : null,
    // "Embouteillage": how bad it is, which weighs on the routing around it.
    severity: ['light', 'heavy', 'standstill'].includes(req.body?.severity) ? req.body.severity : null,
    // Which way the reporter was facing, and their course — both optional.
    direction: req.body?.direction === 'opposite' ? 'opposite' : 'same',
    bearing: req.body?.bearing != null && Number.isFinite(bearing) ? bearing : null,
  });
  if (!result) {
    return res.status(400).json({ error: 'type (valid), lat and lon are required' });
  }
  accountStore.recordReportStat(account.id, 'reportsDeclared');
  if (!prompted) accountStore.countReport(account);
  // The first confirmation is what makes it a "really confirmed" report for its author.
  if (result.authorFirstConfirmed) accountStore.recordReportStat(result.authorFirstConfirmed, 'reportsConfirmed');
  res.status(result.merged ? 200 : 201).json({ report: result.report, merged: result.merged });
}));

/**
 * GET /api/reports/near?lat=..&lon=..&radius=..
 * Live reports around the driver, nearest first, and the radar-car zones.
 */
reportRouter.get('/near', guarded(async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = clamp(
    Number(req.query.radius) || config.reportDefaultNearRadiusM,
    1,
    config.reportMaxNearRadiusM,
  );
  const [reports, zones] = await Promise.all([
    reportStore.near(lat, lon, radius, config.maxResults),
    reportStore.zonesNear(lat, lon, radius),
  ]);
  res.json({ count: reports.length, radiusM: radius, reports, zones });
}));

/** POST /api/reports/:id/confirm — "toujours là". One voice per person: an identity is required. */
reportRouter.post('/:id/confirm', guarded(async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (!UUID.test(req.params.id)) return res.status(404).json({ error: 'report not found' });
  const r = await reportStore.confirm(req.params.id, account.id);
  if (!r) return res.status(404).json({ error: 'report not found' });
  if (r.authorFirstConfirmed) accountStore.recordReportStat(r.authorFirstConfirmed, 'reportsConfirmed');
  res.json({ report: r.report });
}));

/** POST /api/reports/:id/deny — "plus là". One voice per person: an identity is required. */
reportRouter.post('/:id/deny', guarded(async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (!UUID.test(req.params.id)) return res.status(404).json({ error: 'report not found' });
  const r = await reportStore.deny(req.params.id, account.id);
  if (!r) return res.status(404).json({ error: 'report not found' });
  res.json(r.removed ? { removed: true, id: r.id } : { report: r.report });
}));

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
