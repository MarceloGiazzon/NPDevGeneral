param(
    [string]$NPDevRoot = "",
    [string]$FinalExecRoot = "",
    [int]$Port = 0,
    [string]$Profiles = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runScript = Join-Path (Split-Path -Parent $PSScriptRoot) "run-sample-app.ps1"
$invokeArgs = @{
    SampleId = "restaurant-saas-multitenant"
}
if (-not [string]::IsNullOrWhiteSpace($NPDevRoot)) {
    $invokeArgs.NPDevRoot = $NPDevRoot
}
if (-not [string]::IsNullOrWhiteSpace($FinalExecRoot)) {
    $invokeArgs.AppRoot = $FinalExecRoot
}
if ($Port -gt 0) {
    $invokeArgs.Port = $Port
}
if (-not [string]::IsNullOrWhiteSpace($Profiles)) {
    $invokeArgs.Profiles = $Profiles
}

& $runScript @invokeArgs
