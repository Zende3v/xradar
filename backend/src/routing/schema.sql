-- EONA routing measures in PostGIS (phases 1 and 2 of PLAN-VALHALLA.md): the route log, the bench
-- and the shadow mode.
-- Applied at every backend start, so every statement is idempotent; never a DROP. Nothing here
-- says where a driver goes: no account, no coordinates, except the bench's fixed test trips and
-- the shadow's lines of admin accounts (30 days).

CREATE SCHEMA IF NOT EXISTS routing;

-- ---- Route log (routing/log.js) ------------------------------------------------------------

-- One line per /api/route answer to an account (cache hits and errors included) and per
-- /api/route/faster answer. Kept config.routeLogKeepDays days, then purged.
CREATE TABLE IF NOT EXISTS routing.route_log (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    -- route (/api/route) or faster (/api/route/faster).
    kind text NOT NULL,
    -- The engine of the route answered (ors, valhalla; osrm before phase 2; for faster, of the
    -- detour); null without one.
    engine text,
    status integer,
    latency_ms integer,
    -- A new trip (tripCheck.isNew) or a recalculation; null for faster and before the check.
    is_new boolean,
    -- Handed back from the one-minute cache (guard.js); null for faster and before the lookup.
    cached boolean,
    distance_m integer,
    duration_s integer,
    steps integer,
    -- A U-turn among the route's first 2 manoeuvres.
    uturn_start boolean,
    avoid text[],
    -- The refusal or the engine's error; for faster, why there is no detour (its reason).
    error text
);
CREATE INDEX IF NOT EXISTS route_log_at ON routing.route_log (at);

-- ---- Bench (routing/bench.js) --------------------------------------------------------------

-- Where the rotation over bench/trajets.json stands: the next trip to measure.
CREATE TABLE IF NOT EXISTS routing.bench_state (
    name text PRIMARY KEY,
    next_trip integer NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- One bench trip measured: our route (its engine and time, TomTom's time for it with traffic)
-- against TomTom's own best route, and the km each one drives per OSM road class
-- (signs.road.highway; "none" = no car road within reach, a ferry for instance).
CREATE TABLE IF NOT EXISTS routing.bench_run (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    -- matin, midi, soir or nuit; null for a run started by hand.
    slot text,
    trip_id text NOT NULL,
    engine text,
    map_version text,
    -- Everything measured; otherwise [error] says what is missing.
    ok boolean NOT NULL,
    error text,
    latency_ms integer,
    our_distance_m integer,
    our_duration_s integer,
    our_tomtom_s integer,
    best_distance_m integer,
    best_tomtom_s integer,
    our_km_by_class jsonb,
    best_km_by_class jsonb,
    uturn_start boolean,
    steps integer,
    avoid text[],
    from_lat double precision,
    from_lon double precision,
    to_lat double precision,
    to_lon double precision
);
CREATE INDEX IF NOT EXISTS bench_run_at ON routing.bench_run (at DESC);
CREATE INDEX IF NOT EXISTS bench_run_trip ON routing.bench_run (trip_id, at DESC);
-- Phase 2 (27/09/2026): the same trip through Valhalla, measured beside the engine the apps use.
-- Null on older rows or with Valhalla off.
ALTER TABLE routing.bench_run
    ADD COLUMN IF NOT EXISTS valhalla_map_version text,
    ADD COLUMN IF NOT EXISTS valhalla_error text,
    ADD COLUMN IF NOT EXISTS valhalla_latency_ms integer,
    ADD COLUMN IF NOT EXISTS valhalla_distance_m integer,
    ADD COLUMN IF NOT EXISTS valhalla_duration_s integer,
    ADD COLUMN IF NOT EXISTS valhalla_tomtom_s integer,
    ADD COLUMN IF NOT EXISTS valhalla_km_by_class jsonb,
    ADD COLUMN IF NOT EXISTS valhalla_uturn_start boolean,
    ADD COLUMN IF NOT EXISTS valhalla_steps integer;

-- ---- Shadow mode (routing/shadow.js, phase 2, D7.1) ----------------------------------------

-- One comparison of both engines: a computed /api/route answer (kind route) or a /faster check
-- that asked an engine (kind faster). Measures only: no account, no coordinates. Per engine
-- (ors_*, valhalla_*): asked and answered (ok: null = not asked, error then says why), the error
-- code, latency, distance, duration, steps, a U-turn among the first 2. Kept
-- config.shadowKeepDays, then purged.
CREATE TABLE IF NOT EXISTS routing.shadow_run (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    kind text NOT NULL,
    -- routingEngine then: ors, admins or all.
    mode text,
    -- The engine whose answer the app got; null when none could.
    served text,
    -- ORS served (or tried) a route Valhalla should have served, and why (cause).
    fallback boolean NOT NULL DEFAULT false,
    cause text,
    avoid text[],
    ors_ok boolean,
    ors_error text,
    ors_latency_ms integer,
    ors_distance_m integer,
    ors_duration_s integer,
    ors_steps integer,
    ors_uturn_start boolean,
    valhalla_ok boolean,
    valhalla_error text,
    valhalla_latency_ms integer,
    valhalla_distance_m integer,
    valhalla_duration_s integer,
    valhalla_steps integer,
    valhalla_uturn_start boolean,
    -- Common road of both routes: ORS's share on Valhalla's, Valhalla's share on ORS's (0 to 1).
    share_ors real,
    share_valhalla real,
    -- Past D7.1's gaps (duration, common road; for faster, the detour's).
    divergent boolean,
    -- faster: routes drawn, candidates and viable per engine, the detour's common road.
    detail jsonb
);
CREATE INDEX IF NOT EXISTS shadow_run_at ON routing.shadow_run (at);

-- Both lines of a divergent comparison, for admin accounts only (the team), to read on a map.
-- Kept config.shadowTraceKeepDays, then purged.
CREATE TABLE IF NOT EXISTS routing.shadow_trace (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    run_id bigint NOT NULL REFERENCES routing.shadow_run (id) ON DELETE CASCADE,
    kind text NOT NULL,
    ors_geom geometry(LineString, 4326),
    valhalla_geom geometry(LineString, 4326)
);
CREATE INDEX IF NOT EXISTS shadow_trace_at ON routing.shadow_trace (at);
CREATE INDEX IF NOT EXISTS shadow_trace_run ON routing.shadow_trace (run_id);
