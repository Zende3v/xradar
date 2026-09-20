#!/usr/bin/env bash
#
# Signalisation v2 — weekly rebuild: fresh France extract, import, build (signs, then nearby
# services), checks, corrections made by hand replayed, publish.
# Any failure stops here and the published version stays. Run as root (cron.d/eona-signs).
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/var/lib/eona-signs}"
DB="${DB:-eona}"
URL="${PBF_URL:-https://download.geofabrik.de/europe/france-latest.osm.pbf}"

psql_eona() { runuser -u eona -- psql -qX -v ON_ERROR_STOP=1 -d "$DB" "$@"; }

echo "[rebuild] $(date -Is) start"
mkdir -p "$WORK"
cd "$WORK"

echo "[rebuild] downloading $URL"
curl -fsSL -o france-latest.osm.pbf.part "$URL"
curl -fsSL -o france-latest.osm.pbf.md5 "$URL.md5"
mv france-latest.osm.pbf.part france-latest.osm.pbf
md5sum -c france-latest.osm.pbf.md5

bash "$HERE/import.sh" "$WORK/france-latest.osm.pbf"

echo "[rebuild] building"
psql_eona -f "$HERE/build.sql"
psql_eona -f "$HERE/places.sql"

echo "[rebuild] checking"
psql_eona -f "$HERE/checks.sql"

echo "[rebuild] replaying the corrections made by hand"
psql_eona -f "$HERE/edits.sql"

echo "[rebuild] publishing"
psql_eona -f "$HERE/publish.sql"
psql_eona -c 'DROP SCHEMA IF EXISTS osm CASCADE'

echo "[rebuild] $(date -Is) done"
