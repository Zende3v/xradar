# Routage (itinéraires)

Le backend x_radar proxifie `GET /api/route?from=lat,lon&to=lat,lon` vers un moteur
**OSRM**. Par défaut il utilise le **serveur OSRM public gratuit** (hébergé, sans clé,
sans rien à installer) — c'est le choix retenu (VPS avec seulement 8 Go de RAM).

## Rien à héberger

`config.js` pointe déjà sur `https://router.project-osrm.org`. Il suffit donc de
**mettre à jour le backend** (il contient le proxy `/api/route`) :

```bash
# 1. Ré-envoie backend/src/ via WinSCP (écrase l'ancien)
# 2. Sur le VPS :
cd /opt/xradar-backend && sudo npm install --omit=dev && sudo systemctl restart xradar-backend
# 3. Test end-to-end (comme l'app) :
curl "http://127.0.0.1:8090/api/route?from=43.6108,3.8767&to=43.8374,4.3601"
# → {"distanceM":...,"durationS":...,"coordinates":[[lon,lat],...]}
```

C'est tout. L'app appelle la même URL publique (`https://<ton-URL-ts.net>/api/route?...`).

> Le serveur OSRM public est prévu pour un usage **léger / démo**, parfait pour
> développer et un usage perso. Pour monter en charge plus tard, deux options
> (sans changer l'app) : mettre `Environment=OSRM_URL=<ton-endpoint>` dans le
> service, en pointant sur **OpenRouteService** (clé gratuite) ou un **OSRM
> auto-hébergé** (voir l'annexe).

---

## Annexe — auto-héberger OSRM (si un jour tu as la RAM)

⚠️ Le pré-traitement France demande ~8–16 Go de RAM (sinon prends une **région** :
https://download.geofabrik.de/europe/france/). Pipeline officiel Docker :

```bash
sudo mkdir -p /opt/osrm && cd /opt/osrm
sudo curl -L -o france-latest.osm.pbf https://download.geofabrik.de/europe/france-latest.osm.pbf
docker run -t -v /opt/osrm:/data ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/car.lua /data/france-latest.osm.pbf
docker run -t -v /opt/osrm:/data ghcr.io/project-osrm/osrm-backend osrm-partition /data/france-latest.osrm
docker run -t -v /opt/osrm:/data ghcr.io/project-osrm/osrm-backend osrm-customize /data/france-latest.osrm
docker run -d --restart unless-stopped -p 127.0.0.1:5000:5000 -v /opt/osrm:/data --name osrm \
  ghcr.io/project-osrm/osrm-backend osrm-routed --algorithm mld /data/france-latest.osrm
```
Puis `Environment=OSRM_URL=http://127.0.0.1:5000` dans le service et redémarre.
