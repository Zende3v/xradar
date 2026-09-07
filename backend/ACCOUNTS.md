# Comptes & rôles

Identité **par appareil** (MVP, sans mot de passe pour l'instant) : chaque installation
génère un `deviceId` (UUID) stocké localement. Au lancement l'app appelle
`POST /api/accounts/auth { deviceId }` ; le backend crée un compte **guest** au premier
contact et renvoie son rôle.

## Rôles

| Rôle     | Accès | Stats | Signalements |
|----------|-------|-------|--------------|
| `guest`  | libre (restrictions à venir) | **locales uniquement** (rien dans le cloud) | normaux |
| `client` | libre (avantages à venir) | locales pour l'instant | normaux |
| `admin`  | total | — | **100 % de confiance** (auto-validés, durée de vie x2) |

Les rôles `client`/`admin` s'attribuent avec le CLI (ci-dessous). `guest` est le défaut.

## API

Publique :
- `POST /api/accounts/auth { deviceId, platform? }` → `{ account: { id, role, displayName, banned } }`

Admin (protégée par `ADMIN_TOKEN`, en-tête `Authorization: Bearer <token>`) :
- `GET  /api/admin/accounts[?role=guest|client|admin]`
- `GET  /api/admin/accounts/:id`
- `POST /api/admin/accounts { role, deviceId?, displayName? }`
- `PATCH /api/admin/accounts/:id { role?, displayName?, banned? }`
- `DELETE /api/admin/accounts/:id`

> Si `ADMIN_TOKEN` n'est pas défini, l'API admin renvoie `503` (désactivée).

## CLI Linux — `xradar-accounts`

Un script Node exécutable (`backend/bin/xradar-accounts.js`) qui parle à l'API admin.

```bash
# Config (mets ça dans ton shell / un .env que tu source) :
export X_RADAR_URL=http://127.0.0.1:8090          # ou l'URL du VPS
export X_RADAR_ADMIN_TOKEN=le-meme-que-ADMIN_TOKEN

chmod +x backend/bin/xradar-accounts.js           # une seule fois

# Voir tous les comptes (ou filtrer par rôle) :
./backend/bin/xradar-accounts.js list
./backend/bin/xradar-accounts.js list guest

# Détail :
./backend/bin/xradar-accounts.js show <id>

# Ajouter un compte :
./backend/bin/xradar-accounts.js add client --name="Jean"
./backend/bin/xradar-accounts.js add admin  --device=<deviceId> --name="Arthur"

# Modifier (promouvoir, renommer, bannir) :
./backend/bin/xradar-accounts.js set <id> --role=admin
./backend/bin/xradar-accounts.js set <id> --name="Nouveau nom"
./backend/bin/xradar-accounts.js set <id> --ban       # / --unban

# Supprimer :
./backend/bin/xradar-accounts.js del <id>
```

Installable comme commande globale : `cd backend && npm link` → `xradar-accounts list`.

### Se promouvoir admin (première fois)

1. Lance l'app une fois → un compte `guest` est créé pour ton appareil.
2. `xradar-accounts list` → repère la ligne avec ton `device`.
3. `xradar-accounts set <id> --role=admin --name="Arthur"`.
4. Relance l'app (ré-auth) → tes signalements sont désormais 100 % de confiance.

## Déploiement

Le backend écrit `data/accounts.json` et `data/reports.json`. Avec le durcissement
systemd (`ProtectSystem=strict`), il faut un dossier `data/` inscriptible :

```bash
sudo mkdir -p /opt/xradar-backend/data
sudo chown xradar:xradar /opt/xradar-backend/data
# Édite le service : ADMIN_TOKEN=<long secret aléatoire>, puis :
sudo systemctl daemon-reload && sudo systemctl restart xradar-backend
```
