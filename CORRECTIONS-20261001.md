# Corrections — 01/10/2026

Arthur demande Codex seul. Push et déploiement explicitement autorisés.

- Android 22 : APK release compilé, 34 secondes. Client Web vérifié dans BuildConfig.
- iOS 20 : main poussé, commit 3239625. Compilation et tests via Codemagic Arthur.
- Icônes alertes/radars agrandies environ 16 %.
- Recherche ferme dock immédiatement, Android et iOS.
- Tracé complet : plafond 2500 supprimé. Android affiche route sans attendre GPS.
- Capture bug facultative : galerie, aperçu, retrait, envoi, consultation admin privée.
- Backend : colonne screenshot bytea, JPEG vérifié, stockage privé, accusé réception.
- Huit tests backend passent. Deux contrats Swift ajoutés pour Codemagic.
- Capture Arthur confirme signature et package. Ancien client Android utilisé par erreur comme client Web.
- Arthur crée client Web. Android 22 utilise nouvel identifiant ; backend accepte audience correspondante.
- Audiences iOS et Android existantes conservées. Configuration sauvegardée avant modification.

Déploiement backend terminé : **01/10/2026, 19:23:55 Europe/Paris**.
Quatre fichiers vérifiés SHA-256. Colonne bytea présente. Santé locale/public OK.
Endpoint capture public refuse accès anonyme (403). Compteur HERE conservé : 75 appels octobre.

Sauvegardes :

- Code : /opt/eona-backend-src-backup-20261001-191553.tgz.
- Configuration : /root/eona-google-config-backup-20261001-191553.tgz, accès root seulement.

Android/backend : commits 83ce9ec et correctif Google, push feature/mini-player-musique autorisé.
Validation Arthur : APK 22, Google, capture avec/sans image, route longue, dock puis recherche.
Fluidité réelle et connexion Google restent à valider sur téléphone.
