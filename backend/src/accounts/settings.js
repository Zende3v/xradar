import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { dirname } from 'node:path';
import { config } from '../config.js';

/**
 * The few settings an admin changes from the app, and the trace of who changed what. Small, rare
 * and worth keeping: a JSON file beside the accounts, written as soon as something moves.
 *
 * Today: how long a referral code stays usable. A new duration applies to the codes minted after
 * it — the ones already handed out keep the date they were given, unless someone extends them on
 * purpose. And which engine serves the routes (routingEngine, D7.2): `ors` (Valhalla in shadow,
 * the default), `admins` (admin accounts on Valhalla, ORS in shadow) or `all`; read at every
 * route, so a change applies at once, without a restart.
 */
export const ROUTING_ENGINES = ['ors', 'admins', 'all'];

export class SettingsStore {
  constructor() {
    this.values = { referralValidityMonths: config.referralValidityMonths, routingEngine: 'ors', trafficDatagouv: true };
    /** Who changed what, newest first; kept to settingsHistoryMax entries. */
    this.history = [];
    this.saveTimer = null;
  }

  async load() {
    try {
      const raw = JSON.parse(await readFile(config.settingsFile, 'utf8'));
      const months = Number(raw?.referralValidityMonths);
      if (Number.isFinite(months)) this.values.referralValidityMonths = this.clampMonths(months);
      // Anything else than a known value keeps ORS: a damaged file never moves the routes.
      if (ROUTING_ENGINES.includes(raw?.routingEngine)) this.values.routingEngine = raw.routingEngine;
      if (typeof raw?.trafficDatagouv === 'boolean') this.values.trafficDatagouv = raw.trafficDatagouv;
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

  /** Which engine serves the routes: ors, admins or all. */
  get routingEngine() {
    return this.values.routingEngine;
  }

  /**
   * Sets which engine serves the routes from the next request on, written to the log with its
   * author. Null for an unknown value: nothing changes.
   */
  setRoutingEngine(engine, by) {
    if (!ROUTING_ENGINES.includes(engine)) return null;
    const before = this.values.routingEngine;
    this.values.routingEngine = engine;
    this.note({ action: 'routing-engine', before, after: engine, by });
    return { before, after: engine };
  }

  /**
   * Whether the ETA shown uses data.gouv's traffic (D2.6): the apps compute both ETAs on every
   * trip and show the one this says. On by default; changed live from the admin.
   */
  get trafficDatagouv() {
    return this.values.trafficDatagouv;
  }

  setTrafficDatagouv(enabled, by) {
    const before = this.values.trafficDatagouv;
    this.values.trafficDatagouv = Boolean(enabled);
    this.note({ action: 'traffic-datagouv', before, after: this.values.trafficDatagouv, by });
    return { before, after: this.values.trafficDatagouv };
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
      routingEngine: this.values.routingEngine,
      routingEngines: ROUTING_ENGINES,
      trafficDatagouv: this.values.trafficDatagouv,
      history: this.history,
    };
  }
}

export const settingsStore = new SettingsStore();
