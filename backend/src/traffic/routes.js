import { Router } from 'express';
import { config } from '../config.js';
import { authAccount } from '../accounts/auth.js';
import { trafficAlong } from './tomtom.js';

export const trafficRouter = Router();

/**
 * POST /api/traffic/route  { coordinates: [[lon, lat], …] }  (Bearer)
 * The slowdowns on this very route, as TomTom sees them now: stretches in metres along the
 * polyline sent (`fromM`, `toM`, `level` slow | jam | heavy | closed, `delayS`, `speedKmh`),
 * with `totalM` its length. 503 without a TomTom key, 502 when TomTom does not answer.
 */
trafficRouter.post('/route', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  if (!config.tomtomApiKey) return res.status(503).json({ error: 'traffic unavailable' });
  const coords = req.body?.coordinates;
  if (!Array.isArray(coords) || coords.length < 2 || coords.length > config.trafficMaxPoints) {
    return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  }
  const points = [];
  for (const c of coords) {
    const lon = Number(c?.[0]);
    const lat = Number(c?.[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return res.status(400).json({ error: 'invalid coordinate' });
    points.push([lat, lon]);
  }
  try {
    res.json(await trafficAlong(points));
  } catch (e) {
    console.warn('[traffic] unavailable —', String(e.message || e));
    res.status(502).json({ error: 'traffic unavailable' });
  }
});
