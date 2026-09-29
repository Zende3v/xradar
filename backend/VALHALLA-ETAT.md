# Valhalla : état du chantier

Mis à jour : **30/09/2026** (Claude : TomTom remplacé par HERE + vitesses EONA). Exploitation vérifiée le 26/09 à **15:10**, collecte le 27/09 à **02:43, Europe/Paris**.

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
| 2 | Valhalla installé, en mode ombre | **en ombre depuis 27/09 20:54** : backend phase 2, Podman, swap 4 Go, carte France (36 min, 50/50), `VALHALLA_ENABLED=1`, `routingEngine=ors`, cron dimanche = signalisation + Valhalla ; banc ORS + Valhalla (3c1bd99), build 11 Gio, alertes mail build/panne vers Arthur |
| 3 | Valhalla pour les admins, puis pour tous | **admins sur Valhalla depuis 27/09 23:45** (banc 472 mesures : Valhalla +6 % vs meilleur TomTom, ORS +12 %) ; cap envoyé par les apps (Android 6, iOS 4) contre demi-tours ; **28/09 : trajets admins** ; tempête de recalculs corrigée (Android 7, iOS 5) ; tous : à décider |
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

## Ménage du 28/09 (aspirateurs)

Cause : recalcul tous les 150 m (hors route mesuré au sommet, pas au segment). Corrigé Android (7), iOS (5).
Revue des autres aspirateurs, décisions d'Arthur :
- **TomTom coupé partout** jusqu'à l'ETA dynamique : interrupteur `TOMTOM_ENABLED` (absent = aucun appel).
  Trafic `/api/traffic/route` et `/faster` : 503, apps gèrent déjà. Recherche : Photon + BAN seuls. Banc : cron retiré.
- **Présence** : position seulement en trajet, plus une à la fermeture de l'app (`closing`). Hors trajet en
  arrière-plan : aucun ping. Serveur jette les positions hors trajet des anciennes apps.
- **Limite de vitesse hors route** : requête tous les 100 m au lieu de 40 m.
- **Code mort supprimé** : Android `data/geocoding`, `SignApi.near` ; iOS `SignAPI.near` ;
  backend `/api/live/position` et `/api/live/near`.
- **Copies `accounts.backup-*`** : gardées 14 jours (jamais purgées avant).
- **Signalements** : rechargés toutes les 30 s, toutes les 90 s après 2 min d'arrêt (Android 9, iOS 7).
- Gardé : `avoid=traffic` (contrat `/api/route`), ORS en secours et en ombre encore un peu (Arthur), `signs_prev` (retour arrière).
- Batterie (GPS en arrière-plan hors trajet) : pas un sujet pour l'instant (Arthur).
- **Déployé 28/09 20:34** (accord Arthur) : sauvegarde `/opt/eona-backend-src-backup-20260928-2034.tgz`, `/health` ok, trafic `null`.
  Quarantaine `/root/eona-quarantaine-20260928/` : cron `eona-bench`, `liste-des-passages-a-niveau.geojson`, `signs-cache/`,
  `ors-usage.json.bak-20260927-1938`, `bench-intensif.sh`, 4 anciennes sauvegardes `src` (3 dernières gardées).
- Politique de confidentialité : collecte réduite, texte à réécrire en phase 5.

## Corrections du 28/09 soir (Arthur)

- **3.2 Guidage autoroute** : étapes Valhalla avec `exitNumber`, `towardRefs`, `toward` (additif, vides pour ORS).
  Bannière : pastille « Sortie 8 », ligne « N 104 · Sénart, Corbeil-Essonnes ». Voix courte : « prenez la sortie 8 vers Sénart ».
- **3.3 Curseur qui tremble à l'arrêt** : filtre d'arrêt (Android `StandstillFilter.kt`, iOS `StandstillFilter.swift`).
  Arrêté : position figée, cap gelé, 0 km/h. Départ seulement si les fixes s'éloignent vraiment.
- **3.4 Arrêter le trajet** : résumé affiché (« Trajet terminé ») et trajet enregistré, comme à l'arrivée.
  Groupe et partage : inchangés (pas de « arrivé » annoncé à tort). Mesure ETA : trajet toujours `arrived: false`.
- **Déployé 28/09 21:17** : sauvegarde `/opt/eona-backend-src-backup-20260928-2117.tgz`, `/health` ok ; vérifié Paris-Évry : sortie 8, N 104, Sénart.
- **3.1 Sens des radars : option 1 seule** (Arthur, 28/09 22:20) :
  - « Pas dans mon sens » sur l'alerte radar : 2 votes à moins de 45° = radar muet dans ce sens pour tous
    (admin : 1 vote suffit) ; muet tout de suite pour le votant, tant que l'app tourne ;
  - sans vote : le radar sonne dans les deux sens, comme avant.
  - Option 2 (sens aspiré sur le site officiel) supprimée : faite par erreur à 21:31 (Arthur avait mal
    compris la question). Collecte arrêtée à 22:20 ; cron, `radar-directions.json` et journal en
    quarantaine `/root/eona-quarantaine-20260928/radar-directions/` ; code retiré.
  - **Retrait déployé 28/09 à 22:38** par Codex, accord Arthur : `config.js` et `radars/store.js` remplacés.
    Module, script et cron source obsolètes déplacés dans `radar-directions/code-20260928-223651/`, sous la quarantaine précédente.
    Sauvegarde vérifiée : `/opt/eona-backend-src-backup-20260928-223651.tgz` (src, manifests, script et cron source).
    Backend actif ; `/health` local HTTP 200, public `status: ok`. API publique : 83 radars vérifiés, tous avec `quietCourse`, aucun avec `course`.
    Aspirateur arrêté ; cron actif et fichier de directions absents. 3 309 radars chargés après redémarrage.
  - **iOS 1.0.0 (10)** : `f98041b` poussé sur `main`. `RadarAPI.notMyWay` vérifie le JSON optionnel avant lecture.
    Compilation et tests Swift restent à lancer par Arthur sur Codemagic. Aucun build iOS local.
  - **Android 1.0.1 (12)** : APK existant, produit par Claude à 22:23 ; aucun nouveau build pendant reprise.
    Vérifications Codex : test ciblé votes réussi (1/1), différences Git sans erreur, fichiers déployés identiques par SHA-256.
    Relecture Claude indisponible : quota mensuel atteint. Aucun nouveau chantier repris.

## Phase 3 finie, phase 4 commencée (29/09)

- **`routingEngine=all`** le 29/09 (Arthur) : tout le monde sur Valhalla, ORS en secours et en ombre.
  Avant : admins seuls, 136 routes, 0 erreur, 0 repli, 16 ms en moyenne.
- **Phase 4, lot 1 : ETA dynamique** (D2.1, D2.4), sans TomTom (coupé) :
  - apps : `EtaEstimator` (Kotlin `core/drive`, Swift `EonaCore/Drive`, 4 tests Swift) ;
    temps restant = base du moteur répartie par durées d'étapes + bouchons encore devant ;
    avec TomTom plus tard : base = temps TomTom moins ses bouchons listés ;
  - arrivée affichée bouge seulement de 1 min ou plus (`ArrivalClock`) ; HUD, partage, groupe et
    mesures (`etaChecks`) sur la même ETA ; mode d'ETA enregistré `dynamic` ;
  - backend : `/api/traffic/route` sans TomTom rend les bouchons EONA (plus de 503), `source` sur chaque section ;
  - Android 1.0.1 (14), iOS 1.0.0 (12).
- **Lot 1 déployé** le 29/09 à 14:46 (sauvegarde `/opt/eona-backend-src-backup-20260929-1446.tgz`).
- **Lot 2 : recalage TomTom sur événement** (D2.2, D3.1) : `TrafficRefresh` (Kotlin, Swift) ; TomTom à la nouvelle route,
  écart d'arrivée ≥ 2 min et ≥ 10 % du restant, bouchon TomTom dépassé, ou au plus tard 5/10/15 min selon le restant
  (valeurs de départ, à mesurer) ; reste du trajet seulement ; EONA et data.gouv toutes les 2 min sans TomTom.
  Backend : budget du jour 2 300 (ETA 1 700, `/faster` 400, banc à part), une requête TomTom par minute et par compte,
  `minGapS` 10 min passé 50 % de la part ETA, 20 min passé 80 %, puis plus de TomTom.
- **Lot 3 : data.gouv** (D2.6, D3.2) : `src/traffic/datagouv.js` ; vitesses QTV (507 stations placées, retard contre 90 %
  de la limite OSM) et événements DIR (fermetures, travaux, incidents, files) ; interrupteur `trafficDatagouv` (admin,
  actif par défaut) ; apps : chaque source entière (`raw`), fusion côté app, deux ETA enregistrées par relevé.
- **Sytadin** : pas de flux ouvert trouvé (data.gouv, transport.data.gouv, Bison Futé sans station IDF, Cerema).
  sytadin.fr : « Toute reproduction interdite sans l'accord écrit préalable de la DiRIF ». Source prête à brancher
  dès qu'Arthur donne l'adresse d'un jeu ouvert ou l'accord DiRIF.
- **Lot 4** : option « Éviter les ferries » (D4.2) ; « À propos » : Valhalla, TomTom, DIR data.gouv (D8.1).
- Android 1.0.1 (15), iOS 1.0.0 (14) (build 13 : test `AccountAPITests` cassé par un NSNull dans `etaChecks`, corrigé).
- **Lots 2 à 4 déployés le 29/09 à 22:19** (accord Arthur) : sauvegarde `/opt/eona-backend-src-backup-20260929-2219.tgz`,
  TomTom rallumé (drop-in `tomtom-on.conf`, `TOMTOM_ENABLED=1`). `/health` : plafond 2 300, data.gouv 503 stations,
  868 vitesses, 465 événements, aucune erreur.

## TomTom remplacé par HERE (29-30/09, Arthur)

- **HERE Traffic API v7** (offre standard ; Deep Coverage plus tard, `HERE_DEEP_COVERAGE=1`) : vitesses et
  incidents dans un couloir autour du reste du trajet (`src/traffic/here.js`), 2 requêtes par rafraîchissement.
  Tarifs relevés sur here.com le 29/09 : Traffic 5 000 gratuites par mois puis 2,33 € les 1 000 ; Advanced
  Traffic (Deep Coverage) 2 500 gratuites puis 4,66 € les 1 000.
- Pas de plafond global (Arthur) ; garde par compte : un rafraîchissement par minute, 150 par jour ; `/faster` une
  vérification par minute. Compteur jour et mois dans `/health` `traffic.here`.
- **Vitesses EONA** (`src/traffic/speeds.js`, `POST /api/traffic/speeds`) : échantillons anonymes pendant les
  trajets avec « Aide au trafic partagé » (clé aléatoire du trajet), 30 min en mémoire. Route couverte à 80 % par
  les conducteurs : HERE pas appelé. Plus d'EONA, moins de HERE.
- `/faster` : temps = temps moteur + retards live (HERE + conducteurs), plus de chronométrage TomTom.
- **TomTom retiré** : trafic, `/faster`, recherche POI, banc (historique gardé, lecture seule).
- Apps : source « here », vitesses partagées, `etaS` envoyé à `/faster`, « À propos ». Android 1.0.1 (16), iOS 1.0.0 (15).
- **Recherche : moteur EONA** (30/09, option A d'Arthur ; Google écarté : ses conditions EEE interdisent d'afficher
  les lieux Places près d'une carte non Google). Index OSM dans PostGIS (schéma `search`, 2,4 M lieux, rebuild hebdo
  dans la chaîne geodata) + BAN ; Photon en secours seulement. 30 à 100 ms. README §5 bis.
- Déployé 30/09 nuit : HERE (TomTom en quarantaine `/root/eona-quarantaine-20260930-here/`), doublons HERE
  corrigés, recherche EONA. Android 1.0.1 (17), iOS 1.0.0 (16) : crédit OSM pour la recherche.

## Prochaine étape

1. Arthur : tests téléphone (trafic HERE, ETA, recherche).
2. Arthur : moyen de paiement HERE (Base Plan), pour passer le gratuit quand il faudra ; régénérer la clé HERE.
3. Phase 5 : CGU et politique de confidentialité (HERE, vitesses EONA, data.gouv, recherche EONA).
