import { config } from '../config.js';
import { stateFile } from '../state-file.js';

/**
 * TomTom requests sent today, all together and by use: `eta` (/api/traffic/route), `faster`
 * (/api/route/faster), `bench` (routing/bench.js), `other`. The free plan allows about
 * config.tomtomFreeDailyQuota a day for every TomTom API together; when TomTom starts counting
 * again is not documented (to check), so the day here is the UTC day. Kept on disk
 * (config.tomtomUsageFile): a restart does not start the day again. It only counts — the bench
 * keeps to its own share with it; the other uses to theirs (tomtomAllows, D3.1).
 */
const USES = ['eta', 'faster', 'bench', 'other'];

const utcDay = () => new Date().toISOString().slice(0, 10);
const fresh = (day) => ({ day, used: 0, byUse: Object.fromEntries(USES.map((use) => [use, 0])) });
const whole = (value) => Math.max(0, Math.round(Number(value) || 0));

let state = null;
const file = stateFile(config.tomtomUsageFile, 'tomtom', () => state);

/** Today's counter: the saved one at first (the same UTC day only), a new one past midnight. */
function today() {
  const day = utcDay();
  if (!state) {
    const saved = file.read();
    state = fresh(day);
    if (saved?.day === day) {
      for (const use of USES) state.byUse[use] = whole(saved.byUse?.[use]);
      state.used = Math.max(whole(saved.used), Object.values(state.byUse).reduce((a, b) => a + b, 0));
    }
  }
  if (state.day !== day) state = fresh(day);
  return state;
}

/** One request about to be sent to TomTom, for [use] (anything unknown counts as `other`). */
export function countTomtom(use) {
  const s = today();
  s.used += 1;
  s.byUse[USES.includes(use) ? use : 'other'] += 1;
  file.touch();
}

/** The requests sent today for [use]. */
export function tomtomUsedFor(use) {
  return today().byUse[use] ?? 0;
}

/**
 * Whether one more request for [use] fits the day (D3.1): under tomtomDailyCap in all, and under
 * its share (config.tomtomShares; the bench keeps its own, benchTomtomDailyMax).
 */
export function tomtomAllows(use) {
  const s = today();
  if (s.used >= config.tomtomDailyCap) return false;
  const share = use === 'bench' ? config.benchTomtomDailyMax : config.tomtomShares[use];
  return share == null || (s.byUse[use] ?? 0) < share;
}

/** The gap the apps keep between two TomTom recalages of their ETA, as the `eta` share empties. */
export function etaMinGapS() {
  const used = (today().byUse.eta ?? 0) / Math.max(1, config.tomtomShares.eta);
  let gap = 0;
  for (const [ratio, seconds] of config.tomtomEtaSpacing) if (used >= ratio) gap = seconds;
  return gap;
}

/** For /health: { day, used, byUse, freeDailyQuota, dailyCap, shares }. */
export function tomtomUsage() {
  const s = today();
  return {
    day: s.day,
    used: s.used,
    byUse: { ...s.byUse },
    freeDailyQuota: config.tomtomFreeDailyQuota,
    dailyCap: config.tomtomDailyCap,
    shares: { ...config.tomtomShares, bench: config.benchTomtomDailyMax },
  };
}
