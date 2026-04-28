[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$SampleIds = @(),
    [switch]$BuildCachesOnly,
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-clean-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Assert-PathInsideRoot([string]$RootPath, [string]$TargetPath) {
    $root = Normalize-NPDevPath $RootPath
    $target = Normalize-NPDevPath $TargetPath
    if (-not $root.EndsWith("\")) {
        $root += "\"
    }

    if (-not $target.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ("Refusing to operate outside " + $root + ": " + $target)
    }
}

$entries = Get-NPDevSampleEntries $WorkspaceRoot
if ($SampleIds.Count -eq 0) {
    $SampleIds = @($entries | Select-Object -ExpandProperty id)
}

$samplesRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples"
$results = @()

foreach ($sampleId in $SampleIds) {
    $sampleRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $sampleId)
    $outputRoot = Join-Path $sampleRoot "Output"
    Assert-PathInsideRoot $samplesRoot $outputRoot

    if ($BuildCachesOnly) {
        New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
        $removed = @()
        foreach ($relativeCache in @("App\.gradle", "App\build", "App\node_modules")) {
            $cachePath = Join-Path $outputRoot $relativeCache
            Assert-PathInsideRoot $outputRoot $cachePath
            if (Test-Path -LiteralPath $cachePath) {
                Remove-Item -LiteralPath $cachePath -Recurse -Force
                $removed += (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $cachePath)
            }
        }

        $retainedEvidencePaths = @()
        foreach ($relativeEvidencePath in @(
                "Reports\generation-run.json",
                "App\PROJECT_DIGEST.md",
                "App\MIGRATION_DIGEST.md",
                "App\gradlew.bat",
                "App\settings.gradle",
                "App\build.gradle",
                "App\src\main\resources\db\migration"
            )) {
            $evidencePath = Join-Path $outputRoot $relativeEvidencePath
            Assert-PathInsideRoot $outputRoot $evidencePath
            if (Test-Path -LiteralPath $evidencePath) {
                $retainedEvidencePaths += (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $evidencePath)
            }
        }

        $results += [pscustomobject]@{
            sampleId = $sampleId
            mode = "build-caches-only"
            outputRoot = $outputRoot
            removed = $removed
            removedPaths = $removed
            retainedEvidencePaths = @($retainedEvidencePaths | Select-Object -Unique)
            cleanupPolicy = [pscustomobject]@{
                removedPathPatterns = @("App\.gradle", "App\build", "App\node_modules")
                retainedEvidencePatterns = @(
                    "Output\Reports\generation-run.json",
                    "Output\App\PROJECT_DIGEST.md",
                    "Output\App\MIGRATION_DIGEST.md",
                    "Output\App\gradlew.bat",
                    "Output\App\settings.gradle",
                    "Output\App\build.gradle",
                    "Output\App\src\main\resources\db\migration"
                )
            }
        }
        continue
    }

    if ((Split-Path -Leaf $outputRoot) -ne "Output") {
        throw ("Refusing to clean a non-Output folder: " + $outputRoot)
    }

    $removedOutput = Test-Path -LiteralPath $outputRoot
    if ($removedOutput) {
        Remove-Item -LiteralPath $outputRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $outputRoot "Reports") | Out-Null

    $results += [pscustomObject]@{
        sampleId = $sampleId
        mode = "full-output"
        outputRoot = $outputRoot
        removedOutput = $removedOutput
    }
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    workspaceRoot = $WorkspaceRoot
    overallStatus = "passed"
    buildCachesOnly = [bool]$BuildCachesOnly
    results = $results
}
Write-NPDevJsonFile $ReportPath $report
Write-NPDevOk ("Sample output cleanup completed for " + ($SampleIds -join ", ") + ".")
