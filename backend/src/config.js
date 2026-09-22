// Central configuration (env-overridable).
const MIN = 60 * 1000;
const H = 60 * MIN;
const D = 24 * H;

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

  // Guards on /api/route, so no client can spend the day's routing quota on its own (a
  // recalculation loop, a retry that never gives up). Same trip asked again within
  // routeCacheMs, from routeCacheFromM of the same start to routeCacheToM of the same
  // destination: the answer already given comes back, without asking the routing engine.
  routeCacheMs: 60 * 1000,
  routeCacheFromM: 75,
  routeCacheToM: 30,
  routeCacheMax: 300,
  // What one account may really compute: a trip and its recalculations, never a loop.
  routePerMinute: 10,
  routePerHour: 120,
  routeAccountsMax: 2000,
  // Calls to ORS a day, under the free plan's own quota (2000, reset at midnight UTC): the rest
  // is the margin that keeps the rerouting around traffic, and the other drivers, working.
  // Per key: two keys give 3000 routes a day.
  orsDailyBudget: Number(process.env.ORS_DAILY_BUDGET) || 1500,

  // Routing engine, proxied by /api/route. Defaults to the free public OSRM
  // demo server (hosted, no self-host, no key). Override with OSRM_URL to point
  // at a self-hosted OSRM or another OSRM-compatible endpoint later.
  osrmUrl: process.env.OSRM_URL || 'https://router.project-osrm.org',

  // OpenRouteService: preferred routing provider when a key is set (better quality,
  // supports avoiding tolls/motorways). Falls back to OSRM when ORS_API_KEY is absent.
  orsApiKey: process.env.ORS_API_KEY || null,
  // A spare key (ORS_API_KEY_2, then _3) takes over while the one before it is refused — its
  // quota spent for the day, or too many calls at once. Set in the service environment only.
  orsApiKeys: [process.env.ORS_API_KEY, process.env.ORS_API_KEY_2, process.env.ORS_API_KEY_3].filter(Boolean),
  // A key refused for too many calls at once waits this long; refused for anything else (key
  // disabled, plan changed), this long. A spent quota waits for midnight UTC on its own.
  orsKeyPauseMs: 60 * 1000,
  orsKeyBlockMs: 60 * MIN,
  orsUrl: process.env.ORS_URL || 'https://api.openrouteservice.org',

  // TomTom Traffic on the route being followed (key from the service environment, never versioned).
  tomtomApiKey: process.env.TOMTOM_API_KEY || null,
  tomtomUrl: process.env.TOMTOM_URL || 'https://api.tomtom.com',
  // Our route goes back to TomTom as supporting points: one every 30 m at least, 1000 at most.
  trafficSupportingSpacingM: 30,
  trafficMaxSupportingPoints: 1000,
  trafficMaxPoints: 50_000,
  // Drivers on the same route share one answer this long.
  trafficCacheMs: 60 * 1000,
  trafficTimeoutMs: 12 * 1000,
  // Smart rerouting around traffic (POST /api/route/faster). A variant replaces the route only
  // when TomTom times it, with traffic, at least rerouteMinGainS and rerouteMinGainRatio of the
  // time left faster. No new route within rerouteCooldownS of the last one, and twice the gain
  // (rerouteStickyFactor) until rerouteStickyS: no back and forth between two routes.
  rerouteMinGainS: 3 * 60,
  rerouteMinGainRatio: 0.05,
  rerouteCooldownS: 5 * 60,
  rerouteStickyS: 15 * 60,
  rerouteStickyFactor: 2,
  // A slowdown worth going around on its own (sections closer than rerouteJamGapM make one).
  rerouteJamMinDelayS: 60,
  rerouteJamGapM: 1000,
  // Variants timed by TomTom per check, at most (one TomTom request each).
  rerouteMaxVariants: 3,

  // Drivers' own traffic, besides TomTom (traffic/crowd.js). A "Bouchon" report weighs on
  // routing once confirmed (2 drivers, an admin's, or made from probes): it covers
  // crowdJamHalfLengthM each side and costs crowdJamDefaultDelayS unless probes measured it.
  // On a route: within crowdOnRouteM, for its way (crowdSameWayDeg).
  crowdJamHalfLengthM: 500,
  crowdJamDefaultDelayS: 180,
  // What an "Embouteillage" costs when no probe measured it, by what the driver saw.
  crowdJamDelayS: { light: 90, heavy: 240, standstill: 600 },
  crowdOnRouteM: 60,
  crowdSameWayDeg: 60,
  // Slowdown probes ("Partager les ralentissements", traffic/probes.js): anonymous, in memory,
  // kept probeTtlMs, one per driver per probeMinIntervalMs. A probe reports a crawl under
  // probeMaxSpeedRatio of a limit of probeMinLimitKmh or more. probeClusterMinDrivers
  // different drivers within probeClusterWindowMs, probeClusterRadiusM and probeSameWayDeg
  // make a "Bouchon" (once per probeAutoJamEveryMs per spot).
  probeTtlMs: 30 * MIN,
  probeMinIntervalMs: MIN,
  probeMaxStored: 20_000,
  probeMinLimitKmh: 70,
  probeMaxSpeedRatio: 0.6,
  probeClusterWindowMs: 10 * MIN,
  probeClusterRadiusM: 1000,
  probeSameWayDeg: 45,
  probeClusterMinDrivers: 3,
  probeAutoJamEveryMs: 5 * MIN,

  // PostgreSQL / PostGIS: the signalisation (schema "signs", rebuilt weekly by
  // signalisation/rebuild.sh) and the drivers' reports and speed-limit changes (schema "crowd").
  pgHost: process.env.PGHOST || '/var/run/postgresql',
  pgDatabase: process.env.PGDATABASE || 'eona',
  pgPoolMax: 8,
  pgStatementTimeoutMs: 15000,
  signDefaultRadiusM: 30000, // 30 km
  signMaxRadiusM: 120000, // 120 km
  signMaxElements: 8000,
  // The road under the driver: candidates this close, then course and continuity decide.
  signRoadMaxDistM: 30,
  // Along a route: signs this close to its line, and the limit sampled every this many metres.
  signRouteSignBufferM: 15,
  signRouteLimitStepM: 40,

  // Safety caps for query endpoints (radius is user-tunable up to 1000 km).
  maxNearRadiusM: 1_000_000,
  defaultNearRadiusM: 3000,
  maxResults: 5000,
  // Radars "on the trip" (POST /api/radars/route): how far from the route line a radar
  // still counts. Fixed-radar coordinates sit beside the carriageway, not on it.
  radarRouteBufferM: 150,
  radarRouteMaxBufferM: 500,
  // Load everything within 90 km, but shrink to 40 km where it's ultra-dense
  // (too many points around the driver) to keep the map light.
  denseRadiusM: 40000,
  denseCountThreshold: 300,

  // Nearby places (stations, bornes, parkings…) from PostGIS (schema signs, table place,
  // rebuilt weekly with the signalisation). No perimeter: the nearest ones, however far.
  // signs_next.place lets a staging instance try a build before it is published.
  placeTable: process.env.PLACE_TABLE === 'signs_next.place' ? 'signs_next.place' : 'signs.place',
  placeLimit: 20,
  // Places sent to a client asking for a pool (?pool=1), which ranks them itself.
  placePoolLimit: 60,
  // Search (/api/search): what a driver types is rarely an address, so a place search
  // (Photon, OpenStreetMap) and the official address search (Base Adresse Nationale) answer
  // together. Both are free and need no key; the answers are kept a few minutes.
  photonUrl: process.env.PHOTON_URL || 'https://photon.komoot.io',
  banUrl: process.env.BAN_URL || 'https://api-adresse.data.gouv.fr',
  searchMinChars: 2,
  searchLimit: 8,
  searchMaxLimit: 15,
  searchSourceLimit: 10,
  searchTimeoutMs: 6000,
  searchCacheMs: 5 * MIN,
  searchCacheMax: 500,
  searchSameSpotM: 60,
  // Same name, this close: one place with several entrances or buildings.
  searchSameNameM: 400,
  searchPerMinute: 40,

  // Identifies us to the open-data servers we download from.
  placeUserAgent: process.env.PLACE_USER_AGENT || 'EONA/1.0 (+https://api.lrda-mercuriale.uk)',

  // Official fuel prices — prix-carburants.gouv.fr "flux instantané" (Licence Ouverte).
  // They only enrich the fuel stations the nearby search finds; they never replace it.
  fuelFeedUrl: process.env.FUEL_FEED_URL || 'https://donnees.roulez-eco.fr/opendata/instantane',
  fuelRefreshIntervalMs: Number(process.env.FUEL_REFRESH_INTERVAL_MS) || 10 * MIN,
  fuelRetryMs: MIN,
  fuelGridDeg: 0.01, // ~1 km cells
  // An OSM station tagged with an official id must still sit near that station.
  fuelRefMaxDistanceM: 1000,
  // Without an id: the nearest official station within this distance…
  fuelMatchMaxDistanceM: 200,
  // …unless a second one is nearly as close, in which case there is no match.
  fuelMatchAmbiguityM: 30,

  // Crowdsourced user reports (radar mobile, zone de contrôle, accident…), in PostGIS.
  /**
   * Anti-duplicate: a new report this close to a live one of the same type, for the same
   * traffic (courses within reportMergeSameWayDeg) on the same or a touching road, joins it as
   * one more voice instead of becoming a second report.
   */
  reportMergeRadiusM: {
    camera: 30,
    stopped_vehicle: 100,
    object_on_road: 100,
    damaged_road: 100,
    radar_mobile: 150,
    hazard: 150,
    accident: 200,
    control_zone: 200,
    roadworks: 300,
    road_crew: 300,
    voiture_radar: 300,
    slippery_road: 500,
    traffic_jam: 800,
    low_visibility: 1000,
    wrong_way: 2000,
  },
  reportMergeSameWayDeg: 60,
  reportMergeRoadTouchM: 25,
  /** Events that move: a merged report brings them to where they were seen last. */
  reportMovingTypes: ['traffic_jam', 'wrong_way', 'voiture_radar'],
  /** Closed reports (expired, denied, removed) stay this long, for the statistics. */
  reportHistoryMs: 90 * D,
  /**
   * Report scoring.
   *
   *   k          = ln(factors / minimumScore)
   *   timeScore  = factors × exp(-(k × age) / baseDurationMs)
   *
   * so a report is worth exactly `minimumScore` when it reaches `baseDurationMs`:
   * the base duration IS the lifetime, not a time constant.
   *
   * Confirmations and contradictions scale it instead of moving the clock:
   *   confirmationBonus     = min(1 + 0.10 × √confirmations, 1.40)
   *   contradictionPenalty  = 1 / (1 + 0.25 × √contradictions)
   *
   * That intrinsic score is what the server keeps and prunes on (< minimum = gone).
   * The driver-facing score multiplies it by road / direction / distance factors,
   * which depend on who is asking — see the app's ReportRelevance.
   *
   * `persistent` (a fixed camera) never decays and only an admin removes it.
   */
  reportScore: {
    // type              facteur   durée de vie          zone d'impact
    radar_mobile:    { factors: 70, baseDurationMs: 60 * MIN, impactM: 500 },
    control_zone:    { factors: 75, baseDurationMs: 90 * MIN, impactM: 1000 },
    voiture_radar:   { factors: 70, baseDurationMs: 60 * MIN, impactM: 500 },
    stopped_vehicle: { factors: 65, baseDurationMs: 45 * MIN, impactM: 500 },
    accident:        { factors: 90, baseDurationMs: 2 * H, impactM: 2000 },
    object_on_road:  { factors: 90, baseDurationMs: 60 * MIN, impactM: 1000 },
    traffic_jam:     { factors: 75, baseDurationMs: 30 * MIN, impactM: 3000 },
    damaged_road:    { factors: 80, baseDurationMs: 1 * D, impactM: 500 },
    roadworks:       { factors: 85, baseDurationMs: 7 * D, impactM: 1500 },
    slippery_road:   { factors: 75, baseDurationMs: 90 * MIN, impactM: 2000 },
    low_visibility:  { factors: 75, baseDurationMs: 60 * MIN, impactM: 2000 },
    road_crew:       { factors: 85, baseDurationMs: 60 * MIN, impactM: 1000 },
    wrong_way:       { factors: 95, baseDurationMs: 30 * MIN, impactM: 5000 },
    camera:          { factors: 65, baseDurationMs: null, impactM: 300, persistent: true },
    // Legacy "Danger", kept for the reports already stored.
    hazard:          { factors: 70, baseDurationMs: 60 * MIN, impactM: 1000 },
  },
  /** Below this a report is worthless and disappears. */
  reportScoreMinimum: 10,
  /** Confirmation bonus: 1 + step × √confirmations, capped. */
  reportConfirmStep: 0.10,
  reportConfirmCap: 1.40,
  /** Contradiction penalty: 1 / (1 + step × √contradictions). */
  reportContradictionStep: 0.25,
  /** Relevance bands handed to the app (below the minimum the report is deleted). */
  reportRelevance: { low: 10, normal: 30, high: 60 },
  /** How relevant a report on another road / another carriageway still is. */
  reportRoadFactor: { same: 1.0, other: 0.35 },
  reportDirectionFactor: { same: 1.0, unknown: 0.75, opposite: 0.15 },
  /** Stand-in lifetime for a persistent report (camera). */
  reportPermanentMs: 100 * 365 * D,

  // Who may create each report type (guest < client < admin).
  reportMinRole: {
    voiture_radar: 'client',
    camera: 'admin',
    radar_mobile: 'guest',
    control_zone: 'guest',
    accident: 'guest',
    hazard: 'guest',
    stopped_vehicle: 'guest',
    object_on_road: 'guest',
    traffic_jam: 'guest',
    damaged_road: 'guest',
    roadworks: 'guest',
    slippery_road: 'guest',
    low_visibility: 'guest',
    road_crew: 'guest',
    wrong_way: 'guest',
  },
  // A control zone covers a stretch of the reported lane, not a single point.
  controlZoneLengthM: 80,
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

  // Speed-limit maintenance: drivers propose the limit a sign shows ("50 → 70"). The
  // proposals for one spot are scored with the report formula (reports/score.js) and the
  // limit only changes once they are strong enough; a change applied is permanent.
  /** The limits a driver can pick: the ones the app draws as signs. */
  speedLimitValues: [20, 30, 50, 70, 80, 90, 100, 110, 130],
  /**
   * Same shape as reportScore. A proposal is worth `factors` and decays over
   * `baseDurationMs` from the last driver who proposed the same value: a sign does not go
   * away like an event, each new witness renews it. Drivers proposing anything else there
   * (the current limit included) are its contradictions.
   *
   * A proposal is applied when its score reaches the "high" relevance band, at least
   * `minSupporters` distinct people back it and they outnumber everyone else there:
   *   known   — the spot has a limit (OSM, radar VMA, an earlier change): 3 people, and
   *             one other opinion there takes 8 of them.
   *   unknown — nothing mapped there yet: 2 people fill the gap.
   * An admin's proposal is applied at once.
   */
  speedLimitScore: {
    known: { factors: 60, baseDurationMs: 60 * D, minSupporters: 3 },
    unknown: { factors: 70, baseDurationMs: 60 * D, minSupporters: 2 },
  },
  /** Proposals this close to one another, on the same way and from the same limit, are one change. */
  speedLimitGroupRadiusM: 250,
  /** An applied change replaces the mapped limit points this close to its supporters. */
  speedLimitZoneRadiusM: 150,
  /** Two courses closer than this run the same way (ReportRelevance's same-way angle). */
  speedLimitSameWayDeg: 60,
  /** A validated change still wins over the mapped road when it is up to this much farther. */
  speedLimitOverrideTieM: 10,
  /** A validated change this close to an earlier one on the same way replaces it. */
  speedLimitOverlapM: 20,
  /** Proposals that never applied (expired, outdated, rejected) stay in the history this long. */
  speedLimitHistoryMs: 365 * D,
  speedLimitMinRole: 'guest',
  speedLimitDefaultNearRadiusM: 5000,
  speedLimitMaxNearRadiusM: 100000,

  // Accounts (identity + roles guest/client/admin).
  accountsFile: process.env.ACCOUNTS_FILE || './data/accounts.json',
  // A Guest gets the complete navigation experience for one week. Afterwards
  // the account stays signed in and can consult the map, but cannot start a trip.
  guestTrialMs: Number(process.env.GUEST_TRIAL_MS) || 7 * D,
  // The free week is one per phone: the end of the first trial on a phone is kept (by a
  // hash of its device id), so a guest purged or deleted does not start a new week there.
  deviceTrialsFile: process.env.DEVICE_TRIALS_FILE || './data/device-trials.json',
  // Guests are limited per day (Paris time), trial included; clients and admins are not.
  guestReportsPerDay: Number(process.env.GUEST_REPORTS_PER_DAY) || 5,
  guestTripsPerDay: Number(process.env.GUEST_TRIPS_PER_DAY) || 7,
  // A route to within this of the day's last destination is the same trip (recalculation).
  tripSameDestinationM: 300,
  // A referral code grants this much Client access. Payments will use the same
  // subscription end date when the payment portal is added.
  referralSubscriptionMonths: Number(process.env.REFERRAL_SUBSCRIPTION_MONTHS) || 6,
  // How long a code stays usable once minted, changed by an admin from the app between a
  // month and a year. A new duration applies to the codes minted after it; the ones already
  // handed out keep their own date unless someone extends them.
  referralValidityMonths: Number(process.env.REFERRAL_VALIDITY_MONTHS) || 3,
  referralValidityMinMonths: 1,
  referralValidityMaxMonths: 12,
  // "Se connecter avec Google": the client ids of our own apps, comma separated in the service
  // environment (GOOGLE_CLIENT_IDS). Empty: the endpoint refuses, and the app hides the button.
  googleClientIds: (process.env.GOOGLE_CLIENT_IDS || '').split(',').map((id) => id.trim()).filter(Boolean),
  googleKeysUrl: process.env.GOOGLE_KEYS_URL || 'https://www.googleapis.com/oauth2/v3/certs',
  googleKeysTtlMs: 60 * MIN,

  // The settings an admin changes, and the trace of who changed what.
  settingsFile: process.env.SETTINGS_FILE || './data/settings.json',
  settingsHistoryMax: 200,
  // What is kept of a code's own life: minted, extended, revoked, regenerated.
  referralHistoryMax: 50,
  // Keep a useful but bounded server-side journey history per account.
  accountTripHistoryMax: Number(process.env.ACCOUNT_TRIP_HISTORY_MAX) || 200,
  // Admin API token. Admin endpoints are DISABLED unless this is set (env).
  adminToken: process.env.ADMIN_TOKEN || null,
  // Session token lifetime (client/admin stay logged in).
  sessionTtlMs: 90 * 24 * 60 * 60 * 1000, // 90 days

  // SMTP for email verification + password reset (via env; disabled if unset).
  smtp: {
    host: process.env.SMTP_HOST || null,
    port: Number(process.env.SMTP_PORT) || 587,
    user: process.env.SMTP_USER || null,
    pass: process.env.SMTP_PASS || null,
    from: process.env.SMTP_FROM || 'EONA',
  },
  resetCodeTtlMs: 30 * 60 * 1000, // 30 min
  // A guest ("Continuer en invité": username + password, no email) is deleted this long
  // after it became a guest. Guests without a password are deleted right away.
  guestLifetimeMs: Number(process.env.GUEST_LIFETIME_MS) || 7 * D,
  // Profile pictures: stored on the VPS filesystem, served statically at /avatars.
  avatarsDir: process.env.AVATARS_DIR || './data/avatars',
  avatarMaxBytes: 2 * 1024 * 1024, // 2 MB
  // Absolute base for avatar URLs the app loads (the API through Cloudflare Tunnel).
  publicBaseUrl: (process.env.PUBLIC_BASE_URL || 'https://api.lrda-mercuriale.uk').replace(/\/$/, ''),
  // Earlier public bases: avatar URLs stored under them move to publicBaseUrl when the accounts load.
  legacyPublicBaseUrls: ['https://debian.taila9954f.ts.net'],

  // "Signaler un bug": text sizes, flood limits (per account, for everyone), a page of the list,
  // and how long a resolved report stays.
  bugTextMin: 10,
  bugTextMax: 1000,
  bugPerHour: 3,
  bugPerDay: 10,
  bugPerDayAll: 500,
  bugPage: 50,
  bugResolvedDays: 90,

  // Trip sharing ("Partager mon trajet"): a link lives until the driver arrives, and a quarter of
  // an hour more so the follower sees the arrival; a trip that never ends stops here anyway.
  shareMaxMs: 6 * H,
  shareAfterArrivalMs: 15 * MIN,
  // Where the link points: the page that opens the app.
  shareBaseUrl: (process.env.SHARE_BASE_URL || process.env.PUBLIC_BASE_URL || 'https://api.lrda-mercuriale.uk').replace(/\/$/, ''),

  // Presence: an account counts as online (and on a trip or not) for 90 s after the app said so.
  liveTtlMs: 90 * 1000,
  // Positions shared by the drivers who turned them on: kept this long, then purged. A trace
  // asked for by the console is cut at positionTraceMax points.
  positionKeepDays: Number(process.env.POSITION_KEEP_DAYS) || 30,
  positionTraceMax: 5000,

  // Browsers only reach the API from the addresses listed here (the admin webapp), comma
  // separated in WEBAPP_ORIGINS. Empty: no browser from another address can call the API.
  webappOrigins: (process.env.WEBAPP_ORIGINS || '').split(',').map((o) => o.trim()).filter(Boolean),

  // "Changer de pseudo": a client with access changes it at most once per
  // usernameChangeIntervalMs; the name left stays theirs usernameHoldMs (nobody else takes it
  // meanwhile, so nobody passes for them). Reserved names (compared without "_", "." and
  // digits, and anything starting with "eona") belong to no driver; admins set them.
  usernameChangeIntervalMs: 7 * D,
  usernameHoldMs: 30 * D,
  reservedUsernames: [
    'admin', 'administrateur', 'administrator', 'moderateur', 'moderator', 'moderation', 'modo',
    'support', 'staff', 'equipe', 'team', 'officiel', 'official', 'system', 'systeme', 'root',
    'contact', 'aide', 'help', 'security', 'securite',
  ],
};
