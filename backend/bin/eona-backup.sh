#!/usr/bin/env bash
# EONA : comptes, fichiers persistants et mesures PostgreSQL. Cron quotidien, 03:10.
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
umask 077

DEST=/var/backups/eona
DATA=/opt/eona-backend/data
JOUR=$(date +%F)
mkdir -p "$DEST"

# Une seule sauvegarde. Échec avant publication : archives précédentes intactes.
exec 9>"$DEST/.backup.lock"
flock -n 9 || { echo '[backup] sauvegarde déjà active' >&2; exit 1; }
TEMP=$(mktemp -d "$DEST/.backup-$JOUR.XXXXXX")
trap 'rm -rf -- "$TEMP"' EXIT

# Garder nom historique ; archive contient aussi schéma routing depuis phase 1.
runuser -u eona -- pg_dump -d eona -Fc -n crowd -n routing > "$TEMP/crowd.dump"
pg_restore --list "$TEMP/crowd.dump" > /dev/null

FICHIERS=(accounts.json device-trials.json avatars)
for FICHIER in settings.json ors-usage.json here-usage.json; do
  [[ ! -e "$DATA/$FICHIER" ]] || FICHIERS+=("$FICHIER")
done
tar czf "$TEMP/data.tgz" -C "$DATA" "${FICHIERS[@]}"
tar tzf "$TEMP/data.tgz" > /dev/null

mv -f -- "$TEMP/crowd.dump" "$DEST/crowd-$JOUR.dump"
mv -f -- "$TEMP/data.tgz" "$DEST/data-$JOUR.tgz"
find "$DEST" -maxdepth 1 -type f \( -name 'crowd-*.dump' -o -name 'data-*.tgz' \) -mtime +14 -delete
echo "[backup] $(date -Is) OK : crowd, routing et fichiers persistants"
