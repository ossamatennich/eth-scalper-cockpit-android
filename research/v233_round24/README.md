# ETH v2.33 — corrected research engine and Round 24

This tree is research-only. It does not modify the SCALP engine, Android, Java,
Gradle, the APK, or the user interface.

## Final decision

- `stable=false`
- `android_integration_allowed=false`
- Round 23 legacy reproduction: 43/43 lifecycle signatures and packaged metrics
  reproduced within `1e-12`
- Round 23 corrected reproduction: 43/43 candidates, zero candidate passes the
  locked Holm family-wise correction
- Round 24: 108/108 preregistered variants, zero of 12 directions passes every
  locked gate
- Round 22 holdout: not loaded and not used for selection, tuning, or validation

## Layout

- `legacy_engine/`: exact Round 23 engine used for the pre-correction replay
- `corrected_engine/`: F01–F08 corrections
- `protocol/round23/`: immutable Round 23 protocols and original checksums
- `protocol/round24/`: preregistration locked before Round 24 evaluation
- `reference/`: immutable packaged Round 23 metric and trade-sequence baseline
- `src/`: deterministic reproduction, comparison, and Round 24 runners
- `tests/`: unit, integration, causality, portfolio-risk, and scope-firewall tests
- `evidence/`: compact pre/post and Round 24 reports

The complete trade-level outputs, duplicate deterministic runs, manifests, Git
bundle, patches, and transfer instructions are built into the final deliverable.
