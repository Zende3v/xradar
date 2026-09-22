/**
 * Ranking what the two sources answered. A driver types a few letters, often with the accents
 * missing and a word forgotten, and expects the place they have in mind — usually one close by.
 * Four things decide, in this order:
 *
 *   1. how close it is to them;
 *   2. how well the name matches what they typed;
 *   3. what kind of place it is (a school beats a bus stop);
 *   4. how well the source itself ranked it, which carries the place's own standing.
 *
 * No search engine here: a score between 0 and 1 per criterion, weighted and added.
 */

/** Weights: proximity and name carry the answer, the rest separates near-equal results. */
const WEIGHT = { distance: 0.40, name: 0.34, kind: 0.18, rank: 0.08 };

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
 * How well [name] (plus what places it) answers [query], between 0 and 1. A word typed in full
 * counts more than one merely begun, and a word the driver did not type costs nothing.
 */
function nameScore(query, name, context) {
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
    } else if (target.includes(word)) {
      hit += 0.85; // inside a longer word: "cherioux" in "cherioux-vitry"
    } else if (word.length >= 4 && target.split(' ').some((part) => part.startsWith(word.slice(0, -1)))) {
      hit += 0.6; // one letter off, or a word still being typed
    } else if (around.includes(word)) {
      hit += 0.5; // the town or the street, not the name itself
    }
  }
  const score = hit / asked.length;
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

/** What kind of place this is, between 0 and 1. */
export function kindScore(item) {
  if (item.source === 'ban') return ADDRESS_SCORE;
  if (!item.osmKey || !item.osmValue) return KIND_DEFAULT;
  return KIND_SCORE.get(`${item.osmKey}:${item.osmValue}`) ?? KIND_DEFAULT;
}

/** The score of one answer, and the parts that made it (handy when tuning). */
export function score(item, { query, index, total }) {
  const parts = {
    distance: distanceScore(item.distanceM),
    name: nameScore(query, item.name, item.subtitle ?? ''),
    kind: kindScore(item),
    rank: rankScore(index, total),
  };
  const total01 = Object.entries(WEIGHT).reduce((sum, [key, weight]) => sum + weight * parts[key], 0);
  return { score: total01, parts };
}
