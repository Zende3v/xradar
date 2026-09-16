import { Router } from 'express';
import { authAccount } from '../accounts/auth.js';
import { liveStore } from './store.js';

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

/**
 * POST /api/live/presence  { inTrip }
 * The app is open (it says so about every 30 s), and whether a trip is running. Counted only.
 */
liveRouter.post('/presence', (req, res) => {
  const account = presentAccount(req, res);
  if (!account) return;
  liveStore.touch(account.id, req.body?.inTrip === true);
  res.json({ ok: true });
});

/**
 * Apps from before presence: sharing a position still counts them as open (the position is
 * ignored), and nobody is shown around them any more.
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
