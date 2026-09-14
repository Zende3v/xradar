import pg from 'pg';
import { config } from './config.js';

/**
 * PostgreSQL / PostGIS pool (signalisation v2). On the VPS it goes through the local socket
 * with peer authentication as the service user; the standard PG* environment variables
 * override every setting.
 */
export const db = new pg.Pool({
  host: config.pgHost,
  database: config.pgDatabase,
  max: config.pgPoolMax,
  idleTimeoutMillis: 30_000,
  connectionTimeoutMillis: 3_000,
  statement_timeout: config.pgStatementTimeoutMs,
});

db.on('error', (e) => console.error('[db] idle client error:', e.message));
