import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { createInterface } from 'node:readline';
import { config } from '../config.js';
import { bearingDeg, haversine } from '../radars/geo.js';

export const SIGN_TYPES = [
  'traffic_signals', 'stop', 'give_way', 'crossing', 'roundabout', 'construction', 'no_entry', 'speed',
  'level_crossing',
];
const SPEED_IDX = 7;
const TYPE_INDEX = Object.fromEntries(SIGN_TYPES.map((t, i) => [t, i]));

/**
 * Whole-France signs preloaded from an NDJSON dataset into compact typed arrays
 * with a coarse grid index. Answers "near a point" and "along a route" fast, with
 * no Overpass at runtime. ~2–3 M points fit comfortably (< ~60 MB).
 */
class SignDataset {
  constructor() {
    this.lat = null;
    this.lon = null;
    this.type = null;
    this.val = null; // speed value (km/h) for speed points, else 0
    this.count = 0;
    this.grid = new Map(); // cellKey -> number[] (indices)
    this.ready = false;
    this.loading = false;
    // Speed-limit changes drivers validated, laid over the OSM limits (speedlimits/store.js):
    // anything with nearest(lat, lon, maxDistM, bearing) → { d, v, lat, lon, key } | null.
    this.overrides = null;
  }

  key(la, lo) {
    return `${Math.round(la / config.signGridDeg)}_${Math.round(lo / config.signGridDeg)}`;
  }

  async load() {
    this.loading = true;
    try {
      return await this.read();
    } finally {
      this.loading = false;
    }
  }

  async read() {
    // Two independent sources: the OSM signs dump and the SNCF level crossings.
    // Either one alone is enough to serve; only both missing falls back to Overpass.
    const crossings = await loadLevelCrossings();
    let size = 0;
    let hasSigns = true;
    try {
      size = (await stat(config.signDataFile)).size;
    } catch {
      hasSigns = false;
    }
    if (!hasSigns && crossings.length === 0) {
      console.log('[signs] no dataset file — using live Overpass fallback');
      return false;
    }

    // First pass count, then fill (avoids array growth). Cheap two-pass read.
    let n = crossings.length;
    if (hasSigns) for await (const _ of lines(config.signDataFile)) n++;
    this.lat = new Float64Array(n);
    this.lon = new Float64Array(n);
    this.type = new Uint8Array(n);
    this.val = new Uint16Array(n);

    let i = 0;
    const push = (la, lo, ti, value) => {
      this.lat[i] = la; this.lon[i] = lo; this.type[i] = ti; this.val[i] = value;
      const k = this.key(la, lo);
      let cell = this.grid.get(k);
      if (!cell) { cell = []; this.grid.set(k, cell); }
      cell.push(i);
      i++;
    };

    if (hasSigns) {
      for await (const line of lines(config.signDataFile)) {
        if (!line) continue;
        let o;
        try { o = JSON.parse(line); } catch { continue; }
        const ti = TYPE_INDEX[o.t];
        if (ti === undefined || !Number.isFinite(o.lat) || !Number.isFinite(o.lon)) continue;
        push(o.lat, o.lon, ti, ti === SPEED_IDX ? (o.v | 0) : 0);
      }
    }
    for (const [la, lo] of crossings) push(la, lo, TYPE_INDEX.level_crossing, 0);

    this.count = i;
    this.ready = true;
    console.log(
      `[signs] dataset loaded: ${this.count} points` +
      (hasSigns ? ` (OSM ${(size / 1e6).toFixed(0)} MB)` : ' (aucun fichier OSM)') +
      ` · ${crossings.length} passages à niveau`,
    );
    return true;
  }

  *candidates(latMin, latMax, lonMin, lonMax) {
    const step = config.signGridDeg;
    for (let la = Math.floor(latMin / step); la <= Math.ceil(latMax / step); la++) {
      for (let lo = Math.floor(lonMin / step); lo <= Math.ceil(lonMax / step); lo++) {
        const cell = this.grid.get(`${la}_${lo}`);
        if (cell) for (const idx of cell) yield idx;
      }
    }
  }

  near(lat, lon, radiusM, limit) {
    const dLat = radiusM / 111000;
    const dLon = radiusM / (111000 * Math.cos((lat * Math.PI) / 180) || 1);
    const out = [];
    for (const i of this.candidates(lat - dLat, lat + dLat, lon - dLon, lon + dLon)) {
      if (this.type[i] === SPEED_IDX) continue; // speed limits show on-route only
      const d = haversine(lat, lon, this.lat[i], this.lon[i]);
      if (d <= radiusM) {
        out.push({ type: SIGN_TYPES[this.type[i]], lat: this.lat[i], lon: this.lon[i], d });
        if (out.length > limit * 4) break;
      }
    }
    out.sort((a, b) => a.d - b.d);
    return out.slice(0, limit).map(({ d, ...s }) => s);
  }

  /**
   * Speed limit (km/h) at a position: nearest sampled speed point, or null. A validated
   * change covering the spot wins; [bearing] (the driver's course, optional) keeps a change
   * validated for one way from applying to the other.
   */
  limitAt(lat, lon, maxDistM, bearing = null) {
    const dLat = maxDistM / 111000;
    const dLon = maxDistM / (111000 * Math.cos((lat * Math.PI) / 180) || 1);
    let best = null;
    for (const i of this.candidates(lat - dLat, lat + dLat, lon - dLon, lon + dLon)) {
      if (this.type[i] !== SPEED_IDX) continue;
      const d = haversine(lat, lon, this.lat[i], this.lon[i]);
      if (d <= maxDistM && (best === null || d < best.d)) best = { d, v: this.val[i] };
    }
    const over = this.overrides?.nearest(lat, lon, maxDistM, bearing);
    if (over && (best === null || over.d <= best.d + config.speedLimitOverrideTieM)) return over.v;
    return best ? best.v : null;
  }

  /** The mapped (OSM) speed points within [radiusM] of a position, as { lat, lon, v }. */
  speedPointsNear(lat, lon, radiusM) {
    const dLat = radiusM / 111000;
    const dLon = radiusM / (111000 * Math.cos((lat * Math.PI) / 180) || 1);
    const out = [];
    for (const i of this.candidates(lat - dLat, lat + dLat, lon - dLon, lon + dLon)) {
      if (this.type[i] !== SPEED_IDX) continue;
      if (haversine(lat, lon, this.lat[i], this.lon[i]) <= radiusM) {
        out.push({ lat: this.lat[i], lon: this.lon[i], v: this.val[i] });
      }
    }
    return out;
  }

  /** Signs within [bufferM] of the route ([[lon,lat],...]) + speed-limit CHANGES. */
  route(coords, bufferM, limit) {
    if (!coords || coords.length < 2) return [];
    const seen = new Set();
    const speedSeen = new Set();
    let lastSpeed = -1;
    const out = [];
    const step = Math.max(1, Math.floor(coords.length / 2000));
    for (let s = 0; s < coords.length; s += step) {
      const [lon, lat] = coords[s];
      const dLat = bufferM / 111000;
      const dLon = bufferM / (111000 * Math.cos((lat * Math.PI) / 180) || 1);
      let bestSpeed = null; // nearest speed point at this sample
      for (const i of this.candidates(lat - dLat, lat + dLat, lon - dLon, lon + dLon)) {
        if (this.type[i] === SPEED_IDX) {
          if (speedSeen.has(i)) continue;
          const d = haversine(lat, lon, this.lat[i], this.lon[i]);
          if (d <= bufferM && (bestSpeed === null || d < bestSpeed.d)) bestSpeed = { i, d };
          continue;
        }
        if (seen.has(i)) continue;
        if (haversine(lat, lon, this.lat[i], this.lon[i]) <= bufferM) {
          seen.add(i);
          out.push({ type: SIGN_TYPES[this.type[i]], lat: this.lat[i], lon: this.lon[i] });
          if (out.length >= limit) return out;
        }
      }
      // A change drivers validated on this stretch wins, for the way the route runs.
      const next = coords[Math.min(s + step, coords.length - 1)];
      const course = next !== coords[s] ? bearingDeg(lat, lon, next[1], next[0]) : null;
      const over = this.overrides?.nearest(lat, lon, bufferM, course);
      let speed = null;
      if (over && (bestSpeed === null || over.d <= bestSpeed.d + config.speedLimitOverrideTieM)) {
        if (!speedSeen.has(over.key)) {
          speedSeen.add(over.key);
          speed = { v: over.v, lat: over.lat, lon: over.lon };
        }
      } else if (bestSpeed) {
        speedSeen.add(bestSpeed.i);
        speed = { v: this.val[bestSpeed.i], lat: this.lat[bestSpeed.i], lon: this.lon[bestSpeed.i] };
      }
      if (speed && speed.v !== lastSpeed) { // only emit where the limit actually changes
        out.push({ type: 'speed', v: speed.v, lat: speed.lat, lon: speed.lon });
        lastSpeed = speed.v;
      }
    }
    return out;
  }
}

/** SNCF level crossings: a GeoJSON FeatureCollection of points. */
async function loadLevelCrossings() {
  let raw;
  try {
    raw = await readFile(config.levelCrossingFile, 'utf8');
  } catch {
    console.log('[signs] no level-crossing file — skipped');
    return [];
  }
  try {
    const json = JSON.parse(raw);
    const out = [];
    for (const f of json.features ?? []) {
      const c = f?.geometry?.coordinates;
      if (!Array.isArray(c) || !Number.isFinite(c[0]) || !Number.isFinite(c[1])) continue;
      out.push([c[1], c[0]]); // [lat, lon]
    }
    return out;
  } catch (e) {
    console.error('[signs] level crossings unreadable:', e.message);
    return [];
  }
}

async function* lines(file) {
  const rl = createInterface({ input: createReadStream(file), crlfDelay: Infinity });
  for await (const line of rl) yield line;
}

export const signDataset = new SignDataset();
