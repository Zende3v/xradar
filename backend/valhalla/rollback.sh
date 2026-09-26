#!/usr/bin/env bash
# Redémarrage VPS : accord explicite requis avant exécution.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
ROOT=/var/lib/valhalla
exec 9>/var/lock/eona-geodata.lock
flock -n 9
PREVIOUS=$(readlink -f "$ROOT/previous")
CURRENT=$(readlink -f "$ROOT/current")
[[ "$PREVIOUS" == "$ROOT/graphs/"* && -s "$PREVIOUS/tiles.tar" ]]
[[ "$CURRENT" == "$ROOT/graphs/"* && -s "$CURRENT/tiles.tar" ]]
ln -s "$PREVIOUS" "$ROOT/current.rollback"
mv -Tf "$ROOT/current.rollback" "$ROOT/current"
if ! systemctl restart eona-valhalla || ! bash "$(dirname "$0")/test.sh" http://127.0.0.1:8002; then
  ln -s "$CURRENT" "$ROOT/current.rollback"
  mv -Tf "$ROOT/current.rollback" "$ROOT/current"
  systemctl restart eona-valhalla
  exit 1
fi
ln -s "$CURRENT" "$ROOT/previous.new"
mv -Tf "$ROOT/previous.new" "$ROOT/previous"
