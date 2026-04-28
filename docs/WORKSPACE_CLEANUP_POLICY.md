# Workspace Cleanup Policy

NPDev source should stay separate from generated state. Build output, sample output, local dependency caches, release reports, logs, and workspace snapshots are disposable unless explicitly promoted into a fixture or release artifact.

## Intentionally Versioned

These remain source and should not be cleaned as residue:

- `README.md`, `PROJECT_DIGEST.md`, `MIGRATION_DIGEST.md`, and committed sample input/reference docs.
- Canonical sample `Input` trees and deliberately checked-in generated app scaffolds such as sample `Output\App\.gitignore`, `PROJECT_DIGEST.md`, and `MIGRATION_DIGEST.md`.
- The current authoritative release decision in `scripts\reports\out\beta-release-gate-report.json` while a release run is active.

## Disposable By Default

- Gradle/npm/build caches: `.npdev-gradle`, `.gradle`, `build`, `node_modules`, `dist`, `coverage`, `target`.
- Generated app/sample output: `Output`, `RunOutput`.
- Rebuildable RuntimeHost assembly residue: `NPDevRuntimeHost\libs`, `NPDevRuntimeHost\npdev-generated`, `NPDevRuntimeHost\npdev-meta`, generated `NPDevRuntimeHost\build.gradle`, and `npdev-build-info.properties`.
- IDE-local metadata: `.idea`, `.vscode`, `*.iml`.
- Local runtime residue: `*.log`, `*.pid`, temporary files.
- Root workspace archives: `*.zip`, `*.7z`, `*.rar`.
- Empty archived-source placeholders such as empty `src-disabled` folders.

Empty `NPDevSamples\<sample>\Output\Reports` scaffolds are intentionally retained because layout checks expect them. Generated files inside sample `Output` directories remain disposable.

## Evidence Handling

The active release decision remains `scripts\reports\out\beta-release-gate-report.json`. Release bundles under `scripts\reports\releases` are artifacts and should be uploaded or archived outside source control when needed.

Local retention keeps only the newest 5 release bundles by default. If a bundle is a milestone worth keeping longer, export it outside the workspace first or pass it explicitly to the prune script as a preserved bundle.

Do not manually combine focused reports into a release claim. Rerun:

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
```

## Cleanup Command

Preview cleanup:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1 -DryRun
```

Preview IDE-local cleanup only:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1 -OnlyIdeMetadata -DryRun
```

Apply cleanup:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1
```

The cleanup script verifies every recursive delete target is inside the workspace and belongs to an explicit disposable category before removal.

Full rebuildable-artifact cleanup:

```powershell
pwsh -File scripts\hygiene\clean-rebuildable-artifacts.ps1
```

That wrapper removes the disposable workspace state above, clears `scripts\reports\out`, deletes all release bundles under `scripts\reports\releases`, and removes local RuntimeHost synced jars/build residue. By default it writes its report to the OS temp directory so the workspace does not get a fresh cleanup artifact immediately after being cleaned.

## Release Bundle Retention

Preview release bundle pruning:

```powershell
pwsh -File scripts\hygiene\prune-release-evidence.ps1 -DryRun
```

Apply the default retention window:

```powershell
pwsh -File scripts\hygiene\prune-release-evidence.ps1 -KeepLatest 5
```

Preserve a specific bundle while pruning:

```powershell
pwsh -File scripts\hygiene\prune-release-evidence.ps1 -KeepLatest 5 -PreserveReleaseBundle runtimehost-beta-YYYYMMDD-HHMMSS
```
    $target = Normalize-NPDevPath $TargetPath
    if (-not $root.EndsWith("\")) {
        $root += "\"
    }
    return $target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-RebuildableTarget(
    [string]$TargetPath,
    [string]$Category
) {
    $target = Normalize-NPDevPath $TargetPath
    if (-not (Test-PathInsideRoot -RootPath $WorkspaceRoot -TargetPath $target)) {
        throw ("Refusing to clean outside workspace: " + $target)
    }

    $relative = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $target
    switch ($Category) {
        "runtimehost-local-libs" {
            if ($relative -ne "NPDevRuntimeHost\libs") {
                throw ("Refusing unexpected RuntimeHost libs path: " + $relative)
            }
        }
        "runtimehost-generated-dir" {
            if ($relative -notin @("NPDevRuntimeHost\npdev-generated", "NPDevRuntimeHost\npdev-meta", "NPDevRuntimeHost\runtime-data")) {
                throw ("Refusing unexpected RuntimeHost generated directory: " + $relative)
            }
        }
        "runtimehost-generated-file" {
            if ($relative -notin @("NPDevRuntimeHost\build.gradle", "NPDevRuntimeHost\npdev-build-info.properties")) {
                throw ("Refusing unexpected RuntimeHost generated file: " + $relative)
            }
        }
        default {
            throw ("Unknown rebuildable target category: " + $Category)
        }
    }
}

function New-RebuildableTarget(
    [string]$PathValue,
    [string]$Category
) {
    $normalizedPath = Normalize-NPDevPath $PathValue
    if (-not (Test-Path -LiteralPath $normalizedPath)) {
        return $null
    }

    Assert-RebuildableTarget -TargetPath $normalizedPath -Category $Category
    $item = Get-Item -LiteralPath $normalizedPath -Force
    $files = @(if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $normalizedPath -Recurse -File -Force -ErrorAction SilentlyContinue
        }
        else {
            $item
        })
    $sizeMeasure = $files | Measure-Object -Property Length -Sum
    $sizeBytes = if ($null -eq $sizeMeasure -or $null -eq $sizeMeasure.Sum) { 0 } else { [int64]$sizeMeasure.Sum }

    return [pscustomobject]@{
        path = $normalizedPath
        relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $normalizedPath
        category = $Category
        isDirectory = [bool]$item.PSIsContainer
        fileCount = $files.Count
        sizeBytes = $sizeBytes
    }
}

& $workspaceCleanupScript `
    -WorkspaceRoot $WorkspaceRoot `
    -ReportPath $workspaceCleanupReportPath `
    -DryRun:$DryRun `
    -CleanIdeMetadata `
    -CleanReportsOut `
    -CleanReleaseBundles

if (-not (Test-Path -LiteralPath $workspaceCleanupReportPath)) {
    throw "Workspace cleanup script did not emit its temporary report."
}

$workspaceCleanupReport = Get-Content -LiteralPath $workspaceCleanupReportPath -Raw | ConvertFrom-Json

$extraTargets = [System.Collections.Generic.List[object]]::new()
foreach ($target in @(
        @{ path = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\libs"); category = "runtimehost-local-libs" },
        @{ path = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\npdev-generated"); category = "runtimehost-generated-dir" },
        @{ path = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\npdev-meta"); category = "runtimehost-generated-dir" },
        @{ path = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\runtime-data"); category = "runtimehost-generated-dir" },
        @{ path = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\build.gradle"); category = "runtimehost-generated-file" },
        @{ path = (Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\npdev-build-info.properties"); category = "runtimehost-generated-file" }
    )) {
    $candidate = New-RebuildableTarget -PathValue $target.path -Category $target.category
    if ($null -ne $candidate) {
        [void]$extraTargets.Add($candidate)
    }
}

$removedExtraTargets = [System.Collections.Generic.List[object]]::new()
$failedExtraRemovals = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in $extraTargets) {
    if ($DryRun) {
        continue
    }
    if (-not (Test-Path -LiteralPath $candidate.path)) {
        continue
    }

    Assert-RebuildableTarget -TargetPath $candidate.path -Category $candidate.category
    try {
        Remove-Item -LiteralPath $candidate.path -Recurse:$candidate.isDirectory -Force
        [void]$removedExtraTargets.Add($candidate)
    }
    catch {
        [void]$failedExtraRemovals.Add([pscustomobject]@{
                relativePath = $candidate.relativePath
                category = $candidate.category
                error = $_.Exception.Message
            })
    }
}

$extraSizeMeasure = $extraTargets | Measure-Object -Property sizeBytes -Sum
$extraBytes = if ($null -eq $extraSizeMeasure -or $null -eq $extraSizeMeasure.Sum) { 0 } else { [int64]$extraSizeMeasure.Sum }
$failedCount = @($workspaceCleanupReport.failedRemovals).Count + $failedExtraRemovals.Count

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedCount -gt 0) { "warning" } else { "passed" }
    dryRun = [bool]$DryRun
    reportInWorkspace = [bool]$reportInWorkspace
    baseCleanupReportPath = $workspaceCleanupReportPath
    summary = [pscustomobject]@{
        baseCleanupCandidates = [int]$workspaceCleanupReport.candidateCount
        baseCleanupRemoved = [int]$workspaceCleanupReport.removedCount
        extraTargets = $extraTargets.Count
        extraTargetsRemoved = $removedExtraTargets.Count
        totalExtraSizeMB = [math]::Round($extraBytes / 1MB, 2)
        failedRemovals = $failedCount
    }
    baseCleanup = $workspaceCleanupReport
    extraTargets = $extraTargets
    removedExtraTargets = $removedExtraTargets
    failedExtraRemovals = $failedExtraRemovals
}

Write-NPDevJsonFile $ReportPath $report

if ($DryRun) {
    Write-NPDevInfo ("Rebuildable artifact cleanup dry run found " + ($workspaceCleanupReport.candidateCount + $extraTargets.Count) + " target(s). Report: " + $ReportPath)
    return
}

if ($failedCount -gt 0) {
    Write-NPDevWarn ("Rebuildable artifact cleanup completed with " + $failedCount + " failed removal(s). Report: " + $ReportPath)
    return
}

Write-NPDevOk ("Rebuildable artifact cleanup removed workspace state plus " + $removedExtraTargets.Count + " RuntimeHost-specific target(s). Report: " + $ReportPath)
