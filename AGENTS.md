# EONA — Projet et règles

État vérifié : **14 octobre 2026**. Produit : **EONA**, ancien nom XRadar.

Deux références uniquement : ce fichier et [EXPLOITATION.md](EXPLOITATION.md).
Ce fichier décrit produit, architecture, limites réelles et règles de travail.
EXPLOITATION décrit Git, builds, production, sauvegardes et déploiement.
Claude lit ce fichier puis EXPLOITATION à chaque session. Aucun CLAUDE.md ; rien ne se charge seul.
Anciens documents restent consultables dans historique Git.

## 1. Travail avec Arthur

- Arthur décide périmètre et livraison ; validation fonctionnelle sur téléphone.
- Depuis 14/10 : Claude développeur principal backend, iOS et Android. Aucun travail dupliqué.
- Parité stricte Android/iOS : mêmes règles, mêmes champs JSON, logique dans `core/` et EonaCore.
- **Ne pas utiliser bridge.** Sessions autonomes, coordonnées par Arthur.
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
- Root/backend : commit sans push, sauf demande. iOS : commit et push.
- Ne pas commiter secrets, artefacts, `.claude/`, `.mcp.json` ou `.bridge/` sans accord.

## 2. Repos et architecture

| Périmètre | Chemin Windows | Remote | Branche de référence |
|---|---|---|---|
| Android + backend | `C:\Users\usr\Documents\x_radar` | `git@github.com:Zende3v/xradar.git` | `feature/mini-player-musique` ; principale `main` |
| iOS | `C:\Users\usr\Documents\xradar_ios` | `git@github.com:Zende3v/xradar_ios.git` | `main` |
| Déploiement backend | `C:\Users\usr\Documents\x_radar_eco` | même dépôt backend | HEAD détaché `b05d017` ; code identique `435837c`, commentaires exceptés |

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
`mapVersion` décrit publication tuiles ; ne prouve pas date exacte données OSM.
Multi-arrêts : dix étapes maximum. Cap transmis en mouvement, marge 50 m, tolérance 45°.

Évitements péages, autoroutes et ferries stricts.
Polygones bouchons peuvent être retirés si route impossible ou recherche trafic échoue.
Rapide : variantes chronométrées symétriquement, maximum deux, gain minimal trois minutes et 5 %.
Éco et scooter : détour seulement pour fermeture ; variante plus courte.
Après secours, recherche `/faster` désactivée. Horizon 60 km, fenêtre 85 km ; élargissement non livré.

HERE Traffic v7 fournit flow/incidents. HERE Routing v8 Route Import chronomètre tracé EONA.
Vitesses EONA et événements publics complètent HERE. TomTom retiré le 30/09 ; banc historique conservé.
Cache trafic partagé 60 secondes, appels identiques mutualisés.
Réservation budget persistée avant appel payant ; échec écriture interdit appel.
Économie HERE ne doit jamais masquer absence de trafic mesuré.
Validation Import par longueur ±10 % ; identité géométrique exacte non garantie. Taxi HERE reste voiture.

Plafond **estimé** production : **5 €/mois**.
Arthur choisit 10 €. Code `435837c` prépare plafond et réponse Import indépendante du flow lent.
Ce correctif local attend déploiement explicitement autorisé ; ne pas le présenter actif.
Mesure 14/10 : 4,999248 € estimés ; HERE bloque nouveaux appels pour budget mensuel.
Estimation utilise tarifs configurables et marge 20 %, aucune franchise supposée. Facture réelle non importée.
Quotas HERE par compte : ETA une/minute, 150/jour UTC ; variantes une/minute, 30/jour UTC.
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
Aucun coefficient correcteur global, apprentissage historique ou injection trafic Valhalla livré.

Phases initiales 1–5 livrées. Phases 6–8 non implémentées ; trafic traité dans backend.
Aucun accès officiel Sytadin documenté. Prochaine évolution choisie par Arthur.

## 6. Contrats API et données

Référence exécutable : routeurs sous `backend/src`, montés dans `server.js`.
Ne pas modifier noms JSON entre Android et iOS. Ajouter champs sans casser anciennes apps.

| Entrée | Contrat essentiel |
|---|---|
| `GET /health` | Public ; disponibilité, moteurs, carte, budgets, compteurs et index |
| `POST /api/accounts/guest` | `{deviceId, platform?}` ; invité permanent |
| `POST /api/accounts/register` | `{email, password, username, referralCode?}` ; essai automatique |
| `POST /api/accounts/me/register` | Conversion invité authentifié, conservation identité et statistiques |
| `POST /api/accounts/login` | `{identifier ou email, password, deviceId?}` ; compte et Bearer |
| `GET /api/accounts/me` | Identité et droits actuels |
| `GET /api/accounts/me/stats` | Cumuls et historique, maximum 200 trajets par compte |
| `POST /api/accounts/me/trips` | Trajet terminé et relevés ETA ; cumuls conservés au-delà historique |
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
Capture facultative : JPEG, 1 Mio maximum, 4096 pixels/côté, huit millions pixels ; accès admin privé.

Bans persistés dans `<ACCOUNTS_FILE>.bans.json`, séparément des comptes.
Ban bloque compte, emails normalisés, appareils EONA connus et identités fournisseurs connues.
Identifiant appareil EONA logiciel conservé dans Keychain ; **aucun HWID matériel disponible**.
Suppression compte conserve bans. Déban reste possible après suppression compte source.
Suspension réversible ; révocation sessions distincte. Protéger propre compte et dernier administrateur actif.
DTO admin explicites : aucun hash mot de passe, code ou jeton exposé.
Réactivation signalement supprimé et rôle modérateur distinct non développés.

## 7. État livré et points ouverts

Référence iOS fonctionnelle : `c51b834`, build **54**, version **1.0.0**.
Android observé : version **1.0.2**, code **36**.
Build iOS Windows impossible. Présence code ou workflow ne prouve pas archive ni validation téléphone.

Déploiements backend consignés : `a85c6bc`, puis quatre fichiers stations `5646f45` le 08/10.
Lecture production 14/10 : santé HTTP 200, Valhalla `all`, carte 11/10, HERE limité à 5 €.
Empreintes 14/10, 72 fichiers `src`/`bin`, fins de ligne ignorées : code `a85c6bc`.
Seules exceptions : `places/routes.js` et `places/store.js`, identiques `5646f45`. Fichiers production majoritairement CRLF.
Checkout déploiement conserve correctif local `435837c`, encore distinct de production.

Points ouverts concrets :

1. Déployer correctif HERE/plafond 10 € seulement après nouvel accord Arthur.
2. Inclure registre bans dans sauvegarde quotidienne avant prochaine livraison concernée.
3. Aligner HTML confidentialité : défaut présence et couverture HERE décrits différemment du code actuel.
4. Mesurer ETA après nouvelle livraison ; conserver séparation pauses/embouteillages et versions d'application.
5. Valider lecteur système sur téléphone ; titre tiers reste sans solution logicielle démontrée.
6. Perso, feux, gabarit poids lourd et achat abonnement restent chantiers distincts, sans livraison implicite.

Mettre à jour ces deux références après changement réel. Ne pas recréer journal Markdown séparé.
