# Recherche et signalisation — 27/09/2026

Arthur autorise préparation, puis déploiement complet du lot le 27/09 (« oui deploie tout »).
**Lot déployé 27/09 18:12–18:13 Paris.** Voir § Déploiement 27/09.
Apps actuelles et collecte doivent rester compatibles. Aucun build mobile prévu.

## Preuves

- Photon public : « Leclerc Orly » renvoie place du Général-Leclerc ; « Fitness Park Orly » renvoie autres villes.
- IGN, index POI : aucun résultat pour ces deux requêtes.
- TomTom Discover v3 puis POI Search v2 `typeahead=true` : deux réponses HTTP 200 chacun.
  Clé existante conservée sur VPS. Search v2 retenu : coordonnées disponibles pendant saisie,
  sans nouvelle version mobile. Suggest v3 nécessite appel Details ; Discover vise requête complète.
  E.Leclerc Orly : 8 place Gaston-Viens, `[2.4075, 48.7437]`.
  Fitness Park Orly : avenue des Martyrs-de-Châteaubriant, `[2.406266, 48.748551]`.
- Carrefour Lattre-de-Tassigny/Général-Leclerc, Choisy-le-Roi : onze points OSM fusionnés.
  Élément `e52d8f0ebbd7c4a3`, orientation absente, voie `23058072`, avenue du 25-Août-1944.
  Distance branche Lattre : 9,2 m ; filtre sans orientation : 6 m.
  Onze points OSM ne prouvent pas onze feux automobiles distincts.
- Reconstruction automatique confirmée : 27/09, **03:30–03:46**, Europe/Paris.
  Santé vérifiée après interruption : `ok`, données OSM **25/09 20:24 UTC**, publication **27/09 03:44 Paris**.
  Routes : 5 755 457 ; éléments : 1 973 273 ; lieux : 392 624.
- Corrections manuelles de feux : **0**, SQL `BEGIN READ ONLY`, contrôle 27/09 après-midi.

## Livrables

- Bridge racine `task_my9fx5g7qr`, run `run_9nqnzyjw3g`.
- Claude, feux : `task_1ad0fs2w88`, **COMPLETE**, 3 tests ciblés réussis ; SQL/PostGIS réel exécuté au déploiement 27/09.
- Claude, recherche initiale : `task_2sd6s5fc13`, **COMPLETE**, 14 tests ciblés réussis.
- Claude, ajustement après revue : `task_gd4cp83zm2`, **COMPLETE**, 15 tests recherche réussis.
  Search v2 prédictif ; TomTom sans cache/coalescence, réponses HTTP `no-store`, provenance affichée.
- Codex : rejet des coordonnées hors limites avant appels fournisseurs ; syntaxe contrôlée après ajout.
- Codex : comparaison fournisseurs, intégration, revue ciblée, documentation et commit local.
- Reprendre mêmes tâches si interruption. Aucun remplacement de tâche.

## Choix

- Conserver branches et orientations des feux ; ne pas élargir indistinctement filtre 6 m.
- Complément TomTom désactivé par défaut, activation explicite après accord de déploiement.
- Budget recherche dédié : **100 appels/jour, 2 000/mois**, compteur persistant séparé du trafic.
- Panne ou plafond : secours Photon/BAN ; réponses partielles non conservées comme réponses complètes.
- Tarif public Search API : 2 500 appels/mois gratuits affichés. Conditions du compte existant à confirmer avant activation.
  [Tarifs officiels](https://docs.tomtom.com/pricing), [API POI Search](https://docs.tomtom.com/search-api/documentation/search-service/points-of-interest-search).

## Procédure de déploiement appliquée

1. Lot : `src/search/{routes,rank,tomtom,budget}.js`, `src/signs/postgis.js`,
   `signalisation/{build,checks}.sql`, configuration production avec seul bloc recherche modifié.
   Ne pas transférer tout backend local : préparation Valhalla reste hors lot.
2. Accord Arthur reçu pour sauvegarde, installation du lot, reconstruction signalisation et redémarrage backend.
3. Sauvegarder `src`, configuration et pipeline existants avant transfert.
4. Vérifier aucune reconstruction active. Réimporter PBF local récent avec pipeline existant ; pas besoin nouveau téléchargement.
   Exécuter `build.sql`, `places.sql`, `checks.sql`, puis contrôle Choisy avant `publish.sql`.
   Approche attendue : nœud `11887308749`, voie `35269773`, direction environ 349°.
   Rejouer corrections manuelles via `edits.sql` ; publier seulement après contrôles réussis.
   Construire hors schéma publié ; conserver `signs_prev` et archive du code pour retour arrière.
5. Vérifier santé, recherche des deux établissements et carrefour. Arthur valide affichage sur téléphone.

Outils locaux : `.bridge/tools/search-signs-20260927/prepare-bundle.py`, `manifest.json`,
`check-choisy.sql`. Manifeste contrôle SHA-256 de configuration production avant remplacement.
Variables : `SEARCH_TOMTOM_ENABLED=1`, `SEARCH_TOMTOM_DAILY_MAX=100`, `SEARCH_TOMTOM_MONTHLY_MAX=2000`.
Clé dédiée facultative `SEARCH_TOMTOM_API_KEY` ; sinon clé TomTom existante.
Compteur : `data/search-tomtom-usage.json`, un seul processus backend par fichier.

Limites restantes : feu piéton de sortie peut produire seconde icône.
Validation téléphone par Arthur après installation.
Vérification finale Search v2 : 15 tests ciblés réussis par Claude ; 3 tests feux réussis auparavant.
Pas de nouvelle campagne générale ni build mobile. Revue Codex : protocole fournisseur, budget,
compatibilité JSON, regroupement SQL, filtre trajet.
Commit `620462b` poussé sur `origin/feature/mini-player-musique`. Paquet isolé : `.bridge/tools/search-signs-20260927/search-signs.tgz`.

## Déploiement 27/09

Claude, via bridge, accord explicite Arthur pour lot entier. Heures Europe/Paris.

- **Sauvegarde 17:56** : `/opt/eona-backend-src-backup-20260927-175606.tgz`, mode 0600,
  SHA-256 `0111350457171616be0e1892ff22c874b0bcdfd9a191fe46e9d026150282c68b`.
  Contenu : `src`, `package*.json`, `signalisation` ; 85 entrées, gzip OK ; `config.js` interne = SHA production attendu.
- **Paquet** : `search-signs.tgz` SHA-256 `f21313e9…`, 8 hashes manifest OK sur VPS, aucun fichier Valhalla.
  Config production `cfa2574e…` contrôlée avant remplacement. `node --check` 6 fichiers JS OK.
- **Staging** : dossier VPS `/var/lib/eona-signs/deploy-20260927` (scripts, journal `stage.log`, `stage.step`, `stage.exit`).
  Pipeline production réel ; seuls `build.sql` et `checks.sql` du lot. PBF local 25/09 20:24 UTC, md5 OK.
  Lancé détaché après banc 18:00, verrou `/var/lock/eona-geodata.lock`.
  Import 18:00–18:05, build 18:05–18:10, places, checks OK 18:11, contrôle Choisy OK 18:11 ; code sortie 0.
- **Contrôles avant publication** : routes 5 755 457, lieux 392 624, identiques.
  Feux : 45 549 → **143 149** éléments, nœuds OSM **166 632** inchangés ; checks accepte car règle regroupée.
  Autres types : écarts ≤ 7 éléments. Nœud `11887308749` : élément `16d57c390c87d23a`, voie `35269773`, cap **349°**.
- **Publication 18:12:57** : `edits.sql` (0 correction), `publish.sql`, schéma `osm` supprimé.
  `signs` = build 27/09 18:10, 2 070 868 éléments. `signs_prev` = build 27/09 03:44, conservé.
- **Installation 18:12:58** : 8 fichiers conformes au manifest, `chown eona`.
  Drop-in `/etc/systemd/system/eona-backend.service.d/search-tomtom.conf`, 0600 :
  `SEARCH_TOMTOM_ENABLED=1`, `SEARCH_TOMTOM_DAILY_MAX=100`, `SEARCH_TOMTOM_MONTHLY_MAX=2000`.
  Aucune clé dedans : `TOMTOM_API_KEY` existante.
- **Redémarrage 18:12:58** : `/health` local `ok` 18:13:00, `NRestarts=0`, aucun avertissement recherche au journal.
- **Recherches réelles** (compte test, autour d'Orly) : HTTP 200, `Cache-Control: no-store`.
  « Leclerc Orly » → 1er **E.Leclerc Orly**, 8 place Gaston-Viens, TomTom.
  « Fitness Park Orly » → 1er **Fitness Park**, avenue des Martyrs-de-Châteaubriant, Orly, TomTom.
- **Budget** : `data/search-tomtom-usage.json` = `{"day":"2026-09-27","month":"2026-09","today":2,"thisMonth":2}`.
- **Feux** : `POST /api/signs/route` le long voie `35269773`, sens 349° → un seul feu, `16d57c390c87d23a`, cap 349,2°.

Retour arrière, si Arthur le demande :
- signalisation : `BEGIN; ALTER SCHEMA signs RENAME TO signs_broken; ALTER SCHEMA signs_prev RENAME TO signs; COMMIT;` ;
- code : extraire sauvegarde (`src/config.js`, `src/search`, `src/signs/postgis.js`, `signalisation/{build,checks}.sql`),
  supprimer `src/search/{budget,tomtom}.js` et drop-in, `chown eona`, `daemon-reload`, restart.

Santé publique vérifiée par Codex après déploiement : HTTP 200, `status=ok`, publication 27/09 18:10 Paris,
2 070 868 éléments. Revue ciblée des scripts : staging séparé, contrôles avant publication, hashes avant installation.
Reste : validation téléphone Arthur (recherche, icônes feux sur trajet).
`/api/signs/near` renvoie désormais un feu par approche ; Android ne l'appelle pas (`route` et `limit` seulement).

Incident distinct observé avant déploiement : deux clés ORS bloquées jusqu'au 28/09 02:00 Paris ;
banc 18:00 en échec `routing budget reached`. Cause non déterminée ; suivi dans `VALHALLA-ETAT.md`.
