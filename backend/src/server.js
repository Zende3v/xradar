import express from 'express';
import { config } from './config.js';
import { avatarRouter } from './accounts/avatar.js';
import { accountRouter, adminAccountRouter } from './accounts/routes.js';
import { accountStore } from './accounts/store.js';
import { liveRouter } from './live/routes.js';
import { liveStore } from './live/store.js';
import { radarRouter } from './radars/routes.js';
import { radarStore } from './radars/store.js';
import { reportRouter } from './reports/routes.js';
import { reportStore } from './reports/store.js';
import { routeRouter } from './routing/routes.js';

/** Builds the Express app (kept separate from bootstrap for testability). */
export function createApp() {
  const app = express();
  // Profile pictures: served statically, and uploaded (base64) with a larger body
  // limit than the rest of the API — mounted before the global 16 kb JSON parser.
  app.use('/avatars', express.static(config.avatarsDir, { maxAge: '7d' }));
  app.use('/api/accounts/avatar', express.json({ limit: '4mb' }), avatarRouter);
  app.use(express.json({ limit: '16kb' }));

  app.get('/health', (_req, res) => {
    res.json({
      status: 'ok',
      radars: radarStore.meta,
      reports: reportStore.meta,
      accounts: accountStore.meta,
      live: liveStore.meta,
    });
  });

  app.use('/api/accounts', accountRouter);
  app.use('/api/admin/accounts', adminAccountRouter);
  app.use('/api/live', liveRouter);
  app.use('/api/radars', radarRouter);
  app.use('/api/reports', reportRouter);
  app.use('/api/route', routeRouter);

  return app;
}
