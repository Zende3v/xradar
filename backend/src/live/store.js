import { config } from '../config.js';

/**
 * Who uses the app right now, in memory only (never persisted): per account, when the app last
 * said it was open, and whether a trip was running. No position is kept, nothing is shown to
 * other drivers: only the counts, in /health. An account not heard from for [liveTtlMs] is gone.
 */
class LiveStore {
  constructor() {
    this.byAccount = new Map(); // accountId -> { at, inTrip }
  }

  touch(accountId, inTrip = false) {
    this.byAccount.set(accountId, { at: Date.now(), inTrip: Boolean(inTrip) });
  }

  remove(accountId) {
    this.byAccount.delete(accountId);
  }

  prune() {
    const cutoff = Date.now() - config.liveTtlMs;
    for (const [id, p] of this.byAccount) if (p.at < cutoff) this.byAccount.delete(id);
  }

  /** Accounts with the app open now, and how many of them are on a trip. */
  get meta() {
    this.prune();
    let inTrip = 0;
    for (const p of this.byAccount.values()) if (p.inTrip) inTrip += 1;
    return { online: this.byAccount.size, inTrip };
  }
}

export const liveStore = new LiveStore();
