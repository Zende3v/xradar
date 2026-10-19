import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { after, describe, it } from 'node:test';

// Copie dérivée du vrai script : seuls chemins, verrou et PostgreSQL remplacés. Aucune sauvegarde réelle.
const SCRIPT = readFileSync(new URL('../bin/eona-backup.sh', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const STUBS = [
  ['export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n', ''],
  ['DEST=/var/backups/eona\n', 'DEST="$FIXTURE_DEST"\n'],
  ['DATA=/opt/eona-backend/data\n', 'DATA="$FIXTURE_DATA"\n'],
  ["flock -n 9 || { echo '[backup] sauvegarde déjà active' >&2; exit 1; }\n", 'true\n'],
  ['runuser -u eona -- pg_dump -d eona -Fc -n crowd -n routing > "$TEMP/crowd.dump"\n', ': > "$TEMP/crowd.dump"\n'],
  ['pg_restore --list "$TEMP/crowd.dump" > /dev/null\n', 'true\n'],
];
const bashOk = spawnSync('bash', ['-c', 'tar --version'], { encoding: 'utf8' }).status === 0;
const directory = mkdtempSync(join(tmpdir(), 'eona-backup-'));
after(async () => {
  const target = resolve(directory);
  assert.ok(target.startsWith(`${resolve(tmpdir())}${sep}`));
  await rm(target, { recursive: true, force: true });
});
// Bash Windows (Git/MSYS) : C:\x → /c/x ; tar lirait « C: » comme hôte distant.
const posix = (path) => process.platform === 'win32' ? path.replace(/^([A-Za-z]):/, (_, d) => `/${d.toLowerCase()}`).replace(/\\/g, '/') : path;

function run(name, withBans) {
  let script = SCRIPT;
  for (const [from, to] of STUBS) {
    assert.equal(script.split(from).length, 2, `ligne attendue absente : ${from.trim()}`);
    script = script.replace(from, to);
  }
  const root = join(directory, name);
  const data = join(root, 'data');
  mkdirSync(join(data, 'avatars'), { recursive: true });
  writeFileSync(join(data, 'accounts.json'), '[]');
  writeFileSync(join(data, 'device-trials.json'), '{}');
  writeFileSync(join(data, 'settings.json'), '{}');
  if (withBans) writeFileSync(join(data, 'accounts.json.bans.json'), '{"version":1}');
  writeFileSync(join(root, 'backup.sh'), script);
  const env = { ...process.env, FIXTURE_DEST: posix(join(root, 'dest')), FIXTURE_DATA: posix(data) };
  const out = spawnSync('bash', [posix(join(root, 'backup.sh'))], { env, encoding: 'utf8' });
  assert.equal(out.status, 0, out.stderr);
  const day = spawnSync('bash', ['-c', 'date +%F'], { encoding: 'utf8' }).stdout.trim();
  const list = spawnSync('bash', ['-c', `tar tzf "${posix(join(root, 'dest', `data-${day}.tgz`))}"`], { encoding: 'utf8' });
  assert.equal(list.status, 0, list.stderr);
  return list.stdout.split('\n').filter(Boolean);
}

describe('sauvegarde quotidienne : registre bans (audit 19/10)', { skip: !bashOk && 'bash/tar indisponibles' }, () => {
  it('syntaxe bash valide', () => {
    const out = spawnSync('bash', ['-n', posix(resolve('bin/eona-backup.sh'))], { encoding: 'utf8' });
    assert.equal(out.status, 0, out.stderr);
  });

  it('registre présent : archivé', () => {
    assert.ok(run('with', true).includes('accounts.json.bans.json'));
  });

  it('registre absent : archive produite sans lui', () => {
    const files = run('without', false);
    assert.ok(files.includes('accounts.json'));
    assert.ok(files.includes('settings.json'));
    assert.equal(files.includes('accounts.json.bans.json'), false);
  });
});
