[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$PanelPath = "golden-ai-scenarios\custom-procedure-panel\custom-panel.json",
    [string]$ReportPath = ""
)

. (Join-Path $PSScriptRoot "ai-beta-common.ps1")
$WorkspaceRoot = Initialize-AiBetaWorkspace $WorkspaceRoot
$PanelPath = Resolve-NPDevWorkspacePath $WorkspaceRoot $PanelPath
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\custom-panel-validation-report.json"
}
$report = Test-AiCustomPanelObject (Read-AiJsonFile $PanelPath "AI custom panel")
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString("o")
    panelPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PanelPath
    overallStatus = $report.overallStatus
    failureClass = $report.failureClass
    checks = $report.checks
}
Write-NPDevJsonFile $ReportPath $report
if ($report.overallStatus -eq "passed") {
    Write-NPDevOk "AI custom panel validation passed."
    return
}
Write-NPDevWarn "AI custom panel validation failed."
throw "AI custom panel validation failed."
