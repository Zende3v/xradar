-- x_radar crowd data in PostGIS: drivers' reports and speed-limit changes. The weekly
-- signalisation rebuild never touches schema "crowd". Applied at every backend start, so every
-- statement is idempotent. Metric geometry in Lambert-93 (EPSG:2154), like schema "signs".

CREATE SCHEMA IF NOT EXISTS crowd;

-- Smallest angle between two courses, 0..180 (kept here: "signs" is rebuilt every week).
CREATE OR REPLACE FUNCTION crowd.angle_diff(a double precision, b double precision) RETURNS double precision
LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT abs((a - b) - 360 * floor((a - b + 180) / 360)) $$;

-- ---- Reports -------------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS crowd.report (
    id uuid PRIMARY KEY,
    type text NOT NULL,
    -- live, then expired (its time ran out), denied (the crowd said it is gone) or removed (admin).
    status text NOT NULL DEFAULT 'live',
    geom geometry(Point, 4326) NOT NULL,
    geom_m geometry(Point, 2154) NOT NULL,
    -- Course of the traffic concerned: the reporter's, turned round for the other carriageway.
    course real,
    direction text NOT NULL DEFAULT 'same',
    bearing real,
    -- The road it was attached to when reported (signs.road).
    way_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    last_reported_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    closed_at timestamptz,
    confirmations integer NOT NULL DEFAULT 0,
    contradictions integer NOT NULL DEFAULT 0,
    reporters integer NOT NULL DEFAULT 1,
    reporter_id text,
    reporter_role text NOT NULL DEFAULT 'guest',
    plate text,
    street text,
    side text
);
CREATE INDEX IF NOT EXISTS report_live_geom ON crowd.report USING gist (geom_m) WHERE status = 'live';
CREATE INDEX IF NOT EXISTS report_status ON crowd.report (status, expires_at);
CREATE INDEX IF NOT EXISTS report_closed ON crowd.report (closed_at) WHERE status <> 'live';

-- One voice per person per report: its author, a duplicate report merged into it, a
-- confirmation ("toujours là") or a denial ("plus là"). A new voice replaces the previous one.
CREATE TABLE IF NOT EXISTS crowd.report_voice (
    report_id uuid NOT NULL REFERENCES crowd.report (id) ON DELETE CASCADE,
    voter_id text NOT NULL,
    voice text NOT NULL,
    at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (report_id, voter_id)
);

-- ---- Speed-limit changes -------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS crowd.speed_limit_change (
    id uuid PRIMARY KEY,
    -- pending, then validated (applied) or expired / outdated / rejected; a validated one ends
    -- superseded (a later change on the same stretch) or removed (admin).
    status text NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    geom_m geometry(Point, 2154) NOT NULL,
    course real,
    old_kmh smallint,
    old_source text NOT NULL,
    new_kmh smallint,
    -- Where the new limit applies once validated: the stretches of road its supporters drove.
    zone_m geometry(Geometry, 2154),
    way_ids bigint[],
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    validated_at timestamptz,
    validated_by text,
    superseded_by uuid,
    closed_at timestamptz
);
CREATE INDEX IF NOT EXISTS speed_limit_change_zone ON crowd.speed_limit_change USING gist (zone_m) WHERE status = 'validated';
CREATE INDEX IF NOT EXISTS speed_limit_change_pending ON crowd.speed_limit_change USING gist (geom_m) WHERE status = 'pending';

-- One voice per person per change: the limit they proposed, where and when.
CREATE TABLE IF NOT EXISTS crowd.speed_limit_voice (
    change_id uuid NOT NULL REFERENCES crowd.speed_limit_change (id) ON DELETE CASCADE,
    reporter_id text NOT NULL,
    role text NOT NULL,
    geom geometry(Point, 4326) NOT NULL,
    geom_m geometry(Point, 2154) NOT NULL,
    course real,
    new_kmh smallint NOT NULL,
    displayed_kmh smallint,
    at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (change_id, reporter_id)
);
CREATE INDEX IF NOT EXISTS speed_limit_voice_geom ON crowd.speed_limit_voice USING gist (geom_m);

-- What happened to a change, in order: why a limit moved.
CREATE TABLE IF NOT EXISTS crowd.speed_limit_event (
    id bigserial PRIMARY KEY,
    change_id uuid NOT NULL REFERENCES crowd.speed_limit_change (id) ON DELETE CASCADE,
    at timestamptz NOT NULL DEFAULT now(),
    type text NOT NULL,
    detail jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS speed_limit_event_change ON crowd.speed_limit_event (change_id, at);
