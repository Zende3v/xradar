import { Router } from 'express';
import { config } from '../config.js';
import { fuelStore } from '../fuel/store.js';
import { KINDS, describe, near } from './store.js';

export const placeRouter = Router();

/**
 * GET /api/places/near?lat&lon&kind=fuel[&limit=20][&pool=1]
 * The nearest places of a kind, nearest first — no perimeter: PostGIS walks outwards until it
 * has them. Each comes with its opening state and today's hours, and what matters for its
 * kind: power and connectors for a charger, fee and type for a car park, stars for a hotel,
 * the official prices (and declared hours) for a fuel station.
 * `pool=1`: up to `placePoolLimit` places instead of `limit`, for the app to rank them (open
 * first, fuel with a price first).
 */
placeRouter.get('/near', async (req, res) => {
  const lat = Number(req.query.lat);
  const lon = Number(req.query.lon);
  const kind = String(req.query.kind || '');
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) {
    return res.status(400).json({ error: 'lat and lon are required numbers' });
  }
  if (!KINDS.includes(kind)) {
    return res.status(400).json({ error: `unknown kind, expected one of ${KINDS.join(', ')}` });
  }
  const limit = req.query.pool === '1'
    ? config.placePoolLimit
    : Math.max(1, Math.min(Number(req.query.limit) || config.placeLimit, config.placePoolLimit));

  let found;
  try {
    found = await near(kind, lat, lon, limit);
  } catch (e) {
    console.error('[places]', kind, e.message);
    return res.status(503).json({ error: 'places unavailable' });
  }

  const now = new Date();
  if (kind !== 'fuel') {
    const places = found.map((place) => describe(place, { now }));
    return res.json({ count: places.length, places, source: 'postgis' });
  }

  // Fuel: the official station behind each place, matched at answer time (fresh prices).
  const matched = fuelStore.enrich(found.map((place) => ({ ...place, refId: place.tags['ref:FR:prix-carburants'] || null })));
  const places = matched.map(({ fuel, ...place }) => ({
    ...describe(place, { officialHours: fuel ? fuelStore.byId.get(fuel.stationId)?.hours : null, now }),
    fuel,
  }));
  res.json({
    count: places.length,
    places,
    source: 'postgis',
    fuelPrices: {
      provider: 'prix-carburants.gouv.fr',
      ready: fuelStore.meta.ready,
      refreshedAt: fuelStore.meta.refreshedAt,
    },
  });
});
