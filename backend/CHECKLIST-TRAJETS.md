# Checklist trajets — équipe EONA

Trajets tests de l'équipe et points à vérifier (D7.4). Phase 1 du plan Valhalla : on **mesure**
ORS, l'ETA au prorata et les itinéraires actuels. Chaque phase met cette liste à jour (D8.4).

## Avant de partir

**Reprise 26/09** : aucun trajet réel phase 1 exploitable enregistré à 15:10.
Validation au prochain trajet Arthur ; préparation technique peut continuer avant.
Finir trajet jusqu'à arrivée automatique, statistiques activées, puis vérifier présence mesures côté serveur.
Version actuelle suffit : aucun nouvel APK ni IPA demandé pour cette validation.

- App à jour (Android versionCode 4 et plus, iOS build de la phase 1).
- Menu ▸ Confidentialité : « Statistiques de conduite » **activé**. Sinon aucun trajet envoyé.
- Compte connecté. Destination choisie depuis sa position réelle (sauf test « départ à la main »).
- Téléphone fixé. Rien à toucher en roulant : notes par un passager ou à l'arrêt.

## Trajets à faire

| # | Trajet | But | Attendu dans les mesures |
|---|---|---|---|
| 1 | Court en ville (< 20 min) jusqu'à destination | ETA en ville, feux | `arrived: true`, gardé dans le rapport ETA |
| 2 | Moyen (20-60 min), périurbain | ETA, petites routes | gardé |
| 3 | Long (> 1 h) sur autoroute à péage | ETA longue, bascules `/faster` | gardé, `fasterCount` si bascule |
| 4 | Même trajet, option « Éviter les péages » | évitement strict | aucun péage emprunté |
| 5 | Même trajet, option « Éviter les autoroutes » | évitement strict | aucune autoroute empruntée |
| 6 | Trajet en heure de pointe (lun-ven 7-10 h ou 16-20 h) | ETA dans le trafic | tranche « pointe » |
| 7 | Trajet avec une pause café de plus de 5 min hors bouchon | règle des pauses (D1.4) | `pausedSeconds` ≥ 300, pause retirée |
| 8 | Sortir exprès de la route (rue parallèle) | recalcul, demi-tour | `recalcCount` ≥ 1 ; noter tout demi-tour proposé |
| 9 | Trajet arrêté avant la destination (« Arrêter la navigation ») | exclusion | `arrived: false`, exclu du rapport |
| 10 | Départ choisi à la main (autre point que sa position) | exclusion | `manualStart: true`, exclu du rapport |
| 11 | Trajet qui traverse un bouchon signalé ou TomTom | ETA + arrêts en bouchon | arrêt long compté bouchon, pas pause |
| 12 | Près d'une frontière (Lille, Strasbourg, Annemasse, Menton) | route qui reste en France | pas de passage à l'étranger inutile |

Refaire 1 à 3 plusieurs fois, à des heures différentes. Plus il y a de trajets arrivés, plus le
rapport ETA dit quelque chose.

## Ce qu'on vérifie

**ETA (arrivée affichée)**
- Au départ, noter l'heure d'arrivée affichée. À l'arrivée, comparer.
- Bonne ETA : écart ≤ 10 % du temps restant, au moins 2 min de marge, jamais plus de 8 min (D1.2).
- ETA qui saute d'un coup (plus de 2-3 min) sans raison visible : à signaler.

**Itinéraires bizarres (D1.8)**
- Petites routes ou traversée de village pour gagner moins d'une minute.
- Beaucoup plus de km qu'un trajet évident, pour un temps proche.
- Détour inutile, boucle, sortie puis retour sur la même route.

**Demi-tours**
- Demi-tour proposé au départ ou juste après un recalcul, alors qu'on roule dans le bon sens.
  Cible : 0.

**Évitements**
- Option péages / autoroutes active : un seul péage ou une seule autoroute emprunté = défaut
  bloquant. Cible : 0 violation.

**Bascules « itinéraire plus rapide »**
- Gain annoncé contre arrivée réelle : la bascule valait-elle le coup ?

## Signaler

Menu ▸ **Signaler un bug**, catégorie **Navigation**.

- **À l'arrêt seulement** : en roulant (≥ 1,5 m/s) le formulaire reste fermé et affiche
  « Disponible à l'arrêt ». Se garer, ou demander au passager une fois arrêté.
- Joint automatiquement : moteur, version de la carte, trajet en cours ou dernier (destination,
  tracé, distances). Rien à recopier.
- Écrire : ce qui cloche, où (rue, sortie, village), ce qui était attendu. Une ligne suffit.
  Exemple : « Demi-tour proposé à la sortie du parking, rue X, alors que la route continue
  tout droit. »
- Un signalement par problème. Trajet fini : le signaler juste après, le dernier trajet est
  encore en mémoire (jusqu'à la fermeture de l'app).

## Après

- Rien à faire : trajet envoyé à la fin (statistiques de conduite actives).
- Arthur lit le rapport ETA (`node bin/eona-eta-report.js`, README §7 bis) et les signalements
  dans la console (API-WEBAPP.md §3).
