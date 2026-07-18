# Round 23 audit findings F01–F08

| Finding | Locked correction | Automated coverage |
|---|---|---|
| F01 | Every required gate is evaluated independently for `LONG`, `SHORT`, and `COMBINED`; a combination cannot rescue a failed direction. | Scoped-gate unit tests plus corrected Phase 6/7 integration decisions |
| F02 | Collision priority is fixed before results; same-direction overlap rejects the new trade; opposite directions are permitted only within 0.30% total risk, 2.0 total leverage, and two active sleeves. | Collision, overlap, risk-cap, and leverage-cap tests |
| F03 | The one-sided null uses circular moving blocks of three consecutive calendar months; Holm–Bonferroni is applied across all searched variants and separately across outer direction streams. | Multiple-testing and temporal-null tests; Round 23 and Round 24 reports |
| F04 | Bollinger period/deviation and close-location inputs are effective; duplicate effective neighbors are forbidden and removed. | Parameter-activity and unique-neighbor tests |
| F05 | Bootstrap and Monte Carlo retain monthly blocks, chronological sequences inside blocks, entry-time clusters, and simultaneous sleeve losses. | Temporal-dependence and clustered-loss tests |
| F06 | Four-hour bars use only completed one-hour bars, ignore zero-volume placeholder OHLC, and become available only at block close. | Repaired 4 h resampling and causal availability tests |
| F07 | A fold accepts only trades with entry inside the test interval and `exit_time < test_end_exclusive`; purge and embargo boundaries are enforced. | Boundary, purge, embargo, and crossing-exit tests |
| F08 | `max_hold_bars` and time decay are counted from fully completed post-entry bars; exit reason and effective hold parameters are emitted. | Exact-bar hold and time-decay tests |

## Portfolio rule locked before evaluation

Round 23 correction certification uses fixed priority
`DRE_SHORT`, `VCE_SHORT`, `FOLLOW_SHORT`, `MOMENTUM_LONG`, `VCE_LONG`.
Round 24 uses canonical candidate-ID order. Neither priority may depend on an
observed outcome. Risk is 0.15% per sleeve, capped at 0.30% total; leverage is
capped at 1.0 per sleeve and 2.0 total.

## Selection control

Round 23 replays exactly the 43 historical central candidates. Round 24 locks six
genuinely distinct architecture families, two directions, and nine effective
variants per direction, for a hard budget of 108. No adaptive addition or
threshold relaxation is allowed. Unadjusted significance never validates a
candidate.

## Validation boundaries

Round 24 uses nested monthly walk-forward validation with 72-hour purge and
48-hour edge embargo, external annual folds with a 168-hour boundary embargo,
fee/slippage/delay/gap stresses, temporal bootstrap, and clustered temporal
Monte Carlo. LONG and SHORT must pass separately before combination. The Round
22 holdout is permanently diagnostic-only and the runner never loads it.
