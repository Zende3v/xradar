# AGENTS.md — EONA

Source unique du contexte projet, pour Codex et pour Claude (`CLAUDE.md` l'importe).

## Mode de travail (Arthur, 05/10/2026)

- **ChatGPT : iOS et backend.** Codex travaille directement dans ces périmètres.
- **Claude local : Android.** Session locale rattrape les fonctionnalités iOS.
- Respecter périmètres disjoints. Aucun travail dupliqué.
- **Bridge uniquement sur demande explicite d'Arthur.** Ancien pilotage Codex/Claude via bridge abandonné.
- Tests automatiques uniquement indispensables à correction ou risque concret. Aucune campagne par défaut ni relance sans nécessité.
  Arthur réalise validation fonctionnelle et téléphone. Ne pas ajouter tests pour changements mineurs.
- Revue croisée ciblée : contrat, changements risqués, défauts concrets. Pas de double audit ni double campagne de tests.
- Rapports ultra courts : résultat, blocage, prochaine étape. Conserver point de reprise précis après chaque lot.
- Les deux : skill **caveman** à chaque réponse, en français. Commits et docs en caveman aussi.
- Arthur ne travaille plus qu'à deux.

### Bridge (uniquement sur demande d'Arthur)

- Dossier : `C:/Users/usr/bidirectional-bridge-claude-codex`. Codex 0.158.0-alpha.13 requis pour
  gpt-6-astra.
- Déroulé :
  1. `bridge_server_info` ;
  2. tâche racine (depth 0) ;
  3. délégations depth 1 (`max_attempts: 0`, délai 15 à 30 min, scopes d'écriture disjoints) ;
  4. vérifier chaque livrable ;
  5. `bridge_record_verification` pour les seules vérifs réellement lancées.
- Tâche coupée ou bloquée : `bridge_resume_delegated_task` sur la tâche existante, jamais de
  tâche de remplacement.
- Agent indisponible ou quota épuisé : le dire en une ligne. Ne pas reprendre gros lot côté Codex sans accord Arthur.
- Bridge bloqué : diagnostic borné, point de reprise conservé. Ne pas transformer incident en chantier prolongé par défaut.
- Config : `.codex/config.toml` (Codex, `caller=codex`) et `.mcp.json` (Claude, `caller=claude`),
  locaux, non versionnés. Base partagée : `.bridge/bridge.db`.

### Worker Claude (lancé par le bridge quand Codex délègue)

- Commande imposée par le bridge : `claude -p … --model opus --effort max --permission-mode
  acceptEdits`, outils `Read`, `Edit`, `Write`, `Bash` seulement. Pas d'outils MCP, pas de
  skills, pas de `Grep`/`Glob` : chercher avec `grep` dans `Bash`.
- Tours : 12 par défaut. Mettre `max_turns` 32 à 64 dans la tâche pour un vrai travail (build +
  corrections, relecture large).
- Build Android dans `Bash` : `export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" &&
  ./gradlew :app:assembleRelease`.
- Le livrable part tout seul à la fin de la tâche : le worker ne l'appelle pas lui-même.
- Inclure dans chaque contrat : français, caveman ultra, livrable bref, tests indispensables seulement.
  Appliquer ces règles directement ; worker ne dispose pas d'outil skills.
- Scopes relatifs au workspace du bridge. `../xradar_ios/**` est refusé (`INVALID_ARGUMENT`).
  Pour iOS, ouvrir bridge sur `C:/Users/usr/Documents/xradar_ios`, puis déléguer avec scopes relatifs à ce repo.

### Caveman (Codex et Claude, toujours)

Skill caveman niveau **ultra**, en français, pour Codex et Claude Opus 5.5. Sans articles superflus, sans formules de politesse, sans
remplissage. Phrases courtes (20 mots max), une idée par phrase, voix active, impératif pour les
consignes. Termes techniques, code, commandes et messages d'erreur exacts. Jamais d'abréviation
inventée. Clarté d'abord : style normal pour les alertes de sécurité et les actions
irréversibles.

## Arthur (propriétaire)

- Dev pro, équipe de 4. Travaille étape par étape, avec validation entre chaque étape.
- Choix qui lui revient : question courte, options, ta recommandation.
- Il fait lui-même les tests sur téléphone : pas d'émulateur, pas de captures.
- Rapports courts : ce qui est fait, où, ce qu'il doit trancher.
- Design premium sur mesure, inspiré d'Apple. Refus du look Material ou « interface générée par
  IA ». Chaque écran a tous ses états (chargement, erreur, vide).

## Repos

| Repo | Chemin | Remote | Branche | Règle |
|---|---|---|---|---|
| Android + backend | `C:\Users\usr\Documents\x_radar` | `git@github.com:Zende3v/xradar.git` | `feature/mini-player-musique` (principale `main`) | commit **sans push**, sauf demande |
| iOS | `C:\Users\usr\Documents\xradar_ios` | `git@github.com:Zende3v/xradar_ios.git` | `main` | commit **et push** (Arthur lance Codemagic) |

- Nom produit : **EONA** (ancien nom XRadar). Package Android : `com.eona.app`.
- Non suivis dans x_radar, à ne pas commiter sans accord : `.claude/`, `.mcp.json`.
- `C:\Users\usr\Documents\x_radar_eco` : worktree détaché, déploiement backend seulement.

## Android (`app/`)

- Kotlin + Jetpack Compose, UI 100 % sur mesure. Carte MapLibre + tuiles Stadia (clé
  `stadia.apiKey` dans `local.properties`, non versionné).
- Logique métier en Kotlin pur (`core/`), reprise à l'identique sur iOS.
- Build release, toujours en PowerShell :
  `$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleRelease`
  Sortie : `app/build/outputs/apk/release/app-release.apk`, signée avec la clé debug.
- Toolchain :
  - JDK 25 (JBR), Gradle 9.7.1, SDK 37, build-tools 36.0.0.
  - AGP 9 embarque Kotlin : **ne jamais** appliquer `org.jetbrains.kotlin.android`.
  - KGP 2.2.10 : aucune lib compilée avec Kotlin ≥ 2.4.
  - Pas de tests unitaires Android.
- Version : 1.0.1 (versionCode 22). **Monter le `versionCode` à chaque nouvel APK.**

## iOS (`xradar_ios`)

- SwiftUI iOS 26 (Liquid Glass), Swift 6, XcodeGen (`project.yml`), package SPM
  `Packages/EonaKit` (EonaCore, EonaData, tests Swift Testing), carte MapKit.
- Pas de Mac : aucune compilation locale. Codemagic (`codemagic.yaml`) build et teste ; Arthur
  lance le build.
- Build : `CURRENT_PROJECT_VERSION` dans `Config/Base.xcconfig`, aujourd'hui 30. Le monter à chaque
  nouvelle IPA.
- Parité stricte avec Android : mêmes règles, mêmes noms de champs JSON.

## Backend (`backend/`)

- Node 20 ESM + Express, PostgreSQL 17 + PostGIS (schémas `signs`, `crowd`, `routing`, `search`).
- Docs d'exploitation : `README.md` à la racine (install, déploiement §4, surveillance, pannes,
  API §12) et `backend/API-WEBAPP.md`.
- Routage actuel : Valhalla 3.9 sur VPS (`127.0.0.1:8002`), ORS en secours. OSRM démo retiré.
- Trafic : HERE depuis le 30/09. TomTom retiré. Recherche : index OSM EONA, BAN, Photon en secours.

## VPS de production

- `ssh root@193.168.146.56` (clé `~/.ssh/id_ed25519`, sans alias). Ancien VPS Tailscale
  `100.107.151.127` : ne plus utiliser.
- Debian 13, 8 vCPU, 16 Go RAM, **pas de swap**, 154 Go libres, fuseau Europe/Paris. Ni Docker,
  ni nginx. ufw : port 22 seulement.
- Services systemd :
  - `eona-backend` : `/opt/eona-backend`, user `eona`, `127.0.0.1:8090` ;
  - `eona-tunnel` : Cloudflare, `https://api.lrda-mercuriale.uk` ;
  - `eona-privacy` (9020) et `eona-cgu` (9021) ;
  - `postgresql` : base `eona`, auth peer (`runuser -u eona -- psql -d eona`).
- **Secrets** : drop-ins `/etc/systemd/system/eona-backend.service.d/*.conf`. Ne jamais les
  afficher (ni `systemctl cat`, ni env du process).
- Cron (`/etc/cron.d`) :
  - dimanche 03:30 : signalisation, Valhalla, recherche (`bin/eona-geodata-rebuild.sh`) ;
  - `eona-backup` : 03:10, `/var/backups/eona`, 14 jours, sur le VPS seulement ;
  - banc TomTom arrêté depuis le 30/09 ; historique conservé en lecture seule.
- Déploiement : README §4. `ssh`, sauvegarde `tar` et `scp` depuis cmd du PC, dossier `x_radar_eco`.
- Sauvegarder `src` en `.tgz` d'abord. Puis `scp` depuis PC ; `chown`, restart, `/health` sur VPS.
- Ne jamais lancer `scp` depuis VPS.
- **Aucun déploiement, installation ni redémarrage sur le VPS sans accord explicite d'Arthur, à
  chaque fois.** Lecture seule libre.

## Chantier en cours : ORS remplacé par Valhalla

- Lire d'abord `backend/VALHALLA-ETAT.md` (état du chantier, à mettre à jour à chaque étape),
  puis :
  - `backend/PLAN-VALHALLA.md` : plan validé, phases 1 à 8 ;
  - `backend/VALHALLA-DECISIONS.md` : journal D1 à D8 et P1.1 à P1.7 ;
  - `backend/CHECKLIST-TRAJETS.md` : trajets tests de l'équipe.
- Cadre :
  - France seule ;
  - ORS seulement en secours ;
  - contrat `/api/route` inchangé, tout ajout de champ additif ;
  - évitements stricts (péages, autoroutes, ferries, bouchons) ;
  - HERE à économiser ;
  - aucun chiffre inventé : ce qui n'est pas mesuré est « à mesurer ».
- Phase 1 : collecte depuis le 25/09. Bilan après 2 à 3 semaines.
- Historique documenté : Valhalla généralisé le 29/09 ; phase 5 livrée le 30/09.
- Prochaine phase à confirmer avec Arthur : `VALHALLA-ETAT.md` contient des sections historiques contradictoires.
