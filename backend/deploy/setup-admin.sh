#!/usr/bin/env bash
#
# Setup unique pour les comptes x_radar sur le VPS.
# À lancer sur le VPS, depuis le dossier backend/, en sudo :
#
#     sudo bash deploy/setup-admin.sh
#
# Ce script :
#   1. installe le service systemd à jour (dossier data/ inscriptible),
#   2. crée /opt/xradar-backend/data (inscriptible par l'utilisateur xradar),
#   3. génère un ADMIN_TOKEN aléatoire (ou réutilise celui déjà en place),
#   4. redémarre le backend,
#   5. affiche le token + les 2 lignes à copier pour le CLI.
#
set -euo pipefail

APP_DIR=/opt/xradar-backend
SERVICE=xradar-backend
UNIT=/etc/systemd/system/${SERVICE}.service
DROPIN_DIR=${UNIT}.d
SRC_UNIT="$(cd "$(dirname "$0")" && pwd)/xradar-backend.service"
RUN_USER=xradar

if [[ $EUID -ne 0 ]]; then
  echo "Lance-moi en sudo :  sudo bash deploy/setup-admin.sh" >&2
  exit 1
fi

# 1. Installer/mettre à jour le service (apporte ReadWritePaths + chemins data).
if [[ -f "$SRC_UNIT" ]]; then
  cp "$SRC_UNIT" "$UNIT"
  echo "==> service installé : $UNIT"
else
  echo "!! introuvable : $SRC_UNIT (lance le script depuis backend/)" >&2
  exit 1
fi

# 2. Dossier de données inscriptible.
mkdir -p "$APP_DIR/data"
chown -R "$RUN_USER:$RUN_USER" "$APP_DIR/data"
echo "==> dossier data prêt : $APP_DIR/data"

# 3. Token admin : on garde celui déjà défini dans un drop-in, sinon on en génère un.
mkdir -p "$DROPIN_DIR"
EXISTING=""
if [[ -f "$DROPIN_DIR/admin.conf" ]]; then
  EXISTING=$(sed -n 's/^Environment=ADMIN_TOKEN=//p' "$DROPIN_DIR/admin.conf" || true)
fi
if [[ -n "$EXISTING" ]]; then
  TOKEN="$EXISTING"
  echo "==> token existant réutilisé"
else
  TOKEN=$(openssl rand -hex 32)
  cat > "$DROPIN_DIR/admin.conf" <<EOF
[Service]
Environment=ADMIN_TOKEN=$TOKEN
EOF
  echo "==> nouveau token généré"
fi

# 4. Recharger + redémarrer.
systemctl daemon-reload
systemctl restart "$SERVICE"
sleep 1
systemctl is-active --quiet "$SERVICE" && echo "==> backend actif" || {
  echo "!! le backend n'a pas démarré — voir : journalctl -u $SERVICE -n 40" >&2
  exit 1
}

cat <<EOF

────────────────────────────────────────────────────────
 Terminé. Ton token admin :

   ADMIN_TOKEN = $TOKEN

 Pour utiliser le CLI (sur le VPS) :

   export X_RADAR_URL=http://127.0.0.1:8090
   export X_RADAR_ADMIN_TOKEN=$TOKEN
   node $APP_DIR/bin/xradar-accounts.js list

────────────────────────────────────────────────────────
EOF
