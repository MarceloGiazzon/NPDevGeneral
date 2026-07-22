# NPDev Maturity Closure Ledger

This ledger records the bounded maturity-closure contract for the NPDev Full Maturity Closure Roadmap.

## Baseline and Target

| Measure | Value |
|---|---:|
| Current overall maturity | 7.8/10 (~78%) |
| Target maturity | 9.2-9.5/10 (~92-95%) |

Checkpoint 0 normalizes generated evidence to these values. If the human-provided roadmap source includes an older target, it is recorded as a source inconsistency and not propagated into CP0-generated evidence.

## Closure Contract

The roadmap contains exactly 16 checkpoints. Checkpoints may not be added, removed, renamed, split, merged, or reordered without explicit human approval.

Every new finding must use exactly one allowed classification:

| Classification | Closure handling |
|---|---|
| `current-checkpoint-blocker` | Must be resolved before the current checkpoint can pass. |
| `current-roadmap-blocker` | Must be resolved before final roadmap closure. |
| `known-risk-accepted` | Documented and accepted as non-blocking for this roadmap. |
| `post-roadmap-backlog` | Deferred outside this roadmap without expanding scope. |
| `human-decision-required` | Registered for human/product/admin decision. |
| `invalid-or-duplicate` | Closed with evidence. |

## Beta0 Truth

Checkpoint 0 verifies Beta0 using the peeled tag commit from `git rev-parse beta0^{}` or `git rev-list -n 1 beta0`. Beta0 may not be retagged, moved, deleted, recreated, or reinterpreted by this roadmap.

The repository state is declared by `scripts/reports/out/beta0-state-truth-report.json` as one of:

| State | Meaning |
|---|---|
| `beta0-verified` | The peeled `beta0` commit matches the commit recorded in Beta0 closure evidence. |
| `beta0-stale-evidence` | The tag exists, but available closure evidence is missing or does not match the peeled commit. |
| `pre-release-hardening` | The `beta0` tag is missing. |

## Checkpoint 0 Does Not Solve

Checkpoint 0 does not modify product code, fix technical gaps, clean the worktree, retag Beta0, or proceed to Checkpoint 1. It establishes the honest-state and closure contract that later checkpoints must obey.
