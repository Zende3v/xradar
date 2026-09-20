-- EONA signalisation v2, step 3b: replay the corrections made by hand (crowd.sign_edit) onto
-- the fresh build, before it is published. The corrections are the truth: this script never
-- deletes one. A correction whose sign cannot be found again with certainty is left alone and
-- marked "conflict" — the console shows it, someone decides.
--
-- Runs on signs_next (the build about to be published). A failed rebuild stops before publish,
-- so the live signalisation and its corrections stay exactly as they are.

\set ON_ERROR_STOP on

BEGIN;

-- A sign is found again by what it was: same kind, same value, close by, facing the same way.
DO $$
DECLARE
    e record;
    match_id text;
    matches int;
BEGIN
    FOR e IN
        SELECT id, op, target_id, kind, value, course,
               ST_Y(geom) AS lat, ST_X(geom) AS lon, was
        FROM crowd.sign_edit
        WHERE status IN ('applied', 'conflict')
        ORDER BY created_at
    LOOP
        -- An added sign does not exist in OSM: it is simply put back, with the same id.
        IF e.op = 'add' THEN
            INSERT INTO signs_next.sign (id, kind, value, course, way_id, sources, node_ids, geom, geom_m)
            SELECT e.target_id, e.kind, e.value, e.course,
                   (SELECT r.way_id FROM signs_next.road r
                     ORDER BY r.geom_m <-> ST_Transform(ST_SetSRID(ST_MakePoint(e.lon, e.lat), 4326), 2154)
                     LIMIT 1),
                   1, NULL,
                   ST_SetSRID(ST_MakePoint(e.lon, e.lat), 4326),
                   ST_Transform(ST_SetSRID(ST_MakePoint(e.lon, e.lat), 4326), 2154)
            ON CONFLICT (id) DO UPDATE
              SET kind = EXCLUDED.kind, value = EXCLUDED.value, course = EXCLUDED.course,
                  geom = EXCLUDED.geom, geom_m = EXCLUDED.geom_m;
            UPDATE crowd.sign_edit SET status = 'applied', conflict = NULL WHERE id = e.id;
            CONTINUE;
        END IF;

        -- The id first: it is built from the sign itself, so it usually survives a rebuild.
        SELECT s.id INTO match_id FROM signs_next.sign s WHERE s.id = e.target_id;

        -- Otherwise the sign as it was: one candidate and only one, or it is a conflict.
        IF match_id IS NULL THEN
            SELECT count(*), min(s.id) INTO matches, match_id
            FROM signs_next.sign s
            WHERE s.kind = (e.was ->> 'kind')
              AND s.value IS NOT DISTINCT FROM (e.was ->> 'value')::smallint
              AND ST_DWithin(
                    s.geom_m,
                    ST_Transform(ST_SetSRID(ST_MakePoint((e.was ->> 'lon')::float8, (e.was ->> 'lat')::float8), 4326), 2154),
                    30)
              AND (
                    (s.course IS NULL AND (e.was ->> 'course') IS NULL)
                    OR abs(((s.course - (e.was ->> 'course')::real + 540)::int % 360) - 180) <= 45
                  );
            IF matches IS DISTINCT FROM 1 THEN
                UPDATE crowd.sign_edit
                SET status = 'conflict',
                    conflict = CASE WHEN coalesce(matches, 0) = 0
                                    THEN 'panneau introuvable après la reconstruction'
                                    ELSE 'plusieurs panneaux possibles' END
                WHERE id = e.id;
                CONTINUE;
            END IF;
            -- Found again under a new id: the correction follows it.
            UPDATE crowd.sign_edit SET target_id = match_id WHERE id = e.id;
        END IF;

        IF e.op = 'hide' THEN
            DELETE FROM signs_next.sign WHERE id = match_id;
        ELSE
            UPDATE signs_next.sign
            SET kind = e.kind, value = e.value, course = e.course,
                geom = CASE WHEN e.lat IS NULL THEN geom ELSE ST_SetSRID(ST_MakePoint(e.lon, e.lat), 4326) END,
                geom_m = CASE WHEN e.lat IS NULL THEN geom_m
                              ELSE ST_Transform(ST_SetSRID(ST_MakePoint(e.lon, e.lat), 4326), 2154) END
            WHERE id = match_id;
        END IF;
        UPDATE crowd.sign_edit SET status = 'applied', conflict = NULL WHERE id = e.id;
    END LOOP;
END $$;

COMMIT;

-- What was replayed, for the rebuild log.
SELECT status, count(*) AS corrections FROM crowd.sign_edit GROUP BY status ORDER BY status;
