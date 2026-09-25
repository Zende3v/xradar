import { readFile } from 'node:fs/promises';
import { db } from '../db.js';

/** Create or complete schema "routing" (idempotent): the route log and the bench. */
export async function ensureRoutingSchema() {
  const sql = await readFile(new URL('./schema.sql', import.meta.url), 'utf8');
  await db.query(sql);
}
