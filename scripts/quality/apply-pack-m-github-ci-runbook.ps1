[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General'
)

$ErrorActionPreference = 'Stop'

$sourceRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path -LiteralPath $sourceRoot)) {
    throw "Could not resolve source root from script path: $PSScriptRoot"
}

$items = @(
    @{ Source = Join-Path $sourceRoot 'docs\OFFICIAL_BETA_RELEASE_RUNBOOK.md'; Destination = Join-Path $WorkspaceRoot 'docs\OFFICIAL_BETA_RELEASE_RUNBOOK.md' },
    @{ Source = Join-Path $sourceRoot '.github\workflows\npdev-release-gate.yml'; Destination = Join-Path $WorkspaceRoot '.github\workflows\npdev-release-gate.yml' },
    @{ Source = Join-Path $sourceRoot 'scripts\release\push-npdev-monorepo-and-tag.ps1'; Destination = Join-Path $WorkspaceRoot 'scripts\release\push-npdev-monorepo-and-tag.ps1' }
)

foreach ($item in $items) {
    if (-not (Test-Path -LiteralPath $item.Source)) {
        throw "Source file not found: $($item.Source)"
    }

    $destDir = Split-Path -Parent $item.Destination
    New-Item -ItemType Directory -Force -Path $destDir | Out-Null
    Copy-Item -LiteralPath $item.Source -Destination $item.Destination -Force
    Write-Host ('OK    Wrote ' + $item.Destination)
}

Write-Host ''
Write-Host 'OK    Pack M GitHub/CI/runbook files installed.'
