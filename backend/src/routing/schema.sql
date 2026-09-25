-- EONA routing measures in PostGIS (phase 1 of PLAN-VALHALLA.md): the route log and the bench.
-- Applied at every backend start, so every statement is idempotent; never a DROP. Nothing here
-- says where a driver goes: no account, no coordinates, except the bench's fixed test trips.

CREATE SCHEMA IF NOT EXISTS routing;

-- ---- Route log (routing/log.js) ------------------------------------------------------------

-- One line per /api/route answer to an account (cache hits and errors included) and per
-- /api/route/faster answer. Kept config.routeLogKeepDays days, then purged.
CREATE TABLE IF NOT EXISTS routing.route_log (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    -- route (/api/route) or faster (/api/route/faster).
    kind text NOT NULL,
    -- The engine of the route answered (ors, osrm…; for faster, of the detour); null without one.
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
