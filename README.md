# EONA — installer, déployer, lancer, surveiller, réparer

Guide unique du projet. App Android (Kotlin/Compose) + backend Node/Express + PostgreSQL/PostGIS sur un VPS Debian, exposé en HTTPS par Cloudflare Tunnel. L'app iOS vit dans son propre dépôt : `git@github.com:Zende3v/xradar_ios.git (dépôt pas encore renommé)` (guide dans son README).

---

## 0. Mémo express

| Quoi | Où / commande |
|---|---|
| URL publique backend | `https://api.lrda-mercuriale.uk/` (Cloudflare Tunnel `eona`, service `eona-tunnel` → `127.0.0.1:8090`). Politique : `https://confidentialite.zylo-app.fr` (même tunnel → `127.0.0.1:9020`) |
| SSH VPS | `ssh root@193.168.146.56` (clé SSH ; le mot de passe est coupé) |
| Code backend VPS | `/opt/eona-backend` (user `eona`) |
| Service | `systemctl status eona-backend` |
| Logs live | `journalctl -u eona-backend -f` |
| Santé | `curl -s http://127.0.0.1:8090/health` |
| Base | PostgreSQL 17 + PostGIS, base `eona` (`runuser -u eona -- psql -d eona`) |
| Rebuild signalisation | cron dimanche 03:30, log `/var/lib/eona-signs/rebuild.log` |
| Relancer le backend | `systemctl restart eona-backend` |
| Build APK | `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk` |

---

## 1. Architecture

```
App Android ──HTTPS──▶ Cloudflare Tunnel ──▶ backend Node :8090 (systemd, user eona)
   │                                              │
   ├─ tuiles carte : Stadia Maps (direct)          ├─ PostgreSQL/PostGIS « eona »
   └─ adresses : api-adresse.data.gouv.fr          │    ├─ schéma signs  : routes + panneaux + services autour (rebuild hebdo depuis OSM)
                                                   │    ├─ schéma search : lieux nommés OSM de la recherche (rebuild hebdo)
                                                   │    ├─ schéma crowd  : signalements + corrections de limite
                                                   │    └─ schéma routing : journal de routage + banc (mesures phase 1 Valhalla)
                                                   ├─ data/accounts.json : comptes, stats, parrainage
                                                   ├─ data/ors-usage.json, here-usage.json : compteurs ORS (jour) / HERE (jour, mois)
                                                   ├─ data/avatars/      : photos de profil
                                                   ├─ data.gouv : radars fixes (téléchargé au démarrage + chaque jour)
                                                   ├─ prix-carburants : flux officiel, prix + horaires (toutes les 10 min)
                                                   ├─ OpenRouteService (si ORS_API_KEY) sinon OSRM public : itinéraires
                                                   └─ HERE Traffic v7 : trafic sur le trajet, évitement des bouchons
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
│  ├─ routing/            itinéraires : engine.js (ORS/OSRM), faster.js, log.js (journal), bench.js (banc),
│  │                      schema.sql (schéma routing, appliqué à chaque démarrage)
│  ├─ traffic/            HERE (here.js), compteur HERE (budget.js), data.gouv (datagouv.js), vitesses et bouchons des conducteurs (speeds.js, crowd.js)
│  ├─ accounts/ live/ radars/ fuel/
│  ├─ places/             services autour (PostGIS) + horaires (hours.js, lib opening_hours)
│  ├─ search/             recherche : index EONA (local.js), BAN, Photon en secours, classement (rank.js)
│  ├─ reports/            signalements (anti-doublon, votes, score.js)
│  ├─ speedlimits/        corrections de limitation
│  └─ signs/postgis.js    limite sous le conducteur, panneaux du trajet
├─ signalisation/         pipeline OSM → PostGIS (import.sh, style.lua, build.sql, checks.sql, publish.sql, rebuild.sh)
├─ search/                index de recherche OSM → PostGIS (import.sh, style.lua, build.sql, publish.sql, rebuild.sh)
├─ bin/eona-accounts.js CLI admin comptes
├─ bin/eona-eta-report.js rapport ETA (lecture seule)
├─ bin/eona-geodata-rebuild.sh  chaîne hebdo : signalisation, Valhalla, recherche (cron dimanche 03:30)
├─ deploy/                unit systemd + setup-admin.sh
└─ scripts/apk-server.js  serveur temporaire de téléchargement APK
app/                      app Android
```

---

## 2. Le VPS

- Debian 13, 8 vCPU, 16 Go RAM, NVMe 180 Go (RAID 10). Migré depuis l'ancien VPS le 20/09/2026 — voir [GUIDE.md](GUIDE.md).
- Accès : **SSH par clé** (`193.168.146.56`). Machine dédiée à EONA, rien d'autre ne tourne dessus.
- `ufw` actif. EONA n'ouvre **aucun** port : tout passe par Cloudflare Tunnel (connexion sortante). Backend (8090) et politique (9020) n'écoutent que sur `127.0.0.1`.
- Services EONA lancés au boot : `eona-backend`, `eona-privacy`, `eona-tunnel`, `postgresql`, `cron` (tous `enabled`). Un reboot remet tout en route seul.

---

## 3. Installation complète (VPS neuf)

Tout en root.

### 3.1 Paquets

```bash
apt-get update
# Node 20 (NodeSource) — le service utilise /usr/bin/node
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs postgresql postgresql-17-postgis-3 osm2pgsql osmium-tool curl python3
```

### 3.2 Utilisateur, dossiers, code

```bash
useradd --system --home /opt/eona-backend --shell /usr/sbin/nologin eona || true
mkdir -p /opt/eona-backend/data/avatars /var/lib/eona-signs
```

Depuis le PC (dossier du repo) :

```bash
scp -r backend/src backend/bin backend/deploy backend/signalisation backend/search backend/valhalla backend/scripts backend/package.json backend/package-lock.json root@193.168.146.56:/opt/eona-backend/
```

Sur le VPS :

```bash
cd /opt/eona-backend && npm ci --omit=dev
chown -R eona:eona /opt/eona-backend /var/lib/eona-signs
```

### 3.3 PostgreSQL / PostGIS

```bash
cat > /etc/postgresql/17/main/conf.d/eona.conf <<'EOF'
# EONA : machine 8 Go partagée avec le backend, disque NVMe.
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
runuser -u postgres -- psql -c "CREATE ROLE eona LOGIN"
runuser -u postgres -- createdb -O eona eona
runuser -u postgres -- psql -d eona -c "CREATE EXTENSION postgis"
```

Auth : socket local + **peer** (user système `eona` = rôle `eona`). Aucun mot de passe, aucun port DB exposé (écoute `127.0.0.1` seulement).

### 3.4 Secrets (drop-ins systemd, jamais dans git)

```bash
cd /opt/eona-backend && bash deploy/setup-admin.sh   # installe le .service, crée ADMIN_TOKEN (admin.conf), redémarre
```

Autres secrets, un fichier par sujet dans `/etc/systemd/system/eona-backend.service.d/` :

```bash
install -m 600 /dev/null /etc/systemd/system/eona-backend.service.d/ors.conf
cat > /etc/systemd/system/eona-backend.service.d/ors.conf <<'EOF'
[Service]
Environment=ORS_API_KEY=ta_cle_openrouteservice
Environment=ORS_API_KEY_2=ta_cle_de_secours
EOF
cat > /etc/systemd/system/eona-backend.service.d/smtp.conf <<'EOF'
[Service]
Environment=SMTP_HOST=smtp.gmail.com
Environment=SMTP_PORT=587
Environment=SMTP_USER=adresse@gmail.com
Environment="SMTP_PASS=xxxx xxxx xxxx xxxx"
Environment="SMTP_FROM=eona adresse@gmail.com"
EOF
systemctl daemon-reload && systemctl restart eona-backend
```

⚠️ Valeur avec espaces = **guillemets** autour de toute la ligne `Environment="…"`, sinon mail muet sans erreur.
⚠️ Ne jamais afficher `systemctl cat eona-backend` en public : secrets en clair.

| Variable | Rôle | Défaut |
|---|---|---|
| `PORT` / `HOST` | écoute locale | `8090` / `127.0.0.1` (via le .service) |
| `ADMIN_TOKEN` | API admin + CLI + modération | absent = API admin coupée |
| `ORS_API_KEY` / `ORS_URL` | itinéraires OpenRouteService | clé absente = OSRM public ; URL défaut `https://api.heigit.org/openrouteservice` (ancienne `api.openrouteservice.org` coupée le 28/09/2026 ; prod : drop-in `ors-url.conf`) |
| `ORS_API_KEY_2` (et `_3`) | clé de secours : elle prend le relais dès que la précédente est refusée (quota du jour épuisé, trop d'appels d'un coup), jusqu'à minuit UTC | — |
| `ORS_DAILY_BUDGET` | appels ORS par clé et par jour, sous le quota du plan gratuit (2000) | `1500` |
| `ORS_USAGE_FILE` | compteurs du jour des clés ORS (clé nommée par un hash court, jamais en clair) | `./data/ors-usage.json` |
| `OSRM_URL` | OSRM de repli | `https://router.project-osrm.org` |
| `HERE_USAGE_FILE` | compteurs persistants, mois par type, quotas compte ; écriture avant appel | `./data/here-usage.json` |
| `HERE_MONTHLY_BUDGET_EUR` | plafond mensuel estimé ; zéro bloque appels payants | 5 |
| `HERE_TRAFFIC_EUR_PER_1000` / `HERE_IMPORT_EUR_PER_1000` | hypothèses tarifaires configurables, franchise supposée zéro | 2.33 / 4.66 |
| `HERE_PRICE_MARGIN` | marge sur estimation ; minimum 1 | 1.2 |
| `HERE_FASTER_ACCOUNT_DAILY_MAX` | contrôles détour maximum par compte/jour, persistants | 30 |
| `PGHOST` / `PGDATABASE` | base | `/var/run/postgresql` / `eona` |
| `SMTP_HOST` `SMTP_PORT` `SMTP_USER` `SMTP_PASS` `SMTP_FROM` | vérif email, mot de passe oublié | absent = pas de mail |
| `PUBLIC_BASE_URL` | base des URLs d'avatars (les anciennes en `ts.net` sont réécrites au chargement) | `https://api.lrda-mercuriale.uk` |
| `ACCOUNTS_FILE` / `AVATARS_DIR` | comptes / photos | `./data/accounts.json` / `./data/avatars` |
| `ACCOUNTS_BACKUP_KEEP_DAYS` | copies `accounts.backup-<jour>.json` (avant purge des invités) gardées | 14 j |
| `GUEST_TRIAL_MS` | essai compte email | 7 j |
| `GUEST_LIFETIME_MS` | durée de vie compte invité | 7 j |
| `HERE_API_KEY` | trafic HERE Traffic v7 sur le trajet (drop-in `here.conf`, jamais versionnée) | absent = pas de trafic live |
| `HERE_DEEP_COVERAGE` / `HERE_DAILY_CAP` / `HERE_ACCOUNT_DAILY_MAX` | Deep Coverage (tarif Advanced Traffic) ; plafond du jour (aucun par défaut) ; rafraîchissements HERE par compte et par jour | coupé / aucun / 150 |
| `DATAGOUV_ENABLED` | collecte des flux DIR (data.gouv, Bison Futé) : vitesses QTV et événements toutes les 6 min, stations par jour | actif ; `0` = coupé |
| `DATAGOUV_SPEEDS_URL` / `DATAGOUV_EVENTS_URL` / `DATAGOUV_STATIONS_URL` | adresses des flux DIR | tipi.bison-fute.gouv.fr |
| `HERE_TRAFFIC_URL` | adresse de HERE Traffic v7 | `https://data.traffic.hereapi.com/v7` |
| `DEVICE_TRIALS_FILE` | fin du premier essai par téléphone | `./data/device-trials.json` |
| `GUEST_REPORTS_PER_DAY` / `GUEST_TRIPS_PER_DAY` | limites invité par jour | 5 / 7 |
| `REFERRAL_SUBSCRIPTION_MONTHS` | mois offerts par parrainage | 6 |
| `ACCOUNT_TRIP_HISTORY_MAX` | trajets gardés par compte | 200 |
| `RADAR_DATASET_API_URL` / `REFRESH_INTERVAL_MS` | dataset radars / refresh | data.gouv / 24 h |
| `FUEL_FEED_URL` / `FUEL_REFRESH_INTERVAL_MS` | prix carburants | roulez-eco / 10 min |
| `PLACE_USER_AGENT` | identité HTTP vers les données ouvertes | `EONA/1.0 (+url)` |
| `PLACE_TABLE` | services autour : `signs_next.place` = tester un build non publié (staging) | `signs.place` |

Plafond HERE : estimation serveur, tarifs du compte à confirmer. Cache exact 60 s ; erreurs jamais conservées.
Compteur illisible ou écriture impossible : appels HERE bloqués. `/health.traffic.here` expose budget, cache et refus import.

Générer un secret : `openssl rand -hex 32`.

### 3.5 Service

```bash
systemctl daemon-reload
systemctl enable --now eona-backend
```

Le .service : `Restart=on-failure` (relance seul 5 s après un crash), `ProtectSystem=strict`, seul `data/` inscriptible.

### 3.6 Signalisation (premier build + cron)

```bash
bash /opt/eona-backend/bin/eona-geodata-rebuild.sh  # téléchargement + signalisation, puis Valhalla, puis recherche (§5, §5 bis)
cat > /etc/cron.d/eona-signs <<'EOF'
# EONA : reconstruction hebdo depuis un extrait France frais (dimanche 03:30).
30 3 * * 0 root /bin/bash /opt/eona-backend/bin/eona-geodata-rebuild.sh >> /var/lib/eona-signs/rebuild.log 2>&1
EOF
```

### 3.7 HTTPS public

Cloudflare Tunnel `eona` (`cloudflared` déjà dans `/usr/local/bin`, identifiants dans `/root/.cloudflared/<tunnel id>.json`, jamais versionnés) :

```bash
cloudflared tunnel create eona                      # une fois ; donne le <tunnel id>
install -D -m 644 deploy/cloudflared-eona.yml /etc/cloudflared/eona.yml   # y mettre le <tunnel id>
install -m 644 deploy/eona-tunnel.service /etc/systemd/system/
systemctl daemon-reload && systemctl enable --now eona-tunnel
```

DNS dans le tableau de bord Cloudflare : `api` (zone `lrda-mercuriale.uk`) et `confidentialite` (zone `zylo-app.fr`) en CNAME proxifié vers `<tunnel id>.cfargotunnel.com`. `cloudflared tunnel route dns` ne marche que pour `zylo-app.fr` (le `cert.pem` est lié à cette zone) : pour `lrda-mercuriale.uk`, passer par le tableau de bord.

### 3.8 Vérifier

```bash
systemctl is-active eona-backend postgresql
curl -s http://127.0.0.1:8090/health
curl -s "http://127.0.0.1:8090/api/signs/limit?lat=48.1113&lon=-1.6778&bearing=0"
curl -s https://api.lrda-mercuriale.uk/health        # depuis n'importe où (Cloudflare)
```

---

## 4. Déployer une mise à jour du backend

Depuis le PC, dossier du repo.

1. Sauvegarder le code en place (retour arrière) :
   ```bash
   ssh root@193.168.146.56 "cd /opt/eona-backend && tar czf /opt/eona-backend-src-backup-\$(date +%Y%m%d-%H%M).tgz src package.json package-lock.json"
   ```
2. Envoyer le code (le `rm` retire les fichiers supprimés dans le repo) :
   ```bash
   ssh root@193.168.146.56 "rm -rf /opt/eona-backend/src"
   scp -r backend/src backend/package.json backend/package-lock.json root@193.168.146.56:/opt/eona-backend/
   ```
   Pipeline signalisation ou recherche modifié → envoyer aussi `backend/signalisation` ou `backend/search`.
   Scripts modifiés → aussi `backend/bin`.
3. Sur le VPS :
   ```bash
   cd /opt/eona-backend
   npm ci --omit=dev                      # seulement si package.json a changé
   chown -R eona:eona src node_modules package.json package-lock.json signalisation search bin
   systemctl restart eona-backend
   curl -s http://127.0.0.1:8090/health
   journalctl -u eona-backend -n 30 --no-pager
   ```

Schéma `crowd` modifié (`src/crowd/schema.sql`) → appliqué seul au redémarrage (idempotent, jamais de `DROP`). Pareil pour le schéma `routing` (`src/routing/schema.sql`).

### Instance de test (optionnel, avant la prod)

`/opt/eona-backend-staging` : copie du backend sur le port **8099**, lancée par `bash /opt/eona-backend-staging/run.sh` (ADMIN_TOKEN=`staging-admin`).
⚠️ Elle utilise **la même base** que la prod : ses tests écrivent dans `crowd`. Nettoyer après :
```bash
runuser -u eona -- psql -d eona -c "TRUNCATE crowd.report, crowd.report_voice, crowd.speed_limit_change, crowd.speed_limit_voice, crowd.speed_limit_event"
```
Seulement si la prod n'a pas encore de vraies données dans `crowd`. Arrêt : `pkill -f "[n]ode src/index.js --stag[i]ng"`.

---

## 5. Signalisation (OSM → PostGIS)

Chaîne `rebuild.sh` (cron hebdo) :

1. **Téléchargement** `france-latest.osm.pbf` Geofabrik + contrôle md5 → `/var/lib/eona-signs/`.
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

Rejouer seulement les services sur un `signs_next` déjà construit : `runuser -u eona -- psql -d eona -f places.sql` (il nettoie un essai interrompu), puis `checks.sql`. Tester avant publication : instance staging avec `PLACE_TABLE=signs_next.place`.

Échec à n'importe quelle étape = version publiée intacte.

```bash
tail -50 /var/lib/eona-signs/rebuild.log                        # dernier rebuild
bash /opt/eona-backend/signalisation/rebuild.sh                  # relancer à la main
runuser -u eona -- psql -d eona -c "SELECT * FROM signs.meta"  # version publiée
```

**Revenir à la version précédente** (build publié mais faux) :
```bash
runuser -u eona -- psql -d eona -c "BEGIN; ALTER SCHEMA signs RENAME TO signs_broken; ALTER SCHEMA signs_prev RENAME TO signs; COMMIT;"
runuser -u eona -- psql -d eona -c "DROP SCHEMA signs_broken CASCADE"
```

Sans `ALTER`, la base ne touche jamais `crowd` : signalements et corrections survivent à chaque rebuild.

---

## 5 bis. Recherche (OSM → PostGIS)

Moteur de recherche EONA depuis le 30/09 (Photon avant, TomTom avant le 29/09). `GET /api/search` fusionne :

- **lieux nommés** : index EONA, schéma `search` (`src/search/local.js`) ;
- **adresses** : Base Adresse Nationale (API publique).

Photon ne sert plus qu'en secours, si l'index manque ou si la base ne répond pas (log `[search] index — … — Photon instead`).

Chaîne `search/rebuild.sh`, lancée chaque semaine par `bin/eona-geodata-rebuild.sh` après la signalisation et Valhalla, sur le même extrait déjà vérifié (~7 min au total) :

1. **import.sh** (~3 min) : osmium garde commerces, services, écoles, gares, aéroports, aires, lieux-dits, communes… ; osm2pgsql (`style.lua`) charge dans le schéma `search_osm` les objets **nommés** seulement. Écartés : bancs, poubelles, distributeurs automatiques, parkings privés, etc.
2. **build.sql** (~4 min) → schéma `search_next`. Table `poi` : 2,4 M lieux (30/09), 766 Mo avec index.
   - Doublons fusionnés : même type, même nom, à moins de 150 m.
   - Commune tirée des polygones admin_level 8.
   - `doc` : texte replié (sans accents ni ponctuation) des noms, de la marque, de la commune et du code postal.
   - `weight` : importance de 0 à 1 (ville, aéroport avec code IATA, gare, lien Wikidata, surface).
   - Index : trigrammes (`pg_trgm`) sur `doc`, trigrammes partiel sur les lieux notables (`weight >= 0.6`), GiST sur la position.
   - Refus si moins de 500 k lieux ou moins de 20 k villes et villages.
3. **publish.sql** : bascule atomique `search` → `search_prev`, `search_next` → `search`. Puis suppression de `search_osm`.

Requête : chaque mot tapé de 3 lettres ou plus doit ressembler (`<%`, similarité de mot ≥ 0,6) à un mot des noms ou de la commune. Un mot en cours de frappe ou une faute d'une lettre passe donc. Deux listes :

- les lieux à moins de 60 km, 40 au plus ;
- les lieux notables de toute la France, 15 au plus.

`rank.js` classe ensuite ces lieux avec la BAN : distance, nom, type, importance. Un lieu notable nommé comme tapé (« marseille ») compte comme proche.

Temps mesurés le 30/09 : 30 à 100 ms (jusqu'à 250 ms à froid), plafond 2,5 s par recherche.

```bash
tail -30 /var/lib/eona-signs/search-build.log                 # premier build (30/09)
bash /opt/eona-backend/search/rebuild.sh                      # relancer à la main (verrou partagé avec la chaîne hebdo)
runuser -u eona -- psql -d eona -c "SELECT * FROM search.meta"
```

**Revenir à l'index précédent** :

```bash
runuser -u eona -- psql -d eona -c "BEGIN; ALTER SCHEMA search RENAME TO search_broken; ALTER SCHEMA search_prev RENAME TO search; COMMIT;"
```

---

## 6. Données et sauvegardes

| Donnée | Où | Précieux ? |
|---|---|---|
| Comptes, stats, parrainage | `data/accounts.json` | **OUI** |
| Photos de profil | `data/avatars/` | oui |
| Signalements + votes, corrections de limite + historique | PostGIS schéma `crowd` | **OUI** |
| Journal de routage (90 j), mesures du banc | PostGIS schéma `routing` | oui (mesures phase 1) |
| Compteurs ORS (jour) / HERE (jour, mois) | `data/ors-usage.json`, `data/here-usage.json` | non (repartent à zéro chaque jour, HERE chaque mois aussi) |
| Routes + panneaux | PostGIS schémas `signs`, `signs_prev` | non (rebuild) |
| Extrait OSM | `/var/lib/eona-signs/france-latest.osm.pbf` | non (retéléchargé) |
| Copies auto des comptes avant purge | `data/accounts.backup-<date>.json` | oui |
| Ancien système (NDJSON, JSON d'avant PostGIS) | `data/archive/` | non (supprimable) |
| Sauvegardes du code | `/opt/eona-backend-src-backup-*.tgz` | garder les 2-3 dernières |

Sauvegarde quotidienne : **03:10**, cron `/etc/cron.d/eona-backup`, rétention **14 jours**.
Script versionné : `backend/bin/eona-backup.sh`. Journal : `/var/log/eona-backup.log`.
Archive `crowd-<date>.dump` : schémas **crowd et routing**. Archive `data-<date>.tgz` : comptes,
essais par appareil, avatars, réglages et compteurs présents. Archives privées, vérifiées avant
publication ; sauvegardes concurrentes refusées. Aucune copie hors VPS pour l'instant.

Installation ou mise à jour, après accord d'Arthur :
```bash
cd /opt/eona-backend
install -o root -g root -m 755 bin/eona-backup.sh /usr/local/bin/eona-backup.sh
install -o root -g root -m 644 deploy/eona-backup.cron /etc/cron.d/eona-backup
/usr/local/bin/eona-backup.sh
```

Le script fixe son `PATH` : cron ne fournit pas `/usr/sbin`, emplacement de `runuser` sur Debian.
Même protection dans les scripts de signalisation et leur cron versionné.

Contrôle des archives, sans restauration :
```bash
pg_restore --list /var/backups/eona/crowd-AAAA-MM-JJ.dump > /dev/null
tar tzf /var/backups/eona/data-AAAA-MM-JJ.tgz > /dev/null
```

Restauration, après accord d'Arthur. Arrête le backend et remplace les données actuelles :
```bash
systemctl stop eona-backend
runuser -u postgres -- pg_restore -d eona --clean --if-exists < /var/backups/eona/crowd-AAAA-MM-JJ.dump
tar xzf /var/backups/eona/data-AAAA-MM-JJ.tgz -C /opt/eona-backend/data && chown -R eona:eona /opt/eona-backend/data
systemctl start eona-backend
```

Lancer aussi une sauvegarde avant migration, restauration ou gros déploiement. Vérifier chaque
matin présence des deux archives du jour. Archive lisible ne prouve pas restauration complète :
test de restauration isolé reste à organiser.

---

## 7. Surveiller

```bash
systemctl status eona-backend eona-tunnel eona-privacy postgresql --no-pager
journalctl -u eona-backend -f                          # logs en direct
journalctl -u eona-backend --since "-1 h" --no-pager | grep -iE "error|unavailable|failed"
curl -s http://127.0.0.1:8090/health | python3 -m json.tool
curl -s -o /dev/null -w "%{http_code}
" https://api.lrda-mercuriale.uk/health
tail -20 /var/lib/eona-signs/rebuild.log
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
| `routing.usage` | `day` (UTC), `keys[]` : `used` (appels du jour), `blockedUntil` (clé écartée jusqu'à, sinon `null`) ; survit au redémarrage |
| `traffic.here` | `day`, `used`, `byUse` {`eta`, `faster`}, `byKind` {`flow`, `incidents`, `import`}, `month`, `monthUsed`, `dailyCap`, `accountDailyMax`, `deepCoverage` ; survit au redémarrage. `traffic.speeds` : échantillons de vitesse des conducteurs en mémoire, trajets distincts |
| `search` | `places` : `eona` (index EONA) ou `photon` (index absent) ; `index` : `builtAt`, `places` (§5 bis), relu toutes les 10 min |
| `fuel.ready` / `lastError` | `true` / `null` |
| `memoryMB` | ~180–400 |

Requêtes utiles :
```bash
runuser -u eona -- psql -d eona <<'SQL'
SELECT type, count(*) FROM crowd.report WHERE status = 'live' GROUP BY type;
SELECT status, count(*) FROM crowd.speed_limit_change GROUP BY status;
SELECT nspname, pg_size_pretty(sum(pg_total_relation_size(c.oid))) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE nspname IN ('signs', 'signs_prev', 'crowd', 'osm') GROUP BY nspname;
SQL
```

Mémoire au repos : backend ~180 Mo, PostgreSQL ~1 Go de cache. Disque : `signs` ~4,7 Go (x2 avec `signs_prev`), pic ~8 Go en plus pendant un rebuild.

---

## 7 bis. Mesures du routage (phase 1 Valhalla)

Plan : `backend/PLAN-VALHALLA.md`, décisions : `backend/VALHALLA-DECISIONS.md`. Rien ne change pour les conducteurs.

**Compteurs** ORS et HERE : `/health` (§7), jour UTC (HERE : mois aussi, sa facturation), fichiers `data/ors-usage.json` et `data/here-usage.json` (écriture ~1 s après chaque appel, fichier temporaire renommé). Un redémarrage garde le jour en cours.

**Délais** : ORS et OSRM coupés à 10 s (`orsTimeoutMs`, `osrmTimeoutMs` dans `config.js`, à calibrer en phase 2 ; les apps abandonnent à 15 s) → 502.

**Journal de routage** : `routing.route_log`, une ligne par réponse de `/api/route` (cache et erreurs compris ; pas les 401) et de `/faster`. Ni compte ni coordonnée. Purge > 90 jours (`routeLogKeepDays`), au plus une fois par heure.

```bash
runuser -u eona -- psql -d eona <<'SQL'
-- latence p50 / p95 et erreurs par jour
SELECT at::date, kind, count(*), percentile_cont(ARRAY[0.5, 0.95]) WITHIN GROUP (ORDER BY latency_ms) AS p50_p95,
       count(*) FILTER (WHERE status >= 400) AS erreurs, count(*) FILTER (WHERE uturn_start) AS demi_tours
FROM routing.route_log WHERE cached IS NOT TRUE GROUP BY 1, 2 ORDER BY 1 DESC, 2;
SQL
```

**Banc** (D1.7) : a mesuré nos routes contre TomTom jusqu'au 29/09 (TomTom retiré). Plus de passage ; historique lisible : `GET /api/admin/bench/runs`, table `routing.bench_run`.

Lire : `GET /api/admin/bench/runs?since=…` (API-WEBAPP.md §6 ter) ou :

```bash
runuser -u eona -- psql -d eona -c "SELECT at, slot, trip_id, ok, our_tomtom_s - best_tomtom_s AS ecart_s, uturn_start, error FROM routing.bench_run ORDER BY at DESC LIMIT 30"
```

**Rapport ETA** (D1.2 à D1.6), lecture seule, n'affiche ni libellé ni id ni compte :

```bash
cd /opt/eona-backend
node bin/eona-eta-report.js data/accounts.json          # tableau
node bin/eona-eta-report.js data/accounts.json --json   # JSON
```

- Gardés : `arrived` vrai, départ pas à la main, `departedAt` et `etaChecks` présents. Exclus comptés par raison.
- Erreur = arrivée réelle (pauses après le relevé retirées) − arrivée affichée ; > 0 = arrivé plus tard qu'annoncé. Bonne si |erreur| ≤ min(8 min, max(2 min, 10 % du temps restant réel)).
- Deux variantes : arrêts incertains retirés comme des pauses / gardés comme conduite.
- Tranches : durée conduite (< 20, 20-60, > 60 min) × pointe / hors pointe (lun-ven 7-10 h et 16-20 h, sam 10-19 h, heure de Paris ; constante en tête du script), mode d'ETA, moteur, version. Par relevé 0/25/50/75 % : n, taux de bonnes, intervalle de Wilson 95 %, erreur médiane, biais médian.

**Checklist de l'équipe** : `backend/CHECKLIST-TRAJETS.md`.

---

## 7 ter. Valhalla : préparation locale phase 2

État au 26/09 : backend production reste phase 1 ORS. Code Valhalla et scripts prêts pour validation locale,
**sans installation Valhalla ni déploiement backend phase 2**. Procédure : [backend/valhalla/README.md](backend/valhalla/README.md).

- ORS par défaut ; `VALHALLA_ENABLED=1` nécessaire pour toute requête Valhalla.
- Réglage admin `routingEngine` : `ors`, `admins`, `all`. Retour immédiat via `PUT /api/admin/routing/engine`.
- Réglage admin `trafficDatagouv` (D2.6) : l'ETA affichée utilise data.gouv ou non ; les apps calculent les deux. `PUT /api/admin/traffic/datagouv` `{"enabled":true|false}`.
- Ombre après réponse ; aucune coordonnée dans mesures 90 jours. Tracés divergents admin seulement, 30 jours.
- `/faster` garde fonctionnement ORS actuel en mode `ors`. En secours Valhalla → ORS, détour coupé : `reason: "fallback"`.
- `/health` enrichi avec moteur, carte, disjoncteur, compteurs de secours et file ombre. Champs phase 1 gardés.
- API, variables et limites : [backend/API-WEBAPP.md](backend/API-WEBAPP.md), section routage.

Script sauvegarde et correction PATH cron déjà installés le 26/09 à 02:13, sans redémarrage backend.
Nouvelle chaîne dimanche `eona-geodata-rebuild.sh` reste locale. Cron production lance encore signalisation seule.
Ne remplacer cron qu'après accord et premier build Valhalla validé ; conserver un seul lancement hebdomadaire.

Tests locaux ne valident pas moteur réel, SQL/PostGIS, charge France, p95 ou frontière belge.
Chaque installation et redémarrage VPS demande accord Arthur. Mesures réelles consignées dans journal avant bascule.

## 8. En cas de panne

Le backend relance seul après un crash (5 s). Un reboot relance tout. Sinon :

| Symptôme | Vérifier | Réparer |
|---|---|---|
| App : « Réseau indisponible » partout | `curl -s https://api.lrda-mercuriale.uk/health` depuis le PC | voir lignes suivantes |
| Backend arrêté / boucle de redémarrage | `systemctl status eona-backend` ; `journalctl -u eona-backend -n 80 --no-pager` | erreur de code → retour arrière (ci-dessous) ; `EADDRINUSE` → un autre process tient 8090 (`ss -ltnp \| grep 8090`) |
| `/health` OK en local, KO en public | `journalctl -u eona-tunnel -n 50` | `systemctl restart eona-tunnel` |
| `signs.published: null`, erreurs 503 signs/reports | `systemctl status postgresql` ; `journalctl -u postgresql@17-main -n 50` | `systemctl restart postgresql` puis `systemctl restart eona-backend` |
| Limites / panneaux faux après un dimanche | `tail -80 /var/lib/eona-signs/rebuild.log` ; `SELECT * FROM signs.meta` | retour à `signs_prev` (§5) |
| Rebuild échoué | log : download, md5, osm2pgsql, `checks` | cause réseau → relancer `rebuild.sh` ; `checks` refuse → extrait OSM douteux, attendre le suivant (version publiée intacte) |
| Disque plein | `df -h /` ; `du -sh /var/lib/postgresql /var/lib/eona-signs /opt/eona-backend/data` | `DROP SCHEMA IF EXISTS osm CASCADE` ; `DROP SCHEMA IF EXISTS signs_prev CASCADE` ; vider `data/archive/`, vieux `*.tgz` |
| RAM saturée | `free -h` ; `top` | `systemctl restart eona-backend` ; rebuild en cours = normal (osm2pgsql) |
| Comptes perdus / `accounts.json` cassé | `journalctl … \| grep accounts` | `systemctl stop eona-backend` → copier `data/accounts.backup-<date>.json` (ou sauvegarde §6) sur `accounts.json` → `chown eona:eona` → start |
| Mails (vérif, mot de passe oublié) ne partent pas | logs `[mail]` | `smtp.conf` : guillemets autour des valeurs avec espaces |
| API admin 503 | `systemctl show eona-backend -p Environment \| grep -c ADMIN_TOKEN` | `bash deploy/setup-admin.sh` |
| Itinéraires lents / en erreur | `/health` → `routing.provider` ; logs `[route]` | clé ORS (`ors.conf`) ; quota ORS dépassé → attendre ou retirer la clé (bascule OSRM public) |
| Carte vide dans l'app | — | clé Stadia (`local.properties`, §9) invalide ou quota Stadia |

**Retour arrière du code** :
```bash
cd /opt/eona-backend
ls -t /opt/eona-backend-src-backup-*.tgz | head -3
rm -rf src && tar xzf /opt/eona-backend-src-backup-AAAAMMJJ-HHMM.tgz
npm ci --omit=dev && chown -R eona:eona src node_modules package.json package-lock.json
systemctl restart eona-backend
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
scp app/build/outputs/apk/release/app-release.apk root@193.168.146.56:/root/apk/
ssh root@193.168.146.56 "ufw allow 8087/tcp && cd /opt/eona-backend && APK_DIR=/root/apk PORT=8087 nohup node scripts/apk-server.js > /var/log/eona-apk.log 2>&1 &"
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
cd /opt/eona-backend
node bin/eona-accounts.js list [guest|client|admin]
node bin/eona-accounts.js show <id>
node bin/eona-accounts.js set <id> --role=client       # --role=admin, --name="Nom", --ban, --unban
node bin/eona-accounts.js del <id>
```

Parrainage : un admin crée les codes dans l'app (`Menu ▸ Parrainage`), code saisi à la création du compte.
Note de confiance /5 = (confirmés + 1) / (déclarés + 2) × 5.

### Modération (jeton admin)

```bash
T=$(systemctl show eona-backend -p Environment --value | grep -oP 'ADMIN_TOKEN=\K\S+')
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
| GET/PATCH/DELETE | `/api/accounts/me` · GET `/api/accounts/username-available` | Bearer ; GET : `limits` `{reportsPerDay, reportsToday, tripsPerDay, tripsToday}` (null client/admin), `canChangeUsername`, `usernameChangeableAt` ; PATCH `{username?, avatarUrl?}` : photo client/admin, **pseudo seulement client avec accès actif** (403 sinon), 1 fois / 7 j (429 + `nextAt`), libre (409 `username taken`), ni invalide ni réservé (400 ; admin, support, moderateur…, tout « eona… », comparés sans `_ .` ni chiffres) ; l'ancien pseudo reste réservé 30 j à son titulaire ; username-available (Bearer facultatif : son ancien pseudo compte libre) ; DELETE : suppression définitive par le titulaire (compte, stats, trajets, sessions, présence, avatar ; signalements et propositions de limitation gardés, anonymisés) |
| GET/POST | `/api/accounts/me/stats` `/me/trips` `/me/drive` · `/api/accounts/referrals` | Bearer (referrals : admin) ; trajet : `plannedSeconds` (estimation, null inconnue), `stops` / `stoppedSeconds` (arrêts ≥ 10 s), `events` {radarFixed, radarMobile, controlZone, camera, hazard, accident, roadwork, radarCar : nombre} ; mesures phase 1 (facultatives, apps récentes) : `arrived`, `departedAt`, `manualStart`, `plannedMeters`, `pausedSeconds`, `uncertainSeconds`, `etaChecks` [{at 0/25/50/75, shownAt, arrivalAt, pausedBefore, uncertainBefore}], `recalcCount`, `fasterCount`, `engines`, `mapVersion`, `appVersion`, `platform`, `etaMode`, `trafficSources` (détail API-WEBAPP.md §5) |
| POST | `/api/accounts/avatar` | Bearer, base64 ≤ 4 Mo |
| GET/POST/PATCH/DELETE | `/api/admin/accounts[/:id]` | ADMIN_TOKEN |
| GET | `/api/radars/near` `/bbox` · POST `/api/radars/route` | radars fixes ; chacun avec `quietCourse` (sens où les conducteurs l'ont dit « pas dans mon sens » : 2 votes à 45° près, 1 admin suffit), null = sonne dans les deux sens |
| POST | `/api/radars/:id/not-my-way {course}` | Bearer ; « Pas dans mon sens » : un vote par radar et compte (`crowd.radar_vote`, 365 j), admin compte 2 ; 2 votes à moins de 45° l'un de l'autre = `quietCourse` ; réponse `{quietCourse}` |
| GET | `/api/route?from=lat,lon&to=lat,lon&avoid=tolls,highways,traffic&heading=0-360` | compte obligatoire (401), restreint 403, limite du jour 429 ; `heading` facultatif (cap voiture en roulant, D4.4) : Valhalla part dans ce sens (tolérance 45°, rayon 50 m), ORS `bearings`, pas de cache ; ORS ou OSRM (`engine` : `ors`/`osrm`, `mapVersion` : date de carte ORS ou null) ; étapes : `type`, `modifier`, `location`, `exit`, `name`, plus panneaux d'autoroute (Valhalla seul, 28/09) `exitNumber` (numéro de sortie ou null), `towardRefs` (routes, ex. `N 104`) et `toward` (villes), 3 au plus, vides sinon ; chaque réponse → `routing.route_log` ; `traffic` (ORS, anciennes versions des apps seulement : iOS et Android passent par `/api/route/faster`) contourne les bouchons signalés en direct (carré de 500 m autour de chacun, 100 max, sauf à moins de 500 m du départ ou de l'arrivée ; recalcul sans eux si l'itinéraire devient impossible) |
| POST | `/api/route/faster {coordinates, avoid?, sinceRerouteS?, etaS?}` | Bearer, mêmes refus que `/api/route` (sans compter de trajet) ; une vérification par compte et par minute (429) ; évitement intelligent des bouchons sur **le reste** du trajet : trafic live (HERE + conducteurs) sur le reste, bouchons proches (< 1 km) regroupés, gardés s'ils coûtent ≥ 60 s (ou route fermée) ; **détour local** : le moteur trace des variantes (autour de tous, autour du pire, ses alternatives) sur une fenêtre ; temps = temps moteur + retards live ; `better` (itinéraire au format `/api/route`, temps moteur) seulement si le gain ≥ 3 min et ≥ 5 % de `etaS` (l'ETA de l'app), ou toujours autour d'une **route fermée** ; aucune recherche < 5 min après un recalcul trafic, gain doublé jusqu'à 15 min. Sinon `better: null` et `reason`. 503 sans HERE ni ORS quand ORS trace |
| POST | `/api/bugs {category, description, steps?, app, context?, screenshot?}` · GET/PATCH (admin) | Capture JPEG base64 facultative ≤ 1 Mio ; corps ≤ 2 Mio ; `context` navigation avec route ≤ 600 points ; réponse `screenshotSaved` ; liste inclut `hasScreenshot` |
| GET | `/api/bugs/:id/screenshot` | Admin seulement ; JPEG privé sans cache, 403 sinon, 404 si absent |
| GET | `/api/admin/bench/runs?since=&limit=` | admin ; banc, lecture seule depuis le 30/09 (§7 bis) |
| GET | `/api/search?q=&lat=&lon=&limit=` | Bearer, 40 par minute et par compte (429) ; lieux nommés (index EONA, §5 bis ; Photon s'il manque) + adresses (BAN), fusionnés et classés (distance, nom, type) ; `{count, results[]}` : `id`, `name`, `subtitle`, `lat`, `lon`, `city`, `address`, `postcode`, `category`, `source` (`osm`, `ban`), `distanceM` ; jamais gardé (`no-store`) |
| GET | `/api/places/near?lat&lon&kind=fuel\|charging\|parking\|tobacco\|garage\|hotel\|atm[&limit][&pool=1]` | plus proches d'abord (20, `pool=1` : 60) ; `hours` (état, créneaux du jour, prochain changement), `charging`, `parking`, `stars`, `brand` ; station : prix + horaires officiels |
| POST | `/api/live/presence {inTrip}` | Bearer ; app ouverte (~30 s), compteur seulement. Anciennes apps : `/position` compte la présence (position ignorée), `/near` renvoie personne |
| GET | `/api/signs/limit?lat&lon&bearing&way` | `{v, way}` |
| POST | `/api/traffic/speeds {tripKey, samples[]}` | Bearer ; vitesses du conducteur en trajet (« Aide au trafic partagé ») : `lat`, `lon`, `course`, `speedKmh`, `limitKmh?`, `t` ; anonymes (clé aléatoire du trajet, jamais le compte), 20 par envoi, un envoi toutes les 30 s par compte (429), gardées 30 min en mémoire |
| POST | `/api/traffic/route {coordinates, aheadM?, live?, raw?, sources?}` | Bearer ; ralentissements **sur notre route** en mètres (`fromM`, `toM`, `level` slow/jam/heavy/closed, `delayS`, `kind`, `source`) : `here` (HERE Traffic v7, vitesses et incidents dans un couloir autour du reste du trajet, 2 requêtes, plus `travelS` : le temps HERE de la route envoyée, trafic compris, par Route Import, 1 requête, la base de l'ETA depuis le 30/09 ; pas demandé si les vitesses EONA couvrent déjà 80 % du trajet, une fois par minute et 150 fois par jour par compte au plus), `crowd` (bouchons signalés, sondes, vitesses partagées), `datagouv` (DIR, derrière l'interrupteur) ; `live` dit si HERE a répondu (`tomtom` aussi, pour les apps d'avant HERE, qui reçoivent HERE sous le nom `tomtom`), `eonaCoverage` la part couverte par les conducteurs ; `raw: true` : chaque source entière ; `check` si `/api/route/faster` vaut la peine. Les apps en tirent l'ETA dynamique (D2.1) |
| POST | `/api/traffic/probe {lat, lon, bearing, speedKmh, limitKmh}` · `/api/traffic/probe/dismiss` | Bearer ; sonde « Partager les ralentissements » : vitesse < 60 % d'une limitation ≥ 70 km/h (400 sinon), 1 par minute et par conducteur (429) ; en mémoire 30 min sous un pseudonyme changé chaque jour ; 3 conducteurs en 10 min à moins de 1 km, même sens (±45°) → signalement « Bouchon » `system` (fusionné s'il existe, au plus 1 fois / 5 min par endroit) ; réponse `{known}` (bouchon déjà connu là : pas de question au conducteur) ; `dismiss` = « Non », retire ses sondes récentes |
| POST | `/api/signs/route {coordinates}` | panneaux + changements de limite du trajet |
| GET | `/api/signs/near` | panneaux autour |
| POST | `/api/reports` | compte obligatoire (401), restreint 403, limite du jour 429 ; 201 nouveau / 200 `merged: true` ; `prompted: true` (« Oui » à « Ralentissement du trafic ? », sa sonde juste à côté) : bouchon hors quota invité |
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
- App iOS : dépôt `eona_ios` (voir son README).
