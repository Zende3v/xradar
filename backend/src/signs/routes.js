import { Router } from 'express';
import { config } from '../config.js';
import { signDataset } from './dataset.js';
import { signStore } from './store.js';

export const signRouter = Router();

/** GET /api/signs/near?lat=..&lon=..&radius=.. — signs around a point. */
signRouter.get('/near', async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = Math.max(1, Math.min(Number(req.query.radius) || config.signDefaultRadiusM, config.signMaxRadiusM));
  try {
    const signs = signDataset.ready
      ? signDataset.near(lat, lon, radius, config.signMaxElements)
      : await signStore.near(lat, lon, radius);
    res.json({ count: signs.length, signs, source: signDataset.ready ? 'dataset' : 'overpass' });
  } catch (e) {
    res.status(502).json({ error: 'signs unavailable', detail: String(e.message || e) });
  }
});

/**
 * POST /api/signs/route  { coordinates: [[lon,lat],...], buffer? }
 * All signs along the whole route (within `buffer` metres). Dataset only.
 */
signRouter.post('/route', (req, res) => {
  if (!signDataset.ready) return res.json({ count: 0, signs: [], source: 'none' });
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords)) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  const buffer = Math.min(Number(req.body?.buffer) || config.signRouteBufferM, 200);
  const signs = signDataset.route(coords, buffer, config.signMaxElements);
  res.json({ count: signs.length, signs, source: 'dataset' });
});

/** GET /api/signs/limit?lat=..&lon=.. — speed limit (km/h) where the driver is. */
signRouter.get('/limit', (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  if (!signDataset.ready) return res.json({ v: null, source: 'none' });
  const v = signDataset.limitAt(lat, lon, config.signLimitMaxDistM);
  res.json({ v: v || null, source: 'dataset' });
});
