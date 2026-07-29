# AI-Only Beta 0 Release Runbook

Official Beta 0 evidence is produced from the Windows release gate plus the blocking Docker/Linux CI proof. Docker/Linux evidence must come from `scripts/quality/run-docker-linux-proof.ps1`.

Run the full closure sequence:

```powershell
pwsh ./scripts/quality/run-traceable-local-release.ps1 -WorkspaceRoot .
pwsh ./scripts/quality/run-roadmap-closure-check.ps1 -WorkspaceRoot .
```

For diagnostics, `run-traceable-local-release.ps1` invokes the canonical final release script:

```powershell
pwsh ./scripts/quality/run-beta0-final-release-check.ps1
```

The canonical final release script owns the ordered gate list. Use `scripts/reports/out/beta0-final-release-check-report.json.gates` as the machine-readable record of the exact commands, exit codes, and durations from a given run.

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

Once `beta0TagAllowed` is true, create the tag with the gate script that re-validates the decision
table above before tagging (refuses if `overallStatus` is not `passed` or any required boolean is
false):

```powershell
pwsh ./scripts/release/create-beta0-tag.ps1 -Version beta0
```
