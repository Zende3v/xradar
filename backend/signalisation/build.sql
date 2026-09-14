-- x_radar signalisation v2, step 2 (after import.sh): from the raw import (schema osm) to the
-- tables the backend publishes, built in schema signs_next and swapped in by rebuild.sh.
--
--   signs_next.road   every car road, with its limit in each direction
--   signs_next.sign   every sign once: on its road, oriented, deduplicated, with a stable id
--
-- Distances are metric in Lambert-93 (EPSG:2154): the extract is metropolitan France.
-- Run as xradar:  psql -d xradar -f build.sql

\set ON_ERROR_STOP on
SET client_min_messages = warning;
\timing on

DROP SCHEMA IF EXISTS signs_next CASCADE;
CREATE SCHEMA signs_next;

CREATE INDEX IF NOT EXISTS way_signs_node ON osm.way_signs (node_id);
ANALYZE osm.way_signs;
ANALYZE osm.sign_nodes;

-- ---- Helpers ---------------------------------------------------------------------------

-- A course brought back to 0..360.
CREATE FUNCTION signs_next.norm(x double precision) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT x - 360 * floor(x / 360) $$;

-- Smallest angle between two courses, 0..180.
CREATE FUNCTION signs_next.angle_diff(a double precision, b double precision) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT abs((a - b) - 360 * floor((a - b + 180) / 360)) $$;

-- True course (0 = north) of a Lambert-93 line at a fraction of its length, over ±3 m.
CREATE FUNCTION signs_next.course_at(line geometry, frac double precision) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT degrees(ST_Azimuth(
        ST_Transform(ST_LineInterpolatePoint(line, greatest(0, frac - d)), 4326)::geography,
        ST_Transform(ST_LineInterpolatePoint(line, least(1, frac + d)), 4326)::geography))
    FROM (SELECT least(0.5, 3.0 / greatest(ST_Length(line), 0.01)) AS d) s
$$;

-- direction=N, NE, SSW…
CREATE FUNCTION signs_next.cardinal(d text) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT (ARRAY[0, 22.5, 45, 67.5, 90, 112.5, 135, 157.5, 180, 202.5, 225, 247.5, 270, 292.5, 315, 337.5])[
        array_position(ARRAY['N','NNE','NE','ENE','E','ESE','SE','SSE','S','SSW','SW','WSW','W','WNW','NW','NNW'], upper(d))]::double precision
$$;

-- ---- Roads -----------------------------------------------------------------------------

CREATE TABLE signs_next.road AS
SELECT way_id, highway, name, ref, oneway, roundabout, maxspeed_fwd, maxspeed_bwd,
       geom, ST_Transform(geom, 2154) AS geom_m
FROM osm.roads;
ALTER TABLE signs_next.road ADD PRIMARY KEY (way_id);
CREATE INDEX road_geom_m ON signs_next.road USING gist (geom_m);
CREATE INDEX road_geom ON signs_next.road USING gist (geom);
ANALYZE signs_next.road;

-- ---- Signs on their road ---------------------------------------------------------------

-- Every sign node on the road it belongs to: exactly when it is one of the road's nodes,
-- else the nearest car road within 20 m (a post beside the carriageway). Off-road ones go.
CREATE UNLOGGED TABLE signs_next.candidate AS
WITH node AS (
    SELECT node_id, kind, value, direction, ST_Transform(geom, 2154) AS geom_m FROM osm.sign_nodes
),
attached AS (
    SELECT DISTINCT n.node_id, n.kind, n.value, n.direction, n.geom_m, ws.way_id, true AS on_way
    FROM node n
    JOIN osm.way_signs ws USING (node_id)
    JOIN signs_next.road r ON r.way_id = ws.way_id
    UNION ALL
    SELECT n.node_id, n.kind, n.value, n.direction, n.geom_m, near.way_id, false AS on_way
    FROM node n
    CROSS JOIN LATERAL (
        SELECT r.way_id FROM signs_next.road r
        WHERE ST_DWithin(r.geom_m, n.geom_m, 20)
        ORDER BY r.geom_m <-> n.geom_m
        LIMIT 1
    ) near
    WHERE NOT EXISTS (SELECT 1 FROM osm.way_signs ws WHERE ws.node_id = n.node_id)
)
SELECT row_number() OVER () AS cid,
       a.node_id, a.kind, a.value, a.direction, a.on_way,
       (SELECT count(DISTINCT ws.way_id) FROM osm.way_signs ws WHERE ws.node_id = a.node_id) AS ways_here,
       a.way_id, r.oneway,
       ST_ClosestPoint(r.geom_m, a.geom_m) AS snap_m,
       ST_LineLocatePoint(r.geom_m, a.geom_m) AS frac,
       ST_Length(r.geom_m) AS road_len,
       signs_next.course_at(r.geom_m, ST_LineLocatePoint(r.geom_m, a.geom_m)) AS road_course
FROM attached a
JOIN signs_next.road r USING (way_id);

-- The course of the traffic each sign is for (null = both ways).
ALTER TABLE signs_next.candidate ADD COLUMN course double precision;
UPDATE signs_next.candidate SET course = CASE
    -- Orientation stated on the node: along or against its road, or where it faces.
    WHEN on_way AND ways_here = 1 AND direction = 'forward' THEN road_course
    WHEN on_way AND ways_here = 1 AND direction = 'backward' THEN signs_next.norm(road_course + 180)
    WHEN direction ~ '^[0-9]+(\.[0-9]+)?$' THEN signs_next.norm(direction::double precision + 180)
    WHEN signs_next.cardinal(direction) IS NOT NULL THEN signs_next.norm(signs_next.cardinal(direction) + 180)
    -- A no-entry sign faces the traffic it stops: the wrong way up a one-way street.
    WHEN kind = 'no_entry' AND oneway = 1 THEN signs_next.norm(road_course + 180)
    WHEN kind = 'no_entry' AND oneway = -1 THEN road_course
    -- On a one-way street, every sign is for its traffic.
    WHEN oneway = 1 THEN road_course
    WHEN oneway = -1 THEN signs_next.norm(road_course + 180)
    ELSE NULL
END
WHERE kind NOT IN ('crossing', 'level_crossing', 'roundabout');

-- A stop, a give-way or a traffic light nobody oriented is for the traffic heading to the
-- nearest junction along its road; right at the junction it is for everyone.
WITH junction AS (
    SELECT c.cid, j.frac AS junction_frac
    FROM signs_next.candidate c
    JOIN signs_next.road r ON r.way_id = c.way_id
    CROSS JOIN LATERAL (
        SELECT ST_LineLocatePoint(r.geom_m, p.geom) AS frac
        FROM signs_next.road o
        CROSS JOIN LATERAL ST_Dump(ST_Intersection(r.geom_m, o.geom_m)) p
        WHERE o.way_id <> r.way_id
          AND ST_DWithin(o.geom_m, c.snap_m, 80)
          AND GeometryType(p.geom) = 'POINT'
        ORDER BY abs(ST_LineLocatePoint(r.geom_m, p.geom) - c.frac)
        LIMIT 1
    ) j
    WHERE c.course IS NULL AND c.oneway = 0 AND c.ways_here <= 1
      AND c.kind IN ('stop', 'give_way', 'traffic_signals')
)
UPDATE signs_next.candidate c
SET course = CASE
    WHEN abs(j.junction_frac - c.frac) * c.road_len < 2 THEN NULL
    WHEN j.junction_frac > c.frac THEN c.road_course
    ELSE signs_next.norm(c.road_course + 180)
END
FROM junction j
WHERE j.cid = c.cid;

-- Roundabouts from the roads themselves: one per ring of junction=roundabout ways (rings
-- longer than 800 m are circular roads, not roundabouts).
INSERT INTO signs_next.candidate (cid, node_id, kind, value, direction, on_way, ways_here, way_id, oneway, snap_m, frac, road_len, road_course, course)
SELECT -row_number() OVER (), NULL, 'roundabout', NULL, NULL, false, 0, min(way_id), 1,
       ST_Centroid(ST_Collect(geom_m)), NULL, NULL, NULL, NULL
FROM (
    SELECT way_id, geom_m, ST_ClusterDBSCAN(geom_m, 0.5, 1) OVER () AS ring
    FROM signs_next.road WHERE roundabout
) rings
GROUP BY ring
HAVING sum(ST_Length(geom_m)) <= 800;

ANALYZE signs_next.candidate;

-- ---- Deduplication ---------------------------------------------------------------------

-- Same kind (and value) within a distance of its own: one place, never wider than max_span.
-- Signs for the traffic of one way stay apart from those for another (a stop on each approach
-- of a junction).
CREATE UNLOGGED TABLE signs_next.rule (kind text PRIMARY KEY, eps double precision, max_span double precision, by_course boolean);
INSERT INTO signs_next.rule VALUES
    ('traffic_signals', 25, 80, false),
    ('crossing', 12, 20, false),
    ('level_crossing', 30, 50, false),
    ('roundabout', 30, 60, false),
    ('stop', 20, 30, true),
    ('give_way', 20, 30, true),
    ('no_entry', 15, 30, true),
    ('speed_sign', 30, 50, true);

CREATE UNLOGGED TABLE signs_next.clustered AS
SELECT c.*, 0 AS place, 0 AS subplace FROM signs_next.candidate c WITH NO DATA;

DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT kind, eps FROM signs_next.rule LOOP
        INSERT INTO signs_next.clustered
        SELECT c.*, ST_ClusterDBSCAN(c.snap_m, r.eps, 1) OVER (PARTITION BY c.value), 0
        FROM signs_next.candidate c
        WHERE c.kind = r.kind;
    END LOOP;
END $$;

-- Nearby signs chain up (a row of stops across a car park, crossings all along a street):
-- a place wider than its kind allows is split into parts about half that size.
WITH span AS (
    SELECT kind, value, place, count(*) AS n, ST_Length(ST_BoundingDiagonal(ST_Collect(snap_m))) AS span
    FROM signs_next.clustered
    GROUP BY kind, value, place
),
too_wide AS (
    SELECT s.kind, s.value, s.place, least(s.n, ceil(s.span / (r.max_span / 2)))::int AS parts
    FROM span s JOIN signs_next.rule r USING (kind)
    WHERE s.span > r.max_span
),
split AS (
    SELECT c.cid, ST_ClusterKMeans(c.snap_m, t.parts) OVER (PARTITION BY c.kind, c.value, c.place) AS part
    FROM signs_next.clustered c
    JOIN too_wide t ON t.kind = c.kind AND t.value IS NOT DISTINCT FROM c.value AND t.place = c.place
)
UPDATE signs_next.clustered c SET subplace = split.part FROM split WHERE split.cid = c.cid;

-- Within one place, courses more than 45° apart are different signs (wrapping at north).
CREATE UNLOGGED TABLE signs_next.grouped AS
WITH broken AS (
    SELECT c.*, rule.by_course,
           CASE WHEN rule.by_course AND c.course - lag(c.course) OVER w > 45 THEN 1 ELSE 0 END AS starts_group
    FROM signs_next.clustered c
    JOIN signs_next.rule rule USING (kind)
    WINDOW w AS (PARTITION BY c.kind, c.value, c.place, c.subplace ORDER BY c.course NULLS FIRST)
),
numbered AS (
    SELECT b.*,
           sum(starts_group) OVER (PARTITION BY kind, value, place, subplace ORDER BY course NULLS FIRST ROWS UNBOUNDED PRECEDING) AS grp,
           min(course) OVER (PARTITION BY kind, value, place, subplace) AS first_course,
           max(course) OVER (PARTITION BY kind, value, place, subplace) AS last_course
    FROM broken b
)
SELECT n.*,
       CASE
           WHEN by_course AND grp > 0 AND first_course + 360 - last_course <= 45
                AND grp = max(grp) OVER (PARTITION BY kind, value, place, subplace) THEN 0
           ELSE grp
       END AS sign_group
FROM numbered n;

-- One row per sign: on its road near the middle of its sources, with the mean course of
-- those that had one, and an id that stays the same from one build to the next.
CREATE TABLE signs_next.sign AS
WITH g AS (
    SELECT kind, value, place, subplace, sign_group,
           count(*)::int AS sources,
           array_agg(DISTINCT node_id) FILTER (WHERE node_id IS NOT NULL) AS node_ids,
           ST_Centroid(ST_Collect(snap_m)) AS centre_m,
           mode() WITHIN GROUP (ORDER BY way_id) AS way_id,
           CASE
               WHEN bool_or(by_course) AND count(course) > 0 THEN
                   signs_next.norm(degrees(atan2(avg(sin(radians(course))), avg(cos(radians(course))))))
           END AS course
    FROM signs_next.grouped
    GROUP BY kind, value, place, subplace, sign_group
),
placed AS (
    SELECT g.kind, g.value, g.course, g.way_id, g.sources, g.node_ids,
           CASE WHEN g.kind = 'roundabout' THEN g.centre_m ELSE ST_ClosestPoint(r.geom_m, g.centre_m) END AS geom_m
    FROM g JOIN signs_next.road r USING (way_id)
),
keyed AS (
    SELECT p.*,
           left(md5(p.kind || ':' || coalesce(p.value::text, '') || ':' ||
                    round(ST_X(p.geom_m) / 25) || ':' || round(ST_Y(p.geom_m) / 25) || ':' ||
                    coalesce((round(p.course / 45)::int % 8)::text, '*')), 16) AS base_id
    FROM placed p
)
SELECT base_id || CASE WHEN row_number() OVER w > 1 THEN '-' || row_number() OVER w ELSE '' END AS id,
       kind, value::smallint AS value, course::real AS course, way_id, sources, node_ids,
       ST_Transform(geom_m, 4326) AS geom, geom_m
FROM keyed
WINDOW w AS (PARTITION BY base_id ORDER BY node_ids);

ALTER TABLE signs_next.sign ADD PRIMARY KEY (id);
CREATE INDEX sign_geom_m ON signs_next.sign USING gist (geom_m);
CREATE INDEX sign_geom ON signs_next.sign USING gist (geom);
CREATE INDEX sign_way ON signs_next.sign (way_id);
ANALYZE signs_next.sign;

-- What was built, from which extract (health endpoint).
CREATE TABLE signs_next.meta AS
SELECT now() AS built_at,
       (SELECT value FROM osm.osm2pgsql_properties WHERE property = 'import_timestamp') AS osm_timestamp,
       (SELECT count(*) FROM signs_next.road) AS roads,
       (SELECT count(*) FROM signs_next.sign) AS signs;

-- ---- Report ----------------------------------------------------------------------------

SELECT c.kind, c.nodes AS osm_nodes, s.signs, s.oriented, round(100.0 * s.oriented / s.signs, 1) AS pct_oriented
FROM (SELECT kind, count(DISTINCT node_id) AS nodes FROM signs_next.candidate GROUP BY kind) c
JOIN (SELECT kind, count(*) AS signs, count(course) AS oriented FROM signs_next.sign GROUP BY kind) s USING (kind)
ORDER BY s.signs DESC;

DROP TABLE signs_next.grouped, signs_next.clustered, signs_next.candidate;
