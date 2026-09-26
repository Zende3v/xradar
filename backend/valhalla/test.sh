#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
HERE="$(cd "$(dirname "$0")" && pwd)"
URL="${1:-http://127.0.0.1:8003}"
# Attendre disponibilité, puis tester détails et routes. Aucun succès sur simple HTTP 200.
for ((i=0; i<60; i++)); do
  if curl -fsS --max-time 2 "$URL/status" > /dev/null; then
    exec node "$HERE/test.mjs" "$URL"
  fi
  sleep 1
done
echo '[valhalla] instance indisponible après attente' >&2
exit 1
