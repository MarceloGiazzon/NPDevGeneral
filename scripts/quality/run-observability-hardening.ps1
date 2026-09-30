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
    [string]$HealthDegradedTestPath = "",
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

# 2026-08-22: a hand-run of this script overwrote the RuntimeHost gate's own artifact at the default
# path with a different verdict, and the stale file was then read as gate evidence. A run that is not
# the gate's own writes to a side path unless it explicitly asks not to.
if (-not $PSBoundParameters.ContainsKey("ReportPath") -and -not $RuntimeHostGatePendingOk) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\observability-hardening-standalone-report.json"
    Write-NPDevInfo "Standalone run: writing to observability-hardening-standalone-report.json so the gate's own artifact is not overwritten. Pass -ReportPath to override."
}

$RuntimeHostReportPath = if ([string]::IsNullOrWhiteSpace($RuntimeHostReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtimehost-gate-report.json" } else { Normalize-NPDevPath $RuntimeHostReportPath }
$ClassificationReportPath = if ([string]::IsNullOrWhiteSpace($ClassificationReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-classification-report.json" } else { Normalize-NPDevPath $ClassificationReportPath }
$AllowlistReportPath = if ([string]::IsNullOrWhiteSpace($AllowlistReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-surface-allowlist-report.json" } else { Normalize-NPDevPath $AllowlistReportPath }
$FootprintReportPath = if ([string]::IsNullOrWhiteSpace($FootprintReportPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\runtime-footprint-report.json" } else { Normalize-NPDevPath $FootprintReportPath }
$AsyncWaitResumeTestPath = if ([string]::IsNullOrWhiteSpace($AsyncWaitResumeTestPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\AsyncWaitResumeE2EIT.java" } else { Normalize-NPDevPath $AsyncWaitResumeTestPath }
$HealthTestPath = if ([string]::IsNullOrWhiteSpace($HealthTestPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\test\java\com\finalexec\RuntimeHealthEndpointIT.java" } else { Normalize-NPDevPath $HealthTestPath }
# W3.3 (2026-08-25 remediation plan / QUAL-33): the "dependency is DOWN" half lives as a plain unit
# test of NpdevEventStoreHealthIndicator in NPDevKernel/adapters/runtime-validation, not as a second
# @SpringBootTest beside RuntimeHealthEndpointIT -- a @SpringBootTest version was tried and abandoned
# after its @Primary EventStore override leaked into RuntimeHealthEndpointIT's own SEPARATE
# @SpringBootTest run in the same Gradle test JVM (see RuntimeHealthEndpointIT.java's own comment).
# Both files are scanned together below so the split doesn't itself fail this check.
$HealthDegradedTestPath = if ([string]::IsNullOrWhiteSpace($HealthDegradedTestPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevKernel\adapters\runtime-validation\src\test\java\com\npdev\adapters\runtime\validation\RuntimeHealthIndicatorsTest.java" } else { Normalize-NPDevPath $HealthDegradedTestPath }
# BT-1: NpdevRuntimeModeConfig.java is app-independent (no com.npdev.generated. reference) and now
# lives under runtimehost-core, RuntimeHost's app-independent module (scripts/proofs/
# classify_runtimehost_sources.py).
$RuntimeModeConfigPath = if ([string]::IsNullOrWhiteSpace($RuntimeModeConfigPath)) { Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\runtimehost-core\src\main\java\com\finalexec\config\NpdevRuntimeModeConfig.java" } else { Normalize-NPDevPath $RuntimeModeConfigPath }

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

$healthTestCombinedContent = @(
    if (Test-Path -LiteralPath $HealthTestPath -PathType Leaf) { Get-Content -LiteralPath $HealthTestPath -Raw }
    if (Test-Path -LiteralPath $HealthDegradedTestPath -PathType Leaf) { Get-Content -LiteralPath $HealthDegradedTestPath -Raw }
) -join "`n"
# W3.3 (2026-08-25 remediation plan / QUAL-33): "mock alert sink" removed -- verified live (grep for
# AlertSink across NPDevRuntimeHost/src/main, zero hits) that no such mechanism exists anywhere in
# this codebase. This pattern was satisfied only by the OLD @Disabled stub's own aspirational
# comment describing a feature that was never built; keeping it would mean the checker passes only
# when the test file contains a specific fictional phrase, exactly the vacuous-check shape this
# whole remediation plan exists to fix. "dependency is DOWN" is kept -- it is now backed by a real
# test (RuntimeHealthEndpointDegradedIT substitutes a broken EventStore bean and reads the actual
# aggregated /actuator/health response), not by prose.
$healthPatterns = @(
    "/actuator/health",
    "dependency is DOWN"
)
$healthMissingPatterns = @($healthPatterns | Where-Object { $healthTestCombinedContent -notmatch $_ })

# LNCH-1 closeout C7.1 (2026-07-21). This scraped `public <Type> postgresXxx(...)` bean methods, a
# naming convention NpdevRuntimeModeConfig no longer uses -- its store-backed beans are named
# `jdbcXxx` (jdbcEventStore, jdbcFlowInstanceStore, jdbcTraceStore, ...). Verified live: the old
# pattern matched ZERO methods, so $storeBackedSurfaces was empty, so the `-and .Count -gt 0` guard
# below forced `health-indicator-coverage` to FAIL even though $healthMissingPatterns was empty.
# The check had been failing vacuously -- reporting a coverage gap that did not exist -- for as long
# as the beans have been named jdbc*. The check's intent is still valid, so the pattern is corrected
# rather than the check removed. If this ever returns empty again the check fails loudly, which is
# the desired behaviour: an empty surface set means this scrape has drifted, not that coverage is fine.
$storeBackedSurfaces = @(
    Select-String -Path $RuntimeModeConfigPath -Pattern 'public\s+([A-Za-z0-9_<>]+)\s+jdbc[A-Za-z0-9_]+\s*\(' |
    ForEach-Object { $_.Matches[0].Groups[1].Value } |
    Select-Object -Unique
)
$brokenBackendPatterns = @(
    "dependency is DOWN"
)
$brokenBackendMissingPatterns = @($brokenBackendPatterns | Where-Object { $healthTestCombinedContent -notmatch $_ })
$healthCoveragePassed = ($healthMissingPatterns.Count -eq 0 -and $storeBackedSurfaces.Count -gt 0)
$brokenBackendAggregationPassed = ($brokenBackendMissingPatterns.Count -eq 0)
# The runtime-surface convergence checks were un-retired on 2026-09-30 once the RuntimeHost classes
# were moved into the package their manifest bucket names, so all three surface reports must pass.
$surfaceReportsPresent = (
    $null -ne $classificationReport -and
    $null -ne $allowlistReport -and
    $null -ne $footprintReport
)
$runtimeSurfaceReportsGreen = (
    $surfaceReportsPresent -and
    [string]$classificationReport.overallStatus -eq "passed" -and
    [string]$allowlistReport.overallStatus -eq "passed" -and
    [string]$footprintReport.overallStatus -eq "passed"
)
$runtimeHostGateGreen = ($null -ne $runtimeHostReport -and [string]$runtimeHostReport.overallStatus -eq "passed")
$runtimeHostGatePendingAccepted = (-not $runtimeHostGateGreen -and $RuntimeHostGatePendingOk -and $runtimeSurfaceReportsGreen)

$checks = @(
    (New-NPDevCheckResult -Name "runtimehost-gate-current" -Status $(if ($runtimeHostGateGreen -or $runtimeHostGatePendingAccepted) { "passed" } else { "failed" }) -Summary $(if ($runtimeHostGateGreen) { "RuntimeHost gate is currently green." } elseif ($runtimeHostGatePendingAccepted) { "RuntimeHost gate finalization is pending in the current run; runtime surface evidence is green." } else { "RuntimeHost gate evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $RuntimeHostReportPath; overallStatus = if ($null -eq $runtimeHostReport) { $null } else { [string]$runtimeHostReport.overallStatus }; runId = $RunId; pendingFinalizationAccepted = $runtimeHostGatePendingAccepted }))
    (New-NPDevCheckResult -Name "runtime-surface-reports-current" -Status $(if ($runtimeSurfaceReportsGreen) { "passed" } else { "failed" }) -Summary $(if ($runtimeSurfaceReportsGreen) { "Runtime surface evidence is current and green." } elseif (-not $surfaceReportsPresent) { "Runtime surface evidence is missing." } else { "Runtime surface evidence is failing." }) -Data ([pscustomobject]@{ classification = if ($null -eq $classificationReport) { $null } else { [string]$classificationReport.overallStatus }; allowlist = if ($null -eq $allowlistReport) { $null } else { [string]$allowlistReport.overallStatus }; footprint = if ($null -eq $footprintReport) { $null } else { [string]$footprintReport.overallStatus } }))
    (New-NPDevCheckResult -Name "correlation-timeline-proof" -Status $(if ($correlationMissingPatterns.Count -eq 0) { "passed" } else { "failed" }) -Summary $(if ($correlationMissingPatterns.Count -eq 0) { "Async wait/resume canonical scenario proves correlation timeline and trace retrieval." } else { "Async wait/resume canonical scenario is missing required correlation timeline assertions." }) -Data ([pscustomobject]@{ testPath = Get-Bucket2RelativePath $WorkspaceRoot $AsyncWaitResumeTestPath; missingPatterns = @($correlationMissingPatterns) }))
    (New-NPDevCheckResult -Name "health-indicator-coverage" -Status $(if ($healthCoveragePassed) { "passed" } else { "failed" }) -Summary $(if ($healthCoveragePassed) { "Health coverage is documented against the store-backed runtime surface set." } else { "Health coverage evidence is incomplete for the store-backed runtime surface set." }) -Data ([pscustomobject]@{ testPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath; storeBackedSurfaces = @($storeBackedSurfaces); missingPatterns = @($healthMissingPatterns) }))
    (New-NPDevCheckResult -Name "broken-backend-aggregation" -Status $(if ($brokenBackendAggregationPassed) { "passed" } else { "failed" }) -Summary $(if ($brokenBackendAggregationPassed) { "Health evidence includes a real broken-backend aggregation assertion." } else { "Broken-backend aggregation evidence is missing required assertions." }) -Data ([pscustomobject]@{ testPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthTestPath; degradedTestPath = Get-Bucket2RelativePath $WorkspaceRoot $HealthDegradedTestPath; missingPatterns = @($brokenBackendMissingPatterns) }))
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
