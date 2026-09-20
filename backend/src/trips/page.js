import { config } from './../config.js';
import { shareStore } from './shares.js';

/**
 * The page behind a shared link. It is not a map: it opens the app, where the trip is followed.
 * Someone without EONA lands on a short explanation instead of a stranger's position.
 */
export function sharePage(req, res) {
  const token = String(req.params.token || '').slice(0, 32);
  const live = Boolean(shareStore.get(token));
  const deepLink = `eona://t/${encodeURIComponent(token)}`;
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
  <h1>${live ? 'Un trajet t’est partagé' : 'Ce partage est terminé'}</h1>
  <p>${live
    ? 'Ouvre EONA pour suivre le trajet en direct : la position, l’itinéraire et l’heure d’arrivée.'
    : 'Le conducteur est arrivé, ou a arrêté le partage. Le lien ne montre plus rien.'}</p>
  ${live ? `<a class="open" href="${deepLink}">Ouvrir dans EONA</a>` : ''}
  <small>Un compte EONA est nécessaire pour suivre un trajet. Le lien s’éteint à l’arrivée.</small>
</main>
${live ? '<script>setTimeout(function () { location.href = ' + JSON.stringify(deepLink) + '; }, 400);</script>' : ''}
</body>
</html>`);
}

/** The scheme the app answers to, for the documentation. */
export const APP_SCHEME = `${config.shareBaseUrl}/t/<token>`;
