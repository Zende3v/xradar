#!/usr/bin/env bash
#
# Signalisation v2 — weekly rebuild: fresh France extract, import, build (signs, then nearby
# services), checks, corrections made by hand replayed, publish.
# Any failure stops here and the published version stays. Run as root (cron.d/eona-signs).
#
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/var/lib/eona-signs}"
DB="${DB:-eona}"
URL="${PBF_URL:-https://download.geofabrik.de/europe/france-latest.osm.pbf}"

psql_eona() { runuser -u eona -- psql -qX -v ON_ERROR_STOP=1 -d "$DB" "$@"; }

if [[ "${EONA_GEODATA_LOCK_HELD:-0}" != 1 ]]; then
  exec 9>/var/lock/eona-geodata.lock
  flock -n 9 || { echo '[rebuild] autre reconstruction active' >&2; exit 1; }
fi
echo "[rebuild] $(date -Is) start"
mkdir -p "$WORK"
cd "$WORK"

echo "[rebuild] downloading $URL"
# Invalider preuve avant téléchargement. Un échec ne réutilise jamais ancien PBF pour Valhalla.
rm -f pbf-verified.sha256
curl -fsSL -o france-latest.osm.pbf.part "$URL"
curl -fsSL -o france-latest.osm.pbf.md5.part "$URL.md5"
EXPECTED=$(awk 'NR==1 {print $1}' france-latest.osm.pbf.md5.part)
[[ "$EXPECTED" =~ ^[a-fA-F0-9]{32}$ ]]
printf '%s  france-latest.osm.pbf.part\n' "$EXPECTED" | md5sum -c -
mv france-latest.osm.pbf.part france-latest.osm.pbf
mv france-latest.osm.pbf.md5.part france-latest.osm.pbf.md5
sha256sum france-latest.osm.pbf > pbf-verified.sha256

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
