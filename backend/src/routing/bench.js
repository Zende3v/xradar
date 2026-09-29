import { readFile } from 'node:fs/promises';
import { config } from '../config.js';
import { db } from '../db.js';
import { tomtomAllows, tomtomUsedFor } from '../traffic/budget.js';
import { bestRoute, trafficAlong } from '../traffic/tomtom.js';
import { computeRoute, computeValhallaRoute } from './engine.js';
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
// What one trip costs TomTom: our route timed, then its own best route; with Valhalla on, its
// route timed too.
const tomtomPerTrip = () => (config.valhallaEnabled ? 3 : 2);
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
    if (!tomtomAllows('bench') || tomtomUsedFor('bench') + tomtomPerTrip() > config.benchTomtomDailyMax) {
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
  // The same trip through Valhalla (phase 2), measured apart: its failure never spoils ours.
  const valhalla = config.valhallaEnabled ? await measureValhalla(trip, from, to) : null;
  // No route at all: nothing to compare, TomTom is not asked.
  if (route.error && !valhalla?.route) return { ok: false, error: `route: ${route.error}`, latencyMs, valhalla };

  let ourTomtom = null;
  let ourKm = null;
  if (route.error) {
    errors.push(`route: ${route.error}`);
  } else {
    await pause();
    ourTomtom = await attempt('TomTom route', trafficAlong(toLatLon(route.coordinates), { use: 'bench' }));
    ourKm = await attempt('roads', kmByClass(route.coordinates));
  }
  await pause();
  const best = await attempt('TomTom best', bestRoute(from, to, { avoid: trip.avoid, use: 'bench' }));
  const bestKm = best ? await attempt('roads best', kmByClass(best.coordinates)) : null;
  return {
    ok: errors.length === 0,
    error: errors.length ? errors.join('; ') : null,
    latencyMs,
    route: route.error ? null : route,
    facts: route.error ? null : routeFacts(route),
    ourTomtomS: ourTomtom?.travelS ?? null,
    best,
    ourKm,
    bestKm,
    valhalla,
  };
}

/**
 * The trip through Valhalla alone: its route, TomTom's time for it with today's traffic and its
 * km per road class. What failed is said in `error`.
 */
async function measureValhalla(trip, from, to) {
  const startedAt = Date.now();
  const route = await computeValhallaRoute(from, to, trip.avoid)
    .catch((e) => ({ error: String(e?.code ?? e?.message ?? e) }));
  const latencyMs = Date.now() - startedAt;
  if (!route || route.error) return { route: null, latencyMs, error: `valhalla: ${route?.error ?? 'no route'}`.slice(0, 160) };
  const errors = [];
  await pause();
  const tomtom = await trafficAlong(toLatLon(route.coordinates), { use: 'bench' }).catch((e) => {
    errors.push(`TomTom valhalla: ${String(e.message || e).slice(0, 120)}`);
    return null;
  });
  const km = await kmByClass(route.coordinates).catch((e) => {
    errors.push(`roads valhalla: ${String(e.message || e).slice(0, 120)}`);
    return null;
  });
  return {
    route,
    facts: routeFacts(route),
    tomtomS: tomtom?.travelS ?? null,
    km,
    latencyMs,
    error: errors.length ? errors.join('; ') : null,
  };
}

const pause = () => new Promise((resolve) => setTimeout(resolve, TOMTOM_GAP_MS));
const toLatLon = (coordinates) => coordinates.map(([lon, lat]) => [lat, lon]);

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
  const v = m.valhalla;
  await db.query(
    `INSERT INTO routing.bench_run (slot, trip_id, engine, map_version, ok, error, latency_ms,
       our_distance_m, our_duration_s, our_tomtom_s, best_distance_m, best_tomtom_s,
       our_km_by_class, best_km_by_class, uturn_start, steps, avoid, from_lat, from_lon, to_lat, to_lon,
       valhalla_map_version, valhalla_error, valhalla_latency_ms, valhalla_distance_m, valhalla_duration_s,
       valhalla_tomtom_s, valhalla_km_by_class, valhalla_uturn_start, valhalla_steps)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21,
       $22, $23, $24, $25, $26, $27, $28, $29, $30)`,
    [
      slot, trip.id, m.facts?.engine ?? null, m.route?.mapVersion ?? null, m.ok, m.error, m.latencyMs,
      whole(m.facts?.distanceM), whole(m.facts?.durationS), whole(m.ourTomtomS), whole(m.best?.distanceM), whole(m.best?.travelS),
      m.ourKm ? JSON.stringify(m.ourKm) : null, m.bestKm ? JSON.stringify(m.bestKm) : null,
      m.facts?.uturnStart ?? null, m.facts?.steps ?? null, trip.avoid,
      trip.from.lat, trip.from.lon, trip.to.lat, trip.to.lon,
      v?.route?.mapVersion ?? null, v?.error ?? null, v ? whole(v.latencyMs) : null,
      whole(v?.facts?.distanceM), whole(v?.facts?.durationS), whole(v?.tomtomS),
      v?.km ? JSON.stringify(v.km) : null, v?.facts?.uturnStart ?? null, v?.facts?.steps ?? null,
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
    valhalla: valhallaSummary(m.valhalla, bestTomtomS),
  };
}

/** Valhalla's side of a trip, null when Valhalla is off: its times, gap to TomTom's best, km. */
function valhallaSummary(v, bestTomtomS) {
  if (!v) return null;
  const tomtomS = whole(v.tomtomS);
  return {
    ok: Boolean(v.route) && !v.error,
    error: v.error ?? null,
    latencyMs: whole(v.latencyMs),
    durationS: whole(v.facts?.durationS),
    tomtomS,
    gapS: tomtomS != null && bestTomtomS != null ? tomtomS - bestTomtomS : null,
    km: km(v.facts?.distanceM),
    minorKm: minorKm(v.km),
    uturnStart: v.facts?.uturnStart ?? null,
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
            avoid, from_lat, from_lon, to_lat, to_lon, valhalla_map_version, valhalla_error, valhalla_latency_ms,
            valhalla_distance_m, valhalla_duration_s, valhalla_tomtom_s, valhalla_km_by_class,
            valhalla_uturn_start, valhalla_steps
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
    // Null for runs from before phase 2, or with Valhalla off.
    valhalla: row.valhalla_latency_ms == null ? null : {
      mapVersion: row.valhalla_map_version,
      error: row.valhalla_error,
      latencyMs: row.valhalla_latency_ms,
      distanceM: row.valhalla_distance_m,
      durationS: row.valhalla_duration_s,
      tomtomS: row.valhalla_tomtom_s,
      gapS: row.valhalla_tomtom_s != null && row.best_tomtom_s != null ? row.valhalla_tomtom_s - row.best_tomtom_s : null,
      kmByClass: row.valhalla_km_by_class,
      minorKm: minorKm(row.valhalla_km_by_class),
      uturnStart: row.valhalla_uturn_start,
      steps: row.valhalla_steps,
    },
  }));
}

const whole = (value) => (typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : null);
const km = (metres) => (typeof metres === 'number' && Number.isFinite(metres) ? Math.round(metres / 10) / 100 : null);
