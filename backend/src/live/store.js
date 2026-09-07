import { config } from '../config.js';
import { haversine } from '../radars/geo.js';

/**
 * Live driver positions, in memory only (never persisted). A position is kept
 * for [liveTtlMs] then considered stale. Going invisible removes it at once.
 */
class LiveStore {
  constructor() {
    this.byAccount = new Map(); // accountId -> { lat, lon, bearing, speedKmh, at, username, avatarUrl }
  }

  set(accountId, { lat, lon, bearing = null, speedKmh = null, username = null, avatarUrl = null }) {
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return;
    this.byAccount.set(accountId, { lat, lon, bearing, speedKmh, username, avatarUrl, at: Date.now() });
  }

  remove(accountId) {
    this.byAccount.delete(accountId);
  }

  prune() {
    const cutoff = Date.now() - config.liveTtlMs;
    for (const [id, p] of this.byAccount) if (p.at < cutoff) this.byAccount.delete(id);
  }

  /** Other live drivers within [radiusM], nearest first (excludes [selfId]). */
  near(selfId, lat, lon, radiusM) {
    this.prune();
    const out = [];
    for (const [id, p] of this.byAccount) {
      if (id === selfId) continue;
      const distanceM = haversine(lat, lon, p.lat, p.lon);
      if (distanceM <= radiusM) {
        out.push({
          id,
          username: p.username,
          avatarUrl: p.avatarUrl,
          lat: p.lat,
          lon: p.lon,
          bearing: p.bearing,
          speedKmh: p.speedKmh,
          ageMs: Date.now() - p.at,
          distanceM: Math.round(distanceM),
        });
      }
    }
    out.sort((a, b) => a.distanceM - b.distanceM);
    return out.slice(0, config.liveMaxResults);
  }

  get meta() {
    this.prune();
    return { live: this.byAccount.size };
  }
}

export const liveStore = new LiveStore();
