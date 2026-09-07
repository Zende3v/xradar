// Central configuration (env-overridable).
export const config = {
  port: Number(process.env.PORT) || 8080,

  // Bind to localhost by default: the server is only reachable through the
  // reverse proxy / tunnel on the same host, never directly from the internet.
  // (The Android emulator's 10.0.2.2 also maps to the host loopback, so local
  //  dev still works.) Set HOST=0.0.0.0 only if you really need LAN exposure.
  host: process.env.HOST || '127.0.0.1',

  // data.gouv dataset "Liste des radars fixes en France".
  // We query the API to always pick the latest published CSV resource.
  radarDatasetApiUrl:
    process.env.RADAR_DATASET_API_URL ||
    'https://www.data.gouv.fr/api/1/datasets/liste-des-radars-fixes-en-france/',

  // How often to re-check data.gouv for a fresh dataset (ms). Default: daily.
  refreshIntervalMs: Number(process.env.REFRESH_INTERVAL_MS) || 24 * 60 * 60 * 1000,

  // Routing engine, proxied by /api/route. Defaults to the free public OSRM
  // demo server (hosted, no self-host, no key). Override with OSRM_URL to point
  // at a self-hosted OSRM or another OSRM-compatible endpoint later.
  osrmUrl: process.env.OSRM_URL || 'https://router.project-osrm.org',

  // OpenRouteService: preferred routing provider when a key is set (better quality,
  // supports avoiding tolls/motorways). Falls back to OSRM when ORS_API_KEY is absent.
  orsApiKey: process.env.ORS_API_KEY || null,
  orsUrl: process.env.ORS_URL || 'https://api.openrouteservice.org',

  // Safety caps for query endpoints (radius is user-tunable up to 1000 km).
  maxNearRadiusM: 1_000_000,
  defaultNearRadiusM: 3000,
  maxResults: 600,
  // Load everything within 90 km, but shrink to 40 km where it's ultra-dense
  // (too many points around the driver) to keep the map light.
  denseRadiusM: 40000,
  denseCountThreshold: 300,

  // Crowdsourced user reports (radar mobile, zone de contrôle, accident…).
  reportsFile: process.env.REPORTS_FILE || './data/reports.json',
  // How long a report stays live, per type (ms). Confirmations extend it.
  reportTtlMs: {
    voiture_radar: 30 * 60 * 1000, // 30 min — radar cars move
    camera: 24 * 60 * 60 * 1000, // 24h — semi-permanent
    radar_mobile: 90 * 60 * 1000, // 1h30
    control_zone: 3 * 60 * 60 * 1000, // 3h (police checkpoint)
    accident: 60 * 60 * 1000, // 1h
    hazard: 2 * 60 * 60 * 1000, // 2h
  },
  reportDefaultTtlMs: 90 * 60 * 1000,
  // Who may create each report type (guest < client < admin).
  reportMinRole: {
    voiture_radar: 'admin',
    camera: 'client',
    radar_mobile: 'guest',
    control_zone: 'guest',
    accident: 'guest',
    hazard: 'guest',
  },
  // Radar-car probable-zone aggregation (circle tightens with more reports).
  zoneBaseRadiusM: 2500,
  zoneMinRadiusM: 250,
  zoneMaxRadiusM: 4000,
  // Each confirm pushes the expiry out by this much; each deny pulls it in.
  reportConfirmExtendMs: 20 * 60 * 1000,
  reportDenyShortenMs: 30 * 60 * 1000,
  // A report is dropped once (denials - confirms) reaches this.
  reportDropScore: 3,
  reportMaxNearRadiusM: 1_000_000,
  reportDefaultNearRadiusM: 8000,

  // Accounts (identity + roles guest/client/admin).
  accountsFile: process.env.ACCOUNTS_FILE || './data/accounts.json',
  // Admin API token. Admin endpoints are DISABLED unless this is set (env).
  adminToken: process.env.ADMIN_TOKEN || null,
  // Session token lifetime (client/admin stay logged in).
  sessionTtlMs: 90 * 24 * 60 * 60 * 1000, // 90 days
  // Guests are deleted after this long without launching the app.
  guestMaxAgeMs: 10 * 24 * 60 * 60 * 1000, // 10 days
  // Profile pictures: stored on the VPS filesystem, served statically at /avatars.
  avatarsDir: process.env.AVATARS_DIR || './data/avatars',
  avatarMaxBytes: 2 * 1024 * 1024, // 2 MB
  // Absolute base for avatar URLs the app loads (the public tunnel host).
  publicBaseUrl: (process.env.PUBLIC_BASE_URL || 'https://debian.taila9954f.ts.net').replace(/\/$/, ''),

  // Live users on the road (shared position; visible by default, invisible mode available).
  liveTtlMs: 90 * 1000, // a shared position is "live" for 90 s
  liveDefaultRadiusM: 20000,
  liveMaxRadiusM: 200000,
  liveMaxResults: 200,
};
