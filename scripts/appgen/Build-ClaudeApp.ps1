<#
.SYNOPSIS
  One-command builder for the Claude Support Desk NPDev sample.

.DESCRIPTION
  This is the single orchestration script for the sample. It:
    1. Validates the app definition (config.json, model.json, capabilities, inputs).
    2. Stages the definition (model + capabilities bundle side by side, as the
       generator expects) into the output root.
    3. Patches the staged config/model to absolute, outside-repo output paths.
    4. Calls the prepared NPDev generator runtime to produce ArtifactNP + the final App.
    5. Syncs the RuntimeHost libs into the generated app so it can actually compile.
    6. Writes a self-contained _ops toolbox next to the app:
         Build-App.ps1   - compile the generated app into a runnable jar
         Start-App.ps1   - start the app in the background
         Stop-App.ps1    - stop the running app
         Status-App.ps1  - report whether the app is up
         Test-App.ps1    - REST smoke test across the showcase flows
         Pack-App.ps1     - zip the definition + generated app for sharing
    7. Writes RUN_COMMANDS.md and a JSON report.

  No Gradle is invoked by this script for generation; generation is a direct Java
  classpath call inside invoke-npdev-generator.ps1. Gradle is only used later by
  Build-App.ps1 to compile the generated app.

.EXAMPLE
  .\Build-ClaudeApp.ps1
      Generate the app, sync libs, and emit the _ops toolbox.

.EXAMPLE
  .\Build-ClaudeApp.ps1 -GenerateOnly
      Only run the generator (fast feedback that the model is accepted); skip libs sync.

.NOTES
  Output (never written inside the NPDev_General source repo):
    D:\WorkSpace\NPDev\Build\generated-finalapps\claude-support-desk
#>
[CmdletBinding()]
param(
  [string]$AppFolder = 'D:\WorkSpace\NPDev\AppGen\apps\_official\Claude',
  [string]$ProductRepo = 'D:\WorkSpace\NPDev\NPDev_General',
  [string]$RuntimeCurrent = 'D:\WorkSpace\NPDev\AppGen\generator-runtime\current',
  [string]$OutRoot = 'D:\WorkSpace\NPDev\Build\generated-finalapps\claude-support-desk',
  [int]$ServerPort = 8090,
  [string]$ApiKey = 'dev-key',
  [string]$SpringProfiles = 'dev,step0,trial',
  [string]$RuntimeHostLibsDir = 'D:\WorkSpace\NPDev\Build\runtimehost-libs',
  [switch]$GenerateOnly,
  [switch]$SkipRuntimeHostLibs
)

$ErrorActionPreference = 'Stop'

function Write-Step {
  param([string]$Message)
  Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $Message"
}

function Read-JsonFile {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { throw "JSON file not found: $Path" }
  Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}

function Write-JsonFile {
  param([object]$Value, [string]$Path)
  $parent = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
  }
  $Value | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Set-JsonProp {
  param([object]$Object, [string]$Name, [object]$Value)
  if ($null -ne $Object) {
    $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force
  }
}

# ----------------------------------------------------------------------------
# 1. Resolve and validate inputs
# ----------------------------------------------------------------------------
$Definition = Join-Path $AppFolder 'definition'
$ConfigSrc  = Join-Path $Definition 'config.json'
$ModelSrc   = Join-Path $Definition 'model.json'

foreach ($p in @($AppFolder, $ProductRepo, $RuntimeCurrent, $Definition, $ConfigSrc, $ModelSrc)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "Required path not found: $p" }
}

$RuntimeManifest = Join-Path $RuntimeCurrent 'generator-runtime-manifest.json'
$RuntimeInvoker  = Join-Path $RuntimeCurrent 'invoke-npdev-generator.ps1'
if (-not (Test-Path -LiteralPath $RuntimeManifest)) {
  throw "Generator runtime is not prepared (missing $RuntimeManifest). Run prepare-npdev-generator-runtime.ps1 first."
}
if (-not (Test-Path -LiteralPath $RuntimeInvoker)) {
  throw "Generator runtime is not prepared (missing $RuntimeInvoker)."
}
$RuntimeInfo = Read-JsonFile $RuntimeManifest
if ($RuntimeInfo.complete -ne $true) {
  throw "Generator runtime manifest says runtime is incomplete: $RuntimeManifest"
}

$RuntimeHostTemplate = Join-Path $ProductRepo 'NPDevRuntimeHost'
$MigrationsDir       = Join-Path $ProductRepo 'NPDevGenerator\db-history\src\main\resources\db\migration'
$ContractSchemas     = Join-Path $ProductRepo 'NPDevContract\schemas'
foreach ($p in @($RuntimeHostTemplate, $MigrationsDir, $ContractSchemas)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "Required product-repo path not found: $p" }
}

Write-Step "App folder       : $AppFolder"
Write-Step "Product repo     : $ProductRepo"
Write-Step "Generator runtime: $RuntimeCurrent"
Write-Step "Output root      : $OutRoot"

# ----------------------------------------------------------------------------
# 2. Stage definition (model + capabilities bundle side by side)
# ----------------------------------------------------------------------------
if (Test-Path -LiteralPath $OutRoot) {
  Write-Step "Removing existing output root: $OutRoot"
  Remove-Item -LiteralPath $OutRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutRoot '_logs') | Out-Null

$StagedInput = Join-Path $OutRoot 'Input'
Write-Step "Staging definition into: $StagedInput"
New-Item -ItemType Directory -Force -Path $StagedInput | Out-Null
Get-ChildItem -LiteralPath $Definition -Force | ForEach-Object {
  Copy-Item -LiteralPath $_.FullName -Destination $StagedInput -Recurse -Force
}

$ConfigPath       = Join-Path $StagedInput 'config.json'
$ModelPath        = Join-Path $StagedInput 'model.json'
$DbDefinitionPath = Join-Path $StagedInput 'db.definition.json'
if (-not (Test-Path -LiteralPath $DbDefinitionPath)) {
  throw "db.definition.json not found in staged input: $DbDefinitionPath"
}

# ----------------------------------------------------------------------------
# 3. Patch staged config/model to absolute outside-repo paths
# ----------------------------------------------------------------------------
Write-Step 'Patching staged config/model paths for outside-repo output.'
$Config = Read-JsonFile $ConfigPath
$Model  = Read-JsonFile $ModelPath
$ConsoleMode = if ($Config.console -and $Config.console.mode) { "$($Config.console.mode)" } else { 'none' }

$ArtifactRoot = Join-Path $OutRoot 'ArtifactNP'
$FinalAppRoot = Join-Path $OutRoot 'App'

Set-JsonProp $Config '$schema' (Join-Path $ContractSchemas 'config.schema.json')
Set-JsonProp $Config.scenario 'outputRoot' $OutRoot
Set-JsonProp $Config.bootstrap 'root' $RuntimeHostTemplate
Set-JsonProp $Config.artifact 'root' $ArtifactRoot
Set-JsonProp $Config.finalExec 'root' $FinalAppRoot
if ($Config.runtime) { Set-JsonProp $Config.runtime 'serverPort' $ServerPort }
if ($Config.trialDefaults) {
  Set-JsonProp $Config.trialDefaults 'apiKey' $ApiKey
  Set-JsonProp $Config.trialDefaults 'pluginDiscoveryMode' 'filesystem-folder'
  Set-JsonProp $Config.trialDefaults 'pluginPackageDirectory' (Join-Path $ArtifactRoot 'npdev-generated\src\main\resources\npdev\plugin-packages')
}
Set-JsonProp $Model '$schema' (Join-Path $ContractSchemas 'model.schema.json')
# 'console' is AppGen-only; generator config schema is additionalProperties:false -> strip it.
if ($Config.PSObject.Properties.Name -contains 'console') { $Config.PSObject.Properties.Remove('console') }

Write-JsonFile $Config $ConfigPath
Write-JsonFile $Model $ModelPath

# ----------------------------------------------------------------------------
# 4. Call the prepared generator runtime
# ----------------------------------------------------------------------------
Write-Step 'Calling prepared NPDev generator runtime (direct Java; no Gradle).'
$InvokeArgs = @{
  ConfigPath          = $ConfigPath
  ModelPath           = $ModelPath
  OutRoot             = $OutRoot
  DbDefinitionPath    = $DbDefinitionPath
  RuntimeHostTemplate = $RuntimeHostTemplate
  Clean               = $true
}
& $RuntimeInvoker @InvokeArgs
$GeneratorExit = $LASTEXITCODE
if ($GeneratorExit -ne 0) {
  Write-Host ''
  Write-Host "Generator FAILED with exit code $GeneratorExit. See $OutRoot\_logs\generator-direct-java.log" -ForegroundColor Red
  exit $GeneratorExit
}
Write-Step 'Generator succeeded.'

# ----------------------------------------------------------------------------
# 5. Resolve the RuntimeHost libs the generated app compiles against.
#
# The generated app's build.gradle resolves its RuntimeHost jars from the gradle
# property 'npdevRuntimeHostLibsDir' (or env NPDEV_RUNTIMEHOST_LIBS_DIR), falling
# back to an <appRoot>__OutsideRepo/runtimehost-libs folder. Rather than rebuild
# the kernel/generator source jars on every run, we point the build at the
# already-staged workspace libs folder and validate it is complete.
# ----------------------------------------------------------------------------
$GeneratedAppRoot = Join-Path $OutRoot 'App'
$LibsResult = [ordered]@{ status = 'skipped'; reason = ''; libsDir = $RuntimeHostLibsDir }

if ($GenerateOnly -or $SkipRuntimeHostLibs) {
  $LibsResult.reason = if ($GenerateOnly) { 'GenerateOnly switch.' } else { 'SkipRuntimeHostLibs switch.' }
  Write-Step "Skipping RuntimeHost libs resolution ($($LibsResult.reason))"
}
else {
  Write-Step "Resolving RuntimeHost libs from: $RuntimeHostLibsDir"
  $libsManifest = Join-Path $RuntimeHostLibsDir 'runtimehost-libs-manifest.json'
  if (-not (Test-Path -LiteralPath $RuntimeHostLibsDir)) {
    throw "RuntimeHostLibsDir not found: $RuntimeHostLibsDir. Stage it with scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars, or pass -RuntimeHostLibsDir."
  }
  if (-not (Test-Path -LiteralPath $libsManifest)) {
    throw "RuntimeHost libs manifest not found: $libsManifest"
  }
  $m = Read-JsonFile $libsManifest
  $required = @($m.requiredStagedJars)
  $missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $RuntimeHostLibsDir $_)) })
  if ($missing.Count -gt 0) {
    throw "RuntimeHost libs are incomplete in $RuntimeHostLibsDir. Missing: $($missing -join ', ')"
  }
  $LibsResult = [ordered]@{
    status     = 'resolved'
    reason     = ''
    libsDir    = $RuntimeHostLibsDir
    jarCount   = (@(Get-ChildItem -LiteralPath $RuntimeHostLibsDir -Filter *.jar).Count)
    requiredOk = $required.Count
  }
  Write-Step "RuntimeHost libs resolved ($($LibsResult.jarCount) jars, $($required.Count) required present)."
}

# ----------------------------------------------------------------------------
# 6. Emit the _ops toolbox (build / start / stop / status / test / pack)
# ----------------------------------------------------------------------------
$OpsDir = Join-Path $OutRoot '_ops'
New-Item -ItemType Directory -Force -Path $OpsDir | Out-Null

# A tiny plan file the ops scripts read, so they stay parameter-free to run.
$Plan = [ordered]@{
  appId              = 'claude-support-desk'
  appName            = 'Claude Support Desk'
  outRoot            = $OutRoot
  appRoot            = $GeneratedAppRoot
  serverPort         = $ServerPort
  apiKey             = $ApiKey
  springProfiles     = $SpringProfiles
  baseUrl            = "http://localhost:$ServerPort"
  jarGlob            = 'build\libs\FinalExec-*.jar'
  runtimeHostLibsDir = $RuntimeHostLibsDir
}
Write-JsonFile $Plan (Join-Path $OpsDir 'app-plan.json')

$BuildApp = @'
param([switch]$Force)
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json

# Pre-flight: a running instance locks build\libs\FinalExec-*.jar and would make
# gradle ':clean' fail. Detect it (listener on the app port and/or app.pid), warn,
# and let the operator choose to stop it or cancel. Use -Force to stop without asking.
$running = @()
$listeners = Get-NetTCPConnection -LocalPort $plan.serverPort -State Listen -ErrorAction SilentlyContinue
if ($listeners) { $running += ($listeners | Select-Object -ExpandProperty OwningProcess) }
$pidFile = Join-Path $PSScriptRoot 'app.pid'
if (Test-Path -LiteralPath $pidFile) {
  $fp = 0; [void][int]::TryParse((Get-Content -Raw -LiteralPath $pidFile).Trim(), [ref]$fp)
  if ($fp -and (Get-Process -Id $fp -ErrorAction SilentlyContinue)) { $running += $fp }
}
$running = @($running | Sort-Object -Unique)
if ($running.Count -gt 0) {
  Write-Host ""
  Write-Host "WARNING: $($plan.appName) appears to be RUNNING (PID $($running -join ', '), port $($plan.serverPort))." -ForegroundColor Yellow
  Write-Host "A build now would fail at ':clean' because the running app locks FinalExec-*.jar." -ForegroundColor Yellow
  $stopIt = $false
  if ($Force) { $stopIt = $true; Write-Host "-Force: stopping the running app." }
  elseif ([Environment]::UserInteractive) {
    $ans = Read-Host "Stop the running app and continue the build? [y/N]"
    $stopIt = ($ans -match '^(y|yes)$')
  } else {
    Write-Host "Non-interactive shell: re-run with -Force to stop it automatically. Cancelling." -ForegroundColor Red
    exit 2
  }
  if (-not $stopIt) { Write-Host "Build cancelled - the app is still running." -ForegroundColor Yellow; exit 2 }
  $stopApp = Join-Path $PSScriptRoot 'Stop-App.ps1'
  if (Test-Path -LiteralPath $stopApp) { & $stopApp }
  foreach ($procId in $running) { Get-Process -Id $procId -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
}

Set-Location $plan.appRoot
Write-Host "Building $($plan.appName) at $($plan.appRoot)"
Write-Host "RuntimeHost libs: $($plan.runtimeHostLibsDir)"
$env:NPDEV_RUNTIMEHOST_LIBS_DIR = $plan.runtimeHostLibsDir
# REG-11: pick the OS-appropriate wrapper so this builder runs on Linux/macOS CI too. Mirrors
# scripts/npdev-common.ps1's Get-NPDevGradleWrapperExecutable inline rather than dot-sourcing that
# file, which sets `Set-StrictMode -Version Latest` at file scope and would impose strict mode on
# this legacy builder that was never written for it. Generated apps always ship both wrappers.
$gradleWrapper = if ($IsWindows) { Join-Path $plan.appRoot 'gradlew.bat' } else { Join-Path $plan.appRoot 'gradlew' }
& $gradleWrapper --no-daemon --console=plain "-PnpdevRuntimeHostLibsDir=$($plan.runtimeHostLibsDir)" clean build -x test
if ($LASTEXITCODE -ne 0) { Write-Host 'Build FAILED.' -ForegroundColor Red; exit $LASTEXITCODE }
$jar = Get-ChildItem -LiteralPath $plan.appRoot -Recurse -Filter 'FinalExec-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.FullName -like '*\build\libs\*' -and $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
if ($null -eq $jar) { Write-Host 'Build succeeded but runnable jar was not found.' -ForegroundColor Yellow; exit 1 }
Write-Host "Build OK. Runnable jar: $($jar.FullName)"
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Build-App.ps1') -Value $BuildApp -Encoding UTF8

$StartApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$jar = Get-ChildItem -LiteralPath $plan.appRoot -Recurse -Filter 'FinalExec-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.FullName -like '*\build\libs\*' -and $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
if ($null -eq $jar) { Write-Host 'Runnable jar not found. Run Build-App.ps1 first.' -ForegroundColor Red; exit 1 }
$pidFile = Join-Path $PSScriptRoot 'app.pid'
$logFile = Join-Path $PSScriptRoot 'app.out.log'
if (Test-Path -LiteralPath $pidFile) {
  $old = Get-Process -Id ([int](Get-Content -Raw -LiteralPath $pidFile)) -ErrorAction SilentlyContinue
  if ($null -ne $old) { Write-Host "Already running (PID $($old.Id)). Stop it first (Stop-App.ps1)."; exit 0 }
}
$portBusy = Get-NetTCPConnection -LocalPort $plan.serverPort -State Listen -ErrorAction SilentlyContinue
if ($portBusy) { Write-Host "Port $($plan.serverPort) is already in use (PID $(($portBusy.OwningProcess | Sort-Object -Unique) -join ', ')). Stop that first (Stop-App.ps1) to avoid a duplicate." -ForegroundColor Yellow; exit 0 }
Write-Host "Starting $($plan.appName) on $($plan.baseUrl) (profiles: $($plan.springProfiles))"
$args = @('-jar', $jar.FullName, "--server.port=$($plan.serverPort)", "--spring.profiles.active=$($plan.springProfiles)")
$proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.appRoot -PassThru -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $PSScriptRoot 'app.err.log') -WindowStyle Hidden
$proc.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
Write-Host "Started PID $($proc.Id). Logs: $logFile"
Write-Host "Waiting for health..."
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  try {
    Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/api/flows" -Headers @{ 'X-Api-Key' = $plan.apiKey } -TimeoutSec 3 | Out-Null
    $ok = $true; break
  } catch { }
}
if ($ok) { Write-Host "App is UP at $($plan.baseUrl)/api/flows" } else { Write-Host 'App did not report healthy in time; check logs.' -ForegroundColor Yellow }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Start-App.ps1') -Value $StartApp -Encoding UTF8

$StopApp = @'
$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $PSScriptRoot 'app.pid'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Host 'No app.pid found; nothing to stop.'; exit 0 }
$procId = [int](Get-Content -Raw -LiteralPath $pidFile)
$proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
if ($null -ne $proc) { Stop-Process -Id $procId -Force; Write-Host "Stopped PID $procId." }
else { Write-Host "Process $procId was not running." }
Remove-Item -LiteralPath $pidFile -Force
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Stop-App.ps1') -Value $StopApp -Encoding UTF8

$StatusApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$pidFile = Join-Path $PSScriptRoot 'app.pid'
$running = $false
if (Test-Path -LiteralPath $pidFile) {
  $proc = Get-Process -Id ([int](Get-Content -Raw -LiteralPath $pidFile)) -ErrorAction SilentlyContinue
  $running = ($null -ne $proc)
}
try {
  Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/api/flows" -Headers @{ 'X-Api-Key' = $plan.apiKey } -TimeoutSec 3 | Out-Null
  Write-Host "UP   - $($plan.baseUrl)/api/flows reachable (pidFileRunning=$running)"
} catch {
  Write-Host "DOWN - $($plan.baseUrl)/api/flows not reachable (pidFileRunning=$running)"
}
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Status-App.ps1') -Value $StatusApp -Encoding UTF8

$TestApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$inputDir = Join-Path (Split-Path -Parent $PSScriptRoot) 'Input\input'
$base = $plan.baseUrl
$headers = @{ 'X-Api-Key' = $plan.apiKey }
$report = [ordered]@{ appId = $plan.appId; baseUrl = $base; steps = @(); status = 'FAIL' }

function Invoke-Flow {
  param([string]$Flow, [string]$PayloadFile)
  $body = Get-Content -Raw -LiteralPath (Join-Path $inputDir $PayloadFile)
  $uri = "$base/api/flows/$Flow/execute"
  $res = Invoke-RestMethod -Method POST -Uri $uri -Headers $headers -ContentType 'application/json' -Body $body
  return $res
}

try {
  $flows = Invoke-RestMethod -Method GET -Uri "$base/api/flows" -Headers $headers
  $report.steps += @{ step = 'GET /api/flows'; ok = $true; flows = $flows }

  $ws = Invoke-Flow -Flow 'CreateWorkspace' -PayloadFile 'create-workspace.json'
  $report.steps += @{ step = 'CreateWorkspace'; ok = $true; result = $ws }

  $agent = Invoke-Flow -Flow 'RegisterAgent' -PayloadFile 'register-agent.json'
  $report.steps += @{ step = 'RegisterAgent'; ok = $true; result = $agent }

  $triage = Invoke-Flow -Flow 'TriageTicket' -PayloadFile 'triage-ticket.json'
  $isUserJava = ($triage.output.source -eq 'user-java') -or ($triage.source -eq 'user-java')
  $report.steps += @{ step = 'TriageTicket (custom Java capability)'; ok = $true; userJava = $isUserJava; result = $triage }

  $low = Invoke-Flow -Flow 'SubmitTicket' -PayloadFile 'submit-ticket.json'
  $report.steps += @{ step = 'SubmitTicket (low priority)'; ok = $true; result = $low }

  try {
    $audit = Invoke-RestMethod -Method GET -Uri "$base/api/audit" -Headers $headers
    $report.steps += @{ step = 'GET /api/audit'; ok = $true; auditCount = (@($audit).Count) }
  } catch {
    $report.steps += @{ step = 'GET /api/audit'; ok = $false; error = $_.Exception.Message }
  }

  $report.status = 'PASS'
} catch {
  $report.steps += @{ step = 'ERROR'; ok = $false; error = $_.Exception.Message }
}

$reportPath = Join-Path $PSScriptRoot 'smoke-test-report.json'
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $reportPath -Encoding UTF8
Write-Host "Smoke test report: $reportPath"
Write-Host "Status: $($report.status)"
if ($report.status -ne 'PASS') { exit 1 }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Test-App.ps1') -Value $TestApp -Encoding UTF8

# Make the generator's older convenience scripts delegate to the guarded ops scripts,
# so running either name (Build-FinalApp.ps1 / Run-FinalApp.ps1) is safe.
$buildShim = "# Deprecated name -> delegates to the guarded Build-App.ps1 (detects a running app first).`n& (Join-Path `$PSScriptRoot 'Build-App.ps1') @args`nexit `$LASTEXITCODE`n"
$runShim   = "# Deprecated name -> delegates to Start-App.ps1 (detects a running app first).`n& (Join-Path `$PSScriptRoot 'Start-App.ps1') @args`nexit `$LASTEXITCODE`n"
foreach ($shimPair in @(@('Build-FinalApp.ps1', $buildShim), @('Run-FinalApp.ps1', $runShim))) {
  $shimTarget = Join-Path $OpsDir $shimPair[0]
  if (Test-Path -LiteralPath $shimTarget) { Set-Content -LiteralPath $shimTarget -Value $shimPair[1] -Encoding UTF8 }
}

$PackApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$stamp = Get-Date -Format 'dd-MM-yyyy_HHmmss'
$zipName = "app_Claude_Support-Desk_${stamp}.zip"
$zipPath = Join-Path $plan.outRoot $zipName
$staging = Join-Path $env:TEMP "claude_pack_$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $staging | Out-Null
Copy-Item -LiteralPath (Join-Path $plan.outRoot 'Input') -Destination (Join-Path $staging 'definition') -Recurse -Force
Copy-Item -LiteralPath $PSScriptRoot -Destination (Join-Path $staging '_ops') -Recurse -Force
$appBuild = Join-Path $plan.appRoot 'build\libs'
if (Test-Path -LiteralPath $appBuild) {
  New-Item -ItemType Directory -Force -Path (Join-Path $staging 'app-libs') | Out-Null
  Get-ChildItem -LiteralPath $appBuild -Filter 'FinalExec-*.jar' | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $staging 'app-libs') -Force
  }
}
if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zipPath -Force
Remove-Item -LiteralPath $staging -Recurse -Force
Write-Host "Packed: $zipPath"
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Pack-App.ps1') -Value $PackApp -Encoding UTF8

$OpsReadme = @"
# Claude Support Desk - operations toolbox

Generated by Build-ClaudeApp.ps1. All scripts read app-plan.json, so they run with no
arguments. App: $($Plan.appName)  |  Port: $ServerPort  |  Base: $($Plan.baseUrl)

| Script | Purpose |
| --- | --- |
| Build-App.ps1  | Compile the generated app into build\libs\FinalExec-*.jar (gradle clean build). |
| Start-App.ps1  | Start the app in the background, wait for /api/flows to answer. |
| Stop-App.ps1   | Stop the background app (app.pid). |
| Status-App.ps1 | Report whether the app is up. |
| Test-App.ps1   | REST smoke test across CreateWorkspace, RegisterAgent, TriageTicket, SubmitTicket. |
| Pack-App.ps1   | Zip the definition + ops + runnable jar for sharing. |

## Typical session

``````powershell
.\Build-App.ps1
.\Start-App.ps1
.\Test-App.ps1
.\Status-App.ps1
.\Stop-App.ps1
``````
"@
Set-Content -LiteralPath (Join-Path $OpsDir 'README.md') -Value $OpsReadme -Encoding UTF8

# ---- emit interactive app-info page (Property/Value table + copy/open) -----
$ConsolePort = $ServerPort + 100
$consoleLaunch = if ($ConsoleMode -ne 'none') { "& '$(Join-Path $OpsDir 'Serve-AppConsole.ps1')'" } else { '' }
$infoArgs = @{
  StaticDir        = (Join-Path $GeneratedAppRoot 'src\main\resources\static')
  AppId            = 'claude-support-desk'
  Port             = $ServerPort
  AppFolder        = $AppFolder
  OutRoot          = $OutRoot
  GeneratedAppRoot = $GeneratedAppRoot
  OpsDir           = $OpsDir
  Engine           = 'InMemory'
  Flows            = @($Model.flows | ForEach-Object { $_.name })
  Concepts         = @($Model.concepts | ForEach-Object { $_.name })
  CompanionFiles   = @('info.html')
  BuilderName      = 'Claude'
  ConsoleLaunch    = $consoleLaunch
  ConsolePort      = $(if ($ConsoleMode -ne 'none') { $ConsolePort } else { 0 })
}
& (Join-Path $PSScriptRoot 'New-AppInfoPage.ps1') @infoArgs
Write-Step "Emitted interactive info page: http://localhost:$ServerPort/info.html"

# info.html links to control-panel.html and app-tree.html unconditionally -- both were
# missing entirely for this builder, leaving two dead links. Emit them like every other
# NPDev-built app does (Build-NpdevApp.ps1's equivalent calls).
& (Join-Path $PSScriptRoot 'New-ControlPanelPage.ps1') `
  -StaticDir (Join-Path $GeneratedAppRoot 'src\main\resources\static') `
  -AppId 'claude-support-desk' -Port $ServerPort -OutRoot $OutRoot
Write-Step "Emitted ControlPanel page: http://localhost:$ServerPort/control-panel.html"

& (Join-Path $PSScriptRoot 'New-AppTreePage.ps1') `
  -AppFolder $AppFolder -StaticDir (Join-Path $GeneratedAppRoot 'src\main\resources\static') -AppId 'claude-support-desk'
Write-Step "Emitted app tree page: http://localhost:$ServerPort/app-tree.html"

if ($ConsoleMode -ne 'none') {
  & (Join-Path $PSScriptRoot 'New-AppConsole.ps1') -OpsDir $OpsDir -AppId 'claude-support-desk' -ConsolePort $ConsolePort -OutRoot $OutRoot -Mode $ConsoleMode
  Write-Step "Emitted local console (mode=$ConsoleMode): & '$OpsDir\Serve-AppConsole.ps1'  ->  http://127.0.0.1:$ConsolePort/"
}

# ----------------------------------------------------------------------------
# 7. RUN_COMMANDS.md + report
# ----------------------------------------------------------------------------
$RunCommands = @"
# Claude Support Desk - run commands

## Re-generate
``````powershell
& '$($MyInvocation.MyCommand.Path)'
``````

## Build, start, test, stop
``````powershell
& '$OpsDir\Build-App.ps1'
& '$OpsDir\Start-App.ps1'
& '$OpsDir\Test-App.ps1'
& '$OpsDir\Stop-App.ps1'
``````

## Manual run (foreground)
``````powershell
Set-Location '$GeneratedAppRoot'
.\gradlew.bat --no-daemon bootRun "--args=--spring.profiles.active=$SpringProfiles --server.port=$ServerPort"
``````
"@
Set-Content -LiteralPath (Join-Path $OutRoot 'RUN_COMMANDS.md') -Value $RunCommands -Encoding UTF8

$DirectReportPath = Join-Path $OutRoot 'generator-direct-java-report.json'
$DirectReport = if (Test-Path -LiteralPath $DirectReportPath) { Read-JsonFile $DirectReportPath } else { $null }

$Report = [ordered]@{
  schemaVersion     = 'claude-support-desk-build-report.v1'
  generatedAt       = (Get-Date).ToString('o')
  appFolder         = $AppFolder
  productRepo       = $ProductRepo
  runtimeCurrent    = $RuntimeCurrent
  outRoot           = $OutRoot
  generatedAppRoot  = $GeneratedAppRoot
  serverPort        = $ServerPort
  generatorExitCode = $GeneratorExit
  generator         = $DirectReport
  runtimeHostLibs   = $LibsResult
  opsDir            = $OpsDir
  generateOnly      = [bool]$GenerateOnly
}
Write-JsonFile $Report (Join-Path $OutRoot 'build-claude-app-report.json')

Write-Host ''
Write-Host 'Claude Support Desk generation complete.' -ForegroundColor Green
Write-Host "Output root : $OutRoot"
Write-Host "Generated app: $GeneratedAppRoot"
Write-Host "Ops toolbox  : $OpsDir"
Write-Host "Libs sync    : $($LibsResult.status)"
Write-Host ''
if ($GenerateOnly) {
  Write-Host 'GenerateOnly: skipped libs sync. Re-run without -GenerateOnly to produce a compilable app.'
} else {
  Write-Host 'Next:'
  Write-Host "  & '$OpsDir\Build-App.ps1'"
  Write-Host "  & '$OpsDir\Start-App.ps1'"
  Write-Host "  & '$OpsDir\Test-App.ps1'"
}
exit 0
