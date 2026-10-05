import { config } from '../config.js';
import { accountStore } from './store.js';

/** Bearer invalide ferme accès. Compatibilité deviceId limitée aux invités. */
export function authAccount(req) {
  const header = req.get('authorization');
  if (header !== undefined && header !== null) {
    const token = /^Bearer\s+(\S+)$/i.exec(header)?.[1];
    return token ? accountStore.resolveToken(token) : null;
  }
  const deviceId = req.body?.deviceId || req.query?.deviceId;
  if (deviceId) {
    const account = accountStore.getByDevice(String(deviceId));
    return account?.role === 'guest' ? account : null;
  }
  return null;
}

/**
 * Who acts as an admin, or null: an admin account signed in with its session — never a device
 * id alone, never a banned account — or the ADMIN_TOKEN, kept for scripts. The answer names the
 * actor, so that what they do can be written in the journal.
 */
export function adminActor(req) {
  const header = req.get('authorization');
  const m = /^Bearer\s+(\S+)$/i.exec(header || '');
  const token = m?.[1] ?? null;
  if (header != null && !token) return null;
  if (config.adminToken && (token === config.adminToken || (header == null && req.get('x-admin-token') === config.adminToken))) {
    return { kind: 'token', id: null, name: 'ADMIN_TOKEN' };
  }
  const account = token ? accountStore.resolveToken(token) : null;
  if (account?.role === 'admin' && accountStore.accessFor(account).canNavigate) {
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
    banned: accountStore.blockReason(account) === 'banned',
    suspended: Boolean(account.suspended),
    revoked: Boolean(account.revoked),
    access: access.status,
    canNavigate: access.canNavigate,
    accessEndsAt: access.endsAt,
    tier: accountStore.tierFor(account),
    hasPlus: accountStore.hasPlus(account),
  };
}
