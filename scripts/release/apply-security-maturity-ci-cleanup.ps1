[CmdletBinding()]
param(
    [string]$WorkspaceRoot = 'D:\WorkSpace\NPDev_General',
    [string]$CommitMessage = 'Add security maturity evidence to CI validation',
    [switch]$SkipPush
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Step {
    param([string]$Message)
    Write-Host ''
    Write-Host ('== ' + $Message + ' ==') -ForegroundColor Cyan
}

function Invoke-RepoGit {
    param([Parameter(Mandatory = $true)][string[]]$Args)
    & git @Args
    if ($LASTEXITCODE -ne 0) {
        throw ("git failed with exit code {0}: git {1}" -f $LASTEXITCODE, ($Args -join ' '))
    }
}

$WorkspaceRoot = (Resolve-Path $WorkspaceRoot).Path
Set-Location $WorkspaceRoot

Step 'Validate clean repository'
$branch = (& git branch --show-current).Trim()
if ($branch -ne 'main') {
    throw "Expected branch main. Current branch: $branch"
}

$status = git status --short
if (-not [string]::IsNullOrWhiteSpace(($status -join "`n"))) {
    Write-Host 'Working tree is not clean:'
    Write-Host ($status -join "`n")
    throw 'Commit/revert current changes before applying security maturity CI cleanup.'
}

Step 'Create security hardening maturity evidence runner'
$scriptPath = Join-Path $WorkspaceRoot 'scripts\evidence\write-security-hardening-maturity-report.ps1'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $scriptPath) | Out-Null

@'
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
    scriptPath = 'scripts\evidence\write-security-hardening-maturity-report.ps1'
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
'@ | Set-Content -LiteralPath $scriptPath -Encoding UTF8

Step 'Patch segmented CI workflow'
$workflowPath = Join-Path $WorkspaceRoot '.github\workflows\npdev-ci-validation.yml'
if (-not (Test-Path -LiteralPath $workflowPath)) {
    throw "Workflow not found: $workflowPath"
}

$workflow = Get-Content -LiteralPath $workflowPath -Raw

if ($workflow -notmatch 'name:\s*Security hardening maturity evidence') {
    $securityStep = @'
      - name: Security hardening maturity evidence
        timeout-minutes: 10
        run: |
          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\evidence\write-security-hardening-maturity-report.ps1" `
            -WorkspaceRoot "$env:NPDEV_WORKSPACE"

      - name: Runtime security consistency evidence
        timeout-minutes: 10
        run: |
          & pwsh -NoProfile -ExecutionPolicy Bypass `
            -File "$env:NPDEV_WORKSPACE\scripts\quality\run-runtime-security-consistency.ps1" `
            -WorkspaceRoot "$env:NPDEV_WORKSPACE"

'@

    $marker = '      - name: RuntimeHost gate'
    if ($workflow -notlike "*$marker*") {
        throw "RuntimeHost gate marker not found in workflow."
    }

    $workflow = $workflow.Replace($marker, $securityStep + $marker)
    Set-Content -LiteralPath $workflowPath -Value $workflow -Encoding UTF8
}

Step 'Run local evidence checks'
& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File $scriptPath -WorkspaceRoot $WorkspaceRoot
if ($LASTEXITCODE -ne 0) {
    throw 'Local security hardening maturity evidence failed.'
}

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File (Join-Path $WorkspaceRoot 'scripts\quality\run-runtime-surface-evidence.ps1') -WorkspaceRoot $WorkspaceRoot
if ($LASTEXITCODE -ne 0) {
    throw 'Local runtime surface evidence failed.'
}

& 'C:\Program Files (x86)\PowerShell\7\pwsh.exe' -NoProfile -ExecutionPolicy Bypass -File (Join-Path $WorkspaceRoot 'scripts\quality\run-runtime-security-consistency.ps1') -WorkspaceRoot $WorkspaceRoot
if ($LASTEXITCODE -ne 0) {
    throw 'Local runtime security consistency failed.'
}

Step 'Commit and push'
Invoke-RepoGit @('add', 'scripts/quality/run-security-hardening-maturity.ps1', '.github/workflows/npdev-ci-validation.yml')
Invoke-RepoGit @('commit', '-m', $CommitMessage)

if (-not $SkipPush) {
    Git @('push', 'origin', 'main')
}

Write-Host ''
Write-Host 'OK    Security maturity CI cleanup applied.'
Write-Host '      Watch NPDev CI Validation in GitHub Actions.'


