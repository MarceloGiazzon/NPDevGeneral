[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\custom-procedure-panel",
    [string]$ReportPath = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$ScenarioRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot $ScenarioRoot
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\custom-panel-runtime-report.json"
}
$runtime = Invoke-AiRuntimeScenario -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot (Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\custom-panel-runtime")
$hasPanel = $null -ne $runtime.observations.customPanel -and -not [string]::IsNullOrWhiteSpace([string]$runtime.observations.customPanel.panelId)
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    scenarioId = $runtime.scenarioId
    overallStatus = if ($runtime.overallStatus -eq "passed" -and $hasPanel) { "passed" } else { "failed" }
    runtime = $runtime
}
Write-NPDevJsonFile $ReportPath $report
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "AI custom panel runtime verification passed."
    return
}
Write-NPDevWarn "AI custom panel runtime verification failed."
throw "AI custom panel runtime verification failed."
