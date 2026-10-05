import { closeSync, fsyncSync, mkdirSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';
import { randomUUID } from 'node:crypto';

export const normalizedEmail = (value) => String(value ?? '').trim().toLowerCase();
export const normalizedDevice = (value) => {
  const id = typeof value === 'string' ? value.trim() : '';
  return id && id.length <= 128 ? id : null;
};
const unique = (values) => [...new Set(values.filter(Boolean))];

/** Registre indépendant : supprimer compte ne libère ni email ni appareil banni. */
export class BanRegistry {
  constructor(file) {
    this.file = file;
    this.records = new Map();
    this.dirty = false;
  }

  load() {
    let raw;
    try { raw = readFileSync(this.file, 'utf8'); } catch (error) {
      if (error.code === 'ENOENT') return;
      throw error;
    }
    const document = JSON.parse(raw);
    if (document?.version !== 1 || !Array.isArray(document.bans)) throw new Error('invalid ban registry');
    for (const entry of document.bans) {
      if (!entry || typeof entry.accountId !== 'string') throw new Error('invalid ban entry');
      this.records.set(entry.accountId, {
        accountId: entry.accountId,
        emails: unique((entry.emails ?? []).map(normalizedEmail)),
        deviceIds: unique((entry.deviceIds ?? []).map(normalizedDevice)),
        providerIds: unique((entry.providerIds ?? []).filter((value) => typeof value === 'string')),
        bannedAt: entry.bannedAt ?? null,
        reason: typeof entry.reason === 'string' ? entry.reason.slice(0, 500) : null,
      });
    }
  }

  matches({ id = null, email = null, emailLower = null, deviceId = null, knownDeviceIds = [], providers = [], providerKey = null } = {}) {
    const emails = unique([normalizedEmail(email), normalizedEmail(emailLower), ...providers.map((link) => normalizedEmail(link.email))]);
    const devices = unique([normalizedDevice(deviceId), ...knownDeviceIds.map(normalizedDevice)]);
    const identities = unique([providerKey, ...providers.map((link) => `${link.provider}:${link.subject}`)]);
    for (const record of this.records.values()) {
      if (record.accountId === id
        || emails.some((value) => record.emails.includes(value))
        || devices.some((value) => record.deviceIds.includes(value))
        || identities.some((value) => record.providerIds.includes(value))) return record;
    }
    return null;
  }

  ban(account, { reason = null, persist = true } = {}) {
    const previous = this.records.get(account.id);
    const record = {
      accountId: account.id,
      emails: unique([...(previous?.emails ?? []), normalizedEmail(account.email), normalizedEmail(account.emailLower), ...(account.providers ?? []).map((link) => normalizedEmail(link.email))]),
      deviceIds: unique([...(previous?.deviceIds ?? []), normalizedDevice(account.deviceId), ...(account.knownDeviceIds ?? []).map(normalizedDevice)]),
      providerIds: unique([...(previous?.providerIds ?? []), ...(account.providers ?? []).map((link) => `${link.provider}:${link.subject}`)]),
      bannedAt: previous?.bannedAt ?? new Date().toISOString(),
      reason: typeof reason === 'string' ? reason.trim().slice(0, 500) : previous?.reason ?? null,
    };
    if (JSON.stringify(previous) === JSON.stringify(record)) return;
    this.records.set(account.id, record);
    this.dirty = true;
    if (persist) this.save();
  }

  unban(accountId) {
    const previous = this.records.get(accountId);
    if (!previous) return false;
    this.records.delete(accountId);
    this.dirty = true;
    try { this.save(); } catch (error) {
      this.records.set(accountId, previous);
      throw error;
    }
    return true;
  }

  save() {
    if (!this.dirty) return;
    mkdirSync(dirname(this.file), { recursive: true });
    const temporary = `${this.file}.${randomUUID()}.tmp`;
    let descriptor = null;
    try {
      descriptor = openSync(temporary, 'wx', 0o600);
      writeFileSync(descriptor, JSON.stringify({ version: 1, bans: [...this.records.values()] }, null, 2), 'utf8');
      fsyncSync(descriptor);
      closeSync(descriptor);
      descriptor = null;
      renameSync(temporary, this.file);
    } catch (error) {
      if (descriptor !== null) try { closeSync(descriptor); } catch {}
      try { unlinkSync(temporary); } catch {}
      throw error;
    }
    this.dirty = false;
  }
}
