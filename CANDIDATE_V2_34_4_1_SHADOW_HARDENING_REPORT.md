# NMC v2.34.4.1 — Shadow A/B hardening report

## Scope

Version 2.34.4.1 remains **SHADOW A/B OBSERVABILITY ONLY**. No shadow decision can
block, delay, publish, mutate, terminate, persist, or notify a public plan. Public
P01, P02, OLS60, entry, TP, SL, quantity, rearm and TP/SL-only lifecycle are unchanged.

## Corrections

- Every shadow entry point in `MarketPlanOrchestrator` is isolated behind a
  `RuntimeException` boundary. A bounded `SHADOW_INTERNAL_ERROR` is attempted, and
  recorder failure is itself contained.
- Public confirmation is installed and recorded before its protected shadow observation.
- Shadow terminals require a fresh, finite, positive traded-market bid and ask. BTC
  freshness remains diagnostic and does not block an already-open shadow terminal.
- Policy version is `SHADOW_V23441_20260801`; every shadow event carries
  `shadowSchemaVersion=SHADOW_SCHEMA_V2`.
- `E60` is the absolute adverse price excursion; `eNormalized` is the distinct `E60/A` value.
- `resultR` is net result divided by the planned stop risk including estimated round-trip fees.
- Added shadow lanes cannot open after the candidate's historical favorable excursion has
  already reached the planned target, even if price later returns.
- Current revalidation classification covers the repository's French and English code
  families while keeping `PRIX_DEJA_TROP_LOIN` non-universal.

## Validation intent

Tests compare the public outputs of real, disabled, and deliberately failing shadow
observers. They cover confirmation, terminal, candidate processing, stale terminal
observations, typed diagnostic metrics, fee-inclusive R, cooldown, bounded deduplication,
and public counter isolation. Stable Samsung installation and new out-of-sample diagnostics
remain required before any future calibration decision.

Local validation completed with 440/440 JVM tests on each of Debug, Stable and Release,
9/9 Python SOL validator tests, all three APK assemblies, and `lintRelease` without error.

This research build does not promise profitability and does not authorize real trading.
`realTradingAllowed=false`; execution remains manual.
