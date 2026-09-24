# Remplacer ORS par un moteur Valhalla auto-hébergé

Ce qu'un moteur Valhalla doit prendre en charge pour remplacer OpenRouteService (ORS) sans rien
perdre de ce qu'EONA fait aujourd'hui. Rien n'est codé : c'est la liste des conditions.

**Principe.** Les apps iOS et Android ne changent pas. Elles ne lisent que la route normalisée par
le backend :

```json
{
  "distanceM": 12345,
  "durationS": 900,
  "coordinates": [[lon, lat], ...],
  "steps": [{ "type", "modifier", "location": [lon, lat], "exit", "name", "distanceM", "durationS" }]
}
```

Tout se passe dans `backend/src/routing` (`ors.js`, `routes.js`, `faster.js`).

---

## 1. Ce qu'EONA demande à ORS, et l'équivalent Valhalla

| Usage EONA | ORS aujourd'hui | Valhalla |
|---|---|---|
| Itinéraire voiture A → B | `/v2/directions/driving-car/geojson` | `/route`, costing `auto` |
| Tracé complet, non simplifié : guidage calé sur la route, tracé « mangé », radars et panneaux le long, points d'appui envoyés à TomTom | `geometry_simplify: false` | shape complet ; en polyline, **précision 6** (1e6, pas 1e5) |
| Manœuvres : type, direction, n° de sortie, nom, distance, durée, point | `instructions`, `maneuvers` (codes 0–13 traduits par `ORS_TYPE`) | `format: "osrm"` : le backend a déjà `normalizeOsrmSteps`, et les apps comprennent tout le vocabulaire OSRM (`on ramp`, `off ramp`, `merge`, `rotary`, `end of road`…) |
| Éviter péages / autoroutes / ferries | `avoid_features` (exclusion stricte) | `exclude_tolls`, `exclude_highways`, `exclude_ferries` (strict). Pas `use_tolls: 0`, qui n'est qu'une préférence |
| « Éviter les bouchons » sur `/api/route` : jusqu'à 100 carrés de 500 m autour des bouchons signalés, puis nouvel essai sans eux si le trajet devient impossible | `avoid_polygons` (MultiPolygon) | `exclude_polygons` (liste d'anneaux) |
| Contournement intelligent `/api/route/faster` : couloir jusqu'à 300 carrés autour des bouchons | `avoid_polygons` | `exclude_polygons`, **limite de périmètre à relever** (§ 2) |
| Cap au départ (pas de demi-tour) et au point de retour sur la route | `bearings: [[cap, 45]]` | `heading` + `heading_tolerance` sur chaque point |
| 3 variantes à comparer | `alternative_routes { target_count: 3, weight_factor: 1.6, share_factor: 0.6 }` | `alternates: 3`. Pas d'équivalent à `weight_factor` / `share_factor` : le tri « même route à 90 % » de `faster.js` compense déjà |
| Plusieurs clés, budget du jour, `orsKeysMeta` dans `/health` | logique maison dans `ors.js` | inutile : l'état du moteur vient de `/status` |
| Réponses d'erreur | 404 `no route found`, 502 moteur, 503 budget épuisé | garder les mêmes codes : les apps savent déjà les lire |

## 2. Réglages Valhalla obligatoires (`valhalla.json` et requêtes)

- **`service_limits.max_exclude_polygons_length`**. La valeur par défaut est de l'ordre de 10 km de
  périmètre cumulé. Le couloir de `/faster` (300 carrés) dépasse largement 100 km de périmètre.
  Sans ce réglage, `/faster` et « Éviter les bouchons » échouent.
- **`service_limits.max_alternates` ≥ 3** (2 par défaut).
- **`service_limits.auto.max_distance`** : assez grand pour les longs trajets.
- **`roundabout_exits: false`** dans la requête au format OSRM : sinon des étapes « exit
  roundabout » s'ajoutent, et les apps ne les connaissent pas. L'autre option est de les filtrer
  côté backend.
- **Recherche du point de départ** (`radius`, `search_cutoff`) : un départ depuis un parking ou un
  bâtiment doit trouver la route la plus proche.
- **Profil `auto` réglé comme en France** : `top_speed` 130, pénalités voies privées et « accès
  riverains » (`private_access_penalty`, `destination_only_penalty`). Les durées servent à l'heure
  d'arrivée, au « temps prévu » de l'historique et au tri des variantes (`baselineS + lostS` dans
  `faster.js`).
- **Nom de rue** : en format OSRM, prendre `name`, sinon `ref` (« A6 »), comme ORS le donnait.
- **Unités** : le format OSRM rend des mètres et des secondes. En format natif, Valhalla rend des
  kilomètres : convertir, ou demander les bonnes unités.

## 3. Données et hébergement

- **Couverture.** ORS couvre le monde entier. À décider : France seule, ou France + pays voisins
  pour les trajets frontaliers (extraits Geofabrik fusionnables avec osmium).
- **Construction des tuiles, lourde.** Ordres de grandeur à vérifier : France seule, environ 16 Go
  de RAM et 1 à 3 h ; Europe, 64 Go de RAM et plus, plusieurs heures. Le plus simple : construire
  sur une grosse machine, puis copier le `.tar` de tuiles sur le VPS. Servir les tuiles demande peu
  de RAM (lecture en mmap).
- **Base admins et fuseaux** (`valhalla_build_admins`, `valhalla_build_timezones`) : règles par
  pays et passages de frontière.
- **Mise à jour OSM régulière** : cron hebdomadaire (téléchargement du PBF, construction, bascule
  du `.tar` sans coupure, redémarrage). Sinon les routes neuves et les travaux vieillissent.
- **Assez de cœurs CPU** : `/faster` lance 3 à 4 calculs en parallèle à chaque vérification, en
  plus des recalculs de tous les conducteurs.
- **Accès réseau** : Valhalla écoute en local seulement, seul le backend l'appelle. Pas de clé.
- **Garder `guard.js`** (cache d'une minute, plafonds par compte) : il ne protège plus un quota,
  mais le CPU.
- **Délai d'attente côté backend** sur chaque appel : un long trajet européen avec beaucoup de
  polygones peut prendre plusieurs secondes.

## 4. Comportements à préserver

- **Départ et arrivée jamais exclus** : les polygones laissent déjà 500 m (`/api/route`) et 300 m
  (`/faster`) libres autour du départ et de l'arrivée. Valhalla échoue si un point tombe dans une
  zone exclue.
- **Nouvel essai sans les bouchons** quand la route devient impossible (déjà dans `routeViaORS`).
- **Concaténation détour + reste de la route** (`withRest` dans `faster.js`) : suppose des étapes
  `arrive` / `depart` en bout de chaque morceau, que le format OSRM fournit.
- **Chaîne de secours** : Valhalla, puis ORS (garder les clés pendant la transition), puis OSRM
  démo.
- **`/health` et la doc webapp** (`API-WEBAPP.md`) : remplacer le budget des clés ORS par l'état de
  Valhalla.

## 5. Ce qui ne bouge pas

- **TomTom** : temps avec trafic, chronométrage des variantes, sections ralenties. Il reçoit
  simplement la géométrie de Valhalla comme points d'appui.
- **Limitations de vitesse, radars et panneaux le long du trajet** : base PostGIS d'EONA.
- **Bouchons des conducteurs** (`traffic/crowd.js`) : inchangés.
- **Trajet partagé et trajet en groupe** : ils n'utilisent que la géométrie de la route.
- **Apps iOS et Android** : aucun changement.

## 6. Ce qu'on gagne

- **Plus de quota** (3000 itinéraires par jour aujourd'hui avec deux clés).
- **Plus de plafonds ORS** : variantes limitées à 100 km, zones évitées à 150 km. On pourra
  agrandir la fenêtre de `/faster`, bloquée à 85 km aujourd'hui (`WINDOW_MAX_M`).
- **Latence plus basse** : le moteur est sur la même machine.
- **Manœuvres plus fines** : bretelles, insertions et sorties arrivent telles quelles, sans passer
  par la table appauvrie `ORS_TYPE`.
- **Bonus possible** : envoyer aussi le cap du conducteur lors des recalculs hors route (seul
  `/faster` l'envoie aujourd'hui), pour éviter les demi-tours proposés.

## 7. À vérifier au moment de brancher

Les noms de paramètres et les valeurs par défaut cités ici n'ont pas été vérifiés contre une
version précise de Valhalla. Les confirmer sur la version installée, en particulier :
`max_exclude_polygons_length`, `max_alternates`, `exclude_tolls` / `exclude_highways` /
`exclude_ferries`, `roundabout_exits`, `shape_format` en format OSRM, et le comportement de
`alternates` combiné à `exclude_polygons`.
