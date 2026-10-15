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

const PRODUCTS = ['realTimeTraffic', 'advancedTraffic', 'routeImport'];
const productCounts = () => Object.fromEntries(PRODUCTS.map(product => [product, 0]));
const utcDay = at => new Date(at).toISOString().slice(0, 10);
const validDay = day => typeof day === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(day)
  && Number.isFinite(Date.parse(day)) && utcDay(Date.parse(day)) === day;
const previousDay = day => utcDay(Date.parse(day) - DAY_MS);
function checkedProducts(value) {
  if (!value || !PRODUCTS.every(product => Number.isSafeInteger(value[product]) && value[product] >= 0)) throw Error('invalid billing');
  return Object.fromEntries(PRODUCTS.map(product => [product, value[product]]));
}
function restoreBilling(saved) {
  if (saved?.version !== 1 || !saved.dailyUTC || typeof saved.dailyUTC !== 'object'
      || Array.isArray(saved.dailyUTC) || !Array.isArray(saved.legacy)) throw Error('invalid billing');
  const dailyUTC = {};
  for (const [day, value] of Object.entries(saved.dailyUTC)) {
    if (!validDay(day)) throw Error('invalid billing');
    dailyUTC[day] = checkedProducts(value);
  }
  const legacy = saved.legacy.map(bucket => {
    if (!validDay(bucket.from) || !validDay(bucket.through) || bucket.from > bucket.through) throw Error('invalid billing');
    return { from: bucket.from, through: bucket.through, byProduct: checkedProducts(bucket.byProduct) };
  });
  return { version: 1, dailyUTC, legacy };
}
/** Anciennes dates Paris : chevauchement prudent, jamais de faux jour UTC exact. */
function migrateBilling(saved, history) {
  const billing = { version: 1, dailyUTC: {}, legacy: [] };
  if (!saved) return billing;
  const detailed = counts();
  const add = (from, through, byKind) => {
    if (from > through || !sum(byKind)) return;
    // Ancienne couverture inconnue : débit prudent dans les deux produits trafic.
    billing.legacy.push({ from, through, byProduct: {
      realTimeTraffic: byKind.flow + byKind.incidents,
      advancedTraffic: byKind.flow + byKind.incidents, routeImport: byKind.import,
    } });
  };
  for (const [day, byKind] of Object.entries(history.daily)) {
    for (const kind of KINDS) detailed[kind] += byKind[kind];
    add(previousDay(day), day < saved.day ? day : saved.day, byKind);
  }
  const unplaced = Object.fromEntries(KINDS.map(kind => [kind, history.totalByKind[kind] - detailed[kind]]));
  let through = saved.day;
  if (saved.history) {
    const since = Date.parse(history.since);
    const beforeSince = utcDay(since % DAY_MS === 0 ? since - 1 : since);
    const beforeRetention = utcDay(Date.parse(saved.day) - HISTORY_DAYS * DAY_MS);
    through = [through, beforeSince > beforeRetention ? beforeSince : beforeRetention].sort()[0];
  }
  const from = history.baselineMonth ? history.baselineMonth + '-01' : through.slice(0, 7) + '-01';
  add(from < through ? from : through, through, unplaced);
  return billing;
}
function anniversaryAt(year, month, day) {
  const last = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
  return utcDay(Date.UTC(year, month, Math.min(day, last)));
}

/** Réservation persistée avant appel. Échec disque : aucun appel HERE. */
export function createHereBudget({ settings = config, now = Date.now } = {}) {
  let state;
  let storageError = false;
  let lastDenied = null;
  const setting = (key, fallback) => settings[key] === undefined ? fallback : settings[key];
  const billingStart = () => setting('hereBillingStart', '2026-10-14');
  const accountMax = () => setting('hereAccountDailyMax', 250);
  const franchises = () => ({
    realTimeTraffic: setting('hereTrafficFreeMonthly', 5000),
    advancedTraffic: setting('hereAdvancedTrafficFreeMonthly', 2500),
    routeImport: setting('hereImportFreeMonthly', 2500),
  });
  const prices = () => ({
    realTimeTraffic: settings.hereTrafficEURPer1000,
    advancedTraffic: setting('hereAdvancedTrafficEURPer1000', 4.66),
    routeImport: settings.hereImportEURPer1000,
  });
  const productOf = kind => kind === 'import' ? 'routeImport' : settings.hereDeepCoverage ? 'advancedTraffic' : 'realTimeTraffic';
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
    const at = now(), day = utcDay(at), month = day.slice(0, 7);
    if (!state) {
      let saved = null, history, billing;
      try {
        if (typeof settings.hereUsageFile !== 'string' || !settings.hereUsageFile.trim()) throw Error('invalid storage');
        if (existsSync(settings.hereUsageFile)) {
          saved = JSON.parse(readFileSync(settings.hereUsageFile, 'utf8'));
          if (!validDay(saved?.day) || !/^\d{4}-\d{2}$/.test(saved.month ?? '')
              || !Number.isSafeInteger(saved.monthUsed) || saved.monthUsed < 0) throw Error('invalid storage');
        }
        history = saved?.history != null ? restoreHistory(saved.history) : legacyHistory(saved, at);
        billing = saved?.billing != null ? restoreBilling(saved.billing) : migrateBilling(saved, history);
        if (saved?.accountTransactions != null && (typeof saved.accountTransactions !== 'object'
            || Array.isArray(saved.accountTransactions)
            || Object.values(saved.accountTransactions).some(value => !Number.isSafeInteger(value) || value < 0))) throw Error('invalid accounts');
      } catch { storageError = true; }
      const sameMonth = saved?.month === month, sameDay = saved?.day === day;
      const monthByKind = counts();
      if (sameMonth) {
        for (const kind of KINDS) monthByKind[kind] = whole(saved.monthByKind?.[kind] ?? (sameDay ? saved.byKind?.[kind] : 0));
        monthByKind.import += Math.max(0, whole(saved.monthUsed) - sum(monthByKind));
      }
      state = {
        day, month, used: sameDay ? whole(saved.used) : 0,
        byUse: Object.fromEntries(USES.map(use => [use, sameDay ? whole(saved.byUse?.[use]) : 0])),
        byKind: Object.fromEntries(KINDS.map(kind => [kind, sameDay ? whole(saved.byKind?.[kind]) : 0])),
        monthByKind, monthUsed: sum(monthByKind),
        accounts: sameDay && saved?.accounts && typeof saved.accounts === 'object' ? saved.accounts : {},
        accountTransactions: sameDay ? saved?.accountTransactions ?? {} : {},
        history: history ?? legacyHistory(null, at),
        billing: billing ?? { version: 1, dailyUTC: {}, legacy: [] },
      };
      trimHistory(state.history, at);
      if (!storageError && (!saved?.history || !saved?.billing)) save();
    }
    trimHistory(state.history, at);
    if (state.day !== day) {
      if (state.month !== month) { state.month = month; state.monthByKind = counts(); state.monthUsed = 0; }
      state.day = day; state.used = 0; state.byKind = counts(); state.byUse = { eta: 0, faster: 0 };
      state.accounts = {}; state.accountTransactions = {};
    }
    return state;
  }
  function pricingValid() {
    return Number.isFinite(settings.hereMonthlyBudgetEUR) && settings.hereMonthlyBudgetEUR >= 0
      && Object.values(prices()).every(price => Number.isFinite(price) && price > 0)
      && Object.values(franchises()).every(value => Number.isSafeInteger(value) && value >= 0)
      && Number.isFinite(settings.herePriceMargin) && settings.herePriceMargin >= 1;
  }
  function windows() {
    const date = new Date(now()), year = date.getUTCFullYear(), month = date.getUTCMonth();
    const anchor = Number(billingStart().slice(8, 10));
    let cycleMonth = month;
    if (utcDay(now()) < anniversaryAt(year, month, anchor)) cycleMonth--;
    const start = value => value < billingStart() ? billingStart() : value;
    return {
      calendar: { start: start(utcDay(Date.UTC(year, month, 1))), end: utcDay(Date.UTC(year, month + 1, 1)) },
      anniversary: { start: start(anniversaryAt(year, cycleMonth, anchor)), end: anniversaryAt(year, cycleMonth + 1, anchor) },
    };
  }
  function windowCounts(window) {
    const used = productCounts();
    const add = byProduct => { for (const product of PRODUCTS) used[product] += byProduct[product]; };
    for (const [day, byProduct] of Object.entries(today().billing.dailyUTC)) {
      if (day >= window.start && day < window.end) add(byProduct);
    }
    for (const bucket of today().billing.legacy) {
      if (bucket.through >= window.start && bucket.from < window.end) add(bucket.byProduct);
    }
    return used;
  }
  function overage(byProduct) {
    const free = franchises(), rate = prices();
    return PRODUCTS.reduce((total, product) => total + Math.max(0, byProduct[product] - free[product])
      * rate[product] * settings.herePriceMargin / 1000, 0);
  }
  function reason(kind = 'flow', accountId) {
    const s = today();
    if (!settings.hereApiKey) return 'key missing';
    if (storageError) return 'storage unavailable';
    if (!KINDS.includes(kind)) return 'invalid kind';
    if (!validDay(billingStart())) return 'invalid billing start';
    if (s.day < billingStart()) return 'billing not started';
    if (!pricingValid()) return 'invalid pricing';
    if (!Number.isSafeInteger(accountMax()) || accountMax() < 0) return 'invalid account quota';
    if (settings.hereDailyCap != null && s.used >= settings.hereDailyCap) return 'daily cap';
    if (accountId != null && (s.accountTransactions['account:' + accountId] ?? 0) >= accountMax()) return 'account daily cap';
    const product = productOf(kind);
    for (const window of Object.values(windows())) {
      const next = windowCounts(window);
      next[product]++;
      // Budget commun des dépassements : impossible de le dépenser une fois par produit.
      const estimated = overage(next);
      if (settings.hereMonthlyBudgetEUR === 0 ? estimated > 0
        : estimated > settings.hereMonthlyBudgetEUR + 1e-12) return 'monthly budget';
    }
    return null;
  }
  function reserve(use, kind, accountId) {
    const denied = reason(kind, accountId);
    if (denied || !USES.includes(use) || typeof accountId !== 'string' || !accountId.trim()) {
      lastDenied = denied || (!USES.includes(use) ? 'invalid use' : 'account required');
      return false;
    }
    const s = today(), previous = structuredClone(s);
    s.used++; s.monthUsed++; s.byUse[use]++; s.byKind[kind]++; s.monthByKind[kind]++;
    s.accountTransactions['account:' + accountId] = (s.accountTransactions['account:' + accountId] ?? 0) + 1;
    const bucket = s.history.daily[parisDay(now())] ??= counts();
    s.history.totalRequests++; s.history.totalByKind[kind]++; bucket[kind]++;
    const daily = s.billing.dailyUTC[s.day] ??= productCounts();
    daily[productOf(kind)]++;
    if (save()) return true;
    state = previous;
    return false;
  }
  function accountAllows(id, at = now(), use = 'eta') {
    if (!USES.includes(use) || typeof id !== 'string' || !id.trim()) return false;
    const s = today(), key = use + ':' + id, seen = s.accounts[key];
    const gap = use === 'faster' ? settings.rerouteCheckGapS : settings.hereAccountGapS;
    if (storageError || seen && at - seen.at < gap * 1000) return false;
    const previous = structuredClone(s);
    // Écart seul : quota journalier débité par reserve, jamais par un cache ou une vérification.
    s.accounts[key] = { at };
    const keys = Object.keys(s.accounts);
    if (keys.length > 20_000) delete s.accounts[keys[0]];
    if (save()) return true;
    state = previous;
    return false;
  }
  function cost(byKind) {
    const traffic = settings.hereTrafficEURPer1000 * (settings.hereDeepCoverage ? 2 : 1);
    const routing = settings.hereImportEURPer1000, margin = settings.herePriceMargin;
    if (![traffic, routing, margin].every(Number.isFinite) || traffic <= 0 || routing <= 0 || margin < 1) return Infinity;
    return ((byKind.flow + byKind.incidents) * traffic + byKind.import * routing) * margin / 1000;
  }
  const rounded = value => Number.isFinite(value) ? Math.round(value * 1e6) / 1e6 : null;
  function usage() {
    const s = today(), week = hereParisWeek(now()), currentDay = parisDay(now());
    const weekByKind = counts();
    for (const [day, bucket] of Object.entries(s.history.daily)) {
      if (day < week.startDay || day > currentDay) continue;
      for (const kind of KINDS) weekByKind[kind] += bucket[kind];
    }
    const historyCost = byKind => storageError ? null : rounded(cost(byKind));
    const billingWindows = {};
    if (validDay(billingStart()) && pricingValid()) {
      for (const [name, window] of Object.entries(windows())) {
        const usedByProduct = windowCounts(window), free = franchises(), rate = prices();
        billingWindows[name] = { ...window, usedByProduct, estimatedOverageEUR: rounded(overage(usedByProduct)),
          products: Object.fromEntries(PRODUCTS.map(product => {
            const limit = free[product] + Math.floor(settings.hereMonthlyBudgetEUR * 1000 / (rate[product] * settings.herePriceMargin) + 1e-9);
            return [product, { used: usedByProduct[product], freeMonthly: free[product], limit,
              freeRemaining: Math.max(0, free[product] - usedByProduct[product]),
              estimatedOverageEUR: rounded(Math.max(0, usedByProduct[product] - free[product]) * rate[product] * settings.herePriceMargin / 1000) }];
          })),
        };
      }
    }
    const estimated = Object.keys(billingWindows).length && !storageError
      ? rounded(Math.max(...Object.values(billingWindows).map(window => window.estimatedOverageEUR))) : null;
    return {
      day: s.day, used: s.used, byUse: { ...s.byUse }, byKind: { ...s.byKind },
      month: s.month, monthUsed: s.monthUsed, monthByKind: { ...s.monthByKind },
      dailyCap: settings.hereDailyCap, accountDailyMax: accountMax(), fasterAccountDailyMax: accountMax(),
      deepCoverage: settings.hereDeepCoverage, monthlyBudgetEUR: settings.hereMonthlyBudgetEUR,
      estimatedMonthEUR: estimated, estimatedOverageEUR: estimated,
      billingStart: billingStart(), franchises: franchises(), billingWindows,
      legacyBillingConservative: s.billing.legacy.some(bucket => bucket.through >= billingStart()),
      estimateOnly: true, freeTierAssumed: 0, priceMargin: settings.herePriceMargin,
      trafficEURPer1000: settings.hereTrafficEURPer1000,
      advancedTrafficEURPer1000: prices().advancedTraffic, importEURPer1000: settings.hereImportEURPer1000,
      history: {
        since: s.history.since, baselineMonth: s.history.baselineMonth,
        totalRequests: s.history.totalRequests, totalEstimatedEUR: historyCost(s.history.totalByKind),
        weekStart: week.startDay, weekRequests: sum(weekByKind), weekEstimatedEUR: historyCost(weekByKind),
        weekComplete: !storageError && Date.parse(s.history.since) <= week.startAt, earlierHistoryComplete: false,
      },
      blockedByProduct: Object.fromEntries(PRODUCTS.map(product => {
        const kind = product === 'routeImport' ? 'import' : 'flow';
        return [product, product !== productOf(kind) ? 'inactive product' : reason(kind)];
      })),
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
