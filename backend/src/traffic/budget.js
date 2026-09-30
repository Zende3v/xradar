import { config } from '../config.js';
import { stateFile } from '../state-file.js';

/**
 * HERE requests sent, per UTC day and per UTC month, by use (`eta`: /api/traffic/route, `faster`:
 * /api/route/faster) and by kind (flow, incidents: Traffic API; import: Route Import, the ETA's
 * time): what the Base Plan bills past its free tier.
 * Kept on disk (config.hereUsageFile): a restart does not start the day again. No cap unless
 * HERE_DAILY_CAP (Arthur, 29/09); each account keeps to its own gap and daily count
 * (hereAccountAllows), so no app loop can drain the account.
 */
const USES = ['eta', 'faster'];
const KINDS = ['flow', 'incidents', 'import'];

const utcDay = () => new Date().toISOString().slice(0, 10);
const whole = (value) => Math.max(0, Math.round(Number(value) || 0));
const fresh = (day, month = day.slice(0, 7), monthUsed = 0) => ({
  day,
  used: 0,
  byUse: Object.fromEntries(USES.map((use) => [use, 0])),
  byKind: Object.fromEntries(KINDS.map((kind) => [kind, 0])),
  month,
  monthUsed,
});

let state = null;
const file = stateFile(config.hereUsageFile, 'here', () => state);

/** Today's counter: the saved one at first (the same UTC day, the same month), a new one past midnight. */
function today() {
  const day = utcDay();
  if (!state) {
    const saved = file.read();
    state = fresh(day);
    if (saved?.month === day.slice(0, 7)) state.monthUsed = whole(saved.monthUsed);
    if (saved?.day === day) {
      for (const use of USES) state.byUse[use] = whole(saved.byUse?.[use]);
      for (const kind of KINDS) state.byKind[kind] = whole(saved.byKind?.[kind]);
      state.used = whole(saved.used);
    }
  }
  if (state.day !== day) state = fresh(day, day.slice(0, 7), state.month === day.slice(0, 7) ? state.monthUsed : 0);
  return state;
}

/** One request about to be sent to HERE, for [use] and of [kind] (flow | incidents | import). */
export function countHere(use, kind) {
  const s = today();
  s.used += 1;
  s.monthUsed += 1;
  if (USES.includes(use)) s.byUse[use] += 1;
  if (kind in s.byKind) s.byKind[kind] += 1;
  file.touch();
}

/** Whether HERE may be asked at all now: its key, and HERE_DAILY_CAP when set. */
export function hereAllows() {
  if (!config.hereApiKey) return false;
  return config.hereDailyCap == null || today().used < config.hereDailyCap;
}

/** Each account's last HERE refresh and its refreshes today. */
const accounts = new Map();

/**
 * Whether [accountId] may have HERE refresh its traffic at [now]: once a hereAccountGapS, and
 * hereAccountDailyMax times a UTC day. Taken at once when it may.
 */
export function hereAccountAllows(accountId, now = Date.now()) {
  const day = new Date(now).toISOString().slice(0, 10);
  const seen = accounts.get(accountId);
  const count = seen?.day === day ? seen.count : 0;
  if (seen && now - seen.at < config.hereAccountGapS * 1000) return false;
  if (count >= config.hereAccountDailyMax) return false;
  accounts.set(accountId, { at: now, day, count: count + 1 });
  if (accounts.size > 10_000) accounts.delete(accounts.keys().next().value);
  return true;
}

/** For /health: { day, used, byUse, byKind, month, monthUsed, dailyCap, accountDailyMax, deepCoverage }. */
export function hereUsage() {
  const s = today();
  return {
    day: s.day,
    used: s.used,
    byUse: { ...s.byUse },
    byKind: { ...s.byKind },
    month: s.month,
    monthUsed: s.monthUsed,
    dailyCap: config.hereDailyCap,
    accountDailyMax: config.hereAccountDailyMax,
    deepCoverage: config.hereDeepCoverage,
  };
}
