import { Router } from 'express';
import { config } from '../config.js';
import { authAccount, publicView } from './auth.js';
import { accountStore } from './store.js';

export const accountRouter = Router();

/** Self view: adds the private email to the public view. */
function selfView(a) {
  return { ...publicView(a), email: a.email ?? null };
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
 * POST /api/accounts/guest  { deviceId, username, platform? }
 * Guest identity: a unique username tied to the device. No password.
 */
accountRouter.post('/guest', (req, res) => {
  const deviceId = String(req.body?.deviceId || '').trim();
  const result = accountStore.claimGuest(deviceId, String(req.body?.username || '').trim(), {
    platform: req.body?.platform,
  });
  if (result.error) return res.status(400).json({ error: result.error });
  const token = accountStore.issueToken(result.account.id);
  res.status(201).json({ account: selfView(result.account), token });
});

/** POST /api/accounts/register  { email, password, username } → client account. */
accountRouter.post('/register', (req, res) => {
  const result = accountStore.register({
    email: req.body?.email,
    password: req.body?.password,
    username: String(req.body?.username || '').trim(),
  });
  if (result.error) return res.status(400).json({ error: result.error });
  const token = accountStore.issueToken(result.account.id);
  res.status(201).json({ account: selfView(result.account), token });
});

/** POST /api/accounts/login  { email, password } → { account, token }. */
accountRouter.post('/login', (req, res) => {
  const result = accountStore.login(req.body?.email, req.body?.password);
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
