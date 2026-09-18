import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { authAccount } from '../accounts/auth.js';
import { reportStore } from '../reports/store.js';
import { worthChecking } from '../routing/faster.js';
import { measure } from '../routing/geometry.js';
import { crowdAlong, withCrowd } from './crowd.js';
import { probeStore } from './probes.js';
import { trafficAlong } from './tomtom.js';

export const trafficRouter = Router();

// A "Bouchon" made from probes has this author: one voice per report, whatever the drivers.
const PROBES_AUTHOR = 'system:traffic';

/**
 * POST /api/traffic/route  { coordinates: [[lon, lat], …], aheadM? }  (Bearer)
 * The slowdowns on this very route: TomTom's now (stretches in metres along the polyline sent:
 * `fromM`, `toM`, `level` slow | jam | heavy | closed, `delayS`, `speedKmh`), and the drivers'
 * own jams where they cost more than TomTom says (`source: "crowd"`, the extra time only), with
 * `totalM` its length and `travelS` / `delayS` TomTom's time for it with traffic and the part
 * lost to traffic. With `aheadM` (the driver's metres along it), `check` says whether a
 * faster-route check (/api/route/faster) is worth asking. 503 without a TomTom key, 502 when
 * TomTom does not answer.
 */
trafficRouter.post('/route', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  if (!config.tomtomApiKey) return res.status(503).json({ error: 'traffic unavailable' });
  const points = pointsOf(req.body?.coordinates);
  if (!points) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  let traffic;
  try {
    traffic = await trafficAlong(points);
  } catch (e) {
    console.warn('[traffic] unavailable —', String(e.message || e));
    return res.status(502).json({ error: 'traffic unavailable' });
  }
  const path = measure(points);
  // The drivers' jams add to TomTom; without them (database down) TomTom's alone still count.
  const crowd = await crowdAlong(path).catch((e) => {
    console.warn('[traffic] drivers\' jams unavailable —', String(e.message || e));
    return [];
  });
  const sections = withCrowd(traffic.sections, crowd);
  const aheadM = Number(req.body?.aheadM);
  res.json({
    ...traffic,
    sections,
    ...(Number.isFinite(aheadM) ? { check: worthChecking(sections, path.total, aheadM) } : {}),
  });
});

/**
 * POST /api/traffic/probe  { lat, lon, bearing, speedKmh, limitKmh }  (Bearer)
 * A slowdown the app measured: a crawl (under probeMaxSpeedRatio of the limit) on a road
 * limited to probeMinLimitKmh or more. Kept anonymous, in memory (traffic/probes.js); with
 * probeClusterMinDrivers different drivers there, it becomes a "Bouchon". Answers { known }:
 * the jam is already known there (a live "Bouchon" for that way), the app asks the driver
 * nothing. 429 when the driver sent one less than a minute ago.
 */
trafficRouter.post('/probe', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const lat = Number(req.body?.lat);
  const lon = Number(req.body?.lon);
  const course = Number(req.body?.bearing);
  const speedKmh = Number(req.body?.speedKmh);
  const limitKmh = Number(req.body?.limitKmh);
  if (![lat, lon, course, speedKmh, limitKmh].every(Number.isFinite) || Math.abs(lat) > 90 || Math.abs(lon) > 180
    || speedKmh < 0 || limitKmh < config.probeMinLimitKmh || limitKmh > 130 || speedKmh >= limitKmh * config.probeMaxSpeedRatio) {
    return res.status(400).json({ error: 'not a slowdown' });
  }
  const added = probeStore.add(account.id, { lat, lon, course: ((course % 360) + 360) % 360, speedKmh, limitKmh });
  if (!added.accepted) return res.status(429).json({ error: 'too many probes' });
  try {
    const cluster = added.cluster;
    if (cluster.drivers >= config.probeClusterMinDrivers && probeStore.claimJam(cluster.lat, cluster.lon, cluster.course)) {
      const made = await reportStore.add({
        type: 'traffic_jam',
        lat: cluster.lat,
        lon: cluster.lon,
        reporterId: PROBES_AUTHOR,
        reporterRole: 'system',
        bearing: cluster.course,
      });
      if (made?.authorFirstConfirmed) accountStore.recordReportStat(made.authorFirstConfirmed, 'reportsConfirmed');
      console.log(`[probes] jam from ${cluster.drivers} drivers${made?.merged ? ', joined a report' : ''}`);
      if (made) return res.json({ known: true });
    }
    const known = await reportStore.liveNear('traffic_jam', lat, lon, config.probeClusterRadiusM, course, config.crowdSameWayDeg);
    res.json({ known });
  } catch (e) {
    // The probe is kept; only the answer is unsure: the app may ask the driver.
    console.warn('[probes] reports unavailable —', String(e.message || e));
    res.json({ known: false });
  }
});

/** POST /api/traffic/probe/dismiss  (Bearer) — "Non": the driver's recent probes are taken back. */
trafficRouter.post('/probe/dismiss', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  probeStore.dismiss(account.id);
  res.json({ ok: true });
});

/** [[lon, lat], …] as [lat, lon] points; null when missing, too many or malformed. */
function pointsOf(coords) {
  if (!Array.isArray(coords) || coords.length < 2 || coords.length > config.trafficMaxPoints) return null;
  const points = [];
  for (const c of coords) {
    const lon = Number(c?.[0]);
    const lat = Number(c?.[1]);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    points.push([lat, lon]);
  }
  return points;
}
