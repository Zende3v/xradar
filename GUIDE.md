# x_radar — Guide de déploiement & maintenance

Tout ce qu'il faut pour **déployer, réparer et comprendre** le projet seul.

---

## 1. Architecture (qui fait quoi)

```
┌─────────────┐        HTTPS (Tailscale Funnel)        ┌──────────────────────┐
│  App Android │  ───────────────────────────────────▶ │  Backend Node/Express │
│  (Kotlin)    │   https://debian.taila9954f.ts.net/    │  sur le VPS (port 8090)│
└─────────────┘                                         └──────────┬───────────┘
                                                                    │
        ┌───────────────────────────────────────────────┬──────────┴─────────┐
        ▼                        ▼                        ▼                    ▼
  data.gouv (radars)      OSRM/OpenRouteService     OSM signs.ndjson     fichiers data/
  (téléchargé au boot)    (itinéraires)             (préchargé, RAM)     (comptes, reports…)

  App → aussi en direct : tuiles Stadia Maps (fond de carte) · api-adresse.data.gouv.fr (adresses)
```

- **App** : UI + GPS + carte (MapLibre + fonds vectoriels Stadia). Ne parle qu'au backend via `BACKEND_BASE_URL`, sauf pour les tuiles Stadia et la recherche d'adresses (Base Adresse Nationale).
- **Backend** : sert radars, itinéraires, signalisation, comptes, signalements, position live.
- **Externe** : data.gouv (dataset radars, auto au démarrage), OSRM public **ou** OpenRouteService (si clé), OpenStreetMap (signalisation, préchargée en local).

### Arborescence backend
```
backend/
├─ src/
│  ├─ index.js          bootstrap (démarre stores + serveur)
│  ├─ server.js         montage des routes Express
│  ├─ config.js         TOUTE la config (ports, chemins, clés via env)
│  ├─ radars/           dataset radars data.gouv + /api/radars
│  ├─ routing/          /api/route → OSRM ou ORS
│  ├─ reports/          signalements crowdsourcés (JSON persistant)
│  ├─ accounts/         comptes, auth, sessions, avatars, API admin
│  ├─ live/             positions en direct (mémoire)
│  └─ signs/            signalisation : dataset OSM préchargé (+ fallback Overpass)
├─ scripts/
│  ├─ extract-signs.sh  génère data/signs.ndjson depuis france-latest.osm.pbf
│  ├─ convert-signs.js  (appelé par le script)
│  └─ apk-server.js     serveur temporaire de téléchargement de l'APK (§10)
├─ bin/xradar-accounts.js   CLI d'administration des comptes
├─ deploy/xradar-backend.service   unit systemd
└─ data/               (créé au runtime — voir §6)
```

---

## 2. Prérequis VPS (une fois)

Debian/Ubuntu. En root/sudo :
```bash
# Node 18+ et outils
sudo apt update
sudo apt install -y nodejs npm osmium-tool
node -v   # doit être ≥ 18

# utilisateur de service
sudo useradd --system --home /opt/xradar-backend --shell /usr/sbin/nologin xradar || true
```
Tailscale doit déjà exposer le VPS (Funnel) sur `https://debian.taila9954f.ts.net/` → port local `8090`.

---

## 3. Déploiement complet (from scratch)

```bash
# 1. code
sudo mkdir -p /opt/xradar-backend
sudo rsync -a --delete backend/ /opt/xradar-backend/     # ou git clone/pull
cd /opt/xradar-backend
sudo -u xradar npm install --omit=dev                    # installe express

# 2. dossiers de données (inscriptibles par le service)
sudo mkdir -p data/avatars data/signs-cache
sudo chown -R xradar:xradar /opt/xradar-backend/data

# 3. secrets (voir §4)
#    → crée le drop-in systemd avec ADMIN_TOKEN (+ ORS_API_KEY si tu veux ORS)

# 4. service systemd
sudo cp deploy/xradar-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now xradar-backend

# 5. vérifier
curl -s http://127.0.0.1:8090/health | head -c 400
```
`/health` doit renvoyer `status:"ok"` + le nombre de radars/comptes/reports.

---

## 4. Secrets & configuration (variables d'env)

Toute la config est dans `src/config.js`, surchargée par des **variables d'environnement**. On les pose via un **drop-in systemd** (ne touche pas au fichier .service, ne va pas dans git) :

```bash
sudo mkdir -p /etc/systemd/system/xradar-backend.service.d
sudo tee /etc/systemd/system/xradar-backend.service.d/env.conf >/dev/null <<'EOF'
[Service]
Environment=ADMIN_TOKEN=METS_UN_LONG_SECRET_ALEATOIRE
Environment=ORS_API_KEY=ta_cle_openrouteservice
EOF
sudo systemctl daemon-reload && sudo systemctl restart xradar-backend
```

| Variable | Rôle | Défaut |
|---|---|---|
| `PORT` / `HOST` | port/bind local | 8090 / 127.0.0.1 |
| `ADMIN_TOKEN` | **obligatoire** pour l'API admin + le CLI | (désactivé si absent) |
| `ORS_API_KEY` | active OpenRouteService (sinon OSRM public) | (aucune → OSRM) |
| `OSRM_URL` | serveur OSRM de repli | router.project-osrm.org |
| `OVERPASS_URL` | serveur Overpass (fallback signs seulement) | overpass-api.de |
| `PUBLIC_BASE_URL` | base des URLs d'avatars | https://debian.taila9954f.ts.net |
| `SIGN_DATA_FILE` | dataset signalisation | ./data/signs.ndjson |
| `ACCOUNTS_FILE` / `REPORTS_FILE` | stockage JSON | ./data/*.json |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASS` / `SMTP_FROM` | vérification email + mot de passe oublié | (désactivé si absent) |
| `LEVEL_CROSSING_FILE` | passages à niveau SNCF (GeoJSON) | ./data/liste-des-passages-a-niveau.geojson |
| `GUEST_TRIAL_MS` | durée de l'essai gratuit guest | 7 jours |
| `REFERRAL_SUBSCRIPTION_MONTHS` | mois de membre offerts par un code | 6 |
| `ACCOUNT_TRIP_HISTORY_MAX` | trajets gardés par compte | 200 |
| `PLACE_USER_AGENT` | User-Agent envoyé à Overpass (sinon 406) | x_radar/1.0 (+url) |

⚠️ **Piège systemd** : `Environment=` coupe sur les espaces. Toute valeur qui en contient
(mot de passe applicatif Gmail, `SMTP_FROM`) doit être **entre guillemets** :
`Environment="SMTP_PASS=xxxx yyyy zzzz wwww"`. Sans ça, aucun mail ne part et rien ne le dit.

Générer un token : `openssl rand -hex 32`.

---

## 5. Signalisation OSM (dataset préchargé)

La signalisation vient d'un extrait OpenStreetMap **chargé en mémoire** (pas d'Overpass en direct). À refaire à chaque mise à jour de la carte (ou quand tu changes les filtres).

```bash
# 1. le dump France (5 Go) — à télécharger une fois depuis Geofabrik :
#    https://download.geofabrik.de/europe/france-latest.osm.pbf
mv france-latest.osm.pbf /opt/xradar-backend/data/france-latest.osm.pbf

# 2. extraire (quelques minutes) :
cd /opt/xradar-backend && bash scripts/extract-signs.sh

# 3. redémarrer :
sudo systemctl restart xradar-backend
# logs → "[signs] dataset loaded: N signs"
```
Ce que ça extrait : feux, stop, cédez, passage piéton, rond-point, travaux, sens interdit
(`traffic_sign`), **et les limitations de vitesse** (`maxspeed`). Les limitations sont
**échantillonnées tous les 150 m le long des routes** (`SPEED_SAMPLE_M` dans
`convert-signs.js`) : c'est ce qui permet à `/api/signs/limit` de répondre « la limite ici »
n'importe où. Le dataset complet fait ~6,4 M de points / 326 Mo (~300 Mo de RAM).

La signalisation n'est chargée **que sur le trajet en cours** : tout l'itinéraire en une requête
(`POST /api/signs/route`), et rien hors navigation.

**Passages à niveau** : fichier SNCF séparé (`data/liste-des-passages-a-niveau.geojson`, 16 878
points) chargé au démarrage **à côté** du dump OSM, dans le même index — pas besoin de relancer
`extract-signs.sh` pour eux. Pour le mettre à jour : remplacer le fichier et redémarrer. Chacune des
deux sources suffit seule ; logs → `[signs] dataset loaded: N points … · 16878 passages à niveau`.

⚠️ Si tu régénères après avoir changé l'échantillonnage, **il faut relancer `extract-signs.sh`**,
sinon la limitation de vitesse reste vide dans l'app.

Si `signs.ndjson` est absent → repli auto sur Overpass (plus lent, mis en cache disque dans `data/signs-cache/`).

---

## 6. Fichiers de données (`/opt/xradar-backend/data/`)

| Fichier / dossier | Contenu | Sauvegarder ? |
|---|---|---|
| `accounts.json` | comptes (mdp **hachés**), rôles, essais/abonnements, **statistiques + historique des trajets**, codes de parrainage | **OUI** |
| `reports.json` | signalements actifs | recommandé |
| `signs.ndjson` | signalisation France (régénérable) | non (régénérable) |
| `signs-cache/` | cache Overpass (fallback) | non |
| `avatars/` | photos de profil | recommandé |
| `france-latest.osm.pbf` | source signalisation | non (retéléchargeable) |
| `liste-des-passages-a-niveau.geojson` | passages à niveau SNCF | non (retéléchargeable) |

Backup rapide : `tar czf xradar-backup.tgz -C /opt/xradar-backend/data accounts.json reports.json avatars`

---

## 7. Comptes (CLI admin)

Nécessite `ADMIN_TOKEN`. Depuis le VPS, **rien à exporter** : le CLI lit le jeton
directement dans le service systemd (`systemctl show xradar-backend`).

```bash
cd /opt/xradar-backend
node bin/xradar-accounts.js list                    # tous les comptes
node bin/xradar-accounts.js add admin --name=arthur --email=a@b.com --password=xxxxxxxx
node bin/xradar-accounts.js set <id> --role=client  # promouvoir/rétrograder
node bin/xradar-accounts.js set <id> --ban          # / --unban
node bin/xradar-accounts.js del <id>
```

Si le service ne tourne pas (ou hors VPS), les deux variables restent possibles :
```bash
export X_RADAR_URL=http://127.0.0.1:8090
export X_RADAR_ADMIN_TOKEN=le_meme_que_ADMIN_TOKEN
```

### Statuts

| Rôle | Accès | Détails |
|---|---|---|
| **guest** | **7 jours d'essai**, puis **restreint** | statut par défaut (pseudo seul *ou* email/mdp), pas de photo ; purgé après 10 j sans lancement |
| **client** | abonnement avec date de fin (`subscriptionEndsAt`) | pas de date = illimité ; aujourd'hui obtenu uniquement par parrainage |
| **admin** | tout | seul à pouvoir supprimer une caméra (persistante) et créer des codes |

**Restreint** = la carte reste, mais **pas de navigation, pas d'alertes, pas de signalement** :
le serveur répond `403 subscription required` à `/api/route` et `POST /api/reports`, l'app masque
les alertes et affiche « Abonnement requis ».

### Parrainage

Un admin génère les codes **depuis l'app** (`Menu ▸ Parrainage`) et y voit combien de fois chacun a
servi. Un code donne **6 mois de membre** et ne se saisit **qu'à la création du compte**.

### Statistiques (serveur, tous les comptes)

Stockées dans `accounts.json` : trajets, km, temps sur la route (envoyé chaque minute, trajet ou
non), alertes traversées, signalements déclarés, signalements confirmés (1re confirmation d'un
autre). **Note de confiance** /5 = (confirmés + 1) / (déclarés + 2) × 5 — démarre à 2,5.

---

## 8. Endpoints (référence)

Public :
- `GET  /health` — état : radars, comptes, reports, live, `routing.provider` (ors|osrm), `signs` (chargé + nombre), `memoryMB`.
- `POST /api/accounts/{auth,guest,register,login,logout}` (register accepte `referralCode`), `GET|PATCH /api/accounts/me` (inclut `access`, `canNavigate`, `accessEndsAt`, `trust`), `POST /api/accounts/avatar`, `GET /api/accounts/username-available`
- `GET /api/accounts/me/stats` · `POST /api/accounts/me/trips` · `POST /api/accounts/me/drive {seconds, meters}` (Bearer)
- `GET|POST /api/accounts/referrals` (compte **admin**, Bearer)
- `GET  /api/places/near?lat&lon&kind=fuel|charging|parking|tobacco|garage|hotel|atm` (20 plus proches, via Overpass)
- `GET  /api/radars/near?lat&lon&radius`, `/bbox`
- `GET  /api/route?from=lat,lon&to=lat,lon[&avoid=tolls,highways]`
- `POST /api/reports` · `GET /api/reports/near` · `POST /api/reports/:id/{confirm,deny}` · `DELETE /api/reports/:id` (admin)
- `POST /api/live/position` · `GET /api/live/near`
- `GET  /api/signs/near?lat&lon&radius` · `POST /api/signs/route {coordinates}` · `GET /api/signs/limit?lat&lon` (limitation à cet endroit, `{v, source}`)

Admin (header `Authorization: Bearer <ADMIN_TOKEN>`) :
- `GET|POST /api/admin/accounts` · `GET|PATCH|DELETE /api/admin/accounts/:id`

---

## 9. Mettre à jour le backend

```bash
cd /opt/xradar-backend
sudo -u xradar git pull            # ou rsync du dossier backend/
sudo -u xradar npm install --omit=dev
sudo systemctl restart xradar-backend
```
Refaire l'extraction signalisation (§5) seulement si tu as changé la carte ou les filtres.

---

## 10. Construire l'app Android (APK)

Sur ta machine (PowerShell), le shell Bash n'a pas de JAVA_HOME :
```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
cd C:\Users\usr\Documents\x_radar
.\gradlew.bat :app:assembleRelease
```
→ `app/build/outputs/apk/release/app-release.apk` (signé avec la clé **debug** → installable ; générer un vrai keystore avant un vrai store).

### Clé Stadia Maps (fond de carte)

Le fond de carte vient de **Stadia Maps** : *OSM Bright* en thème clair, *Alidade Smooth Dark*
en thème sombre (bascule automatique avec le thème du téléphone). Il n'y a plus de vue satellite —
la clé Stadia est donc **obligatoire** pour que la carte s'affiche.

1. Crée un compte sur https://client.stadiamaps.com/ (offre gratuite : 200 k tuiles/mois) et génère une
   clé « API key ».
2. Ajoute-la dans `local.properties` (fichier **non versionné**, à la racine du projet) :
   ```properties
   stadia.apiKey=TA_CLE_ICI
   ```
   (ou variable d'environnement `STADIA_API_KEY` avant le build)
3. Rebuild. Sans clé valide, Stadia renvoie 401 et la carte reste vide.

Le style est choisi dans `baseStyle()` / `stadiaStyleUri()` de
`app/src/main/java/com/xradar/app/feature/drive/component/DriveMap.kt`.

L'URL du backend est dans `app/build.gradle.kts` (`BACKEND_BASE_URL`). ⚠️ Signature debug = si une version *debug* était installée, **désinstalle-la** avant (sinon « signatures différentes »).

### Distribuer l'APK (serveur temporaire)

`scripts/apk-server.js` sert le `.apk` **le plus récent** d'un dossier, et rien d'autre : le
chemin de la requête n'est jamais utilisé pour résoudre un fichier. Zéro dépendance.

```bash
# sur ta machine
scp app/build/outputs/apk/release/app-release.apk root@45.80.23.8:/root/apk/
scp backend/scripts/apk-server.js root@45.80.23.8:/opt/xradar-backend/scripts/

# sur le VPS
ufw allow 8087/tcp
cd /opt/xradar-backend
APK_DIR=/root/apk PORT=8087 nohup node scripts/apk-server.js > /var/log/xradar-apk.log 2>&1 &
```

Lien à partager : **http://45.80.23.8:8087/** (téléchargement direct sur `/download`).
Chaque téléchargement est logué avec l'IP dans `/var/log/xradar-apk.log`.

Pour tout couper : `pkill -f apk-server.js && ufw delete allow 8087/tcp`.

C'est du **HTTP en clair, sans authentification** : n'importe qui avec l'adresse télécharge, et
rien d'autre que des `.apk` ne doit traîner dans le dossier servi.

---

## 11. Repères côté app

Utile quand un comportement surprend — ce n'est pas configurable côté serveur.

| Sujet | Comportement |
|---|---|
| Chargement des alertes | **toute la France en une requête** (~3 200 radars, 374 Ko) ; refetch après 50 km. Plus de réglage de rayon. |
| Signalisation | **uniquement sur le trajet en cours** (tout l'itinéraire d'un coup), passages à niveau compris ; rien hors navigation |
| Regroupement | radars, signalements et panneaux se regroupent sous le **zoom 13** (rayon 62 px), avec le badge de la famille et le compte à côté ; un tap sur un paquet zoome dedans |
| Fond de carte | Stadia, `Menu ▸ Réglages ▸ Apparence` : *Auto* suit **le jour et la nuit réels** à ta position (soleil, crépuscule civil), sinon forcé clair/sombre |
| Thème | `Menu ▸ Réglages ▸ Apparence` : Système / Clair / Sombre |
| Menu | page 1 : identité, étoiles de confiance, statut ; puis **Mon compte**, **Statistiques**, **Réglages**, **Parrainage** (admin), **Se déconnecter** |
| Péage / autoroute | dans le menu **Options** du HUD (E3), pas dans Réglages ; le trajet en cours est recalculé aussitôt |
| Limitation affichée | `/api/signs/limit` toutes les 2,5 s / 40 m ; prioritaire sur la VMA du radar, oubliée après 25 s sans donnée |
| Voix | annonces sur des paliers réels (1000/700/500/300/200/150/100 m), déclenchées 2 s trop tôt pour que le chiffre soit encore vrai à l'oreille |
| Signalement | 13 catégories, 6 par page ; « Mon sens / Sens opposé », **mon sens automatique après 5 s** ; caméra réservée aux admins ; plaque de voiture radar facultative |
| Score des signalements | serveur : facteur × e^(−k·âge/durée) × bonus confirmations × pénalité contradictions, supprimé sous 10 (la durée de base = durée de vie). App : × route × sens × distance pour décider d'alerter. Barème dans `config.js` (`reportScore`) |

---

## 12. Dépannage

| Symptôme | Cause probable → fix |
|---|---|
| App bloquée à l'onboarding | backend pas à jour (endpoints `/guest` etc. en 404) → redéploie |
| API admin renvoie 503 | `ADMIN_TOKEN` non défini → §4 |
| Itinéraires lents | OSRM public → pose `ORS_API_KEY` (§4) |
| Pas de signalisation | `signs.ndjson` absent → §5 ; sinon vérifie les logs `[signs]` |
| « Écriture échouée » / data | dossier `data/` non inscriptible → `chown xradar:xradar` + `ReadWritePaths` (déjà dans le .service) |
| Carte vide (fond gris) | clé Stadia absente/invalide → §10, `stadia.apiKey` dans `local.properties` |
| Limitation jamais affichée | dataset pas régénéré depuis l'échantillonnage 150 m → §5 ; tester `curl "http://127.0.0.1:8090/api/signs/limit?lat=48.05&lon=3.09"` |
| « Itinéraire indisponible » dans l'app | l'app a déjà réessayé une fois → cherche `[route]` dans `journalctl -u xradar-backend -n 100` pour l'erreur ORS exacte |
| L'app ne joint pas le VPS | Tailscale Funnel down, ou mauvaise `BACKEND_BASE_URL` |
| « Statistiques indisponibles » / Parrainage vide | backend pas redéployé : `curl -s -o /dev/null -w "%{http_code}" …/api/accounts/me/stats` doit donner **401**, pas 404 → §9 |
| Pas de passages à niveau | fichier absent de `data/` → logs `[signs] no level-crossing file` ; `/health` doit compter ~16 878 points de plus |
| Un compte ne peut plus naviguer | essai de 7 j terminé ou abonnement expiré (`access: restricted` dans `/api/accounts/me`) → le passer client via le CLI (§7) |

Commandes utiles :
```bash
sudo systemctl status xradar-backend
journalctl -u xradar-backend -n 80 --no-pager     # logs récents
journalctl -u xradar-backend -f                   # logs en direct
curl -s http://127.0.0.1:8090/health              # santé
```

---

## 13. Ce qui reste (roadmap)

- Login **Google / Apple** (nécessite tes comptes développeur).
- **Portail de paiement** pour l'abonnement client (aujourd'hui : parrainage uniquement).
- Migration de l'**historique local** des trajets vers le serveur (repart de zéro côté serveur).
- Vrai **keystore** de release avant publication store (aujourd'hui : clé debug).
- `versionName` encore à **0.1.0** dans `app/build.gradle.kts` alors qu'on parle de v1.5.0 — à aligner.
- **E4 — timer du feu rouge** : la carte est en place dans le menu E3, il manque la source de données.
- Signalisation **par zone visible** (comme `/api/radars/bbox`) pour couvrir tout ce qu'on voit en dézoomant.
