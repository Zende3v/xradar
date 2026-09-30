import { Router } from 'express';
import { config } from '../config.js';
import { accountStore } from '../accounts/store.js';
import { authAccount } from '../accounts/auth.js';
import { settingsStore } from '../accounts/settings.js';
import { reportStore } from '../reports/store.js';
import { worthChecking } from '../routing/faster.js';
import { measure } from '../routing/geometry.js';
import { hereAccountAllows, hereAllows } from './budget.js';
import { crowdAlong, withCrowd } from './crowd.js';
import { datagouvAlong, withDatagouv } from './datagouv.js';
import { hereAlong, hereTravel } from './here.js';
import { probeStore } from './probes.js';
import { speedStore } from './speeds.js';

export const trafficRouter = Router();

// A "Bouchon" made from probes has this author: one voice per report, whatever the drivers.
const PROBES_AUTHOR = 'system:traffic';

/**
 * POST /api/traffic/route  { coordinates: [[lon, lat], …], aheadM?, live?, raw?, sources? }  (Bearer)
 * The slowdowns on this very route, in metres along the polyline sent (`fromM`, `toM`, `level`
 * slow | jam | heavy | closed, `delayS`, `speedKmh`, `kind`), each with its `source` (D2.7):
 * - `here`: HERE's live traffic (flow and incidents, traffic/here.js), since 30/09. Asked unless
 *   `live: false` (the apps' own recalage, D2.2; `tomtom: false` from the apps before HERE), while
 *   the drivers' own speeds do not already cover the route (speedCoverageSkipRatio), and once a
 *   hereAccountGapS per account, hereAccountDailyMax a day. `live` in the answer says whether it
 *   did (`tomtom` too, for those apps), `eonaCoverage` how much of the route the drivers covered.
 *   With it, `travelS`: HERE's time for the route sent, live traffic included (Route Import,
 *   hereTravel), the ETA's base since 30/09 — the engine's alone is far too quick in towns; null
 *   when HERE could not time it.
 * - `crowd`: the drivers' own jams and speeds; `datagouv`: the DIR's speeds and events (D3.2).
 * Merged (the default): the drivers' where they cost more than HERE, then data.gouv for its extra
 * only and only while the switch trafficDatagouv is on (D2.6). With `raw: true` (apps that merge
 * themselves, and compute both ETAs): each source whole, data.gouv included when `sources` lists
 * "datagouv"; `datagouv` says which ETA to show. Apps that do not list "here" in `sources` get
 * HERE's stretches as "tomtom", the live source they know. With `aheadM` (the driver's metres along
 * it), `check` says whether a faster-route check (/api/route/faster) is worth asking.
 */
trafficRouter.post('/route', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const points = pointsOf(req.body?.coordinates);
  if (!points) return res.status(400).json({ error: 'coordinates [[lon,lat],...] required' });
  const path = measure(points);
  const raw = req.body?.raw === true;
  const sources = Array.isArray(req.body?.sources) ? req.body.sources : [];
  const liveName = sources.includes('here') ? 'here' : 'tomtom';
  const wantsLive = req.body?.live !== false && req.body?.tomtom !== false;
  const datagouvOn = settingsStore.trafficDatagouv;

  // The drivers' own speeds first: where they cover the route, HERE is not asked.
  const eona = speedStore.along(path, config.hereCorridorMaxM);
  const covered = eona.coverage >= config.speedCoverageSkipRatio;
  let traffic = { totalM: Math.round(path.total), travelS: null, delayS: null, updatedAt: new Date().toISOString(), sections: [] };
  let live = false;
  if (wantsLive && !covered && hereAllows() && hereAccountAllows(account.id)) {
    // The slowdowns and incidents on the route, and HERE's time for the route itself (the ETA's
    // base: the engine's alone is far too quick in towns), at once.
    const [along, travel] = await Promise.all([
      hereAlong(points, { use: 'eta' }).catch((e) => {
        // HERE silent: the other sources still answer, the ETA goes on without it.
        console.warn('[traffic] HERE unavailable —', String(e.message || e));
        return null;
      }),
      hereTravel(points, { use: 'eta' }).catch((e) => {
        console.warn('[traffic] HERE import unavailable —', String(e.message || e));
        return null;
      }),
    ]);
    if (along) {
      traffic = { ...along, travelS: travel?.travelS ?? null };
      live = true;
    }
  }
  const liveSections = traffic.sections.map((section) => ({ ...section, source: liveName }));
  // Without the drivers' jams (database down), the rest still counts.
  const jams = await crowdAlong(path).catch((e) => {
    console.warn('[traffic] drivers\' jams unavailable —', String(e.message || e));
    return [];
  });
  const crowd = [...jams, ...eona.sections].sort((a, b) => a.fromM - b.fromM);
  const datagouv = (raw ? sources.includes('datagouv') : datagouvOn) ? datagouvAlong(path) : [];
  const merged = withCrowd(liveSections, crowd);
  const sections = raw
    ? [...liveSections, ...crowd, ...datagouv].sort((a, b) => a.fromM - b.fromM)
    : datagouv.length ? withDatagouv(merged, datagouv) : merged;
  const aheadM = Number(req.body?.aheadM);
  res.json({
    ...traffic,
    sections,
    live,
    tomtom: live,
    eonaCoverage: Math.round(eona.coverage * 100) / 100,
    datagouv: datagouvOn,
    minGapS: config.hereAccountGapS,
    ...(Number.isFinite(aheadM) ? { check: (live || covered) && worthChecking(merged, path.total, aheadM) } : {}),
  });
});

/** When each account last sent its speeds (speedPostGapS). */
const lastSpeedsAt = new Map();

/**
 * POST /api/traffic/speeds  { tripKey, samples: [{ lat, lon, course, speedKmh, limitKmh?, t }] }  (Bearer)
 * The driver's speeds during a trip ("Aide au trafic partagé", traffic/speeds.js), anonymous: the
 * account is not kept with them, only [tripKey], a random key the app draws for each trip. At most
 * speedSamplesPerPost samples, one post a speedPostGapS per account (429 otherwise). { kept }.
 */
trafficRouter.post('/speeds', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  if (account.banned) return res.status(403).json({ error: 'banned' });
  const tripKey = String(req.body?.tripKey ?? '');
  const samples = req.body?.samples;
  if (!/^[A-Za-z0-9-]{8,64}$/.test(tripKey) || !Array.isArray(samples)) {
    return res.status(400).json({ error: 'tripKey and samples required' });
  }
  const now = Date.now();
  if (now - (lastSpeedsAt.get(account.id) ?? 0) < config.speedPostGapS * 1000) return res.status(429).json({ error: 'too many speeds' });
  lastSpeedsAt.set(account.id, now);
  if (lastSpeedsAt.size > 10_000) lastSpeedsAt.delete(lastSpeedsAt.keys().next().value);
  res.json({ kept: speedStore.add(tripKey, samples, now) });
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
