import { config } from '../config.js';
import { searchBudget } from './budget.js';

/**
 * TomTom POI Search (Search API version 2), in typeahead mode — what is typed may stop mid-word:
 * the shops, gyms, restaurants… OpenStreetMap misses ("leclerc orly", "fitness park orly").
 * Places only — the Base Adresse Nationale has the addresses — and in France only. Nothing is
 * sent without SEARCH_TOMTOM_ENABLED=1 and a key, and every request spends the search's own budget
 * first (search/budget.js), never the traffic's. The key and the query travel in the URL: a
 * failure is logged by its reason alone, never its URL nor its message (routes.js).
 */
const PATH = '/search/2/poiSearch/';
/** Refusals that asking again will not change: the key, its rights, our own request. */
const REFUSED = new Set([400, 401, 403, 404]);

export function createTomtomPlaces({
  enabled,
  apiKey,
  baseUrl,
  maxResults,
  timeoutMs,
  pauseMs,
  blockMs,
  budget,
  fetch: send = (...args) => globalThis.fetch(...args),
  now = Date.now,
}) {
  if (enabled && !apiKey) console.warn('[search] SEARCH_TOMTOM_ENABLED without a key (SEARCH_TOMTOM_API_KEY, TOMTOM_API_KEY): TomTom off');
  let restUntil = 0;
  const on = () => Boolean(enabled && apiKey) && now() >= restUntil;
  return {
    /** Whether TomTom may be asked now: on, with a key, not resting after a failure, budget left. */
    available() {
      return on() && budget.left();
    },

    /**
     * TomTom's places for [query] near [around] ({ lat, lon }, or null: anywhere in France), in the
     * search's shape. Null when nothing was sent (off, resting, budget spent or not saved); throws
     * when TomTom failed — and then rests: pauseMs, or blockMs when it refused.
     */
    async search(query, around) {
      if (!on()) return null;
      const params = new URLSearchParams({
        key: apiKey,
        typeahead: 'true',
        limit: String(maxResults),
        countrySet: 'FR',
        language: 'fr-FR',
      });
      if (around) {
        // Near where the driver is, to about 100 m: enough to rank, no more than needed.
        params.set('lat', String(round3(around.lat)));
        params.set('lon', String(round3(around.lon)));
      }
      const url = `${baseUrl.replace(/\/$/, '')}${PATH}${encodeURIComponent(query)}.json?${params}`;
      if (!(await budget.take())) return null;
      try {
        const answer = await send(url, {
          headers: { Accept: 'application/json' },
          signal: AbortSignal.timeout(timeoutMs),
        });
        if (!answer.ok) throw Object.assign(new Error('HTTP error'), { status: answer.status });
        const json = await answer.json();
        return (Array.isArray(json?.results) ? json.results : []).map(tomtomPlace).filter(Boolean);
      } catch (e) {
        restUntil = now() + (REFUSED.has(e.status) ? blockMs : pauseMs);
        throw e;
      }
    },

    usage: () => budget.usage(),
  };
}

const round3 = (value) => Math.round(value * 1000) / 1000;
const text = (value) => (typeof value === 'string' && value.trim() ? value.trim() : null);

/** One TomTom result as the search's other results; null without a name or a position. */
export function tomtomPlace(result) {
  const lat = result?.position?.lat;
  const lon = result?.position?.lon;
  const name = text(result?.poi?.name);
  if (!name || !Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  const a = result.address ?? {};
  const street = [text(a.streetNumber), text(a.streetName)].filter(Boolean).join(' ') || null;
  const [category, kind] = poiKind(result.poi);
  return {
    id: `tomtom:${text(result.id) ?? `${lat},${lon}`}`,
    name,
    // What places it — the ranking reads the town there (rank.js); rewritten after the merge.
    subtitle: [street, text(a.postalCode), text(a.municipality)].filter(Boolean).join(' '),
    lat,
    lon,
    city: text(a.municipality),
    address: street,
    postcode: text(a.postalCode),
    category,
    osmKey: null,
    osmValue: null,
    named: true,
    // What the place is worth by its kind (rank.js): TomTom has no OpenStreetMap tags.
    kind,
    source: 'tomtom',
    // TomTom's data, said so under its name in the apps (routes.js).
    attribution: '© TomTom',
  };
}

/**
 * Its label in the app and its worth for the ranking: from its category first, else from its
 * class. A TomTom place of a kind not listed is still a place someone goes to: no label, but it
 * scores above an unknown OpenStreetMap kind.
 */
function poiKind(poi) {
  const categories = Array.isArray(poi?.categorySet) ? poi.categorySet.map((category) => category?.id) : [];
  const classes = Array.isArray(poi?.classifications) ? poi.classifications.map((c) => c?.code) : [];
  for (const key of [...categories, ...classes]) {
    const known = POI.get(String(key));
    if (known) return known;
  }
  return [null, POI_DEFAULT];
}

const POI_DEFAULT = 0.85;

/**
 * Label, worth (as in rank.js). The two categories are the ones TomTom gave E.Leclerc and Fitness
 * Park at Orly; then the classes (classifications' code) a driver goes to most.
 */
const POI = new Map(Object.entries({
  7332005: ['Supermarché', 0.95], 7320002: ['Salle de sport', 0.9],
  MARKET: ['Marché', 0.85], SHOPPING_CENTER: ['Centre commercial', 0.95], SHOP: ['Commerce', 0.8],
  SPORTS_CENTER: ['Centre sportif', 0.85], RESTAURANT: ['Restaurant', 0.85], CAFE_PUB: ['Café', 0.8],
  HOTEL_MOTEL: ['Hôtel', 0.9], PETROL_STATION: ['Station-service', 0.95],
  ELECTRIC_VEHICLE_STATION: ['Borne de recharge', 0.9], PARKING_GARAGE: ['Parking', 0.85],
  OPEN_PARKING_AREA: ['Parking', 0.85], REPAIR_FACILITY: ['Garage', 0.8],
  HOSPITAL_POLYCLINIC: ['Hôpital', 1], PHARMACY: ['Pharmacie', 0.9], SCHOOL: ['École', 1],
  COLLEGE_UNIVERSITY: ['Université', 1], RAILWAY_STATION: ['Gare', 1], AIRPORT: ['Aéroport', 1],
  POLICE_STATION: ['Police', 0.95], POST_OFFICE: ['Poste', 0.9],
}));

/** The search's TomTom, from the configuration. */
export const tomtomPlaces = createTomtomPlaces({
  enabled: config.searchTomtomEnabled,
  apiKey: config.searchTomtomApiKey,
  baseUrl: config.tomtomUrl,
  maxResults: config.searchSourceLimit,
  timeoutMs: config.searchTimeoutMs,
  pauseMs: config.searchTomtomPauseMs,
  blockMs: config.searchTomtomBlockMs,
  budget: searchBudget,
});
