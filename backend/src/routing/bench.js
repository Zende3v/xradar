import { config } from '../config.js';
import { db } from '../db.js';

/**
 * The bench (D1.7, D1.8) timed our routes, ORS's then Valhalla's, against TomTom with the day's
 * traffic, and TomTom's own best route, until TomTom left on 29/09 (HERE since). Its runs stay
 * readable (routing.bench_run): the measures behind the move to Valhalla.
 */

/** The km on minor roads (config.benchMinorRoadClasses, P1.3) of a km-per-class record. */
export function minorKm(kmByClass) {
  if (!kmByClass) return null;
  const km = config.benchMinorRoadClasses.reduce((sum, highway) => sum + (Number(kmByClass[highway]) || 0), 0);
  return Math.round(km * 1000) / 1000;
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
