param(
    [string]$WorkspaceRoot = "",
    [string]$ReportPath = "",
    [string]$RunId = "",
    [int]$MaxFileCount = 3000,
    [decimal]$MaxSizeMB = 75,
    [int]$MaxScriptsFileCount = 500,
    [decimal]$MaxScriptsSizeMB = 10,
    [decimal]$MaxReportsOutSizeMB = 15,
    [bool]$CleanTransientReportTemp = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path (Split-Path $WorkspaceRoot -Parent) "Build\reports\workspace-cleanliness-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Get-RelativePath([string]$PathValue) {
    return (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PathValue).Replace("/", "\")
}

function Get-LengthSum {
    # StrictMode-safe total of the .Length property across the supplied items.
    # Measure-Object's result does not expose a usable Sum for an empty set, and
    # under Set-StrictMode -Version Latest dereferencing .Sum then throws instead
    # of yielding $null. Summing manually avoids that whole class of failure.
    param([object[]]$Items)
    $sum = [long]0
    if ($null -eq $Items) { return $sum }
    foreach ($item in $Items) {
        if ($null -ne $item -and $null -ne $item.Length) {
            $sum += [long]$item.Length
        }
    }
    return $sum
}

function Get-FileSummary([string]$PathValue) {
    $normalized = Normalize-NPDevPath $PathValue
    if (-not (Test-Path -LiteralPath $normalized)) {
        return [pscustomobject]@{
            path = Get-RelativePath $normalized
            exists = $false
            fileCount = 0
            sizeMB = 0
        }
    }

    $item = Get-Item -LiteralPath $normalized -Force
    $files = @(if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $normalized -Recurse -Force -File -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -notmatch "\\.git\\" }
        }
        else {
            $item
        })
    $size = Get-LengthSum $files
    return [pscustomobject]@{
        path = Get-RelativePath $normalized
        exists = $true
        fileCount = $files.Count
        sizeMB = [math]::Round(([decimal]$size) / 1MB, 2)
    }
}

function Add-Violation {
    param(
        [System.Collections.Generic.List[object]]$Violations,
        [string]$Code,
        [string]$Message,
        [object]$Details = $null
    )
    [void]$Violations.Add([pscustomobject]@{
            code = $Code
            message = $Message
            details = $Details
        })
}

$cleanedTransientDirectories = @()
if ($CleanTransientReportTemp) {
    $transientReportsTmp = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\tmp"
    if (Test-Path -LiteralPath $transientReportsTmp -PathType Container) {
        Remove-Item -LiteralPath $transientReportsTmp -Recurse -Force
        $cleanedTransientDirectories += "scripts\reports\tmp"
    }
}

$allFiles = @(Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Force -File -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "\\.git\\" })
$totalBytes = Get-LengthSum $allFiles
$totalSizeMB = [math]::Round(([decimal]$totalBytes) / 1MB, 2)

$topLevel = @($allFiles | ForEach-Object {
        $relative = Get-RelativePath $_.FullName
        $top = ($relative -split "\\")[0]
        [pscustomobject]@{ top = $top; length = $_.Length }
    } | Group-Object top | ForEach-Object {
        [pscustomobject]@{
            path = $_.Name
            fileCount = $_.Count
            sizeMB = [math]::Round(([decimal](($_.Group | Measure-Object length -Sum).Sum)) / 1MB, 2)
        }
    } | Sort-Object sizeMB -Descending)

$violations = [System.Collections.Generic.List[object]]::new()

if ($allFiles.Count -gt $MaxFileCount) {
    Add-Violation $violations "workspace-file-count-limit" ("Workspace has " + $allFiles.Count + " files; limit is " + $MaxFileCount + ".") ([pscustomobject]@{ actual = $allFiles.Count; limit = $MaxFileCount })
}
if ($totalSizeMB -gt $MaxSizeMB) {
    Add-Violation $violations "workspace-size-limit" ("Workspace is " + $totalSizeMB + " MB; limit is " + $MaxSizeMB + " MB.") ([pscustomobject]@{ actualMB = $totalSizeMB; limitMB = $MaxSizeMB })
}

$scriptsSummary = Get-FileSummary (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts")
if ($scriptsSummary.fileCount -gt $MaxScriptsFileCount) {
    Add-Violation $violations "scripts-file-count-limit" ("scripts contains " + $scriptsSummary.fileCount + " files; limit is " + $MaxScriptsFileCount + ".") $scriptsSummary
}
if ($scriptsSummary.sizeMB -gt $MaxScriptsSizeMB) {
    Add-Violation $violations "scripts-size-limit" ("scripts is " + $scriptsSummary.sizeMB + " MB; limit is " + $MaxScriptsSizeMB + " MB.") $scriptsSummary
}

$reportsOutSummary = Get-FileSummary (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out")
if ($reportsOutSummary.sizeMB -gt $MaxReportsOutSizeMB) {
    Add-Violation $violations "reports-out-size-limit" ("scripts/reports/out is " + $reportsOutSummary.sizeMB + " MB; limit is " + $MaxReportsOutSizeMB + " MB.") $reportsOutSummary
}

$forbiddenExplicitDirs = @(
    "scripts\reports\tmp",
    "scripts\reports\cache",
    "NPDevRuntimeHost\libs",
    "NPDevRuntimeHost\npdev-generated",
    "NPDevRuntimeHost\npdev-meta",
    "NPDevRuntimeHost\runtime-data"
)
foreach ($relative in $forbiddenExplicitDirs) {
    $summary = Get-FileSummary (Resolve-NPDevWorkspacePath $WorkspaceRoot $relative)
    if ($summary.exists -and $summary.fileCount -gt 0) {
        Add-Violation $violations "forbidden-generated-tree" ("Generated/cache tree must not remain in the workspace: " + $summary.path) $summary
    }
}

$sampleOutputRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples"
if (Test-Path -LiteralPath $sampleOutputRoot -PathType Container) {
    foreach ($sampleDir in @(Get-ChildItem -LiteralPath $sampleOutputRoot -Directory -Force -ErrorAction SilentlyContinue)) {
        $summary = Get-FileSummary (Join-Path $sampleDir.FullName "Output")
        if ($summary.exists -and $summary.fileCount -gt 0) {
            Add-Violation $violations "sample-output-inside-workspace" ("Sample generated Output must be cleaned or moved outside the workspace: " + $summary.path) $summary
        }
    }
}

$forbiddenDirNames = @(".gradle", "build", "target", "dist", "coverage", "node_modules", "RunOutput", "bin")
foreach ($dir in @(Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Force -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "\\.git\\" -and $_.Name -in $forbiddenDirNames })) {
    $summary = Get-FileSummary $dir.FullName
    if ($summary.fileCount -gt 0) {
        Add-Violation $violations "rebuildable-directory-inside-workspace" ("Rebuildable directory must not remain in the workspace: " + $summary.path) $summary
    }
}

$jarViolations = @(Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Force -File -Filter "*.jar" -ErrorAction SilentlyContinue |
        Where-Object {
            $_.FullName -notmatch "\\.git\\" -and
            ((Get-RelativePath $_.FullName) -notmatch "gradle\\wrapper\\gradle-wrapper\.jar$")
        } |
        ForEach-Object {
            [pscustomobject]@{
                path = Get-RelativePath $_.FullName
                sizeMB = [math]::Round(([decimal]$_.Length) / 1MB, 2)
            }
        })
if ($jarViolations.Count -gt 0) {
    Add-Violation $violations "jar-inside-workspace" "Only Gradle wrapper jars may live inside NPDev_General. Runtime/local jars must live in NPDev_General__OutsideRepo." $jarViolations
}

$archiveViolations = @(Get-ChildItem -LiteralPath $WorkspaceRoot -Recurse -Force -File -Include "*.zip", "*.7z", "*.rar" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch "\\.git\\" } |
        ForEach-Object {
            [pscustomobject]@{
                path = Get-RelativePath $_.FullName
                sizeMB = [math]::Round(([decimal]$_.Length) / 1MB, 2)
            }
        })
if ($archiveViolations.Count -gt 0) {
    Add-Violation $violations "archive-inside-workspace" "Workspace archives must be written to NPDev_General__OutsideRepo." $archiveViolations
}

$status = if ($violations.Count -eq 0) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-workspace-cleanliness-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/hygiene/Test-WorkspaceSlimness.ps1"
    workspaceRoot = $WorkspaceRoot
    overallStatus = $status
    status = $status
    policy = [pscustomobject]@{
        maxFileCount = $MaxFileCount
        maxSizeMB = $MaxSizeMB
        maxScriptsFileCount = $MaxScriptsFileCount
        maxScriptsSizeMB = $MaxScriptsSizeMB
        maxReportsOutSizeMB = $MaxReportsOutSizeMB
        cleanTransientReportTemp = $CleanTransientReportTemp
        allowedJarPattern = "*/gradle/wrapper/gradle-wrapper.jar"
        outsideRepoRoot = ((Get-Item -LiteralPath $WorkspaceRoot).Name + "__OutsideRepo")
    }
    summary = [pscustomobject]@{
        fileCount = $allFiles.Count
        sizeMB = $totalSizeMB
        scripts = $scriptsSummary
        reportsOut = $reportsOutSummary
        topLevel = $topLevel
        cleanedTransientDirectories = $cleanedTransientDirectories
    }
    violations = @($violations)
    failures = @($violations | ForEach-Object { $_.message })
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
Write-NPDevJsonFile $ReportPath $report

if ($status -eq "passed") {
    Write-NPDevOk ("Workspace slimness passed: " + $allFiles.Count + " files, " + $totalSizeMB + " MB. Report: " + $ReportPath)
    exit 0
}

Write-NPDevWarn ("Workspace slimness failed with " + $violations.Count + " violation(s). Report: " + $ReportPath)
exit 1
