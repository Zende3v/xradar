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
    if (group.members.has(account.id)) {
      this.byAccount.set(account.id, group.id);
      return { group };
    }
    if (group.members.size >= config.groupMaxMembers) return { error: 'group full' };
    this.leave(account.id);
    group.members.set(account.id, member(account, { route }));
    this.byAccount.set(account.id, group.id);
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
    if (fields.toLabel) me.toLabel = String(fields.toLabel).slice(0, 160);
    if (Array.isArray(fields.route) && fields.route.length >= 2) {
      me.route = fields.route;
      me.routeRev += 1;
    }
    if (Number.isFinite(fields.lat) && Number.isFinite(fields.lon)) {
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
    if (Number.isFinite(fields.speedKmh)) me.speedKmh = Math.max(0, Math.round(fields.speedKmh));
    if (Number.isFinite(fields.remainingM)) me.remainingM = Math.max(0, Math.round(fields.remainingM));
    if (Number.isFinite(fields.etaS)) me.etaAt = now + Math.max(0, Math.round(fields.etaS)) * 1000;
    if (Number.isFinite(fields.progress)) me.progress = Math.min(1, Math.max(0, fields.progress));
    if (Number.isFinite(fields.distanceM)) me.distanceM = Math.max(0, Math.round(fields.distanceM));

    if (fields.arrived === true && me.state !== 'arrived') this.arrive(group, me);
    this.settle(group);
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

  /** A member steps out of the group; the host stepping out closes it for everyone. */
  leave(accountId) {
    const group = this.forAccount(accountId);
    if (!group) return null;
    this.byAccount.delete(accountId);
    if (group.hostId === accountId) {
      this.close(group);
      return group;
    }
    const me = group.members.get(accountId);
    if (me) {
      me.state = 'left';
      me.position = null;
      me.route = null;
      me.speedKmh = null;
      me.routeRev += 1;
    }
    this.settle(group);
    return group;
  }

  /** The host ends the group: the link dies, and everybody's app is told at its next call. */
  close(group) {
    if (group.finishedAt) return;
    group.finishedAt = Date.now();
    group.endsAt = Math.min(group.endsAt, Date.now() + config.groupAfterFinishMs);
    // The ranking is taken here, once, and never moves again — not even if a phone goes silent
    // afterwards and its owner is dropped from the group.
    group.finalRanking = ranking(group);
    group.publicRanking = ranking(group, [...group.members.values()].filter((m) => m.sharing && m.observable));
    this.revokeLink(group);
  }

  /**
   * Ends the group on its own once nobody is still driving — everyone arrived or left — and drops
   * the members whose phone has been silent for too long.
   */
  settle(group) {
    const now = Date.now();
    for (const [id, m] of group.members) {
      const silent = now - m.lastSeenAt;
      if (m.state !== 'arrived' && silent > config.groupDropMs && id !== group.hostId) {
        group.members.delete(id);
        if (this.byAccount.get(id) === group.id) this.byAccount.delete(id);
      }
    }
    if (group.finishedAt) return;
    const live = [...group.members.values()].filter((m) => m.state === 'invited' || m.state === 'driving');
    const arrived = [...group.members.values()].some((m) => m.state === 'arrived');
    if (live.length === 0 && arrived) this.close(group);
  }

  /** Opens (or replaces) the link that lets someone watch the group without driving. */
  openLink(group) {
    const token = randomBytes(9).toString('base64url'); // 12 characters, unguessable
    if (group.watch) this.byWatch.delete(group.watch.token);
    group.watch = { token, openedAt: Date.now() };
    group.observerIds.clear();
    group.removedObserverIds.clear();
    this.byWatch.set(token, group.id);
    return group.watch;
  }

  /** The link stops working at once; whoever had it open sees the group end. */
  revokeLink(group) {
    if (!group.watch) return;
    this.byWatch.delete(group.watch.token);
    group.watch = null;
    group.observerIds.clear();
  }

  /** One observer is shown the door; the others keep watching. */
  removeObserver(group, observerId) {
    group.observerIds.delete(observerId);
    group.removedObserverIds.add(observerId);
  }

  expired(group) {
    return group.endsAt <= Date.now();
  }

  forget(group) {
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
    route: route ?? null,
    routeRev: 0,
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
    joinedAt: new Date(m.joinedAt).toISOString(),
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
    id: group.id,
    code: group.code,
    host: group.hostId === viewerId,
    toLabel: group.toLabel,
    destination: group.destination,
    maxMembers: config.groupMaxMembers,
    endsAt: new Date(group.endsAt).toISOString(),
    finishedAt: group.finishedAt ? new Date(group.finishedAt).toISOString() : null,
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
  const shown = [...group.members.values()].filter((m) => m.sharing && m.observable);
  return {
    toLabel: group.toLabel,
    destination: group.destination,
    endsAt: new Date(group.endsAt).toISOString(),
    finishedAt: group.finishedAt ? new Date(group.finishedAt).toISOString() : null,
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
