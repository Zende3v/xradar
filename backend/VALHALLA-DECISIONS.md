# Valhalla : journal des décisions

Atelier par thèmes pour remplacer ORS par un moteur Valhalla auto-hébergé, avec le trafic temps
réel et une ETA précise. Point de départ : l'audit du 24/09/2026 et les conditions de
[VALHALLA.md](VALHALLA.md). Chaque entrée donne la décision, sa raison et sa date. Rien n'est codé
avant la validation du plan (`PLAN-VALHALLA.md`, écrit à la fin de l'atelier).

---

## Cadre fixé par Arthur (24/09/2026)

- **Priorités** : une ETA ultra précise et des itinéraires plus malins.
- **Options gardées** : éviter les péages, les autoroutes, les ferries et les bouchons, en
  exclusion stricte, comme aujourd'hui.
- **Trafic** : TomTom gratuit (environ 2 500 requêtes par jour), données data.gouv, données EONA
  (bouchons signalés, ralentissements partagés). Le moteur doit économiser TomTom.
- **Hébergement** : le VPS actuel (Debian 13, 8 cœurs, 16 Go de RAM, 154 Go libres).
- **Couverture** : la France seule.
- **ORS** : uniquement en secours (Valhalla en panne ou trop lent, trajet hors de France).
- **Contrat `/api/route`** inchangé pour les apps déjà installées ; tout ajout de champ est
  additif.

---

## Thème 1 : objectifs mesurables (24/09/2026)

### Constaté (lecture seule)

- Le serveur n'a que 3 trajets, dont 2 avec `plannedSeconds` (réel / prévu : 0,65 et 1,59).
  Aucune statistique n'est possible ; l'historique local des téléphones n'est pas lisible depuis
  le serveur.
- Les données de `TripRecorder` (mêmes règles sur Android et iOS) ne suffisent pas pour mesurer
  l'ETA :
  - aucun drapeau « arrivé » : un trajet arrêté en route compte comme un trajet fini ;
  - `startedAt` est le moment où la destination est choisie, pas le vrai départ ;
  - `plannedSeconds` est figé à la première route (ORS, sans trafic), jamais mis à jour après un
    recalcul ou une bascule ;
  - `stoppedSeconds` est le total des arrêts de 10 s ou plus : feux, bouchons et pauses
    mélangés ;
  - aucune ETA n'est enregistrée en cours de route.
- Le backend ne journalise que les erreurs et `/faster` : recalculs, demi-tours et latence ne
  sont pas mesurés. `tripCheck.isNew` distingue déjà un nouveau trajet d'un recalcul.
- Référence externe : Google suit la part des trajets dont l'erreur d'ETA dépasse un seuil
  (« negative ETA outcome ») et annonce plus de 97 % de trajets justes, sans publier le seuil
  ([blog Google](https://blog.google/products-and-platforms/products/maps/google-maps-101-how-ai-helps-predict-traffic-and-determine-routes/),
  [arXiv 2108.11482](https://arxiv.org/abs/2108.11482)). Le chiffre n'est donc pas comparable tel
  quel.

### Décisions

**D1.1 — Ce qu'on mesure pour l'ETA.** L'erreur entre l'arrivée annoncée et l'arrivée réelle, au
vrai départ puis à 25 %, 50 % et 75 % du trajet. Indicateur principal : la part des trajets hors
seuil. Suivis aussi : l'erreur médiane et le biais (ETA optimiste ou pessimiste).
*Raison* : la priorité est une ETA qui se corrige en route ; mesurer seulement le départ ne le
verrait pas.

**D1.2 — Seuil d'une bonne ETA.** Erreur au plus de 10 % de la durée ou de 2 min (la plus grande
des deux valeurs), et jamais plus de 8 min.
*Raison* : 10 % seuls laisseraient passer 18 min d'erreur sur un trajet de 3 h, ce qui n'est pas
« ultra précis » ; 2 min évitent de juger le bruit des petits trajets.

**D1.3 — Cible chiffrée.** Fixée après 2 à 3 semaines de mesure de l'existant, pas avant.
*Raison* : la base actuelle compte 2 trajets ; une cible posée aujourd'hui serait inventée.

**D1.4 — Pauses retirées du calcul.** Une pause est un arrêt de 5 min ou plus hors d'un tronçon
ralenti (trafic connu de l'app, `RouteTraffic.slowed`) et hors d'un bouchon EONA signalé. Quand le
trafic n'est pas connu à ce moment-là, l'arrêt est marqué « incertain », pas « pause ». Le temps
des pauses est retiré de la durée réelle.
*Raison* : aucune ETA ne peut deviner un arrêt café ; un arrêt long dans un bouchon, lui, fait
partie de ce que l'ETA doit prévoir. `stoppedSeconds` ne permet pas d'isoler ces arrêts : il faut
de nouveaux champs.

**D1.5 — Trajets exclus de la mesure.** Seuls les trajets arrivés à destination comptent. Sont
exclus : les trajets arrêtés avant la destination et ceux dont le départ a été choisi à la main
(pas la position du conducteur).
*Raison* : on ne peut juger une arrivée annoncée que sur un trajet réellement conduit jusqu'au
bout, depuis la position réelle.

**D1.6 — Découpage des résultats.** Par durée de trajet (moins de 20 min, 20 à 60 min, plus
d'1 h) et par moment de la journée (heures de pointe ou non). On ne conclut sur une tranche
qu'à partir d'un nombre minimum de trajets, à mesurer (voir « Points ouverts »).
*Raison* : sans découpage, les nombreux petits trajets écrasent les longs, où l'erreur coûte le
plus.

**D1.7 — Sources des mesures.**
1. L'historique des trajets, avec des champs ajoutés (additifs) : arrivé ou non, départ réel,
   départ choisi à la main ou non, distance prévue, pauses et arrêts incertains, ETA à 0, 25,
   50 et 75 %, moteur utilisé, version de l'app, mode d'ETA (figée ou dynamique).
2. Un banc backend : environ 50 trajets France fixes, comparés à TomTom avec trafic comme
   référence. Les horaires tournent (matin, midi, soir, nuit) au lieu d'une heure fixe, dans le
   même budget TomTom (budget confirmé au thème 3).

*Raison* : l'historique donne la vérité terrain mais avance lentement avec le volume actuel ; le
banc donne tout de suite des comparaisons régulières. La version de l'app, le mode d'ETA et le
moteur permettent de comparer avant et après chaque changement.

**D1.8 — Ce qu'on mesure pour des itinéraires plus malins.**
- Demi-tours proposés au recalcul (cible : 0).
- Gain réel des bascules `/faster` : le gain annoncé contre l'arrivée réelle.
- Écart avec la meilleure route TomTom, sur le banc.
- Latence p50 et p95 de `/api/route`, recalculs par trajet, aucune violation des évitements
  stricts.
- Itinéraires bizarres : petites routes ou traversée de villages pour moins d'une minute gagnée,
  ou nettement plus de km que TomTom pour un temps proche. Comptés sur le banc (les km par classe
  de route viennent de `signs.road.highway` dans PostGIS) et dans la checklist de trajets de
  l'équipe.

*Raison* : ce sont les défauts qu'un conducteur remarque ; tous sont mesurables avec le journal
backend, le banc ou la checklist.

**D1.9 — Mesurer avant de changer.** Les champs de D1.7 sont ajoutés dans les deux apps et dans
le backend avant tout passage à Valhalla : c'est la première phase du futur plan.
*Raison* : sans mesure de l'existant, on ne pourra pas prouver que Valhalla et l'ETA dynamique
font mieux.

**D1.10 — Périmètre.** Ces mesures ne concernent que les conducteurs qui ont activé
« Statistiques de conduite ». La ligne correspondante de la politique de confidentialité est
traitée au thème 8.
*Raison* : c'est déjà la condition pour qu'un trajet soit enregistré.

### Points ouverts

- **Nombre minimum de trajets par tranche** : à mesurer. Méthode proposée : calculer l'intervalle
  de confiance du taux de bonnes ETA de chaque tranche (intervalle de Wilson) et ne conclure que
  lorsque sa largeur passe sous une marge à choisir.
- **Heures de pointe** : définition à fixer (jours, heures, heure de Paris).
- **« Nettement plus de km »** : seuil fixé au thème 4, après un premier passage du banc.
- **Format de la checklist de trajets** : thème 7.
- **Politique de confidentialité** : thème 8.

---

## Thème 2 : ETA dynamique (24/09/2026)

### Constaté (lecture seule)

- Les apps ont déjà la progression du conducteur sur la route (`RoutePath.match` →
  `alongMeters`, à chaque fix) et les retards placés sur la route (`sections[fromM, toM, delayS]`
  de `/api/traffic/route`, déjà lus).
- Le backend envoie aussi la durée de chaque étape (`steps[].durationS`) et le temps TomTom avec
  trafic (`travelS`) : les apps les ignorent.
- TomTom est appelé toutes les 120 s pendant un trajet et à chaque nouvelle route, soit environ
  30 appels par heure et par conducteur, avec la route entière (pas le reste). Le cache du backend
  (60 s) est indexé sur les points exacts de la route : deux conducteurs ne le partagent jamais.
- TomTom peut rendre, dans la même requête, les temps sans trafic, historique et avec incidents
  en direct (`computeTravelTimeFor=all`,
  [doc Calculate Route](https://docs.tomtom.com/routing-api/documentation/tomtom-maps/calculate-route)).
  Non utilisé aujourd'hui.
- Le partage et le trajet en groupe calculent déjà une ETA qui avance (`durationS` × part
  restante) : une deuxième formule, différente de celle du HUD.
- `settingsStore` (`accounts/settings.js`) garde des réglages modifiables en direct par un admin,
  enregistrés dans `data/settings.json` avec l'historique de qui a changé quoi.

### Décisions

**D2.1 — Formule de l'ETA.** Temps restant = base restante + retards des bouchons encore devant.
- Base = temps TomTom avec trafic (`travelTimeInSeconds`) moins les retards des bouchons listés
  dans ses sections. Le trafic diffus (heures de pointe) reste donc dans la base. Elle est répartie
  le long de la route selon les durées d'étapes du moteur.
- On y ajoute les retards des bouchons encore devant le conducteur (TomTom, EONA, data.gouv),
  au prorata de la longueur restante pour celui qu'on traverse.
- Recalculée à chaque fix GPS, sans requête. Sans réponse TomTom : durées du moteur seules.
- Test d'acceptation : au départ, l'ETA vaut le temps TomTom avec trafic plus les retards EONA.

*Raison* : partir du temps sans trafic ferait perdre le ralentissement diffus des heures de
pointe, que TomTom ne liste pas comme bouchon ; retirer puis rajouter les bouchons listés permet
de les faire disparaître de l'ETA une fois dépassés.

**D2.2 — Recalage TomTom sur événement, avec un plafond.** Un appel à chaque nouvelle route,
quand la progression réelle s'écarte de la progression prévue au-delà d'un seuil, quand un bouchon
est dépassé, et sinon après un délai maximal qui dépend du temps restant. Seul le reste de la
route est envoyé. Les seuils sont calibrés au thème 3, par mesure.
*Raison* : l'ETA se recalcule localement à chaque fix ; TomTom ne sert qu'à la corriger, ce qui
économise le quota.

**D2.3 — Pas de correction selon le rythme du conducteur, pour l'instant.** Le biais est d'abord
mesuré (par conducteur, par type de route) avec les champs du thème 1, puis on décide sur chiffres.
*Raison* : un facteur de correction sans mesure serait une hypothèse, et le volume actuel est
faible.

**D2.4 — Calcul dans les apps.** Logique pure partagée : Kotlin dans `core`, port Swift dans
`EonaCore`, avec les mêmes tests. Le HUD, le partage et le groupe utilisent la même formule.
L'arrivée affichée ne change que si l'écart atteint 1 min. Le backend n'ajoute que des champs.
*Raison* : l'ETA bouge à chaque fix sans réseau, continue quand le réseau coupe, et une seule
formule évite deux ETA différentes pour le même trajet.

**D2.5 — Phasage.** L'ETA dynamique et le trafic data.gouv arrivent dans la même phase, après la
bascule sur Valhalla. La phase de mesure (D1.9) reste la première et mesure l'ETA figée actuelle.
*Raison* : décision d'Arthur ; la mesure de l'ETA figée sert de point de départ pour juger le
gain.

**D2.6 — data.gouv derrière un interrupteur serveur, comparé sur tous les trajets.**
- L'interrupteur vit dans `settingsStore` : activable et coupable depuis l'admin, sans nouvelle
  version des apps.
- L'app calcule à chaque trajet les deux ETA (avec et sans data.gouv), affiche celle que dit
  l'interrupteur et enregistre les deux. La comparaison porte sur 100 % des trajets, dans le même
  trafic.
- Conséquences à tenir au thème 3 : les retards data.gouv s'ajoutent en dernier, seulement pour
  ce qu'ils coûtent en plus de TomTom et EONA (comme `withCrowd` aujourd'hui), pour que les
  retirer donne exactement l'ETA sans data.gouv. Les apps déjà installées ne savent pas trier les
  sources : elles ne doivent recevoir les retards data.gouv que si l'interrupteur est actif (les
  nouvelles apps le signalent dans leur requête, champ additif).

*Raison* : un tirage par trajet ou par période compare des trajets différents ; calculer les deux
ETA sur chaque trajet compare dans les mêmes conditions, sans diviser le volume.

**D2.7 — Chaque retard porte sa source.** Chaque section de `/api/traffic/route` a un champ
`source` (`tomtom`, `crowd`, `datagouv`), et plus seulement celles d'EONA. L'app enregistre avec
chaque trajet les sources utilisées, en plus du moteur, de la version de l'app et du mode d'ETA
(D1.7).
*Raison* : c'est ce qui permet de calculer l'ETA sans une source donnée, et de savoir ce qui
apporte quoi.
