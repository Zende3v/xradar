import opening_hours from 'opening_hours';

/**
 * Opening hours, read at request time on the French clock (the process runs in Europe/Paris,
 * see index.js). Values are OpenStreetMap opening_hours strings — the official fuel hours
 * are turned into one too (fuel/feed.js) — parsed once and kept.
 */

// Public holidays and sunrise are those of France; the exact spot barely moves sunrise.
const FRANCE = { lat: 46.6, lon: 2.4, address: { country_code: 'fr', state: '' } };
const MAX_PARSED = 20_000;
const WEEK_MS = 8 * 24 * 3600 * 1000;

const parsed = new Map(); // value -> opening_hours, or null when unreadable

function parse(value) {
  if (parsed.has(value)) return parsed.get(value);
  let oh = null;
  try {
    oh = new opening_hours(value, FRANCE, { tag_key: 'opening_hours' });
  } catch {
    oh = null;
  }
  if (parsed.size >= MAX_PARSED) parsed.clear();
  parsed.set(value, oh);
  return oh;
}

const hhmm = (date) =>
  `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;

/**
 * `{state, alwaysOpen, today, nextAt}` for an opening_hours value, or null when there is none
 * or it cannot be read:
 *   state       'open' | 'closed' | 'unknown' (a comment such as "sur rendez-vous")
 *   alwaysOpen  open now with no change ahead (24/7)
 *   today       today's opening slots [{from: '07:00', to: '12:00'}], '24:00' for midnight
 *   nextAt      when the state changes next (epoch ms), within a week; null otherwise
 */
export function evaluate(value, now = new Date()) {
  if (typeof value !== 'string' || !value.trim()) return null;
  const oh = parse(value.trim());
  if (!oh) return null;
  try {
    const unknown = oh.getUnknown(now);
    const open = oh.getState(now);
    const next = oh.getNextChange(now, new Date(now.getTime() + WEEK_MS));
    const dayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const dayEnd = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
    const today = oh
      .getOpenIntervals(dayStart, dayEnd)
      .filter(([, , isUnknown]) => !isUnknown)
      .map(([from, to]) => ({ from: hhmm(from), to: to.getTime() >= dayEnd.getTime() ? '24:00' : hhmm(to) }));
    return {
      state: unknown ? 'unknown' : open ? 'open' : 'closed',
      alwaysOpen: open && !unknown && next === undefined,
      today,
      nextAt: next ? next.getTime() : null,
    };
  } catch {
    return null;
  }
}
