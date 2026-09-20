import { randomBytes } from 'node:crypto';
import { config } from '../config.js';

/**
 * Trip sharing: a driver hands a link to someone, who follows their trip live until they arrive.
 * Everything lives in memory — nothing about where people go is written to disk — and a share
 * dies fifteen minutes after the arrival, or after config.shareMaxMs if the trip never ends.
 *
 * A share holds only what the follower needs: where the driver is, the route being driven, the
 * destination and when they should arrive. No history, no speed, nothing else.
 */
class ShareStore {
  constructor() {
    this.byToken = new Map(); // token -> share
    this.byAccount = new Map(); // account id -> token (one live share per driver)
  }

  /** Opens (or replaces) the share of [account]; returns it, token included. */
  open(account, { toLabel, destination, route }) {
    this.close(account.id);
    const token = randomBytes(9).toString('base64url'); // 12 characters, unguessable
    const share = {
      token,
      accountId: account.id,
      name: account.username ?? account.displayName ?? 'Un conducteur',
      openedAt: Date.now(),
      endsAt: Date.now() + config.shareMaxMs,
      toLabel: toLabel ?? null,
      destination: destination ?? null,
      route: route ?? null,
      position: null,
      etaAt: null,
      remainingM: null,
      arrived: false,
      // Who follows, counted and never named: the driver sees a number.
      followerIds: new Set(),
    };
    this.byToken.set(token, share);
    this.byAccount.set(account.id, token);
    return share;
  }

  /** What the driver's app sends as it drives: where it is, and what is left of the trip. */
  update(accountId, { lat, lon, bearing, remainingM, etaS, route, toLabel, destination, arrived }) {
    const share = this.get(this.byAccount.get(accountId));
    if (!share) return null;
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      share.position = { lat, lon, bearing: Number.isFinite(bearing) ? bearing : null, at: Date.now() };
    }
    if (Number.isFinite(remainingM)) share.remainingM = Math.max(0, Math.round(remainingM));
    if (Number.isFinite(etaS)) share.etaAt = Date.now() + Math.max(0, Math.round(etaS)) * 1000;
    if (Array.isArray(route)) share.route = route;
    if (toLabel) share.toLabel = String(toLabel).slice(0, 160);
    if (destination) share.destination = destination;
    if (arrived === true && !share.arrived) {
      share.arrived = true;
      // Arrived: the link lives just long enough for the follower to see it.
      share.endsAt = Date.now() + config.shareAfterArrivalMs;
    }
    return share;
  }

  /** The live share behind a token, or null when it never existed or is over. */
  get(token) {
    if (!token) return null;
    const share = this.byToken.get(token);
    if (!share) return null;
    if (share.endsAt <= Date.now()) {
      this.forget(share);
      return null;
    }
    return share;
  }

  /** The driver's own live share, if any. */
  forAccount(accountId) {
    return this.get(this.byAccount.get(accountId));
  }

  close(accountId) {
    const share = this.byToken.get(this.byAccount.get(accountId));
    if (share) this.forget(share);
  }

  forget(share) {
    this.byToken.delete(share.token);
    if (this.byAccount.get(share.accountId) === share.token) this.byAccount.delete(share.accountId);
  }

  prune() {
    const now = Date.now();
    for (const share of [...this.byToken.values()]) if (share.endsAt <= now) this.forget(share);
  }

  get meta() {
    this.prune();
    return { shares: this.byToken.size };
  }
}

/** What the driver who opened the share sees (their own link). */
export function ownerView(share) {
  return {
    token: share.token,
    url: `${config.shareBaseUrl}/t/${share.token}`,
    endsAt: new Date(share.endsAt).toISOString(),
    followers: share.followerIds.size,
    arrived: share.arrived,
  };
}

/** What a follower sees: enough to place the driver and know when they arrive, nothing more. */
export function followerView(share) {
  return {
    name: share.name,
    toLabel: share.toLabel,
    destination: share.destination,
    route: share.route,
    position: share.position,
    remainingM: share.remainingM,
    etaAt: share.etaAt ? new Date(share.etaAt).toISOString() : null,
    arrived: share.arrived,
    endsAt: new Date(share.endsAt).toISOString(),
  };
}

export const shareStore = new ShareStore();
