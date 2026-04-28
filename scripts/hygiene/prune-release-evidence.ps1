[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = "",
    [int]$KeepLatest = 5,
    [string[]]$PreserveReleaseBundle = @(),
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\release-evidence-prune-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if ($KeepLatest -lt 1) {
    throw "KeepLatest must be >= 1."
}

$releasesRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\releases"
Ensure-NPDevDirectory $releasesRoot "Release evidence root"

function Test-PathInsideRoot(
    [string]$RootPath,
    [string]$TargetPath
) {
    $root = Normalize-NPDevPath $RootPath
    $target = Normalize-NPDevPath $TargetPath
    if (-not $root.EndsWith("\")) {
        $root += "\"
    }
    return $target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-ReleaseBundlePath(
    [string]$TargetPath
) {
    $target = Normalize-NPDevPath $TargetPath
    if (-not (Test-PathInsideRoot -RootPath $releasesRoot -TargetPath $target)) {
        throw ("Refusing to prune outside release evidence root: " + $target)
    }

    $relative = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $target
    if ($relative -eq "" -or $relative -eq "." -or $relative -eq "scripts\reports\releases") {
        throw "Refusing to prune the release evidence root itself."
    }

    if (-not (Test-Path -LiteralPath $target -PathType Container)) {
        throw ("Refusing to prune non-directory release evidence: " + $relative)
    }
}

function New-ReleaseBundleEntry(
    [System.IO.DirectoryInfo]$Directory
) {
    Assert-ReleaseBundlePath -TargetPath $Directory.FullName
    $files = @(Get-ChildItem -LiteralPath $Directory.FullName -Recurse -File -Force -ErrorAction SilentlyContinue)
    $sizeMeasure = $files | Measure-Object -Property Length -Sum
    $sizeBytes = if ($null -eq $sizeMeasure -or $null -eq $sizeMeasure.Sum) { 0 } else { [int64]$sizeMeasure.Sum }
    return [pscustomobject]@{
        name = $Directory.Name
        relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $Directory.FullName
        lastWriteTime = $Directory.LastWriteTime.ToString("o")
        fileCount = $files.Count
        sizeMB = [math]::Round($sizeBytes / 1MB, 2)
    }
}

$preserveSet = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($bundleName in $PreserveReleaseBundle) {
    if (-not [string]::IsNullOrWhiteSpace($bundleName)) {
        [void]$preserveSet.Add($bundleName.Trim())
    }
}

$bundleDirectories = @(Get-ChildItem -LiteralPath $releasesRoot -Directory -Force | Sort-Object @{ Expression = { $_.LastWriteTime }; Descending = $true }, @{ Expression = { $_.Name }; Descending = $true })
$keptBundles = [System.Collections.Generic.List[object]]::new()
$pruneCandidates = [System.Collections.Generic.List[object]]::new()
$missingPreserveRequests = [System.Collections.Generic.List[string]]::new()

for ($index = 0; $index -lt $bundleDirectories.Count; $index++) {
    $directory = $bundleDirectories[$index]
    $entry = New-ReleaseBundleEntry -Directory $directory
    $preserveRequested = $preserveSet.Contains($directory.Name)
    $keepReason = if ($index -lt $KeepLatest) {
        "latest-window"
    }
    elseif ($preserveRequested) {
        "explicit-preserve"
    }
    else {
        $null
    }

    if ($null -ne $keepReason) {
        [void]$keptBundles.Add([pscustomobject]@{
                name = $entry.name
                relativePath = $entry.relativePath
                lastWriteTime = $entry.lastWriteTime
                fileCount = $entry.fileCount
                sizeMB = $entry.sizeMB
                keepReason = $keepReason
            })
        continue
    }

    [void]$pruneCandidates.Add($entry)
}

foreach ($requested in $preserveSet) {
    if ($requested -notin @($bundleDirectories | Select-Object -ExpandProperty Name)) {
        [void]$missingPreserveRequests.Add($requested)
    }
}

$removedBundles = [System.Collections.Generic.List[object]]::new()
$failedRemovals = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in $pruneCandidates) {
    if ($DryRun) {
        continue
    }
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $candidate.relativePath
    if (-not (Test-Path -LiteralPath $absolutePath)) {
        continue
    }

    Assert-ReleaseBundlePath -TargetPath $absolutePath
    try {
        Remove-Item -LiteralPath $absolutePath -Recurse -Force
        [void]$removedBundles.Add($candidate)
    }
    catch {
        [void]$failedRemovals.Add([pscustomobject]@{
                name = $candidate.name
                relativePath = $candidate.relativePath
                error = $_.Exception.Message
            })
    }
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    releasesRoot = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $releasesRoot
    overallStatus = if ($failedRemovals.Count -gt 0 -or $missingPreserveRequests.Count -gt 0) { "warning" } else { "passed" }
    dryRun = [bool]$DryRun
    keepLatest = $KeepLatest
    preserveReleaseBundle = @($preserveSet | Sort-Object)
    bundleCount = $bundleDirectories.Count
    keptCount = $keptBundles.Count
    pruneCandidateCount = $pruneCandidates.Count
    removedCount = $removedBundles.Count
    failedRemovalCount = $failedRemovals.Count
    missingPreserveRequests = @($missingPreserveRequests)
    keptBundles = $keptBundles
    pruneCandidates = $pruneCandidates
    removedBundles = $removedBundles
    failedRemovals = $failedRemovals
}

Write-NPDevJsonFile $ReportPath $report

if ($DryRun) {
    Write-NPDevInfo ("Release evidence prune dry run found " + $pruneCandidates.Count + " bundle(s) outside the latest " + $KeepLatest + ".")
    return
}

if ($failedRemovals.Count -gt 0) {
    Write-NPDevWarn ("Release evidence pruning removed " + $removedBundles.Count + " bundle(s), with " + $failedRemovals.Count + " failure(s).")
    return
}

if ($missingPreserveRequests.Count -gt 0) {
    Write-NPDevWarn ("Release evidence pruning completed, but " + $missingPreserveRequests.Count + " preserve request(s) did not match an existing bundle.")
    return
}

Write-NPDevOk ("Release evidence pruning kept " + $keptBundles.Count + " bundle(s) and removed " + $removedBundles.Count + ".")
