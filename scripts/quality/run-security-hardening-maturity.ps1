[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$WorkspaceRoot = (Resolve-Path $WorkspaceRoot).Path
$outDir = Join-Path $WorkspaceRoot 'scripts\reports\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$reportPath = Join-Path $outDir 'security-hardening-maturity-report.json'
$checkedAt = (Get-Date).ToString('o')

function New-Check {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Summary,
        [object]$Data = $null
    )

    [pscustomobject]@{
        name = $Name
        status = if ($Passed) { 'passed' } else { 'failed' }
        summary = $Summary
        data = $Data
        checkedAt = $checkedAt
    }
}

function Test-FileRelative {
    param([string]$RelativePath)
    return (Test-Path -LiteralPath (Join-Path $WorkspaceRoot $RelativePath) -PathType Leaf)
}

function Read-JsonRelative {
    param([string]$RelativePath)

    $path = Join-Path $WorkspaceRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return $null
    }

    return Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
}

$checks = New-Object 'System.Collections.Generic.List[object]'

$policyRel = 'scripts\policy\security-sensitive-field-inventory.json'
$policy = Read-JsonRelative $policyRel
$fieldCount = 0
if ($null -ne $policy) {
    if ($policy.fields) {
        $fieldCount = @($policy.fields).Count
    }
    elseif ($policy.sensitiveFields) {
        $fieldCount = @($policy.sensitiveFields).Count
    }
}

$checks.Add((New-Check `
    -Name 'sensitive-field-inventory-present' `
    -Passed ($null -ne $policy -and $fieldCount -gt 0) `
    -Summary 'Sensitive field inventory exists and contains fields.' `
    -Data ([pscustomobject]@{ policyPath = $policyRel; fieldCount = $fieldCount }))) | Out-Null

$authConfigRel = 'NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevAuthConfig.java'
$runtimeModeConfigRel = 'NPDevRuntimeHost\src\main\java\com\finalexec\config\NpdevRuntimeModeConfig.java'
$externalBetaRel = 'NPDevRuntimeHost\src\main\resources\application-external-beta.properties'

$checks.Add((New-Check `
    -Name 'runtimehost-auth-config-present' `
    -Passed (Test-FileRelative $authConfigRel) `
    -Summary 'RuntimeHost auth configuration exists.' `
    -Data ([pscustomobject]@{ path = $authConfigRel }))) | Out-Null

$checks.Add((New-Check `
    -Name 'runtimehost-mode-config-present' `
    -Passed (Test-FileRelative $runtimeModeConfigRel) `
    -Summary 'RuntimeHost runtime-mode configuration exists.' `
    -Data ([pscustomobject]@{ path = $runtimeModeConfigRel }))) | Out-Null

$checks.Add((New-Check `
    -Name 'external-beta-security-properties-present' `
    -Passed (Test-FileRelative $externalBetaRel) `
    -Summary 'External beta security properties exist.' `
    -Data ([pscustomobject]@{ path = $externalBetaRel }))) | Out-Null

$allowlistRel = 'scripts\reports\out\runtime-surface-allowlist-report.json'
$allowlist = Read-JsonRelative $allowlistRel
$allowlistPassed = ($null -ne $allowlist -and $allowlist.overallStatus -eq 'passed')
$checks.Add((New-Check `
    -Name 'runtime-surface-allowlist-current' `
    -Passed $allowlistPassed `
    -Summary 'Runtime surface allowlist evidence is current and passed.' `
    -Data ([pscustomobject]@{ reportPath = $allowlistRel; overallStatus = if ($null -eq $allowlist) { $null } else { $allowlist.overallStatus } }))) | Out-Null

$redactionFiles = @(Get-ChildItem -LiteralPath (Join-Path $WorkspaceRoot 'NPDevKernel') -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object {
        $_.FullName -match 'tracing-redaction-default' -and
        ($_.Extension -in @('.java', '.kt', '.json', '.md'))
    })

$checks.Add((New-Check `
    -Name 'redaction-evidence-present' `
    -Passed ($redactionFiles.Count -gt 0) `
    -Summary 'Tracing redaction implementation or tests are present.' `
    -Data ([pscustomobject]@{
        fileCount = $redactionFiles.Count
        sample = @($redactionFiles | Select-Object -First 10 | ForEach-Object { [System.IO.Path]::GetRelativePath($WorkspaceRoot, $_.FullName) })
    }))) | Out-Null

$failed = @($checks | Where-Object { $_.status -ne 'passed' })
$overallStatus = if ($failed.Count -eq 0) { 'passed' } else { 'failed' }

$report = [pscustomobject]@{
    generatedAt = $checkedAt
    runId = 'security-hardening-maturity-' + (Get-Date).ToString('yyyyMMdd-HHmmss')
    scriptPath = 'scripts\quality\run-security-hardening-maturity.ps1'
    workspaceRoot = $WorkspaceRoot
    overallStatus = $overallStatus
    checks = $checks
    summary = [pscustomobject]@{
        total = $checks.Count
        passed = @($checks | Where-Object { $_.status -eq 'passed' }).Count
        failed = $failed.Count
        warnings = 0
    }
}

$report | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $reportPath -Encoding UTF8

if ($overallStatus -ne 'passed') {
    Write-Host 'WARN  Security hardening maturity evidence failed.'
    Write-Host ('Report: ' + $reportPath)
    throw 'Security hardening maturity evidence failed.'
}

Write-Host ('OK    Security hardening maturity evidence generated: ' + $reportPath)
