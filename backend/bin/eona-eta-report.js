#!/usr/bin/env node
/**
 * EONA — ETA report (phase 1 of PLAN-VALHALLA.md: D1.2 to D1.6). Read-only.
 *
 * Reads the trips saved in the accounts file and says how right the arrival time shown by the app
 * was, at departure and at 25, 50 and 75 % of the trip, slice by slice. Prints aggregates only:
 * never a label, an id or anything about an account.
 *
 * Usage (on the VPS, from /opt/eona-backend):
 *   node bin/eona-eta-report.js [accounts.json] [--json] [--since=2026-10-01T14:25:00+02:00]
 * File: the argument, else $ACCOUNTS_FILE, else ./data/accounts.json. [--since] : trajets partis
 * depuis cette date seulement (ex. dernier correctif ETA).
 */

import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

// Peak hours (D1.6), decided by Arthur (P1.1), Europe/Paris time: Monday to Friday 07:00-10:00 and
// 16:00-20:00, Saturday 10:00-19:00; everything else is off-peak. Days 1 = Monday … 7 = Sunday,
// [from, to) in minutes of the day.
export const PEAK_HOURS = {
  1: [[7 * 60, 10 * 60], [16 * 60, 20 * 60]],
  2: [[7 * 60, 10 * 60], [16 * 60, 20 * 60]],
  3: [[7 * 60, 10 * 60], [16 * 60, 20 * 60]],
  4: [[7 * 60, 10 * 60], [16 * 60, 20 * 60]],
  5: [[7 * 60, 10 * 60], [16 * 60, 20 * 60]],
  6: [[10 * 60, 19 * 60]],
};
const PEAK_TIME_ZONE = 'Europe/Paris';

// A good ETA (D1.2): off by at most 10 % of the time left, 2 min whatever the trip, never more
// than 8 min.
const GOOD_RATIO = 0.1;
const GOOD_MIN_S = 120;
const GOOD_MAX_S = 480;
const CHECKPOINTS = [0, 25, 50, 75];
// Slices of driven time (D1.6), in minutes: [label, from, to).
const DURATION_SLICES = [['< 20 min', 0, 20], ['20-60 min', 20, 60], ['> 60 min', 60, Infinity]];
// 95 % Wilson interval.
const Z = 1.959964;

// The stops the traffic could not tell (D1.4) are reported both ways.
export const VARIANTS = [
  { key: 'uncertainAsPause', title: 'Arrêts incertains retirés (comptés comme pauses)', uncertainIsPause: true },
  { key: 'uncertainKept', title: 'Arrêts incertains gardés (comptés comme conduite)', uncertainIsPause: false },
];

/** Why a trip is left out of the measure (D1.5, P1.6), or null when it counts. */
export function exclusion(trip) {
  // Count every changed destination apart, even if another exclusion also applies.
  if (trip.retargeted === true) return 'retargeted';
  if (!('arrived' in trip) && !('etaChecks' in trip)) return 'sans mesures (app d\'avant la phase 1)';
  if (trip.arrived !== true) return 'non arrivé (arrêté en route)';
  if (trip.manualStart === true) return 'départ choisi à la main';
  if (!(Number(trip.departedAt) > 0)) return 'sans départ réel';
  if (!Array.isArray(trip.etaChecks) || !trip.etaChecks.length) return 'sans relevé d\'ETA';
  if (!Number.isFinite(trip.startedAt) || !Number.isFinite(trip.durationSeconds)) return 'durée illisible';
  return null;
}

/**
 * A kept trip's snapshots judged (D1.2, D1.4): the real arrival minus the pauses taken after the
 * snapshot, against the arrival shown. errorS > 0: arrived later than announced. With
 * [variant].uncertainIsPause, uncertain stops count as pauses too. drivenMin: the trip's driving
 * time from its real departure, the pauses taken since then removed (the departure snapshot's
 * pausedBefore/uncertainBefore are stops made before it).
 */
export function judge(trip, variant) {
  const uncertain = variant.uncertainIsPause;
  const actual = trip.startedAt + trip.durationSeconds * 1000;
  const pausedS = (Number(trip.pausedSeconds) || 0) + (uncertain ? Number(trip.uncertainSeconds) || 0 : 0);
  const pausedBefore = (check) => (Number(check?.pausedBefore) || 0) + (uncertain ? Number(check?.uncertainBefore) || 0 : 0);
  const departed = trip.etaChecks.find((check) => check?.at === 0);
  const checks = [];
  for (const check of trip.etaChecks) {
    if (!CHECKPOINTS.includes(check?.at) || !(check.arrivalAt > 0) || !(check.shownAt > 0)) continue;
    const before = pausedBefore(check);
    const adjusted = actual - Math.max(0, pausedS - before) * 1000;
    const errorS = (adjusted - check.arrivalAt) / 1000;
    const remainingS = (adjusted - check.shownAt) / 1000;
    checks.push({ at: check.at, errorS, good: Math.abs(errorS) <= tolerance(remainingS) });
  }
  const pausedSinceS = Math.max(0, pausedS - (departed ? pausedBefore(departed) : 0));
  return { drivenMin: (actual - pausedSinceS * 1000 - trip.departedAt) / 60000, checks };
}

/** The largest error still good for [remainingS] seconds left (D1.2). */
export function tolerance(remainingS) {
  return Math.min(GOOD_MAX_S, Math.max(GOOD_MIN_S, GOOD_RATIO * remainingS));
}

const parisClock = new Intl.DateTimeFormat('en-GB', {
  timeZone: PEAK_TIME_ZONE, weekday: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
});
const WEEKDAYS = { Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7 };

/** Whether [epochMs] falls in the peak hours (PEAK_HOURS, Paris time). */
export function isPeak(epochMs) {
  const parts = Object.fromEntries(parisClock.formatToParts(new Date(epochMs)).map((p) => [p.type, p.value]));
  const minutes = Number(parts.hour) * 60 + Number(parts.minute);
  return (PEAK_HOURS[WEEKDAYS[parts.weekday]] ?? []).some(([from, to]) => minutes >= from && minutes < to);
}

/** The 95 % Wilson interval of [good] out of [n], as [low, high]; null for n = 0. */
export function wilson(good, n) {
  if (!n) return null;
  const p = good / n;
  const z2 = Z * Z;
  const denominator = 1 + z2 / n;
  const center = (p + z2 / (2 * n)) / denominator;
  const half = (Z * Math.sqrt((p * (1 - p)) / n + z2 / (4 * n * n))) / denominator;
  return [Math.max(0, center - half), Math.min(1, center + half)];
}

export function median(values) {
  if (!values.length) return null;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = sorted.length >> 1;
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

/** The slices a kept trip belongs to (D1.6 and the comparison keys of D1.7). */
function slicesOf(trip, drivenMin) {
  const duration = DURATION_SLICES.find(([, from, to]) => drivenMin >= from && drivenMin < to)?.[0] ?? DURATION_SLICES[0][0];
  const peak = isPeak(trip.departedAt) ? 'pointe' : 'hors pointe';
  const engines = Array.isArray(trip.engines) && trip.engines.length ? trip.engines.join('+') : 'inconnu';
  return [
    'tous',
    `durée ${duration}`,
    peak,
    `durée ${duration}, ${peak}`,
    `mode d'ETA ${trip.etaMode ?? 'inconnu'}`,
    `moteur ${engines}`,
    `version ${trip.platform ?? '?'} ${trip.appVersion ?? 'inconnue'}`,
  ];
}

/** The whole report, from the saved accounts; [since] (epoch ms) : trajets partis depuis seulement. */
export function report(accounts, { since = null } = {}) {
  const trips = (Array.isArray(accounts) ? accounts : []).flatMap((a) => (Array.isArray(a?.trips) ? a.trips : []))
    .filter((trip) => since == null || Number(trip?.startedAt) >= since);
  const excluded = {};
  const kept = [];
  for (const trip of trips) {
    const reason = trip && typeof trip === 'object' ? exclusion(trip) : 'illisible';
    if (reason) excluded[reason] = (excluded[reason] ?? 0) + 1;
    else kept.push(trip);
  }
  const variants = {};
  for (const variant of VARIANTS) {
    const slices = new Map(); // name -> at -> [{ errorS, good }]
    for (const trip of kept) {
      const { drivenMin, checks } = judge(trip, variant);
      for (const name of slicesOf(trip, drivenMin)) {
        if (!slices.has(name)) slices.set(name, new Map(CHECKPOINTS.map((at) => [at, []])));
        for (const check of checks) slices.get(name).get(check.at).push(check);
      }
    }
    variants[variant.key] = {
      title: variant.title,
      slices: [...slices].map(([name, byAt]) => ({
        name,
        checkpoints: CHECKPOINTS.map((at) => {
          const list = byAt.get(at);
          const good = list.filter((c) => c.good).length;
          return {
            at,
            n: list.length,
            good,
            goodRate: list.length ? good / list.length : null,
            wilson95: wilson(good, list.length),
            medianAbsErrorS: median(list.map((c) => Math.abs(c.errorS))),
            medianErrorS: median(list.map((c) => c.errorS)),
          };
        }),
      })),
    };
  }
  return {
    generatedAt: new Date().toISOString(),
    since: since == null ? null : new Date(since).toISOString(),
    rules: {
      good: `|erreur| <= min(${GOOD_MAX_S} s, max(${GOOD_MIN_S} s, ${GOOD_RATIO * 100} % du temps restant réel))`,
      error: 'arrivée réelle (pauses après le relevé retirées) - arrivée affichée, en s ; > 0 = arrivé plus tard qu\'annoncé',
      peak: 'lun-ven 07:00-10:00 et 16:00-20:00, sam 10:00-19:00, heure de Paris',
    },
    trips: { read: trips.length, kept: kept.length, excluded },
    variants,
  };
}

// ---- Printing ------------------------------------------------------------------

const percent = (x) => (x == null ? '-' : `${Math.round(x * 100)} %`);
const minutes = (s, signed = false) => (s == null ? '-' : `${signed && s >= 0 ? '+' : ''}${(s / 60).toFixed(1)}`);
/** One table line: each cell right-aligned in its width. */
const row = (cells) => `  ${cells.map(([text, width]) => String(text).padStart(width)).join('  ')}`;
const WIDTHS = [6, 5, 7, 15, 12, 12];

function print(result) {
  const out = [];
  out.push(`Rapport ETA — ${result.generatedAt}`);
  if (result.since) out.push(`Trajets partis depuis : ${result.since}`);
  out.push(`Bonne ETA : ${result.rules.good}`);
  out.push(`Erreur : ${result.rules.error}`);
  out.push(`Pointe : ${result.rules.peak}`);
  out.push('');
  out.push(`Trajets lus : ${result.trips.read}, gardés : ${result.trips.kept}`);
  for (const [reason, n] of Object.entries(result.trips.excluded).sort((a, b) => b[1] - a[1])) {
    out.push(`  exclus, ${reason} : ${n}`);
  }
  for (const variant of Object.values(result.variants)) {
    out.push('');
    out.push(`== ${variant.title}`);
    if (!variant.slices.length) out.push('  (aucun trajet gardé)');
    for (const slice of variant.slices) {
      out.push(`-- ${slice.name}`);
      out.push(row(['relevé', 'n', 'bonnes', 'Wilson 95 %', '|err| méd.', 'biais méd.'].map((h, i) => [h, WIDTHS[i]])) + '  (min)');
      for (const c of slice.checkpoints) {
        const interval = c.wilson95 ? `${percent(c.wilson95[0])} - ${percent(c.wilson95[1])}` : '-';
        const cells = [`${c.at} %`, c.n, percent(c.goodRate), interval, minutes(c.medianAbsErrorS), minutes(c.medianErrorS, true)];
        out.push(row(cells.map((cell, i) => [cell, WIDTHS[i]])));
      }
    }
  }
  console.log(out.join('\n'));
}

function main() {
  const args = process.argv.slice(2);
  const json = args.includes('--json');
  const file = args.find((a) => !a.startsWith('--')) || process.env.ACCOUNTS_FILE || './data/accounts.json';
  const sinceArg = args.find((a) => a.startsWith('--since='))?.slice('--since='.length);
  const since = sinceArg ? Date.parse(sinceArg) : null;
  if (sinceArg && !Number.isFinite(since)) {
    console.error(`✗ date --since illisible : ${sinceArg}`);
    process.exit(1);
  }
  let accounts;
  try {
    accounts = JSON.parse(readFileSync(file, 'utf8'));
  } catch (e) {
    console.error(`✗ fichier des comptes illisible (${file}) : ${e.message}`);
    process.exit(1);
  }
  const result = report(accounts, { since });
  if (json) console.log(JSON.stringify(result, null, 2));
  else print(result);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
