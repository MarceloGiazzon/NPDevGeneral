[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [switch]$PassThru
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = Get-NPDevWorkspaceRoot $PSScriptRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot

$templateResidue = @()
foreach ($relativePath in @(
        "NPDevRuntimeHost\npdev-generated",
        "NPDevRuntimeHost\npdev-meta",
        "NPDevRuntimeHost\build.gradle"
    )) {
    $absolutePath = Resolve-NPDevWorkspacePath $WorkspaceRoot $relativePath
    if (Test-Path -LiteralPath $absolutePath) {
        $templateResidue += $relativePath
    }
}

$sampleCacheEntries = @()
$samplesRoot = Resolve-NPDevWorkspacePath $WorkspaceRoot "NPDevSamples"
Get-ChildItem -LiteralPath $samplesRoot -Directory -Force | ForEach-Object {
    $outputApp = Join-Path $_.FullName "Output\App"
    foreach ($staleName in @(".gradle", "build", "node_modules")) {
        $candidate = Join-Path $outputApp $staleName
        if (Test-Path -LiteralPath $candidate) {
            $sampleCacheEntries += (Get-NPDevWorkspaceRelativePath $WorkspaceRoot $candidate)
        }
    }
}

$status = "passed"
$summary = "Runtime template and sample output cleanliness checks passed."
if ($templateResidue.Count -gt 0) {
    $status = "failed"
    $summary = "RuntimeHost template contains generated residue that should not be canonical."
}
elseif ($sampleCacheEntries.Count -gt 0) {
    $status = "warning"
    $summary = "Sample outputs contain disposable build caches that should be cleaned by scripts."
}

$result = New-NPDevCheckResult "output-cleanliness" $status $summary @{
    templateResidue = $templateResidue
    sampleCacheEntries = $sampleCacheEntries
}

if ($PassThru) {
    return $result
}

switch ($result.status) {
    "passed" { Write-NPDevOk $result.summary }
    "warning" { Write-NPDevWarn $result.summary }
    default {
        Write-NPDevWarn $result.summary
        throw $result.summary
    }
}

