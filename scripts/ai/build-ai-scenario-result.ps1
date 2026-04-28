[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$OutputRoot = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
$pipeline = Invoke-AiScenarioPipeline -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
if ($pipeline.result.overallStatus -eq "passed") {
    Write-NPDevOk ("AI scenario result passed for " + $pipeline.result.scenarioId)
    return
}
Write-NPDevWarn ("AI scenario result failed for " + $pipeline.result.scenarioId + " as " + $pipeline.result.resultClass)
throw "AI scenario result failed."
