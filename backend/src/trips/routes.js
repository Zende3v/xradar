import { Router } from 'express';
import { authAccount } from '../accounts/auth.js';
import { followerView, ownerView, shareStore } from './shares.js';

export const tripRouter = Router();

/** The signed-in account, or the refusal already sent. */
function caller(req, res) {
  const account = authAccount(req);
  if (!account) {
    res.status(401).json({ error: 'account required' });
    return null;
  }
  if (account.banned) {
    res.status(403).json({ error: 'banned' });
    return null;
  }
  return account;
}

/**
 * POST /api/trips/share  { toLabel?, destination?: {lat, lon}, route?: [[lon,lat], …] }
 * Opens a link on the trip being driven. A driver has one live share at a time: opening again
 * replaces the previous link, which stops working at once.
 */
tripRouter.post('/share', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const share = shareStore.open(account, {
    toLabel: req.body?.toLabel,
    destination: point(req.body?.destination),
    route: Array.isArray(req.body?.route) ? req.body.route : null,
  });
  res.status(201).json({ share: ownerView(share) });
});

/**
 * PATCH /api/trips/share  { lat, lon, bearing?, remainingM?, etaS?, route?, arrived? }
 * The driver's app, as it drives. Nothing is kept: the share holds the last position only.
 */
tripRouter.patch('/share', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const share = shareStore.update(account.id, {
    lat: Number(req.body?.lat),
    lon: Number(req.body?.lon),
    bearing: Number(req.body?.bearing),
    remainingM: Number(req.body?.remainingM),
    etaS: Number(req.body?.etaS),
    route: req.body?.route,
    toLabel: req.body?.toLabel,
    destination: point(req.body?.destination),
    arrived: req.body?.arrived,
  });
  if (!share) return res.status(404).json({ error: 'no live share' });
  res.json({ share: ownerView(share) });
});

/** GET /api/trips/share — the driver's own live link, if there is one. */
tripRouter.get('/share', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const share = shareStore.forAccount(account.id);
  res.json({ share: share ? ownerView(share) : null });
});

/** DELETE /api/trips/share — stop sharing now; the link dies with it. */
tripRouter.delete('/share', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  shareStore.close(account.id);
  res.json({ closed: true });
});

/**
 * GET /api/trips/shared/:token — what the follower sees, refreshed as they watch.
 * An account is required: a position never leaves the app, and a lost link is not a public map.
 */
tripRouter.get('/shared/:token', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const share = shareStore.get(req.params.token);
  if (!share) return res.status(404).json({ error: 'share over' });
  // Counted, not identified: the driver sees how many people follow, never who.
  if (share.accountId !== account.id) share.followerIds.add(account.id);
  res.json({ share: followerView(share) });
});

function point(value) {
  const lat = Number(value?.lat);
  const lon = Number(value?.lon);
  return Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
}
