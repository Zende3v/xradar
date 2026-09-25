import { readFileSync } from 'node:fs';
import { mkdir, rename, writeFile } from 'node:fs/promises';
import { dirname } from 'node:path';

/**
 * A small JSON state kept on disk so it survives a restart (the ORS keys' counters, TomTom's):
 * read once, the first time it is needed; written a moment after it changes ([snapshot] gives
 * what to write then), through a temporary file renamed over the old one — a crash mid-write
 * never leaves half a file. A second change within [delayMs] joins the same write.
 */
export function stateFile(path, label, snapshot, delayMs = 1000) {
  let timer = null;
  let writing = Promise.resolve();
  const write = async () => {
    const tmp = `${path}.tmp`;
    await mkdir(dirname(path), { recursive: true });
    await writeFile(tmp, JSON.stringify(snapshot()), 'utf8');
    await rename(tmp, path);
  };
  return {
    /** What was saved; null without a file (or an unreadable one: counting starts again). */
    read() {
      try {
        return JSON.parse(readFileSync(path, 'utf8'));
      } catch (e) {
        if (e.code !== 'ENOENT') console.warn(`[${label}] ${path} unreadable —`, e.message);
        return null;
      }
    },
    /** Something changed: written soon, one write at a time. */
    touch() {
      if (timer) return;
      timer = setTimeout(() => {
        timer = null;
        writing = writing.then(write).catch((e) => console.error(`[${label}] save failed:`, e.message));
      }, delayMs);
      if (timer.unref) timer.unref();
    },
  };
}
