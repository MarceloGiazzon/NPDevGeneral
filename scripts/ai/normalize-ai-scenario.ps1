[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$OutputRoot = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
$report = Invoke-AiScenarioNormalization -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI scenario normalization passed for " + $report.scenarioId)
    return
}
Write-NPDevWarn ("AI scenario normalization failed for " + $report.scenarioId)
throw "AI scenario normalization failed."
