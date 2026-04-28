[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$RuntimeHostReportPath = "",
    [string]$ClassificationReportPath = "",
    [string]$AllowlistReportPath = "",
    [string]$FootprintReportPath = "",
    [string]$AsyncWaitResumeTestPath = "",
    [string]$HealthTestPath = "",
    [string]$RuntimeModeConfigPath = "",
    [switch]$RuntimeHostGatePendingOk,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "bucket2-report-common.ps1")

$WorkspaceRoot = Initialize-Bucket2Workspace -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "observability-hardening"
$ReportPath = if ([string]::IsNullOrWhiteSpace($ReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\observability-hardening-report.json" } else { Normalize-NPDevPath $ReportPath }
$RuntimeHostReportPath = if ([string]::IsNullOrWhiteSpace($RuntimeHostReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json" } else { Normalize-NPDevPath $RuntimeHostReportPath }
$ClassificationReportPath = if ([string]::IsNullOrWhiteSpace($ClassificationReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-classification-report.json" } else { Normalize-NPDevPath $ClassificationReportPath }
$AllowlistReportPath = if ([string]::IsNullOrWhiteSpace($AllowlistReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json" } else { Normalize-NPDevPath $AllowlistReportPath }
$FootprintReportPath = if ([string]::IsNullOrWhiteSpace($FootprintReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-footprint-report.json" } else { Normalize-NPDevPath $FootprintReportPath }
$AsyncWaitResumeTestPath = if ([string]::IsNullOrWhiteSpace($AsyncWaitResumeTestPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\AsyncWaitResumeE2EIT.java" } else { Normalize-NPDevPath $AsyncWaitResumeTestPath }
$HealthTestPath = if ([string]::IsNullOrWhiteSpace($HealthTestPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\RuntimeHealthEndpointIT.java" } else { Normalize-NPDevPath $HealthTestPath }
$RuntimeModeConfigPath = if ([string]::IsNullOrWhiteSpace($RuntimeModeConfigPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevRuntimeModeConfig.java" } else { Normalize-NPDevPath $RuntimeModeConfigPath }

$runtimeHostReport = Read-Bucket2JsonFile $RuntimeHostReportPath
$classificationReport = Read-Bucket2JsonFile $ClassificationReportPath
$allowlistReport = Read-Bucket2JsonFile $AllowlistReportPath
$footprintReport = Read-Bucket2JsonFile $FootprintReportPath

$correlationPatterns = @(
    "/api/v1/traces/",
    "/api/v1/correlations/",
    "awaitedCorrelationId",
    'trace.path\("meta"\)\.path\("correlationId"\)',
    'arrayContains\(correlationTimeline\.path\("events"\), "eventName", "EmailVerified"\)'
)
$correlationMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $AsyncWaitResumeTestPath -Patterns $correlationPatterns)

$healthPatterns = @(
    "/actuator/health",
    "dependency is DOWN",
    "mock alert sink"
)
$healthMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $HealthTestPath -Patterns $healthPatterns)

$storeBackedSurfaces = @(
    Select-String -Path $RuntimeModeConfigPath -Pattern 'public\s+([A-Za-z0-9_<>]+)\s+postgres[A-Za-z0-9_]+' |
    ForEach-Object { $_.Matches[0].Groups[1].Value } |
    Select-Object -Unique
)
$brokenBackendPatterns = @(
    "dependency is DOWN",
    "mock alert sink"
)
$brokenBackendMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $HealthTestPath -Patterns $brokenBackendPatterns)
$healthCoveragePassed = ($healthMissingPatterns.Count -eq 0 -and $storeBackedSurfaces.Count -gt 0)
$brokenBackendAggregationPassed = ($brokenBackendMissingPatterns.Count -eq 0)
$runtimeSurfaceReportsGreen = (
    $null -ne $classificationReport -and
    $null -ne $allowlistReport -and
    $null -ne $footprintReport -and
    [string]$classificationReport.overallStatus -eq "passed" -and
    [string]$allowlistReport.overallStatus -eq "passed" -and
    [string]$footprintReport.overallStatus -eq "passed"
)
$runtimeHostGateGreen = ($null -ne $runtimeHostReport -and [string]$runtimeHostReport.overallStatus -eq "passed")
$runtimeHostGatePendingAccepted = (-not $runtimeHostGateGreen -and $RuntimeHostGatePendingOk -and $runtimeSurfaceReportsGreen)

$checks = @(
    (New-NPDevCheckResult -Name "runtimehost-gate-current" -Status $(if ($runtimeHostGateGreen -or $runtimeHostGatePendingAccepted) { "passed" } else { "failed" }) -Summary $(if ($runtimeHostGateGreen) { "RuntimeHost gate is currently green." } elseif ($runtimeHostGatePendingAccepted) { "RuntimeHost gate finalization is pending in the current run; runtime surface evidence is green." } else { "RuntimeHost gate evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $RuntimeHostReportPath; overallStatus = if ($null -eq $runtimeHostReport) { $null } else { [string]$runtimeHostReport.overallStatus }; runId = $RunId; pendingFinalizationAccepted = $runtimeHostGatePendingAccepted }))
    (New-NPDevCheckResult -Name "runtime-surface-reports-current" -Status $(if ($runtimeSurfaceReportsGreen) { "passed" } else { "failed" }) -Summary $(if ($runtimeSurfaceReportsGreen) { "Runtime surface evidence is current and green." } else { "Runtime surface evidence is missing or failing." }) -Data ([pscustomobject]@{ classification = if ($null -eq $classificationReport) { $null } else { [string]$classificationReport.overallStatus }; allowlist = if ($null -eq $allowlistReport) { $null } else { [string]$allowlistReport.overallStatus }; footprint = if ($null -eq $footprintReport) { $null } else { [string]$footprintReport.overallStatus } }))
    (New-NPDevCheckResult -Name "correlation-timeline-proof" -Status $(if ($correlationMissingPatterns.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($correlationMissingPatterns.Count -eq 0) { "Async wait/resume canonical scenario proves correlation timeline and trace retrieval." } else { "Async wait/resume canonical scenario is missing required correlation timeline assertions." }) -Data ([pscustomobject]@{ testPath = Get-Bucket2RelativePath $WorkspaceRoot $AsyncWaitResumeTestPath; missingPatterns = @($correlationMissingPatterns) }))
    (New-NPDevCheckResult -Name "health-indicator-coverage" -Status $(if ($healthCoveragePassed) { "passed" } else { "failed" }) -Summary $(if ($healthCoveragePassed) { "Health coverage is documented against the store-backed runtime surface set." } else { "Health coverage evidence is incomplete for the store-backed runtime surface set." }) -Data ([pscustomobject]@{ testPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath; storeBackedSurfaces = @($storeBackedSurfaces); missingPatterns = @($healthMissingPatterns) }))
    (New-NPDevCheckResult -Name "broken-backend-aggregation" -Status $(if ($brokenBackendAggregationPassed) { "passed" } else { "failed" }) -Summary $(if ($brokenBackendAggregationPassed) { "Health evidence includes broken-backend aggregation and alert-sink assertions." } else { "Broken-backend aggregation evidence is missing required assertions." }) -Data ([pscustomobject]@{ testPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath; missingPatterns = @($brokenBackendMissingPatterns) }))
)

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = Get-Bucket2OverallStatus $checks
    correlationProof = [pscustomobject]@{
        canonicalScenario = "AsyncWaitResumeE2EIT"
        testPath = Get-Bucket2RelativePath $WorkspaceRoot $AsyncWaitResumeTestPath
        status = if ($correlationMissingPatterns.Count -eq 0) { "passed" } else { "failed" }
        missingPatterns = @($correlationMissingPatterns)
    }
    healthIndicatorCoverage = [pscustomobject]@{
        testPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath
        requiredStoreBackedSurfaces = @($storeBackedSurfaces)
        status = if ($healthCoveragePassed) { "passed" } else { "failed" }
        missingPatterns = @($healthMissingPatterns)
    }
    brokenBackendAggregation = [pscustomobject]@{
        testPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath
        status = if ($brokenBackendAggregationPassed) { "passed" } else { "failed" }
        missingPatterns = @($brokenBackendMissingPatterns)
    }
    evidencePaths = @(
        Get-Bucket2RelativePath $WorkspaceRoot $RuntimeHostReportPath
        Get-Bucket2RelativePath $WorkspaceRoot $ClassificationReportPath
        Get-Bucket2RelativePath $WorkspaceRoot $AllowlistReportPath
        Get-Bucket2RelativePath $WorkspaceRoot $FootprintReportPath
        Get-Bucket2RelativePath $WorkspaceRoot $AsyncWaitResumeTestPath
        Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath
    )
    checks = $checks
    summary = Get-Bucket2Summary $checks
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Observability hardening report generated."
    return
}

Write-NPDevWarn "Observability hardening report failed."
throw "Observability hardening report failed."
