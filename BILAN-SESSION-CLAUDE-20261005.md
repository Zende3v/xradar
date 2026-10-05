# Bilan session Claude (cloud), 02/10 → 05/10/2026

Reprise temporaire d'EONA par Claude seul : ni Codex ni bridge. Android non touché.
Arthur déploie le VPS et lance Codemagic. SSH VPS impossible depuis le cloud.
Branche de session : `claude/inspiring-planck-vksc4r` sur les deux repos.
Recalage 05/10 : intégrée dans iOS `main` (push `252fb9d`) et x_radar `feature/mini-player-musique` (cloud `1455c5b`).

## État final (05/10, 01:10)

| Élément | État |
|---|---|
| Backend en service | `f51cbbd`, déployé 05/10 01:04, `/health` ok |
| Dernier commit backend | `d4ce9fd` (docs seules, rien à déployer) |
| Carte Valhalla | `current` = `20261004T170051Z-a788fcddd0dc`, `previous` = `20260927T180134Z-717bad2d052c` |
| iOS | build 30 (`2a02aef`) poussé ; Codemagic build 29 passé ; build 30 à lancer |
| Tests backend | 175 passent |
| Sauvegardes VPS | `src` avant chaque déploiement (`/opt/eona-backend-src-backup-*.tgz`), `search` 04/10 |

## Backend (`x_radar`)

| Date | Commit | Lot |
|---|---|---|
| 02/10 | `19aa105` | Choix Rapide / Éco : `preference=shortest`, `timed=1` (`travelS` HERE), Éco sur `/faster`, colonne `route_log.preference`, rapport ETA `--since` |
| 04/10 | `0234ddf` | Multi-arrêts : `via` (10 étapes) sur `/api/route` et `/faster`, colonne `route_log.stops` |
| 04/10 | `585fbb7` | Build Valhalla du dimanche réparé : `valhalla/trajets.json` versionné, test Éco + étape, ligne d'échec dans `rebuild.log` |
| 04/10 | `63189a7` | Ménage auto : après build validé, seules `current` et `previous` restent |
| 04/10 | `b8640ec` | `vehicle=moped` (Valhalla `motor_scooter` 45 km/h, sans autoroute, sans HERE) ; recherche enseigne + ville ; catégorie bug `suggestion` |
| 04/10 | `f361480` | `search/extra.sql` : lieux ajoutés à la main, réappliqués à chaque rebuild ; X1 E.Leclerc Orly |
| 04/10 | `f51cbbd` | Champ additif `roads` `{toll, motorway, ferry}` sur routes Valhalla ; X2 Fitness Park Orly |

Contrat `/api/route` : ajouts additifs seulement. JSON ORS inchangé. Détails : `backend/API-WEBAPP.md`, `README.md` §5 bis et §12.

## iOS (`xradar_ios`)

| Build | Commit | Contenu |
|---|---|---|
| 24 | `2a18dca` | Choix Rapide / Éco / Perso ; Véhicule dans Réglages ; icônes Signaler en couleur |
| 25 | `8489a68` | ETA départ = temps HERE du choix |
| 26 | `ece0187` | Multi-arrêts ; permis probatoire ; Garer mon véhicule ; Signaler sobre |
| 27 | `57585bb` | Rapide jamais plus lent qu'Éco avec trafic |
| 28 | `a9ca0d1` | Stationnement multi-repères ; Scooter 50 et Sans permis (45 km/h, itinéraire moped) ; Protection pluie ; couleur en curseur ; présence activée d'office ; menu en boîtes ; Mon compte & Statistiques fusionnés ; EONA + ; Contactez-nous ; Rapports |
| 29 | `9505844` | Choix d'itinéraire : temps gagné, coût €, autoroute / péage ; Éco : temps en plus, km et € économisés ; carburant préféré ; pseudo au toucher ; carte véhicule « Esthétique » |
| 30 | `2a02aef` | Consommation au curseur, 1,0 à 30,0 L/100 km, cran 0,1 |

Aucune compilation locale (pas de Mac). Codemagic a validé jusqu'au build 29.

## VPS (fait par Arthur)

- 03/10 01:11 : déploiement `19aa105`.
- 04/10 soir : `valhalla/` (`585fbb7`), build relancé, carte validée 19:38. Candidats ratés supprimés. Puis `build.sh` (`63189a7`).
- 04/10 22:16 : déploiement `src` (`f2643e8`). Puis `search/extra.sql` et `rebuild.sh` appliqués.
- 05/10 01:04 : déploiement `src` (`f51cbbd`), `extra.sql` réappliqué : X1 et X2 en service.
- Incident 04/10 22:15 : `src` supprimé, puis `scp` lancé depuis le VPS au lieu du PC. Corrigé depuis le PC.
  Service jamais coupé (code en mémoire). Archive vide supprimée.

## Contrôles faits sur le VPS

- Scooter, Orly → Porte d'Italie : `auto` prend A 6b ; `motor_scooter` reste sur D 7.
- Valhalla 3.9, format OSRM, Orly → Rouen : classes `toll` et `motorway` présentes. `roads` fiable.
- Recherche : aucun Fitness Park à Orly dans OSM (le plus proche : Thiais, 2,8 km).
  E.Leclerc Orly : OSM n'a que le centre commercial Orlydis (`shop=mall`, `operator=E.Leclerc`).
  Ancien filtre : la ville tapée devait être la commune du lieu, donc zéro résultat.

## Décisions d'Arthur

- « Présence et position » activée d'office, une fois. Risque RGPD signalé, accepté.
- Apple : « Bientôt ». Google seul actif.
- Scooter 50 / Sans permis : complet, backend et app.
- Protection pluie : verrou dès 15 km/h, aucune détection de pluie.
- Lieux absents d'OSM : `search/extra.sql`.
- Consommation : 6,5 L/100 km par défaut, curseur.

## Hypothèses, chiffres non mesurés

- 6,5 L/100 km : valeur de départ choisie, pas mesurée.
- Coût affiché : distance × consommation × prix médian des stations proches du départ. Péages non chiffrés.
- X2 Fitness Park : position approchée (point BAN de l'avenue, sans numéro).
- Statuts gardés : Admin / Invité / Membre.

## Reste à faire

- Codemagic build 30. Tests téléphone des builds 28 à 30.
- Scooter : exclusion des voies rapides (`motorroad=yes`) pas encore prouvée.
- Android : aucun lot de cette session porté. Parité cassée : choix Rapide / Éco, multi-arrêts, stationnement,
  Scooter 50 / Sans permis, protection pluie, menu, coût du trajet, carburant, consommation.
- Fusion réalisée le 05/10 en avance rapide, sans réécriture. Branche cloud conservée.
- Fitness Park : numéro exact à fournir, pour corriger X2.
- Leclerc : si OSM corrigé, retirer X1 d'`extra.sql`.
- Rebuild dimanche 11/10 03:30 : premier passage avec `extra.sql`. Vérifier `/var/lib/eona-signs/rebuild.log`.
- Valhalla : phases 1 à 5 livrées ; collecte continue. Phases 6 à 8 non implémentées dans moteur.
  Statut et preuves : `backend/VALHALLA-ETAT.md`. Aucune nouvelle phase 2 à lancer.
