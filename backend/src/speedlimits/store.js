import { createHash, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { accountStore } from '../accounts/store.js';
import { config } from '../config.js';
import { angleBetween, haversine } from '../radars/geo.js';
import { crowdScore } from '../reports/score.js';
import { signDataset } from '../signs/dataset.js';

const CELL_DEG = 0.02; // ~2.2 km cells for the applied changes' points
const PENDING = 'pending';
const VALIDATED = 'validated';
/** Closed without ever applying: dropped from the history after speedLimitHistoryMs. */
const DROPPABLE = new Set(['expired', 'outdated', 'rejected']);

/**
 * Speed-limit maintenance. A driver who sees a new sign proposes its limit ("50 → 70").
 * The proposals made at one spot — close to one another, on the same way, from the same
 * former limit — form one change, scored with the report formula (reports/score.js).
 * Nothing moves on a single proposal: the new limit is applied once it is strong enough
 * (see config.speedLimitScore), and from then on it is permanent, like a fixed camera,
 * until a later validated change on the same stretch replaces it or an admin removes it.
 *
 * Applied changes are laid over the OSM limits (signDataset.overrides); the dataset file is
 * never rewritten, so a change survives the next OSM extraction. Every change keeps its
 * proposals and events, so the history shows why a limit moved. Authors stay server-side.
 */
class SpeedLimitStore {
  constructor() {
    this.changes = new Map(); // id -> change
    this.grid = new Map(); // cellKey -> points of the validated changes
    this.timer = null;
    this.saveTimer = null;
  }

  async start() {
    await this.load();
    this.rebuildIndex();
    signDataset.overrides = this;
    if (this.sweep(Date.now()) > 0) this.scheduleSave();
    this.timer = setInterval(() => {
      if (this.sweep(Date.now()) > 0) this.scheduleSave();
    }, 10 * 60 * 1000);
    if (this.timer.unref) this.timer.unref();
  }

  async load() {
    try {
      const list = JSON.parse(await readFile(config.speedLimitsFile, 'utf8'));
      if (Array.isArray(list)) {
        for (const c of list) if (c && c.id) this.changes.set(c.id, c);
      }
      console.log(`[speed-limits] loaded ${this.changes.size} changes from ${config.speedLimitsFile}`);
    } catch (e) {
      if (e.code !== 'ENOENT') console.error('[speed-limits] load failed:', e.message);
    }
  }

  scheduleSave() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.save().catch((e) => console.error('[speed-limits] save failed:', e.message));
    }, 1500);
    if (this.saveTimer.unref) this.saveTimer.unref();
  }

  async save() {
    await mkdir(dirname(config.speedLimitsFile), { recursive: true });
    await writeFile(config.speedLimitsFile, JSON.stringify([...this.changes.values()]), 'utf8');
  }

  /**
   * A driver proposes [newKmh] where they are. [displayedKmh] / [displayedSource] are what
   * their HUD showed ("map" or "radar"); the limit the map holds here is what counts, a
   * radar's VMA only where the map has none. Returns { change, firstVoice } or { error };
   * { change: null } when they confirm a limit nobody proposed to change.
   */
  report({ lat, lon, bearing, displayedKmh, displayedSource, newKmh, reporterId, reporterRole = 'guest', now = Date.now() }) {
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return { error: 'lat and lon are required numbers' };
    if (!config.speedLimitValues.includes(newKmh)) {
      return { error: `newKmh must be one of ${config.speedLimitValues.join(', ')}` };
    }
    if (!reporterId) return { error: 'reporter required' };
    const course = Number.isFinite(bearing) ? ((bearing % 360) + 360) % 360 : null;
    const shown = Number.isInteger(displayedKmh) && displayedKmh >= 5 && displayedKmh <= 130 ? displayedKmh : null;

    const mapped = signDataset.limitAt(lat, lon, config.signLimitMaxDistM, course);
    const oldKmh = mapped ?? (displayedSource === 'radar' ? shown : null);
    const oldSource = mapped != null ? 'map' : oldKmh != null ? 'radar' : 'unknown';

    let change = this.openChangeFor(lat, lon, course, oldKmh);
    if (!change) {
      // "The limit is right" only means something against a proposal already there.
      if (newKmh === oldKmh) return { change: null, firstVoice: false };
      change = {
        id: randomUUID(),
        status: PENDING,
        createdAt: now,
        updatedAt: now,
        lat,
        lon,
        bearing: course,
        oldKmh,
        oldSource,
        newKmh: null,
        votes: [],
        events: [],
      };
      this.changes.set(change.id, change);
    }

    // One voice per person: a new proposal from the same driver replaces their previous one.
    const previous = change.votes.findIndex((v) => v.reporter === reporterId);
    if (previous >= 0) change.votes.splice(previous, 1);
    const vote = { reporter: reporterId, role: reporterRole, lat, lon, bearing: course, newKmh, displayedKmh: shown, at: now };
    change.votes.push(vote);
    change.events.push({ at: now, type: previous >= 0 ? 'revised' : 'reported', newKmh, displayedKmh: shown, role: reporterRole });
    reshape(change);
    change.updatedAt = now;

    if (reporterRole === 'admin') this.decideByAdmin(change, vote, now);
    else this.evaluate(change, now);
    this.scheduleSave();
    return { change: publicView(change, now), firstVoice: previous < 0 };
  }

  /** The pending change a proposal belongs to: same former limit, same way, close by. */
  openChangeFor(lat, lon, course, oldKmh) {
    let best = null;
    for (const c of this.changes.values()) {
      if (c.status !== PENDING || c.oldKmh !== oldKmh || !sameWay(c.bearing, course)) continue;
      const d = Math.min(...c.votes.map((v) => haversine(lat, lon, v.lat, v.lon)));
      if (d <= config.speedLimitGroupRadiusM && (best === null || d < best.d)) best = { c, d };
    }
    return best?.c ?? null;
  }

  /**
   * Apply the proposal that has become strong enough: the best-scored value other than the
   * current limit, when it beats the current limit too, reaches the "high" relevance band,
   * has enough distinct supporters and outnumbers everyone else. A pending change whose
   * voices all aged out expires; one whose former limit no longer holds is outdated.
   */
  evaluate(change, now) {
    if (change.status !== PENDING) return;
    const { model, live, proposals } = tally(change, now);
    if (live.length === 0) return this.close(change, 'expired', now);
    // Only once the map's limits are there to compare with (never while they load).
    if (signDataset.ready && !this.stillHolds(change)) return this.close(change, 'outdated', now);
    const top = proposals[0];
    if (!top || top.kmh === change.oldKmh) return;
    if (
      top.score >= config.reportRelevance.high &&
      top.supporters >= model.minSupporters &&
      top.supporters > top.contradictions
    ) {
      this.apply(change, top.kmh, 'crowd', now, {
        score: Math.round(top.score),
        supporters: top.supporters,
        contradictions: top.contradictions,
      });
    }
  }

  /** An admin's proposal is applied at once; an admin confirming the current limit closes it. */
  decideByAdmin(change, vote, now) {
    if (vote.newKmh === change.oldKmh) return this.close(change, 'rejected', now, { by: 'admin' });
    this.apply(change, vote.newKmh, 'admin', now, {});
  }

  /** Does the limit the change started from still hold where its drivers proposed it? */
  stillHolds(change) {
    return change.votes.some((v) => {
      const current = signDataset.limitAt(v.lat, v.lon, config.signLimitMaxDistM, change.bearing);
      return current === change.oldKmh || (change.oldSource !== 'map' && current == null);
    });
  }

  apply(change, kmh, by, now, detail) {
    const supporters = change.votes.filter((v) => v.newKmh === kmh);
    const points = this.pointsFor(change, supporters);
    // A later change on the same stretch replaces the earlier one, which stays in the history.
    for (const other of this.changes.values()) {
      if (other === change || other.status !== VALIDATED || !sameWay(other.bearing, change.bearing)) continue;
      const overlaps = other.points.some(([la, lo]) =>
        points.some(([lb, lob]) => haversine(la, lo, lb, lob) <= config.speedLimitZoneRadiusM));
      if (!overlaps) continue;
      other.status = 'superseded';
      other.closedAt = now;
      other.supersededBy = change.id;
      other.events.push({ at: now, type: 'superseded', by: change.id, newKmh: kmh });
    }
    change.status = VALIDATED;
    change.newKmh = kmh;
    change.validatedAt = now;
    change.validatedBy = by;
    change.points = points;
    change.events.push({ at: now, type: 'validated', by, newKmh: kmh, points: points.length, ...detail });
    this.rebuildIndex();
    // As with a road report's first confirmation, the change counts as confirmed for its authors.
    for (const v of supporters) accountStore.recordReportStat(v.reporter, 'reportsConfirmed');
  }

  /**
   * Where the new limit applies: the mapped limit points near its supporters that still read
   * the former limit for this way (a side road with its own limit keeps it). With nothing
   * mapped to replace — an unknown spot, a radar's VMA — it applies where the sign was seen.
   */
  pointsFor(change, supporters) {
    const points = [];
    const seen = new Set();
    if (change.oldKmh != null) {
      for (const v of supporters) {
        for (const p of signDataset.speedPointsNear(v.lat, v.lon, config.speedLimitZoneRadiusM)) {
          const key = `${p.lat.toFixed(6)},${p.lon.toFixed(6)}`;
          if (seen.has(key)) continue;
          seen.add(key);
          const current = signDataset.limitAt(p.lat, p.lon, config.speedLimitOverrideTieM, change.bearing);
          if (current === change.oldKmh) points.push([round6(p.lat), round6(p.lon)]);
        }
      }
    }
    if (points.length === 0) for (const v of supporters) points.push([round6(v.lat), round6(v.lon)]);
    return points;
  }

  close(change, status, now, detail = {}) {
    change.status = status;
    change.closedAt = now;
    change.updatedAt = now;
    change.events.push({ at: now, type: status, ...detail });
  }

  /** Expire what aged out, drop old dead proposals. Returns how many changes moved. */
  sweep(now) {
    let moved = 0;
    for (const [id, c] of this.changes) {
      if (c.status === PENDING) {
        const before = c.status;
        this.evaluate(c, now);
        if (c.status !== before) moved++;
      } else if (DROPPABLE.has(c.status) && now - (c.closedAt ?? now) > config.speedLimitHistoryMs) {
        this.changes.delete(id);
        moved++;
      }
    }
    return moved;
  }

  /** Admin moderation: a validated change stops applying, a pending one is rejected. Both stay in the history. */
  remove(id, now = Date.now()) {
    const c = this.changes.get(id);
    if (!c) return null;
    if (c.status === VALIDATED) {
      this.close(c, 'removed', now, { by: 'admin' });
      this.rebuildIndex();
    } else if (c.status === PENDING) {
      this.close(c, 'rejected', now, { by: 'admin' });
    }
    this.scheduleSave();
    return publicView(c, now);
  }

  /** Pending and validated changes around a position, nearest first. */
  near(lat, lon, radiusM, now = Date.now()) {
    const out = [];
    for (const c of this.changes.values()) {
      if (c.status !== PENDING && c.status !== VALIDATED) continue;
      const distanceM = haversine(lat, lon, c.lat, c.lon);
      if (distanceM <= radiusM) out.push({ ...publicView(c, now), distanceM: Math.round(distanceM) });
    }
    return out.sort((a, b) => a.distanceM - b.distanceM);
  }

  /** One change with its proposals and events, authors reduced to an opaque tag (moderation). */
  history(id, now = Date.now()) {
    const c = this.changes.get(id);
    if (!c) return null;
    return {
      ...publicView(c, now),
      supersededBy: c.supersededBy ?? null,
      points: c.points ?? [],
      votes: c.votes.map(({ reporter, ...v }) => ({ ...v, reporter: tag(reporter) })),
      events: c.events,
    };
  }

  /** The validated change nearest to a position, for the way [bearing] runs (null = any way). */
  nearest(lat, lon, maxDistM, bearing) {
    if (this.grid.size === 0) return null;
    const dLat = maxDistM / 111000;
    const dLon = maxDistM / (111000 * Math.cos((lat * Math.PI) / 180) || 1);
    let best = null;
    for (let r = Math.floor((lat - dLat) / CELL_DEG); r <= Math.floor((lat + dLat) / CELL_DEG); r++) {
      for (let c = Math.floor((lon - dLon) / CELL_DEG); c <= Math.floor((lon + dLon) / CELL_DEG); c++) {
        for (const p of this.grid.get(`${r}_${c}`) ?? []) {
          if (!sameWay(p.bearing, bearing)) continue;
          const d = haversine(lat, lon, p.lat, p.lon);
          if (d <= maxDistM && (best === null || d < best.d)) best = { d, v: p.v, lat: p.lat, lon: p.lon, key: p.key };
        }
      }
    }
    return best;
  }

  rebuildIndex() {
    this.grid.clear();
    for (const c of this.changes.values()) {
      if (c.status !== VALIDATED) continue;
      (c.points ?? []).forEach(([lat, lon], i) => {
        const key = `${Math.floor(lat / CELL_DEG)}_${Math.floor(lon / CELL_DEG)}`;
        let cell = this.grid.get(key);
        if (!cell) this.grid.set(key, (cell = []));
        cell.push({ lat, lon, v: c.newKmh, bearing: c.bearing, key: `${c.id}:${i}` });
      });
    }
  }

  get meta() {
    let pending = 0;
    let validated = 0;
    for (const c of this.changes.values()) {
      if (c.status === PENDING) pending++;
      else if (c.status === VALIDATED) validated++;
    }
    return { pending, validated, total: this.changes.size };
  }
}

function modelFor(change) {
  return config.speedLimitScore[change.oldKmh == null ? 'unknown' : 'known'];
}

/**
 * Score every value proposed in a change, with the report formula: its supporters beyond
 * the first are confirmations, everyone proposing something else there contradicts it, and
 * the clock runs from its latest supporter. Voices older than the model's base duration no
 * longer count. Best first.
 */
function tally(change, now) {
  const model = modelFor(change);
  const live = change.votes.filter((v) => now - v.at <= model.baseDurationMs);
  const byValue = new Map();
  for (const v of live) {
    const t = byValue.get(v.newKmh) ?? { kmh: v.newKmh, supporters: 0, lastAt: 0 };
    t.supporters++;
    t.lastAt = Math.max(t.lastAt, v.at);
    byValue.set(v.newKmh, t);
  }
  const proposals = [...byValue.values()].map((t) => {
    const contradictions = live.length - t.supporters;
    return { ...t, contradictions, score: crowdScore(model, now - t.lastAt, t.supporters - 1, contradictions) };
  });
  proposals.sort((a, b) => b.score - a.score);
  return { model, live, proposals };
}

/** Centre of the proposals and the mean course of those that had one. */
function reshape(change) {
  change.lat = change.votes.reduce((s, v) => s + v.lat, 0) / change.votes.length;
  change.lon = change.votes.reduce((s, v) => s + v.lon, 0) / change.votes.length;
  const courses = change.votes.filter((v) => v.bearing != null);
  if (courses.length === 0) return;
  const x = courses.reduce((s, v) => s + Math.cos((v.bearing * Math.PI) / 180), 0);
  const y = courses.reduce((s, v) => s + Math.sin((v.bearing * Math.PI) / 180), 0);
  change.bearing = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
}

function sameWay(a, b) {
  return a == null || b == null || angleBetween(a, b) <= config.speedLimitSameWayDeg;
}

/** What anyone may see: no authors, no positions of individual drivers. */
function publicView(change, now) {
  const { model, live, proposals } = tally(change, now);
  return {
    id: change.id,
    status: change.status,
    lat: change.lat,
    lon: change.lon,
    bearing: change.bearing,
    oldKmh: change.oldKmh,
    oldSource: change.oldSource,
    newKmh: change.newKmh ?? null,
    reporters: live.length,
    required: model.minSupporters,
    proposals: proposals.map((p) => ({ kmh: p.kmh, supporters: p.supporters, score: Math.round(p.score) })),
    createdAt: change.createdAt,
    updatedAt: change.updatedAt,
    validatedAt: change.validatedAt ?? null,
    validatedBy: change.validatedBy ?? null,
  };
}

function round6(n) {
  return Math.round(n * 1e6) / 1e6;
}

function tag(id) {
  return createHash('sha256').update(String(id)).digest('hex').slice(0, 12);
}

export const speedLimitStore = new SpeedLimitStore();
