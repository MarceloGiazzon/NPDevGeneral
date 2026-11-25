# Stale-report check for the beta release gate (REG-356 item 2a, 2026-11-24).
#
# WHY THIS IS ITS OWN PRODUCER
# ----------------------------
# This used to be computed inside run-expanded-beta0-evidence.ps1, which the orchestration runs in the MIDDLE of stage 2.
# A check that judges the age of every required report must run after the last producer has written its report, and that
# step could not: it measured report-schema-validation (stage 3), sample-matrix and runtimehost-staged-jar-preflight
# BEFORE the run regenerated them -- 49-51 h old when the previous full gate was two days earlier -- and failed a
# 1 h 33 m gate on reports that were fresh and passing minutes later. Measured there, it can only pass if the previous
# full run was under 24 h earlier. It now runs as the last orchestration stage (see run-beta-release-evidence-orchestration.ps1,
# stage 4) and at the end of the legacy chain (run-beta0-final-release-check.ps1).
#
# WHAT IT CHECKS: every report listed in scripts/policy/beta-release-gate-policy.json requiredReports that exists on disk
# (a missing report is the release gate's own finding, not judged here) is no older than maxReportAgeHours, parses as
# JSON, and -- when it declares expandedBeta0ContractVersion -- declares the current one. This report and the provenance
# report are skipped: the first is this one, the second is written by the same step that declares the contract.
# Exit 1 when any report is stale, so a standalone run and the legacy chain see the verdict; the orchestration treats a
# non-zero exit with the report written as failed-with-evidence, which is the gate's to judge.

param(
    [string]$RunId = "",
    [string]$PolicyPath = "scripts/policy/beta-release-gate-policy.json",
    [string]$ScopePolicyPath = "scripts/policy/beta0-scope.json",
    [string]$ReportPath = "scripts/reports/out/stale-report-check-report.json"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = "stale-report-check-" + (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmssfff")
}

$scope = Get-Content -LiteralPath $ScopePolicyPath -Raw | ConvertFrom-Json
$betaGatePolicy = Get-Content -LiteralPath $PolicyPath -Raw | ConvertFrom-Json
$maxReportAgeHours = [double]$betaGatePolicy.maxReportAgeHours

$staleReportFailures = New-Object System.Collections.Generic.List[string]
$checkedReportCount = 0
foreach ($requiredReport in @($betaGatePolicy.requiredReports)) {
    $requiredReportPath = [string]$requiredReport.path
    if ($requiredReportPath -like "*stale-report-check-report.json" -or $requiredReportPath -like "*provenance-report.json") { continue }
    if (-not (Test-Path -LiteralPath $requiredReportPath)) { continue }
    $checkedReportCount++
    $ageHours = ((Get-Date) - (Get-Item -LiteralPath $requiredReportPath).LastWriteTime).TotalHours
    if ($ageHours -gt $maxReportAgeHours) {
        $staleReportFailures.Add("Report is older than " + $maxReportAgeHours + "h (" + [math]::Round($ageHours, 1) + "h): " + $requiredReportPath) | Out-Null
    }
    try {
        $reportJson = Get-Content -LiteralPath $requiredReportPath -Raw | ConvertFrom-Json
        $declaredContract = $reportJson.PSObject.Properties["expandedBeta0ContractVersion"]
        if ($null -ne $declaredContract -and [string]$declaredContract.Value -ne [string]$scope.expandedBeta0ContractVersion) {
            $staleReportFailures.Add("Report declares contract " + [string]$declaredContract.Value + ", expected " + [string]$scope.expandedBeta0ContractVersion + ": " + $requiredReportPath) | Out-Null
        }
    }
    catch {
        $staleReportFailures.Add("Report is not parseable JSON: " + $requiredReportPath) | Out-Null
    }
}

$passed = ($staleReportFailures.Count -eq 0)
$status = if ($passed) { "passed" } else { "failed" }
$report = [pscustomobject]@{
    schemaVersion = "npdev-stale-report-check-report.v1"
    runId = $RunId
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    scriptPath = "scripts/quality/run-stale-report-check.ps1"
    expandedBeta0ContractVersion = $scope.expandedBeta0ContractVersion
    overallStatus = $status
    status = $status
    evidence = [pscustomobject]@{
        checkedReports = $checkedReportCount
        maxReportAgeHours = $maxReportAgeHours
        blockingReportsFresh = $passed
        expandedBeta0ContractVersion = $scope.expandedBeta0ContractVersion
    }
    failures = @($staleReportFailures)
}
$reportParent = Split-Path -Parent $ReportPath
if (-not [string]::IsNullOrWhiteSpace($reportParent)) { New-Item -ItemType Directory -Force -Path $reportParent | Out-Null }
$report | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

Write-Host ("Stale-report check " + $status + ": " + $checkedReportCount + " report(s) checked, max age " + $maxReportAgeHours + " h.")
foreach ($failure in $staleReportFailures) { Write-Host ("  " + $failure) }
if ($passed) { exit 0 }
exit 1
