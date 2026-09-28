import { readFile, stat } from 'node:fs/promises';
import { config } from '../config.js';
import { downloadRadars, fetchLatestCsvResource } from './dataset.js';
import { radarVoteStore } from './votes.js';

/** The radar directions file is looked at this often, read again only when it changed. */
const DIRECTIONS_CHECK_MS = 60 * 60 * 1000;

/**
 * In-memory radar store. Loads the latest data.gouv dataset on start and refreshes
 * on an interval. Serves fast reads to the API.
 * Each radar carries the way it controls (28/09): `course` (degrees, the official site's
 * direction on its road, bin/eona-radar-directions.js) and `quietCourse` (a way drivers said it
 * does not control, votes.js); null when unknown: the radar rings both ways.
 */
class RadarStore {
  constructor() {
    this.radars = [];
    this.meta = {
      count: 0,
      source: null,
      datasetLastModified: null,
      refreshedAt: null,
      ready: false,
      withCourse: 0,
      quiet: 0,
    };
    this.timer = null;
    this.courses = new Map(); // radarId -> course
    this.directionsMtime = 0;
  }

  async refresh() {
    const { url, lastModified } = await fetchLatestCsvResource();
    const radars = await downloadRadars(url);
    this.radars = radars;
    this.meta = {
      ...this.meta,
      count: radars.length,
      source: url,
      datasetLastModified: lastModified,
      refreshedAt: new Date().toISOString(),
      ready: true,
    };
    this.decorate();
    console.log(`[radars] loaded ${radars.length} radars (dataset ${lastModified})`);
  }

  /** The directions file, read again when it changed; a missing or broken file changes nothing. */
  async loadDirections() {
    const info = await stat(config.radarDirectionsFile).catch(() => null);
    if (!info || info.mtimeMs === this.directionsMtime) return;
    try {
      const saved = JSON.parse(await readFile(config.radarDirectionsFile, 'utf8'));
      this.courses = new Map(Object.entries(saved.radars ?? {})
        .filter(([, entry]) => Number.isFinite(entry?.course))
        .map(([id, entry]) => [id, entry.course]));
      this.directionsMtime = info.mtimeMs;
      this.decorate();
    } catch (e) {
      console.warn('[radars] directions unreadable —', String(e.message || e));
    }
  }

  /** Each radar's course and quiet course, from what is known now. */
  decorate() {
    let withCourse = 0;
    let quiet = 0;
    for (const radar of this.radars) {
      radar.course = this.courses.get(radar.id) ?? null;
      radar.quietCourse = radarVoteStore.quiet.get(radar.id) ?? null;
      if (radar.course !== null) withCourse += 1;
      if (radar.quietCourse !== null) quiet += 1;
    }
    this.meta = { ...this.meta, withCourse, quiet };
  }

  /** The votes, once the crowd schema is there. */
  async loadVotes() {
    await radarVoteStore.load();
    this.decorate();
  }

  /** A radar's quiet course changed (a vote). */
  setQuiet(radarId, quietCourse) {
    const radar = this.radars.find((r) => r.id === radarId);
    if (radar) radar.quietCourse = quietCourse;
    this.meta = { ...this.meta, quiet: this.radars.filter((r) => r.quietCourse !== null).length };
  }

  start() {
    this.loadDirections()
      .then(() => this.refresh())
      .catch((e) => console.error('[radars] initial load failed:', e.message));
    this.timer = setInterval(() => {
      this.refresh().catch((e) => console.error('[radars] refresh failed:', e.message));
    }, config.refreshIntervalMs);
    if (this.timer.unref) this.timer.unref();
    const directions = setInterval(() => {
      this.loadDirections().catch((e) => console.warn('[radars] directions —', String(e.message || e)));
    }, DIRECTIONS_CHECK_MS);
    if (directions.unref) directions.unref();
  }

  all() {
    return this.radars;
  }

  has(radarId) {
    return this.radars.some((r) => r.id === radarId);
  }
}

export const radarStore = new RadarStore();
