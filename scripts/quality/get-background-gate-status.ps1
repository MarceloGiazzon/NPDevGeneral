[CmdletBinding(DefaultParameterSetName = "ByMetadataPath")]
param(
    [Parameter(ParameterSetName = "ByMetadataPath")]
    [string]$MetadataPath = "",
    [Parameter(ParameterSetName = "Latest")]
    [switch]$Latest,
    [string]$WorkspaceRoot = "",
    [ValidateSet("beta-release", "runtimehost", "sample-matrix", "frontend", "frontend-audit", "editor", "hygiene", "ai-beta-matrix")]
    [string]$Gate = "",
    [int]$TailLineCount = 25
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "background-gate-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

if ($PSCmdlet.ParameterSetName -eq "Latest") {
    $metadataRoot = Get-NPDevBackgroundGateMetadataRoot $WorkspaceRoot
    $candidates = @(Get-ChildItem -LiteralPath $metadataRoot -Filter "*.json" | Sort-Object LastWriteTime -Descending)
    if (-not [string]::IsNullOrWhiteSpace($Gate)) {
        $candidates = @($candidates | Where-Object { $_.BaseName -like ($Gate + "-background-*") })
    }
    if ($candidates.Count -eq 0) {
        throw "No background gate metadata files were found."
    }
    $MetadataPath = $candidates[0].FullName
}

$metadata = Read-NPDevBackgroundGateMetadata $MetadataPath

function Get-BackgroundMetadataValue(
    [object]$MetadataObject,
    [string]$Name
) {
    if ($null -eq $MetadataObject) {
        return $null
    }

    if ($MetadataObject.PSObject.Properties.Name -contains $Name) {
        return $MetadataObject.$Name
    }

    return $null
}

function Convert-BackgroundStatusDate([object]$Value) {
    if ($null -eq $Value) {
        return $null
    }

    $text = ([string]$Value).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $null
    }

    try {
        return [datetimeoffset]::Parse($text, [Globalization.CultureInfo]::InvariantCulture)
    }
    catch {
        return $null
    }
}

$process = $null
$processAlive = $false
if ($metadata.PSObject.Properties.Name -contains "processId" -and $null -ne $metadata.processId) {
    try {
        $process = Get-Process -Id ([int]$metadata.processId) -ErrorAction Stop
        $processAlive = $true
    }
    catch {
        $process = $null
        $processAlive = $false
    }
}

$expectedReportPath = if ([string]::IsNullOrWhiteSpace([string](Get-BackgroundMetadataValue $metadata "expectedReportPath"))) {
    $null
}
else {
    Resolve-NPDevWorkspacePath $WorkspaceRoot ([string](Get-BackgroundMetadataValue $metadata "expectedReportPath"))
}
$reportSnapshot = Get-NPDevBackgroundGateReportSnapshot $expectedReportPath
$launchedAtDate = Convert-BackgroundStatusDate (Get-BackgroundMetadataValue $metadata "launchedAt")
$reportGeneratedAtDate = Convert-BackgroundStatusDate $reportSnapshot.generatedAt
$expectedReportFreshForJob = $false
if ($null -ne $launchedAtDate -and $null -ne $reportGeneratedAtDate) {
    $expectedReportFreshForJob = $reportGeneratedAtDate -ge $launchedAtDate
}

$stdoutLogPath = if ([string]::IsNullOrWhiteSpace([string](Get-BackgroundMetadataValue $metadata "stdoutLogPath"))) {
    $null
}
else {
    Resolve-NPDevWorkspacePath $WorkspaceRoot ([string](Get-BackgroundMetadataValue $metadata "stdoutLogPath"))
}
$stderrLogPath = if ([string]::IsNullOrWhiteSpace([string](Get-BackgroundMetadataValue $metadata "stderrLogPath"))) {
    $null
}
else {
    Resolve-NPDevWorkspacePath $WorkspaceRoot ([string](Get-BackgroundMetadataValue $metadata "stderrLogPath"))
}

$resolvedStatus = if ([string](Get-BackgroundMetadataValue $metadata "status") -in @("passed", "failed", "stopped")) {
    [string](Get-BackgroundMetadataValue $metadata "status")
}
elseif ($processAlive) {
    "running"
}
else {
    "ended-unknown"
}

$statusObject = [pscustomobject]@{
    jobId = [string](Get-BackgroundMetadataValue $metadata "jobId")
    gate = [string](Get-BackgroundMetadataValue $metadata "gate")
    status = $resolvedStatus
    processAlive = $processAlive
    processId = if ($processAlive) { $process.Id } else { Get-BackgroundMetadataValue $metadata "processId" }
    createdAt = [string](Get-BackgroundMetadataValue $metadata "createdAt")
    launchedAt = [string](Get-BackgroundMetadataValue $metadata "launchedAt")
    runnerStartedAt = [string](Get-BackgroundMetadataValue $metadata "runnerStartedAt")
    completedAt = [string](Get-BackgroundMetadataValue $metadata "completedAt")
    exitCode = Get-BackgroundMetadataValue $metadata "exitCode"
    error = [string](Get-BackgroundMetadataValue $metadata "error")
    metadataPath = $MetadataPath
    stdoutLogPath = $stdoutLogPath
    stderrLogPath = $stderrLogPath
    expectedReportPath = $expectedReportPath
    expectedReportExists = [bool]$reportSnapshot.exists
    expectedReportOverallStatus = $reportSnapshot.overallStatus
    expectedReportGeneratedAt = $reportSnapshot.generatedAt
    expectedReportRunId = $reportSnapshot.runId
    expectedReportParseError = $reportSnapshot.parseError
    expectedReportFreshForJob = $expectedReportFreshForJob
    stdoutTail = @(Get-NPDevBackgroundGateLogTail -PathValue $stdoutLogPath -LineCount $TailLineCount)
    stderrTail = @(Get-NPDevBackgroundGateLogTail -PathValue $stderrLogPath -LineCount $TailLineCount)
}

Write-NPDevInfo ("Background gate '" + $statusObject.gate + "' is " + $statusObject.status + ".")
if ($statusObject.processAlive) {
    Write-NPDevInfo ("PID: " + $statusObject.processId)
}
if (-not [string]::IsNullOrWhiteSpace([string]$statusObject.expectedReportPath)) {
    Write-NPDevInfo ("Report: " + $statusObject.expectedReportPath)
    if ($statusObject.expectedReportExists) {
        $freshnessLabel = if ($statusObject.expectedReportFreshForJob) { "current-run" } else { "stale-or-pre-run" }
        Write-NPDevInfo ("Report status: " + $statusObject.expectedReportOverallStatus + " (" + $freshnessLabel + ")")
    }
}
if (-not [string]::IsNullOrWhiteSpace([string]$statusObject.error)) {
    Write-NPDevWarn ("Error: " + $statusObject.error)
}

return $statusObject
