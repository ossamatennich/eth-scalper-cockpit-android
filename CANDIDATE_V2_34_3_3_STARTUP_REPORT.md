# NMC v2.34.3.3 — démarrage fiable du service Android

## Cause racine

Le chemin Binance Futures `wss://fstream.binance.com` est valide et reste prioritaire. Le défaut
se produisait avant son ouverture : le service relisait et migrait les journaux JSONL persistants,
puis reconstruisait potentiellement leur index, avant d'appeler `startForeground()`.

Sur un téléphone conservant les diagnostics de plusieurs versions, ce travail peut dépasser le
délai de démarrage imposé par Android. Le système arrête alors le service avant la première
requête WebSocket ou REST. L'absence simultanée de prix ETH, SOL et BTC, y compris après ajout du
secours REST, confirme que le problème se situe au démarrage du service plutôt que sur une seule
route Binance.

## Correction

- notification foreground publiée immédiatement dans `onCreate()` ;
- aucun scan, aucune migration et aucun accès réseau avant cette publication ;
- index recorder chargé par un chemin rapide qui ne lit jamais les JSONL ;
- journaux historiques conservés tels quels et toujours disponibles pour l'export ;
- plans et réarmements restaurés comme auparavant ;
- Binance Futures reste la première source ; les secours restent occasionnels ;
- aucune modification du moteur de décision ou du lifecycle TP/SL.
