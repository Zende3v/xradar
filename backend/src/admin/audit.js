import { config } from '../config.js';
import { db } from '../db.js';

/**
 * The admins' journal: who did what to which account, report, speed limit or bug. Written as
 * the action happens, read by the console. A line that fails to be written never blocks the
 * action itself — it is said in the logs.
 */
class AdminAudit {
  constructor() {
    this.lastPurge = 0;
  }

  /**
   * One action by [actor] (from adminActor): [action] on [targetType] [targetId], and what
   * changed in [detail] — a few plain values, never an email or a password.
   */
  log(actor, action, targetType, targetId = null, detail = {}) {
    if (!actor) return;
    db.query(
      `INSERT INTO crowd.admin_action (actor_id, actor_name, action, target_type, target_id, detail)
       VALUES ($1, $2, $3, $4, $5, $6)`,
      [actor.id, actor.name, action, targetType, targetId == null ? null : String(targetId), JSON.stringify(detail ?? {})],
    ).catch((e) => console.warn('[audit] not written —', String(e.message || e)));
    if (Date.now() - this.lastPurge > 60 * 60 * 1000) {
      this.lastPurge = Date.now();
      db.query('DELETE FROM crowd.admin_action WHERE at < now() - make_interval(days => $1)', [config.adminAuditDays])
        .catch((e) => console.warn('[audit] purge —', String(e.message || e)));
    }
  }

  /** The journal, newest first, [limit] at a time before [before] (ISO), with optional filters. */
  async list({ before = null, actor = null, action = null, targetType = null, targetId = null, limit = 50 } = {}) {
    const { rows } = await db.query(
      `SELECT id, at, actor_id, actor_name, action, target_type, target_id, detail
       FROM crowd.admin_action
       WHERE ($1::timestamptz IS NULL OR at < $1)
         AND ($2::text IS NULL OR actor_id = $2)
         AND ($3::text IS NULL OR action = $3)
         AND ($4::text IS NULL OR target_type = $4)
         AND ($5::text IS NULL OR target_id = $5)
       ORDER BY at DESC, id DESC
       LIMIT $6`,
      [before, actor, action, targetType, targetId, limit],
    );
    return rows.map((row) => ({
      id: Number(row.id),
      at: new Date(row.at).toISOString(),
      actor: { id: row.actor_id, name: row.actor_name },
      action: row.action,
      target: { type: row.target_type, id: row.target_id },
      detail: row.detail ?? {},
    }));
  }
}

export const adminAudit = new AdminAudit();
