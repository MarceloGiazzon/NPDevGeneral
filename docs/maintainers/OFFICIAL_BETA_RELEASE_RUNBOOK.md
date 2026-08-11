# NPDev Official Beta Release Runbook

Current AI-only Beta 0 readiness is determined by the canonical traceable release wrapper, `scripts\quality\run-traceable-local-release.ps1`, and the closure helper, `scripts\quality\run-roadmap-closure-check.ps1`, using fresh reports from the current workspace fingerprint.

## Purpose

This runbook defines the official local and GitHub/CI release path for NPDev after the RuntimeHost convergence roadmap.

It must be treated as the release source of truth for the current official beta baseline.

---

## Current official baseline

| Field | Value |
|---|---|
| Baseline tag | `npdev-official-beta-20260428-062512` |
| Baseline state zip | `NPDev_General_State_ALL_20260428_062512.zip` |
| Latest verified state zip | `NPDev_General_State_ALL_20260428_093957.zip` |
| Release evidence run | `runtimehost-beta-20260428-001515` |
| ReleaseReady | `True` |
| OfficialReleaseEligible | `True` |
| PackagingMode | `RELEASE_READY` |
| ProvenanceGrade | `git-traceable` |
| TraceabilitySatisfied | `True` |
| RuntimeHost convergence | Complete for the current evidence set |

---

## Repository model

NPDev uses one monorepo.

```text
NPDev_General
├─ NPDevContract
├─ NPDevEditor
├─ NPDevGenerator
├─ NPDevKernel
├─ NPDevRuntimeHost
├─ NPDevSamples
├─ docs
├─ scripts
├─ README.md
└─ PROJECT_DIGEST.md
```

Do not split the subprojects into separate repositories during the current beta closure phase.

Reason:

- the beta release gate is aggregate;
- state zip packaging captures the whole workspace;
- RuntimeHost depends on Kernel, Contract, Generator, and Samples evidence;
- release traceability is currently proven at the monorepo level.

---

## Release rules

### Official release may be claimed only when all are true

- `ReleaseReady=True`
- `OfficialReleaseEligible=True`
- `PackagingMode=RELEASE_READY`
- `TraceabilitySatisfied=True`
- `ProvenanceGrade=git-traceable` or `ci-traceable`
- beta release gate is `passed`
- release evidence bundle exists
- state zip is generated from the latest passing evidence
- Git HEAD commit is resolvable
- source tree is clean or the evidence explicitly records dirty state according to policy

### Diagnostic release may not be called official

If any of these are false:

- `OfficialReleaseEligible`
- `TraceabilitySatisfied`
- commit identity availability

then the result is diagnostic only, even if the beta gate passes.

### Freeze rule

After creating a release tag:

- do not edit release evidence bundles manually;
- do not edit files and reuse the same tag;
- do not claim a new official release without a new traceable commit and tag.

---

## Local official release command sequence

Run from the monorepo root.

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'

git status --short
git rev-parse HEAD
git tag --list 'npdev-official-beta-*'

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\run-traceable-local-release.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\tests\Test-ReleaseTraceability.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -RequireOfficialEligibility

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\statezip-npdev-general.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -OutDir 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips' `
  -ReleaseReady `
  -ExistingEvidenceRoot last

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\fix-statezip-visible-timestamps.ps1' `
  -ZipRoot 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\tests\Test-CiReleaseEvidenceFreshness.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -StateZipOut 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\run-roadmap-closure-check.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -StateZipRoot 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'
```

Expected result:

```text
ReleaseReady=True
OfficialReleaseEligible=True
PackagingMode=RELEASE_READY
ProvenanceGrade=git-traceable
TraceabilitySatisfied=True
```

---

## GitHub/CI release path

The GitHub workflow is:

```text
.github/workflows/npdev-release-gate.yml
```

It should run on:

- pull requests;
- pushes to `main`;
- pushes of tags matching `npdev-official-beta-*`;
- manual workflow dispatch.

The CI path must:

1. check out the repository with full history;
2. set up Java 21;
3. set up Node 22;
4. cache Gradle;
5. cache npm;
6. cache Playwright browsers;
7. install Playwright Chromium;
8. run `run-traceable-local-release.ps1 -WorkspaceRoot .`, which calls the canonical final release script and records command, log, report, hash, commit, and branch evidence;
9. run `run-roadmap-closure-check.ps1 -WorkspaceRoot .` so CI verifies the same official runbook and workflow alignment checks used locally;
10. upload release evidence as workflow artifacts.

---

## GitHub push sequence

Create an empty GitHub repository first.

Then run:

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'

git branch --show-current
git status --short
git tag --list 'npdev-official-beta-*'

git branch -M main
git remote add origin <GITHUB_REMOTE_URL>

git push -u origin main
git push origin --tags
```

If `origin` already exists:

```powershell
git remote set-url origin <GITHUB_REMOTE_URL>
git push -u origin main
git push origin --tags
```

Validate:

```powershell
git ls-remote --tags origin 'npdev-official-beta-*'
```

The result must include:

```text
npdev-official-beta-20260428-062512
```

---

## GitHub release creation

After CI is green, create a GitHub Release from:

```text
npdev-official-beta-20260428-062512
```

Release title:

```text
NPDev Official Beta Baseline 20260428_062512
```

Attach:

```text
NPDev_General_State_ALL_20260428_062512.zip
NPDev_General_State_NPDevContract_20260428_062512.zip
NPDev_General_State_NPDevEditor_20260428_062512.zip
NPDev_General_State_NPDevGenerator_20260428_062512.zip
NPDev_General_State_NPDevKernel_20260428_062512.zip
NPDev_General_State_NPDevRuntimeHost_20260428_062512.zip
NPDev_General_State_NPDevSamples_20260428_062512.zip
```

Suggested release notes:

```markdown
## NPDev Official Beta Baseline

- ReleaseReady: True
- OfficialReleaseEligible: True
- PackagingMode: RELEASE_READY
- Release evidence run: runtimehost-beta-20260428-001515
- Provenance: git-traceable
- Traceability satisfied: True
- RuntimeHost convergence: complete for current evidence set
- State zip: NPDev_General_State_ALL_20260428_062512.zip
```

---

## Verification commands

### Verify release-ready summary inside latest state zip

```powershell
$zipRoot = 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'
$allZip = Get-ChildItem -LiteralPath $zipRoot -Filter 'NPDev_General_State_ALL_*.zip' |
  Sort-Object Name -Descending |
  Select-Object -First 1

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($allZip.FullName)
try {
  $entry = $zip.GetEntry('release-ready-summary.json')
  if ($null -eq $entry) {
    throw "release-ready-summary.json not found inside $($allZip.FullName)"
  }

  $reader = [System.IO.StreamReader]::new($entry.Open())
  try {
    $summary = $reader.ReadToEnd() | ConvertFrom-Json
  }
  finally {
    $reader.Dispose()
  }

  $summary | Select-Object releaseReady, officialReleaseEligible, packagingMode, releaseEvidenceStatus, releaseEvidenceRunId, provenanceGrade, traceabilitySatisfied, sourceDirty
}
finally {
  $zip.Dispose()
}
```

### Verify Git traceability

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'

git rev-parse HEAD
git status --short
git tag --list 'npdev-official-beta-*'
git show --no-patch --decorate --oneline npdev-official-beta-20260428-062512
```

---

## Recovery guide

### Problem: Playwright download or browser cache failure

Symptoms:

```text
getaddrinfo ENOTFOUND cdn.playwright.dev
Task :playwrightInstall FAILED
```

Fix:

```powershell
Set-Location 'D:\WorkSpace\NPDev_General\NPDevEditor\ui-react'

Resolve-DnsName cdn.playwright.dev
Test-NetConnection cdn.playwright.dev -Port 443
npm ci
npm exec playwright install chromium
```

Then rerun:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\run-editor-gate.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```

### Problem: Git commit identity missing

Symptoms:

```text
officialReleaseEligible=false
provenanceGrade=local-unanchored
traceabilitySatisfied=false
commitIdentity.available=false
```

Fix:

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'

git status
git rev-parse --is-inside-work-tree
git rev-parse HEAD

git config --local user.name "NPDev Local Release"
git config --local user.email "npdev-local@example.invalid"
git add -A
git commit -m "Establish NPDev official beta traceability baseline"
```

Then rerun the local official release sequence.

### Problem: wrong visible zip timestamp

Symptoms:

Windows Explorer shows:

```text
31/12/1999 20:00
```

Cause:

The zip internals are deterministic. Explorer displays the normalized timestamp.

Fix:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\fix-statezip-visible-timestamps.ps1' `
  -ZipRoot 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips'
```

### Problem: stale release evidence

Symptoms:

- state zip says release evidence failed;
- state zip references an old evidence root;
- official eligibility is false despite a later passing beta gate.

Fix:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\statezip-npdev-general.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -OutDir 'D:\WorkSpace\NPDev_General__OutsideRepo\state-zips' `
  -ReleaseReady `
  -ExistingEvidenceRoot last
```

Then run freshness and closure checks.

---

## RuntimeHost policy

RuntimeHost convergence is closed for the current evidence set.

Current policy:

- no new RuntimeHost deletion batch;
- no broad RuntimeHost cleanup;
- only reopen RuntimeHost if a fresh future `runtime-footprint-report.json` introduces new, verified `deadRemoveCandidates`.

Expected current footprint:

```text
deadRemoveCandidates.controllers = []
deadRemoveCandidates.services = []
```

---

## Final definition of done

The roadmap is closed when all are true:

- [ ] GitHub monorepo exists.
- [ ] Baseline tag exists locally and on GitHub.
- [ ] GitHub Actions release gate is green.
- [ ] CI evidence artifact is uploaded.
- [ ] CI state zip artifact is uploaded.
- [ ] Local roadmap closure check passes.
- [ ] This runbook exists.
- [ ] Temporary patch debt is archived or removed.
- [ ] Visible state zip timestamps are fixed after packaging.
- [ ] RuntimeHost has no current dead-remove candidates.
- [ ] `ReleaseReady=True`.
- [ ] `OfficialReleaseEligible=True`.
- [ ] `TraceabilitySatisfied=True`.
- [ ] `ProvenanceGrade=git-traceable` or `ci-traceable`.

---

## Current roadmap interpretation

- Local official beta baseline: complete.
- RuntimeHost convergence: complete.
- Release evidence and state zip: release-ready and traceable.
- Roadmap closure: ~97% locally, ~98% after GitHub Actions is green.

Remaining future-hardening items:

- broader OS CI matrix;
- deeper generated-app compatibility matrix;
- soak tests;
- external release governance;
- real beta-user feedback.
