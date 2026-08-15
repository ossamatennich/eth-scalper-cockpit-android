# NMC 2.34.5.1 — Reliable Microstructure Capture V2

## Objet

Cette livraison est exclusivement une correction de capture et d’observabilité. Le moteur public
`NMC_SCALP_CV_CORE_V1`, ses trois routes, leurs seuils, priorités, TP/SL, budgets, frais, sizing,
publication, alertes et lifecycle ne changent pas. `realTradingAllowed=false` et aucune API Binance
privée ou exécution automatique n’est ajoutée.

## Causes confirmées dans la 2.34.5.0

1. La capture `aggTrade` WS n’était appelée qu’après acceptation par `MarketDataRouter`. Le fallback
   REST, relancé périodiquement même quand le WS était sain, avançait `lastAggTradeId`; les messages
   WS correspondants étaient alors rejetés avant d’atteindre la capture causale.
2. Les fallbacks kline/trade avaient une condition temporelle autonome qui provoquait des appels
   REST périodiques au lieu de les limiter aux flux manquants ou périmés.
3. Chaque `bookTicker` était persisté. Le writer attendait jusqu’à deux secondes sans signal de
   haute pression, permettant à la file bornée de saturer avant son premier drain.

## Architecture V2

- socket publique moteur inchangée ;
- socket recherche indépendante Binance USD-M Futures pour `aggTrade` et `depth20@100ms` sur
  ETHUSDT, SOLUSDT et BTCUSDT ;
- top-of-book latest-wins persisté au plus une fois par bucket de 250 ms ;
- agressions BUY (`m=false`) et SELL (`m=true`) agrégées par bucket de réception locale de 100 ms ;
- déduplication bornée par aggTrade ID entre socket publique, socket recherche et REST, avec
  provenance, transitions de source, IDs manquants, doublons et retards comptés ;
- snapshots top 20 complets, latest-wins et persistés au plus une fois par 250 ms ;
- file non bloquante bornée, réveil sur premier élément/haute pression, batch max 75 ms et
  `DROP_SUMMARY` explicite dès que la capacité revient ;
- segments compressés CRC32 et FIFO bornés, lecteur V1/V2 et manifeste par kind, symbole, source,
  plage temporelle, gap, drop, corruption et troncature ;
- états `MICRO_CAPTURE_HEALTHY`, `MICRO_CAPTURE_DEGRADED` et `MICRO_CAPTURE_STALE` indépendants
  de l’autorité du feed de trading.

La V2 capture le top 20, pas un carnet local complet de 5 000 niveaux. Cette limite est volontaire
pour garder une synchronisation déterministe et testable sur Android.

## Recherche offline

`tools/microstructure_research.py` construit causalement flow signé, ratios, CVD 1/5/15/60 s,
accélération, spread, microprice, notionnels/imbalances top 5/10/20, variations de profondeur,
replenishment/depletion, proxies d’absorption/exhaustion/sweep/divergence et contexte ETH/SOL/BTC.
Les fenêtres sont vidées aux gaps/drops et aucun point futur n’est interpolé.

Ces features sont des données de recherche uniquement. Elles ne sont ni une stratégie activée,
ni une preuve de prédictivité, ni une promesse de rentabilité.
