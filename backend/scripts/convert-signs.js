// Convert an osmium GeoJSON-seq export into a compact NDJSON of {t,lat,lon}.
// Nodes → their coordinate; ways (roundabout/construction) → centroid.
// Ways with maxspeed → speed points sampled ALONG the road, so the app can ask
// "what's the limit where I am?" and get an answer anywhere on the way.
import { createReadStream, createWriteStream } from 'node:fs';
import { createInterface } from 'node:readline';

const [, , input, output] = process.argv;
if (!input || !output) {
  console.error('usage: node convert-signs.js <input.geojsonl> <output.ndjson>');
  process.exit(1);
}

const SPEED_SAMPLE_M = 150; // one speed point every ~150 m along a way

function classify(p) {
  if (p.highway === 'traffic_signals') return 'traffic_signals';
  if (p.highway === 'stop') return 'stop';
  if (p.highway === 'give_way') return 'give_way';
  if (p.highway === 'crossing') return 'crossing';
  if (p.junction === 'roundabout') return 'roundabout';
  if (p.highway === 'construction') return 'construction';
  const ts = String(p.traffic_sign || '').toLowerCase();
  if (ts) {
    if (ts.includes('b1') || ts.includes('no_entry') || ts.includes('sens_interdit')) return 'no_entry';
    if (ts.includes('ab4') || ts.includes('stop')) return 'stop';
    if (ts.includes('ab3') || ts.includes('give_way') || ts.includes('yield')) return 'give_way';
    if (ts.includes('ab25') || ts.includes('roundabout')) return 'roundabout';
    if (ts.includes('ak5') || ts.includes('work')) return 'construction';
  }
  return null;
}

function parseSpeed(v) {
  if (!v) return null;
  const s = String(v).toLowerCase();
  if (s.includes('walk')) return 20;
  if (s.includes('urban')) return 50;
  if (s.includes('rural')) return 80;
  if (s.includes('motorway')) return 130;
  if (s.includes('none')) return null;
  const m = s.match(/\d+/);
  if (!m) return null;
  const n = parseInt(m[0], 10);
  if (s.includes('mph')) return Math.round(n * 1.60934);
  return n >= 10 && n <= 130 ? n : null;
}

function pairs(coords, acc) {
  if (typeof coords[0] === 'number') acc.push(coords);
  else for (const c of coords) pairs(c, acc);
  return acc;
}

/** Rough metres between two [lon,lat] points (equirectangular, fine at this scale). */
function distM(a, b) {
  const mLat = 111320;
  const mLon = 111320 * Math.cos((a[1] * Math.PI) / 180);
  const dx = (b[0] - a[0]) * mLon;
  const dy = (b[1] - a[1]) * mLat;
  return Math.sqrt(dx * dx + dy * dy);
}

/** Walk a polyline and return points every [stepM] (always includes the first). */
function sampleAlong(pts, stepM) {
  const out = [pts[0]];
  let acc = 0;
  for (let i = 1; i < pts.length; i++) {
    let seg = distM(pts[i - 1], pts[i]);
    if (!Number.isFinite(seg) || seg <= 0) continue;
    let from = pts[i - 1];
    while (acc + seg >= stepM) {
      const need = stepM - acc;
      const t = need / seg;
      const p = [from[0] + (pts[i][0] - from[0]) * t, from[1] + (pts[i][1] - from[1]) * t];
      out.push(p);
      from = p;
      seg -= need;
      acc = 0;
    }
    acc += seg;
  }
  return out;
}

const rl = createInterface({ input: createReadStream(input), crlfDelay: Infinity });
const out = createWriteStream(output);
let n = 0;

for await (const raw of rl) {
  const line = raw.replace(/\x1e/g, '').trim(); // geojsonseq record separator
  if (!line) continue;
  let f;
  try { f = JSON.parse(line); } catch { continue; }
  const props = f.properties || {};
  const t = classify(props);
  const speed = parseSpeed(props.maxspeed);
  if (!t && speed == null) continue;
  const g = f.geometry;
  if (!g || !g.coordinates) continue;

  const isPoint = g.type === 'Point';
  const pts = isPoint ? [g.coordinates] : pairs(g.coordinates, []);
  if (!pts.length) continue;

  if (t) {
    let lon, lat;
    if (isPoint) {
      [lon, lat] = pts[0];
    } else {
      let sx = 0, sy = 0;
      for (const [x, y] of pts) { sx += x; sy += y; }
      lon = sx / pts.length; lat = sy / pts.length;
    }
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      out.write(`{"t":"${t}","lat":${lat.toFixed(6)},"lon":${lon.toFixed(6)}}\n`);
      n++;
    }
  }

  if (speed != null) {
    const samples = isPoint ? pts : sampleAlong(pts, SPEED_SAMPLE_M);
    for (const [lon, lat] of samples) {
      if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
      out.write(`{"t":"speed","v":${speed},"lat":${lat.toFixed(6)},"lon":${lon.toFixed(6)}}\n`);
      n++;
    }
  }
}

out.end(() => console.log(`signs écrits : ${n}`));
