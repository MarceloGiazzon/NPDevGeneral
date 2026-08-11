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

The gate creates a timestamped release evidence bundle under:

```text
scripts\reports\releases\runtimehost-beta-<yyyyMMdd-HHmmss>
```

The bundle contains `evidence-manifest.json`, copied focused reports, environment fingerprinting, and the aggregate report reference.

## Decision Rule

- If `beta-release-gate-report.json` is `passed`, the current release run is green.
- If it is `warning`, the release requires explicit review before promotion.
- If it is `failed`, the release is not green, even if some focused reports are green.
- If a focused report is rerun later, rerun the aggregate beta gate before making a release claim.

Release-ready packaging preserves the aggregate gate decision and separately records official release eligibility:

- `release-ready-summary.json.releaseReady` follows the aggregate beta release gate decision. If the aggregate report is `passed`, packaging can remain release-ready even when the provenance is not traceable.
- `release-ready-summary.json.officialReleaseEligible` is `true` only when the aggregate beta release gate is `passed` and the evidence provenance grade is `git-traceable` or `ci-traceable`.
- `local-unanchored` evidence is valid for diagnosis, investigation, handoff, and diagnostic packaging, but it is not a release-grade artifact.

## Freshness Rule

At the start of an aggregate beta release run, generated reports in `scripts\reports\out` that participate in the evidence bundle are cleared unless `-PreserveExistingReports` is explicitly used for stale-report testing. Each report-producing step must produce a report for the current aggregate `runId`. Missing, stale-run-id, unparsable, or invalid reports fail the aggregate gate.

Every child gate report used by the aggregate decision must include:

- `runId`
- `generatedAt`
- `scriptPath`
- `workspaceRoot`
- `overallStatus`

The aggregate gate accepts child reports only when every required child report has the same non-empty `runId` and that value matches the aggregate run. This prevents a stale focused report from making the aggregate pass or fail incorrectly.

## Required Fields

The aggregate report records:

- `runId`: the identity shared by the aggregate report and all current-run child reports.
- `releaseRunId`: the timestamped identity of the release run.
- `authoritativeDecision`: the source-of-truth rule and report path.
- `provenanceGrade`: `ci-traceable`, `git-traceable`, or `local-unanchored`.
- `traceabilitySatisfied`: whether the run meets the release-ready traceability rule.
- `steps`: every beta release lane and its current status, including `exitDisposition`, `childReportDisposition`, `runIdMatch`, and `finalDecisionReason`.
- `copiedEvidence`: evidence files copied into the release bundle.
- `evidenceRoot`: the timestamped bundle for this run.

Use the aggregate report first, then inspect focused reports only to explain the aggregate result.

## Release-Ready State Zip

When you need a shareable snapshot that proves the current readiness state instead of just the source layout, first run the aggregate gate, then generate the general state zip in release-ready mode:

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
```

```powershell
pwsh -File scripts\statezip-npdev-general.ps1 -ReleaseReady
```

The state zip command is packaging-only. It must not build, test, generate samples, or refresh gates. By default, it reads the existing `scripts\reports\out\beta-release-gate-report.json`, resolves the existing `evidenceRoot`, and stages:

- `scripts\reports\out`
- the current `scripts\reports\releases\runtimehost-beta-<timestamp>` bundle
- sample `Output\Reports` evidence inside the `NPDevSamples` subproject zip

If `scripts\reports\out` was cleaned but a complete release bundle still exists, package that existing bundle explicitly:

```powershell
pwsh -File scripts\statezip-npdev-general.ps1 -ReleaseReady -ExistingEvidenceRoot scripts\reports\releases\runtimehost-beta-<timestamp>
```

You can also use `last` to select the most recent complete existing bundle:

```powershell
pwsh -File scripts\statezip-npdev-general.ps1 -ReleaseReady -ExistingEvidenceRoot last
```

If the required existing aggregate report or release evidence bundle is missing, release-ready state zip generation fails instead of trying to recreate evidence.
It intentionally does not promote rebuildable sample assembled apps or RuntimeHost local sync residue into the snapshot.
If the run is not traceable to Git or CI metadata, the state zip still packages the evidence, but `release-ready-summary.json` marks it as diagnostic-only with `packagingMode: "DIAGNOSTIC"` and `officialReleaseEligible: false`.

When the workspace itself is not a Git checkout, use the traceable wrapper to provide explicit source metadata for an official local release run:

```powershell
pwsh -File scripts\quality\run-traceable-local-release.ps1 -SourceCommitSha <sha> -SourceBranch <branch>
```

For the additive Bucket 1 compatibility layer, use the prioritized control board runner:

```powershell
pwsh -File scripts\maturity_adv\run-prioritized-control-board.ps1
```
