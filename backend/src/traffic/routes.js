import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { authAccount } from '../accounts/auth.js';
import { settingsStore } from '../accounts/settings.js';
import { reportStore } from '../reports/store.js';
import { worthChecking } from '../routing/faster.js';
import { measure } from '../routing/geometry.js';
import { etaMinGapS, tomtomAllows } from './budget.js';
import { crowdAlong, withCrowd } from './crowd.js';
import { datagouvAlong, withDatagouv } from './datagouv.js';
import { probeStore } from './probes.js';
import { trafficAlong } from './tomtom.js';

export const trafficRouter = Router();

// A "Bouchon" made from probes has this author: one voice per report, whatever the drivers.
const PROBES_AUTHOR = 'system:traffic';

/** When each account last had TomTom time its route for the ETA (trafficTomtomAccountGapS). */
const lastTomtomAt = new Map();

/**
 * POST /api/traffic/route  { coordinates: [[lon, lat], …], aheadM?, tomtom?, raw?, sources? }  (Bearer)
 * The slowdowns on this very route, in metres along the polyline sent (`fromM`, `toM`, `level`
 * slow | jam | heavy | closed, `delayS`, `speedKmh`), each with its `source` (D2.7):
 * - `tomtom`: TomTom's now, with `travelS` / `delayS` its time for the route with traffic and the
 *   part lost to it. Asked unless `tomtom: false` (the apps' own recalage, D2.2), within the
 *   day's budget (D3.1) and once a trafficTomtomAccountGapS per account; `tomtom` in the answer
 *   says whether it did, `minGapS` how long the app waits before the next recalage.
 * - `crowd`: the drivers' own jams; `datagouv`: the DIR's speeds and events (D3.2, `kind`).
 * Merged (the default): the drivers' jams where they cost more than TomTom, then data.gouv for
 * its extra only and only while the switch trafficDatagouv is on (D2.6). With `raw: true` (apps
 * that merge themselves, and compute both ETAs): each source whole, data.gouv included when
 * `sources` lists "datagouv"; `datagouv` says which ETA to show. With `aheadM` (the driver's metres
 * along it), `check` says whether a faster-route check (/api/route/faster) is worth asking: only
 * when TomTom answered.
 */
trafficRouter.post('/route', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const points = pointsOf(req.body?.coordinates);
  if (!points) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  const path = measure(points);
  const raw = req.body?.raw === true;
  const readsDatagouv = Array.isArray(req.body?.sources) && req.body.sources.includes('datagouv');
  const datagouvOn = settingsStore.trafficDatagouv;

  let traffic = { totalM: Math.round(path.total), travelS: null, delayS: null, updatedAt: new Date().toISOString(), sections: [] };
  let tomtom = false;
  const now = Date.now();
  const gapOk = now - (lastTomtomAt.get(account.id) ?? 0) >= config.trafficTomtomAccountGapS * 1000;
  if (config.tomtomApiKey && req.body?.tomtom !== false && gapOk && tomtomAllows('eta')) {
    lastTomtomAt.set(account.id, now);
    try {
      traffic = await trafficAlong(points, { use: 'eta' });
      tomtom = true;
    } catch (e) {
      // TomTom silent: the other sources still answer, the ETA goes on without it.
      console.warn('[traffic] TomTom unavailable —', String(e.message || e));
    }
  }
  const tomtomSections = traffic.sections.map((s) => ({ ...s, source: 'tomtom' }));
  // Without the drivers' jams (database down), the rest still counts.
  const crowd = await crowdAlong(path).catch((e) => {
    console.warn('[traffic] drivers\' jams unavailable —', String(e.message || e));
    return [];
  });
  const datagouv = (raw ? readsDatagouv : datagouvOn) ? datagouvAlong(path) : [];
  const merged = withCrowd(tomtomSections, crowd);
  const sections = raw
    ? [...tomtomSections, ...crowd, ...datagouv].sort((a, b) => a.fromM - b.fromM)
    : datagouv.length ? withDatagouv(merged, datagouv) : merged;
  const aheadM = Number(req.body?.aheadM);
  res.json({
    ...traffic,
    sections,
    tomtom,
    datagouv: datagouvOn,
    minGapS: etaMinGapS(),
    ...(Number.isFinite(aheadM) ? { check: tomtom && worthChecking(merged, path.total, aheadM) } : {}),
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
