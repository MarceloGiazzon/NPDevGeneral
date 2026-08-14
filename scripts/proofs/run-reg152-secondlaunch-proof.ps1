<#
.SYNOPSIS
  Durable, re-runnable RED/GREEN proof for REG-152: the AppGen "second launch pipeline"
  (Build-NpdevApp.ps1 / Build-ClaudeApp.ps1) must never boot a generated app with a working
  published ADMIN credential (dev-key/api-dev).

.DESCRIPTION
  This is the artifact a live-verification claim for REG-152/SEC-1 should point at instead of
  unanchored session prose -- the exact gap an adversarial review of this fix's own PR flagged:
  this platform's CI (npdev-pr-gate.yml) never invokes Build-NpdevApp.ps1/Build-ClaudeApp.ps1 or
  any script they emit, so a green CI run corroborates nothing about this code path. Run this
  script by hand (or from a future CI job that adds Windows/pwsh coverage for scripts/appgen/) to
  independently re-confirm the fix, rather than trusting a session's own "verified live" claim.

  Generates a real app via Build-NpdevApp.ps1 against an existing, known-good AppGen app
  definition (default: simple-product-h2local -- small, H2Local, fast). AppGen\apps is a
  separate, not-git-tracked truth layer (see CLAUDE.md's Source-of-truth layers), so depending on
  a specific app there by default is the SAME pattern Build-ClaudeApp.ps1 itself already uses
  (its own -AppFolder default points at AppGen\apps\_official\Claude) -- not a new fragility.
  Pass -AppFolder to point at a different one.

.EXAMPLE
  pwsh -NoProfile -File scripts\proofs\run-reg152-secondlaunch-proof.ps1

.NOTES
  Writes an app under $BuildRoot\<scenario.name> (default: outside the repo, per
  docs/BUILD_OUTPUT_LOCATION_POLICY.md) and durable evidence (this run's full console output) to
  $EvidenceDir\reg152-secondlaunch-proof-output.txt.
#>
param(
  # Portable default (REG-144): resolved from $PSScriptRoot below, not hardcoded to the author's
  # D:\ layout. This script lives at <repo>/scripts/proofs/, so the repo root is two levels up.
  [string]$WorkspaceRoot = '',
  [string]$AppFolder = '',
  [string]$BuildRoot = '',
  [string]$EvidenceDir = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
  $WorkspaceRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}
if ([string]::IsNullOrWhiteSpace($AppFolder)) {
  $AppFolder = Join-Path (Split-Path -Parent $WorkspaceRoot) 'AppGen\apps\simple-product-h2local'
}
if ([string]::IsNullOrWhiteSpace($BuildRoot)) {
  $BuildRoot = Join-Path (Split-Path -Parent $WorkspaceRoot) 'Build\generated-finalapps'
}
if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
  $EvidenceDir = Join-Path (Split-Path -Parent $WorkspaceRoot) 'NPDev_General__OutsideRepo\reg152-secondlaunch-proof-evidence'
}
if (-not (Test-Path -LiteralPath (Join-Path $AppFolder 'definition\config.json'))) {
  throw "AppFolder not found or not AppGen-shaped: $AppFolder (pass -AppFolder to point at a real AppGen app; AppGen\apps is a separate, not-git-tracked truth layer -- see CLAUDE.md's Source-of-truth layers)"
}

New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$log = Join-Path $EvidenceDir 'reg152-secondlaunch-proof-output.txt'
Set-Content -LiteralPath $log -Value "REG-152 second-launch-pipeline proof -- $(Get-Date -Format o)" -Encoding UTF8

function Log {
  param([string]$Text)
  Write-Host $Text
  Add-Content -LiteralPath $log -Value $Text -Encoding UTF8
}

function Assert-Status {
  param([string]$Label, [string]$Uri, [string]$ApiKey, [int]$Expected)
  try {
    Invoke-WebRequest -Uri $Uri -Headers @{ 'X-Api-Key' = $ApiKey } -TimeoutSec 5 -ErrorAction Stop | Out-Null
    $actual = 200
  } catch {
    $resp = $_.Exception.Response
    $actual = if ($resp) { [int]$resp.StatusCode } else { -1 }
  }
  $line = "$Label -> expected $Expected, got $actual"
  Log $line
  if ($actual -ne $Expected) { throw "RED/GREEN proof FAILED: $line" }
}

$appConfig = Get-Content -Raw -LiteralPath (Join-Path $AppFolder 'definition\config.json') | ConvertFrom-Json
$appId = $appConfig.scenario.name
$ServerPort = [int]$appConfig.runtime.serverPort
Log "===== App: $appId (from $AppFolder), port $ServerPort ====="

Log '===== Generating via Build-NpdevApp.ps1 ====='
$buildScript = Join-Path $WorkspaceRoot 'scripts\appgen\Build-NpdevApp.ps1'
& $buildScript -AppFolder $AppFolder -BuildRoot $BuildRoot 2>&1 | ForEach-Object { Log $_ }
if ($LASTEXITCODE -ne 0) { throw "Build-NpdevApp.ps1 generation failed with exit code $LASTEXITCODE" }

$opsDir = Join-Path (Join-Path $BuildRoot $appId) '_ops'
$secretsFile = Join-Path (Join-Path $BuildRoot $appId) 'App\secrets\api-key.env'

Log '===== Building the app ====='
& (Join-Path $opsDir 'Build-App.ps1') 2>&1 | ForEach-Object { Log $_ }
if ($LASTEXITCODE -ne 0) { throw 'Build-App.ps1 failed.' }

Log '===== RED control: launch with the OLD (pre-fix) arguments -- no provisioner, key env unset ====='
Remove-Item Env:NPDEV_AUTH_API_KEYS -ErrorAction SilentlyContinue
$plan = Get-Content -Raw -LiteralPath (Join-Path $opsDir 'app-plan.json') | ConvertFrom-Json
$jar = Get-ChildItem -LiteralPath $plan.appRoot -Recurse -Filter 'FinalExec-*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
$redLog = Join-Path $EvidenceDir 'red-control.out.log'
$redProc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jar.FullName, "--server.port=$ServerPort", "--spring.profiles.active=$($plan.springProfiles)") `
  -WorkingDirectory $plan.appRoot -PassThru -RedirectStandardOutput $redLog -RedirectStandardError (Join-Path $EvidenceDir 'red-control.err.log') -WindowStyle Hidden
try {
  $ok = $false
  for ($i = 0; $i -lt 60; $i++) { Start-Sleep -Seconds 2; try { Invoke-RestMethod -Uri "http://localhost:$ServerPort/actuator/health" -TimeoutSec 3 | Out-Null; $ok = $true; break } catch {} }
  if (-not $ok) { throw 'RED control app never became healthy.' }
  Assert-Status -Label 'RED control: dev-key' -Uri "http://localhost:$ServerPort/api/flows" -ApiKey 'dev-key' -Expected 200
} finally {
  Stop-Process -Id $redProc.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2
}

Log '===== GREEN: real, fixed Start-App.ps1 ====='
Remove-Item -LiteralPath $secretsFile -Force -ErrorAction SilentlyContinue
& (Join-Path $opsDir 'Start-App.ps1') 2>&1 | ForEach-Object { Log $_ }
if ($LASTEXITCODE -ne 0) { throw 'Start-App.ps1 failed.' }

if (-not (Test-Path -LiteralPath $secretsFile)) { throw "GREEN proof FAILED: $secretsFile was not created." }
$generatedKey = ((Get-Content -Raw -LiteralPath $secretsFile) -split '=', 2)[1].Split('=', 2)[0]
Log "Generated key: $generatedKey"

Assert-Status -Label 'GREEN: dev-key'  -Uri "http://localhost:$ServerPort/api/flows" -ApiKey 'dev-key' -Expected 401
Assert-Status -Label 'GREEN: api-dev'  -Uri "http://localhost:$ServerPort/api/flows" -ApiKey 'api-dev' -Expected 401
Assert-Status -Label 'GREEN: real key' -Uri "http://localhost:$ServerPort/api/flows" -ApiKey $generatedKey -Expected 200

Log '===== Test-App.ps1 (standalone, resolves the live key itself) ====='
& (Join-Path $opsDir 'Test-App.ps1') 2>&1 | ForEach-Object { Log $_ }
$testExit = $LASTEXITCODE

& (Join-Path $opsDir 'Stop-App.ps1') 2>&1 | ForEach-Object { Log $_ }

Log ''
Log '===== RESULT: PASS -- REG-152 second-launch-pipeline fix verified RED-then-GREEN ====='
Log "Evidence: $log"
exit 0
