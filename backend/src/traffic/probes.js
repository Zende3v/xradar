import { createHash, randomBytes } from 'node:crypto';
import { config } from '../config.js';
import { haversine } from '../radars/geo.js';
import { angleDiff } from '../routing/geometry.js';

/**
 * Slowdown probes ("Partager les ralentissements"): where a driver's app saw them crawl on a
 * road meant to be fluid — position, course, speed and the road's limit, nothing else. Kept in
 * memory only, config.probeTtlMs at most, under a pseudonym that changes every day: once
 * stored, nothing ties a probe to an account. One probe per driver and spot: a newer one
 * replaces theirs nearby, so a cluster counts different drivers.
 */
class ProbeStore {
  constructor() {
    this.probes = []; // { at, lat, lon, course, speedKmh, limitKmh, voter }, oldest first
    this.lastBy = new Map(); // pseudonym -> time of their last probe
    this.jamAt = new Map(); // spot key -> when a jam was last made there from probes
    this.salt = null;
    this.saltDay = null;
  }

  /**
   * A driver's probe. { accepted: false } when they sent one less than probeMinIntervalMs ago;
   * otherwise { accepted: true, cluster } with the probes of that spot (see clusterAt).
   */
  add(accountId, { lat, lon, course, speedKmh, limitKmh }, now = Date.now()) {
    this.prune(now);
    const voter = this.pseudonym(accountId, now);
    const last = this.lastBy.get(voter);
    if (last != null && now - last < config.probeMinIntervalMs) return { accepted: false };
    this.lastBy.set(voter, now);
    this.probes = this.probes.filter((p) => !(p.voter === voter && haversine(p.lat, p.lon, lat, lon) <= config.probeClusterRadiusM));
    this.probes.push({ at: now, lat, lon, course, speedKmh, limitKmh, voter });
    if (this.probes.length > config.probeMaxStored) this.probes.splice(0, this.probes.length - config.probeMaxStored);
    return { accepted: true, cluster: this.clusterAt(lat, lon, course, now) };
  }

  /**
   * The recent probes around (lat, lon) for the same traffic: how many different drivers, and
   * where they are on average (position and course).
   */
  clusterAt(lat, lon, course, now = Date.now()) {
    const around = this.recent(now).filter((p) => haversine(p.lat, p.lon, lat, lon) <= config.probeClusterRadiusM
      && angleDiff(p.course, course) <= config.probeSameWayDeg);
    const drivers = new Set(around.map((p) => p.voter)).size;
    const n = around.length || 1;
    const x = around.reduce((sum, p) => sum + Math.cos((p.course * Math.PI) / 180), 0);
    const y = around.reduce((sum, p) => sum + Math.sin((p.course * Math.PI) / 180), 0);
    return {
      drivers,
      lat: around.reduce((sum, p) => sum + p.lat, 0) / n,
      lon: around.reduce((sum, p) => sum + p.lon, 0) / n,
      course: around.length ? ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360 : course,
    };
  }

  /** "Non" to "Ralentissement du trafic ?": this driver's recent probes are taken back. */
  dismiss(accountId, now = Date.now()) {
    const voter = this.pseudonym(accountId, now);
    this.probes = this.probes.filter((p) => !(p.voter === voter && now - p.at <= config.probeClusterWindowMs));
  }

  /** Whether this driver sent a probe near (lat, lon) lately: their "Oui" is that slowdown. */
  sentNear(accountId, lat, lon, now = Date.now()) {
    const voter = this.pseudonym(accountId, now);
    return this.recent(now).some((p) => p.voter === voter && haversine(p.lat, p.lon, lat, lon) <= config.probeClusterRadiusM);
  }

  /** The probes young enough to say something about the traffic now. */
  recent(now = Date.now()) {
    return this.probes.filter((p) => now - p.at <= config.probeClusterWindowMs);
  }

  /** A jam is made from the probes of a spot at most once per probeAutoJamEveryMs. */
  claimJam(lat, lon, course, now = Date.now()) {
    const key = `${Math.round(lat * 100)},${Math.round(lon * 100)},${Math.round(course / 45) % 8}`;
    const last = this.jamAt.get(key);
    if (last != null && now - last < config.probeAutoJamEveryMs) return false;
    this.jamAt.set(key, now);
    return true;
  }

  get meta() {
    return { count: this.probes.length };
  }

  /** A day's pseudonym for an account: the salt is new every day and never stored. */
  pseudonym(accountId, now) {
    const day = new Date(now).toISOString().slice(0, 10);
    if (day !== this.saltDay) {
      this.saltDay = day;
      this.salt = randomBytes(16).toString('hex');
      this.lastBy.clear();
    }
    return createHash('sha256').update(`${this.salt}:${accountId}`).digest('hex').slice(0, 20);
  }

  prune(now) {
    const cutoff = now - config.probeTtlMs;
    if (this.probes.length && this.probes[0].at < cutoff) this.probes = this.probes.filter((p) => p.at >= cutoff);
    for (const [voter, at] of this.lastBy) if (now - at >= config.probeMinIntervalMs) this.lastBy.delete(voter);
    for (const [key, at] of this.jamAt) if (now - at >= config.probeAutoJamEveryMs) this.jamAt.delete(key);
  }
}

export const probeStore = new ProbeStore();
