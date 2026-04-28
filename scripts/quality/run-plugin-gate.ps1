[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$SampleId = "",
    [switch]$GenerateIfMissing,
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}
$RunId = Resolve-NPDevRunId $RunId "plugin-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\plugin-gate-report.json"
}

$generateScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\samples\generate-sample.ps1"
$generationMarkerPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $SampleId + "\Output\Reports\generation-run.json")
$artifactResourceRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot ("NPDevSamples\" + $SampleId + "\Output\ArtifactNP\src\main\resources\npdev")

if ($GenerateIfMissing) {
    Ensure-NPDevFile $generateScript "Sample generation wrapper"
    & $generateScript -WorkspaceRoot $WorkspaceRoot -SampleIds @($SampleId) -RunId $RunId
}
elseif (-not (Test-Path -LiteralPath $artifactResourceRoot -PathType Container)) {
    throw ("Generated plugin resources are missing. Rerun with -GenerateIfMissing or generate " + $SampleId + " first.")
}

$generationMarker = $null
$generationMarkerError = $null
try {
    Ensure-NPDevFile $generationMarkerPath "Sample generation marker"
    $generationMarker = Get-Content -LiteralPath $generationMarkerPath -Raw | ConvertFrom-Json
}
catch {
    $generationMarkerError = $_.Exception.Message
}

$requiredFiles = @(
    "plugins\default.plugin-manifest.json",
    "plugins\warning.plugin-manifest.json",
    "plugin-packages\index.json",
    "plugin-packages\notification-inproc.package.json",
    "plugin-packages\custom-procedure.package.json"
)

$results = @()
$markerMatchesRun = $false
if ($null -ne $generationMarker) {
    $markerMatchesRun = [string]$generationMarker.runId -eq $RunId
}
$results += [pscustomobject]@{
    path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $generationMarkerPath
    status = if ($markerMatchesRun) { "passed" } else { "failed" }
    error = if ($markerMatchesRun) { $null } elseif ($null -ne $generationMarkerError) { $generationMarkerError } else { "Generated artifacts were not produced for current runId." }
}
foreach ($relativeFile in $requiredFiles) {
    $path = Join-Path $artifactResourceRoot $relativeFile
    try {
        Ensure-NPDevFile $path ("Plugin artifact " + $relativeFile)
        Get-Content -LiteralPath $path -Raw | ConvertFrom-Json | Out-Null
        $results += [pscustomobject]@{
            path = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $path)
            status = "passed"
            error = $null
        }
    }
    catch {
        $results += [pscustomobject]@{
            path = (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $path)
            status = "failed"
            error = $_.Exception.Message
        }
    }
}

$failed = @($results | Where-Object { $_.status -eq "failed" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    sampleId = $SampleId
    generation = [pscustomobject]@{
        markerPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $generationMarkerPath
        markerRunId = if ($null -eq $generationMarker) { $null } else { [string]$generationMarker.runId }
        runIdMatches = $markerMatchesRun
        generatedDuringThisGate = [bool]$GenerateIfMissing
    }
    overallStatus = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    results = $results
}
Write-NPDevJsonFile $ReportPath $report

if ($failed.Count -eq 0) {
    Write-NPDevOk "Plugin gate passed."
    return
}

Write-NPDevWarn "Plugin gate failed."
throw "Plugin gate failed."
