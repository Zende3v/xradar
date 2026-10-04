# Valhalla : préparation exploitation

Sources préparées le 26/09/2026. **Aucune installation Valhalla effectuée.**
Accord explicite Arthur requis avant installation, build initial ou redémarrage VPS.

## Image et plafonds

Image officielle `3.9.0`, manifeste fixé par digest dans Quadlet et `valhalla.env.example`.
Tag et digest vérifiés sur [registre officiel](https://github.com/valhalla/valhalla/pkgs/container/valhalla).
[Quadlet Podman 5.4](https://docs.podman.io/en/v5.4.0/markdown/podman-systemd.unit.5.html) pilote service.

Valeurs initiales **provisoires**, aucune mesure France encore : build 2 CPU, 8 Gio RAM,
10 Gio RAM + swap ; service et candidat 2 threads, 2 Gio RAM chacun, 3 Gio RAM + swap.
Réserve swap initiale 4 Gio. Disque libre exigé avant build : 40 Gio.
PostgreSQL protégé par plafonds cgroup et priorité OOM supérieure des conteneurs Valhalla.
Vérifier absence d'OOM PostgreSQL pendant premier build ; aucune garantie sans cette mesure.

Configuration complète générée par outil officiel puis surchargée avec `valhalla.json`.
Exclusions strictes et status détaillé activés explicitement. Périmètre polygones 200 km,
2 000 sommets, 3 alternatives. Accroche recherche 500 m, acceptation 250 m : valeurs provisoires.
Ces options existent dans [configuration 3.9.0](https://github.com/valhalla/valhalla/blob/3.9.0/scripts/valhalla_build_config).

## Installation future

1. Obtenir accord Arthur pour Podman, swap 4 Gio, service et premier build France.
2. Sauvegarder sources et configuration selon README racine §4. Déployer fichiers avec fins de ligne LF.
3. Exécuter `bash /opt/eona-backend/valhalla/install.sh --apply`.
4. Configurer `/etc/eona/notify.env`, root 0600 : `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`,
   `SMTP_PASS`, `SMTP_FROM`, `ALERT_EMAILS`. Reprendre secrets existants sans les afficher.
   Installer `deploy/eona-valhalla-notify.service` dans `/etc/systemd/system`, puis `daemon-reload`.
   Vérifier configuration avec `eona-notify.js --check` dans environnement correspondant ; aucun mail de test automatique.
5. Lancer signalisation avec nouveau `rebuild.sh`, puis `bash /opt/eona-backend/valhalla/build.sh`.
   Téléchargement neuf validé requis. Succès signalisation facultatif ; preuve PBF obligatoire.
6. Consigner durée, taille et pics dans `VALHALLA-DECISIONS.md` ; calibrer plafonds.
7. Après validation, remplacer **contenu** `/etc/cron.d/eona-signs` par `deploy/eona-geodata.cron`.
   Ne jamais installer deuxième cron du dimanche. Ancienne cron sauvegardée avant remplacement.
8. Déployer backend séparément après accord, `routingEngine=ors`, `VALHALLA_ENABLED=1`,
   `VALHALLA_SEARCH_CUTOFF_M=500`, `VALHALLA_MAX_SNAP_M=250`. Voir aussi `API-WEBAPP.md`.

`install.sh` télécharge image et prépare Quadlet. Il ne lance ni build ni routage.
Premier build crée carte, teste instance temporaire port 8003, puis lance service port 8002.
Deux ports liés à `127.0.0.1` seulement. Aucun changement ufw ou tunnel.

## Reconstruction et retour arrière

`eona-geodata-rebuild.sh` tient verrou commun ; signalisation finit avant Valhalla, même en erreur.
Téléchargement efface preuve précédente, vérifie MD5 du nouveau PBF avant publication puis inscrit SHA-256.
Build exige preuve datant de moins de 24 h et hash différent de carte courante.
Échec téléchargement : aucun build Valhalla. Carte active conservée.

Chaque candidat possède dossier `graphs/<date>-<hash>` : admins, fuseaux, tuiles, archive,
`build-metrics.json`, taille, hash PBF et résultats tests. Ménage automatique après succès seulement
(04/10) : cibles `current` et `previous` gardées, autres dossiers `graphs/` supprimés, chacun écrit au
journal. Échec : candidat gardé pour diagnostic, retiré au succès suivant. Cible `current` introuvable :
rien supprimé.

`test.sh` interroge status détaillé, 50 trajets fixes (`valhalla/trajets.json`), une route Éco et
une route avec étape, avec fournisseur EONA. Aucun appel ORS ou HERE. Tests arrêtent promotion au
premier échec. Trajets versionnés ici depuis 04/10 : `bench/trajets.json`, retiré le 30/09 avec
TomTom, cassait build du 04/10. Échec : ligne et commande écrites dans journal cron.
Trajets fixes ne prouvent pas tous évitements ou frontières ; tests terrain restent nécessaires.

Après succès candidat : arrêt instance temporaire, lien `current`, redémarrage service, mêmes tests.
Échec service final : retour automatique ancienne carte ; premier démarrage échoué laisse service arrêté.
Échec inscrit journal, notification si configurée. Retour manuel : `bash valhalla/rollback.sh`, après accord.

Mesures : `max_child_rss_kib` mesure plus grand RSS d'un processus enfant ; `cgroup_peak_bytes`
mesure pic cgroup, cache inclus, si noyau l'expose. OOM brutal peut empêcher écriture métriques finales.
Durée indisponibilité pendant bascule, p95 API, mémoire France : **à mesurer sur VPS**.

## Contrôles avant feu vert

- Revue indépendante des scripts : ouverte, tâche Claude interrompue ; reprise bridge actuellement bloquée.
- Génération Quadlet et démarrage réels sur Debian : à vérifier après installation autorisée.
- Construction France, tests fournisseur contre moteur réel, frontière belge : à vérifier.
- Échec build, rollback réel, notification SMTP, purge des cartes : à vérifier.
- Port 8002 local, aucun port public supplémentaire ; santé backend et PostgreSQL pendant charge.
- Dimanche 27/09 : cron actuellement déployée exécute signalisation seule.
  Nouvelle chaîne Valhalla reste locale jusqu'au prochain accord.
