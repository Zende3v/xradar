import { createHash } from 'node:crypto';
import { config } from '../../config.js';
import { stateFile } from '../../state-file.js';

/** The app's avoid options → ORS avoid_features. */
export const ORS_AVOID = { tolls: 'tollways', highways: 'highways', ferries: 'ferries' };

/**
 * The ORS keys and what is left of each one today. ORS gives a free plan a fixed number of
 * routes a day, counted on UTC days: each key keeps to config.orsDailyBudget, under that quota,
 * so a burst can never leave the app without routing and the rerouting around traffic keeps its
 * share. A key ORS refuses (quota spent, too many calls at once) is set aside and the next key
 * takes over until it is worth trying again — the spare only serves while the first is blocked.
 */
const keys = config.orsApiKeys.map((key) => ({ key, id: fingerprint(key), day: '', used: 0, blockedUntil: 0, warned: false }));

/** A key's name on disk: a short hash, never the key itself. */
function fingerprint(key) {
  return createHash('sha256').update(key).digest('hex').slice(0, 12);
}

/**
 * What each key spent today and until when it is set aside, on disk (config.orsUsageFile): a
 * restart hands neither the day's calls nor a key ORS refused back to the budget.
 */
const usage = stateFile(config.orsUsageFile, 'route', () => ({
  keys: keys.map(({ id, day, used, blockedUntil }) => ({ id, day, used, blockedUntil })),
}));
let restored = false;

/** The saved counters, taken back once before anything is counted: today's (UTC) only. */
function restore() {
  if (restored) return;
  restored = true;
  const saved = usage.read();
  const list = Array.isArray(saved?.keys) ? saved.keys : [];
  const day = utcDay();
  for (const k of keys) {
    const entry = list.find((e) => e?.id === k.id);
    if (!entry || entry.day !== day) continue;
    k.day = day;
    k.used = Math.max(0, Math.round(Number(entry.used) || 0));
    k.blockedUntil = Number(entry.blockedUntil) > Date.now() ? Number(entry.blockedUntil) : 0;
  }
}

/** A request that was never sent: postORS answers with it when no key can serve. */
const SPENT = {
  ok: false,
  status: 429,
  budgetSpent: true,
  text: async () => 'ORS keys exhausted',
  json: async () => ({ error: 'ORS keys exhausted' }),
};

const utcDay = () => new Date().toISOString().slice(0, 10);

/** The key to use now: the first one with budget left and not set aside. Null when none can. */
function pick() {
  restore();
  const day = utcDay();
  const now = Date.now();
  for (const k of keys) {
    if (k.day !== day) {
      // Midnight UTC: ORS counts again, and a key set aside for its quota may serve once more.
      k.day = day;
      k.used = 0;
      k.warned = false;
      k.blockedUntil = 0;
    }
    if (k.blockedUntil > now) continue;
    if (k.used >= config.orsDailyBudget) {
      if (!k.warned) {
        k.warned = true;
        console.warn(`[route] budget du jour atteint sur la clé ORS n°${keys.indexOf(k) + 1} (${config.orsDailyBudget})`);
      }
      continue;
    }
    return k;
  }
  return null;
}

/** How long to set a key aside after ORS refused it (ms); 0 when the failure is not the key's. */
function blockMs(status, detail) {
  if (status === 429) return config.orsKeyPauseMs; // too many calls at once: a short pause
  if (status !== 403) return 0;
  if (/quota/i.test(detail)) {
    // Spent for the day: nothing to retry before ORS counts again, at midnight UTC.
    const d = new Date();
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1) - Date.now();
  }
  return config.orsKeyBlockMs; // refused for another reason (key disabled, wrong plan)
}

/**
 * One call with one key. A refusal comes back readable, its body already in hand. Past
 * config.orsTimeoutMs (answer and body together), or once the caller's [signal] fires (the
 * routing façade's deadline, engine.js), the call is dropped and this throws.
 */
async function send(key, body, signal) {
  const r = await fetch(`${config.orsUrl.replace(/\/$/, '')}/v2/directions/driving-car/geojson`, {
    method: 'POST',
    headers: { Authorization: key, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal: withTimeout(signal, config.orsTimeoutMs),
  });
  if (r.ok) return r;
  const detail = await r.text().catch(() => '');
  return {
    ok: false,
    status: r.status,
    detail,
    text: async () => detail,
    json: async () => JSON.parse(detail),
  };
}

/** How many keys there are, how many can serve now, and the routes left today, for /health. */
export function orsKeysMeta() {
  restore();
  const day = utcDay();
  const now = Date.now();
  const left = keys.map((k) => (k.day === day ? Math.max(0, config.orsDailyBudget - k.used) : config.orsDailyBudget));
  const usable = keys.map((k, i) => (k.blockedUntil <= now ? left[i] : 0));
  return {
    keys: keys.length,
    ready: usable.filter((n) => n > 0).length,
    // What can really be asked now: a key set aside counts for nothing until it comes back.
    budgetLeft: usable.reduce((a, b) => a + b, 0),
  };
}

/**
 * What each key spent today (UTC), in the keys' order, and until when ORS set it aside (ISO,
 * null when it serves), for /health. Survives a restart.
 */
export function orsUsage() {
  restore();
  const day = utcDay();
  const now = Date.now();
  return {
    day,
    keys: keys.map((k) => ({
      used: k.day === day ? k.used : 0,
      blockedUntil: k.blockedUntil > now ? new Date(k.blockedUntil).toISOString() : null,
    })),
  };
}

/** The day's routes still available across every key, for /health. */
export function orsBudgetLeft() {
  return orsKeysMeta().budgetLeft;
}

/**
 * One ORS directions request, the next key taking over while ORS refuses one. [signal]
 * (optional): the caller's deadline, for every key tried; once it fired, no other key is tried.
 */
export async function postORS(body, { signal = null } = {}) {
  let refusal = SPENT;
  for (;;) {
    if (signal?.aborted) throw signal.reason ?? new Error('ORS: request aborted');
    const state = pick();
    if (!state) return refusal;
    state.used += 1;
    usage.touch();
    const r = await send(state.key, body, signal);
    if (r.ok) return r;
    const pause = blockMs(r.status, r.detail || '');
    if (!pause) return r; // the call itself went wrong: another key would fail the same way
    state.blockedUntil = Date.now() + pause;
    usage.touch();
    console.warn(
      `[route] clé ORS n°${keys.indexOf(state) + 1} écartée ${Math.round(pause / 60000)} min (HTTP ${r.status}) — passage à la suivante`,
    );
    refusal = r;
  }
}

/** config.orsTimeoutMs, or sooner when the caller's [signal] fires. */
function withTimeout(signal, ms) {
  const timeout = AbortSignal.timeout(ms);
  if (!signal) return timeout;
  if (typeof AbortSignal.any === 'function') return AbortSignal.any([signal, timeout]);
  const controller = new AbortController();
  const stop = (s) => () => controller.abort(s.reason);
  for (const s of [signal, timeout]) {
    if (s.aborted) {
      controller.abort(s.reason);
      break;
    }
    s.addEventListener('abort', stop(s), { once: true });
  }
  return controller.signal;
}

/**
 * The ORS directions body for [from] → [to] ({ lat, lon }): steps, full shape, the app's avoid
 * options ([avoid]: tolls, highways, ferries; anything else is dropped), areas to keep off
 * ([polygons]: a GeoJSON MultiPolygon), a heading per point ([bearings]: [[heading, tolerance],
 * …]), ORS's own alternatives ([alternatives]) and [preference] (`shortest` : route Éco).
 */
export function orsBody({ from, to, avoid = [], polygons = null, bearings = null, alternatives = false, preference = null }) {
  const options = {};
  const features = avoid.map((a) => ORS_AVOID[a]).filter(Boolean);
  if (features.length) options.avoid_features = features;
  if (polygons) options.avoid_polygons = polygons;
  return {
    coordinates: [[from.lon, from.lat], [to.lon, to.lat]],
    // Éco : ORS au plus court en distance, même choix que Valhalla.
    ...(preference === 'shortest' ? { preference: 'shortest' } : {}),
    ...(bearings ? { bearings } : {}),
    instructions: true,
    maneuvers: true,
    geometry_simplify: false,
    ...(Object.keys(options).length ? { options } : {}),
    ...(alternatives ? { alternative_routes: { target_count: 3, weight_factor: 1.6, share_factor: 0.6 } } : {}),
  };
}

/** A counter-clockwise square around a point, as one GeoJSON polygon ([lon, lat]) for avoid_polygons. */
export function square(lat, lon, halfM) {
  const dLat = halfM / 111320;
  const dLon = halfM / (111320 * Math.max(Math.cos(lat * Math.PI / 180), 0.1));
  return [[
    [lon - dLon, lat - dLat],
    [lon + dLon, lat - dLat],
    [lon + dLon, lat + dLat],
    [lon - dLon, lat + dLat],
    [lon - dLon, lat - dLat],
  ]];
}

/** The date of the map ORS routed on (`metadata.engine.graph_date` of its answer), or null. */
export function orsMapVersion(json) {
  const date = json?.metadata?.engine?.graph_date;
  return typeof date === 'string' && date ? date.slice(0, 40) : null;
}

/**
 * One ORS feature as the app's route: [lon, lat] coordinates and OSRM-style steps, with the
 * engine that drew it and its map ([mapVersion]: orsMapVersion of the whole answer).
 */
export function normalizeOrsFeature(feature, mapVersion = null) {
  const summary = feature.properties.summary || {};
  const coordinates = feature.geometry.coordinates; // [[lon, lat], ...]
  return {
    distanceM: Math.round(summary.distance ?? 0),
    durationS: Math.round(summary.duration ?? 0),
    coordinates,
    steps: normalizeOrsSteps(feature.properties.segments || [], coordinates),
    engine: 'ors',
    mapVersion,
  };
}

/** ORS instruction type codes → OSRM-style {type, modifier} the app already parses. */
const ORS_TYPE = {
  0: ['turn', 'left'], 1: ['turn', 'right'], 2: ['turn', 'sharp left'], 3: ['turn', 'sharp right'],
  4: ['turn', 'slight left'], 5: ['turn', 'slight right'], 6: ['continue', 'straight'],
  7: ['roundabout', null], 8: ['continue', 'straight'], 9: ['turn', 'uturn'], 10: ['arrive', null],
  11: ['depart', null], 12: ['fork', 'left'], 13: ['fork', 'right'],
};

function normalizeOrsSteps(segments, coordinates) {
  const out = [];
  for (const seg of segments) {
    for (const s of seg.steps || []) {
      const [type, modifier] = ORS_TYPE[s.type] || ['continue', 'straight'];
      const at = Array.isArray(s.way_points) ? coordinates[s.way_points[0]] : null;
      out.push({
        type,
        modifier,
        location: s.maneuver?.location ?? at ?? null, // [lon, lat]
        exit: s.exit_number ?? null,
        name: s.name && s.name !== '-' ? s.name : '',
        // The motorway signs (exit number, where the branch leads): Valhalla only.
        exitNumber: null,
        towardRefs: [],
        toward: [],
        distanceM: Math.round(s.distance ?? 0),
        durationS: Math.round(s.duration ?? 0),
      });
    }
  }
  return out;
}
