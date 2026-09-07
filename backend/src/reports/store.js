import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';
import { haversine } from '../radars/geo.js';

const VALID_TYPES = new Set(Object.keys(config.reportTtlMs));

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

  add({ type, lat, lon, reporterRole = 'guest', plate = null, street = null, side = null }) {
    if (!VALID_TYPES.has(type)) return null;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    const now = Date.now();
    const baseTtl = config.reportTtlMs[type] ?? config.reportDefaultTtlMs;
    // Admin reports are fully trusted: max confidence + a longer life.
    const trusted = reporterRole === 'admin';
    // Radar cars move — never extend their life even for admins.
    const ttl = trusted && type !== 'voiture_radar' ? baseTtl * 2 : baseTtl;
    const report = {
      id: randomUUID(),
      type,
      lat,
      lon,
      createdAt: now,
      expiresAt: now + ttl,
      confirms: trusted ? 5 : 0,
      denials: 0,
      trusted,
      reporterRole,
      // Extra fields (only kept where relevant). Plate stays server-side.
      plate: type === 'voiture_radar' ? plate : null,
      street: type === 'camera' ? street : null,
      side: type === 'camera' ? side : null,
    };
    this.reports.set(report.id, report);
    this.scheduleSave();
    return report;
  }

  confirm(id) {
    const r = this.reports.get(id);
    if (!r) return null;
    r.confirms++;
    r.expiresAt = Math.min(r.expiresAt + config.reportConfirmExtendMs, Date.now() + maxTtl(r.type));
    this.scheduleSave();
    return r;
  }

  deny(id) {
    const r = this.reports.get(id);
    if (!r) return null;
    r.denials++;
    r.expiresAt -= config.reportDenyShortenMs;
    if (r.denials - r.confirms >= config.reportDropScore || r.expiresAt <= Date.now()) {
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
      // Radar cars are served as aggregated zones, not individual points.
      if (r.type === 'voiture_radar') continue;
      const distanceM = haversine(lat, lon, r.lat, r.lon);
      if (distanceM <= radiusM) {
        const { plate, ...pub } = r; // never expose plates
        out.push({ ...pub, distanceM: Math.round(distanceM) });
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
        const w = Math.max(0.15, 1 - (now - r.createdAt) / (r.expiresAt - r.createdAt));
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

function maxTtl(type) {
  // Cap how far confirmations can push a report so it can't live forever.
  return (config.reportTtlMs[type] ?? config.reportDefaultTtlMs) * 2;
}

/** Opaque, stable id for a plate — the plate itself never leaves the server. */
function hashPlate(plate) {
  return createHash('sha256').update(String(plate).toUpperCase().replace(/\s/g, '')).digest('hex').slice(0, 16);
}

export const reportStore = new ReportStore();
