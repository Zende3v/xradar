import { randomUUID } from 'node:crypto';
import { unlink } from 'node:fs/promises';
import { Router } from 'express';
import { config } from '../config.js';
import { transaction } from '../crowd/schema.js';
import { liveStore } from '../live/store.js';
import { authAccount, publicView } from './auth.js';
import { accountStore } from './store.js';
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
    // A guest's daily limits and today's use; null for clients and admins.
    limits: accountStore.limitsFor(a),
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
  const account = accountStore.auth(deviceId, { platform: req.body?.platform });
  if (account.banned) return res.status(403).json({ error: 'banned', account: publicView(account) });
  const token = accountStore.issueToken(account.id);
  res.json({ account: selfView(account), token });
});

/** GET /api/accounts/username-available?u=pseudo */
accountRouter.get('/username-available', (req, res) => {
  const check = accountStore.usernameAvailable(String(req.query.u || ''));
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
  const token = accountStore.issueToken(result.account.id);
  res.json({ account: selfView(result.account), token });
});

/** GET /api/accounts/me — current account from the Bearer token / deviceId. */
accountRouter.get('/me', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  res.json({ account: selfView(account) });
});

/** PATCH /api/accounts/me  { username?, avatarUrl? } — client/admin only. */
accountRouter.patch('/me', (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  if (account.role === 'guest') {
    return res.status(403).json({ error: 'profile edits require a client account' });
  }
  const result = accountStore.setProfile(account.id, {
    username: req.body?.username !== undefined ? String(req.body.username).trim() : undefined,
    avatarUrl: req.body?.avatarUrl,
  });
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
    }
  });
}

// ---- Statistics (everyone, server-side) -------------------------------------

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

/** POST /api/accounts/logout — revoke the current Bearer token. */
accountRouter.post('/logout', (req, res) => {
  const m = /^Bearer\s+(.+)$/i.exec(req.get('authorization') || '');
  if (m) accountStore.revokeToken(m[1]);
  res.json({ ok: true });
});

// ---- Admin API (token-guarded) --------------------------------------------

export const adminAccountRouter = Router();

adminAccountRouter.use((req, res, next) => {
  if (!config.adminToken) {
    return res.status(503).json({ error: 'admin API disabled (set ADMIN_TOKEN)' });
  }
  const token = bearer(req.get('authorization')) || req.get('x-admin-token');
  if (token !== config.adminToken) return res.status(401).json({ error: 'unauthorized' });
  next();
});

/** Admin view: full record minus the password hash. */
function adminView(a) {
  if (!a) return a;
  const { passwordHash, ...rest } = a;
  return rest;
}

adminAccountRouter.get('/', (req, res) => {
  const role = req.query.role ? String(req.query.role) : undefined;
  const accounts = accountStore.list(role).map(adminView);
  res.json({ count: accounts.length, meta: accountStore.meta, accounts });
});

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
  res.status(201).json({ account: adminView(result.account) });
});

adminAccountRouter.patch('/:id', (req, res) => {
  const result = accountStore.update(req.params.id, {
    role: req.body?.role,
    displayName: req.body?.displayName,
    banned: req.body?.banned,
  });
  if (result.error) return res.status(result.error === 'not found' ? 404 : 400).json({ error: result.error });
  res.json({ account: adminView(result.account) });
});

adminAccountRouter.delete('/:id', (req, res) => {
  const result = accountStore.remove(req.params.id);
  if (result.error) return res.status(404).json({ error: result.error });
  res.json(result);
});

function bearer(header) {
  if (!header) return null;
  const m = /^Bearer\s+(.+)$/i.exec(header);
  return m ? m[1] : null;
}
