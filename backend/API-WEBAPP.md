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

**CORS** : le backend répond aux navigateurs venant des adresses listées dans `WEBAPP_ORIGINS`
(côté serveur, séparées par des virgules). Donne ton adresse exacte à Arthur, scheme compris —
`https://console.exemple.fr`. Toute autre adresse est refusée par le navigateur.

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

- **L'adresse IP**, le **modèle d'appareil** et la **version de l'app** par compte. Le modèle et la
  version n'apparaissent que dans un rapport de bug, où l'app les joint volontairement.
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
  -H "x-admin-token: $ADMIN_TOKEN" -H 'content-type: application/json' \
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

## 7. Ce qui manque encore

À faire valider par Arthur :

1. **Liste globale des signalements** avec filtres, pagination, historique des fermés et auteur
   visible pour les admins.
2. **Pagination et recherche** sur `/api/admin/accounts` : aujourd'hui tout arrive d'un coup.
3. **Compte de service** pour la webapp plutôt que le `ADMIN_TOKEN` partagé, avec ses propres
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
