# NMC Stable 6.1 — V4 operational fix report

## Scope

Bug-fix release only for `NMC_PROP_DAILY_HYBRID_V4`. No model retraining, alpha change, threshold change, universe change, leverage change, TP/SL change, risk-per-source change, private API, or automatic order execution.

## Corrections

- FALLBACK daily-best history is persisted and keyed by one normalized completed UTC day. Reads use only strictly prior days; same-day calls are immutable/idempotent; legacy duplicate dates retain the first record deterministically.
- An `ORDER_PLACED` plan whose unresolved 1m bar contains ENTRY and SL is accounted as filled at ENTRY and then closed at SL. ENTRY+TP+SL is also STOP-first, symmetrically for LONG and SHORT.
- Plan quantity becomes immutable after publication/external commitment. Existing plans reserve theoretical stop risk from frozen quantity; only the new plan uses the remaining 2.40% budget. Unsafe minimum sizing is persisted as `RISK_CAP_REACHED` and is non-actionable.
- Fresh-parent and CORE second-segment continuation guards are independent. Same-symbol fresh decisions replace stale continuations; no third continuation and no more than two logical active plans are allowed.
- Cross-sectional snapshots and seed history use one explicit UTC cutoff date. Missing or late-listed assets are absent instead of contributing a stale D-1 row.

## Frozen artifacts

- model SHA-256: `207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680`
- Stable 6.0 frozen-manifest canonical LF SHA-256: `47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3`
- both original files are unchanged; Stable 6.1 operational code is covered separately by `v4_operational_6_1_manifest.json`.

## Real Binance smoke

At `2026-08-15T16:49:02.833816Z`, unsigned public checks passed for daily BTCUSDT klines, BTCUSDT/AAVEUSDT exchange metadata, ten chronological BTCUSDT 1m bars, and the exact Android WebSocket endpoint `wss://fstream.binance.com/public/stream?streams=...`. Real bookTicker events were observed for BTCUSDT, ETHUSDT, SOLUSDT, AAVEUSDT, BNBUSDT and XRPUSDT. Full evidence is in `NMC_STABLE_6_1_BINANCE_SMOKE_REPORT.json`.

## Validation

- Debug/Stable/Release JVM unit tests: green, zero failures/errors/skips.
- Python deterministic tests: 33 green.
- Python/Java ExtraTrees fixture parity: green.
- `assembleDebug`, `assembleStable`, `assembleRelease`, and `lintRelease`: green.
- Stable APK identity: `com.ethscalper.cockpit.stable`, `23461`, `2.34.6.1`, `NMC Stable 6.1`.

`realTradingAllowed=false` remains enforced and `NMC_SCALP_CV_CORE_V1` remains blocked from publishing new actionable plans.
