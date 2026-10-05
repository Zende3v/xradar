import { existsSync, readFileSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { dirname } from 'node:path';
import { config } from '../config.js';

const USES = ['eta', 'faster'];
const KINDS = ['flow', 'incidents', 'import'];
const counts = () => Object.fromEntries(KINDS.map(k => [k, 0]));
const whole = value => Number.isSafeInteger(value) && value >= 0 ? value : 0;
const DAY_MS = 86_400_000;
const HISTORY_DAYS = 730;
const parisCalendar = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/Paris', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
});
function parisParts(at) {
  return Object.fromEntries(parisCalendar.formatToParts(new Date(at))
    .filter(part => part.type !== 'literal').map(part => [part.type, Number(part.value)]));
}
function parisDay(at) {
  const p = parisParts(at);
  return new Date(Date.UTC(p.year, p.month - 1, p.day)).toISOString().slice(0, 10);
}
/** Semaine locale : lundi 00:00 Paris. Offset calculé au lundi, y compris changement d'heure. */
export function hereParisWeek(at = Date.now()) {
  const p = parisParts(at);
  const localDay = Date.UTC(p.year, p.month - 1, p.day);
  const sinceMonday = (new Date(localDay).getUTCDay() + 6) % 7;
  const nominal = localDay - sinceMonday * DAY_MS;
  let startAt = nominal;
  for (let iteration = 0; iteration < 2; iteration++) {
    const parts = parisParts(startAt);
    const offset = Date.UTC(parts.year, parts.month - 1, parts.day, parts.hour, parts.minute, parts.second) - startAt;
    startAt = nominal - offset;
  }
  return { startDay: new Date(nominal).toISOString().slice(0, 10), startAt };
}
function sum(byKind) { return KINDS.reduce((total, kind) => total + byKind[kind], 0); }
function legacyHistory(saved, at) {
  const totalByKind = counts();
  for (const kind of KINDS) totalByKind[kind] = whole(saved?.monthByKind?.[kind] ?? saved?.byKind?.[kind]);
  totalByKind.import += Math.max(0, whole(saved?.monthUsed) - sum(totalByKind));
  return {
    version: 1, since: new Date(at).toISOString(),
    baselineMonth: /^\d{4}-\d{2}$/.test(saved?.month ?? '') ? saved.month : null,
    totalRequests: sum(totalByKind), totalByKind, daily: {},
  };
}
function restoreHistory(saved) {
  if (saved?.version !== 1 || !Number.isFinite(Date.parse(saved.since))
      || !Number.isSafeInteger(saved.totalRequests) || saved.totalRequests < 0
      || saved.baselineMonth != null && !/^\d{4}-\d{2}$/.test(saved.baselineMonth)
      || !saved.daily || typeof saved.daily !== 'object' || Array.isArray(saved.daily)) throw Error('invalid history');
  function checkedCounts(value) {
    if (!value || !KINDS.every(kind => Number.isSafeInteger(value[kind]) && value[kind] >= 0)) throw Error('invalid history');
    return Object.fromEntries(KINDS.map(kind => [kind, value[kind]]));
  }
  const totalByKind = checkedCounts(saved.totalByKind);
  if (sum(totalByKind) !== saved.totalRequests) throw Error('invalid history');
  const daily = {};
  const detailed = counts();
  for (const [day, value] of Object.entries(saved.daily)) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) throw Error('invalid history');
    daily[day] = checkedCounts(value);
    for (const kind of KINDS) detailed[kind] += daily[day][kind];
  }
  if (KINDS.some(kind => detailed[kind] > totalByKind[kind])) throw Error('invalid history');
  return { version: 1, since: new Date(saved.since).toISOString(), baselineMonth: saved.baselineMonth ?? null,
    totalRequests: saved.totalRequests, totalByKind, daily };
}
function trimHistory(history, at) {
  const cutoff = new Date(Date.parse(parisDay(at) + 'T00:00:00Z') - (HISTORY_DAYS - 1) * DAY_MS).toISOString().slice(0, 10);
  for (const day of Object.keys(history.daily)) if (day < cutoff) delete history.daily[day];
}

/** Réservation persistée avant chaque appel. Échec disque : aucun appel payant. */
export function createHereBudget({ settings = config, now = Date.now } = {}) {
  let state;
  let storageError = false;
  let lastDenied = null;
  function save() {
    try {
      mkdirSync(dirname(settings.hereUsageFile), { recursive: true });
      writeFileSync(settings.hereUsageFile + '.tmp', JSON.stringify(state), { encoding: 'utf8', mode: 0o600 });
      renameSync(settings.hereUsageFile + '.tmp', settings.hereUsageFile);
      return true;
    } catch {
      storageError = true;
      lastDenied = 'storage unavailable';
      return false;
    }
  }
  function today() {
    const at = now();
    const day = new Date(at).toISOString().slice(0, 10);
    const month = day.slice(0, 7);
    if (!state) {
      let saved = null;
      let history;
      try {
        if (typeof settings.hereUsageFile !== 'string' || !settings.hereUsageFile.trim()) throw Error('invalid storage');
        if (existsSync(settings.hereUsageFile)) {
          saved = JSON.parse(readFileSync(settings.hereUsageFile, 'utf8'));
          if (!saved || typeof saved.day !== 'string' || !Number.isSafeInteger(saved.monthUsed)) throw Error('invalid');
        }
        if (saved?.history != null) history = restoreHistory(saved.history);
      } catch { storageError = true; }
      const sameMonth = saved?.month === month;
      const sameDay = saved?.day === day;
      const monthByKind = counts();
      if (sameMonth) {
        for (const k of KINDS) monthByKind[k] = whole(saved.monthByKind?.[k] ?? (sameDay ? saved.byKind?.[k] : 0));
        // Ancien format : appels sans type facturés au tarif import, plus prudent.
        monthByKind.import += Math.max(0, whole(saved.monthUsed) - Object.values(monthByKind).reduce((a,b)=>a+b,0));
      }
      state = {
        day, month, used: sameDay ? whole(saved.used) : 0,
        byUse: Object.fromEntries(USES.map(k=>[k, sameDay ? whole(saved.byUse?.[k]) : 0])),
        byKind: Object.fromEntries(KINDS.map(k=>[k, sameDay ? whole(saved.byKind?.[k]) : 0])),
        monthByKind, monthUsed: Object.values(monthByKind).reduce((a,b)=>a+b,0),
        accounts: sameDay && saved?.accounts && typeof saved.accounts === 'object' ? saved.accounts : {},
        history: history ?? legacyHistory(storageError ? null : saved, at),
      };
      trimHistory(state.history, at);
      // Fixe début de mesure et migration dès première lecture, sans perdre dernier mois connu.
      if (!storageError && !history) save();
    }
    trimHistory(state.history, at);
    if (state.day !== day) {
      if (state.month !== month) { state.month = month; state.monthByKind = counts(); state.monthUsed = 0; }
      state.day = day; state.used = 0; state.byKind = counts(); state.byUse = { eta: 0, faster: 0 }; state.accounts = {};
    }
    return state;
  }
  function cost(byKind) {
    const traffic = settings.hereTrafficEURPer1000 * (settings.hereDeepCoverage ? 2 : 1);
    const routing = settings.hereImportEURPer1000;
    const margin = settings.herePriceMargin;
    if (![traffic, routing, margin, settings.hereMonthlyBudgetEUR].every(v=>Number.isFinite(v) && v>=0) || traffic===0 || routing===0 || margin<1) return Infinity;
    return ((byKind.flow + byKind.incidents) * traffic + byKind.import * routing) * margin / 1000;
  }
  function reason(kind = 'flow') {
    const s = today();
    if (!settings.hereApiKey) return 'key missing';
    if (storageError) return 'storage unavailable';
    if (!KINDS.includes(kind)) return 'invalid kind';
    if (![settings.hereMonthlyBudgetEUR, settings.hereTrafficEURPer1000, settings.hereImportEURPer1000, settings.herePriceMargin].every(Number.isFinite)
        || settings.hereMonthlyBudgetEUR < 0 || settings.hereTrafficEURPer1000 <= 0
        || settings.hereImportEURPer1000 <= 0 || settings.herePriceMargin < 1) return 'invalid pricing';
    if (settings.hereDailyCap != null && s.used >= settings.hereDailyCap) return 'daily cap';
    if (cost({ ...s.monthByKind, [kind]: s.monthByKind[kind] + 1 }) > settings.hereMonthlyBudgetEUR) return 'monthly budget';
    return null;
  }
  function reserve(use, kind) {
    const denied = reason(kind);
    if (denied || !USES.includes(use)) { lastDenied = denied || 'invalid use'; return false; }
    const s = today();
    s.used++; s.monthUsed++; s.byUse[use]++; s.byKind[kind]++; s.monthByKind[kind]++;
    const day = parisDay(now());
    const existed = Object.hasOwn(s.history.daily, day);
    const bucket = s.history.daily[day] ??= counts();
    s.history.totalRequests++; s.history.totalByKind[kind]++; bucket[kind]++;
    if (save()) return true;
    // Réservation disque refusée : fournisseur jamais appelé, historique inchangé.
    s.history.totalRequests--; s.history.totalByKind[kind]--; bucket[kind]--;
    if (!existed) delete s.history.daily[day];
    return false;
  }
  function accountAllows(id, at = now(), use = 'eta') {
    if (!USES.includes(use)) return false;
    const s = today();
    const key = use + ':' + id;
    const seen = s.accounts[key];
    const gap = use === 'faster' ? settings.rerouteCheckGapS : settings.hereAccountGapS;
    const max = use === 'faster' ? settings.hereFasterAccountDailyMax : settings.hereAccountDailyMax;
    if (seen && at - seen.at < gap * 1000 || (seen?.count ?? 0) >= max) return false;
    s.accounts[key] = { at, count: (seen?.count ?? 0) + 1 };
    // Comptes authentifiés seulement ; borne mémoire indépendante du plafond global.
    const keys = Object.keys(s.accounts);
    if (keys.length > 20_000) delete s.accounts[keys[0]];
    return !storageError && save();
  }
  function usage() {
    const s = today();
    const estimated = cost(s.monthByKind);
    const week = hereParisWeek(now());
    const currentDay = parisDay(now());
    const weekByKind = counts();
    for (const [day, bucket] of Object.entries(s.history.daily)) {
      if (day < week.startDay || day > currentDay) continue;
      for (const kind of KINDS) weekByKind[kind] += bucket[kind];
    }
    function historyCost(byKind) {
      if (storageError) return null;
      const value = cost(byKind);
      return Number.isFinite(value) ? Math.round(value * 1e6) / 1e6 : null;
    }
    return {
      day: s.day, used: s.used, byUse: { ...s.byUse }, byKind: { ...s.byKind },
      month: s.month, monthUsed: s.monthUsed, monthByKind: { ...s.monthByKind },
      dailyCap: settings.hereDailyCap, accountDailyMax: settings.hereAccountDailyMax,
      fasterAccountDailyMax: settings.hereFasterAccountDailyMax, deepCoverage: settings.hereDeepCoverage,
      monthlyBudgetEUR: settings.hereMonthlyBudgetEUR,
      estimatedMonthEUR: Number.isFinite(estimated) ? Math.round(estimated*1e6)/1e6 : null,
      estimateOnly: true, freeTierAssumed: 0, priceMargin: settings.herePriceMargin,
      trafficEURPer1000: settings.hereTrafficEURPer1000, importEURPer1000: settings.hereImportEURPer1000,
      history: {
        since: s.history.since, baselineMonth: s.history.baselineMonth,
        totalRequests: s.history.totalRequests, totalEstimatedEUR: historyCost(s.history.totalByKind),
        weekStart: week.startDay, weekRequests: sum(weekByKind), weekEstimatedEUR: historyCost(weekByKind),
        weekComplete: !storageError && Date.parse(s.history.since) <= week.startAt,
        earlierHistoryComplete: false,
      },
      blocked: reason(), lastDenied,
    };
  }
  return { reserve, accountAllows, usage, allows: () => reason() == null };
}
const budget = createHereBudget();
export const reserveHere = budget.reserve;
export const hereAllows = budget.allows;
export const hereAccountAllows = budget.accountAllows;
export const hereUsage = budget.usage;
