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
  [switch]$SkipRuntimeHostLibs,
  # LNCH-1 P6 (task 6.2c). -PlanOnly: compute + print the migration plan (SAFE items plainly,
  # DESTRUCTIVE items with a red banner and the copyable ack token), then exit -- before the
  # web-asset/ops-toolbox/info-page steps -- with a script-friendly exit code (1 if any destructive
  # item is present, 0 otherwise). A full generation pass still happens (cheap, local, touches
  # nothing live) -- see this switch's usage below for why an honest plan needs one anyway.
  [switch]$PlanOnly,
  # -Upgrade: same plan computation/printing as -PlanOnly, but does NOT exit early -- the script
  # continues through its normal steps (this IS the real upgrade). Additionally captures the
  # PREVIOUS FinalApp output's canonical compiled-model.json before the wipe below destroys it,
  # threads it into the generator as --previousCompiledModel so the plan is a real diff (not a
  # fresh-install plan), and echoes migration-plan.json outside the wiped tree so it survives the
  # NEXT wipe too.
  [switch]$Upgrade,
  # -AcknowledgeDestructive <token>: threads the token into the generator's new
  # --destructiveAcknowledgment flag (LNCH-1 P6 task 6.2b), landing it verbatim in the generated
  # manifest's destructiveAcknowledgment key -- the value SchemaLifecycleExecutor's Phase 4
  # destructive-path token check reads at boot. Independent of -PlanOnly/-Upgrade (a plain build
  # with just this parameter also threads the token; no plan is computed unless -PlanOnly/-Upgrade
  # is also passed).
  [string]$AcknowledgeDestructive
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
$PreviousCompiledModelPath = $null
if ($PlanOnly -or $Upgrade) {
  $PriorCompiledModelPath = Join-Path $OutRoot 'App\npdev-generated\src\main\resources\npdev\compiled-model.json'
  if (Test-Path -LiteralPath $PriorCompiledModelPath) {
    $PlanScratchDir = Join-Path ([System.IO.Path]::GetTempPath()) 'npdev-build-npdevapp-scratch'
    New-Item -ItemType Directory -Force -Path $PlanScratchDir | Out-Null
    $PreviousCompiledModelPath = Join-Path $PlanScratchDir "$AppId-previous-compiled-model-$([Guid]::NewGuid().ToString('N')).json"
    Copy-Item -LiteralPath $PriorCompiledModelPath -Destination $PreviousCompiledModelPath -Force
    Write-Step "Captured previous compiled model before wipe: $PreviousCompiledModelPath"
  } else {
    Write-Step "No previous compiled model found at $PriorCompiledModelPath -- plan will be a fresh-install plan."
  }
}
$PlanJsonPath = Join-Path $OutRoot 'migration-plan.json'

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
  if ($PlanOnly -or $Upgrade) { $DirectGeneratorArgs += @('--schemaMigrationPlanOut', $PlanJsonPath) }
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
    $PlanEchoDir = Join-Path (Split-Path -Parent $BuildRoot) "$AppId\migration-plans"
    New-Item -ItemType Directory -Force -Path $PlanEchoDir | Out-Null
    $SafeFingerprint = ("$($PlanObj.toFingerprint)" -replace '[^A-Za-z0-9_.-]', '_')
    $PlanEchoPath = Join-Path $PlanEchoDir "plan-$SafeFingerprint.json"
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
         Where-Object { $_.FullName -notlike '*\gradle-8*\lib\*' -and $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' } |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($null -eq $jar) { throw 'No standalone h2-2*.jar (binary, non-sources/javadoc) found under Build or ~/.gradle. Build an app once to populate the gradle cache.' }
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
# Start-Process -RedirectStandardOutput overwrites app.out.log on every restart -- archive whatever
# was in it first (e.g. a first-boot SUPER USER KEY banner) so a later restart never destroys the
# only place a one-time value like that was ever shown. app.out.log itself still only ever shows
# the CURRENT run's live output, unchanged.
if (Test-Path -LiteralPath $logFile) {
  $historyFile = Join-Path $PSScriptRoot 'app.out.history.log'
  Add-Content -LiteralPath $historyFile -Value "`n----- run ending $(Get-Date -Format o) -----"
  Get-Content -LiteralPath $logFile -Raw -ErrorAction SilentlyContinue | Add-Content -LiteralPath $historyFile
}
$args = @('-jar', $jar.FullName, "--server.port=$($plan.serverPort)", "--spring.profiles.active=$($plan.springProfiles)")
$proc = Start-Process -FilePath 'java' -ArgumentList $args -WorkingDirectory $plan.appRoot -PassThru -RedirectStandardOutput $logFile -RedirectStandardError (Join-Path $PSScriptRoot 'app.err.log') -WindowStyle Hidden
$proc.Id | Set-Content -LiteralPath $pidFile -Encoding ascii
Write-Host "Started PID $($proc.Id). Logs: $logFile"
Write-Host 'Waiting for health...'
# /actuator/health, not /api/flows -- it needs no credential under any auth.mode (apiKey or jwt),
# unlike /api/flows which 401s once an app switches to jwt (X-Api-Key stops being valid), which
# previously made this loop report a false "did not report healthy" for a genuinely-up app.
$ok = $false
for ($i = 0; $i -lt 60; $i++) {
  Start-Sleep -Seconds 2
  try { $h = Invoke-RestMethod -Method GET -Uri "$($plan.baseUrl)/actuator/health" -TimeoutSec 3; if ($h.status -eq 'UP') { $ok = $true; break } } catch { }
}
if ($ok) { Write-Host "App is UP at $($plan.baseUrl)/actuator/health" } else { Write-Host 'App did not report healthy in time; check logs.' -ForegroundColor Yellow }
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
$ReissueSuperUserKey = @'
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
