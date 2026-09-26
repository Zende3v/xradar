#!/usr/bin/env node
// Notification exploitation. Aucun envoi pendant installation ou vérification --check.
import nodemailer from 'nodemailer';
import { config } from '../src/config.js';

const to = config.alertEmails;
const configured = Boolean(config.smtp.host && config.smtp.user && to?.length);
if (process.argv.includes('--check')) {
  console.log(JSON.stringify({ configured }));
} else if (process.argv[2] !== 'valhalla-build-failed') {
  console.error('Usage : eona-notify.js --check | valhalla-build-failed');
  process.exitCode = 2;
} else if (!configured) {
  console.error('[notify] SMTP ou destinataires absents');
  process.exitCode = 1;
} else {
  const { host, port, user, pass, from } = config.smtp;
  const transport = nodemailer.createTransport({
    host, port, secure: port === 465, auth: { user, pass },
    connectionTimeout: 10000, greetingTimeout: 10000, socketTimeout: 20000,
  });
  try {
    await transport.sendMail({
      from: `${from} <${user}>`, to: to.join(', '), subject: 'EONA : construction Valhalla échouée',
      text: 'Construction ou validation Valhalla échouée. Consulter journal et /var/lib/valhalla/graphs. Vérifier carte courante et /health.',
    });
    console.log('[notify] alerte envoyée');
  } catch {
    console.error('[notify] envoi échoué');
    process.exitCode = 1;
  } finally {
    transport.close();
  }
}
