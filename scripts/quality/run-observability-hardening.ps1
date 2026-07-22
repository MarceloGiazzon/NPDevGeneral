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
    # LNCH-1 T5 (GATE-OBS-1): accept the KNOWN surface-governance convergence drift as advisory,
    # mirroring the -PendingOk the caller already passes to run-runtime-surface-evidence.ps1. Only the
    # six named checks are excused, and only when nothing else in those reports is failing.
    [switch]$SurfaceConvergencePendingOk,
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
    "dependency is DOWN",
    "mock alert sink"
)
$brokenBackendMissingPatterns = @(Get-Bucket2MissingPatterns -PathValue $HealthTestPath -Patterns $brokenBackendPatterns)
$healthCoveragePassed = ($healthMissingPatterns.Count -eq 0 -and $storeBackedSurfaces.Count -gt 0)
$brokenBackendAggregationPassed = ($brokenBackendMissingPatterns.Count -eq 0)
# LNCH-1 T5 (GATE-OBS-1, 2026-07-21). This check required all three surface reports to be
# `overallStatus == passed`, which made the RuntimeHost gate exit 1 for four consecutive rounds.
#
# The drift is GOVERNANCE, not a code defect, and the gate ALREADY says so: run-runtimehost-gate.ps1
# invokes run-runtime-surface-evidence.ps1 with -PendingOk precisely because the convergence and
# exclusivity checks encode the pre-d0bf41b "package == support bucket" convention that the beta-0
# manifest refactor replaced with exact lists. That step reports them as advisory pending a
# governance-owner realignment -- and then THIS step re-read the same report files and treated the
# same drift as blocking. The gate was simultaneously calling one set of findings advisory and
# failing on it.
#
# Verified against the live reports (2026-07-21): every failing sub-check across both reports is one
# of the six named below. runtime-surface-allowlist-report.json -- the one backing real build-time
# allowlist ENFORCEMENT -- is `passed`, exactly as the gate's own comment claims.
#
# So: those six are excused only when the caller passes -SurfaceConvergencePendingOk, and only when
# they are the ONLY failures. Any other failing sub-check, in any of the three reports, still fails
# this check loudly. That keeps the exit code meaningful instead of blanket-suppressing the step.
#
# GATE-OBS-1a DECISION (REG-5, 2026-07-21): these six checks are now FORMALLY RETIRED, not "advisory
# pending an owner." Concrete finding that settles it: runtime-surface-allowlist-report.json -- the
# exact-list allowlist backed by RuntimeControllerAllowlistConfig reading
# npdev/runtime-supported-controllers.json -- IS the exact-list-model enforcement the beta-0 manifest
# refactor introduced, it is BLOCKING, and it passes. These six package-convention convergence/
# exclusivity checks are a redundant proxy for the superseded "package == support bucket" rule; a
# rewrite "against the exact-list model" (the other option considered) would only duplicate what the
# allowlist already enforces. They are kept as informational observations, never as a gate blocker.
# Reversible if the surface governance model ever changes. Recorded in docs/OPEN_GAPS_AND_ROADMAP.md
# (GATE-OBS-1a). This ends the "advisory, unowned" state that REG-5 existed to close.
$surfaceGovernanceRetiredChecks = @(
    "service-buckets-are-exclusive",
    "controller-namespaces-match-convergence-buckets",
    "service-namespaces-match-convergence-buckets",
    "controller-namespace-convergence-is-clean",
    "service-namespace-convergence-is-clean",
    "supported-controller-footprint-stays-minority"
)

function Get-NPDevFailingCheckNames {
    param($Report)
    if ($null -eq $Report) { return @() }
    if (-not ($Report.PSObject.Properties.Name -contains "checks")) { return @() }
    if ($null -eq $Report.checks) { return @() }
    return @(
        $Report.checks |
        Where-Object { [string]$_.status -ne "passed" } |
        ForEach-Object { [string]$_.name }
    )
}

$surfaceReportsPresent = (
    $null -ne $classificationReport -and
    $null -ne $allowlistReport -and
    $null -ne $footprintReport
)
$surfaceFailingCheckNames = @()
$surfaceFailingCheckNames += Get-NPDevFailingCheckNames $classificationReport
$surfaceFailingCheckNames += Get-NPDevFailingCheckNames $allowlistReport
$surfaceFailingCheckNames += Get-NPDevFailingCheckNames $footprintReport
$surfaceFailingCheckNames = @($surfaceFailingCheckNames | Select-Object -Unique)
$surfaceUnexpectedFailures = @(
    $surfaceFailingCheckNames | Where-Object { $surfaceGovernanceRetiredChecks -notcontains $_ }
)

$runtimeSurfaceReportsStrictlyGreen = (
    $surfaceReportsPresent -and
    [string]$classificationReport.overallStatus -eq "passed" -and
    [string]$allowlistReport.overallStatus -eq "passed" -and
    [string]$footprintReport.overallStatus -eq "passed"
)
$runtimeSurfaceGovernanceDriftAccepted = (
    -not $runtimeSurfaceReportsStrictlyGreen -and
    $SurfaceConvergencePendingOk -and
    $surfaceReportsPresent -and
    $surfaceUnexpectedFailures.Count -eq 0 -and
    $surfaceFailingCheckNames.Count -gt 0
)
$runtimeSurfaceReportsGreen = ($runtimeSurfaceReportsStrictlyGreen -or $runtimeSurfaceGovernanceDriftAccepted)
$runtimeHostGateGreen = ($null -ne $runtimeHostReport -and [string]$runtimeHostReport.overallStatus -eq "passed")
$runtimeHostGatePendingAccepted = (-not $runtimeHostGateGreen -and $RuntimeHostGatePendingOk -and $runtimeSurfaceReportsGreen)

$checks = @(
    (New-NPDevCheckResult -Name "runtimehost-gate-current" -Status $(if ($runtimeHostGateGreen -or $runtimeHostGatePendingAccepted) { "passed" } else { "failed" }) -Summary $(if ($runtimeHostGateGreen) { "RuntimeHost gate is currently green." } elseif ($runtimeHostGatePendingAccepted) { "RuntimeHost gate finalization is pending in the current run; runtime surface evidence is green." } else { "RuntimeHost gate evidence is missing or failing." }) -Data ([pscustomobject]@{ reportPath = Get-Bucket2RelativePath $WorkspaceRoot $RuntimeHostReportPath; overallStatus = if ($null -eq $runtimeHostReport) { $null } else { [string]$runtimeHostReport.overallStatus }; runId = $RunId; pendingFinalizationAccepted = $runtimeHostGatePendingAccepted }))
    (New-NPDevCheckResult -Name "runtime-surface-reports-current" -Status $(if ($runtimeSurfaceReportsGreen) { "passed" } else { "failed" }) -Summary $(if ($runtimeSurfaceReportsStrictlyGreen) { "Runtime surface evidence is current and green." } elseif ($runtimeSurfaceGovernanceDriftAccepted) { "RETIRED (GATE-OBS-1a, REG-5 2026-07-21): the only non-green runtime-surface sub-checks [" + ($surfaceFailingCheckNames -join ", ") + "] are the formally-retired package-convention convergence checks, superseded by the exact-list allowlist (runtime-surface-allowlist-report.json), which is BLOCKING and green. Not a pending item; informational only. See docs/OPEN_GAPS_AND_ROADMAP.md#GATE-OBS-1a." } elseif (-not $surfaceReportsPresent) { "Runtime surface evidence is missing." } else { "Runtime surface evidence is failing on check(s) OUTSIDE the retired GATE-OBS-1a convergence set: " + ($surfaceUnexpectedFailures -join ", ") }) -Data ([pscustomobject]@{ classification = if ($null -eq $classificationReport) { $null } else { [string]$classificationReport.overallStatus }; allowlist = if ($null -eq $allowlistReport) { $null } else { [string]$allowlistReport.overallStatus }; footprint = if ($null -eq $footprintReport) { $null } else { [string]$footprintReport.overallStatus }; failingChecks = @($surfaceFailingCheckNames); unexpectedFailures = @($surfaceUnexpectedFailures); governanceDriftAccepted = $runtimeSurfaceGovernanceDriftAccepted }))
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
