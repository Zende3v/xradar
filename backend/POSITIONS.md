# Positions des conducteurs — comment ça marche

De la case cochée dans l'app jusqu'à la ligne effacée en base. Tout ce qui suit est vrai du code
en service ; rien n'est prévu « pour plus tard ».

---

## 1. Réglages actuels (05/10/2026)

Deux réglages dans Menu > Confidentialité :

| Réglage | Ce qu'il autorise |
|---|---|
| **Présence et position** | Le décompte des apps ouvertes, et la position + la vitesse du conducteur |
| **Temps d'utilisation** | Le cumul du temps passé dans l'app sur le compte |

Présence activée par défaut. Migration l'active une fois : iOS build 28, Android lot de parité du 05/10.
Migration réactive aussi un ancien refus. Refus après migration conservé.
Temps d'utilisation activé sans préférence enregistrée. Choix existant conservé, sans réactivation forcée.

Les deux éteints, l'app **ne fait aucun appel** : pas de requête vide, pas de ping. C'est la
condition `privacy.presence || privacy.usageTime` avant l'envoi, côté iOS comme Android.

---

## 2. Ce que l'app envoie

Depuis le 28/09 (Android 1.0.1 (8), iOS 1.0.0 (6)) : une requête toutes les 30 secondes quand
l'app est à l'écran, ou pendant un trajet (arrière-plan compris). Hors trajet en arrière-plan :
rien. Plus une dernière requête `closing` quand on quitte l'app hors trajet.

```
POST /api/live/presence
Authorization: Bearer <jeton de session>
{ "inTrip": true, "lat": 48.1173, "lon": -1.6778, "speedKmh": 52, "session": true }
```

- `inTrip` : un trajet est en cours ou non. Toujours envoyé.
- `lat`, `lon`, `speedKmh` : **seulement** si « Présence et position » est actif, **et** seulement
  pendant un trajet ou dans la requête `closing`. Sinon les champs sont absents, pas à zéro.
- `session: true` : **seulement** si « Temps d'utilisation » est actif.
- `closing: true` : l'app est quittée hors trajet. Dernière position connue, où l'app a servi.

Chaque champ dépend du réglage actuel, lors de construction de la requête.
Couper présence arrête nouvelles positions ; positions déjà enregistrées gardent conservation prévue, sauf suppression du compte.
Dernier point peut rester visible dans `/online` pendant 90 secondes, puis coordonnées nulles.

---

## 3. Ce que le serveur en fait

Dans l'ordre, dans `src/live/routes.js` :

1. **Le décompte** (`liveStore.touch`) : en mémoire, jamais sur disque. Un compte est « en ligne »
   90 secondes après son dernier signe de vie. C'est ce qui alimente `live.online` et
   `live.inTrip` dans `/health`. `closing: true` le retire tout de suite.
2. **Le temps passé** (`accountStore.recordActivity`) : l'écart entre deux pings s'ajoute à
   `stats.appDurationSeconds` du compte, **si** `session: true`. Un écart de plus de 90 secondes
   ne compte pas : c'est une nouvelle session, pas du temps passé. La date de dernière activité
   (`lastActiveAt`), elle, est toujours mise à jour — elle sert à la gestion des comptes.
3. **La position** (`positionStore.add`) : une ligne dans `crowd.position`, seulement si `lat` et
   `lon` sont là et valides, **et** si `inTrip` ou `closing`. Les anciennes apps envoient une
   position à chaque ping : hors trajet, le serveur la jette. Une ligne `in_trip = false` est donc
   une fermeture d'app : la dernière position connue du compte.

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

Un ping toutes les 30 secondes **en trajet seulement** (plus une ligne par fermeture d'app), soit
au plus **120 lignes par heure de trajet et par conducteur**, environ
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
