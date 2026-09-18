import { createHash } from 'node:crypto';
import { config } from '../config.js';
import { haversine } from '../radars/geo.js';

// TomTom's own points farther than this from our route are on another road.
const ON_ROUTE_M = 40;
// How far ahead of the last matched point a TomTom point is looked for along our route.
const LOOKAHEAD_M = 5000;
// Shorter stretches are not worth a colour.
const MIN_STRETCH_M = 15;

const cache = new Map(); // key -> { at, value }

/** Cumulative metres along [points] ([lat, lon]). */
function cumulative(points) {
  const cum = new Array(points.length).fill(0);
  for (let i = 1; i < points.length; i++) {
    cum[i] = cum[i - 1] + haversine(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1]);
  }
  return cum;
}

/**
 * The supporting points sent to TomTom: our route thinned to a point every [spacing] metres or
 * more (the ends kept), no more than config.trafficMaxSupportingPoints of them.
 */
function supportingPoints(points, cum) {
  const total = cum[cum.length - 1];
  const spacing = Math.max(config.trafficSupportingSpacingM, total / config.trafficMaxSupportingPoints);
  const out = [points[0]];
  let last = 0;
  for (let i = 1; i < points.length - 1; i++) {
    if (cum[i] - last >= spacing) {
      out.push(points[i]);
      last = cum[i];
    }
  }
  out.push(points[points.length - 1]);
  return out;
}

/** Where [lat, lon] falls on segment a→b: its share t along it and its distance to it, in metres. */
function nearestOnSegment(a, b, lat, lon) {
  const mLat = 111_320;
  const mLon = 111_320 * Math.cos((lat * Math.PI) / 180);
  const ax = a[1] * mLon, ay = a[0] * mLat;
  const bx = b[1] * mLon, by = b[0] * mLat;
  const px = lon * mLon, py = lat * mLat;
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 === 0 ? 0 : Math.min(Math.max(((px - ax) * dx + (py - ay) * dy) / len2, 0), 1);
  const sx = ax + t * dx, sy = ay + t * dy;
  return { t, dist: Math.hypot(px - sx, py - sy) };
}

/**
 * Metres along our route of each TomTom point, in order; null where TomTom's road leaves ours.
 * The match only moves forward, so a route crossing itself keeps its order.
 */
function project(route, cum, points) {
  const out = new Array(points.length).fill(null);
  let seg = 0;
  let lastAlong = 0;
  for (let j = 0; j < points.length; j++) {
    const [lat, lon] = points[j];
    let best = null;
    for (let i = seg; i < route.length - 1 && cum[i] <= lastAlong + LOOKAHEAD_M; i++) {
      const m = nearestOnSegment(route[i], route[i + 1], lat, lon);
      if (!best || m.dist < best.dist) best = { i, t: m.t, dist: m.dist };
    }
    if (best && best.dist <= ON_ROUTE_M) {
      const along = cum[best.i] + best.t * (cum[best.i + 1] - cum[best.i]);
      out[j] = along;
      seg = best.i;
      lastAlong = along;
    }
  }
  return out;
}

/**
 * How bad a TomTom traffic section is: slow, jam, heavy or closed; null when no time is lost
 * there now (road works or events without effect: the road keeps its usual look).
 */
function levelOf(section) {
  if (section.simpleCategory === 'ROAD_CLOSURE') return 'closed';
  if (!(section.delayInSeconds > 0)) return null;
  switch (section.magnitudeOfDelay) {
    case 2: return 'jam';
    case 3: return 'heavy';
    // 1 = minor; 0 (unknown) and 4 (undefined) with time lost count as slow.
    default: return 'slow';
  }
}

/**
 * The traffic TomTom sees on our own route: [points] ([lat, lon], the route the app follows)
 * go back to it as supporting points, so it rebuilds this very route rather than its own, and
 * its slowed stretches come back as metres along [points]. Only what lies on our road is kept.
 * Answers are cached a short while: several drivers on the same route share one request.
 */
export async function trafficAlong(points) {
  const cum = cumulative(points);
  const support = supportingPoints(points, cum);
  const key = createHash('sha1').update(JSON.stringify(support.map(([lat, lon]) => [lat.toFixed(5), lon.toFixed(5)]))).digest('hex');
  const hit = cache.get(key);
  if (hit && Date.now() - hit.at < config.trafficCacheMs) return hit.value;

  const [from, to] = [support[0], support[support.length - 1]];
  const url = `${config.tomtomUrl.replace(/\/$/, '')}/routing/1/calculateRoute/${from[0]},${from[1]}:${to[0]},${to[1]}/json` +
    `?key=${encodeURIComponent(config.tomtomApiKey)}&traffic=true&sectionType=traffic&travelMode=car&routeType=fastest`;
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ supportingPoints: support.map(([latitude, longitude]) => ({ latitude, longitude })) }),
    signal: AbortSignal.timeout(config.trafficTimeoutMs),
  });
  if (!res.ok) throw new Error(`TomTom ${res.status}`);
  const body = await res.json();
  const route = body.routes?.[0];
  if (!route) throw new Error('TomTom: no route');

  const tomtomPoints = route.legs.flatMap((leg) => leg.points.map((p) => [p.latitude, p.longitude]));
  const alongs = project(points, cum, tomtomPoints);
  const stretches = [];
  for (const section of route.sections ?? []) {
    if (section.sectionType !== 'TRAFFIC') continue;
    const level = levelOf(section);
    if (!level) continue;
    let run = [];
    const flush = () => {
      if (run.length >= 2) {
        const fromM = Math.min(...run);
        const toM = Math.max(...run);
        if (toM - fromM >= MIN_STRETCH_M) {
          stretches.push({
            fromM: Math.round(fromM),
            toM: Math.round(toM),
            level,
            delayS: section.delayInSeconds ?? null,
            speedKmh: section.effectiveSpeedInKmh ?? null,
          });
        }
      }
      run = [];
    };
    for (let j = section.startPointIndex; j <= section.endPointIndex; j++) {
      if (alongs[j] == null) flush();
      else run.push(alongs[j]);
    }
    flush();
  }
  stretches.sort((a, b) => a.fromM - b.fromM);

  const value = { totalM: Math.round(cum[cum.length - 1]), updatedAt: new Date().toISOString(), sections: stretches };
  cache.set(key, { at: Date.now(), value });
  if (cache.size > 200) cache.delete(cache.keys().next().value);
  return value;
}
