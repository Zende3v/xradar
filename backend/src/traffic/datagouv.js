import { config } from '../config.js';
import { db } from '../db.js';
import { angleDiff, gridOf, headingAt, project } from '../routing/geometry.js';

/**
 * data.gouv traffic (D3.2, Licence Ouverte 2.0): the DIR's open feeds on the non-conceded national
 * network, published by Bison Futé.
 * - Speeds (QTV, DATEX II, every 6 min) at counting stations placed from their Lambert-93 ends
 *   (refDir.csv, by PostGIS). A station slower than its road allows is a slowdown, with the time
 *   it costs over its length: the only data.gouv delays.
 * - Events (DATEX II situations): closures, works, incidents and queues, placed by their TPEG
 *   points. They say where, not how long: a closure is `closed`, the rest `slow` or `jam`, no delay.
 * All in memory, each fetch replacing the last; a feed that fails keeps what it had until it is
 * too old (datagouvSpeedsMaxAgeMs, the events' own end). Fetched with their ETag: the events'
 * file, rebuilt hourly by Bison Futé, is not downloaded again every 6 min. Served behind the switch
 * trafficDatagouv (D2.6): its stretches carry `source: "datagouv"`.
 */

const SOURCE = 'datagouv';

// ---- Stations (refDir.csv) ------------------------------------------------------------------

/** The counting stations with both ends: { id, axe, lengthM, x1, y1, x2, y2 } (Lambert-93). */
export function parseStations(csv) {
  const lines = String(csv).split(/\r?\n/).filter((line) => line.trim());
  if (lines.length < 2) return [];
  const header = lines[0].split(';').map((h) => h.trim().toLowerCase());
  const at = (name) => header.indexOf(name);
  const [id, axe, length, x1, y1, x2, y2] = ['code_pme', 'axe', 'longueur', 'x_deb', 'y_deb', 'x_fin', 'y_fin'].map(at);
  // The rows leave out `source_2` (seen 29/09): one cell short, every later column one to the left.
  const missing = at('source_2');
  const stations = [];
  for (const line of lines.slice(1)) {
    const cells = line.split(';');
    const short = cells.length === header.length - 1 && missing >= 0;
    const cell = (i) => String(cells[short && i > missing ? i - 1 : i] ?? '').trim();
    const n = (i) => Number(cell(i).replace(',', '.'));
    const station = { id: cell(id), axe: cell(axe), lengthM: n(length), x1: n(x1), y1: n(y1), x2: n(x2), y2: n(y2) };
    if (!station.id || ![station.x1, station.y1, station.x2, station.y2].every((v) => Number.isFinite(v) && v > 0)) continue;
    if (!(station.lengthM > 0)) continue;
    stations.push(station);
  }
  return stations;
}

/** The stations in WGS 84, with the limit of the road at their start (signs.road, OSM). */
async function placeStations(stations) {
  if (stations.length === 0) return [];
  const { rows } = await db.query(
    `WITH s(id, x1, y1, x2, y2) AS (SELECT * FROM unnest($1::text[], $2::float8[], $3::float8[], $4::float8[], $5::float8[]))
     SELECT s.id,
       ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(s.x1, s.y1), 2154), 4326)) AS lat1,
       ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(s.x1, s.y1), 2154), 4326)) AS lon1,
       ST_Y(ST_Transform(ST_SetSRID(ST_MakePoint(s.x2, s.y2), 2154), 4326)) AS lat2,
       ST_X(ST_Transform(ST_SetSRID(ST_MakePoint(s.x2, s.y2), 2154), 4326)) AS lon2,
       r.limit_kmh, r.highway
     FROM s LEFT JOIN LATERAL (
       SELECT GREATEST(road.maxspeed_fwd, road.maxspeed_bwd) AS limit_kmh, road.highway
       FROM signs.road road
       WHERE ST_DWithin(road.geom_m, ST_SetSRID(ST_MakePoint(s.x1, s.y1), 2154), $6)
       ORDER BY road.geom_m <-> ST_SetSRID(ST_MakePoint(s.x1, s.y1), 2154)
       LIMIT 1) r ON true`,
    [stations.map((s) => s.id), stations.map((s) => s.x1), stations.map((s) => s.y1), stations.map((s) => s.x2), stations.map((s) => s.y2), config.datagouvRoadMaxM],
  );
  const byId = new Map(stations.map((s) => [s.id, s]));
  return rows.map((row) => ({
    id: row.id,
    axe: byId.get(row.id)?.axe ?? '',
    lengthM: byId.get(row.id)?.lengthM ?? 0,
    from: [row.lat1, row.lon1],
    to: [row.lat2, row.lon2],
    limitKmh: row.limit_kmh || config.datagouvDefaultLimitKmh[row.highway] || config.datagouvDefaultLimitKmh.other,
  }));
}

// ---- Speeds (qtvDir.xml) ---------------------------------------------------------------------

/** Each station's average speed and when it was measured: Map id -> { speedKmh, at }. */
export function parseSpeeds(xml) {
  const speeds = new Map();
  for (const block of String(xml).split('<siteMeasurements>').slice(1)) {
    const id = /measurementSiteReference[^>]*\sid="([^"]+)"/.exec(block)?.[1];
    const at = Date.parse(/<measurementTimeDefault>([^<]+)</.exec(block)?.[1] ?? '');
    const speed = Number(/TrafficSpeed[\s\S]*?<speed>([\d.]+)</.exec(block)?.[1]);
    if (id && Number.isFinite(at) && Number.isFinite(speed) && speed > 0) speeds.set(id, { speedKmh: speed, at });
  }
  return speeds;
}

// ---- Events (content.xml) --------------------------------------------------------------------

/** The situation record types kept, and what they mean on the road. */
const RECORD_KIND = {
  MaintenanceWorks: 'works',
  ConstructionWorks: 'works',
  Accident: 'incident',
  VehicleObstruction: 'incident',
  GeneralObstruction: 'incident',
  InfrastructureDamageObstruction: 'incident',
  EnvironmentalObstruction: 'incident',
  AbnormalTraffic: 'queue',
};
const MANAGEMENT_KIND = {
  roadClosed: 'closed',
  closedPermanentlyForTheWinter: 'closed',
  carriagewayClosures: 'closed',
  laneClosures: 'works',
  singleAlternateLineTraffic: 'works',
  narrowLanes: 'works',
  contraflow: 'works',
};
const LEVEL_OF_KIND = { closed: 'closed', queue: 'jam', works: 'slow', incident: 'slow' };
/** A TPEG direction as a course, for events placed by a single point. */
const COURSE_OF = { northBound: 0, eastBound: 90, southBound: 180, westBound: 270 };

/**
 * The events in force at [now]: { id, kind, level, from: [lat, lon], to: [lat, lon] | null,
 * direction (TPEG), endsAt }. Suspended ones, those not started or over, and the kinds that do
 * not slow a driver (reroutings, messages, weight limits) are left out.
 */
export function parseEvents(xml, now = Date.now()) {
  const events = [];
  for (const record of String(xml).split('<ns2:situationRecord ').slice(1)) {
    const type = /^xsi:type="ns2:([A-Za-z]+)"/.exec(record)?.[1];
    const management = /<ns2:roadOrCarriagewayOrLaneManagementType>([A-Za-z]+)</.exec(record)?.[1];
    const kind = type === 'RoadOrCarriagewayOrLaneManagement' ? MANAGEMENT_KIND[management] : RECORD_KIND[type];
    if (!kind) continue;
    if (/<ns2:validityStatus>suspended</.test(record)) continue;
    const start = Date.parse(/<ns2:overallStartTime>([^<]+)</.exec(record)?.[1] ?? '');
    const end = Date.parse(/<ns2:overallEndTime>([^<]+)</.exec(record)?.[1] ?? '');
    if (Number.isFinite(start) && start > now) continue;
    if (Number.isFinite(end) && end <= now) continue;
    const point = (tag) => {
      const m = new RegExp(`<ns2:${tag}\\b[\\s\\S]*?<ns2:latitude>([-\\d.]+)</ns2:latitude><ns2:longitude>([-\\d.]+)<`).exec(record);
      return m ? [Number(m[1]), Number(m[2])] : null;
    };
    // A segment gives its two ends (TPEG `to` comes first in the feed), a point its coordinates.
    const linear = /groupOfLocations xsi:type="ns2:Linear"/.test(record);
    const from = linear ? point('from') : point('pointCoordinates');
    const to = linear ? point('to') : null;
    if (!from || !from.every(Number.isFinite)) continue;
    events.push({
      id: /^[^>]*\sid="([^"]+)"/.exec(record)?.[1] ?? null,
      kind,
      level: LEVEL_OF_KIND[kind],
      from,
      to: to && to.every(Number.isFinite) ? to : null,
      direction: /<ns2:tpegDirection>([A-Za-z]+)</.exec(record)?.[1] ?? 'bothWays',
      endsAt: Number.isFinite(end) ? end : null,
    });
  }
  return events;
}

// ---- The feeds, kept fresh ---------------------------------------------------------------------

class DatagouvStore {
  constructor() {
    this.stations = [];
    this.speeds = new Map();
    this.events = [];
    this.at = { stations: null, speeds: null, events: null };
    this.errors = { stations: null, speeds: null, events: null };
    this.timers = [];
  }

  // A feed unchanged since the last fetch (304) keeps what it gave: nothing downloaded again.
  async refreshStations() {
    const csv = await fetchText(config.datagouvStationsUrl, 'latin1');
    if (csv != null) this.stations = await placeStations(parseStations(csv));
    this.at.stations = Date.now();
  }

  async refreshSpeeds() {
    const xml = await fetchText(config.datagouvSpeedsUrl);
    if (xml != null) this.speeds = parseSpeeds(xml);
    this.at.speeds = Date.now();
  }

  async refreshEvents() {
    const xml = await fetchText(config.datagouvEventsUrl);
    if (xml != null) this.events = parseEvents(xml);
    this.at.events = Date.now();
  }

  /** Each feed now, then on its own rhythm; a failure is logged and tried again next time. */
  start() {
    const run = (name, work) => () => work.call(this)
      .then(() => { this.errors[name] = null; })
      .catch((e) => {
        this.errors[name] = String(e.message || e);
        console.warn(`[datagouv] ${name} —`, this.errors[name]);
      });
    const every = (ms, task) => {
      task();
      const timer = setInterval(task, ms);
      if (timer.unref) timer.unref();
      this.timers.push(timer);
    };
    every(config.datagouvStationsEveryMs, run('stations', this.refreshStations));
    every(config.datagouvSpeedsEveryMs, run('speeds', this.refreshSpeeds));
    every(config.datagouvEventsEveryMs, run('events', this.refreshEvents));
  }

  /** For /health. */
  get meta() {
    const iso = (t) => (t ? new Date(t).toISOString() : null);
    return {
      stations: this.stations.length,
      speeds: this.speeds.size,
      events: this.events.length,
      updatedAt: { stations: iso(this.at.stations), speeds: iso(this.at.speeds), events: iso(this.at.events) },
      errors: this.errors,
    };
  }
}

export const datagouvStore = new DatagouvStore();

/** Each feed's ETag and date, sent back so an unchanged file is not downloaded again. */
const validators = new Map();

/** The feed's text; null when unchanged since the last fetch (HTTP 304). */
async function fetchText(url, encoding = 'utf-8') {
  const known = validators.get(url);
  const headers = {};
  if (known?.etag) headers['if-none-match'] = known.etag;
  if (known?.modified) headers['if-modified-since'] = known.modified;
  const res = await fetch(url, { headers, signal: AbortSignal.timeout(config.datagouvTimeoutMs) });
  if (res.status === 304) return null;
  if (!res.ok) throw new Error(`${url} ${res.status}`);
  const text = new TextDecoder(encoding).decode(await res.arrayBuffer());
  validators.set(url, { etag: res.headers.get('etag'), modified: res.headers.get('last-modified') });
  return text;
}

// ---- On a route --------------------------------------------------------------------------------

/**
 * data.gouv on a route ([path], routing/geometry.js): stretches in metres along it, raw (the whole
 * delay each says, before any other source), `source: "datagouv"`, `kind` works | incident |
 * queue | closed | speed. A station counts when both its ends lie on the route in its order; an
 * event by its two ends the same way, or by its point with a course close to the route's.
 */
export function datagouvAlong(path, store = datagouvStore, now = Date.now()) {
  if (path.points.length < 2) return [];
  const grid = gridOf(path.points);
  const onRoute = ([lat, lon]) => project(path, grid, lat, lon, config.datagouvOnRouteM)?.along ?? null;
  const sections = [];

  for (const station of store.stations) {
    const speed = store.speeds.get(station.id);
    if (!speed || now - speed.at > config.datagouvSpeedsMaxAgeMs) continue;
    const fromM = onRoute(station.from);
    const toM = fromM == null ? null : onRoute(station.to);
    if (fromM == null || toM == null || toM <= fromM) continue;
    const slowed = stationSlowdown(station, speed.speedKmh, toM - fromM);
    if (slowed) sections.push({ fromM: Math.round(fromM), toM: Math.round(toM), ...slowed, source: SOURCE, kind: 'speed' });
  }

  for (const event of store.events) {
    if (event.endsAt != null && event.endsAt <= now) continue;
    const fromM = onRoute(event.from);
    if (fromM == null) continue;
    let range = null;
    const toM = event.to ? onRoute(event.to) : null;
    if (toM != null && Math.abs(toM - fromM) >= 1) {
      // Given the other way round, it is the other carriageway's (unless it holds both ways).
      if (toM < fromM && event.direction !== 'bothWays') continue;
      range = [Math.min(fromM, toM), Math.max(fromM, toM)];
    } else {
      const course = COURSE_OF[event.direction];
      if (course != null && angleDiff(course, headingAt(path, fromM)) > config.datagouvSameWayDeg) continue;
      range = [fromM - config.datagouvPointHalfM, fromM + config.datagouvPointHalfM];
    }
    sections.push({
      fromM: Math.max(0, Math.round(range[0])),
      toM: Math.min(Math.round(path.total), Math.round(range[1])),
      level: event.level,
      delayS: 0,
      source: SOURCE,
      kind: event.kind,
    });
  }
  return sections.filter((s) => s.toM > s.fromM).sort((a, b) => a.fromM - b.fromM);
}

/** A station's slowdown over [lengthM] at [speedKmh]: { level, delayS, speedKmh }, or null when the road flows. */
function stationSlowdown(station, speedKmh, lengthM) {
  const ratio = speedKmh / station.limitKmh;
  if (ratio >= config.datagouvSlowRatio) return null;
  const free = (station.limitKmh * config.datagouvFreeFlowRatio) / 3.6;
  const measured = Math.max(speedKmh, config.datagouvMinSpeedKmh) / 3.6;
  const delayS = Math.round(Math.max(0, lengthM / measured - lengthM / free));
  if (delayS <= 0) return null;
  const level = ratio < 0.3 ? 'heavy' : ratio < 0.5 ? 'jam' : 'slow';
  return { level, delayS, speedKmh: Math.round(speedKmh) };
}

/**
 * data.gouv added last to what the other sources say (D2.6, D3.4): only for the time it costs
 * beyond them where they overlap, so leaving it out gives exactly the ETA without it. A closure
 * always shows.
 */
export function withDatagouv(sections, datagouv) {
  const extra = [];
  for (const zone of datagouv) {
    const known = sections.reduce((sum, s) => {
      const overlap = Math.min(s.toM, zone.toM) - Math.max(s.fromM, zone.fromM);
      return overlap > 0 && s.toM > s.fromM ? sum + ((s.delayS ?? 0) * overlap) / (s.toM - s.fromM) : sum;
    }, 0);
    const delayS = Math.round((zone.delayS ?? 0) - known);
    if (delayS > 0) extra.push({ ...zone, delayS });
    else if (zone.level === 'closed' || zone.delayS === 0) extra.push({ ...zone, delayS: 0 });
  }
  return sections.concat(extra).sort((a, b) => a.fromM - b.fromM);
}
