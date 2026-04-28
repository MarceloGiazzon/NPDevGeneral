[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "maturity-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "performance-scalability-maturity"
$ReportPath = Resolve-MaturityReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\performance-scalability-maturity-report.json"

$checks = @()

function Add-Condition {
    param(
        [string]$Id,
        [string]$Text,
        [bool]$Passed,
        [string]$PassSummary,
        [string]$FailSummary,
        [object]$Data = $null
    )

    $script:checks += New-MaturityDoneConditionCheck `
        -ConditionId $Id `
        -ConditionText $Text `
        -Passed:$Passed `
        -PassSummary $PassSummary `
        -FailSummary $FailSummary `
        -Data $Data
}

$perfIndexPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevRuntimeHost\src\main\resources\db\migration\V5007__add_perf_indexes.sql"
$performanceReportFiles = @(Get-ChildItem -LiteralPath (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out") -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'performance|latency|throughput' })
$perfTestHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevRuntimeHost\src\test\java" -Includes @("*.java") -Pattern 'EXPLAIN ANALYZE|p95|p99|latency|throughput|1000 concurrent|10000 events'
$eventStorePerfHits = Find-MaturityTextMatches -WorkspaceRoot $WorkspaceRoot -RelativeRoot "NPDevKernel" -Includes @("*.java") -Pattern '10000 events|p99|compaction|crash|recover|throughput'
$releaseEvidenceManifest = Read-MaturityJsonFile (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\releases\runtimehost-beta-20260423-012013\evidence-manifest.json")
$releasePerformanceFiles = if ($null -eq $releaseEvidenceManifest) {
    @()
}
else {
    @($releaseEvidenceManifest.files | Where-Object { [string]$_.source -match 'performance|latency|throughput' })
}

Add-Condition "PSM-001" "Performance baseline established for: concept read, concept list, event query, audit query" `
    (@($perfTestHits | Where-Object { $_.line -match 'concept read|concept list|event query|audit query' }).Count -ge 4) `
    "Performance test sources reference the expected concept/event/audit baseline scenarios." `
    "No explicit evidence was found for all required concept/event/audit baseline scenarios." `
    @{ hits = $perfTestHits }

Add-Condition "PSM-002" "Regression test fails if query time >2x baseline" `
    (@($perfTestHits | Where-Object { $_.line -match '2x baseline|baseline' }).Count -gt 0) `
    "Performance test sources reference regression-to-baseline comparison." `
    "No explicit regression-to-baseline comparison evidence was found." `
    @{ hits = $perfTestHits }

Add-Condition "PSM-003" "Index usage validated with EXPLAIN ANALYZE in tests" `
    (@($perfTestHits | Where-Object { $_.line -match 'EXPLAIN ANALYZE' }).Count -gt 0) `
    "Performance test sources reference EXPLAIN ANALYZE." `
    "No EXPLAIN ANALYZE evidence was found in performance test sources." `
    @{ hits = $perfTestHits }

Add-Condition "PSM-004" "Load test: 1000 concurrent concept operations, p95 latency <100ms" `
    (@($perfTestHits | Where-Object { $_.line -match '1000 concurrent|p95|100ms' }).Count -ge 3) `
    "Performance test sources reference 1000-concurrent / p95 / 100ms targets." `
    "No explicit 1000-concurrent / p95 / 100ms evidence was found." `
    @{ hits = $perfTestHits }

Add-Condition "PSM-005" "Performance report generated per release, stored in evidence bundle" `
    (@($releasePerformanceFiles).Count -gt 0) `
    "The current release evidence bundle includes performance-related files." `
    "The current release evidence bundle does not include performance-related files." `
    @{ releasePerformanceFiles = $releasePerformanceFiles }

Add-Condition "PSM-006" "Throughput test: 10,000 events/second sustained for 1 minute" `
    (@($eventStorePerfHits | Where-Object { $_.line -match '10000 events|10,000 events' }).Count -gt 0) `
    "Event store performance sources reference the 10,000 events/second target." `
    "No explicit 10,000 events/second target evidence was found for the event store." `
    @{ hits = $eventStorePerfHits }

Add-Condition "PSM-007" "Latency test: p99 event write latency <10ms" `
    (@($eventStorePerfHits | Where-Object { $_.line -match 'p99|10ms' }).Count -ge 2) `
    "Event store performance sources reference p99 latency and 10ms targets." `
    "No explicit p99 latency / 10ms target evidence was found for the event store." `
    @{ hits = $eventStorePerfHits }

Add-Condition "PSM-008" "Recovery test: event store recovers from crash without data loss" `
    (@($eventStorePerfHits | Where-Object { $_.line -match 'crash|recover' }).Count -ge 2) `
    "Event store sources reference crash/recovery scenarios." `
    "No explicit crash/recovery evidence was found for the event store." `
    @{ hits = $eventStorePerfHits }

Add-Condition "PSM-009" "Compaction test: old events archived without impacting active queries" `
    (@($eventStorePerfHits | Where-Object { $_.line -match 'compaction|archived' }).Count -gt 0) `
    "Event store sources reference compaction/archive behavior." `
    "No explicit compaction/archive evidence was found for the event store." `
    @{ hits = $eventStorePerfHits }

Add-Condition "PSM-010" "Performance report included in release evidence" `
    (@($releasePerformanceFiles).Count -gt 0 -or @($performanceReportFiles).Count -gt 0) `
    "Performance-related report files were found in current workspace or release evidence." `
    "No performance-related report files were found in current workspace or release evidence." `
    @{ workspaceReports = @($performanceReportFiles | ForEach-Object { $_.Name }); releasePerformanceFiles = $releasePerformanceFiles }

$report = Write-MaturityReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -MaturityItem "performance-scalability-maturity" `
    -ReportPath $ReportPath `
    -Checks $checks `
    -Extra @{
        perfIndexPresent = (Test-Path -LiteralPath $perfIndexPath -PathType Leaf)
        conditionCount = $checks.Count
    }

Complete-MaturityScript -Report $report -PassThru:$PassThru
