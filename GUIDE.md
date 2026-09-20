# Migration du backend EONA — ce qui a été fait, et comment vivre avec

Migration effectuée le **20/09/2026**, de l'ancien VPS partagé vers une machine dédiée.
Les apps n'ont rien eu à faire : aucune adresse n'a changé.

---

## 1. Le résumé

| | Avant | Maintenant |
|---|---|---|
| Machine | VPS partagé avec d'autres projets | VPS **dédié à EONA** |
| Matériel | i5-11400H, 7,4 Go RAM, 460 Go (27 % pleins) | 8 vCPU, **16 Go RAM ECC**, 180 Go NVMe RAID 10 (10 % pleins) |
| Accès | Tailscale | **SSH par clé** sur `193.168.146.56` |
| API | `https://api.lrda-mercuriale.uk` | inchangée |
| Politique | `https://confidentialite.zylo-app.fr` | inchangée |
| Tunnel Cloudflare | même identifiant | **déménagé**, aucun DNS touché |

**Coupure réelle : moins de deux minutes**, le temps de couper l'ancien, rattraper les dernières
données et ouvrir le tunnel sur le nouveau.

### Ce qui a été transporté

- **La base PostgreSQL entière** : 7,7 Go, soit 1 988 889 panneaux, 5 842 303 routes,
  399 658 services autour, plus les signalements et les corrections de limite.
- **Les 8 comptes** (1 invité, 3 clients, 4 admins) avec leurs statistiques, trajets et parrainages.
- **Les 2 photos de profil**, qui se chargent bien derrière Cloudflare.
- **Les secrets** : jeton admin, deux clés OpenRouteService, clé TomTom, SMTP. Copiés d'une
  machine à l'autre sans jamais passer par un écran ni par le dépôt.
- **Les identifiants du tunnel Cloudflare**, ce qui a évité de toucher au moindre enregistrement
  DNS.

### Ce qui n'a pas été transporté, volontairement

- L'extrait OpenStreetMap de 5,5 Go : il se retéléchargera tout seul à la prochaine
  reconstruction.
- Les vieilles sauvegardes et l'archive de 322 Mo : elles restent sur l'ancien VPS, à récupérer si
  elles comptent.

### Vérifications passées après la bascule

- `/health` : `ok`, 3309 radars, signalisation publiée du 14/09, deux clés ORS prêtes, TomTom actif.
- Panneaux autour de Rennes : 30 trouvés par l'API publique.
- Comptes, signalements et photos de profil : tous présents.
- Routes d'administration (`/api/live/online`, `/api/admin/accounts`) : 200.
- Page de confidentialité : 200 par le domaine public.

---

## 2. L'ancien VPS

Ses trois services EONA sont **arrêtés et désactivés** : ils ne repartiront pas, même après un
redémarrage. Le cron de la signalisation y a été retiré. Les fichiers, la base et les sauvegardes
sont **intacts** : c'est le filet de sécurité.

**À garder au moins une semaine.** Ensuite, pour faire le ménage, uniquement ce qui appartient à
EONA — d'autres projets tournent sur cette machine (Caddy, lazarus-server, medocs) :

```bash
rm -rf /opt/eona-backend /var/lib/eona-signs /opt/eona-backend-src-backup-*.tgz /root/eona-src-backup-*.tgz
rm -f /etc/systemd/system/eona-*.service /etc/cloudflared/eona.yml
rm -rf /etc/systemd/system/eona-backend.service.d /root/.cloudflared
runuser -u postgres -- dropdb eona
runuser -u postgres -- dropuser eona
userdel eona
systemctl daemon-reload
```

### Revenir en arrière, si besoin, avant ce ménage

```bash
# sur le NOUVEAU
systemctl stop eona-tunnel eona-backend
# sur l'ANCIEN
systemctl enable --now eona-backend eona-privacy eona-tunnel
```

Les apps repartent sur l'ancien en quelques secondes. Ce qui aura été créé entre-temps sur le
nouveau (comptes, signalements, rapports de bug) y restera : basculer vite, vérifier vite.

---

## 3. Vivre avec le nouveau serveur

### Les commandes du quotidien

```bash
ssh root@193.168.146.56

systemctl status eona-backend eona-tunnel eona-privacy postgresql --no-pager
journalctl -u eona-backend -f                      # les logs en direct
curl -s http://127.0.0.1:8090/health               # l'état complet
runuser -u eona -- psql -d eona                    # la base
df -h / && free -g                                 # disque et mémoire
tail -30 /var/lib/eona-signs/rebuild.log           # la dernière reconstruction
```

### Déployer une nouvelle version du backend

Depuis le PC, à la racine du dépôt :

```bash
cd backend && tar czf - src package.json package-lock.json | ssh root@193.168.146.56 "cd /opt/eona-backend && tar xzf - && npm ci --omit=dev && chown -R eona:eona /opt/eona-backend && systemctl restart eona-backend && sleep 3 && curl -s -o /dev/null -w 'health %{http_code}\n' http://127.0.0.1:8090/health"
```

### La reconstruction hebdomadaire

Le cron tourne **le dimanche à 03:30** : extrait France frais, import, construction, vérifications,
rejeu des corrections faites à la main, publication. Elle a besoin de beaucoup de place au pic
(jusqu'à 120 Go sur les 180). Le dimanche suivant la migration, vérifier :

```bash
tail -30 /var/lib/eona-signs/rebuild.log && df -h /
```

Si elle échoue, la signalisation en service reste celle d'avant : rien n'est cassé, il y a juste
une donnée un peu plus vieille.

---

## 4. Ce qui reste à faire

1. **Fermer SSH correctement.** Le mot de passe root donné par l'hébergeur a circulé dans une
   conversation : il doit être changé, et l'authentification par mot de passe coupée.

   ```bash
   passwd
   sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin prohibit-password/' /etc/ssh/sshd_config
   sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
   systemctl restart ssh
   apt-get install -y fail2ban && systemctl enable --now fail2ban
   ```

   Ne ferme pas ta session tant qu'une **nouvelle** connexion par clé n'a pas fonctionné.

2. **Mieux : SSH sans port ouvert du tout**, à travers le tunnel qui sert déjà l'API. La marche à
   suivre est dans [backend/DEPLOY.md](backend/DEPLOY.md), section 9. Une fois en place :
   `ufw delete allow 22/tcp`, et la machine n'a plus aucun port ouvert.

3. **Sauvegardes.** Il n'y en a aucune d'automatique aujourd'hui. Ce qui compte vraiment tient
   dans quelques mégaoctets : `data/accounts.json`, `data/device-trials.json`, `data/avatars/` et
   le schéma `crowd` de la base. Le reste (2 millions de panneaux) se reconstruit à partir
   d'OpenStreetMap. Un cron quotidien suffirait :

   ```bash
   runuser -u eona -- pg_dump -d eona -Fc -n crowd -f /var/backups/eona-crowd-$(date +\%F).dump
   ```

4. **L'adresse de la webapp d'administration**, quand ton ami l'aura : à déclarer sur le nouveau
   serveur (`WEBAPP_ORIGINS`), sinon son navigateur restera bloqué. Commande dans
   [backend/API-WEBAPP.md](backend/API-WEBAPP.md).

5. **Vider l'ancien VPS**, une semaine après, avec le bloc de la section 2.
