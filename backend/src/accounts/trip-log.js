import { db } from '../db.js';

export const TRIP_MIN_METERS = 500;
export const TRIP_MIN_SECONDS = 60;
export const TRACE_MAX_POINTS = 1000;

/** Paquet GPS filtré ; champs facultatifs invalides remplacés par null. */
export function traceShape(body) {
  const seq = body?.seq;
  if (!Number.isInteger(seq) || seq < 0 || seq > 999) return { error: 'invalid trace seq' };
  if (!Array.isArray(body?.points) || body.points.length < 1 || body.points.length > TRACE_MAX_POINTS) {
    return { error: 'invalid trace points' };
  }
  const points = [];
  for (const point of body.points) {
    if (!Array.isArray(point)) continue;
    const [t, lat, lon, kmh, accuracyM, course] = point;
    if (!Number.isInteger(t) || t <= 0 || !Number.isFinite(lat) || Math.abs(lat) > 90
      || !Number.isFinite(lon) || Math.abs(lon) > 180) continue;
    points.push([
      t, lat, lon,
      Number.isFinite(kmh) && kmh >= 0 && kmh <= 400 ? kmh : null,
      Number.isFinite(accuracyM) && accuracyM >= 0 ? accuracyM : null,
      Number.isFinite(course) && course >= 0 && course <= 360 ? course : null,
    ]);
  }
  return points.length ? { seq, points } : { error: 'invalid trace points' };
}

/** Journal permanent : aucune purge, suppression avec le compte seulement. */
export function createTripLog({ query = (...args) => db.query(...args) } = {}) {
  return {
    async save(accountId, record) {
      await query(
        `INSERT INTO crowd.trip
           (account_id, trip_id, started_at, platform, app_version, distance_m, duration_s, arrived, end_reason, record)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb)
         ON CONFLICT (account_id, trip_id) DO NOTHING`,
        [accountId, record.id, new Date(record.startedAt), record.platform ?? null, record.appVersion ?? null,
          record.distanceMeters, record.durationSeconds, record.arrived ?? null, record.endReason ?? null, JSON.stringify(record)],
      );
    },

    // Aucun lien obligatoire au trajet : paquets reçus avant ou après sa fin.
    async saveTrace(accountId, tripId, seq, points) {
      await query(
        `INSERT INTO crowd.trip_trace (account_id, trip_id, seq, points)
         VALUES ($1, $2, $3, $4::jsonb)
         ON CONFLICT (account_id, trip_id, seq) DO NOTHING`,
        [accountId, tripId, seq, JSON.stringify(points)],
      );
    },
  };
}

export const tripLog = createTripLog();
