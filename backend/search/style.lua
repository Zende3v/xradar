-- EONA search — osm2pgsql flex style (osm2pgsql 2.x).
--
-- Keeps the named places a driver may look for — shops, schools, stations, towns, lieux-dits,
-- car parks, sights… — with what places them (street, postcode, town), and the communes.
-- Everything lands in schema "search_osm"; build.sql turns it into the published index.

local SRID = 4326

-- The keys that say what a place is, in this order: the first one an object has names it.
local KEYS = {
    'amenity', 'shop', 'tourism', 'leisure', 'office', 'healthcare', 'craft', 'historic',
    'railway', 'aeroway', 'highway', 'natural', 'landuse', 'place', 'building',
}

-- Values of a key that are never a destination, even named. `true` for a key: every value.
local SKIP = {
    amenity = {
        bench = true, waste_basket = true, waste_disposal = true, bicycle_parking = true,
        bicycle_rental = true, vending_machine = true, recycling = true, parking_space = true,
        parking_entrance = true, post_box = true, telephone = true, drinking_water = true,
        shelter = true, hunting_stand = true, grit_bin = true, clock = true, letter_box = true,
        loading_dock = true, water_point = true, bbq = true, table = true, lounger = true,
        photo_booth = true, dog_toilet = true, give_box = true, public_bookcase = true,
        watering_place = true, feeding_place = true, bicycle_repair_station = true,
    },
    historic = { boundary_stone = true, milestone = true },
    tourism = { information = true },
}

-- Keys where only these values count.
local ONLY = {
    railway = { station = true, halt = true },
    aeroway = { aerodrome = true, terminal = true },
    highway = { services = true, rest_area = true },
    natural = { beach = true, peak = true, volcano = true },
    landuse = { retail = true, industrial = true },
    place = {
        city = true, town = true, village = true, hamlet = true, suburb = true, borough = true,
        quarter = true, neighbourhood = true, locality = true, isolated_dwelling = true,
        farm = true, square = true, island = true, islet = true,
    },
    building = {
        school = true, university = true, college = true, hospital = true, train_station = true,
        stadium = true, church = true, cathedral = true, civic = true, public = true,
        government = true, townhall = true, sports_hall = true, supermarket = true, hotel = true,
        museum = true, castle = true, retail = true,
    },
}

-- Access that keeps drivers out: such a car park is nowhere to go.
local CLOSED_ACCESS = { private = true, no = true }

local pois = osm2pgsql.define_table({
    name = 'poi',
    schema = 'search_osm',
    ids = { type = 'any', id_column = 'osm_id', type_column = 'osm_type' },
    columns = {
        { column = 'key', type = 'text', not_null = true },
        { column = 'value', type = 'text', not_null = true },
        { column = 'name', type = 'text', not_null = true },
        -- The other names it goes by ("alt_name", "short_name"…), joined by " ; ".
        { column = 'other_names', type = 'text' },
        { column = 'brand', type = 'text' },
        { column = 'housenumber', type = 'text' },
        { column = 'street', type = 'text' },
        { column = 'postcode', type = 'text' },
        { column = 'city', type = 'text' },
        { column = 'population', type = 'text' },
        { column = 'iata', type = 'text' },
        { column = 'wikidata', type = 'boolean', not_null = true },
        { column = 'geom', type = 'geometry', projection = SRID, not_null = true },
    },
})

local communes = osm2pgsql.define_relation_table('communes', {
    { column = 'name', type = 'text', not_null = true },
    { column = 'insee', type = 'text' },
    { column = 'geom', type = 'multipolygon', projection = SRID, not_null = true },
}, { schema = 'search_osm' })

local OTHER_NAMES = { 'official_name', 'alt_name', 'short_name', 'loc_name', 'old_name' }

-- What an object is ({ key, value }), or nil when it is nothing to look for.
local function kind_of(tags)
    for _, key in ipairs(KEYS) do
        local value = tags[key]
        if value and value ~= 'no' then
            local skip = SKIP[key]
            local only = ONLY[key]
            if not (skip and skip[value]) and not (only and not only[value]) then
                return key, value
            end
        end
    end
    return nil
end

-- The row for a place, without its geometry; nil when it is nothing to look for.
local function row_of(tags)
    local name = tags.name or tags.brand
    if not name or name == '' then return nil end
    local key, value = kind_of(tags)
    if not key then return nil end
    if key == 'amenity' and value == 'parking' and CLOSED_ACCESS[tags.access] then return nil end
    local others = {}
    for _, k in ipairs(OTHER_NAMES) do
        if tags[k] and tags[k] ~= name then others[#others + 1] = tags[k] end
    end
    return {
        key = key,
        value = value,
        name = name,
        other_names = #others > 0 and table.concat(others, ' ; ') or nil,
        brand = tags.brand ~= name and tags.brand or nil,
        housenumber = tags['addr:housenumber'],
        street = tags['addr:street'] or tags['addr:place'],
        postcode = tags['addr:postcode'],
        city = tags['addr:city'],
        population = tags.population,
        iata = tags.iata,
        wikidata = tags.wikidata ~= nil,
    }
end

function osm2pgsql.process_node(object)
    local row = row_of(object.tags)
    if not row then return end
    row.geom = object:as_point()
    pois:insert(row)
end

function osm2pgsql.process_way(object)
    local row = row_of(object.tags)
    if not row then return end
    local geom = object.is_closed and object:as_polygon() or object:as_linestring()
    if geom:is_null() then return end
    row.geom = geom
    pois:insert(row)
end

function osm2pgsql.process_relation(object)
    local tags = object.tags
    if tags.type == 'boundary' and tags.boundary == 'administrative' and tags.admin_level == '8' and tags.name then
        local geom = object:as_multipolygon()
        if not geom:is_null() then
            communes:insert({ name = tags.name, insee = tags['ref:INSEE'], geom = geom })
        end
        return
    end
    if tags.type ~= 'multipolygon' then return end
    local row = row_of(tags)
    if not row then return end
    local geom = object:as_multipolygon()
    if geom:is_null() then return end
    row.geom = geom
    pois:insert(row)
end
