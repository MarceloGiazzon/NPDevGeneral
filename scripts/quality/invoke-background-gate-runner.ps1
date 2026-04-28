[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [ValidateSet("beta-release", "runtimehost", "sample-matrix", "frontend", "frontend-audit", "editor", "hygiene", "ai-beta-matrix")]
    [string]$Gate,
    [string]$MetadataPath = "",
    [string]$SampleId = "",
    [string]$SourceCommitSha = "",
    [string]$SourceBranch = "",
    [AllowNull()][object]$SourceDirty = $null,
    [string]$SourceProvider = "",
    [string]$SourceRunId = "",
    [string]$SourceRunAttempt = "",
    [string]$SourceWorkflow = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "background-gate-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
if ([string]::IsNullOrWhiteSpace($SampleId)) {
    $SampleId = Get-NPDevDefaultSampleId $WorkspaceRoot
}

if ([string]::IsNullOrWhiteSpace($MetadataPath)) {
    throw "MetadataPath is required."
}

$definition = Resolve-NPDevBackgroundGateDefinition `
    -WorkspaceRoot $WorkspaceRoot `
    -Gate $Gate `
    -SampleId $SampleId `
    -SourceCommitSha $SourceCommitSha `
    -SourceBranch $SourceBranch `
    -SourceDirty $SourceDirty `
    -SourceProvider $SourceProvider `
    -SourceRunId $SourceRunId `
    -SourceRunAttempt $SourceRunAttempt `
    -SourceWorkflow $SourceWorkflow

$startedAt = Get-Date
Write-NPDevBackgroundGateMetadata -MetadataPath $MetadataPath -Updates @{
    runnerStartedAt = $startedAt.ToString("o")
    status = "running"
    scriptPath = $definition.scriptPathRelative
    expectedReportPath = $definition.expectedReportPathRelative
} | Out-Null

$exitCode = 0
$errorMessage = $null

try {
    $invocationParameters = @{}
    foreach ($property in $definition.parameters.PSObject.Properties) {
        $invocationParameters[$property.Name] = $property.Value
    }

    & $definition.scriptPath @invocationParameters
}
catch {
    $exitCode = 1
    $errorMessage = $_.Exception.Message
}

$endedAt = Get-Date
$reportSnapshot = Get-NPDevBackgroundGateReportSnapshot $definition.expectedReportPath
$finalStatus = if ($exitCode -eq 0) { "passed" } else { "failed" }

Write-NPDevBackgroundGateMetadata -MetadataPath $MetadataPath -Updates @{
    status = $finalStatus
    completedAt = $endedAt.ToString("o")
    exitCode = $exitCode
    error = $errorMessage
    expectedReportExists = [bool]$reportSnapshot.exists
    expectedReportOverallStatus = $reportSnapshot.overallStatus
    expectedReportGeneratedAt = $reportSnapshot.generatedAt
    expectedReportRunId = $reportSnapshot.runId
    expectedReportParseError = $reportSnapshot.parseError
} | Out-Null

exit $exitCode
