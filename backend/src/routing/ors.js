import { config } from '../config.js';

/** The app's avoid options → ORS avoid_features. */
export const ORS_AVOID = { tolls: 'tollways', highways: 'highways', ferries: 'ferries' };

/**
 * The ORS keys and what is left of each one today. ORS gives a free plan a fixed number of
 * routes a day, counted on UTC days: each key keeps to config.orsDailyBudget, under that quota,
 * so a burst can never leave the app without routing and the rerouting around traffic keeps its
 * share. A key ORS refuses (quota spent, too many calls at once) is set aside and the next key
 * takes over until it is worth trying again — the spare only serves while the first is blocked.
 */
const keys = config.orsApiKeys.map((key) => ({ key, day: '', used: 0, blockedUntil: 0, warned: false }));

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

/** One call with one key. A refusal comes back readable, its body already in hand. */
async function send(key, body) {
  const r = await fetch(`${config.orsUrl.replace(/\/$/, '')}/v2/directions/driving-car/geojson`, {
    method: 'POST',
    headers: { Authorization: key, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
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

/** The day's routes still available across every key, for /health. */
export function orsBudgetLeft() {
  return orsKeysMeta().budgetLeft;
}

export async function postORS(body) {
  let refusal = SPENT;
  for (;;) {
    const state = pick();
    if (!state) return refusal;
    state.used += 1;
    const r = await send(state.key, body);
    if (r.ok) return r;
    const pause = blockMs(r.status, r.detail || '');
    if (!pause) return r; // the call itself went wrong: another key would fail the same way
    state.blockedUntil = Date.now() + pause;
    console.warn(
      `[route] clé ORS n°${keys.indexOf(state) + 1} écartée ${Math.round(pause / 60000)} min (HTTP ${r.status}) — passage à la suivante`,
    );
    refusal = r;
  }
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

/** One ORS feature as the app's route: [lon, lat] coordinates and OSRM-style steps. */
export function normalizeOrsFeature(feature) {
  const summary = feature.properties.summary || {};
  const coordinates = feature.geometry.coordinates; // [[lon, lat], ...]
  return {
    distanceM: Math.round(summary.distance ?? 0),
    durationS: Math.round(summary.duration ?? 0),
    coordinates,
    steps: normalizeOrsSteps(feature.properties.segments || [], coordinates),
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
        distanceM: Math.round(s.distance ?? 0),
        durationS: Math.round(s.duration ?? 0),
      });
    }
  }
  return out;
}
