import { config } from './config.js';
import { createApp } from './server.js';
import { accountStore } from './accounts/store.js';
import { settingsStore } from './accounts/settings.js';
import { ensureCrowdSchema } from './crowd/schema.js';
import { fuelStore } from './fuel/store.js';
import { radarStore } from './radars/store.js';
import { reportStore } from './reports/store.js';
import { routing } from './routing/engine.js';
import { ensureRoutingSchema } from './routing/schema.js';
import { shadowStore } from './routing/shadow.js';
import { speedLimitStore } from './speedlimits/store.js';
import { refreshLocalMeta } from './search/local.js';
import { datagouvStore } from './traffic/datagouv.js';

// Opening hours are read on the French clock, whatever the host's timezone.
process.env.TZ = 'Europe/Paris';

// Load the radar dataset (and schedule refreshes), then start the HTTP server.
radarStore.start();
// data.gouv's traffic (D3.2): the DIR's feeds, kept in memory.
if (config.datagouvEnabled) datagouvStore.start();
// Official fuel prices (refreshed every 10 min) — they only enrich the fuel search.
fuelStore.start();
// EONA's own place index: its build date and size for /health, read now and every 10 min (a
// weekly rebuild swaps it in while the service runs).
refreshLocalMeta();
setInterval(() => refreshLocalMeta(), 10 * 60_000).unref();
// Load persisted accounts.
settingsStore.load().catch((e) => console.error('[settings] load failed:', e.message));
accountStore.start().catch((e) => console.error('[accounts] start failed:', e.message));
// Drivers' reports and speed-limit changes live in PostGIS (schema crowd): the schema first,
// then the stores that prune and decide on it.
ensureCrowdSchema()
  .then(() => Promise.all([reportStore.start(), speedLimitStore.start(), radarStore.loadVotes()]))
  .catch((e) => console.error('[crowd] start failed:', e.message));
// The route log, the bench and the shadow mode (schema routing): measures only, nothing waits
// for them. Then the shadow's purges, schema there or not (a failed purge is tried again),
// Valhalla on or off: now and every hour, trips or not (shadow.js).
ensureRoutingSchema()
  .catch((e) => console.error('[routing] schema failed:', e.message))
  .then(() => shadowStore.start());
// Valhalla's /status, read now and then off the requests (map, has_live_traffic, outage mail).
// Nothing without VALHALLA_ENABLED: no call to Valhalla at all.
routing.start();
console.log(`[routing] Valhalla ${config.valhallaEnabled ? `on (${config.valhallaUrl})` : 'off'} — routingEngine read from the settings`);

const app = createApp();
app.listen(config.port, config.host, () => {
  console.log(`EONA backend listening on ${config.host}:${config.port}`);
});
