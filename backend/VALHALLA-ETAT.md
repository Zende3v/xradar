# Valhalla : état du chantier

Mis à jour : **25/09/2026, 19 h 40**. À mettre à jour à chaque étape.

Objectif : remplacer ORS par Valhalla auto-hébergé, trafic temps réel, ETA ultra précise,
itinéraires plus malins, sans casser les apps.

## Documents

| Fichier | Rôle |
|---|---|
| `VALHALLA-ETAT.md` | ce fichier : où on en est |
| `PLAN-VALHALLA.md` | plan en phases (validé le 25/09) |
| `VALHALLA-DECISIONS.md` | journal des décisions : D1–D8 (atelier), P1.1–P1.7 (choix phase 1) |
| `CHECKLIST-TRAJETS.md` | trajets tests de l'équipe |
| `VALHALLA.md` | conditions d'origine, remplacé par le plan et le journal |

L'audit du 24/09 (A à O) n'existe que dans la conversation, pas dans le repo. Ses constats utiles
sont repris dans le journal.

## Phases

| Phase | Contenu | État |
|---|---|---|
| 1 | Mesurer l'existant | **en service depuis le 25/09**, collecte en cours |
| 2 | Valhalla installé, en mode ombre | à faire |
| 3 | Valhalla pour les admins, puis pour tous | à faire |
| 4 | ETA dynamique, data.gouv, cap, ferries | à faire |
| 5 | Politique de confidentialité et CGU | à faire |
| 6 | Trafic dans Valhalla : fermetures et travaux | à faire |
| 7-8 | Vitesses en direct, historique | à détailler après mesure |
| — | Sytadin | en attente d'un accès DiRIF |

## Phase 1 : ce qui tourne

- **Backend** déployé le 25/09 à 19:34 (sauvegarde `/opt/eona-backend-src-backup-20260925-1934.tgz`).
  Champs `engine`/`mapVersion`, mesures des trajets, contexte des bugs, timeout ORS, compteurs
  ORS et TomTom dans `/health`, journal `routing.route_log`.
- **Banc** : cron `/etc/cron.d/eona-bench` (08:00, 12:30, 18:00, 23:00), log
  `/var/log/eona-bench.log`, résultats `routing.bench_run`. 1er passage manuel : 6/6 OK,
  12 requêtes TomTom.
- **Android** 1.0.1 (4) et **iOS** 1.0.0 (2) : buildent et se lancent (Arthur, 25/09).
- **Commits** : iOS `55c56c9` poussé. x_radar `b7759b0` **pas poussé**, comme les commits
  `4073930`, `3d883fe`, `976e6db` et `ce39fd8`.

## Collecte en cours

- Durée : 2 à 3 semaines depuis le 25/09 (D1.3), donc un premier bilan entre le 09/10 et le 16/10.
- Rapport ETA sur le VPS : `cd /opt/eona-backend && node bin/eona-eta-report.js data/accounts.json`.
- Banc et journal de routage : requêtes SQL dans `README.md` §7 bis.
- Après la collecte : chiffrer la cible (D1.3) et la largeur d'intervalle qui rend une tranche
  concluante (D1.6).

Premiers signaux, 6 trajets en ville le vendredi à 19 h 30 : rien à conclure. La route ORS est
souvent plus lente que la meilleure route TomTom, de 4 à 11 min sur 4 trajets. ORS sous-estime
beaucoup la durée (14 min annoncées contre 29 min pour TomTom). Toulouse (Capitole → Rangueil) fait
1,9 km sur des petites routes, contre 0,65 km pour TomTom.

## Points ouverts

- Heure de remise à zéro du quota TomTom : à vérifier (le compteur suppose minuit UTC).
- Seuil « nettement plus de km » (itinéraires bizarres) : thème 4, après quelques jours de banc.
- Politique de confidentialité : inexacte jusqu'à la phase 5, risque accepté (D8.2).
- Dernière revue Codex : les derniers correctifs (F1 à F4, `retargeted`) n'ont pas été relus
  par Codex (quota épuisé) ; ils ont été relus en local, sans défaut trouvé.
- Signalisation : première reconstruction automatique dimanche 27/09 à 03:30, à vérifier dans
  `/var/lib/eona-signs/rebuild.log`.
- Sauvegardes uniquement sur le VPS, aucune copie ailleurs.

## Prochaine étape

Phase 2 (Valhalla en mode ombre), qui peut commencer pendant la collecte. Chaque installation sur
le VPS demande l'accord d'Arthur : Podman, fichier d'échange, service `eona-valhalla`, premier
build de la France.
