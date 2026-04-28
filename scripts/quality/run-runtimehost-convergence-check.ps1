[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [switch]$RefreshSurfaceEvidence
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'runtimehost-automation-contract-helper.psm1') -Force

$scriptPath = 'scripts\quality\run-runtimehost-convergence-check.ps1'
$outPath = Join-Path $WorkspaceRoot 'scripts\reports\out\runtimehost-convergence-report.json'
$report = New-StructuredRunReport -WorkspaceRoot $WorkspaceRoot -ScriptPath $scriptPath -RunIdPrefix 'runtimehost-convergence-check'

try {
    if ($RefreshSurfaceEvidence) {
        Invoke-ReportedCommand -Report $report -Name 'runtime-surface-evidence' -CommandText "& 'scripts\quality\run-runtime-surface-evidence.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
            & (Join-Path $PSScriptRoot 'run-runtime-surface-evidence.ps1') -WorkspaceRoot $WorkspaceRoot
        }
    }

    $footprint = Read-JsonFile -Path (Join-Path $WorkspaceRoot 'scripts\reports\out\runtime-footprint-report.json')
    $classification = Read-JsonFile -Path (Join-Path $WorkspaceRoot 'scripts\reports\out\runtime-surface-classification-report.json')
    $allowlistPath = Join-Path $WorkspaceRoot 'scripts\reports\out\runtime-surface-allowlist-report.json'
    $allowlist = if (Test-Path -LiteralPath $allowlistPath) { Read-JsonFile -Path $allowlistPath } else { $null }

    $controllerInventory = [int]$footprint.footprint.totalControllerCount
    $supportedControllers = [int]$footprint.footprint.supportedControllerCount
    $serviceInventory = [int]$footprint.footprint.totalServiceCount
    $supportedServices = [int]$footprint.footprint.supportedServiceCount
    $internalButNeeded = [int]$classification.inventory.controllerBuckets.internalButNeeded + [int]$classification.inventory.serviceBuckets.internalButNeeded
    $transitional = [int]$classification.inventory.controllerBuckets.transitional + [int]$classification.inventory.serviceBuckets.transitional
    $deadControllerCandidates = @($footprint.footprint.deadRemoveCandidates.controllers).Count
    $deadServiceCandidates = @($footprint.footprint.deadRemoveCandidates.services).Count
    $allowlistCount = if ($null -ne $allowlist -and $null -ne $allowlist.allowlistCount) { [int]$allowlist.allowlistCount } else { 0 }

    Add-StructuredCheck -Report $report -Name 'controller-footprint-read' -Status 'passed' -Summary "Controllers: supported=$supportedControllers total=$controllerInventory." -Data @{
        supportedControllers = $supportedControllers
        totalControllers = $controllerInventory
    }

    Add-StructuredCheck -Report $report -Name 'service-footprint-read' -Status 'passed' -Summary "Services: supported=$supportedServices total=$serviceInventory." -Data @{
        supportedServices = $supportedServices
        totalServices = $serviceInventory
    }

    Add-StructuredCheck -Report $report -Name 'convergence-observations' -Status 'passed' -Summary "internalButNeeded=$internalButNeeded; transitional=$transitional; deadControllers=$deadControllerCandidates; deadServices=$deadServiceCandidates." -Data @{
        internalButNeeded = $internalButNeeded
        transitional = $transitional
        deadControllerCandidates = $deadControllerCandidates
        deadServiceCandidates = $deadServiceCandidates
        allowlistCount = $allowlistCount
    }
}
finally {
    Write-StructuredRunReport -Report $report -OutputPath $outPath
}

if ($report.overallStatus -eq 'failed') {
    Write-Warning 'RuntimeHost convergence check failed.'
    throw 'RuntimeHost convergence check failed.'
}

Write-Host "OK    RuntimeHost convergence check passed. Report: $outPath"
