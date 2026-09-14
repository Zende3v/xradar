-- x_radar nearby services, step 2b (after build.sql): the places a driver looks for around them
-- — fuel, chargers, car parks, tobacconists, garages, hotels, cash machines — filtered,
-- deduplicated and published with the signs (schema signs_next, swapped in by publish.sql).
--
--   signs_next.place   one row per place: its kind, the OSM tags the search shows, its commune
--
-- The same place is often mapped twice (a station node inside the station area, a car park
-- node on its polygon, one charger per bay): objects of the same kind that close, under
-- compatible names, become one place, with the tags of all of them.
-- Run as xradar after build.sql:  psql -d xradar -f places.sql

\set ON_ERROR_STOP on
SET client_min_messages = warning;
\timing on

-- What an interrupted run left behind (build.sql is not re-run for a new try).
DROP TABLE IF EXISTS signs_next.place, signs_next.poi_pair, signs_next.poi, signs_next.commune_part;
DROP FUNCTION IF EXISTS signs_next.names_compatible, signs_next.name_keys, signs_next.to_int;
ALTER TABLE signs_next.meta DROP COLUMN IF EXISTS places;

-- ---- Helpers ---------------------------------------------------------------------------

CREATE FUNCTION signs_next.to_int(v text) RETURNS integer
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE WHEN v ~ '^\s*[0-9]{1,6}\s*$' THEN trim(v)::integer END
$$;

-- The names a place goes by, flattened to compare them: "Total Access" -> "totalaccess". For a
-- charger or a cash machine the operator is the name drivers know; for a shop or a hotel two
-- neighbours often share one (Accor), so only the name and brand count.
CREATE FUNCTION signs_next.name_keys(kind text, tags jsonb) RETURNS text[]
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT coalesce(array_agg(DISTINCT k) FILTER (WHERE length(k) >= 2), '{}')
    FROM (
        SELECT lower(regexp_replace(tags ->> key, '[^[:alnum:]]+', '', 'g')) AS k
        FROM unnest(CASE WHEN kind IN ('charging', 'atm')
                         THEN ARRAY['name', 'brand', 'operator', 'network']
                         ELSE ARRAY['name', 'brand'] END) AS key
    ) s
$$;

-- Two places may be one: one of them has no name, or a name matches or holds the other.
CREATE FUNCTION signs_next.names_compatible(a text[], b text[]) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT cardinality(a) = 0 OR cardinality(b) = 0 OR EXISTS (
        SELECT 1 FROM unnest(a) x, unnest(b) y
        WHERE x = y OR (length(x) >= 4 AND strpos(y, x) > 0) OR (length(y) >= 4 AND strpos(x, y) > 0)
    )
$$;

-- ---- Communes --------------------------------------------------------------------------

CREATE UNLOGGED TABLE signs_next.commune_part AS
SELECT name, ST_Subdivide(ST_Transform(geom, 2154), 128) AS geom_m
FROM osm.communes;
CREATE INDEX ON signs_next.commune_part USING gist (geom_m);
ANALYZE signs_next.commune_part;

-- ---- Candidates ------------------------------------------------------------------------

CREATE UNLOGGED TABLE signs_next.poi AS
WITH raw AS (
    SELECT osm_type, osm_id, kind, tags,
           ST_Transform(geom, 2154) AS shape_m,
           GeometryType(geom) IN ('POLYGON', 'MULTIPOLYGON') AS is_area,
           signs_next.to_int(tags ->> 'capacity') AS capacity
    FROM osm.places
)
SELECT row_number() OVER () AS pid, osm_type, osm_id, kind, tags, shape_m, is_area,
       CASE WHEN is_area THEN ST_Area(shape_m) END AS area_m2,
       (SELECT count(*) FROM jsonb_object_keys(tags))::int AS richness,
       signs_next.name_keys(kind, tags) AS keys
FROM raw
-- A kerbside strip or a few bays is not a car park anyone drives to.
WHERE NOT (kind = 'parking' AND tags ->> 'parking' = 'street_side'
           AND coalesce(capacity, 0) < 10 AND (NOT is_area OR ST_Area(shape_m) < 250))
  AND NOT (kind = 'parking' AND is_area AND ST_Area(shape_m) < 120 AND coalesce(capacity, 0) < 5)
  -- Nor is a bare car park point: no name, size, fee or access, nothing to judge it by.
  AND NOT (kind = 'parking' AND NOT is_area AND (tags - 'amenity' - 'parking') = '{}'::jsonb);

-- The primary key comes after the pairs: with it, the planner walks "b.pid > a.pid" through
-- the key for every object (quadratic) instead of the spatial index.
CREATE INDEX ON signs_next.poi USING gist (shape_m);
ANALYZE signs_next.poi;

-- ---- Deduplication ---------------------------------------------------------------------

-- Pairs of objects that are one place. Two car park polygons never are (neighbouring car
-- parks touch); a car park node is one with a polygon only when on it. Other areas join only
-- when they overlap.
CREATE UNLOGGED TABLE signs_next.poi_pair AS
SELECT a.pid AS a, b.pid AS b
FROM signs_next.poi a
JOIN signs_next.poi b
  ON b.kind = a.kind AND b.pid > a.pid
 AND ST_DWithin(a.shape_m, b.shape_m, CASE a.kind WHEN 'fuel' THEN 40 WHEN 'hotel' THEN 30 WHEN 'atm' THEN 10 ELSE 20 END)
WHERE NOT (a.is_area AND b.is_area AND (a.kind = 'parking' OR NOT ST_Intersects(a.shape_m, b.shape_m)))
  AND NOT (a.kind = 'parking' AND (a.is_area OR b.is_area) AND NOT ST_DWithin(a.shape_m, b.shape_m, 5))
  -- Two stations a few metres apart are one, whatever their names (a rebranding mapped twice).
  AND (signs_next.names_compatible(a.keys, b.keys) OR (a.kind = 'fuel' AND ST_DWithin(a.shape_m, b.shape_m, 12)));

-- Connected pairs are one place: every object takes the smallest id it is linked to.
ALTER TABLE signs_next.poi ADD PRIMARY KEY (pid);
ALTER TABLE signs_next.poi ADD COLUMN grp bigint;
UPDATE signs_next.poi SET grp = pid;
CREATE INDEX ON signs_next.poi_pair (a);
CREATE INDEX ON signs_next.poi_pair (b);
DO $$
BEGIN
    LOOP
        WITH link AS (
            SELECT a AS pid, b AS other FROM signs_next.poi_pair
            UNION ALL
            SELECT b, a FROM signs_next.poi_pair
        ),
        lowest AS (
            SELECT l.pid, min(o.grp) AS grp
            FROM link l JOIN signs_next.poi o ON o.pid = l.other
            GROUP BY l.pid
        )
        UPDATE signs_next.poi p SET grp = lowest.grp
        FROM lowest
        WHERE p.pid = lowest.pid AND lowest.grp < p.grp;
        EXIT WHEN NOT FOUND;
    END LOOP;
END $$;

-- ---- Places ----------------------------------------------------------------------------

-- One row per place: placed on its largest area (else its richest node), with the tags of all
-- its objects — the richest object wins where they disagree — and its commune.
CREATE TABLE signs_next.place AS
WITH member AS (
    SELECT p.*,
           row_number() OVER (PARTITION BY grp ORDER BY is_area DESC, area_m2 DESC NULLS LAST, richness DESC, osm_type, osm_id) AS pos_rank,
           row_number() OVER (PARTITION BY grp ORDER BY richness DESC, is_area DESC, osm_type, osm_id) AS tag_rank,
           count(*) OVER (PARTITION BY grp) AS sources
    FROM signs_next.poi p
),
merged AS (
    SELECT m.grp, jsonb_object_agg(e.key, e.value ORDER BY m.tag_rank DESC) AS tags
    FROM member m CROSS JOIN LATERAL jsonb_each(m.tags) e
    GROUP BY m.grp
),
head AS (
    SELECT grp, kind, osm_type, osm_id, sources, area_m2,
           CASE WHEN is_area THEN ST_PointOnSurface(shape_m)
                WHEN GeometryType(shape_m) = 'POINT' THEN shape_m
                ELSE ST_LineInterpolatePoint(shape_m, 0.5) END AS geom_m
    FROM member
    WHERE pos_rank = 1
)
SELECT CASE h.osm_type WHEN 'N' THEN 'node/' WHEN 'W' THEN 'way/' ELSE 'relation/' END || h.osm_id AS id,
       h.kind, coalesce(m.tags, '{}') AS tags, h.sources::smallint AS sources, h.area_m2::real AS area_m2,
       c.name AS commune,
       ST_Transform(h.geom_m, 4326) AS geom, h.geom_m
FROM head h
LEFT JOIN merged m USING (grp)
LEFT JOIN LATERAL (
    SELECT cp.name FROM signs_next.commune_part cp WHERE ST_Intersects(cp.geom_m, h.geom_m) LIMIT 1
) c ON true;

ALTER TABLE signs_next.place ADD PRIMARY KEY (id);
-- One index per kind: every search is for one kind, nearest first.
DO $$
DECLARE k text;
BEGIN
    FOREACH k IN ARRAY ARRAY['fuel', 'charging', 'parking', 'tobacco', 'garage', 'hotel', 'atm'] LOOP
        EXECUTE format('CREATE INDEX place_%s_geom_m ON signs_next.place USING gist (geom_m) WHERE kind = %L', k, k);
    END LOOP;
END $$;
ANALYZE signs_next.place;

ALTER TABLE signs_next.meta ADD COLUMN places bigint;
UPDATE signs_next.meta SET places = (SELECT count(*) FROM signs_next.place);

-- ---- Report ----------------------------------------------------------------------------

SELECT kind, count(*) AS places, sum(sources) AS osm_objects,
       count(*) FILTER (WHERE tags ? 'opening_hours') AS with_hours,
       count(*) FILTER (WHERE commune IS NOT NULL) AS with_commune
FROM signs_next.place
GROUP BY kind
ORDER BY places DESC;

DROP TABLE signs_next.poi_pair, signs_next.poi, signs_next.commune_part;
DROP FUNCTION signs_next.names_compatible, signs_next.name_keys, signs_next.to_int;
