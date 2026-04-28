[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$PlanPath = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($PlanPath)) {
    $PlanPath = Join-Path $WorkspaceRoot 'scripts\policy\runtimehost-batch33-remove-list.json'
}

if (-not (Test-Path -LiteralPath $PlanPath)) {
    throw "Batch 33 plan file not found: $PlanPath"
}

$plan = Get-Content -LiteralPath $PlanPath -Raw | ConvertFrom-Json -Depth 50
$missing = @()
$present = @()

foreach ($relativePath in $plan.files) {
    $fullPath = Join-Path $WorkspaceRoot $relativePath
    if (Test-Path -LiteralPath $fullPath) {
        $present += $relativePath
    } else {
        $missing += $relativePath
    }
}

$outDir = Join-Path $WorkspaceRoot 'scripts\reports\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$reportPath = Join-Path $outDir 'runtimehost-batch33-plan-report.json'
$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    scriptPath = 'scripts\tests\Test-RuntimeHostBatch33Plan.ps1'
    workspaceRoot = $WorkspaceRoot
    planPath = [System.IO.Path]::GetRelativePath($WorkspaceRoot, $PlanPath)
    totalPlanned = @($plan.files).Count
    presentCount = $present.Count
    missingCount = $missing.Count
    present = $present
    missing = $missing
    overallStatus = if ($missing.Count -eq 0) { 'passed' } else { 'failed' }
}

$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportPath -Encoding UTF8

if ($missing.Count -gt 0) {
    throw "Batch 33 plan validation failed. Missing file(s): $($missing -join ', ')"
}

Write-Host "OK    RuntimeHost Batch 33 plan validation passed."
