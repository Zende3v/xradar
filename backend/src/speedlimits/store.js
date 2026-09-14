import { createHash, randomUUID } from 'node:crypto';
import { accountStore } from '../accounts/store.js';
import { config } from '../config.js';
import { transaction } from '../crowd/schema.js';
import { db } from '../db.js';
import { crowdScore } from '../reports/score.js';
import { roadAt } from '../signs/postgis.js';

const PENDING = 'pending';
const VALIDATED = 'validated';
/** Closed without ever applying: dropped from the history after speedLimitHistoryMs. */
const DROPPABLE = ['expired', 'outdated', 'rejected'];
/** Proposals are decided one at a time. */
const LOCK = 'speed-limits';

const point = (lon, lat) => `ST_SetSRID(ST_MakePoint(${lon}, ${lat}), 4326)`;
const pointM = (lon, lat) => `ST_Transform(${point(lon, lat)}, 2154)`;

const CHANGE_COLUMNS = `
  c.*, ST_Y(c.geom) AS lat, ST_X(c.geom) AS lon,
  (extract(epoch FROM c.created_at) * 1000)::bigint AS created_ms,
  (extract(epoch FROM c.updated_at) * 1000)::bigint AS updated_ms,
  (extract(epoch FROM c.validated_at) * 1000)::bigint AS validated_ms`;

/**
 * Speed-limit maintenance, in PostGIS (schema crowd). A driver who sees a new sign proposes its
 * limit ("50 → 70"). The proposals made at one spot — close to one another, for the same way,
 * from the same former limit — form one change, scored with the report formula
 * (reports/score.js), one voice per person. Nothing moves on a single proposal: the new limit
 * applies once it is strong enough (config.speedLimitScore), on the stretches of road its
 * supporters drove, and stays until a later change on the same stretch replaces it or an admin
 * removes it. The former limit is the one signs.road holds for that way. Every change keeps
 * its voices and events, so the history shows why a limit moved.
 */
class SpeedLimitStore {
  constructor() {
    this.timer = null;
    this.counts = { pending: 0, validated: 0, total: 0 };
  }

  async start() {
    await this.sweep();
    this.timer = setInterval(() => {
      this.sweep().catch((e) => console.error('[speed-limits] sweep failed:', e.message));
    }, 10 * 60 * 1000);
    if (this.timer.unref) this.timer.unref();
  }

  /**
   * A driver proposes [newKmh] where they are. [displayedKmh] / [displayedSource] are what their
   * HUD showed ("map" or "radar"). Returns { change, firstVoice } or { error };
   * { change: null } when they confirm a limit nobody proposed to change.
   */
  async report({ lat, lon, bearing, displayedKmh, displayedSource, newKmh, reporterId, reporterRole = 'guest' }) {
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return { error: 'lat and lon are required numbers' };
    if (!config.speedLimitValues.includes(newKmh)) {
      return { error: `newKmh must be one of ${config.speedLimitValues.join(', ')}` };
    }
    if (!reporterId) return { error: 'reporter required' };
    const course = Number.isFinite(bearing) ? norm(bearing) : null;
    const shown = Number.isInteger(displayedKmh) && displayedKmh >= 5 && displayedKmh <= 130 ? displayedKmh : null;

    // The limit the map holds here for this way, a validated change included; a radar's VMA
    // only counts where the map has none.
    const road = await roadAt(lat, lon, { bearing: course });
    const mapped = road?.limit ?? null;
    const oldKmh = mapped ?? (displayedSource === 'radar' ? shown : null);
    const oldSource = mapped != null ? 'map' : oldKmh != null ? 'radar' : 'unknown';

    const outcome = await transaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [LOCK]);
      let change = await openChangeFor(client, lat, lon, course, oldKmh);
      if (!change) {
        // "The limit is right" only means something against a proposal already there.
        if (newKmh === oldKmh) return { id: null, firstVoice: false, confirmed: [] };
        const { rows } = await client.query(
          `INSERT INTO crowd.speed_limit_change (id, status, geom, geom_m, course, old_kmh, old_source)
           VALUES ($1, 'pending', ${point('$3', '$2')}, ${pointM('$3', '$2')}, $4, $5, $6)
           RETURNING id`,
          [randomUUID(), lat, lon, course, oldKmh, oldSource],
        );
        change = { id: rows[0].id };
      }
      // One voice per person: a new proposal from the same driver replaces their previous one.
      const { rows: voice } = await client.query(
        `INSERT INTO crowd.speed_limit_voice (change_id, reporter_id, role, geom, geom_m, course, new_kmh, displayed_kmh)
         VALUES ($1, $2, $3, ${point('$5', '$4')}, ${pointM('$5', '$4')}, $6, $7, $8)
         ON CONFLICT (change_id, reporter_id) DO UPDATE SET
           role = EXCLUDED.role, geom = EXCLUDED.geom, geom_m = EXCLUDED.geom_m, course = EXCLUDED.course,
           new_kmh = EXCLUDED.new_kmh, displayed_kmh = EXCLUDED.displayed_kmh, at = now()
         RETURNING (xmax = 0) AS inserted`,
        [change.id, reporterId, reporterRole, lat, lon, course, newKmh, shown],
      );
      const firstVoice = voice[0].inserted;
      await addEvent(client, change.id, firstVoice ? 'reported' : 'revised', { newKmh, displayedKmh: shown, role: reporterRole });
      await reshape(client, change.id);
      const current = await changeRow(client, change.id);
      const confirmed = reporterRole === 'admin'
        ? await decideByAdmin(client, current, newKmh)
        : await evaluate(client, current);
      return { id: change.id, firstVoice, confirmed };
    });

    // As with a road report's first confirmation, the change counts as confirmed for its authors.
    for (const accountId of outcome.confirmed) accountStore.recordReportStat(accountId, 'reportsConfirmed');
    await this.refreshCounts();
    return { change: outcome.id ? await this.view(outcome.id) : null, firstVoice: outcome.firstVoice };
  }

  /** Expire pending changes whose voices aged out or whose former limit moved; drop old dead ones. */
  async sweep() {
    const { rows } = await db.query(`SELECT id FROM crowd.speed_limit_change WHERE status = $1`, [PENDING]);
    for (const { id } of rows) {
      await transaction(async (client) => {
        await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [LOCK]);
        const change = await changeRow(client, id);
        if (change?.status === PENDING) await evaluate(client, change);
      });
    }
    await db.query(
      `DELETE FROM crowd.speed_limit_change WHERE status = ANY ($1) AND closed_at < now() - make_interval(secs => $2)`,
      [DROPPABLE, config.speedLimitHistoryMs / 1000],
    );
    await this.refreshCounts();
  }

  /** Admin moderation: a validated change stops applying, a pending one is rejected. Both stay in the history. */
  async remove(id) {
    const found = await transaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [LOCK]);
      const change = await changeRow(client, id);
      if (!change) return false;
      if (change.status === VALIDATED) await close(client, change, 'removed', { by: 'admin' });
      else if (change.status === PENDING) await close(client, change, 'rejected', { by: 'admin' });
      return true;
    });
    if (!found) return null;
    await this.refreshCounts();
    return this.view(id);
  }

  /** Pending and validated changes around a position, nearest first. */
  async near(lat, lon, radiusM) {
    const { rows } = await db.query(
      `SELECT c.id, ST_Distance(c.geom_m, ${pointM('$2', '$1')}) AS distance_m
       FROM crowd.speed_limit_change c
       WHERE c.status IN ('pending', 'validated') AND ST_DWithin(c.geom_m, ${pointM('$2', '$1')}, $3)
       ORDER BY distance_m
       LIMIT 200`,
      [lat, lon, radiusM],
    );
    const out = [];
    for (const row of rows) {
      const change = await this.view(row.id);
      if (change) out.push({ ...change, distanceM: Math.round(row.distance_m) });
    }
    return out;
  }

  /** One change with its voices (authors reduced to an opaque tag), events and zone (moderation). */
  async history(id) {
    const change = await changeRow(db, id);
    if (!change) return null;
    const voices = await voicesOf(db, id);
    const { rows: events } = await db.query(
      `SELECT (extract(epoch FROM at) * 1000)::bigint AS at, type, detail FROM crowd.speed_limit_event WHERE change_id = $1 ORDER BY at, id`,
      [id],
    );
    const { rows: zone } = await db.query(
      `SELECT ST_AsGeoJSON(ST_Transform(zone_m, 4326))::json AS zone FROM crowd.speed_limit_change WHERE id = $1`,
      [id],
    );
    return {
      ...publicView(change, voices, Date.now()),
      supersededBy: change.superseded_by ?? null,
      wayIds: (change.way_ids ?? []).map(String),
      zone: zone[0]?.zone ?? null,
      votes: voices.map(({ reporter_id: reporterId, ...v }) => ({ ...v, reporter: tag(reporterId) })),
      events: events.map((e) => ({ at: Number(e.at), type: e.type, ...e.detail })),
    };
  }

  async view(id) {
    const change = await changeRow(db, id);
    if (!change) return null;
    return publicView(change, await voicesOf(db, id), Date.now());
  }

  async refreshCounts() {
    const { rows } = await db.query(
      `SELECT count(*) FILTER (WHERE status = 'pending')::int AS pending,
              count(*) FILTER (WHERE status = 'validated')::int AS validated,
              count(*)::int AS total
       FROM crowd.speed_limit_change`,
    );
    this.counts = rows[0];
  }

  get meta() {
    return this.counts;
  }
}

/** The pending change a proposal belongs to (locked): same former limit, same way, close by. */
async function openChangeFor(client, lat, lon, course, oldKmh) {
  const { rows } = await client.query(
    `SELECT c.id FROM crowd.speed_limit_change c
     WHERE c.status = 'pending' AND c.old_kmh IS NOT DISTINCT FROM $3::smallint
       AND (c.course IS NULL OR $4::float8 IS NULL OR crowd.angle_diff(c.course, $4::float8) <= $5)
       AND EXISTS (
         SELECT 1 FROM crowd.speed_limit_voice v
         WHERE v.change_id = c.id AND ST_DWithin(v.geom_m, ${pointM('$2', '$1')}, $6))
     ORDER BY ST_Distance(c.geom_m, ${pointM('$2', '$1')})
     LIMIT 1
     FOR UPDATE OF c`,
    [lat, lon, oldKmh, course, config.speedLimitSameWayDeg, config.speedLimitGroupRadiusM],
  );
  return rows[0] ?? null;
}

/** Centre of the voices and the mean course of those that had one. */
async function reshape(client, id) {
  await client.query(
    `UPDATE crowd.speed_limit_change c SET
       geom = v.centre, geom_m = ST_Transform(v.centre, 2154), course = coalesce(v.course, c.course), updated_at = now()
     FROM (
       SELECT ST_Centroid(ST_Collect(geom)) AS centre,
              CASE WHEN count(course) > 0 THEN
                ((degrees(atan2(avg(sin(radians(course))), avg(cos(radians(course))))) + 360)::numeric % 360)::real
              END AS course
       FROM crowd.speed_limit_voice WHERE change_id = $1
     ) v
     WHERE c.id = $1`,
    [id],
  );
}

/**
 * Apply the proposal that has become strong enough: the best-scored value, when it is not the
 * current limit, reaches the "high" relevance band, has enough distinct supporters and
 * outnumbers everyone else. A pending change whose voices all aged out expires; one whose
 * former limit no longer holds is outdated. Returns the accounts that saw it confirmed.
 */
async function evaluate(client, change) {
  const voices = await voicesOf(client, change.id);
  const { model, live, proposals } = tally(change, voices, Date.now());
  if (live.length === 0) {
    await close(client, change, 'expired');
    return [];
  }
  if (!(await stillHolds(change, live))) {
    await close(client, change, 'outdated');
    return [];
  }
  const top = proposals[0];
  if (!top || top.kmh === change.old_kmh) return [];
  if (top.score >= config.reportRelevance.high && top.supporters >= model.minSupporters && top.supporters > top.contradictions) {
    return apply(client, change, top.kmh, 'crowd', live, {
      score: Math.round(top.score),
      supporters: top.supporters,
      contradictions: top.contradictions,
    });
  }
  return [];
}

/** An admin's proposal applies at once; an admin confirming the current limit closes the change. */
async function decideByAdmin(client, change, newKmh) {
  if (newKmh === change.old_kmh) {
    await close(client, change, 'rejected', { by: 'admin' });
    return [];
  }
  return apply(client, change, newKmh, 'admin', await voicesOf(client, change.id), {});
}

/** Does the limit the change started from still hold where its drivers proposed it? */
async function stillHolds(change, live) {
  for (const v of live.slice(0, 3)) {
    let road;
    try {
      road = await roadAt(v.lat, v.lon, { bearing: change.course ?? v.course });
    } catch {
      return true; // cannot tell right now: keep it
    }
    const current = road?.limit ?? null;
    if (current === change.old_kmh || (change.old_source !== 'map' && current == null)) return true;
  }
  return false;
}

/**
 * The new limit applies on the stretches (within speedLimitZoneRadiusM of its supporters) of
 * the roads they drove that still read the former limit for this way — a side road with its
 * own limit keeps it. With no such road (an unknown spot), it applies where the sign was seen.
 * A later change on the same stretch replaces the earlier one, which stays in the history.
 */
async function apply(client, change, kmh, by, voices, detail) {
  const supporters = voices.filter((v) => v.new_kmh === kmh);
  const wayIds = new Set();
  for (const v of supporters) {
    const road = await roadAt(v.lat, v.lon, { bearing: change.course ?? v.course }).catch(() => null);
    if (!road?.wayId) continue;
    if (change.old_kmh == null || change.old_source !== 'map' || road.limit === change.old_kmh) wayIds.add(road.wayId);
  }
  const ids = [...wayIds];

  await client.query(
    `WITH supporters AS (
       SELECT ST_Collect(geom_m) AS g FROM crowd.speed_limit_voice WHERE change_id = $1 AND new_kmh = $2
     ),
     stretches AS (
       SELECT ST_Union(ST_Intersection(r.geom_m, ST_Buffer(s.g, $3))) AS g
       FROM signs.road r, supporters s
       WHERE r.way_id = ANY ($4::bigint[])
     )
     UPDATE crowd.speed_limit_change c SET
       status = 'validated', new_kmh = $2, way_ids = $4::bigint[], validated_at = now(), validated_by = $5,
       updated_at = now(), closed_at = NULL,
       zone_m = CASE WHEN st.g IS NULL OR ST_IsEmpty(st.g) THEN s.g ELSE st.g END
     FROM supporters s, stretches st
     WHERE c.id = $1`,
    [change.id, kmh, config.speedLimitZoneRadiusM, ids, by],
  );
  const { rows: superseded } = await client.query(
    `UPDATE crowd.speed_limit_change o SET status = 'superseded', superseded_by = $1, closed_at = now(), updated_at = now()
     FROM crowd.speed_limit_change c
     WHERE c.id = $1 AND o.id <> c.id AND o.status = 'validated'
       AND ST_DWithin(o.zone_m, c.zone_m, $2)
       AND (o.course IS NULL OR c.course IS NULL OR crowd.angle_diff(o.course, c.course) <= $3)
     RETURNING o.id`,
    [change.id, config.speedLimitOverlapM, config.speedLimitSameWayDeg],
  );
  for (const old of superseded) await addEvent(client, old.id, 'superseded', { by: change.id, newKmh: kmh });
  await addEvent(client, change.id, 'validated', { by, newKmh: kmh, roads: ids.length, ...detail });
  return supporters.map((v) => v.reporter_id);
}

async function close(client, change, status, detail = {}) {
  await client.query(`UPDATE crowd.speed_limit_change SET status = $2, closed_at = now(), updated_at = now() WHERE id = $1`, [change.id, status]);
  await addEvent(client, change.id, status, detail);
}

async function addEvent(client, changeId, type, detail) {
  await client.query(`INSERT INTO crowd.speed_limit_event (change_id, type, detail) VALUES ($1, $2, $3)`, [changeId, type, JSON.stringify(detail)]);
}

async function changeRow(client, id) {
  const { rows } = await client.query(`SELECT ${CHANGE_COLUMNS} FROM crowd.speed_limit_change c WHERE c.id = $1`, [id]);
  return rows[0] ?? null;
}

async function voicesOf(client, id) {
  const { rows } = await client.query(
    `SELECT reporter_id, role, ST_Y(geom) AS lat, ST_X(geom) AS lon, course, new_kmh, displayed_kmh,
            (extract(epoch FROM at) * 1000)::bigint AS at
     FROM crowd.speed_limit_voice WHERE change_id = $1 ORDER BY at`,
    [id],
  );
  return rows.map((v) => ({ ...v, at: Number(v.at) }));
}

function modelFor(change) {
  return config.speedLimitScore[change.old_kmh == null ? 'unknown' : 'known'];
}

/**
 * Score every value proposed in a change with the report formula: its supporters beyond the
 * first are confirmations, everyone proposing something else there contradicts it, and the
 * clock runs from its latest supporter. Voices older than the model's base duration no longer
 * count. Best first.
 */
function tally(change, voices, now) {
  const model = modelFor(change);
  const live = voices.filter((v) => now - v.at <= model.baseDurationMs);
  const byValue = new Map();
  for (const v of live) {
    const t = byValue.get(v.new_kmh) ?? { kmh: v.new_kmh, supporters: 0, lastAt: 0 };
    t.supporters++;
    t.lastAt = Math.max(t.lastAt, v.at);
    byValue.set(v.new_kmh, t);
  }
  const proposals = [...byValue.values()].map((t) => {
    const contradictions = live.length - t.supporters;
    return { ...t, contradictions, score: crowdScore(model, now - t.lastAt, t.supporters - 1, contradictions) };
  });
  proposals.sort((a, b) => b.score - a.score);
  return { model, live, proposals };
}

/** What anyone may see: no authors, no positions of individual drivers. */
function publicView(change, voices, now) {
  const { model, live, proposals } = tally(change, voices, now);
  return {
    id: change.id,
    status: change.status,
    lat: change.lat,
    lon: change.lon,
    bearing: change.course,
    oldKmh: change.old_kmh,
    oldSource: change.old_source,
    newKmh: change.new_kmh ?? null,
    reporters: live.length,
    required: model.minSupporters,
    proposals: proposals.map((p) => ({ kmh: p.kmh, supporters: p.supporters, score: Math.round(p.score) })),
    createdAt: Number(change.created_ms),
    updatedAt: Number(change.updated_ms),
    validatedAt: change.validated_ms == null ? null : Number(change.validated_ms),
    validatedBy: change.validated_by ?? null,
  };
}

function norm(course) {
  return ((course % 360) + 360) % 360;
}

function tag(id) {
  return createHash('sha256').update(String(id)).digest('hex').slice(0, 12);
}

export const speedLimitStore = new SpeedLimitStore();
