[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = "",
    [switch]$DryRun,
    [switch]$CleanIdeMetadata,
    [switch]$OnlyIdeMetadata,
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

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\workspace-clean-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

if ($OnlyIdeMetadata) {
    $CleanIdeMetadata = $true
}

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

function Assert-DisposablePath(
    [string]$TargetPath,
    [string]$Category
) {
    $target = Normalize-NPDevPath $TargetPath
    if (-not (Test-PathInsideRoot -RootPath $WorkspaceRoot -TargetPath $target)) {
        throw ("Refusing to clean outside workspace: " + $target)
    }

    $relative = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $target
    if ($relative -eq "" -or $relative -eq ".") {
        throw "Refusing to clean the workspace root."
    }

    if ($relative.StartsWith(".git\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ("Refusing to clean git metadata: " + $relative)
    }

    switch ($Category) {
        "cache-directory" {
            $leaf = Split-Path -Leaf $target
            if ($leaf -notin @(".npdev-gradle", ".gradle", "build", "node_modules", "dist", "coverage", "target")) {
                throw ("Refusing unexpected cache directory: " + $relative)
            }
        }
        "generated-output" {
            if ($relative -notmatch '^(NPDevSamples\\[^\\]+\\Output|NPDevContract\\examples\\[^\\]+\\Output)(\\|$)') {
                throw ("Refusing unexpected generated output path: " + $relative)
            }
        }
        "runtime-residue" {
            $extension = [System.IO.Path]::GetExtension($target).ToLowerInvariant()
            if ($extension -notin @(".log", ".pid", ".tmp", ".temp", ".bak")) {
                throw ("Refusing unexpected runtime residue: " + $relative)
            }
        }
        "root-archive" {
            $parent = Split-Path -Parent $target
            $extension = [System.IO.Path]::GetExtension($target).ToLowerInvariant()
            if ((Normalize-NPDevPath $parent) -ne $WorkspaceRoot -or $extension -notin @(".zip", ".7z", ".rar")) {
                throw ("Refusing unexpected root archive: " + $relative)
            }
        }
        "ide-metadata" {
            if (Test-Path -LiteralPath $target -PathType Container) {
                $leaf = Split-Path -Leaf $target
                if ($leaf -notin @(".idea", ".vscode")) {
                    throw ("Refusing unexpected IDE metadata directory: " + $relative)
                }
                break
            }

            $extension = [System.IO.Path]::GetExtension($target).ToLowerInvariant()
            if ($extension -notin @(".iml", ".ipr", ".iws")) {
                throw ("Refusing unexpected IDE metadata file: " + $relative)
            }
        }
        "empty-src-disabled" {
            if ((Split-Path -Leaf $target) -ne "src-disabled") {
                throw ("Refusing unexpected disabled-source path: " + $relative)
            }
            $files = @(Get-ChildItem -LiteralPath $target -Recurse -File -Force -ErrorAction SilentlyContinue)
            if ($files.Count -gt 0) {
                throw ("Refusing to remove non-empty src-disabled path: " + $relative)
            }
        }
        "report-artifact" {
            $reportsRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports"
            if (-not (Test-PathInsideRoot -RootPath $reportsRoot -TargetPath $target)) {
                throw ("Refusing unexpected report artifact: " + $relative)
            }
        }
        default {
            throw ("Unknown cleanup category: " + $Category)
        }
    }
}

function New-CleanupCandidate(
    [string]$PathValue,
    [string]$Category
) {
    $normalizedPath = Normalize-NPDevPath $PathValue
    if (-not (Test-Path -LiteralPath $normalizedPath)) {
        return $null
    }

    Assert-DisposablePath -TargetPath $normalizedPath -Category $Category
    $item = Get-Item -LiteralPath $normalizedPath -Force
    $files = @(if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $normalizedPath -Recurse -File -Force -ErrorAction SilentlyContinue
        }
        else {
            $item
        })

    $sizeMeasure = $files | Measure-Object -Property Length -Sum
    $sizeBytes = if ($null -eq $sizeMeasure) { 0 } else { $sizeMeasure.Sum }
    if ($null -eq $sizeBytes) {
        $sizeBytes = 0
    }

    if ($Category -eq "generated-output" -and $files.Count -eq 0) {
        return $null
    }

    return [pscustomobject]@{
        path = $normalizedPath
        relativePath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $normalizedPath
        category = $Category
        isDirectory = [bool]$item.PSIsContainer
        fileCount = $files.Count
        sizeBytes = [int64]$sizeBytes
    }
}

function Add-Candidate(
    [System.Collections.Generic.List[object]]$Candidates,
    [string]$PathValue,
    [string]$Category
) {
    $candidate = New-CleanupCandidate -PathValue $PathValue -Category $Category
    if ($null -ne $candidate -and $candidate.relativePath -notin @($Candidates | Select-Object -ExpandProperty relativePath)) {
        [void]$Candidates.Add($candidate)
    }
}

$candidates = [System.Collections.Generic.List[object]]::new()

if (-not $OnlyIdeMetadata) {
    $cacheNames = @(".npdev-gradle", ".gradle", "build", "node_modules", "dist", "coverage", "target")
    Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Directory -Force -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -in $cacheNames -and
        $_.FullName -notmatch "\\.git(\\|$)" -and
        $_.FullName -notmatch "\\scripts\\reports\\releases(\\|$)"
    } | ForEach-Object {
        Add-Candidate -Candidates $candidates -PathValue $_.FullName -Category "cache-directory"
    }

    foreach ($relativeGeneratedRoot in @("NPDevSamples", "NPDevContract\examples")) {
        $root = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativeGeneratedRoot
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            continue
        }
        Get-ChildItem -LiteralPath $root -Directory -Force -ErrorAction SilentlyContinue | ForEach-Object {
            $outputPath = Join-Path $_.FullName "Output"
            Add-Candidate -Candidates $candidates -PathValue $outputPath -Category "generated-output"
        }
    }

    Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Directory -Force -Filter "src-disabled" -ErrorAction SilentlyContinue | ForEach-Object {
        $files = @(Get-ChildItem -LiteralPath $_.FullName -Recurse -File -Force -ErrorAction SilentlyContinue)
        if ($files.Count -eq 0) {
            Add-Candidate -Candidates $candidates -PathValue $_.FullName -Category "empty-src-disabled"
        }
    }

    Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -File -Force -Include "*.log", "*.pid", "*.tmp", "*.temp", "*.bak" -ErrorAction SilentlyContinue | Where-Object {
        $_.FullName -notmatch "\\scripts\\reports\\releases(\\|$)"
    } | ForEach-Object {
        Add-Candidate -Candidates $candidates -PathValue $_.FullName -Category "runtime-residue"
    }

    Get-ChildItem -LiteralPath $WorkspaceRoot -File -Force -ErrorAction SilentlyContinue | Where-Object {
        [System.IO.Path]::GetExtension($_.FullName).ToLowerInvariant() -in @(".zip", ".7z", ".rar")
    } | ForEach-Object {
        Add-Candidate -Candidates $candidates -PathValue $_.FullName -Category "root-archive"
    }
}

if ($CleanIdeMetadata) {
    Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Directory -Force -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -in @(".idea", ".vscode") -and
        $_.FullName -notmatch "\\.git(\\|$)"
    } | ForEach-Object {
        Add-Candidate -Candidates $candidates -PathValue $_.FullName -Category "ide-metadata"
    }

    Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -File -Force -Include "*.iml", "*.ipr", "*.iws" -ErrorAction SilentlyContinue | Where-Object {
        $_.FullName -notmatch "\\.git(\\|$)"
    } | ForEach-Object {
        Add-Candidate -Candidates $candidates -PathValue $_.FullName -Category "ide-metadata"
    }
}

if ($CleanReportsOut) {
    Add-Candidate -Candidates $candidates -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out") -Category "report-artifact"
}
if ($CleanReleaseBundles) {
    Add-Candidate -Candidates $candidates -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\releases") -Category "report-artifact"
}

function Test-CandidateIsInside(
    [object]$ParentCandidate,
    [object]$ChildCandidate
) {
    if ($ParentCandidate.path -eq $ChildCandidate.path) {
        return $true
    }
    if (-not $ParentCandidate.isDirectory) {
        return $false
    }
    return Test-PathInsideRoot -RootPath $ParentCandidate.path -TargetPath $ChildCandidate.path
}

$topLevelCandidates = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in @($candidates | Sort-Object @{ Expression = { $_.relativePath.Length } }, relativePath)) {
    $coveredByExisting = $false
    foreach ($existing in $topLevelCandidates) {
        if (Test-CandidateIsInside -ParentCandidate $existing -ChildCandidate $candidate) {
            $coveredByExisting = $true
            break
        }
    }
    if (-not $coveredByExisting) {
        [void]$topLevelCandidates.Add($candidate)
    }
}

$orderedCandidates = @($topLevelCandidates | Sort-Object @{ Expression = { $_.relativePath.Length }; Descending = $true }, relativePath)
$removed = @()
$failedRemovals = @()
foreach ($candidate in $orderedCandidates) {
    if ($DryRun) {
        continue
    }
    if (-not (Test-Path -LiteralPath $candidate.path)) {
        continue
    }

    Assert-DisposablePath -TargetPath $candidate.path -Category $candidate.category
    try {
        Remove-Item -LiteralPath $candidate.path -Recurse:$candidate.isDirectory -Force
        if ($candidate.category -eq "generated-output") {
            New-Item -ItemType Directory -Force -Path $candidate.path | Out-Null
            if ($candidate.relativePath -match '^NPDevSamples\\[^\\]+\\Output$') {
                New-Item -ItemType Directory -Force -Path (Join-Path $candidate.path "Reports") | Out-Null
            }
        }
        $removed += $candidate
    }
    catch {
        $failedRemovals += [pscustomobject]@{
            relativePath = $candidate.relativePath
            category = $candidate.category
            error = $_.Exception.Message
        }
    }
}

$totalMeasure = $orderedCandidates | Measure-Object -Property sizeBytes -Sum
$totalBytes = if ($null -eq $totalMeasure) { 0 } else { $totalMeasure.Sum }
if ($null -eq $totalBytes) {
    $totalBytes = 0
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedRemovals.Count -gt 0) { "warning" } else { "passed" }
    dryRun = [bool]$DryRun
    cleanIdeMetadata = [bool]$CleanIdeMetadata
    onlyIdeMetadata = [bool]$OnlyIdeMetadata
    candidateCount = $orderedCandidates.Count
    removedCount = $removed.Count
    failedRemovalCount = $failedRemovals.Count
    totalCandidateSizeMB = [math]::Round($totalBytes / 1MB, 2)
    categories = @($orderedCandidates | Group-Object category | ForEach-Object {
            $measure = $_.Group | Measure-Object -Property sizeBytes -Sum
            $bytes = if ($null -eq $measure) { 0 } else { $measure.Sum }
            if ($null -eq $bytes) {
                $bytes = 0
            }
            [pscustomobject]@{
                category = $_.Name
                count = $_.Count
                sizeMB = [math]::Round($bytes / 1MB, 2)
            }
        })
    candidates = $orderedCandidates
    failedRemovals = $failedRemovals
}

Write-NPDevJsonFile $ReportPath $report

if ($DryRun) {
    Write-NPDevInfo ("Workspace cleanup dry run found " + $orderedCandidates.Count + " candidate(s), " + $report.totalCandidateSizeMB + " MB.")
    return
}

if ($failedRemovals.Count -gt 0) {
    Write-NPDevWarn ("Workspace cleanup removed " + $removed.Count + " item(s), with " + $failedRemovals.Count + " locked/failed item(s).")
    return
}

Write-NPDevOk ("Workspace cleanup removed " + $removed.Count + " item(s), " + $report.totalCandidateSizeMB + " MB candidate size.")
