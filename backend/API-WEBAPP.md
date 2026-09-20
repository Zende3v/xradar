# API XRadar — doc pour la webapp d'administration

Tout passe par HTTP/JSON. Pas d'accès à la base, pas de SSH : la webapp demande et envoie des
données, le backend fait le reste.

Base : `https://api.lrda-mercuriale.uk` (Cloudflare Tunnel vers le VPS).
Santé : `GET /health` — sans authentification, donne l'état du serveur, les compteurs et le nombre
d'utilisateurs en ligne.

---

## 1. Authentification

Deux identités différentes, à ne pas confondre.

| Identité | Comment | Ce qu'elle ouvre |
|---|---|---|
| Compte admin | `POST /api/accounts/login` → `token`, puis `Authorization: Bearer <token>` | Signalements, bugs, limites de vitesse, plus tout ce qu'un compte normal peut faire |
| `ADMIN_TOKEN` | `x-admin-token: <token>` (ou `Authorization: Bearer <token>`) | Tout ce qui précède **plus** `/api/admin/accounts` |

```bash
curl -s -X POST https://api.lrda-mercuriale.uk/api/accounts/login \
  -H 'content-type: application/json' \
  -d '{"identifier":"admin@exemple.fr","password":"..."}'
# → { "account": {...}, "token": "..." }
```

**Le `ADMIN_TOKEN` ne doit jamais partir dans le navigateur.** Il donne la création et la
suppression de comptes. La webapp a donc besoin d'un petit serveur à elle (Node, PHP, peu importe)
qui garde le token et relaie les appels. Le navigateur parle à ce serveur, jamais directement au
backend XRadar.

**CORS : le backend n'envoie aucun en-tête `Access-Control-Allow-Origin` aujourd'hui.** Un appel
`fetch` depuis une page servie sur un autre domaine sera bloqué par le navigateur. Deux sorties :
passer par le serveur de la webapp (recommandé, et obligatoire pour le token admin), ou demander à
Arthur d'ajouter CORS pour le domaine de la webapp.

Erreurs : `401` pas authentifié, `403` pas les droits (ou compte banni), `404` inconnu, `429`
trop de demandes, `503` base ou service momentanément indisponible. Le corps est toujours
`{ "error": "..." }`.

---

## 2. Signalements des usagers

Ce que les conducteurs signalent : radar mobile, zone de contrôle, accident, bouchon, objet sur la
route, route glissante, travaux, véhicule arrêté, visibilité, contresens, voiture radar, caméra.

| Besoin | Appel | Droits |
|---|---|---|
| Voir ceux qui sont vivants | `GET /api/reports/near?lat=..&lon=..&radius=..` | compte |
| Supprimer (modération) | `DELETE /api/reports/:id` | admin |
| Confirmer / infirmer | `POST /api/reports/:id/confirm` · `/deny` | compte |
| En créer un | `POST /api/reports` `{type, lat, lon, ...}` | compte (certains types demandent `client` ou `admin`) |

`radius` est en mètres, jusqu'à 1 000 000 (1000 km) : une seule demande centrée sur la France
ramène tout ce qui est vivant, jusqu'à 5000 signalements.

```bash
curl -s -H "x-admin-token: $ADMIN_TOKEN" \
  'https://api.lrda-mercuriale.uk/api/reports/near?lat=46.6&lon=2.5&radius=900000'
```

Un signalement contient : `id`, `type`, `lat`, `lon`, `createdAt` et `expiresAt` (millisecondes,
pas ISO), `confirmations`, `contradictions`, `reporters`, `reporterRole`, `direction`, `bearing`,
`course`, `street`, `side`, `score`, `impactM`, `persistent`.

**Manques pour une vraie console de modération**, tous à ajouter côté backend :

- aucune liste globale, donc pas de pagination ni de filtre par type ou par statut ;
- pas d'historique des signalements fermés (expirés, infirmés, supprimés) — ils restent en base,
  aucune route ne les sert ;
- **l'auteur n'est jamais renvoyé** (ni son compte, ni la plaque d'une voiture radar) : la réponse
  est volontairement anonyme pour les apps. Modérer un compte qui abuse demande donc une nouvelle
  route réservée aux admins.

---

## 3. Rapports de bug

| Besoin | Appel | Droits |
|---|---|---|
| Lister | `GET /api/bugs?status=new\|progress\|resolved&before=<ISO>` | admin |
| Changer le statut | `PATCH /api/bugs/:id` `{ "status": "progress" }` | admin |
| En envoyer un | `POST /api/bugs` `{category, description, steps?, app{}}` | compte |

50 par page, du plus récent au plus ancien. Pour la page suivante, renvoyer `before` = le
`createdAt` du dernier reçu.

Un rapport contient : `id`, `status`, `category` (map, navigation, alerts, account, other),
`description`, `steps`, `createdAt`, `author` (`username` + `role`, ou `null` si le compte a été
supprimé), `app` (`platform`, `version`, `os`, `model`).

Les rapports résolus de plus de 90 jours sont effacés automatiquement.

---

## 4. Limites de vitesse proposées par les conducteurs

| Besoin | Appel | Droits |
|---|---|---|
| Voir autour d'un point | `GET /api/speed-limits/near?lat=..&lon=..&radius=..` | compte |
| Détail, voix et historique | `GET /api/speed-limits/:id` | admin |
| Rejeter / arrêter d'appliquer | `DELETE /api/speed-limits/:id` | admin |

Une proposition passe de `pending` à `validated` quand assez de conducteurs disent la même chose.
Validée, elle s'applique par-dessus la donnée OSM pour toutes les apps.

---

## 5. Utilisateurs

`GET /api/admin/accounts` (ADMIN_TOKEN seulement) renvoie **tous** les comptes d'un coup, sans
pagination ni recherche. `?role=guest|client|admin` filtre. `GET /api/admin/accounts/:id` donne un
compte. `PATCH /api/admin/accounts/:id` change `role`, `banned`, `displayName`.
`DELETE /api/admin/accounts/:id` supprime.

### Ce qui existe vraiment sur un compte

- **Identité** : `id`, `username`, `displayName`, `email`, `emailVerified`, `avatarUrl`, `role`
  (guest / client / admin), `banned`.
- **Appareil** : `deviceId` (UUID tiré par l'app, gardé dans le Keychain / le stockage privé),
  `platform` (`ios` ou `android`).
- **Dates** : `createdAt`, `lastSeenAt` (dernière ouverture de session depuis l'appareil),
  `trialEndsAt`, `subscriptionEndsAt`.
- **Statistiques cumulées** : `stats.tripCount`, `distanceMeters`, `driveDurationSeconds`,
  `alertsTraversed`, `reportsDeclared`, `reportsConfirmed`.
- **Trajets** : `trips[]`, les 200 derniers — `startedAt`, `fromLabel`, `toLabel`,
  `distanceMeters`, `durationSeconds`, `alertsCount`, `topSpeedKmh`, `plannedSeconds`, `stops`,
  `stoppedSeconds`, `events` (alertes rencontrées par type).
- **Quotas du jour** (invités) : `usage.reports`, `usage.trips`.
- **Parrainage** : `referralCodes[]`.

### Ce qui n'existe pas, contrairement à ce qui a été demandé

- **La position des utilisateurs.** Aucune position n'est enregistrée, ni en direct ni en
  historique. `POST /api/live/position` existe encore pour les vieilles versions mais **jette** la
  position, et `GET /api/live/near` répond toujours une liste vide. Les trajets gardent seulement
  des libellés de lieux (« Ma position » → « Rennes »), pas de coordonnées.
- **La durée des sessions.** Rien ne mesure le temps passé dans l'app. Ce qui s'en approche :
  `stats.driveDurationSeconds`, le temps passé à rouler.
- **La liste des utilisateurs en ligne.** Le backend sait combien de comptes ont l'app ouverte et
  combien roulent (`/health` → `live.online`, `live.inTrip`), mais **qui** n'est pas exposé. C'est
  gardé en mémoire 90 secondes, jamais écrit sur disque.
- **L'adresse IP, le modèle d'appareil, la version de l'app.** Sauf dans un rapport de bug, où
  l'app les joint volontairement.

Tout ça peut s'ajouter, mais c'est un choix d'Arthur : ça change la politique de confidentialité
et le mot « anonyme » dans les réglages.

### Confidentialité — ce que les réglages de l'app changent

La webapp doit partir du principe que certaines données n'arrivent pas, et ne pas présenter un
vide comme une anomalie.

| Réglage dans l'app | Par défaut | Effet quand il est éteint |
|---|---|---|
| Statistiques de conduite | activé | Aucun trajet, aucune statistique envoyés : `stats` et `trips` restent vides |
| Présence anonyme | **désactivé** | Aucun ping de présence : le compte ne compte pas dans `live.online` |
| Aide au trafic partagé | activé | Aucun ralentissement remonté : moins de bouchons détectés automatiquement |
| Suggestions de trajets | activé | Ne change rien côté serveur : les destinations récentes restent sur le téléphone |

---

## 6. Signalisation (mapper)

C'est la partie **qui n'existe pas encore** côté API.

Ce qui existe, en lecture seule :

- `GET /api/signs/near?lat=..&lon=..&radius=..` — panneaux autour d'un point (jusqu'à 120 km,
  8000 éléments).
- `POST /api/signs/route` `{coordinates: [[lon,lat],...]}` — ce qu'un conducteur rencontre le long
  d'un itinéraire, dans l'ordre.
- `GET /api/signs/limit?lat=..&lon=..&bearing=..` — la limite sous le conducteur.

Aucune route n'ajoute, ne modifie ni ne supprime un panneau. Et surtout : **le schéma `signs` de
PostGIS est reconstruit entièrement chaque dimanche à partir d'un extrait OpenStreetMap**. Une
modification écrite directement dedans serait effacée à la reconstruction suivante.

La bonne façon de faire, et c'est déjà le modèle des limites de vitesse : une table de corrections
par-dessus (schéma `crowd`), que la lecture applique sur la donnée OSM. Il faut donc, à valider
avec Arthur :

1. une table `crowd.sign_edit` (ajout, modification, suppression d'un panneau, avec auteur, date,
   statut) ;
2. des routes `POST` / `PATCH` / `DELETE /api/signs/edits` réservées aux admins ;
3. la prise en compte de ces corrections dans `signs/postgis.js` (`near`, `route`, `limit`), comme
   `speed_limit_change` l'est déjà ;
4. la reconstruction hebdomadaire qui conserve la table de corrections.

---

## 7. À ajouter pour la webapp — récapitulatif

À faire valider par Arthur avant de coder quoi que ce soit côté backend :

1. **CORS** pour le domaine de la webapp (ou tout passe par le serveur de la webapp).
2. **Liste globale des signalements** avec filtres, pagination, historique des fermés et auteur visible pour les admins.
3. **Écriture de la signalisation** : table de corrections + routes admin (section 6).
4. **Pagination et recherche** sur `/api/admin/accounts` : aujourd'hui tout arrive d'un coup.
5. **Liste des comptes en ligne** et **durée de session**, si c'est vraiment voulu — décision de
   confidentialité, pas seulement technique.
6. **Compte de service** pour la webapp plutôt que le `ADMIN_TOKEN` partagé, avec ses propres
   droits et sa propre révocation.

---

## 8. Limites et bonnes manières

- Les itinéraires (`/api/route`) sont plafonnés : 10 par minute et 120 par heure et par compte, et
  un budget quotidien global. Une webapp n'a normalement pas à les appeler.
- Les rapports de bug sont limités à 3 par heure et 10 par jour et par compte.
- Les réponses volumineuses (signalements sur toute la France, liste des comptes) ne sont pas
  paginées : à demander toutes les quelques minutes, pas en continu.
- Ne jamais afficher `deviceId`, `email` ou `passwordHash` à quelqu'un qui n'est pas admin. Le
  backend ne renvoie jamais le mot de passe, même haché, mais il renvoie l'email dans la vue admin.
