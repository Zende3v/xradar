import { config } from './config.js';
import { createApp } from './server.js';
import { accountStore } from './accounts/store.js';
import { radarStore } from './radars/store.js';
import { reportStore } from './reports/store.js';

// Load the radar dataset (and schedule refreshes), then start the HTTP server.
radarStore.start();
// Load persisted user reports (and schedule pruning).
reportStore.start().catch((e) => console.error('[reports] start failed:', e.message));
// Load persisted accounts.
accountStore.start().catch((e) => console.error('[accounts] start failed:', e.message));

const app = createApp();
app.listen(config.port, config.host, () => {
  console.log(`x_radar backend listening on ${config.host}:${config.port}`);
});
