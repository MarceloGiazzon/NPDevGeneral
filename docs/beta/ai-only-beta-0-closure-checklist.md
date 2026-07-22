# AI-only Beta 0 Closure Checklist

## Required Commands

- [ ] `pwsh ./scripts/quality/run-json-schema-validator-tests.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-ai-schema-validation.ps1` exits 0 and reports schema validation separately from semantic validation.
- [ ] `pwsh ./scripts/quality/run-ai-beta-gate.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-report-schema-validation.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-doc-entrypoint-validation.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-report-provenance-tests.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-beta-release-gate.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-beta0-final-closure-gate.ps1` exits 0.
- [ ] `pwsh ./scripts/quality/run-beta0-final-release-check.ps1` exits 0.

## Required Reports

- [ ] `scripts/reports/out/ai-beta-gate-report.json` exists.
- [ ] `scripts/reports/out/ai-beta-gate-report.json` has `overallStatus=passed`.
- [ ] `scripts/reports/out/beta-release-gate-report.json` exists.
- [ ] `scripts/reports/out/beta-release-gate-report.json` has `releaseReady=true`.
- [ ] `scripts/reports/out/beta-release-gate-report.json` has `provenanceReady=true`.
- [ ] `scripts/reports/out/beta-release-gate-report.json` has `officialReleaseEligible=true`.
- [ ] `scripts/reports/out/beta-release-evidence-manifest.json` exists.
- [ ] `scripts/reports/out/ai-beta-reproducibility-report.json` exists.

## Contract Readiness

- [ ] AI schemas validate all positive scenarios.
- [ ] Negative scenarios fail at expected stages.
- [ ] Normalizer output validates official model/config expectations.

## Execution Safety

- [ ] Controlled runner tests pass.
- [ ] Malicious command scenarios are blocked.
- [ ] Secret redaction test passes.
- [ ] External network command scenarios are blocked.

## Autonomous Verification

- [ ] Generated app builds.
- [ ] Generated app boots.
- [ ] Health check passes.
- [ ] REST behavior smoke checks pass.

## Reproducibility

- [ ] Clean environment path is documented.
- [ ] Dirty state is recorded.
- [ ] Cache mode is recorded.
- [ ] CI workflow runs the AI beta gate.

## Tag Rule

- [ ] A Beta 0 tag is created only after `run-beta0-final-closure-gate.ps1` exits 0 with `beta0TagAllowed=true` for the current commit/workspace fingerprint.
- [ ] Recommended tag format is `v0.1.0-ai-beta.0`.
