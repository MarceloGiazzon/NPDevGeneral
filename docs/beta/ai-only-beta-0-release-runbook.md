# AI-Only Beta 0 Release Runbook

Official Beta 0 evidence is produced on Windows CI or an equivalent clean Windows workspace. Docker/Linux proof is experimental for this pass and must not be used to claim official release eligibility.

Run the full closure sequence:

```powershell
pwsh ./scripts/quality/run-beta0-final-release-check.ps1
```

For diagnostics, the final command runs this ordered sequence:

```powershell
pwsh ./scripts/quality/run-json-schema-validator-tests.ps1
pwsh ./scripts/quality/run-ai-schema-validation.ps1
pwsh ./scripts/quality/run-ai-contract-normalizer-tests.ps1
pwsh ./scripts/quality/run-controlled-command-runner-tests.ps1
pwsh ./scripts/quality/run-ai-rest-smoke-verifier-tests.ps1
pwsh ./scripts/quality/run-sample-matrix.ps1
pwsh ./scripts/quality/run-ai-beta-gate.ps1
pwsh ./scripts/quality/run-report-schema-validation.ps1
pwsh ./scripts/quality/run-doc-entrypoint-validation.ps1
pwsh ./scripts/quality/run-report-provenance-tests.ps1
pwsh ./scripts/quality/run-beta-release-gate.ps1
pwsh ./scripts/quality/run-beta0-final-closure-gate.ps1
```

Decision table:

| candidateReady | releaseReady | officialReleaseEligible | beta0TagAllowed | Decision |
| --- | --- | --- | --- | --- |
| true | true | true | true | Beta 0 tag may be created. |
| true | true | false | false | Candidate evidence exists, but official release is blocked. |
| true | false | false | false | Candidate evidence is incomplete or not acceptable. |
| false | false | false | false | Do not release. Regenerate evidence and fix blockers. |

Before tagging, confirm `scripts/reports/out/beta0-final-closure-report.json` contains:

```json
{
  "candidateReady": true,
  "releaseReady": true,
  "provenanceReady": true,
  "officialReleaseEligible": true,
  "beta0TagAllowed": true
}
```

If the workspace is dirty, the release gate and final closure gate must fail official eligibility even when functional tests pass.
