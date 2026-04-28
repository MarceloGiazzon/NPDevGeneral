[CmdletBinding(SupportsShouldProcess=$true)]
param(
    [string]$WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$PlanPath = (Join-Path $PSScriptRoot '..\policy\runtimehost-batch5-remove-list.json'),
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $PlanPath)) {
    throw "Removal plan not found: $PlanPath"
}

$plan = Get-Content -LiteralPath $PlanPath -Raw | ConvertFrom-Json -Depth 50
$outPath = Join-Path $WorkspaceRoot 'scripts\reports\out\runtimehost-batch5-reduction-report.json'

$results = New-Object 'System.Collections.Generic.List[object]'
$removedCount = 0
$missingCount = 0

foreach ($relativePath in $plan.files) {
    $fullPath = Join-Path $WorkspaceRoot $relativePath
    $exists = Test-Path -LiteralPath $fullPath
    if (-not $exists) {
        $missingCount++
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

    if ($PSCmdlet.ShouldProcess($relativePath, 'Remove RuntimeHost transitional UI controller')) {
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
    scriptPath = 'scripts\quality\apply-runtimehost-batch5-reduction.ps1'
    workspaceRoot = $WorkspaceRoot
    planPath = [System.IO.Path]::GetRelativePath($WorkspaceRoot, $PlanPath)
    dryRun = [bool]$DryRun
    totalPlanned = @($plan.files).Count
    removedCount = $removedCount
    missingCount = $missingCount
    results = $results
}

$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $outPath -Encoding UTF8

if ($DryRun) {
    Write-Host "OK    RuntimeHost Batch 5 dry-run completed. Report: $outPath"
} else {
    Write-Host "OK    RuntimeHost Batch 5 reduction applied. Removed $removedCount file(s). Report: $outPath"
}
