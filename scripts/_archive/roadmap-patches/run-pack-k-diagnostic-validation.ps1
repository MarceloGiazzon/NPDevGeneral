[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General'
)

$ErrorActionPreference = 'Stop'

$commands = @(
    'scripts\quality\run-runtime-surface-evidence.ps1',
    'scripts\quality\run-runtimehost-batch33-verification.ps1',
    'scripts\quality\run-beta-release-gate.ps1',
    'scripts\quality\run-hygiene-gate.ps1'
)

foreach ($relative in $commands) {
    $path = Join-Path $WorkspaceRoot $relative
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Required validation script not found: $path"
    }
    & $path -WorkspaceRoot $WorkspaceRoot
}

Write-Host 'OK    Pack K diagnostic validation completed.'
