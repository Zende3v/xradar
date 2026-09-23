import { randomUUID } from 'node:crypto';
import { unlink } from 'node:fs/promises';
import { Router } from 'express';
import { config } from '../config.js';
import { transaction } from '../crowd/schema.js';
import { liveStore } from '../live/store.js';
import { adminActor, authAccount, publicView } from './auth.js';
import { adminAudit } from '../admin/audit.js';
import { accountStore } from './store.js';
import { verifyGoogleToken } from './google.js';
import { settingsStore } from './settings.js';
import { mailer } from '../mailer.js';

export const accountRouter = Router();

/**
 * Self view: the public view plus what only the owner sees — email, verification,
 * access status (trial / active / restricted) and the trust summary.
 */
function selfView(a) {
  const access = accountStore.accessFor(a);
  const stats = accountStore.statsFor(a.id)?.totals ?? {};
  return {
    ...publicView(a),
    email: a.email ?? null,
    emailVerified: Boolean(a.emailVerified),
    access: access.status, // 'trial' | 'active' | 'restricted'
    canNavigate: access.canNavigate,
    accessEndsAt: access.endsAt,
    referredByCode: a.referredByCode ?? null,
    trust: trustOf(stats),
    // "Changer de pseudo": who may, and when again (ISO, null = now).
    canChangeUsername: accountStore.canChangeUsername(a),
    usernameChangeableAt: accountStore.usernameChangeableAt(a),
    // A guest's daily limits and today's use; null for clients and admins.
    limits: accountStore.limitsFor(a),
    // How this account was opened, and which providers it can sign in with.
    signupMethod: a.signupMethod ?? null,
    providers: (a.providers ?? []).map((link) => ({ provider: link.provider, email: link.email, linkedAt: link.linkedAt })),
    hasPassword: Boolean(a.passwordHash),
  };
}

/**
 * "Note de confiance", 0..5: the share of your reports others confirmed, smoothed
 * so a single lucky report is not five stars — (confirmed + 1) / (declared + 2).
 */
function trustOf(stats) {
  const declared = Number(stats.reportsDeclared) || 0;
  const confirmed = Number(stats.reportsConfirmed) || 0;
  return Math.round(((confirmed + 1) / (declared + 2)) * 50) / 10;
}

/**
 * POST /api/accounts/auth  { deviceId, platform? }
 * Legacy device sign-in: returns the account (creating a bare guest on first contact).
 */
accountRouter.post('/auth', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim();
  if (!deviceId || deviceId.length > 128) {
    return res.status(400).json({ error: 'deviceId is required' });
  }
  const account = accountStore.auth(deviceId, { platform: req.body?.platform, ...(req.body?.app ?? {}) });
  if (account.banned) return res.status(403).json({ error: 'banned', account: publicView(account) });
  const token = accountStore.issueToken(account.id);
  res.json({ account: selfView(account), token });
});

/**
 * GET /api/accounts/username-available?u=pseudo  (Bearer optional)
 * Signed in, a name the driver left lately counts as theirs again.
 */
accountRouter.get('/username-available', (req, res) => {
  const account = authAccount(req);
  const check = accountStore.usernameAvailable(String(req.query.u || ''), { accountId: account?.id ?? null });
  res.json({ available: check.ok, reason: check.error ?? null });
});

/**
 * POST /api/accounts/guest  { deviceId, username, password, platform? }
 * Guest identity: a unique username and a password (8 min.) tied to the device. The guest
 * signs back in with them after a reinstall; the account is deleted after its 7 days.
 */
accountRouter.post('/guest', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim();
  const result = accountStore.claimGuest(deviceId, String(req.body?.username || '').trim(), req.body?.password, {
    platform: req.body?.platform,
  });
  if (result.error) return res.status(400).json({ error: result.error });
  const token = accountStore.issueToken(result.account.id);
  res.status(201).json({ account: selfView(result.account), token });
});

/**
 * POST /api/accounts/register  { email, password, username, referralCode? }
 * A new account starts as a guest on a 7-day trial; a valid referral code — only
 * accepted here, at creation — turns it into a client for 6 months.
 */
accountRouter.post('/register', (req, res) => {
  const referral = String(req.body?.referralCode || '').trim();
  const result = accountStore.register({
    email: req.body?.email,
    password: req.body?.password,
    username: String(req.body?.username || '').trim(),
    referralCode: referral || null,
    // What the app knows of itself, for the admin card.
    app: req.body?.app ?? null,
  });
  if (result.error) return res.status(400).json({ error: result.error });
  const token = accountStore.issueToken(result.account.id);
  const code = accountStore.setVerifyCode(result.account.id);
  if (code) mailer.sendVerify(result.account.email, code);
  res.status(201).json({ account: selfView(result.account), token });
});

/** POST /api/accounts/verify  { email, code } — confirm the email. */
accountRouter.post('/verify', (req, res) => {
  const result = accountStore.verifyEmail(req.body?.email, req.body?.code);
  if (result.error) return res.status(400).json({ error: result.error });
  res.json({ account: selfView(result.account) });
});

/** POST /api/accounts/resend-verify  { email } — send a fresh code. */
accountRouter.post('/resend-verify', (req, res) => {
  const r = accountStore.resendVerify(req.body?.email);
  if (r) mailer.sendVerify(r.account.email, r.code);
  res.json({ ok: true }); // vague on purpose
});

/** POST /api/accounts/forgot  { email } — email a reset code (always 200). */
accountRouter.post('/forgot', (req, res) => {
  const r = accountStore.startReset(req.body?.email);
  if (r) mailer.sendReset(r.account.email, r.code);
  res.json({ ok: true });
});

/** POST /api/accounts/reset  { email, code, password } — set a new password. */
accountRouter.post('/reset', (req, res) => {
  const result = accountStore.resetPassword(req.body?.email, req.body?.code, req.body?.password);
  if (result.error) return res.status(400).json({ error: result.error })
  res.json({ ok: true });
});

/**
 * POST /api/accounts/login  { email | identifier, password, deviceId? } → { account, token }.
 * The identifier is an email (member) or a username (guest); deviceId attaches the account
 * to that phone.
 */
accountRouter.post('/login', (req, res) => {
  const result = accountStore.login(req.body?.identifier ?? req.body?.email, req.body?.password, req.body?.deviceId);
  if (result.error) return res.status(401).json({ error: result.error });
  // From the admin webapp (web: true), the session lasts a working day, not three months.
  const ttlMs = req.body?.web === true ? config.webSessionTtlMs : config.sessionTtlMs;
  const token = accountStore.issueToken(result.account.id, ttlMs);
  res.json({ account: selfView(result.account), token, expiresInS: Math.round(ttlMs / 1000) });
});

/**
 * POST /api/accounts/google  { idToken, deviceId?, app? }
 * "Se connecter avec Google". The identity token is checked against Google here, on the
 * server: nothing the app claims about the person is taken on trust. An address already
 * known signs into that account instead of making a second one.
 */
accountRouter.post('/google', async (req, res) => {
  const checked = await verifyGoogleToken(req.body?.idToken);
  if (checked.error) return res.status(401).json({ error: checked.error });
  const outcome = accountStore.signInWithProvider(checked.identity, {
    deviceId: req.body?.deviceId ? String(req.body.deviceId).trim() : null,
    app: req.body?.app ?? null,
  });
  if (outcome.account.banned) return res.status(403).json({ error: 'banned' });
  const token = accountStore.issueToken(outcome.account.id);
  res.status(outcome.created ? 201 : 200).json({
    account: selfView(outcome.account),
    token,
    created: outcome.created,
    linked: outcome.linked,
  });
});

/**
 * POST /api/accounts/me/link/google  { idToken }
 * Ties a Google account to the one already signed in, so either way in works afterwards.
 */
accountRouter.post('/me/link/google', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  const checked = await verifyGoogleToken(req.body?.idToken);
  if (checked.error) return res.status(401).json({ error: checked.error });
  const result = accountStore.linkProvider(account.id, checked.identity);
  if (result.error) return res.status(409).json({ error: result.error });
  res.json({ account: selfView(result.account) });
});

/** DELETE /api/accounts/me/link/google — unties it, unless it is the only way in. */
accountRouter.delete('/me/link/google', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  const result = accountStore.unlinkProvider(account.id, 'google');
  if (result.error) return res.status(400).json({ error: result.error });
  res.json({ account: selfView(result.account) });
});

/** GET /api/accounts/me — current account from the Bearer token / deviceId. */
accountRouter.get('/me', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  res.json({ account: selfView(account) });
});

/**
 * PATCH /api/accounts/me  { username?, avatarUrl? } — clients and admins; the username only for
 * a client with access, once a week (429 + nextAt), to a free name (409 taken, 400 invalid or
 * reserved). Nothing changes when the username is refused.
 */
accountRouter.patch('/me', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  if (account.role === 'guest') {
    return res.status(403).json({ error: 'profile edits require a client account' });
  }
  if (req.body?.username !== undefined) {
    const changed = accountStore.changeUsername(account.id, String(req.body.username).trim());
    if (changed.error) return res.status(changed.status).json({ error: changed.error, nextAt: changed.nextAt ?? null });
  }
  const result = accountStore.setProfile(account.id, { avatarUrl: req.body?.avatarUrl });
  if (result.error) return res.status(400).json({ error: result.error });
  res.json({ account: selfView(result.account) });
});

/**
 * DELETE /api/accounts/me — the owner deletes the account, for good: the account with its
 * statistics and trips, every session, its presence and the avatar. The reports and
 * speed-limit proposals stay for the other drivers, no longer tied to anyone.
 */
accountRouter.delete('/me', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  try {
    await forgetInCrowd([account.id, account.deviceId].filter(Boolean));
  } catch (e) {
    console.error('[accounts] delete failed:', e.message);
    return res.status(500).json({ error: 'could not delete account' });
  }
  liveStore.remove(account.id);
  await Promise.all(['png', 'jpg', 'webp'].map((ext) => unlink(`${config.avatarsDir}/${account.id}.${ext}`).catch(() => {})));
  accountStore.deleteAccount(account.id);
  res.json({ deleted: true });
});

/** The crowd's data no longer points at these ids (account, device): each becomes a new anonymous id. */
async function forgetInCrowd(ids) {
  await transaction(async (client) => {
    for (const id of ids) {
      const anonymous = `deleted:${randomUUID()}`;
      await client.query('UPDATE crowd.report SET reporter_id = NULL WHERE reporter_id = $1', [id]);
      await client.query('UPDATE crowd.report_voice SET voter_id = $2 WHERE voter_id = $1', [id, anonymous]);
      await client.query('UPDATE crowd.speed_limit_voice SET reporter_id = $2 WHERE reporter_id = $1', [id, anonymous]);
      await client.query('UPDATE crowd.bug_report SET account_id = NULL WHERE account_id = $1', [id]);
      // A position says where someone was: it is deleted, never anonymised.
      await client.query('DELETE FROM crowd.position WHERE account_id = $1', [id]);
    }
  });
}

// ---- Statistics (everyone, server-side) -------------------------------------

/**
 * POST /api/accounts/me/terms  { version }
 * The driver accepted the terms in the app: which version, and when. Kept as proof, nothing else.
 */
accountRouter.post('/me/terms', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'account required' });
  const version = String(req.body?.version ?? '').trim().slice(0, 20);
  if (!version) return res.status(400).json({ error: 'version required' });
  const terms = accountStore.recordTerms(account.id, version);
  if (!terms) return res.status(404).json({ error: 'not found' });
  res.json({ terms });
});

/** GET /api/accounts/me/stats — totals + trip history. */
accountRouter.get('/me/stats', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  const stats = accountStore.statsFor(account.id);
  res.json({ ...stats, trust: trustOf(stats.totals) });
});

/** POST /api/accounts/me/trips  { id, startedAt, toLabel, distanceMeters, durationSeconds, alertsCount, topSpeedKmh, plannedSeconds?, stops?, stoppedSeconds?, events? } */
accountRouter.post('/me/trips', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  const result = accountStore.recordTrip(account.id, req.body || {});
  if (result.error) return res.status(400).json({ error: result.error });
  res.status(201).json(result);
});

/** POST /api/accounts/me/drive  { seconds, meters } — time on the road with the app. */
accountRouter.post('/me/drive', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  const result = accountStore.recordDrive(account.id, req.body?.seconds, req.body?.meters);
  if (result.error) return res.status(400).json({ error: result.error });
  res.json(result);
});

// ---- Referral codes (admins, from the app) ----------------------------------

/** GET /api/accounts/referrals — the admin's codes and how often each was used. */
accountRouter.get('/referrals', (req, res) => {
  const account = authAccount(req);
  if (account?.role !== 'admin') return res.status(403).json({ error: 'admin only' });
  res.json({ referrals: accountStore.referralStats(account.id) });
});

/** POST /api/accounts/referrals — mint a new code (6 months of client). */
accountRouter.post('/referrals', (req, res) => {
  const account = authAccount(req);
  if (account?.role !== 'admin') return res.status(403).json({ error: 'admin only' });
  const result = accountStore.createReferral(account.id);
  if (result.error) return res.status(400).json({ error: result.error });
  res.status(201).json(result);
});

/**
 * PATCH /api/accounts/referrals/:code  { action: "extend" | "revoke" | "regenerate", months? }
 * Extends a code, stops it, or replaces it with a fresh one. Every action is kept in the code's
 * own history, with who did it and when.
 */
accountRouter.patch('/referrals/:code', (req, res) => {
  const account = authAccount(req);
  if (account?.role !== 'admin') return res.status(403).json({ error: 'admin only' });
  const result = accountStore.actOnReferral(account.id, req.params.code, {
    action: String(req.body?.action ?? ''),
    months: req.body?.months,
  });
  if (result.error) return res.status(result.error === 'code not found' ? 404 : 400).json({ error: result.error });
  res.json(result);
});

/** GET /api/accounts/referrals/settings — the duration new codes get, its range, and the log. */
accountRouter.get('/referrals/settings', (req, res) => {
  const account = authAccount(req);
  if (account?.role !== 'admin') return res.status(403).json({ error: 'admin only' });
  res.json({ settings: settingsStore.meta });
});

/**
 * PUT /api/accounts/referrals/settings  { validityMonths }
 * How long the codes minted from now on stay usable, between a month and a year. The codes
 * already handed out keep their own date: only "extend" moves one.
 */
accountRouter.put('/referrals/settings', (req, res) => {
  const account = authAccount(req);
  if (account?.role !== 'admin') return res.status(403).json({ error: 'admin only' });
  const months = Number(req.body?.validityMonths);
  if (!Number.isFinite(months)) return res.status(400).json({ error: 'validityMonths required' });
  const changed = settingsStore.setReferralValidity(months, account.username ?? account.id);
  res.json({ settings: settingsStore.meta, changed });
});

/** POST /api/accounts/logout — revoke the current Bearer token. */
accountRouter.post('/logout', (req, res) => {
  const m = /^Bearer\s+(.+)$/i.exec(req.get('authorization') || '');
  if (m) accountStore.revokeToken(m[1]);
  res.json({ ok: true });
});

// ---- Admin API (token-guarded) --------------------------------------------

export const adminAccountRouter = Router();

// An admin account signed in with its session (the webapp), or the ADMIN_TOKEN (scripts).
// Taking the admin role away from an account closes this door to it at once.
adminAccountRouter.use((req, res, next) => {
  const actor = adminActor(req);
  if (!actor) return res.status(401).json({ error: 'admin session required' });
  req.actor = actor;
  next();
});

/** Admin view: full record minus the password hash. */
function adminView(a) {
  if (!a) return a;
  const { passwordHash, ...rest } = a;
  return rest;
}

/**
 * GET /api/admin/accounts?q=&role=&banned=&signup=&sort=lastSeen|created|username&order=desc|asc
 *                         &limit=50&offset=0
 * A page of accounts. `q` looks in the pseudo, the display name, the email and the id, accents
 * and case aside. `total` is how many match; `next` the offset of the following page, or null.
 */
adminAccountRouter.get('/', (req, res) => {
  const role = req.query.role ? String(req.query.role) : undefined;
  const q = fold(req.query.q);
  const banned = req.query.banned === 'true' ? true : req.query.banned === 'false' ? false : null;
  const signup = req.query.signup ? String(req.query.signup) : null;
  let found = accountStore.list(role).filter((a) =>
    (banned === null || Boolean(a.banned) === banned)
    && (!signup || (a.signupMethod ?? 'email') === signup)
    && (!q || [a.username, a.displayName, a.email, a.id].some((field) => fold(field).includes(q))));
  const sort = { created: 'createdAt', username: 'usernameLower', lastSeen: 'lastSeenAt' }[req.query.sort] ?? 'lastSeenAt';
  const asc = req.query.order === 'asc';
  found = found.sort((a, b) => String(a[sort] ?? '').localeCompare(String(b[sort] ?? '')) * (asc ? 1 : -1));
  const limit = Math.min(Math.max(Math.floor(Number(req.query.limit)) || 50, 1), 200);
  const offset = Math.max(Math.floor(Number(req.query.offset)) || 0, 0);
  const page = found.slice(offset, offset + limit).map(adminView);
  res.json({
    total: found.length,
    count: page.length,
    offset,
    next: offset + limit < found.length ? offset + limit : null,
    meta: accountStore.meta,
    accounts: page,
  });
});

/** Case and accents aside, for the search. */
function fold(value) {
  return String(value ?? '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
}

adminAccountRouter.get('/:id', (req, res) => {
  const account = accountStore.get(req.params.id);
  if (!account) return res.status(404).json({ error: 'not found' });
  res.json({ account: adminView(account) });
});

adminAccountRouter.post('/', (req, res) => {
  const result = accountStore.create({
    deviceId: req.body?.deviceId || null,
    role: req.body?.role || 'guest',
    displayName: req.body?.displayName ?? null,
    username: req.body?.username ?? req.body?.name ?? null,
    email: req.body?.email ?? null,
    password: req.body?.password ?? null,
  });
  if (result.error) return res.status(400).json({ error: result.error });
  adminAudit.log(req.actor, 'account.create', 'account', result.account.id, { role: result.account.role, username: result.account.username ?? null });
  res.status(201).json({ account: adminView(result.account) });
});

adminAccountRouter.patch('/:id', (req, res) => {
  // Nobody locks themselves out: an admin cannot ban themselves nor take their own role away.
  if (req.actor.id === req.params.id && (req.body?.banned === true || (req.body?.role && req.body.role !== 'admin'))) {
    return res.status(400).json({ error: 'cannot demote or ban yourself' });
  }
  const before = accountStore.get(req.params.id);
  const was = before ? { role: before.role, banned: Boolean(before.banned), displayName: before.displayName ?? null } : null;
  const result = accountStore.update(req.params.id, {
    role: req.body?.role,
    displayName: req.body?.displayName,
    banned: req.body?.banned,
  });
  if (result.error) return res.status(result.error === 'not found' ? 404 : 400).json({ error: result.error });
  const now = { role: result.account.role, banned: Boolean(result.account.banned), displayName: result.account.displayName ?? null };
  const changed = Object.keys(now).filter((key) => was && was[key] !== now[key]);
  if (changed.length) {
    const action = changed.includes('banned')
      ? (now.banned ? 'account.ban' : 'account.unban')
      : changed.includes('role') ? 'account.role' : 'account.update';
    adminAudit.log(req.actor, action, 'account', result.account.id,
      Object.fromEntries(changed.map((key) => [key, { from: was[key], to: now[key] }])));
  }
  res.json({ account: adminView(result.account) });
});

adminAccountRouter.delete('/:id', async (req, res) => {
  if (req.actor.id === req.params.id) return res.status(400).json({ error: 'cannot delete yourself here' });
  const account = accountStore.get(req.params.id);
  if (!account) return res.status(404).json({ error: 'not found' });
  // Deleted by an admin or by its owner, an account leaves the same thing behind: nothing.
  try {
    await forgetInCrowd([account.id, account.deviceId].filter(Boolean));
  } catch (e) {
    console.error('[accounts] admin delete failed:', e.message);
    return res.status(500).json({ error: 'could not delete account' });
  }
  liveStore.remove(account.id);
  await Promise.all(['png', 'jpg', 'webp'].map((ext) => unlink(`${config.avatarsDir}/${account.id}.${ext}`).catch(() => {})));
  const result = accountStore.remove(account.id);
  if (result.error) return res.status(404).json({ error: result.error });
  adminAudit.log(req.actor, 'account.delete', 'account', account.id, { role: account.role, username: account.username ?? null });
  res.json(result);
});

