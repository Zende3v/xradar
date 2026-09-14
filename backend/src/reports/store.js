import { createHash, randomUUID } from 'node:crypto';
import { config } from '../config.js';
import { transaction } from '../crowd/schema.js';
import { db } from '../db.js';
import { haversine } from '../radars/geo.js';
import { roadAt } from '../signs/postgis.js';
import { crowdScore, expiryFor as expiryAfter } from './score.js';

const VALID_TYPES = new Set(Object.keys(config.reportScore));
/** Voices that say the event is there. */
const POSITIVE = new Set(['reported', 'merged', 'confirm']);
const MOVING = new Set(config.reportMovingTypes);

const POINT_M = 'ST_Transform(ST_SetSRID(ST_MakePoint($LON, $LAT), 4326), 2154)';
const at = (lonParam, latParam) => POINT_M.replace('$LON', lonParam).replace('$LAT', latParam);

const PUBLIC_COLUMNS = `
  r.id, r.type, r.status, ST_Y(r.geom) AS lat, ST_X(r.geom) AS lon, r.course, r.direction, r.bearing,
  (extract(epoch FROM r.created_at) * 1000)::bigint AS created_at,
  (extract(epoch FROM r.expires_at) * 1000)::bigint AS expires_at,
  r.confirmations, r.contradictions, r.reporters, r.reporter_id, r.reporter_role, r.street, r.side`;

/**
 * Drivers' reports, in PostGIS (schema crowd). A report is scored with reports/score.js and
 * expires when that score crosses the minimum; closed reports stay reportHistoryMs for the
 * statistics. The same event is never stored twice: a report of the same type close by, for
 * the same traffic, on the same or a touching road, becomes one more voice on the existing
 * one — and every person has a single voice per report (reported, merged, confirm or deny).
 */
class ReportStore {
  constructor() {
    this.timer = null;
    this.live = 0;
  }

  async start() {
    await this.prune();
    this.timer = setInterval(() => {
      this.prune().catch((e) => console.error('[reports] prune failed:', e.message));
    }, 60 * 1000);
    if (this.timer.unref) this.timer.unref();
  }

  /** Close what expired, forget what closed long ago, refresh the live count. */
  async prune() {
    await db.query(`UPDATE crowd.report SET status = 'expired', closed_at = now() WHERE status = 'live' AND expires_at <= now()`);
    await db.query(
      `DELETE FROM crowd.report WHERE status <> 'live' AND closed_at < now() - make_interval(secs => $1)`,
      [config.reportHistoryMs / 1000],
    );
    const { rows } = await db.query(`SELECT count(*)::int AS live FROM crowd.report WHERE status = 'live'`);
    this.live = rows[0].live;
  }

  /**
   * A driver reports [type] where they are. Returns { report, merged, authorFirstConfirmed }
   * (the event's author, when this voice is its first confirmation), or null when invalid.
   */
  async add({ type, lat, lon, reporterId = null, reporterRole = 'guest', plate = null, street = null, side = null, direction = 'same', bearing = null }) {
    if (!VALID_TYPES.has(type) || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    const dir = direction === 'opposite' ? 'opposite' : 'same';
    const reporterCourse = Number.isFinite(bearing) ? norm(bearing) : null;
    const course = reporterCourse == null ? null : norm(reporterCourse + (dir === 'opposite' ? 180 : 0));
    const road = await roadAt(lat, lon, { bearing: course }).catch(() => null);
    const wayId = road?.wayId ?? null;
    // Radar cars with a plate are aggregated into zones by plate, never merged.
    const byPlate = type === 'voiture_radar' && plate;

    return transaction(async (client) => {
      // Two drivers reporting the same event at the same moment still make one report.
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`report:${type}`]);
      const same = byPlate ? null : await findSame(client, { type, lat, lon, course, wayId });
      if (same) {
        const outcome = await addVoice(client, same, reporterId, 'merged', MOVING.has(type) ? { lat, lon, wayId } : null);
        return { report: await publicReport(client, same.id), merged: true, authorFirstConfirmed: outcome.firstConfirmation ? same.reporter_id : null };
      }
      const id = randomUUID();
      const now = Date.now();
      await client.query(
        `INSERT INTO crowd.report (id, type, geom, geom_m, course, direction, bearing, way_id, expires_at,
                                   reporter_id, reporter_role, plate, street, side)
         VALUES ($1, $2, ST_SetSRID(ST_MakePoint($4, $3), 4326), ${at('$4', '$3')}, $5, $6, $7, $8, to_timestamp($9 / 1000.0),
                 $10, $11, $12, $13, $14)`,
        [id, type, lat, lon, course, dir, reporterCourse, wayId, expiryAfter(scoreModel(type), now, 0, 0),
          reporterId, reporterRole, type === 'voiture_radar' ? plate : null,
          type === 'camera' ? street : null, type === 'camera' ? side : null],
      );
      if (reporterId) {
        await client.query(`INSERT INTO crowd.report_voice (report_id, voter_id, voice) VALUES ($1, $2, 'reported')`, [id, reporterId]);
      }
      return { report: await publicReport(client, id), merged: false, authorFirstConfirmed: null };
    });
  }

  /** "Toujours là": one more confirmation, unless this person already said so. */
  confirm(id, voterId) {
    return this.vote(id, voterId, 'confirm');
  }

  /** "Plus là": one more contradiction; under the minimum score the report closes. */
  deny(id, voterId) {
    return this.vote(id, voterId, 'deny');
  }

  async vote(id, voterId, voice) {
    return transaction(async (client) => {
      const { rows } = await client.query(`SELECT * FROM crowd.report WHERE id = $1 AND status = 'live' FOR UPDATE`, [id]);
      const report = rows[0];
      if (!report) return null;
      const outcome = await addVoice(client, report, voterId, voice, null);
      if (outcome.closed) return { removed: true, id };
      return { report: await publicReport(client, id), authorFirstConfirmed: outcome.firstConfirmation ? report.reporter_id : null };
    });
  }

  /** Admin moderation: the report stops being served (kept in the history). */
  async removeById(id) {
    const { rowCount } = await db.query(`UPDATE crowd.report SET status = 'removed', closed_at = now() WHERE id = $1 AND status = 'live'`, [id]);
    return rowCount > 0;
  }

  /** Live reports around a point, nearest first (radar cars with a plate come as zones). */
  async near(lat, lon, radiusM, limit) {
    const { rows } = await db.query(
      `SELECT ${PUBLIC_COLUMNS}, ST_Distance(r.geom_m, ${at('$2', '$1')}) AS distance_m
       FROM crowd.report r
       WHERE r.status = 'live' AND r.expires_at > now()
         AND NOT (r.type = 'voiture_radar' AND r.plate IS NOT NULL)
         AND ST_DWithin(r.geom_m, ${at('$2', '$1')}, $3)
       ORDER BY distance_m
       LIMIT $4`,
      [lat, lon, radiusM, limit],
    );
    const now = Date.now();
    return rows.map((row) => ({ ...toPublic(row, now), distanceM: Math.round(row.distance_m) }));
  }

  /**
   * Live radar-car reports with a plate, as probable zones (one per plate): the centre is a
   * recency-weighted mean, the radius shrinks as reports pile up. The plate never leaves.
   */
  async zonesNear(lat, lon, radiusM) {
    const { rows } = await db.query(
      `SELECT plate, ST_Y(geom) AS lat, ST_X(geom) AS lon,
              (extract(epoch FROM created_at) * 1000)::bigint AS created_at,
              (extract(epoch FROM expires_at) * 1000)::bigint AS expires_at
       FROM crowd.report
       WHERE status = 'live' AND expires_at > now() AND type = 'voiture_radar' AND plate IS NOT NULL`,
    );
    const now = Date.now();
    const byPlate = new Map();
    for (const r of rows) {
      if (!byPlate.has(r.plate)) byPlate.set(r.plate, []);
      byPlate.get(r.plate).push(r);
    }
    const zones = [];
    for (const [plate, reports] of byPlate) {
      let wLat = 0;
      let wLon = 0;
      let wSum = 0;
      for (const r of reports) {
        const createdAt = Number(r.created_at);
        const w = Math.max(0.15, 1 - (now - createdAt) / Math.max(1, Number(r.expires_at) - createdAt));
        wLat += r.lat * w;
        wLon += r.lon * w;
        wSum += w;
      }
      const cLat = wLat / wSum;
      const cLon = wLon / wSum;
      const distanceM = haversine(lat, lon, cLat, cLon);
      if (distanceM > radiusM) continue;
      zones.push({
        id: hashPlate(plate),
        lat: cLat,
        lon: cLon,
        radiusM: Math.round(Math.min(config.zoneMaxRadiusM, Math.max(config.zoneMinRadiusM, config.zoneBaseRadiusM / reports.length))),
        count: reports.length,
        distanceM: Math.round(distanceM),
      });
    }
    return zones.sort((a, b) => a.distanceM - b.distanceM);
  }

  /** Live reports of [type] inside a lon/lat box, newest first, at most [limit]: their positions. */
  async liveInBox(type, { south, west, north, east }, limit) {
    const { rows } = await db.query(
      `SELECT ST_Y(geom) AS lat, ST_X(geom) AS lon
       FROM crowd.report
       WHERE status = 'live' AND expires_at > now() AND type = $1
         AND geom && ST_MakeEnvelope($2, $3, $4, $5, 4326)
       ORDER BY created_at DESC
       LIMIT $6`,
      [type, west, south, east, north, limit],
    );
    return rows;
  }

  get meta() {
    return { count: this.live };
  }
}

/** The live report this one would duplicate, locked for update; null when it is a new event. */
async function findSame(client, { type, lat, lon, course, wayId }) {
  const { rows } = await client.query(
    `SELECT r.* FROM crowd.report r
     WHERE r.status = 'live' AND r.expires_at > now() AND r.type = $1
       AND NOT (r.type = 'voiture_radar' AND r.plate IS NOT NULL)
       AND ST_DWithin(r.geom_m, ${at('$3', '$2')}, $4)
       AND (r.course IS NULL OR $5::float8 IS NULL OR crowd.angle_diff(r.course, $5::float8) <= $6)
       AND (r.way_id IS NULL OR $7::bigint IS NULL OR r.way_id = $7::bigint OR EXISTS (
             SELECT 1 FROM signs.road a JOIN signs.road b ON ST_DWithin(a.geom_m, b.geom_m, $8)
             WHERE a.way_id = r.way_id AND b.way_id = $7::bigint))
     ORDER BY ST_Distance(r.geom_m, ${at('$3', '$2')})
     LIMIT 1
     FOR UPDATE OF r`,
    [type, lat, lon, config.reportMergeRadiusM[type] ?? 150, course, config.reportMergeSameWayDeg, wayId, config.reportMergeRoadTouchM],
  );
  return rows[0] ?? null;
}

/**
 * One person's voice on a report, replacing their previous one. Counters move only when
 * the voice changes sides (there / gone); the author's own "there" never counts as a
 * confirmation. [moveTo] brings a moving event to where it was seen last.
 */
async function addVoice(client, report, voterId, voice, moveTo) {
  const isAuthor = voterId != null && voterId === report.reporter_id;
  let previous = null;
  if (voterId != null) {
    const { rows } = await client.query('SELECT voice FROM crowd.report_voice WHERE report_id = $1 AND voter_id = $2', [report.id, voterId]);
    previous = rows[0]?.voice ?? (isAuthor ? 'reported' : null);
  }
  const stored = isAuthor && POSITIVE.has(voice) ? 'reported' : voice;
  const wasThere = previous == null ? null : POSITIVE.has(previous);
  const isThere = POSITIVE.has(stored);

  let confirmations = 0;
  let contradictions = 0;
  let reporters = 0;
  if (wasThere !== isThere) {
    if (wasThere === true && previous !== 'reported') { confirmations -= 1; reporters -= 1; }
    if (wasThere === false) contradictions -= 1;
    if (isThere && stored !== 'reported') { confirmations += 1; reporters += 1; }
    if (!isThere) contradictions += 1;
  }

  if (voterId != null) {
    await client.query(
      `INSERT INTO crowd.report_voice (report_id, voter_id, voice) VALUES ($1, $2, $3)
       ON CONFLICT (report_id, voter_id) DO UPDATE SET voice = EXCLUDED.voice, at = now()`,
      [report.id, voterId, stored],
    );
  }
  if (confirmations === 0 && contradictions === 0 && !moveTo) return { firstConfirmation: false, closed: false };

  const next = {
    confirmations: Math.max(0, report.confirmations + confirmations),
    contradictions: Math.max(0, report.contradictions + contradictions),
  };
  const model = scoreModel(report.type);
  const createdAt = new Date(report.created_at).getTime();
  const expiresAt = expiryAfter(model, createdAt, next.confirmations, next.contradictions);
  const score = crowdScore(model, Date.now() - createdAt, next.confirmations, next.contradictions);
  const closed = !isThere && !model.persistent && score < config.reportScoreMinimum;
  await client.query(
    `UPDATE crowd.report SET
       confirmations = $2, contradictions = $3, reporters = greatest(1, reporters + $4),
       expires_at = to_timestamp($5 / 1000.0),
       last_reported_at = CASE WHEN $6 THEN now() ELSE last_reported_at END,
       status = CASE WHEN $7 THEN 'denied' ELSE status END,
       closed_at = CASE WHEN $7 THEN now() ELSE closed_at END,
       geom = CASE WHEN $8::float8 IS NULL THEN geom ELSE ST_SetSRID(ST_MakePoint($9, $8), 4326) END,
       geom_m = CASE WHEN $8::float8 IS NULL THEN geom_m ELSE ${at('$9', '$8')} END,
       way_id = coalesce($10::bigint, way_id)
     WHERE id = $1`,
    [report.id, next.confirmations, next.contradictions, reporters, expiresAt, isThere, closed,
      moveTo?.lat ?? null, moveTo?.lon ?? null, moveTo?.wayId ?? null],
  );
  return { firstConfirmation: report.confirmations === 0 && next.confirmations === 1, closed };
}

async function publicReport(client, id) {
  const { rows } = await client.query(`SELECT ${PUBLIC_COLUMNS} FROM crowd.report r WHERE r.id = $1`, [id]);
  return rows[0] ? toPublic(rows[0], Date.now()) : null;
}

/** What the app sees: never the plate nor the author. */
function toPublic(row, now) {
  const model = scoreModel(row.type);
  const createdAt = Number(row.created_at);
  return {
    id: row.id,
    type: row.type,
    lat: row.lat,
    lon: row.lon,
    createdAt,
    expiresAt: Number(row.expires_at),
    confirmations: row.confirmations,
    contradictions: row.contradictions,
    reporters: row.reporters,
    reporterRole: row.reporter_role,
    direction: row.direction,
    bearing: row.bearing,
    course: row.course,
    street: row.street,
    side: row.side,
    // Intrinsic score: time + crowd. The app multiplies it by the road, direction and
    // distance factors, which depend on the driver asking.
    score: Math.round(crowdScore(model, now - createdAt, row.confirmations, row.contradictions)),
    impactM: model.impactM,
    persistent: model.persistent === true,
  };
}

function scoreModel(type) {
  return config.reportScore[type] ?? config.reportScore.hazard;
}

function norm(course) {
  return ((course % 360) + 360) % 360;
}

/** Opaque, stable id for a plate — the plate itself never leaves the server. */
function hashPlate(plate) {
  return createHash('sha256').update(String(plate).toUpperCase().replace(/\s/g, '')).digest('hex').slice(0, 16);
}

export const reportStore = new ReportStore();
