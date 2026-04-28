# Release Blocker Execution Roadmap

This roadmap converted the release evidence into an execution sequence for closing the remaining red lanes.

The blocker baseline evidence run was:

```text
scripts\reports\out\beta-release-gate-report.json
releaseRunId = runtimehost-beta-20260422-153502
generatedAt = 2026-04-22T15:43:36-03:00
```

The closing authoritative evidence run is:

```text
scripts\reports\out\beta-release-gate-report.json
releaseRunId = runtimehost-beta-20260422-182333
generatedAt = 2026-04-22T18:31:11-03:00
overallStatus = passed
summary = 14 passed, 0 warnings, 0 failed
```

The closing release-ready state zip is:

```text
D:\WorkSpace\NPDev_General__OutsideRepo\state-zips\NPDev_General_State_ALL_20260422_182333.zip
```

## Baseline State

- Aggregate beta release gate status is `failed`.
- `11` steps passed, `1` step warned, and `2` steps failed.
- Direct blockers are `runtimehost` and `sample-matrix`.
- The current non-blocking warning is `doctor -> output-cleanliness`.
- The release-ready state zip summary currently reports `releaseReady: true` even when the aggregate report is failed. That is a governance defect and must be fixed before the state zip can be trusted as a release artifact.

## Closing State

- Aggregate beta release gate status is `passed`.
- `14` steps passed, `0` steps warned, and `0` steps failed.
- `runtimehost` is green through the `simple-contact-intake` assembled app canary.
- `sample-matrix` is green across all `5` release samples with `matrixCoveragePercent = 100.0`.
- `doctor` is green and no longer reports disposable sample-output cache warnings.
- `release-ready-summary.json.releaseReady` is derived from the aggregate gate and agrees with `releaseEvidenceStatus = passed`.
- The release-ready state zip contains `scripts\reports\out`, the current `scripts\reports\releases\runtimehost-beta-20260422-182333` bundle, and sample `Output\Reports` generation evidence without bundling rebuildable `Output\App` or `Output\ArtifactNP` trees.

## Scope

This roadmap is intentionally narrow.

It focuses on:

- release-summary truthfulness
- runtimehost canary verification
- sample matrix recovery
- failure observability
- sample-output cleanup on failed runs

It does not expand into broader architectural work that is already green, such as Editor, Contract, or Kernel restructuring.

## Phase Overview

| Phase | Owner | Outcome |
| --- | --- | --- |
| 0 | Root scripts / governance | Release summary and evidence reporting become truthful and diagnostic |
| 1 | NPDevRuntimeHost primary, NPDevGenerator secondary | `simple-contact-intake` passes the runtimehost lane |
| 2 | NPDevSamples primary, NPDevRuntimeHost + NPDevGenerator co-owners | All release samples pass the matrix |
| 3 | Sample scripts / doctor / hygiene | Doctor warning is removed and failed runs clean themselves |
| 4 | Root scripts / governance | Aggregate gate and release-ready zip are both trustworthy and green |

## Phase 0: Evidence Truth And Diagnostics

**Owner**

- Primary: Root scripts / governance
- Files: `scripts\statezip-npdev-general.ps1`, `scripts\quality\run-runtimehost-gate.ps1`, `scripts\quality\run-sample-matrix.ps1`, `scripts\npdev-common.ps1`

**Goal**

Make the packaged evidence internally consistent and fast to diagnose.

**Work Items**

1. Derive `releaseReady` in `release-ready-summary.json` from the aggregate beta release gate result instead of hardcoding it to `true`.
2. Include the aggregate report path, aggregate status, and release run ID in the summary as explicit fields.
3. Fail closed if the aggregate report is missing, unparsable, or missing `overallStatus`.
4. Replace raw streaming-only verification in the runtimehost and sample-matrix gates with captured report output using the existing command capture/report helpers.
5. Record enough failure evidence to diagnose the failing Gradle task from the report bundle:
   - command output tail
   - failing Gradle task name when available
   - optional per-sample log file path if logs are written out separately
6. Ensure the new failure evidence is copied into `scripts\reports\out` and the release bundle under `scripts\reports\releases\<runId>`.

**Acceptance Criteria**

- `release-ready-summary.json.releaseReady` is `true` only when `beta-release-gate-report.json.overallStatus` is `passed`.
- `release-ready-summary.json` and `beta-release-gate-report.json` cannot disagree on overall readiness.
- Failed `runtimehost` and `sample-matrix` reports contain actionable failure details beyond the command string.
- A freshly generated release bundle contains the enhanced diagnostics.

**Validation**

```powershell
pwsh -File scripts\quality\run-runtimehost-gate.ps1
pwsh -File scripts\quality\run-sample-matrix.ps1
pwsh -File scripts\quality\run-beta-release-gate.ps1
pwsh -File scripts\statezip-npdev-general.ps1 -ReleaseReady
```

## Phase 1: RuntimeHost Canary Closure

**Owner**

- Primary: `NPDevRuntimeHost`
- Secondary: `NPDevGenerator`
- Canary sample: `simple-contact-intake`

**Goal**

Make one generated sample app pass the dedicated runtimehost verification lane before attempting full matrix recovery.

**Work Items**

1. Reproduce the runtimehost failure using only `simple-contact-intake`.
2. Use the new captured diagnostics from Phase 0 to identify the exact failing Gradle task and first real compile/test error.
3. Confirm or reject the current leading hypothesis:
   - generated apps compile code that imports `com.networknt.schema.*`
   - `NPDevRuntimeHost\build.gradle.template` currently does not declare the required schema validator dependency
4. If the dependency gap is confirmed, add the missing dependency in the RuntimeHost template and verify that Generator propagates it into assembled sample apps.
5. If the failure is instead caused by migration enforcement, isolate whether the failure comes from:
   - `enforceSingleMigrationSource`
   - runtime tests
   - generated migration/resource layout
6. Add one focused regression check that proves a generated `simple-contact-intake` app can complete `enforceSingleMigrationSource test`.

**Acceptance Criteria**

- `scripts\reports\out\runtimehost-gate-report.json` reports `overallStatus = passed`.
- The `simple-contact-intake` assembled app completes `.\gradlew.bat --no-daemon --console=plain enforceSingleMigrationSource test`.
- The root cause is covered by at least one focused regression so the same failure cannot silently return.

**Validation**

```powershell
pwsh -File scripts\quality\run-runtimehost-gate.ps1 -SampleId simple-contact-intake
```

## Phase 2: Full Sample Matrix Recovery

**Owner**

- Primary: `NPDevSamples`
- Co-owners: `NPDevRuntimeHost`, `NPDevGenerator`

**Goal**

Move from one passing canary to a fully green release sample matrix.

**Work Items**

1. Re-run the full release sample matrix after the canary is green.
2. Split any remaining failures into two buckets:
   - shared platform/generator/runtime defects
   - sample-specific defects
3. Fix any sample-specific mismatches in manifests, generated resources, or runtime expectations only after the shared defect path is closed.
4. Preserve current behavior that records per-sample input fingerprints and generation markers.
5. Keep the matrix policy unchanged unless a real catalog policy defect is discovered. Do not weaken coverage to make the lane pass.

**Acceptance Criteria**

- `scripts\reports\out\sample-matrix-report.json` reports `overallStatus = passed`.
- `matrixCoveragePercent = 100.0`.
- `releaseEvidence.eligible = true`.
- All `5` release samples pass verification in one run.
- The aggregate beta release gate no longer lists `sample-matrix` as a failed step.

**Validation**

```powershell
pwsh -File scripts\quality\run-sample-matrix.ps1
pwsh -File scripts\quality\run-beta-release-gate.ps1
```

## Phase 3: Hygiene And Cleanup Closure

**Owner**

- Primary: sample automation / hygiene scripts
- Files: `scripts\quality\run-runtimehost-gate.ps1`, `scripts\quality\run-sample-matrix.ps1`, `scripts\samples\verify-sample.ps1`, `scripts\samples\clean-sample-output.ps1`, `scripts\doctor\check-output-cleanliness.ps1`

**Goal**

Remove the current doctor warning without losing failure evidence.

**Work Items**

1. Move sample build-cache cleanup into a `finally` path so failed verification runs still clean disposable residue.
2. Preserve `Output\Reports` evidence while removing disposable `Output\App\.gradle`, `Output\App\build`, and similar cache directories.
3. Apply the same cleanup behavior across:
   - runtimehost gate
   - sample matrix
   - sample verification helpers
4. Re-run doctor after both a failed and successful sample verification run to confirm cleanup is reliable in both cases.

**Acceptance Criteria**

- `scripts\reports\out\doctor-report.json` reports `overallStatus = passed`.
- `output-cleanliness` no longer warns about disposable sample caches.
- Failure evidence remains available even after cleanup runs.

**Validation**

```powershell
pwsh -File scripts\doctor\npdev-doctor.ps1
```

## Phase 4: Aggregate Proof And Release Artifact Closure

**Owner**

- Primary: Root scripts / governance
- Evidence files: `scripts\reports\out\beta-release-gate-report.json`, `scripts\reports\releases\<runId>\**`, `release-ready-summary.json`

**Goal**

Restore a fully green release lane and produce a release-ready state zip whose summary agrees with the authoritative evidence.

**Work Items**

1. Re-run the full aggregate beta release gate after Phases 0 through 3 are complete.
2. Generate a fresh release-ready state zip.
3. Inspect the bundle contents to confirm it contains:
   - `scripts\reports\out`
   - the current release evidence bundle
   - sample `Output\Reports` evidence
4. Verify that the release-ready summary and aggregate report agree on status, run ID, and readiness.
5. Record the closing evidence run ID in this roadmap or in the release evidence notes once the lane is green.

**Acceptance Criteria**

- `scripts\reports\out\beta-release-gate-report.json` reports `overallStatus = passed`.
- `runtimehost` is green in the aggregate report.
- `sample-matrix` is green in the aggregate report.
- `doctor` is green in the aggregate report.
- The release-ready state zip contains the required evidence trees.
- `release-ready-summary.json` agrees with the aggregate report with no contradictory fields.

**Validation**

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
pwsh -File scripts\statezip-npdev-general.ps1 -ReleaseReady
```

## Stop Conditions

Pause and reassess before moving forward if any of these occur:

- The captured failure logs show that the canary failure is not shared with the rest of the matrix.
- A fix requires weakening sample-matrix policy or skipping release samples.
- The release summary and aggregate report still disagree after Phase 0 changes.
- The runtimehost canary passes, but at least three matrix samples still fail for unrelated reasons.

## Definition Of Done

The roadmap is complete only when all of the following are true in the same current-run evidence set:

1. Aggregate beta release gate is `passed`.
2. Runtimehost gate is `passed`.
3. Sample matrix is `passed` with full release coverage.
4. Doctor is `passed` with no output-cleanliness warning.
5. Release-ready summary and aggregate report agree.
6. The release-ready state zip includes current evidence and no contradictory readiness claim.
