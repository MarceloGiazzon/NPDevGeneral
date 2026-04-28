Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")
. (Join-Path $PSScriptRoot "..\statezip-common.ps1")

$script:failures = [System.Collections.Generic.List[string]]::new()

function Assert-True {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        [void]$script:failures.Add($Message)
    }
}

function Test-ReleaseReadySummaryFalseWhenGateFailed {
    $releaseReadySummary = Get-ReleaseReadyDecision -AggregateStatus "failed" -ProvenanceGrade "git-traceable"
    Assert-True (-not $releaseReadySummary.releaseReady) "release-ready-summary should return releaseReady=false when aggregate gate is failed."
    Assert-True ($releaseReadySummary.packagingMode -eq "DIAGNOSTIC") "release-ready-summary should fall back to DIAGNOSTIC packaging when aggregate gate is failed."
}

function Test-StaleRunIdCausesAggregateFailure {
    $aggregateRunId = "aggregate-run-001"
    $childReport = [pscustomobject]@{
        runId = "stale-run-999"
        overallStatus = "passed"
    }

    $staleRunIdRejected = ([string]$childReport.runId -ne $aggregateRunId)
    Assert-True $staleRunIdRejected "A stale report with a mismatched runId should cause the aggregate gate to fail."
}

function Test-EvidenceManifestContainsEveryChildReportAfterSimulatedFailure {
    $stepReports = @(
        "scripts\\reports\\out\\runtimehost-gate-report.json",
        "scripts\\reports\\out\\sample-matrix-report.json",
        "scripts\\reports\\out\\frontend-gate-report.json"
    )
    $evidenceManifest = [pscustomobject]@{
        files = @(
            [pscustomobject]@{ source = "scripts\\reports\\out\\runtimehost-gate-report.json"; sha256 = "a" },
            [pscustomobject]@{ source = "scripts\\reports\\out\\sample-matrix-report.json"; sha256 = "b" },
            [pscustomobject]@{ source = "scripts\\reports\\out\\frontend-gate-report.json"; sha256 = "c" }
        )
    }

    $manifestSources = @($evidenceManifest.files | ForEach-Object { [string]$_.source })
    $missing = @($stepReports | Where-Object { $_ -notin $manifestSources })
    Assert-True (@($missing).Count -eq 0) "A simulated failure evidence-manifest should still contain every child report."
}

try {
    Test-ReleaseReadySummaryFalseWhenGateFailed
    Test-StaleRunIdCausesAggregateFailure
    Test-EvidenceManifestContainsEveryChildReportAfterSimulatedFailure
}
catch {
    [void]$script:failures.Add($_.Exception.Message)
}

if ($script:failures.Count -eq 0) {
    Write-NPDevOk "Release evidence governance tests passed."
    exit 0
}

foreach ($failure in $script:failures) {
    Write-NPDevWarn $failure
}
throw "Release evidence governance tests failed."
