import { createHash } from 'node:crypto';
import { config } from '../config.js';

/** Cache exact, court, borné. Requêtes simultanées identiques partagent un seul appel. */
export function createHereCache({ ttlMs = config.hereCacheMs, maxEntries = config.hereCacheEntries, now = Date.now } = {}) {
  const entries = new Map();
  const pending = new Map();
  let hits = 0, joined = 0, misses = 0;
  async function get(key, load) {
    const found = entries.get(key);
    if (found && now() - found.at < ttlMs) { hits++; return found.value; }
    entries.delete(key);
    if (pending.has(key)) { joined++; return pending.get(key); }
    misses++;
    const promise = Promise.resolve().then(load).then(value => {
      entries.set(key, { at: now(), value });
      while (entries.size > maxEntries) entries.delete(entries.keys().next().value);
      return value;
    }).finally(()=>pending.delete(key));
    pending.set(key, promise);
    return promise;
  }
  return { get, status: () => ({ entries: entries.size, pending: pending.size, hits, joined, misses, ttlMs, maxEntries }) };
}
export const hereCache = createHereCache();
export const hereCacheKey = (kind, body) => kind + ':' + createHash('sha256').update(JSON.stringify(body)).digest('hex');
