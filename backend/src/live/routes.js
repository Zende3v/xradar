import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { liveStore } from './store.js';

export const liveRouter = Router();

/**
 * POST /api/live/position  { lat, lon, bearing?, speedKmh?, visible }
 * Share (or, when invisible, withdraw) the driver's live position.
 */
liveRouter.post('/position', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const visible = req.body?.visible !== false; // visible by default
  if (!visible) {
    liveStore.remove(account.id);
    return res.json({ ok: true, visible: false });
  }
  liveStore.set(account.id, {
    lat: Number(req.body?.lat),
    lon: Number(req.body?.lon),
    bearing: req.body?.bearing != null ? Number(req.body.bearing) : null,
    speedKmh: req.body?.speedKmh != null ? Number(req.body.speedKmh) : null,
    username: account.username ?? account.displayName,
    avatarUrl: account.avatarUrl ?? null,
  });
  res.json({ ok: true, visible: true });
});

/** GET /api/live/near?lat=..&lon=..&radius=.. — other live drivers nearby. */
liveRouter.get('/near', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = Math.max(1, Math.min(Number(req.query.radius) || config.liveDefaultRadiusM, config.liveMaxRadiusM));
  const users = liveStore.near(account.id, lat, lon, radius);
  res.json({ count: users.length, radiusM: radius, users });
});
