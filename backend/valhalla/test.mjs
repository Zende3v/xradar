// Test réel du candidat : aucun ORS, TomTom, compte ni écritures PostgreSQL.
import { readFileSync } from 'node:fs';
import { createValhallaClient } from '../src/routing/providers/valhalla.js';

const client = createValhallaClient({
  baseUrl: process.argv[2] || 'http://127.0.0.1:8003', timeoutMs: 5000,
  searchCutoffM: Number(process.env.VALHALLA_SEARCH_CUTOFF_M || 500),
  maxSnapM: Number(process.env.VALHALLA_MAX_SNAP_M || 250),
});
const status = await client.status();
if (!status.has_tiles || !status.has_admins || !status.has_timezones || !status.version.startsWith('3.9.0')) {
  throw new Error('Carte incomplète ou version inattendue');
}
const { trips } = JSON.parse(readFileSync(new URL('../bench/trajets.json', import.meta.url)));
const results = [];
for (const trip of trips) {
  const at = performance.now();
  const routes = await client.routes(trip.from, trip.to, { avoid: trip.avoid.filter(a => a !== 'traffic') });
  if (!routes.length || !routes[0].distanceM || !routes[0].durationS) throw new Error(`Route vide : ${trip.id}`);
  results.push({ id: trip.id, ms: Math.round(performance.now() - at) });
}
console.log(JSON.stringify({ status, trips: results.length, results }));
