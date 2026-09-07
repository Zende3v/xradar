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

/** What any viewer may see about an account (no email, no password). */
export function publicView(account) {
  if (!account) return null;
  return {
    id: account.id,
    role: account.role,
    username: account.username ?? null,
    displayName: account.displayName ?? null,
    avatarUrl: account.avatarUrl ?? null,
    banned: Boolean(account.banned),
  };
}
