# NMC Stable 6.3 — UI/UX final

Version : `23463` / `2.34.6.3` / `NMC Stable 6.3`.

Cette livraison modifie uniquement l'interface native et la vérité du statut runtime affiché. Le moteur public reste `NMC_PROP_DAILY_HYBRID_V4`, ses règles, son sizing, son univers, ses signaux et son modèle restent inchangés.

## Corrections

- Safe area Android appliquée à l'en-tête et à la navigation basse pour les modes gestuel et trois boutons.
- Accueil et Plans entièrement scrollables ; une carte principale et toutes les autres cartes actives restent consultables.
- Copie exacte de `ENTRY`, `TP` et `SL` vers le presse-papiers, avec retour discret « Copié ».
- Badge `ACTIF` / `SYNCHRO` / `HORS LIGNE` calculé depuis le réseau validé, l'état du socket Binance, la fraîcheur bookTicker (15 s), la synchronisation daily et la dernière analyse V4 réussie.
- Navigation réelle testée sur l'émulateur CI, en plus du smoke test de lancement de l'APK Stable.

## Gel et sécurité

- Modèle SHA-256 : `207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680`.
- Manifeste gelé SHA-256 : `47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3`.
- `realTradingAllowed=false`.
- Aucune API privée et aucun ordre automatique.
