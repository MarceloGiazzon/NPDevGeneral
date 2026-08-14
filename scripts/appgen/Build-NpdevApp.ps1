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
  # Portable default (REG-144): resolved from $PSScriptRoot below rather than hardcoded to the
  # author's D:\ layout -- this script lives at <repo>/scripts/appgen/, so the repo root is two
  # levels up, correct under any clone name. The AppGen/Build defaults below are deliberately NOT
  # derived: they point outside the repo, into a layout that is genuinely per-machine.
  [string]$ProductRepo = '',
  [string]$RuntimeCurrent = 'D:\WorkSpace\NPDev\AppGen\generator-runtime\current',
  [string]$BuildRoot = 'D:\WorkSpace\NPDev\Build\generated-finalapps',
  [string]$RuntimeHostLibsDir = 'D:\WorkSpace\NPDev\Build\runtimehost-libs',
  [switch]$GenerateOnly,
  [switch]$SkipRuntimeHostLibs,
  # LNCH-1 P6 (task 6.2c). -PlanOnly: compute + print the migration plan (SAFE items plainly,
  # DESTRUCTIVE items with a red banner and the copyable ack token), then exit -- before the
  # web-asset/ops-toolbox/info-page steps -- with a script-friendly exit code (1 if any destructive
  # item is present, 0 otherwise). A full generation pass still happens (cheap, local, touches
  # nothing live) -- see this switch's usage below for why an honest plan needs one anyway.
  # SER-P9.2: this is the OFFLINE estimate -- model vs. the PREVIOUS MODEL, no database contacted, no
  # row counts, safe to run with nothing deployed yet. Kept deliberately (not part of the SER-P9.1
  # dead-lineage retirement) because that offline capability has no live-database equivalent.
  # Contrast -ImpactOnly (SER-P6.4): model vs. the LIVE DATABASE -- the truth, with real row counts,
  # but the target must already be reachable. Use -PlanOnly for a quick pre-authoring sanity check;
  # use -ImpactOnly before an actual deploy.
  [switch]$PlanOnly,
  # -Upgrade: same plan computation/printing as -PlanOnly, but does NOT exit early -- the script
  # continues through its normal steps (this IS the real upgrade). Additionally captures the
  # PREVIOUS FinalApp output's canonical compiled-model.json before the wipe below destroys it,
  # threads it into the generator as --previousCompiledModel so the plan is a real diff (not a
  # fresh-install plan), and echoes migration-plan.json outside the wiped tree so it survives the
  # NEXT wipe too.
  [switch]$Upgrade,
  # SER-P6.4 (Surface 2). -ImpactOnly: build the jar, then run it ONCE against the app's configured
  # live database with npdev.schema.lifecycle.mode=REPORT_ONLY. Prints the impact table (what will
  # change, how many rows) and EXITS with the app's verdict code (0 safe/no-changes, 2 needs-attention,
  # 3 destructive) WITHOUT applying anything. Contrast with -PlanOnly = model-vs-previous-MODEL
  # (offline, no DB needed); -ImpactOnly = model-vs-LIVE-DATABASE (the GeneXus impact; the target
  # database must already be reachable -- this script does not start it for you).
  [switch]$ImpactOnly,
  # -AcknowledgeDestructive <token>: threads the token into the generator's new
  # --destructiveAcknowledgment flag (LNCH-1 P6 task 6.2b), landing it verbatim in the generated
  # manifest's destructiveAcknowledgment key -- the value SchemaLifecycleExecutor's Phase 4
  # destructive-path token check reads at boot. Independent of -PlanOnly/-Upgrade (a plain build
  # with just this parameter also threads the token; no plan is computed unless -PlanOnly/-Upgrade
  # is also passed).
  [string]$AcknowledgeDestructive
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ProductRepo)) {
  $ProductRepo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

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
# REG-111 (Fast Lane plan item 4b/A2): same sidecar shape `npdev run app` already writes
# (npdev-run-app-progress.json: schemaVersion/phase/updatedAt/pid, GENERATE/BUILD/BOOT/READY
# vocabulary) -- so one file, at the app root, can be tailed across all three entry points
# regardless of which one a caller used. Best-effort: a write failure never fails the run.
function Write-NpdevRunAppProgress {
  param([string]$AppRoot, [string]$Phase)
  try {
    New-Item -ItemType Directory -Force -Path $AppRoot | Out-Null
    $payload = [ordered]@{
      schemaVersion = 'npdev-run-app-progress.v1'
      phase         = $Phase
      updatedAt     = (Get-Date).ToUniversalTime().ToString('o')
      pid           = $PID
    }
    ($payload | ConvertTo-Json) | Set-Content -LiteralPath (Join-Path $AppRoot 'npdev-run-app-progress.json') -Encoding UTF8
  } catch { }
}

# LNCH-1 P6 (task 6.2c). Renders a migration-plan.json object (MigrationPlanEmitter's schema --
# see NPDevContract\schemas\migration-plan.schema.json) as a readable console table: SAFE items
# plainly, DESTRUCTIVE items behind a visible red banner with the copyable ack token clearly
# labeled, per the plan's explicit ask ("the person running the upgrade must be able to see the
# full plan ... before anything touches the database").
function Write-MigrationPlanTable {
  param([Parameter(Mandatory = $true)][object]$PlanObj)

  Write-Host ''
  Write-Host '================ NPDev Migration Plan =================' -ForegroundColor Cyan
  if ($PlanObj.freshInstall) {
    Write-Host 'Fresh install -- no previous compiled model to diff against.'
  } else {
    Write-Host "From fingerprint : $($PlanObj.fromFingerprint)"
  }
  Write-Host "To fingerprint   : $($PlanObj.toFingerprint)"

  $items = @($PlanObj.items)
  if ($items.Count -eq 0) {
    Write-Host ''
    Write-Host 'No changes.' -ForegroundColor Green
    Write-Host '========================================================' -ForegroundColor Cyan
    return
  }

  # LNCH-1 remediation R6 (F7): non-fatal stale-marker warnings (exit code unchanged).
  $warnings = @($PlanObj.warnings)
  if ($warnings.Count -gt 0) {
    Write-Host ''
    Write-Host "WARNINGS ($($warnings.Count)):" -ForegroundColor Yellow
    foreach ($w in $warnings) {
      Write-Host "  ! $w" -ForegroundColor Yellow
    }
  }

  $safeItems = @($items | Where-Object { -not $_.destructive })
  $destructiveItems = @($items | Where-Object { $_.destructive })

  if ($safeItems.Count -gt 0) {
    Write-Host ''
    Write-Host "SAFE changes ($($safeItems.Count)):" -ForegroundColor Green
    foreach ($item in $safeItems) {
      Write-Host "  [$($item.kind)] $($item.description)"
    }
  }

  if ($destructiveItems.Count -gt 0) {
    Write-Host ''
    Write-Host '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!' -ForegroundColor Red
    Write-Host "DESTRUCTIVE changes ($($destructiveItems.Count)) -- DATA WILL BE LOST for these items:" -ForegroundColor Red
    foreach ($item in $destructiveItems) {
      Write-Host "  [$($item.kind)] $($item.description)" -ForegroundColor Red
      if ($item.sqlPreview) { Write-Host "      SQL: $($item.sqlPreview)" -ForegroundColor Yellow }
    }
    Write-Host ''
    Write-Host 'Acknowledgment token (copy exactly; pass to -AcknowledgeDestructive, or submit via' -ForegroundColor Yellow
    Write-Host 'the ControlPanel schema-migration screen on the CURRENTLY RUNNING app):' -ForegroundColor Yellow
    Write-Host "  $($PlanObj.destructiveAckToken)" -ForegroundColor Yellow
    Write-Host '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!' -ForegroundColor Red
  }
  Write-Host ''
  Write-Host '========================================================' -ForegroundColor Cyan
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

# ---- 1a. LNCH-1 P6 (task 6.2c): capture the previous compiled model BEFORE the wipe ----------
# -Upgrade (and -PlanOnly, so its preview reflects a real diff rather than always reading as a
# fresh install) need the PREVIOUS FinalApp output's canonical compiled-model.json to compute a
# real migration plan against -- but step 2 immediately below deletes the entire $OutRoot
# (including this file, at $OutRoot\App\npdev-generated\src\main\resources\npdev\compiled-model.json)
# before the generator ever runs again. This is the one narrow window where both the OLD file and
# the about-to-be-regenerated NEW one can coexist (the wipe happens BEFORE the generator call, not
# after) -- read/copy it out to a location OUTSIDE $OutRoot right now, before it is destroyed.
# LNCH-1 closeout C4 (finding C-B2 / LNCH-1-B8). The capture above was only ever written to a
# GUID-named file under %TEMP% that nothing ever read again, so it did not survive a FAILED run in
# any USABLE sense: if generation failed after the wipe below, $OutRoot no longer held a compiled
# model, and the NEXT -PlanOnly silently reported "Fresh install -- no previous compiled model to
# diff against" and exited 0 -- the script-friendly "safe to proceed" signal -- for a database that
# actually needed a destructive change. A wrong plan presented as a valid one.
#
# So the snapshot is now ALSO written to a durable, DISCOVERABLE location under the build-output
# area, next to the plan echoes that already survive rebuilds, under a STABLE filename that the
# next run looks for.
#
# Keyed by the definition folder, NOT by $AppId: several shipped definitions deliberately share one
# scenario.name and therefore one $OutRoot (verified 2026-07-20: simple-user-registry-h2local,
# -postgres and -h2local-freshdb are all scenario.name "simple-user-registry"). Keying the snapshot
# by $AppId alone would let one app's model be diffed against another's and presented as
# authoritative -- worse than the bug being fixed here.
$AppDefinitionKey = (Split-Path -Leaf $AppFolder) -replace '[^A-Za-z0-9_.-]', '_'
$PlanArtifactDir = Join-Path (Split-Path -Parent $BuildRoot) "$AppId\migration-plans"
$PreservedCompiledModelPath = Join-Path $PlanArtifactDir "previous-compiled-model-$AppDefinitionKey.json"

$PreviousCompiledModelPath = $null
$HasPriorDeploymentEvidence = $false
if ($PlanOnly -or $Upgrade) {
  $PriorCompiledModelPath = Join-Path $OutRoot 'App\npdev-generated\src\main\resources\npdev\compiled-model.json'
  if (Test-Path -LiteralPath $PriorCompiledModelPath) {
    $PlanScratchDir = Join-Path ([System.IO.Path]::GetTempPath()) 'npdev-build-npdevapp-scratch'
    New-Item -ItemType Directory -Force -Path $PlanScratchDir | Out-Null
    $PreviousCompiledModelPath = Join-Path $PlanScratchDir "$AppId-previous-compiled-model-$([Guid]::NewGuid().ToString('N')).json"
    Copy-Item -LiteralPath $PriorCompiledModelPath -Destination $PreviousCompiledModelPath -Force
    Write-Step "Captured previous compiled model before wipe: $PreviousCompiledModelPath"

    # Durable copy (C4), so a failed run below cannot leave the next plan blind.
    New-Item -ItemType Directory -Force -Path $PlanArtifactDir | Out-Null
    Copy-Item -LiteralPath $PriorCompiledModelPath -Destination $PreservedCompiledModelPath -Force
    Write-Step "Preserved previous compiled model outside the wiped tree: $PreservedCompiledModelPath"
  }
  elseif (Test-Path -LiteralPath $PreservedCompiledModelPath) {
    # $OutRoot's copy is gone but a previous run preserved one -- the failed-upgrade case. Diff
    # against the preserved snapshot, which is still the model this app was last GENERATED from.
    $PreviousCompiledModelPath = $PreservedCompiledModelPath
    Write-Step "No compiled model in the output root (a previous run was wiped or failed) -- using the preserved snapshot: $PreservedCompiledModelPath"
  }
  else {
    # Neither. Is there durable EVIDENCE this app was ever generated before? The plan echoes written
    # by past -Upgrade runs are exactly that, and they survive every wipe. If any exist, this is NOT
    # a fresh install and we must NOT pretend otherwise -- refuse instead of degrading.
    $PriorPlanEchoes = @(Get-ChildItem -LiteralPath $PlanArtifactDir -Filter 'plan-*.json' -ErrorAction SilentlyContinue)
    $HasPriorDeploymentEvidence = ($PriorPlanEchoes.Count -gt 0)
    if ($HasPriorDeploymentEvidence) {
      throw @"
Refusing to emit a migration plan for '$AppId' ($AppDefinitionKey).

This app has evidence of a PRIOR deployment -- $($PriorPlanEchoes.Count) migration plan artifact(s) in
  $PlanArtifactDir
-- but no previous compiled model is available to diff against:
  missing from the output root : $PriorCompiledModelPath
  missing preserved snapshot   : $PreservedCompiledModelPath

That combination means a previous compiled model existed and was then LOST -- almost always a
generation run that failed AFTER the output root was wiped (LNCH-1-B8). Emitting a plan now would
report 'Fresh install -- no previous compiled model to diff against' and exit 0, which is the
script-friendly 'safe to proceed' signal -- for a database that may well need a destructive change.
That is a wrong plan presented as a valid one, so this refuses instead.

To proceed, restore a real starting point -- rebuild this app successfully once with NEITHER -Upgrade
NOR -PlanOnly (a plain build), which regenerates the compiled model and re-preserves the snapshot.
Note that -Upgrade alone is NOT enough: this guard runs for -Upgrade and -PlanOnly alike, so an
-Upgrade run refuses here too and cannot be the way out. If you genuinely intend a fresh install,
delete the stale plan artifacts in the directory above; that is a deliberate act, which is the point.
"@
    }
    Write-Step "No previous compiled model found at $PriorCompiledModelPath and no prior deployment evidence -- plan will be a fresh-install plan."
  }
}
$PlanJsonPath = Join-Path $OutRoot 'migration-plan.json'

# ---- 2. stage definition ---------------------------------------------------
# PORT-1 made the app's database app-relative (<OutRoot>\App\data) so that a generated app can be
# handed to someone else and still find it. That put the database INSIDE the directory this line used
# to delete wholesale -- so a plain regeneration would have destroyed it, and with it every path
# through SchemaLifecycleExecutor that only runs against an EXISTING database with a changed model
# (KeepExistingIfCompatible, the migration plans, LNCH-1's whole diff machinery). Losing those
# quietly would have been the worse half of this change: the schema-evolution tests would still pass,
# against a fresh database, proving nothing.
#
# So the wipe now spares exactly one directory, in place -- never moved to a temp location and moved
# back, because a generation that fails in between would strand a user's data somewhere they would
# never think to look.
#
# MONITOR_PLAN D10 adds `logs` to the same list, for the same reason one step removed: `logs\`
# holds the app's own stdout from previous runs, and the single most valuable moment to read it is
# right after a regeneration that was itself triggered by something going wrong. Wiping the evidence
# of why the last run failed, as part of the attempt to fix it, is the worst possible timing.
#
# `secrets` joins them for the agent-proxy feature: <App>\secrets\agent-proxy.env holds the operator's
# provider API key, which the generator cannot reproduce and which nothing else on the machine has a
# copy of -- losing it on a regeneration is the same class of loss as `data`. Only the .env.example is
# ever emitted; the real file is written by hand and never overwritten.
# Twin-pair `app-secrets-dir-spared-three-seams` (token: npdev-app-secrets-spared). The Java twin is
# FinalAppAssembler.PRESERVED_APP_DIRECTORIES, which runs in the SAME build and spares its own list --
# a directory added here and not there is deleted anyway, which is exactly how `logs` behaved until now.
# The third seam is Build-ClaudeApp.ps1, which has its own copy of this wipe.
$SparedInsideApp = @('data', 'logs', 'secrets')
$PreservedRoots = @($SparedInsideApp | ForEach-Object { Join-Path $OutRoot "App\$_" })
if (Test-Path -LiteralPath $OutRoot) {
  if ($PreservedRoots | Where-Object { Test-Path -LiteralPath $_ }) {
    Write-Step ("Removing existing output root (preserving " + (($SparedInsideApp | ForEach-Object { "App\$_" }) -join ', ') + "): $OutRoot")
    Get-ChildItem -LiteralPath $OutRoot -Force | Where-Object { $_.Name -ne 'App' } |
      ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }
    Get-ChildItem -LiteralPath (Join-Path $OutRoot 'App') -Force | Where-Object { $SparedInsideApp -notcontains $_.Name } |
      ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }
  }
  else {
    Write-Step "Removing existing output root: $OutRoot"
    Remove-Item -LiteralPath $OutRoot -Recurse -Force
  }
}
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
# Pack $ref paths are left as-authored (relative). ModelSourceResolver requires pack refs to
# stay relative and resolves them against the staged model.json's own directory -- which already
# contains a full copy of the source definition folder (step 2 above copies every top-level item,
# packs/ included), so a pack authored under definition/packs/... resolves correctly without any
# rewriting. Rewriting to an absolute path here (as a previous version of this script did) actively
# breaks generation: ModelSourceResolver.resolveJsonRefUnderRoot explicitly rejects any pack $ref
# that is rooted/absolute ("Pack $ref must be relative, not a drive path"), so an app whose pack is
# colocated under its own definition/ folder (the supported, working pattern) would fail to build.
# 'console' is an AppGen-only field; the generator's config schema is additionalProperties:false,
# so strip it from the staged config before generation (we already captured $ConsoleMode).
if ($Config.PSObject.Properties.Name -contains 'console') { $Config.PSObject.Properties.Remove('console') }
Write-JsonFile $Config $ConfigPath
Write-JsonFile $Model  $ModelPath

# ---- 4. call generator -----------------------------------------------------
# LNCH-1 P6 (task 6.2c): -PlanOnly/-Upgrade/-AcknowledgeDestructive need generator CLI flags
# (--previousCompiledModel/--schemaMigrationPlanOut/--destructiveAcknowledgment, LNCH-1 P6 tasks
# 6.1/6.2b) that invoke-npdev-generator.ps1 -- an AppGen-runtime wrapper script staged OUTSIDE
# this repo at $RuntimeCurrent, with no pass-through mechanism for extra generator arguments --
# does not support (verified by reading its source before writing this). Every PLAIN call (none of
# these three switches/parameter used) keeps calling that wrapper completely unchanged -- zero
# behavior change. Only this opt-in path calls the SAME generator runtime jars directly instead,
# mirroring the wrapper's own classpath resolution and argument shape exactly (config/model/out/
# dbDefinitionPath/runtimeHostTemplate/finalAppOut, --clean, --assembleFinalApp, --cleanFinalApp --
# the wrapper always adds the latter two since Build-NpdevApp.ps1 never passes -NoAssembleFinalApp/
# -NoCleanFinalApp), plus the new flags.
$UsesDirectGeneratorFlags = [bool]$PlanOnly -or [bool]$Upgrade -or (-not [string]::IsNullOrWhiteSpace($AcknowledgeDestructive))
Write-NpdevRunAppProgress -AppRoot $FinalAppRoot -Phase 'GENERATE'
if (-not $UsesDirectGeneratorFlags) {
  Write-Step 'Calling prepared NPDev generator runtime (direct Java; no Gradle).'
  & $RuntimeInvoker -ConfigPath $ConfigPath -ModelPath $ModelPath -OutRoot $OutRoot -DbDefinitionPath $DbDefinitionPath -RuntimeHostTemplate $RuntimeHostTemplate -Clean
  $GeneratorExit = $LASTEXITCODE
} else {
  Write-Step 'Calling NPDevGenerator directly (Java) -- migration-plan/acknowledgment flags need pass-through invoke-npdev-generator.ps1 does not provide.'
  $GenRuntimeLibDir = Join-Path $RuntimeCurrent 'lib'
  $GenJars = @(Get-ChildItem -LiteralPath $GenRuntimeLibDir -File -Filter '*.jar' -Force | Sort-Object Name)
  if ($GenJars.Count -eq 0) { throw "No jars found in generator runtime lib folder: $GenRuntimeLibDir" }
  $GenClasspath = ($GenJars | ForEach-Object { $_.FullName }) -join [System.IO.Path]::PathSeparator
  $DirectGeneratorArgs = @(
    '--config', $ConfigPath,
    '--model', $ModelPath,
    '--out', $ArtifactRoot,
    '--dbDefinitionPath', $DbDefinitionPath,
    '--runtimeHostTemplate', $RuntimeHostTemplate,
    '--finalAppOut', $FinalAppRoot,
    '--clean',
    '--assembleFinalApp',
    '--cleanFinalApp'
  )
  if ($PreviousCompiledModelPath) { $DirectGeneratorArgs += @('--previousCompiledModel', $PreviousCompiledModelPath) }
  if ($PlanOnly -or $Upgrade) {
    $DirectGeneratorArgs += @('--schemaMigrationPlanOut', $PlanJsonPath)
    # LNCH-1 closeout C4: belt-and-braces. The step-1a block above already refuses this combination
    # before we get here, so this should be unreachable -- but asserting it at the generator boundary
    # too means any OTHER caller that loses its previous model gets the same refusal instead of a
    # silent fresh-install plan, and it fails loudly if step 1a is ever refactored wrong.
    if (-not $PreviousCompiledModelPath -and $HasPriorDeploymentEvidence) {
      $DirectGeneratorArgs += '--requirePreviousCompiledModel'
    }
  }
  if (-not [string]::IsNullOrWhiteSpace($AcknowledgeDestructive)) { $DirectGeneratorArgs += @('--destructiveAcknowledgment', $AcknowledgeDestructive) }
  $DirectJavaArgs = @('-cp', $GenClasspath, 'com.npdev.generator.GeneratorMain') + $DirectGeneratorArgs

  # Mirror invoke-npdev-generator.ps1's own report+log writing (report.status/exitCode consumed
  # later at step 4a2/the build-app-report.json section; the failure message just below points here
  # regardless of which branch ran) -- without this, a -PlanOnly/-Upgrade/-AcknowledgeDestructive
  # failure would point at a log file the direct-invocation path never created.
  $GenLogDir = Join-Path $OutRoot '_logs'
  New-Item -ItemType Directory -Force -Path $GenLogDir | Out-Null
  $GenLogPath = Join-Path $GenLogDir 'generator-direct-java.log'
  $GenReportPath = Join-Path $OutRoot 'generator-direct-java-report.json'
  $GenStartedAt = Get-Date
  Push-Location $RuntimeCurrent
  try {
    $GenOutput = & java @DirectJavaArgs 2>&1
    $GeneratorExit = $LASTEXITCODE
    $GenOutput | ForEach-Object { Write-Host $_ }
  } finally {
    Pop-Location
  }
  $GenFinishedAt = Get-Date
  @(
    'NPDevGenerator direct Java invocation (Build-NpdevApp.ps1 -PlanOnly/-Upgrade/-AcknowledgeDestructive path)',
    "StartedAt: $($GenStartedAt.ToString('o'))",
    "FinishedAt: $($GenFinishedAt.ToString('o'))",
    "ExitCode: $GeneratorExit",
    '',
    'Command:',
    'java ' + (($DirectJavaArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '),
    '',
    'Output:',
    ($GenOutput -join [Environment]::NewLine)
  ) | Set-Content -LiteralPath $GenLogPath -Encoding UTF8
  Write-JsonFile ([ordered]@{
    schemaVersion = 'npdev-generator-direct-java-report.v1'
    startedAt = $GenStartedAt.ToString('o'); finishedAt = $GenFinishedAt.ToString('o')
    exitCode = $GeneratorExit; status = if ($GeneratorExit -eq 0) { 'passed' } else { 'failed' }
    runtimeRoot = $RuntimeCurrent; mainClass = 'com.npdev.generator.GeneratorMain'
    configPath = $ConfigPath; modelPath = $ModelPath; outRoot = $OutRoot
    artifactOut = $ArtifactRoot; finalAppOut = $FinalAppRoot
    runtimeHostTemplate = $RuntimeHostTemplate; dbDefinitionPath = $DbDefinitionPath; logPath = $GenLogPath
  }) $GenReportPath
}
if ($GeneratorExit -ne 0) {
  Write-Host "Generator FAILED ($GeneratorExit). See $OutRoot\_logs\generator-direct-java.log" -ForegroundColor Red
  exit $GeneratorExit
}
Write-Step 'Generator succeeded.'

# ---- 4a2. LNCH-1 P6 (task 6.2c): print + (for -Upgrade) durably echo the migration plan --------
if ($PlanOnly -or $Upgrade) {
  if (-not (Test-Path -LiteralPath $PlanJsonPath)) {
    throw "Expected a migration plan at $PlanJsonPath but it was not written by the generator."
  }
  $PlanObj = Read-JsonFile $PlanJsonPath
  $HasDestructive = @($PlanObj.items | Where-Object { $_.destructive }).Count -gt 0

  if ($Upgrade) {
    # Echoed OUTSIDE the wiped tree, sibling to $BuildRoot (not nested inside it), per the plan's
    # explicit ask -- so this plan survives the NEXT build's wipe too, as an operator-facing trail.
    # $PlanArtifactDir is the same directory C4's preserved compiled-model snapshot lives in, and
    # these echoes are what C4 reads as "evidence of a prior deployment" -- computed once, above.
    New-Item -ItemType Directory -Force -Path $PlanArtifactDir | Out-Null
    $SafeFingerprint = ("$($PlanObj.toFingerprint)" -replace '[^A-Za-z0-9_.-]', '_')
    $PlanEchoPath = Join-Path $PlanArtifactDir "plan-$SafeFingerprint.json"
    Copy-Item -LiteralPath $PlanJsonPath -Destination $PlanEchoPath -Force
    Write-Step "Migration plan echoed outside the wiped tree: $PlanEchoPath"
  }

  Write-MigrationPlanTable -PlanObj $PlanObj

  if ($PlanOnly) {
    Write-Host ''
    if ($HasDestructive) {
      Write-Host '-PlanOnly: destructive item(s) present -- exiting 1 (script-friendly gate signal).' -ForegroundColor Yellow
      exit 1
    }
    Write-Host '-PlanOnly: no destructive items -- exiting 0.' -ForegroundColor Green
    exit 0
  }
}

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

# ---- 4c. emit a workspace::Menu seed for declared companion pages + menu tree ----
# definition\pages.json is an AppGen-authoring-only convention (like the existing 'console'
# field further down this script) -- an array of { path, label, requiredRole, ordinal } for
# hand-authored web/ pages. definition\menu.json is a sibling, optional convention: a
# multi-level tree ({ label, ordinal, requiredRole?, kind?, target?, children? }) that lets an
# app author declare hierarchical navigation (System sub-menu groups, e.g. WmsOffice's
# Demanda/Cadastros/Consultas/Desenvolvimento). Neither is ever forwarded to the generator
# (which has no visibility into web/ or navigation hierarchy at all).
# IMPORTANT: this must NOT touch the generator's own npdev-generated/.../workspace-menu-seed.json
# -- that tree is hash-verified at startup by StrictExecutionValidator, and editing it after
# generation trips a "strict execution signature mismatch" (confirmed live). Instead this writes
# a SEPARATE seed file directly under the App module's own (non-generated, AppGen-owned)
# src/main/resources/, the same boundary the web/ mount above already uses. WorkspaceMenuSeeder
# (NPDevRuntimeHost) reads both files at startup and inserts the union, only while the table is
# empty for the tenant -- both are optional, so an app with neither is unaffected.
#
# menu.json rows are flattened depth-first into rows carrying a synthetic 'key'/'parentKey' pair
# (not persisted columns -- WorkspaceMenuSeeder resolves them into real parent_menu_id values at
# seed time and drops them). pages.json entries are appended afterwards as extra roots, so an app
# with only pages.json (no menu.json) produces the exact same flat seed as before this feature.
$PagesJsonPath = Join-Path $Definition 'pages.json'
$MenuJsonPath  = Join-Path $Definition 'menu.json'
$hasPages = Test-Path -LiteralPath $PagesJsonPath
$hasMenu  = Test-Path -LiteralPath $MenuJsonPath
if ($hasPages -or $hasMenu) {
  $menuSeedRows = @()
  $menuTreePageTargets = New-Object 'System.Collections.Generic.HashSet[string]'
  $nextOrdinal = 1000
  $nextKey = 0

  if ($hasMenu) {
    Write-Step "Flattening declared menu hierarchy from $MenuJsonPath."
    $declaredMenuTree = @(Read-JsonFile $MenuJsonPath)

    function Add-MenuNode {
      param($Node, [string]$ParentKey)
      $script:nextKey += 1
      $ownKey = "n$($script:nextKey)"
      $nodeKind = if ($null -ne $Node.kind) { $Node.kind } elseif ($Node.children) { 'GROUP' } else { 'BUSINESS' }
      $nodeTarget = if ($null -ne $Node.target) { $Node.target } else { '' }
      $nodeRequiredRole = if ($null -ne $Node.requiredRole) { $Node.requiredRole } else { $null }
      if ($nodeKind -eq 'PAGE' -and $nodeTarget) { [void]$script:menuTreePageTargets.Add($nodeTarget) }
      $script:menuSeedRows += [ordered]@{
        key          = $ownKey
        parentKey    = $ParentKey
        label        = $Node.label
        target       = $nodeTarget
        kind         = $nodeKind
        ordinal      = if ($null -ne $Node.ordinal) { $Node.ordinal } else { 0 }
        requiredRole = $nodeRequiredRole
        visible      = $true
      }
      if ($Node.children) {
        foreach ($child in @($Node.children)) { Add-MenuNode -Node $child -ParentKey $ownKey }
      }
    }

    foreach ($rootNode in $declaredMenuTree) { Add-MenuNode -Node $rootNode -ParentKey $null }
  }

  if ($hasPages) {
    Write-Step "Emitting workspace::Menu seed for declared companion pages from $PagesJsonPath."
    $declaredPages = @(Read-JsonFile $PagesJsonPath)
    # A page already placed inside the menu.json tree (as a PAGE node with a matching target) is
    # skipped here so it isn't seeded twice -- once nested in its declared group, once as a root.
    foreach ($page in $declaredPages) {
      if ($menuTreePageTargets.Contains($page.path)) { continue }
      $pageOrdinal = if ($null -ne $page.ordinal) { $page.ordinal } else { $nextOrdinal }
      $pageRequiredRole = if ($null -ne $page.requiredRole) { $page.requiredRole } else { $null }
      $menuSeedRows += [ordered]@{
        label        = $page.label
        target       = $page.path
        kind         = 'PAGE'
        ordinal      = $pageOrdinal
        requiredRole = $pageRequiredRole
        visible      = $true
      }
      $nextOrdinal += 10
    }
  }

  $PageSeedDst = Join-Path $GeneratedAppRoot 'src\main\resources\npdev-seed\workspace-menu-pages-seed.json'
  Write-JsonFile $menuSeedRows $PageSeedDst
  Write-Step "workspace-menu-pages-seed.json written with $($menuSeedRows.Count) row(s)."
}

# ---- 4d. copy definition/seeds/*.json (smart/raw mock-data seeds) into npdev-seed/data-seeds/ ----
# definition/seeds/<id>.json is an AppGen-authoring-only convention (like pages.json/menu.json
# above) -- never forwarded to the generator. Copied verbatim (no flattening, unlike menu.json)
# into a classpath folder SeedDataService (NPDevRuntimeHost) reads at runtime via
# DataSeedAdminController (GET /api/admin/seeds, POST /api/admin/seeds/{id}/run). A generated
# index.json manifest lists id/label/description/kind for each file so the runtime doesn't need
# to scan the classpath inside a packaged jar.
$SeedsSrcDir = Join-Path $Definition 'seeds'
if (Test-Path -LiteralPath $SeedsSrcDir) {
  $SeedsDstDir = Join-Path $GeneratedAppRoot 'src\main\resources\npdev-seed\data-seeds'
  New-Item -ItemType Directory -Force -Path $SeedsDstDir | Out-Null
  Write-Step "Copying declared data seeds from $SeedsSrcDir."
  $seedManifest = @()
  Get-ChildItem -LiteralPath $SeedsSrcDir -Filter '*.json' -File | ForEach-Object {
    $seedJson = Read-JsonFile $_.FullName
    if (-not $seedJson.id) { throw "Seed file $($_.FullName) is missing required 'id'." }
    if ($seedJson.id -ne [System.IO.Path]::GetFileNameWithoutExtension($_.Name)) {
      throw "Seed file $($_.FullName): 'id' ($($seedJson.id)) must match the filename stem."
    }
    Copy-Item -LiteralPath $_.FullName -Destination $SeedsDstDir -Force
    $seedManifest += [ordered]@{
      id          = $seedJson.id
      label       = $seedJson.label
      description = $seedJson.description
      kind        = if ($seedJson.kind) { $seedJson.kind } else { 'smart' }
    }
  }
  Write-JsonFile $seedManifest (Join-Path $SeedsDstDir 'index.json')
  Write-Step "data-seeds/index.json written with $($seedManifest.Count) seed(s)."
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
  # R-G1 (docs/REMEDIATION_PLAN.md): Check-Provenance.ps1 needs the app's SOURCE web/ directory
  # (where *.panel.json manifests are authored, in AppGen/apps/<App>/web) -- distinct from
  # $GeneratedAppRoot's copy under src/main/resources/static, which is a build artifact, not the
  # source of truth an author edits. Blank when the app has no web/ directory at all.
  webSourceDir = $(if (Test-Path -LiteralPath $WebSrc) { $WebSrc } else { '' })
  productRepo = $ProductRepo
}
Write-JsonFile $Plan (Join-Path $OpsDir 'app-plan.json')

# ---- resolved DB plan + environment lifecycle (H2Server / InMemory) --------
$DbDef = Read-JsonFile $DbDefinitionPath
$Engine = "$($DbDef.database.engine)"
$JdbcUrl = "$($DbDef.database.jdbcUrl)"
$H2Port = 9092
# PORT-1. Was `Join-Path 'D:\WorkSpace\NPDev\Build\databases' $AppId` -- this machine's drive letter,
# written as a default in a script that ships to everyone, which is REG-144's family in a place its
# eleven-site sweep did not reach. It is now the SAME anchor the generator uses (<FinalApp>/data), so
# this toolbox and the app it operates cannot name two different databases -- the QUAL-3 defect.
#
# The old jdbcUrl-derived override is deleted rather than adapted: it existed to recover the absolute
# root from an H2Server URL, and the URL is app-relative now, so `Split-Path -Parent` on it would
# produce a relative fragment that resolves against whatever directory the caller happened to be in.
$DataRoot = Join-Path $GeneratedAppRoot 'data'
if ($JdbcUrl -match 'tcp://localhost:(\d+)/') { $H2Port = [int]$Matches[1] }
$DbPlan = [ordered]@{
  engine = $Engine; appId = $AppId; serverPort = $ServerPort; apiKey = $ApiKey
  hostPort = $H2Port; resolvedDataRoot = $DataRoot; jdbcUrl = $JdbcUrl
  resolvedDatabaseName = "$($DbDef.database.databaseName)"
  # The H2Server baseDir. NOT the data root: the app's URL carries './data/<db>', which H2 resolves
  # SERVER-side, so a server anchored at <App>/data would open <App>/data/data/<db> -- a second,
  # silently-created, empty database.
  appRoot = $GeneratedAppRoot
  runtimeHostLibsDir = $RuntimeHostLibsDir
}
Write-JsonFile $DbPlan (Join-Path $OpsDir 'resolved-db-plan.json')

$StartEnv = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'resolved-db-plan.json') | ConvertFrom-Json
if ($plan.engine -eq 'InMemory') { Write-Host 'InMemory: no environment to start.'; exit 0 }
if ($plan.engine -eq 'H2Server') {
  New-Item -ItemType Directory -Force -Path $plan.resolvedDataRoot | Out-Null
  # REG-144's family again: this used to search a literal 'D:\WorkSpace\NPDev\Build'. The libs
  # directory THIS app was staged against travels in the plan; the gradle cache stays as the
  # fallback, since that is where the jar actually comes from on a fresh machine.
  $h2SearchRoots = @($plan.runtimeHostLibsDir, (Join-Path $env:USERPROFILE '.gradle\caches')) |
                   Where-Object { $_ -and (Test-Path -LiteralPath $_) }
  $jar = @(Get-ChildItem -Path $h2SearchRoots -Recurse -Filter 'h2-2*.jar' -ErrorAction SilentlyContinue) |
         Where-Object { $_.FullName -notlike '*\gradle-8*\lib\*' -and $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($null -eq $jar) { throw 'No standalone h2-2*.jar (binary, non-sources/javadoc) found under Build or ~/.gradle. Build an app once to populate the gradle cache.' }
  $pidFile = Join-Path $PSScriptRoot 'h2server.pid'
  $logFile = Join-Path $PSScriptRoot 'h2server.log'
  if (Test-Path -LiteralPath $pidFile) {
    $p = Get-Process -Id ([int](Get-Content -Raw -LiteralPath $pidFile)) -ErrorAction SilentlyContinue
    if ($null -ne $p) { Write-Host "H2Server already running (PID $($p.Id))."; exit 0 }
  }
  $args = @('-cp', $jar.FullName, 'org.h2.tools.Server', '-tcp', '-tcpPort', [string]$plan.hostPort, '-tcpAllowOthers', '-ifNotExists', '-baseDir', $plan.appRoot)
  $proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.appRoot -PassThru -WindowStyle Hidden -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $PSScriptRoot 'h2server.err.log')
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

try {
  $npdevProgress = [ordered]@{ schemaVersion = 'npdev-run-app-progress.v1'; phase = 'BUILD'; updatedAt = (Get-Date).ToUniversalTime().ToString('o'); pid = $PID }
  ($npdevProgress | ConvertTo-Json) | Set-Content -LiteralPath (Join-Path $plan.appRoot 'npdev-run-app-progress.json') -Encoding UTF8
} catch { }
Set-Location $plan.appRoot
Write-Host "Building $($plan.appName) at $($plan.appRoot)"
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
if ($null -eq $jar) { Write-Host 'Build OK but runnable jar not found.' -ForegroundColor Yellow; exit 1 }
Write-Host "Build OK. Runnable jar: $($jar.FullName)"
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Build-App.ps1') -Value $BuildApp -Encoding UTF8

# REG-152: this pipeline generates its own, separate _ops toolbox and never called
# OperationalRunbookEmitter's Ensure-NpdevApiKey (R7 Stage C) at all -- an app launched through it
# still booted with application-dev.yml's live, published dev-key/api-dev ADMIN mapping. Same
# algorithm, same secrets\api-key.env file format (NPDEV_AUTH_API_KEYS=<key>=dev:developer:admin)
# as the Java-emitted version in OperationalRunbookEmitter.API_KEY_PROVISIONER, so both pipelines
# idempotently share ONE key per app (app-plan.json's appRoot and OperationalRunbookEmitter's
# finalAppRoot are the same directory -- $OutRoot\App). Kept as its own copy rather than shared
# tooling: every emitted _ops script here is deliberately self-contained (see the
# Get-NPDevGradleWrapperExecutable precedent above), since a generated app's toolbox must not
# depend on dot-sourcing anything from the platform repo.
$ApiKeyProvisioner = @'
function Ensure-NpdevApiKey {
  param([string]$AppRoot)
  $secretsDir = Join-Path $AppRoot 'secrets'
  $keyFile = Join-Path $secretsDir 'api-key.env'
  if (-not (Test-Path -LiteralPath $keyFile)) {
    if (-not (Test-Path -LiteralPath $secretsDir)) { New-Item -ItemType Directory -Force -Path $secretsDir | Out-Null }
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $key = ([Convert]::ToBase64String($bytes) -replace '[^a-zA-Z0-9]', '')
    Set-Content -LiteralPath $keyFile -Value ('NPDEV_AUTH_API_KEYS=' + $key + '=dev:developer:admin') -Encoding UTF8 -NoNewline
    Write-Host ''
    Write-Host '==========================================================================' -ForegroundColor Yellow
    Write-Host 'Generated a new admin API key for this app (printed once, saved to:' -ForegroundColor Yellow
    Write-Host "  $keyFile" -ForegroundColor Yellow
    Write-Host "X-Api-Key: $key" -ForegroundColor Yellow
    Write-Host '==========================================================================' -ForegroundColor Yellow
    Write-Host ''
  }
  foreach ($rawLine in (Get-Content -LiteralPath $keyFile)) {
    $line = $rawLine.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
      $parts = $line.Split('=', 2)
      $name = $parts[0].Trim()
      if ($name) { Set-Item -Path ("env:" + $name) -Value $parts[1].Trim() }
    }
  }
}

'@

$StartApp = $ApiKeyProvisioner + @'
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
# Start-Process -RedirectStandardOutput overwrites app.out.log on every restart -- archive whatever
# was in it first (e.g. a first-boot SUPER USER KEY banner) so a later restart never destroys the
# only place a one-time value like that was ever shown. app.out.log itself still only ever shows
# the CURRENT run's live output, unchanged.
if (Test-Path -LiteralPath $logFile) {
  $historyFile = Join-Path $PSScriptRoot 'app.out.history.log'
  Add-Content -LiteralPath $historyFile -Value "`n----- run ending $(Get-Date -Format o) -----"
  Get-Content -LiteralPath $logFile -Raw -ErrorAction SilentlyContinue | Add-Content -LiteralPath $historyFile
}
# Agent proxy: optional per-app provider credentials, loaded into THIS process so Start-Process's
# child inherits them. Absent on every app that has not opted in, and absent is not an error -- the
# Agent Prompter page falls back to compose-and-copy. Kept behaviourally identical to the same block
# in OperationalRunbookEmitter.runFinalAppScript(); both are launchers for the same app, and an app
# whose proxy works under one launcher and not the other is worse than one where it never works.
$secretsEnv = Join-Path $plan.appRoot 'secrets/agent-proxy.env'
if (Test-Path -LiteralPath $secretsEnv) {
  $loadedNames = @()
  foreach ($rawLine in (Get-Content -LiteralPath $secretsEnv)) {
    $line = $rawLine.Trim()
    if ($line -and -not $line.StartsWith('#') -and $line.Contains('=')) {
      $parts = $line.Split('=', 2)
      $name = $parts[0].Trim()
      if ($name) {
        Set-Item -Path ("env:" + $name) -Value $parts[1].Trim()
        $loadedNames += $name
      }
    }
  }
  # NAMES only -- never values. app.out.log is archived on restart and collected by log bundles.
  Write-Host ("Loaded " + $loadedNames.Count + " secret(s) from " + $secretsEnv + ": " + ($loadedNames -join ', '))
}
Ensure-NpdevApiKey -AppRoot $plan.appRoot
$args = @('-jar', $jar.FullName, "--server.port=$($plan.serverPort)", "--spring.profiles.active=$($plan.springProfiles)")
$proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.appRoot -PassThru -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $PSScriptRoot 'app.err.log') -WindowStyle Hidden
$proc.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
Write-Host "Started PID $($proc.Id). Logs: $logFile"
try {
  $npdevProgress = [ordered]@{ schemaVersion = 'npdev-run-app-progress.v1'; phase = 'BOOT'; updatedAt = (Get-Date).ToUniversalTime().ToString('o'); pid = $PID }
  ($npdevProgress | ConvertTo-Json) | Set-Content -LiteralPath (Join-Path $plan.appRoot 'npdev-run-app-progress.json') -Encoding UTF8
} catch { }
Write-Host 'Waiting for health...'
# /actuator/health, not /api/flows -- it needs no credential under any auth.mode (apiKey or jwt),
# unlike /api/flows which 401s once an app switches to jwt (X-Api-Key stops being valid), which
# previously made this loop report a false "did not report healthy" for a genuinely-up app.
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  try { $h = Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/actuator/health" -TimeoutSec 3; if ($h.status -eq 'UP') { $ok = $true; break } } catch { }
}
if ($ok) {
  Write-Host "App is UP at $($plan.baseUrl)/actuator/health"
  try {
    $npdevProgress = [ordered]@{ schemaVersion = 'npdev-run-app-progress.v1'; phase = 'READY'; updatedAt = (Get-Date).ToUniversalTime().ToString('o'); pid = $PID }
    ($npdevProgress | ConvertTo-Json) | Set-Content -LiteralPath (Join-Path $plan.appRoot 'npdev-run-app-progress.json') -Encoding UTF8
  } catch { }
} else { Write-Host 'App did not report healthy in time; check logs.' -ForegroundColor Yellow }
# On a first-ever boot, SuperUserBootstrapper writes the one-time Super User key to
# SUPER_USER_KEY.txt in the app's own working directory (it doesn't know about _ops). Relocate it
# here so there's exactly ONE fixed, documented place to look -- the same path control-panel.html's
# own unlock instructions point to. No-op on every later restart (the file won't exist).
$keyFileSource = Join-Path $plan.appRoot 'SUPER_USER_KEY.txt'
if (Test-Path -LiteralPath $keyFileSource) {
  $keyFileDest = Join-Path $PSScriptRoot 'SUPER_USER_KEY.txt'
  Move-Item -LiteralPath $keyFileSource -Destination $keyFileDest -Force
  Write-Host ''
  Write-Host "First boot: your Super User key is saved at $keyFileDest" -ForegroundColor Green
  Write-Host 'Open that file and paste its contents into control-panel.html to unlock it.' -ForegroundColor Green
}
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Start-App.ps1') -Value $StartApp -Encoding UTF8

# SER-P6.4 (Surface 2): sibling of Build-App.ps1/Start-App.ps1 -- same jar-lookup pattern as
# Start-App.ps1, but runs the jar ONCE in the FOREGROUND (not Start-Process/backgrounded) so this
# script can capture the app's own verdict exit code and propagate it. Deliberately does NOT call
# Start-Environment.ps1 -- -ImpactOnly is a pre-deploy check against the TARGET's already-live
# database, not a fresh local boot. npdev.schema.lifecycle.mode=REPORT_ONLY makes
# SchemaLifecycleExecutor compute + print the impact report and System.exit before any DDL/write and
# before the web server binds a port, so nothing long-running is left behind.
$ImpactOnlyApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$jar = Get-ChildItem -LiteralPath $plan.appRoot -Recurse -Filter 'FinalExec-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.FullName -like '*\build\libs\*' -and $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
if ($null -eq $jar) { Write-Host 'Runnable jar not found. Run Build-App.ps1 first.' -ForegroundColor Red; exit 1 }
Write-Host "Computing schema impact for $($plan.appName) against its configured live database (zero writes)..."
# REG-152 review: deliberately NOT calling Ensure-NpdevApiKey here. REPORT_ONLY mode never sends
# an HTTP request or checks X-Api-Key -- SchemaLifecycleExecutor exits before the web server binds
# a port -- so the key is never consumed, and calling the provisioner would perform an
# unnecessary filesystem write against a script whose own banner promises "zero writes".
# StartupValidator still passes: application-dev.yml's own npdev.auth.api-keys mapping (left
# untouched by design, see application-dev.yml's own comments) is a valid mapping on its own.
$javaArgs = @("-Dnpdev.schema.lifecycle.mode=REPORT_ONLY", '-jar', $jar.FullName,
              "--server.port=$($plan.serverPort)", "--spring.profiles.active=$($plan.springProfiles)")
& java @javaArgs
$code = $LASTEXITCODE
switch ($code) {
  0 { Write-Host "Impact: NO_CHANGES/SAFE (exit 0)." -ForegroundColor Green }
  2 { Write-Host "Impact: NEEDS_ATTENTION (exit 2) -- review the table above." -ForegroundColor Yellow }
  3 { Write-Host "Impact: DESTRUCTIVE (exit 3) -- review the table above; an acknowledgment token is required to deploy." -ForegroundColor Red }
  default { Write-Host "Impact check did not complete cleanly (exit $code) -- see output above." -ForegroundColor Red }
}
exit $code
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Impact-Only.ps1') -Value $ImpactOnlyApp -Encoding UTF8

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

# Recovery for a lost Super User key: no network endpoint can safely do this (there's no
# authenticated Super User yet to ask, same chicken-and-egg BootstrapAdminController has), so this
# requires filesystem/process access to the server -- the appropriate trust boundary for "reissue
# the app's own master credential." Starts the app once with npdev.superuser.force-reissue=true
# (SuperUserBootstrapper revokes any existing Super User credential then issues a fresh one),
# captures the new key straight from this one run's log, then restarts normally.
$ReissueSuperUserKey = $ApiKeyProvisioner + @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$pidFile = Join-Path $PSScriptRoot 'app.pid'
if (Test-Path -LiteralPath $pidFile) {
  Write-Host 'Stopping the app first...'
  & (Join-Path $PSScriptRoot 'Stop-App.ps1') | Out-Null
}
$startEnv = Join-Path $PSScriptRoot 'Start-Environment.ps1'
if (Test-Path -LiteralPath $startEnv) { & $startEnv }
$jar = Get-ChildItem -LiteralPath $plan.appRoot -Recurse -Filter 'FinalExec-*.jar' -ErrorAction SilentlyContinue |
       Where-Object { $_.FullName -like '*\build\libs\*' -and $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
if ($null -eq $jar) { Write-Host 'Runnable jar not found. Run Build-App.ps1 first.' -ForegroundColor Red; exit 1 }
$logFile = Join-Path $PSScriptRoot 'app.out.log'
$keyFileSource = Join-Path $plan.appRoot 'SUPER_USER_KEY.txt'
Remove-Item -LiteralPath $keyFileSource -Force -ErrorAction SilentlyContinue
Write-Host 'Starting the app once with npdev.superuser.force-reissue=true...'
Ensure-NpdevApiKey -AppRoot $plan.appRoot
$args = @('-jar', $jar.FullName, "--server.port=$($plan.serverPort)", "--spring.profiles.active=$($plan.springProfiles)", '--npdev.superuser.force-reissue=true')
$proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.appRoot -PassThru -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $PSScriptRoot 'app.err.log') -WindowStyle Hidden
$proc.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
$newKey = $null
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  if (Test-Path -LiteralPath $keyFileSource) { $newKey = (Get-Content -Raw -LiteralPath $keyFileSource).Trim(); break }
}
& (Join-Path $PSScriptRoot 'Stop-App.ps1') | Out-Null
if ($null -eq $newKey) {
  Write-Host 'Did not see SUPER_USER_KEY.txt appear in time -- check app.out.log directly.' -ForegroundColor Red
  exit 1
}
Remove-Item -LiteralPath $keyFileSource -Force -ErrorAction SilentlyContinue
$keyFileDest = Join-Path $PSScriptRoot 'SUPER_USER_KEY.txt'
Set-Content -LiteralPath $keyFileDest -Value $newKey -Encoding UTF8
Write-Host ''
Write-Host "NEW Super User key saved at $keyFileDest" -ForegroundColor Green
Write-Host "  $newKey"
Write-Host ''
Write-Host 'Restarting normally...'
& (Join-Path $PSScriptRoot 'Start-App.ps1')
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Reissue-SuperUserKey.ps1') -Value $ReissueSuperUserKey -Encoding UTF8

$StatusApp = @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
try { $h = Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/actuator/health" -TimeoutSec 3; if ($h.status -eq 'UP') { Write-Host "UP   - $($plan.baseUrl)/actuator/health reports UP" } else { Write-Host "DOWN - $($plan.baseUrl)/actuator/health reports $($h.status)" } }
catch { Write-Host "DOWN - $($plan.baseUrl)/actuator/health not reachable" }
exit 0
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Status-App.ps1') -Value $StatusApp -Encoding UTF8

# Data-driven smoke test: reads ..\Input\smoke-plan.json if present.
$TestApp = $ApiKeyProvisioner + @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json
$inputRoot = Join-Path (Split-Path -Parent $PSScriptRoot) 'Input'
$inputDir = Join-Path $inputRoot 'input'
$base = $plan.baseUrl
Ensure-NpdevApiKey -AppRoot $plan.appRoot
$headers = @{ 'X-Api-Key' = $env:NPDEV_AUTH_API_KEYS.Split('=', 2)[0] }
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

# docs/REMEDIATION_PLAN.md R-G1: the panel-provenance impact gate (check-panel-provenance-impact.py,
# F4) proved itself live (a real field rename against a real bundle named the exact broken screen)
# but was never wired anywhere runnable -- "the demo proved the gun fires; nobody loaded it." This
# gives it the same home every other lifecycle script already has: it logs in with THIS app's own
# credentials, fetches the live UI-contract bundle, and runs the gate against THIS app's own web/
# source directory (where *.panel.json manifests are authored). A field rename followed by the
# normal rebuild-and-restart now fails here, without anyone remembering to run anything separately.
$CheckProvenance = $ApiKeyProvisioner + @'
$ErrorActionPreference = 'Stop'
$plan = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-plan.json') | ConvertFrom-Json

if (-not $plan.webSourceDir -or -not (Test-Path -LiteralPath $plan.webSourceDir)) {
  Write-Host 'No web/ source directory recorded for this app -- nothing to check.' -ForegroundColor Yellow
  exit 0
}
$manifestCount = @(Get-ChildItem -LiteralPath $plan.webSourceDir -Filter '*.panel.json' -ErrorAction SilentlyContinue).Count
if ($manifestCount -eq 0) {
  Write-Host "No *.panel.json manifests under $($plan.webSourceDir) -- nothing to check yet. Run bootstrap-panel-provenance.py to draft one." -ForegroundColor Yellow
  exit 0
}

$base = $plan.baseUrl
$bundleUri = "$base/api/v1/runtime/metadata/ui/bundle"
$bundle = $null
Ensure-NpdevApiKey -AppRoot $plan.appRoot
$apiKey = $env:NPDEV_AUTH_API_KEYS.Split('=', 2)[0]
try {
  $bundle = Invoke-RestMethod -Method GET -Uri $bundleUri -Headers @{ 'X-Api-Key' = $apiKey } -TimeoutSec 15
} catch {
  # X-Api-Key doesn't authenticate a JWT-mode app (e.g. WmsOffice) -- fall back to a bearer token
  # an operator drops at _ops\jwt-token.txt after logging in once. No attempt to automate that
  # login here across every app's auth setup: an honest, documented fallback beats a guessed one.
  $tokenFile = Join-Path $PSScriptRoot 'jwt-token.txt'
  if (Test-Path -LiteralPath $tokenFile) {
    $token = (Get-Content -Raw -LiteralPath $tokenFile).Trim()
    try {
      $bundle = Invoke-RestMethod -Method GET -Uri $bundleUri -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 15
    } catch {
      Write-Host "Bundle fetch failed with both X-Api-Key and the bearer token in jwt-token.txt: $($_.Exception.Message)" -ForegroundColor Red
      exit 2
    }
  } else {
    Write-Host "Bundle fetch failed with X-Api-Key: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "If this app uses JWT auth, log in once and save the token to: $tokenFile" -ForegroundColor Yellow
    exit 2
  }
}

$bundlePath = Join-Path $PSScriptRoot 'ui-contract-bundle.json'
$bundle | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $bundlePath -Encoding UTF8

$py = @('python', 'python3') | Where-Object { Get-Command $_ -ErrorAction SilentlyContinue } | Select-Object -First 1
if (-not $py) { Write-Host 'python not found on PATH.' -ForegroundColor Red; exit 2 }

$gateScript = Join-Path $plan.productRepo 'scripts\quality\check-panel-provenance-impact.py'
if (-not (Test-Path -LiteralPath $gateScript)) { Write-Host "Gate script not found: $gateScript" -ForegroundColor Red; exit 2 }

& $py $gateScript --root $plan.webSourceDir --metadata $bundlePath
exit $LASTEXITCODE
'@
Set-Content -LiteralPath (Join-Path $OpsDir 'Check-Provenance.ps1') -Value $CheckProvenance -Encoding UTF8

# Make the generator's older convenience scripts delegate to the guarded ops scripts,
# so running either name (Build-FinalApp.ps1 / Run-FinalApp.ps1) is safe.
$buildShim = "# Deprecated name -> delegates to the guarded Build-App.ps1 (detects a running app first).`n& (Join-Path `$PSScriptRoot 'Build-App.ps1') @args`nexit `$LASTEXITCODE`n"
$runShim   = "# Deprecated name -> delegates to Start-App.ps1 (starts the DB environment, guards duplicates).`n& (Join-Path `$PSScriptRoot 'Start-App.ps1') @args`nexit `$LASTEXITCODE`n"
foreach ($shimPair in @(@('Build-FinalApp.ps1', $buildShim), @('Run-FinalApp.ps1', $runShim))) {
  $shimTarget = Join-Path $OpsDir $shimPair[0]
  if (Test-Path -LiteralPath $shimTarget) { Set-Content -LiteralPath $shimTarget -Value $shimPair[1] -Encoding UTF8 }
}

$OpsReadme = "# $AppId - operations toolbox`n`nAll scripts read app-plan.json and run with no arguments. Port $ServerPort, base http://localhost:$ServerPort.`n`n| Script | Purpose |`n| --- | --- |`n| Build-App.ps1 | gradle clean build -> FinalExec jar |`n| Start-App.ps1 | start in background, wait for /api/flows |`n| Stop-App.ps1 | stop background app |`n| Status-App.ps1 | report up/down |`n| Test-App.ps1 | data-driven smoke (reads Input\smoke-plan.json) |`n| Check-Provenance.ps1 | panel-provenance impact gate against the live bundle (docs/REMEDIATION_PLAN.md R-G1); needs the app running (Start-App.ps1 first) |`n`n``````powershell`n.\Build-App.ps1; .\Start-App.ps1; .\Test-App.ps1; .\Check-Provenance.ps1; .\Stop-App.ps1`n```````n`nREG-111: while a step above is running, `App\npdev-run-app-progress.json` (schemaVersion npdev-run-app-progress.v1: phase/updatedAt/pid) advances GENERATE -> BUILD -> BOOT -> READY -- the same shape and file 'npdev run app' writes, so one 'tail -f' works regardless of which entry point is driving the run. Best-effort only; a stalled file that stops updating for longer than the step normally takes is the 'stuck, not just slow' signal.`n"
Set-Content -LiteralPath (Join-Path $OpsDir 'README.md') -Value $OpsReadme -Encoding UTF8

# ---- emit interactive app-info page (Property/Value table + copy/open) -----
$companionFiles = @()
if (Test-Path -LiteralPath $WebSrc) { $companionFiles = @(Get-ChildItem -LiteralPath $WebSrc -File | Select-Object -ExpandProperty Name) }
$companionFiles += 'info.html'
$ConsolePort = $ServerPort + 100
$consoleLaunch = if ($ConsoleMode -ne 'none') { "& '$(Join-Path $OpsDir 'Serve-AppConsole.ps1')'" } else { '' }
# Optional info-page rows, left blank/absent unless the app author declared them under
# config.json's "defaults" bag (a free-form settingId -> value map -- no schema change needed).
$loginPath = ''
$homePath = ''
if ($Config.defaults) {
  $loginPathProp = $Config.defaults.PSObject.Properties['auth.loginPath']
  if ($loginPathProp -and $loginPathProp.Value) { $loginPath = "$($loginPathProp.Value)" }
  $homePathProp = $Config.defaults.PSObject.Properties['ui.homePath']
  if ($homePathProp -and $homePathProp.Value) { $homePath = "$($homePathProp.Value)" }
}
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
  Concepts         = @($Model.concepts | ForEach-Object {
    if ($_.PSObject.Properties.Name -contains 'name' -and $_.name) {
      $_.name
    } elseif ($_.PSObject.Properties.Name -contains '$ref') {
      # one-file-per-concept authoring: model.json's concepts array holds $ref
      # pointers, not inline objects, so the name must be read from the target file.
      (Read-JsonFile (Join-Path $StagedInput $_.'$ref')).name
    }
  })
  CompanionFiles   = $companionFiles
  BuilderName      = (Split-Path -Leaf $AppFolder)
  ConsoleLaunch    = $consoleLaunch
  ConsolePort      = $(if ($ConsoleMode -ne 'none') { $ConsolePort } else { 0 })
  LoginPath        = $loginPath
  HomePath         = $homePath
}
& (Join-Path $PSScriptRoot 'New-AppInfoPage.ps1') @infoArgs
Write-Step "Emitted interactive info page: http://localhost:$ServerPort/info.html"

& (Join-Path $PSScriptRoot 'New-ControlPanelPage.ps1') `
  -StaticDir (Join-Path $GeneratedAppRoot 'src\main\resources\static') `
  -AppId $AppId -Port $ServerPort -OutRoot $OutRoot
Write-Step "Emitted ControlPanel page: http://localhost:$ServerPort/control-panel.html"

# info.html links to app-tree.html unconditionally, so it must actually exist -- emit it here
# too (cheap: reads model.json/config.json only, no live app/DB needed).
& (Join-Path $PSScriptRoot 'New-AppTreePage.ps1') `
  -AppFolder $AppFolder -StaticDir (Join-Path $GeneratedAppRoot 'src\main\resources\static') -AppId $AppId
Write-Step "Emitted app tree page: http://localhost:$ServerPort/app-tree.html"

# info.html links to agent-prompter.html unconditionally too -- same reasoning as app-tree.html
# above. Cheap: no per-app data at generation time, everything it shows is fetched at page-load
# from app-tree.json/info.json.
& (Join-Path $PSScriptRoot 'New-AgentPrompterPage.ps1') `
  -StaticDir (Join-Path $GeneratedAppRoot 'src\main\resources\static') -AppId $AppId
Write-Step "Emitted Agent Prompter page: http://localhost:$ServerPort/agent-prompter.html"

# RC-A5 (Move 14 Phase B item B3): the generated properties admin surface -- one section per
# scope, one control per property, widget from type, settableAt-gated, effective value + source
# shown inline (A3's explain). Cheap to always emit (reads nothing live); the page itself renders
# "no properties declared" gracefully for an app whose model declares none.
& (Join-Path $PSScriptRoot 'New-PropertiesAdminPage.ps1') `
  -StaticDir (Join-Path $GeneratedAppRoot 'src\main\resources\static') -AppId $AppId
Write-Step "Emitted properties admin page: http://localhost:$ServerPort/properties.html"

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
  Write-Host "  & '$OpsDir\Impact-Only.ps1'   (pre-deploy: impact vs. the live database, zero writes)"
}
if ($ImpactOnly) {
  if ($GenerateOnly) { Write-Host '-ImpactOnly has no effect combined with -GenerateOnly (no jar would exist to run).' -ForegroundColor Yellow; exit 1 }
  Write-Step "-ImpactOnly: building $AppId, then running it once against its live database in REPORT_ONLY mode (zero writes)..."
  & (Join-Path $OpsDir 'Build-App.ps1')
  if ($LASTEXITCODE -ne 0) { Write-Host 'Build failed; cannot compute impact.' -ForegroundColor Red; exit $LASTEXITCODE }
  & (Join-Path $OpsDir 'Impact-Only.ps1')
  exit $LASTEXITCODE
}
exit 0
