// Test réel du candidat : aucun ORS, HERE, compte ni écritures PostgreSQL.
// Trajets versionnés à côté (trajets.json) : test indépendant du banc, retiré le 30/09.
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
const { trips } = JSON.parse(readFileSync(new URL('./trajets.json', import.meta.url)));
const results = [];
for (const trip of trips) {
  const at = performance.now();
  const routes = await client.routes(trip.from, trip.to, { avoid: trip.avoid.filter(a => a !== 'traffic') });
  if (!routes.length || !routes[0].distanceM || !routes[0].durationS) throw new Error(`Route vide : ${trip.id}`);
  results.push({ id: trip.id, ms: Math.round(performance.now() - at) });
}
// Éco et étapes (choix d'itinéraire, multi-arrêts) : même carte, mêmes points.
const [first, second] = trips;
const [eco] = await client.routes(first.from, first.to, { shortest: true });
if (!eco?.distanceM || !eco.durationS) throw new Error(`Route Éco vide : ${first.id}`);
const [stops] = await client.routes(first.from, second.to, { via: [first.to] });
if (!stops?.distanceM || !stops.durationS) throw new Error(`Route avec étape vide : ${first.id} > ${second.id}`);
results.push({ id: 'eco', km: Math.round(eco.distanceM / 100) / 10 }, { id: 'via', km: Math.round(stops.distanceM / 100) / 10 });
console.log(JSON.stringify({ status, trips: trips.length, results }));
