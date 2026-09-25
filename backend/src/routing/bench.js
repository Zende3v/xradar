import { readFile } from 'node:fs/promises';
import { config } from '../config.js';
import { db } from '../db.js';
import { tomtomUsedFor } from '../traffic/budget.js';
import { bestRoute, trafficAlong } from '../traffic/tomtom.js';
import { computeRoute } from './engine.js';
import { measure } from './geometry.js';
import { routeFacts } from './log.js';

/**
 * The bench (D1.7, D1.8): fixed France trips (config.benchTripsFile), a few per run, four runs a
 * day at rotating times (deploy/eona-bench.cron). Each trip: our route from the engine /api/route
 * uses (same keys, same budget), TomTom's time for it with today's traffic, and TomTom's own best
 * route with traffic — plus the km each one drives per road class. Runs inside the backend: the
 * ORS keys, the TomTom counter and the database pool are the ones the apps use. Stored in
 * routing.bench_run, coordinates included (fixed test trips, nobody's journey).
 */

export const BENCH_SLOTS = ['matin', 'midi', 'soir', 'nuit'];
// What one trip costs TomTom: our route timed, then its own best route.
const TOMTOM_PER_TRIP = 2;
// A pause between TomTom requests: its free tier refuses bursts.
const TOMTOM_GAP_MS = 250;

let running = false;

/**
 * One run: the next config.benchTripsPerRun trips after the cursor (routing.bench_state), each
 * measured and stored, the cursor moved past it. Stops early once the bench spent its TomTom
 * share of the day (config.benchTomtomDailyMax). One run at a time: null while another goes.
 */
export async function runBench({ slot = null } = {}) {
  if (running) return null;
  running = true;
  try {
    return await run(slot);
  } finally {
    running = false;
  }
}

async function run(slot) {
  const startedAt = new Date().toISOString();
  const trips = await loadTrips();
  const { rows: [state] } = await db.query(`SELECT next_trip FROM routing.bench_state WHERE name = 'trips'`);
  let next = (state?.next_trip ?? 0) % trips.length;
  const results = [];
  let stopped = null;
  for (let i = 0; i < Math.min(config.benchTripsPerRun, trips.length); i++) {
    if (tomtomUsedFor('bench') + TOMTOM_PER_TRIP > config.benchTomtomDailyMax) {
      stopped = 'bench TomTom share spent';
      break;
    }
    const trip = trips[next];
    const measured = await measureTrip(trip);
    await store(slot, trip, measured);
    results.push(summaryOf(trip, measured));
    next = (next + 1) % trips.length;
    await db.query(
      `INSERT INTO routing.bench_state (name, next_trip, updated_at) VALUES ('trips', $1, now())
       ON CONFLICT (name) DO UPDATE SET next_trip = EXCLUDED.next_trip, updated_at = now()`,
      [next],
    );
  }
  console.log(`[bench] ${slot ?? 'manual'}: ${results.length} trip(s), ${results.filter((r) => r.ok).length} ok${stopped ? ` — ${stopped}` : ''}`);
  return {
    slot,
    startedAt,
    trips: results.length,
    ok: results.filter((r) => r.ok).length,
    stopped,
    nextTrip: next,
    tomtom: { bench: tomtomUsedFor('bench'), benchDailyMax: config.benchTomtomDailyMax },
    results,
  };
}

/** The trips of the bench file, checked: an id, two points, the avoid options. */
async function loadTrips() {
  const raw = JSON.parse(await readFile(config.benchTripsFile, 'utf8'));
  const list = Array.isArray(raw) ? raw : raw?.trips;
  const point = (p) => Number.isFinite(p?.lat) && Number.isFinite(p?.lon);
  const trips = (Array.isArray(list) ? list : []).filter((t) => typeof t?.id === 'string' && point(t.from) && point(t.to));
  if (!trips.length) throw new Error(`no bench trip in ${config.benchTripsFile}`);
  return trips.map((t) => ({ ...t, avoid: Array.isArray(t.avoid) ? t.avoid.filter((a) => typeof a === 'string') : [] }));
}

/** One trip measured; what failed is said in `error`, the rest is kept. */
async function measureTrip(trip) {
  const from = { lat: trip.from.lat, lon: trip.from.lon };
  const to = { lat: trip.to.lat, lon: trip.to.lon };
  const errors = [];
  /** [work]'s answer, or null with what went wrong noted under [label]. */
  const attempt = (label, work) => work.catch((e) => {
    errors.push(`${label}: ${String(e.message || e).slice(0, 120)}`);
    return null;
  });
  const startedAt = Date.now();
  const route = await computeRoute(from, to, trip.avoid).catch((e) => ({ error: String(e.message || e) }));
  const latencyMs = Date.now() - startedAt;
  // No route of ours: nothing to compare, TomTom is not asked.
  if (route.error) return { ok: false, error: `route: ${route.error}`, latencyMs };

  const ourTomtom = await attempt('TomTom route', trafficAlong(route.coordinates.map(([lon, lat]) => [lat, lon]), { use: 'bench' }));
  await new Promise((resolve) => setTimeout(resolve, TOMTOM_GAP_MS));
  const best = await attempt('TomTom best', bestRoute(from, to, { avoid: trip.avoid, use: 'bench' }));
  const ourKm = await attempt('roads', kmByClass(route.coordinates));
  const bestKm = best ? await attempt('roads best', kmByClass(best.coordinates)) : null;
  return {
    ok: errors.length === 0,
    error: errors.length ? errors.join('; ') : null,
    latencyMs,
    route,
    facts: routeFacts(route),
    ourTomtomS: ourTomtom?.travelS ?? null,
    best,
    ourKm,
    bestKm,
  };
}

// The road under each sample of a route: the nearest car road within reach (like the limits along
// a route, signs/postgis.js), counted per OSM class. "none": no road there (a ferry, a gap).
const ROAD_CLASSES_SQL = `
  WITH line AS (SELECT ST_Transform(ST_SetSRID(ST_GeomFromGeoJSON($1), 4326), 2154) AS g),
  pts AS (
    SELECT dp.geom AS g
    FROM line, ST_DumpPoints(ST_LineInterpolatePoints(line.g, least(1, $2 / greatest(ST_Length(line.g), 1)))) dp
  )
  SELECT coalesce(c.highway, 'none') AS highway, count(*)::int AS samples, (SELECT ST_Length(g) FROM line) AS length_m
  FROM pts
  LEFT JOIN LATERAL (
    SELECT r.highway
    FROM signs.road r
    WHERE ST_DWithin(r.geom_m, pts.g, $3)
    ORDER BY r.geom_m <-> pts.g
    LIMIT 1
  ) c ON true
  GROUP BY 1`;

/** Km driven per road class along [coordinates] ([lon, lat]): { motorway: 12.345, …, none: 0.4 }. */
async function kmByClass(coordinates) {
  const points = coordinates.filter((c, i) => i === 0 || c[0] !== coordinates[i - 1][0] || c[1] !== coordinates[i - 1][1]);
  if (points.length < 2) return {};
  const lengthM = measure(points.map(([lon, lat]) => [lat, lon])).total;
  const step = Math.max(config.benchRoadStepM, lengthM / config.benchRoadMaxSamples);
  const { rows } = await db.query(ROAD_CLASSES_SQL, [
    JSON.stringify({ type: 'LineString', coordinates: points }),
    step,
    config.signRoadMaxDistM,
  ]);
  const samples = rows.reduce((sum, row) => sum + row.samples, 0);
  if (!samples) return {};
  const total = Number(rows[0].length_m);
  return Object.fromEntries(rows
    .map((row) => [row.highway, Math.round((row.samples / samples) * total) / 1000])
    .sort((a, b) => b[1] - a[1]));
}

/** The km on minor roads (config.benchMinorRoadClasses, P1.3) of a km-per-class record. */
export function minorKm(kmByClass) {
  if (!kmByClass) return null;
  const km = config.benchMinorRoadClasses.reduce((sum, highway) => sum + (Number(kmByClass[highway]) || 0), 0);
  return Math.round(km * 1000) / 1000;
}

async function store(slot, trip, m) {
  await db.query(
    `INSERT INTO routing.bench_run (slot, trip_id, engine, map_version, ok, error, latency_ms,
       our_distance_m, our_duration_s, our_tomtom_s, best_distance_m, best_tomtom_s,
       our_km_by_class, best_km_by_class, uturn_start, steps, avoid, from_lat, from_lon, to_lat, to_lon)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21)`,
    [
      slot, trip.id, m.facts?.engine ?? null, m.route?.mapVersion ?? null, m.ok, m.error, m.latencyMs,
      whole(m.facts?.distanceM), whole(m.facts?.durationS), whole(m.ourTomtomS), whole(m.best?.distanceM), whole(m.best?.travelS),
      m.ourKm ? JSON.stringify(m.ourKm) : null, m.bestKm ? JSON.stringify(m.bestKm) : null,
      m.facts?.uturnStart ?? null, m.facts?.steps ?? null, trip.avoid,
      trip.from.lat, trip.from.lon, trip.to.lat, trip.to.lon,
    ],
  );
}

/** A trip's line in a run's answer: times, gap to TomTom's best, km and minor-road km. */
function summaryOf(trip, m) {
  const ourTomtomS = whole(m.ourTomtomS);
  const bestTomtomS = whole(m.best?.travelS);
  return {
    id: trip.id,
    ok: m.ok,
    error: m.error,
    engine: m.facts?.engine ?? null,
    latencyMs: m.latencyMs,
    ourDurationS: whole(m.facts?.durationS),
    ourTomtomS,
    bestTomtomS,
    gapS: ourTomtomS != null && bestTomtomS != null ? ourTomtomS - bestTomtomS : null,
    ourKm: km(m.facts?.distanceM),
    bestKm: km(m.best?.distanceM),
    ourMinorKm: minorKm(m.ourKm),
    bestMinorKm: minorKm(m.bestKm),
    uturnStart: m.facts?.uturnStart ?? null,
  };
}

/**
 * The bench's measures, newest first: from [since] (ISO) on, [limit] at most. Each with the km
 * on minor roads of both routes, computed now from the km per class.
 */
export async function benchRuns({ since = null, limit = 200 } = {}) {
  const { rows } = await db.query(
    `SELECT id, at, slot, trip_id, engine, map_version, ok, error, latency_ms, our_distance_m, our_duration_s,
            our_tomtom_s, best_distance_m, best_tomtom_s, our_km_by_class, best_km_by_class, uturn_start, steps,
            avoid, from_lat, from_lon, to_lat, to_lon
     FROM routing.bench_run
     WHERE ($1::timestamptz IS NULL OR at >= $1)
     ORDER BY at DESC
     LIMIT $2`,
    [since, limit],
  );
  return rows.map((row) => ({
    id: Number(row.id),
    at: new Date(row.at).toISOString(),
    slot: row.slot,
    tripId: row.trip_id,
    engine: row.engine,
    mapVersion: row.map_version,
    ok: row.ok,
    error: row.error,
    latencyMs: row.latency_ms,
    ourDistanceM: row.our_distance_m,
    ourDurationS: row.our_duration_s,
    ourTomtomS: row.our_tomtom_s,
    bestDistanceM: row.best_distance_m,
    bestTomtomS: row.best_tomtom_s,
    gapS: row.our_tomtom_s != null && row.best_tomtom_s != null ? row.our_tomtom_s - row.best_tomtom_s : null,
    ourKmByClass: row.our_km_by_class,
    bestKmByClass: row.best_km_by_class,
    ourMinorKm: minorKm(row.our_km_by_class),
    bestMinorKm: minorKm(row.best_km_by_class),
    uturnStart: row.uturn_start,
    steps: row.steps,
    avoid: row.avoid ?? [],
    from: { lat: row.from_lat, lon: row.from_lon },
    to: { lat: row.to_lat, lon: row.to_lon },
  }));
}

const whole = (value) => (typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : null);
const km = (metres) => (typeof metres === 'number' && Number.isFinite(metres) ? Math.round(metres / 10) / 100 : null);
