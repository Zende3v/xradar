# Valhalla : état du chantier

Mis à jour : **27/09/2026 20:55** (Claude : ORS `api.heigit.org`, phase 2 installée, ombre active). Exploitation vérifiée le 26/09 à **15:10**, collecte le 27/09 à **02:43, Europe/Paris**.

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
| 2 | Valhalla installé, en mode ombre | **en ombre depuis 27/09 20:54** : backend phase 2, Podman, swap 4 Go, carte France (36 min, 50/50), `VALHALLA_ENABLED=1`, `routingEngine=ors`, cron dimanche = signalisation + Valhalla |
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
- **Passage automatique vérifié** : 25/09 à 23:00, créneau `nuit`, **6/6 OK**, curseur 12.
  Passages 26/09 à 08:00 et 12:30 : **6/6 OK chacun**, curseur 24.
  Total banc : **24 lignes réussies**, dont 18 automatiques. Log midi : 24 requêtes TomTom banc ce jour-là.
  Contrôle 20:25 : passage 18:00 **6/6 OK** ; total **30 lignes réussies**, dont 24 automatiques.
- **Android** 1.0.1 (4) et **iOS** 1.0.0 (2) : buildent et se lancent (Arthur, 25/09).
- **Commits** : iOS `55c56c9` poussé. x_radar `b7759b0` phase 1, puis documentation jusqu'à
  `53a81c4`, sans push. Audit distant 25/09 : branche x_radar avait 9 commits locaux d'avance.

## Reprise vérifiée le 26/09

- `/health` local `ok`, public HTTP 200. Backend démarré depuis 25/09 19:34:38 ; aucun redémarrage pendant correction sauvegardes.
- Avant préparation phase 2 : 62 fichiers backend suivis comparés au VPS, identiques après normalisation CRLF.
- À 15:10 : `routing.route_log` vide ; 7 anciens trajets, aucun exploitable.
  À **20:25** : **22 lignes de routage**, **9 trajets enregistrés**, dont **2 nouveaux exploitables** sur iOS 1.0.0 (2).
  Deux trajets courts, hors pointe, ORS ; arrivée reconnue, départ réel et relevés ETA présents.
  **8 relevés exploitables sur 8**, tous dans tolérance prévue. Erreur absolue médiane : départ **90 s**, 25 % **23 s**, 50 % **24 s**, 75 % **12 s**.
  Variantes avec/sans arrêts incertains identiques. Sept anciens trajets exclus normalement, faute de mesures phase 1.
  Collecte bout en bout confirmée pour ces deux trajets iOS ; Android et précision générale restent à confirmer.
  `/health` public HTTP 200. Vérification lecture seule ; aucun test, déploiement ni redémarrage.
- Sauvegarde cassée confirmée : `runuser: command not found` sous PATH cron réduit.
  Correction autorisée par Arthur, installée **26/09 à 02:13** : PATH explicite, verrou, archives temporaires vérifiées.
- Sauvegarde réelle exécutée avec `PATH=/usr/bin:/bin` : succès ; dump **89 765 octets**, archive **36 427 octets**, mode 0600.
  Schémas `crowd` et `routing` présents ; archive données contient comptes, appareils, avatars et fichiers persistants disponibles.
  **Cron 03:10 confirmé** : succès à 03:10:01, dump 89 765 octets, données 36 525 octets, archives intègres et 0600.
  Restauration réelle non testée.
- Sauvegardes avant intervention : `/opt/eona-backend-src-backup-20260925-214605.tgz`
  et `/opt/eona-cron-backup-20260925-214605.tgz`. Fichiers installés contrôlés par SHA-256.
- `rebuild.sh` et `import.sh` déployés avec correction PATH seulement.
  Nouvelle chaîne Valhalla et preuve PBF restent **locales**, non installées.
- Bridge Windows réparé, revue indépendante Claude sans défaut bloquant, commit local **`1d5a856`**, sans push.
  Délégations Claude réelles exécutées via nouvelles instances MCP ; ancien connecteur devra être reconnecté.
- Revue scripts interrompue par timeout ; bridge avait marqué tâche `FAILED`, reprise stricte refusée.
  Reprise renvoie `ILLEGAL_TRANSITION`. Correction bridge elle-même interrompue ; aucune correction timeout intégrée.
  Revue indépendante des scripts reste ouverte. Conserver mêmes tâches, aucun remplacement.
  Lot local livré sans prolonger dépannage bridge ; détails de reprise conservés dans `.bridge/tools/REPRISE-20260926.md`.
- Arthur demande désormais **majorité du travail chez Claude**. Codex cadre, intègre et relit risques ciblés ; français caveman ultra.

## Phase 2 : préparation locale

- Claude : fournisseur Valhalla, façade, secours ORS, disjoncteur, mode ombre, réglage admin, `/faster`, santé et tests.
- Codex : scripts installation/build/test/rollback, Quadlet, sauvegardes, documentation et revue indépendante.
- Routage reste ORS par défaut. Aucun appel Valhalla sans `VALHALLA_ENABLED=1`.
- Ombre après réponse ; mesures 90 jours sans compte ni coordonnées. Tracés divergents admin seulement, 30 jours.
- Image officielle `3.9.0` fixée par digest. Voir [exploitation préparée](valhalla/README.md).
- **Aucun Podman, swap ni service Valhalla installé sur VPS. Aucun backend phase 2 déployé.**
- Tests locaux backend : **102 réussis, 0 échec**, relancés par Codex après correction de rétention.
  Purge ombre au démarrage puis chaque heure, même sans trajet ; lectures filtrées immédiatement par durée de conservation.
  Arthur demande contrôles ciblés, sans répétition des campagnes déjà réussies. Validation téléphone par Arthur.
  SQL/PostGIS réel, moteur réel, build France, p95, notification et rollback réels : **à vérifier**.
- Proximité frontière : contrôle d'accroche ne constitue pas frontière géographique exacte.
  Ne pas valider passage admins/tous avant essais Belgique et cas proches frontière.

## Collecte en cours

- Contrôle **27/09 02:43** : **10 trajets enregistrés**, dont **3 exploitables** sur iOS 1.0.0 (2).
  Un trajet supplémentaire depuis contrôle 26/09 20:25 ; quatre relevés ETA présents pour chacun des trois trajets.
  Sept anciens trajets restent exclus faute de mesures. `routing.route_log` : **363 lignes** ; ce nombre ne compte pas trajets terminés.
  `/health` public HTTP 200. Aucune conclusion sur trajets non reçus ; nombre attendu inconnu.
- Corrections mobiles préparées le 26/09 avec Claude Opus 5.5 : résumé arrivée conservé jusqu'à fermeture,
  popups routiers limités à 300 m, véhicule local esthétique. Critère arrivée, champs/API collecte et backend inchangés.
  Android **1.0.1 (5)** compilé ; iOS **1.0.0 (3)** prêt pour Codemagic, compilation non effectuée localement.
  Versions Android (4) et iOS (2) continuent collecte ; aucune mise à jour obligatoire pour amis cette nuit.
  Vérification téléphone reste à faire. Aucun test de trajet supplémentaire exigé avant installation facultative des nouvelles apps.

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
- Revue Codex reprise : arrivée automatique, départ réel, pauses, relevés ETA, `retargeted`, sérialisation Android/iOS et filtre rapport.
  Contrôles exécutés : 14 assertions sur exclusions, pauses après relevé, variantes, tolérance et heures Paris, toutes réussies.
  Aucun défaut trouvé dans chaîne relue. Libellés historiques F1–F4 absents du dépôt : correspondance exacte non reconstructible.
  Collecte bout en bout confirmée le 26/09 à 20:25 sur deux nouveaux trajets iOS après aller-retour signalé par Arthur.
  Autres situations et Android restent à couvrir ; deux trajets ne suffisent pas pour bilan de précision.
- Signalisation : reconstruction automatique 27/09 03:30–03:46 réussie.
  Puis lot recherche + feux `620462b` déployé 27/09 18:13 (accord Arthur), hors Valhalla : voir `REPRISE-RECHERCHE-SIGNALISATION.md`.
  Sauvegarde `/opt/eona-backend-src-backup-20260927-175606.tgz`. Backend redémarré 18:12:58 ; `/health` local et public `ok`.
  `rebuild.sh`/`import.sh` production inchangés ; chaîne Valhalla toujours locale.
- **Banc 27/09 18:00 : 0/6**, erreur `routing budget reached`, avant début déploiement.
  `/health` 17:52 : ORS 2 clés bloquées jusqu'au 28/09 00:00 UTC (201 et 45 appels), `budgetLeft` 0. Cause à diagnostiquer.
- Sauvegardes uniquement sur le VPS, aucune copie ailleurs.

## Prochaine étape

1. Clore revue indépendante des scripts avant installation ; reprendre tâche Claude existante quand bridge permet reprise stricte.
2. Présenter lot VPS précis à Arthur : Podman, swap, service, premier build France et mesures. Accord requis avant intervention.
3. Poursuivre trajets ordinaires et collecte phase 1 ; couvrir Android et situations variées avant bilan.
