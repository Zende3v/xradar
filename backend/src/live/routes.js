import { Router } from 'express';
import { config } from '../config.js';
import { authAccount, isAdminRequest } from '../accounts/auth.js';
import { accountStore } from '../accounts/store.js';
import { liveStore } from './store.js';
import { positionStore } from './positions.js';

export const liveRouter = Router();

/** The signed-in account, or the refusal already sent. */
function presentAccount(req, res) {
  const account = authAccount(req);
  if (!account) {
    res.status(401).json({ error: 'unauthorized' });
    return null;
  }
  if (account.banned) {
    res.status(403).json({ error: 'banned' });
    return null;
  }
  return account;
}

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[live]', e.message);
    res.status(503).json({ error: 'live unavailable' });
  }
};

/**
 * POST /api/live/presence  { inTrip, lat?, lon?, speedKmh?, session? }
 * The app is open (it says so about every 30 s) and whether a trip is running. What it sends
 * follows the driver's privacy switches: a position only with "Ma présence et ma position",
 * `session: true` only with "Temps d'utilisation". Nothing is guessed here.
 */
liveRouter.post('/presence', guarded(async (req, res) => {
  const account = presentAccount(req, res);
  if (!account) return;
  const inTrip = req.body?.inTrip === true;
  liveStore.touch(account.id, inTrip);
  accountStore.recordActivity(account.id, req.body?.session === true);
  const lat = Number(req.body?.lat);
  const lon = Number(req.body?.lon);
  if (Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) {
    await positionStore.add(account.id, { lat, lon, speedKmh: Number(req.body?.speedKmh), inTrip });
  }
  res.json({ ok: true });
}));

/**
 * Apps from before presence: sharing a position still counts them as open (the position is
 * ignored, they never asked the driver), and nobody is shown around them any more.
 */
liveRouter.post('/position', (req, res) => {
  const account = presentAccount(req, res);
  if (!account) return;
  liveStore.touch(account.id, false);
  res.json({ ok: true, visible: false });
});

liveRouter.get('/near', (req, res) => {
  if (!presentAccount(req, res)) return;
  res.json({ count: 0, users: [] });
});

/**
 * GET /api/live/online (admins) — who has the app open now: the account, whether a trip is
 * running, and its last position for those who share it.
 */
liveRouter.get('/online', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const online = liveStore.entries();
  const points = new Map((await positionStore.live()).map((p) => [p.accountId, p]));
  const users = online.map(([id, seen]) => {
    const account = accountStore.get(id);
    const point = points.get(id);
    return {
      accountId: id,
      username: account?.username ?? null,
      displayName: account?.displayName ?? null,
      role: account?.role ?? null,
      platform: account?.platform ?? null,
      inTrip: seen.inTrip,
      lastSeenAt: new Date(seen.at).toISOString(),
      // Null unless the driver shares their position.
      lat: point?.lat ?? null,
      lon: point?.lon ?? null,
      speedKmh: point?.speedKmh ?? null,
      positionAt: point?.at ?? null,
    };
  });
  res.json({ count: users.length, inTrip: users.filter((u) => u.inTrip).length, users });
}));

/**
 * GET /api/live/positions?accountId=..&from=..&to=..&limit=.. (admins)
 * Where one account went, oldest first. Positions are kept config.positionKeepDays days.
 */
liveRouter.get('/positions', guarded(async (req, res) => {
  if (!isAdminRequest(req)) return res.status(403).json({ error: 'admin only' });
  const accountId = String(req.query.accountId || '');
  if (!accountId) return res.status(400).json({ error: 'accountId required' });
  const points = await positionStore.trace(accountId, {
    from: req.query.from ? new Date(String(req.query.from)) : null,
    to: req.query.to ? new Date(String(req.query.to)) : null,
    limit: req.query.limit,
  });
  res.json({ count: points.length, keepDays: config.positionKeepDays, points });
}));
