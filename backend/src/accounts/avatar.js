import { mkdir, writeFile } from 'node:fs/promises';
import { Router } from 'express';
import { config } from '../config.js';
import { authAccount, publicView } from './auth.js';
import { accountStore } from './store.js';

export const avatarRouter = Router();

const EXT = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' };

/**
 * POST /api/accounts/avatar  { dataUrl: "data:image/png;base64,..." }
 * Client/admin only. Stores the image on disk (served at /avatars) and sets avatarUrl.
 */
avatarRouter.post('/', async (req, res) => {
  const account = authAccount(req);
  if (!account) return res.status(401).json({ error: 'unauthorized' });
  if (account.role === 'guest') return res.status(403).json({ error: 'profile picture requires a client account' });

  const m = /^data:(image\/(?:png|jpeg|webp));base64,(.+)$/i.exec(String(req.body?.dataUrl || ''));
  if (!m) return res.status(400).json({ error: 'dataUrl must be a base64 png/jpeg/webp image' });
  const mime = m[1].toLowerCase();
  const bytes = Buffer.from(m[2], 'base64');
  if (bytes.length === 0 || bytes.length > config.avatarMaxBytes) {
    return res.status(413).json({ error: `image must be 1..${config.avatarMaxBytes} bytes` });
  }

  try {
    await mkdir(config.avatarsDir, { recursive: true });
    const file = `${account.id}.${EXT[mime]}`;
    await writeFile(`${config.avatarsDir}/${file}`, bytes);
    // Cache-bust so the app reloads the new image.
    const url = `${config.publicBaseUrl}/avatars/${file}?v=${Date.now()}`;
    const result = accountStore.setProfile(account.id, { avatarUrl: url });
    if (result.error) return res.status(400).json({ error: result.error });
    res.json({ account: { ...publicView(result.account), email: result.account.email ?? null } });
  } catch (e) {
    res.status(500).json({ error: 'could not store image', detail: String(e.message || e) });
  }
});
