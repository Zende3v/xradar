#!/usr/bin/env bash
#
# EONA search, step 1: keep the objects that may be named places, and the communes, of a France
# extract, then load the named ones into PostGIS (schema "search_osm") with osm2pgsql and
# style.lua. build.sql comes next.
#
# Run as root:  bash import.sh [/path/to/france-latest.osm.pbf]
#
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/var/lib/eona-signs}"
PBF="${1:-$WORK/france-latest.osm.pbf}"
DB="${DB:-eona}"

mkdir -p "$WORK"
chown eona:eona "$WORK"

echo "[search-import] filtering places and communes…"
osmium tags-filter --overwrite -o "$WORK/search.osm.pbf" "$PBF" \
  nwr/amenity nwr/shop nwr/tourism nwr/office nwr/healthcare nwr/craft nwr/historic \
  nwr/leisure=sports_centre,stadium,park,water_park,golf_course,marina,ice_rink,fitness_centre,swimming_pool,nature_reserve,beach_resort,bowling_alley,miniature_golf,horse_riding,dance,escape_game,trampoline_park,sports_hall,amusement_arcade,track \
  nwr/railway=station,halt nwr/aeroway=aerodrome,terminal nwr/highway=services,rest_area \
  nwr/natural=beach,peak,volcano nwr/landuse=retail,industrial nwr/place \
  nwr/building=school,university,college,hospital,train_station,stadium,church,cathedral,civic,public,government,townhall,sports_hall,supermarket,hotel,museum,castle,retail \
  r/admin_level=8
chown eona:eona "$WORK/search.osm.pbf"

echo "[search-import] loading into PostGIS (schema search_osm)…"
runuser -u eona -- psql -qX -v ON_ERROR_STOP=1 -d "$DB" -c 'DROP SCHEMA IF EXISTS search_osm CASCADE; CREATE SCHEMA search_osm;'
runuser -u eona -- osm2pgsql --create --output=flex --style "$HERE/style.lua" \
  --database "$DB" --schema search_osm --number-processes 8 --log-progress=false \
  "$WORK/search.osm.pbf"
rm -f "$WORK/search.osm.pbf"

echo "[search-import] done"
