#!/usr/bin/env bash
#
# Extrait TOUTE la signalisation de france-latest.osm.pbf vers data/signs.ndjson,
# que le backend charge en mémoire (plus d'Overpass en direct).
#
# Prérequis (une fois) :  sudo apt install osmium-tool
# pbf attendu :           /opt/xradar-backend/data/france-latest.osm.pbf
# Lancer :                bash scripts/extract-signs.sh
#
set -euo pipefail

DIR="${1:-/opt/xradar-backend/data}"
PBF="$DIR/france-latest.osm.pbf"
HERE="$(cd "$(dirname "$0")" && pwd)"

[ -f "$PBF" ] || { echo "❌ pbf introuvable : $PBF"; exit 1; }
command -v osmium >/dev/null || { echo "❌ osmium manquant : sudo apt install osmium-tool"; exit 1; }

echo "1/3 — filtrage de la signalisation…"
osmium tags-filter --overwrite -o "$DIR/signs-filtered.osm.pbf" "$PBF" \
  n/highway=traffic_signals,stop,give_way,crossing \
  n/traffic_sign \
  w/junction=roundabout \
  w/highway=construction \
  w/maxspeed

echo "2/3 — export GeoJSON…"
osmium export --overwrite "$DIR/signs-filtered.osm.pbf" -f geojsonseq -o "$DIR/signs.geojsonl"

echo "3/3 — conversion NDJSON…"
node "$HERE/convert-signs.js" "$DIR/signs.geojsonl" "$DIR/signs.ndjson"

rm -f "$DIR/signs-filtered.osm.pbf" "$DIR/signs.geojsonl"
echo "✅ terminé → $DIR/signs.ndjson"
echo "   Redémarre : sudo systemctl restart xradar-backend"
