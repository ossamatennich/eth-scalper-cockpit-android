# GitHub state and single-pass handoff

GitHub was rechecked at `2026-07-18T19:31:27Z` before final packaging.

- Repository: `ossamatennich/eth-scalper-cockpit-android`
- Default branch: `main`
- Verified remote head: `7edd5719c3f767f86726ef58c405974515763f4e`
- Head message: `Fix Round 22 holdout workflow YAML payload indentation`
- Dedicated local branch: `agent/v233-research-engine-round24`

The connected GitHub integration can read the repository but branch creation is
rejected by GitHub with HTTP 403 `Resource not accessible by integration`.
Direct Git authentication is also unavailable in this workspace. Therefore no
remote branch, commit, workflow run, or artifact ID is claimed.

The final archive contains one Git bundle and ordered format-patches. A single
authorized Codex pass can import the bundle, verify that `main` is still at the
base SHA above, push the dedicated branch, and monitor
`.github/workflows/v233-round24-corrected-research.yml`. The workflow performs
the scope firewall, corpus/protocol checksum checks, all tests, duplicate Round
23 and Round 24 calculations, determinism comparison, and immutable artifact
upload. It must not merge or modify Android/SCALP files.
