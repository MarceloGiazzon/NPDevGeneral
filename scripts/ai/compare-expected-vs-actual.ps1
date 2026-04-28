[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\base-ai-loop",
    [string]$ActualPath = "",
    [string]$OutputRoot = "",
    [string]$MismatchClass = "FAIL_BEHAVIOR_MISMATCH"
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
if ([string]::IsNullOrWhiteSpace($ActualPath)) {
    $runtime = Invoke-AiRuntimeScenario -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\runtime")
    $ActualPath = Resolve-NPDevWorkspacePath $WorkspaceRoot ("scripts\reports\out\ai-beta\runtime\" + $runtime.scenarioId + "\actual-output.json")
}
else {
    $ActualPath = Normalize-NPDevPath $ActualPath
}
$report = Invoke-AiExpectedActualComparison -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ActualPath $ActualPath -OutputRoot $OutputRoot -MismatchClass $MismatchClass
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk ("AI expected-vs-actual comparison passed for " + $report.scenarioId)
    return
}
Write-NPDevWarn ("AI expected-vs-actual comparison failed for " + $report.scenarioId + " as " + $report.resultClass)
throw "AI expected-vs-actual comparison failed."
