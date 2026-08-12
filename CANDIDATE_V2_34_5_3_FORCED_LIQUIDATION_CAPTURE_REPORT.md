# NMC 2.34.5.3 — Forced Liquidation Capture V3

Cette version est exclusivement une évolution du collecteur de recherche. Le moteur public
`NMC_SCALP_CV_CORE_V1` reste inchangé et `realTradingAllowed=false`.

## Flux et contrat

MARKET_WS conserve `aggTrade` et `kline_1m` et ajoute, une fois par symbole,
`ethusdt@forceOrder`, `solusdt@forceOrder` et `btcusdt@forceOrder`. PUBLIC_WS reste limité à
`bookTicker` et `depth20@100ms`. Le flux global `!forceOrder@arr` n'est pas utilisé et aucun
fallback REST de liquidation n'est créé.

Selon le contrat Binance USD-M Futures, `forceOrder` est un *Liquidation Order Snapshot Stream* :
il publie au plus le dernier snapshot observé dans sa fenêtre d'environ une seconde et reste
silencieux quand aucune liquidation ne survient. Cette donnée est donc naturellement rare et non
exhaustive à l'échelle sous-seconde.

## Schéma causal V3

`NMC_CAUSAL_MARKET_CAPTURE_V3` (`formatVersion=3`) ajoute le kind
`LIQUIDATION_SNAPSHOT`. Chaque record conserve les horloges locales causales, le symbole, la
provenance MARKET_WS, les timestamps Binance et les champs bruts de l'ordre (`S`, `o`, `f`, `q`,
`p`, `ap`, `X`, `l`, `z`). Les nombres non finis, timestamps invalides, symboles non supportés et
payloads incomplets sont rejetés sans atteindre le writer. Le stockage segmenté, borné et CRC
reste le même ; le replay accepte V1, V2 et V3.

## Santé et limites

Une session saine peut légitimement contenir zéro liquidation. Aucun âge ou compteur forceOrder
n'entre dans les conditions de santé. Une liquidation récente ne peut pas masquer un flux
`aggTrade` absent ou stale. Les diagnostics signalent explicitement que le stream est configuré,
naturellement sparse et non exhaustif. Aucune liquidation manquante n'est inventée.

Les snapshots serviront seulement à une future analyse offline. Ils n'activent aucun signal,
filtre, seuil, sizing ou ordre. Cette capture ne constitue ni une stratégie ni une promesse de
rentabilité.
