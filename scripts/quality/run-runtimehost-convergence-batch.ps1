[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [switch]$SkipRuntimeHostGate,
    [switch]$SkipPrunePlan
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'runtimehost-automation-contract-helper.psm1') -Force

$scriptPath = 'scripts\quality\run-runtimehost-convergence-batch.ps1'
$outPath = Join-Path $WorkspaceRoot 'scripts\reports\out\runtimehost-convergence-batch-report.json'
$report = New-StructuredRunReport -WorkspaceRoot $WorkspaceRoot -ScriptPath $scriptPath -RunIdPrefix 'runtimehost-convergence-batch'

try {
    Invoke-ReportedCommand -Report $report -Name 'runtime-surface-evidence' -CommandText "& 'scripts\quality\run-runtime-surface-evidence.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
        & (Join-Path $PSScriptRoot 'run-runtime-surface-evidence.ps1') -WorkspaceRoot $WorkspaceRoot
    }

    Invoke-ReportedCommand -Report $report -Name 'runtimehost-convergence-check' -CommandText "& 'scripts\quality\run-runtimehost-convergence-check.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
        & (Join-Path $PSScriptRoot 'run-runtimehost-convergence-check.ps1') -WorkspaceRoot $WorkspaceRoot
    }

    if (-not $SkipPrunePlan) {
        Invoke-ReportedCommand -Report $report -Name 'export-runtimehost-prune-plan' -CommandText "& 'scripts\quality\export-runtimehost-prune-plan.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
            & (Join-Path $PSScriptRoot 'export-runtimehost-prune-plan.ps1') -WorkspaceRoot $WorkspaceRoot
        }
    } else {
        Add-StructuredCheck -Report $report -Name 'export-runtimehost-prune-plan' -Status 'passed' -Summary 'Skipped by request.' -Data @{ skipped = $true }
    }

    if (-not $SkipRuntimeHostGate) {
        Invoke-ReportedCommand -Report $report -Name 'runtimehost-gate' -CommandText "& 'scripts\quality\run-runtimehost-gate.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
            & (Join-Path $PSScriptRoot 'run-runtimehost-gate.ps1') -WorkspaceRoot $WorkspaceRoot
        }
    } else {
        Add-StructuredCheck -Report $report -Name 'runtimehost-gate' -Status 'passed' -Summary 'Skipped by request.' -Data @{ skipped = $true }
    }
}
finally {
    Write-StructuredRunReport -Report $report -OutputPath $outPath
}

if ($report.overallStatus -eq 'failed') {
    Write-Warning 'RuntimeHost convergence batch failed.'
    throw 'RuntimeHost convergence batch failed.'
}

Write-Host 'OK    RuntimeHost convergence batch completed.'
