# NMC Stable 6.6 — nettoyage final du runtime historique

Version : `23466` / `2.34.6.6` / `NMC Stable 6.6`.

Le runtime de production est désormais exclusivement composé de `V4ForegroundService`, `V4RuntimeCoordinator`, `V4MarketDataClient` et `V4MainActivity`. Les sources historiques restent disponibles pour les tests et l'audit, mais `MainActivity` et `MarketWatchService` ne sont plus déclarés dans le manifeste et ne sont lancés ni par l'Activity V4 ni par le boot.

Le service foreground publie une notification silencieuse LOW sur `nmc_v4_monitor_v1`, dont le contenu provient de `V4RuntimeCoordinator.status()` et dont le clic cible uniquement `V4MainActivity`. Au démarrage, il annule la notification historique `22801` et retire le canal `eth_scalper_watch_v22801`.

Le canal fort `nmc_final_signal_loud_v2`, le son `eth_alert_loud`, la vibration `0,750,180,750,180,1200` et la déduplication des alertes de plan restent inchangés. Le moteur, le modèle, l'alpha, le sizing, les barrières et `realTradingAllowed=false` restent gelés.
