#!/usr/bin/env bash
#
# EONA search — weekly rebuild, after the signalisation (same France extract, already checked):
# import, build, publish. Any failure stops here and the published index stays. Run as root
# (bin/eona-geodata-rebuild.sh), or alone the same way.
#
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-/var/lib/eona-signs}"
DB="${DB:-eona}"

psql_eona() { runuser -u eona -- psql -qX -v ON_ERROR_STOP=1 -d "$DB" "$@"; }

if [[ "${EONA_GEODATA_LOCK_HELD:-0}" != 1 ]]; then
  exec 9>/var/lock/eona-geodata.lock
  flock -n 9 || { echo '[search] autre reconstruction active' >&2; exit 1; }
fi
echo "[search] $(date -Is) start"
# Only the extract the signalisation downloaded and checked.
cd "$WORK"
sha256sum -c --quiet pbf-verified.sha256

bash "$HERE/import.sh" "$WORK/france-latest.osm.pbf"

echo "[search] building"
psql_eona -f "$HERE/build.sql"

echo "[search] publishing"
psql_eona -f "$HERE/publish.sql"
psql_eona -c 'DROP SCHEMA IF EXISTS search_osm CASCADE'

echo "[search] $(date -Is) done"
