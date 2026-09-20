# Positions des conducteurs — comment ça marche

De la case cochée dans l'app jusqu'à la ligne effacée en base. Tout ce qui suit est vrai du code
en service ; rien n'est prévu « pour plus tard ».

---

## 1. Rien ne part sans un oui

Deux réglages, tous les deux **éteints par défaut**, dans Menu → Confidentialité :

| Réglage | Ce qu'il autorise |
|---|---|
| **Présence et position** | Le décompte des apps ouvertes, et la position + la vitesse du conducteur |
| **Temps d'utilisation** | Le cumul du temps passé dans l'app sur le compte |

Les deux éteints, l'app **ne fait aucun appel** : pas de requête vide, pas de ping. C'est la
condition `privacy.presence || privacy.usageTime` avant l'envoi, côté iOS comme Android.

---

## 2. Ce que l'app envoie

Une seule requête, toutes les 30 secondes, tant que l'app tourne (écran éteint compris, le suivi
GPS étant déjà actif pour les alertes) :

```
POST /api/live/presence
Authorization: Bearer <jeton de session>
{ "inTrip": true, "lat": 48.1173, "lon": -1.6778, "speedKmh": 52, "session": true }
```

- `inTrip` : un trajet est en cours ou non. Toujours envoyé.
- `lat`, `lon`, `speedKmh` : **seulement** si « Présence et position » est actif. Sinon les champs
  sont absents, pas à zéro.
- `session: true` : **seulement** si « Temps d'utilisation » est actif.

L'app n'envoie jamais un champ que le conducteur n'a pas autorisé. C'est décidé au moment de
construire le corps de la requête, pas filtré plus loin.

---

## 3. Ce que le serveur en fait

Dans l'ordre, dans `src/live/routes.js` :

1. **Le décompte** (`liveStore.touch`) : en mémoire, jamais sur disque. Un compte est « en ligne »
   90 secondes après son dernier signe de vie. C'est ce qui alimente `live.online` et
   `live.inTrip` dans `/health`.
2. **Le temps passé** (`accountStore.recordActivity`) : l'écart entre deux pings s'ajoute à
   `stats.appDurationSeconds` du compte, **si** `session: true`. Un écart de plus de 90 secondes
   ne compte pas : c'est une nouvelle session, pas du temps passé. La date de dernière activité
   (`lastActiveAt`), elle, est toujours mise à jour — elle sert à la gestion des comptes.
3. **La position** (`positionStore.add`) : une ligne dans `crowd.position`, seulement si `lat` et
   `lon` sont là et valides.

```sql
CREATE TABLE crowd.position (
    id         bigserial PRIMARY KEY,
    account_id text NOT NULL,
    at         timestamptz NOT NULL DEFAULT now(),
    geom       geometry(Point, 4326) NOT NULL,
    speed_kmh  smallint,
    in_trip    boolean NOT NULL DEFAULT false
);
```

Une ligne par ping. Pas de trajet, pas de session, pas d'appareil : juste qui, quand, où, à
quelle vitesse, en trajet ou non.

---

## 4. Ce que la console voit

Deux routes, **réservées aux administrateurs** (compte admin ou `ADMIN_TOKEN`) :

```bash
# Qui a l'app ouverte en ce moment
curl -s -H "x-admin-token: $ADMIN_TOKEN" https://api.lrda-mercuriale.uk/api/live/online

# Où un compte est passé
curl -s -H "x-admin-token: $ADMIN_TOKEN" \
  "https://api.lrda-mercuriale.uk/api/live/positions?accountId=<id>&from=2026-09-20T00:00:00Z"
```

`/online` croise deux sources : le décompte en mémoire (qui est là) et la dernière position en
base (où). Un conducteur qui ne partage pas sa position apparaît quand même dans la liste, avec
`lat`, `lon`, `speedKmh` et `positionAt` à `null`. La console doit donc traiter ce cas, ce n'est
pas une panne.

`/positions` rend la trace du plus ancien au plus récent, 5000 points au maximum par demande.

---

## 5. Quand ça s'efface

| Donnée | Durée |
|---|---|
| Décompte en ligne | 90 secondes, en mémoire |
| Positions | **30 jours**, puis suppression automatique |
| Temps cumulé et dernière activité | Tant que le compte existe |

La purge tourne toute seule : au maximum une fois par heure, en marge d'une écriture, elle
supprime tout ce qui dépasse `POSITION_KEEP_DAYS` (30 par défaut). Pas de cron, rien à surveiller.

**Suppression d'un compte** : ses positions sont **supprimées**, pas anonymisées — une position
dit où quelqu'un était, l'anonymiser ne protège personne. Vrai pour la suppression par
l'utilisateur comme par un administrateur.

**Réglage coupé en cours de route** : l'app arrête d'envoyer immédiatement. Les positions déjà
enregistrées vivent leurs 30 jours. Pour les effacer tout de suite :

```sql
DELETE FROM crowd.position WHERE account_id = '<id>';
```

---

## 6. Combien ça pèse

Un ping toutes les 30 secondes, soit **120 lignes par heure et par conducteur**, environ
200 octets avec les index.

| Usage | Par jour | En régime stable (30 jours) |
|---|---|---|
| 4 conducteurs × 2 h | 0,2 Mo | ~6 Mo |
| 100 conducteurs × 1 h | 2,5 Mo | ~75 Mo |
| 1000 conducteurs × 1 h | 25 Mo | ~750 Mo |

Le disque du VPS fait 460 Go et les écritures plafonnent à 33 par seconde dans le dernier cas :
PostgreSQL ne s'en aperçoit pas. Si le service grossit vraiment, deux leviers avant de changer
quoi que ce soit : baisser `POSITION_KEEP_DAYS`, ou espacer les pings dans l'app.

---

## 7. Ce que ça change côté légal

La politique de confidentialité le dit noir sur blanc (section 8) : fonction éteinte par défaut,
positions rattachées au compte, 30 jours, consultables par les administrateurs depuis un outil
interne, ni vendues ni montrées aux autres conducteurs. Toute modification du délai, de l'usage
ou des personnes qui y accèdent doit être répercutée dans ce document **avant** d'être mise en
service.
