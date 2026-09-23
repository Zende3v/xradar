import { config } from '../config.js';
import { accountStore } from './store.js';

/** Resolve the caller's account from a Bearer session token, or a deviceId (guests). */
export function authAccount(req) {
  const header = req.get('authorization');
  const m = header && /^Bearer\s+(.+)$/i.exec(header);
  if (m) {
    const account = accountStore.resolveToken(m[1]);
    if (account) return account;
  }
  const deviceId = req.body?.deviceId || req.query?.deviceId;
  if (deviceId) return accountStore.getByDevice(String(deviceId));
  return null;
}

/**
 * Who acts as an admin, or null: an admin account signed in with its session — never a device
 * id alone, never a banned account — or the ADMIN_TOKEN, kept for scripts. The answer names the
 * actor, so that what they do can be written in the journal.
 */
export function adminActor(req) {
  const m = /^Bearer\s+(.+)$/i.exec(req.get('authorization') || '');
  const token = m?.[1] ?? null;
  if (config.adminToken && (token === config.adminToken || req.get('x-admin-token') === config.adminToken)) {
    return { kind: 'token', id: null, name: 'ADMIN_TOKEN' };
  }
  const account = token ? accountStore.resolveToken(token) : null;
  if (account?.role === 'admin' && !account.banned) {
    return { kind: 'account', id: account.id, name: account.username ?? account.displayName ?? account.id };
  }
  return null;
}

/** Moderation rights: see adminActor. */
export function isAdminRequest(req) {
  return adminActor(req) !== null;
}

/** What any viewer may see about an account (no email, no password). */
export function publicView(account) {
  if (!account) return null;
  const access = accountStore.accessFor(account);
  return {
    id: account.id,
    role: account.role,
    username: account.username ?? null,
    displayName: account.displayName ?? null,
    avatarUrl: account.avatarUrl ?? null,
    banned: Boolean(account.banned),
    access: access.status,
    canNavigate: access.canNavigate,
    accessEndsAt: access.endsAt,
  };
}
