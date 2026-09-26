import { request as httpRequest } from 'node:http';

// Shared fixtures of the routing tests: no request ever leaves the machine (fetch is trapped),
// no database is reached (a fake one records the queries), engines are fakes.

/** Swaps globalThis.fetch for a trap while a file runs; `count()` says how many calls it caught. */
export function trapNetwork(before, after) {
  const real = globalThis.fetch;
  let stray = 0;
  before(() => {
    globalThis.fetch = async () => {
      stray += 1;
      throw new Error('network forbidden in these tests');
    };
  });
  after(() => {
    globalThis.fetch = real;
  });
  return { count: () => stray };
}

export const PARIS = { lat: 48.856614, lon: 2.352222 };
export const ETOILE = { lat: 48.873792, lon: 2.295028 };
/** Brussels: out of a France-only map. */
export const BRUSSELS = { lat: 50.846557, lon: 4.351697 };

/** [n] points from [from] to [to] in a straight line, [lon, lat]; bent north by [bendDeg] in the middle. */
export function line(from, to, n = 12, bendDeg = 0) {
  const out = [];
  for (let i = 0; i < n; i++) {
    const t = i / (n - 1);
    const bend = bendDeg * Math.sin(Math.PI * t);
    out.push([from.lon + (to.lon - from.lon) * t, from.lat + (to.lat - from.lat) * t + bend]);
  }
  return out;
}

/** A route in the app's shape, as a provider gives it. */
export function appRoute(engine, coordinates, { distanceM = 5000, durationS = 600, mapVersion = null, uturn = false } = {}) {
  const first = coordinates[0];
  const last = coordinates[coordinates.length - 1];
  return {
    distanceM,
    durationS,
    coordinates,
    steps: [
      { type: 'depart', modifier: null, location: first, exit: null, name: 'Rue de Rivoli', distanceM, durationS },
      { type: 'turn', modifier: uturn ? 'uturn' : 'right', location: coordinates[1], exit: null, name: 'Avenue', distanceM: 0, durationS: 0 },
      { type: 'arrive', modifier: null, location: last, exit: null, name: '', distanceM: 0, durationS: 0 },
    ],
    engine,
    mapVersion,
  };
}

/** An ORS GeoJSON feature for [coordinates]. */
export function orsFeature(coordinates, { distance = 5000, duration = 600 } = {}) {
  return {
    type: 'Feature',
    geometry: { type: 'LineString', coordinates },
    properties: {
      summary: { distance, duration },
      segments: [{
        steps: [
          { type: 11, way_points: [0, 0], distance, duration, name: 'Rue de Rivoli' },
          { type: 10, way_points: [coordinates.length - 1, coordinates.length - 1], distance: 0, duration: 0, name: '-' },
        ],
      }],
    },
  };
}

/** A fetch-like ORS answer: [features] (ok), or a refusal ([status], [text]), or the spent budget. */
export function orsAnswer(features) {
  const json = { features, metadata: { engine: { graph_date: '2026-09-20T00:00:00Z' } } };
  return { ok: true, status: 200, json: async () => json, text: async () => JSON.stringify(json) };
}
export function orsRefusal(status, text = 'refused') {
  return { ok: false, status, detail: text, text: async () => text, json: async () => ({ error: text }) };
}
export const ORS_SPENT = { ok: false, status: 429, budgetSpent: true, text: async () => 'ORS keys exhausted', json: async () => ({}) };

/** A fake ORS for the façade: [respond] per call (body, signal); what it was sent in `bodies`. */
export function fakeOrs({ respond, configured = true, budgetLeft = 3000 } = {}) {
  const bodies = [];
  const ors = {
    bodies,
    configured: () => configured,
    budgetLeft: () => ors.budget,
    budget: budgetLeft,
    post: async (body, { signal } = {}) => {
      bodies.push(body);
      return respond ? respond(body, signal) : orsAnswer([orsFeature(line(PARIS, ETOILE))]);
    },
  };
  return ors;
}

/**
 * A fake Valhalla client: [routes] per call (from, to, options) → routes (the app's shape) or a
 * throw; [status] per /status. What it was asked in `calls`.
 */
export function fakeValhalla({ routes, status } = {}) {
  const calls = [];
  const statuses = [];
  return {
    calls,
    statuses,
    routes: async (from, to, options = {}) => {
      calls.push({ from, to, options });
      if (routes) return routes(from, to, options);
      return [appRoute('valhalla', line(from, to), { mapVersion: '2026-09-20T03:00:00Z' })];
    },
    status: async () => {
      statuses.push(Date.now());
      if (status) return status();
      return {
        version: '3.9.0', tileset_last_modified: 1789873200, has_tiles: true, has_admins: true, has_timezones: true,
        has_live_traffic: false, has_transit_tiles: false, osm_changeset: 1, available_actions: ['route', 'status'], bbox: null, warnings: null,
      };
    },
  };
}

/** A promise and the functions settling it. */
export function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** A fake pg pool: every query recorded; [answer](sql, params) gives { rows }. */
export function fakeDb(answer = () => ({ rows: [] })) {
  const queries = [];
  return {
    queries,
    query: async (sql, params = []) => {
      queries.push({ sql, params });
      return answer(sql, params);
    },
  };
}

/** Polyline6, the reference algorithm, for Valhalla's answers. */
export function encodePolyline6(points) {
  let out = '';
  let lastLat = 0;
  let lastLon = 0;
  for (const [lon, lat] of points) {
    const la = Math.round(lat * 1e6);
    const lo = Math.round(lon * 1e6);
    out += encodeValue(la - lastLat) + encodeValue(lo - lastLon);
    lastLat = la;
    lastLon = lo;
  }
  return out;
}

function encodeValue(value) {
  let n = value < 0 ? -2 * value - 1 : 2 * value;
  let out = '';
  while (n >= 0x20) {
    out += String.fromCharCode((0x20 | (n % 32)) + 63);
    n = Math.floor(n / 32);
  }
  return out + String.fromCharCode(n + 63);
}

/** Valhalla's OSRM-format answer for [shape] ([lon, lat]), as 3.9.0 sends it. */
export function valhallaAnswer(shape, { distance = 5000, duration = 600 } = {}) {
  const first = shape[0];
  const last = shape[shape.length - 1];
  return {
    code: 'Ok',
    waypoints: [{ location: first, distance: 1 }, { location: last, distance: 1 }],
    routes: [{
      distance,
      duration,
      geometry: encodePolyline6(shape),
      legs: [{
        steps: [
          { maneuver: { type: 'depart', location: first }, distance, duration, name: 'Rue de Rivoli' },
          { maneuver: { type: 'arrive', location: last }, distance: 0, duration: 0, name: '' },
        ],
      }],
    }],
  };
}

/** One HTTP request to [server] (listening on 127.0.0.1): { status, json }. */
export function call(server, method, path, { headers = {}, body = null } = {}) {
  const { port } = server.address();
  const payload = body == null ? null : JSON.stringify(body);
  return new Promise((resolve, reject) => {
    const req = httpRequest({
      host: '127.0.0.1',
      port,
      method,
      path,
      headers: { ...(payload ? { 'content-type': 'application/json', 'content-length': Buffer.byteLength(payload) } : {}), ...headers },
    }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { text += chunk; });
      res.on('end', () => {
        let json = null;
        try {
          json = JSON.parse(text);
        } catch {
          json = null;
        }
        resolve({ status: res.statusCode, json });
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

/** Waits [ms]. */
export const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
