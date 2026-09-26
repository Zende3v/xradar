#!/usr/bin/env bash
# Installation, swap et daemon-reload : accord explicite d'Arthur avant exécution.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
[[ $EUID == 0 && "${1:-}" == --apply ]] || { echo 'Usage : root, install.sh --apply' >&2; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
[[ "$HERE" == /opt/eona-backend/valhalla ]] || { echo 'Déployer sources dans /opt/eona-backend avant installation' >&2; exit 1; }
apt-get update
apt-get install -y podman curl python3 util-linux
install -d -m 0755 /etc/eona /etc/containers/systemd /var/lib/valhalla/graphs
[[ -e /etc/eona/valhalla.env ]] || install -m 0644 "$HERE/valhalla.env.example" /etc/eona/valhalla.env
source /etc/eona/valhalla.env
[[ "$VALHALLA_IMAGE" =~ ^ghcr.io/valhalla/valhalla:3\.9\.0@sha256:[a-f0-9]{64}$ ]]
# Réserve initiale 4 Gio. Dimension finale : à mesurer après premier build.
SWAP=/var/lib/eona-valhalla.swap
[[ ! -L "$SWAP" ]] || { echo '[valhalla] refus fichier swap symbolique' >&2; exit 1; }
if [[ ! -e "$SWAP" ]]; then
  TEMP_SWAP=$(mktemp /var/lib/eona-valhalla.swap.XXXXXX)
  trap 'rm -f -- "$TEMP_SWAP"' EXIT
  chmod 0600 "$TEMP_SWAP"
  fallocate -l 4G "$TEMP_SWAP"
  mkswap "$TEMP_SWAP"
  mv -T "$TEMP_SWAP" "$SWAP"
  trap - EXIT
fi
[[ -f "$SWAP" && "$(blkid -p -s TYPE -o value "$SWAP")" == swap ]] || {
  echo '[valhalla] swap existant invalide ; inspection manuelle requise' >&2; exit 1;
}
chmod 0600 "$SWAP"
swapon --noheadings --show=NAME | grep -Fxq "$SWAP" || swapon "$SWAP"
awk -v path="$SWAP" '$1 == path && $3 == "swap" { found=1 } END { exit !found }' /etc/fstab \
  || printf '%s none swap sw 0 0\n' "$SWAP" >> /etc/fstab
podman pull "$VALHALLA_IMAGE"
podman run --rm --pull=never "$VALHALLA_IMAGE" valhalla_service --version
install -m 0644 "$HERE/../deploy/eona-valhalla.container" /etc/containers/systemd/eona-valhalla.container
systemctl daemon-reload
echo '[valhalla] installé, service pas démarré ; lancer build.sh après PBF neuf vérifié'
echo '[valhalla] cron geodata pas installé ; remplacer eona-signs après validation premier build'
