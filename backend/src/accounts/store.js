import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import { mkdir, readdir, readFile, unlink, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';
import { haversine } from '../radars/geo.js';
import { settingsStore } from './settings.js';

export const ROLES = ['guest', 'client', 'admin'];

/**
 * "Note de confiance", 0..5: the share of your reports others confirmed, smoothed
 * so a single lucky report is not five stars — (confirmed + 1) / (declared + 2).
 */
export function trustOf(stats) {
  const declared = Number(stats?.reportsDeclared) || 0;
  const confirmed = Number(stats?.reportsConfirmed) || 0;
  return Math.round(((confirmed + 1) / (declared + 2)) * 50) / 10;
}

const USERNAME_RE = /^[a-zA-Z0-9_.]{3,20}$/;

/** scrypt password hash, stored as "salt:hash" (both hex). */
function hashPassword(password) {
  const salt = randomBytes(16).toString('hex');
  const hash = scryptSync(String(password), salt, 64).toString('hex');
  return `${salt}:${hash}`;
}

function verifyPassword(password, stored) {
  if (!stored || !stored.includes(':')) return false;
  const [salt, hash] = stored.split(':');
  const expected = Buffer.from(hash, 'hex');
  const actual = scryptSync(String(password), salt, 64);
  return expected.length === actual.length && timingSafeEqual(expected, actual);
}

/** Reserved: compared without "_", "." and digits ("Admin_2" is "admin"), and any "eona…". */
function isReservedUsername(lower) {
  const stem = lower.replace(/[._\d]/g, '');
  return stem.startsWith('eona') || config.reservedUsernames.includes(stem);
}

function genCode() {
  return String(Math.floor(100000 + Math.random() * 900000)); // 6-digit code
}


const EMPTY_STATS = Object.freeze({
  tripCount: 0,
  appDurationSeconds: 0,
  distanceMeters: 0,
  driveDurationSeconds: 0,
  alertsTraversed: 0,
  reportsDeclared: 0,
  reportsConfirmed: 0,
});

function statsShape(value) {
  const input = value && typeof value === 'object' ? value : {};
  return Object.fromEntries(Object.entries(EMPTY_STATS).map(([key, fallback]) => {
    const value = Number(input[key]);
    return [key, Number.isFinite(value) && value >= 0 ? Math.round(value) : fallback];
  }));
}

// The kinds of alerts a trip counts, as the app names them.
const TRIP_EVENT_KINDS = ['radarFixed', 'radarMobile', 'controlZone', 'camera', 'hazard', 'accident', 'roadwork', 'radarCar'];

/** A trip's alerts met, by kind: known kinds with a positive count only. */
function tripEventsShape(value) {
  const input = value && typeof value === 'object' ? value : {};
  const out = {};
  for (const kind of TRIP_EVENT_KINDS) {
    const count = Math.round(Number(input[kind]));
    if (Number.isFinite(count) && count > 0) out[kind] = Math.min(count, 10000);
  }
  return out;
}

// What a trip carries for the ETA and routing measures (D1.7, phase 1 of PLAN-VALHALLA.md). A
// trip from an app before them has none of these keys and is kept as it always was.
const TRIP_MEASURE_KEYS = ['arrived', 'departedAt', 'manualStart', 'retargeted', 'plannedMeters', 'pausedSeconds', 'uncertainSeconds',
  'etaChecks', 'recalcCount', 'fasterCount', 'engines', 'mapVersion', 'appVersion', 'platform', 'etaMode', 'trafficSources'];
const TRIP_ENGINES = ['ors', 'osrm', 'valhalla', 'unknown'];
const TRIP_TRAFFIC_SOURCES = ['tomtom', 'here', 'crowd', 'datagouv', 'sytadin'];
const TRIP_PLATFORMS = ['android', 'ios'];
// The ETA is noted at departure and at 25, 50 and 75 % of the trip, once each.
const TRIP_ETA_CHECKPOINTS = [0, 25, 50, 75];

/** A whole number ≥ 0 when [value] is a finite number, [fallback] otherwise. */
function wholeOr(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.round(value)) : fallback;
}

/** Short text (trimmed, 40 characters at most); null when not text or empty. */
function shortText(value) {
  if (typeof value !== 'string') return null;
  const text = value.trim();
  return text ? text.slice(0, 40) : null;
}

/** The distinct words of [value] that [known] lists, in their order, 10 at most. */
function knownWords(value, known) {
  if (!Array.isArray(value)) return [];
  return [...new Set(value.filter((word) => known.includes(word)))].slice(0, 10);
}

/**
 * The ETA shown along the trip: one complete snapshot per checkpoint (the first one given),
 * in checkpoint order — { at, shownAt, arrivalAt, pausedBefore, uncertainBefore }, and the two
 * ETAs of D2.6 when the app computed them: withDatagouvAt, withoutDatagouvAt (null otherwise).
 */
function etaChecksShape(value) {
  const byAt = new Map();
  for (const check of Array.isArray(value) ? value.slice(0, 10) : []) {
    const at = check?.at;
    if (!TRIP_ETA_CHECKPOINTS.includes(at) || byAt.has(at)) continue;
    const shownAt = wholeOr(check.shownAt, 0);
    const arrivalAt = wholeOr(check.arrivalAt, 0);
    if (!shownAt || !arrivalAt) continue;
    byAt.set(at, {
      at,
      shownAt,
      arrivalAt,
      pausedBefore: wholeOr(check.pausedBefore, 0),
      uncertainBefore: wholeOr(check.uncertainBefore, 0),
      withDatagouvAt: wholeOr(check.withDatagouvAt, 0) || null,
      withoutDatagouvAt: wholeOr(check.withoutDatagouvAt, 0) || null,
    });
  }
  return TRIP_ETA_CHECKPOINTS.filter((at) => byAt.has(at)).map((at) => byAt.get(at));
}

/** A trip's measures (D1.7), checked one by one; null for a trip from an app before them. */
function tripMeasuresShape(trip) {
  if (!TRIP_MEASURE_KEYS.some((key) => key in trip)) return null;
  return {
    // Ended at the destination (the auto-finish), not stopped by the driver.
    arrived: trip.arrived === true,
    // When the driver first joined the route (epoch ms).
    departedAt: wholeOr(trip.departedAt, 0) || null,
    manualStart: trip.manualStart === true,
    retargeted: trip.retargeted === true,
    plannedMeters: wholeOr(trip.plannedMeters, 0) || null,
    // Long stops away from any known jam (pauses), and the ones the traffic could not tell.
    pausedSeconds: wholeOr(trip.pausedSeconds, 0),
    uncertainSeconds: wholeOr(trip.uncertainSeconds, 0),
    etaChecks: etaChecksShape(trip.etaChecks),
    recalcCount: Math.min(wholeOr(trip.recalcCount, 0), 10000),
    fasterCount: Math.min(wholeOr(trip.fasterCount, 0), 10000),
    engines: knownWords(trip.engines, TRIP_ENGINES),
    mapVersion: shortText(trip.mapVersion),
    appVersion: shortText(trip.appVersion),
    platform: TRIP_PLATFORMS.includes(trip.platform) ? trip.platform : null,
    etaMode: shortText(trip.etaMode),
    trafficSources: knownWords(trip.trafficSources, TRIP_TRAFFIC_SOURCES),
  };
}

function addMonths(iso, months) {
  const date = new Date(iso);
  date.setUTCMonth(date.getUTCMonth() + months);
  return date.toISOString();
}

function trialEndsAt(createdAt) {
  const start = Date.parse(createdAt);
  const safeStart = Number.isFinite(start) ? start : Date.now();
  return new Date(safeStart + config.guestTrialMs).toISOString();
}

/** The day of [now] in France, "YYYY-MM-DD": daily limits start again at midnight, Paris time. */
function parisDay(now) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Paris' }).format(now);
}

/**
 * What the app says about itself when someone signs up or signs in: the phone, its system,
 * the version of EONA and the language of the device. Nothing is guessed, nothing is asked
 * of the system beyond what it hands over freely — no advertising identifier, ever.
 */
function appInfo(value) {
  const text = (field, max = 60) => {
    const raw = value?.[field];
    if (typeof raw !== 'string') return null;
    const trimmed = raw.trim().slice(0, max);
    return trimmed || null;
  };
  const info = {
    platform: text('platform', 16),
    model: text('model', 60),
    osVersion: text('osVersion', 30),
    appVersion: text('appVersion', 30),
    locale: text('locale', 20),
    region: text('region', 10),
  };
  return Object.values(info).some(Boolean) ? info : null;
}

/** A referral code may be used while it is active, not revoked, and not past its date. */
function usableReferral(entry) {
  if (!entry || entry.active === false) return false;
  if (!entry.expiresAt) return true; // minted before durations existed: no end date
  return Date.parse(entry.expiresAt) > Date.now();
}

/** One line in a code's own history, newest first. */
function noteReferral(entry, line) {
  if (!Array.isArray(entry.history)) entry.history = [];
  entry.history.unshift(line);
  entry.history = entry.history.slice(0, config.referralHistoryMax);
}

/**
 * Account store. Device-based identity: each app install authenticates with a
 * deviceId and gets a `guest` account by default. Roles (client/admin) are
 * assigned by an admin (CLI / admin API). Persisted to a JSON file — no DB.
 */
export class AccountStore {
  constructor() {
    this.byId = new Map();
    this.byDevice = new Map();
    this.byUsername = new Map(); // lowercase -> account
    this.byEmail = new Map(); // lowercase -> account
    this.heldUsernames = new Map(); // lowercase -> { accountId, until } (names just left)
    this.sessions = new Map(); // token -> { accountId, expiresAt }
    this.byProvider = new Map(); // "google:123…" -> account
    this.saveTimer = null;
  }

  async start() {
    await this.load();
  }

  async load() {
    try {
      const raw = await readFile(config.accountsFile, 'utf8');
      const list = JSON.parse(raw);
      if (Array.isArray(list)) {
        for (const a of list) if (a && a.id) this.index(a);
      }
      console.log(`[accounts] loaded ${this.byId.size} accounts from ${config.accountsFile}`);
    } catch (e) {
      if (e.code !== 'ENOENT') console.error('[accounts] load failed:', e.message);
    }
  }

  normalizeAccount(account) {
    // A photo stored under an earlier public address (Tailscale Funnel) now points to the current one.
    const legacy = config.legacyPublicBaseUrls.find((base) => account.avatarUrl?.startsWith(base + '/'));
    if (legacy) {
      account.avatarUrl = config.publicBaseUrl + account.avatarUrl.slice(legacy.length);
      this.scheduleSave();
    }
    if (!Array.isArray(account.trips)) account.trips = [];
    if (account.trips.length > config.accountTripHistoryMax) account.trips = account.trips.slice(0, config.accountTripHistoryMax);
    account.stats = statsShape(account.stats);
    if (!Array.isArray(account.referralCodes)) account.referralCodes = [];
    if (!Array.isArray(account.providers)) account.providers = [];
    // Names left once, held for their owner a while: the lapsed ones go.
    account.heldUsernames = (Array.isArray(account.heldUsernames) ? account.heldUsernames : [])
      .filter((held) => held?.lower && Date.parse(held.until) > Date.now());
    // Anciennes inscriptions email/Google : essai conservé, rôle membre, aucune prolongation.
    if (account.role === 'guest' && (account.emailLower || account.providers.length)) {
      account.role = 'client';
      account.trialEndsAt ??= trialEndsAt(account.createdAt);
      this.scheduleSave();
    }
    return account;
  }

  index(account) {
    this.normalizeAccount(account);
    this.byId.set(account.id, account);
    if (account.deviceId) this.byDevice.set(account.deviceId, account);
    if (account.usernameLower) this.byUsername.set(account.usernameLower, account);
    if (account.emailLower) this.byEmail.set(account.emailLower, account);
    for (const link of account.providers ?? []) this.byProvider.set(`${link.provider}:${link.subject}`, account);
    for (const held of account.heldUsernames) this.heldUsernames.set(held.lower, { accountId: account.id, until: held.until });
  }

  unindex(account) {
    this.byId.delete(account.id);
    if (account.deviceId) this.byDevice.delete(account.deviceId);
    if (account.usernameLower) this.byUsername.delete(account.usernameLower);
    if (account.emailLower) this.byEmail.delete(account.emailLower);
    for (const link of account.providers ?? []) this.byProvider.delete(`${link.provider}:${link.subject}`);
    for (const held of account.heldUsernames ?? []) {
      if (this.heldUsernames.get(held.lower)?.accountId === account.id) this.heldUsernames.delete(held.lower);
    }
  }

  scheduleSave() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.save().catch((e) => console.error('[accounts] save failed:', e.message));
    }, 800);
    if (this.saveTimer.unref) this.saveTimer.unref();
  }

  async save() {
    await mkdir(dirname(config.accountsFile), { recursive: true });
    await writeFile(config.accountsFile, JSON.stringify([...this.byId.values()], null, 2), 'utf8');
  }

  /** Auth by device: return the existing account (touch lastSeen) or create a guest. */
  auth(deviceId, meta = {}) {
    const now = new Date().toISOString();
    const app = appInfo(meta);
    let account = this.byDevice.get(deviceId);
    if (account) {
      account.lastSeenAt = now;
      if (meta.platform) account.platform = meta.platform;
      // The phone and the version move on: the card follows.
      if (app) account.app = { ...account.app, ...app };
      this.scheduleSave();
      return account;
    }
    account = {
      id: randomUUID(),
      deviceId,
      role: 'guest',
      displayName: null,
      platform: meta.platform ?? null,
      app,
      signupMethod: 'device',
      banned: false,
      createdAt: now,
      lastSeenAt: now,
    };
    this.index(account);
    this.scheduleSave();
    return account;
  }

  get(id) {
    return this.byId.get(id) ?? null;
  }

  getByDevice(deviceId) {
    return this.byDevice.get(deviceId) ?? null;
  }

  accessFor(account) {
    if (!account || account.banned) return { status: 'restricted', canNavigate: false, endsAt: null };
    if (account.role === 'admin') return { status: 'active', canNavigate: true, endsAt: null };
    if (account.role === 'client') {
      const endsAt = account.subscriptionEndsAt ?? null;
      if (endsAt) return { status: Date.parse(endsAt) > Date.now() ? 'active' : 'free', canNavigate: true, endsAt };
      if (account.trialEndsAt) {
        return { status: Date.parse(account.trialEndsAt) > Date.now() ? 'trial' : 'free', canNavigate: true, endsAt: account.trialEndsAt };
      }
      // Accès permanents déjà attribués par administrateur : conservés.
      return { status: 'active', canNavigate: true, endsAt: null };
    }
    return { status: 'free', canNavigate: true, endsAt: null };
  }

  hasPlus(account) {
    const access = this.accessFor(account);
    return access.status === 'active' || access.status === 'trial';
  }

  tierFor(account) {
    if (this.hasPlus(account)) return 'plus';
    return account?.role === 'guest' ? 'guest' : 'free';
  }

  // ---- Daily limits (guests) ------------------------------------------------

  /** A guest's counters for today (Paris day): reports posted, trips started, last trip's end. */
  usageOf(account, now = Date.now()) {
    const day = parisDay(now);
    if (account.usage?.day !== day) account.usage = { day, reports: 0, trips: 0, lastTo: null };
    return account.usage;
  }

  /** Quotas gratuits ; null pendant EONA+, essai compris. */
  limitsFor(account) {
    if (this.hasPlus(account)) return null;
    const usage = this.usageOf(account);
    return {
      day: usage.day, // counts are for this Paris day; a cached copy from another day is zero
      reportsPerDay: null,
      reportsToday: usage.reports,
      tripsPerDay: config.guestTripsPerDay,
      tripsToday: usage.trips,
    };
  }

  canReport(account) {
    return !account.banned;
  }

  countReport(account) {
    if (this.hasPlus(account)) return;
    this.usageOf(account).reports += 1;
    this.scheduleSave();
  }

  /**
   * A route towards [to] ({ lat, lon }) is a new trip, unless it heads where the day's last
   * trip went (a recalculation, an avoid option changed). Not allowed once a guest started
   * all of today's trips.
   */
  tripCheck(account, to) {
    if (this.hasPlus(account)) return { allowed: true, isNew: false };
    const usage = this.usageOf(account);
    const last = usage.lastTo;
    if (last && haversine(last.lat, last.lon, to.lat, to.lon) <= config.tripSameDestinationM) {
      return { allowed: true, isNew: false };
    }
    return { allowed: usage.trips < config.guestTripsPerDay, isNew: true };
  }

  countTrip(account, to) {
    if (this.hasPlus(account)) return true;
    const usage = this.usageOf(account);
    // Plusieurs calculs simultanés : admission vérifiée après réponse du moteur.
    const check = this.tripCheck(account, to);
    if (!check.allowed) return false;
    if (!check.isNew) return true;
    usage.trips += 1;
    usage.lastTo = { lat: to.lat, lon: to.lon };
    this.scheduleSave();
    return true;
  }

  /** Départ iOS : compte une fois, même destination répétée = nouveau trajet. */
  startNavigation(account, to, tripId) {
    if (this.navigationMatches(account, to, tripId)) return { allowed: true };
    const usage = this.usageOf(account);
    if (!this.hasPlus(account) && usage.trips >= config.guestTripsPerDay) return { allowed: false };
    if (!this.hasPlus(account)) usage.trips += 1;
    account.navigation = { id: tripId, to: { lat: to.lat, lon: to.lon }, startedAt: Date.now() };
    this.scheduleSave();
    return { allowed: true };
  }

  /** Recalculs gratuits pendant trajet admis, y compris après minuit. */
  navigationMatches(account, to, tripId) {
    const current = account.navigation;
    return Boolean(tripId && current?.id === tripId && Date.now() - current.startedAt < 7 * 86_400_000
      && haversine(current.to.lat, current.to.lon, to.lat, to.lon) <= config.tripSameDestinationM);
  }

  statsFor(id) {
    const account = this.get(id);
    if (!account) return null;
    this.normalizeAccount(account);
    return {
      totals: { ...account.stats },
      trips: account.trips.map((trip) => ({ ...trip })),
    };
  }

  recordTrip(id, trip) {
    const account = this.get(id);
    if (!account) return { error: 'not found' };
    this.normalizeAccount(account);
    const startedAt = Number(trip?.startedAt);
    const distanceMeters = Number(trip?.distanceMeters);
    const durationSeconds = Number(trip?.durationSeconds);
    const alertsCount = Number(trip?.alertsCount) || 0;
    const topSpeedKmh = Number(trip?.topSpeedKmh) || 0;
    const plannedSeconds = trip?.plannedSeconds == null ? NaN : Number(trip.plannedSeconds);
    const tripId = String(trip?.id || '').trim();
    if (!tripId || !Number.isFinite(startedAt) || !Number.isFinite(distanceMeters) || !Number.isFinite(durationSeconds)) {
      return { error: 'invalid trip' };
    }
    const existing = account.trips.find((item) => item.id === tripId);
    if (existing) return { trip: existing, stats: { ...account.stats } };
    const record = {
      id: tripId.slice(0, 100),
      startedAt: Math.round(startedAt),
      fromLabel: String(trip?.fromLabel || 'Ma position').slice(0, 160),
      toLabel: String(trip?.toLabel || 'Destination').slice(0, 160),
      distanceMeters: Math.max(0, Math.round(distanceMeters)),
      durationSeconds: Math.max(0, Math.round(durationSeconds)),
      alertsCount: Math.max(0, Math.round(alertsCount)),
      topSpeedKmh: Math.max(0, Math.round(topSpeedKmh)),
      // Trip details (apps from before them send none): the route's estimate, the stops of
      // 10 s or more and their time, the alerts met by kind.
      plannedSeconds: Number.isFinite(plannedSeconds) && plannedSeconds > 0 ? Math.round(plannedSeconds) : null,
      stops: Math.max(0, Math.round(Number(trip?.stops) || 0)),
      stoppedSeconds: Math.max(0, Math.round(Number(trip?.stoppedSeconds) || 0)),
      events: tripEventsShape(trip?.events),
      // The ETA and routing measures, when the app sends them (tripMeasuresShape). No coordinates.
      ...(trip && typeof trip === 'object' ? tripMeasuresShape(trip) : null),
    };
    account.trips.unshift(record);
    account.trips = account.trips.slice(0, config.accountTripHistoryMax);
    account.stats.tripCount += 1;
    account.stats.alertsTraversed += record.alertsCount;
    this.scheduleSave();
    return { trip: record, stats: { ...account.stats } };
  }

  /** Time and distance driven with the app open, trip or not. */
  recordDrive(id, seconds, meters) {
    const account = this.get(id);
    if (!account) return { error: 'not found' };
    this.normalizeAccount(account);
    const s = Math.max(0, Math.min(Math.round(Number(seconds) || 0), 3600));
    const m = Math.max(0, Math.min(Math.round(Number(meters) || 0), 200000));
    account.stats.driveDurationSeconds += s;
    account.stats.distanceMeters += m;
    this.scheduleSave();
    return { stats: { ...account.stats } };
  }

  /**
   * The app is open and says so about every 30 s. The moment is always kept (last activity);
   * the time between two pings only adds up to "Temps d'utilisation" when the driver turned that
   * on ([counts]), and a gap longer than the presence TTL is a new session, not time spent.
   */
  recordActivity(id, counts, now = Date.now()) {
    const account = this.get(id);
    if (!account) return;
    this.normalizeAccount(account);
    const last = Date.parse(account.lastActiveAt ?? '');
    if (counts && Number.isFinite(last)) {
      const gap = now - last;
      if (gap > 0 && gap <= config.liveTtlMs) account.stats.appDurationSeconds += Math.round(gap / 1000);
    }
    account.lastActiveAt = new Date(now).toISOString();
    this.scheduleSave();
  }

  /** The terms accepted by [id]: the version and the moment, kept as proof. */
  recordTerms(id, version) {
    const account = this.get(id);
    if (!account) return null;
    account.terms = { version, acceptedAt: new Date().toISOString() };
    this.scheduleSave();
    return { ...account.terms };
  }

  recordReportStat(id, kind) {
    const account = this.get(id);
    if (!account || !Object.hasOwn(EMPTY_STATS, kind)) return;
    this.normalizeAccount(account);
    account.stats[kind] += 1;
    this.scheduleSave();
  }

  /** The code and whoever minted it, while it may still be used: active, not revoked, not expired. */
  findReferral(code) {
    const normalized = String(code || '').trim().toUpperCase();
    if (!normalized) return null;
    for (const owner of this.byId.values()) {
      const referral = owner.referralCodes?.find((entry) => entry.code === normalized && usableReferral(entry));
      if (referral) return { owner, referral };
    }
    return null;
  }

  /** One code of [ownerId], usable or not: what the admin screen acts on. */
  referralOf(ownerId, code) {
    const owner = this.get(ownerId);
    if (!owner || owner.role !== 'admin') return null;
    this.normalizeAccount(owner);
    const normalized = String(code || '').trim().toUpperCase();
    return owner.referralCodes.find((entry) => entry.code === normalized) ?? null;
  }

  createReferral(ownerId) {
    const owner = this.get(ownerId);
    if (!owner || owner.role !== 'admin') return { error: 'admin only' };
    this.normalizeAccount(owner);
    let code;
    do {
      code = `XR-${randomBytes(4).toString('hex').toUpperCase()}`;
    } while (this.findReferral(code));
    const now = new Date().toISOString();
    const validity = settingsStore.referralValidityMonths;
    const referral = {
      code,
      active: true,
      createdAt: now,
      // What the code grants once used…
      months: config.referralSubscriptionMonths,
      // …and how long it may be used at all.
      validityMonths: validity,
      expiresAt: addMonths(now, validity),
      redemptions: [],
      history: [{ at: now, action: 'created', by: owner.username ?? owner.id, months: validity }],
    };
    owner.referralCodes.unshift(referral);
    this.scheduleSave();
    return { referral };
  }

  /**
   * Acts on one code: "extend" pushes its end further by [months], "revoke" stops it at once,
   * "regenerate" revokes it and mints a fresh one. Every action is written in the code's history
   * with its author and the moment.
   */
  actOnReferral(ownerId, code, { action, months }) {
    const owner = this.get(ownerId);
    if (!owner || owner.role !== 'admin') return { error: 'admin only' };
    const referral = this.referralOf(ownerId, code);
    if (!referral) return { error: 'code not found' };
    const now = new Date().toISOString();
    const by = owner.username ?? owner.id;

    if (action === 'extend') {
      const added = settingsStore.clampMonths(months ?? settingsStore.referralValidityMonths);
      // From its own end when it is still alive, from today when it already lapsed.
      const from = Date.parse(referral.expiresAt ?? now) > Date.now() ? referral.expiresAt : now;
      referral.expiresAt = addMonths(from, added);
      referral.active = true;
      referral.revokedAt = null;
      noteReferral(referral, { at: now, action: 'extended', by, months: added, until: referral.expiresAt });
      this.scheduleSave();
      return { referral };
    }

    if (action === 'revoke') {
      referral.active = false;
      referral.revokedAt = now;
      noteReferral(referral, { at: now, action: 'revoked', by });
      this.scheduleSave();
      return { referral };
    }

    if (action === 'regenerate') {
      referral.active = false;
      referral.revokedAt = now;
      noteReferral(referral, { at: now, action: 'regenerated', by });
      const fresh = this.createReferral(ownerId);
      if (fresh.error) return fresh;
      noteReferral(fresh.referral, { at: now, action: 'replaces', by, code: referral.code });
      this.scheduleSave();
      return { referral: fresh.referral, replaced: referral.code };
    }

    return { error: 'action must be extend, revoke or regenerate' };
  }

  referralStats(ownerId) {
    const owner = this.get(ownerId);
    if (!owner || owner.role !== 'admin') return null;
    this.normalizeAccount(owner);
    return owner.referralCodes.map((entry) => ({
      code: entry.code,
      // Usable right now: not revoked, not past its date.
      active: usableReferral(entry),
      createdAt: entry.createdAt,
      // What it grants once used, and how long it may still be used.
      months: entry.months ?? config.referralSubscriptionMonths,
      validityMonths: entry.validityMonths ?? null,
      expiresAt: entry.expiresAt ?? null,
      revokedAt: entry.revokedAt ?? null,
      redemptions: Array.isArray(entry.redemptions) ? entry.redemptions.length : 0,
      history: Array.isArray(entry.history) ? entry.history : [],
    }));
  }

  claimReferral(code, account) {
    const hit = this.findReferral(code);
    if (!hit) return { error: 'invalid referral code' };
    const now = new Date().toISOString();
    account.role = 'client';
    account.subscriptionEndsAt = addMonths(now, hit.referral.months ?? config.referralSubscriptionMonths);
    account.referredByCode = hit.referral.code;
    if (!Array.isArray(hit.referral.redemptions)) hit.referral.redemptions = [];
    hit.referral.redemptions.push({ accountId: account.id, redeemedAt: now });
    return { referral: hit.referral };
  }

  list(role) {
    const all = [...this.byId.values()].sort((a, b) =>
      String(b.lastSeenAt).localeCompare(String(a.lastSeenAt)),
    );
    return role ? all.filter((a) => a.role === role) : all;
  }

  create({ deviceId, role = 'guest', displayName = null, username = null, email = null, password = null }) {
    if (!ROLES.includes(role)) return { error: 'invalid role' };
    if (deviceId && this.byDevice.has(deviceId)) return { error: 'deviceId already exists' };
    // Admins (and clients created here) must have email + password + username.
    if ((role === 'admin' || role === 'client') && (!email || !password || !username)) {
      return { error: `${role} requires --email, --password and --name (username)` };
    }
    const e = email ? String(email).trim().toLowerCase() : null;
    if (e && this.byEmail.has(e)) return { error: 'email already registered' };
    if (username) {
      // Admin tooling: reserved names allowed.
      const check = this.usernameAvailable(username, { admin: true });
      if (!check.ok) return { error: `username ${check.error}` };
    }
    const now = new Date().toISOString();
    const account = {
      id: randomUUID(),
      deviceId: deviceId ?? null,
      role,
      username: username ?? null,
      usernameLower: username ? String(username).toLowerCase() : null,
      displayName: displayName ?? username ?? null,
      email: email ?? null,
      emailLower: e,
      passwordHash: password ? hashPassword(password) : null,
      avatarUrl: null,
      platform: null,
      banned: false,
      createdAt: now,
      lastSeenAt: now,
    };
    this.index(account);
    this.scheduleSave();
    return { account };
  }

  update(id, patch) {
    const account = this.byId.get(id);
    if (!account) return { error: 'not found' };
    if (patch.role !== undefined) {
      if (!ROLES.includes(patch.role)) return { error: 'invalid role' };
      account.role = patch.role;
    }
    if (patch.displayName !== undefined) account.displayName = patch.displayName;
    if (patch.banned !== undefined) account.banned = Boolean(patch.banned);
    this.scheduleSave();
    return { account };
  }

  remove(id) {
    const account = this.byId.get(id);
    if (!account) return { error: 'not found' };
    this.unindex(account);
    this.scheduleSave();
    return { removed: true, id };
  }

  /** The owner deletes the account: gone with its stats and trips, and signed out everywhere. */
  deleteAccount(id) {
    const result = this.remove(id);
    if (result.error) return result;
    for (const [token, session] of this.sessions) {
      if (session.accountId === id) this.sessions.delete(token);
    }
    return result;
  }

  // ---- Usernames / auth -----------------------------------------------------

  /**
   * Whether [username] can be taken: well formed, nobody's (a name another driver left lately
   * counts as taken, except for [accountId], its owner), and not reserved (unless [admin]).
   */
  usernameAvailable(username, { accountId = null, admin = false } = {}) {
    if (!USERNAME_RE.test(String(username || ''))) return { ok: false, error: 'invalid username' };
    const lower = String(username).toLowerCase();
    const owner = this.byUsername.get(lower);
    if (owner && owner.id !== accountId) return { ok: false, error: 'taken' };
    const held = this.heldUsernames.get(lower);
    if (held && held.accountId !== accountId && Date.parse(held.until) > Date.now()) return { ok: false, error: 'taken' };
    if (!admin && isReservedUsername(lower)) return { ok: false, error: 'reserved' };
    return { ok: true };
  }

  /** Who may change their username now: a client whose access runs. */
  canChangeUsername(account) {
    return account?.role === 'client' && this.accessFor(account).canNavigate;
  }

  /** When [account] may change its username again (ISO); null when it may now. */
  usernameChangeableAt(account, now = Date.now()) {
    const last = Date.parse(account?.usernameChangedAt ?? '');
    if (!Number.isFinite(last)) return null;
    const next = last + config.usernameChangeIntervalMs;
    return next > now ? new Date(next).toISOString() : null;
  }

  /**
   * "Changer de pseudo": a client with access, once per usernameChangeIntervalMs, to a name
   * nobody has. The name left stays held for this account usernameHoldMs. The account keeps
   * its id: reports, votes, trips and sessions follow it. Returns { account } or
   * { error, status, nextAt? }.
   */
  changeUsername(id, username, now = Date.now()) {
    const account = this.byId.get(id);
    if (!account) return { error: 'not found', status: 404 };
    if (!this.canChangeUsername(account)) return { error: 'clients only', status: 403 };
    if (username === account.username) return { account };
    const nextAt = this.usernameChangeableAt(account, now);
    if (nextAt) return { error: 'username change too soon', status: 429, nextAt };
    const check = this.usernameAvailable(username, { accountId: id });
    if (!check.ok) return { error: `username ${check.error}`, status: check.error === 'taken' ? 409 : 400 };
    const lower = String(username).toLowerCase();
    this.unindex(account);
    const held = account.heldUsernames.filter((entry) => entry.lower !== lower);
    if (account.usernameLower && account.usernameLower !== lower) {
      held.push({ lower: account.usernameLower, until: new Date(now + config.usernameHoldMs).toISOString() });
    }
    account.heldUsernames = held;
    account.username = username;
    account.usernameLower = lower;
    account.displayName = username;
    account.usernameChangedAt = new Date(now).toISOString();
    this.index(account);
    this.scheduleSave();
    return { account };
  }

  /** Invité permanent : pseudo aléatoire sans mot de passe. Anciennes identités acceptées. */
  claimGuest(deviceId, username, password, meta = {}) {
    if (!deviceId || deviceId.length > 128) return { error: 'deviceId required' };
    // Anciennes apps : pseudo/mot de passe acceptés. iOS : identité aléatoire sans mot de passe.
    const legacy = Boolean(username || password);
    if (legacy && String(password || '').length < 8) return { error: 'password too short (min 8)' };
    let existing = this.byDevice.get(deviceId);
    if (existing?.banned) return { error: 'banned' };
    // A phone signed in to a member account starts a separate guest.
    if (existing && (existing.role !== 'guest' || existing.emailLower)) {
      this.byDevice.delete(deviceId);
      existing.deviceId = null;
      existing = null;
    }
    if (!legacy && existing?.username) return { account: existing };
    if (!legacy) username = this.freeUsername(`Invite_${randomBytes(4).toString('hex')}`);
    const check = this.usernameAvailable(username);
    // Allow keeping one's own username on re-auth.
    if (!check.ok && !(existing && existing.usernameLower === String(username).toLowerCase())) {
      return { error: check.error };
    }
    const now = new Date().toISOString();
    if (existing) {
      this.unindex(existing);
      existing.username = username;
      existing.usernameLower = String(username).toLowerCase();
      existing.displayName = username;
      existing.passwordHash = legacy ? hashPassword(password) : null;
      // Date d'entrée invité conservée, sans expiration.
      if (!existing.guestSince) {
        existing.guestSince = now;
      }
      existing.lastSeenAt = now;
      if (meta.platform) existing.platform = meta.platform;
      this.index(existing);
      this.scheduleSave();
      return { account: existing };
    }
    const account = {
      id: randomUUID(),
      deviceId,
      role: 'guest',
      username,
      usernameLower: String(username).toLowerCase(),
      displayName: username,
      email: null,
      emailLower: null,
      passwordHash: legacy ? hashPassword(password) : null,
      guestSince: now,
      avatarUrl: null,
      platform: meta.platform ?? null,
      banned: false,
      createdAt: now,
      lastSeenAt: now,
    };
    this.index(account);
    this.scheduleSave();
    return { account };
  }

  register({ email, password, username, referralCode = null, app = null, method = 'email', guest = null, deviceId = null }) {
    const e = String(email || '').trim().toLowerCase();
    if (!e.includes('@') || e.length > 190) return { error: 'invalid email' };
    if (String(password || '').length < 8) return { error: 'password too short (min 8)' };
    if (guest && (guest.banned || guest.role !== 'guest' || guest.emailLower || guest.providers?.length)) {
      return { error: 'guest account required' };
    }
    const check = this.usernameAvailable(username, { accountId: guest?.id });
    if (!check.ok) return { error: `username ${check.error}` };
    if (this.byEmail.has(e)) return { error: 'email already registered' };
    const now = new Date().toISOString();
    if (referralCode && !this.findReferral(referralCode)) return { error: 'invalid referral code' };
    const account = {
      ...guest,
      id: guest?.id ?? randomUUID(),
      deviceId: guest?.deviceId ?? null,
      role: 'client',
      username,
      usernameLower: String(username).toLowerCase(),
      displayName: username,
      email,
      emailLower: e,
      passwordHash: hashPassword(password),
      emailVerified: false,
      avatarUrl: null,
      platform: app?.platform ?? null,
      app: appInfo(app),
      // How this account came to be: by email, or through a provider.
      signupMethod: method,
      banned: false,
      createdAt: guest?.createdAt ?? now,
      trialStartedAt: now,
      trialEndsAt: trialEndsAt(now),
      lastSeenAt: now,
    };
    if (referralCode) {
      const applied = this.claimReferral(referralCode, account);
      if (applied.error) return applied;
    }
    // Conversion sur même objet : compte, trajets, statistiques et sessions conservés.
    if (guest) {
      this.unindex(guest);
      Object.assign(guest, account);
    }
    const registered = guest ?? account;
    this.index(registered);
    if (deviceId) this.bindDevice(registered, deviceId);
    this.scheduleSave();
    return { account: registered };
  }

  // ---- Providers (Google) ---------------------------------------------------

  /** The account already tied to this provider identity, or null. */
  byProviderIdentity(provider, subject) {
    return this.byProvider.get(`${provider}:${subject}`) ?? null;
  }

  /**
   * Signs someone in from a provider identity already checked by the server.
   *
   *   • tied to an account already: that account signs in;
   *   • same verified email as an account: the provider is linked to it, no second account;
   *   • nobody yet: a new account, with a free username taken from the name or the email.
   *
   * An address Apple or Google hides behind a relay works like any other: it is just an
   * address we can write to.
   */
  signInWithProvider(identity, { deviceId = null, app = null } = {}) {
    const { provider, subject, email, emailVerified, name } = identity;
    const now = new Date().toISOString();

    const known = this.byProviderIdentity(provider, subject);
    if (known) {
      known.lastSeenAt = now;
      if (deviceId && !known.deviceId) {
        known.deviceId = deviceId;
        this.byDevice.set(deviceId, known);
      }
      const info = appInfo(app);
      if (info) known.app = { ...known.app, ...info };
      this.scheduleSave();
      return { account: known, created: false, linked: false };
    }

    // Same address, already an account: linked rather than doubled.
    const byEmail = email && emailVerified ? this.byEmail.get(email) : null;
    if (byEmail) {
      this.linkProvider(byEmail.id, identity);
      byEmail.lastSeenAt = now;
      this.scheduleSave();
      return { account: byEmail, created: false, linked: true };
    }

    const username = this.freeUsername(name ?? (email ? email.split("@")[0] : provider));
    const account = {
      id: randomUUID(),
      deviceId: deviceId ?? null,
      role: 'client',
      username,
      usernameLower: username.toLowerCase(),
      displayName: name ?? username,
      email: email ?? null,
      emailLower: email ?? null,
      passwordHash: null,
      // The provider checked the address, so we do not ask for it again.
      emailVerified: Boolean(email && emailVerified),
      avatarUrl: null,
      platform: app?.platform ?? null,
      app: appInfo(app),
      signupMethod: provider,
      trialStartedAt: now,
      trialEndsAt: trialEndsAt(now),
      providers: [{ provider, subject, email: email ?? null, linkedAt: now }],
      banned: false,
      createdAt: now,
      lastSeenAt: now,
    };
    this.index(account);
    this.scheduleSave();
    return { account, created: true, linked: false };
  }

  /** Ties a provider identity to an account that already exists. */
  linkProvider(id, { provider, subject, email }) {
    const account = this.get(id);
    if (!account) return { error: 'not found' };
    this.normalizeAccount(account);
    const taken = this.byProviderIdentity(provider, subject);
    if (taken && taken.id !== account.id) return { error: 'already linked to another account' };
    if (!account.providers.some((link) => link.provider === provider && link.subject === subject)) {
      if (account.role === 'guest') {
        const now = new Date().toISOString();
        account.role = 'client';
        account.trialStartedAt = now;
        account.trialEndsAt = trialEndsAt(now);
      }
      account.providers.push({ provider, subject, email: email ?? null, linkedAt: new Date().toISOString() });
      this.byProvider.set(`${provider}:${subject}`, account);
      this.scheduleSave();
    }
    return { account };
  }

  /**
   * Unties a provider. Refused when it is the only way in: nobody is locked out of their own
   * account by a tap in the settings.
   */
  unlinkProvider(id, provider) {
    const account = this.get(id);
    if (!account) return { error: 'not found' };
    this.normalizeAccount(account);
    const left = account.providers.filter((link) => link.provider !== provider);
    if (left.length === account.providers.length) return { error: 'not linked' };
    const canStillSignIn = Boolean(account.passwordHash) || left.length > 0 || Boolean(account.deviceId);
    if (!canStillSignIn) return { error: 'set a password first' };
    for (const link of account.providers) {
      if (link.provider === provider) this.byProvider.delete(`${link.provider}:${link.subject}`);
    }
    account.providers = left;
    this.scheduleSave();
    return { account };
  }

  /** A username nobody has yet, built from [wanted] ("Jean Dupont" → "jeandupont", "…2"). */
  freeUsername(wanted) {
    const base = String(wanted ?? '')
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .replace(/[^a-zA-Z0-9_.]/g, '')
      .slice(0, 16) || 'driver';
    let candidate = base.length >= 3 ? base : `${base}eona`;
    let n = 1;
    while (!this.usernameAvailable(candidate).ok) {
      n += 1;
      candidate = `${base.slice(0, 16)}${n}`;
    }
    return candidate;
  }

  // ---- Email verification + password reset ----------------------------------

  setVerifyCode(id) {
    const a = this.byId.get(id);
    if (!a) return null;
    a.verifyCode = genCode();
    this.scheduleSave();
    return a.verifyCode;
  }

  resendVerify(email) {
    const a = this.byEmail.get(String(email || '').trim().toLowerCase());
    if (!a) return null;
    a.verifyCode = genCode();
    this.scheduleSave();
    return { code: a.verifyCode, account: a };
  }

  verifyEmail(email, code) {
    const a = this.byEmail.get(String(email || '').trim().toLowerCase());
    if (!a) return { error: 'not found' };
    if (!a.verifyCode || String(code) !== String(a.verifyCode)) return { error: 'invalid code' };
    a.emailVerified = true;
    a.verifyCode = null;
    this.scheduleSave();
    return { account: a };
  }

  /** Returns the reset code to email (or null if no such account — caller stays vague). */
  startReset(email) {
    const a = this.byEmail.get(String(email || '').trim().toLowerCase());
    if (!a) return null;
    a.resetCode = genCode();
    a.resetExpiresAt = Date.now() + config.resetCodeTtlMs;
    this.scheduleSave();
    return { code: a.resetCode, account: a };
  }

  resetPassword(email, code, password) {
    const a = this.byEmail.get(String(email || '').trim().toLowerCase());
    if (!a || !a.resetCode) return { error: 'invalid code' };
    if (String(code) !== String(a.resetCode)) return { error: 'invalid code' };
    if (!a.resetExpiresAt || Date.now() > a.resetExpiresAt) return { error: 'code expired' };
    if (String(password || '').length < 8) return { error: 'password too short (min 8)' };
    a.passwordHash = hashPassword(password);
    a.resetCode = null;
    a.resetExpiresAt = null;
    this.scheduleSave();
    return { account: a };
  }

  /**
   * Email (members) or username (guests) + password. [deviceId], when given, attaches the
   * account to the phone signing in, so it comes back by itself on that phone.
   */
  login(identifier, password, deviceId = null) {
    const key = String(identifier || '').trim().toLowerCase();
    const account = key.includes('@') ? this.byEmail.get(key) : this.byUsername.get(key);
    if (!account || !account.passwordHash) return { error: 'invalid credentials' };
    if (!verifyPassword(password, account.passwordHash)) return { error: 'invalid credentials' };
    if (account.banned) return { error: 'banned' };
    this.bindDevice(account, deviceId);
    account.lastSeenAt = new Date().toISOString();
    this.scheduleSave();
    return { account };
  }

  /** Attach [account] to a phone. The bare account that phone had without a password goes. */
  bindDevice(account, deviceId) {
    const id = String(deviceId || '').trim();
    if (!id || id.length > 128 || account.deviceId === id) return;
    const other = this.byDevice.get(id);
    if (other && other !== account) {
      if (!other.passwordHash && other.role === 'guest') {
        this.unindex(other);
      } else {
        this.byDevice.delete(id);
        other.deviceId = null;
      }
    }
    if (account.deviceId) this.byDevice.delete(account.deviceId);
    account.deviceId = id;
    this.byDevice.set(id, account);
  }

  /** Photo and display name; the username changes through changeUsername only. */
  setProfile(id, { avatarUrl, displayName }) {
    const account = this.byId.get(id);
    if (!account) return { error: 'not found' };
    if (displayName !== undefined) account.displayName = displayName;
    if (avatarUrl !== undefined) account.avatarUrl = avatarUrl;
    this.scheduleSave();
    return { account };
  }

  /** What the driver shows of themself to the other members of a group trip. */
  setPrivacy(id, { groupStatsVisible }) {
    const account = this.byId.get(id);
    if (!account) return { error: 'not found' };
    if (groupStatsVisible) delete account.groupStatsHidden;
    else account.groupStatsHidden = true;
    this.scheduleSave();
    return { account };
  }

  /**
   * A driver's card, as the other members of their group trip see it: the photo, the name, the
   * role, the month they joined and the trust score always; the statistics unless the driver
   * hid them. Never the email, the trips or anything that says where they go.
   */
  cardFor(id) {
    const account = this.get(id);
    if (!account) return null;
    this.normalizeAccount(account);
    const s = account.stats;
    const hidden = Boolean(account.groupStatsHidden);
    return {
      id: account.id,
      username: account.username ?? account.displayName ?? null,
      avatarUrl: account.avatarUrl ?? null,
      role: account.role,
      memberSince: typeof account.createdAt === 'string' ? account.createdAt.slice(0, 7) : null,
      trust: trustOf(s),
      statsHidden: hidden,
      stats: hidden ? null : {
        distanceMeters: s.distanceMeters,
        driveDurationSeconds: s.driveDurationSeconds,
        tripCount: s.tripCount,
        reportsDeclared: s.reportsDeclared,
        reportsConfirmed: s.reportsConfirmed,
      },
    };
  }

  // ---- Sessions (in-memory tokens) ------------------------------------------

  issueToken(accountId, ttlMs = config.sessionTtlMs) {
    const token = randomBytes(32).toString('hex');
    this.sessions.set(token, { accountId, expiresAt: Date.now() + ttlMs });
    return token;
  }

  resolveToken(token) {
    const s = token && this.sessions.get(token);
    if (!s) return null;
    if (s.expiresAt <= Date.now()) {
      this.sessions.delete(token);
      return null;
    }
    return this.byId.get(s.accountId) ?? null;
  }

  revokeToken(token) {
    this.sessions.delete(token);
  }

  /** Compatibilité CLI : invités permanents, purge automatique sans suppression. */
  async purge(now = Date.now()) {
    const doomed = [...this.byId.values()].filter((account) => this.isExpiredGuest(account, now));
    if (doomed.length === 0) return 0;
    await this.backup(now);
    for (const account of doomed) this.unindex(account);
    console.log(`[accounts] purged ${doomed.length} guest account(s)`);
    this.scheduleSave();
    return doomed.length;
  }

  isExpiredGuest(account, now) {
    // Invités permanents. Suppression seulement sur demande explicite du propriétaire.
    return false;
  }

  async backup(now) {
    const day = new Date(now).toISOString().slice(0, 10);
    if (this.backupDay === day) return;
    const dir = dirname(config.accountsFile);
    await mkdir(dir, { recursive: true });
    await writeFile(`${dir}/accounts.backup-${day}.json`, JSON.stringify([...this.byId.values()], null, 2), 'utf8');
    this.backupDay = day;
    // The purged guests must not live on in old copies: ACCOUNTS_BACKUP_KEEP_DAYS days, like the
    // nightly backups.
    const oldest = new Date(now - config.accountsBackupKeepDays * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
    for (const name of await readdir(dir).catch(() => [])) {
      const kept = /^accounts\.backup-(\d{4}-\d{2}-\d{2})\.json$/.exec(name)?.[1];
      if (kept && kept < oldest) await unlink(`${dir}/${name}`).catch(() => {});
    }
  }

  get meta() {
    const counts = { guest: 0, client: 0, admin: 0 };
    for (const a of this.byId.values()) counts[a.role] = (counts[a.role] ?? 0) + 1;
    return { count: this.byId.size, ...counts };
  }
}

export const accountStore = new AccountStore();
