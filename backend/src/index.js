import { config } from './config.js';
import { createApp } from './server.js';
import { accountStore } from './accounts/store.js';
import { ensureCrowdSchema } from './crowd/schema.js';
import { fuelStore } from './fuel/store.js';
import { radarStore } from './radars/store.js';
import { reportStore } from './reports/store.js';
import { speedLimitStore } from './speedlimits/store.js';

// Opening hours are read on the French clock, whatever the host's timezone.
process.env.TZ = 'Europe/Paris';

// Load the radar dataset (and schedule refreshes), then start the HTTP server.
radarStore.start();
// Official fuel prices (refreshed every 10 min) — they only enrich the fuel search.
fuelStore.start();
// Load persisted accounts.
accountStore.start().catch((e) => console.error('[accounts] start failed:', e.message));
// Drivers' reports and speed-limit changes live in PostGIS (schema crowd): the schema first,
// then the stores that prune and decide on it.
ensureCrowdSchema()
  .then(() => Promise.all([reportStore.start(), speedLimitStore.start()]))
  .catch((e) => console.error('[crowd] start failed:', e.message));

const app = createApp();
app.listen(config.port, config.host, () => {
  console.log(`EONA backend listening on ${config.host}:${config.port}`);
});
