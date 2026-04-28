[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string[]]$SampleIds = @(),
    [string]$RunId = "",
    [switch]$NoAssembleFinalApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "sample-generation"
if ($SampleIds.Count -eq 0) {
    $SampleIds = @(Get-NPDevDefaultSampleId $WorkspaceRoot)
}

$generateScript = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples\scripts\generate-sample-app.ps1"
Ensure-NPDevFile $generateScript "Canonical sample generation script"

foreach ($sampleId in $SampleIds) {
    Write-NPDevInfo ("Generating sample " + $sampleId)
    if ($NoAssembleFinalApp) {
        & $generateScript -SampleId $sampleId -NPDevRoot $WorkspaceRoot -RunId $RunId -NoAssembleFinalApp
    }
    else {
        & $generateScript -SampleId $sampleId -NPDevRoot $WorkspaceRoot -RunId $RunId
    }
}

Write-NPDevOk ("Sample generation completed for " + ($SampleIds -join ", ") + ".")
