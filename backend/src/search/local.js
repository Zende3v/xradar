import { config } from '../config.js';
import { db } from '../db.js';
import { fold } from './rank.js';

/**
 * EONA's own place search (30/09, in place of Photon): the named places of OpenStreetMap in
 * PostGIS, rebuilt each week (search/build.sql, schema "search"). Each word typed (3 letters or
 * more) must match the place's names or its town, a word still being typed or one letter off
 * included (pg_trgm word similarity), so "lycee cherioux vitry" and "leclerc orly" find their
 * place. Two lists: the places around the driver, and the notable ones all over France (towns,
 * airports, stations…); rank.js orders the whole with the Base Adresse Nationale's answers.
 * Throws when the index cannot answer (not built yet, database down): the caller falls back.
 */
export async function fromLocal(query, around, { pool = db } = {}) {
  const client = await pool.connect();
  try {
    await client.query('BEGIN READ ONLY');
    await client.query(`SET LOCAL statement_timeout = ${Number(config.searchLocalTimeoutMs)}`);
    const folded = (await client.query('SELECT search.fold($1) AS q', [query])).rows[0]?.q ?? '';
    const words = [...new Set(folded.split(' ').filter((word) => word.length >= 3))].slice(0, config.searchLocalMaxWords);
    if (!words.length) return [];
    // Each word, somewhere in the place's names or town, from parameter [first] on (the GIN
    // index takes each).
    const match = (first) => words.map((_, i) => `$${i + first} <% poi.doc`).join(' AND ');
    const here = 'ST_Transform(ST_SetSRID(ST_MakePoint($2, $3), 4326), 2154)';
    const near = around
      ? await client.query(
        `SELECT ${COLUMNS}
         FROM search.poi
         WHERE poi.geom_m && ST_Expand(${here}, $4) AND ${match(5)}
         ORDER BY word_similarity($1, poi.name_fold) * 0.5 + exp(-ST_Distance(poi.geom_m, ${here}) / 12500) * 0.35
                  + poi.weight * 0.15 DESC
         LIMIT ${Number(config.searchLocalLimit)}`,
        [folded, around.lon, around.lat, config.searchLocalRadiusM, ...words],
      )
      : { rows: [] };
    const notable = await client.query(
      `SELECT ${COLUMNS}
       FROM search.poi
       WHERE poi.weight >= 0.6 AND ${match(2)}
       ORDER BY word_similarity($1, poi.name_fold) * 0.7 + poi.weight * 0.3 DESC
       LIMIT ${Number(config.searchNotableLimit)}`,
      [folded, ...words],
    );
    // Enseigne + ville (« E.Leclerc Orly », « Fitness Park Orly ») : la ville typée sert de repère,
    // l'enseigne est cherchée autour, même dans la commune voisine (04/10).
    const hinted = await nearTown(client, words, around);
    // The notable places first: few, and meant whenever their name is typed (rank.js).
    const seen = new Set();
    return [...notable.rows, ...near.rows, ...hinted]
      .filter((row) => !seen.has(row.id) && seen.add(row.id))
      .map(localPlace);
  } finally {
    await client.query('ROLLBACK').catch(() => {});
    client.release();
  }
}

/** Autour de la ville typée : l'enseigne à cette distance au plus de son centre. */
const TOWN_HINT_M = 8000;
/** Ce qu'est une ville pour un repère : commune ou quartier, pas un lieu-dit. */
const TOWN_KINDS = `('city', 'town', 'village', 'suburb', 'borough', 'quarter')`;

/**
 * Les lieux nommés par une partie des mots, autour de la ville nommée par l'autre : dernier mot,
 * deux derniers, puis premier mot (« orly leclerc »). Première ville trouvée seulement, la plus
 * proche du conducteur quand plusieurs portent ce nom. Chaque lieu porte `town_hint`.
 */
async function nearTown(client, words, around) {
  if (words.length < 2) return [];
  const splits = [
    [words.slice(-1), words.slice(0, -1)],
    [words.slice(-2), words.slice(0, -2)],
    [words.slice(0, 1), words.slice(1)],
  ].filter(([town, rest]) => town.length && rest.some((word) => !FILLER.has(word)));
  for (const [townWords, rest] of splits) {
    const town = townWords.join(' ');
    const values = [town];
    const closest = around
      ? `ST_Distance(t.geom_m, ST_Transform(ST_SetSRID(ST_MakePoint($${values.push(around.lon)}, $${values.push(around.lat)}), 4326), 2154))`
      : '-t.weight';
    const radius = `$${values.push(TOWN_HINT_M)}`;
    const restText = `$${values.push(rest.join(' '))}`;
    const first = values.length + 1;
    values.push(...rest);
    const { rows } = await client.query(
      `WITH town AS (
         SELECT t.name, t.geom_m FROM search.poi t
         WHERE $1 <% t.doc AND t.key = 'place' AND t.value IN ${TOWN_KINDS}
           AND (t.name_fold = $1 OR t.name_fold LIKE $1 || ' %')
         ORDER BY (t.name_fold = $1) DESC, ${closest}
         LIMIT 1
       )
       SELECT ${COLUMNS}, town.name AS town_hint
       FROM town JOIN search.poi ON poi.geom_m && ST_Expand(town.geom_m, ${radius})
       WHERE poi.key <> 'place' AND ST_DWithin(poi.geom_m, town.geom_m, ${radius})
         AND ${rest.map((_, i) => `$${i + first} <% poi.doc`).join(' AND ')}
       ORDER BY word_similarity(${restText}, poi.name_fold) * 0.6 + exp(-ST_Distance(poi.geom_m, town.geom_m) / 4000) * 0.4 DESC
       LIMIT 20`,
      values,
    );
    if (!rows.length) continue;
    // La ville elle-même tapée en entier (« vitry sur seine ») : rien à chercher autour.
    const named = new Set(fold(rows[0].town_hint).split(' '));
    return rest.every((word) => named.has(word) || FILLER.has(word)) ? [] : rows;
  }
  return [];
}

/** Mots de liaison des noms de villes : jamais une enseigne à eux seuls. */
const FILLER = new Set(['sur', 'sous', 'les', 'lez', 'aux', 'des', 'saint', 'sainte', 'pres']);

const COLUMNS = `poi.id, poi.osm_type, poi.osm_id, poi.key, poi.value, poi.name, poi.housenumber, poi.street,
  poi.postcode, poi.city, poi.lat, poi.lon, poi.weight`;

/** One row as a result, the shape of the other sources' (routes.js). */
export function localPlace(row) {
  return {
    id: `osm:${row.osm_type ?? ''}${row.osm_id}`,
    name: row.name,
    subtitle: [[row.housenumber, row.street].filter(Boolean).join(' '), row.postcode, row.city].filter(Boolean).join(' '),
    lat: Number(row.lat),
    lon: Number(row.lon),
    city: row.city ?? null,
    address: [row.housenumber, row.street].filter(Boolean).join(' ') || null,
    postcode: row.postcode ?? null,
    osmKey: row.key,
    osmValue: row.value,
    // What it is worth beyond its name and distance: a town, an airport, a notable place (build.sql).
    importance: Number(row.weight),
    // Trouvé autour de la ville typée (nearTown) : ce mot vaut la ville pour le classement (rank.js).
    townHint: row.town_hint ?? null,
    named: true,
    source: 'osm',
  };
}

/** The index's state for /health: when it was built and how many places; null before the first build. */
let meta = null;
export async function refreshLocalMeta(pool = db) {
  try {
    const { rows } = await pool.query('SELECT built_at, places FROM search.meta LIMIT 1');
    meta = rows[0] ? { builtAt: rows[0].built_at.toISOString(), places: Number(rows[0].places) } : null;
  } catch {
    meta = null;
  }
  return meta;
}
export function localMeta() {
  return meta;
}
