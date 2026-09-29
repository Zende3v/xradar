-- EONA search, step 2 (after import.sh): the published index of named places, built in schema
-- search_next and swapped in by publish.sql.
--
--   search_next.poi    one row per place: what it is (OSM key and value), its name, its street,
--                      postcode and commune, a point, a weight, and the folded text searched
--   search_next.meta   when it was built, how many places
--
-- A place mapped twice (a school's node and its grounds) becomes one: same kind, same name,
-- within 150 m. Words are matched with pg_trgm, each typed word against the place's names and
-- town (src/search/local.js), so a word still being typed or one letter off still finds it.
--
-- Run as eona after import.sh:  psql -d eona -f build.sql

\set ON_ERROR_STOP on
SET client_min_messages = warning;
SET maintenance_work_mem = '1GB';
\timing on

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

DROP SCHEMA IF EXISTS search_next CASCADE;
CREATE SCHEMA search_next;

-- ---- Helpers ---------------------------------------------------------------------------

-- Lowercase, no accents, no punctuation: "Lycée Adolphe-Chérioux" -> "lycee adolphe cherioux".
-- The same function folds what is typed (search.fold), so both sides always agree.
CREATE FUNCTION search_next.fold(t text) RETURNS text
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT trim(regexp_replace(lower(public.unaccent('public.unaccent'::regdictionary, coalesce(t, ''))), '[^a-z0-9]+', ' ', 'g'))
$$;

CREATE FUNCTION search_next.to_count(v text) RETURNS bigint
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN v ~ '^\s*[0-9][0-9 ]{0,10}\s*$' THEN replace(v, ' ', '')::bigint END
$$;

-- ---- Communes --------------------------------------------------------------------------

CREATE UNLOGGED TABLE search_next.commune_part AS
SELECT name, ST_Subdivide(ST_Transform(geom, 2154), 128) AS geom_m
FROM search_osm.communes;
CREATE INDEX ON search_next.commune_part USING gist (geom_m);
ANALYZE search_next.commune_part;

-- ---- Candidates ------------------------------------------------------------------------

-- Each object on one point: inside its area (its centre when the area is not valid), on its line.
CREATE UNLOGGED TABLE search_next.raw AS
WITH shaped AS (
    SELECT osm_type, osm_id, key, value, name, other_names, brand, housenumber, street, postcode,
           city, population, iata, wikidata,
           ST_Transform(geom, 2154) AS shape_m
    FROM search_osm.poi
)
SELECT osm_type, osm_id, key, value, name, search_next.fold(name) AS name_fold, other_names, brand,
       housenumber, street, postcode, city, population, iata, wikidata,
       CASE WHEN GeometryType(shape_m) IN ('POLYGON', 'MULTIPOLYGON') THEN ST_Area(shape_m) ELSE 0 END AS area_m2,
       CASE WHEN GeometryType(shape_m) IN ('POLYGON', 'MULTIPOLYGON') AND NOT ST_IsValid(shape_m)
            THEN ST_Centroid(shape_m) ELSE ST_PointOnSurface(shape_m) END AS geom_m
FROM shaped;
DELETE FROM search_next.raw WHERE name_fold = '';

-- The same place mapped twice: same kind and name, within 150 m.
CREATE UNLOGGED TABLE search_next.clustered AS
SELECT r.*, ST_ClusterDBSCAN(geom_m, 150, 1) OVER (PARTITION BY key, value, name_fold) AS cid
FROM search_next.raw r;

-- ---- Places ----------------------------------------------------------------------------

-- One row per place: on its largest area, else its best known object, with the address any of
-- its objects gives.
CREATE UNLOGGED TABLE search_next.merged AS
SELECT DISTINCT ON (key, value, name_fold, cid)
       osm_type, osm_id, key, value, name, name_fold,
       coalesce(other_names, max(other_names) OVER g) AS other_names,
       coalesce(brand, max(brand) OVER g) AS brand,
       coalesce(housenumber, max(housenumber) OVER g) AS housenumber,
       coalesce(street, max(street) OVER g) AS street,
       coalesce(postcode, max(postcode) OVER g) AS postcode,
       coalesce(city, max(city) OVER g) AS city_tag,
       search_next.to_count(coalesce(population, max(population) OVER g)) AS population,
       coalesce(iata, max(iata) OVER g) AS iata,
       bool_or(wikidata) OVER g AS wikidata,
       max(area_m2) OVER g AS area_m2,
       geom_m
FROM search_next.clustered
WINDOW g AS (PARTITION BY key, value, name_fold, cid)
ORDER BY key, value, name_fold, cid, area_m2 DESC, wikidata DESC, (housenumber IS NOT NULL) DESC, osm_type, osm_id;
CREATE INDEX ON search_next.merged USING gist (geom_m);
ANALYZE search_next.merged;

-- Its commune, from the boundaries (the mapped addr:city otherwise); its weight: what it is
-- worth for a driver who did not type exactly its name — a town, an airport, a notable place.
CREATE TABLE search_next.poi AS
SELECT row_number() OVER () AS id, m.osm_type, m.osm_id, m.key, m.value, m.name, m.name_fold,
       m.housenumber, m.street, m.postcode, coalesce(c.name, m.city_tag) AS city,
       search_next.fold(concat_ws(' ', m.name, m.other_names, m.brand, coalesce(c.name, m.city_tag), m.postcode)) AS doc,
       least(1.0,
           CASE
               WHEN m.key = 'place' THEN
                   CASE m.value WHEN 'city' THEN 0.9 WHEN 'town' THEN 0.8 WHEN 'village' THEN 0.6
                                WHEN 'suburb' THEN 0.55 WHEN 'borough' THEN 0.55 WHEN 'quarter' THEN 0.45
                                WHEN 'neighbourhood' THEN 0.4 WHEN 'island' THEN 0.45 WHEN 'hamlet' THEN 0.3
                                WHEN 'square' THEN 0.3 ELSE 0.25 END
                   + CASE WHEN m.population > 0 THEN least(0.1, ln(m.population) / 150) ELSE 0 END
               WHEN m.key = 'aeroway' AND m.value = 'aerodrome' THEN CASE WHEN m.iata IS NOT NULL THEN 1.0 ELSE 0.5 END
               WHEN m.key = 'aeroway' THEN 0.5
               WHEN m.key = 'railway' AND m.value = 'station' THEN 0.6
               ELSE 0.3
           END
           + CASE WHEN m.wikidata THEN 0.15 ELSE 0 END
           + CASE WHEN m.area_m2 > 1000 THEN least(0.2, ln(m.area_m2 / 1000) / 35) ELSE 0 END
       )::real AS weight,
       ST_Y(ST_Transform(m.geom_m, 4326)) AS lat,
       ST_X(ST_Transform(m.geom_m, 4326)) AS lon,
       m.geom_m
FROM search_next.merged m
LEFT JOIN LATERAL (
    SELECT cp.name FROM search_next.commune_part cp WHERE ST_Intersects(cp.geom_m, m.geom_m) LIMIT 1
) c ON true;

ALTER TABLE search_next.poi ADD PRIMARY KEY (id);
CREATE INDEX poi_doc ON search_next.poi USING gin (doc gin_trgm_ops);
-- The notable places, searched all over France whoever is looking.
CREATE INDEX poi_notable_doc ON search_next.poi USING gin (doc gin_trgm_ops) WHERE weight >= 0.6;
CREATE INDEX poi_geom ON search_next.poi USING gist (geom_m);
ANALYZE search_next.poi;

DROP TABLE search_next.commune_part, search_next.raw, search_next.clustered, search_next.merged;

-- ---- Checks and meta -------------------------------------------------------------------

DO $$
DECLARE
    n bigint;
    towns bigint;
BEGIN
    SELECT count(*), count(*) FILTER (WHERE key = 'place' AND value IN ('city', 'town', 'village'))
      INTO n, towns FROM search_next.poi;
    -- A France extract holds millions of named places and some 30 000 villages and towns: far
    -- fewer is a broken download or import, and the published index stays.
    IF n < 500000 OR towns < 20000 THEN
        RAISE EXCEPTION 'search index too small: % places, % towns', n, towns;
    END IF;
END $$;

CREATE TABLE search_next.meta AS
SELECT now() AS built_at, (SELECT count(*) FROM search_next.poi) AS places;
