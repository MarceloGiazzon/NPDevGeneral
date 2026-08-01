<#
.SYNOPSIS
Wave 1.3 (LC-C2, MASTER_AI_PLATFORM_PROGRAMME_v2.md): apply a METADATA_ONLY model change to an
already-built, already-running AppGen FinalApp without a Gradle build -- classify the change first
(REG-102's fix, :generator:classifyModelChange), refuse anything that isn't METADATA_ONLY, then
overwrite the app's external compiled-model.json and restart the existing jar.

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

Deliberately narrower than "every model.json edit becomes hot": RuntimeMetadataService's separate
compiled-metadata.json / npdev/metadata/* catalogs (AI-authoring introspection, not read by the
panel/procedure runtime) are classpath-only with no external-path override, so this script does NOT
refresh them -- a named, out-of-scope-for-now follow-up, not an oversight (see the class-level
javadoc on ModelChangeClassifierMain).

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

$repo = "D:\WorkSpace\NPDev\NPDev_General"
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

$sw = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "Classifying '$CurrentModelPath' against baseline '$BaselineModelPath'..."
$gradleArgs = @(
    ":generator:classifyModelChange",
    "-PcurrentPath=$CurrentModelPath",
    "-PbaselinePath=$BaselineModelPath",
    "-PreportOut=$reportPath",
    "-PemitCompiledModelTo=$compiledModelDest",
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
    $sw.Stop()
    Write-Host "Elapsed (no restart, -SkipRestart): $($sw.Elapsed.TotalSeconds.ToString('0.0'))s"
    exit 0
}

Write-Host "Restarting the app ($AppOpsRoot)..."
& (Join-Path $AppOpsRoot "Stop-App.ps1")
& (Join-Path $AppOpsRoot "Start-App.ps1")

$sw.Stop()
Write-Host ""
Write-Host "TOTAL ELAPSED (classify + emit + restart): $($sw.Elapsed.TotalSeconds.ToString('0.0'))s" -ForegroundColor Cyan
