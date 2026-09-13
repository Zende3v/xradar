import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { authAccount, isAdminRequest } from '../accounts/auth.js';
import { reportStore } from './store.js';

export const reportRouter = Router();

/** DELETE /api/reports/:id — moderation, admins only (account or ADMIN_TOKEN). */
reportRouter.delete('/:id', (req, res) => {
  if (!isAdminRequest(req)) {
    return res.status(403).json({ error: 'admin only' });
  }
  const removed = reportStore.removeById(req.params.id);
  if (!removed) return res.status(404).json({ error: 'not found' });
  res.json({ removed: true, id: req.params.id });
});

const ROLE_RANK = { guest: 0, client: 1, admin: 2 };

/**
 * POST /api/reports  { type, lat, lon, deviceId?, plate?, street?, side? }
 * Create a crowdsourced report. Role-gated per type: voiture_radar = admin,
 * camera = client+, the rest = everyone. An admin's report is fully trusted.
 */
reportRouter.post('/', (req, res) => {
  const { type } = req.body || {};
  const lat = Number(req.body?.lat);
  const lon = Number(req.body?.lon);
  const account = authAccount(req); // Bearer token (preferred) or deviceId fallback
  if (account?.banned) return res.status(403).json({ error: 'banned' });
  if (account && !accountStore.accessFor(account).canNavigate) {
    return res.status(403).json({ error: 'subscription required' });
  }

  const minRole = config.reportMinRole[type];
  if (!minRole) return res.status(400).json({ error: 'unknown report type' });
  const role = account?.role ?? 'guest';
  if ((ROLE_RANK[role] ?? 0) < (ROLE_RANK[minRole] ?? 0)) {
    return res.status(403).json({ error: `type "${type}" requires role ${minRole}` });
  }

  const plate = req.body?.plate ? String(req.body.plate).trim() : null;
  const street = req.body?.street ? String(req.body.street).trim() : null;
  const side = req.body?.side === 'left' || req.body?.side === 'right' ? req.body.side : null;
  // Which way the reporter was facing, and their course — both optional.
  const direction = req.body?.direction === 'opposite' ? 'opposite' : 'same';
  const bearing = Number(req.body?.bearing);

  const report = reportStore.add({
    type,
    lat,
    lon,
    reporterId: account?.id ?? null,
    reporterRole: role,
    plate,
    street,
    side,
    direction,
    bearing: Number.isFinite(bearing) ? bearing : null,
  });
  if (!report) {
    return res.status(400).json({ error: 'type (valid), lat and lon are required' });
  }
  if (account) accountStore.recordReportStat(account.id, 'reportsDeclared');
  const { plate: _p, reporterId: _r, ...pub } = report; // never echo the plate or author
  res.status(201).json({ report: pub });
});

/**
 * GET /api/reports/near?lat=..&lon=..&radius=..
 * Active reports around the driver, nearest first.
 */
reportRouter.get('/near', (req, res) => {
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
  const reports = reportStore.near(lat, lon, radius, config.maxResults);
  const zones = reportStore.zonesNear(lat, lon, radius);
  res.json({ count: reports.length, radiusM: radius, reports, zones });
});

/** POST /api/reports/:id/confirm — "toujours là". */
reportRouter.post('/:id/confirm', (req, res) => {
  const r = reportStore.confirm(req.params.id);
  if (!r) return res.status(404).json({ error: 'report not found' });
  // The first confirmation is what makes it a "really confirmed" report for its author.
  if (r.confirmations === 1 && r.reporterId) accountStore.recordReportStat(r.reporterId, 'reportsConfirmed');
  const { plate: _p, reporterId: _r, ...pub } = r;
  res.json({ report: pub });
});

/** POST /api/reports/:id/deny — "plus là". */
reportRouter.post('/:id/deny', (req, res) => {
  const r = reportStore.deny(req.params.id);
  if (!r) return res.status(404).json({ error: 'report not found' });
  res.json(r.removed ? { removed: true, id: r.id } : { report: r });
});

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
