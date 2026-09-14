-- x_radar signalisation v2 — osm2pgsql flex style (osm2pgsql 2.x).
--
-- Keeps what a driver needs from OpenStreetMap: the roads a car can take, with their limit
-- in each direction, and the sign nodes on or beside them. Everything lands in schema "osm";
-- build.sql turns it into the published tables (snapped, oriented, deduplicated).

local SRID = 4326

local CAR_ROADS = {
    motorway = true, motorway_link = true, trunk = true, trunk_link = true,
    primary = true, primary_link = true, secondary = true, secondary_link = true,
    tertiary = true, tertiary_link = true, unclassified = true, residential = true,
    living_street = true, service = true, road = true,
}

-- Service ways that are not streets: car park aisles and private driveways would only
-- attract wrong matches.
local NOT_STREETS = { parking_aisle = true, driveway = true, drive_through = true }

-- Limits written as names (maxspeed, maxspeed:type, source:maxspeed, zone:maxspeed).
local NAMED_LIMITS = {
    ['fr:urban'] = 50, ['fr:rural'] = 80, ['fr:motorway'] = 130,
    ['fr:zone30'] = 30, ['fr:zone:30'] = 30, ['fr:30'] = 30,
    ['fr:zone20'] = 20, ['fr:zone:20'] = 20, ['fr:20'] = 20,
    ['fr:living_street'] = 20, ['fr:walk'] = 20,
}

-- French sign codes (and the generic words some mappers use) a driver acts on. Roundabouts
-- come from the roads themselves (junction=roundabout), not from their warning sign (AB25),
-- which stands well before them.
local SIGN_CODES = {
    ['fr:ab4'] = 'stop', stop = 'stop',
    ['fr:ab3a'] = 'give_way', ['fr:ab3'] = 'give_way', give_way = 'give_way',
    ['fr:b1'] = 'no_entry', no_entry = 'no_entry',
}

-- Tags worth keeping on a sign node, for the build and for debugging.
local KEPT_TAGS = {
    'highway', 'crossing', 'railway', 'traffic_sign', 'traffic_sign:forward', 'traffic_sign:backward',
    'direction', 'traffic_signals:direction', 'traffic_sign:direction', 'maxspeed', 'name',
}

local roads = osm2pgsql.define_way_table('roads', {
    { column = 'highway', type = 'text', not_null = true },
    { column = 'name', type = 'text' },
    { column = 'ref', type = 'text' },
    -- 1: one way along the geometry, -1: against it, 0: both ways.
    { column = 'oneway', type = 'int2', not_null = true },
    { column = 'roundabout', type = 'boolean', not_null = true },
    -- Limit for traffic along the geometry (fwd) and against it (bwd); null = not mapped.
    { column = 'maxspeed_fwd', type = 'int2' },
    { column = 'maxspeed_bwd', type = 'int2' },
    { column = 'maxspeed_raw', type = 'text' },
    { column = 'geom', type = 'linestring', projection = SRID, not_null = true },
}, { schema = 'osm' })

local sign_nodes = osm2pgsql.define_node_table('sign_nodes', {
    { column = 'kind', type = 'text', not_null = true },
    { column = 'value', type = 'int2' },
    -- Raw direction: forward/backward (along the way), degrees, a cardinal point, or null.
    { column = 'direction', type = 'text' },
    { column = 'tags', type = 'jsonb' },
    { column = 'geom', type = 'point', projection = SRID, not_null = true },
}, { schema = 'osm' })

-- Sign nodes that are part of a road: the exact attachment, with their place in the way.
local way_signs = osm2pgsql.define_table({
    name = 'way_signs',
    schema = 'osm',
    ids = { type = 'way', id_column = 'way_id' },
    columns = {
        { column = 'node_id', sql_type = 'bigint', not_null = true },
        { column = 'seq', type = 'int4', not_null = true },
        { column = 'n', type = 'int4', not_null = true },
    },
})

-- Nodes come before ways in the file: remember which ones carry a sign.
local sign_node_ids = {}

local function trim(value)
    return (value:gsub('^%s+', ''):gsub('%s+$', ''))
end

local function parse_speed(value)
    if not value then return nil end
    local v = value:lower():gsub('%s+', '')
    if v:find(';', 1, true) then return nil end
    local named = NAMED_LIMITS[v]
    if named then return named end
    local n = tonumber(v:match('^(%d+)'))
    if not n then return nil end
    if v:find('mph', 1, true) then n = math.floor(n * 1.609344 + 0.5) end
    if n < 5 or n > 150 then return nil end
    return n
end

local function road_limits(tags)
    local base = parse_speed(tags.maxspeed)
        or parse_speed(tags['maxspeed:type'])
        or parse_speed(tags['source:maxspeed'])
        or parse_speed(tags['zone:maxspeed'])
    -- A "zone de rencontre" is 20 km/h by law.
    if not base and tags.highway == 'living_street' then base = 20 end
    return parse_speed(tags['maxspeed:forward']) or base, parse_speed(tags['maxspeed:backward']) or base
end

local function oneway(tags)
    local o = tags.oneway
    if o == 'yes' or o == '1' or o == 'true' then return 1 end
    if o == '-1' or o == 'reverse' then return -1 end
    if o == 'no' or o == 'alternating' or o == 'reversible' then return 0 end
    if tags.junction == 'roundabout' or tags.junction == 'circular' or tags.highway == 'motorway' then return 1 end
    return 0
end

local function kept(tags)
    local out = {}
    for _, key in ipairs(KEPT_TAGS) do
        if tags[key] then out[key] = tags[key] end
    end
    return out
end

function osm2pgsql.process_node(object)
    local tags = object.tags
    local seen = {}

    local function add(kind, value, forced)
        local key = kind .. ':' .. tostring(value) .. ':' .. tostring(forced)
        if seen[key] then return end
        seen[key] = true
        sign_nodes:insert({
            kind = kind,
            value = value,
            direction = forced or tags.direction or tags['traffic_signals:direction'] or tags['traffic_sign:direction'],
            tags = kept(tags),
            geom = object:as_point(),
        })
        sign_node_ids[object.id] = true
    end

    local highway = tags.highway
    if highway == 'traffic_signals' then
        add('traffic_signals')
    elseif highway == 'stop' then
        add('stop')
    elseif highway == 'give_way' then
        add('give_way')
    elseif highway == 'mini_roundabout' then
        add('roundabout')
    elseif highway == 'crossing' and tags.crossing ~= 'no' then
        -- A pedestrian crossing with lights is a traffic light for the driver.
        add(tags.crossing == 'traffic_signals' and 'traffic_signals' or 'crossing')
    end
    if tags.railway == 'level_crossing' then add('level_crossing') end

    -- traffic_sign=FR:AB4;FR:B14[50], matched token by token (FR:B14 is not FR:B1).
    for key, forced in pairs({ traffic_sign = false, ['traffic_sign:forward'] = 'forward', ['traffic_sign:backward'] = 'backward' }) do
        local value = tags[key]
        if value then
            for raw in value:gmatch('[^;,]+') do
                local token = trim(raw):lower()
                local code = (token:gsub('%[.*$', ''))
                if code == 'fr:b14' then
                    local limit = tonumber(token:match('%[(%d+)%]')) or parse_speed(tags.maxspeed)
                    if limit then add('speed_sign', limit, forced or nil) end
                elseif SIGN_CODES[code] then
                    add(SIGN_CODES[code], nil, forced or nil)
                end
            end
        end
    end
end

function osm2pgsql.process_way(object)
    local tags = object.tags
    if not CAR_ROADS[tags.highway] or NOT_STREETS[tags.service] or tags.area == 'yes' then return end
    local fwd, bwd = road_limits(tags)
    roads:insert({
        highway = tags.highway,
        name = tags.name,
        ref = tags.ref,
        oneway = oneway(tags),
        roundabout = tags.junction == 'roundabout' or tags.junction == 'circular',
        maxspeed_fwd = fwd,
        maxspeed_bwd = bwd,
        maxspeed_raw = tags.maxspeed,
        geom = object:as_linestring(),
    })
    local nodes = object.nodes
    local n = #nodes
    for i, id in ipairs(nodes) do
        if sign_node_ids[id] then way_signs:insert({ node_id = id, seq = i, n = n }) end
    end
end
