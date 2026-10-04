-- EONA search, step 2 bis: places added by hand, absent from OpenStreetMap or wrongly placed there.
-- Applied by rebuild.sh after build.sql, on the new index (schema search_next), so they survive
-- every weekly rebuild. On the published index right away, as root:
--   runuser -u eona -- psql -qX -v ON_ERROR_STOP=1 -v schema=search -d eona -f extra.sql
-- osm_type 'X' marks them, osm_id is their number below (stable: the app's result id is
-- "osm:X<n>"). A line removed here leaves the index at the next run. Once OSM has the place
-- right, remove its line: the weekly rebuild then takes OSM's.

\set ON_ERROR_STOP on
\if :{?schema}
\else
    \set schema search_next
\endif

BEGIN;
DELETE FROM :"schema".poi WHERE osm_type = 'X';
INSERT INTO :"schema".poi (id, osm_type, osm_id, key, value, name, name_fold, housenumber, street, postcode, city, doc, weight, lat, lon, geom_m)
SELECT (SELECT coalesce(max(id), 0) FROM :"schema".poi) + e.n, 'X', e.n, e.key, e.value, e.name, :"schema".fold(e.name),
       e.housenumber, e.street, e.postcode, e.city,
       :"schema".fold(concat_ws(' ', e.name, e.aliases, e.city, e.postcode)), 0.3, e.lat, e.lon,
       ST_Transform(ST_SetSRID(ST_MakePoint(e.lon, e.lat), 4326), 2154)
FROM (VALUES
    -- 04/10 (Arthur) : OSM n'a que le centre commercial Orlydis (w143320617, operator=E.Leclerc).
    -- Position : celle d'Orlydis.
    (1, 'shop', 'supermarket', 'E.Leclerc', 'Leclerc Orlydis', '8', 'Place Gaston Viens', '94310', 'Orly', 48.74318::float8, 2.40728::float8),
    -- 04/10 (Arthur) : absent d'OSM (Fitness Park le plus proche : Thiais, 2,8 km).
    -- Position : point BAN de l'avenue, sans numéro, approchée.
    (2, 'leisure', 'fitness_centre', 'Fitness Park', 'Fitness Park Orly', NULL, 'Avenue des Martyrs de Châteaubriant', '94310', 'Orly', 48.748502::float8, 2.406903::float8)
) AS e(n, key, value, name, aliases, housenumber, street, postcode, city, lat, lon);
COMMIT;
