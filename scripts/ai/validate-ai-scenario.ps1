[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$ReportPath = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\validation-report.json"
}
$report = Invoke-AiScenarioValidation -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ReportPath $ReportPath
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI scenario validation passed for " + $report.scenarioId)
    return
}
Write-NPDevWarn ("AI scenario validation failed for " + $report.scenarioId + " as " + $report.failureClass)
throw "AI scenario validation failed."
