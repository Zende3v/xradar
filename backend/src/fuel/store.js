import { config } from '../config.js';
import { haversine } from '../radars/geo.js';
import { downloadStations } from './feed.js';

/**
 * Official fuel prices (prix-carburants.gouv.fr), held in memory and refreshed on a timer.
 * It never lists stations itself: it only enriches the stations the nearby-services
 * search already found — by official id when OpenStreetMap carries it
 * (`ref:FR:prix-carburants`), otherwise by position, and only when that is unambiguous.
 */
class FuelStore {
  constructor() {
    this.byId = new Map();
    this.grid = new Map(); // "row_col" -> stations
    this.meta = { count: 0, withPrices: 0, refreshedAt: null, ready: false, lastError: null };
    this.timer = null;
  }

  /** Load now, then refresh; a first load that fails is retried soon, not a period later. */
  start() {
    const tick = async () => {
      let delay = config.fuelRefreshIntervalMs;
      try {
        await this.refresh();
      } catch (e) {
        this.meta = { ...this.meta, lastError: String(e.message || e) };
        console.error('[fuel] refresh failed:', this.meta.lastError);
        if (!this.meta.ready) delay = config.fuelRetryMs;
      }
      this.timer = setTimeout(tick, delay);
      if (this.timer.unref) this.timer.unref();
    };
    tick();
  }

  async refresh() {
    const stations = await downloadStations();
    const byId = new Map();
    const grid = new Map();
    for (const s of stations) {
      byId.set(s.id, s);
      const key = this.key(s.lat, s.lon);
      let cell = grid.get(key);
      if (!cell) grid.set(key, (cell = []));
      cell.push(s);
    }
    this.byId = byId;
    this.grid = grid;
    this.meta = {
      count: stations.length,
      withPrices: stations.filter((s) => s.prices.length > 0).length,
      refreshedAt: new Date().toISOString(),
      ready: true,
      lastError: null,
    };
    console.log(`[fuel] loaded ${this.meta.count} stations (${this.meta.withPrices} with prices)`);
  }

  key(lat, lon) {
    return `${Math.floor(lat / config.fuelGridDeg)}_${Math.floor(lon / config.fuelGridDeg)}`;
  }

  /** The official station behind a place from the nearby search, or null when unsure. */
  match(place) {
    if (place.refId) {
      const byRef = this.byId.get(String(place.refId));
      if (byRef && haversine(place.lat, place.lon, byRef.lat, byRef.lon) <= config.fuelRefMaxDistanceM) {
        return { station: byRef, by: 'id' };
      }
    }
    const row = Math.floor(place.lat / config.fuelGridDeg);
    const col = Math.floor(place.lon / config.fuelGridDeg);
    const near = [];
    for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        for (const s of this.grid.get(`${row + dr}_${col + dc}`) || []) {
          const d = haversine(place.lat, place.lon, s.lat, s.lon);
          if (d <= config.fuelMatchMaxDistanceM) near.push({ s, d });
        }
      }
    }
    if (near.length === 0) return null;
    near.sort((a, b) => a.d - b.d);
    // Two official stations about as close: naming one would be a guess.
    if (near.length > 1 && near[1].d - near[0].d < config.fuelMatchAmbiguityM) return null;
    return { station: near[0].s, by: 'position' };
  }

  /**
   * The same places, same order, each with a `fuel` field: the matched official station
   * and its prices, or null when there is no reliable match (or no data yet).
   */
  enrich(places) {
    const hits = places.map((place) => (this.meta.ready ? this.match(place) : null));
    // A station found by its id belongs to that place: a position match may not borrow it.
    // And one official station never lends its prices to two places by position — at a
    // motorway area, three pumps side by side are three different stations.
    const claimedById = new Set(hits.filter((h) => h?.by === 'id').map((h) => h.station.id));
    const positionUses = new Map();
    for (const h of hits) {
      if (h?.by === 'position') positionUses.set(h.station.id, (positionUses.get(h.station.id) || 0) + 1);
    }
    return places.map(({ refId, ...place }, i) => {
      let hit = hits[i];
      if (hit?.by === 'position' && (claimedById.has(hit.station.id) || positionUses.get(hit.station.id) > 1)) {
        hit = null;
      }
      return {
        ...place,
        fuel: hit ? { stationId: hit.station.id, matchedBy: hit.by, prices: hit.station.prices } : null,
      };
    });
  }
}

export const fuelStore = new FuelStore();
