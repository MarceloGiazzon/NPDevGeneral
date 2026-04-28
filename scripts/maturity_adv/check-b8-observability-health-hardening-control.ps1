[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$ObservabilityHardeningReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b8-observability-health-hardening-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b8-observability-health-hardening-report.json"
$ObservabilityHardeningReportPath = if ([string]::IsNullOrWhiteSpace($ObservabilityHardeningReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\observability-hardening-report.json"
}
else {
    Normalize-NPDevPath $ObservabilityHardeningReportPath
}

$schema = Test-MaturityReportSchema -PathValue $ObservabilityHardeningReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "correlationProof",
    "healthIndicatorCoverage",
    "brokenBackendAggregation",
    "evidencePaths",
    "checks",
    "summary"
)
$reportDoc = if ($schema.exists -and [string]::IsNullOrWhiteSpace([string]$schema.parseError)) { Read-MaturityJsonFile $ObservabilityHardeningReportPath } else { $null }
$correlationProof = if ($null -eq $reportDoc) { $null } else { $reportDoc.correlationProof }
$healthIndicatorCoverage = if ($null -eq $reportDoc) { $null } else { $reportDoc.healthIndicatorCoverage }
$brokenBackendAggregation = if ($null -eq $reportDoc) { $null } else { $reportDoc.brokenBackendAggregation }

$checks = @(
    (New-MaturityCheck -Name "observability-hardening-report" -Status $(if ($schema.valid) { "passed" } else { "failed" }) -Expectation "The observability hardening report must exist and expose the exact Bucket 2 fields." -Summary $(if ($schema.valid) { "The observability hardening report is readable and exposes the expected fields." } else { "The observability hardening report is missing or does not expose the expected fields." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ObservabilityHardeningReportPath; missingProperties = $schema.missingProperties; parseError = $schema.parseError })
    (New-MaturityCheck -Name "observability-hardening-current" -Status $(if ($null -ne $reportDoc -and [string]$reportDoc.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The official observability hardening report must currently pass." -Summary $(if ($null -ne $reportDoc -and [string]$reportDoc.overallStatus -eq "passed") { "The observability hardening report is green." } else { "The observability hardening report is missing or failing." }) -Data @{ overallStatus = if ($null -eq $reportDoc) { $null } else { [string]$reportDoc.overallStatus } })
    (New-MaturityCheck -Name "correlation-proof" -Status $(if ($null -ne $correlationProof -and [string]$correlationProof.status -eq "passed") { "passed" } else { "failed" }) -Expectation "The canonical async wait/resume scenario must prove correlation timeline and trace retrieval." -Summary $(if ($null -ne $correlationProof -and [string]$correlationProof.status -eq "passed") { "The canonical correlation proof passed." } else { "The canonical correlation proof is missing or failing." }) -Data @{ proof = $correlationProof })
    (New-MaturityCheck -Name "health-indicator-coverage" -Status $(if ($null -ne $healthIndicatorCoverage -and [string]$healthIndicatorCoverage.status -eq "passed" -and @($healthIndicatorCoverage.requiredStoreBackedSurfaces).Count -gt 0) { "passed" } else { "failed" }) -Expectation "Health coverage must be derived from the store-backed runtime surface set." -Summary $(if ($null -ne $healthIndicatorCoverage -and [string]$healthIndicatorCoverage.status -eq "passed" -and @($healthIndicatorCoverage.requiredStoreBackedSurfaces).Count -gt 0) { "Health coverage matches the store-backed runtime surface set." } else { "Health coverage does not match the store-backed runtime surface set." }) -Data @{ proof = $healthIndicatorCoverage })
    (New-MaturityCheck -Name "broken-backend-aggregation" -Status $(if ($null -ne $brokenBackendAggregation -and [string]$brokenBackendAggregation.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Broken-backend aggregation must be explicitly covered by the health hardening report." -Summary $(if ($null -ne $brokenBackendAggregation -and [string]$brokenBackendAggregation.status -eq "passed") { "Broken-backend aggregation coverage is present." } else { "Broken-backend aggregation coverage is missing or failing." }) -Data @{ proof = $brokenBackendAggregation })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B2" `
    -ControlId "B8-OBSERVABILITY-HEALTH-HARDENING" `
    -ReportPath $ReportPath `
    -EvidencePaths @(Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ObservabilityHardeningReportPath) `
    -Checks $checks `
    -Extra @{ observabilityHardeningReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ObservabilityHardeningReportPath }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
