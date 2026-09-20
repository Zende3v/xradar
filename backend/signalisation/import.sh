#!/usr/bin/env bash
#
# Signalisation v2, step 1: keep the car roads, the sign nodes, the nearby services and the
# communes of a France extract, then load them into PostGIS (schema "osm") with osm2pgsql and
# style.lua. build.sql and places.sql come next.
#
# Run as root:  bash import.sh [/path/to/france-latest.osm.pbf]
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PBF="${1:-/opt/eona-backend/data/france-latest.osm.pbf}"
WORK="${WORK:-/var/lib/eona-signs}"
DB="${DB:-eona}"

mkdir -p "$WORK"
chown eona:eona "$WORK"

echo "[import] filtering roads, sign nodes, services and communes…"
osmium tags-filter --overwrite -o "$WORK/roads-signs.osm.pbf" "$PBF" \
  w/highway=motorway,motorway_link,trunk,trunk_link,primary,primary_link,secondary,secondary_link,tertiary,tertiary_link,unclassified,residential,living_street,service,road \
  n/highway=traffic_signals,stop,give_way,crossing,mini_roundabout \
  n/traffic_sign n/traffic_sign:forward n/traffic_sign:backward \
  n/railway=level_crossing \
  nwr/amenity=fuel,charging_station,parking,atm,bank nwr/shop=tobacco,car_repair nwr/tobacco=yes,only \
  nwr/tourism=hotel,motel r/admin_level=8
chown eona:eona "$WORK/roads-signs.osm.pbf"

echo "[import] loading into PostGIS (schema osm)…"
runuser -u eona -- psql -qX -v ON_ERROR_STOP=1 -d "$DB" -c 'DROP SCHEMA IF EXISTS osm CASCADE; CREATE SCHEMA osm;'
runuser -u eona -- osm2pgsql --create --output=flex --style "$HERE/style.lua" \
  --database "$DB" --schema osm --number-processes 8 --log-progress=false \
  "$WORK/roads-signs.osm.pbf"

echo "[import] done"
