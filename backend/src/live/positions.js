import { config } from '../config.js';
import { db } from '../db.js';

/**
 * Where the drivers who share their position have been ("Ma présence et ma position", off unless
 * they turn it on). One row per ping, kept config.positionKeepDays days, then purged: the console
 * shows who is on the road now, and can replay where a driver went.
 */
class PositionStore {
  constructor() {
    this.lastPurge = 0;
  }

  /** One more point for [accountId]; the purge runs at most once an hour, in passing. */
  async add(accountId, { lat, lon, speedKmh, inTrip }) {
    await db.query(
      `INSERT INTO crowd.position (account_id, geom, speed_kmh, in_trip)
       VALUES ($1, ST_SetSRID(ST_MakePoint($3, $2), 4326), $4, $5)`,
      [accountId, lat, lon, Number.isFinite(speedKmh) ? Math.round(speedKmh) : null, inTrip === true],
    );
    if (Date.now() - this.lastPurge > 60 * 60 * 1000) {
      this.lastPurge = Date.now();
      this.purge().catch((e) => console.warn('[positions] purge —', String(e.message || e)));
    }
  }

  /** The last point of every account heard from within [withinMs]. */
  async live(withinMs = config.liveTtlMs) {
    const { rows } = await db.query(
      `SELECT DISTINCT ON (account_id) account_id, at, ST_Y(geom) AS lat, ST_X(geom) AS lon, speed_kmh, in_trip
       FROM crowd.position
       WHERE at > now() - make_interval(secs => $1)
       ORDER BY account_id, at DESC`,
      [Math.round(withinMs / 1000)],
    );
    return rows.map(toPoint);
  }

  /** Where one account went between [from] and [to] (ISO or null), oldest first. */
  async trace(accountId, { from = null, to = null, limit = config.positionTraceMax } = {}) {
    const { rows } = await db.query(
      `SELECT account_id, at, ST_Y(geom) AS lat, ST_X(geom) AS lon, speed_kmh, in_trip
       FROM crowd.position
       WHERE account_id = $1
         AND ($2::timestamptz IS NULL OR at >= $2)
         AND ($3::timestamptz IS NULL OR at <= $3)
       ORDER BY at
       LIMIT $4`,
      [accountId, from, to, Math.min(Number(limit) || config.positionTraceMax, config.positionTraceMax)],
    );
    return rows.map(toPoint);
  }

  /** A deleted account leaves nothing behind. */
  async forget(accountId) {
    await db.query('DELETE FROM crowd.position WHERE account_id = $1', [accountId]);
  }

  async purge() {
    await db.query(
      'DELETE FROM crowd.position WHERE at < now() - make_interval(days => $1)',
      [config.positionKeepDays],
    );
  }
}

function toPoint(row) {
  return {
    accountId: row.account_id,
    at: new Date(row.at).toISOString(),
    lat: row.lat,
    lon: row.lon,
    speedKmh: row.speed_kmh,
    inTrip: row.in_trip,
  };
}

export const positionStore = new PositionStore();
