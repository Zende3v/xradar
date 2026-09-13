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

### Speed-limit maintenance

| Method | Path | Description |
|---|---|---|
| GET | `/api/signs/limit?lat=&lon=&bearing=` | Limit where the driver is: OSM, with the validated changes for that way on top |
| POST | `/api/speed-limits/reports` | `{ lat, lon, newKmh, bearing?, displayedKmh?, displayedSource?, deviceId? }` — propose the limit a sign shows (account or device required) |
| GET | `/api/speed-limits/near?lat=&lon=&radius=` | Pending and validated changes around a point |
| GET | `/api/speed-limits/:id` | One change with its proposals and events (admin) |
| DELETE | `/api/speed-limits/:id` | Stop applying / reject a change, kept in the history (admin) |

Proposals at one spot (same way, same former limit) are one change, scored with the report
formula (`src/reports/score.js`). It is applied once its score reaches the "high" band with
enough distinct people (3 on a known limit, 2 where none is known; an admin applies at once),
then stays until a later change replaces it. Stored in `data/speed-limits.json`.

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
