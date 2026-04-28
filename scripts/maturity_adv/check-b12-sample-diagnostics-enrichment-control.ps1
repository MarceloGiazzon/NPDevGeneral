[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$SampleMatrixReportPath = "",
    [string]$SampleDiagnosticsReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b12-sample-diagnostics-enrichment-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b12-sample-diagnostics-enrichment-report.json"
$SampleMatrixReportPath = if ([string]::IsNullOrWhiteSpace($SampleMatrixReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-matrix-report.json"
}
else {
    Normalize-NPDevPath $SampleMatrixReportPath
}
$SampleDiagnosticsReportPath = if ([string]::IsNullOrWhiteSpace($SampleDiagnosticsReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\sample-diagnostics-enrichment-report.json"
}
else {
    Normalize-NPDevPath $SampleDiagnosticsReportPath
}

function Get-ControlCheckByName {
    param(
        [AllowNull()][object]$Report,
        [string]$Name
    )

    if ($null -eq $Report) {
        return $null
    }

    return ($Report.checks | Where-Object { [string]$_.name -eq $Name } | Select-Object -First 1)
}

$matrixSchema = Test-MaturityReportSchema -PathValue $SampleMatrixReportPath -RequiredProperties @(
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
$matrixReport = if ($matrixSchema.valid) { Read-MaturityJsonFile $SampleMatrixReportPath } else { $null }
$diagnosticsSchema = Test-MaturityReportSchema -PathValue $SampleDiagnosticsReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "matrixReportPath",
    "sampleAudits",
    "checks",
    "summary"
)
$diagnosticsReport = if ($diagnosticsSchema.valid) { Read-MaturityJsonFile $SampleDiagnosticsReportPath } else { $null }

$missingInlineFingerprintSamples = @()
$missingVerificationCommandSamples = @()
if ($null -ne $matrixReport) {
    foreach ($result in @($matrixReport.results)) {
        $matchingFingerprint = @($matrixReport.inputFingerprints | Where-Object { [string]$_.sampleId -eq [string]$result.sampleId })
        if ($null -eq $result.inputFingerprint -and $matchingFingerprint.Count -eq 0) {
            $missingInlineFingerprintSamples += [string]$result.sampleId
        }
        if ($null -eq $result.verificationCommand -or [string]::IsNullOrWhiteSpace([string]$result.verificationCommand.logPath)) {
            $missingVerificationCommandSamples += [string]$result.sampleId
        }
    }
}

$perResultDiagnosticsCheck = Get-ControlCheckByName -Report $diagnosticsReport -Name "per-result-diagnostics"
$verificationLogEvidenceCheck = Get-ControlCheckByName -Report $diagnosticsReport -Name "verification-log-evidence"
$cleanupReportEvidenceCheck = Get-ControlCheckByName -Report $diagnosticsReport -Name "cleanup-report-evidence"
$retainedEvidenceCheck = Get-ControlCheckByName -Report $diagnosticsReport -Name "retained-evidence-preserved"
$coverageAssertionCheck = Get-ControlCheckByName -Report $diagnosticsReport -Name "coverage-assertions"
$coverageAssertionsMatch = $false
if ($null -ne $matrixReport) {
    $actualCoverageSatisfied = [double]$matrixReport.matrixCoveragePercent -ge [double]$matrixReport.coverage.requiredReleaseCoveragePercent
    $coverageAssertionsMatch = ($null -ne $matrixReport.coverageAssertions) -and `
        ([bool]$matrixReport.coverageAssertions.releaseMatrixCoverageSatisfied -eq $actualCoverageSatisfied) -and `
        ([bool]$matrixReport.coverageAssertions.releaseEvidenceEligible -eq [bool]$matrixReport.releaseEvidence.eligible)
}

$checks = @(
    (New-MaturityCheck -Name "sample-matrix-report" -Status $(if ($matrixSchema.valid) { "passed" } else { "failed" }) -Expectation "The official sample matrix report must expose inline fingerprint, coverage, and cleanup governance fields." -Summary $(if ($matrixSchema.valid) { "The sample matrix report is readable and exposes the expected fields." } else { "The sample matrix report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleMatrixReportPath; missingProperties = $matrixSchema.missingProperties; parseError = $matrixSchema.parseError })
    (New-MaturityCheck -Name "sample-diagnostics-audit-report" -Status $(if ($diagnosticsSchema.valid) { "passed" } else { "failed" }) -Expectation "The sample diagnostics enrichment report must exist and expose per-sample audit results." -Summary $(if ($diagnosticsSchema.valid) { "The sample diagnostics enrichment report is readable." } else { "The sample diagnostics enrichment report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleDiagnosticsReportPath; missingProperties = $diagnosticsSchema.missingProperties; parseError = $diagnosticsSchema.parseError })
    (New-MaturityCheck -Name "sample-matrix-current" -Status $(if ($null -ne $matrixReport -and [string]$matrixReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The official sample matrix must currently pass." -Summary $(if ($null -ne $matrixReport -and [string]$matrixReport.overallStatus -eq "passed") { "The sample matrix is green." } else { "The sample matrix is missing or failing." }) -Data @{ overallStatus = if ($null -eq $matrixReport) { $null } else { [string]$matrixReport.overallStatus } })
    (New-MaturityCheck -Name "inline-fingerprint-and-command-evidence" -Status $(if ($missingInlineFingerprintSamples.Count -eq 0 -and $missingVerificationCommandSamples.Count -eq 0 -and $null -ne $perResultDiagnosticsCheck -and [string]$perResultDiagnosticsCheck.status -eq "passed" -and $null -ne $verificationLogEvidenceCheck -and [string]$verificationLogEvidenceCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Every sample result must include direct or auditable input fingerprint evidence and exact command evidence including logPath." -Summary $(if ($missingInlineFingerprintSamples.Count -eq 0 -and $missingVerificationCommandSamples.Count -eq 0 -and $null -ne $perResultDiagnosticsCheck -and [string]$perResultDiagnosticsCheck.status -eq "passed" -and $null -ne $verificationLogEvidenceCheck -and [string]$verificationLogEvidenceCheck.status -eq "passed") { "Fingerprint evidence and command evidence are present for every sample." } else { "One or more samples are missing fingerprint evidence or exact command evidence." }) -Data @{ missingInlineFingerprintSamples = $missingInlineFingerprintSamples; missingVerificationCommandSamples = $missingVerificationCommandSamples })
    (New-MaturityCheck -Name "cleanup-and-retained-evidence" -Status $(if ($null -ne $cleanupReportEvidenceCheck -and [string]$cleanupReportEvidenceCheck.status -eq "passed" -and $null -ne $retainedEvidenceCheck -and [string]$retainedEvidenceCheck.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Sample cleanup reporting must preserve retained evidence and expose exact removed/retained paths." -Summary $(if ($null -ne $cleanupReportEvidenceCheck -and [string]$cleanupReportEvidenceCheck.status -eq "passed" -and $null -ne $retainedEvidenceCheck -and [string]$retainedEvidenceCheck.status -eq "passed") { "Cleanup evidence is exact and retained evidence remains preserved." } else { "Cleanup evidence is missing, inconsistent, or removed retained artifacts." }) -Data @{ cleanupReportEvidence = $cleanupReportEvidenceCheck; retainedEvidence = $retainedEvidenceCheck })
    (New-MaturityCheck -Name "coverage-assertions-current" -Status $(if ($null -ne $coverageAssertionCheck -and [string]$coverageAssertionCheck.status -eq "passed" -and $coverageAssertionsMatch) { "passed" } else { "failed" }) -Expectation "Sample matrix coverageAssertions must match the actual release coverage and release-evidence decision." -Summary $(if ($null -ne $coverageAssertionCheck -and [string]$coverageAssertionCheck.status -eq "passed" -and $coverageAssertionsMatch) { "Coverage assertions match the actual matrix coverage." } else { "Coverage assertions drift from the actual matrix coverage or release-evidence decision." }) -Data @{ coverageAssertions = if ($null -eq $matrixReport) { $null } else { $matrixReport.coverageAssertions }; releaseEvidence = if ($null -eq $matrixReport) { $null } else { $matrixReport.releaseEvidence }; coverageMatches = $coverageAssertionsMatch })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B3" `
    -ControlId "B12-SAMPLE-DIAGNOSTICS-ENRICHMENT" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleMatrixReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleDiagnosticsReportPath
    ) `
    -Checks $checks `
    -Extra @{
        sampleMatrixReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleMatrixReportPath
        sampleDiagnosticsReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $SampleDiagnosticsReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
