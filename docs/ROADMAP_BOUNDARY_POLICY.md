# NPDev Full Maturity Closure Roadmap Boundary Policy

This policy governs the NPDev Full Maturity Closure Roadmap. It is a finite maturity-maximization roadmap with hard closure. It is not a source for automatic new phases, checkpoints, epics, or another open-ended analysis cycle.

The authoritative human-provided roadmap input for Checkpoint 0 is:

```text
C:\Users\Marcelo\Downloads\npdev_full_maturity_closure_roadmap_updated.md
```

Checkpoint evidence preserves the exact roadmap input and SHA-256 hash under `artifacts/roadmap/`.

## Maturity Baseline and Target

All Checkpoint 0 evidence uses these normalized values:

| Measure | Value |
|---|---:|
| Current overall maturity | 7.8/10 (~78%) |
| Target maturity | 9.2-9.5/10 (~92-95%) |

If a source roadmap copy contains an older target, Checkpoint 0 records that as a source inconsistency and normalizes generated evidence to `9.2-9.5/10 (~92-95%)`.

## Authoritative Checkpoint List

The roadmap contains exactly these 16 checkpoints:

0. Honest State and Closure Contract
1. Phase-2 Postgres and Linux Residual Fixes
2. RuntimeHost Integration Test Infrastructure
3. Trusted-Source and Custom Scenario Reconciliation
4. Report Bootstrap and Evidence Regeneration
5. Portable Tooling and Path Neutrality
6. Gradle-Native Validation Migration
7. Schema Consolidation and Strict Legacy Rejection
8. Stateful Additive Migration Support
9. Incremental Migration Test Harness
10. Trusted Source Security Hardening
11. Shift-Left AI Safety and Schema Hardening
12. Custom UX and Extensibility Support
13. React Editor Decomplexification
14. DSL Parser Robustness
15. CI Parallelization, Caching, Onboarding, and Final Closure

No checkpoint may be added, removed, renamed, split, merged, or reordered without explicit human approval.

## Beta0 Tag Rule

The existing `beta0` tag is immutable for this maturity-closure cycle. Automation and agents must not move, recreate, delete, reinterpret, retag, or redefine the existing `beta0` tag. This roadmap starts after Beta0 has already been tagged and pushed.

Beta0 evidence must use the peeled tag commit, not the tag object. The accepted commands are:

```powershell
git rev-parse beta0^{}
git rev-list -n 1 beta0
```

`beta0-verified` may be declared only when the peeled `beta0` commit matches the commit recorded in Beta0 closure evidence.

## No-New-Roadmap Rule

This roadmap is not allowed to grow itself. New findings do not create new phases, checkpoints, roadmaps, release gates, or broad V1 feature tracks automatically. Any proposal to add or reshape scope requires explicit human approval before implementation.

## Finding Classification Taxonomy

Every new issue found during implementation must be classified as exactly one of:

| Classification | Meaning | Action |
|---|---|---|
| `current-checkpoint-blocker` | Prevents the current checkpoint from passing honestly | Fix before checkpoint approval |
| `current-roadmap-blocker` | Prevents final roadmap closure | Fix before final closure |
| `known-risk-accepted` | Real issue, documented, and not blocking this roadmap | Record in the known-risk ledger |
| `post-roadmap-backlog` | Real future work outside this roadmap | Add to backlog; do not expand the checkpoint |
| `human-decision-required` | Requires product, human, admin, or release decision | Add to the human decision register |
| `invalid-or-duplicate` | Not reproducible, already fixed, duplicate, or out of date | Close with evidence |

Findings with any other classification are invalid for this roadmap. Failures must not be hidden by reclassification unless the checkpoint explicitly permits that decision.

## Checkpoint Evidence Requirements

Cursor local runs must produce each checkpoint evidence bundle under:

```text
D:\WorkSpace\NPDev_General__OutsideRepo\temp\last-roadmap
```

Cloud/Codex fallback locations are:

```text
$env:NPDEV_CHECKPOINT_DIR
docs/maturity-closure/checkpoints/last-roadmap
```

Each checkpoint bundle must include:

```text
checkpoint-summary.md
checkpoint-result.json
acceptance-matrix.md
progress-delta.json
changed-files.txt
git-diff.patch
incremental-diff-from-previous-checkpoint.patch
validation-commands.txt
validation-output.txt
bundle-size-report.md
omitted-large-artifacts.md
omitted-large-artifacts.json
artifacts/
```

Checkpoint 0 additionally includes:

```text
artifacts/roadmap/npdev_full_maturity_closure_roadmap_updated.md
artifacts/roadmap/roadmap-sha256.txt
```

The main review zip must remain under 100 MB. Bulky artifacts such as `node_modules`, `.gradle`, `build`, `dist`, `target`, generated application build directories, browser videos, duplicate logs, and other nonessential generated outputs must be omitted or split into a secondary archive with manifest entries for size, SHA-256 hash, and review impact.

Every checkpoint summary and result must state what the checkpoint does not solve.

## Closure Definition

This roadmap is complete only when:

1. Checkpoints 0 through 15 are accepted.
2. Each checkpoint has a reviewed evidence bundle.
3. The final maturity closure evidence passes.
4. Any remaining issues are classified as `known-risk-accepted`, `post-roadmap-backlog`, `human-decision-required`, or `invalid-or-duplicate`.
5. No checkpoint remains `failed`, `partial`, or `deferred`.
6. No new roadmap is automatically generated from this roadmap.
7. The `beta0` tag has not been moved, recreated, deleted, or reinterpreted.

Closure does not mean NPDev has no future work. Closure means the specific findings integrated into this bounded roadmap have been addressed, validated, or explicitly classified outside this bounded scope.

## Dirty Worktree Handling

Existing uncommitted work is preserved. Dirty worktree state is recorded as evidence only. It is not treated as a Beta0 retag action and is not an automatic Checkpoint 0 blocker.

## Checkpoint 0 Does Not Solve

Checkpoint 0 does not modify product code, fix Postgres or Linux fidelity, fix golden scenarios, address schema/parser gaps, implement migrations, refactor UI code, speed up CI, clean the worktree, or change the `beta0` tag. Does not proceed to Checkpoint 1. It only establishes honest state, closure policy, evidence discipline, and whether Checkpoint 1 is unblocked.
