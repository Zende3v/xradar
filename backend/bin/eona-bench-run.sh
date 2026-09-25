#!/usr/bin/env bash
#
# EONA banc (phase 1, D1.7) : lance un créneau sur le backend local. Appelé par
# deploy/eona-bench.cron, ou à la main :
#
#     bash /opt/eona-backend/bin/eona-bench-run.sh matin|midi|soir|nuit
#     bash /opt/eona-backend/bin/eona-bench-run.sh manuel     # essai : passage sans créneau
#
# Jeton admin lu dans l'environnement du service (comme le README, §10), jamais affiché :
# passé à curl par l'entrée standard, absent de la ligne de commande.
#
set -euo pipefail

SLOT="${1:-}"
case "$SLOT" in
  matin|midi|soir|nuit) QUERY="?slot=$SLOT" ;;
  manuel) QUERY="" ;;
  *) echo "usage : $0 matin|midi|soir|nuit|manuel" >&2; exit 2 ;;
esac

T=$(systemctl show eona-backend -p Environment --value | grep -oP 'ADMIN_TOKEN=\K\S+' || true)
if [[ -z "$T" ]]; then
  echo "$(date -Is) banc $SLOT : ADMIN_TOKEN introuvable dans eona-backend" >&2
  exit 1
fi

echo "$(date -Is) banc $SLOT"
printf 'x-admin-token: %s\n' "$T" | curl -sS --max-time 900 --fail-with-body -X POST -H @- \
  "http://127.0.0.1:8090/api/admin/bench/run$QUERY"
echo
