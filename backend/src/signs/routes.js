import { Router } from 'express';
import { config } from '../config.js';
import * as postgis from './postgis.js';

export const signRouter = Router();

/** Runs a handler; a database failure answers 503 instead of crashing the request. */
const guarded = (handler) => async (req, res) => {
  try {
    await handler(req, res);
  } catch (e) {
    console.error('[signs]', e.message);
    res.status(503).json({ error: 'signs unavailable' });
  }
};

function optionalNumber(value) {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/** GET /api/signs/near?lat=..&lon=..&radius=.. — signs around a point, nearest first. */
signRouter.get('/near', guarded(async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = Math.max(1, Math.min(Number(req.query.radius) || config.signDefaultRadiusM, config.signMaxRadiusM));
  const signs = await postgis.near(lat, lon, radius, config.signMaxElements);
  res.json({ count: signs.length, signs, source: 'postgis' });
}));

/**
 * POST /api/signs/route  { coordinates: [[lon,lat],...] }
 * What a driver meets along the whole route, in order: the signs for the traffic going that
 * way, and a "speed" entry wherever the limit changes.
 */
signRouter.post('/route', guarded(async (req, res) => {
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords)) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  const signs = await postgis.route(coords);
  res.json({ count: signs.length, signs, source: 'postgis' });
}));

/**
 * GET /api/signs/limit?lat=..&lon=..&bearing=..&way=..
 * Speed limit (km/h) where the driver is, for the way they go — a change drivers validated
 * included. [bearing] is their course, [way] the road id the previous answer gave.
 */
signRouter.get('/limit', guarded(async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const bearing = optionalNumber(req.query.bearing);
  const previousWayId = /^\d{1,19}$/.test(String(req.query.way ?? '')) ? String(req.query.way) : null;
  const road = await postgis.roadAt(lat, lon, { bearing, previousWayId });
  res.json({ v: road?.limit ?? null, way: road?.wayId ?? null, source: 'postgis' });
}));
