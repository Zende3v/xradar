import { config } from './config.js';
import { createApp } from './server.js';
import { accountStore } from './accounts/store.js';
import { fuelStore } from './fuel/store.js';
import { radarStore } from './radars/store.js';
import { reportStore } from './reports/store.js';
import { signDataset } from './signs/dataset.js';
import { speedLimitStore } from './speedlimits/store.js';

// Load the radar dataset (and schedule refreshes), then start the HTTP server.
radarStore.start();
// Official fuel prices (refreshed every 10 min) — they only enrich the fuel search.
fuelStore.start();
// Load persisted user reports (and schedule pruning).
reportStore.start().catch((e) => console.error('[reports] start failed:', e.message));
// Load persisted accounts.
accountStore.start().catch((e) => console.error('[accounts] start failed:', e.message));
// Load the preloaded France signs dataset (if generated); else Overpass is used.
signDataset.load().catch((e) => console.error('[signs] dataset load failed:', e.message));
// Speed-limit changes drivers validated, laid over the dataset's limits.
speedLimitStore.start().catch((e) => console.error('[speed-limits] start failed:', e.message));

const app = createApp();
app.listen(config.port, config.host, () => {
  console.log(`x_radar backend listening on ${config.host}:${config.port}`);
});
