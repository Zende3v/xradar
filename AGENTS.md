# EONA — Projet et règles

État vérifié : **20 octobre 2026**. Produit : **EONA**, ancien nom XRadar.

Deux références uniquement : ce fichier et [EXPLOITATION.md](EXPLOITATION.md).
Ce fichier décrit produit, architecture, limites réelles et règles de travail.
EXPLOITATION décrit Git, builds, production, sauvegardes et déploiement.
Codex charge ce fichier, puis lit EXPLOITATION. `CLAUDE.md` importe les deux pour Claude, sans contenu dupliqué.
Dépôt iOS : `AGENTS.md` de renvoi seulement (Arthur 18/10), sans état ; contexte ici. Reprise rapide : §7, point ouvert 10.
Anciens documents restent consultables dans historique Git.

## 1. Travail avec Arthur

- Arthur décide périmètre, arbitrages produit et livraison ; validation fonctionnelle sur téléphone.
- Arthur seul autorise production : déploiement, installation, redémarrage, push hors règle.
- Parité stricte Android/iOS : mêmes règles, mêmes champs JSON, logique dans `core/` et EonaCore.
- **Mode bridge v0.4.0 (19/10, demande Arthur) : ChatGPT/Codex pilote, Claude codeur exclusif.** Remplace mode 15/10. Config : EXPLOITATION §1.
- ChatGPT : demandes, vue d'ensemble, architecture, contrats, parité Android/iOS, décisions, périmètre.
- ChatGPT : plan, découpage, intégration par revue, vérifications proportionnées, état, réponse finale à Arthur.
- **Toute écriture de code passe à Claude via `bridge_delegate`.** Tests, correctifs et changements minuscules compris.
- Codex n'écrit jamais de code. Claude indisponible : tâche bloquée, aucun secours de codage par Codex.
- Exception Arthur 19/10 : Codex solo pour avatars, descriptions confidentialité, inscription vérifiée et ancien test recherche. Retour Claude après ce lot.
- Exception Arthur 20/10 : Codex solo pour panneau arrivée iOS (DAD), code et livraison Git. Retour Claude après ce lot.
- Claude exécute contrat reçu. Signale ambiguïtés. Rend fichiers exacts, vérifications réelles, risques.
- Claude ne décide aucun nouveau chantier. Aucune redélégation. Décision manquante : blocage rendu.
- Délégation : une racine par chantier et dépôt ; scopes disjoints ; critères explicites ; chaque diff relu.
- Aucune boucle, aucune tâche doublonnée. Interruption : reprendre même enfant, jamais remplaçant.
- Commits, push iOS, build Android : exécutés par Claude seulement si contrat le demande.
- Continuité début session : lire ces deux références, mémoire bridge utile, tâches bridge pertinentes.
- Mémoire bridge : namespace `development`, `context_id` `eona`, branch, version et epoch `null` pour décisions communes.
- Y garder demande, critères, décisions, prochain point de reprise. Aucune donnée personnelle ni secret.
- État métier de ces références : mis à jour seulement après fait réel. Longue spec : artefact bridge.
- Scopes bridge contractuels : aucune sandbox OS garantie. Rester dans scope déclaré.
- Économie de tokens jamais au détriment de la qualité : code réel, cas limites, vérifications et questions utiles gardés.
- Français, skill **caveman ultra** pour réponses, textes, commentaires, commits et documents.
- Phrases courtes, vingt mots maximum, une idée par phrase. Termes techniques exacts.
- Clarté prioritaire pour sécurité, actions irréversibles et procédures sensibles.
- Question courte seulement si décision manque. Donner options et recommandation.
- Rapports courts : résultat, blocage, point de reprise précis.
- Tests automatiques seulement pour correction ou risque concret. Aucune campagne systématique.
- Aucun émulateur ni capture demandée pour validation. Arthur teste sur téléphone.
- Revue ciblée sur contrat ou risque concret. Aucun double audit.
- Aucun chiffre inventé. Séparer code présent, production vérifiée et validation téléphone.
- **VPS : lecture seule libre. Chaque déploiement, installation ou redémarrage exige nouvel accord explicite d'Arthur.**
- Root/backend : commit puis push `main` (Arthur 19/10) ; aucun empilement local. iOS : commit et push.
- Ne pas commiter secrets, artefacts, `.claude/`, `.mcp.json` ou `.bridge/` sans accord.

## 2. Repos et architecture

| Périmètre | Chemin Windows | Remote | Branche de référence |
|---|---|---|---|
| Android + backend | `C:\Users\usr\Documents\x_radar` | `git@github.com:Zende3v/xradar.git` | `main` (Arthur 19/10) ; ancienne `feature/mini-player-musique` |
| iOS | `C:\Users\usr\Documents\xradar_ios` | `git@github.com:Zende3v/xradar_ios.git` | `main` |
| Déploiement backend | `C:\Users\usr\Documents\x_radar_eco` | même dépôt backend | HEAD détaché `5c1627d` ; lots 1, 1b, budget HERE, 2 et 4 déployés. Trafic `270d0b2` déployé depuis `x_radar`, pas ce worktree |

Android : Kotlin, Jetpack Compose sur mesure, MapLibre, tuiles Stadia. Package `com.eona.app`.
Logique métier commune dans `core/`, portée vers iOS avec mêmes règles et champs JSON.

iOS : SwiftUI, Swift 6, iOS 26, MapKit, projet généré par XcodeGen.
`Packages/EonaKit` contient EonaCore, EonaData et tests Swift Testing.
`EONA/Features` porte écrans ; services et adaptateurs restent séparés des vues.

Backend : Node 20 ESM, Express, PostgreSQL 17 et PostGIS.
Point d'entrée : [backend/src/index.js](backend/src/index.js). Montage HTTP : [backend/src/server.js](backend/src/server.js).
Configuration : [backend/src/config.js](backend/src/config.js). Secrets uniquement hors Git.

- `accounts/` : identité, droits, statistiques, quotas, bans, réglages.
- `routing/` : Valhalla, ORS, cache, mesures, comparaison et variantes.
- `traffic/` : HERE, événements publics, vitesses EONA et chronométrage.
- `search/`, `places/`, `fuel/` : recherche, services proches, prix et espèces.
- `signs/`, `radars/`, `reports/`, `speedlimits/` : signalisation et contributions.
- `trips/`, `live/` : groupes, partage et présence.
- `admin/`, `bugs/` : gestion EONA, modération et bugs privés.
- `backend/signalisation`, `backend/search`, `backend/valhalla` : imports et publication géographique.
- `backend/bin`, `backend/deploy` : exploitation, sauvegarde, services et cron.

Comptes et réglages persistent en JSON. Schémas PostgreSQL : `signs`, `crowd`, `routing`, `search`.
Sessions, groupes, partages et vitesses trafic restent en mémoire. Redémarrage vide ces états.

API publique : `https://api.lrda-mercuriale.uk`.
Webapp configurée : `https://ground-truthh.lovable.app` ; CORS limité aux origines autorisées.

## 3. Direction artistique durable

Partir composants existants et thème choisi. Réorganiser ergonomie ne change jamais direction artistique.
Écrans : chargement, erreur, vide, contenu. Textes courts, usage natif iOS.

- OLED `#0A0A0C`, verre dépoli, bordures `0,5 pt`, blanc `8 %`.
- Accents minéraux et ambre. Hiérarchie à gauche ; micro-libellés majuscules, lettres espacées.
- Aucun style Material, carte générique centrée, ombre lourde ou rayon uniforme `16`.
- Aucun dégradé violet/bleu générique, remplissage marketing ou explication permanente inutile.
- Réutiliser surfaces, boutons, lignes et espacements EONA. Ne pas inventer palette locale.
- Options réservées restent visibles. Interaction présente offre EONA+, sans masquer fonction.

**EONA+ : référence Telegram Premium validée sur iPhone le 05/10/2026.**
Conserver pastels mixtes, radar animé, icônes colorées, offres compactes et rythme natif.
Dégradés pastel autorisés sur emblème et action EONA+.
Navigation et action flottantes : aucun bandeau opaque, séparateur périphérique ou démarcation visible.
Suspendre animation hors écran, en arrière-plan et avec mouvements réduits.
Résumé visible ; comparatif complet dépliable. Aucune nouvelle composition sans demande.
Catalogue présente avantages prévus au présent, sans mentions de préparation.
**Documentation technique conserve leurs limites réelles. Catalogue ne prouve pas livraison.**

Thèmes : onze unicolores ; Pastels EONA+, Minéral, Opale, Crépuscule.
Onze unicolores et Opale gratuits. Trois autres palettes réservées EONA+ ou essai actif.
Opale par défaut sans choix enregistré. Conserver choix existant.
Expiration remplace seulement palette réservée par Opale ; véhicule réservé revient à Voiture.
Thème observable : interface, tracé et curseur actualisés immédiatement, sans reconstruire caméra ni navigation.
Ligne Thème compacte ; quinze choix dans feuille dédiée. Palettes sobres, jamais couleurs de sécurité.

## 4. Comptes, accès et fonctions

Rôles serveur : `guest|client|admin`. Accès : `free|trial|active|restricted`.
Rôle `client` ne prouve pas abonnement ; vérifier `hasPlus` et échéances.
Dans statistiques admin : Gratuit = invités + membres gratuits ; Client = EONA+ ; Admin séparé.

- Invité permanent : pseudo aléatoire, aucun mot de passe, aucune purge après sept jours.
- Nouvelles inscriptions email : membre créé après code validé. Anciens comptes non vérifiés gardent leur accès.
- Inscription en attente : invité utilisable, identité et données conservées. Conversion après preuve email ou Google vérifié.
- Création membre ou conversion invité : sept jours EONA+ offerts, même sans parrainage.
- Conversion conserve identifiant, trajets, statistiques et session.
- Expiration conserve compte ; retour gratuit automatique.
- Gratuit : quatre départs par jour, remise à zéro minuit Europe/Paris.
- EONA+, essai valide, admin autorisé : aucun plafond quotidien trajets.
- Taxi, groupe et curseur camion exigent EONA+. Musique reste gratuite.
- Aucun plafond quotidien signalements ; protections anti-abus restent actives.

| Fonction | État technique actuel |
|---|---|
| Rapide / Éco | Calculs réels `fastest\|shortest`, aperçu chronométré si trafic disponible |
| Carte inclinable | Geste natif à deux doigts ; recentrage conserve angle choisi |
| Recherches récentes | Quinze entrées locales ; distinctes de limite API recherche |
| Taxi | Profil Valhalla `taxi` ; accès selon données OSM et restrictions horaires |
| EONA Poids lourd | Curseur réservé ; routage actuel voiture, sans dimensions ni poids |
| Trajets intelligents / Perso | Non développé ; aucune consommation quotidienne dédiée |
| Feux en direct | Non développé ; aucun compte à rebours opérationnel |
| Groupe | Création/code, cinq participants maximum, positions et destination partagées |
| Achat abonnement | Non intégré ; aucun parcours StoreKit opérationnel |
| Apple Music / Spotify | Adaptateurs présents ; Spotify exige client public configuré |
| Lecteur système | Expérimental, API privée Apple, uniquement distribution adaptée |
| Gestion EONA | Accès admin, mesures, activité, comptes, suspension, bans et révocation |

Scooter et sans permis : Valhalla `motor_scooter`, 45 km/h, autoroutes exclues.
Exclusion générale `motorroad=yes` non prouvée. Aucun chronométrage HERE voiture pour ce profil.
Taxi en secours ORS utilise profil voiture. Ne pas garantir toutes voies de bus ou exceptions réglementaires.

### Stations proches

Disposition demandée par Arthur, implémentée iOS 54 : A1 sélecteur services inchangé ; A3 commandes ; A2 liste.
A3 : rangée unique, trois boutons compacts égaux, surface principale EONA commune.
Menu carburant, menu Plus proche/Prix, bascule Espèces directe avec cercle vide/coché.
A2 reprend lignes Bornes/Tabac ; prix à droite. Aucun compteur ni commande rayon.

Carburant, classement et espèces restent indépendants. Tri proximité conserve prix.
Plus proche par défaut après mise à jour ; choix Prix suivant persisté dans `fuelSortOrder`.
Rayons automatiques : 10, 25, 50 km, uniquement si zéro station admissible.
Station fermée ou sans prix reste résultat ; aucune extension pour obtenir prix artificiellement.
Arrêter sur résultat, erreur ou annulation. Ne jamais relâcher filtre espèces pendant secours.

Prix officiels : rafraîchissement backend dix minutes ; fraîcheur affichée iOS limitée à 96 heures.
Paiement : OSM `payment:cash=yes|only` accepté, `no` refusé, absence inconnue.
Filtre Espèces conserve seulement acceptation explicite. Aucun rapprochement enseigne ou supposition liée au guichet.
Import conserve tag paiement ; enrichissement par identifiant OSM exact seulement.
Acceptation déclarée ne prouve pas encaissement disponible actuellement.
Relevé du 08/10 : 160 oui, 105 non, 11 579 inconnues ; aucun décompte actuel supposé.

### Lecteur système

Video Lite : lecture/pause et Back/Skip avec playlist confirmés par Arthur sur iPhone.
Synchronisation réelle icône lecture/pause reste à valider ; aucune confirmation simulée.
Demande envoyée et état observé restent distincts. Événements directs prioritaires sur getters.
Métadonnées facultatives, sans avertissement orange.

Commandes privées : `MRMediaRemoteSendCommand`, piste ciblée via `MRMediaRemoteSendCommandToApp` si disponible.
Capacités annoncées : piste prioritaire, sinon intervalle natif affiché ; défaut quinze secondes.
Capacités inconnues : code actuel envoie avance temporelle, puis commande piste après 300 ms.
Cette stratégie expérimentale ne garantit pas résultat identique dans chaque lecteur.
Polling et notifications restent limités au lecteur visible, application au premier plan.
Titre, pochette et état tiers non lus lors essais iPhone 26.5.2 : infos nulles, PID zéro, commandes vides.
`systemMusicPlayer` ne fournit pas métadonnées Video Lite. Piste matérielle BLE refusée par Arthur.
Variante standard exclut adaptateur privé. Aucun entitlement privé ajouté.
App Store/TestFlight exigent désactivation de `EONA_EXPERIMENTAL_SYSTEM_MEDIA`.

## 5. Routage, trafic et ETA

Valhalla **3.9.0** principal France. ORS secours ; OSRM démo retiré.
Modes administrables : `ors|admins|all`. Production vérifiée : `all`.
Échec, délai dépassé, disjoncteur ou accroche hors couverture permettent secours ORS.
Contrat `/api/route` conserve structure GeoJSON ; nouveaux champs uniquement additifs.
Route : coordonnées `[lon,lat]`, distances mètres, durées secondes.
Champs selon demande : `engine`, `mapVersion`, `roads`, `preference`, `travelS`.
Depuis lot 4 : `durationS` et durées d'étapes = temps EONA ; `engineDurationS` brut ; `eonaTime` (§5, lot 4).
`mapVersion` décrit publication tuiles ; ne prouve pas date exacte données OSM.
Multi-arrêts : dix étapes maximum. Cap transmis en mouvement, marge 50 m, tolérance 45°.

Évitements péages, autoroutes et ferries stricts.
Polygones bouchons peuvent être retirés si route impossible ou recherche trafic échoue.
Rapide : variantes chronométrées symétriquement, maximum deux, gain minimal trois minutes et 5 %.
Éco et scooter : détour seulement pour fermeture ; variante plus courte.
Après secours, recherche `/faster` désactivée. Horizon 60 km, fenêtre 85 km ; élargissement non livré.

HERE Traffic v7 fournit flow/incidents. HERE Routing v8 Route Import chronomètre tracé EONA.
Vitesses EONA et événements publics complètent HERE. TomTom retiré le 30/09 ; banc historique conservé. Clé TomTom Freemium chargée 15/10, inutilisée avant lot 6.
Cache trafic partagé 60 secondes, appels identiques mutualisés.
Réservation budget persistée avant appel payant ; échec écriture interdit appel.
Économie HERE ne doit jamais masquer absence de trafic mesuré.
Validation Import par longueur ±10 % ; identité géométrique exacte non garantie. Taxi HERE reste voiture.

**Contrat HERE Base Plan (abonnement du 14/10/2026, carte enregistrée : HERE facture au-delà de la franchise).**
Franchises mensuelles : Real-Time Traffic 5 000 puis 2,33 €/1 000 ; Advanced Traffic 2 500 et Route Import 2 500 puis 4,66 €/1 000.
Budget déployé 15/10 (`9a8ea37`) : 0 € de dépassement ; comptage depuis 14/10 sur mois civil ET mois d'anniversaire (14 au 13).
Flow et incidents = Real-Time Traffic ; Import = Route Import. Ancien usage (ancienne clé, 1 317 requêtes) hors abonnement.
Quota par compte : 250 transactions HERE par jour UTC, tous usages ; cache non compté. Écart une/minute ETA et détours conservé.
Production 15/10 17:05 : `blocked: null`, dépassement estimé 0 €, nouvelle clé testée HTTP 200.
Réponse Import sans attente du flow lent (`435837c`) déployée avec ce lot.
Historique cumulé/hebdomadaire persistant ; semaine commence lundi minuit Paris. Historique ancien incomplet indiqué.

### Mesure ETA

Référence historique : arrivée annoncée au départ, jamais remplacée par dernière ETA pour calculer retard final.
Relevés départ et 25/50/75 %, pauses et arrêts incertains conservés.
Trajets interrompus signalés ; données brutes conservées.
Rapport exclut non-arrivée, départ manuel, destination modifiée et mesures absentes.
Comparer arrêts incertains conservés ou retirés ; ne pas confondre pause et embouteillage.
Tolérance : 10 % temps restant, minimum deux minutes, maximum huit minutes ; intervalle Wilson 95 %.
Bilan 06/10 : 62 trajets, 30 retenus, 19 dynamiques ; versions iOS 16–34 mélangées.
Erreur absolue médiane dynamique : départ 128 s ; relevé 75 % 39,27 s.
Aucun gain des builds suivants démontré par ce bilan. Nouveau bilan requis pour conclure.
Bilan 14/10 : 70 trajets lus, 32 retenus, tous iOS ; 30 exclus non arrivés ; 8 trajets depuis 06/10.
Départ : 47 % bonnes, erreur médiane +158 s, réel/annoncé 1,41. Aucune ETA trop longue au départ.
Vitesse moyenne réelle < 25 km/h : 1,82 ; ≥ 45 km/h : 0,98. Erreur concentrée premier quart : +90 s médian.
Sonde Valhalla 14/10 : vitesses Paris = limites taguées, ~3 s par feu, aucun effet de l'heure.
Aucun coefficient correcteur global, apprentissage historique ou injection trafic Valhalla livré.

### Plan ETA validé 15/10

Lots courts, feu vert Arthur à chaque lot. Aucun coût API en plus.
1. Collecte totale · 2. Rapport v2 · 3. Franchise HERE comptée · 4. Modèle EONA sans trafic.
5. ETA apps v2 · 6. Hub trafic + TomTom Freemium · 7. Arbitrage HERE/TomTom · 8. ETA dynamique.
9. Mémoire trafic + calibrage · 10. Vitesses EONA injectées dans Valhalla, plus tard.
**Séquence ajustée 20/10 (Arthur) : priorité heure d'arrivée fiable.** Lots 1–4 historiques ; 5–10 futurs, non achevés.
Ordre : fusion fiable des retards, puis mesures séparées par base et version, puis ETA apps v2, avant tout nouveau fournisseur.
Coefficients lot 4 provisoires. Aucune calibration sur un seul trajet.
Avant arbitrage : même tracé, fraîcheur comparable ; référence du plafond +25 % à préciser avant codage.
Avant TomTom : contrat réel de la clé et quotas vérifiés, aucun coût en plus. TomTom non activé.
Arbitrage décidé : accord ≤ max(60 s, 8 %) → moyenne ; désaccord → valeur lente, plafond +25 %.
Fermeture ou incident d'une seule source : retenu. Données EONA ajoutent retard, ne tranchent jamais.
Situations en table (voie × tranche × phase), jamais arbre de conditions. Valeurs de départ, puis calibrées.

**Réponses Arthur 15/10 :** collecte TOUT, tous comptes, statistiques coupées comprises ; tracé GPS tous comptes, conservé sans limite.
ETA prudente en cas de doute. Nouvelle clé HERE (Base Plan, carte, franchise seule) et clé TomTom Freemium installées.
HERE : 0 € de dépassement, quota 250 par compte et par jour (Arthur 15/10). Page confidentialité publiée par Codex, relue par Arthur.
Voiture garée au lancement : confirmé 18/10, ETA mesurée depuis le mouvement. App fermée à l'arrivée : un `interrupted` à 93 m (18/10).
**Question ouverte à reposer à Arthur :** coupe-t-il la navigation avant l'arrivée ? Deux `stopped` à 746 m et 1 253 m.

**Lot 1 collecte : backend déployé 15/10 ; iOS 55 lancé par Arthur ; Android 37 non validé.**
Instantané trajet toutes les 30 s ; app fermée : repris au lancement, envoyé `interrupted`.
File d'envoi persistée, 50 trajets max, liée au compte. Même `id` renvoyé : serveur ignore doublon.
Fin `near` : arrêt à 300 m ou moins de destination, compte arrivé.
Champs : `firstMoveAt`, `lastFixAt`, `endReason`, `endDistanceM`, `timeline` minute, `stopLog`, `etaChecks[].base/engineLeftS`.
Historique local sans relevés minute. Rapport ETA inchangé : nouveaux champs exploités au lot 2.

**Lot 1b : backend déployé 15/10 ; iOS 56 vu en base (trajets et tracés) ; Android jamais vu en base au 18/10.**
Tout trajet part au serveur, court ou long, tous comptes. Statistiques coupées : `countInStats: false`.
Base : `crowd.trip` (mesures, sans coordonnée) et `crowd.trip_trace` (tracé GPS par paquets). Aucune purge.
Historique JSON et totaux du compte : trajets de 500 m et 60 s ou plus, statistiques actives.
Tracé : un point toutes les 5 s `[t, lat, lon, kmh, précision, cap]`, paquets de 1 000, envoyé après le trajet.
Backend écrit par Codex (bridge), relu et testé par Claude.

**Lecture production 18/10 :** 9 trajets, 9 tracés, 3 213 points. Base départ : HERE 7, moteur 2.
Départ depuis 15/10 : erreur médiane +517 s, car attente avant premier mouvement 37–109 s (médiane 80 s).
Navigation lancée voiture garée : confirmé. Choix Arthur 18/10 : mesurer l'ETA depuis le mouvement.
HERE en 3 jours : 314 trafic, 167 Import, sous franchises. Import rejeté : notice 25, longueur 6, sur 189.
Retours route Arthur 18/10 : ETA beaucoup plus réaliste.

**Lot 2 rapport v2 : commit `9824a80`, déployé 18/10 03:57.** `bin/eona-eta-report-v2.js`, lecture seule `crowd.trip`.
Départ au premier mouvement, profils d'erreur, ratios réel/affiché et réel/moteur, versions séparées.
`--roads` : tracés recalés par Valhalla `trace_attributes`, temps par classe de route. Seuil ville ≥ 8 heuristique, remplacé au lot 4 par groupe × période.
`here.js` compte chaque code notice rejeté par Import (`notice:<code>`). Écrit par Codex, relu par Claude.

**Rapport v2 18/10 (`--since=2026-10-15 --roads`) :** 9 lus, 4 retenus (2 arrivés, 2 proches), échantillon faible.
Exclus : `stopped` 4 (fin à 746 m, 1 253 m, 2 385 m, 12 840 m), `interrupted` 1 à 93 m de destination.
Départ depuis mouvement : biais +370 s, 1 bonne sur 4. Attente au départ médiane 75 s.
Base HERE (2 trajets) : 50 % biais −34 s, 75 % +1 s, 2 bonnes sur 2. Réel/affiché médian 0,79.
Base moteur (2 trajets) : réel/moteur médian 3,10 ; un trajet < 25 km/h fini +2 039 s. Repli Valhalla très optimiste.
Arrêts : 8,5 par trajet, 30 % du temps, durée médiane 31 s ; arrêt initial médian 79 s (3 sur 4).
Routes (59 % du temps apparié) : primary réel/moteur 0,90, tertiary 0,81, motorway 1,48 (moteur 90 km/h).
Feux : primary 0,95/km, tertiary 1,83/km. Densité ≥ 8 classe tout en ville : heuristique inutilisable en Île-de-France.
Correction 18/10 : ratios ci-dessus = médianes par arête. Temps total réel ≈ 2× moteur sur autoroute (bouchons), ≈ 1,5× primary/tertiary.

**Lot 4 temps EONA sans trafic : commit `5c1627d`, déployé 18/10 04:45.** `routing/eona-time.js`, appliqué dans la façade (`engine.js`).
Toute route servie : principale, alternatives, détours, Valhalla ou ORS, voiture, taxi, 50cc. Apps inchangées.
Arêtes via Valhalla local `trace_attributes` (edge_walk ; ORS : map_snap), découpe 200 km, délai 1 500 ms.
Facteur sur temps de roulage, jamais < 1. Autoroute (motorway, trunk) 1,15 / pointe 1,40 ; grands axes (primary, secondary) 1,25 / 1,45 ; rues 1,20 / 1,35.
Feux : +12 s par feu Valhalla (≈ 15 s avec ses 3 s). Pointe = isPeak v1, à l'heure de passage estimée de chaque arête.
Pointe autoroute seulement à 90 km/h ou moins (urbaine) ; au-dessus, hors pointe. Choix Arthur : aucun gain sur autoroute.
Repli : facteur unique selon vitesse moyenne (≥ 70 autoroute, 40–70 grands axes, < 40 rues), sans feux.
JSON additif : `durationS` et étapes = temps EONA, `engineDurationS` brut, `eonaTime` {version, mode, signals}. Cache partitionné par période.
`faster.js` : gain et sélection restent HERE contre HERE. `shadow`/`bench` : brut. Journal : colonne `engine_duration_s` ajoutée au démarrage.
Contrôle Valhalla prod 18/10 (brut → EONA) : Châtelet–Versailles 34 → 44 min, pointe 50 ; République–Bastille 8 → 13–15 min ; Paris–Lyon 3 h 59 → 4 h 41. 26–150 ms.
Valeurs de départ, non calibrées (4 trajets). Calibrage au lot 9 via rapport v2 `--json` (ratios pondérés réel/EONA par groupe et période).
Base moteur brute (audit 19/10, non déployé) : étapes ajoutent `engineDurationS` ; relevés marqués `engineBasis: "raw"`. Relevés sans marqueur : base inconnue.

Production après lot 4 (18/10, compteurs depuis redémarrage 04:45) : `eonaTime` 23 routes `edges`, 0 repli, 0 échec, 8–150 ms.
Import HERE : 25 valides sur 26 ; 1 rejet `notice:violatedBlockedRoad`. Trajets reçus ensuite : 2, iOS 56 (`interrupted`, `stopped`).

**Fusion des retards (`270d0b2`, backend déployé 20/10 01:35, §7 point 14) :** `traffic/delay-fusion.js`, utilisé par `withCrowd` et `withDatagouv`.
Défaut corrigé : HERE soustrait de chaque zone séparément ; HERE 100 s, signalement 180 s, vitesses 140 s → 220 s, au lieu de 180 s.
Règle : sections connues intactes. Par intervalle : complément = max(0, densité max observations − somme densités connues).
Observations superposées : la pire, jamais leur somme. Zones disjointes additionnées. Complément limité à sa portion réelle.
Champs de l'observation dominante conservés. Arrondi cumulé : total exact arrondi.
Rang commun : densité décroissante, puis début, fin, retard croissants, source, niveau, nature absente d'abord.
Calcul : largeur finie positive et densité finie exigées. Complément exact ≤ 1e-9 s ignoré.
Marqueurs 0 s : fermeture toujours ; avec `keepFree`, retard absent, null ou zéro. Bornes finies, fin ≥ début.
Fermeture à retard positif : marqueur entier à 0 s, plus compléments localisés. Intervalle inversé rejeté, jamais retourné.
Entrées invalides : aucun temps ajouté. iOS `TrafficParts.extra` : même contrat, mêmes cas de test (build 61, point 14).
Android : écart de parité restant, non traité.
Ancien plan Valhalla : phases 1–5 livrées. Phases 6–8 (fermetures data.gouv dans `traffic.tar`, vitesses live, trafic historique prédit) non implémentées, à reprendre avec le lot 10.
Trafic traité dans le backend. Aucun accès officiel Sytadin documenté.

## 6. Contrats API et données

Référence exécutable : routeurs sous `backend/src`, montés dans `server.js`.
Ne pas modifier noms JSON entre Android et iOS. Ajouter champs sans casser anciennes apps.

| Entrée | Contrat essentiel |
|---|---|
| `GET /health` | Public ; disponibilité, moteurs, carte, budgets, compteurs et index |
| `POST /api/accounts/guest` | `{deviceId, platform?}` ; invité permanent |
| `POST /api/accounts/register` | `{email, password, username, referralCode?}` ; 202, brouillon et code email, aucun compte ni jeton |
| `POST /api/accounts/me/register` | Même attente, Bearer invité exigé ; invité reste utilisable avant validation |
| `POST /api/accounts/verify` | `{email, code, registrationToken}` ; création vérifiée, session ; conversion conserve Bearer et identité invité |
| `POST /api/accounts/resend-verify` | `{email, registrationToken}` ; renvoi après 60 s ; ancien code remplacé |
| `POST /api/accounts/login` | `{identifier ou email, password, deviceId?}` ; compte et Bearer |
| `GET /api/accounts/me` | Identité et droits actuels |
| `GET /api/accounts/me/stats` | Cumuls et historique, maximum 200 trajets par compte |
| `POST /api/accounts/me/trips` | Trajet terminé et relevés ETA ; fin, relevés minute, arrêts ; même `id` ignoré ; relevés absents des réponses |
| `POST /api/accounts/me/trips/:id/trace` | `{ seq, points }` : paquet GPS ≤ 1 000 points, corps 1 Mo maximum ; même paquet ignoré |
| `GET /api/route?preview=1` | Aperçu sans débiter trajet ; API limitée 10/minute et 120/heure/compte |
| `POST /api/route/start` | `{to, tripId, vehicle?}` ; même identifiant compte une seule fois |
| `/api/traffic/route`, `/api/route/faster` | Trafic/variantes selon droits, couverture et quotas |
| `GET /api/search` | Index OSM EONA + BAN ; Photon secours ; huit résultats défaut, quinze maximum |
| `GET /api/places/near` | Services proches ; rayon maximum 50 km, espèces filtrées avant limite SQL |
| `/api/signs`, `/api/radars` | Signalisation et radars proches/sur tracé |
| `/api/reports`, `/api/speed-limits` | Contributions, corrections et modération |
| `/api/trips/share`, `/api/trips/group` | Partage et groupe ; SSE `group`/`pos`, battement quinze secondes |
| `/api/bugs` | Rapport volontaire ; consultation et capture privées, administrateurs seulement |
| `/api/live/online`, `/api/live/positions` | Présence et traces réservées administrateurs |
| `GET /api/admin/overview` | Comptes, présence, activité, Valhalla, HERE ; absence mesures = `null` |
| `GET /api/admin/trips` | Activité anonyme, pagination, départ décroissant ; aucun lieu ou identifiant compte |
| `/api/admin/accounts` | Recherche, pagination, création, édition, suppression et registre bans |
| `POST /api/admin/accounts/:id/action` | `ban\|unban\|suspend\|restore\|revokeSessions` |

`tripId` admis permet recalcul gratuit, même plafond atteint ou après minuit.
Nouvel identifiant compte nouveau départ, même destination. Anciennes apps gardent comptage historique au calcul.
Membres exigent Bearer valide ; secours `deviceId` réservé invités. Bearer invalide ne retombe jamais sur appareil.
Sessions mobiles : 90 jours ; `web:true` : douze heures. Redémarrage nécessite reconnexion membres.
Admin : session administrateur valide. `ADMIN_TOKEN` réservé scripts, jamais embarqué dans application.

Inscription vérifiée, sources 19/10 : brouillon privé `<ACCOUNTS_FILE>.registrations.json`, maximum 1 000, expiration 30 minutes, cinq erreurs maximum.
Mot de passe haché seulement. Aucun compte, essai ou parrainage consommé avant validation. Échec livraison email : 503.
Réponse 202 : `verificationRequired`, `registration` avec email, jeton temporaire, `guestId`, `expiresAt`, `resendAt`.
Entrées iOS 60 : `/registration` et `/me/registration`, mêmes règles. Ancien serveur répond 404 sans créer de membre non vérifié.
Conversion `/verify` : même Bearer invité exigé. Ancien `/verify` sans `registrationToken` reste disponible pour comptes existants.
Google inconnu exige email vérifié. Identités fournisseurs déjà connues gardent leur accès.
Ancien client sans nouveau parcours : inscription bloquée ; invités et connexions existantes conservés. Android hors périmètre de ce lot.

Recherche : trigrammes, proximité 60 km, lieux notables France, enseigne et commune.
Corrections manuelles : [backend/search/extra.sql](backend/search/extra.sql), rejouées au rebuild.
Fitness Park Orly : position approchée ; adresse exacte reste à confirmer.
Coût carburant : consommation estimée et prix proches ; péages non tarifés.

### Confidentialité et modération

Réglages Présence/Temps d'utilisation actifs par défaut dans iOS actuel.
Deux réglages coupés : aucun ping mobile. Temps cumulé seulement avec `session:true`, absence >90 secondes exclue.
Positions envoyées en trajet ; dernière position possible à fermeture hors trajet.
Positions liées au compte, conservation 30 jours ; suppression compte efface positions.
Historique statistiques conserve libellés, sans coordonnées.
Groupes/partages mémoire : six heures maximum, quinze minutes après fin.
Vitesses EONA anonymes : clé aléatoire trajet, mémoire trente minutes.
Mesures routage : 90 jours ; tracés divergents admin : 30 jours ; journal admin : 365 jours.
Signalements fermés et bugs résolus : 90 jours. Plaque voiture radar jamais exposée.
Bugs volontaires peuvent joindre destination/tracé même statistiques désactivées.
Lot 1b (déployé 15/10) : tous trajets et tracé GPS transmis, tous comptes, statistiques coupées comprises.
Trajets et tracés conservés sans limite de durée, supprimés avec le compte. Texte Confidentialité des apps le dit.
Page confidentialité web alignée et publiée 15/10 (`8191466`). **Conservation illimitée de positions à valider juridiquement (RGPD, limitation de conservation).**
Capture facultative : JPEG, 1 Mio maximum, 4096 pixels/côté, huit millions pixels ; accès admin privé.
Avatars, sources 19/10 : photos EONA seulement. Anciennes URLs externes retirées ; iOS filtre aussi avant chargement.

Bans persistés dans `<ACCOUNTS_FILE>.bans.json`, séparément des comptes.
Ban bloque compte, emails normalisés, appareils EONA connus et identités fournisseurs connues.
Identifiant appareil EONA logiciel conservé dans Keychain ; **aucun HWID matériel disponible**.
Suppression compte conserve bans. Déban reste possible après suppression compte source.
Suspension réversible ; révocation sessions distincte. Protéger propre compte et dernier administrateur actif.
DTO admin explicites : aucun hash mot de passe, code ou jeton exposé.
Réactivation signalement supprimé et rôle modérateur distinct non développés.

## 7. État livré et points ouverts

Référence iOS fonctionnelle : `c51b834`, build **54**, version **1.0.0**.
iOS build **55** (`7ef22c9`, lot 1) : archive et lancement confirmés par Arthur ; collecte à valider.
iOS build **56** (`2a5d06b`, lot 1b) : utilisé en route, 10 trajets en base au 18/10.
iOS build **57** (`b0d3125`) : dock glissable, Stationnement dans le dock, bouton 3D/2D ; 1 trajet en base.
Collecte lot 1b vérifiée en base 18/10 : 9 trajets, 9 tracés. Essais route Arthur : ETA plus réaliste.
iOS build **58** (`eea9704`) : Feu rouge non cliquable, vue 3D 55° ; poussé, archive et téléphone à valider.
iOS build **59** (`f833a6b`, audit 19/10) : poussé, compilation confirmée par Arthur. Téléphone non validé.
iOS build **60** : quatre correctifs supplémentaires, point 13. Compilé et testé selon Arthur (20/10). Aucun gain ETA mesuré.
iOS build **61** : fusion des retards `TrafficParts.extra`, point 14. Sources écrites, non compilées, non validées téléphone.
iOS build **62** (`7d46c08`) : panneau arrivée, point 15. Poussé ; compilation et téléphone à valider.
Android version **1.0.2**, code **40** (`c82a336`, mêmes correctifs) : APK release compilé, téléphone à valider. Code repris dans livraison `main`.
Build iOS Windows impossible. Présence code ou workflow ne prouve pas archive ni validation téléphone.

Déploiements backend consignés : `a85c6bc`, puis quatre fichiers stations `5646f45` le 08/10.
15/10 vers 00:45 Paris : `accounts/store.js` et `accounts/routes.js` de `6e27a2b` (lot 1). Sauvegarde `/var/backups/eona/deploy-20261015-003412-lot1` : code, `data`, base.
Après redémarrage : service actif, santé locale et publique OK, journal sans erreur, 10 comptes. Membres reconnectés à refaire.
15/10 vers 02:40 Paris : lot 1b (`7125da9`) : `crowd/schema.sql`, `accounts/trip-log.js`, `accounts/routes.js`, `accounts/store.js`, `server.js`.
Sauvegarde `/var/backups/eona/deploy-20261015-023709-lot1b` : code, `data`, `crowd.dump`. Tables `crowd.trip`, `crowd.trip_trace` créées.
Nouvelles clés HERE et TomTom chargées. Santé OK, journal sans erreur, trace sans compte : 401.
15/10 17:05 : budget HERE franchise (`9a8ea37`) : `traffic/budget.js`, `here.js`, `traffic/routes.js`, `routing/routes.js`, `routing/faster.js`, `config.js`.
Sauvegarde `/var/backups/eona/deploy-20261015-170303-here-franchise` : code, `data`. Santé OK, journal sans erreur, HERE ouvert.
15/10 : page confidentialité publiée (ancienne gardée dans `deploy-20261015-023709-lot1b`).
Lecture production 14/10 : santé HTTP 200, Valhalla `all`, carte 11/10, HERE limité à 5 €.
Empreintes 14/10, 72 fichiers `src`/`bin`, fins de ligne ignorées : code `a85c6bc`.
Seules exceptions : `places/routes.js` et `places/store.js`, identiques `5646f45`. Fichiers production majoritairement CRLF.
Correctif `435837c` (Import sans attente du flow) déployé avec le budget HERE le 15/10.
18/10 03:57 : lot 2 (`9824a80`) : `traffic/here.js`, `bin/eona-eta-report-v2.js` (nouveau). Sauvegarde `/var/backups/eona/deploy-20261018-035650-lot2` : code, `bin`, `data`.
Empreintes conformes, santé locale et publique 200, journal sans erreur. Membres reconnectés à refaire.
18/10 04:45 : lot 4 (`5c1627d`), 12 fichiers `routing`, `server.js`, rapport v2. Sauvegarde `/var/backups/eona/deploy-20261018-044112-lot4` : code, `data`, `routing.dump`.
Empreintes conformes, santé 200, colonne `engine_duration_s` créée, Node 20 : Châtelet–Versailles 2 058 → 2 648 s en mode `edges`, 33 ms.
20/10 01:35 Paris : fusion des retards (`270d0b2`, depuis `x_radar` par `git archive`). Trois fichiers `src/traffic` : `delay-fusion.js` (nouveau), `crowd.js`, `datagouv.js`.
Sauvegarde `/var/backups/eona/deploy-20261020-013518-traffic-fusion` : `code.tgz` (`src`, `package.json`, `package-lock.json`), manifestes `src` avant/après.
Avant : `crowd.js`, `datagouv.js` = `38f9af5` (CRLF ignorées), helper absent. Après : empreintes conformes à l’archive exportée. Sources identiques à `270d0b2`, fins de ligne ignorées. Propriétaire `eona:eona`, mode 644.
Manifeste `src` complet : deux fichiers changés, un ajouté, rien d'autre. Test pur fusion sur VPS, Node 20.20.2 : 21/21.
Redémarrage unique 01:35:47 : service actif, santé locale et publique 200, journal sans erreur. Membres reconnectés à refaire.
Production = lot 4 `5c1627d` + correctif trafic. Lots comptes, radars, rapport, sauvegarde : toujours non déployés.

Points ouverts concrets :

1. HERE en route : Import utilisé (base départ HERE 7 sur 9) ; rejets par code visibles dans `/health` `traffic.here.quality.import` depuis 18/10.
2. Registre bans : script source l'inclut (19/10). Cron VPS `/usr/local/bin/eona-backup.sh` encore sans lui ; installation exige accord.
3. Page confidentialité publiée 15/10 : relecture Arthur ; durée de conservation des tracés à valider juridiquement.
4. Mesurer ETA après nouvelle livraison ; conserver séparation pauses/embouteillages et versions d'application.
5. Valider lecteur système sur téléphone ; titre tiers reste sans solution logicielle démontrée.
6. Perso, feux, gabarit poids lourd et achat abonnement restent chantiers distincts, sans livraison implicite.
7. Android : aucun trajet en base au 18/10 (9 iOS : build 56 ×8, 57 ×1) ; iOS 58 et Android 40 à valider téléphone.
8. HERE : surveiller usage par fenêtre dans `/health` ; crédits ajoutés par Arthur plus tard, alors relever `HERE_MONTHLY_BUDGET_EUR`.
9. Ancien test recherche corrigé : vrai compte invité, aucune identité factice ; contrat HTTP testé sans réseau tiers.
10. **Point de reprise 19/10 :** audit initial poussé, compilation iOS 59 confirmée. Quatre correctifs supplémentaires : point 13.
    - Production : lot 4 `5c1627d` + fusion des retards `270d0b2` (20/10, point 14). Autres lots backend et cron attendent accord Arthur.
    - iOS 60 : compilé et testé selon Arthur (20/10). Android inchangé.
    - 20/10 : Arthur valide la suite du plan ETA ajusté (§5). Premier lot : fusion des retards, point 14.
    - Question §5 conservée : navigation coupée avant arrivée ? À reprendre pendant examen ETA.
11. **Audit 19/10 — neuf correctifs écrits, non déployés, non validés téléphone.**
    - Backend : 214 tests ciblés passés sous Node 20.20.2, aucun ignoré (pilote). Aucune validation production.
    - Radars sur tracé : coordonnées bornées, 100 000 points, budget 400 000 cellules, sinon 400. OOM reproduit avant (64 Mo, exit 134).
    - Google : email non vérifié repris par preuve fournisseur. Mot de passe, sessions, codes, autres fournisseurs retirés.
    - Liaison Google : session revérifiée après attente Google ; reset, récupération ou ban entre-temps : aucune liaison.
    - Codes reset : tirage cryptographique, 30 min, cinq échecs, un envoi par 60 s.
    - Reset réussi : toutes sessions révoquées.
    - Codes vérification email : même règles, expiration 30 min.
    - ETA : étapes portent `engineDurationS` brut ; relevés `engineBasis: "raw"` seulement si `engineLeftS` brut. Affichage inchangé.
    - Rapport v2 : réel/moteur sur relevés `raw` seuls ; anciens comptés base inconnue, hors ratio.
    - iOS 59 : URL admin parrainage interpolée ; suppression compte purge file trajets et tracés du compte supprimé.
    - iOS 59 : envoi file trajets lié à session figée (compte, jeton, révision) ; autre compte : aucun envoi croisé.
    - Git : backend `04ad8d7`, iOS `f833a6b`, poussés sur `main`. iOS 59 compilé selon Arthur ; téléphone non validé.
    - Sauvegarde : script source inclut registre bans facultatif ; cron VPS non mis à jour (point 2).
    - `saveTrace` sans trajet parent : contrat collecte, ordre d'envoi libre. Aucun défaut démontré.
12. **Défaut restant hors lot :**
    - iOS `RoutePath.match` : repassage au même endroit recale sur premier passage, orientation et progression fausses.
13. **Quatre correctifs supplémentaires, sources 19/10, Codex solo autorisé par Arthur.**
    - Avatars : origine EONA et fichier propriétaire exigés ; anciennes URLs filtrées dans comptes et groupes, puis avant chargement iOS.
    - Confidentialité iOS : descriptions raccourcies ; vitesses guidées, ralentissements hors guidage et collecte GPS permanente explicités.
    - Email : nouveau parcours avec code avant création membre. Invités et anciens comptes non vérifiés gardés.
    - Conversion invité : même compte, session, statistiques et trajets. Annulation ne supprime rien.
    - Google inconnu non vérifié refusé ; index email ne remplace plus compte existant par doublon non vérifié.
    - Test recherche : authentification invité réelle. Backend : 368 tests passés sous Node 20.20.2, aucun ignoré.
    - iOS 60 : tests Swift ajoutés, jamais exécutés sous Windows. Compilé et testé selon Arthur (20/10).
    - Avant utilisation du nouveau parcours iOS : déployer backend compatible après accord Arthur. SMTP doit fonctionner.
14. **Fusion des retards, lot backend + iOS build 61, sources 20/10 (§5).** Backend déployé 20/10 01:35 ; iOS 61 non compilé.
    - Backend : `src/traffic/delay-fusion.js` (nouveau), `crowd.js`, `datagouv.js` ; tests `traffic-delay-fusion`, `traffic-crowd`, `traffic-datagouv`.
    - Revue parité 20/10 : rang, seuils, marqueurs et densité alignés Swift ; fixtures communes ajoutées.
    - Backend : 393 tests passés sous Node 20.20.2, aucun ignoré (local).
    - Production 20/10 : trois fichiers `270d0b2` installés, santé 200, journal sans erreur. Détail : déploiements ci-dessus.
    - Aucun appel route ou HERE après déploiement : effet en route non observé.
    - iOS build 61 : `TrafficParts.extra`, mêmes règles et fixtures. Sources écrites ; non compilées, tests Swift non exécutés.
    - iOS 60 reste dernier build compilé et testé selon Arthur (20/10).
    - Android : inchangé. Parité future à traiter dans lot distinct.
    - Effet attendu : moins de retard compté deux fois. Aucun gain d'ETA démontré avant trajets mesurés.

15. **Panneau arrivée iOS, Codex solo demandé par Arthur, build 62 (`7d46c08`).** Poussé sur `main` le 20/10.
    - Destination prioritaire, heure de fin figée, durée totale et distance parcourue ; alertes traversées secondaires.
    - Bilan, historique éventuel et file utilisent le même `TripRecord`. Arrêt manuel distingué de l'arrivée automatique.
    - Verre, couleurs et bouton primaire réutilisent le thème EONA observable. Aucun vert imposé ni rebond décoratif.
    - Dock masqué pendant le bilan. « Terminé » reste hors défilement ; grands caractères disposent les mesures verticalement.
    - Diff relu, `git diff --check` réussi. Aucun Xcode sous Windows : compilation et rendu téléphone non vérifiés.
    - Backend, détection arrivée, calcul ETA et contrats JSON inchangés. Aucun déploiement ni workflow lancé.

Mettre à jour ces deux références après changement réel. Ne pas recréer journal Markdown séparé.
