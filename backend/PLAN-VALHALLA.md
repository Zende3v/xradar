# Plan : moteur de routage EONA sur Valhalla

Plan en phases tiré de l'atelier du 24/09/2026. Chaque choix renvoie à sa décision dans
[VALHALLA-DECISIONS.md](VALHALLA-DECISIONS.md) (D1.1 à D8.4). **Aucun code avant qu'Arthur valide
ce plan.**

## Règles pour toutes les phases

- **Contrat des apps** : `/api/route`, `/api/route/faster` et `/api/traffic/route` gardent leur
  format ; tout ajout de champ est additif. Une app déjà installée continue de marcher à chaque
  phase.
- **Accord d'Arthur** avant chaque déploiement du backend, chaque installation sur le VPS et chaque
  mise en ligne de texte (politique, CGU).
- **Tests** : Arthur fait lui-même les tests sur téléphone ; de notre côté, build release Android
  et build Codemagic iOS (dépôt `xradar_ios`, commit et push).
- **Chiffres** : aucune valeur inventée. Ce qui est « à mesurer » est mesuré dans la phase indiquée,
  puis noté dans le journal des décisions avant d'être utilisé.
- **Docs** (D8.4) : chaque phase met à jour `API-WEBAPP.md`, `README.md` et
  `CHECKLIST-TRAJETS.md`.

## Vue d'ensemble

| Phase | Objectif | Apps | Visible pour les conducteurs |
|---|---|---|---|
| 1 | Mesurer l'existant | mise à jour 1 | non (sauf « Signaler un bug » à l'arrêt) |
| 2 | Valhalla installé, en mode ombre | — | non |
| 3 | Valhalla pour les admins, puis pour tous | — | oui (itinéraires) |
| 4 | ETA dynamique, trafic data.gouv, cap, ferries | mise à jour 2 | oui |
| 5 | Politique de confidentialité et CGU | — | textes seulement |
| 6 | Trafic dans Valhalla : fermetures et travaux | — | oui (itinéraires) |
| 7-8 | Vitesses en direct, puis historique | — | à détailler après mesure |
| — | Sytadin (D3.5) | — | quand un accès officiel existe |

La mesure de l'ETA figée (phase 1) dure 2 à 3 semaines (D1.3) ; les phases 2 et 3 avancent pendant
ce temps.

---

## Phase 1 — Mesurer l'existant

**Objectif.** Enregistrer, sans rien changer au comportement, tout ce qu'il faut pour juger l'ETA
et les itinéraires (D1.1 à D1.10), et poser le banc.

**Contenu.**
- Trajet enregistré, champs ajoutés (D1.7) : arrivé ou non, départ réel (moment où la route est
  rejointe), départ choisi à la main, distance prévue, temps de pause et d'arrêts incertains (règle
  D1.4), ETA affichée à 0, 25, 50 et 75 %, nombre de recalculs, moteur, version de la carte, version
  de l'app, mode d'ETA (`static`), sources de trafic vues. Aucune coordonnée.
- `/api/route` : champs additifs `engine` (`ors`) et `mapVersion` (D5.4, D7.4).
- Appels ORS : timeout (D5.4) ; compteurs ORS et TomTom gardés sur disque et visibles dans
  `/health` (D3.1, D5.4).
- Journal de routage : une ligne par vrai calcul (latence, moteur, statut, nouveau trajet ou
  recalcul, demi-tour en première manœuvre), dans un schéma PostGIS `routing`, sans coordonnées,
  gardé 90 jours (D1.8).
- Banc (D1.7) : environ 50 trajets France fixes (dont Île-de-France, péages, montagne, bac, près des
  frontières), horaires tournants (matin, midi, soir, nuit). Chaque trajet coûte 2 requêtes TomTom
  (notre route chronométrée, route TomTom optimale) : environ 25 trajets par jour dans le budget
  d'environ 50. Mesures : écarts de temps et de km, km sur petites routes (`signs.road.highway`),
  itinéraires bizarres (D1.8).
- Rapport ETA : script qui applique les règles D1.2 à D1.6 (seuil, pauses, exclusions, tranches) et
  donne, par tranche, le nombre de trajets et l'intervalle de Wilson.
- « Signaler un bug », catégorie navigation : moteur, version de la carte et trajet en cours ou
  dernier joints automatiquement ; formulaire ouvert seulement à l'arrêt (moins de 1,5 m/s) (D7.4).
- À fixer pendant la phase : la définition des heures de pointe (D1.6).

**Fichiers touchés.**
- Backend : `src/accounts/store.js`, `src/accounts/routes.js`, `src/routing/routes.js`,
  `src/routing/ors.js`, `src/traffic/tomtom.js`, `src/bugs/routes.js`, `src/crowd/schema.sql`
  (colonnes du rapport de bug), `src/config.js`, `src/server.js` ; nouveaux : `src/routing/log.js`,
  `src/routing/schema.sql` (+ application au démarrage comme `crowd`), `src/traffic/budget.js`,
  `bin/eona-bench.js`, `bench/trajets.json`, `bin/eona-eta-report.js`,
  `deploy/eona-bench.cron`.
- Android : `core/drive/TripRecorder.kt`, `core/model/TripRecord.kt`, `core/model/Route.kt`,
  `data/routing/RoutingApi.kt`, `data/stats/TripHistoryRepository.kt`,
  `data/account/AccountApi.kt`, `data/bugs/BugApi.kt`, `feature/menu/BugReportScreen.kt`,
  `feature/drive/DriveViewModel.kt`.
- iOS : `EonaCore/Drive/TripRecorder.swift`, `EonaCore/Model/TripRecord.swift`,
  `EonaCore/Model/Route.swift`, `EonaData/API/RoadAPIs.swift`, `EonaData/API/AccountAPI.swift`,
  `EonaData/API/BugAPI.swift`, `EONA/Features/Menu/BugReportScreen.swift`,
  `EONA/Features/Drive/DriveModel.swift`, tests `TripTests`, `RoadAPITests`.
- Docs : `API-WEBAPP.md`, `README.md`, nouveau `CHECKLIST-TRAJETS.md`, `VALHALLA.md` marqué
  remplacé (D8.4).

**Critères d'acceptation.**
- Build release Android et build Codemagic iOS réussis, tests unitaires passés (règle des pauses,
  exclusions, relevés d'ETA).
- Un trajet arrivé, envoyé par la nouvelle app, contient tous les champs ; un trajet envoyé par une
  ancienne app est toujours accepté.
- Les réponses de `/api/route` ne diffèrent que par `engine` et `mapVersion`.
- Les compteurs TomTom et ORS de `/health` survivent à un redémarrage.
- Le banc tourne sur les 4 créneaux sans dépasser sa part TomTom ; ses résultats sont en base.
- Le rapport ETA sort ses tranches avec leurs effectifs.
- « Signaler un bug » refuse de s'ouvrir en roulant et joint le contexte à l'arrêt.

**Test d'Arthur.** Installer l'APK et l'IPA, puis : un trajet normal jusqu'à destination ; un trajet
arrêté en route (doit être exclu) ; un trajet avec une pause café de plus de 5 min (pause
retirée) ; un trajet avec départ choisi à la main (exclu). Lire le rapport ETA. Ouvrir « Signaler un
bug » en roulant (passager : refusé), puis à l'arrêt, et vérifier le contexte dans la console.
Regarder les compteurs de `/health`.

**Retour arrière.** Backend : restaurer la sauvegarde `src` (README, « Retour arrière du code ») ;
les nouvelles tables restent inutilisées. Apps : version précédente ; les champs étant additifs, les
deux versions cohabitent.

---

## Phase 2 — Valhalla installé, en mode ombre

**Objectif.** Valhalla tourne sur le VPS, se reconstruit chaque semaine et calcule en ombre derrière
ORS (D7.1), sans aucun effet pour les conducteurs.

**Contenu.**
- VPS (D6.1 à D6.4) : Podman depuis apt ; image `ghcr.io/valhalla/valhalla` fixée sur la version
  3.9.0 (nom exact du tag à vérifier) ; service systemd par Quadlet `eona-valhalla`, sur
  `127.0.0.1:8002` ; dossiers `/var/lib/valhalla` (cartes construites + lien vers la carte en
  service + carte précédente) ; fichier d'échange de sécurité.
- Construction (D6.2) : script lancé après `rebuild.sh` le dimanche, même si la signalisation
  échoue ; aucune reconstruction si le PBF n'est pas neuf ; admins, fuseaux, tuiles, archive ;
  basse priorité CPU et disque, mémoire plafonnée, premier tué si la mémoire manque. Premier build :
  pic mémoire, durée et taille mesurés, puis taille du fichier d'échange et plafonds fixés.
- Configuration Valhalla : `max_exclude_polygons_length` d'au moins 200 km (D4.5), `max_alternates`
  à 3, `search_cutoff` réduit (D5.1).
- Test puis bascule (D6.3) : instance temporaire, `/status` et trajets du banc ; réussite → lien +
  redémarrage ; échec → ancienne carte gardée, journal et mail.
- Backend : façade `engine.js` (Valhalla puis ORS, délais, disjoncteur, contrôle d'accroche hors de
  France) (D5.1) ; fournisseur Valhalla (profil `auto`, `top_speed` 130, `exclude_*`,
  `exclude_polygons`, `alternates`, décodage polyline6, format d'étapes OSRM avec
  `roundabout_exits: false` et `name` sinon `ref`, traduction des erreurs) (D4.1 à D4.3) ; OSRM démo
  retiré (D5.3) ; `/faster` indépendant du moteur, coupé en secours (D5.2), bornes actuelles de
  60 et 85 km (D4.5).
- Réglage `routingEngine` dans `settingsStore`, valeur `ors` (D7.2).
- Mode ombre (D7.1) : l'autre moteur calcule après la réponse ; mesures dans `routing` (90 jours,
  sans coordonnées) ; tracés complets des comptes admin quand l'écart est gros (30 jours) ; route
  admin pour les relire sur une carte.
- `/health` : version et date de la carte, `has_live_traffic`, bascules sur ORS et leur cause ; mail
  si la bascule dure (D6.4).
- Délais, nombre d'échecs du disjoncteur, seuil d'accroche et plafonds du service : mesurés en ombre,
  puis notés dans le journal.

**Fichiers touchés.**
- Backend : nouveaux `src/routing/engine.js`, `src/routing/providers/valhalla.js`,
  `src/routing/shadow.js`, `valhalla/build.sh`, `valhalla/test.sh`, `valhalla/valhalla.json`,
  `deploy/eona-valhalla.container`, `deploy/eona-signs.cron` (enchaînement), `bin/eona-notify.js` ;
  `src/routing/ors.js` déplacé en `src/routing/providers/ors.js` ; modifiés
  `src/routing/routes.js`, `src/routing/faster.js`, `src/routing/schema.sql`,
  `src/accounts/settings.js`, `src/accounts/routes.js`, `src/admin/routes.js`, `src/config.js`,
  `src/server.js`.
- Docs : `README.md` (service, build, pannes), `API-WEBAPP.md` (`/health`, réglage, route admin),
  journal (valeurs mesurées).

**Critères d'acceptation.**
- Premier build réussi sur le VPS, valeurs mesurées notées ; PostgreSQL jamais tué.
- Avec `routingEngine = ors`, les apps ne voient aucune différence et le p95 de `/api/route` ne
  bouge pas par rapport à la phase 1.
- La table de l'ombre se remplit ; les tracés complets n'existent que pour des comptes admin ; la
  purge fonctionne.
- `eona-valhalla` arrêté : le backend continue sur ORS, `/health` le montre, le mail part après le
  seuil.
- Une destination en Belgique part sur ORS.
- Plus aucun appel à OSRM.
- Un build du dimanche passe, seul, du téléchargement à la bascule.

**Test d'Arthur.** Donner son accord aux installations. Lire `/health`. Relire sur une carte
quelques écarts gardés pour les comptes admin. Conduire normalement : rien ne doit changer.

**Retour arrière.** `routingEngine` reste sur `ors` ; `systemctl stop eona-valhalla` ; restaurer la
sauvegarde `src` du backend ; retirer le service et Podman si besoin.

---

## Phase 3 — Valhalla pour les admins, puis pour tous

**Objectif.** Servir les itinéraires par Valhalla, d'abord à l'équipe, puis à tous, sur critères
écrits (D7.3).

**Contenu.**
- Chiffrer les seuils de D7.3 avec les mesures de l'ombre et les écrire dans le journal avant tout
  passage.
- Régler le profil sur le banc, un réglage à la fois, mesuré avant et après (`maneuver_penalty`,
  `use_distance`, `use_living_streets`, `service_penalty`) (D4.1).
- Rouvrir la fenêtre de `/faster` par paliers, horizon en temps de conduite, latence mesurée à
  chaque palier (D4.5).
- `routingEngine = admins` : l'équipe roule avec `CHECKLIST-TRAJETS.md` et « Signaler un bug » ;
  ORS en ombre.
- Critères atteints et décision d'Arthur : `routingEngine = all`.

**Fichiers touchés.** `valhalla/valhalla.json`, `src/routing/providers/valhalla.js`,
`src/routing/faster.js`, `src/config.js`, `CHECKLIST-TRAJETS.md`, `README.md`, `API-WEBAPP.md`,
journal (seuils, réglages, paliers).

**Critères d'acceptation.** Les critères de D7.3 sont atteints à chaque étape, et la décision
d'Arthur est notée dans le journal.

**Test d'Arthur.** Rouler les trajets de la checklist avec un compte admin ; signaler ce qui cloche ;
basculer une fois `routingEngine` sur `ors` puis de nouveau sur `admins` pour vérifier le retour
arrière.

**Retour arrière.** `routingEngine = ors`, en direct depuis l'admin.

---

## Phase 4 — ETA dynamique, trafic data.gouv, cap et ferries

**Objectif.** Une ETA qui se recalcule à chaque fix (D2.1 à D2.4), le trafic data.gouv comparé sur
tous les trajets (D2.6, D3.2), le cap aux recalculs (D4.4) et l'option ferries (D4.2) : la
deuxième mise à jour des apps.

**Contenu.**
- Backend :
  - chaque section de `/api/traffic/route` porte sa `source` (`tomtom`, `crowd`, `datagouv`)
    (D2.7) ;
  - collecteur data.gouv (événements et vitesses QTV, DATEX II 2.2), dans le backend sur le modèle
    de `fuel/feed.js` (choix d'implémentation à confirmer avec ce plan) ; stations placées depuis le
    référentiel Lambert-93 ; retards data.gouv ajoutés en dernier, pour leur part en plus seulement
    (D2.6, D3.4) ;
  - interrupteur data.gouv dans `settingsStore` ; les nouvelles apps le signalent dans leur requête
    et reçoivent toujours les retards data.gouv ; les anciennes seulement si l'interrupteur est
    actif (D2.6) ;
  - budget TomTom : parts réservées (banc, `/faster`, ETA), espacement puis arrêt du recalage
    quand le budget baisse, plafond strict (D3.1) ;
  - paramètre `heading` dans `/api/route`, transmis à Valhalla (et à ORS en secours) (D4.4).
- Apps (Android et iOS) :
  - calcul d'ETA en logique pure, mêmes tests sur les deux plateformes, test d'acceptation D2.1 ;
  - HUD, partage et groupe sur la même formule ; arrivée affichée changée seulement pour 1 min ou
    plus (D2.4) ;
  - recalage TomTom sur événement (nouvelle route, dérive, bouchon dépassé, délai maximal selon le
    temps restant), reste de la route seulement (D2.2) ; seuils calibrés avec les mesures de la
    phase 1 ;
  - deux ETA calculées et enregistrées (avec et sans data.gouv), affichage selon l'interrupteur,
    mode d'ETA `dynamic`, sources enregistrées (D2.6, D2.7) ;
  - durée des étapes et `travelS` lus ;
  - cap envoyé au départ et au recalcul à partir de 1,5 m/s (D4.4) ;
  - option « Éviter les ferries » (D4.2) ;
  - « À propos » : openrouteservice remplacé par Valhalla, ligne source et date des données DIR
    (D8.1).

**Fichiers touchés.**
- Backend : `src/traffic/routes.js`, `src/traffic/tomtom.js`, `src/traffic/crowd.js`,
  `src/traffic/budget.js`, nouveau `src/traffic/datagouv/` (collecte, lecture DATEX II, placement
  sur la route), `src/accounts/settings.js`, `src/routing/routes.js`, `src/routing/engine.js`,
  `src/config.js`, `src/server.js`.
- Android : nouveau `core/drive/EtaEstimator.kt` et ses tests, `core/model/{Route,RouteStep,Traffic,TripInfo}.kt`,
  `data/routing/RoutingApi.kt`, `data/traffic/TrafficApi.kt`,
  `data/preferences/AppPreferences.kt`, `feature/drive/DriveViewModel.kt`,
  `feature/drive/group/GroupSession.kt`, `feature/drive/component/DriveDock.kt` (options de trajet), `feature/menu/LegalScreen.kt`.
- iOS : nouveau `EonaCore/Drive/EtaEstimator.swift` et ses tests,
  `EonaCore/Drive/TripProgress.swift`, `EonaCore/Model/{Route,Traffic}.swift`,
  `EonaData/API/RoadAPIs.swift`, `EonaData/Stores/PreferencesStore.swift` (+ `StoresTests`), `EONA/Features/Drive/Components/DriveDock.swift`,
  `EONA/Features/Drive/DriveModel.swift`, groupe et partage, `EONA/Features/Menu/LegalScreen.swift`.
- Docs : `API-WEBAPP.md` (`source`, `heading`, interrupteur, budget), `README.md`,
  `CHECKLIST-TRAJETS.md`.

**Critères d'acceptation.**
- Mêmes tests unitaires réussis sur Android et iOS ; au départ, ETA = temps TomTom avec trafic +
  retards EONA.
- En trajet, l'ETA bouge à chaque fix sans requête ; l'arrivée affichée ne change que pour 1 min ou
  plus.
- Appels TomTom par heure de conduite en baisse par rapport à la mesure de la phase 1, dans les
  parts de D3.1 ; budget épuisé simulé : l'ETA continue sans TomTom.
- Interrupteur data.gouv changé en direct : l'ETA affichée change de source, les deux restent
  enregistrées.
- Les apps de la phase 1 marchent toujours et ne reçoivent les retards data.gouv que si
  l'interrupteur est actif.
- Taux de demi-tours au recalcul mesuré par rapport à la phase 3.

**Test d'Arthur.** Des trajets réels, dont un avec bouchon : l'ETA doit baisser régulièrement et se
corriger au bouchon. Basculer l'interrupteur data.gouv et regarder l'ETA. Quitter la route en
roulant : pas de demi-tour proposé. Tester « Éviter les ferries » sur un trajet qui passe par un bac
(par exemple l'estuaire de la Gironde). Lire « À propos ».

**Retour arrière.** Interrupteur data.gouv coupé ; apps précédentes (compatibles) ; sauvegarde `src`
du backend.

---

## Phase 5 — Politique de confidentialité et CGU

**Objectif.** Mettre les textes en accord avec ce qui tourne, en une seule fois (D8.2, D8.3).

**Contenu.**
- Politique : champs de trajet, mesures de l'ombre (90 jours, sans coordonnées), tracés des comptes
  admin (30 jours), trajet joint à « Signaler un bug », TomTom (reste de la route, sur événement),
  Valhalla sur les serveurs EONA, ORS en secours, données data.gouv ; plus les deux écarts actuels
  (position envoyée à la BAN et à Photon, Cloudflare à la place de Tailscale).
- CGU, article 11.1 : liste des fournisseurs et date, sans nouvelle acceptation.

**Fichiers touchés.** `backend/privacy/index.html`, `backend/cgu/index.html`, `README.md`.

**Critères d'acceptation.** Chaque traitement en service a son paragraphe (liste de contrôle
traitement par traitement) ; les dates de mise à jour sont changées.

**Test d'Arthur.** Relire les deux pages en ligne.

**Retour arrière.** Remettre les versions précédentes (git) sur le VPS.

---

## Phase 6 — Trafic dans Valhalla : fermetures et travaux

**Objectif.** Le moteur connaît les routes fermées et les travaux data.gouv et les évite lui-même
(D3.3, étape 1).

**Contenu.**
- Squelette `traffic.tar` refait à chaque carte (`valhalla_build_extract --with-traffic`) ;
  `mjolnir.traffic_extract` configuré.
- Outil qui écrit les vitesses dans `traffic.tar` (à coder : l'outil n'est pas fourni par Valhalla ;
  langage choisi dans la phase).
- Rattachement des événements aux tronçons Valhalla (`/trace_attributes` ou `/locate`).
- Écriture périodique ; rien n'est écrit quand l'interrupteur data.gouv est coupé (D3.3).
- Banc avec et sans (option d'ignorance du trafic temps réel par requête, à vérifier) ; effet de
  `max_timedep_distance` (500 km) à vérifier.

**Fichiers touchés.** `valhalla/build.sh`, `valhalla/valhalla.json`, nouveau `valhalla/traffic/`
(outil d'écriture), `src/traffic/datagouv/`, `src/routing/providers/valhalla.js`, `README.md`,
`API-WEBAPP.md`.

**Critères d'acceptation.** Sur le banc, les routes évitent les fermetures DATEX en cours ; pas de
recul sur les critères D7.3 ; l'écriture tourne sans erreur et repart à zéro à chaque nouvelle
carte.

**Test d'Arthur.** Choisir une fermeture en cours dans la liste Bison Futé et demander un trajet qui
la traverse : Valhalla doit la contourner.

**Retour arrière.** Interrupteur data.gouv coupé (plus rien d'écrit), ou `traffic_extract` retiré de
la configuration et service redémarré.

---

## Phases 7 et 8 — Vitesses en direct, puis historique

Détaillées après mesure de la couverture de la phase 6 (D3.3) : 7) vitesses des stations data.gouv
et des sondes EONA dans `traffic.tar` ; 8) historique appris, ajouté à la construction de la carte
(`valhalla_add_predicted_traffic`).

## Plus tard — Sytadin

Dès qu'un flux officiel ou un accord écrit de la DiRIF existe (D3.5) : même traitement que
data.gouv (interrupteur, `source`, deux ETA, fusion D3.4).
