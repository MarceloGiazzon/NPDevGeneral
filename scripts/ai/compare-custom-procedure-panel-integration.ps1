[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\custom-procedure-panel",
    [string]$OutputRoot = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
$pipeline = Invoke-AiScenarioPipeline -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot -MismatchClass "FAIL_PANEL_PROCEDURE_INTEGRATION"
if ($pipeline.result.resultClass -eq "PASS_PANEL_PROCEDURE_INTEGRATION") {
    Write-NPDevOk ("AI custom procedure-panel integration passed for " + $pipeline.result.scenarioId)
    return
}
Write-NPDevWarn ("AI custom procedure-panel integration failed as " + $pipeline.result.resultClass)
throw "AI custom procedure-panel integration failed."
