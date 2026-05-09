# AI-Only Beta 0 No-False-Green Scope

Beta 0 release evidence is official only when produced by the Windows release gate path plus the blocking Docker/Linux proof from `scripts/quality/run-docker-linux-proof.ps1`. Docker/Linux execution must include CI compatibility, explicit timeouts, logs, report artifacts, and current-source report provenance.

Green means machine-checked proof:

- official model/config files validate against `NPDevContract/schemas/model.schema.json` and `NPDevContract/schemas/config.schema.json`;
- positive gates have negative fixtures or expected-failure scenarios;
- generated reports are fresh source-of-truth artifacts with hashes and provenance;
- all required child reports share the same non-empty `runId`;
- `officialReleaseEligible=true` is blocked by a dirty workspace, missing commit identity, stale reports, or invalid report schemas;
- `beta0TagAllowed=true` is granted only by `scripts/quality/run-beta0-final-closure-gate.ps1`.

Current command sequence:

```powershell
pwsh ./scripts/quality/run-json-schema-validation-tests.ps1
pwsh ./scripts/quality/run-json-schema-validator-tests.ps1
pwsh ./scripts/quality/run-ai-contract-normalizer-tests.ps1
pwsh ./scripts/quality/run-runtimehost-staged-jar-preflight.ps1
pwsh ./scripts/quality/run-docker-linux-proof.ps1
pwsh ./scripts/quality/run-sample-matrix.ps1
pwsh ./scripts/quality/run-ai-beta-gate.ps1
pwsh ./scripts/quality/run-report-schema-validation.ps1
pwsh ./scripts/quality/run-doc-entrypoint-validation.ps1
pwsh ./scripts/quality/run-report-provenance-tests.ps1
pwsh ./scripts/quality/run-beta-release-gate.ps1
pwsh ./scripts/quality/run-beta0-final-closure-gate.ps1
pwsh ./scripts/quality/run-beta0-final-release-check.ps1
```

Release truth fields:

- `candidateReady`: required evidence reports exist, are fresh, and pass.
- `releaseReady`: candidate evidence is sufficient for a release candidate.
- `provenanceReady`: evidence came from a clean, traceable, approved platform with fresh non-contradictory reports.
- `officialReleaseEligible`: release candidate is clean, current, and officially eligible.
- `beta0TagAllowed`: final closure allows tagging Beta 0.
