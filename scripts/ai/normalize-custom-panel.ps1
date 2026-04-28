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
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\ai-beta\normalized-custom-panel.json"
}
$panel = Read-AiJsonFile $PanelPath "AI custom panel"
$validation = Test-AiCustomPanelObject $panel
$normalized = [pscustomobject]@{
    schemaVersion = "ai-normalized-custom-panel.v1"
    panelId = [string](Get-AiProperty $panel "panelId" "")
    route = [string](Get-AiProperty $panel "route" "")
    layoutType = [string](Get-AiProperty (Get-AiProperty $panel "layout" $null) "type" "")
    visibleFields = @(Get-AiProperty $panel "visibleFields" @())
    actionNames = @((Get-AiProperty $panel "actions" @()) | ForEach-Object { [string](Get-AiProperty $_ "name" "") })
    validationStatus = $validation.overallStatus
}
Write-NPDevJsonFile $ReportPath $normalized
if ($validation.overallStatus -eq "passed") {
    Write-NPDevOk "AI custom panel normalization passed."
    return
}
Write-NPDevWarn "AI custom panel normalization failed."
throw "AI custom panel normalization failed."
