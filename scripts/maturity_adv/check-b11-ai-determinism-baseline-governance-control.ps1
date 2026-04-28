[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$AiBetaMatrixReportPath = "",
    [string]$AiBaselineGovernanceReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b11-ai-determinism-baseline-governance-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b11-ai-determinism-baseline-governance-report.json"
$AiBetaMatrixReportPath = if ([string]::IsNullOrWhiteSpace($AiBetaMatrixReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta-matrix-report.json"
}
else {
    Normalize-NPDevPath $AiBetaMatrixReportPath
}
$AiBaselineGovernanceReportPath = if ([string]::IsNullOrWhiteSpace($AiBaselineGovernanceReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-baseline-governance-report.json"
}
else {
    Normalize-NPDevPath $AiBaselineGovernanceReportPath
}

$matrixSchema = Test-MaturityReportSchema -PathValue $AiBetaMatrixReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "matrixId",
    "caseCount",
    "determinismContract",
    "baselineGovernance",
    "cases"
)
$matrixReport = if ($matrixSchema.exists -and [string]::IsNullOrWhiteSpace([string]$matrixSchema.parseError)) { Read-MaturityJsonFile $AiBetaMatrixReportPath } else { $null }
$governanceSchema = Test-MaturityReportSchema -PathValue $AiBaselineGovernanceReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "determinismContract",
    "cases",
    "checks",
    "summary"
)
$governanceReport = if ($governanceSchema.exists -and [string]::IsNullOrWhiteSpace([string]$governanceSchema.parseError)) { Read-MaturityJsonFile $AiBaselineGovernanceReportPath } else { $null }

$matrixCases = if ($null -eq $matrixReport) { @() } else { @($matrixReport.cases) }
$governanceCases = if ($null -eq $governanceReport) { @() } else { @($governanceReport.cases) }
$determinismFailures = @($matrixCases | Where-Object {
        $null -eq $_.determinism -or (
            [string]$_.determinism.status -ne "passed" -and
            [string]$_.determinism.status -ne "not-applicable"
        )
    })
$missingBaselines = @($matrixCases | Where-Object { @($_.baselines | Where-Object { -not $_.checkedIn }).Count -gt 0 })
$reviewWarnings = @($governanceCases | Where-Object { $null -ne $_.reviewMetadata -and [string]$_.reviewMetadata.status -eq "warning" })

$checks = @(
    (New-MaturityCheck -Name "ai-beta-matrix-report" -Status $(if ($matrixSchema.valid) { "passed" } else { "failed" }) -Expectation "The official AI beta matrix report must expose the exact determinism metadata fields." -Summary $(if ($matrixSchema.valid) { "The AI beta matrix report is readable and exposes the expected fields." } else { "The AI beta matrix report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AiBetaMatrixReportPath; missingProperties = $matrixSchema.missingProperties; parseError = $matrixSchema.parseError })
    (New-MaturityCheck -Name "ai-baseline-governance-report" -Status $(if ($governanceSchema.valid) { "passed" } else { "failed" }) -Expectation "The AI baseline governance report must expose the exact governance fields." -Summary $(if ($governanceSchema.valid) { "The AI baseline governance report is readable and exposes the expected fields." } else { "The AI baseline governance report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AiBaselineGovernanceReportPath; missingProperties = $governanceSchema.missingProperties; parseError = $governanceSchema.parseError })
    (New-MaturityCheck -Name "ai-determinism-cases" -Status $(if ($determinismFailures.Count -eq 0) { "passed" } else { "failed" }) -Expectation "Every AI beta matrix case must publish exact determinism metadata, with governed negative scenarios allowed to report not-applicable." -Summary $(if ($determinismFailures.Count -eq 0) { "Every AI beta matrix case publishes valid exact determinism metadata." } else { "One or more AI beta matrix cases are missing or failing determinism metadata." }) -Data @{ failingCases = $determinismFailures })
    (New-MaturityCheck -Name "ai-baseline-checkin" -Status $(if ($missingBaselines.Count -eq 0) { "passed" } else { "failed" }) -Expectation "Every AI beta matrix case must keep its scenario baselines checked in and represented in the report." -Summary $(if ($missingBaselines.Count -eq 0) { "Scenario baselines remain checked in for every AI beta matrix case." } else { "One or more AI beta matrix cases are missing checked-in baselines." }) -Data @{ failingCases = $missingBaselines })
    (New-MaturityCheck -Name "ai-baseline-governance-current" -Status $(if ($null -ne $governanceReport -and [string]$governanceReport.overallStatus -eq "failed") { "failed" } elseif ($reviewWarnings.Count -gt 0 -or ($null -ne $governanceReport -and [string]$governanceReport.overallStatus -eq "warning")) { "warning" } else { "passed" }) -Expectation "Determinism and missing-baseline regressions fail; review metadata freshness remains warning-only." -Summary $(if ($null -ne $governanceReport -and [string]$governanceReport.overallStatus -eq "failed") { "AI baseline governance detected a deterministic or baseline regression." } elseif ($reviewWarnings.Count -gt 0 -or ($null -ne $governanceReport -and [string]$governanceReport.overallStatus -eq "warning")) { "AI baseline governance completed with warning-only review metadata freshness gaps." } else { "AI baseline governance is green." }) -Data @{ reviewWarnings = $reviewWarnings; overallStatus = if ($null -eq $governanceReport) { $null } else { [string]$governanceReport.overallStatus } })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B2" `
    -ControlId "B11-AI-DETERMINISM-BASELINE-GOVERNANCE" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AiBetaMatrixReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AiBaselineGovernanceReportPath
    ) `
    -Checks $checks `
    -Extra @{
        aiBetaMatrixReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AiBetaMatrixReportPath
        aiBaselineGovernanceReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $AiBaselineGovernanceReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
