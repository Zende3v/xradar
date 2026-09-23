# API EONA — doc pour la webapp d'administration

Tout passe par HTTP/JSON. Pas d'accès à la base, pas de SSH : la webapp demande et envoie des
données, le backend fait le reste.

Base : `https://api.lrda-mercuriale.uk` (Cloudflare Tunnel vers le VPS).
Santé : `GET /health` — sans authentification, donne l'état du serveur, les compteurs et le nombre
d'utilisateurs en ligne.

---

## 1. Authentification

La webapp est publiée sur **https://ground-truthh.lovable.app**. Chaque admin s'y connecte avec
**son propre compte EONA** (rôle `admin`). Pas de secret partagé dans le navigateur.

| Identité | Comment | Ce qu'elle ouvre |
|---|---|---|
| Compte admin (la webapp) | `POST /api/accounts/login` avec `"web": true` → `token`, puis `Authorization: Bearer <token>` | Tout : signalements, bugs, limites, signalisation, comptes, journal |
| `ADMIN_TOKEN` (scripts seulement) | `x-admin-token: <token>` | Pareil. Reste sur le VPS, jamais dans un navigateur |

```bash
curl -s -X POST https://api.lrda-mercuriale.uk/api/accounts/login \
  -H 'content-type: application/json' \
  -d '{"identifier":"admin@exemple.fr","password":"...","web":true}'
# → { "account": {...}, "token": "...", "expiresInS": 43200 }
```

- Avec `"web": true`, la session dure **12 heures** (au lieu de 90 jours pour les apps). À
  l'expiration, les appels répondent `401` : la webapp renvoie vers l'écran de connexion.
- `POST /api/accounts/logout` (avec le Bearer) ferme la session tout de suite.
- `GET /api/admin/me` dit qui est connecté : `{ actor: { kind: "account", id, name } }`, ou
  `401` si la session n'est pas celle d'un admin. À appeler au démarrage de la webapp.
- Une session admin ne vaut que tant que le compte est admin et non banni : retirer le rôle ou
  bannir coupe l'accès au prochain appel. Un `deviceId` seul n'ouvre **jamais** les droits admin.
- Un admin ne peut ni se bannir, ni se retirer le rôle admin, ni supprimer son propre compte par
  l'API admin (`400`) : personne ne s'enferme dehors par erreur.

**CORS** : le backend répond aux navigateurs venant de `https://ground-truthh.lovable.app` et des
aperçus `https://*.lovable.app` (un seul niveau de nom, en HTTPS). Réglé côté serveur dans
`WEBAPP_ORIGINS`. Toute autre adresse est refusée par le navigateur.

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
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://api.lrda-mercuriale.uk/api/reports/near?lat=46.6&lon=2.5&radius=900000'
```

Un signalement contient : `id`, `type`, `lat`, `lon`, `createdAt` et `expiresAt` (millisecondes,
pas ISO), `confirmations`, `contradictions`, `reporters`, `reporterRole`, `direction`, `bearing`,
`course`, `street`, `side`, `score`, `impactM`, `persistent`.

### La console : tous les signalements, avec leur auteur

| Besoin | Appel | Droits |
|---|---|---|
| Lister, filtrer, paginer | `GET /api/admin/reports` | admin |
| Un signalement en détail | `GET /api/admin/reports/:id` | admin |

Filtres de `GET /api/admin/reports`, tous facultatifs :

| Paramètre | Valeurs |
|---|---|
| `status` | `live`, `expired` (temps écoulé), `denied` (la foule a dit « plus là »), `removed` (un admin), ou `closed` pour tous sauf `live` |
| `type` | un type de signalement (`radar_mobile`, `traffic_jam`…) |
| `author` | l'`id` d'un compte : tout ce qu'il a signalé |
| `from`, `to` | période de création, ISO ou millisecondes |
| `bbox` | `ouest,sud,est,nord` en degrés |
| `limit` | 50 par défaut, 200 au plus |
| `before` | pagination : le `createdAt` du dernier reçu |

Du plus récent au plus ancien. La réponse donne `next` : la valeur à passer en `before` pour la
page suivante, ou `null` quand il n'y en a plus.

Chaque signalement a les champs de l'app, plus `status`, `closedAt`, `lastReportedAt` et
`author` : `{ id, username, role, banned }`, ou `null` si le compte a été supprimé depuis.
`GET /api/admin/reports/:id` ajoute `voices` : les voix comptées par sorte (`reported`, `merged`,
`confirm`, `deny`).

**La plaque d'une voiture radar ne sort jamais**, même pour un admin : l'app promet au
conducteur qu'elle reste privée.

L'historique des fermés est gardé **90 jours** après leur fermeture, puis effacé. Supprimer un
signalement (`DELETE /api/reports/:id`) le passe en `removed` : il reste dans l'historique, et
l'action est écrite au journal (section 6 bis).

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

| Besoin | Appel | Droits |
|---|---|---|
| Chercher, filtrer, paginer | `GET /api/admin/accounts` | admin |
| Un compte | `GET /api/admin/accounts/:id` | admin |
| Changer `role`, `banned`, `displayName` | `PATCH /api/admin/accounts/:id` | admin |
| Créer | `POST /api/admin/accounts` | admin |
| Supprimer | `DELETE /api/admin/accounts/:id` | admin |

Paramètres de `GET /api/admin/accounts`, tous facultatifs :

| Paramètre | Valeurs |
|---|---|
| `q` | cherche dans le pseudo, le nom affiché, l'email et l'id (sans accents ni majuscules) |
| `role` | `guest`, `client`, `admin` |
| `banned` | `true` ou `false` |
| `signup` | méthode d'inscription : `email`, `google` |
| `sort` | `lastSeen` (défaut), `created`, `username` |
| `order` | `desc` (défaut) ou `asc` |
| `limit`, `offset` | 50 par défaut, 200 au plus ; `offset` pour la page |

Réponse : `total` (combien correspondent en tout), `count` (dans cette page), `offset`, `next`
(l'`offset` de la page suivante, ou `null`), `meta` (les compteurs) et `accounts`.

Chaque création, changement de rôle, bannissement, débannissement et suppression est écrit au
journal (section 6 bis).

### Ce qui existe vraiment sur un compte

- **Identité** : `id`, `username`, `displayName`, `email`, `emailVerified`, `avatarUrl`, `role`
  (guest / client / admin), `banned`.
- **Appareil** : `deviceId` (UUID tiré par l'app, gardé dans le Keychain / le stockage privé),
  `platform` (`ios` ou `android`), et `app` : `model`, `osVersion`, `appVersion`, `locale`,
  `region`, tels que le système les donne à l'inscription et à la connexion. Jamais
  d'identifiant publicitaire.
- **Inscription** : `signupMethod` (`email` ou `google`), `providers[]` (comptes liés), et le
  parrainage utilisé s'il y en a un.
- **Dates** : `createdAt`, `lastSeenAt` (dernière ouverture de session depuis l'appareil),
  `trialEndsAt`, `subscriptionEndsAt`.
- **Statistiques cumulées** : `stats.tripCount`, `distanceMeters`, `driveDurationSeconds`,
  `alertsTraversed`, `reportsDeclared`, `reportsConfirmed`.
- **Trajets** : `trips[]`, les 200 derniers — `startedAt`, `fromLabel`, `toLabel`,
  `distanceMeters`, `durationSeconds`, `alertsCount`, `topSpeedKmh`, `plannedSeconds`, `stops`,
  `stoppedSeconds`, `events` (alertes rencontrées par type).
- **Quotas du jour** (invités) : `usage.reports`, `usage.trips`.
- **Parrainage** : `referralCodes[]`.

### Qui est en ligne, et où

| Besoin | Appel | Droits |
|---|---|---|
| Qui a l'app ouverte maintenant | `GET /api/live/online` | admin |
| Où un compte est passé | `GET /api/live/positions?accountId=..&from=..&to=..` | admin |

`/online` donne, par compte : `username`, `role`, `platform`, `inTrip`, `lastSeenAt`, et
`lat`/`lon`/`speedKmh`/`positionAt` **pour les conducteurs qui partagent leur position** (null
pour les autres). Un compte reste « en ligne » 90 secondes après son dernier signe de vie.

Le détail du mécanisme (ce que l'app envoie, ce qui est écrit, quand c'est effacé, ce que ça pèse)
est dans [POSITIONS.md](POSITIONS.md).

`/positions` donne la trace d'un compte, du plus ancien au plus récent, 5000 points au maximum.
Les positions sont effacées au bout de 30 jours (`POSITION_KEEP_DAYS`), et la suppression d'un
compte efface les siennes immédiatement.

### Ce qui n'existe toujours pas

- **L'adresse IP** d'un compte : elle n'est pas enregistrée.
- **La durée d'une session vue comme un événement** (début, fin, appareil). Ce qui existe :
  `stats.appDurationSeconds`, le temps cumulé passé dans l'app, et `lastActiveAt`.
- **Les coordonnées de départ et d'arrivée des trajets enregistrés** : un trajet garde des
  libellés de lieux (« Ma position » → « Rennes »), pas de points. La trace, elle, est dans
  `/api/live/positions`.

### Confidentialité — ce que les réglages de l'app changent

La webapp doit partir du principe que certaines données n'arrivent pas, et ne pas présenter un
vide comme une anomalie.

| Réglage dans l'app | Par défaut | Effet quand il est éteint |
|---|---|---|
| Statistiques de conduite | activé | Aucun trajet, aucune statistique envoyés : `stats` et `trips` restent vides |
| Présence et position | **désactivé** | Aucune position : le compte n'apparaît pas sur la carte de `/api/live/online` et n'a aucune trace |
| Temps d'utilisation | **désactivé** | `stats.appDurationSeconds` n'augmente pas ; `lastActiveAt` reste tenu à jour dès que l'app appelle le serveur |
| Aide au trafic partagé | activé | Aucun ralentissement remonté : moins de bouchons détectés automatiquement |
| Suggestions de trajets | activé | Ne change rien côté serveur : les destinations récentes restent sur le téléphone |

---

## 5 bis. Partage de trajet

Un conducteur ouvre un lien sur le trajet qu'il conduit, quelqu'un d'autre le suit en direct. Tout
vit en mémoire, rien n'est écrit.

| Besoin | Appel | Droits |
|---|---|---|
| Ouvrir un lien | `POST /api/trips/share` `{toLabel?, destination?, route?}` | compte |
| Envoyer sa position | `PATCH /api/trips/share` `{lat, lon, bearing?, remainingM?, etaS?, arrived?}` | compte |
| Son lien en cours | `GET /api/trips/share` | compte |
| Arrêter | `DELETE /api/trips/share` | compte |
| Suivre un trajet | `GET /api/trips/shared/:token` | compte |

Un conducteur n'a qu'un lien à la fois : en rouvrir un remplace le précédent, qui cesse de
fonctionner. Le lien meurt 15 minutes après l'arrivée, 6 heures au plus après sa création.

`GET /t/:token` est la page publique du lien : elle ouvre l'app et ne montre aucune position
elle-même. Elle répond 410 quand le partage est terminé.

Le suiveur reçoit `name`, `toLabel`, `destination`, `route`, `position`, `remainingM`, `etaAt`
et `arrived` — jamais la vitesse, jamais l'historique. `/health` compte les partages en cours
dans `trips.shares`.

---

## 5 ter. Trajet en groupe

Jusqu'à cinq conducteurs vont à la même adresse, chacun depuis son point de départ et sur son
propre itinéraire. Comme le partage simple : tout en mémoire, rien écrit.

| Besoin | Appel | Droits |
|---|---|---|
| Créer un groupe | `POST /api/trips/group` `{destination, toLabel?, route?}` | compte |
| Rejoindre | `POST /api/trips/group/join` `{code, route?}` | compte |
| Son groupe | `GET /api/trips/group` | compte |
| Envoyer sa position | `PATCH /api/trips/group/me` `{lat, lon, bearing?, speedKmh?, remainingM?, etaS?, progress?, distanceM?, route?, started?, arrived?, sharing?, observable?}` | compte |
| Suivre un participant | `GET /api/trips/group/member/:id` | compte du groupe |
| Quitter | `POST /api/trips/group/leave` | compte |
| Annuler le trajet | `DELETE /api/trips/group` | créateur |
| Ouvrir / révoquer le lien | `POST` / `DELETE /api/trips/group/link` | créateur |
| Retirer un observateur | `DELETE /api/trips/group/observers/:id` | créateur |
| Observer le groupe | `GET /api/trips/group/watch/:token` | compte |

Ce qui sort du serveur dépend de ce que chacun accepte :

- `sharing` à `false` : plus de position, plus de vitesse, plus d'avancement pour ce
  participant, ni pour le groupe ni pour le lien. Il reste listé avec son pseudo et son état ;
- `observable` à `false` : il disparaît du lien public, le groupe continue de le voir ;
- l'itinéraire ne part que dans `GET /api/trips/group/member/:id` et dans la vue observateur,
  et il est effacé dès l'arrivée du participant.

L'app n'envoie une position qu'une fois le trajet vraiment commencé (conducteur sur
l'itinéraire) ; `started: true` fait passer « en route » même sans partager sa position. Après
l'arrivée, plus rien n'est écrit.

Quitter (`leave`) : le groupe continue ; si c'est l'hôte, le rôle passe au participant suivant
encore en route (`hostName` dit qui mène). Un hôte seul qui quitte annule. Annuler (`DELETE`)
met `cancelled: true` : chaque participant le lit une fois, puis `GET /api/trips/group` répond
`null` — reprendre la même destination repart d'un groupe neuf.

États d'un participant : `invited` (pas encore parti), `driving`, `arrived`, `left`. Un
téléphone muet depuis 45 s passe `online: false` en gardant sa dernière position ; il ne quitte
le groupe qu’après 15 minutes de silence.

Le classement est pris dans l'ordre des arrivées et figé à la fin du trajet (tous arrivés ou
partis, ou annulation du créateur) : `ranking` donne `name`, `rank`, `durationS`, `distanceM`.

`GET /g/:token` est la page publique du lien de groupe ; elle ouvre l’app et répond 410 quand le
trajet est fini. `/health` compte les groupes et leurs participants dans `trips.groups` et
`trips.members`.

---

## 6. Signalisation (mapper)

Lecture, comme avant :

- `GET /api/signs/near?lat=..&lon=..&radius=..` — panneaux autour d'un point (jusqu'à 120 km,
  8000 éléments).
- `POST /api/signs/route` `{coordinates: [[lon,lat],...]}` — ce qu'un conducteur rencontre le long
  d'un itinéraire, dans l'ordre.
- `GET /api/signs/limit?lat=..&lon=..&bearing=..` — la limite sous le conducteur.

Écriture, réservée aux admins :

| Besoin | Appel |
|---|---|
| Voir les corrections | `GET /api/signs/edits?status=applied\|conflict\|reverted` |
| Corriger | `POST /api/signs/edits` `{op, targetId?, kind?, value?, course?, lat?, lon?, note?}` |
| Changer une correction | `PATCH /api/signs/edits/:id` |
| Annuler | `DELETE /api/signs/edits/:id` |

Trois opérations (`op`) :

- **`add`** — un panneau qu'OpenStreetMap n'a pas. Demande `kind`, `lat`, `lon`, et `value` pour
  une limite (`speed_sign`), `course` pour le sens auquel il s'adresse.
- **`edit`** — corriger un panneau existant : `targetId` (son `id` renvoyé par `/near`) plus ce
  qui change.
- **`hide`** — le panneau n'existe pas sur le terrain : `targetId` seul.

`kind` vaut `traffic_signals`, `stop`, `give_way`, `crossing`, `roundabout`, `construction`,
`no_entry`, `level_crossing` ou `speed_sign`.

```bash
curl -s -X POST https://api.lrda-mercuriale.uk/api/signs/edits \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"op":"add","kind":"stop","lat":48.1173,"lon":-1.6778,"course":90,"note":"vu sur place"}'
```

### Comment ça tient dans le temps

Les corrections vivent dans leur propre table (`crowd.sign_edit`), séparée de la donnée
OpenStreetMap. Deux moments :

1. **Tout de suite** — la correction est écrite et appliquée à la signalisation publiée dans la
   même transaction. Les apps la voient à la requête suivante, sans attendre.
2. **À chaque reconstruction** (dimanche, à partir d'un extrait OSM frais) — les corrections sont
   rejouées sur la donnée neuve avant publication. La table des corrections n'est jamais
   modifiée par la reconstruction, et une reconstruction qui échoue s'arrête avant de publier :
   la signalisation en service et les corrections restent intactes.

Rattachement d'une correction à son panneau : l'identifiant d'un panneau est calculé à partir de
ce qu'il est (type, valeur, position au pas de 25 m, orientation), donc il survit en général à
une reconstruction. Sinon, le panneau est recherché par ce qu'il était : même type, même valeur,
à moins de 30 m, orienté à moins de 45° près.

**Si le panneau a disparu, ou si plusieurs candidats se ressemblent, rien n'est appliqué.** La
correction est conservée et passe en `status: "conflict"`, avec `conflict` qui dit pourquoi. La
console doit lister ces conflits : c'est un humain qui tranche — refaire la correction sur le
bon panneau, ou l'annuler.

---

## 6 bis. Journal des actions admin

`GET /api/admin/audit` — ce que les admins ont fait, du plus récent au plus ancien.

| Paramètre | Valeurs |
|---|---|
| `actor` | l'`id` du compte admin |
| `action` | voir ci-dessous |
| `targetType`, `targetId` | `account`, `report`, `speedLimit`, `bug`, et l'id visé |
| `limit` | 50 par défaut, 200 au plus |
| `before` | pagination : le `at` de la dernière ligne reçue |

Actions écrites : `account.create`, `account.role`, `account.ban`, `account.unban`,
`account.update`, `account.delete`, `report.remove`, `speedLimit.remove`, `bug.status`.

Une ligne : `id`, `at` (ISO), `actor` (`{ id, name }` — `id` à `null` pour l'`ADMIN_TOKEN`),
`action`, `target` (`{ type, id }`) et `detail` (ce qui a changé, par ex.
`{ "banned": { "from": false, "to": true } }`). Jamais d'email ni de mot de passe dedans.

Gardé **un an**, puis effacé. Les corrections de signalisation ont déjà leur propre historique
(section 6), et les codes de parrainage aussi (dans le compte).

---

## 7. Ce qui manque encore

Fait depuis la première version de cette doc : liste globale des signalements avec filtres,
historique des fermés et auteur (section 2), recherche et pagination des comptes (section 5),
connexion de la webapp par compte admin au lieu du `ADMIN_TOKEN` partagé, et journal des
actions (section 6 bis).

Reste ouvert, à demander si besoin :

1. **Remettre en ligne** un signalement supprimé par erreur (aujourd'hui, il reste dans
   l'historique mais ne revient pas).
2. **Droits plus fins** qu'« admin » (un modérateur qui voit mais ne supprime pas de compte).

---
## 8. Limites et bonnes manières

- Les itinéraires (`/api/route`) sont plafonnés : 10 par minute et 120 par heure et par compte, et
  un budget quotidien global. Une webapp n'a normalement pas à les appeler.
- Les rapports de bug sont limités à 3 par heure et 10 par jour et par compte.
- `/api/reports/near` sur toute la France n'est pas paginé : à demander toutes les quelques
  minutes, pas en continu. Pour la console, préférer `/api/admin/reports` et
  `/api/admin/accounts`, paginés.
- Ne jamais afficher `deviceId`, `email` ou `passwordHash` à quelqu'un qui n'est pas admin. Le
  backend ne renvoie jamais le mot de passe, même haché, mais il renvoie l'email dans la vue admin.
