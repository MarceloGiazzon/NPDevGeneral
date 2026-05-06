param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = "",
    [switch]$DryRun,
    [switch]$OnlyIdeMetadata,
    [switch]$CleanIdeMetadata,
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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\workspace-cleanup-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Test-PathInsideWorkspace([string]$TargetPath) {
    $root = $WorkspaceRoot
    if (-not $root.EndsWith("\")) {
        $root += "\"
    }
    $target = Normalize-NPDevPath $TargetPath
    return $target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-CleanupTarget([string]$TargetPath, [string]$Category) {
    $target = Normalize-NPDevPath $TargetPath
    if (-not (Test-PathInsideWorkspace $target)) {
        throw ("Refusing to clean outside workspace: " + $target)
    }

    $relative = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $target
    if ($relative -eq ".git" -or $relative.StartsWith(".git\", [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ("Refusing to clean git metadata: " + $relative)
    }

    $leaf = Split-Path -Leaf $target
    switch ($Category) {
        "explicit-dir" {
            $allowed = @(
                ".npdev-gradle",
                ".gradle",
                "scripts\reports\tmp",
                "scripts\reports\out",
                "scripts\reports\releases"
            )
            $isSampleOutput = $relative -match '^NPDevSamples\\[^\\]+\\Output$'
            if ($relative -notin $allowed -and -not $isSampleOutput) {
                throw ("Refusing unexpected explicit cleanup path: " + $relative)
            }
        }
        "named-dir" {
            if ($leaf -notin @("build", "target", "dist", "coverage", "node_modules", "RunOutput")) {
                throw ("Refusing unexpected named cleanup path: " + $relative)
            }
        }
        "ide-dir" {
            if ($leaf -notin @(".idea", ".vscode")) {
                throw ("Refusing unexpected IDE cleanup path: " + $relative)
            }
        }
        "ide-file" {
            if (-not $leaf.EndsWith(".iml", [System.StringComparison]::OrdinalIgnoreCase)) {
                throw ("Refusing unexpected IDE file cleanup path: " + $relative)
            }
        }
        default {
            throw ("Unknown cleanup category: " + $Category)
        }
    }
}

function New-CleanupTarget([string]$PathValue, [string]$Category) {
    $normalizedPath = Normalize-NPDevPath $PathValue
    if (-not (Test-Path -LiteralPath $normalizedPath)) {
        return $null
    }

    Assert-CleanupTarget -TargetPath $normalizedPath -Category $Category
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

function Add-CleanupTarget([System.Collections.Generic.List[object]]$Targets, [string]$PathValue, [string]$Category) {
    $candidate = New-CleanupTarget -PathValue $PathValue -Category $Category
    if ($null -ne $candidate -and -not ($Targets | Where-Object { $_.path -eq $candidate.path })) {
        [void]$Targets.Add($candidate)
    }
}

$targets = [System.Collections.Generic.List[object]]::new()

if (-not $OnlyIdeMetadata) {
    foreach ($relativePath in @(".npdev-gradle", ".gradle", "scripts\reports\tmp")) {
        Add-CleanupTarget -Targets $targets -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath) -Category "explicit-dir"
    }

    if ($CleanReportsOut) {
        Add-CleanupTarget -Targets $targets -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out") -Category "explicit-dir"
    }
    if ($CleanReleaseBundles) {
        Add-CleanupTarget -Targets $targets -PathValue (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\releases") -Category "explicit-dir"
    }

    $sampleRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples"
    if (Test-Path -LiteralPath $sampleRoot -PathType Container) {
        foreach ($sampleDir in @(Get-ChildItem -LiteralPath $sampleRoot -Directory -Force -ErrorAction SilentlyContinue)) {
            Add-CleanupTarget -Targets $targets -PathValue (Join-Path $sampleDir.FullName "Output") -Category "explicit-dir"
        }
    }

    foreach ($dir in @(Get-ChildItem -LiteralPath $WorkspaceRoot -Directory -Recurse -Force -ErrorAction SilentlyContinue)) {
        if ($dir.FullName -like "*\.git\*" -or $dir.FullName -like "*\.npdev-gradle\*") {
            continue
        }
        if ($dir.Name -in @("build", "target", "dist", "coverage", "node_modules", "RunOutput")) {
            Add-CleanupTarget -Targets $targets -PathValue $dir.FullName -Category "named-dir"
        }
    }
}

if ($OnlyIdeMetadata -or $CleanIdeMetadata) {
    foreach ($dir in @(Get-ChildItem -LiteralPath $WorkspaceRoot -Directory -Recurse -Force -ErrorAction SilentlyContinue)) {
        if ($dir.FullName -like "*\.git\*") {
            continue
        }
        if ($dir.Name -in @(".idea", ".vscode")) {
            Add-CleanupTarget -Targets $targets -PathValue $dir.FullName -Category "ide-dir"
        }
    }
    foreach ($file in @(Get-ChildItem -LiteralPath $WorkspaceRoot -File -Recurse -Force -Filter "*.iml" -ErrorAction SilentlyContinue)) {
        if ($file.FullName -like "*\.git\*") {
            continue
        }
        Add-CleanupTarget -Targets $targets -PathValue $file.FullName -Category "ide-file"
    }
}

$removedTargets = [System.Collections.Generic.List[object]]::new()
$failedRemovals = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in $targets) {
    if ($DryRun) {
        continue
    }
    if (-not (Test-Path -LiteralPath $candidate.path)) {
        continue
    }

    Assert-CleanupTarget -TargetPath $candidate.path -Category $candidate.category
    try {
        Remove-Item -LiteralPath $candidate.path -Recurse:$candidate.isDirectory -Force
        [void]$removedTargets.Add($candidate)
    }
    catch {
        [void]$failedRemovals.Add([pscustomobject]@{
                relativePath = $candidate.relativePath
                category = $candidate.category
                error = $_.Exception.Message
            })
    }
}

$sizeMeasure = $targets | Measure-Object -Property sizeBytes -Sum
$sizeBytes = if ($null -eq $sizeMeasure -or $null -eq $sizeMeasure.Sum) { 0 } else { [int64]$sizeMeasure.Sum }

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    dryRun = [bool]$DryRun
    overallStatus = if ($failedRemovals.Count -gt 0) { "warning" } else { "passed" }
    candidateCount = $targets.Count
    removedCount = $removedTargets.Count
    failedCount = $failedRemovals.Count
    totalCandidateSizeMB = [math]::Round($sizeBytes / 1MB, 2)
    targets = $targets
    removedTargets = $removedTargets
    failedRemovals = $failedRemovals
}

Write-NPDevJsonFile $ReportPath $report

if ($DryRun) {
    Write-NPDevInfo ("Workspace cleanup dry run found " + $targets.Count + " target(s), " + [math]::Round($sizeBytes / 1MB, 2) + " MB. Report: " + $ReportPath)
}
elseif ($failedRemovals.Count -gt 0) {
    Write-NPDevWarn ("Workspace cleanup completed with " + $failedRemovals.Count + " failed removal(s). Report: " + $ReportPath)
}
else {
    Write-NPDevOk ("Workspace cleanup removed " + $removedTargets.Count + " target(s). Report: " + $ReportPath)
}
