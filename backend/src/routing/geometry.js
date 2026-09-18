import { bearingDeg, haversine } from '../radars/geo.js';

// Geometry on routes given as [lat, lon] points: distances along them, cuts, samples, and a grid
// to find the road near a point without scanning the whole route.

// A point this close to a route lies on that road.
export const SAME_ROAD_M = 40;
// Grid cell (degrees, ~150-220 m): a point's road is looked for in its cell and the 8 around.
const CELL_DEG = 0.002;

/** [points] with their cumulative metres. */
export function measure(points) {
  const cum = [0];
  for (let i = 1; i < points.length; i++) {
    cum.push(cum[i - 1] + haversine(points[i - 1][0], points[i - 1][1], points[i][0], points[i][1]));
  }
  return { points, cum, total: cum[cum.length - 1] };
}

/** The point [d] metres along [path]. */
export function pointAt(path, d) {
  const { points, cum } = path;
  if (points.length === 1) return points[0];
  let lo = 0;
  let hi = points.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] <= d) lo = mid;
    else hi = mid;
  }
  const length = cum[hi] - cum[lo];
  const t = length > 0 ? Math.min(Math.max((d - cum[lo]) / length, 0), 1) : 0;
  return [points[lo][0] + (points[hi][0] - points[lo][0]) * t, points[lo][1] + (points[hi][1] - points[lo][1]) * t];
}

/** Points every [step] metres from [fromM] to [toM] (clamped to the path), both ends included. */
export function samplesBetween(path, fromM, toM, step = 200) {
  const end = Math.min(toM, path.total);
  const out = [];
  for (let d = Math.max(fromM, 0); d < end; d += step) out.push(pointAt(path, d));
  out.push(pointAt(path, end));
  return out;
}

/** The route's points from [fromM] to [toM], the cut ends interpolated. */
export function sliceOf(path, fromM, toM) {
  const out = [pointAt(path, fromM)];
  for (let i = 0; i < path.points.length; i++) {
    if (path.cum[i] > fromM && path.cum[i] < toM) out.push(path.points[i]);
  }
  out.push(pointAt(path, toM));
  return out;
}

/** The route's heading at [d] metres along: its next [ahead] metres (its last ones at the end). */
export function headingAt(path, d, ahead = 30) {
  const from = Math.min(d, Math.max(path.total - ahead, 0));
  const a = pointAt(path, from);
  const b = pointAt(path, Math.min(from + ahead, path.total));
  return Math.round(bearingDeg(a[0], a[1], b[0], b[1]));
}

/** Smallest angle between two courses, 0...180°. */
export function angleDiff(a, b) {
  const d = Math.abs((((a - b) % 360) + 360) % 360);
  return d > 180 ? 360 - d : d;
}

/** [line]'s segments by grid cell. */
export function gridOf(line) {
  const cells = new Map();
  for (let i = 0; i < line.length - 1; i++) {
    const [a, b] = [line[i], line[i + 1]];
    const x0 = Math.floor(Math.min(a[1], b[1]) / CELL_DEG);
    const x1 = Math.floor(Math.max(a[1], b[1]) / CELL_DEG);
    const y0 = Math.floor(Math.min(a[0], b[0]) / CELL_DEG);
    const y1 = Math.floor(Math.max(a[0], b[0]) / CELL_DEG);
    for (let x = x0; x <= x1; x++) {
      for (let y = y0; y <= y1; y++) {
        const key = `${x},${y}`;
        const list = cells.get(key);
        if (list) list.push(i);
        else cells.set(key, [i]);
      }
    }
  }
  return { line, cells };
}

/** Share of [samples] lying on the road of [grid]. */
export function share(samples, grid) {
  if (!samples.length) return 0;
  let on = 0;
  for (const [lat, lon] of samples) if (nearest(grid, lat, lon, SAME_ROAD_M)) on++;
  return on / samples.length;
}

/**
 * Where (lat, lon) falls on the route of [path] (its grid: [grid]), within [maxM]: the metres
 * along it and the distance to it; null when farther.
 */
export function project(path, grid, lat, lon, maxM) {
  const hit = nearest(grid, lat, lon, maxM);
  if (!hit) return null;
  const i = hit.segment;
  return { along: path.cum[i] + hit.t * (path.cum[i + 1] - path.cum[i]), distM: hit.distM };
}

/** The nearest segment of [grid]'s line within [maxM] of (lat, lon): { segment, t, distM }. */
function nearest(grid, lat, lon, maxM) {
  const cx = Math.floor(lon / CELL_DEG);
  const cy = Math.floor(lat / CELL_DEG);
  const mLat = 111_320;
  const mLon = 111_320 * Math.cos((lat * Math.PI) / 180);
  let best = null;
  for (let x = cx - 1; x <= cx + 1; x++) {
    for (let y = cy - 1; y <= cy + 1; y++) {
      for (const i of grid.cells.get(`${x},${y}`) ?? []) {
        const [a, b] = [grid.line[i], grid.line[i + 1]];
        const ax = (a[1] - lon) * mLon;
        const ay = (a[0] - lat) * mLat;
        const dx = (b[1] - a[1]) * mLon;
        const dy = (b[0] - a[0]) * mLat;
        const length2 = dx * dx + dy * dy;
        const t = length2 === 0 ? 0 : Math.min(Math.max(-(ax * dx + ay * dy) / length2, 0), 1);
        const distM = Math.hypot(ax + t * dx, ay + t * dy);
        if (distM <= maxM && (!best || distM < best.distM)) best = { segment: i, t, distM };
      }
    }
  }
  return best;
}
