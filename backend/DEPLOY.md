# Déploiement du backend x_radar sur un VPS Debian

Objectif : faire tourner le backend en permanence + l'exposer en **HTTPS sécurisé**
pour que l'app le joigne. Léger, robuste, et l'IP du VPS reste cachée.

---

## 1. Quels fichiers envoyer (WinSCP)

Envoie **tout le dossier `backend/`** du projet, **SAUF** `node_modules/` et `.env`.
Concrètement, ces éléments suffisent :

```
package.json
src/            (tout le dossier)
deploy/         (les fichiers de ce dossier)
```

Destination sur le VPS : **`/opt/xradar-backend/`**
(si WinSCP se connecte en `root`. Sinon, envoie dans ton dossier perso puis
`sudo mv` vers `/opt/xradar-backend`.)

---

## 2. Sur le VPS (SSH) — installation

```bash
# Node.js 22 (dépôt officiel NodeSource)
sudo apt-get update
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt-get install -y nodejs
node -v          # doit afficher v22.x

# Utilisateur dédié (ne peut pas se connecter, plus sûr)
sudo useradd --system --shell /usr/sbin/nologin xradar || true

# Dépendances + droits
cd /opt/xradar-backend
sudo npm install --omit=dev
sudo chown -R xradar:xradar /opt/xradar-backend

# Test rapide (Ctrl+C pour arrêter)
sudo -u xradar node src/index.js
# → "x_radar backend listening on 127.0.0.1:8080"
# → "[radars] loaded 3309 radars ..."
```

---

## 3. Lancer en service (systemd) — démarre au boot, redémarre si crash

```bash
sudo cp /opt/xradar-backend/deploy/xradar-backend.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now xradar-backend
sudo systemctl status xradar-backend      # doit être "active (running)"
curl http://127.0.0.1:8090/health         # doit renvoyer du JSON "ready":true
```

> ⚠️ **Port déjà pris ?** Si `status` reste en `activating (auto-restart)` avec
> `EADDRINUSE`, un autre service occupe le port. Le service utilise **8090** par
> défaut ; s'il est aussi pris, choisis-en un libre :
> ```bash
> sudo ss -ltnp | grep :8090          # vérifie qu'il est libre (aucune ligne)
> sudo sed -i 's/PORT=8090/PORT=8091/' /etc/systemd/system/xradar-backend.service
> sudo systemctl daemon-reload && sudo systemctl restart xradar-backend
> ```

Le serveur n'écoute que sur `127.0.0.1` → **impossible à joindre directement
depuis Internet**. On l'expose proprement à l'étape suivante.

---

## 4. Exposer en HTTPS (choisis UNE option)

### Option C — Tailscale Funnel  ⭐ recommandé si tu n'as pas de domaine

Gratuit, **sans nom de domaine**, HTTPS automatique, **aucun port ouvert**, et
l'IP du VPS reste cachée (le trafic passe par Tailscale). Tu obtiens une URL
publique en `https://<machine>.<ton-tailnet>.ts.net/`.

```bash
# 1. Installer Tailscale
curl -fsSL https://tailscale.com/install.sh | sh

# 2. Connecter le VPS (crée un compte gratuit si besoin — le lien s'affiche)
sudo tailscale up

# 3. Exposer le backend (port local 8090) en HTTPS public
sudo tailscale funnel --bg 8090
#   → affiche : https://<machine>.<tailnet>.ts.net/
#   (si Funnel/HTTPS ne sont pas activés, la commande t'affiche un lien
#    d'admin pour cocher "HTTPS certificates" et "Funnel", puis relance-la)

sudo tailscale funnel status      # doit lister ton URL publique
```

Pare-feu : **tu peux tout fermer sauf SSH** (aucun port entrant nécessaire).
```bash
sudo apt-get install -y ufw && sudo ufw allow OpenSSH && sudo ufw enable
```

L'URL `https://<machine>.<tailnet>.ts.net/` est ce que tu mettras dans l'app.

---

### Option A — Cloudflare Tunnel (cache le VPS aussi, mais demande un domaine sur Cloudflare)

Il faut un domaine géré par **Cloudflare** (plan gratuit OK). L'IP du VPS reste
invisible, HTTPS automatique, rien à ouvrir dans le pare-feu.

```bash
# Installer cloudflared
curl -L https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o cloudflared.deb
sudo dpkg -i cloudflared.deb

cloudflared tunnel login                       # ouvre un lien → autorise ton domaine
cloudflared tunnel create xradar               # note le TUNNEL_ID affiché
cloudflared tunnel route dns xradar api.tondomaine.fr

# Config : copie deploy/cloudflared-config.example.yml vers /etc/cloudflared/config.yml
# puis remplace <TUNNEL_ID> et le domaine, et place le fichier <TUNNEL_ID>.json
# (généré par "tunnel create") dans /etc/cloudflared/.
sudo cloudflared service install
sudo systemctl enable --now cloudflared
```

Pare-feu (tu peux tout fermer sauf SSH) :
```bash
sudo apt-get install -y ufw
sudo ufw allow OpenSSH
sudo ufw enable
```

### Option B — Caddy + domaine (reverse proxy classique)

Plus simple si tu n'utilises pas Cloudflare, mais l'IP du VPS est visible via le DNS.

```bash
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy

# Édite /etc/caddy/Caddyfile (voir deploy/Caddyfile.example) :
#   api.tondomaine.fr { reverse_proxy 127.0.0.1:8080 }
sudo systemctl reload caddy

# Pare-feu : n'ouvre que SSH + 80 + 443
sudo apt-get install -y ufw
sudo ufw allow OpenSSH && sudo ufw allow 80,443/tcp && sudo ufw enable
```

Le domaine `api.tondomaine.fr` doit pointer (A/AAAA) vers l'IP du VPS.

---

## 5. Vérifier + brancher l'app

```bash
curl https://api.tondomaine.fr/health                       # depuis n'importe où
curl "https://api.tondomaine.fr/api/radars/near?lat=43.6126&lon=3.8671&radius=3000"
```

Dans l'app : `app/build.gradle.kts`, buildType `release`, remplace
`BACKEND_BASE_URL` par ton URL HTTPS — ex. `"https://<machine>.<tailnet>.ts.net/"`
(Tailscale) ou `"https://api.tondomaine.fr/"` (domaine).
(En debug, l'app utilise déjà ton backend local via l'émulateur.)

---

## Mettre à jour plus tard

Ré-envoie `src/` (+ `package.json` si changé) via WinSCP, puis :
```bash
cd /opt/xradar-backend && sudo npm install --omit=dev && sudo systemctl restart xradar-backend
```

## Sécurité — récap (léger mais correct)
- Serveur lié à `127.0.0.1` uniquement (jamais exposé directement).
- Tourne sous un utilisateur dédié `xradar` sans shell, service durci (systemd).
- HTTPS géré par le proxy/tunnel ; avec Cloudflare Tunnel, **l'IP du VPS est cachée** et aucun port entrant n'est ouvert.
- Pare-feu `ufw` : n'ouvre que le strict nécessaire.
- (Les données radars sont publiques : pas besoin d'authentification. Si tu veux
  limiter l'usage plus tard, on ajoutera un token — c'est trivial.)
