import nodemailer from 'nodemailer';
import { config } from './config.js';

let transport;

function tx() {
  if (transport !== undefined) return transport;
  const { host, port, user, pass } = config.smtp;
  transport = host && user
    ? nodemailer.createTransport({ host, port, secure: port === 465, auth: { user, pass } })
    : null;
  if (!transport) console.log('[mail] SMTP non configuré — emails désactivés');
  return transport;
}

async function send(to, subject, text) {
  const t = tx();
  if (!t) return false;
  const from = config.smtp.user ? `${config.smtp.from} <${config.smtp.user}>` : config.smtp.from;
  try {
    await t.sendMail({ from, to, subject, text });
    return true;
  } catch (e) {
    console.error('[mail] envoi échoué:', e.message);
    return false;
  }
}

export const mailer = {
  sendVerify: (to, code) =>
    send(to, 'Vérifie ton compte x_radar', `Ton code de vérification x_radar : ${code}`),
  sendReset: (to, code) =>
    send(to, 'Réinitialisation de ton mot de passe x_radar',
      `Ton code de réinitialisation x_radar : ${code}\nValable 30 minutes. Si tu n'es pas à l'origine de cette demande, ignore ce message.`),
};
