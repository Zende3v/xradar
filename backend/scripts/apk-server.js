/**
 * Temporary public download server for the EONA APK.
 *
 *   APK_DIR=/root/apk PORT=8087 node scripts/apk-server.js
 *
 * It serves the newest .apk found in APK_DIR and nothing else: the request path is
 * never used to resolve a file, so there is no way to reach anything outside that
 * directory. Plain HTTP, no auth — anyone who knows the address can download it,
 * which is the point. Delete the process (and close the port) when you are done.
 */
import { createReadStream, readdirSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';

const dir = process.env.APK_DIR || '/root/apk';
const port = Number(process.env.PORT) || 8087;
const host = process.env.HOST || '0.0.0.0';
const downloadName = process.env.APK_NAME || 'EONA.apk';

/** Newest *.apk in the directory, or null. Re-read per request so a rebuild is picked up. */
function currentApk() {
  let best = null;
  for (const name of readdirSync(dir)) {
    if (!name.toLowerCase().endsWith('.apk')) continue;
    const path = join(dir, name);
    const stat = statSync(path);
    if (!stat.isFile()) continue;
    if (!best || stat.mtimeMs > best.stat.mtimeMs) best = { name, path, stat };
  }
  return best;
}

function human(bytes) {
  return `${(bytes / 1e6).toFixed(1)} Mo`;
}

function page(apk) {
  const info = apk
    ? `<p class="meta">${apk.name} · ${human(apk.stat.size)} · ${apk.stat.mtime.toLocaleString('fr-FR')}</p>
       <a class="btn" href="/download">Télécharger l'APK</a>
       <p class="hint">Android demandera d'autoriser l'installation depuis cette source. C'est normal pour une app hors Play Store.</p>`
    : `<p class="meta">Aucun APK disponible pour le moment.</p>`;
  return `<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>EONA — téléchargement</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; min-height:100vh; display:flex; align-items:center; justify-content:center;
         background:#06070A; color:#F5F7FA; font:16px/1.5 system-ui,-apple-system,Segoe UI,sans-serif; }
  main { max-width:32rem; padding:2rem; text-align:center; }
  h1 { font-size:1.6rem; margin:0 0 .25rem; letter-spacing:-.02em; }
  .tag { color:#2CD5E0; font-size:.85rem; text-transform:uppercase; letter-spacing:.12em; }
  .meta { color:#A9B0BC; font-size:.95rem; }
  .btn { display:inline-block; margin:1.5rem 0 .5rem; padding:.9rem 2rem; border-radius:14px;
         background:#2CD5E0; color:#062024; font-weight:700; text-decoration:none; }
  .hint { color:#6C7280; font-size:.8rem; }
</style></head><body><main>
<p class="tag">EONA</p><h1>Application Android</h1>${info}
</main></body></html>`;
}

createServer((req, res) => {
  const apk = currentApk();
  const from = req.socket.remoteAddress;

  if (req.url === '/download' || req.url === `/${downloadName}`) {
    if (!apk) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      return res.end('aucun APK disponible\n');
    }
    console.log(`[apk] ${new Date().toISOString()} ${from} -> ${apk.name} (${human(apk.stat.size)})`);
    res.writeHead(200, {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': apk.stat.size,
      'Content-Disposition': `attachment; filename="${downloadName}"`,
      'Cache-Control': 'no-store',
    });
    return createReadStream(apk.path).pipe(res);
  }

  res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(page(apk));
}).listen(port, host, () => {
  const apk = currentApk();
  console.log(`[apk] serving ${dir} on ${host}:${port}`);
  console.log(apk ? `[apk] current file: ${apk.name} (${human(apk.stat.size)})` : '[apk] no .apk in the directory yet');
});
