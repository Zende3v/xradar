import { Router } from 'express';
import { config } from '../config.js';
import { inBbox, near } from './geo.js';
import { radarStore } from './store.js';

export const radarRouter = Router();

/**
 * GET /api/radars/near?lat=..&lon=..&radius=..
 * Radars within `radius` metres of the point, nearest first. This is what the
 * app polls around the current GPS position.
 */
radarRouter.get('/near', (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  const radius = clamp(Number(req.query.radius) || config.defaultNearRadiusM, 1, config.maxNearRadiusM);
  const { list, effectiveRadius } = nearDense(radarStore.all(), lat, lon, radius);
  res.json({ count: list.length, radiusM: effectiveRadius, radars: list });
});

/**
 * Nearest points within [radius], but if that's ultra-dense (too many results),
 * shrink to the dense radius so the client doesn't drown in markers.
 */
function nearDense(items, lat, lon, radius) {
  // Respect the user's radius; just cap the count (nearest-first) so a big radius
  // never *reduces* what's shown. The map stays light via maxResults.
  const list = near(items, lat, lon, radius, config.maxResults);
  return { list, effectiveRadius: radius };
}

/**
 * GET /api/radars/bbox?bbox=minLon,minLat,maxLon,maxLat
 * Radars inside the map viewport.
 */
radarRouter.get('/bbox', (req, res) => {
  const parts = String(req.query.bbox || '').split(',').map(Number);
  if (parts.length !== 4 || parts.some((n) => !Number.isFinite(n))) {
    return res.status(400).json({ error: 'bbox must be "minLon,minLat,maxLon,maxLat"' });
  }
  const [minLon, minLat, maxLon, maxLat] = parts;
  const radars = inBbox(radarStore.all(), minLon, minLat, maxLon, maxLat, config.maxResults);
  res.json({ count: radars.length, radars });
});

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
