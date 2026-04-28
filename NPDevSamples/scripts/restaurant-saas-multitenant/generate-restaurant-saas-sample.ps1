param(
    [string]$NPDevRoot = "",
    [string]$OutputRoot = "",
    [switch]$NoAssembleFinalExec
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$generateScript = Join-Path (Split-Path -Parent $PSScriptRoot) "generate-sample-app.ps1"
$invokeArgs = @{
    SampleId = "restaurant-saas-multitenant"
}

if (-not [string]::IsNullOrWhiteSpace($NPDevRoot)) {
    $invokeArgs.NPDevRoot = $NPDevRoot
}
if (-not [string]::IsNullOrWhiteSpace($OutputRoot)) {
    $invokeArgs.OutputRoot = $OutputRoot
}
if ($NoAssembleFinalExec) {
    $invokeArgs.NoAssembleFinalApp = $true
}

& $generateScript @invokeArgs
