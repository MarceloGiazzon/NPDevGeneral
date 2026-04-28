[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

& (Join-Path $PSScriptRoot 'run-runtime-surface-evidence.ps1') -WorkspaceRoot $WorkspaceRoot
& (Join-Path $PSScriptRoot 'run-runtimehost-gate.ps1') -WorkspaceRoot $WorkspaceRoot

Write-Host 'OK    RuntimeHost Batch 26 verification completed.'
