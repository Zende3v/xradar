import { isPeak } from '../../bin/eona-eta-report.js';
import { haversine } from '../radars/geo.js';

export { isPeak };
// Valeurs de départ 18/10, calibrées au lot 9.
export const TIME_FACTORS = Object.freeze({
  autoroute: Object.freeze({ autre: 1.15, pointe: 1.40 }),
  'grands axes': Object.freeze({ autre: 1.25, pointe: 1.45 }),
  rues: Object.freeze({ autre: 1.20, pointe: 1.35 }),
});
export const SIGNAL_EXTRA_S = 12;
// Pointe autoroute seulement en zone urbaine : 90 km/h ou moins (A86, périphérique). A6 à 130 : hors pointe (18/10).
export const URBAN_MOTORWAY_KMH = 90;
export const EONA_TIME_TIMEOUT_MS = 1500;
const positive = (v) => Number.isFinite(v) && v > 0;

export function roadGroup(roadClass) {
  if (['motorway', 'trunk'].includes(roadClass)) return 'autoroute';
  if (['primary', 'secondary'].includes(roadClass)) return 'grands axes';
  return 'rues';
}

export const periodAt = (at) => isPeak(at) ? 'pointe' : 'autre';
export const timeFactor = (roadClass, at) => TIME_FACTORS[roadGroup(roadClass)][periodAt(at)];

/** Le feu est au nœud final : Valhalla 3.9.0, tyr/trace_serializer.cc, node.traffic_signal. */
export function edgeTime(edge, at, { km = edge?.length, signals = null, period = periodAt(at) } = {}) {
  if (!positive(km) || !positive(edge?.speed)) return { engineS: null, extraS: 0, signals: 0, skipped: true };
  const count = signals ?? Number(edge.end_node?.traffic_signal === true && (edge.target_percent_along ?? 1) === 1);
  const engineS = km / edge.speed * 3600;
  const group = roadGroup(edge.road_class);
  const used = group === 'autoroute' && edge.speed > URBAN_MOTORWAY_KMH ? 'autre' : period;
  return { engineS, extraS: engineS * (TIME_FACTORS[group][used] - 1) + count * SIGNAL_EXTRA_S,
    signals: count, skipped: false };
}

export function fallbackFactor(route, at) {
  const kmh = positive(route.durationS) ? route.distanceM / route.durationS * 3.6 : 0;
  const group = kmh >= 70 ? 'autoroute' : kmh >= 40 ? 'grands axes' : 'rues';
  return TIME_FACTORS[group][periodAt(at)];
}

/** Indices des étapes sur la forme appariée, dans l'ordre du trajet. */
export function stepShapeRanges(steps, coordinates) {
  const positions = new Map(), distances = [0];
  coordinates.forEach((p, i) => {
    const key = p.join(',');
    if (!positions.has(key)) positions.set(key, []);
    positions.get(key).push(i);
    if (i) distances.push(distances.at(-1) + haversine(coordinates[i - 1][1], coordinates[i - 1][0], p[1], p[0]));
  });
  const total = steps.reduce((sum, step) => sum + (step.distanceM ?? 0), 0);
  let cursor = 0, beforeM = 0;
  const starts = steps.map((step, index) => {
    const expectedM = total > 0 ? beforeM / total * distances.at(-1) : 0;
    beforeM += step.distanceM ?? 0;
    if (!index) return 0;
    if (step.type === 'arrive' && index === steps.length - 1) return coordinates.length - 1;
    const exact = (positions.get(step.location.join(',')) ?? []).filter((i) => i >= cursor);
    if (exact.length) {
      cursor = exact.reduce((best, i) => Math.abs(distances[i] - expectedM) < Math.abs(distances[best] - expectedM) ? i : best);
      return cursor;
    }
    let best = cursor, distance = Infinity;
    const scale = Math.cos(step.location[1] * Math.PI / 180);
    for (let i = Math.floor(cursor); i < coordinates.length - 1; i++) {
      const a = coordinates[i], b = coordinates[i + 1];
      const x = (a[0] - step.location[0]) * scale, y = a[1] - step.location[1];
      const dx = (b[0] - a[0]) * scale, dy = b[1] - a[1];
      const length2 = dx * dx + dy * dy;
      const fraction = length2 > 0 ? Math.max(0, Math.min(1, -(x * dx + y * dy) / length2)) : 0;
      const at = i + fraction;
      if (at < cursor) continue;
      const d = (x + fraction * dx) ** 2 + (y + fraction * dy) ** 2;
      if (d < distance) { distance = d; best = at; }
    }
    cursor = best;
    return best;
  });
  return starts.map((from, i) => [from, starts[i + 1] ?? coordinates.length - 1]);
}

/** Somme entière exacte ; déficit exceptionnel repris sans durée négative. */
export function coherentSteps(steps, values, durationS) {
  if (!steps.length) return [];
  const rounded = values.map((v) => Math.max(0, Math.round(v)));
  let delta = durationS - rounded.reduce((sum, v) => sum + v, 0);
  for (let i = rounded.length - 1; i >= 0 && delta; i--) {
    const change = Math.max(-rounded[i], delta);
    rounded[i] += change;
    delta -= change;
  }
  return steps.map((step, i) => ({ ...step, durationS: rounded[i] }));
}

/** Étapes du fournisseur, durée brute gardée à côté avant correction (19/10, additif). */
function withRawSteps(steps) {
  return steps.map((step) => ({ ...step, engineDurationS: step.durationS }));
}

/** Temps brut conservé ; aucun état du fournisseur modifié. */
export function applyEdgeTime(route, matched, at) {
  if (!Array.isArray(matched?.edges) || !matched.edges.length) throw new Error('match_invalid');
  const coordinates = matched.coordinates;
  if (!Array.isArray(coordinates) || coordinates.length < 2) throw new Error('match_invalid');
  const distances = [0];
  for (let i = 1; i < coordinates.length; i++) {
    distances.push(distances.at(-1) + haversine(coordinates[i - 1][1], coordinates[i - 1][0], coordinates[i][1], coordinates[i][0]));
  }
  const steps = route.steps ?? [];
  const ranges = stepShapeRanges(steps, coordinates);
  const additions = steps.map(() => 0);
  const distanceAt = (index) => {
    const low = Math.floor(index), high = Math.ceil(index);
    return distances[low] + (distances[high] - distances[low]) * (index - low);
  };
  // Période à l'heure de passage estimée : une pointe au départ ne ralentit pas tout un long trajet (18/10).
  let extraS = 0, signals = 0, skipped = 0, stepIndex = 0, elapsedS = 0;
  for (const edge of matched.edges) {
    const time = edgeTime(edge, at, { period: periodAt(at + elapsedS * 1000) });
    if (time.skipped) { skipped++; continue; }
    elapsedS += time.engineS + time.extraS;
    const from = edge.begin_shape_index, to = edge.end_shape_index;
    if (!Number.isInteger(from) || !Number.isInteger(to) || from < 0 || to <= from || to >= coordinates.length) {
      throw new Error('match_invalid');
    }
    const length = distances[to] - distances[from];
    if (!(length > 0)) throw new Error('match_invalid');
    extraS += time.extraS;
    signals += time.signals;
    while (stepIndex < steps.length && ranges[stepIndex][1] <= from) stepIndex++;
    for (let i = stepIndex; i < steps.length && ranges[i][0] < to; i++) {
      const [a, b] = ranges[i];
      const overlap = Math.min(distances[to], distanceAt(b)) - Math.max(distances[from], distanceAt(a));
      if (overlap > 0) additions[i] += time.extraS * overlap / length;
    }
  }
  if (skipped === matched.edges.length) {
    const error = new Error('match_unusable');
    error.skipped = skipped;
    throw error;
  }
  const durationS = Math.max(route.durationS, Math.round(route.durationS + extraS));
  return {
    ...route, durationS, engineDurationS: route.durationS,
    steps: coherentSteps(withRawSteps(steps), steps.map((step, i) => step.durationS + additions[i]), durationS),
    eonaTime: { version: 1, mode: 'edges', signals, ...(skipped ? { skipped } : {}) },
  };
}

export function applyFallbackTime(route, at) {
  const factor = fallbackFactor(route, at);
  const durationS = Math.max(route.durationS, Math.round(route.durationS * factor));
  const steps = route.steps ?? [];
  return {
    ...route, durationS, engineDurationS: route.durationS,
    steps: coherentSteps(withRawSteps(steps), steps.map((step) => step.durationS * factor), durationS),
    eonaTime: { version: 1, mode: 'fallback', signals: 0 },
  };
}

/** Échec du modèle : repli prudent. Le délai ne dépend pas du respect du signal par le client. */
export function createEonaTime({ trace = null, available = () => true } = {}) {
  const counts = { edges: 0, fallback: 0, failed: 0, lastMs: null };
  async function apply(route, { at = Date.now(), moped = false, vehicle = 'car', timeoutMs = EONA_TIME_TIMEOUT_MS } = {}) {
    const startedAt = performance.now();
    let timer, result, skipped = 0;
    if (trace && available()) {
      const controller = new AbortController();
      const late = new Promise((_, reject) => {
        timer = setTimeout(() => { controller.abort(); reject(new Error('match_timeout')); }, timeoutMs);
      });
      try {
        const work = async () => {
          const matched = await trace(route.coordinates.map(([lon, lat]) => ({ lat, lon })), {
            shapeMatch: route.engine === 'valhalla' ? 'edge_walk' : 'map_snap',
            costing: moped ? 'motor_scooter' : vehicle === 'taxi' ? 'taxi' : 'auto',
            timeoutMs, signal: controller.signal,
          });
          const adjusted = applyEdgeTime(route, matched, at);
          if (performance.now() - startedAt > timeoutMs) throw new Error('match_timeout');
          return adjusted;
        };
        result = await Promise.race([work(), late]);
      } catch (error) {
        skipped = error.skipped ?? 0;
        counts.failed++;
      } finally {
        clearTimeout(timer);
      }
    }
    result ??= applyFallbackTime(route, at);
    if (skipped) result.eonaTime.skipped = skipped;
    counts[result.eonaTime.mode]++;
    counts.lastMs = Math.round(performance.now() - startedAt);
    return result;
  }
  return { apply, health: () => ({ ...counts }) };
}
