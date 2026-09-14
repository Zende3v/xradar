import { readFile } from 'node:fs/promises';
import { db } from '../db.js';

/** Create or complete schema "crowd" (idempotent): run before the stores that use it. */
export async function ensureCrowdSchema() {
  const sql = await readFile(new URL('./schema.sql', import.meta.url), 'utf8');
  await db.query(sql);
}

/** Run [work] in a transaction on one client; rolled back if it throws. */
export async function transaction(work) {
  const client = await db.connect();
  try {
    await client.query('BEGIN');
    const result = await work(client);
    await client.query('COMMIT');
    return result;
  } catch (e) {
    await client.query('ROLLBACK').catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}
