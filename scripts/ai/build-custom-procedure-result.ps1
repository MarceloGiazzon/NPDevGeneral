[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$ScenarioRoot = "golden-ai-scenarios\custom-procedure-panel",
    [string]$OutputRoot = ""
)

& (Join-Path $PSScriptRoot "run-custom-procedure-scenario.ps1") -WorkspaceRoot $WorkspaceRoot -ScenarioRoot $ScenarioRoot -OutputRoot $OutputRoot
