import { config } from '../config.js';

/** Photos téléversées sur EONA seulement, jamais une adresse choisie chez un tiers. */
export function safeAvatarUrl(value, accountId) {
  if (typeof value !== 'string' || !/^[\w-]+$/.test(String(accountId ?? ''))) return null;
  try {
    const base = new URL(config.publicBaseUrl);
    const url = new URL(value);
    if (url.origin !== base.origin || url.username || url.password || url.hash) return null;
    const prefix = `${base.pathname.replace(/\/$/, '')}/avatars/${accountId}.`;
    if (!['png', 'jpg', 'webp'].some((ext) => url.pathname === prefix + ext)) return null;
    const query = [...url.searchParams];
    if (query.length > 1 || query.some(([key, v]) => key !== 'v' || !/^\d+$/.test(v))) return null;
    return url.href;
  } catch { return null; }
}
