[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$ContractGateReportPath = "",
    [string]$ContractSchemaGovernanceReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b13-contract-schema-mirror-simplification-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b13-contract-schema-mirror-simplification-report.json"
$ContractGateReportPath = if ([string]::IsNullOrWhiteSpace($ContractGateReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-gate-report.json"
}
else {
    Normalize-NPDevPath $ContractGateReportPath
}
$ContractSchemaGovernanceReportPath = if ([string]::IsNullOrWhiteSpace($ContractSchemaGovernanceReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\contract-schema-governance-report.json"
}
else {
    Normalize-NPDevPath $ContractSchemaGovernanceReportPath
}

$gateSchema = Test-MaturityReportSchema -PathValue $ContractGateReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "workingDirectory",
    "command",
    "contractSchemaGovernance"
)
$gateReport = if ($gateSchema.valid) { Read-MaturityJsonFile $ContractGateReportPath } else { $null }
$governanceSchema = Test-MaturityReportSchema -PathValue $ContractSchemaGovernanceReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "inventoryPath",
    "schemaInventory",
    "aliasBehavior",
    "mirrorSync",
    "checks",
    "summary"
)
$governanceReport = if ($governanceSchema.valid) { Read-MaturityJsonFile $ContractSchemaGovernanceReportPath } else { $null }

$schemaInventoryFailures = @()
$regressionCoverageFailures = @()
$aliasBehaviorPassed = $false
$mirrorSyncPassed = $false
if ($null -ne $governanceReport) {
    $schemaInventoryFailures = @($governanceReport.schemaInventory | Where-Object { -not [bool]$_.matchesInventory })
    $regressionCoverageFailures = @($governanceReport.regressionCoverage | Where-Object { -not [bool]$_.passed })
    $aliasBehaviorPassed = [bool]$governanceReport.aliasBehavior.passed
    $mirrorSyncPassed = ($null -ne $governanceReport.mirrorSync) -and [string]$governanceReport.mirrorSync.overallStatus -eq "passed" -and [int]$governanceReport.mirrorSync.summary.failed -eq 0
}

$checks = @(
    (New-MaturityCheck -Name "contract-gate-report" -Status $(if ($gateSchema.valid) { "passed" } else { "failed" }) -Expectation "The contract gate report must expose contract schema governance evidence." -Summary $(if ($gateSchema.valid) { "The contract gate report is readable and exposes governance linkage." } else { "The contract gate report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ContractGateReportPath; missingProperties = $gateSchema.missingProperties; parseError = $gateSchema.parseError })
    (New-MaturityCheck -Name "contract-schema-governance-report" -Status $(if ($governanceSchema.valid) { "passed" } else { "failed" }) -Expectation "The contract schema governance report must exist and expose schema inventory, alias behavior, and mirror sync evidence." -Summary $(if ($governanceSchema.valid) { "The contract schema governance report is readable." } else { "The contract schema governance report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ContractSchemaGovernanceReportPath; missingProperties = $governanceSchema.missingProperties; parseError = $governanceSchema.parseError })
    (New-MaturityCheck -Name "contract-gate-current" -Status $(if ($null -ne $gateReport -and [string]$gateReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The official contract gate must currently pass." -Summary $(if ($null -ne $gateReport -and [string]$gateReport.overallStatus -eq "passed") { "The contract gate is green." } else { "The contract gate is missing or failing." }) -Data @{ overallStatus = if ($null -eq $gateReport) { $null } else { [string]$gateReport.overallStatus } })
    (New-MaturityCheck -Name "schema-inventory-current" -Status $(if ($schemaInventoryFailures.Count -eq 0) { "passed" } else { "failed" }) -Expectation "Contract schema inventory entries must match the exact current schema assets." -Summary $(if ($schemaInventoryFailures.Count -eq 0) { "Schema inventory entries match the current schema assets." } else { "One or more schema inventory entries drift from the current schema assets." }) -Data @{ failures = $schemaInventoryFailures })
    (New-MaturityCheck -Name "deprecated-alias-behavior" -Status $(if ($aliasBehaviorPassed) { "passed" } else { "failed" }) -Expectation "Concepts must remain canonical while entities remain legacy-reader-only." -Summary $(if ($aliasBehaviorPassed) { "Canonical and legacy alias behavior remains intact." } else { "Canonical and legacy alias behavior drifted." }) -Data @{ aliasBehavior = if ($null -eq $governanceReport) { $null } else { $governanceReport.aliasBehavior } })
    (New-MaturityCheck -Name "deprecation-regression-coverage" -Status $(if ($regressionCoverageFailures.Count -eq 0) { "passed" } else { "failed" }) -Expectation "Deprecation regression coverage must remain present for schema and alias behavior." -Summary $(if ($regressionCoverageFailures.Count -eq 0) { "Deprecation regression coverage remains present." } else { "Required deprecation regression coverage is missing." }) -Data @{ failures = $regressionCoverageFailures })
    (New-MaturityCheck -Name "exact-mirror-sync" -Status $(if ($mirrorSyncPassed) { "passed" } else { "failed" }) -Expectation "Mirror topology stays exact in this slice and all mirror targets must remain in zero-drift sync." -Summary $(if ($mirrorSyncPassed) { "Mirror targets remain exactly synchronized." } else { "Mirror drift was detected or could not be verified." }) -Data @{ mirrorSync = if ($null -eq $governanceReport) { $null } else { $governanceReport.mirrorSync } })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B3" `
    -ControlId "B13-CONTRACT-SCHEMA-MIRROR-SIMPLIFICATION" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ContractGateReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ContractSchemaGovernanceReportPath
    ) `
    -Checks $checks `
    -Extra @{
        contractGateReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ContractGateReportPath
        contractSchemaGovernanceReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ContractSchemaGovernanceReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
