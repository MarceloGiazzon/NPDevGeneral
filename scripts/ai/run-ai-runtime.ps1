[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$OutputRoot = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
$report = Invoke-AiRuntimeScenario -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI runtime harness passed for " + $report.scenarioId)
    return
}
Write-NPDevWarn ("AI runtime harness failed for " + $report.scenarioId + " as " + $report.resultClass)
throw "AI runtime harness failed."
