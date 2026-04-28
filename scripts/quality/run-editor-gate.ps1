[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "editor-gate"

if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\editor-gate-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

$projectRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevEditor"
$gradleWrapperPath = Join-Path $projectRoot "gradlew.bat"
Write-NPDevInfo "Running NPDevEditor gate"

Invoke-NPDevReportedCommand `
    -WorkspaceRoot $WorkspaceRoot `
    -ScriptPath $PSCommandPath `
    -RunId $RunId `
    -ReportPath $ReportPath `
    -GateName "editor" `
    -WorkingDirectory $projectRoot `
    -Executable $gradleWrapperPath `
    -Arguments @("clean", "build", "--no-daemon", "--console=plain") | Out-Null

Write-NPDevOk "NPDevEditor gate passed."
