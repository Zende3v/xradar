# x_radar backend

Serves French road-safety data to the app. First data source: **fixed radars**
from the official [data.gouv dataset](https://www.data.gouv.fr/datasets/liste-des-radars-fixes-en-france)
(free, ~3,400 radars). The service resolves the **latest** published CSV via the
data.gouv API on startup and refreshes daily — no dated URL to maintain.

## Run locally

```bash
cd backend
npm install
npm start
# → x_radar backend listening on :8080
```

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Status + dataset metadata (count, last-modified, refreshed-at) |
| GET | `/api/radars/near?lat=&lon=&radius=` | Radars within `radius` m (default 3000, max 20000) of a point, nearest first |
| GET | `/api/radars/bbox?bbox=minLon,minLat,maxLon,maxLat` | Radars inside a map viewport |

Radar shape:

```json
{ "id": "12001", "type": "ETD", "vma": 70, "serviceDate": "31/10/2011 00:00", "lat": 45.361, "lon": 4.2526, "distanceM": 820 }
```

`vma` = vitesse maximale autorisée (speed limit at the radar, km/h).

## Deploy on the VPS

**Docker (recommended):**

```bash
docker build -t xradar-backend .
docker run -d --restart unless-stopped -p 8080:8080 --name xradar-backend xradar-backend
```

**Or with pm2:**

```bash
npm install --omit=dev
pm2 start src/index.js --name xradar-backend
pm2 save
```

Put it behind your reverse proxy (nginx/Caddy) with HTTPS, then point the app's
`BASE_URL` at `https://<your-domain>/`.

## Configuration

Env vars (see `.env.example`): `PORT`, `RADAR_DATASET_API_URL`, `REFRESH_INTERVAL_MS`.

## Next

Crowdsourced events (mobile radars, control zones, hazards) will be added as
POST/report endpoints backed by a database — this is where the community layer lives.
