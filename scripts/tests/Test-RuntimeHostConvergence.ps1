[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'

$targetsPath = Join-Path $WorkspaceRoot 'scripts\policy\runtimehost-convergence-targets.json'
if (-not (Test-Path -LiteralPath $targetsPath)) {
    throw "RuntimeHost convergence policy file not found: $targetsPath"
}

$doc = Get-Content -LiteralPath $targetsPath -Raw | ConvertFrom-Json -Depth 50

$required = @('controllerInventoryMax','supportedControllersMin','serviceInventoryMax','supportedServicesMin')
$missing = @()
foreach ($name in $required) {
    if ($null -eq $doc.targets.PSObject.Properties[$name]) {
        $missing += $name
    }
}

if ($missing.Count -gt 0) {
    throw "RuntimeHost convergence policy is missing required target(s): $($missing -join ', ')"
}

Write-Host "OK    RuntimeHost convergence policy test passed."
