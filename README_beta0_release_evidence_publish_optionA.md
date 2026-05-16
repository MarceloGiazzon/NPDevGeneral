# Beta0 final release check v2 - Option A path integration fix

This package fixes the path mismatch between:

- `scripts/quality/run-beta0-final-release-check_v2.ps1`, which produces successful live evidence under `scripts/reports/out`
- `scripts/statezip-npdev-general.ps1 -ReleaseReady -ExistingEvidenceRoot last`, which expects archived aggregate release evidence under `scripts/reports/releases/<runId>`

## Files included

```text
scripts/quality/run-beta0-final-release-check_v2.ps1
scripts/quality/run-beta-release-gate.ps1
scripts/quality/run-beta0-final-closure-gate.ps1
```

## What changed

### `run-beta0-final-release-check_v2.ps1`

After all gates pass, the script now automatically publishes the successful evidence into:

```text
scripts/reports/releases/<runId>
```

The published archive contains:

```text
scripts/reports/releases/<runId>/scripts/reports/out/*
scripts/reports/releases/<runId>/evidence-manifest.json
scripts/reports/releases/<runId>/beta-release-evidence-manifest.json
scripts/reports/releases/<runId>/beta-release-gate-report.json
scripts/reports/releases/<runId>/beta0-final-release-check-report.json
scripts/reports/releases/<runId>/release-evidence-publish-report.json
```

After that, this works naturally:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' `
  -NoProfile `
  -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\statezip-npdev-general.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -OutDir 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips' `
  -ReleaseReady `
  -ExistingEvidenceRoot 'last'
```

### `run-beta-release-gate.ps1` and `run-beta0-final-closure-gate.ps1`

Generated release evidence under `scripts/reports/releases/**` is now classified as allowed generated evidence, like `scripts/reports/out/*.json` and `scripts/reports/out/*.log`.

This prevents the new archived evidence bundle from blocking the next release check as source dirtiness.

## Install

From the extracted package root, copy the three files into the workspace, replacing the existing versions.

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'

Copy-Item `
  -LiteralPath 'D:\PATH_TO_EXTRACTED_PACKAGE\scripts\quality\run-beta0-final-release-check_v2.ps1' `
  -Destination 'D:\WorkSpace\NPDev_General\scripts\quality\run-beta0-final-release-check_v2.ps1' `
  -Force

Copy-Item `
  -LiteralPath 'D:\PATH_TO_EXTRACTED_PACKAGE\scripts\quality\run-beta-release-gate.ps1' `
  -Destination 'D:\WorkSpace\NPDev_General\scripts\quality\run-beta-release-gate.ps1' `
  -Force

Copy-Item `
  -LiteralPath 'D:\PATH_TO_EXTRACTED_PACKAGE\scripts\quality\run-beta0-final-closure-gate.ps1' `
  -Destination 'D:\WorkSpace\NPDev_General\scripts\quality\run-beta0-final-closure-gate.ps1' `
  -Force
```

No standalone `else` blocks are used in the command examples.

## Commit before official release run

These script changes are source changes. Commit them before running official release evidence.

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'

git add `
  scripts/quality/run-beta0-final-release-check_v2.ps1 `
  scripts/quality/run-beta-release-gate.ps1 `
  scripts/quality/run-beta0-final-closure-gate.ps1

git commit -m "Publish Beta0 release evidence archive after final check"
```

## Run

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' `
  -NoProfile `
  -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\run-beta0-final-release-check_v2.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```

Expected successful ending:

```text
Publishing aggregate release evidence END   => passed (scripts/reports/releases/<runId>)
Beta 0 final release check passed. Report: scripts/reports/out/beta0-final-release-check-report.json
Aggregate release evidence published. Statezip can use: -ExistingEvidenceRoot 'last'
```

Then run statezip with `-ExistingEvidenceRoot 'last'`.

## Optional switches

The release evidence archive publish is enabled by default.

To skip it:

```powershell
-SkipReleaseEvidencePublish
```

To publish somewhere else:

```powershell
-ReleaseEvidenceArchiveRoot 'D:\WorkSpace\NPDev_General__OutsideRepo\release-evidence'
```

If you publish outside the repo, `statezip -ExistingEvidenceRoot last` will not find it unless you pass the absolute evidence root instead of `last`.
