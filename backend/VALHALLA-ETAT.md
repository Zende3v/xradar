# Valhalla : état du chantier

Mis à jour : **05/10/2026**. Code relu, VPS contrôlé en lecture seule.

## Point de reprise

- Valhalla **3.9.0** actif pour tous : `/health.routing.mode = "all"`, état `up`.
- ORS reste secours et comparaison en ombre. OSRM démo retiré.
- Trafic traité dans le backend : **HERE, EONA, data.gouv**.
- ETA dynamique dans les apps. HERE Route Import chronomètre notre tracé ; dernière durée valide conservée.
- **Phases 1 à 5 livrées.** Collecte et validation de précision continuent, sans nouvelle phase 2.
- **Phases 6 à 8 du plan initial non implémentées.** Clôture ou maintien en attente à trancher avec Arthur.
- Aucun chantier supplémentaire lancé. Arthur choisit prochaine étape.

## Phases du plan initial

| Phase | Contenu | État au 05/10 |
|---|---|---|
| 1 | Mesurer l'existant | Instrumentation livrée le 25/09. Collecte en cours ; bilan de précision reste à mesurer. |
| 2 | Valhalla installé, mode ombre | Livrée le 27/09. Installation et mode ombre déjà réalisés. |
| 3 | Valhalla admins, puis tous | Livrée : admins le 27/09, tous le 29/09. Mode `all` vérifié sur VPS le 05/10. |
| 4 | ETA dynamique, data.gouv, cap, ferries | Livrée le 29/09, complétée par HERE puis budget et détours du 01/10. |
| 5 | Politique de confidentialité et CGU | Pages mises en ligne le 30/09 à 22:18, selon journal de déploiement. |
| 6 | Fermetures et travaux dans Valhalla | Non implémentée : aucun `traffic.tar` ni outil d'écriture versionné. Fermetures traitées par backend et `/faster`. |
| 7 | Vitesses en direct dans Valhalla | Non implémentée : vitesses HERE/EONA/data.gouv traitées dans backend, sans injection dans tuiles. |
| 8 | Historique appris dans Valhalla | Non implémentée : aucun `valhalla_add_predicted_traffic` dans construction. |
| — | Sytadin | Accès officiel DiRIF toujours absent du dossier projet. |

## Preuves des limites du moteur

- VPS, `/status` détaillé : `version = "3.9.0"`, `has_tiles = true`, **`has_live_traffic = false`**.
- VPS, `/health` : `status = "ok"`, `routing.mode = "all"`, `traffic.provider = "here"`.
- `valhalla/valhalla.json` : `tile_extract` configuré ; aucun `mjolnir.traffic_extract`.
- `valhalla/build-in-container.py` : `valhalla_build_extract` sans `--with-traffic` ; aucun ajout d'historique.
- `src/traffic/routes.js` fusionne HERE, EONA et data.gouv sur le tracé reçu.
- `src/routing/faster.js` traite fermetures, polygones d'évitement et contrôle des variantes.

Trafic opérationnel dans backend ne prouve pas injection dans Valhalla prévue par phases 6 à 8.
Ne pas déclarer ces trois phases livrées sans changement correspondant et preuve vérifiée.

## Production documentée

- Dernier déploiement backend documenté : **`f51cbbd`**, 05/10 à 01:04 ; `/health` alors OK.
- Carte validée le 04/10 à 19:38 : **`20261004T170051Z-a788fcddd0dc`**.
- Carte précédente documentée : `20260927T180134Z-717bad2d052c`.
- Rebuild dimanche 03:30 : signalisation, Valhalla, recherche ; même extrait France vérifié.
- Nouvelle carte testée avant bascule. Échec : ancienne carte conservée, journal et notification.
- Après build validé : seules cartes `current` et `previous` conservées.
- Sauvegardes avant déploiement `src` : `/opt/eona-backend-src-backup-*.tgz`, selon bilan.
- **Aucun déploiement, installation ni redémarrage sans accord explicite d'Arthur.**

## Livraisons récentes

- 01/10 : budget HERE mensuel **estimé 5 €**, cache 60 s, appels identiques partagés, quotas persistants.
- Détours : durées HERE comparées sur même portion ; fermeture ou contrôle incomplet refuse variante.
- EONA complète HERE. Couverture seule ne coupe plus HERE avant preuve de précision.
- 02/10 : Rapide / Éco, `preference`, `timed=1`, temps HERE au départ du trajet.
- 04/10 : multi-arrêts `via`, 10 étapes au plus, cache distinct, aucune arrivée intermédiaire.
- 04/10 : Scooter 50 / Sans permis, `vehicle=moped`, Valhalla `motor_scooter`, 45 km/h, sans HERE.
- 04/10 : recherche enseigne + commune, catégorie `suggestion`, lieux manuels `search/extra.sql`.
- 05/10 : champ additif `roads`, X1 E.Leclerc Orly et X2 Fitness Park Orly en service selon bilan.
- 05/10 : branches cloud intégrées. iOS `main` poussé à `252fb9d`, build 30 ; x_radar fusionné localement.

## Points ouverts

- **Précision ETA : à mesurer.** Instrumentation livrée ne constitue pas bilan de précision.
- Dernier bilan documenté du 03/10 : 5 trajets `dynamic` exploitables, échantillon insuffisant pour conclusion générale.
- Scooter : exclusion `motorroad=yes` encore non prouvée. Test A 6b / D 7 prouve seulement ce trajet sans autoroute.
- Rebuild **dimanche 11/10 à 03:30, Europe/Paris** : premier passage avec `extra.sql`, résultat à documenter.
- X2 Fitness Park Orly : coordonnées approchées, numéro exact à fournir.
- Consommation initiale **6,5 L/100 km** : valeur choisie, non mesurée. Péages non chiffrés dans coût carburant.
- Build iOS 30 et tests téléphone : validation par Arthur, résultat à documenter.
- Phases 6 à 8 : statut futur en attente de réponse d'Arthur ; aucune implémentation demandée.

## Documents

- `PLAN-VALHALLA.md` : plan initial validé le 25/09 ; lire statuts actuels dans ce fichier.
- `VALHALLA-DECISIONS.md` : décisions et mesures datées ; dernières décisions remplacent anciennes hypothèses.
- `../BILAN-SESSION-CLAUDE-20261005.md` : bilan cloud du 02/10 au 05/10.
- `CHECKLIST-TRAJETS.md` : trajets et points à vérifier par l'équipe.
- `API-WEBAPP.md` : contrat de la console, ajouts API et confidentialité.
- `../README.md` : exploitation backend ; `valhalla/README.md` : installation et retour arrière du moteur.
