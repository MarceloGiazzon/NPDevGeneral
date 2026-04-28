[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = "",
    [string]$ScriptAutomationQualityReportPath = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "prioritized-control-common.ps1")

$WorkspaceRoot = Resolve-MaturityWorkspaceRoot -WorkspaceRoot $WorkspaceRoot -ScriptRoot $PSScriptRoot
$RunId = Resolve-NPDevRunId $RunId "b16-script-automation-quality-polish-control"
$ReportPath = Resolve-PrioritizedControlReportPath -WorkspaceRoot $WorkspaceRoot -ReportPath $ReportPath -DefaultRelativePath "scripts\reports\out\prioritized-b16-script-automation-quality-polish-report.json"
$ScriptAutomationQualityReportPath = if ([string]::IsNullOrWhiteSpace($ScriptAutomationQualityReportPath)) {
    Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\script-automation-quality-report.json"
}
else {
    Normalize-NPDevPath $ScriptAutomationQualityReportPath
}

$reportSchema = Test-MaturityReportSchema -PathValue $ScriptAutomationQualityReportPath -RequiredProperties @(
    "generatedAt",
    "runId",
    "overallStatus",
    "parserValidation",
    "structuredReportContract",
    "commonHelperCoverage",
    "sharedModuleRegression",
    "analyzer",
    "checks",
    "summary"
)
$qualityReport = if ($reportSchema.valid) { Read-MaturityJsonFile $ScriptAutomationQualityReportPath } else { $null }

$parserPassed = $false
$reportContractPassed = $false
$helperCoveragePassed = $false
$sharedRegressionPassed = $false
$analyzerAcceptable = $false
if ($null -ne $qualityReport) {
    $parserPassed = @($qualityReport.parserValidation.failures).Count -eq 0
    $reportContractPassed = @($qualityReport.structuredReportContract.failures).Count -eq 0
    $helperCoveragePassed = @($qualityReport.commonHelperCoverage.failures).Count -eq 0
    $sharedRegressionPassed = @($qualityReport.sharedModuleRegression.results | Where-Object { -not [bool]$_.passed }).Count -eq 0
    $analyzerAcceptable = (-not [bool]$qualityReport.analyzer.available) -or @($qualityReport.analyzer.violations).Count -eq 0
}

$checks = @(
    (New-MaturityCheck -Name "script-automation-quality-report" -Status $(if ($reportSchema.valid) { "passed" } else { "failed" }) -Expectation "The script automation quality report must expose parser, contract, helper, regression, and analyzer results." -Summary $(if ($reportSchema.valid) { "The script automation quality report is readable." } else { "The script automation quality report is missing or invalid." }) -Data @{ path = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ScriptAutomationQualityReportPath; missingProperties = $reportSchema.missingProperties; parseError = $reportSchema.parseError })
    (New-MaturityCheck -Name "parser-validation-current" -Status $(if ($parserPassed) { "passed" } else { "failed" }) -Expectation "All audited PowerShell automation scripts must parse cleanly." -Summary $(if ($parserPassed) { "Parser validation is clean." } else { "One or more PowerShell automation scripts have parser failures." }) -Data @{ parserValidation = if ($null -eq $qualityReport) { $null } else { $qualityReport.parserValidation } })
    (New-MaturityCheck -Name "structured-report-contract-current" -Status $(if ($reportContractPassed) { "passed" } else { "failed" }) -Expectation "Quality automation scripts must keep the standard structured report contract." -Summary $(if ($reportContractPassed) { "Structured report contracts remain intact." } else { "Structured report contract coverage regressed." }) -Data @{ structuredReportContract = if ($null -eq $qualityReport) { $null } else { $qualityReport.structuredReportContract } })
    (New-MaturityCheck -Name "common-helper-coverage-current" -Status $(if ($helperCoveragePassed) { "passed" } else { "failed" }) -Expectation "Automation scripts must stay wired through shared helper modules." -Summary $(if ($helperCoveragePassed) { "Shared helper coverage remains intact." } else { "One or more automation scripts are not wired through shared helper modules." }) -Data @{ commonHelperCoverage = if ($null -eq $qualityReport) { $null } else { $qualityReport.commonHelperCoverage } })
    (New-MaturityCheck -Name "shared-module-regression-current" -Status $(if ($sharedRegressionPassed) { "passed" } else { "failed" }) -Expectation "Shared helper regression checks must stay green." -Summary $(if ($sharedRegressionPassed) { "Shared helper regression checks remain green." } else { "Shared helper regression checks failed." }) -Data @{ sharedModuleRegression = if ($null -eq $qualityReport) { $null } else { $qualityReport.sharedModuleRegression } })
    (New-MaturityCheck -Name "analyzer-nonblocking-governed" -Status $(if ($analyzerAcceptable) { "passed" } else { "failed" }) -Expectation "PSScriptAnalyzer may be unavailable without failing the control, but actual analyzer violations must fail." -Summary $(if ($analyzerAcceptable) { "Analyzer availability and violations are governed as expected." } else { "Analyzer violations are present." }) -Data @{ analyzer = if ($null -eq $qualityReport) { $null } else { $qualityReport.analyzer } })
)

$report = Write-PrioritizedControlReport `
    -WorkspaceRoot $WorkspaceRoot `
    -RunId $RunId `
    -ScriptPath $PSCommandPath `
    -Bucket "B3" `
    -ControlId "B16-SCRIPT-AUTOMATION-QUALITY-POLISH" `
    -ReportPath $ReportPath `
    -EvidencePaths @(
        Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ScriptAutomationQualityReportPath
    ) `
    -Checks $checks `
    -Extra @{
        scriptAutomationQualityReportPath = Get-PrioritizedControlEvidencePath -WorkspaceRoot $WorkspaceRoot -PathValue $ScriptAutomationQualityReportPath
    }

Complete-PrioritizedControlScript -Report $report -PassThru:$PassThru
