# Release Evidence Source Of Truth

This document describes the aggregate release evidence model. For AI-only Beta 0, the aggregate gate must include the current AI beta proof from `scripts\reports\out\ai-beta-gate-report.json`. Historical packaged evidence is diagnostic only and is not current AI-only Beta 0 proof.

NPDev release candidate readiness is decided by the aggregate report:

```text
scripts\reports\out\beta-release-gate-report.json
```

Beta 0 tagging is decided by the final closure report:

```text
scripts\reports\out\beta0-final-closure-report.json
```

Focused reports under `scripts\reports\out` are evidence only. They are useful for diagnosing a failed lane, but they must not be combined manually to override the aggregate beta release gate status.

## Authoritative Command

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
pwsh -File scripts\quality\run-beta0-final-closure-gate.ps1
```

If either command is missing, or if it does not include the AI-only Beta 0 evidence gates, no current AI-only Beta 0 release claim may be made.

The gate **evaluates** evidence; by default it does not produce it. The reports it requires (`requiredReports` in `scripts\policy\beta-release-gate-policy.json`) are written by separate producer scripts. Pass `-GenerateReports` to run those producers first (through `scripts\quality\run-beta-release-evidence-orchestration.ps1`, which shares one `runId` across every producer), or run that script yourself beforehand.

The gate writes a **flat** set of files under `scripts\reports\out`; it creates no timestamped bundle directory:

```text
scripts\reports\out\beta-release-gate-report.json          the aggregate decision
scripts\reports\out\beta-release-evidence-manifest.json    git state, requiredReports, direct-evidence summary
scripts\reports\out\release-ready-summary.json             packaging summary
```

(The earlier `scripts\reports\releases\runtimehost-beta-<timestamp>` bundle and its `evidence-manifest.json` were removed on 2026-04-29.)

## Decision Rule

- `beta-release-gate-report.json` has `status` / `overallStatus` of `passed` or `failed` — no `warning` state is produced. `passed` requires `releaseReady` **and** `officialReleaseEligible` (which in turn requires `provenanceReady`).
- `gateOutcome` splits a non-pass into **`precondition-unmet`** (required evidence was never generated, exit code `2` — the release was not evaluated, this is not a release failure) and **`check-failed`** (evidence exists and something it asserts is broken, exit code `1`). A pass is exit code `0`.
- If it is `failed`, the release is not green, even if some focused reports are green.
- If a focused report is rerun later, rerun the aggregate beta gate before making a release claim.

The truth-table results are separate booleans in the report: `candidateReady`, `releaseReady`, `provenanceReady` and `officialReleaseEligible`. Because aggregate `passed` requires `officialReleaseEligible`, a run can no longer pass while its provenance is untraceable. `release-ready-summary.json` repeats `status`, and `local-unanchored` evidence (no git or CI anchor) is valid for diagnosis, investigation, handoff and diagnostic packaging, but it is not a release-grade artifact.

## Freshness Rule

The gate does not clear `scripts\reports\out` at the start of a run; it evaluates the reports that are there (only its own schema-validation scratch directory is removed). Each report must carry the current aggregate `runId`: missing, stale-run-id, unparsable, or invalid reports fail the aggregate gate (as `precondition-unmet` when they were never produced).

Every child gate report used by the aggregate decision must include:

- `runId`
- `generatedAt`
- `scriptPath`
- `workspaceRoot`
- `overallStatus`

The aggregate gate accepts child reports only when every required child report has the same non-empty `runId` and that value matches the aggregate run. This prevents a stale focused report from making the aggregate pass or fail incorrectly.

## Required Fields

The aggregate report (`beta-release-gate-report.v1`) records:

- `runId`: the identity shared by the aggregate report and all current-run child reports.
- `status` / `overallStatus`, `gateOutcome`, `evidencePreconditions` (which reports are missing or unreadable, and the precondition-versus-check blocker split).
- `candidateReady`, `releaseReady`, `provenanceReady`, `officialReleaseEligible` and `truthTableEvaluation` (how each was computed).
- `sourceOfTruth`, `decisionRule`, `policyPath`, `maxReportAgeHours`.
- `git` (branch, commit, dirty state), `requiredReports` and `informationalReports` with each report's status.
- `blockers` and `officialEligibilityBlockers`, `activeContradictoryReports`, `scopePolicyEnforcement`, `directEvidenceSummary`.

Use the aggregate report first, then inspect focused reports only to explain the aggregate result.

## Release-Ready State Zip

When you need a shareable snapshot that proves the current readiness state instead of just the source layout, first run the aggregate gate, then generate the general state zip in release-ready mode:

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
```

```powershell
pwsh -File scripts\statezip-npdev-general.ps1 -ReleaseReady
```

The state zip command is packaging-only. It must not build, test, generate samples, or refresh gates. By default it reads the existing `scripts\reports\out\beta-release-gate-report.json`, uses `scripts\reports\out` itself as the evidence root (the gate writes no `evidenceRoot`), and stages:

- `scripts\reports\out`
- sample `Output\Reports` evidence inside the `NPDevSamples` subproject zip

`-ExistingEvidenceRoot last` selects the most recent complete evidence root: a legacy `scripts\reports\releases\...` bundle when that directory exists, otherwise the flat `scripts\reports\out` when it holds a `beta-release-evidence-manifest.json`. You can also pass an explicit root with `-ExistingEvidenceRoot <path>`.

If the required existing aggregate report or evidence manifest is missing, release-ready state zip generation fails instead of trying to recreate evidence.
It intentionally does not promote rebuildable sample assembled apps or RuntimeHost local sync residue into the snapshot.
If the run is not traceable to Git or CI metadata, the state zip still packages the evidence, but `release-ready-summary.json` marks it as diagnostic-only with `packagingMode: "DIAGNOSTIC"` and `officialReleaseEligible: false`.

When the workspace itself is not a Git checkout, use the traceable wrapper to provide explicit source metadata for an official local release run:

```powershell
pwsh -File scripts\quality\run-traceable-local-release.ps1 -SourceCommitSha <sha> -SourceBranch <branch>
```
