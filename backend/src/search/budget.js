import { readFileSync } from 'node:fs';
import { mkdir, open, rename } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';

/**
 * The TomTom Places requests the search may still send (search/tomtom.js), per UTC day and per
 * UTC month, kept on disk (config.searchTomtomUsageFile) apart from the traffic's counter
 * (traffic/budget.js): the search never spends the traffic's requests, nor the traffic the
 * search's.
 *
 * A request is counted before it leaves, and only leaves once that count is on disk: a timeout,
 * a refusal or a crash mid-request still counts, and a restart hands nothing back. Whatever goes
 * wrong with the file — unreadable, unwritable — no request leaves (fail closed): Photon and the
 * Base Adresse Nationale answer alone. Counting is synchronous, so requests arriving together
 * never slip past the ceiling between them; writes go one at a time, later counts joining the
 * next one. One process per file: two servers sharing it would each count on their own.
 */
export function createSearchBudget({ path, dailyMax, monthlyMax, now = () => new Date(), store = diskStore(path) }) {
  let state = null; // { day, month, today, thisMonth }
  let unreadable = false;
  let unsaved = false;
  let spentFor = null; // the day (or month) already said to be spent, said once
  let writing = Promise.resolve();
  let next = null; // the write waiting for its turn: counts taken meanwhile join it

  /** The counters now: read from disk the first time; a new UTC day, a new UTC month start at 0. */
  function current() {
    if (!state) {
      try {
        state = parse(store.read()) ?? { day: '', month: '', today: 0, thisMonth: 0 };
        unreadable = false;
      } catch (e) {
        if (!unreadable) console.error(`[search] TomTom budget unreadable, TomTom off until it is fixed or removed — ${e.message}`);
        unreadable = true;
        return null;
      }
    }
    const iso = now().toISOString();
    // Forward only: a clock set back does not hand the day's requests back.
    if (iso.slice(0, 7) > state.month) Object.assign(state, { month: iso.slice(0, 7), thisMonth: 0 });
    if (iso.slice(0, 10) > state.day) Object.assign(state, { day: iso.slice(0, 10), today: 0 });
    return state;
  }

  /** Whether the day's or the month's requests are all spent — said once in the logs for each. */
  function spent(s) {
    const period = s.today >= dailyMax ? s.day : s.thisMonth >= monthlyMax ? s.month : null;
    if (period && spentFor !== period) {
      console.warn(`[search] TomTom budget spent for ${period} (${dailyMax} a day, ${monthlyMax} a month): Photon and BAN only`);
      spentFor = period;
    }
    return Boolean(period);
  }

  /** The counters on disk, one write at a time; resolves once this count is written. */
  function save() {
    if (!next) {
      next = writing.then(() => {
        next = null;
        return store.write(JSON.stringify(state));
      });
      writing = next.then(
        () => {
          if (unsaved) console.warn('[search] TomTom budget saved again');
          unsaved = false;
        },
        (e) => {
          if (!unsaved) console.error(`[search] TomTom budget not saved, no TomTom request until it is — ${e.message}`);
          unsaved = true;
        },
      );
    }
    return next;
  }

  return {
    /** Whether a request could leave now (budget readable, not spent). Counts nothing. */
    left() {
      const s = current();
      return Boolean(s) && !spent(s);
    },

    /** One request about to leave: true once it is counted on disk, false if it must not leave. */
    async take() {
      const s = current();
      if (!s || spent(s)) return false;
      s.today += 1;
      s.thisMonth += 1;
      try {
        await save();
        return true;
      } catch {
        // Still counted, though not sent: the budget errs on the side of too few requests.
        return false;
      }
    },

    /** For /health: { day, today, dailyMax, month, thisMonth, monthlyMax }, null if unreadable. */
    usage() {
      const s = current();
      return s && { day: s.day, today: s.today, dailyMax, month: s.month, thisMonth: s.thisMonth, monthlyMax };
    },
  };
}

/** The saved counters, null without a file; throws on anything else than what save() writes. */
function parse(text) {
  if (text == null) return null;
  const saved = JSON.parse(text);
  const count = (value) => Number.isInteger(value) && value >= 0;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(saved?.day) || !/^\d{4}-\d{2}$/.test(saved?.month)
    || !count(saved.today) || !count(saved.thisMonth)) {
    throw new Error('unexpected content');
  }
  return { day: saved.day, month: saved.month, today: saved.today, thisMonth: saved.thisMonth };
}

/** The file itself: read at once, written to a temporary file flushed to disk, then renamed. */
function diskStore(path) {
  return {
    read() {
      try {
        return readFileSync(path, 'utf8');
      } catch (e) {
        if (e.code === 'ENOENT') return null;
        throw e;
      }
    },
    async write(text) {
      const tmp = `${path}.tmp`;
      await mkdir(dirname(path), { recursive: true });
      const file = await open(tmp, 'w');
      try {
        await file.writeFile(text, 'utf8');
        await file.sync();
      } finally {
        await file.close();
      }
      await rename(tmp, path);
    },
  };
}

/** The search's budget. Nothing is read nor written until TomTom is first asked. */
export const searchBudget = createSearchBudget({
  path: config.searchTomtomUsageFile,
  dailyMax: config.searchTomtomDailyMax,
  monthlyMax: config.searchTomtomMonthlyMax,
});
