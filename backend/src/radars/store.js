import { config } from '../config.js';
import { downloadRadars, fetchLatestCsvResource } from './dataset.js';

/**
 * In-memory radar store. Loads the latest data.gouv dataset on start and refreshes
 * on an interval. Serves fast reads to the API.
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
    };
    this.timer = null;
  }

  async refresh() {
    const { url, lastModified } = await fetchLatestCsvResource();
    const radars = await downloadRadars(url);
    this.radars = radars;
    this.meta = {
      count: radars.length,
      source: url,
      datasetLastModified: lastModified,
      refreshedAt: new Date().toISOString(),
      ready: true,
    };
    console.log(`[radars] loaded ${radars.length} radars (dataset ${lastModified})`);
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
}

export const radarStore = new RadarStore();
