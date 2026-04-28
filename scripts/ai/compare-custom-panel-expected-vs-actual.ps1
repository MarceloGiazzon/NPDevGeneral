[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\custom-procedure-panel",
    [string]$ActualPath = "",
    [string]$OutputRoot = ""
)

& (Join-Path $PSScriptRoot "compare-expected-vs-actual.ps1") -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -ActualPath $ActualPath -OutputRoot $OutputRoot -MismatchClass "FAIL_PANEL_BEHAVIOR_MISMATCH"
