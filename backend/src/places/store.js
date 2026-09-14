import { config } from '../config.js';
import { db } from '../db.js';
import { evaluate } from './hours.js';

/**
 * Nearby services from PostGIS (schema signs, table place — built weekly by
 * signalisation/places.sql from OpenStreetMap): the nearest places of a kind, each described
 * for the driver — its name and brand, address, opening state, and what matters for its kind.
 */

export const KINDS = ['fuel', 'charging', 'parking', 'tobacco', 'garage', 'hotel', 'atm'];

const FALLBACK_NAMES = {
  fuel: 'Station-service',
  charging: 'Borne de recharge',
  parking: 'Parking',
  tobacco: 'Tabac',
  garage: 'Garage',
  hotel: 'Hôtel',
  atm: 'Distributeur',
};

// The kind is part of the SQL text (checked against KINDS) so each search uses its own
// partial index; the position and the count stay parameters.
const nearSql = (kind) => `
  SELECT p.id, p.tags, p.commune, ST_Y(p.geom) AS lat, ST_X(p.geom) AS lon,
         round(ST_Distance(p.geom_m, q.here))::int AS distance_m
  FROM (SELECT ST_Transform(ST_SetSRID(ST_MakePoint($2, $1), 4326), 2154) AS here) q
  CROSS JOIN LATERAL (
    SELECT * FROM ${config.placeTable} WHERE kind = '${kind}' ORDER BY geom_m <-> q.here LIMIT $3
  ) p
  ORDER BY distance_m`;

/** The [limit] nearest places of [kind] around lat/lon, nearest first. */
export async function near(kind, lat, lon, limit) {
  if (!KINDS.includes(kind)) throw new Error(`unknown kind ${kind}`);
  const { rows } = await db.query(nearSql(kind), [lat, lon, limit]);
  return rows.map((row) => ({
    id: row.id,
    kind,
    lat: row.lat,
    lon: row.lon,
    distanceM: row.distance_m,
    tags: row.tags,
    commune: row.commune,
  }));
}

/**
 * What the app shows for a place found by [near]. [officialHours] is the opening_hours value
 * the official fuel feed declares for a matched station: it wins over OpenStreetMap.
 */
export function describe(place, { officialHours = null, now = new Date() } = {}) {
  const { kind, tags } = place;
  const street = [tags['addr:housenumber'], tags['addr:street'] || tags['addr:place']].filter(Boolean).join(' ');
  const town = tags['addr:city'] || place.commune || null;
  const official = evaluate(officialHours, now);
  // A bank's hours are its counter's, not those of the cash machine in its wall.
  const ownHours = kind === 'atm' && tags.amenity === 'bank' ? null : tags.opening_hours;
  const mapped = official ? null : evaluate(ownHours, now);
  return {
    id: place.id,
    kind,
    name: nameOf(kind, tags),
    brand: tags.brand || null,
    subtitle: [street, town].filter(Boolean).join(', '),
    lat: place.lat,
    lon: place.lon,
    distanceM: place.distanceM,
    customersOnly: tags.access === 'customers',
    hours: official ? { ...official, source: 'official' } : mapped && { ...mapped, source: 'osm' },
    ...(kind === 'charging' ? { charging: charging(tags) } : {}),
    ...(kind === 'parking' ? { parking: parking(tags) } : {}),
    ...(kind === 'hotel' ? { stars: stars(tags.stars) } : {}),
  };
}

function nameOf(kind, tags) {
  switch (kind) {
    case 'atm':
      return tags.brand || tags.operator || tags.name || FALLBACK_NAMES.atm;
    case 'charging':
      return tags.name || tags.network || tags.brand || tags.operator || FALLBACK_NAMES.charging;
    case 'parking':
      return tags.name || (tags.operator ? `Parking ${tags.operator}` : FALLBACK_NAMES.parking);
    default:
      return tags.name || tags.brand || tags.operator || FALLBACK_NAMES[kind];
  }
}

// Connectors a driver asks for, strongest first; OSM socket:* keys map onto them.
const CONNECTORS = [
  ['CCS', ['type2_combo', 'tesla_supercharger_ccs', 'type1_combo']],
  ['CHAdeMO', ['chademo']],
  ['Tesla', ['tesla_supercharger', 'nacs']],
  ['Type 2', ['type2', 'type2_cable']],
  ['Type 3', ['type3c', 'type3a']],
  ['Type 1', ['type1']],
  ['Prise E', ['typee', 'schuko']],
];

/** `{maxKw, connectors, points}` — power in kW, connector names, charging points. */
export function charging(tags) {
  const connectors = [];
  const powers = [kilowatts(tags['charging_station:output']), kilowatts(tags.maxpower)];
  for (const [label, keys] of CONNECTORS) {
    let present = false;
    for (const key of keys) {
      const value = tags[`socket:${key}`];
      if (!value || value === 'no' || value === '0') continue;
      present = true;
      powers.push(kilowatts(tags[`socket:${key}:output`]));
    }
    if (present) connectors.push(label);
  }
  const known = powers.filter((p) => p != null);
  return {
    maxKw: known.length ? Math.max(...known) : null,
    connectors,
    points: positiveInt(tags.capacity),
  };
}

/** "50 kW", "22kW", "7400 W", "3.7 kW;22 kW" -> the highest, in kW; null if unreadable. */
export function kilowatts(value) {
  if (!value) return null;
  let best = null;
  for (const m of String(value).matchAll(/(\d+(?:[.,]\d+)?)\s*(kw|w|kva)?/gi)) {
    let n = Number(m[1].replace(',', '.'));
    const unit = (m[2] || '').toLowerCase();
    if (unit === 'w' || (!unit && n > 1000)) n /= 1000;
    if (n < 1 || n > 1000) continue;
    best = best == null ? n : Math.max(best, n);
  }
  return best == null ? null : Math.round(best * 10) / 10;
}

const PARKING_TYPES = {
  underground: 'underground',
  'multi-storey': 'multi_storey',
  rooftop: 'rooftop',
  surface: 'surface',
  street_side: 'street_side',
};

/** `{fee, type, capacity, parkAndRide}` — fee true (paying), false (free) or null (unknown). */
export function parking(tags) {
  const fee = String(tags.fee || '').toLowerCase();
  return {
    fee: fee === '' || fee === 'unknown' ? null : fee === 'no' || fee === 'free' || fee === 'donation' ? false : true,
    type: PARKING_TYPES[tags.parking] || null,
    capacity: positiveInt(tags.capacity),
    parkAndRide: Boolean(tags.park_ride) && tags.park_ride !== 'no',
  };
}

function stars(value) {
  const n = Number.parseInt(String(value || ''), 10);
  return n >= 1 && n <= 5 ? n : null;
}

function positiveInt(value) {
  const n = /^\s*\d{1,6}\s*$/.test(String(value ?? '')) ? Number(value) : NaN;
  return n > 0 ? n : null;
}
