import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';
import { haversine } from '../radars/geo.js';
import { crowdScore, expiryFor as expiryAfter } from './score.js';

const VALID_TYPES = new Set(Object.keys(config.reportScore));

/**
 * Crowdsourced report store. Kept in memory for fast reads, persisted to a JSON
 * file so reports survive restarts. Reports expire (per-type TTL, extended by
 * community confirmations) and are pruned on read/write. No external DB needed.
 */
class ReportStore {
  constructor() {
    this.reports = new Map(); // id -> report
    this.timer = null;
    this.saveTimer = null;
  }

  async start() {
    await this.load();
    this.prune();
    // Periodic prune + flush so expired reports disappear even without traffic.
    this.timer = setInterval(() => {
      if (this.prune() > 0) this.scheduleSave();
    }, 60 * 1000);
    if (this.timer.unref) this.timer.unref();
  }

  async load() {
    try {
      const raw = await readFile(config.reportsFile, 'utf8');
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        for (const r of list) if (r && r.id) this.reports.set(r.id, r);
      }
      console.log(`[reports] loaded ${this.reports.size} reports from ${config.reportsFile}`);
    } catch (e) {
      if (e.code !== 'ENOENT') console.error('[reports] load failed:', e.message);
    }
  }

  scheduleSave() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.save().catch((e) => console.error('[reports] save failed:', e.message));
    }, 1500);
    if (this.saveTimer.unref) this.saveTimer.unref();
  }

  async save() {
    await mkdir(dirname(config.reportsFile), { recursive: true });
    const list = [...this.reports.values()];
    await writeFile(config.reportsFile, JSON.stringify(list), 'utf8');
  }

  /** Remove expired reports. Returns how many were dropped. */
  prune() {
    const now = Date.now();
    let dropped = 0;
    for (const [id, r] of this.reports) {
      if (r.expiresAt <= now) {
        this.reports.delete(id);
        dropped++;
      }
    }
    return dropped;
  }

  add({ type, lat, lon, reporterId = null, reporterRole = 'guest', plate = null, street = null, side = null, direction = 'same', bearing = null }) {
    if (!VALID_TYPES.has(type)) return null;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    const now = Date.now();
    const report = {
      id: randomUUID(),
      type,
      lat,
      lon,
      createdAt: now,
      // Scoring state: the age decays the score, the two counters scale it.
      // Guests and members count the same; only an admin is special (persistent
      // reports are theirs to remove).
      confirmations: 0,
      contradictions: 0,
      // How many people reported it (the first one, plus every confirmation).
      reporters: 1,
      // Who filed it — kept server-side for the author's statistics, never served.
      reporterId,
      reporterRole,
      expiresAt: expiryFor(type, now, 0, 0),
      // Which side of the road it is on, from the reporter's point of view.
      direction: direction === 'opposite' ? 'opposite' : 'same',
      // Course of the reporter when they sent it: gives the report its orientation.
      bearing: Number.isFinite(bearing) ? bearing : null,
      // Extra fields (only kept where relevant). Plate stays server-side.
      plate: type === 'voiture_radar' ? plate : null,
      street: type === 'camera' ? street : null,
      side: type === 'camera' ? side : null,
    };
    this.reports.set(report.id, report);
    this.scheduleSave();
    return report;
  }

  /**
   * Somebody still sees it. This does not rewind the clock — it raises the score
   * through the confirmation bonus, so a stream of confirmations keeps an event alive
   * without ever making it immortal.
   */
  confirm(id) {
    const r = this.reports.get(id);
    if (!r) return null;
    r.confirmations = (r.confirmations || 0) + 1;
    r.reporters = (r.reporters || 1) + 1;
    r.expiresAt = expiryFor(r.type, r.createdAt, r.confirmations, r.contradictions || 0);
    this.scheduleSave();
    return r;
  }

  /** Nobody sees it any more: the contradiction penalty pulls the score down. */
  deny(id) {
    const r = this.reports.get(id);
    if (!r) return null;
    const now = Date.now();
    r.contradictions = (r.contradictions || 0) + 1;
    r.expiresAt = expiryFor(r.type, r.createdAt, r.confirmations || 0, r.contradictions);
    // A fixed camera is only ever removed by an admin, whatever the crowd says.
    if (!scoreModel(r.type).persistent && intrinsicScore(r, now) < config.reportScoreMinimum) {
      this.reports.delete(id);
      this.scheduleSave();
      return { removed: true, id };
    }
    this.scheduleSave();
    return r;
  }

  /** Hard-delete a report (admin moderation). */
  removeById(id) {
    const existed = this.reports.delete(id);
    if (existed) this.scheduleSave();
    return existed;
  }

  near(lat, lon, radiusM, limit) {
    this.prune();
    const out = [];
    for (const r of this.reports.values()) {
      // Radar cars reported with a plate become probability zones; without one they
      // are just a point like any other report.
      if (r.type === 'voiture_radar' && r.plate) continue;
      const distanceM = haversine(lat, lon, r.lat, r.lon);
      if (distanceM <= radiusM) {
        const { plate, reporterId, ...pub } = r; // never expose plates or authors
        const model = scoreModel(r.type);
        out.push({
          ...pub,
          distanceM: Math.round(distanceM),
          // Intrinsic score: time + crowd. The app multiplies it by the road,
          // direction and distance factors, which depend on the driver asking.
          score: Math.round(intrinsicScore(r, Date.now())),
          impactM: model.impactM,
          persistent: model.persistent === true,
        });
      }
    }
    out.sort((a, b) => a.distanceM - b.distanceM);
    return out.slice(0, limit);
  }

  /**
   * Aggregate live radar-car reports into probable zones (one per plate). The
   * centre is a recency-weighted mean; the radius shrinks as reports pile up.
   * The plate is hashed into an opaque id — it never leaves the server.
   */
  zonesNear(lat, lon, radiusM) {
    this.prune();
    const now = Date.now();
    const byPlate = new Map();
    for (const r of this.reports.values()) {
      if (r.type !== 'voiture_radar' || !r.plate) continue;
      if (!byPlate.has(r.plate)) byPlate.set(r.plate, []);
      byPlate.get(r.plate).push(r);
    }
    const zones = [];
    for (const [plate, reports] of byPlate) {
      let wLat = 0, wLon = 0, wSum = 0;
      for (const r of reports) {
        // Recent reports weigh more (linear decay over the report's lifetime).
        const w = Math.max(0.15, 1 - (now - r.createdAt) / Math.max(1, r.expiresAt - r.createdAt));
        wLat += r.lat * w; wLon += r.lon * w; wSum += w;
      }
      const cLat = wLat / wSum;
      const cLon = wLon / wSum;
      const distanceM = haversine(lat, lon, cLat, cLon);
      if (distanceM > radiusM) continue;
      const radius = Math.min(
        config.zoneMaxRadiusM,
        Math.max(config.zoneMinRadiusM, config.zoneBaseRadiusM / reports.length),
      );
      zones.push({
        id: hashPlate(plate),
        lat: cLat,
        lon: cLon,
        radiusM: Math.round(radius),
        count: reports.length,
        distanceM: Math.round(distanceM),
      });
    }
    zones.sort((a, b) => a.distanceM - b.distanceM);
    return zones;
  }

  get meta() {
    return { count: this.reports.size };
  }
}

function scoreModel(type) {
  return config.reportScore[type] ?? config.reportScore.hazard;
}

/** What the report is worth in itself: time, confirmations, contradictions (see score.js). */
function intrinsicScore(r, now) {
  return crowdScore(scoreModel(r.type), now - (r.createdAt ?? now), r.confirmations, r.contradictions);
}

/** When the intrinsic score will cross the minimum — that is the report's death. */
function expiryFor(type, createdAt, confirmations, contradictions) {
  return expiryAfter(scoreModel(type), createdAt, confirmations, contradictions);
}

/** Opaque, stable id for a plate — the plate itself never leaves the server. */
function hashPlate(plate) {
  return createHash('sha256').update(String(plate).toUpperCase().replace(/\s/g, '')).digest('hex').slice(0, 16);
}

export const reportStore = new ReportStore();
