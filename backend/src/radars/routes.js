import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { alongRoute, inBbox, near } from './geo.js';
import { radarStore } from './store.js';
import { radarVoteStore } from './votes.js';

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

/**
 * POST /api/radars/route  { coordinates: [[lon,lat],...], buffer? }
 * The trip's radars — within `buffer` metres of the route line — in one request, the
 * same way /api/signs/route serves the trip's signs. Ordered along the route.
 */
radarRouter.post('/route', (req, res) => {
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords) || coords.length < 2) {
    return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  }
  const buffer = clamp(Number(req.body?.buffer) || config.radarRouteBufferM, 1, config.radarRouteMaxBufferM);
  const radars = alongRoute(radarStore.all(), coords, buffer, config.maxResults);
  res.json({ count: radars.length, bufferM: buffer, radars });
});

/**
 * POST /api/radars/:id/not-my-way  { course }  (Bearer)
 * "Pas dans mon sens": the driver says this fixed radar does not control the way they drive
 * ([course], their GPS course, 0-360). One vote per radar and account, the latest; an admin's
 * weighs config.radarVoteAdminWeight. Answers { quietCourse }: the way the radar is now quiet,
 * or null while the votes are too few.
 */
radarRouter.post('/:id/not-my-way', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const id = String(req.params.id);
  if (!radarStore.has(id)) return res.status(404).json({ error: 'unknown radar' });
  const course = Number(req.body?.course);
  if (!Number.isFinite(course) || course < 0 || course > 360) return res.status(400).json({ error: 'course 0-360 required' });
  try {
    const quietCourse = await radarVoteStore.vote(id, account.id, course, account.role === 'admin');
    radarStore.setQuiet(id, quietCourse);
    res.json({ quietCourse });
  } catch (e) {
    console.warn('[radar-votes] vote failed —', String(e.message || e));
    res.status(503).json({ error: 'votes unavailable' });
  }
});

function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}
