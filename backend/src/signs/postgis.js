import { config } from '../config.js';
import { db } from '../db.js';
import { angleBetween, bearingDeg } from '../radars/geo.js';

/**
 * Signalisation v2, read side: the tables signalisation/build.sql publishes in schema "signs" —
 * every car road with its limit in each direction, every sign once, on its road and oriented.
 *
 * The road under a driver is never just the nearest one: candidates close to the position are
 * weighed by distance, by how far their line is from the driver's course (and whether a
 * one-way street runs that way) and by continuity with the road the driver was on.
 */

/** Metres of distance one degree of course mismatch is worth. */
const MISALIGN_M_PER_DEG = 0.3;
/** Going the wrong way up a one-way street is almost never where the driver is. */
const WRONG_WAY_M = 25;
/** Staying on the same road wins over a nearby one. */
const SAME_ROAD_BONUS_M = 6;
/** A sign with no orientation counts along a route only this close to its line. */
const UNORIENTED_MAX_OFF_M = 6;
/** Along a route, a sign facing more than this away from the route's course is for other traffic. */
const ROUTE_COURSE_MAX_DEG = 60;
/** Kinds never shown as signs: speed signs only feed the limits. */
const HIDDEN_KINDS = new Set(['speed_sign']);

/**
 * The road a driver is on among [candidates] ({ way_id, oneway, maxspeed_fwd, maxspeed_bwd,
 * d, course }), with the way they travel along it. Null when there is none.
 */
export function pickRoad(candidates, bearing, previousWayId = null) {
  let best = null;
  for (const road of candidates) {
    let forward = road.oneway !== -1;
    let misalign = 0;
    if (bearing != null && road.course != null) {
      const diff = angleBetween(bearing, road.course);
      forward = diff <= 90;
      misalign = forward ? diff : 180 - diff;
    }
    let cost = road.d + misalign * MISALIGN_M_PER_DEG;
    if ((road.oneway === 1 && !forward) || (road.oneway === -1 && forward)) cost += WRONG_WAY_M;
    if (previousWayId != null && String(road.way_id) === String(previousWayId)) cost -= SAME_ROAD_BONUS_M;
    if (best === null || cost < best.cost) best = { road, forward, cost };
  }
  if (!best) return null;
  const { road, forward } = best;
  // Without a course, a road with a different limit each way cannot say which one applies.
  const known = bearing != null || road.maxspeed_fwd === road.maxspeed_bwd;
  return {
    wayId: String(road.way_id),
    highway: road.highway,
    d: road.d,
    forward,
    limit: known ? (forward ? road.maxspeed_fwd : road.maxspeed_bwd) : null,
  };
}

const CANDIDATES_SQL = `
  WITH p AS (SELECT ST_Transform(ST_SetSRID(ST_MakePoint($2::float8, $1::float8), 4326), 2154) AS g)
  SELECT r.way_id, r.highway, r.oneway, r.maxspeed_fwd, r.maxspeed_bwd,
         ST_Distance(r.geom_m, p.g) AS d,
         signs.course_at(r.geom_m, ST_LineLocatePoint(r.geom_m, p.g)) AS course
  FROM signs.road r, p
  WHERE ST_DWithin(r.geom_m, p.g, $3)
  ORDER BY r.geom_m <-> p.g
  LIMIT 8`;

// Speed-limit changes drivers validated (schema crowd), nearest first.
const OVERRIDES_SQL = `
  WITH p AS (SELECT ST_Transform(ST_SetSRID(ST_MakePoint($2::float8, $1::float8), 4326), 2154) AS g)
  SELECT o.id, o.new_kmh, o.course, ST_Distance(o.zone_m, p.g) AS d
  FROM crowd.speed_limit_change o, p
  WHERE o.status = 'validated' AND ST_DWithin(o.zone_m, p.g, $3)
  ORDER BY d
  LIMIT 4`;

/**
 * A change drivers validated wins over the mapped limit — for the way it was validated for,
 * and unless the mapped road is clearly closer.
 */
function withOverride(road, overrides, course) {
  const over = overrides.find((o) => o.course == null || course == null || angleBetween(o.course, course) <= config.speedLimitSameWayDeg);
  if (!over || (road && over.d > road.d + config.speedLimitOverrideTieM)) return road;
  return { ...(road ?? { wayId: null, highway: null, d: over.d, forward: true }), limit: over.new_kmh, changeId: over.id };
}

/**
 * The road under the driver and its limit for the way they go, a validated change included:
 * { limit, wayId, highway, d, forward, changeId? } or null.
 */
export async function roadAt(lat, lon, { bearing = null, previousWayId = null } = {}) {
  const [candidates, overrides] = await Promise.all([
    db.query(CANDIDATES_SQL, [lat, lon, config.signRoadMaxDistM]),
    db.query(OVERRIDES_SQL, [lat, lon, config.signRoadMaxDistM]),
  ]);
  return withOverride(pickRoad(candidates.rows, bearing, previousWayId), overrides.rows, bearing);
}

/** A route as a clean LineString: valid [lon, lat] pairs, no repeated point. */
function lineOf(coords) {
  const points = [];
  for (const c of Array.isArray(coords) ? coords : []) {
    if (!Array.isArray(c) || !Number.isFinite(c[0]) || !Number.isFinite(c[1])) continue;
    const last = points[points.length - 1];
    if (last && last[0] === c[0] && last[1] === c[1]) continue;
    points.push([c[0], c[1]]);
  }
  return points.length >= 2 ? { type: 'LineString', coordinates: points } : null;
}

const ROUTE_SIGNS_SQL = `
  WITH line AS (SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON($1), 4326), 2154) AS g),
  piece AS (SELECT ST_Subdivide(line.g, 64) AS g FROM line),
  hit AS (
    SELECT DISTINCT ON (s.id) s.id, s.kind, s.value, s.course, s.geom, s.geom_m
    FROM piece JOIN signs.sign s ON ST_DWithin(s.geom_m, piece.g, $2)
  )
  SELECT h.id, h.kind, h.value, h.course, ST_Y(h.geom) AS lat, ST_X(h.geom) AS lon,
         ST_LineLocatePoint(line.g, h.geom_m) AS frac,
         ST_Distance(line.g, h.geom_m) AS off_m,
         signs.course_at(line.g, ST_LineLocatePoint(line.g, h.geom_m)) AS route_course
  FROM hit h, line`;

const ROUTE_ROADS_SQL = `
  WITH line AS (SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON($1), 4326), 2154) AS g),
  pts AS (
    SELECT dp.path[1] AS i, dp.geom AS g
    FROM line, ST_DumpPoints(ST_LineInterpolatePoints(line.g, least(1, $2 / greatest(ST_Length(line.g), 1)))) dp
  )
  SELECT pts.i, ST_Y(ST_Transform(pts.g, 4326)) AS lat, ST_X(ST_Transform(pts.g, 4326)) AS lon,
         c.way_id, c.highway, c.oneway, c.maxspeed_fwd, c.maxspeed_bwd, c.d, c.course,
         o.id AS over_id, o.new_kmh AS over_kmh, o.course AS over_course, o.d AS over_d
  FROM pts
  LEFT JOIN LATERAL (
    SELECT r.way_id, r.highway, r.oneway, r.maxspeed_fwd, r.maxspeed_bwd,
           ST_Distance(r.geom_m, pts.g) AS d,
           signs.course_at(r.geom_m, ST_LineLocatePoint(r.geom_m, pts.g)) AS course
    FROM signs.road r
    WHERE ST_DWithin(r.geom_m, pts.g, $3)
    ORDER BY r.geom_m <-> pts.g
    LIMIT 4
  ) c ON true
  LEFT JOIN LATERAL (
    SELECT x.id, x.new_kmh, x.course, ST_Distance(x.zone_m, pts.g) AS d
    FROM crowd.speed_limit_change x
    WHERE x.status = 'validated' AND ST_DWithin(x.zone_m, pts.g, $3)
    ORDER BY d
    LIMIT 1
  ) o ON true
  ORDER BY pts.i`;

/**
 * What a driver meets along a route, in order: the signs for the traffic going that way,
 * and a "speed" entry wherever the limit changes. Same shape as the dataset's route().
 */
export async function route(coords) {
  const line = lineOf(coords);
  if (!line) return [];
  const json = JSON.stringify(line);
  const [signs, roads] = await Promise.all([
    db.query(ROUTE_SIGNS_SQL, [json, config.signRouteSignBufferM]),
    db.query(ROUTE_ROADS_SQL, [json, config.signRouteLimitStepM, config.signRoadMaxDistM]),
  ]);

  const out = [];
  for (const s of signs.rows) {
    if (HIDDEN_KINDS.has(s.kind)) continue;
    const forThisWay = s.course == null
      ? s.off_m <= UNORIENTED_MAX_OFF_M
      : s.route_course == null || angleBetween(s.course, s.route_course) <= ROUTE_COURSE_MAX_DEG;
    if (!forThisWay) continue;
    out.push({ at: s.frac, sign: { id: s.id, type: s.kind, lat: s.lat, lon: s.lon, course: s.course } });
  }
  for (const change of limitChanges(roads.rows)) out.push(change);

  out.sort((a, b) => a.at - b.at);
  return out.slice(0, config.signMaxElements).map((entry) => entry.sign);
}

/**
 * Limit changes from the route's samples: each sample's road (its course taken from the samples
 * around it, continuity from the previous one); a new limit counts once two samples in a row
 * agree, so a cross street never flickers the limit.
 */
function limitChanges(rows) {
  const samples = new Map(); // i -> { lat, lon, candidates }
  for (const row of rows) {
    let sample = samples.get(row.i);
    if (!sample) samples.set(row.i, (sample = { i: row.i, lat: row.lat, lon: row.lon, candidates: [], overrides: [] }));
    if (row.way_id != null) sample.candidates.push(row);
    if (row.over_id != null && sample.overrides.length === 0) {
      sample.overrides.push({ id: row.over_id, new_kmh: row.over_kmh, course: row.over_course, d: row.over_d });
    }
  }
  const ordered = [...samples.values()].sort((a, b) => a.i - b.i);
  const out = [];
  let previousWayId = null;
  let shown = null;
  let pending = null;
  ordered.forEach((sample, k) => {
    const before = ordered[Math.max(0, k - 1)];
    const after = ordered[Math.min(ordered.length - 1, k + 1)];
    const course = before === after ? null : bearingDeg(before.lat, before.lon, after.lat, after.lon);
    const picked = withOverride(pickRoad(sample.candidates, course, previousWayId), sample.overrides, course);
    previousWayId = picked?.wayId ?? previousWayId;
    const limit = picked?.limit ?? null;
    if (limit == null || limit === shown) {
      pending = null;
      return;
    }
    if (pending && pending.limit === limit) {
      out.push({ at: pending.k / Math.max(1, ordered.length - 1), sign: { type: 'speed', v: limit, lat: pending.lat, lon: pending.lon } });
      shown = limit;
      pending = null;
    } else {
      pending = { limit, k, lat: sample.lat, lon: sample.lon };
    }
  });
  return out;
}

const NEAR_SQL = `
  WITH p AS (SELECT ST_Transform(ST_SetSRID(ST_MakePoint($2::float8, $1::float8), 4326), 2154) AS g)
  SELECT s.id, s.kind, s.course, ST_Y(s.geom) AS lat, ST_X(s.geom) AS lon
  FROM signs.sign s, p
  WHERE ST_DWithin(s.geom_m, p.g, $3) AND s.kind <> 'speed_sign'
  ORDER BY s.geom_m <-> p.g
  LIMIT $4`;

/** Signs around a point, nearest first. */
export async function near(lat, lon, radiusM, limit) {
  const { rows } = await db.query(NEAR_SQL, [lat, lon, radiusM, limit]);
  return rows.map((s) => ({ id: s.id, type: s.kind, lat: s.lat, lon: s.lon, course: s.course }));
}

/** When the published signalisation was built, from which OSM extract. */
export async function meta() {
  // Every column: builds since the nearby services also count their places.
  const { rows } = await db.query('SELECT * FROM signs.meta');
  return rows[0] ?? null;
}
