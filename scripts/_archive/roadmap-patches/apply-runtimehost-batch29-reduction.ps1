[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$PlanPath = (Join-Path $WorkspaceRoot 'scripts\policy\runtimehost-batch29-remove-list.json'),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $PlanPath)) {
    throw "Removal plan not found: $PlanPath"
}

$plan = Get-Content -LiteralPath $PlanPath -Raw | ConvertFrom-Json -Depth 50
$outPath = Join-Path $WorkspaceRoot 'scripts\reports\out\runtimehost-batch29-reduction-report.json'

$results = New-Object 'System.Collections.Generic.List[object]'
$removedCount = 0
$missing = @()

foreach ($relativePath in $plan.files) {
    $fullPath = Join-Path $WorkspaceRoot $relativePath
    if (-not (Test-Path -LiteralPath $fullPath)) {
        $missing += $relativePath
        $results.Add([pscustomobject]@{
            path = $relativePath
            status = 'missing'
            removed = $false
        }) | Out-Null
        continue
    }

    if ($DryRun) {
        $results.Add([pscustomobject]@{
            path = $relativePath
            status = 'dry-run'
            removed = $false
        }) | Out-Null
        continue
    }

    if ($PSCmdlet.ShouldProcess($relativePath, 'Remove RuntimeHost evidence-backed internal service')) {
        Remove-Item -LiteralPath $fullPath -Force
        $removedCount++
        $results.Add([pscustomobject]@{
            path = $relativePath
            status = 'removed'
            removed = $true
        }) | Out-Null
    }
}

$report = [pscustomobject]@{
    generatedAt = (Get-Date).ToString('o')
    scriptPath = 'scripts\quality\apply-runtimehost-batch29-reduction.ps1'
    workspaceRoot = $WorkspaceRoot
    planPath = [System.IO.Path]::GetRelativePath($WorkspaceRoot, $PlanPath)
    dryRun = [bool]$DryRun
    totalPlanned = @($plan.files).Count
    removedCount = $removedCount
    missingCount = $missing.Count
    results = $results
    overallStatus = if ($missing.Count -eq 0) { 'passed' } else { 'failed' }
}

$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $outPath -Encoding UTF8

if ($missing.Count -gt 0) {
    throw "Batch 29 reduction aborted because one or more planned files were missing. See $outPath"
}

if ($DryRun) {
    Write-Host "OK    RuntimeHost Batch 29 dry-run completed. Report: $outPath"
} else {
    Write-Host "OK    RuntimeHost Batch 29 reduction applied. Removed $removedCount file(s). Report: $outPath"
}
