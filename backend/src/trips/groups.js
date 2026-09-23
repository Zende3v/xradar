import { randomBytes, randomUUID } from 'node:crypto';
import { config } from '../config.js';

/**
 * Trip in a group: up to config.groupMaxMembers drivers head for one destination, each from their
 * own start and along their own route. Everyone on the same map, with everyone's progress.
 *
 * It lives beside the plain share (shares.js) and follows the same rules: memory only, nothing
 * written to disk, and it dies a quarter of an hour after the last arrival.
 *
 * What a member exposes is theirs to decide. `sharing` off and nobody sees their position, their
 * speed or their progress — they are still in the group, marked as not sharing. `observable` off
 * and they stay out of the public link while their group still sees them.
 *
 * Nothing is kept about where a driver went: a member holds their last position, and their route
 * only while the trip runs. When the group ends, only the ranking remains — names, times,
 * distances — and even that goes with the group.
 */

/** Letters and digits that cannot be read for one another (no O/0, no I/1). */
const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

class GroupStore {
  constructor() {
    this.byId = new Map(); // group id -> group
    this.byCode = new Map(); // joining code -> group id
    this.byAccount = new Map(); // account id -> group id (one group at a time)
    this.byWatch = new Map(); // observer token -> group id
    // Phones listening to a group (the stream): every change is pushed to them at once.
    this.listeners = new Map(); // group id -> Set of { accountId, send, end }
  }

  /**
   * A member's phone listens to its group. [send](event, data) writes to its stream, [end]
   * closes it. Returns what stops listening.
   */
  listen(group, accountId, send, end) {
    let set = this.listeners.get(group.id);
    if (!set) this.listeners.set(group.id, (set = new Set()));
    const entry = { accountId, send, end };
    set.add(entry);
    return () => {
      set.delete(entry);
      if (!set.size && this.listeners.get(group.id) === set) this.listeners.delete(group.id);
    };
  }

  /**
   * The group changed shape — someone joined, left, arrived, stopped sharing, the lead moved,
   * a route changed, the trip ended: each listener gets the group as they see it. Positions
   * alone do not come through here (see pushPosition), and nothing is pushed twice.
   */
  notify(group) {
    const shape = JSON.stringify([
      group.hostId, group.finishedAt, group.cancelledAt, group.watch?.token ?? null, group.observerIds.size,
      [...group.members.values()].map((m) => [m.accountId, m.state, m.sharing, m.observable, m.routeRev, m.rank]),
    ]);
    if (shape === group.shape) return;
    group.shape = shape;
    for (const listener of [...(this.listeners.get(group.id) ?? [])]) {
      listener.send('group', groupView(group, listener.accountId));
      // A cancelled group is told once, then it is theirs no more.
      this.release(group, listener.accountId);
    }
  }

  /** One member moved: the others hear it at once — only if they share. */
  pushPosition(group, m) {
    if (!m.sharing || !m.position || m.state === 'left') return;
    const data = positionView(m);
    for (const listener of this.listeners.get(group.id) ?? []) {
      if (listener.accountId !== m.accountId) listener.send('pos', data);
    }
  }

  /** Opens a group led by [account], heading for [destination]; the host is its first member. */
  open(account, { toLabel, destination, route }) {
    this.leave(account.id);
    const now = Date.now();
    const group = {
      id: randomUUID(),
      code: this.freeCode(),
      hostId: account.id,
      openedAt: now,
      endsAt: now + config.groupMaxMs,
      toLabel: toLabel ?? null,
      destination: destination ?? null,
      members: new Map(),
      // The public link, opened by the host and revocable; observers are named only to be removed.
      watch: null,
      observerIds: new Set(),
      removedObserverIds: new Set(),
      // Set when the group is over; the ranking is frozen with it and never moves again.
      finishedAt: null,
      // Set when the host cancelled rather than everyone arriving: no ranking to show then.
      cancelledAt: null,
      finalRanking: null,
      publicRanking: null,
    };
    group.members.set(account.id, member(account, { route }));
    this.byId.set(group.id, group);
    this.byCode.set(group.code, group.id);
    this.byAccount.set(account.id, group.id);
    return group;
  }

  /** [account] joins the group behind [code]; an error when it is full, over, or unknown. */
  join(account, code, { route } = {}) {
    const group = this.byJoiningCode(code);
    if (!group) return { error: 'group not found' };
    if (group.finishedAt) return { error: 'group over' };
    const known = group.members.get(account.id);
    if (known && known.state !== 'left') {
      this.byAccount.set(account.id, group.id);
      return { group };
    }
    // Those who left give their place back.
    const seated = [...group.members.values()].filter((m) => m.state !== 'left').length;
    if (!known && seated >= config.groupMaxMembers) return { error: 'group full' };
    if (this.byAccount.get(account.id) !== group.id) this.leave(account.id);
    // Someone who left and comes back starts over: nothing of the first attempt is kept.
    group.members.set(account.id, member(account, { route }));
    this.byAccount.set(account.id, group.id);
    this.notify(group);
    return { group };
  }

  /** The live group behind an id, or null when it never existed or is over. */
  get(id) {
    if (!id) return null;
    const group = this.byId.get(id);
    if (!group) return null;
    if (this.expired(group)) {
      this.forget(group);
      return null;
    }
    return group;
  }

  byJoiningCode(code) {
    const clean = String(code ?? '').trim().toUpperCase();
    return clean ? this.get(this.byCode.get(clean)) : null;
  }

  /** The group [accountId] drives in, if any. */
  forAccount(accountId) {
    const group = this.get(this.byAccount.get(accountId));
    if (!group) {
      this.byAccount.delete(accountId);
      return null;
    }
    return group;
  }

  /** The group behind an observer's token; null once the link is revoked or the group is over. */
  byWatchToken(token) {
    if (!token) return null;
    const group = this.get(this.byWatch.get(token));
    if (!group || group.watch?.token !== token) return null;
    return group;
  }

  /**
   * What a member's app sends as it drives. Only the fields it sends move; a field left out keeps
   * its value, so a phone with no fix still says it is there.
   */
  update(accountId, fields) {
    const group = this.forAccount(accountId);
    if (!group) return null;
    const me = group.members.get(accountId);
    if (!me) return null;
    const now = Date.now();
    me.lastSeenAt = now;

    if (typeof fields.sharing === 'boolean') me.sharing = fields.sharing;
    if (typeof fields.observable === 'boolean') me.observable = fields.observable;
    // Over, arrived or gone: the phone may still ask how the others do, but nothing of where it
    // is gets written any more.
    if (group.finishedAt || me.state === 'arrived' || me.state === 'left') {
      this.settle(group);
      this.notify(group);
      return group;
    }
    if (fields.toLabel) me.toLabel = String(fields.toLabel).slice(0, 160);
    if (Array.isArray(fields.route) && fields.route.length >= 2) {
      me.route = simplify(fields.route);
      me.routeRev += 1;
    }
    const moved = Number.isFinite(fields.lat) && Number.isFinite(fields.lon);
    if (moved) {
      me.position = {
        lat: fields.lat,
        lon: fields.lon,
        bearing: Number.isFinite(fields.bearing) ? fields.bearing : null,
        at: now,
      };
      // Moving means the trip has begun: an invited driver becomes a driving one.
      if (me.state === 'invited') {
        me.state = 'driving';
        me.startedAt = now;
      }
    }
    // "En route" is a state the others may know even when the driver does not share a position.
    if (fields.started === true && me.state === 'invited') {
      me.state = 'driving';
      me.startedAt = now;
    }
    if (Number.isFinite(fields.speedKmh)) me.speedKmh = Math.max(0, Math.round(fields.speedKmh));
    if (Number.isFinite(fields.remainingM)) me.remainingM = Math.max(0, Math.round(fields.remainingM));
    if (Number.isFinite(fields.etaS)) me.etaAt = now + Math.max(0, Math.round(fields.etaS)) * 1000;
    if (Number.isFinite(fields.progress)) me.progress = Math.min(1, Math.max(0, fields.progress));
    if (Number.isFinite(fields.distanceM)) me.distanceM = Math.max(0, Math.round(fields.distanceM));

    if (fields.arrived === true && me.state !== 'arrived') this.arrive(group, me);
    this.settle(group);
    if (moved) this.pushPosition(group, me);
    this.notify(group);
    return group;
  }

  /** A member reached the destination: their rank is taken, in the order they arrive. */
  arrive(group, me) {
    const now = Date.now();
    me.state = 'arrived';
    me.arrivedAt = now;
    me.progress = 1;
    me.remainingM = 0;
    me.durationS = me.startedAt ? Math.round((now - me.startedAt) / 1000) : null;
    // The rank is the number of drivers already in, plus this one. It never moves afterwards.
    me.rank = [...group.members.values()].filter((m) => m.state === 'arrived').length;
    // The route is not kept once the driver is there: no replay of where they went.
    me.route = null;
    me.routeRev += 1;
  }

  /**
   * A member steps out. The group goes on without them: when the host leaves, the role passes to
   * whoever joined next and is still on their way. Only a host left alone ends it.
   *
   * Once the group is over, leaving only means "stop showing it to me": nothing else moves.
   */
  leave(accountId) {
    const group = this.forAccount(accountId);
    if (!group) return null;
    this.byAccount.delete(accountId);
    if (group.finishedAt) return group;
    const me = group.members.get(accountId);
    if (me && me.state !== 'arrived') {
      me.state = 'left';
      me.leftAt = Date.now();
      me.position = null;
      me.route = null;
      me.speedKmh = null;
      me.routeRev += 1;
    }
    if (group.hostId === accountId) {
      const next = [...group.members.values()].find(
        (m) => m.accountId !== accountId && (m.state === 'invited' || m.state === 'driving'),
      );
      if (next) {
        group.hostId = next.accountId;
      } else if (![...group.members.values()].some((m) => m.state === 'arrived')) {
        // Nobody else, nobody arrived: there is no trip left to share.
        this.close(group, { cancelled: true });
        return group;
      }
    }
    this.settle(group);
    this.notify(group);
    return group;
  }

  /** The host cancels the trip for everyone. Each member is told once, then it is gone. */
  cancel(group, accountId) {
    this.close(group, { cancelled: true });
    if (this.byAccount.get(accountId) === group.id) this.byAccount.delete(accountId);
  }

  /**
   * After a cancelled group has been shown to a member, it is theirs no more: the next call finds
   * nothing, and the same destination chosen again starts from scratch.
   */
  release(group, accountId) {
    if (group.cancelledAt && this.byAccount.get(accountId) === group.id) this.byAccount.delete(accountId);
  }

  /** The trip is over — everyone arrived, or it was cancelled. The link dies with it. */
  close(group, { cancelled = false } = {}) {
    if (group.finishedAt) return;
    if (cancelled) group.cancelledAt = Date.now();
    group.finishedAt = Date.now();
    group.endsAt = Math.min(group.endsAt, Date.now() + config.groupAfterFinishMs);
    // The ranking is taken here, once, and never moves again — not even if a phone goes silent
    // afterwards and its owner is dropped from the group.
    group.finalRanking = ranking(group);
    group.publicRanking = ranking(group, [...group.members.values()].filter((m) => m.sharing && m.observable));
    this.revokeLink(group);
    this.notify(group);
  }

  /**
   * Ends the group on its own once nobody is still driving — everyone arrived or left — and drops
   * the members whose phone has been silent for too long.
   */
  settle(group) {
    if (group.finishedAt) return;
    const now = Date.now();
    const arrived = [...group.members.values()].some((m) => m.state === 'arrived');
    for (const [id, m] of group.members) {
      const silent = now - m.lastSeenAt > config.groupDropMs;
      // A driver on the way who went silent for good is dropped. One who never left home stays
      // listed as "pas encore parti" — until somebody arrives and the trip has to end.
      const gone = silent && (m.state === 'driving' || (m.state === 'invited' && arrived));
      if (gone) {
        group.members.delete(id);
        if (this.byAccount.get(id) === group.id) this.byAccount.delete(id);
      }
    }
    // A host dropped that way hands the role to the next driver still on the way.
    if (!group.members.has(group.hostId)) {
      const next = [...group.members.values()].find((m) => m.state === 'invited' || m.state === 'driving');
      if (next) group.hostId = next.accountId;
    }
    const live = [...group.members.values()].filter((m) => m.state === 'invited' || m.state === 'driving');
    if (live.length === 0 && arrived) this.close(group);
    this.notify(group);
  }

  /** Opens (or replaces) the link that lets someone watch the group without driving. */
  openLink(group) {
    const token = randomBytes(9).toString('base64url'); // 12 characters, unguessable
    if (group.watch) this.byWatch.delete(group.watch.token);
    group.watch = { token, openedAt: Date.now() };
    group.observerIds.clear();
    group.removedObserverIds.clear();
    this.byWatch.set(token, group.id);
    this.notify(group);
    return group.watch;
  }

  /** The link stops working at once; whoever had it open sees the group end. */
  revokeLink(group) {
    if (!group.watch) return;
    this.byWatch.delete(group.watch.token);
    group.watch = null;
    group.observerIds.clear();
    this.notify(group);
  }

  /** One observer is shown the door; the others keep watching. */
  removeObserver(group, observerId) {
    group.observerIds.delete(observerId);
    group.removedObserverIds.add(observerId);
    this.notify(group);
  }

  expired(group) {
    return group.endsAt <= Date.now();
  }

  forget(group) {
    for (const listener of [...(this.listeners.get(group.id) ?? [])]) listener.end();
    this.listeners.delete(group.id);
    this.byId.delete(group.id);
    this.byCode.delete(group.code);
    if (group.watch) this.byWatch.delete(group.watch.token);
    for (const id of group.members.keys()) {
      if (this.byAccount.get(id) === group.id) this.byAccount.delete(id);
    }
  }

  /** A code nobody uses right now. */
  freeCode() {
    for (let tries = 0; tries < 50; tries += 1) {
      const bytes = randomBytes(config.groupCodeLength);
      let code = '';
      for (const byte of bytes) code += CODE_ALPHABET[byte % CODE_ALPHABET.length];
      if (!this.byCode.has(code)) return code;
    }
    return randomBytes(6).toString('hex').toUpperCase();
  }

  prune() {
    for (const group of [...this.byId.values()]) if (this.expired(group)) this.forget(group);
  }

  get meta() {
    this.prune();
    let members = 0;
    for (const group of this.byId.values()) members += group.members.size;
    return { groups: this.byId.size, members };
  }
}

/** A driver joining a group: nothing about them yet, beyond their name and their willingness. */
function member(account, { route } = {}) {
  const now = Date.now();
  return {
    accountId: account.id,
    name: account.username ?? account.displayName ?? 'Un conducteur',
    // The profile picture, as the account shows it everywhere else.
    avatarUrl: account.avatarUrl ?? null,
    leftAt: null,
    // Sharing is a choice, made in the app before joining and changed at any moment.
    sharing: true,
    observable: true,
    state: 'invited', // invited → driving → arrived, or left
    joinedAt: now,
    startedAt: null,
    arrivedAt: null,
    lastSeenAt: now,
    position: null,
    speedKmh: null,
    remainingM: null,
    etaAt: null,
    progress: 0,
    distanceM: null,
    durationS: null,
    rank: null,
    toLabel: null,
    route: route ? simplify(route) : null,
    routeRev: 0,
  };
}

/** A position as the stream pushes it: where, which way, how fast, when (server clock). */
export function positionView(m) {
  return {
    id: m.accountId,
    lat: m.position.lat,
    lon: m.position.lon,
    bearing: m.position.bearing,
    at: m.position.at,
    speedKmh: m.speedKmh,
    progress: m.progress,
    remainingM: m.remainingM,
    etaAt: m.etaAt ? new Date(m.etaAt).toISOString() : null,
  };
}

/** Live now, or silent long enough that the map should say so. */
function online(m) {
  return Date.now() - m.lastSeenAt <= config.groupOnlineMs;
}

/**
 * One member as the others see them. A member who does not share gives their name and their state
 * and nothing else: no position, no speed, no progress.
 */
function memberView(m, { withRoute = false } = {}) {
  const base = {
    id: m.accountId,
    name: m.name,
    state: m.state,
    sharing: m.sharing,
    online: online(m),
    rank: m.rank,
    avatarUrl: m.avatarUrl ?? null,
    joinedAt: new Date(m.joinedAt).toISOString(),
    leftAt: m.leftAt ? new Date(m.leftAt).toISOString() : null,
  };
  if (m.state === 'arrived') {
    base.arrivedAt = m.arrivedAt ? new Date(m.arrivedAt).toISOString() : null;
    base.durationS = m.durationS;
    base.distanceM = m.distanceM;
  }
  if (!m.sharing) return base;
  return {
    ...base,
    position: m.position,
    speedKmh: m.speedKmh,
    progress: m.progress,
    remainingM: m.remainingM,
    etaAt: m.etaAt ? new Date(m.etaAt).toISOString() : null,
    routeRev: m.routeRev,
    // The route travels only when it is asked for: the group view does not need five of them.
    route: withRoute ? m.route : undefined,
  };
}

/** The group as one of its members sees it. */
export function groupView(group, viewerId) {
  const me = group.members.get(viewerId) ?? null;
  return {
    // The server's clock: the phone places positions in time with it, whatever its own says.
    now: Date.now(),
    id: group.id,
    code: group.code,
    host: group.hostId === viewerId,
    hostName: group.members.get(group.hostId)?.name ?? null,
    toLabel: group.toLabel,
    destination: group.destination,
    maxMembers: config.groupMaxMembers,
    endsAt: new Date(group.endsAt).toISOString(),
    finishedAt: group.finishedAt ? new Date(group.finishedAt).toISOString() : null,
    cancelled: Boolean(group.cancelledAt),
    // Set when the trip is over: names, times, distances, in the order of arrival.
    ranking: group.finalRanking,
    link: group.watch
      ? {
          url: `${config.shareBaseUrl}/g/${group.watch.token}`,
          token: group.watch.token,
          observers: group.observerIds.size,
        }
      : null,
    me: me
      ? { id: me.accountId, sharing: me.sharing, observable: me.observable, state: me.state, rank: me.rank }
      : null,
    members: [...group.members.values()].map((m) => memberView(m)),
  };
}

/**
 * The routes of the other members who share, except those the phone already has: [known]
 * maps a member id to the route version it holds. A route crosses the network once per
 * change, never at every tick.
 */
export function routesFor(group, viewerId, known = new Map()) {
  const routes = [];
  for (const m of group.members.values()) {
    if (m.accountId === viewerId || !m.sharing || m.state === 'left' || !m.route) continue;
    if (known.get(m.accountId) === m.routeRev) continue;
    routes.push({ id: m.accountId, rev: m.routeRev, route: m.route });
  }
  return routes;
}

/**
 * A route light enough to travel: points closer than ~8 m to the line they sit on go
 * (Douglas–Peucker), and 1500 points at most remain. The shape on the map does not change.
 */
export function simplify(route) {
  const points = route
    .map((p) => [Number(p?.[0]), Number(p?.[1])])
    .filter(([lon, lat]) => Number.isFinite(lon) && Number.isFinite(lat));
  if (points.length <= 2) return points;
  const toleranceDeg = 8 / 111_320;
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [first, last] = stack.pop();
    let worst = 0;
    let index = -1;
    const [ax, ay] = points[first];
    const [bx, by] = points[last];
    const dx = bx - ax;
    const dy = by - ay;
    const length2 = dx * dx + dy * dy;
    for (let i = first + 1; i < last; i += 1) {
      const [px, py] = points[i];
      const t = length2 === 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / length2));
      const d = Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
      if (d > worst) {
        worst = d;
        index = i;
      }
    }
    if (index > 0 && worst > toleranceDeg) {
      keep[index] = 1;
      stack.push([first, index], [index, last]);
    }
  }
  let kept = points.filter((_, i) => keep[i]);
  if (kept.length > 1500) {
    const step = kept.length / 1500;
    kept = Array.from({ length: 1500 }, (_, i) => kept[Math.min(kept.length - 1, Math.round(i * step))]);
    kept[kept.length - 1] = points[points.length - 1];
  }
  return kept;
}

/** One member in full — their route included — for the "suivre ce participant" view. */
export function memberDetail(group, memberId) {
  const m = group.members.get(memberId);
  if (!m) return null;
  if (!m.sharing) return { id: m.accountId, name: m.name, state: m.state, sharing: false };
  return memberView(m, { withRoute: true });
}

/**
 * The group as someone holding the link sees it: the map, the progress, the routes and the speeds
 * of the members who agreed to it, and nothing else. A member who is not observable is absent.
 */
export function observerView(group) {
  const shown = [...group.members.values()].filter((m) => m.sharing && m.observable && m.state !== 'left');
  return {
    toLabel: group.toLabel,
    destination: group.destination,
    endsAt: new Date(group.endsAt).toISOString(),
    finishedAt: group.finishedAt ? new Date(group.finishedAt).toISOString() : null,
    cancelled: Boolean(group.cancelledAt),
    ranking: group.publicRanking,
    members: shown.map((m) => memberView(m, { withRoute: true })),
  };
}

/** The finished trip, in the order people arrived; whoever never arrived closes the list. */
export function ranking(group, only = null) {
  const members = only ?? [...group.members.values()];
  return members
    .filter((m) => m.state !== 'left' || m.rank !== null)
    .sort((a, b) => (a.rank ?? 99) - (b.rank ?? 99) || a.joinedAt - b.joinedAt)
    .map((m) => ({
      id: m.accountId,
      name: m.name,
      rank: m.rank,
      state: m.state,
      durationS: m.durationS,
      distanceM: m.distanceM,
      arrivedAt: m.arrivedAt ? new Date(m.arrivedAt).toISOString() : null,
    }));
}

export const groupStore = new GroupStore();
