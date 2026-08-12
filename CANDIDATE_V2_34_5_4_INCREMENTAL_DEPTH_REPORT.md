# NMC 2.34.5.4 — Incremental Depth Capture V4

Cette version est exclusivement une évolution du collecteur. Le moteur public `NMC_SCALP_CV_CORE_V1`, ses trois voies, seuils, priorités, TP/SL, budgets, sizing, frais, publication et lifecycle restent inchangés. `realTradingAllowed=false` et aucune API Binance privée ni exécution automatique n’est ajoutée.

## Flux et contrat Binance

Les flux 5.3 restent inchangés : PUBLIC_WS transporte `bookTicker` et `depth20@100ms`; MARKET_WS transporte `aggTrade`, `kline_1m` et `forceOrder`. Un troisième socket public isolé transporte exactement `ethusdt@depth@100ms`, `solusdt@depth@100ms` et `btcusdt@depth@100ms`.

L’implémentation suit la documentation officielle USD-M Futures : les quantités d’un diff sont absolues, une quantité zéro supprime un niveau, le premier événement synchronisé recouvre le `lastUpdateId` de l’ancre REST, puis `pu` doit correspondre au `u` précédent. L’ancre est obtenue sans authentification via `/fapi/v1/depth?symbol=...&limit=1000`. La limite 1000 fournit une base robuste tout en restant dans la limite officielle et dans le stockage borné existant.

## Schéma et causalité

`NMC_CAUSAL_MARKET_CAPTURE_V4` ajoute, à la fin des kinds existants :

- `DEPTH_DIFF`, un record par message, sans coalescing, avec `E`, `T`, `U`, `u`, `pu` et les deltas bid/ask bruts ;
- `DEPTH_BOOTSTRAP`, avec les temps de requête/réponse, `lastUpdateId`, la limite et le snapshot complet.

Le replay reste compatible V1/V2/V3/V4. Les ruptures, drops, reconnexions et échecs de bootstrap invalident explicitement la reconstruction du symbole concerné. Une nouvelle ancre ouvre un nouvel intervalle ; aucun trou n’est interpolé.

## Santé isolée

`usableForIncrementalDepthResearch` est distinct de `usableForMicrostructureResearch`. Il exige le troisième socket connecté, un diff récent et une reconstruction ancrée/continue pour les trois symboles, ainsi qu’un writer sain sans saturation critique. Une panne incremental-depth ne dégrade pas la santé validée des flux 5.3, et les flux 5.3 sains ne rendent pas artificiellement le nouveau domaine utilisable.

## Stockage et limites

Les diffs passent par la queue bornée, le writer asynchrone, les segments compressés et CRC existants. Aucun callback WebSocket n’effectue d’I/O disque. Un drop `DEPTH_DIFF` est compté par kind et invalide la continuité. Le volume sera sensiblement supérieur à V3 puisqu’aucun diff n’est coalescé ; le manifest expose les comptes par kind, symbole et source, les octets source, le high-water et les drops.

L’application ne construit pas un carnet 5000 niveaux en direct et n’utilise aucune donnée incremental-depth pour décider d’un signal. La reconstructibilité doit être confirmée sur une vraie session Android ; aucune donnée live n’est simulée. Cette collecte ne constitue ni une stratégie ni une promesse de rentabilité.
