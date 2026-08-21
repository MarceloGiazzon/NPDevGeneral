<#
.SYNOPSIS
Wave 1.3 (LC-C2, MASTER_AI_PLATFORM_PROGRAMME_v2.md): apply a METADATA_ONLY model change to an
already-built, already-running AppGen FinalApp without a Gradle build -- classify the change first
(REG-102's fix, :generator:classifyModelChange), refuse anything that isn't METADATA_ONLY, then
overwrite the app's external compiled-model.json. R1.7 (roadmap Wave 1): before restarting, tries
`npdev monitor hotswap` against the ALREADY-RUNNING app -- MetadataHotSwapController#apply swaps the
descriptive metadata catalogs into the live JVM atomically, no restart. Only falls back to the
original stop/start cycle when the app isn't running, has no endpoint (pre-R1.7), or the swap is
otherwise refused -- and always says why before falling back.

.DESCRIPTION
Reuses three things that already exist rather than re-deriving them:
  1. ModelChangeClassifierMain / MigrationPlanEmitter (REG-102's fix) -- the real, no-database
     model-vs-model diff and its METADATA_ONLY/SAFE_ADDITIVE/BACKFILL_REQUIRED/MANUAL_REVIEW
     classification.
  2. NPDevModelProvider's own external-path-before-classpath lookup order (the generated app's
     compiled-model loader): it checks
     <AppRoot>\npdev-generated\src\main\resources\npdev\compiled-model.json on disk BEFORE its
     classpath-baked fallback, so overwriting that exact file and restarting the JVM is enough for
     PanelRuntime/AggregateRuntime/ProcedureRunner (everything that reads the injected CompiledModel
     bean) to see the change -- no codegen, no jar surgery, no Gradle build of the app itself.
  3. GeneratedFolderSignature.capture/write (the SAME code StrictExecutionValidator itself calls at
     boot) -- re-signs npdev-generated/ after the sanctioned compiled-model.json write. Found live,
     the hard way, on the first version of this script: StrictExecutionValidator hashes the WHOLE
     npdev-generated/ tree at every boot and refuses to start on any mismatch
     ("StrictExecutionViolationException: ... content mismatch for
     src/main/resources/npdev/compiled-model.json") -- a real, deliberate integrity guard against
     exactly an unaudited hand-edit, and it caught this fast path's first draft correctly. Re-signing
     with the validator's own algorithm (:adapters:runtime-validation:resignGeneratedFolder) is
     "I re-derived this file through a tool that reasoned about why it's safe," not a bypass.

Fast Lane plan item 1a (REG-103 follow-up, 2026-08-01): RuntimeMetadataService's separate
compiled-metadata.json / npdev/metadata/* catalogs (AI-authoring introspection, concept/panel/field
UI labels among them) now DO get refreshed here too. REG-103 (Move 13 P5.1) already wired
RuntimeMetadataService to check the same external-path-before-classpath-fallback convention for all
three of npdev.compiled-metadata.path / npdev.metadata-index.path / npdev.generated-resources.path;
this script's own classifier task grew a matching --emitMetadataTo flag (same METADATA_ONLY-gated
refusal contract as --emitCompiledModelTo) that writes those exact files to a scratch directory,
which step 4b below copies onto the app's own npdev-generated/src/main/resources/npdev/ tree before
the SAME re-signing step already used for compiled-model.json. Still deliberately narrower than
"every model.json edit becomes hot": the generated static frontend bundle
(static/npdev-business-ui/{app.js,shell.js,generated-ui-manifest.json}) has no external-path
override yet -- tracked separately as REG-109, not attempted here.

Refuses (exit 1, no file touched, no restart) unless the classification is METADATA_ONLY.

.PARAMETER AppOpsRoot
Path to the running app's _ops directory (e.g. ...\wmsoffice\_ops). Read for app-plan.json
(appRoot, baseUrl) and used to invoke Stop-App.ps1 / Start-App.ps1.

.PARAMETER CurrentModelPath
Path to the candidate model.json (the change to apply).

.PARAMETER BaselineModelPath
Path to the model.json to diff against. Defaults to the app's own currently-deployed source model
(<AppRoot>\npdev-generated\src\main\resources\npdev\model.json) -- "what's live right now."

.PARAMETER SkipRestart
Classify and (if METADATA_ONLY) write compiled-model.json, but do not stop/start the app. Useful
for the byte-identical-vs-full-regeneration comparison test, which has no running app to restart.
#>
param(
    [Parameter(Mandatory = $true)][string]$AppOpsRoot,
    [Parameter(Mandatory = $true)][string]$CurrentModelPath,
    [string]$BaselineModelPath = "",
    [switch]$SkipRestart
)
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Portable repo root (REG-144): was hardcoded to the author's D:\ layout, so the script could only
# ever run on one machine. This file lives at <repo>/scripts/appgen/, so the repo root is two
# levels up -- correct under any clone name and any drive.
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$generatorRoot = Join-Path $repo "NPDevGenerator"
$gradlew = Join-Path $generatorRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $gradlew)) { throw "Gradle wrapper not found: $gradlew" }
$kernelRoot = Join-Path $repo "NPDevKernel"
$kernelGradlew = Join-Path $kernelRoot "gradlew.bat"
if (-not (Test-Path -LiteralPath $kernelGradlew)) { throw "Gradle wrapper not found: $kernelGradlew" }

$AppOpsRoot = (Resolve-Path -LiteralPath $AppOpsRoot).Path
$planPath = Join-Path $AppOpsRoot "app-plan.json"
if (-not (Test-Path -LiteralPath $planPath)) { throw "app-plan.json not found under $AppOpsRoot -- is this a real _ops directory?" }
$plan = Get-Content -Raw -LiteralPath $planPath | ConvertFrom-Json
$appRoot = $plan.appRoot
$compiledModelDest = Join-Path $appRoot "npdev-generated\src\main\resources\npdev\compiled-model.json"

if ([string]::IsNullOrWhiteSpace($BaselineModelPath)) {
    $BaselineModelPath = Join-Path $appRoot "npdev-generated\src\main\resources\npdev\model.json"
}
if (-not (Test-Path -LiteralPath $BaselineModelPath)) { throw "Baseline model not found: $BaselineModelPath" }
$CurrentModelPath = (Resolve-Path -LiteralPath $CurrentModelPath).Path
$BaselineModelPath = (Resolve-Path -LiteralPath $BaselineModelPath).Path

$reportPath = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-metadata-fastpath-" + [Guid]::NewGuid().ToString("N") + ".json")
$metadataScratchDir = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-metadata-fastpath-catalogs-" + [Guid]::NewGuid().ToString("N"))

$sw = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "Classifying '$CurrentModelPath' against baseline '$BaselineModelPath'..."
$gradleArgs = @(
    ":generator:classifyModelChange",
    "-PcurrentPath=$CurrentModelPath",
    "-PbaselinePath=$BaselineModelPath",
    "-PreportOut=$reportPath",
    "-PemitCompiledModelTo=$compiledModelDest",
    "-PemitMetadataTo=$metadataScratchDir",
    "--console=plain"
)
Push-Location $generatorRoot
try {
    & $gradlew @gradleArgs 2>&1 | ForEach-Object { Write-Host "  $_" }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $reportPath)) {
    throw "Classifier did not produce a report at $reportPath -- see Gradle output above for the real failure."
}
$report = Get-Content -Raw -LiteralPath $reportPath | ConvertFrom-Json
Write-Host ""
Write-Host "Classification: $($report.classification)" -ForegroundColor Cyan
foreach ($reason in $report.classificationReasons) { Write-Host "  - $reason" }

if ($report.classification -ne "METADATA_ONLY") {
    Write-Host ""
    Write-Host "REFUSED: not METADATA_ONLY. compiled-model.json was NOT touched; the app was NOT restarted." -ForegroundColor Red
    Write-Host "Run a real build (Rebuild-And-Restage.ps1) for this change instead."
    exit 1
}

if (-not (Test-Path -LiteralPath $compiledModelDest)) {
    throw "Classifier reported METADATA_ONLY but did not write $compiledModelDest -- this is a real bug, not a refusal."
}
Write-Host ""
Write-Host "compiled-model.json updated: $compiledModelDest" -ForegroundColor Green

# Item 1a (REG-103 follow-up): copy compiled-metadata.json + every metadata/*.manifest.json catalog
# --emitMetadataTo just wrote to the scratch dir, onto the app's OWN npdev-generated tree, at exactly
# the relative paths RuntimeMetadataService's external-path defaults already resolve
# (npdev-generated/src/main/resources/npdev/{compiled-metadata.json,metadata/*}) -- same "external
# wins over classpath" mechanism REG-103 (Move 13 P5.1) already shipped, now actually fed.
$metadataScratchNpdevRoot = Join-Path $metadataScratchDir "src\main\resources\npdev"
$compiledMetadataScratchPath = Join-Path $metadataScratchNpdevRoot "compiled-metadata.json"
$metadataCatalogScratchDir = Join-Path $metadataScratchNpdevRoot "metadata"
if (-not (Test-Path -LiteralPath $compiledMetadataScratchPath)) {
    throw "Classifier reported METADATA_ONLY but did not write $compiledMetadataScratchPath -- this is a real bug, not a refusal."
}
if (-not (Test-Path -LiteralPath $metadataCatalogScratchDir)) {
    throw "Classifier reported METADATA_ONLY but did not write $metadataCatalogScratchDir -- this is a real bug, not a refusal."
}
$appNpdevRoot = Join-Path $appRoot "npdev-generated\src\main\resources\npdev"
$compiledMetadataDest = Join-Path $appNpdevRoot "compiled-metadata.json"
$metadataCatalogDestDir = Join-Path $appNpdevRoot "metadata"
Copy-Item -LiteralPath $compiledMetadataScratchPath -Destination $compiledMetadataDest -Force
New-Item -ItemType Directory -Force -Path $metadataCatalogDestDir | Out-Null
Get-ChildItem -LiteralPath $metadataCatalogScratchDir -File | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $metadataCatalogDestDir $_.Name) -Force
}
# NOTE: $metadataScratchDir is NOT removed here anymore -- R1.7's hot-swap attempt below needs it
# to still exist (it is the exact --emitMetadataTo directory MetadataHotSwapController#apply reads
# from, server-side, on the SAME machine). Cleaned up after that attempt, whichever way it goes.
Write-Host "compiled-metadata.json + metadata/*.manifest.json catalogs updated: $appNpdevRoot" -ForegroundColor Green

$generatedRoot = Join-Path $appRoot "npdev-generated"
Write-Host "Re-signing $generatedRoot (StrictExecutionValidator checks this at every boot)..."
Push-Location $kernelRoot
try {
    & $kernelGradlew ":adapters:runtime-validation:resignGeneratedFolder" "-PgeneratedRoot=$generatedRoot" "--console=plain" 2>&1 |
        ForEach-Object { Write-Host "  $_" }
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) {
    throw "Re-signing npdev-generated/ failed (exit $LASTEXITCODE) -- compiled-model.json was updated but the app will refuse to boot until this succeeds. See output above."
}

if ($SkipRestart) {
    Remove-Item -LiteralPath $metadataScratchDir -Recurse -Force -ErrorAction SilentlyContinue
    $sw.Stop()
    Write-Host "Elapsed (no restart, -SkipRestart): $($sw.Elapsed.TotalSeconds.ToString('0.0'))s"
    exit 0
}

# R1.7 (roadmap Wave 1): try the atomic, no-restart hot swap first -- `npdev monitor hotswap` drives
# MetadataHotSwapController#apply against the ALREADY-RUNNING app, pushing exactly the descriptive
# metadata catalogs (compiled-metadata.json, metadata/*.manifest.json) into its live JVM. This is
# the whole point: a pure label/hint edit no longer pays for a Stop-App/Start-App cycle.
#
# Silent-safe, not silent (this script's own contract): every refusal reason -- app not running, no
# SUPER_USER_KEY.txt on disk, the endpoint rejecting the classification, or the endpoint simply not
# existing on an app generated before R1.7 -- is printed before falling back, never swallowed.
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
$cliScript = Join-Path $repo "NPDevCli\npdev_cli.py"
$hotswapApplied = $false

if (Test-Path -LiteralPath $cliScript) {
    $hotswapArgs = @(
        $cliScript, "monitor", "hotswap",
        "--app-dir", $appRoot,
        "--classification", $report.classification,
        "--metadata-source-root", $metadataScratchDir,
        "--json"
    )
    foreach ($reason in $report.classificationReasons) { $hotswapArgs += @("--reason", $reason) }

    Write-Host ""
    Write-Host "Attempting metadata hot swap into the running app (no restart)..."
    $hotswapOutput = & $py @hotswapArgs 2>&1
    $hotswapExit = $LASTEXITCODE
    $hotswapResult = $null
    try { $hotswapResult = ($hotswapOutput -join "`n") | ConvertFrom-Json -ErrorAction Stop } catch { }

    if ($hotswapExit -eq 0 -and $hotswapResult -and $hotswapResult.ok) {
        $hotswapApplied = $true
        Write-Host "HOT SWAP APPLIED (generation $($hotswapResult.metadataGeneration)) -- no restart needed." -ForegroundColor Green
        foreach ($cat in $hotswapResult.catalogsUpdated) { Write-Host "  updated: $cat" }
    } elseif ($hotswapResult -and $hotswapResult.code) {
        Write-Host "Hot swap not applied ($($hotswapResult.code)): $($hotswapResult.message)" -ForegroundColor Yellow
        Write-Host "Falling back to restart." -ForegroundColor Yellow
    } else {
        Write-Host "Hot swap not applied -- 'npdev monitor hotswap' exited $hotswapExit with unparsed output:" -ForegroundColor Yellow
        $hotswapOutput | ForEach-Object { Write-Host "  $_" }
        Write-Host "Falling back to restart." -ForegroundColor Yellow
    }
} else {
    Write-Host "Hot swap skipped: $cliScript not found -- falling back to restart." -ForegroundColor Yellow
}

Remove-Item -LiteralPath $metadataScratchDir -Recurse -Force -ErrorAction SilentlyContinue

if ($hotswapApplied) {
    $sw.Stop()
    Write-Host ""
    Write-Host "TOTAL ELAPSED (classify + emit + hot swap, no restart): $($sw.Elapsed.TotalSeconds.ToString('0.0'))s" -ForegroundColor Cyan
    exit 0
}

Write-Host "Restarting the app ($AppOpsRoot)..."
& (Join-Path $AppOpsRoot "Stop-App.ps1")
& (Join-Path $AppOpsRoot "Start-App.ps1")

$sw.Stop()
Write-Host ""
Write-Host "TOTAL ELAPSED (classify + emit + restart): $($sw.Elapsed.TotalSeconds.ToString('0.0'))s" -ForegroundColor Cyan
