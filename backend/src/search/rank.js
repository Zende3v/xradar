/**
 * Ranking what the sources answered. A driver types a few letters, often with the accents
 * missing and a word forgotten, and expects the place they have in mind — usually one close by.
 * Four things decide, in this order:
 *
 *   1. how close it is to them — or whether it is in the town they typed ("leclerc orly");
 *   2. how well the name matches what they typed;
 *   3. what kind of place it is (a school beats a bus stop; a street loses to a shop when a
 *      place's name is typed, not an address);
 *   4. how much the place matters (a town, an airport: EONA's index says) and how well the source
 *      itself ranked it.
 *
 * No search engine here: a score between 0 and 1 per criterion, weighted and added.
 */

/** Weights: proximity and name carry the answer, the rest separates near-equal results. */
const WEIGHT = { distance: 0.34, name: 0.34, kind: 0.18, importance: 0.10, rank: 0.04 };
/** What a place matters when its source does not say: a commune more than a street or a shop. */
const IMPORTANCE_DEFAULT = 0.3;
const IMPORTANCE_COMMUNE = 0.8;

/**
 * How proximity fades. A driver looking for "carrefour" means the one down the road, not the one
 * 200 km away that happens to be spelled exactly right: past this, the score is all but gone.
 */
const FAR_M = 25_000;

/**
 * What a place is worth by its kind, from its OpenStreetMap tags. A driver looks for somewhere to
 * go: a school, a station or a shop, rarely a bench or a hedge.
 */
const KIND_SCORE = new Map(Object.entries({
  'amenity:school': 1, 'amenity:college': 1, 'amenity:university': 1, 'amenity:kindergarten': 0.9,
  'amenity:hospital': 1, 'amenity:clinic': 0.95, 'amenity:pharmacy': 0.9, 'amenity:doctors': 0.85,
  'amenity:townhall': 1, 'amenity:police': 0.95, 'amenity:fire_station': 0.9, 'amenity:post_office': 0.9,
  'amenity:fuel': 0.95, 'amenity:charging_station': 0.9, 'amenity:parking': 0.85, 'amenity:restaurant': 0.85,
  'amenity:cafe': 0.8, 'amenity:bar': 0.75, 'amenity:fast_food': 0.8, 'amenity:bank': 0.85,
  'amenity:library': 0.85, 'amenity:theatre': 0.85, 'amenity:cinema': 0.85, 'amenity:place_of_worship': 0.8,
  'amenity:bus_station': 0.95, 'amenity:marketplace': 0.85, 'amenity:toilets': 0.5, 'amenity:bench': 0.2,
  'amenity:waste_basket': 0.1, 'amenity:bicycle_parking': 0.4, 'amenity:atm': 0.6,
  'railway:station': 1, 'railway:halt': 0.85, 'railway:tram_stop': 0.35, 'railway:subway_entrance': 0.5,
  'aeroway:aerodrome': 1, 'aeroway:terminal': 0.95,
  'highway:bus_stop': 0.2, 'highway:services': 0.9, 'highway:rest_area': 0.8,
  'tourism:hotel': 0.9, 'tourism:museum': 0.9, 'tourism:attraction': 0.85, 'tourism:camp_site': 0.85,
  'tourism:information': 0.6, 'tourism:artwork': 0.4, 'tourism:viewpoint': 0.6,
  'leisure:sports_centre': 0.85, 'leisure:stadium': 0.9, 'leisure:swimming_pool': 0.8,
  'leisure:park': 0.75, 'leisure:pitch': 0.4, 'leisure:playground': 0.5,
  'shop:supermarket': 0.95, 'shop:mall': 0.95, 'shop:bakery': 0.8, 'shop:convenience': 0.75,
  'shop:car_repair': 0.8, 'shop:hairdresser': 0.6, 'shop:clothes': 0.7, 'shop:yes': 0.6,
  'office:government': 0.95, 'office:company': 0.7,
  'place:city': 1, 'place:town': 0.95, 'place:village': 0.9, 'place:hamlet': 0.85,
  'place:locality': 0.8, 'place:suburb': 0.85, 'place:neighbourhood': 0.8, 'place:quarter': 0.8,
  'place:isolated_dwelling': 0.7, 'place:farm': 0.7, 'place:house': 0.6,
  'building:yes': 0.5, 'building:school': 0.95, 'building:train_station': 0.95,
}));

/** A kind we know nothing about still deserves a middling score, not a zero. */
const KIND_DEFAULT = 0.65;
/** An address (Base Adresse Nationale): exactly what is asked when a number is typed. */
const ADDRESS_SCORE = 0.8;
/** A street or a door while a place's name is typed: "leclerc orly" is not the Rue du Général-Leclerc. */
const STREET_FOR_PLACE = 0.35;
/** What the Base Adresse Nationale calls a street or a door (not a town, not a lieu-dit). */
const BAN_STREETS = new Set(['street', 'housenumber']);
/**
 * A town the driver typed ("leclerc orly") matters more than being close: whatever is in it
 * scores at least this for its distance (as if about 3 km away).
 */
const TOWN_DISTANCE = 0.8;
/**
 * A notable place (a town, an airport, a station: EONA's index gives its importance, 0 to 1)
 * named as typed is what is meant, even far off ("marseille": the city before the shops nearby
 * named after it): its distance scores at least TOWN_DISTANCE times its importance. Named as
 * typed: every word typed is a word of its name, and they make at least NOTABLE_COVER of it — a
 * generic word ("aeroport") names every airport, never one far away.
 */
const NOTABLE = 0.6;
const NOTABLE_COVER = 0.5;
/** Each word of a name the driver did not type costs this much of the name's score, up to NAME_EXTRA_MAX. */
const NAME_EXTRA = 0.05;
const NAME_EXTRA_MAX = 0.3;

/** Street words that seldom name a place: an address wherever they are typed. */
const STREET_WORDS = new Set([
  'rue', 'ruelle', 'avenue', 'av', 'boulevard', 'bd', 'bld', 'blvd', 'allee', 'allees', 'impasse', 'imp',
  'chemin', 'quai', 'chaussee', 'faubourg', 'fbg', 'lotissement',
]);
/** Street words some places' names hold too: an address only first, or after a house number. */
const LEADING_STREET_WORDS = new Set(['place', 'pl', 'route', 'rte', 'passage', 'sentier', 'voie', 'rond', 'cours']);
/** A house number: "8", "8bis", "12b". */
const HOUSE_NUMBER = /^\d{1,4}(bis|ter|quater|[a-z])?$/;
/** Short words that make a town part of a name: "gare de lyon", "porte d'orléans". */
const OF = new Set(['de', 'd', 'du', 'des']);
/** Words too common in towns' names to tell one ("saint", "sur"). */
const TOWN_FILLER = new Set(['saint', 'sainte', 'sur', 'sous', 'les', 'lez', 'aux', 'des']);

/** Lowercase, no accents, no punctuation: "Lycée Adolphe Chérioux" → "lycee adolphe cherioux". */
export function fold(text) {
  return String(text ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

/** The words of a query, the very short ones ("de", "la") kept only when alone. */
function words(text) {
  const all = fold(text).split(' ').filter(Boolean);
  const strong = all.filter((word) => word.length > 2);
  return strong.length ? strong : all;
}

/**
 * Whether [query] is plainly an address: a street's name ("rue …", "place …"), after a house
 * number or not, or numbers only (a postcode). Anything else may be a place's name.
 */
export function looksLikeAddress(query) {
  const typed = fold(query).split(' ').filter(Boolean);
  if (!typed.length) return false;
  if (typed.every((word) => /^\d+$/.test(word))) return true;
  return typed.some((word, i) => STREET_WORDS.has(word) || (LEADING_STREET_WORDS.has(word) && (
    i === 0
    || HOUSE_NUMBER.test(typed[i - 1])
    // "8 bis place …", "12 b rue …"
    || (i >= 2 && /^(bis|ter|quater|[a-z])$/.test(typed[i - 1]) && HOUSE_NUMBER.test(typed[i - 2])))));
}

/**
 * The words of [query] naming [item]'s town or postcode ("leclerc orly": orly) — none when the
 * town is part of a name ("gare de lyon", "porte de versailles"), nor when only the town is
 * typed ("orly", "vitry sur seine"): then the town itself is looked for, not a place in it.
 */
function townWords(query, item) {
  const town = new Set(fold(item.city).split(' ').filter((word) => word.length > 2 && !TOWN_FILLER.has(word)));
  if (item.postcode) town.add(fold(item.postcode));
  if (!words(query).some((word) => !town.has(word) && !TOWN_FILLER.has(word))) return new Set();
  const typed = fold(query).split(' ');
  return new Set(typed.filter((word, i) => town.has(word) && !OF.has(typed[i - 1])));
}

/**
 * How well [name] (plus what places it) answers [query], between 0 and 1. A word typed in full
 * counts more than one merely begun, and a word the driver did not type costs nothing. A word
 * naming the place's town ([town]) counts in full.
 */
function nameScore(query, name, context, town) {
  const asked = words(query);
  if (!asked.length) return 0;
  const target = fold(name);
  const around = fold(context);
  if (!target) return 0;
  if (target === fold(query)) return 1;

  let hit = 0;
  for (const word of asked) {
    if (target === word || target.startsWith(`${word} `) || target.includes(` ${word} `) || target.endsWith(` ${word}`)) {
      hit += 1;
    } else if (town.has(word)) {
      hit += 1; // the town typed after the name: "orly" in "leclerc orly"
    } else if (target.includes(word)) {
      hit += 0.85; // inside a longer word: "cherioux" in "cherioux-vitry"
    } else if (word.length >= 4 && target.split(' ').some((part) => part.startsWith(word.slice(0, -1)))) {
      hit += 0.6; // one letter off, or a word still being typed
    } else if (around.includes(word)) {
      hit += 0.5; // the town or the street, not the name itself
    }
  }
  // A name longer than what was typed says less about it: "Gare de Lyon" before
  // "Residhome Paris Gare de Lyon".
  const extra = Math.max(0, target.split(' ').length - fold(query).split(' ').filter(Boolean).length);
  const score = (hit / asked.length) * (1 - Math.min(NAME_EXTRA_MAX, NAME_EXTRA * extra));
  // The name starting with what was typed is the strongest sign there is.
  return target.startsWith(fold(query)) ? Math.min(1, score + 0.15) : score;
}

/** 1 next to the driver, fading to 0 at FAR_M; unknown distance sits in the middle. */
function distanceScore(distanceM) {
  if (distanceM == null) return 0.5;
  return Math.exp(-distanceM / (FAR_M / 2));
}

/** What the source thought, as a number: first answer 1, last one near 0. */
function rankScore(index, total) {
  return total <= 1 ? 1 : 1 - index / total;
}

/**
 * What kind of place this is, between 0 and 1. [address]: whether an address was typed — if
 * not, a street or a door is not what is looked for.
 */
export function kindScore(item, { address = true } = {}) {
  // A source that knows its places' kind brings it.
  if (Number.isFinite(item.kind)) return item.kind;
  if (item.source === 'ban') return !address && BAN_STREETS.has(item.banType) ? STREET_FOR_PLACE : ADDRESS_SCORE;
  // Photon without a name: a door ("8 rue …").
  if (!address && item.named === false) return STREET_FOR_PLACE;
  const known = item.osmKey && item.osmValue ? KIND_SCORE.get(`${item.osmKey}:${item.osmValue}`) : undefined;
  if (known != null) return known;
  if (!address && item.osmKey === 'highway') return STREET_FOR_PLACE;
  return KIND_DEFAULT;
}

/** The distance score a notable place named as typed keeps however far it is (NOTABLE); 0 otherwise. */
function notableFloor(query, item) {
  if (!(item.importance >= NOTABLE)) return 0;
  const folded = fold(item.name);
  const typed = words(query);
  const name = ` ${folded} `;
  if (!typed.every((word) => name.includes(` ${word} `))) return 0;
  const cover = typed.join('').length / folded.replace(/ /g, '').length;
  return cover >= NOTABLE_COVER ? TOWN_DISTANCE * item.importance : 0;
}

/** How much the place matters, between 0 and 1: EONA's index says; a commune of the BAN matters. */
function importanceScore(item) {
  if (Number.isFinite(item.importance)) return item.importance;
  return item.source === 'ban' && item.banType === 'municipality' ? IMPORTANCE_COMMUNE : IMPORTANCE_DEFAULT;
}

/** The score of one answer, and the parts that made it (handy when tuning). */
export function score(item, { query, index, total }) {
  const town = townWords(query, item);
  const near = distanceScore(item.distanceM);
  const parts = {
    distance: town.size ? Math.max(near, TOWN_DISTANCE) : Math.max(near, notableFloor(query, item)),
    name: nameScore(query, item.name, item.subtitle ?? '', town),
    kind: kindScore(item, { address: looksLikeAddress(query) }),
    importance: importanceScore(item),
    rank: rankScore(index, total),
  };
  const total01 = Object.entries(WEIGHT).reduce((sum, [key, weight]) => sum + weight * parts[key], 0);
  return { score: total01, parts };
}
