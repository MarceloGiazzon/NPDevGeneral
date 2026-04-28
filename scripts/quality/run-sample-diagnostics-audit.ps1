[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$MatrixReportPath = "",
    [string]$ReportPath = "",
    [switch]$MatrixPendingOk,
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "sample-diagnostics-audit"

if ([string]::IsNullOrWhiteSpace($MatrixReportPath)) {
    $MatrixReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-matrix-report.json"
}
else {
    $MatrixReportPath = Normalize-NPDevPath $MatrixReportPath
}

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

function Get-ObjectPropertyNames {
    param(
        [AllowNull()][object]$Value
    )

    if ($null -eq $Value) {
        return @()
    }

    return @($Value.PSObject.Properties | Select-Object -ExpandProperty Name)
}

function Test-RequiredProperties {
    param(
        [AllowNull()][object]$Value,
        [string[]]$RequiredProperties
    )

    $propertyNames = Get-ObjectPropertyNames $Value
    $missing = @($RequiredProperties | Where-Object { $_ -notin $propertyNames })
    return [pscustomobject]@{
        valid = ($missing.Count -eq 0)
        missing = $missing
        propertyNames = $propertyNames
        parseError = $null
    }
}

function Resolve-RelativeWorkspacePath {
    param(
        [string]$PathValue
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }

    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return Normalize-NPDevPath $PathValue
    }

    return Resolve-NPDevWorkspacePath $WorkspaceRoot $PathValue
}

function Read-JsonFileOrNull {
    param(
        [string]$PathValue
    )

    if ([string]::IsNullOrWhiteSpace($PathValue)) {
        return $null
    }
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }

    return Get-Content -LiteralPath $PathValue -Raw | ConvertFrom-Json
}

$matrixReport = $null
$matrixSchema = [pscustomobject]@{
    valid = $false
    missing = @()
    parseError = $null
}
if (Test-Path -LiteralPath $MatrixReportPath -PathType Leaf) {
    try {
        $matrixReport = Get-Content -LiteralPath $MatrixReportPath -Raw | ConvertFrom-Json
        $matrixSchema = Test-RequiredProperties -Value $matrixReport -RequiredProperties @(
            "generatedAt",
            "runId",
            "overallStatus",
            "matrixCoveragePercent",
            "coverage",
            "releaseEvidence",
            "inputFingerprints",
            "coverageAssertions",
            "cleanupPolicy",
            "results",
            "summary"
        )
    }
    catch {
        $matrixSchema = [pscustomobject]@{
            valid = $false
            missing = @()
            parseError = $_.Exception.Message
        }
    }
}
$matrixPendingAccepted = ($MatrixPendingOk -and ($null -eq $matrixReport -or -not $matrixSchema.valid))

$sampleAudits = [System.Collections.Generic.List[object]]::new()
if ($null -ne $matrixReport -and $matrixSchema.valid) {
    $inputFingerprintBySampleId = @{}
    foreach ($fingerprint in @($matrixReport.inputFingerprints)) {
        $inputFingerprintBySampleId[[string]$fingerprint.sampleId] = $fingerprint
    }
    foreach ($result in @($matrixReport.results)) {
        $resultSchema = Test-RequiredProperties -Value $result -RequiredProperties @(
            "sampleId",
            "kind",
            "verificationCommand",
            "generationMarker",
            "cleanup",
            "outputSummary",
            "status"
        )
        $effectiveInputFingerprint = if ($null -ne $result.inputFingerprint) {
            $result.inputFingerprint
        }
        else {
            $inputFingerprintBySampleId[[string]$result.sampleId]
        }
        $verificationSchema = Test-RequiredProperties -Value $result.verificationCommand -RequiredProperties @(
            "status",
            "workingDirectory",
            "executable",
            "arguments",
            "display",
            "exitCode",
            "startedAt",
            "endedAt",
            "durationSeconds",
            "logPath"
        )
        $fingerprintSchema = Test-RequiredProperties -Value $effectiveInputFingerprint -RequiredProperties @(
            "sampleId",
            "kind",
            "inputRoot",
            "files",
            "issues"
        )
        $outputSummarySchema = Test-RequiredProperties -Value $result.outputSummary -RequiredProperties @(
            "appRoot",
            "exists",
            "fileCount",
            "sizeBytes"
        )

        $verificationLogPath = Resolve-RelativeWorkspacePath ([string]$result.verificationCommand.logPath)
        $cleanupReportPath = Resolve-RelativeWorkspacePath ([string]$result.cleanup.reportPath)
        $cleanupReport = Read-JsonFileOrNull $cleanupReportPath
        $cleanupReportResult = if ($null -eq $cleanupReport) {
            $null
        }
        else {
            @($cleanupReport.results | Where-Object { [string]$_.sampleId -eq [string]$result.sampleId } | Select-Object -First 1)
        }
        $cleanupReportResultSchema = Test-RequiredProperties -Value $cleanupReportResult -RequiredProperties @(
            "sampleId",
            "mode",
            "removedPaths",
            "retainedEvidencePaths"
        )
        $effectiveCleanup = [pscustomobject]@{
            status = if ($null -eq $result.cleanup) { $null } else { $result.cleanup.status }
            reportPath = if ($null -eq $result.cleanup) { $null } else { $result.cleanup.reportPath }
            removedPaths = if ($null -ne $result.cleanup -and (Get-ObjectPropertyNames $result.cleanup) -contains "removedPaths") {
                @($result.cleanup.removedPaths | ForEach-Object { [string]$_ })
            }
            elseif ($null -ne $cleanupReportResult) {
                @($cleanupReportResult.removedPaths | ForEach-Object { [string]$_ })
            }
            else {
                @()
            }
            retainedEvidencePaths = if ($null -ne $result.cleanup -and (Get-ObjectPropertyNames $result.cleanup) -contains "retainedEvidencePaths") {
                @($result.cleanup.retainedEvidencePaths | ForEach-Object { [string]$_ })
            }
            elseif ($null -ne $cleanupReportResult) {
                @($cleanupReportResult.retainedEvidencePaths | ForEach-Object { [string]$_ })
            }
            else {
                @()
            }
        }
        $cleanupSchema = Test-RequiredProperties -Value $effectiveCleanup -RequiredProperties @(
            "status",
            "reportPath",
            "removedPaths",
            "retainedEvidencePaths"
        )

        $removedPaths = @()
        if ($cleanupSchema.valid) {
            $removedPaths = @($effectiveCleanup.removedPaths)
        }
        $retainedEvidencePaths = @()
        if ($cleanupSchema.valid) {
            $retainedEvidencePaths = @($effectiveCleanup.retainedEvidencePaths)
        }
        $removedRetainedEvidencePaths = @($removedPaths | Where-Object { $retainedEvidencePaths -contains $_ })
        $cleanupMatchesResult = $cleanupReportResultSchema.valid -and `
            (@($removedPaths) -join "|") -eq (@($cleanupReportResult.removedPaths | ForEach-Object { [string]$_ }) -join "|") -and `
            (@($retainedEvidencePaths) -join "|") -eq (@($cleanupReportResult.retainedEvidencePaths | ForEach-Object { [string]$_ }) -join "|")

        [void]$sampleAudits.Add([pscustomobject]@{
                sampleId = [string]$result.sampleId
                status = [string]$result.status
                resultSchema = $resultSchema
                verificationSchema = $verificationSchema
                fingerprintSchema = $fingerprintSchema
                cleanupSchema = $cleanupSchema
                outputSummarySchema = $outputSummarySchema
                verificationLogPath = if ([string]::IsNullOrWhiteSpace([string]$result.verificationCommand.logPath)) { $null } else { [string]$result.verificationCommand.logPath }
                verificationLogExists = ($null -ne $verificationLogPath -and (Test-Path -LiteralPath $verificationLogPath -PathType Leaf))
                cleanupReportPath = if ([string]::IsNullOrWhiteSpace([string]$result.cleanup.reportPath)) { $null } else { [string]$result.cleanup.reportPath }
                cleanupReportExists = ($null -ne $cleanupReportPath -and (Test-Path -LiteralPath $cleanupReportPath -PathType Leaf))
                cleanupReportSchema = $cleanupReportResultSchema
                cleanupMatchesResult = $cleanupMatchesResult
                removedRetainedEvidencePaths = $removedRetainedEvidencePaths
            })
    }
}

$coverageAssertionStatus = $false
$coverageAssertionDetails = $null
if ($matrixPendingAccepted) {
    $coverageAssertionStatus = $true
    $coverageAssertionDetails = [pscustomobject]@{
        pendingFinalizationAccepted = $true
        runId = $RunId
        matrixReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MatrixReportPath
        summary = "Sample matrix finalization is pending in the current run; strict diagnostics will be refreshed after sample matrix generation."
    }
}
elseif ($null -ne $matrixReport -and $matrixSchema.valid) {
    $coverageAssertions = $matrixReport.coverageAssertions
    $coverageAssertionsSchema = Test-RequiredProperties -Value $coverageAssertions -RequiredProperties @(
        "releaseMatrixCoverageSatisfied",
        "releaseMatrixCoveragePercent",
        "requiredReleaseCoveragePercent",
        "releaseEvidenceEligible"
    )
    $actualCoverageSatisfied = [double]$matrixReport.matrixCoveragePercent -ge [double]$matrixReport.coverage.requiredReleaseCoveragePercent
    $declaredCoverageSatisfied = if ($coverageAssertionsSchema.valid) { [bool]$coverageAssertions.releaseMatrixCoverageSatisfied } else { $false }
    $declaredReleaseEvidenceEligible = if ($coverageAssertionsSchema.valid) { [bool]$coverageAssertions.releaseEvidenceEligible } else { $false }
    $coverageAssertionStatus = $coverageAssertionsSchema.valid -and `
        ($declaredCoverageSatisfied -eq $actualCoverageSatisfied) -and `
        ([math]::Abs(([double]$coverageAssertions.releaseMatrixCoveragePercent) - [double]$matrixReport.matrixCoveragePercent) -lt 0.01) -and `
        ([double]$coverageAssertions.requiredReleaseCoveragePercent -eq [double]$matrixReport.coverage.requiredReleaseCoveragePercent) -and `
        ($declaredReleaseEvidenceEligible -eq [bool]$matrixReport.releaseEvidence.eligible)
    $coverageAssertionDetails = [pscustomobject]@{
        schema = $coverageAssertionsSchema
        declaredCoverageSatisfied = $declaredCoverageSatisfied
        actualCoverageSatisfied = $actualCoverageSatisfied
        declaredReleaseEvidenceEligible = $declaredReleaseEvidenceEligible
        actualReleaseEvidenceEligible = [bool]$matrixReport.releaseEvidence.eligible
    }
}

$missingLogs = @($sampleAudits | Where-Object { -not $_.verificationLogExists })
$missingCleanupReports = @($sampleAudits | Where-Object { -not $_.cleanupReportExists })
$cleanupSchemaFailures = @($sampleAudits | Where-Object { -not $_.cleanupMatchesResult -or -not $_.cleanupReportSchema.valid })
$removedRetainedEvidence = @($sampleAudits | Where-Object { @($_.removedRetainedEvidencePaths).Count -gt 0 })
$perResultSchemaFailures = @($sampleAudits | Where-Object {
        -not $_.resultSchema.valid -or -not $_.verificationSchema.valid -or -not $_.fingerprintSchema.valid -or -not $_.cleanupSchema.valid -or -not $_.outputSummarySchema.valid
    })

$checks = @(
    (New-NPDevCheckResult "sample-matrix-report" $(if ($matrixSchema.valid -or $matrixPendingAccepted) { "passed" } else { "failed" }) $(if ($matrixSchema.valid) { "Sample matrix report exposes the required governance fields." } elseif ($matrixPendingAccepted) { "Sample matrix finalization is pending in the current run; strict diagnostics will be refreshed after sample matrix generation." } else { "Sample matrix report is missing, unparsable, or incomplete." }) @{
            path = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MatrixReportPath
            missingProperties = $matrixSchema.missing
            parseError = $matrixSchema.parseError
            pendingFinalizationAccepted = $matrixPendingAccepted
        }),
    (New-NPDevCheckResult "per-result-diagnostics" $(if ($perResultSchemaFailures.Count -eq 0) { "passed" } else { "failed" }) $(if ($perResultSchemaFailures.Count -eq 0) { "Every sample result exposes inline fingerprint, command evidence, cleanup, marker, and output summary fields." } else { "One or more sample results are missing required diagnostics fields." }) @{
            failingSamples = @($perResultSchemaFailures | ForEach-Object {
                    [pscustomobject]@{
                        sampleId = $_.sampleId
                        resultMissing = $_.resultSchema.missing
                        verificationMissing = $_.verificationSchema.missing
                        fingerprintMissing = $_.fingerprintSchema.missing
                        cleanupMissing = $_.cleanupSchema.missing
                        outputSummaryMissing = $_.outputSummarySchema.missing
                    }
                })
        }),
    (New-NPDevCheckResult "verification-log-evidence" $(if ($missingLogs.Count -eq 0) { "passed" } else { "failed" }) $(if ($missingLogs.Count -eq 0) { "Every sample result preserves its verification log path." } else { "One or more sample results are missing verification log evidence." }) @{
            missingLogSamples = @($missingLogs | ForEach-Object { $_.sampleId })
        }),
    (New-NPDevCheckResult "cleanup-report-evidence" $(if ($missingCleanupReports.Count -eq 0 -and $cleanupSchemaFailures.Count -eq 0) { "passed" } else { "failed" }) $(if ($missingCleanupReports.Count -eq 0 -and $cleanupSchemaFailures.Count -eq 0) { "Cleanup evidence is present and agrees with the sample matrix report." } else { "Cleanup evidence is missing or does not agree with the sample matrix report." }) @{
            missingCleanupReportSamples = @($missingCleanupReports | ForEach-Object { $_.sampleId })
            mismatchedCleanupSamples = @($cleanupSchemaFailures | ForEach-Object { $_.sampleId })
        }),
    (New-NPDevCheckResult "retained-evidence-preserved" $(if ($removedRetainedEvidence.Count -eq 0) { "passed" } else { "failed" }) $(if ($removedRetainedEvidence.Count -eq 0) { "Cleanup preserved retained evidence and removed only build caches." } else { "Cleanup removed paths that were expected to remain as retained evidence." }) @{
            affectedSamples = @($removedRetainedEvidence | ForEach-Object {
                    [pscustomobject]@{
                        sampleId = $_.sampleId
                        removedRetainedEvidencePaths = $_.removedRetainedEvidencePaths
                    }
                })
        }),
    (New-NPDevCheckResult "coverage-assertions" $(if ($coverageAssertionStatus) { "passed" } else { "failed" }) $(if ($coverageAssertionStatus -and $matrixPendingAccepted) { "Sample matrix finalization is pending in the current run; strict coverage assertions will be refreshed after sample matrix generation." } elseif ($coverageAssertionStatus) { "Coverage assertions agree with the release evidence decision." } else { "Coverage assertions do not match the actual matrix coverage or release evidence decision." }) $coverageAssertionDetails)
)

$failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
$warningChecks = @($checks | Where-Object { $_.status -eq "warning" })
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    runId = $RunId
    scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
    workspaceRoot = $WorkspaceRoot
    overallStatus = if ($failedChecks.Count -gt 0) { "failed" } elseif ($warningChecks.Count -gt 0) { "warning" } else { "passed" }
    matrixReportPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $MatrixReportPath
    sampleAudits = @($sampleAudits)
    checks = $checks
    summary = [pscustomobject]@{
        failed = $failedChecks.Count
        warnings = $warningChecks.Count
        passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
        total = $checks.Count
    }
}
Write-NPDevJsonFile $ReportPath $report

if ($PassThru) {
    return $report
}

if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "Sample diagnostics enrichment report generated."
    return
}

if ($report.overallStatus -eq "warning") {
    Write-NPDevWarn "Sample diagnostics enrichment report generated with warnings."
    return
}

Write-NPDevWarn "Sample diagnostics enrichment report failed."
throw "Sample diagnostics enrichment report failed."
