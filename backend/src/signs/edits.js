import { randomUUID } from 'node:crypto';
import { db } from '../db.js';
import { transaction } from '../crowd/schema.js';

/**
 * The signalisation corrected by hand ("mapper"): add a sign OpenStreetMap does not have, fix a
 * wrong one, hide one that is not there. Each correction is written in crowd.sign_edit — which no
 * rebuild ever touches — and applied at once to the published signalisation (schema signs), so the
 * apps see it straight away. The weekly rebuild replays the same corrections onto the fresh data
 * (signalisation/edits.sql); one that cannot be matched again is kept and marked "conflict".
 */

/** The kinds a correction may carry, as the signalisation names them. */
export const SIGN_KINDS = new Set([
  'traffic_signals', 'stop', 'give_way', 'crossing', 'roundabout',
  'construction', 'no_entry', 'level_crossing', 'speed_sign',
]);

const OPS = new Set(['add', 'edit', 'hide']);

/** A road this far from an added sign carries it (the signalisation hangs signs on roads). */
const ROAD_MAX_M = 60;

class SignEditStore {
  /** The corrections, newest first; [status] filters (applied, conflict, reverted). */
  async list({ status = null, limit = 200 } = {}) {
    const { rows } = await db.query(
      `SELECT id, op, target_id, kind, value, course, ST_Y(geom) AS lat, ST_X(geom) AS lon,
              was, author_id, note, status, conflict, created_at, updated_at
       FROM crowd.sign_edit
       WHERE ($1::text IS NULL OR status = $1)
       ORDER BY updated_at DESC
       LIMIT $2`,
      [status, Math.min(Number(limit) || 200, 1000)],
    );
    return rows.map(toEdit);
  }

  async get(id) {
    const { rows } = await db.query(
      `SELECT id, op, target_id, kind, value, course, ST_Y(geom) AS lat, ST_X(geom) AS lon,
              was, author_id, note, status, conflict, created_at, updated_at
       FROM crowd.sign_edit WHERE id = $1`,
      [id],
    );
    return rows[0] ? toEdit(rows[0]) : null;
  }

  /**
   * A new correction, written and applied in one transaction: either the signalisation carries it
   * and the row exists, or neither does.
   */
  async create({ op, targetId, kind, value, course, lat, lon, note, authorId }) {
    if (!OPS.has(op)) return { error: 'op must be add, edit or hide' };
    if (op === 'add' && !SIGN_KINDS.has(kind)) return { error: 'unknown kind' };
    if (op !== 'add' && !targetId) return { error: 'targetId required' };
    if (op === 'add' && (!Number.isFinite(lat) || !Number.isFinite(lon))) {
      return { error: 'lat and lon required' };
    }
    const done = await transaction(async (client) => {
      const was = op === 'add' ? null : await signRow(client, targetId);
      if (op !== 'add' && !was) return { error: 'sign not found' };
      const id = randomUUID();
      const sign = op === 'add' ? `edit-${id.replace(/-/g, '').slice(0, 12)}` : targetId;
      const next = {
        kind: kind ?? was?.kind ?? null,
        value: value === undefined ? was?.value ?? null : value,
        course: course === undefined ? was?.course ?? null : course,
        lat: Number.isFinite(lat) ? lat : was?.lat ?? null,
        lon: Number.isFinite(lon) ? lon : was?.lon ?? null,
      };
      await client.query(
        `INSERT INTO crowd.sign_edit (id, op, target_id, kind, value, course, geom, was, author_id, note)
         VALUES ($1, $2, $3, $4, $5, $6,
                 CASE WHEN $7::float8 IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint($8, $7), 4326) END,
                 $9, $10, $11)`,
        [id, op, sign, next.kind, next.value, next.course, next.lat, next.lon,
          JSON.stringify(was ?? {}), authorId ?? null, note ? String(note).slice(0, 500) : null],
      );
      await apply(client, { op, targetId: sign, ...next });
      return { id };
    });
    return done.error ? done : { edit: await this.get(done.id) };
  }

  /** Change what a correction says (an add or an edit), and apply it again. */
  async update(id, { kind, value, course, lat, lon, note }) {
    const done = await transaction(async (client) => {
      const { rows } = await client.query('SELECT * FROM crowd.sign_edit WHERE id = $1 FOR UPDATE', [id]);
      const row = rows[0];
      if (!row) return { error: 'not found' };
      if (row.op === 'hide') return { error: 'a hide has nothing to change' };
      if (kind !== undefined && !SIGN_KINDS.has(kind)) return { error: 'unknown kind' };
      const next = {
        kind: kind ?? row.kind,
        value: value === undefined ? row.value : value,
        course: course === undefined ? row.course : course,
        lat: Number.isFinite(lat) ? lat : null,
        lon: Number.isFinite(lon) ? lon : null,
      };
      await client.query(
        `UPDATE crowd.sign_edit
         SET kind = $2, value = $3, course = $4,
             geom = CASE WHEN $5::float8 IS NULL THEN geom ELSE ST_SetSRID(ST_MakePoint($6, $5), 4326) END,
             note = COALESCE($7, note), status = 'applied', conflict = NULL, updated_at = now()
         WHERE id = $1`,
        [id, next.kind, next.value, next.course, next.lat, next.lon, note ? String(note).slice(0, 500) : null],
      );
      const fresh = await client.query(
        'SELECT ST_Y(geom) AS lat, ST_X(geom) AS lon FROM crowd.sign_edit WHERE id = $1',
        [id],
      );
      await apply(client, {
        op: row.op,
        targetId: row.target_id,
        ...next,
        lat: fresh.rows[0].lat,
        lon: fresh.rows[0].lon,
      });
      return { id };
    });
    return done.error ? done : { edit: await this.get(id) };
  }

  /**
   * Undo a correction: the sign goes back to what OpenStreetMap says (an added one disappears, a
   * hidden one comes back). The row stays, marked "reverted", so the history is kept.
   */
  async revert(id) {
    const done = await transaction(async (client) => {
      const { rows } = await client.query('SELECT * FROM crowd.sign_edit WHERE id = $1 FOR UPDATE', [id]);
      const row = rows[0];
      if (!row) return { error: 'not found' };
      if (row.status === 'reverted') return { error: 'already reverted' };
      if (row.op === 'add') {
        await client.query('DELETE FROM signs.sign WHERE id = $1', [row.target_id]);
      } else {
        const was = row.was || {};
        if (was.kind) await restore(client, row.target_id, was);
      }
      await client.query(
        `UPDATE crowd.sign_edit SET status = 'reverted', conflict = NULL, updated_at = now() WHERE id = $1`,
        [id],
      );
      return { id };
    });
    return done.error ? done : { edit: await this.get(id) };
  }
}

/** The sign as it is now in the published signalisation, or null. */
async function signRow(client, id) {
  const { rows } = await client.query(
    `SELECT id, kind, value, course, way_id, sources, node_ids,
            ST_Y(geom) AS lat, ST_X(geom) AS lon
     FROM signs.sign WHERE id = $1`,
    [id],
  );
  return rows[0] ?? null;
}

/** Puts a correction on the published signalisation, right away. */
async function apply(client, { op, targetId, kind, value, course, lat, lon }) {
  if (op === 'hide') {
    await client.query('DELETE FROM signs.sign WHERE id = $1', [targetId]);
    return;
  }
  if (op === 'edit') {
    await client.query(
      `UPDATE signs.sign
       SET kind = $2, value = $3, course = $4,
           geom = CASE WHEN $5::float8 IS NULL THEN geom ELSE ST_SetSRID(ST_MakePoint($6, $5), 4326) END,
           geom_m = CASE WHEN $5::float8 IS NULL THEN geom_m
                         ELSE ST_Transform(ST_SetSRID(ST_MakePoint($6, $5), 4326), 2154) END
       WHERE id = $1`,
      [targetId, kind, value, course, lat, lon],
    );
    return;
  }
  await client.query(
    `INSERT INTO signs.sign (id, kind, value, course, way_id, sources, node_ids, geom, geom_m)
     SELECT $1, $2, $3, $4,
            (SELECT r.way_id FROM signs.road r
              ORDER BY r.geom_m <-> ST_Transform(ST_SetSRID(ST_MakePoint($6, $5), 4326), 2154)
              LIMIT 1),
            1, NULL,
            ST_SetSRID(ST_MakePoint($6, $5), 4326),
            ST_Transform(ST_SetSRID(ST_MakePoint($6, $5), 4326), 2154)
     ON CONFLICT (id) DO UPDATE
       SET kind = EXCLUDED.kind, value = EXCLUDED.value, course = EXCLUDED.course,
           geom = EXCLUDED.geom, geom_m = EXCLUDED.geom_m`,
    [targetId, kind, value, course, lat, lon],
  );
  // A sign too far from any road would never be met on a trip: say so instead of hiding it.
  const { rows } = await client.query(
    `SELECT ST_Distance(s.geom_m, r.geom_m) AS d
     FROM signs.sign s JOIN signs.road r ON r.way_id = s.way_id WHERE s.id = $1`,
    [targetId],
  );
  if (rows[0] && rows[0].d > ROAD_MAX_M) {
    throw new Error(`no road within ${ROAD_MAX_M} m of this point`);
  }
}

/**
 * Puts a sign back exactly the way it was before a correction (used when one is undone): what it
 * carried from OpenStreetMap too, so the next rebuild finds the same thing it built.
 */
async function restore(client, id, was) {
  await client.query(
    `INSERT INTO signs.sign (id, kind, value, course, way_id, sources, node_ids, geom, geom_m)
     VALUES ($1, $2, $3, $4,
             COALESCE($7::bigint,
                      (SELECT r.way_id FROM signs.road r
                        ORDER BY r.geom_m <-> ST_Transform(ST_SetSRID(ST_MakePoint($6, $5), 4326), 2154)
                        LIMIT 1)),
             COALESCE($8::int, 1), $9::bigint[],
             ST_SetSRID(ST_MakePoint($6, $5), 4326),
             ST_Transform(ST_SetSRID(ST_MakePoint($6, $5), 4326), 2154))
     ON CONFLICT (id) DO UPDATE
       SET kind = EXCLUDED.kind, value = EXCLUDED.value, course = EXCLUDED.course,
           way_id = EXCLUDED.way_id, sources = EXCLUDED.sources, node_ids = EXCLUDED.node_ids,
           geom = EXCLUDED.geom, geom_m = EXCLUDED.geom_m`,
    [id, was.kind, was.value ?? null, was.course ?? null, was.lat, was.lon,
      was.way_id ?? null, was.sources ?? null, was.node_ids ?? null],
  );
}

function toEdit(row) {
  return {
    id: row.id,
    op: row.op,
    targetId: row.target_id,
    kind: row.kind,
    value: row.value,
    course: row.course,
    lat: row.lat,
    lon: row.lon,
    was: row.was,
    authorId: row.author_id,
    note: row.note,
    status: row.status,
    conflict: row.conflict,
    createdAt: new Date(row.created_at).toISOString(),
    updatedAt: new Date(row.updated_at).toISOString(),
  };
}

export const signEditStore = new SignEditStore();
