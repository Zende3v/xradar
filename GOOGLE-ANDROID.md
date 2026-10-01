# Google Android — vérification

APK signé avec clé debug locale. Client Web présent dans application.

Console Google Cloud, même projet que client Web :

- Type OAuth : Android.
- Package : `com.eona.app`.
- SHA-1 : `3C:80:2B:36:B3:C0:62:80:8E:5D:84:9F:7A:37:A7:9A:35:2E:9F:4D`.

Client Android nécessaire même si connexion iOS fonctionne.
Code 10 indique configuration OAuth invalide. Repli ne corrige pas signature absente.
Après publication Play Store, enregistrer aussi SHA-1 du certificat Play App Signing.

Correction locale : contexte Activity explicite, repli vers sélecteur de comptes,
annulation respectée, erreurs avec code disponible. Aucun jeton journalisé.
Validation téléphone nécessaire. Configuration console non vérifiée.

Documentation : https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation
