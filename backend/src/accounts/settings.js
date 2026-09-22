import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';

/**
 * The few settings an admin changes from the app, and the trace of who changed what. Small, rare
 * and worth keeping: a JSON file beside the accounts, written as soon as something moves.
 *
 * Today: how long a referral code stays usable. A new duration applies to the codes minted after
 * it — the ones already handed out keep the date they were given, unless someone extends them on
 * purpose.
 */
class SettingsStore {
  constructor() {
    this.values = { referralValidityMonths: config.referralValidityMonths };
    /** Who changed what, newest first; kept to settingsHistoryMax entries. */
    this.history = [];
    this.saveTimer = null;
  }

  async load() {
    try {
      const raw = JSON.parse(await readFile(config.settingsFile, 'utf8'));
      const months = Number(raw?.referralValidityMonths);
      if (Number.isFinite(months)) this.values.referralValidityMonths = this.clampMonths(months);
      if (Array.isArray(raw?.history)) this.history = raw.history.slice(0, config.settingsHistoryMax);
    } catch (e) {
      if (e.code !== 'ENOENT') console.error('[settings] load failed:', e.message);
    }
  }

  /** How long a code minted now stays usable, in months. */
  get referralValidityMonths() {
    return this.values.referralValidityMonths;
  }

  /** Between a month and a year: below or above, the request is brought back into range. */
  clampMonths(months) {
    const rounded = Math.round(Number(months) || 0);
    return Math.min(Math.max(rounded, config.referralValidityMinMonths), config.referralValidityMaxMonths);
  }

  /**
   * Sets the duration for the codes minted from now on. Returns what changed, and writes it to
   * the log with its author — the codes already out there are not touched.
   */
  setReferralValidity(months, by) {
    const before = this.values.referralValidityMonths;
    const after = this.clampMonths(months);
    this.values.referralValidityMonths = after;
    this.note({ action: 'referral-validity', before, after, by });
    return { before, after };
  }

  /** One line in the log: what, who, when. */
  note(entry) {
    this.history.unshift({ ...entry, at: new Date().toISOString() });
    this.history = this.history.slice(0, config.settingsHistoryMax);
    this.scheduleSave();
  }

  scheduleSave() {
    if (this.saveTimer) return;
    this.saveTimer = setTimeout(() => {
      this.saveTimer = null;
      this.save().catch((e) => console.error('[settings] save failed:', e.message));
    }, 500);
    if (this.saveTimer.unref) this.saveTimer.unref();
  }

  async save() {
    await mkdir(dirname(config.settingsFile), { recursive: true });
    await writeFile(config.settingsFile, JSON.stringify({ ...this.values, history: this.history }, null, 2));
  }

  /** What the admin screen shows: the values, their range, and the log. */
  get meta() {
    return {
      referralValidityMonths: this.values.referralValidityMonths,
      referralValidityMinMonths: config.referralValidityMinMonths,
      referralValidityMaxMonths: config.referralValidityMaxMonths,
      history: this.history,
    };
  }
}

export const settingsStore = new SettingsStore();
