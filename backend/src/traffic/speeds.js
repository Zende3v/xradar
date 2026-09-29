import { config } from '../config.js';
import { angleDiff, gridOf, headingAt, project } from '../routing/geometry.js';

/**
 * The drivers' own speeds ("Aide au trafic partagé", 29/09): anonymous samples the apps send during
 * a trip — where, which way, how fast, under which limit — with a random key of the trip, never the
 * account, so one car counts once. In memory only, config.speedSampleKeepMs at most.
 *
 * On a route, the fresh ones (speedSampleFreshMs) lying on it the same way fill stretches of
 * speedSampleBinM metres: a stretch with speedSampleMinPerBin samples is covered, and slowed when
 * their median speed is under speedSlowRatio of their median limit (its delay against 90 % of the
 * limit). The more the drivers cover a route, the less HERE is asked (traffic/routes.js).
 */
class SpeedStore {
  constructor() {
    this.samples = [];
  }

  /**
   * [samples] from one trip ([tripKey]): { lat, lon, course, speedKmh, limitKmh?, t } — kept when
   * whole and recent. Answers how many were kept.
   */
  add(tripKey, samples, now = Date.now()) {
    let kept = 0;
    for (const s of samples.slice(0, config.speedSamplesPerPost)) {
      const [lat, lon, course, speedKmh, t] = [s?.lat, s?.lon, s?.course, s?.speedKmh, s?.t].map(Number);
      const limitKmh = Number(s?.limitKmh);
      if (![lat, lon, course, speedKmh, t].every(Number.isFinite)) continue;
      if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || speedKmh < 0 || speedKmh > 250) continue;
      if (t > now + 60_000 || now - t > config.speedSampleFreshMs) continue;
      this.samples.push({
        lat, lon, course: ((course % 360) + 360) % 360, speedKmh, t, tripKey,
        limitKmh: Number.isFinite(limitKmh) && limitKmh >= 10 && limitKmh <= 130 ? limitKmh : null,
      });
      kept += 1;
    }
    this.prune(now);
    return kept;
  }

  prune(now = Date.now()) {
    const oldest = now - config.speedSampleKeepMs;
    if (this.samples.length && this.samples[0].t < oldest) this.samples = this.samples.filter((s) => s.t >= oldest);
    if (this.samples.length > config.speedSampleMax) this.samples = this.samples.slice(-config.speedSampleMax);
  }

  /**
   * The drivers' speeds on [path] (routing/geometry.js) over its first [lengthM]: { coverage,
   * sections } — the share of those metres their fresh samples cover, and the slowed stretches
   * (`source: "crowd"`, `kind: "speed"`).
   */
  along(path, lengthM = path.total, now = Date.now()) {
    const binM = config.speedSampleBinM;
    const bins = Math.max(1, Math.ceil(Math.min(lengthM, path.total) / binM));
    if (path.points.length < 2 || !this.samples.length) return { coverage: 0, sections: [] };
    const grid = gridOf(path.points);
    const byBin = new Map();
    for (const s of this.samples) {
      if (now - s.t > config.speedSampleFreshMs) continue;
      const hit = project(path, grid, s.lat, s.lon, config.speedSampleOnRouteM);
      if (!hit || hit.along >= bins * binM) continue;
      if (angleDiff(s.course, headingAt(path, hit.along)) > config.crowdSameWayDeg) continue;
      const bin = Math.floor(hit.along / binM);
      if (!byBin.has(bin)) byBin.set(bin, []);
      byBin.get(bin).push(s);
    }
    const sections = [];
    let covered = 0;
    for (const [bin, samples] of byBin) {
      if (samples.length < config.speedSampleMinPerBin) continue;
      covered += 1;
      const limits = samples.map((s) => s.limitKmh).filter((v) => v != null);
      if (!limits.length) continue;
      const speed = median(samples.map((s) => s.speedKmh));
      const limit = median(limits);
      if (speed >= limit * config.speedSlowRatio) continue;
      const fromM = bin * binM;
      const toM = Math.min((bin + 1) * binM, path.total);
      const length = toM - fromM;
      const delayS = Math.round(Math.max(0, length / (Math.max(speed, 3) / 3.6) - length / ((limit * 0.9) / 3.6)));
      if (delayS <= 0) continue;
      const ratio = speed / limit;
      sections.push({
        fromM: Math.round(fromM), toM: Math.round(toM),
        level: ratio < 0.2 ? 'heavy' : ratio < 0.4 ? 'jam' : 'slow',
        delayS, speedKmh: Math.round(speed), source: 'crowd', kind: 'speed',
      });
    }
    return { coverage: covered / bins, sections: sections.sort((a, b) => a.fromM - b.fromM) };
  }

  get meta() {
    return { samples: this.samples.length, trips: new Set(this.samples.map((s) => s.tripKey)).size };
  }
}

function median(values) {
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

export const speedStore = new SpeedStore();
