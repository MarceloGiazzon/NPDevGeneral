param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = "",
    [switch]$DryRun,
    [switch]$CleanReportsOut,
    [switch]$CleanReleaseBundles
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$reportInWorkspace = -not [string]::IsNullOrWhiteSpace($ReportPath)
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-rebuildable-cleanup-" + (Get-Date).ToString("yyyyMMdd-HHmmss") + ".json")
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$workspaceCleanupScript = Join-Path $PSScriptRoot "clean-workspace-state.ps1"
Ensure-NPDevFile -PathValue $workspaceCleanupScript -Label "Workspace cleanup script"
$workspaceCleanupReportPath = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-workspace-cleanup-" + (Get-Date).ToString("yyyyMMdd-HHmmss") + ".json")

& $workspaceCleanupScript `
    -WorkspaceRoot $WorkspaceRoot `
    -ReportPath $workspaceCleanupReportPath `
    -DryRun:$DryRun `
    -CleanIdeMetadata `
    -CleanReportsOut:$CleanReportsOut `
    -CleanReleaseBundles:$CleanReleaseBundles

if (-not (Test-Path -LiteralPath $workspaceCleanupReportPath)) {
    throw "Workspace cleanup script did not emit its temporary report."
}

$workspaceCleanupReport = Get-Content -LiteralPath $workspaceCleanupReportPath -Raw | ConvertFrom-Json

function Test-PathInsideWorkspace([string]$TargetPath) {
    $root = $WorkspaceRoot
    if (-not $root.EndsWith("\")) {
        $root += "\"
    }
    $target = Normalize-NPDevPath $TargetPath
    return $target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-RebuildableTarget([string]$TargetPath, [string]$Category) {
    $target = Normalize-NPDevPath $TargetPath
    if (-not (Test-PathInsideWorkspace $target)) {
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

function New-RebuildableTarget([string]$PathValue, [string]$Category) {
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
}
elseif ($failedCount -gt 0) {
    Write-NPDevWarn ("Rebuildable artifact cleanup completed with " + $failedCount + " failed removal(s). Report: " + $ReportPath)
}
else {
    Write-NPDevOk ("Rebuildable artifact cleanup removed workspace state plus " + $removedExtraTargets.Count + " RuntimeHost-specific target(s). Report: " + $ReportPath)
}
