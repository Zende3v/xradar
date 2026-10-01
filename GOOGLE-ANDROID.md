# Google Android — correction

Capture Arthur, 01/10/2026 : package et signature corrects.
Ancien identifiant intégré : client Android, incompatible avec serverClientId.
Client Web créé par Arthur, même projet Google Cloud.

- Client Web utilisé par Credential Manager : `860999659689-6ul8q6spgdjkfb3a2irv9b4i4q2l2gns.apps.googleusercontent.com`.
- Client Android, conservé dans console : `860999659689-do66gkvaddkq543dl4dg86rildt6fpna.apps.googleusercontent.com`.
- Package Android : `com.eona.app`.
- SHA-1 : `3C:80:2B:36:B3:C0:62:80:8E:5D:84:9F:7A:37:A7:9A:35:2E:9F:4D`.

Android utilise client Web pour demander jeton. Backend doit accepter cette audience
via GOOGLE_CLIENT_IDS, avec audiences existantes conservées.
Origines et redirections Web inutilisées par ce flux Credential Manager Android.
Aucun secret client nécessaire dans application ou backend.

Contexte Activity, repli vers sélecteur de comptes, annulation respectée.
Aucun jeton journalisé. Validation réelle : Arthur sur téléphone avec APK 22.
Après publication Play Store, enregistrer aussi SHA-1 du certificat Play App Signing.

Documentation : https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
