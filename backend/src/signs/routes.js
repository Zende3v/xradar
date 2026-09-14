import { Router } from 'express';
import { config } from '../config.js';
import { signDataset } from './dataset.js';
import * as postgis from './postgis.js';
import { signStore } from './store.js';

export const signRouter = Router();

let degraded = false;

/**
 * Signalisation v2 (PostGIS) first. Undefined when it is switched off or cannot answer: the
 * callers then use the NDJSON dataset, so the HUD keeps its signs through an outage.
 */
async function fromPostgis(work) {
  if (config.signsSource !== 'postgis') return undefined;
  try {
    const result = await work();
    if (degraded) {
      degraded = false;
      console.log('[signs] PostGIS answers again');
    }
    return result;
  } catch (e) {
    if (!degraded) {
      degraded = true;
      console.error('[signs] PostGIS unavailable, using the dataset:', e.message);
    }
    return undefined;
  }
}

function optionalNumber(value) {
  if (value == null || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/** GET /api/signs/near?lat=..&lon=..&radius=.. — signs around a point. */
signRouter.get('/near', async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = Math.max(1, Math.min(Number(req.query.radius) || config.signDefaultRadiusM, config.signMaxRadiusM));
  const v2 = await fromPostgis(() => postgis.near(lat, lon, radius, config.signMaxElements));
  if (v2) return res.json({ count: v2.length, signs: v2, source: 'postgis' });
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
 * What a driver meets along the whole route, in order: the signs for the traffic going that
 * way, and a "speed" entry wherever the limit changes.
 */
signRouter.post('/route', async (req, res) => {
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords)) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  const v2 = await fromPostgis(() => postgis.route(coords));
  if (v2) return res.json({ count: v2.length, signs: v2, source: 'postgis' });
  if (!signDataset.ready) return res.json({ count: 0, signs: [], source: 'none' });
  const buffer = Math.min(Number(req.body?.buffer) || config.signRouteBufferM, 200);
  const signs = signDataset.route(coords, buffer, config.signMaxElements);
  res.json({ count: signs.length, signs, source: 'dataset' });
});

/**
 * GET /api/signs/limit?lat=..&lon=..&bearing=..&way=..
 * Speed limit (km/h) where the driver is, for the way they go. [bearing] is their course,
 * [way] the road id the previous answer gave (continuity); both optional.
 */
signRouter.get('/limit', async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const bearing = optionalNumber(req.query.bearing);
  const previousWayId = /^\d{1,19}$/.test(String(req.query.way ?? '')) ? String(req.query.way) : null;

  const road = await fromPostgis(() => postgis.roadAt(lat, lon, { bearing, previousWayId }));
  if (road !== undefined) {
    // A change drivers validated on this spot still wins (speedlimits store).
    const over = signDataset.overrides?.nearest(lat, lon, config.signRoadMaxDistM, bearing);
    const v = over && (!road || over.d <= road.d + config.speedLimitOverrideTieM) ? over.v : road?.limit ?? null;
    return res.json({ v, way: road?.wayId ?? null, source: 'postgis' });
  }

  if (!signDataset.ready) return res.json({ v: null, source: 'none' });
  const v = signDataset.limitAt(lat, lon, config.signLimitMaxDistM, bearing);
  res.json({ v: v || null, source: 'dataset' });
});
