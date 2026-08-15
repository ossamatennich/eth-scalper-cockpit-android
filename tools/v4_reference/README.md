# NMC_PROP_DAILY_HYBRID_V4 reference builder

The builder consumes validated Binance USD-M daily panels, creates the frozen
14-feature fallback targets through 2025-12-31, fits the two specified
ExtraTrees regressors, and serializes every tree for deterministic Android
inference. It deliberately excludes 2026 targets and raw data from the APK.
