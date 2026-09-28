import { config } from '../config.js';
import { downloadRadars, fetchLatestCsvResource } from './dataset.js';
import { radarVoteStore } from './votes.js';

/**
 * In-memory radar store. Loads the latest data.gouv dataset on start and refreshes
 * on an interval. Serves fast reads to the API.
 * Each radar carries `quietCourse` (28/09): a way drivers said it does not control ("Pas dans
 * mon sens", votes.js); null while unknown: the radar rings both ways.
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
      quiet: 0,
    };
    this.timer = null;
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

  /** Each radar's quiet course, from the votes known now. */
  decorate() {
    let quiet = 0;
    for (const radar of this.radars) {
      radar.quietCourse = radarVoteStore.quiet.get(radar.id) ?? null;
      if (radar.quietCourse !== null) quiet += 1;
    }
    this.meta = { ...this.meta, quiet };
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
    this.refresh().catch((e) => console.error('[radars] initial load failed:', e.message));
    this.timer = setInterval(() => {
      this.refresh().catch((e) => console.error('[radars] refresh failed:', e.message));
    }, config.refreshIntervalMs);
    if (this.timer.unref) this.timer.unref();
  }

  all() {
    return this.radars;
  }

  has(radarId) {
    return this.radars.some((r) => r.id === radarId);
  }
}

export const radarStore = new RadarStore();
