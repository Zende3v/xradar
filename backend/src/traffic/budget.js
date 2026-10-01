import { existsSync, readFileSync, mkdirSync, writeFileSync, renameSync } from 'node:fs';
import { dirname } from 'node:path';
import { config } from '../config.js';

const USES = ['eta', 'faster'];
const KINDS = ['flow', 'incidents', 'import'];
const counts = () => Object.fromEntries(KINDS.map(k => [k, 0]));
const whole = value => Number.isSafeInteger(value) && value >= 0 ? value : 0;

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
    const day = new Date(now()).toISOString().slice(0, 10);
    const month = day.slice(0, 7);
    if (!state) {
      let saved = null;
      try {
        if (existsSync(settings.hereUsageFile)) {
          saved = JSON.parse(readFileSync(settings.hereUsageFile, 'utf8'));
          if (!saved || typeof saved.day !== 'string' || !Number.isSafeInteger(saved.monthUsed)) throw Error('invalid');
        }
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
      };
    }
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
    return save();
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
    return {
      day: s.day, used: s.used, byUse: { ...s.byUse }, byKind: { ...s.byKind },
      month: s.month, monthUsed: s.monthUsed, monthByKind: { ...s.monthByKind },
      dailyCap: settings.hereDailyCap, accountDailyMax: settings.hereAccountDailyMax,
      fasterAccountDailyMax: settings.hereFasterAccountDailyMax, deepCoverage: settings.hereDeepCoverage,
      monthlyBudgetEUR: settings.hereMonthlyBudgetEUR,
      estimatedMonthEUR: Number.isFinite(estimated) ? Math.round(estimated*1e6)/1e6 : null,
      estimateOnly: true, freeTierAssumed: 0, priceMargin: settings.herePriceMargin,
      trafficEURPer1000: settings.hereTrafficEURPer1000, importEURPer1000: settings.hereImportEURPer1000,
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
