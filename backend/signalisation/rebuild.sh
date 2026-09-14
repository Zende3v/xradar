#!/usr/bin/env bash
#
# Signalisation v2 — weekly rebuild: fresh France extract, import, build, checks, publish.
# Any failure stops here and the published version stays. Run as root (cron.d/xradar-signs).
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/var/lib/xradar-signs}"
DB="${DB:-xradar}"
URL="${PBF_URL:-https://download.geofabrik.de/europe/france-latest.osm.pbf}"

psql_xradar() { runuser -u xradar -- psql -qX -v ON_ERROR_STOP=1 -d "$DB" "$@"; }

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
psql_xradar -f "$HERE/build.sql"

echo "[rebuild] checking"
psql_xradar -f "$HERE/checks.sql"

echo "[rebuild] publishing"
psql_xradar -f "$HERE/publish.sql"
psql_xradar -c 'DROP SCHEMA IF EXISTS osm CASCADE'

echo "[rebuild] $(date -Is) done"
