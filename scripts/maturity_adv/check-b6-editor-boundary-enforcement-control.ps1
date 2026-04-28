[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$FrontendGateReportPath = "",
    [string]$BoundaryReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b6-editor-boundary-enforcement-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b6-editor-boundary-enforcement-report.json"
$FrontendGateReportPath = if ([string]::IsNullOrWhiteSpace($FrontendGateReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-gate-report.json"
}
else {
    Normalize-NPDevPath $FrontendGateReportPath
}
$BoundaryReportPath = if ([string]::IsNullOrWhiteSpace($BoundaryReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\frontend-boundary-report.json"
}
else {
    Normalize-NPDevPath $BoundaryReportPath
}

$gateSchema = Test-MaturityReportSchema -PathValue $FrontendGateReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "boundaryAudit",
    "subSteps",
    "summary"
)
$gateReport = if ($gateSchema.exists -and [string]::IsNullOrWhiteSpace([string]$gateSchema.parseError)) { Read-MaturityJsonFile $FrontendGateReportPath } else { $null }
$boundarySchema = Test-MaturityReportSchema -PathValue $BoundaryReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "classificationGroups",
    "summary",
    "imports",
    "checks"
)
$boundaryReport = if ($boundarySchema.exists -and [string]::IsNullOrWhiteSpace([string]$boundarySchema.parseError)) { Read-MaturityJsonFile $BoundaryReportPath } else { $null }

$boundaryAuditSection = if ($null -eq $gateReport) { $null } else { $gateReport.boundaryAudit }
$boundaryAuditSchema = Test-PrioritizedControlObjectProperties -Value $boundaryAuditSection -RequiredProperties @("reportPath", "overallStatus")
$expectedBoundaryReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $BoundaryReportPath
$gateBoundaryAgreement = $boundaryAuditSchema.valid -and ([string]$boundaryAuditSection.reportPath -eq $expectedBoundaryReportPath)

$boundarySummary = if ($null -eq $boundaryReport) { $null } else { $boundaryReport.summary }
$boundaryCoveragePassed = $null -ne $boundarySummary -and `
    [int]$boundarySummary.totalTsxFiles -gt 0 -and `
    [int]$boundarySummary.unclassifiedSourceFiles -eq 0 -and `
    [int]$boundarySummary.multiClassifiedSourceFiles -eq 0 -and `
    [int]$boundarySummary.importViolations -eq 0 -and `
    [int]$boundarySummary.unresolvedLocalImports -eq 0

$checks = @(
    (New-MaturityCheck -Name "frontend-gate-report" -Status $(if ($gateSchema.valid) { "passed" } else { "failed" }) -Expectation "The frontend gate report must exist and expose the official boundary-audit evidence link." -Summary $(if ($gateSchema.valid) { "The frontend gate report is readable and exposes the expected fields." } else { "The frontend gate report is missing or does not expose the expected fields." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $FrontendGateReportPath; missingProperties = $gateSchema.missingProperties; parseError = $gateSchema.parseError })
    (New-MaturityCheck -Name "frontend-boundary-report" -Status $(if ($boundarySchema.valid) { "passed" } else { "failed" }) -Expectation "The machine-readable frontend boundary audit report must exist." -Summary $(if ($boundarySchema.valid) { "The frontend boundary audit report is readable and exposes the expected fields." } else { "The frontend boundary audit report is missing or does not expose the expected fields." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $BoundaryReportPath; missingProperties = $boundarySchema.missingProperties; parseError = $boundarySchema.parseError })
    (New-MaturityCheck -Name "frontend-gate-current" -Status $(if ($null -ne $gateReport -and [string]$gateReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The official frontend gate must currently pass." -Summary $(if ($null -ne $gateReport -and [string]$gateReport.overallStatus -eq "passed") { "The frontend gate is green." } else { "The frontend gate is missing or failing." }) -Data @{ overallStatus = if ($null -eq $gateReport) { $null } else { [string]$gateReport.overallStatus } })
    (New-MaturityCheck -Name "boundary-audit-linked" -Status $(if ($gateBoundaryAgreement) { "passed" } else { "failed" }) -Expectation "The frontend gate must link to the canonical boundary audit report." -Summary $(if ($gateBoundaryAgreement) { "The frontend gate links to the canonical boundary audit report." } else { "The frontend gate does not link to the canonical boundary audit report." }) -Data @{ linkedReportPath = if ($null -eq $boundaryAuditSection) { $null } else { [string]$boundaryAuditSection.reportPath }; expectedReportPath = $expectedBoundaryReportPath; missingProperties = $boundaryAuditSchema.missing })
    (New-MaturityCheck -Name "boundary-rules-hold" -Status $(if ($null -ne $boundaryReport -and [string]$boundaryReport.overallStatus -eq "passed" -and $boundaryCoveragePassed) { "passed" } else { "failed" }) -Expectation "Every local .tsx entry must be classified exactly once and local imports must obey authoring/runtime/shared boundaries." -Summary $(if ($null -ne $boundaryReport -and [string]$boundaryReport.overallStatus -eq "passed" -and $boundaryCoveragePassed) { "Boundary coverage and local import rules are satisfied." } else { "Boundary coverage or local import rules are violated." }) -Data @{ overallStatus = if ($null -eq $boundaryReport) { $null } else { [string]$boundaryReport.overallStatus }; summary = $boundarySummary })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B2" `
    -ControlId "B6-EDITOR-BOUNDARY-ENFORCEMENT" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $FrontendGateReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $BoundaryReportPath
    ) `
    -Checks $checks `
    -Extra @{
        frontendGateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $FrontendGateReportPath
        boundaryReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $BoundaryReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
