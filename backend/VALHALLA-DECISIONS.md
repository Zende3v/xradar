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
