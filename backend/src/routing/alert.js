import nodemailer from 'nodemailer';
import { config } from '../config.js';

/**
 * The routing alerts (D6.4): a mail to config.alertEmails when Valhalla stays unusable past
 * config.valhallaAlertAfterMs, and one when it serves again. Through the SMTP of the account
 * mails (config.smtp, as mailer.js); without recipients or without SMTP, the alert goes to the
 * logs only. Never quotes a route, a coordinate nor an account.
 */
export function createRoutingAlert({ to = config.alertEmails, send = smtpSend } = {}) {
  const recipients = Array.isArray(to) ? to.filter(Boolean) : [];
  const deliver = (subject, text) => {
    console.warn(`[routing-alert] ${subject} — ${text}`);
    if (!recipients.length) return Promise.resolve(false);
    return Promise.resolve()
      .then(() => send(recipients, subject, text))
      .catch((e) => {
        console.error('[routing-alert] mail not sent —', String(e?.message || e));
        return false;
      });
  };
  return {
    /** Valhalla unusable since [since] (ISO) for [cause]; [mode]: routingEngine then. */
    outage: ({ since, cause, mode }) => deliver(
      'EONA : Valhalla indisponible',
      `Valhalla ne répond plus normalement depuis ${since} (cause : ${cause}). Réglage routingEngine : ${mode}. `
        + 'Les itinéraires qu\'il devait servir passent par ORS ; le mode ombre est suspendu. Détail : /health, routing.valhalla.',
    ),
    /** Valhalla serves again, after being unusable from [since] to [until] (ISO). */
    recovered: ({ since, until }) => deliver(
      'EONA : Valhalla de nouveau disponible',
      `Valhalla répond de nouveau depuis ${until} (indisponible depuis ${since}).`,
    ),
  };
}

let transport;

/** config.smtp's transport, made once; false when SMTP is not configured. */
async function smtpSend(recipients, subject, text) {
  if (transport === undefined) {
    const { host, port, user, pass } = config.smtp;
    transport = host && user ? nodemailer.createTransport({ host, port, secure: port === 465, auth: { user, pass } }) : null;
  }
  if (!transport) return false;
  const from = config.smtp.user ? `${config.smtp.from} <${config.smtp.user}>` : config.smtp.from;
  await transport.sendMail({ from, to: recipients.join(', '), subject, text });
  return true;
}
