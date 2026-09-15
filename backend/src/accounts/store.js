import { randomBytes, randomUUID, scryptSync, timingSafeEqual } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';

export const ROLES = ['guest', 'client', 'admin'];

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

function genCode() {
  return String(Math.floor(100000 + Math.random() * 900000)); // 6-digit code
}


const EMPTY_STATS = Object.freeze({
  tripCount: 0,
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
/**
 * Account store. Device-based identity: each app install authenticates with a
 * deviceId and gets a `guest` account by default. Roles (client/admin) are
 * assigned by an admin (CLI / admin API). Persisted to a JSON file — no DB.
 */
class AccountStore {
  constructor() {
    this.byId = new Map();
    this.byDevice = new Map();
    this.byUsername = new Map(); // lowercase -> account
    this.byEmail = new Map(); // lowercase -> account
    this.sessions = new Map(); // token -> { accountId, expiresAt }
    this.saveTimer = null;
  }

  async start() {
    await this.load();
    await this.purge();
    // Daily: guests without a password, and guests past their days, go.
    const timer = setInterval(() => {
      this.purge().catch((e) => console.error('[accounts] purge failed:', e.message));
    }, 24 * 60 * 60 * 1000);
    if (timer.unref) timer.unref();
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
    if (!Array.isArray(account.trips)) account.trips = [];
    if (account.trips.length > config.accountTripHistoryMax) account.trips = account.trips.slice(0, config.accountTripHistoryMax);
    account.stats = statsShape(account.stats);
    if (!Array.isArray(account.referralCodes)) account.referralCodes = [];
    if (account.role === 'guest' && !account.trialEndsAt) {
      account.trialEndsAt = trialEndsAt(account.createdAt);
    }
    return account;
  }

  index(account) {
    this.normalizeAccount(account);
    this.byId.set(account.id, account);
    if (account.deviceId) this.byDevice.set(account.deviceId, account);
    if (account.usernameLower) this.byUsername.set(account.usernameLower, account);
    if (account.emailLower) this.byEmail.set(account.emailLower, account);
  }

  unindex(account) {
    this.byId.delete(account.id);
    if (account.deviceId) this.byDevice.delete(account.deviceId);
    if (account.usernameLower) this.byUsername.delete(account.usernameLower);
    if (account.emailLower) this.byEmail.delete(account.emailLower);
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
    let account = this.byDevice.get(deviceId);
    if (account) {
      account.lastSeenAt = now;
      if (meta.platform) account.platform = meta.platform;
      this.scheduleSave();
      return account;
    }
    account = {
      id: randomUUID(),
      deviceId,
      role: 'guest',
      displayName: null,
      platform: meta.platform ?? null,
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
      if (!endsAt || Date.parse(endsAt) > Date.now()) return { status: 'active', canNavigate: true, endsAt };
      return { status: 'restricted', canNavigate: false, endsAt };
    }
    const endsAt = account.trialEndsAt ?? trialEndsAt(account.createdAt);
    if (Date.parse(endsAt) > Date.now()) return { status: 'trial', canNavigate: true, endsAt };
    return { status: 'restricted', canNavigate: false, endsAt };
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

  recordReportStat(id, kind) {
    const account = this.get(id);
    if (!account || !Object.hasOwn(EMPTY_STATS, kind)) return;
    this.normalizeAccount(account);
    account.stats[kind] += 1;
    this.scheduleSave();
  }

  findReferral(code) {
    const normalized = String(code || '').trim().toUpperCase();
    if (!normalized) return null;
    for (const owner of this.byId.values()) {
      const referral = owner.referralCodes?.find((entry) => entry.code === normalized && entry.active !== false);
      if (referral) return { owner, referral };
    }
    return null;
  }

  createReferral(ownerId) {
    const owner = this.get(ownerId);
    if (!owner || owner.role !== 'admin') return { error: 'admin only' };
    this.normalizeAccount(owner);
    let code;
    do {
      code = `XR-${randomBytes(4).toString('hex').toUpperCase()}`;
    } while (this.findReferral(code));
    const referral = {
      code,
      active: true,
      createdAt: new Date().toISOString(),
      months: config.referralSubscriptionMonths,
      redemptions: [],
    };
    owner.referralCodes.unshift(referral);
    this.scheduleSave();
    return { referral };
  }

  referralStats(ownerId) {
    const owner = this.get(ownerId);
    if (!owner || owner.role !== 'admin') return null;
    this.normalizeAccount(owner);
    return owner.referralCodes.map((entry) => ({
      code: entry.code,
      active: entry.active !== false,
      createdAt: entry.createdAt,
      months: entry.months ?? config.referralSubscriptionMonths,
      redemptions: Array.isArray(entry.redemptions) ? entry.redemptions.length : 0,
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
      const check = this.usernameAvailable(username);
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

  usernameAvailable(username) {
    if (!USERNAME_RE.test(String(username || ''))) return { ok: false, error: 'invalid username' };
    if (this.byUsername.has(String(username).toLowerCase())) return { ok: false, error: 'taken' };
    return { ok: true };
  }

  /**
   * Guest identity ("Continuer en invité"): the device, a unique username and a password —
   * the password is what brings the guest back after reinstalling the app. The account is
   * deleted guestLifetimeMs after it became a guest (see purge).
   */
  claimGuest(deviceId, username, password, meta = {}) {
    if (!deviceId) return { error: 'deviceId required' };
    if (String(password || '').length < 8) return { error: 'password too short (min 8)' };
    let existing = this.byDevice.get(deviceId);
    // A phone signed in to a member account starts a separate guest.
    if (existing?.emailLower) {
      this.byDevice.delete(deviceId);
      existing.deviceId = null;
      existing = null;
    }
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
      existing.passwordHash = hashPassword(password);
      // Becoming a guest starts its days: the bare device account only waited for onboarding.
      if (!existing.guestSince) {
        existing.guestSince = now;
        existing.trialEndsAt = trialEndsAt(now);
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
      passwordHash: hashPassword(password),
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

  register({ email, password, username, referralCode = null }) {
    const e = String(email || '').trim().toLowerCase();
    if (!e.includes('@') || e.length > 190) return { error: 'invalid email' };
    if (String(password || '').length < 8) return { error: 'password too short (min 8)' };
    const check = this.usernameAvailable(username);
    if (!check.ok) return { error: `username ${check.error}` };
    if (this.byEmail.has(e)) return { error: 'email already registered' };
    const now = new Date().toISOString();
    if (referralCode && !this.findReferral(referralCode)) return { error: 'invalid referral code' };
    const account = {
      id: randomUUID(),
      deviceId: null,
      role: 'guest',
      username,
      usernameLower: String(username).toLowerCase(),
      displayName: username,
      email,
      emailLower: e,
      passwordHash: hashPassword(password),
      emailVerified: false,
      avatarUrl: null,
      platform: null,
      banned: false,
      createdAt: now,
      lastSeenAt: now,
    };
    if (referralCode) {
      const applied = this.claimReferral(referralCode, account);
      if (applied.error) return applied;
    }
    this.index(account);
    this.scheduleSave();
    return { account };
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

  setProfile(id, { username, avatarUrl, displayName }) {
    const account = this.byId.get(id);
    if (!account) return { error: 'not found' };
    if (username !== undefined && String(username).toLowerCase() !== account.usernameLower) {
      const check = this.usernameAvailable(username);
      if (!check.ok) return { error: `username ${check.error}` };
      this.unindex(account);
      account.username = username;
      account.usernameLower = String(username).toLowerCase();
      account.displayName = username;
      this.index(account);
    }
    if (displayName !== undefined) account.displayName = displayName;
    if (avatarUrl !== undefined) account.avatarUrl = avatarUrl;
    this.scheduleSave();
    return { account };
  }

  // ---- Sessions (in-memory tokens) ------------------------------------------

  issueToken(accountId) {
    const token = randomBytes(32).toString('hex');
    this.sessions.set(token, { accountId, expiresAt: Date.now() + config.sessionTtlMs });
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

  /**
   * Delete the guest accounts with no way back: every guest without a password (a phone
   * that never finished onboarding, a guest from before passwords were asked) and every
   * "Continuer en invité" account (no email) guestLifetimeMs after it became a guest.
   * Members (email), clients and admins are never touched. Before the first deletion of
   * the day, a copy of all accounts is written next to the accounts file.
   */
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
    if (account.role !== 'guest') return false;
    if (!account.passwordHash) return true;
    if (account.emailLower) return false;
    const since = Date.parse(account.guestSince || account.createdAt || 0);
    return Number.isFinite(since) && now - since > config.guestLifetimeMs;
  }

  async backup(now) {
    const day = new Date(now).toISOString().slice(0, 10);
    if (this.backupDay === day) return;
    const dir = dirname(config.accountsFile);
    await mkdir(dir, { recursive: true });
    await writeFile(`${dir}/accounts.backup-${day}.json`, JSON.stringify([...this.byId.values()], null, 2), 'utf8');
    this.backupDay = day;
  }

  get meta() {
    const counts = { guest: 0, client: 0, admin: 0 };
    for (const a of this.byId.values()) counts[a.role] = (counts[a.role] ?? 0) + 1;
    return { count: this.byId.size, ...counts };
  }
}

export const accountStore = new AccountStore();
