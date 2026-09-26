#!/usr/bin/env bash
# Future chaîne unique. Ne pas installer avant accord Valhalla.
set -uo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HERE="$(cd "$(dirname "$0")/.." && pwd)"
exec 9>/var/lock/eona-geodata.lock
flock -n 9 || exit 1
export EONA_GEODATA_LOCK_HELD=1
bash "$HERE/signalisation/rebuild.sh"
SIGNS=$?
bash "$HERE/valhalla/build.sh"
ROUTING=$?
echo "[geodata] signalisation=$SIGNS valhalla=$ROUTING"
[[ $SIGNS == 0 && $ROUTING == 0 ]]
