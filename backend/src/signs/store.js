import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { config } from '../config.js';

/**
 * Road signs from OpenStreetMap (Overpass), with a **persistent** per-cell disk
 * cache. Overpass can't serve a huge radius live, and signs are very dense — so we
 * query small grid cells once and reuse them forever. A cell is a ~5.5 km square
 * (0.05°); a request assembles all cells covering its radius, fetching only the
 * ones missing from the cache. Fast, reliable, and it fills in as you drive.
 */
class SignStore {
  constructor() {
    this.mem = new Map(); // cellKey -> signs[]
    this.inflight = new Map(); // cellKey -> Promise
  }

  cellKey(latCell, lonCell) {
    return `${latCell}_${lonCell}`;
  }

  async near(lat, lon, radiusM) {
    const step = config.signCellDeg;
    const radDeg = Math.min(radiusM, config.signMaxRadiusM) / 111000;
    const latC0 = Math.round(lat / step);
    const lonC0 = Math.round(lon / step);
    const span = Math.ceil(radDeg / step);
    // Candidate cells within the radius, nearest first.
    const cells = [];
    for (let dy = -span; dy <= span; dy++) {
      for (let dx = -span; dx <= span; dx++) {
        if (dx * dx + dy * dy <= span * span) cells.push([latC0 + dy, lonC0 + dx, dx * dx + dy * dy]);
      }
    }
    cells.sort((a, b) => a[2] - b[2]);
    const nearest = cells.slice(0, config.signMaxCells);

    const out = [];
    let fetched = 0;
    for (const [la, lo] of nearest) {
      const cached = await this.getCached(la, lo);
      if (cached) {
        out.push(...cached);
      } else if (fetched < config.signMaxFetchPerReq) {
        fetched++;
        out.push(...(await this.fetchCellDedup(la, lo)));
      }
      // Missing cells beyond the fetch budget fill in on later requests as you drive.
    }
    return out;
  }

  async getCached(latCell, lonCell) {
    const key = this.cellKey(latCell, lonCell);
    if (this.mem.has(key)) return this.mem.get(key);
    try {
      const j = JSON.parse(await readFile(`${config.signCacheDir}/${key}.json`, 'utf8'));
      if (Date.now() - j.at < config.signCacheTtlMs) {
        this.mem.set(key, j.signs);
        return j.signs;
      }
    } catch (e) { /* miss */ }
    return null;
  }

  fetchCellDedup(latCell, lonCell) {
    const key = this.cellKey(latCell, lonCell);
    if (this.inflight.has(key)) return this.inflight.get(key);
    const file = `${config.signCacheDir}/${key}.json`;
    const p = this.fetchCell(latCell, lonCell, key, file).finally(() => this.inflight.delete(key));
    this.inflight.set(key, p);
    return p;
  }

  async fetchCell(latCell, lonCell, key, file) {
    const step = config.signCellDeg;
    const lat = latCell * step;
    const lon = lonCell * step;
    const r = Math.round((step * 111000) / 1.6); // cell "radius" a bit over half-diagonal
    let signs = [];
    try {
      signs = await fetchOverpass(lat, lon, r);
    } catch (e) {
      // On failure, cache an empty result briefly so we don't hammer Overpass.
      this.mem.set(key, []);
      setTimeout(() => this.mem.delete(key), 60_000).unref?.();
      return [];
    }
    this.mem.set(key, signs);
    mkdir(config.signCacheDir, { recursive: true })
      .then(() => writeFile(file, JSON.stringify({ at: Date.now(), signs })))
      .catch(() => {});
    return signs;
  }
}

async function fetchOverpass(lat, lon, r) {
  const q = `[out:json][timeout:${config.signTimeoutS}];(` +
    `node(around:${r},${lat},${lon})[highway=traffic_signals];` +
    `node(around:${r},${lat},${lon})[highway=stop];` +
    `node(around:${r},${lat},${lon})[highway=give_way];` +
    `node(around:${r},${lat},${lon})[highway=crossing];` +
    `node(around:${r},${lat},${lon})[traffic_sign];` +
    `way(around:${r},${lat},${lon})[junction=roundabout];` +
    `way(around:${r},${lat},${lon})[highway=construction];` +
    `);out center ${config.signMaxElements};`;
  const res = await fetch(config.overpassUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
      'User-Agent': 'x_radar/1.0 (road-safety app)',
    },
    body: `data=${encodeURIComponent(q)}`,
  });
  if (!res.ok) throw new Error(`overpass ${res.status}`);
  const json = await res.json();
  const out = [];
  for (const el of json.elements || []) {
    const type = classify(el.tags || {});
    if (!type) continue;
    const la = el.lat ?? el.center?.lat;
    const lo = el.lon ?? el.center?.lon;
    if (la == null || lo == null) continue;
    out.push({ type, lat: la, lon: lo });
  }
  return out;
}

function classify(tags) {
  if (tags.highway === 'traffic_signals') return 'traffic_signals';
  if (tags.highway === 'stop') return 'stop';
  if (tags.highway === 'give_way') return 'give_way';
  if (tags.highway === 'crossing') return 'crossing';
  if (tags.junction === 'roundabout') return 'roundabout';
  if (tags.highway === 'construction') return 'construction';
  const ts = String(tags.traffic_sign || '').toLowerCase();
  if (ts) {
    if (ts.includes('b1') || ts.includes('no_entry') || ts.includes('sens_interdit')) return 'no_entry';
    if (ts.includes('ab4') || ts.includes('stop')) return 'stop';
    if (ts.includes('ab3') || ts.includes('give_way') || ts.includes('yield')) return 'give_way';
    if (ts.includes('ab25') || ts.includes('roundabout')) return 'roundabout';
    if (ts.includes('ak5') || ts.includes('work')) return 'construction';
  }
  return null;
}

export const signStore = new SignStore();
