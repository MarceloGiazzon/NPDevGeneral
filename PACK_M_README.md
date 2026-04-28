# NPDev Pack M - GitHub CI Runbook Push Helpers

This pack adds the missing closure items:

- docs/OFFICIAL_BETA_RELEASE_RUNBOOK.md
- .github/workflows/npdev-release-gate.yml
- scripts/release/push-npdev-monorepo-and-tag.ps1
- scripts/quality/apply-pack-m-github-ci-runbook.ps1

## Apply

Extract this zip into:

D:\WorkSpace\NPDev_General

Then run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\quality\apply-pack-m-github-ci-runbook.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General'
```

If you extract directly into the repo, the files are already in the correct paths; the apply script is included as a safety helper.

## Commit

```powershell
Set-Location 'D:\WorkSpace\NPDev_General'
git status --short
git add docs/OFFICIAL_BETA_RELEASE_RUNBOOK.md .github/workflows/npdev-release-gate.yml scripts/release/push-npdev-monorepo-and-tag.ps1 scripts/quality/apply-pack-m-github-ci-runbook.ps1
git commit -m "Add NPDev official beta runbook and CI release gate"
```

## Push

Create an empty GitHub repo first, then run:

```powershell
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass `
  -File 'D:\WorkSpace\NPDev_General\scripts\release\push-npdev-monorepo-and-tag.ps1' `
  -WorkspaceRoot 'D:\WorkSpace\NPDev_General' `
  -GitHubRemoteUrl '<GITHUB_REMOTE_URL>' `
  -ForceBranchRename
```
