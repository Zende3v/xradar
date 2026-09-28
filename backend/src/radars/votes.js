import { config } from '../config.js';
import { db } from '../db.js';
import { angleBetween } from './geo.js';

/**
 * "Pas dans mon sens" (28/09): drivers saying a fixed radar does not control the way they drive.
 * One vote per radar and account (crowd.radar_vote), the latest. The votes whose courses lie
 * within config.radarVoteSpreadDeg of each other and weigh config.radarVoteMinWeight together
 * make the radar quiet that way: its `quietCourse`, the mean of their courses.
 */

/** The quiet course of a radar's votes ({ course, weight }), or null when they are too few or too spread. */
export function quietCourseOf(votes) {
  let best = null;
  for (const seed of votes) {
    const close = votes.filter((v) => angleBetween(v.course, seed.course) <= config.radarVoteSpreadDeg);
    const weight = close.reduce((sum, v) => sum + v.weight, 0);
    if (weight >= config.radarVoteMinWeight && (!best || weight > best.weight)) best = { close, weight };
  }
  if (!best) return null;
  // The circular mean: 350° and 10° make 0°, not 180°.
  const x = best.close.reduce((sum, v) => sum + v.weight * Math.sin((v.course * Math.PI) / 180), 0);
  const y = best.close.reduce((sum, v) => sum + v.weight * Math.cos((v.course * Math.PI) / 180), 0);
  return Math.round(((Math.atan2(x, y) * 180) / Math.PI + 360) % 360) % 360;
}

class RadarVoteStore {
  constructor() {
    this.quiet = new Map(); // radarId -> quiet course
    this.lastPurge = 0;
  }

  /** Every radar's quiet course, from the votes still kept. */
  async load() {
    const { rows } = await db.query(
      `SELECT radar_id, course, weight FROM crowd.radar_vote WHERE at > now() - make_interval(days => $1)`,
      [config.radarVoteKeepDays],
    );
    const byRadar = new Map();
    for (const row of rows) {
      if (!byRadar.has(row.radar_id)) byRadar.set(row.radar_id, []);
      byRadar.get(row.radar_id).push({ course: row.course, weight: row.weight });
    }
    this.quiet = new Map();
    for (const [id, votes] of byRadar) {
      const course = quietCourseOf(votes);
      if (course !== null) this.quiet.set(id, course);
    }
    return this.quiet;
  }

  /** [accountId]'s vote on [radarId] at [course]; answers the radar's quiet course now. */
  async vote(radarId, accountId, course, admin) {
    await db.query(
      `INSERT INTO crowd.radar_vote (radar_id, account_id, course, weight)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (radar_id, account_id) DO UPDATE SET course = EXCLUDED.course, weight = EXCLUDED.weight, at = now()`,
      [radarId, accountId, Math.round(course) % 360, admin ? config.radarVoteAdminWeight : 1],
    );
    const { rows } = await db.query(
      `SELECT course, weight FROM crowd.radar_vote WHERE radar_id = $1 AND at > now() - make_interval(days => $2)`,
      [radarId, config.radarVoteKeepDays],
    );
    const quiet = quietCourseOf(rows);
    if (quiet === null) this.quiet.delete(radarId);
    else this.quiet.set(radarId, quiet);
    if (Date.now() - this.lastPurge > 24 * 60 * 60 * 1000) {
      this.lastPurge = Date.now();
      db.query('DELETE FROM crowd.radar_vote WHERE at < now() - make_interval(days => $1)', [config.radarVoteKeepDays])
        .catch((e) => console.warn('[radar-votes] purge —', String(e.message || e)));
    }
    return quiet;
  }
}

export const radarVoteStore = new RadarVoteStore();
