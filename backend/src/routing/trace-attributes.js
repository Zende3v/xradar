import { haversine } from '../radars/geo.js';
import { decodePolyline6 } from './polyline.js';

// Limites Valhalla 3.9.0 : scripts/valhalla_build_config, service_limits.trace.
export const TRACE_MAX_SHAPE = 16000;
export const TRACE_MAX_DISTANCE_M = 200000;
export const TRACE_ATTRIBUTES = Object.freeze([
  'shape', 'edge.length', 'edge.speed', 'edge.speed_type', 'edge.road_class', 'edge.use',
  'edge.begin_shape_index', 'edge.end_shape_index', 'node.traffic_signal',
  'matched.type', 'matched.edge_index', 'matched.distance_along_edge',
  'matched.begin_route_discontinuity', 'matched.end_route_discontinuity',
]);

/** Tronçons consécutifs ; seul le point de raccord se répète. */
export function splitTrace(shape, { maxShape = TRACE_MAX_SHAPE, maxDistanceM = TRACE_MAX_DISTANCE_M } = {}) {
  if (!Array.isArray(shape) || shape.length < 2 || !Number.isInteger(maxShape) || maxShape < 2
    || !(maxDistanceM > 0)) throw new Error('trace_invalid');
  const chunks = [];
  let start = 0, distanceM = 0;
  for (let i = 0; i < shape.length; i++) {
    const p = shape[i];
    if (!Number.isFinite(p?.lat) || !Number.isFinite(p?.lon) || Math.abs(p.lat) > 90 || Math.abs(p.lon) > 180) {
      throw new Error('trace_invalid');
    }
    if (!i) continue;
    const previous = shape[i - 1];
    const segmentM = haversine(previous.lat, previous.lon, p.lat, p.lon);
    if (segmentM > maxDistanceM) throw new Error('trace_limit');
    if (i - start + 1 > maxShape || distanceM + segmentM > maxDistanceM) {
      chunks.push({ start, shape: shape.slice(start, i) });
      start = i - 1;
      distanceM = 0;
    }
    distanceM += segmentM;
  }
  chunks.push({ start, shape: shape.slice(start) });
  return chunks;
}

/** POST local partagé par la façade et le rapport. Délai global, corps JSON compris. */
export async function traceAttributes(shape, {
  baseUrl = 'http://127.0.0.1:8002', fetchImpl = globalThis.fetch, timeoutMs = 1500,
  shapeMatch = 'map_snap', costing = 'auto', maxShape, maxDistanceM, signal,
} = {}) {
  const controller = new AbortController();
  let timer, rejectStopped;
  const stopped = new Promise((_, reject) => { rejectStopped = reject; });
  const stop = (reason) => { controller.abort(); rejectStopped(new Error(reason)); };
  const onAbort = () => stop('match_timeout');
  signal?.addEventListener('abort', onAbort, { once: true });
  timer = setTimeout(() => stop('match_timeout'), timeoutMs);
  try {
    const work = async () => {
      const chunks = splitTrace(shape, { maxShape, maxDistanceM });
      const results = [];
      for (const chunk of chunks) {
        if (signal?.aborted || controller.signal.aborted) throw new Error('match_timeout');
        const response = await fetchImpl(baseUrl.replace(/\/+$/, '') + '/trace_attributes', {
          method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
          signal: controller.signal,
          body: JSON.stringify({
            costing, shape_match: shapeMatch, units: 'kilometers',
            ...(chunk.shape.every((p) => Number.isFinite(p.time)) ? { use_timestamps: true } : {}),
            shape: chunk.shape,
            filters: { action: 'include', attributes: TRACE_ATTRIBUTES },
          }),
        });
        if (!response.ok) throw new Error('match_http');
        const json = await response.json();
        results.push({ ...json, ...(json.shape ? { coordinates: decodePolyline6(json.shape) } : {}) });
      }
      if (results.length === 1) return results[0];
      return joinTraces(results);
    };
    return await Promise.race([work(), stopped]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onAbort);
  }
}

/** Indices locaux décalés vers la forme appariée complète. */
function joinTraces(results) {
  const edges = [], coordinates = [], matched = [];
  for (const part of results) {
    if (!Array.isArray(part.edges) || !Array.isArray(part.coordinates)) throw new Error('match_invalid');
    const last = coordinates.at(-1), first = part.coordinates[0];
    const overlap = last && first && last[0] === first[0] && last[1] === first[1] ? 1 : 0;
    if (coordinates.length && !overlap) throw new Error('match_invalid');
    const shapeOffset = coordinates.length - overlap;
    const edgeOffset = edges.length;
    edges.push(...part.edges.map((edge) => ({
      ...edge,
      begin_shape_index: edge.begin_shape_index + shapeOffset,
      end_shape_index: edge.end_shape_index + shapeOffset,
    })));
    coordinates.push(...part.coordinates.slice(overlap));
    if (Array.isArray(part.matched_points)) {
      matched.push(...part.matched_points.slice(matched.length ? 1 : 0).map((point) => ({
        ...point, ...(Number.isInteger(point.edge_index) ? { edge_index: point.edge_index + edgeOffset } : {}),
      })));
    }
  }
  return { edges, coordinates, ...(matched.length ? { matched_points: matched } : {}) };
}
