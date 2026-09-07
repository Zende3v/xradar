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
    this.purgeGuests();
    // Purge stale guest accounts (no launch for >10 days) daily.
    const timer = setInterval(() => this.purgeGuests(), 24 * 60 * 60 * 1000);
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

  index(account) {
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

  // ---- Usernames / auth -----------------------------------------------------

  usernameAvailable(username) {
    if (!USERNAME_RE.test(String(username || ''))) return { ok: false, error: 'invalid username' };
    if (this.byUsername.has(String(username).toLowerCase())) return { ok: false, error: 'taken' };
    return { ok: true };
  }

  /** Guest identity: (device + chosen unique username). No password. */
  claimGuest(deviceId, username, meta = {}) {
    if (!deviceId) return { error: 'deviceId required' };
    const check = this.usernameAvailable(username);
    const existing = this.byDevice.get(deviceId);
    // Allow keeping one's own username on re-auth.
    if (!check.ok && !(existing && existing.usernameLower === String(username).toLowerCase())) {
      return { error: check.error };
    }
    const now = new Date().toISOString();
    if (existing) {
      this.unindex(existing);
      existing.username = username;
      existing.usernameLower = String(username).toLowerCase();
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
      passwordHash: null,
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

  register({ email, password, username }) {
    const e = String(email || '').trim().toLowerCase();
    if (!e.includes('@') || e.length > 190) return { error: 'invalid email' };
    if (String(password || '').length < 8) return { error: 'password too short (min 8)' };
    const check = this.usernameAvailable(username);
    if (!check.ok) return { error: `username ${check.error}` };
    if (this.byEmail.has(e)) return { error: 'email already registered' };
    const now = new Date().toISOString();
    const account = {
      id: randomUUID(),
      deviceId: null,
      role: 'client',
      username,
      usernameLower: String(username).toLowerCase(),
      displayName: username,
      email,
      emailLower: e,
      passwordHash: hashPassword(password),
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

  login(email, password) {
    const account = this.byEmail.get(String(email || '').trim().toLowerCase());
    if (!account || !account.passwordHash) return { error: 'invalid credentials' };
    if (!verifyPassword(password, account.passwordHash)) return { error: 'invalid credentials' };
    if (account.banned) return { error: 'banned' };
    account.lastSeenAt = new Date().toISOString();
    this.scheduleSave();
    return { account };
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

  /** Delete guest accounts not seen for > guestMaxAgeMs (default 10 days). */
  purgeGuests() {
    const cutoff = Date.now() - config.guestMaxAgeMs;
    let dropped = 0;
    for (const account of [...this.byId.values()]) {
      if (account.role !== 'guest') continue;
      const seen = Date.parse(account.lastSeenAt || account.createdAt || 0);
      if (Number.isFinite(seen) && seen < cutoff) {
        this.unindex(account);
        dropped++;
      }
    }
    if (dropped > 0) {
      console.log(`[accounts] purged ${dropped} stale guest(s)`);
      this.scheduleSave();
    }
    return dropped;
  }

  get meta() {
    const counts = { guest: 0, client: 0, admin: 0 };
    for (const a of this.byId.values()) counts[a.role] = (counts[a.role] ?? 0) + 1;
    return { count: this.byId.size, ...counts };
  }
}

export const accountStore = new AccountStore();
