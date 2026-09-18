# x_radar — installer, déployer, lancer, surveiller, réparer

Guide unique du projet. App Android (Kotlin/Compose) + backend Node/Express + PostgreSQL/PostGIS sur un VPS Debian, exposé en HTTPS par Tailscale Funnel. L'app iOS vit dans son propre dépôt : `git@github.com:Zende3v/xradar_ios.git` (guide dans son README).

---

## 0. Mémo express

| Quoi | Où / commande |
|---|---|
| URL publique backend | `https://debian.taila9954f.ts.net/` (Funnel → `127.0.0.1:8090`) |
| SSH VPS | `ssh root@100.107.151.127` (IP Tailscale ; le PC doit être dans le tailnet) |
| Code backend VPS | `/opt/xradar-backend` (user `xradar`) |
| Service | `systemctl status xradar-backend` |
| Logs live | `journalctl -u xradar-backend -f` |
| Santé | `curl -s http://127.0.0.1:8090/health` |
| Base | PostgreSQL 17 + PostGIS, base `xradar` (`runuser -u xradar -- psql -d xradar`) |
| Rebuild signalisation | cron dimanche 03:30, log `/var/lib/xradar-signs/rebuild.log` |
| Relancer le backend | `systemctl restart xradar-backend` |
| Build APK | `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` |

---

## 1. Architecture

```
App Android ──HTTPS──▶ Tailscale Funnel ──▶ backend Node :8090 (systemd, user xradar)
   │                                              │
   ├─ tuiles carte : Stadia Maps (direct)          ├─ PostgreSQL/PostGIS « xradar »
   └─ adresses : api-adresse.data.gouv.fr          │    ├─ schéma signs  : routes + panneaux + services autour (rebuild hebdo depuis OSM)
                                                   │    └─ schéma crowd  : signalements + corrections de limite
                                                   ├─ data/accounts.json : comptes, stats, parrainage
                                                   ├─ data/avatars/      : photos de profil
                                                   ├─ data.gouv : radars fixes (téléchargé au démarrage + chaque jour)
                                                   ├─ prix-carburants : flux officiel, prix + horaires (toutes les 10 min)
                                                   └─ OpenRouteService (si ORS_API_KEY) sinon OSRM public : itinéraires
```

Présence (app ouverte, en trajet ou non ; **aucune position**) : mémoire seulement (90 s), comptée dans `/health` (`live.online`, `live.inTrip`), montrée à personne. Sessions (tokens) : mémoire seulement → un redémarrage déconnecte, l'app se reconnecte seule par son `deviceId` (compte rattaché au téléphone).

### Arborescence utile

```
backend/
├─ src/
│  ├─ index.js            démarrage : radars, carburants, comptes, schéma crowd, stores
│  ├─ server.js           routes Express + /health
│  ├─ config.js           TOUTE la config (env vars ci-dessous)
│  ├─ db.js               pool PostgreSQL (socket local, auth peer)
│  ├─ crowd/schema.sql    schéma crowd, appliqué à chaque démarrage (idempotent)
│  ├─ accounts/ live/ radars/ routing/ fuel/
│  ├─ places/             services autour (PostGIS) + horaires (hours.js, lib opening_hours)
│  ├─ reports/            signalements (anti-doublon, votes, score.js)
│  ├─ speedlimits/        corrections de limitation
│  └─ signs/postgis.js    limite sous le conducteur, panneaux du trajet
├─ signalisation/         pipeline OSM → PostGIS (import.sh, style.lua, build.sql, checks.sql, publish.sql, rebuild.sh)
├─ bin/xradar-accounts.js CLI admin comptes
├─ deploy/                unit systemd + setup-admin.sh
└─ scripts/apk-server.js  serveur temporaire de téléchargement APK
app/                      app Android
```

---

## 2. Le VPS

- Debian 13, i5-11400H 12 threads, 7,4 Go RAM + 7,6 Go swap, NVMe 460 Go.
- Accès : **Tailscale uniquement** (`100.107.151.127`). Le port 22 public ne répond pas.
- `ufw` actif. x_radar n'ouvre **aucun** port : tout passe par Funnel.
- La machine héberge **d'autres projets** (Caddy sur 80/443, lazarus-server, medocs, zylo…). Ne pas y toucher, ne pas redémarrer Caddy pour x_radar.
- Services x_radar lancés au boot : `xradar-backend`, `postgresql`, `tailscaled`, `cron` (tous `enabled`). Un reboot remet tout en route seul.

---

## 3. Installation complète (VPS neuf)

Tout en root.

### 3.1 Paquets

```bash
apt-get update
# Node 20 (NodeSource) — le service utilise /usr/bin/node
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs postgresql postgresql-17-postgis-3 osm2pgsql osmium-tool curl python3
# Tailscale
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up
```

### 3.2 Utilisateur, dossiers, code

```bash
useradd --system --home /opt/xradar-backend --shell /usr/sbin/nologin xradar || true
mkdir -p /opt/xradar-backend/data/avatars /var/lib/xradar-signs
```

Depuis le PC (dossier du repo) :

```bash
scp -r backend/src backend/bin backend/deploy backend/signalisation backend/scripts backend/package.json backend/package-lock.json root@100.107.151.127:/opt/xradar-backend/
```

Sur le VPS :

```bash
cd /opt/xradar-backend && npm ci --omit=dev
chown -R xradar:xradar /opt/xradar-backend /var/lib/xradar-signs
```

### 3.3 PostgreSQL / PostGIS

```bash
cat > /etc/postgresql/17/main/conf.d/xradar.conf <<'EOF'
# x_radar : machine 8 Go partagée avec le backend, disque NVMe.
shared_buffers = 1GB
effective_cache_size = 4GB
maintenance_work_mem = 1GB
work_mem = 64MB
max_wal_size = 8GB
checkpoint_timeout = 15min
random_page_cost = 1.1
effective_io_concurrency = 200
max_parallel_workers_per_gather = 4
max_parallel_maintenance_workers = 4
jit = off
EOF
systemctl restart postgresql
runuser -u postgres -- psql -c "CREATE ROLE xradar LOGIN"
runuser -u postgres -- createdb -O xradar xradar
runuser -u postgres -- psql -d xradar -c "CREATE EXTENSION postgis"
```

Auth : socket local + **peer** (user système `xradar` = rôle `xradar`). Aucun mot de passe, aucun port DB exposé (écoute `127.0.0.1` seulement).

### 3.4 Secrets (drop-ins systemd, jamais dans git)

```bash
cd /opt/xradar-backend && bash deploy/setup-admin.sh   # installe le .service, crée ADMIN_TOKEN (admin.conf), redémarre
```

Autres secrets, un fichier par sujet dans `/etc/systemd/system/xradar-backend.service.d/` :

```bash
cat > /etc/systemd/system/xradar-backend.service.d/ors.conf <<'EOF'
[Service]
Environment=ORS_API_KEY=ta_cle_openrouteservice
EOF
cat > /etc/systemd/system/xradar-backend.service.d/smtp.conf <<'EOF'
[Service]
Environment=SMTP_HOST=smtp.gmail.com
Environment=SMTP_PORT=587
Environment=SMTP_USER=adresse@gmail.com
Environment="SMTP_PASS=xxxx xxxx xxxx xxxx"
Environment="SMTP_FROM=xradar adresse@gmail.com"
EOF
systemctl daemon-reload && systemctl restart xradar-backend
```

⚠️ Valeur avec espaces = **guillemets** autour de toute la ligne `Environment="…"`, sinon mail muet sans erreur.
⚠️ Ne jamais afficher `systemctl cat xradar-backend` en public : secrets en clair.

| Variable | Rôle | Défaut |
|---|---|---|
| `PORT` / `HOST` | écoute locale | `8090` / `127.0.0.1` (via le .service) |
| `ADMIN_TOKEN` | API admin + CLI + modération | absent = API admin coupée |
| `ORS_API_KEY` / `ORS_URL` | itinéraires OpenRouteService | absent = OSRM public |
| `OSRM_URL` | OSRM de repli | `https://router.project-osrm.org` |
| `PGHOST` / `PGDATABASE` | base | `/var/run/postgresql` / `xradar` |
| `SMTP_HOST` `SMTP_PORT` `SMTP_USER` `SMTP_PASS` `SMTP_FROM` | vérif email, mot de passe oublié | absent = pas de mail |
| `PUBLIC_BASE_URL` | base des URLs d'avatars | `https://debian.taila9954f.ts.net` |
| `ACCOUNTS_FILE` / `AVATARS_DIR` | comptes / photos | `./data/accounts.json` / `./data/avatars` |
| `GUEST_TRIAL_MS` | essai compte email | 7 j |
| `GUEST_LIFETIME_MS` | durée de vie compte invité | 7 j |
| `TOMTOM_API_KEY` | trafic TomTom sur le trajet (drop-in `tomtom.conf`, jamais versionnée) | absent = pas de trafic (503) |
| `DEVICE_TRIALS_FILE` | fin du premier essai par téléphone | `./data/device-trials.json` |
| `GUEST_REPORTS_PER_DAY` / `GUEST_TRIPS_PER_DAY` | limites invité par jour | 5 / 7 |
| `REFERRAL_SUBSCRIPTION_MONTHS` | mois offerts par parrainage | 6 |
| `ACCOUNT_TRIP_HISTORY_MAX` | trajets gardés par compte | 200 |
| `RADAR_DATASET_API_URL` / `REFRESH_INTERVAL_MS` | dataset radars / refresh | data.gouv / 24 h |
| `FUEL_FEED_URL` / `FUEL_REFRESH_INTERVAL_MS` | prix carburants | roulez-eco / 10 min |
| `PLACE_USER_AGENT` | identité HTTP vers les données ouvertes | `x_radar/1.0 (+url)` |
| `PLACE_TABLE` | services autour : `signs_next.place` = tester un build non publié (staging) | `signs.place` |

Générer un secret : `openssl rand -hex 32`.

### 3.5 Service

```bash
systemctl daemon-reload
systemctl enable --now xradar-backend
```

Le .service : `Restart=on-failure` (relance seul 5 s après un crash), `ProtectSystem=strict`, seul `data/` inscriptible.

### 3.6 Signalisation (premier build + cron)

```bash
bash /opt/xradar-backend/signalisation/rebuild.sh    # ~10 min : téléchargement 5,8 Go + import + build + contrôles + publication
cat > /etc/cron.d/xradar-signs <<'EOF'
# x_radar signalisation : reconstruction hebdo depuis un extrait France frais (dimanche 03:30).
30 3 * * 0 root bash /opt/xradar-backend/signalisation/rebuild.sh >> /var/lib/xradar-signs/rebuild.log 2>&1
EOF
```

### 3.7 HTTPS public

```bash
tailscale funnel --bg 8090
tailscale funnel status        # doit montrer https://debian.taila9954f.ts.net → 127.0.0.1:8090
```

### 3.8 Vérifier

```bash
systemctl is-active xradar-backend postgresql
curl -s http://127.0.0.1:8090/health
curl -s "http://127.0.0.1:8090/api/signs/limit?lat=48.1113&lon=-1.6778&bearing=0"
curl -s https://debian.taila9954f.ts.net/health        # depuis n'importe où
```

---

## 4. Déployer une mise à jour du backend

Depuis le PC, dossier du repo.

1. Sauvegarder le code en place (retour arrière) :
   ```bash
   ssh root@100.107.151.127 "cd /opt/xradar-backend && tar czf /opt/xradar-backend-src-backup-\$(date +%Y%m%d-%H%M).tgz src package.json package-lock.json"
   ```
2. Envoyer le code (le `rm` retire les fichiers supprimés dans le repo) :
   ```bash
   ssh root@100.107.151.127 "rm -rf /opt/xradar-backend/src"
   scp -r backend/src backend/package.json backend/package-lock.json root@100.107.151.127:/opt/xradar-backend/
   ```
   Pipeline signalisation modifié → envoyer aussi `backend/signalisation`.
3. Sur le VPS :
   ```bash
   cd /opt/xradar-backend
   npm ci --omit=dev                      # seulement si package.json a changé
   chown -R xradar:xradar src node_modules package.json package-lock.json signalisation
   systemctl restart xradar-backend
   curl -s http://127.0.0.1:8090/health
   journalctl -u xradar-backend -n 30 --no-pager
   ```

Schéma `crowd` modifié (`src/crowd/schema.sql`) → appliqué seul au redémarrage (idempotent, jamais de `DROP`).

### Instance de test (optionnel, avant la prod)

`/opt/xradar-backend-staging` : copie du backend sur le port **8099**, lancée par `bash /opt/xradar-backend-staging/run.sh` (ADMIN_TOKEN=`staging-admin`).
⚠️ Elle utilise **la même base** que la prod : ses tests écrivent dans `crowd`. Nettoyer après :
```bash
runuser -u xradar -- psql -d xradar -c "TRUNCATE crowd.report, crowd.report_voice, crowd.speed_limit_change, crowd.speed_limit_voice, crowd.speed_limit_event"
```
Seulement si la prod n'a pas encore de vraies données dans `crowd`. Arrêt : `pkill -f "[n]ode src/index.js --stag[i]ng"`.

---

## 5. Signalisation (OSM → PostGIS)

Chaîne `rebuild.sh` (cron hebdo) :

1. **Téléchargement** `france-latest.osm.pbf` Geofabrik + contrôle md5 → `/var/lib/xradar-signs/`.
2. **import.sh** : osmium garde routes voitures + nœuds de signalisation + services (stations, bornes, parkings, tabacs, garages, hôtels, distributeurs) + communes (~1 min), osm2pgsql (`style.lua`) charge le schéma `osm` (~2 min). Déjà écartés : accès privé, fermé, bornes vélo, boxes/garages privés.
3. **build.sql** (~3 min) → schéma `signs_next` :
   - `road` : 5,8 M routes, limite **par sens** (`maxspeed`, `:forward`, `:backward`, `FR:urban`…, zone de rencontre = 20).
   - `sign` : ~2 M panneaux, chacun **rattaché à sa route**, **orienté** (tags de sens, sens unique, sinon vers le carrefour le plus proche), **dédoublonné** (même type + lieu + sens, taille max par type), **id stable**. Ronds-points reconstitués depuis les anneaux.
   - `meta` : date du build, date de l'extrait OSM, comptes.
4. **places.sql** (~40 s) → `signs_next.place` : ~400 k services, un par lieu.
   - Filtre : parkings de bord de rue ou minuscules (< 120 m², < 5 places), points de parking sans aucune info.
   - **Dédoublonnage** : même type, proches (station 40 m, hôtel 30 m, distributeur 10 m, autres 20 m ; parking : point posé sur le polygone), noms compatibles (l'un vide, égaux ou l'un contient l'autre ; opérateur compté pour bornes et distributeurs). Deux stations à < 12 m = une seule quel que soit le nom. Tags fusionnés, le plus riche gagne. Position : plus grand polygone, sinon point le plus riche.
   - Commune (polygones admin_level 8) pour l'adresse sans `addr:city`. Index KNN partiel par type.
5. **checks.sql** : refuse si < 4 M routes, < 200 k stops, écart > 15 % sur un type de panneau ou de service vs version publiée, ou services sous leur plancher (parking 240 k, garage 16 k, hôtel 15 k, distributeur 14,5 k, borne 14 k, station 10 k, tabac 6,5 k).
6. **publish.sql** : bascule atomique `signs` → `signs_prev`, `signs_next` → `signs` (panneaux et services ensemble). Zéro coupure.
7. Suppression du schéma `osm`.

Rejouer seulement les services sur un `signs_next` déjà construit : `runuser -u xradar -- psql -d xradar -f places.sql` (il nettoie un essai interrompu), puis `checks.sql`. Tester avant publication : instance staging avec `PLACE_TABLE=signs_next.place`.

Échec à n'importe quelle étape = version publiée intacte.

```bash
tail -50 /var/lib/xradar-signs/rebuild.log                        # dernier rebuild
bash /opt/xradar-backend/signalisation/rebuild.sh                  # relancer à la main
runuser -u xradar -- psql -d xradar -c "SELECT * FROM signs.meta"  # version publiée
```

**Revenir à la version précédente** (build publié mais faux) :
```bash
runuser -u xradar -- psql -d xradar -c "BEGIN; ALTER SCHEMA signs RENAME TO signs_broken; ALTER SCHEMA signs_prev RENAME TO signs; COMMIT;"
runuser -u xradar -- psql -d xradar -c "DROP SCHEMA signs_broken CASCADE"
```

Sans `ALTER`, la base ne touche jamais `crowd` : signalements et corrections survivent à chaque rebuild.

---

## 6. Données et sauvegardes

| Donnée | Où | Précieux ? |
|---|---|---|
| Comptes, stats, parrainage | `data/accounts.json` | **OUI** |
| Photos de profil | `data/avatars/` | oui |
| Signalements + votes, corrections de limite + historique | PostGIS schéma `crowd` | **OUI** |
| Routes + panneaux | PostGIS schémas `signs`, `signs_prev` | non (rebuild) |
| Extrait OSM | `/var/lib/xradar-signs/france-latest.osm.pbf` | non (retéléchargé) |
| Copies auto des comptes avant purge | `data/accounts.backup-<date>.json` | oui |
| Ancien système (NDJSON, JSON d'avant PostGIS) | `data/archive/` | non (supprimable) |
| Sauvegardes du code | `/opt/xradar-backend-src-backup-*.tgz` | garder les 2-3 dernières |

Sauvegarde manuelle :
```bash
mkdir -p /var/backups/xradar
runuser -u postgres -- pg_dump -Fc -n crowd xradar > /var/backups/xradar/crowd-$(date +%F).dump
tar czf /var/backups/xradar/data-$(date +%F).tgz -C /opt/xradar-backend/data accounts.json avatars
```

Restauration :
```bash
systemctl stop xradar-backend
runuser -u postgres -- pg_restore -d xradar --clean --if-exists < /var/backups/xradar/crowd-AAAA-MM-JJ.dump
tar xzf /var/backups/xradar/data-AAAA-MM-JJ.tgz -C /opt/xradar-backend/data && chown -R xradar:xradar /opt/xradar-backend/data
systemctl start xradar-backend
```

Pas de sauvegarde automatique (choix assumé) : lancer la sauvegarde manuelle avant toute opération risquée (migration, restauration, gros déploiement).

---

## 7. Surveiller

```bash
systemctl status xradar-backend postgresql tailscaled --no-pager
journalctl -u xradar-backend -f                          # logs en direct
journalctl -u xradar-backend --since "-1 h" --no-pager | grep -iE "error|unavailable|failed"
curl -s http://127.0.0.1:8090/health | python3 -m json.tool
tailscale funnel status
tail -20 /var/lib/xradar-signs/rebuild.log
df -h / && free -h
```

`/health` :

| Champ | Normal |
|---|---|
| `status` | `ok` |
| `radars.count` / `ready` | ~3 300 / `true` |
| `accounts` | total + guest/client/admin |
| `reports.count` | signalements vivants |
| `speedLimits` | `pending` / `validated` / `total` |
| `signs.published` | `built_at` ≤ 8 jours, `roads` ~5,8 M, `signs` ~2 M ; `null` = base injoignable |
| `routing.provider` | `ors` (clé présente) ou `osrm` |
| `fuel.ready` / `lastError` | `true` / `null` |
| `memoryMB` | ~180–400 |

Requêtes utiles :
```bash
runuser -u xradar -- psql -d xradar <<'SQL'
SELECT type, count(*) FROM crowd.report WHERE status = 'live' GROUP BY type;
SELECT status, count(*) FROM crowd.speed_limit_change GROUP BY status;
SELECT nspname, pg_size_pretty(sum(pg_total_relation_size(c.oid))) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE nspname IN ('signs', 'signs_prev', 'crowd', 'osm') GROUP BY nspname;
SQL
```

Mémoire au repos : backend ~180 Mo, PostgreSQL ~1 Go de cache. Disque : `signs` ~4,7 Go (x2 avec `signs_prev`), pic ~8 Go en plus pendant un rebuild.

---

## 8. En cas de panne

Le backend relance seul après un crash (5 s). Un reboot relance tout. Sinon :

| Symptôme | Vérifier | Réparer |
|---|---|---|
| App : « Réseau indisponible » partout | `curl -s https://debian.taila9954f.ts.net/health` depuis le PC | voir lignes suivantes |
| Backend arrêté / boucle de redémarrage | `systemctl status xradar-backend` ; `journalctl -u xradar-backend -n 80 --no-pager` | erreur de code → retour arrière (ci-dessous) ; `EADDRINUSE` → un autre process tient 8090 (`ss -ltnp \| grep 8090`) |
| `/health` OK en local, KO en public | `tailscale funnel status` | `systemctl restart tailscaled && tailscale funnel --bg 8090` |
| `signs.published: null`, erreurs 503 signs/reports | `systemctl status postgresql` ; `journalctl -u postgresql@17-main -n 50` | `systemctl restart postgresql` puis `systemctl restart xradar-backend` |
| Limites / panneaux faux après un dimanche | `tail -80 /var/lib/xradar-signs/rebuild.log` ; `SELECT * FROM signs.meta` | retour à `signs_prev` (§5) |
| Rebuild échoué | log : download, md5, osm2pgsql, `checks` | cause réseau → relancer `rebuild.sh` ; `checks` refuse → extrait OSM douteux, attendre le suivant (version publiée intacte) |
| Disque plein | `df -h /` ; `du -sh /var/lib/postgresql /var/lib/xradar-signs /opt/xradar-backend/data` | `DROP SCHEMA IF EXISTS osm CASCADE` ; `DROP SCHEMA IF EXISTS signs_prev CASCADE` ; vider `data/archive/`, vieux `*.tgz` |
| RAM saturée | `free -h` ; `top` | `systemctl restart xradar-backend` ; rebuild en cours = normal (osm2pgsql) |
| Comptes perdus / `accounts.json` cassé | `journalctl … \| grep accounts` | `systemctl stop xradar-backend` → copier `data/accounts.backup-<date>.json` (ou sauvegarde §6) sur `accounts.json` → `chown xradar:xradar` → start |
| Mails (vérif, mot de passe oublié) ne partent pas | logs `[mail]` | `smtp.conf` : guillemets autour des valeurs avec espaces |
| API admin 503 | `systemctl show xradar-backend -p Environment \| grep -c ADMIN_TOKEN` | `bash deploy/setup-admin.sh` |
| Itinéraires lents / en erreur | `/health` → `routing.provider` ; logs `[route]` | clé ORS (`ors.conf`) ; quota ORS dépassé → attendre ou retirer la clé (bascule OSRM public) |
| Carte vide dans l'app | — | clé Stadia (`local.properties`, §9) invalide ou quota Stadia |

**Retour arrière du code** :
```bash
cd /opt/xradar-backend
ls -t /opt/xradar-backend-src-backup-*.tgz | head -3
rm -rf src && tar xzf /opt/xradar-backend-src-backup-AAAAMMJJ-HHMM.tgz
npm ci --omit=dev && chown -R xradar:xradar src node_modules package.json package-lock.json
systemctl restart xradar-backend
```

---

## 9. App Android

### Prérequis (PC Windows)

- Android Studio. JDK = sa JBR : `C:\Program Files\Android\Android Studio\jbr` (JDK 25 → Gradle 9.7.1 via le wrapper).
- SDK : `C:\Users\usr\AppData\Local\Android\Sdk`, plateforme **android-37**, build-tools 36.0.0.
- AGP 9.4 : Kotlin intégré. **Ne jamais** appliquer `org.jetbrains.kotlin.android`. Plugin Compose = 2.2.10.
- `local.properties` (non versionné) à la racine :
  ```properties
  sdk.dir=C\:\\Users\\usr\\AppData\\Local\\Android\\Sdk
  stadia.apiKey=TA_CLE_STADIA
  ```
  Sans clé Stadia valide : carte vide. Styles : `app/src/main/assets/map/plans-style.json` + teintes `plans-palettes.json` (généré, voir plus bas).
- URL backend : `BACKEND_BASE_URL` dans `app/build.gradle.kts`.

### Construire (release par défaut)

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew assembleRelease
```
PowerShell : `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat assembleRelease`

→ `app/build/outputs/apk/release/app-release.apk`, signé avec la clé **debug** (installable hors store ; vrai keystore obligatoire avant un store). Une version debug déjà installée → la désinstaller avant.

### Installer

```bash
adb devices
adb -s <ID> install -r app/build/outputs/apk/release/app-release.apk
```

### Distribuer l'APK (lien temporaire)

```bash
scp app/build/outputs/apk/release/app-release.apk root@100.107.151.127:/root/apk/
ssh root@100.107.151.127 "ufw allow 8087/tcp && cd /opt/xradar-backend && APK_DIR=/root/apk PORT=8087 nohup node scripts/apk-server.js > /var/log/xradar-apk.log 2>&1 &"
```
Lien : `http://45.80.23.8:8087/` (HTTP clair, sans auth ; seuls des `.apk` dans `/root/apk`). Couper : `pkill -f apk-server.js && ufw delete allow 8087/tcp`.

### Fond de carte

`plans-style.json` est **généré** par un script Node hors repo (dernière copie : scratchpad de session, `gen-plans-style-v2.mjs`). Retoucher les teintes : `plans-palettes.json` (clair / sombre), puis rebuild.

---

## 10. Comptes et modération

### Rôles

| Rôle | Accès |
|---|---|
| `guest` invité | pseudo + mot de passe ; reconnexion par « Se connecter » (pseudo ou email) ; **supprimé 7 jours** après création |
| `guest` email | 7 jours d'essai puis restreint (carte seule) ; jamais supprimé |
| `client` | abonnement jusqu'à `subscriptionEndsAt` (parrainage = 6 mois) |
| `admin` | tout : caméras, codes de parrainage, modération, correction de limite validée seule |

Purge auto (démarrage + chaque jour) : tout `guest` sans mot de passe + invités sans email > 7 jours. Copie `data/accounts.backup-<date>.json` avant la première suppression du jour. Restreint = pas de navigation, alertes, signalements (`403 subscription required`).

Essai : **un par téléphone**. La fin du premier essai d'un invité inscrit sur un téléphone est gardée (`data/device-trials.json`, id d'appareil haché) : un invité purgé, supprimé ou recréé sur ce téléphone finit son essai à la même date.

Limites `guest` (essai compris, jour à l'heure de Paris) : **5 signalements** (`429 daily report limit`) et **7 trajets** (`429 daily trip limit` ; nouveau trajet = destination à plus de 300 m de la précédente, recalcul gratuit). `client` / `admin` : aucune limite.

### CLI (sur le VPS, jeton lu dans le service)

```bash
cd /opt/xradar-backend
node bin/xradar-accounts.js list [guest|client|admin]
node bin/xradar-accounts.js show <id>
node bin/xradar-accounts.js set <id> --role=client       # --role=admin, --name="Nom", --ban, --unban
node bin/xradar-accounts.js del <id>
```

Parrainage : un admin crée les codes dans l'app (`Menu ▸ Parrainage`), code saisi à la création du compte.
Note de confiance /5 = (confirmés + 1) / (déclarés + 2) × 5.

### Modération (jeton admin)

```bash
T=$(systemctl show xradar-backend -p Environment --value | grep -oP 'ADMIN_TOKEN=\K\S+')
curl -s -X DELETE -H "x-admin-token: $T" http://127.0.0.1:8090/api/reports/<id>          # retirer un signalement
curl -s -H "x-admin-token: $T" http://127.0.0.1:8090/api/speed-limits/<id>                # historique d'une correction
curl -s -X DELETE -H "x-admin-token: $T" http://127.0.0.1:8090/api/speed-limits/<id>     # annuler une correction
curl -s "http://127.0.0.1:8090/api/speed-limits/near?lat=48.11&lon=-1.68&radius=20000"   # corrections autour
```
Rien n'est effacé : statut `removed` / `rejected`, gardé dans l'historique.

---

## 11. Règles métier (réglages dans `backend/src/config.js`)

**Score d'un signalement** (`reports/score.js`) : `facteur × e^(−k·âge/durée) × min(1 + 0,10·√confirmations ; 1,40) × 1/(1 + 0,25·√contradictions)`, `k = ln(facteur/10)`. Sous 10 → expiré. Barème par type : `reportScore`. App : × route (0,35 hors trajet) × sens (1 / 0,75 / 0,15) × distance ; alerte affichée à **700 m** max.

**Anti-doublon** : même type, à moins de `reportMergeRadiusM` (30 m caméra … 2 km contresens), même sens (≤ 60°), même route ou route qui la touche (25 m) → voix de plus sur l'existant. Une voix par personne (auteur / fusion / confirmation / contradiction). Bouchon, contresens, voiture radar suivent leur dernière position. Voiture radar avec plaque → zone probable par plaque. Fermés gardés 90 jours.

**Votes app** : « Toujours là / Plus là » sur la carte d'alerte à 300 m ou moins, compte obligatoire.

**Correction de limitation** : propositions à 250 m, même sens, même ancienne limite = un changement. Même score (60 jours). Validé si score ≥ 60 + **3 personnes** (2 si limite inconnue) + majoritaires ; admin = immédiat. Appliqué sur les tronçons de route des soutiens (150 m), pour ce sens seulement. Un changement plus récent au même endroit remplace l'ancien.

**Limite affichée** : route choisie par distance + écart de cap + sens unique + continuité (`way`). En navigation : lue sur le trajet, sans requête.

**Services autour** : backend = 60 plus proches (KNN PostGIS, ~5 ms), horaires lus à l'heure de Paris (lib `opening_hours`, jours fériés FR). Station : horaires officiels (automate 24/24 ou créneaux déclarés) prioritaires sur OSM. Distributeur dans une banque : horaires de la banque ignorés. App (`NearbyPicker`) : ouverts + horaires inconnus d'abord, du plus proche au plus loin (carburant : station avec prix frais peut passer devant en ville, `FuelStationPicker`), 20 max ; puis « Fermés en ce moment » (8 max, seulement ceux plus proches que le dernier ouvert). « Ferme bientôt » à 30 min de la fermeture.

---

## 12. API

| Méthode | Chemin | Notes |
|---|---|---|
| GET | `/health` | état complet (§7) |
| POST | `/api/accounts/auth` `/guest` `/register` `/login` `/logout` `/verify` `/resend-verify` `/forgot` `/reset` | login : `identifier` (pseudo ou email) + `deviceId` |
| GET/PATCH/DELETE | `/api/accounts/me` · GET `/api/accounts/username-available` | Bearer ; GET : `limits` `{reportsPerDay, reportsToday, tripsPerDay, tripsToday}` (null client/admin) ; DELETE : suppression définitive par le titulaire (compte, stats, trajets, sessions, présence, avatar ; signalements et propositions de limitation gardés, anonymisés) |
| GET/POST | `/api/accounts/me/stats` `/me/trips` `/me/drive` · `/api/accounts/referrals` | Bearer (referrals : admin) ; trajet : `plannedSeconds` (estimation, null inconnue), `stops` / `stoppedSeconds` (arrêts ≥ 10 s), `events` {radarFixed, radarMobile, controlZone, camera, hazard, accident, roadwork, radarCar : nombre} |
| POST | `/api/accounts/avatar` | Bearer, base64 ≤ 4 Mo |
| GET/POST/PATCH/DELETE | `/api/admin/accounts[/:id]` | ADMIN_TOKEN |
| GET | `/api/radars/near` `/bbox` · POST `/api/radars/route` | radars fixes |
| GET | `/api/route?from=lat,lon&to=lat,lon&avoid=tolls,highways,traffic` | compte obligatoire (401), restreint 403, limite du jour 429 ; ORS ou OSRM ; `traffic` (ORS) contourne les bouchons signalés en direct (carré de 500 m autour de chacun, 100 max, sauf à moins de 500 m du départ ou de l'arrivée ; recalcul sans eux si l'itinéraire devient impossible) |
| POST | `/api/route/faster {coordinates, avoid?, sinceRerouteS?}` | Bearer, mêmes refus que `/api/route` (sans compter de trajet) ; évitement intelligent des bouchons sur **le reste** du trajet (du conducteur à l'arrivée) : TomTom chronomètre le trajet avec le trafic, les ralentissements proches (< 1 km) forment un bouchon, gardé s'il coûte ≥ 60 s (ou route fermée) ; rien n'est cherché si leur total ne peut pas atteindre le gain minimum. Sinon ORS propose des variantes (autour de tous les gros bouchons, autour du pire, ses alternatives ; cap du conducteur conservé), les doublons et celles qui traversent tous les bouchons sont écartés, TomTom chronomètre les 3 meilleures. `better` (itinéraire au format `/api/route`, `durationS` = temps TomTom avec trafic, `gainS`) seulement si le gain ≥ 3 min et ≥ 5 % du temps restant ; aucune recherche < 5 min après un recalcul trafic, gain doublé jusqu'à 15 min (anti A→B→A). Sinon `better: null` et `reason`. 503 sans clé ORS ou TomTom |
| GET | `/api/places/near?lat&lon&kind=fuel\|charging\|parking\|tobacco\|garage\|hotel\|atm[&limit][&pool=1]` | plus proches d'abord (20, `pool=1` : 60) ; `hours` (état, créneaux du jour, prochain changement), `charging`, `parking`, `stars`, `brand` ; station : prix + horaires officiels |
| POST | `/api/live/presence {inTrip}` | Bearer ; app ouverte (~30 s), compteur seulement. Anciennes apps : `/position` compte la présence (position ignorée), `/near` renvoie personne |
| GET | `/api/signs/limit?lat&lon&bearing&way` | `{v, way}` |
| POST | `/api/traffic/route {coordinates}` | Bearer ; notre tracé renvoyé à TomTom (points d'appui, 1 tous les 30 m, 1000 max) : portions ralenties **sur notre route** en mètres (`fromM`, `toM`, `level` slow/jam/heavy/closed, `delayS` réparti par longueur quand une section TomTom est coupée), `totalM`, `travelS` / `delayS` (temps TomTom avec trafic, part perdue) ; cache 60 s ; 503 sans clé, 502 si TomTom muet (429 = quota) |
| POST | `/api/signs/route {coordinates}` | panneaux + changements de limite du trajet |
| GET | `/api/signs/near` | panneaux autour |
| POST | `/api/reports` | compte obligatoire (401), restreint 403, limite du jour 429 ; 201 nouveau / 200 `merged: true` |
| GET | `/api/reports/near` | + zones voitures radar |
| POST | `/api/reports/:id/confirm` · `/deny` | compte obligatoire |
| DELETE | `/api/reports/:id` | admin |
| POST | `/api/speed-limits/reports` | compte obligatoire |
| GET | `/api/speed-limits/near` · `/:id` (admin) · DELETE `/:id` (admin) | |

---

## 13. Reste à faire

- Vrai keystore release + `versionName` (encore 0.1.0).
- Login Google / Apple, portail de paiement.
- Timer feu rouge (E4) : source de données manquante.
- Script du style de carte à rapatrier dans le repo.
- App iOS : dépôt `xradar_ios` (voir son README).
