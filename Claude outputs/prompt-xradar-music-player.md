# Prompt — XRadar : mini-player musique

> À coller dans Claude Code, à la racine `~/Documents/x_radar`.

---

ultracode

Ajoute un **mini-player musique** à XRadar. Explore le code avant d'écrire quoi que ce soit et rends-moi un plan court avant de coder.

## Objectif

Contrôler la musique **déjà en cours** dans Spotify / Apple Music / Deezer. Sans SDK tiers, sans OAuth, sans compte développeur. Uniquement l'API média système Android.

## Technique imposée

- `NotificationListenerService` (classe vide, sert seulement à obtenir le droit) + `MediaSessionManager.getActiveSessions(ComponentName)` pour récupérer les `MediaController` actifs.
- Filtrer sur ces packages **uniquement** :
  - Spotify → `com.spotify.music`
  - Apple Music → `com.apple.android.music`
  - Deezer → `deezer.android.app`
- Actions : `transportControls.play()` / `pause()` / `skipToNext()` / `skipToPrevious()`.
- Métadonnées : `METADATA_KEY_TITLE`, `METADATA_KEY_ARTIST`, `METADATA_KEY_ALBUM_ART` (fallback `ART`, puis `ALBUM_ART_URI`).
- État temps réel via `MediaController.Callback` (`onPlaybackStateChanged`, `onMetadataChanged`) + `MediaSessionManager.addOnActiveSessionsChangedListener` pour le changement d'app.
- Plusieurs sessions actives : prendre celle en `STATE_PLAYING`, sinon la plus récente.
- Respecte le `minSdk` du projet : aucune API au-dessus sans garde.
- Aucune dépendance nouvelle — tout est dans le framework.

## Permission

`BIND_NOTIFICATION_LISTENER_SERVICE` sur le service dans le manifest. L'utilisateur doit l'activer à la main.

- Détection : `NotificationManagerCompat.getEnabledListenerPackages(context)`.
- Non accordée → état vide explicite « Autoriser l'accès aux médias » + bouton vers `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`. Texte court, en français.
- Re-vérifier au retour en avant-plan.
- Révocation à chaud : `SecurityException` sur `getActiveSessions` catchée, retour à l'état « permission manquante ». Pas de crash.

## UI — exactement ceci

1. **Bouton musique** dans la colonne de boutons à droite du HUD, **entre « recentrer » et « signaler »**. Même taille, même style, mêmes composants que ses voisins. Nouvelle icône cohérente avec le jeu existant.
2. **Tap** → **bandeau compact sous la barre de recherche** (haut d'écran), pas un bottom sheet :
   - pochette carrée à gauche (placeholder si absente)
   - titre + artiste, une ligne chacun, ellipsis
   - ⏮ ⏯ ⏭ à droite, cible tactile ≥ 48 dp (usage en conduite)
   - re-tap sur le bouton → referme
3. Apparition/disparition animée, même durée et easing que les autres panneaux du HUD.
4. Le bandeau **ne masque ni** la bannière de guidage **ni** la pile d'alertes. Vérifie l'ordre de composition et les paddings.
5. Aucune des 3 apps ne joue → « Aucune musique en cours » + les 3 apps tappables qui les lancent (`getLaunchIntentForPackage`, ignorer silencieusement celles non installées).

## Architecture

Respecte le découpage existant du projet :

- un repository média qui expose un `StateFlow` d'état lecture (disponible, package, titre, artiste, pochette, isPlaying) — pas de callback qui remonte dans l'UI
- le service listener
- un modèle d'état dédié
- le composant bandeau, dans les composants du HUD
- branchement dans le ViewModel et l'état UI existants du HUD — **pas** de ViewModel parallèle
- ouvert/fermé mémorisé dans l'état UI seulement, pas persisté

## Contraintes

- Zéro impact GPS / navigation / batterie : abonnement aux callbacks média uniquement quand l'écran de conduite est `STARTED`, désabonnement sinon. Pas de fuite.
- Thème clair/sombre : tokens de couleur du design system, aucune couleur en dur.
- Build debug qui passe, zéro warning introduit.
- Commits en français, un par étape logique.

## Critères d'acceptation

- [ ] Spotify joue → bouton tapé → titre, artiste, pochette réels
- [ ] play/pause/next/prev agissent bien sur l'app tierce
- [ ] Deezer et Apple Music identiques
- [ ] Permission non accordée → explication + bouton vers les Réglages Android
- [ ] Rien ne masque le guidage ni les alertes en navigation
- [ ] Build OK, callbacks bien désenregistrés
