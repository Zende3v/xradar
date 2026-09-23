import { Router } from 'express';
import { authAccount } from '../accounts/auth.js';
import { followerView, ownerView, shareStore } from './shares.js';
import { groupStore, groupView, memberDetail, observerView, positionView, routesFor } from './groups.js';

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

// ---- Trajet en groupe --------------------------------------------------------------------
//
// Up to five drivers, each leaving from their own place, all going to the same address. Everyone
// sees where the others are and how far along they are — but only those who agreed to share it.
// Like the plain share, a group lives in memory and leaves nothing behind.

/**
 * POST /api/trips/group  { toLabel?, destination: {lat, lon}, route? }
 * Opens a group and hands back its joining code. Being in a group replaces the previous one.
 */
tripRouter.post('/group', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const destination = point(req.body?.destination);
  if (!destination) return res.status(400).json({ error: 'destination required' });
  const group = groupStore.open(account, {
    toLabel: req.body?.toLabel,
    destination,
    route: routeOf(req.body?.route),
  });
  res.status(201).json({ group: groupView(group, account.id) });
});

/**
 * POST /api/trips/group/join  { code, route? }
 * Joins the group behind a code: same destination, own start, own route.
 */
tripRouter.post('/group/join', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const result = groupStore.join(account, req.body?.code, { route: routeOf(req.body?.route) });
  if (result.error) return res.status(result.error === 'group full' ? 409 : 404).json({ error: result.error });
  res.json({ group: groupView(result.group, account.id) });
});

/** GET /api/trips/group — my group as it stands, or null. This is what the map reads. */
tripRouter.get('/group', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (group) groupStore.settle(group);
  const view = group ? groupView(group, account.id) : null;
  // A cancelled group is shown once, then forgotten: choosing the same place again starts afresh.
  if (group) groupStore.release(group, account.id);
  res.json({ group: view });
});

/**
 * PATCH /api/trips/group/me  { lat, lon, bearing?, speedKmh?, remainingM?, etaS?, progress?,
 *                              distanceM?, route?, started?, arrived?, sharing?, observable? }
 * Where I am and how far along I am — and whether I still want the others to see it. Answers with
 * the whole group, so one call a tick is enough.
 */
tripRouter.patch('/group/me', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.update(account.id, {
    lat: Number(req.body?.lat),
    lon: Number(req.body?.lon),
    bearing: Number(req.body?.bearing),
    speedKmh: Number(req.body?.speedKmh),
    remainingM: Number(req.body?.remainingM),
    etaS: Number(req.body?.etaS),
    progress: Number(req.body?.progress),
    distanceM: Number(req.body?.distanceM),
    route: routeOf(req.body?.route),
    toLabel: req.body?.toLabel,
    started: req.body?.started,
    arrived: req.body?.arrived,
    sharing: typeof req.body?.sharing === 'boolean' ? req.body.sharing : undefined,
    observable: typeof req.body?.observable === 'boolean' ? req.body.observable : undefined,
  });
  if (!group) return res.status(404).json({ error: 'no group' });
  // lite: the phone listens to the stream, which already says everything; only the end of the
  // trip still comes back here, in case the stream missed it.
  if ((req.query.lite === '1' || req.body?.lite === true) && !group.finishedAt) {
    return res.json({ ok: true, now: Date.now() });
  }
  const view = groupView(group, account.id);
  groupStore.release(group, account.id);
  res.json({ group: view, now: Date.now() });
});

/**
 * GET /api/trips/group/member/:id — one participant in full, for "suivre ce participant": their
 * progress, their route, their speed. Refused when they do not share; nothing else is exposed.
 */
tripRouter.get('/group/member/:id', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  const detail = memberDetail(group, req.params.id);
  if (!detail) return res.status(404).json({ error: 'not in this group' });
  if (detail.sharing === false) return res.status(403).json({ error: 'not sharing' });
  res.json({ member: detail });
});

/**
 * GET /api/trips/group/stream — the group, pushed as it changes (Server-Sent Events).
 *   event: group  the whole group, when its shape changes (join, leave, arrival, sharing…)
 *   event: pos    one member's position, the moment their phone sends it
 * Every event carries `now`, the server's clock, so the phone can place positions in time
 * whatever its own clock says. A comment every 15 s keeps the connection open.
 */
tripRouter.get('/group/stream', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  res.set({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  res.flushHeaders();
  const send = (event, data) => {
    res.write(`event: ${event}\ndata: ${JSON.stringify({ ...data, now: Date.now() })}\n\n`);
  };
  send('group', groupView(group, account.id));
  for (const m of group.members.values()) {
    if (m.accountId !== account.id && m.sharing && m.position && m.state !== 'left') send('pos', positionView(m));
  }
  const stop = groupStore.listen(group, account.id, send, () => res.end());
  const beat = setInterval(() => res.write(`: ${Date.now()}\n\n`), 15_000);
  req.on('close', () => {
    clearInterval(beat);
    stop();
  });
});

/**
 * GET /api/trips/group/routes?known=<id>:<rev>,<id>:<rev>
 * The routes of the other members who share, drawn on the main map. Only those whose version
 * differs from what the phone holds come back: a route travels once per change.
 */
tripRouter.get('/group/routes', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  const known = new Map(
    String(req.query.known ?? '')
      .split(',')
      .map((pair) => pair.split(':'))
      .filter(([id, rev]) => id && Number.isFinite(Number(rev)))
      .map(([id, rev]) => [id, Number(rev)]),
  );
  res.json({ routes: routesFor(group, account.id, known) });
});

/**
 * POST /api/trips/group/leave — I step out. A host leaving hands the role to the next driver;
 * once the trip is over, it only stops showing it to me.
 */
tripRouter.post('/group/leave', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  groupStore.leave(account.id);
  res.json({ left: true });
});

/** DELETE /api/trips/group — the host cancels the trip: the link dies, everyone is told. */
tripRouter.delete('/group', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  if (group.hostId !== account.id) return res.status(403).json({ error: 'host only' });
  groupStore.cancel(group, account.id);
  res.json({ group: groupView(group, account.id) });
});

/** POST /api/trips/group/link — the host opens a link to watch the group; it replaces the old one. */
tripRouter.post('/group/link', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  if (group.hostId !== account.id) return res.status(403).json({ error: 'host only' });
  groupStore.openLink(group);
  res.status(201).json({ group: groupView(group, account.id) });
});

/** DELETE /api/trips/group/link — the link stops working at once, for everyone holding it. */
tripRouter.delete('/group/link', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  if (group.hostId !== account.id) return res.status(403).json({ error: 'host only' });
  groupStore.revokeLink(group);
  res.json({ group: groupView(group, account.id) });
});

/** DELETE /api/trips/group/observers/:id — one watcher is removed; the link keeps working. */
tripRouter.delete('/group/observers/:id', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.forAccount(account.id);
  if (!group) return res.status(404).json({ error: 'no group' });
  if (group.hostId !== account.id) return res.status(403).json({ error: 'host only' });
  groupStore.removeObserver(group, String(req.params.id));
  res.json({ group: groupView(group, account.id) });
});

/**
 * GET /api/trips/group/watch/:token — the group as a watcher sees it: the map, the progress, the
 * routes and the speeds of the members who agreed to be seen. An account is required, and a
 * watcher the host removed is refused.
 */
tripRouter.get('/group/watch/:token', (req, res) => {
  const account = caller(req, res);
  if (!account) return;
  const group = groupStore.byWatchToken(req.params.token);
  if (!group) return res.status(404).json({ error: 'link over' });
  if (group.removedObserverIds.has(account.id)) return res.status(403).json({ error: 'removed' });
  groupStore.settle(group);
  // Counted so the host knows how many watch, and named only so one can be removed.
  if (!group.members.has(account.id)) group.observerIds.add(account.id);
  res.json({ group: observerView(group) });
});

function point(value) {
  const lat = Number(value?.lat);
  const lon = Number(value?.lon);
  return Number.isFinite(lat) && Number.isFinite(lon) ? { lat, lon } : null;
}

/** A route as [[lon, lat], …], or null. */
function routeOf(value) {
  return Array.isArray(value) && value.length >= 2 ? value : null;
}
