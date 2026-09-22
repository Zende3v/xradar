import { createPublicKey, createVerify } from 'node:crypto';
import { config } from '../config.js';

/**
 * "Se connecter avec Google", checked here and never taken on trust from the app. The phone hands
 * over an identity token; this file asks Google for its public keys and verifies, itself, that the
 * token is signed by Google, meant for our app, and still valid. Only then does the account move.
 *
 * Nothing is believed because the client said so: not the email, not the name, not the identifier.
 */

/** Google's signing keys, fetched once and kept until they go stale. */
let keys = { at: 0, byId: new Map() };

/** Google says which issuers it signs as. Anything else is refused. */
const ISSUERS = new Set(['accounts.google.com', 'https://accounts.google.com']);

async function signingKey(kid) {
  if (Date.now() - keys.at > config.googleKeysTtlMs || !keys.byId.has(kid)) {
    const answer = await fetch(config.googleKeysUrl, { signal: AbortSignal.timeout(6000) });
    if (!answer.ok) throw new Error(`google keys ${answer.status}`);
    const json = await answer.json();
    keys = { at: Date.now(), byId: new Map((json.keys ?? []).map((key) => [key.kid, key])) };
  }
  const jwk = keys.byId.get(kid);
  if (!jwk) throw new Error('unknown key');
  return createPublicKey({ key: jwk, format: 'jwk' });
}

function decode(part) {
  return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
}

/**
 * The identity behind [idToken], or an error. Checks the signature, the issuer, the audience
 * (our own client ids) and the dates; refuses everything else.
 */
export async function verifyGoogleToken(idToken) {
  if (!config.googleClientIds.length) return { error: 'google sign-in not configured' };
  const parts = String(idToken ?? '').split('.');
  if (parts.length !== 3) return { error: 'malformed token' };

  let header;
  let payload;
  try {
    header = decode(parts[0]);
    payload = decode(parts[1]);
  } catch {
    return { error: 'malformed token' };
  }
  if (header.alg !== 'RS256') return { error: 'unexpected algorithm' };

  try {
    const key = await signingKey(header.kid);
    const verifier = createVerify('RSA-SHA256');
    verifier.update(`${parts[0]}.${parts[1]}`);
    verifier.end();
    if (!verifier.verify(key, Buffer.from(parts[2], 'base64url'))) return { error: 'bad signature' };
  } catch (e) {
    return { error: `signature not checked (${e.message})` };
  }

  const now = Math.floor(Date.now() / 1000);
  if (!ISSUERS.has(payload.iss)) return { error: 'wrong issuer' };
  if (!config.googleClientIds.includes(payload.aud)) return { error: 'token meant for another app' };
  if (Number(payload.exp) <= now) return { error: 'token expired' };
  if (Number(payload.iat) > now + 300) return { error: 'token from the future' };
  if (!payload.sub) return { error: 'no subject' };

  return {
    identity: {
      provider: 'google',
      subject: String(payload.sub),
      // Google marks whether it checked the address itself; an unverified one never links.
      email: payload.email ? String(payload.email).toLowerCase() : null,
      emailVerified: payload.email_verified === true || payload.email_verified === 'true',
      name: payload.name ? String(payload.name).slice(0, 60) : null,
    },
  };
}
