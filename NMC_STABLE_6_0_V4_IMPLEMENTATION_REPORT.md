# NMC Stable 6.0 — V4 implementation report

## Identity

- versionCode: `23460`
- versionName: `2.34.6.0`
- stable label: `NMC Stable 6.0`
- public engine: `NMC_PROP_DAILY_HYBRID_V4`
- automatic trading: disabled (`realTradingAllowed=false`)

## Runtime

The V4 registry contains exactly the 53 canonical Kraken Prop crypto assets and
one imposed leverage per asset. Daily Binance USD-M bars are validated and
cached from 2023 where the exact contract exists. Current monitoring uses one
filtered combined `bookTicker` socket; no 53-asset depth collector exists.
Only followed plans request bounded 1-minute bars after a monitoring gap.

CORE uses the full available panel for its common return and limits candidate
publication to BTC/ETH/SOL/BNB/XRP. FALLBACK evaluates the frozen 14-feature
LONG/SHORT ExtraTrees asset trained through 2025-12-31 only. The model SHA-256
is `207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680`.

The legacy CV Core still records diagnostic observations but its public
publication function fails closed with
`LEGACY_ENGINE_PUBLICATION_DISABLED_BY_V4`.

## Manual execution safety

Plans freeze their entry and never chase price. Their persisted lifecycle
distinguishes executable, limit-order possible, locally declared order, open,
missed, invalidated, expired and terminal states. Ambiguous 1-minute bars apply
SL first. Quantity uses exchange step/minimum metadata and always rounds down.
The UI never sends an order and requires no Kraken or Binance private secret.
