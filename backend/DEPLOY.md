# Migrer le backend EONA sur un autre VPS

Marche à suivre à faire **à la main**, WinSCP pour les fichiers et SSH pour les commandes, sans
changer une seule ligne de DNS et sans que les apps s'en aperçoivent.

Compter **1 h 30 à 2 h**, dont l'essentiel en copie de la base, et **moins de deux minutes de
coupure réelle**.

Dans tout le document : `ANCIEN` = le VPS actuel, `NEUF` = le nouveau (Debian 13, 8 vCPU, 16 Go,
180 Go NVMe). Tout se fait en root.

---

## 0. Avant de commencer

### La règle d'or

**Le tunnel Cloudflare ne doit tourner que sur une seule machine à la fois.** Les deux domaines
visent l'identifiant du tunnel, pas une IP. Si les deux machines l'ouvrent en même temps,
Cloudflare partage le trafic entre elles et une requête sur deux tombe sur l'ancienne base.

### Ce qui se déplace

| Donnée | Taille | Comment |
|---|---|---|
| Base PostgreSQL `eona` | 7,7 Go (dont 4,8 Go de signalisation) | Export / import |
| Comptes, statistiques, parrainage (`data/accounts.json`) | ~10 Ko | Copie |
| Essais gratuits par téléphone (`data/device-trials.json`) | ~1 Ko | Copie |
| Photos de profil (`data/avatars/`) | ~50 Ko | Copie |
| Secrets (jeton admin, clés ORS, TomTom, SMTP) | 5 fichiers | Copie |
| Identifiants du tunnel Cloudflare | 2 fichiers | Copie — **c'est ce qui évite de toucher au DNS** |
| Extrait OSM `france-latest.osm.pbf` | 5,5 Go | **Ne pas copier** : il se retéléchargera |
| Sauvegardes `/root/eona-src-backup-*.tgz` | quelques Mo | Facultatif |

### L'accès SSH, maintenant que Tailscale disparaît

L'API et la page de confidentialité passent par Cloudflare : aucun port entrant n'est nécessaire
pour elles, le tunnel est une connexion **sortante**. Reste l'administration. Trois options, de
la meilleure à la moins bonne :

1. **SSH à travers Cloudflare** (aucun port ouvert) : le tunnel publie aussi le port 22 en interne
   et l'accès se fait avec `cloudflared access ssh`. C'est la seule qui laisse la machine
   totalement fermée. Voir la section 9.
2. **SSH public par clé uniquement** : mot de passe désactivé, `PermitRootLogin prohibit-password`,
   et `fail2ban`. Simple et correct.
3. SSH public avec mot de passe : à éviter. Un VPS neuf prend des milliers de tentatives par jour.

⚠️ **Le mot de passe root donné par l'hébergeur doit être changé dès la première connexion**, et ne
doit jamais être écrit dans un fichier du dépôt, un ticket ou un message. Une fois la clé SSH en
place, coupe l'authentification par mot de passe.

---

## 1. Préparer le NEUF (l'ANCIEN continue de servir)

### 1.1 Première connexion et verrouillage

```bash
passwd                     # nouveau mot de passe root, tout de suite
apt-get update && apt-get -y upgrade
timedatectl set-timezone Europe/Paris
```

Copier ta clé publique SSH (depuis WinSCP ou `ssh-copy-id`), puis :

```bash
sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
systemctl restart ssh
apt-get install -y fail2ban && systemctl enable --now fail2ban
```

Ne ferme pas la session en cours tant que tu n'as pas vérifié qu'une **nouvelle** connexion par
clé fonctionne.

### 1.2 Paquets

```bash
curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
apt-get install -y nodejs postgresql postgresql-17-postgis-3 osm2pgsql osmium-tool curl python3 zip ufw
node -v      # doit afficher v20.x : c'est /usr/bin/node qui fait tourner le service
```

Pare-feu — rien d'entrant sauf SSH :

```bash
ufw allow 22/tcp
ufw --force enable
ufw status
```

### 1.3 cloudflared

```bash
curl -fsSL -o /usr/local/bin/cloudflared \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
chmod +x /usr/local/bin/cloudflared
cloudflared --version
```

### 1.4 Utilisateur, dossiers, base

```bash
useradd --system --home /opt/eona-backend --shell /usr/sbin/nologin eona || true
mkdir -p /opt/eona-backend/data/avatars /var/lib/eona-signs
chown -R eona:eona /opt/eona-backend /var/lib/eona-signs

cat > /etc/postgresql/17/main/conf.d/eona.conf <<'EOF'
# EONA — réglages pour 16 Go de RAM, 8 vCPU, NVMe.
shared_buffers = 4GB
effective_cache_size = 10GB
maintenance_work_mem = 2GB
work_mem = 128MB
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

### 2.1 Exporter, sur l'ANCIEN (la base continue de servir pendant ce temps)

```bash
runuser -u eona -- pg_dump -d eona -Fc -Z3 -f /root/eona-$(date +%Y%m%d).dump
ls -lh /root/eona-*.dump
```

### 2.2 Transférer

Le plus rapide, d'une machine à l'autre, si l'ANCIEN peut joindre le NEUF :

```bash
scp /root/eona-*.dump root@IP_DU_NEUF:/root/
```

Sinon, par WinSCP : télécharger le `.dump` sur le PC, puis l'envoyer sur le NEUF dans `/root/`.
Compter le temps d'un fichier de 2 à 3 Go dans les deux sens.

### 2.3 Importer, sur le NEUF

```bash
runuser -u postgres -- pg_restore -d eona --no-owner --role=eona -j 4 /root/eona-*.dump
runuser -u eona -- psql -qX -d eona -c "SELECT count(*) FROM signs.sign"
runuser -u eona -- psql -qX -d eona -c "SELECT count(*) FROM signs.road"
runuser -u eona -- psql -qX -d eona -c "SELECT count(*) FROM crowd.report"
```

Les comptes doivent correspondre à ceux de l'ANCIEN (≈ 2 millions de panneaux). `pg_restore`
affiche des avertissements sur les propriétaires : c'est normal avec `--no-owner`.

---

## 3. Installer le code sur le NEUF

Par WinSCP, depuis le dossier `backend/` du dépôt sur ton PC, envoyer dans `/opt/eona-backend/` :

```
src/  bin/  deploy/  signalisation/  scripts/  privacy/  package.json  package-lock.json
```

Ne pas envoyer `node_modules/` ni `data/` : le premier se réinstalle, le second se copie à la
bascule.

Puis sur le NEUF :

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

Les secrets ne sont **jamais** dans git. Depuis l'ANCIEN (ou par WinSCP, en gardant les mêmes
chemins) :

```bash
scp /etc/systemd/system/eona-backend.service.d/*.conf root@IP_DU_NEUF:/etc/systemd/system/eona-backend.service.d/
scp -r /root/.cloudflared root@IP_DU_NEUF:/root/
scp /etc/cloudflared/eona.yml root@IP_DU_NEUF:/etc/cloudflared/
```

Sur le NEUF :

```bash
mkdir -p /etc/cloudflared
chmod 600 /etc/systemd/system/eona-backend.service.d/*.conf
chmod 700 /root/.cloudflared && chmod 600 /root/.cloudflared/*
systemctl daemon-reload
ls /etc/systemd/system/eona-backend.service.d/
```

Attendu : `admin.conf` (jeton admin), `ors.conf` et `ors2.conf` (clés OpenRouteService),
`tomtom.conf`, `smtp.conf`, plus `webapp.conf` si la webapp d'administration est déjà déclarée.

Ces fichiers contiennent des secrets en clair : ne jamais les ouvrir dans une capture d'écran, ne
jamais lancer `systemctl cat eona-backend` devant quelqu'un.

---

## 5. Répétition générale, avant de basculer

Sur le NEUF, démarrer le backend **sans** le tunnel :

```bash
systemctl enable --now eona-backend eona-privacy
sleep 3
curl -s http://127.0.0.1:8090/health | python3 -m json.tool
curl -s -o /dev/null -w "politique %{http_code}\n" http://127.0.0.1:9020/
```

À vérifier dans le `/health` :

- `"status": "ok"` ;
- `signs.published` : la date de la signalisation, la même que sur l'ANCIEN ;
- `radars.count` : autour de 3300 ;
- `routing` : `provider: "ors"`, `keys: 2`, `ready: 2` ;
- `traffic.provider: "tomtom"`.

Quelques appels de fond :

```bash
curl -s "http://127.0.0.1:8090/api/radars/near?lat=48.1173&lon=-1.6778&radius=5000" | head -c 200
curl -s "http://127.0.0.1:8090/api/signs/near?lat=48.1173&lon=-1.6778&radius=500" | head -c 200
```

Tant que le tunnel n'est pas démarré ici, l'ANCIEN sert encore les apps : on peut recommencer
autant de fois qu'on veut.

---

## 6. La bascule (moins de deux minutes)

**1. Sur l'ANCIEN — couper** :

```bash
systemctl stop eona-tunnel eona-backend
```

**2. Rattraper ce qui a bougé depuis l'export** — comptes et crowd, pas la signalisation :

```bash
# ANCIEN
runuser -u eona -- pg_dump -d eona -Fc -n crowd -f /root/crowd-delta.dump
scp /root/crowd-delta.dump root@IP_DU_NEUF:/root/
scp /opt/eona-backend/data/accounts.json /opt/eona-backend/data/device-trials.json root@IP_DU_NEUF:/root/
scp -r /opt/eona-backend/data/avatars root@IP_DU_NEUF:/root/
```

```bash
# NEUF
systemctl stop eona-backend
runuser -u eona -- psql -qX -d eona -c "DROP SCHEMA crowd CASCADE"
runuser -u postgres -- pg_restore -d eona --no-owner --role=eona /root/crowd-delta.dump
cp /root/accounts.json /root/device-trials.json /opt/eona-backend/data/
cp -r /root/avatars/. /opt/eona-backend/data/avatars/
chown -R eona:eona /opt/eona-backend/data
systemctl start eona-backend
sleep 3 && curl -s -o /dev/null -w "health %{http_code}\n" http://127.0.0.1:8090/health
```

**3. Sur le NEUF — ouvrir le tunnel** :

```bash
systemctl enable --now eona-tunnel
sleep 5
systemctl is-active eona-tunnel
journalctl -u eona-tunnel -n 20 --no-pager
```

Les journaux doivent montrer quatre connexions établies vers Cloudflare.

**4. Vérifier depuis l'extérieur**, depuis ton PC, pas depuis les VPS :

```bash
curl -s -o /dev/null -w "api %{http_code}\n" https://api.lrda-mercuriale.uk/health
curl -s -o /dev/null -w "politique %{http_code}\n" https://confidentialite.zylo-app.fr/
```

**5. Un vrai trajet depuis un téléphone** : navigation, alertes, limite de vitesse, un
signalement, une photo de profil qui s'affiche. C'est le seul test qui compte.

---

## 7. Le cron de la signalisation

À basculer **après** validation, pour que les deux machines ne reconstruisent jamais en même
temps :

```bash
# ANCIEN : couper
rm -f /etc/cron.d/eona-signs
```

```bash
# NEUF : installer
cat > /etc/cron.d/eona-signs <<'EOF'
# EONA signalisation : reconstruction hebdo depuis un extrait France frais (dimanche 03:30).
30 3 * * 0 root bash /opt/eona-backend/signalisation/rebuild.sh >> /var/lib/eona-signs/rebuild.log 2>&1
EOF
```

La reconstruction télécharge un extrait France de 5,5 Go, importe, construit, vérifie, rejoue les
corrections faites à la main puis publie. Elle demande **120 Go libres** au pic : sur 180 Go, ça
passe, mais ne laisse pas traîner de gros fichiers dans `/root`. Le dimanche suivant :

```bash
tail -30 /var/lib/eona-signs/rebuild.log
df -h /
```

---

## 8. Revenir en arrière

Tant que l'ANCIEN existe, le retour prend deux commandes :

```bash
# NEUF
systemctl stop eona-tunnel eona-backend
# ANCIEN
systemctl start eona-backend eona-tunnel
```

Les apps repartent sur l'ANCIEN en quelques secondes. Seul point d'attention : ce qui a été écrit
sur le NEUF entre-temps (nouveaux comptes, signalements, rapports de bug) reste sur le NEUF. D'où
l'intérêt de basculer à une heure creuse et de vérifier tout de suite.

---

## 9. SSH sans port ouvert (facultatif, recommandé)

Le tunnel qui sert déjà l'API peut aussi porter SSH. Sur le NEUF, ajouter dans
`/etc/cloudflared/eona.yml`, **avant** la règle `http_status:404` :

```yaml
  - hostname: ssh.lrda-mercuriale.uk
    service: ssh://127.0.0.1:22
```

Puis `systemctl restart eona-tunnel`, et dans le tableau de bord Cloudflare : un CNAME `ssh` vers
`<identifiant du tunnel>.cfargotunnel.com`, proxifié, et une règle Cloudflare Access qui
n'autorise que ton adresse e-mail.

Depuis le PC (cloudflared installé sur Windows), dans `~/.ssh/config` :

```
Host eona
  HostName ssh.lrda-mercuriale.uk
  User root
  ProxyCommand cloudflared access ssh --hostname %h
```

Une fois `ssh eona` vérifié : `ufw delete allow 22/tcp`. Plus aucun port ouvert sur la machine.

---

## 10. Retirer l'ANCIEN du service

**Le garder au moins une semaine**, éteint mais intact. Ensuite :

```bash
# ANCIEN
systemctl disable --now eona-backend eona-privacy eona-tunnel
rm -f /etc/cron.d/eona-signs
```

Si tu veux récupérer quelque chose avant de le libérer : `/root/eona-src-backup-*.tgz`
(sauvegardes du code), `/opt/eona-backend/data/archive` (322 Mo d'anciennes données),
`/opt/eona-backend/data/accounts.backup-*.json` (sauvegardes de comptes).

Ne surtout pas toucher à ce qui n'est pas EONA sur cette machine : d'autres projets y tournent
(Caddy, lazarus-server, medocs).

---

## 11. Après la migration

- Le README et les mémos visent encore l'ancienne adresse Tailscale : remplacer par la nouvelle
  façon de se connecter (section 9, ou IP publique par clé).
- Supprimer les exports une fois tout validé : `rm /root/eona-*.dump /root/crowd-delta.dump` des
  deux côtés.
- Changer le mot de passe root fourni par l'hébergeur s'il ne l'a pas déjà été, et vérifier que
  l'authentification par mot de passe est bien coupée : `sshd -T | grep -i passwordauthentication`.
- Si la webapp d'administration a une adresse, la déclarer sur le NEUF :
  `/etc/systemd/system/eona-backend.service.d/webapp.conf` avec `Environment=WEBAPP_ORIGINS=…`,
  puis `systemctl daemon-reload && systemctl restart eona-backend`.
