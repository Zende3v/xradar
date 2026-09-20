# Migrer le backend EONA sur un autre VPS

Marche à suivre complète, de la machine neuve au basculement, sans changer une seule ligne de DNS
et sans que les apps s'en aperçoivent. Compter **une à deux heures**, dont l'essentiel en copie de
la base, et **moins de deux minutes de coupure réelle**.

Les noms utilisés ici : `ANCIEN` = le VPS actuel, `NEUF` = le nouveau. Tout se fait en root.

---

## 0. Ce qu'il faut avant de commencer

| Sur le NEUF | Pourquoi |
|---|---|
| Debian 13 (ou 12), 64 bits | Même famille que l'actuel : PostgreSQL 17 + PostGIS 3 y sont packagés |
| 4 Go de RAM au minimum, 8 Go confortable | PostGIS et le backend Node tiennent dans 2 Go ; la reconstruction hebdomadaire de la signalisation en demande bien plus |
| **40 Go de disque libre** pour une migration par copie de la base | Base 7,7 Go + son export + marge |
| **120 Go** si la machine doit aussi reconstruire la signalisation chaque dimanche | Extrait France 5,5 Go + import osm2pgsql + les deux versions du schéma |
| Accès root par SSH, et Tailscale installé | Le déploiement et l'exploitation passent par là |
| Rien qui écoute déjà sur 8090 et 9020 | Ports locaux du backend et de la page de confidentialité |

Aucun port n'a besoin d'être ouvert vers l'extérieur : tout entre par le tunnel Cloudflare, qui
est une connexion **sortante**.

### Ce qui se déplace

| Donnée | Taille | Comment |
|---|---|---|
| Base PostgreSQL `eona` | 7,7 Go (dont 4,8 Go de signalisation) | Export / import, ou reconstruction depuis OpenStreetMap |
| Comptes, statistiques, parrainage (`data/accounts.json`) | ~10 Ko | Copie |
| Photos de profil (`data/avatars/`) | ~50 Ko | Copie |
| Secrets (ADMIN_TOKEN, clés ORS, TomTom, SMTP) | 5 fichiers | Copie des drop-ins systemd |
| Identifiants du tunnel Cloudflare | 2 fichiers | Copie — **c'est ce qui évite de toucher au DNS** |
| Extrait OSM `france-latest.osm.pbf` | 5,5 Go | À ne pas copier : il se retélécharge |

---

## 1. Préparer le NEUF (l'ANCIEN continue de servir)

### 1.1 Paquets et Tailscale

```bash
apt-get update
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs postgresql postgresql-17-postgis-3 osm2pgsql osmium-tool curl python3 zip
curl -fsSL https://tailscale.com/install.sh | sh
tailscale up
```

Vérifier : `node -v` doit répondre v20.x — c'est `/usr/bin/node` qui fait tourner le service.

### 1.2 cloudflared

```bash
curl -fsSL -o /usr/local/bin/cloudflared \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
chmod +x /usr/local/bin/cloudflared
cloudflared --version
```

### 1.3 Utilisateur, dossiers, base

```bash
useradd --system --home /opt/eona-backend --shell /usr/sbin/nologin eona || true
mkdir -p /opt/eona-backend/data/avatars /var/lib/eona-signs
chown -R eona:eona /opt/eona-backend /var/lib/eona-signs

cat > /etc/postgresql/17/main/conf.d/eona.conf <<'EOF'
# EONA : machine partagée avec le backend, disque NVMe. À adapter à la RAM du NEUF :
# shared_buffers ≈ 1/8 de la RAM, effective_cache_size ≈ 1/2.
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

L'authentification est **peer** sur la socket locale : l'utilisateur système `eona` correspond au
rôle `eona`. Aucun mot de passe, aucun port de base exposé.

---

## 2. Copier la base

Deux chemins. Le premier est le bon dans presque tous les cas.

### Chemin A — export / import (recommandé, ~30 à 60 min, aucune reconstruction)

Sur l'ANCIEN, base toujours en service :

```bash
runuser -u eona -- pg_dump -d eona -Fc -Z3 -f /root/eona-$(date +%Y%m%d).dump
ls -lh /root/eona-*.dump
```

Transfert direct d'une machine à l'autre (remplacer `IP_NEUF`) :

```bash
scp /root/eona-*.dump root@IP_NEUF:/root/
```

Sur le NEUF :

```bash
runuser -u postgres -- pg_restore -d eona --no-owner --role=eona -j 4 /root/eona-*.dump
runuser -u eona -- psql -qX -d eona -c "SELECT count(*) FROM signs.sign"
runuser -u eona -- psql -qX -d eona -c "SELECT count(*) FROM crowd.report"
```

Les comptes doivent correspondre à ceux de l'ANCIEN (`signs.sign` ≈ 2 millions de lignes).

### Chemin B — reconstruire depuis OpenStreetMap (plusieurs heures)

À réserver au cas où l'export échoue, ou si on veut repartir d'une donnée fraîche. Sur le NEUF,
après l'étape 3 (le code doit être en place) :

```bash
bash /opt/eona-backend/signalisation/rebuild.sh
```

Ça télécharge l'extrait France, importe, construit, vérifie, rejoue les corrections faites à la
main et publie. Il faut **120 Go libres** et de la patience. Les signalements, comptes et
corrections, eux, ne se reconstruisent pas : ils viennent quand même de l'export du chemin A
(limité aux schémas `crowd` et `public` : `pg_dump -d eona -Fc -n crowd -n public`).

---

## 3. Installer le code sur le NEUF

Depuis le PC, à la racine du dépôt :

```bash
scp -r backend/src backend/bin backend/deploy backend/signalisation backend/scripts \
       backend/privacy backend/package.json backend/package-lock.json \
       root@IP_NEUF:/opt/eona-backend/
```

Sur le NEUF :

```bash
cd /opt/eona-backend && npm ci --omit=dev
install -m 644 deploy/eona-backend.service deploy/eona-privacy.service deploy/eona-tunnel.service \
        /etc/systemd/system/
mkdir -p /etc/systemd/system/eona-backend.service.d
chown -R eona:eona /opt/eona-backend
systemctl daemon-reload
```

---

## 4. Copier les secrets et le tunnel

Les secrets ne sont **jamais** dans git : ils vivent dans des drop-ins systemd sur l'ANCIEN.
Depuis l'ANCIEN, sans jamais les afficher :

```bash
scp /etc/systemd/system/eona-backend.service.d/*.conf root@IP_NEUF:/etc/systemd/system/eona-backend.service.d/
scp -r /root/.cloudflared root@IP_NEUF:/root/
scp /etc/cloudflared/eona.yml root@IP_NEUF:/etc/cloudflared/
```

Sur le NEUF :

```bash
chmod 600 /etc/systemd/system/eona-backend.service.d/*.conf
chmod 600 /root/.cloudflared/*
systemctl daemon-reload
```

Les cinq drop-ins attendus : `admin.conf` (ADMIN_TOKEN), `ors.conf` et `ors2.conf` (clés
OpenRouteService), `tomtom.conf`, `smtp.conf`. Si la webapp d'administration est configurée, il y
a aussi `webapp.conf` (WEBAPP_ORIGINS).

**Le tunnel garde son identifiant.** C'est lui que visent les enregistrements DNS des deux
domaines : rien à changer chez Cloudflare. En revanche, **une seule machine doit faire tourner le
tunnel à la fois** — sinon Cloudflare répartit le trafic entre les deux et une requête sur deux
tombe sur l'ancienne base.

---

## 5. Répétition générale, avant de basculer

Sur le NEUF, démarrer le backend **sans** le tunnel :

```bash
systemctl enable --now eona-backend eona-privacy
curl -s http://127.0.0.1:8090/health | python3 -m json.tool
curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:9020/
```

Le `/health` doit annoncer `"status": "ok"`, la signalisation publiée, les radars chargés, et
`routing.keys: 2`. Quelques appels de fond :

```bash
curl -s "http://127.0.0.1:8090/api/radars/near?lat=48.1173&lon=-1.6778&radius=5000" | head -c 200
curl -s "http://127.0.0.1:8090/api/signs/near?lat=48.1173&lon=-1.6778&radius=500" | head -c 200
```

Tant que le tunnel n'est pas démarré ici, l'ANCIEN continue de servir les apps : on peut
recommencer autant de fois qu'on veut.

---

## 6. Le basculement (moins de deux minutes)

1. **Sur l'ANCIEN — figer et couper** :

   ```bash
   systemctl stop eona-tunnel eona-backend
   ```

2. **Rattraper ce qui a bougé depuis l'export** — les comptes et le crowd, pas la signalisation :

   ```bash
   # sur l'ANCIEN
   runuser -u eona -- pg_dump -d eona -Fc -n crowd -f /root/crowd-delta.dump
   scp /root/crowd-delta.dump /opt/eona-backend/data/accounts.json \
       /opt/eona-backend/data/device-trials.json root@IP_NEUF:/root/
   scp -r /opt/eona-backend/data/avatars root@IP_NEUF:/root/
   ```

   ```bash
   # sur le NEUF
   systemctl stop eona-backend
   runuser -u eona -- psql -qX -d eona -c "DROP SCHEMA crowd CASCADE"
   runuser -u postgres -- pg_restore -d eona --no-owner --role=eona /root/crowd-delta.dump
   cp /root/accounts.json /root/device-trials.json /opt/eona-backend/data/
   cp -r /root/avatars/. /opt/eona-backend/data/avatars/
   chown -R eona:eona /opt/eona-backend/data
   systemctl start eona-backend
   ```

3. **Sur le NEUF — ouvrir le tunnel** :

   ```bash
   systemctl enable --now eona-tunnel
   sleep 5
   systemctl is-active eona-tunnel
   ```

4. **Vérifier depuis l'extérieur** (depuis le PC, pas depuis les VPS) :

   ```bash
   curl -s -o /dev/null -w "api %{http_code}\n" https://api.lrda-mercuriale.uk/health
   curl -s -o /dev/null -w "politique %{http_code}\n" https://confidentialite.zylo-app.fr/
   curl -s https://api.lrda-mercuriale.uk/health | python3 -m json.tool | head -20
   ```

5. **Un vrai trajet depuis un téléphone** : lancer une navigation, vérifier les alertes, la
   limite de vitesse, un signalement. C'est le seul test qui vaut.

---

## 7. Le cron de la signalisation

À installer sur le NEUF seulement une fois le basculement validé, pour que les deux machines ne
reconstruisent pas en même temps :

```bash
# sur l'ANCIEN : couper
rm -f /etc/cron.d/eona-signs

# sur le NEUF : installer
cat > /etc/cron.d/eona-signs <<'EOF'
# EONA signalisation : reconstruction hebdo depuis un extrait France frais (dimanche 03:30).
30 3 * * 0 root bash /opt/eona-backend/signalisation/rebuild.sh >> /var/lib/eona-signs/rebuild.log 2>&1
EOF
```

---

## 8. Revenir en arrière

Tant que l'ANCIEN n'a pas été effacé, le retour se fait en deux commandes :

```bash
# sur le NEUF
systemctl stop eona-tunnel eona-backend
# sur l'ANCIEN
systemctl start eona-backend eona-tunnel
```

Les apps repartent sur l'ANCIEN en quelques secondes. Seul point d'attention : ce qui a été écrit
sur le NEUF pendant l'essai (nouveaux comptes, signalements, rapports de bug) reste sur le NEUF.
D'où l'intérêt de basculer à une heure creuse et de vérifier vite.

**Garder l'ANCIEN au moins une semaine** avant de le libérer. Une fois la décision prise :

```bash
systemctl disable --now eona-backend eona-privacy eona-tunnel
```

---

## 9. Après la migration

- Mettre à jour la procédure de déploiement : le `scp` du README vise l'ancienne IP.
- `ssh root@<IP Tailscale du NEUF>` remplace l'ancienne adresse dans les mémos.
- Les sauvegardes `/root/eona-src-backup-*.tgz` restent sur l'ANCIEN : les recopier si elles
  comptent.
- Supprimer l'export de base une fois tout validé : `rm /root/eona-*.dump` des deux côtés.
- Vérifier le dimanche suivant que la reconstruction hebdomadaire s'est bien déroulée :
  `tail -30 /var/lib/eona-signs/rebuild.log`.
