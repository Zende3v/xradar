import { config } from './../config.js';
import { shareStore } from './shares.js';
import { groupStore } from './groups.js';

/**
 * The pages behind a shared link. Neither is a map: they open the app, where the trip is followed.
 * Someone without EONA lands on a short explanation instead of a stranger's position.
 */

/** A trip shared by one driver: /t/<token>. */
export function sharePage(req, res) {
  const token = String(req.params.token || '').slice(0, 32);
  send(res, {
    live: Boolean(shareStore.get(token)),
    deepLink: `eona://t/${encodeURIComponent(token)}`,
    liveTitle: 'Un trajet t’est partagé',
    liveText: 'Ouvre EONA pour suivre le trajet en direct : la position, l’itinéraire et l’heure d’arrivée.',
    overText: 'Le conducteur est arrivé, ou a arrêté le partage. Le lien ne montre plus rien.',
    foot: 'Un compte EONA est nécessaire pour suivre un trajet. Le lien s’éteint à l’arrivée.',
  });
}

/** A trip driven by a group: /g/<token>. */
export function groupPage(req, res) {
  const token = String(req.params.token || '').slice(0, 32);
  send(res, {
    live: Boolean(groupStore.byWatchToken(token)),
    deepLink: `eona://g/${encodeURIComponent(token)}`,
    liveTitle: 'Un trajet en groupe t’est partagé',
    liveText:
      'Ouvre EONA pour suivre le groupe en direct : la carte, l’avancement et la vitesse des participants qui ont accepté de les partager.',
    overText: 'Le trajet est terminé, ou le lien a été révoqué. Il ne montre plus rien.',
    foot: 'Un compte EONA est nécessaire. Un participant qui ne partage pas sa position n’apparaît pas.',
  });
}

function send(res, { live, deepLink, liveTitle, liveText, overText, foot }) {
  res.set('Cache-Control', 'no-store');
  res.status(live ? 200 : 410).send(`<!doctype html>
<html lang="fr">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>EONA — trajet partagé</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #06070A;
         color: #F5F7FA; font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif; }
  main { max-width: 22rem; padding: 2rem 1.5rem; text-align: center; }
  h1 { font-size: 1.35rem; margin: 0 0 .75rem; }
  p { color: #A9B0BC; margin: 0 0 1.5rem; }
  a.open { display: block; padding: .9rem 1rem; border-radius: 14px; background: #2CD5E0;
           color: #062024; font-weight: 600; text-decoration: none; }
  small { display: block; margin-top: 1.25rem; color: #6C7280; }
</style>
</head>
<body>
<main>
  <h1>${live ? liveTitle : 'Ce partage est terminé'}</h1>
  <p>${live ? liveText : overText}</p>
  ${live ? `<a class="open" href="${deepLink}">Ouvrir dans EONA</a>` : ''}
  <small>${foot}</small>
</main>
${live ? '<script>setTimeout(function () { location.href = ' + JSON.stringify(deepLink) + '; }, 400);</script>' : ''}
</body>
</html>`);
}

/** The schemes the app answers to, for the documentation. */
export const APP_SCHEME = `${config.shareBaseUrl}/t/<token>`;
export const GROUP_SCHEME = `${config.shareBaseUrl}/g/<token>`;
