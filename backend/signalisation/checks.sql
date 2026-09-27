-- EONA signalisation v2, step 3: refuse to publish a build that looks broken. Any exception
-- stops rebuild.sh, and the published schema "signs" stays as it is.

\set ON_ERROR_STOP on

DO $$
DECLARE
    new_roads bigint;
    old_roads bigint;
    changed record;
    old_nodes bigint;
    new_nodes bigint;
BEGIN
    -- Absolute floors, for the very first build (metropolitan France has ~5.8 M car roads).
    SELECT count(*) INTO new_roads FROM signs_next.road;
    IF new_roads < 4000000 THEN
        RAISE EXCEPTION 'only % roads: the import is incomplete', new_roads;
    END IF;
    IF (SELECT count(*) FROM signs_next.sign WHERE kind = 'stop') < 200000 THEN
        RAISE EXCEPTION 'too few stops: the build is incomplete';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'signs') THEN
        RETURN;
    END IF;

    -- Against the published version: OSM moves slowly, a big jump means a broken build.
    SELECT count(*) INTO old_roads FROM signs.road;
    IF new_roads < old_roads * 0.95 THEN
        RAISE EXCEPTION 'roads dropped from % to %', old_roads, new_roads;
    END IF;
    -- A kind whose rule changed since the published build is grouped differently: its signs may
    -- move by design, the OSM nodes behind them must not.
    FOR changed IN
        SELECT o.kind, o.n AS old_n, coalesce(n.n, 0) AS new_n,
               (SELECT jsonb_strip_nulls(to_jsonb(r)) FROM signs.rule r WHERE r.kind = o.kind)
                   IS DISTINCT FROM (SELECT jsonb_strip_nulls(to_jsonb(r)) FROM signs_next.rule r WHERE r.kind = o.kind)
                   AS regrouped
        FROM (SELECT kind, count(*) AS n FROM signs.sign GROUP BY kind) o
        LEFT JOIN (SELECT kind, count(*) AS n FROM signs_next.sign GROUP BY kind) n USING (kind)
    LOOP
        IF changed.regrouped THEN
            SELECT count(DISTINCT n.node_id) INTO old_nodes
            FROM signs.sign s, unnest(s.node_ids) AS n(node_id) WHERE s.kind = changed.kind;
            SELECT count(DISTINCT n.node_id) INTO new_nodes
            FROM signs_next.sign s, unnest(s.node_ids) AS n(node_id) WHERE s.kind = changed.kind;
            IF old_nodes >= 1000 AND abs(new_nodes - old_nodes) > old_nodes * 0.15 THEN
                RAISE EXCEPTION '% changed from % to % OSM nodes', changed.kind, old_nodes, new_nodes;
            END IF;
        ELSIF changed.old_n >= 1000 AND abs(changed.new_n - changed.old_n) > changed.old_n * 0.15 THEN
            RAISE EXCEPTION '% changed from % to %', changed.kind, changed.old_n, changed.new_n;
        END IF;
    END LOOP;
END $$;

-- Nearby services: about 80 % of the first build (2026-09) per kind, then the same ±15 %
-- against the published version once it has them.
DO $$
DECLARE
    floor_n record;
    changed record;
BEGIN
    FOR floor_n IN
        SELECT f.kind, f.n AS floor, coalesce(p.n, 0) AS n
        FROM (VALUES ('parking', 240000), ('garage', 16000), ('hotel', 15000), ('atm', 14500),
                     ('charging', 14000), ('fuel', 10000), ('tobacco', 6500)) f(kind, n)
        LEFT JOIN (SELECT kind, count(*) AS n FROM signs_next.place GROUP BY kind) p USING (kind)
    LOOP
        IF floor_n.n < floor_n.floor THEN
            RAISE EXCEPTION 'only % places of kind %: the import is incomplete', floor_n.n, floor_n.kind;
        END IF;
    END LOOP;

    IF to_regclass('signs.place') IS NULL THEN
        RETURN;
    END IF;
    FOR changed IN
        SELECT o.kind, o.n AS old_n, coalesce(n.n, 0) AS new_n
        FROM (SELECT kind, count(*) AS n FROM signs.place GROUP BY kind) o
        LEFT JOIN (SELECT kind, count(*) AS n FROM signs_next.place GROUP BY kind) n USING (kind)
    LOOP
        IF abs(changed.new_n - changed.old_n) > changed.old_n * 0.15 THEN
            RAISE EXCEPTION 'places of kind % changed from % to %', changed.kind, changed.old_n, changed.new_n;
        END IF;
    END LOOP;
END $$;
