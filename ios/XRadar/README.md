# x_radar — iOS

App iOS native de x_radar : SwiftUI, iOS 26, Liquid Glass. Même backend Node/Express et même
contrat API que l'app Android.

**État : étape 1/13 — squelette** (projet, configuration, confidentialité, CI locale).

## Prérequis

- Mac avec **Xcode 26** (Swift 6.2).
- **XcodeGen** : `brew install xcodegen`
- Optionnel : `brew install xcbeautify` (logs de test lisibles).

## Démarrer

```bash
cd ios/XRadar
cp Config/Secrets.example.xcconfig Config/Secrets.xcconfig   # puis remplir
xcodegen generate
open XRadar.xcodeproj
```

- `XR_STADIA_API_KEY` : même clé Stadia que l'Android (fond de carte clair/sombre).
- `DEVELOPMENT_TEAM` : Team ID Apple, requis pour lancer sur un iPhone réel.
- `project.yml` est la seule source du projet. `XRadar.xcodeproj` est généré et ignoré par git :
  relancer `xcodegen generate` après chaque pull ou ajout/suppression de fichier.

## Tests (CI locale)

```bash
bash scripts/ci.sh        # package + unitaires + UI
bash scripts/ci.sh unit   # sans les tests UI
```

Simulateur par défaut : iPhone 17, dernier iOS installé. Autre appareil :
`XR_DESTINATION='platform=iOS Simulator,name=iPhone 17 Pro' bash scripts/ci.sh`.

Tests rapides du package seul, sans simulateur : `swift test --package-path Packages/XRadarKit`.

## Architecture

```
ios/XRadar/
├─ project.yml               spec XcodeGen
├─ Config/                   xcconfig : Base, Debug, Release, Secrets (non versionné)
├─ Packages/XRadarKit/       package Swift pur (Foundation seulement)
│  ├─ Sources/XRadarCore/    modèles, géométrie, itinéraire, pertinence, guidage, soleil
│  └─ Sources/XRadarData/    client API unique, DTO, repositories, fixtures
├─ XRadar/                   cible app
│  ├─ App/                   lancement, dépendances, configuration
│  ├─ Platform/              Core Location, voix, Keychain, photos, stockage local   (à venir)
│  ├─ Features/              Onboarding, Drive, Search, Menu, Profile, Stats,
│  │                         Settings, Referral                                      (à venir)
│  ├─ DesignSystem/          tokens, typographie, icônes, composants Liquid Glass    (à venir)
│  └─ Resources/             Info.plist, PrivacyInfo.xcprivacy, assets, textes
├─ XRadarTests/              tests unitaires de la cible app (Swift Testing)
├─ XRadarUITests/            tests UI (XCTest)
└─ scripts/ci.sh             xcodegen + xcodebuild test
```

Règles :

- `XRadarCore` et `XRadarData` n'importent jamais UIKit, SwiftUI ni CoreLocation.
- Cible app : isolation `MainActor` par défaut (réglage Xcode 26). Le travail de fond sort
  explicitement du main actor.
- Les coordonnées d'itinéraire du backend sont `[longitude, latitude]`. Ne jamais les inverser.
- Aucun changement du contrat backend : l'app envoie `platform: "ios"` et
  `identifierForVendor` comme `deviceId`.

## Configuration

| Clé Info.plist   | Source xcconfig       | Rôle                         |
|------------------|-----------------------|------------------------------|
| `XRBackendURL`   | `XR_BACKEND_HOST`     | URL du backend (HTTPS)       |
| `XRStadiaAPIKey` | `XR_STADIA_API_KEY`   | styles de carte Stadia       |

## Confidentialité

- Localisation « Quand l'app est active » demandée au démarrage. « Toujours » demandée
  seulement quand une conduite démarre.
- Modes arrière-plan `location` et `audio` : utilisés seulement pendant une conduite active
  (GPS, partage live, voix). Tout s'arrête à la fin du trajet.
- `PrivacyInfo.xcprivacy` : position précise, e-mail, identifiant de compte, identifiant appareil,
  photo de profil, signalements, statistiques de conduite. Tout lié au compte, usage
  « fonctionnalité de l'app », aucun tracking.
