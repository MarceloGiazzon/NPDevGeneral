[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$GeneratorGovernanceReportPath = "",
    [string]$DeterministicGenerationReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b10-generator-determinism-migration-risk-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b10-generator-determinism-migration-risk-report.json"
$GeneratorGovernanceReportPath = if ([string]::IsNullOrWhiteSpace($GeneratorGovernanceReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\generator-governance-report.json"
}
else {
    Normalize-NPDevPath $GeneratorGovernanceReportPath
}
$DeterministicGenerationReportPath = if ([string]::IsNullOrWhiteSpace($DeterministicGenerationReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\deterministic-generation-report.json"
}
else {
    Normalize-NPDevPath $DeterministicGenerationReportPath
}

$governanceSchema = Test-MaturityReportSchema -PathValue $GeneratorGovernanceReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "policyPath",
    "determinism",
    "migrationRisk",
    "checks",
    "summary"
)
$governanceReport = if ($governanceSchema.exists -and [string]::IsNullOrWhiteSpace([string]$governanceSchema.parseError)) { Read-MaturityJsonFile $GeneratorGovernanceReportPath } else { $null }
$deterministicSchema = Test-MaturityReportSchema -PathValue $DeterministicGenerationReportPath -RequiredProperties @(
    "generatedAt",
    "workspaceRoot",
    "sampleId",
    "overallStatus"
)
$deterministicReport = if ($deterministicSchema.exists -and [string]::IsNullOrWhiteSpace([string]$deterministicSchema.parseError)) { Read-MaturityJsonFile $DeterministicGenerationReportPath } else { $null }
$migrationRisk = if ($null -eq $governanceReport) { $null } else { $governanceReport.migrationRisk }
$determinism = if ($null -eq $governanceReport) { $null } else { $governanceReport.determinism }

$checks = @(
    (New-MaturityCheck -Name "deterministic-generation-report" -Status $(if ($deterministicSchema.valid) { "passed" } else { "failed" }) -Expectation "The deterministic generation report must exist and expose the official determinism result." -Summary $(if ($deterministicSchema.valid) { "The deterministic generation report is readable." } else { "The deterministic generation report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $DeterministicGenerationReportPath; missingProperties = $deterministicSchema.missingProperties; parseError = $deterministicSchema.parseError })
    (New-MaturityCheck -Name "generator-governance-report" -Status $(if ($governanceSchema.valid) { "passed" } else { "failed" }) -Expectation "The generator governance report must exist and expose determinism and migration-risk wiring." -Summary $(if ($governanceSchema.valid) { "The generator governance report is readable and exposes the expected fields." } else { "The generator governance report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $GeneratorGovernanceReportPath; missingProperties = $governanceSchema.missingProperties; parseError = $governanceSchema.parseError })
    (New-MaturityCheck -Name "official-determinism-current" -Status $(if ($null -ne $deterministicReport -and [string]$deterministicReport.overallStatus -eq "passed" -and $null -ne $determinism -and [string]$determinism.status -eq "passed") { "passed" } else { "failed" }) -Expectation "Official generator determinism must stay green in both the standalone and governance reports." -Summary $(if ($null -ne $deterministicReport -and [string]$deterministicReport.overallStatus -eq "passed" -and $null -ne $determinism -and [string]$determinism.status -eq "passed") { "Official generator determinism remains green." } else { "Official generator determinism evidence is missing or failing." }) -Data @{ deterministicGenerationReport = $deterministicReport; governanceDeterminism = $determinism })
    (New-MaturityCheck -Name "generator-governance-current" -Status $(if ($null -ne $governanceReport -and [string]$governanceReport.overallStatus -eq "passed") { "passed" } else { "failed" }) -Expectation "The generator governance report must currently pass." -Summary $(if ($null -ne $governanceReport -and [string]$governanceReport.overallStatus -eq "passed") { "The generator governance report is green." } else { "The generator governance report is missing or failing." }) -Data @{ overallStatus = if ($null -eq $governanceReport) { $null } else { [string]$governanceReport.overallStatus } })
    (New-MaturityCheck -Name "migration-risk-wiring" -Status $(if ($null -ne $migrationRisk -and -not [string]::IsNullOrWhiteSpace([string]$migrationRisk.canonicalOutput) -and @($migrationRisk.reportPaths).Count -gt 0) { "passed" } else { "failed" }) -Expectation "Migration-risk output must be emitted into generated support assets and expose a canonical output path." -Summary $(if ($null -ne $migrationRisk -and -not [string]::IsNullOrWhiteSpace([string]$migrationRisk.canonicalOutput) -and @($migrationRisk.reportPaths).Count -gt 0) { "Migration-risk output is wired into generated support assets." } else { "Migration-risk output is missing or lacks a canonical output path." }) -Data @{ migrationRisk = $migrationRisk })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B2" `
    -ControlId "B10-GENERATOR-DETERMINISM-MIGRATION-RISK" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $DeterministicGenerationReportPath
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $GeneratorGovernanceReportPath
    ) `
    -Checks $checks `
    -Extra @{
        deterministicGenerationReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $DeterministicGenerationReportPath
        generatorGovernanceReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $GeneratorGovernanceReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
