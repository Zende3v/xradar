#!/usr/bin/env node
/**
 * EONA — account admin CLI.
 *
 * Manages guest/client/admin accounts through the backend's admin API.
 * Config via env (or a .env you `source`):
 *   X_RADAR_URL          backend base URL   (default http://127.0.0.1:8090)
 *   X_RADAR_ADMIN_TOKEN  must match the backend's ADMIN_TOKEN — when it is not set,
 *                        the token is read straight from the running systemd service.
 *
 * Usage:
 *   eona-accounts list [guest|client|admin]
 *   eona-accounts show <id>
 *   eona-accounts add <guest|client|admin> [--device=ID] [--name="Nom"]
 *   eona-accounts set <id> [--role=guest|client|admin] [--name="Nom"] [--ban] [--unban]
 *   eona-accounts del <id>
 */

import { execFileSync } from 'node:child_process';

const BASE = (process.env.X_RADAR_URL || 'http://127.0.0.1:8090').replace(/\/$/, '');
const TOKEN = process.env.X_RADAR_ADMIN_TOKEN || tokenFromService();
const ROLES = ['guest', 'client', 'admin'];

/**
 * The backend already knows the token: it is in the systemd unit (or its drop-in).
 * Rather than making you export it by hand, ask systemd. Silent no-op off a systemd
 * host, or when the unit is not there — the caller then reports the missing token.
 */
function tokenFromService() {
  const unit = process.env.X_RADAR_SERVICE || 'eona-backend';
  try {
    const raw = execFileSync(
      'systemctl',
      ['show', unit, '--property=Environment', '--value'],
      { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] },
    );
    // systemd quotes values containing spaces: ADMIN_TOKEN=abc "SMTP_PASS=x y z"
    const match = raw.match(/(?:^|\s)"?ADMIN_TOKEN=([^"\s]+)"?/);
    return match ? match[1] : '';
  } catch {
    return '';
  }
}

async function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  if (!cmd || cmd === 'help' || cmd === '--help' || cmd === '-h') return usage();
  if (!TOKEN) {
    fail(
      'jeton admin introuvable.\n' +
      "  Le service tourne ? `systemctl is-active eona-backend`\n" +
      '  Sinon, exporte-le à la main :\n' +
      '    export X_RADAR_ADMIN_TOKEN=<le même que ADMIN_TOKEN du backend>',
    );
  }

  switch (cmd) {
    case 'list': return list(rest[0]);
    case 'show': return show(requireArg(rest[0], 'id'));
    case 'add': return add(rest);
    case 'set': return set(rest);
    case 'del':
    case 'rm':
    case 'delete': return del(requireArg(rest[0], 'id'));
    default: fail(`unknown command "${cmd}"`, true);
  }
}

async function api(method, path, body) {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { 'content-type': 'application/json', authorization: `Bearer ${TOKEN}` },
    body: body ? JSON.stringify(body) : undefined,
  }).catch((e) => fail(`cannot reach backend at ${BASE} (${e.message})`));
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text }; }
  if (!res.ok) fail(`${res.status} ${json.error || text || res.statusText}`);
  return json;
}

/** Every account, page after page: the admin API hands them out by 200 at most. */
async function allAccounts(role) {
  const accounts = [];
  let meta = null;
  let offset = 0;
  while (offset !== null) {
    const q = `?limit=200&offset=${offset}${role ? `&role=${role}` : ''}`;
    const page = await api('GET', `/api/admin/accounts${q}`);
    accounts.push(...page.accounts);
    meta = page.meta;
    offset = page.next ?? null;
  }
  return { accounts, meta };
}

async function list(role) {
  if (role && !ROLES.includes(role)) fail(`invalid role "${role}"`);
  const { accounts, meta } = await allAccounts(role);
  if (!accounts.length) {
    console.log('Aucun compte.');
  } else {
    printTable(accounts);
  }
  console.log(`\nTotal ${meta.count} — admin ${meta.admin} · client ${meta.client} · guest ${meta.guest}`);
}

async function show(arg) {
  const id = await resolveId(arg);
  const { account } = await api('GET', `/api/admin/accounts/${id}`);
  console.log(JSON.stringify(account, null, 2));
}

/** Accept either an account id or a deviceId (both are UUIDs) and return the account id. */
async function resolveId(arg) {
  const { accounts } = await allAccounts();
  const hit = accounts.find((a) => a.id === arg || a.deviceId === arg);
  if (!hit) fail(`aucun compte avec l'id (ou device) "${arg}" — vérifie avec: list`);
  return hit.id;
}

async function add(args) {
  const role = args.find((a) => !a.startsWith('--'));
  if (!role || !ROLES.includes(role)) {
    fail('usage: add <guest|client|admin> --name=pseudo [--email=.. --password=..] [--device=ID]');
  }
  const opts = parseFlags(args);
  if ((role === 'admin' || role === 'client') && (!opts.email || !opts.password || !opts.name)) {
    fail(`${role} exige --email, --password et --name (pseudo)`);
  }
  const { account } = await api('POST', '/api/admin/accounts', {
    role,
    deviceId: opts.device || null,
    username: opts.name ?? null,
    email: opts.email ?? null,
    password: opts.password ?? null,
  });
  console.log(`Créé ${account.role} ${account.id}`);
  printTable([account]);
}

async function set(args) {
  const id = await resolveId(requireArg(args.find((a) => !a.startsWith('--')), 'id'));
  const opts = parseFlags(args);
  const patch = {};
  if (opts.role) {
    if (!ROLES.includes(opts.role)) fail(`invalid role "${opts.role}"`);
    patch.role = opts.role;
  }
  if (opts.name !== undefined) patch.displayName = opts.name;
  if (opts.ban) patch.banned = true;
  if (opts.unban) patch.banned = false;
  if (Object.keys(patch).length === 0) fail('nothing to change (use --role / --name / --ban / --unban)');
  const { account } = await api('PATCH', `/api/admin/accounts/${id}`, patch);
  console.log('Mis à jour :');
  printTable([account]);
}

async function del(arg) {
  const id = await resolveId(arg);
  await api('DELETE', `/api/admin/accounts/${id}`);
  console.log(`Supprimé ${id}`);
}

// ---- helpers ---------------------------------------------------------------

function parseFlags(args) {
  const opts = {};
  for (const a of args) {
    const m = /^--([^=]+)(?:=(.*))?$/.exec(a);
    if (m) opts[m[1]] = m[2] === undefined ? true : m[2];
  }
  return opts;
}

function printTable(accounts) {
  const rows = accounts.map((a) => ({
    id: a.id,
    role: a.role,
    device: a.deviceId || '—',
    name: a.displayName || '—',
    banned: a.banned ? 'OUI' : '',
    lastSeen: (a.lastSeenAt || '').replace('T', ' ').slice(0, 16),
  }));
  const cols = ['id', 'role', 'device', 'name', 'banned', 'lastSeen'];
  const width = {};
  for (const c of cols) width[c] = Math.max(c.length, ...rows.map((r) => String(r[c]).length));
  const line = (r) => cols.map((c) => String(r[c]).padEnd(width[c])).join('  ');
  console.log(line(Object.fromEntries(cols.map((c) => [c, c.toUpperCase()]))));
  console.log(cols.map((c) => '-'.repeat(width[c])).join('  '));
  for (const r of rows) console.log(line(r));
}

function requireArg(value, name) {
  if (!value) fail(`missing <${name}>`, true);
  return value;
}

function fail(msg, showUsage = false) {
  console.error(`Erreur : ${msg}`);
  if (showUsage) usage();
  process.exit(1);
}

function usage() {
  console.log(`EONA accounts — gestion des comptes (${BASE})

  list [guest|client|admin]                     lister les comptes
  show <id>                                     détail d'un compte
  add <guest|client|admin> --name=pseudo [--email=.. --password=..] [--device=ID]
      (admin/client exigent --email, --password et --name)
  set <id> [--role=R] [--name="Nom"] [--ban] [--unban]
  del <id>                                      supprimer

Env : X_RADAR_URL (défaut ${BASE}), X_RADAR_ADMIN_TOKEN (obligatoire).`);
}

main();
