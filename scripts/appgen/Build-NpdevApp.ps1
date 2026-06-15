<#
.SYNOPSIS
  Reusable one-command builder for any AppGen FinalApp definition.

.DESCRIPTION
  Generalized from the verified Claude Support Desk builder. Given an app folder that
  contains a `definition\` directory (config.json, model.json, db.definition.json,
  optional capabilities\, input\, smoke-plan.json), this script:
    1. Reads app identity (name, port, api key, profiles) from definition\config.json.
    2. Stages the definition (model + capabilities side by side, as the generator expects).
    3. Patches the staged config/model to absolute, outside-repo output paths.
    4. Calls the prepared NPDev generator runtime (direct Java; no Gradle).
    5. Resolves the RuntimeHost libs the app compiles against (prebuilt staging folder).
    6. Emits a self-contained _ops toolbox (Build/Start/Stop/Status/Test/Pack) that reads
       app-plan.json and runs with no arguments.

  Per-app smoke flow is data-driven: place a `definition\smoke-plan.json` describing the
  GET checks and the flow/payload POST steps to exercise.

.EXAMPLE
  # From an app's thin wrapper:
  & '..\_shared\Build-NpdevApp.ps1' -AppFolder $PSScriptRoot

.NOTES
  Output goes to D:\WorkSpace\NPDev\Build\generated-finalapps\<scenario.name>
  (never inside the NPDev_General source repo).
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [string]$AppFolder,
  [string]$ProductRepo = 'D:\WorkSpace\NPDev\NPDev_General',
  [string]$RuntimeCurrent = 'D:\WorkSpace\NPDev\AppGen\generator-runtime\current',
  [string]$BuildRoot = 'D:\WorkSpace\NPDev\Build\generated-finalapps',
  [string]$RuntimeHostLibsDir = 'D:\WorkSpace\NPDev\Build\runtimehost-libs',
  [switch]$GenerateOnly,
  [switch]$SkipRuntimeHostLibs
)

$ErrorActionPreference = 'Stop'

function Write-Step { param([string]$m) Write-Host "[$(Get-Date -Format 'HH:mm:ss')] $m" }
function Read-JsonFile {
  param([string]$Path)
  if (-not (Test-Path -LiteralPath $Path)) { throw "JSON file not found: $Path" }
  Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json -Depth 100
}
function Write-JsonFile {
  param([object]$Value, [string]$Path)
  $parent = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }
  $Value | ConvertTo-Json -Depth 100 | Set-Content -LiteralPath $Path -Encoding UTF8
}
function Set-JsonProp {
  param([object]$Object, [string]$Name, [object]$Value)
  if ($null -ne $Object) { $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $Value -Force }
}

# ---- 1. resolve identity ---------------------------------------------------
$Definition = Join-Path $AppFolder 'definition'
$ConfigSrc  = Join-Path $Definition 'config.json'
$ModelSrc   = Join-Path $Definition 'model.json'
foreach ($p in @($AppFolder, $ProductRepo, $RuntimeCurrent, $Definition, $ConfigSrc, $ModelSrc)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "Required path not found: $p" }
}
$cfg = Read-JsonFile $ConfigSrc
$AppId          = $cfg.scenario.name
$ServerPort     = if ($cfg.runtime.serverPort) { [int]$cfg.runtime.serverPort } else { 8090 }
$SpringProfiles = if ($cfg.runtime.springProfile) { $cfg.runtime.springProfile } else { 'dev,step0,trial' }
$ApiKey         = if ($cfg.trialDefaults.apiKey) { $cfg.trialDefaults.apiKey } else { 'dev-key' }
$ConsoleMode    = if ($cfg.console -and $cfg.console.mode) { "$($cfg.console.mode)" } else { 'none' }
$OutRoot        = Join-Path $BuildRoot $AppId

$RuntimeInvoker = Join-Path $RuntimeCurrent 'invoke-npdev-generator.ps1'
if (-not (Test-Path -LiteralPath $RuntimeInvoker)) { throw "Generator runtime not prepared: $RuntimeInvoker" }
$RuntimeHostTemplate = Join-Path $ProductRepo 'NPDevRuntimeHost'
$ContractSchemas     = Join-Path $ProductRepo 'NPDevContract\schemas'
foreach ($p in @($RuntimeHostTemplate, $ContractSchemas)) {
  if (-not (Test-Path -LiteralPath $p)) { throw "Required product-repo path not found: $p" }
}

Write-Step "App id   : $AppId"
Write-Step "Out root : $OutRoot"
Write-Step "Port     : $ServerPort  Profiles: $SpringProfiles"

# ---- 2. stage definition ---------------------------------------------------
if (Test-Path -LiteralPath $OutRoot) { Write-Step "Removing existing output root: $OutRoot"; Remove-Item -LiteralPath $OutRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutRoot '_logs') | Out-Null
$StagedInput = Join-Path $OutRoot 'Input'
New-Item -ItemType Directory -Force -Path $StagedInput | Out-Null
Get-ChildItem -LiteralPath $Definition -Force | ForEach-Object {
  Copy-Item -LiteralPath $_.FullName -Destination $StagedInput -Recurse -Force
}
$ConfigPath       = Join-Path $StagedInput 'config.json'
$ModelPath        = Join-Path $StagedInput 'model.json'
$DbDefinitionPath = Join-Path $StagedInput 'db.definition.json'
if (-not (Test-Path -LiteralPath $DbDefinitionPath)) { throw "db.definition.json not found in staged input: $DbDefinitionPath" }

# ---- 3. patch staged config/model -----------------------------------------
Write-Step 'Patching staged config/model paths.'
$Config = Read-JsonFile $ConfigPath
$Model  = Read-JsonFile $ModelPath
$ArtifactRoot = Join-Path $OutRoot 'ArtifactNP'
$FinalAppRoot = Join-Path $OutRoot 'App'
Set-JsonProp $Config '$schema' (Join-Path $ContractSchemas 'config.schema.json')
Set-JsonProp $Config.scenario 'outputRoot' $OutRoot
Set-JsonProp $Config.bootstrap 'root' $RuntimeHostTemplate
Set-JsonProp $Config.artifact 'root' $ArtifactRoot
Set-JsonProp $Config.finalExec 'root' $FinalAppRoot
if ($Config.trialDefaults) {
  Set-JsonProp $Config.trialDefaults 'pluginDiscoveryMode' 'filesystem-folder'
  Set-JsonProp $Config.trialDefaults 'pluginPackageDirectory' (Join-Path $ArtifactRoot 'npdev-generated\src\main\resources\npdev\plugin-packages')
}
Set-JsonProp $Model '$schema' (Join-Path $ContractSchemas 'model.schema.json')
# Resolve pack $ref paths to absolute so they survive model.json staging (relative refs
# are relative to the source definition folder, not the staged build-output copy).
if ($Model.packs) {
  $srcModelDir = Split-Path -Parent $ModelSrc
  foreach ($pack in $Model.packs) {
    $rawRef = $pack.'$ref'
    if ($rawRef -and -not [System.IO.Path]::IsPathRooted($rawRef)) {
      $absRef = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($srcModelDir, $rawRef))
      $pack | Add-Member -NotePropertyName '$ref' -NotePropertyValue $absRef -Force
    }
  }
}
# 'console' is an AppGen-only field; the generator's config schema is additionalProperties:false,
# so strip it from the staged config before generation (we already captured $ConsoleMode).
if ($Config.PSObject.Properties.Name -contains 'console') { $Config.PSObject.Properties.Remove('console') }
Write-JsonFile $Config $ConfigPath
Write-JsonFile $Model  $ModelPath

# ---- 4. call generator -----------------------------------------------------
Write-Step 'Calling prepared NPDev generator runtime (direct Java; no Gradle).'
& $RuntimeInvoker -ConfigPath $ConfigPath -ModelPath $ModelPath -OutRoot $OutRoot -DbDefinitionPath $DbDefinitionPath -RuntimeHostTemplate $RuntimeHostTemplate -Clean
$GeneratorExit = $LASTEXITCODE
if ($GeneratorExit -ne 0) {
  Write-Host "Generator FAILED ($GeneratorExit). See $OutRoot\_logs\generator-direct-java.log" -ForegroundColor Red
  exit $GeneratorExit
}
Write-Step 'Generator succeeded.'

# ---- 4b. mount companion web/ assets into the app static folder ------------
# Anything under apps/<App>/web is copied into the generated app's classpath static
# resources, so it is served same-origin at http://localhost:<port>/<file> (no CORS,
# and static is exempt from the API-key filter).
# IMPORTANT: must go under the App module's own src/main/resources/static, NOT under
# npdev-generated/ - the runtime's strict-execution validator hashes the npdev-generated
# tree and refuses to start if any unexpected file appears there.
$WebSrc = Join-Path $AppFolder 'web'
$GeneratedAppRoot = Join-Path $OutRoot 'App'
if (Test-Path -LiteralPath $WebSrc) {
  $StaticDst = Join-Path $GeneratedAppRoot 'src\main\resources\static'
  New-Item -ItemType Directory -Force -Path $StaticDst | Out-Null
  Write-Step "Mounting companion web assets into app static: $WebSrc -> $StaticDst"
  Get-ChildItem -LiteralPath $WebSrc -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $StaticDst -Recurse -Force
  }
}

# ---- 5. resolve RuntimeHost libs ------------------------------------------
$GeneratedAppRoot = Join-Path $OutRoot 'App'
$LibsResult = [ordered]@{ status = 'skipped'; libsDir = $RuntimeHostLibsDir }
if ($GenerateOnly -or $SkipRuntimeHostLibs) {
  Write-Step 'Skipping RuntimeHost libs resolution.'
}
else {
  $libsManifest = Join-Path $RuntimeHostLibsDir 'runtimehost-libs-manifest.json'
  if (-not (Test-Path -LiteralPath $libsManifest)) {
    throw "RuntimeHost libs manifest not found: $libsManifest. Stage with scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars."
  }
  $m = Read-JsonFile $libsManifest
  $required = @($m.requiredStagedJars)
  $missing = @($required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $RuntimeHostLibsDir $_)) })
  if ($missing.Count -gt 0) { throw "RuntimeHost libs incomplete. Missing: $($missing -join ', ')" }
  $LibsResult = [ordered]@{ status = 'resolved'; libsDir = $RuntimeHostLibsDir; jarCount = (@(Get-ChildItem -LiteralPath $RuntimeHostLibsDir -Filter *.jar).Count) }
  Write-Step "RuntimeHost libs resolved ($($LibsResult.jarCount) jars)."
}

# ---- 6. emit _ops toolbox --------------------------------------------------
$OpsDir = Join-Path $OutRoot '_ops'
New-Item -ItemType Directory -Force -Path $OpsDir | Out-Null
$Plan = [ordered]@{
  appId = $AppId; appName = $AppId; outRoot = $OutRoot; appRoot = $GeneratedAppRoot
  serverPort = $ServerPort; apiKey = $ApiKey; springProfiles = $SpringProfiles
  baseUrl = "http://localhost:$ServerPort"; runtimeHostLibsDir = $RuntimeHostLibsDir
}
Write-JsonFile $Plan (Join-Path $OpsDir 'app-plan.json')

# ---- resolved DB plan + environment lifecycle (H2Server / InMemory) --------
$DbDef = Read-JsonFile $DbDefinitionPath
$Engine = "$($DbDef.database.engine)"
$JdbcUrl = "$($DbDef.database.jdbcUrl)"
$H2Port = 9092
$DataRoot = Join-Path 'D:\WorkSpace\NPDev\Build\databases' $AppId
if ($JdbcUrl -match 'tcp://localhost:(\d+)/') { $H2Port = [int]$Matches[1] }
if ($JdbcUrl -match 'tcp://localhost(?::\d+)?/([^;]+)') {
  $urlPath = $Matches[1] -replace '/', '\'
  $DataRoot = Split-Path -Parent $urlPath
}
$DbPlan = [ordered]@{
  engine = $Engine; appId = $AppId; serverPort = $ServerPort; apiKey = $ApiKey
  hostPort = $H2Port; resolvedDataRoot = $DataRoot; jdbcUrl = $JdbcUrl
  resolvedDatabaseName = "$($DbDef.database.databaseName)"
}
Write-JsonFile $DbPlan (Join-Path $OpsDir 'resolved-db-plan.json')

$StartEnv = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
if ($plan.engine -eq 'InMemory') { Write-Host 'InMemory: no environment to start.'; exit 0 }
if ($plan.engine -eq 'H2Server') {
  New-Item -ItemType Directory -Force -Path $plan.resolvedDataRoot | Out-Null
  $jar = @(Get-ChildItem -Path 'D:\WorkSpace\NPDev\Build', (Join-Path $env:USERPROFILE '.gradle\caches') -Recurse -Filter 'h2-2*.jar' -ErrorAction SilentlyContinue) |
         Where-Object { $_.FullName -notlike '*\gradle-8*\lib\*' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($null -eq $jar) { throw 'No standalone h2-2*.jar found under Build or ~/.gradle. Build an app once to populate the gradle cache.' }
  $pidFile = Join-Path $PSScriptRoot 'h2server.pid'
  $logFile = Join-Path $PSScriptRoot 'h2server.log'
  if (Test-Path -LiteralPath $pidFile) {
    $p = Get-Process -Id ([int](Get-Content -Raw -LiteralPath $pidFile)) -ErrorAction SilentlyContinue
    if ($null -ne $p) { Write-Host "H2Server already running (PID $($p.Id))."; exit 0 }
  }
  $args = @('-cp', $jar.FullName, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$plan.hostPort, '-tcpAllowOthers', '-ifNotExists', '-baseDir', $plan.resolvedDataRoot)
  $proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.resolvedDataRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $PSScriptRoot 'h2server.err.log')
  $proc.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
  Start-Sleep -Seconds 2
  Write-Host "H2Server started on tcp port $($plan.hostPort) (PID $($proc.Id)), data $($plan.resolvedDataRoot), jar $($jar.Name)"
  exit 0
}
Write-Host "Engine $($plan.engine): no environment starter implemented."
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Start-Environment.ps1') -Value $StartEnv -Encoding UTF8

$StopEnv = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
if ($plan.engine -eq 'H2Server') {
  $pidFile = Join-Path $PSScriptRoot 'h2server.pid'
  if (Test-Path -LiteralPath $pidFile) {
    $procId = [int](Get-Content -Raw -LiteralPath $pidFile)
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if ($null -ne $p) { Stop-Process -Id $procId -Force; Write-Host "H2Server stopped (PID $procId)." }
    Remove-Item -LiteralPath $pidFile -Force
  } else { Write-Host 'No h2server.pid; nothing to stop.' }
  exit 0
}
Write-Host "Engine $($plan.engine): no environment to stop."
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Stop-Environment.ps1') -Value $StopEnv -Encoding UTF8

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
$env:NPDEV_RUNTIMEHOST_LIBS_DIR = $plan.runtimeHostLibsDir
& (Join-Path $plan.appRoot 'gradlew.bat') --no-daemon --console=plain "-PnpdevRuntimeHostLibsDir=$($plan.runtimeHostLibsDir)" clean build -x test
if ($LASTEXITCODE -ne 0) { Write-Host 'Build FAILED.' -ForegroundColor Red; exit $LASTEXITCODE }
$jar = Get-ChildItem -LiteralPath $plan.appRoot -Recurse -Filter 'FinalExec-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.FullName -like '*\build\libs\*' -and $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
if ($null -eq $jar) { Write-Host 'Build OK but runnable jar not found.' -ForegroundColor Yellow; exit 1 }
Write-Host "Build OK. Runnable jar: $($jar.FullName)"
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Build-App.ps1') -Value $BuildApp -Encoding UTF8

$StartApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$startEnv = Join-Path $PSScriptRoot 'Start-Environment.ps1'
if (Test-Path -LiteralPath $startEnv) { & $startEnv }
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
Write-Host 'Waiting for health...'
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  try { Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/api/flows" -Headers @{ 'X-Api-Key' = $plan.apiKey } -TimeoutSec 3 | Out-Null; $ok = $true; break } catch { }
}
if ($ok) { Write-Host "App is UP at $($plan.baseUrl)/api/flows" } else { Write-Host 'App did not report healthy in time; check logs.' -ForegroundColor Yellow }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Start-App.ps1') -Value $StartApp -Encoding UTF8

$StopApp = @'
$ErrorActionPreference = 'Stop'
$pidFile = Join-Path $PSScriptRoot 'app.pid'
if (-not (Test-Path -LiteralPath $pidFile)) { Write-Host 'No app.pid; nothing to stop.'; exit 0 }
$procId = [int](Get-Content -Raw -LiteralPath $pidFile)
$proc = Get-Process -Id $procId -ErrorAction SilentlyContinue
if ($null -ne $proc) { Stop-Process -Id $procId -Force; Write-Host "Stopped PID $procId." } else { Write-Host "Process $procId was not running." }
Remove-Item -LiteralPath $pidFile -Force
$stopEnv = Join-Path $PSScriptRoot 'Stop-Environment.ps1'
if (Test-Path -LiteralPath $stopEnv) { & $stopEnv }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Stop-App.ps1') -Value $StopApp -Encoding UTF8

$StatusApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
try { Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/api/flows" -Headers @{ 'X-Api-Key' = $plan.apiKey } -TimeoutSec 3 | Out-Null; Write-Host "UP   - $($plan.baseUrl)/api/flows reachable" }
catch { Write-Host "DOWN - $($plan.baseUrl)/api/flows not reachable" }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Status-App.ps1') -Value $StatusApp -Encoding UTF8

# Data-driven smoke test: reads ..\Input\smoke-plan.json if present.
$TestApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$inputRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'Input'
$inputDir = Join-Path $inputRoot 'input'
$base = $plan.baseUrl
$headers = @{ 'X-Api-Key' = $plan.apiKey }
$report = [ordered]@{ appId = $plan.appId; baseUrl = $base; steps = @(); status = 'FAIL' }
$smokePlanPath = Join-Path $inputRoot 'smoke-plan.json'
$smoke = if (Test-Path -LiteralPath $smokePlanPath) { Get-Content -Raw -LiteralPath $smokePlanPath | ConvertFrom-Json } else { $null }
try {
  $checks = if ($smoke -and $smoke.checks) { @($smoke.checks) } else { @('/api/flows') }
  foreach ($c in $checks) {
    try { $r = Invoke-RestMethod -Method GET -Uri "$base$c" -Headers $headers -TimeoutSec 10; $report.steps += @{ step = "GET $c"; ok = $true } }
    catch { $report.steps += @{ step = "GET $c"; ok = $false; error = $_.Exception.Message } }
  }
  if ($smoke -and $smoke.steps) {
    foreach ($s in $smoke.steps) {
      $body = Get-Content -Raw -LiteralPath (Join-Path $inputDir $s.payload)
      $uri = "$base/api/flows/$($s.flow)/execute"
      try {
        $res = Invoke-RestMethod -Method POST -Uri $uri -Headers $headers -ContentType 'application/json' -Body $body
        $report.steps += @{ step = "POST $($s.flow)"; ok = $true; status = $res.status }
      } catch {
        $report.steps += @{ step = "POST $($s.flow)"; ok = $false; error = $_.Exception.Message }
      }
    }
  }
  $bad = @($report.steps | Where-Object { -not $_.ok })
  $report.status = if ($bad.Count -eq 0) { 'PASS' } else { 'FAIL' }
} catch {
  $report.steps += @{ step = 'ERROR'; ok = $false; error = $_.Exception.Message }
}
$report | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'smoke-test-report.json') -Encoding UTF8
Write-Host "Status: $($report.status)"
$report.steps | ForEach-Object { Write-Host "  [$($_.ok)] $($_.step)" }
if ($report.status -ne 'PASS') { exit 1 }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Test-App.ps1') -Value $TestApp -Encoding UTF8

# Make the generator's older convenience scripts delegate to the guarded ops scripts,
# so running either name (Build-FinalApp.ps1 / Run-FinalApp.ps1) is safe.
$buildShim = "# Deprecated name -> delegates to the guarded Build-App.ps1 (detects a running app first).`n& (Join-Path `$PSScriptRoot 'Build-App.ps1') @args`nexit `$LASTEXITCODE`n"
$runShim   = "# Deprecated name -> delegates to Start-App.ps1 (starts the DB environment, guards duplicates).`n& (Join-Path `$PSScriptRoot 'Start-App.ps1') @args`nexit `$LASTEXITCODE`n"
foreach ($shimPair in @(@('Build-FinalApp.ps1', $buildShim), @('Run-FinalApp.ps1', $runShim))) {
  $shimTarget = Join-Path $OpsDir $shimPair[0]
  if (Test-Path -LiteralPath $shimTarget) { Set-Content -LiteralPath $shimTarget -Value $shimPair[1] -Encoding UTF8 }
}

$OpsReadme = "# $AppId - operations toolbox`n`nAll scripts read app-plan.json and run with no arguments. Port $ServerPort, base http://localhost:$ServerPort.`n`n| Script | Purpose |`n| --- | --- |`n| Build-App.ps1 | gradle clean build -> FinalExec jar |`n| Start-App.ps1 | start in background, wait for /api/flows |`n| Stop-App.ps1 | stop background app |`n| Status-App.ps1 | report up/down |`n| Test-App.ps1 | data-driven smoke (reads Input\smoke-plan.json) |`n`n``````powershell`n.\Build-App.ps1; .\Start-App.ps1; .\Test-App.ps1; .\Stop-App.ps1`n```````n"
Set-Content -LiteralPath (Join-Path $OpsDir 'README.md') -Value $OpsReadme -Encoding UTF8

# ---- emit interactive app-info page (Property/Value table + copy/open) -----
$companionFiles = @()
if (Test-Path -LiteralPath $WebSrc) { $companionFiles = @(Get-ChildItem -LiteralPath $WebSrc -File | Select-Object -ExpandProperty Name) }
$companionFiles += 'info.html'
$ConsolePort = $ServerPort + 100
$consoleLaunch = if ($ConsoleMode -ne 'none') { "& '$(Join-Path $OpsDir 'Serve-AppConsole.ps1')'" } else { '' }
$infoArgs = @{
  StaticDir        = (Join-Path $GeneratedAppRoot 'src\main\resources\static')
  AppId            = $AppId
  Port             = $ServerPort
  AppFolder        = $AppFolder
  OutRoot          = $OutRoot
  GeneratedAppRoot = $GeneratedAppRoot
  OpsDir           = $OpsDir
  Engine           = $Engine
  JdbcUrl          = $JdbcUrl
  DbDataRoot       = $DataRoot
  DbName           = "$($DbPlan.resolvedDatabaseName)"
  Flows            = @($Model.flows | ForEach-Object { $_.name })
  Concepts         = @($Model.concepts | ForEach-Object { $_.name })
  CompanionFiles   = $companionFiles
  BuilderName      = (Split-Path -Leaf $AppFolder)
  ConsoleLaunch    = $consoleLaunch
  ConsolePort      = $(if ($ConsoleMode -ne 'none') { $ConsolePort } else { 0 })
}
& (Join-Path $PSScriptRoot 'New-AppInfoPage.ps1') @infoArgs
Write-Step "Emitted interactive info page: http://localhost:$ServerPort/info.html"

# ---- emit the local control console (if enabled in config.console.mode) -----
if ($ConsoleMode -ne 'none') {
  & (Join-Path $PSScriptRoot 'New-AppConsole.ps1') -OpsDir $OpsDir -AppId $AppId -ConsolePort $ConsolePort -OutRoot $OutRoot -Mode $ConsoleMode
  Write-Step "Emitted local console (mode=$ConsoleMode): & '$OpsDir\Serve-AppConsole.ps1'  ->  http://127.0.0.1:$ConsolePort/"
}

$DirectReportPath = Join-Path $OutRoot 'generator-direct-java-report.json'
$DirectReport = if (Test-Path -LiteralPath $DirectReportPath) { Read-JsonFile $DirectReportPath } else { $null }
Write-JsonFile ([ordered]@{
  schemaVersion = 'npdev-appgen-build-report.v1'; generatedAt = (Get-Date).ToString('o')
  appId = $AppId; appFolder = $AppFolder; outRoot = $OutRoot; generatedAppRoot = $GeneratedAppRoot
  serverPort = $ServerPort; generatorExitCode = $GeneratorExit; generator = $DirectReport
  runtimeHostLibs = $LibsResult; opsDir = $OpsDir; generateOnly = [bool]$GenerateOnly
}) (Join-Path $OutRoot 'build-app-report.json')

Write-Host ''
Write-Host "$AppId generation complete." -ForegroundColor Green
Write-Host "Output root : $OutRoot"
Write-Host "Ops toolbox : $OpsDir"
Write-Host "Libs        : $($LibsResult.status)"
if (-not $GenerateOnly) {
  Write-Host 'Next:'
  Write-Host "  & '$OpsDir\Build-App.ps1'"
  Write-Host "  & '$OpsDir\Start-App.ps1'"
  Write-Host "  & '$OpsDir\Test-App.ps1'"
}
exit 0
