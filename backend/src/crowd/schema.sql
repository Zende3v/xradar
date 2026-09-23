-- EONA crowd data in PostGIS: drivers' reports and speed-limit changes. The weekly
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
    side text,
    -- "Embouteillage" only: light, heavy or standstill, as the driver saw it.
    severity text
);
-- Added after the table existed: an older database gets the column here.
ALTER TABLE crowd.report ADD COLUMN IF NOT EXISTS severity text;

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

-- ---- Bug reports ("Signaler un bug") ------------------------------------------------------

-- What the driver saw, a category, and the app's own details (platform, version, system,
-- model). The author is the account (a guest's too), never asked again; NULL once deleted.
CREATE TABLE IF NOT EXISTS crowd.bug_report (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- new, then progress, then resolved.
    status text NOT NULL DEFAULT 'new',
    category text NOT NULL,
    description text NOT NULL,
    steps text,
    account_id text,
    platform text,
    app_version text,
    os_version text,
    device_model text
);
CREATE INDEX IF NOT EXISTS bug_report_recent ON crowd.bug_report (status, created_at DESC);
CREATE INDEX IF NOT EXISTS bug_report_author ON crowd.bug_report (account_id, created_at);

-- ---- Presence and positions ---------------------------------------------------------------

-- Where a driver was, each time the app said so (about every 30 s). Written only for a driver
-- who turned "Ma présence et ma position" on, kept a few weeks, then purged. The live view is
-- simply the last row of each account within crowd's TTL.
CREATE TABLE IF NOT EXISTS crowd.position (
    id bigserial PRIMARY KEY,
    account_id text NOT NULL,
    at timestamptz NOT NULL DEFAULT now(),
    geom geometry(Point, 4326) NOT NULL,
    speed_kmh smallint,
    in_trip boolean NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS position_account ON crowd.position (account_id, at DESC);
CREATE INDEX IF NOT EXISTS position_recent ON crowd.position (at DESC);

-- ---- Signalisation corrected by hand ("mapper") --------------------------------------------

-- What the team fixed on top of OpenStreetMap: a sign OSM does not have (add), a sign that is
-- wrong (edit), a sign that is not there (hide). These rows are the truth and never depend on a
-- build: the weekly rebuild replays them onto the fresh signalisation (signalisation/edits.sql),
-- and a failed rebuild changes nothing here. [was] is what the sign looked like when the
-- correction was made, to find it again when its id changes; when it cannot be found with
-- certainty the row is kept, marked "conflict", and nothing is applied by itself.
CREATE TABLE IF NOT EXISTS crowd.sign_edit (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- add, edit or hide.
    op text NOT NULL,
    -- The sign in signs.sign this row is about; for an "add", the id it was given.
    target_id text NOT NULL,
    kind text,
    value smallint,
    course real,
    geom geometry(Point, 4326),
    was jsonb NOT NULL DEFAULT '{}',
    author_id text,
    note text,
    -- applied (on the live signalisation), conflict (sign not found again), reverted (undone).
    status text NOT NULL DEFAULT 'applied',
    conflict text
);
CREATE INDEX IF NOT EXISTS sign_edit_status ON crowd.sign_edit (status, updated_at DESC);
CREATE INDEX IF NOT EXISTS sign_edit_target ON crowd.sign_edit (target_id);
CREATE INDEX IF NOT EXISTS sign_edit_geom ON crowd.sign_edit USING gist (geom);

-- What the admins did, and who did it: an account deleted, a role changed, a report removed.
-- One line per action, kept config.adminAuditDays, then purged. The detail says what changed,
-- never an email or a password.
CREATE TABLE IF NOT EXISTS crowd.admin_action (
    id bigserial PRIMARY KEY,
    at timestamptz NOT NULL DEFAULT now(),
    -- The admin account, or null for the ADMIN_TOKEN (scripts).
    actor_id text,
    actor_name text NOT NULL,
    action text NOT NULL,
    target_type text NOT NULL,
    target_id text,
    detail jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS admin_action_at ON crowd.admin_action (at DESC);
CREATE INDEX IF NOT EXISTS admin_action_target ON crowd.admin_action (target_type, target_id);
