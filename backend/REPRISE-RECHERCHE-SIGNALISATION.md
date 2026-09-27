# Recherche et signalisation — 27/09/2026

Arthur autorise préparation des correctifs. Déploiement VPS non autorisé pour ce lot.
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
- Claude, feux : `task_1ad0fs2w88`, **COMPLETE**, 3 tests ciblés réussis ; SQL/PostGIS réel reste à exécuter.
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

## Déploiement à préparer après revue

1. Lot : `src/search/{routes,rank,tomtom,budget}.js`, `src/signs/postgis.js`,
   `signalisation/{build,checks}.sql`, configuration production avec seul bloc recherche modifié.
   Ne pas transférer tout backend local : préparation Valhalla reste hors lot.
2. Demander accord explicite pour sauvegarde, installation du lot, reconstruction signalisation et redémarrage backend.
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

Limites restantes : regroupement SQL non exécuté localement ; feu piéton de sortie peut produire seconde icône.
Validation téléphone par Arthur après installation. Aucun déploiement manuel ni redémarrage effectué pour ce lot.
Vérification finale Search v2 : 15 tests ciblés réussis par Claude ; 3 tests feux réussis auparavant.
Pas de nouvelle campagne générale ni build mobile. Revue Codex : protocole fournisseur, budget,
compatibilité JSON, regroupement SQL, filtre trajet. SQL réel reste à contrôler avant publication.
Commit local sans push. Paquet local isolé : `.bridge/tools/search-signs-20260927/search-signs.tgz`.
