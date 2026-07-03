# ETH Scalper Cockpit v2.19.0 Android APK

Cette version transforme le cockpit v2.18.5 en projet Android natif.

## Objectif

- Garder une surveillance ETH/BTC active même si l'application passe en arrière-plan.
- Afficher une notification permanente `ETH Scalper actif`.
- Recevoir des notifications de signal depuis un service Android natif.
- Garder l'interface cockpit HTML existante dans une WebView locale.
- Rester gratuit : pas de serveur, pas d'abonnement, pas de Play Store obligatoire.

## Contenu technique

- `app/src/main/assets/www/` : cockpit web v2.18.5 intégré et renommé v2.19.0 Android.
- `MainActivity.java` : WebView locale + démarrage du service permanent.
- `MarketWatchService.java` : foreground service Android, WakeLock, WebSocket Binance Futures, reconnexion automatique, notifications natives.
- `BootReceiver.java` : redémarrage du service après redémarrage téléphone / mise à jour APK.
- `.github/workflows/build-apk.yml` : build APK gratuit via GitHub Actions.

## Important

Le moteur web complet reste dans l'interface. La couche native ajoute une surveillance permanente gratuite.
La logique native de notification est volontairement conservatrice pour v2.19.0 : elle sert de première base Android permanente. Le perfectionnement fin du moteur continue ensuite.

## Build gratuit via GitHub Actions

1. Créer un dépôt GitHub gratuit.
2. Envoyer tout le contenu de ce dossier à la racine du dépôt.
3. Aller dans l'onglet **Actions**.
4. Lancer **Build Android APK**.
5. Télécharger l'artifact `ETH-Scalper-Cockpit-v2.19.0-debug-apk`.
6. Installer l'APK sur le téléphone.

## Réglages téléphone indispensables

Après installation :

1. Autoriser les notifications.
2. Autoriser l'installation depuis cette source si Android le demande.
3. Batterie > ETH Scalper Cockpit > mettre en **Non restreint** / **Unrestricted**.
4. Autoriser l'application à ignorer l'optimisation batterie quand la fenêtre le propose.
5. Laisser la notification permanente active tant que tu veux la surveillance.

## Limite honnête

Une application Android avec foreground service peut rester active bien mieux qu'une PWA Cloudflare.
Mais Android/Samsung peut quand même tuer des apps si l'utilisateur force l'arrêt, active économie d'énergie agressive, ou retire l'autorisation batterie.
