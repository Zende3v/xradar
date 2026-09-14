import express from 'express';
import { config } from './config.js';
import { avatarRouter } from './accounts/avatar.js';
import { accountRouter, adminAccountRouter } from './accounts/routes.js';
import { accountStore } from './accounts/store.js';
import { fuelStore } from './fuel/store.js';
import { liveRouter } from './live/routes.js';
import { liveStore } from './live/store.js';
import { placeRouter } from './places/routes.js';
import { radarRouter } from './radars/routes.js';
import { radarStore } from './radars/store.js';
import { reportRouter } from './reports/routes.js';
import { reportStore } from './reports/store.js';
import { routeRouter } from './routing/routes.js';
import { signDataset } from './signs/dataset.js';
import { meta as signsMeta } from './signs/postgis.js';
import { signRouter } from './signs/routes.js';
import { speedLimitRouter } from './speedlimits/routes.js';
import { speedLimitStore } from './speedlimits/store.js';

/** Builds the Express app (kept separate from bootstrap for testability). */
export function createApp() {
  const app = express();
  // Profile pictures: served statically, and uploaded (base64) with a larger body
  // limit than the rest of the API — mounted before the global 16 kb JSON parser.
  app.use('/avatars', express.static(config.avatarsDir, { maxAge: '7d' }));
  app.use('/api/accounts/avatar', express.json({ limit: '4mb' }), avatarRouter);
  // The route geometry can be large — bigger JSON limit for the endpoints that take it.
  app.use('/api/signs/route', express.json({ limit: '3mb' }));
  app.use('/api/radars/route', express.json({ limit: '3mb' }));
  app.use(express.json({ limit: '16kb' }));

  app.get('/health', async (_req, res) => {
    // The published signalisation v2, when the database answers.
    const published = config.signsSource === 'postgis' ? await signsMeta().catch(() => null) : null;
    res.json({
      status: 'ok',
      radars: radarStore.meta,
      reports: reportStore.meta,
      accounts: accountStore.meta,
      live: liveStore.meta,
      routing: { provider: config.orsApiKey ? 'ors' : 'osrm' },
      signs: { source: config.signsSource, published, dataset: { ready: signDataset.ready, count: signDataset.count } },
      speedLimits: speedLimitStore.meta,
      fuel: fuelStore.meta,
      memoryMB: Math.round(process.memoryUsage().rss / 1e6),
    });
  });

  app.use('/api/accounts', accountRouter);
  app.use('/api/admin/accounts', adminAccountRouter);
  app.use('/api/live', liveRouter);
  app.use('/api/places', placeRouter);
  app.use('/api/radars', radarRouter);
  app.use('/api/reports', reportRouter);
  app.use('/api/route', routeRouter);
  app.use('/api/signs', signRouter);
  app.use('/api/speed-limits', speedLimitRouter);

  return app;
}
