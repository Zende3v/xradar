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
import { orsKeysMeta } from './routing/ors.js';
import { meta as signsMeta } from './signs/postgis.js';
import { signRouter } from './signs/routes.js';
import { speedLimitRouter } from './speedlimits/routes.js';
import { speedLimitStore } from './speedlimits/store.js';
import { bugRouter } from './bugs/routes.js';
import { tripRouter } from './trips/routes.js';
import { searchRouter } from './search/routes.js';
import { shareStore } from './trips/shares.js';
import { sharePage } from './trips/page.js';
import { probeStore } from './traffic/probes.js';
import { trafficRouter } from './traffic/routes.js';

/** Builds the Express app (kept separate from bootstrap for testability). */
export function createApp() {
  const app = express();
  // The admin webapp runs in a browser on its own address: it may call the API only from the
  // addresses WEBAPP_ORIGINS lists. Everything else (the apps) is not a browser and never asks.
  app.use((req, res, next) => {
    const origin = req.get('origin');
    if (origin && config.webappOrigins.includes(origin)) {
      res.set('Access-Control-Allow-Origin', origin);
      res.set('Vary', 'Origin');
      res.set('Access-Control-Allow-Headers', 'authorization, content-type, x-admin-token');
      res.set('Access-Control-Allow-Methods', 'GET, POST, PATCH, DELETE, OPTIONS');
      res.set('Access-Control-Max-Age', '86400');
      if (req.method === 'OPTIONS') return res.sendStatus(204);
    }
    next();
  });
  // Profile pictures: served statically, and uploaded (base64) with a larger body
  // limit than the rest of the API — mounted before the global 16 kb JSON parser.
  app.use('/avatars', express.static(config.avatarsDir, { maxAge: '7d' }));
  app.use('/api/accounts/avatar', express.json({ limit: '4mb' }), avatarRouter);
  // The route geometry can be large — bigger JSON limit for the endpoints that take it.
  app.use('/api/signs/route', express.json({ limit: '3mb' }));
  app.use('/api/radars/route', express.json({ limit: '3mb' }));
  app.use('/api/traffic/route', express.json({ limit: '3mb' }));
  app.use('/api/route/faster', express.json({ limit: '3mb' }));
  app.use(express.json({ limit: '16kb' }));

  app.get('/health', async (_req, res) => {
    // The published signalisation, when the database answers.
    const published = await signsMeta().catch(() => null);
    res.json({
      status: 'ok',
      radars: radarStore.meta,
      reports: reportStore.meta,
      accounts: accountStore.meta,
      live: liveStore.meta,
      routing: config.orsApiKey ? { provider: 'ors', ...orsKeysMeta() } : { provider: 'osrm' },
      traffic: { provider: config.tomtomApiKey ? 'tomtom' : null, probes: probeStore.meta.count },
      trips: shareStore.meta,
      signs: { published },
      speedLimits: speedLimitStore.meta,
      fuel: fuelStore.meta,
      memoryMB: Math.round(process.memoryUsage().rss / 1e6),
    });
  });

  app.use('/api/accounts', accountRouter);
  app.use('/api/bugs', bugRouter);
  app.use('/api/trips', tripRouter);
  app.use('/api/search', searchRouter);
  // The page behind a shared link: it opens the app, it never shows a position itself.
  app.get('/t/:token', sharePage);
  app.use('/api/admin/accounts', adminAccountRouter);
  app.use('/api/live', liveRouter);
  app.use('/api/places', placeRouter);
  app.use('/api/radars', radarRouter);
  app.use('/api/reports', reportRouter);
  app.use('/api/route', routeRouter);
  app.use('/api/signs', signRouter);
  app.use('/api/speed-limits', speedLimitRouter);
  app.use('/api/traffic', trafficRouter);

  return app;
}
