[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General'
)

$ErrorActionPreference = 'Stop'

& (Join-Path $WorkspaceRoot 'scripts\quality\run-runtime-surface-evidence.ps1') -WorkspaceRoot $WorkspaceRoot
& (Join-Path $WorkspaceRoot 'scripts\quality\run-runtimehost-gate.ps1') -WorkspaceRoot $WorkspaceRoot

Write-Host 'OK    RuntimeHost Batch 33 verification completed.'
