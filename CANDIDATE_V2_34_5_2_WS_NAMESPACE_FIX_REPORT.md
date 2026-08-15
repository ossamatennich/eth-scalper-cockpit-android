# NMC 2.34.5.2 — Market/Public WebSocket Namespace Fix

## Portée

Cette livraison corrige uniquement le transport public Binance USD-M Futures et la validité des diagnostics microstructure. Le moteur public `NMC_SCALP_CV_CORE_V1`, ses routes, seuils, priorités, TP/SL, budgets, frais, sizing, publication, alertes et lifecycle sont inchangés. `realTradingAllowed=false`; aucune API privée et aucun ordre automatique ne sont ajoutés.

## Cause et correction

La 5.1 construisait encore ses connexions combinées sur le namespace générique `/stream?streams=`. La session Android réelle recevait le top-of-book et `depth20`, mais aucun `aggTrade` ou `kline` WebSocket. La 5.2 crée deux connexions strictes et indépendantes :

- `PUBLIC_WS`: `wss://fstream.binance.com/public/stream?streams=...`, avec uniquement `bookTicker` et `depth20@100ms` pour ETHUSDT, SOLUSDT et BTCUSDT;
- `MARKET_WS`: `wss://fstream.binance.com/market/stream?streams=...`, avec uniquement `aggTrade` et `kline_1m` pour les trois symboles.

Le routeur refuse une famille reçue sur la mauvaise socket. Le fallback REST Futures `aggTrades` reste disponible, tracé comme REST et dédupliqué, mais ne peut jamais remplacer la preuve d'un flux MARKET_WS continu.

## Santé et diagnostic

`usableForMicrostructureResearch=true` exige simultanément une session V2 valide, le writer sain, aucune saturation persistante, des échantillons top-of-book et `depth20` PUBLIC_WS récents sur les trois symboles, des `aggTrade` MARKET_WS acceptés et récents sur les trois symboles, des buckets flow causaux et les deux sockets connectées. Un symbole MARKET_WS absent ou périmé force au minimum `MICRO_CAPTURE_DEGRADED` et `usableForMicrostructureResearch=false`.

Les diagnostics séparent connexions, reconnexions et échecs PUBLIC/MARKET. Les fermetures sont conservées dans un registre FIFO borné avec endpoint, timestamp, code et raison WebSocket, statut HTTP de handshake si disponible, classe/message d'exception borné, tentative de reconnexion et âge du dernier message valide.

Cette version ne garantit ni qualité réseau future, ni performance de marché, ni rentabilité. Une nouvelle session Android réelle reste nécessaire pour confirmer `wsAggTrade > 0`, `wsKline > 0`, `wsDepth20 > 0` et une santé `MICRO_CAPTURE_HEALTHY` sur un réseau sain.
