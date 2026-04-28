[CmdletBinding()]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'runtimehost-automation-contract-helper.psm1') -Force

$scriptPath = 'scripts\quality\run-runtimehost-batch16-verification.ps1'
$outPath = Join-Path $WorkspaceRoot 'scripts\reports\out\runtimehost-batch16-verification-report.json'
$report = New-StructuredRunReport -WorkspaceRoot $WorkspaceRoot -ScriptPath $scriptPath -RunIdPrefix 'runtimehost-batch16-verification'

try {
    Invoke-ReportedCommand -Report $report -Name 'runtime-surface-evidence' -CommandText "& 'scripts\quality\run-runtime-surface-evidence.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
        & (Join-Path $PSScriptRoot 'run-runtime-surface-evidence.ps1') -WorkspaceRoot $WorkspaceRoot
    }

    Invoke-ReportedCommand -Report $report -Name 'runtimehost-gate' -CommandText "& 'scripts\quality\run-runtimehost-gate.ps1' -WorkspaceRoot '{WorkspaceRoot}'" -ScriptBlock {
        & (Join-Path $PSScriptRoot 'run-runtimehost-gate.ps1') -WorkspaceRoot $WorkspaceRoot
    }
}
finally {
    Write-StructuredRunReport -Report $report -OutputPath $outPath
}

if ($report.overallStatus -eq 'failed') {
    Write-Warning 'RuntimeHost Batch 16 verification failed.'
    throw 'RuntimeHost Batch 16 verification failed.'
}

Write-Host 'OK    RuntimeHost Batch 16 verification completed.'
