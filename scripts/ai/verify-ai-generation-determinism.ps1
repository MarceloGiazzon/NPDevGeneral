[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$OutputRoot = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
$report = Invoke-AiGenerationDeterminism -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI generation determinism passed for " + $report.scenarioId)
    return
}
Write-NPDevWarn ("AI generation determinism failed for " + $report.scenarioId)
throw "AI generation determinism failed."
