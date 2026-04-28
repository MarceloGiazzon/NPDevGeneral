[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SampleId,
    [string]$WorkspaceRoot = "",
    [int]$Port = 0,
    [string]$Profiles = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$runScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\scripts\run-sample-app.ps1"
Ensure-NPDevFile $runScript "Canonical sample run script"

$argsToPass = @{
    SampleId = $SampleId
    NPDevRoot = $WorkspaceRoot
}
if ($Port -gt 0) {
    $argsToPass.Port = $Port
}
if (-not [string]::IsNullOrWhiteSpace($Profiles)) {
    $argsToPass.Profiles = $Profiles
}

& $runScript @argsToPass
