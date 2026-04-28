[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$OutputRoot = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
# temperature=0 and seed=20260423 are the default deterministic AI generation settings
$report = Invoke-AiScenarioGeneration -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI generation harness passed for " + $report.scenarioId)
    return
}
Write-NPDevWarn ("AI generation harness failed for " + $report.scenarioId)
throw "AI generation failed."
