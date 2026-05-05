# AI-only Beta 0 Runbook

## Proof Commands

```powershell
pwsh ./scripts/quality/run-ai-beta-gate.ps1
pwsh ./scripts/quality/run-report-schema-validation.ps1
pwsh ./scripts/quality/run-doc-entrypoint-validation.ps1
pwsh ./scripts/quality/run-report-provenance-tests.ps1
pwsh ./scripts/quality/run-beta-release-gate.ps1
pwsh ./scripts/quality/run-beta0-final-closure-gate.ps1
pwsh ./scripts/quality/run-beta0-final-release-check.ps1
```

## Reports

- `scripts/reports/out/ai-beta-gate-report.json`
- `scripts/reports/out/beta-release-gate-report.json`
- `scripts/reports/out/beta0-final-closure-report.json`
- `scripts/reports/out/beta-release-evidence-manifest.json`
- `scripts/reports/out/ai-beta-reproducibility-report.json`

## Interpreting Results

`run-ai-beta-gate.ps1` proves the autonomous loop. It validates AI-authored contract bundles, normalizes them to official contracts, generates the app, checks determinism, builds, boots, verifies health, executes REST smoke checks, and blocks unsafe command-policy scenarios.

Typed AI command execution is limited to controlled `gradle-task`, `schema-validation`, and `rest-smoke` requests. Raw shell execution remains blocked.

`run-beta-release-gate.ps1` is the release decision. Beta 0 is blocked unless it exits 0 and `beta-release-gate-report.json` has:

```json
{
  "status": "passed",
  "candidateReady": true,
  "releaseReady": true,
  "provenanceReady": true,
  "officialReleaseEligible": true
}
```

`run-beta0-final-closure-gate.ps1` is the tag decision. Beta 0 tagging is blocked unless `beta0TagAllowed=true`.

Expected negative scenarios are successful only when they fail at their declared stage. Do not reinterpret a failed scenario manually.

All current-run child reports must carry the same non-empty `runId`. Mixed-run, missing-run, stale, or manually edited evidence is not release proof.

## Clean Environment

Docker/Linux proof is experimental for Beta 0 and is not official release evidence in this pass:

```bash
docker build -f Dockerfile.ai-beta -t npdev-ai-beta:local .
docker run --rm -v "$PWD:/workspace" -w /workspace npdev-ai-beta:local pwsh ./scripts/quality/run-ai-beta-gate.ps1
```

The reproducibility report records OS, PowerShell, Java, Node/npm, Gradle wrapper distribution, git commit, dirty state, network policy, and cache mode.

## Dependencies

If Gradle, Java, Node/npm, or PowerShell are unavailable, the gate must fail. Do not substitute manual smoke testing as release proof.

## Evidence Rules

Historical packaged evidence is archival only. Current Beta 0 readiness is determined by the current reports under `scripts/reports/out`.

## Tag Rule

A Beta 0 tag may be created only when `run-beta0-final-closure-gate.ps1` exits 0 and `beta0-final-closure-report.json` has `candidateReady=true`, `releaseReady=true`, `officialReleaseEligible=true`, and `beta0TagAllowed=true` for the current commit/workspace fingerprint.

Recommended tag:

```text
v0.1.0-ai-beta.0
```
