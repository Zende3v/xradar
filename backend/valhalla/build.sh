#!/usr/bin/env bash
# Localement préparé. Exécution VPS seulement après accord explicite d'Arthur.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT=/var/lib/valhalla
PBF=/var/lib/eona-signs/france-latest.osm.pbf
STAMP=/var/lib/eona-signs/pbf-verified.sha256
ID=preparation
CANDIDATE="$ROOT"
TEST_NAME=''
# Ménage après succès : cartes `current` et `previous` gardées, échecs et anciennes cartes retirés.
# Cible courante introuvable ou hors graphs : rien supprimé.
purge_graphs() {
  local keep_current keep_previous dir real
  keep_current=$(readlink -f "$ROOT/current")
  [[ -d "$keep_current" && "$keep_current" == "$ROOT/graphs/"?* ]] || return 0
  keep_previous=$(readlink -f "$ROOT/previous" 2>/dev/null || true)
  for dir in "$ROOT"/graphs/*/; do
    real=$(readlink -f "$dir")
    if [[ "$real" == "$ROOT/graphs/"?* && "$real" != "$keep_current" && "$real" != "$keep_previous" ]]; then
      echo "[valhalla] ménage : ${real##*/}"
      rm -rf -- "$real"
    fi
  done
}
cleanup() {
  code=$?
  [[ -z "$TEST_NAME" ]] || podman rm -f "$TEST_NAME" > /dev/null 2>&1 || true
  if [[ $code != 0 ]]; then
    echo "[valhalla] échec $ID ; voir $CANDIDATE" >&2
    logger -p daemon.err -t eona-valhalla "Échec construction $ID, code $code" || true
    if [[ "$(systemctl show -p LoadState --value eona-valhalla-notify.service 2>/dev/null)" == loaded ]]; then
      systemctl start eona-valhalla-notify.service || true
    fi
  fi
  exit "$code"
}
trap cleanup EXIT
# Échec silencieux interdit : ligne et commande dans journal cron.
trap 'echo "[valhalla] échec ligne $LINENO : $BASH_COMMAND" >&2' ERR
trap 'exit 130' INT
trap 'exit 143' TERM
source "${VALHALLA_ENV:-/etc/eona/valhalla.env}"
: "${VALHALLA_IMAGE:?}" "${BUILD_MEMORY:?}" "${BUILD_MEMORY_SWAP:?}" "${BUILD_CPUS:?}" "${MIN_FREE_GB:?}"
[[ "$VALHALLA_IMAGE" =~ ^ghcr.io/valhalla/valhalla:3\.9\.0@sha256:[a-f0-9]{64}$ ]]
if [[ "${EONA_GEODATA_LOCK_HELD:-0}" != 1 ]]; then
  exec 9>/var/lock/eona-geodata.lock
  flock -n 9 || { echo '[valhalla] autre reconstruction active' >&2; exit 1; }
fi
[[ -f "$STAMP" ]] || { echo '[valhalla] aucun PBF neuf vérifié, carte conservée'; exit 0; }
[[ $(( $(date +%s) - $(stat -c %Y "$STAMP") )) -lt 86400 ]] || { echo '[valhalla] PBF ancien, carte conservée'; exit 0; }
(cd /var/lib/eona-signs && sha256sum -c pbf-verified.sha256)
HASH=$(cut -d ' ' -f 1 "$STAMP")
[[ ! -f "$ROOT/current/pbf.sha256" || "$(cat "$ROOT/current/pbf.sha256")" != "$HASH" ]] || {
  echo '[valhalla] PBF déjà construit'; exit 0;
}
[[ -n "$(swapon --show --noheadings)" ]] || { echo '[valhalla] swap de sécurité absent' >&2; exit 1; }
mkdir -p "$ROOT/graphs"
[[ $(df -PB1 "$ROOT" | awk 'NR==2 {print $4}') -ge $((MIN_FREE_GB * 1024 * 1024 * 1024)) ]]
ID="$(date -u +%Y%m%dT%H%M%SZ)-${HASH:0:12}"
CANDIDATE="$ROOT/graphs/$ID"
mkdir "$CANDIDATE"
printf '%s\n' "$HASH" > "$CANDIDATE/pbf.sha256"
TEST_NAME="eona-valhalla-test-$ID"
# Build seul sur réseau hôte : ufw bloque transfert (FORWARD DROP), valhalla_build_timezones télécharge
# fuseaux sur GitHub. Aucun port publié. Service et test restent sur réseau podman.
nice -n 15 ionice -c 3 podman run --rm --pull=never --network=host --name "eona-valhalla-build-$ID" \
  --memory="$BUILD_MEMORY" --memory-swap="$BUILD_MEMORY_SWAP" --cpus="$BUILD_CPUS" \
  --pids-limit=512 --oom-score-adj=900 --cap-drop=all --security-opt=no-new-privileges \
  -v "$CANDIDATE:/data:rw" -v "$PBF:/input/france.osm.pbf:ro" -v "$HERE:/tools:ro" \
  "$VALHALLA_IMAGE" python3 /tools/build-in-container.py
du -sb "$CANDIDATE" > "$CANDIDATE/build-size.txt"
podman run -d --pull=never --name "$TEST_NAME" --read-only \
  --memory=2g --memory-swap=3g --cpus=2 --oom-score-adj=800 --pids-limit=256 \
  --cap-drop=all --security-opt=no-new-privileges \
  -p 127.0.0.1:8003:8002 -v "$CANDIDATE:/data:ro" \
  "$VALHALLA_IMAGE" valhalla_service /data/valhalla.json 2 > /dev/null
export VALHALLA_SEARCH_CUTOFF_M VALHALLA_MAX_SNAP_M
bash "$HERE/test.sh" http://127.0.0.1:8003 > "$CANDIDATE/smoke.json"
podman rm -f "$TEST_NAME" > /dev/null
# Test validé ; rollback automatique si service final ou routes échouent.
OLD=''
if [[ -L "$ROOT/current" ]]; then
  OLD=$(readlink -f "$ROOT/current")
  [[ "$OLD" == "$ROOT/graphs/"* && -s "$OLD/tiles.tar" ]]
fi
ln -s "$CANDIDATE" "$ROOT/current.new"
mv -Tf "$ROOT/current.new" "$ROOT/current"
if ! systemctl restart eona-valhalla || ! bash "$HERE/test.sh" http://127.0.0.1:8002 > "$CANDIDATE/live-smoke.json"; then
  if [[ -n "$OLD" && -d "$OLD" ]]; then
    ln -s "$OLD" "$ROOT/current.rollback"
    mv -Tf "$ROOT/current.rollback" "$ROOT/current"
    systemctl restart eona-valhalla
  else
    rm -f "$ROOT/current"
    systemctl stop eona-valhalla || true
  fi
  exit 1
fi
if [[ -n "$OLD" && -d "$OLD" ]]; then
  ln -s "$OLD" "$ROOT/previous.new"
  mv -Tf "$ROOT/previous.new" "$ROOT/previous"
fi
purge_graphs
echo "[valhalla] carte validée : $ID"
