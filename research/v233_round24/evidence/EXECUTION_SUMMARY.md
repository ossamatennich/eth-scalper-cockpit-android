# Local execution summary

## Engine correction and Round 23 replay

- Tests: 17 passed
- Legacy packaged reproduction tolerance: maximum absolute metric delta
  `7.105427357601002e-15` (`<=1e-12`)
- Legacy packaged lifecycle signatures: 43/43
- Deterministic duplicate runs: legacy `true`, corrected `true`
- Trade sequences changed after correction: 43/43
- Holm-FWER passes: 0/43
- Classifications: 12 declined, 1 edge disappeared, 16 essentially unchanged
  but not validated, 14 improved/mixed but not validated
- Old quasi-candidates validated: `false`

## Round 24

- Protocol lock: `2026-07-18T19:06:02Z`
- Protocol SHA-256:
  `ee6649b4667617805d2b4a88bb11dfb731302705d806e66a49fcf1eb1f25fa3e`
- Variants: 108/108
- Directions passing every gate: 0/12
- Duplicate complete runs: byte-identical
- Decision: `ROUND24_REJECTED_NO_DIRECTION_PASSED_ALL_LOCKED_GATES`
- `stable=false`
- `android_integration_allowed=false`

The full candidate-by-candidate replay is in `round23/`. Round 24 variant results,
signal frequency including zero-signal periods, decisions, and both deterministic
manifests are in `round24/`.
