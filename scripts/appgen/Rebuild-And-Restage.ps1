<#
.SYNOPSIS
    One idempotent command to refresh ALL three NPDev build caches in the right order, then rebuild
    an AppGen FinalApp -- closing the recurring "stale jar / stale generator" class of failures.

.DESCRIPTION
    After changing kernel/adapter/generator Java, three separate caches must be refreshed before a
    regenerated app actually reflects the change, and their default directories do NOT line up
    (see knowledge/cards/runtimehost-libs-dir-mismatch.json and generator-runtime-cache-refresh.json):

      1. runtimehost-libs   -- restaged jars the generated app compiles against
                               (scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars)
      2. generator-runtime  -- the jar cache the AppGen builders read the GENERATOR from
                               (AppGen/generator-runtime/prepare-npdev-generator-runtime.ps1)
      3. the app itself      -- generate + assemble + build
                               (scripts/appgen/Build-NpdevApp.ps1)

    A 4th step then builds the jar, starts the app, and runs the panel-provenance impact gate (F4,
    docs/REMEDIATION_PLAN.md R-G1) against its live bundle -- a rebuild is precisely the moment a
    field rename would silently break a hand-written screen, so the check runs here by default
    rather than needing a separately remembered step.

    This wrapper threads ONE shared -RuntimeHostLibsDir through steps 1 and 3 so the sync writes to,
    and the build reads from, the same directory -- the single most common cause of "my change had
    no effect". Each step announces which cache it refreshed. Fail-fast on any step.

    Skip caches you don't need with -SkipLibs / -SkipGeneratorRuntime (e.g. a pure model change needs
    neither; a kernel change needs libs; a generator change needs generator-runtime). Skip the
    provenance check with -SkipProvenanceCheck.

    Fast Lane plan item 8 (2026-08-01): -TryFastPath makes the METADATA_ONLY win (item 1a) automatic
    rather than remembered. When the app is already built, classifies the candidate model
    (AppFolder/definition/model.json) against the currently-deployed baseline via the SAME
    classifier Update-AppMetadata.ps1 itself uses; a METADATA_ONLY result runs that fast path
    (classify -> emit compiled-model.json + compiled-metadata.json + metadata/*.manifest.json ->
    re-sign -> restart) and returns WITHOUT touching steps 1-4 below -- seconds instead of minutes.
    Anything else (fresh install, non-METADATA_ONLY diff, or the fast path itself failing) falls
    through to the full four-step rebuild unchanged. Off by default: this script's other switches
    all opt IN to skipping something, and -TryFastPath follows that same convention rather than
    silently changing what a bare invocation does.

.PARAMETER TryFastPath
    Classify the candidate model first; route through the METADATA_ONLY fast path
    (scripts/appgen/Update-AppMetadata.ps1) instead of a full rebuild when it applies. See item 8
    above. Never a substitute for T2 -- the fast path only ever changes what the running app already
    has loaded, it does not (and cannot) prove the other 29 corpus models still build.

.EXAMPLE
    pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder D:\WorkSpace\NPDev\AppGen\apps\wmsoffice

.EXAMPLE
    # generator-only change: skip the libs restage
    pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder ...\wmsoffice -SkipLibs

.EXAMPLE
    # let the script decide: fast path if the diff is METADATA_ONLY, full rebuild otherwise
    pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder ...\wmsoffice -TryFastPath
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$AppFolder,
    [string]$RuntimeHostLibsDir = 'D:\WorkSpace\NPDev\Build\runtimehost-libs',
    [string]$GeneratorRuntimeRoot = 'D:\WorkSpace\NPDev\AppGen\generator-runtime',
    [string]$BuildRoot = 'D:\WorkSpace\NPDev\Build\generated-finalapps',
    [switch]$SkipLibs,
    [switch]$SkipGeneratorRuntime,
    [switch]$GenerateOnly,
    # R-G1 (docs/REMEDIATION_PLAN.md): step 4 starts the app and runs the panel-provenance impact
    # gate against its live bundle by default -- "a field rename goes through a rebuild; that is
    # precisely the moment the check has to fire." Opt out for a generate/build-only pass (CI, or
    # an app with no web/ manifests to check -- Check-Provenance.ps1 itself is a no-op then anyway).
    [switch]$SkipProvenanceCheck,
    [switch]$TryFastPath
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Write-Stage { param([string]$m) Write-Host "==> $m" -ForegroundColor Cyan }

# REG-111 (Fast Lane plan item 4b/A2): same sidecar shape `npdev run app` and Build-NpdevApp.ps1
# already write (npdev-run-app-progress.json: schemaVersion/phase/updatedAt/pid, GENERATE/BUILD/
# BOOT/READY) -- written here too so a caller tailing App\npdev-run-app-progress.json sees it exist
# and stay on GENERATE through steps 1-2 (the ~345s of the measured 573s that dominate a full run,
# well before Build-NpdevApp.ps1 gets to step 3 and would write its own GENERATE marker). Step 4
# below calls the emitted Build-App.ps1/Start-App.ps1 directly, so BUILD/BOOT/READY come for free
# from those scripts' own writes -- no separate phase model invented here.
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

Push-Location $repoRoot
try {
    try {
        $rebuildProgressCfg = Get-Content -Raw -LiteralPath (Join-Path $AppFolder 'definition\config.json') | ConvertFrom-Json
        $rebuildProgressAppRoot = Join-Path $BuildRoot "$($rebuildProgressCfg.scenario.name)\App"
        Write-NpdevRunAppProgress -AppRoot $rebuildProgressAppRoot -Phase 'GENERATE'
    } catch { }

    # Item 8 (Fast Lane plan, 2026-08-01): try the METADATA_ONLY fast path (item 1a) before paying
    # for any of the four steps below, when asked to. Only applies when the app is ALREADY built
    # (there is a deployed baseline to diff against) and a candidate model.json is where
    # Build-NpdevApp.ps1 itself expects one (AppFolder/definition/model.json) -- anything else
    # (fresh install, no candidate model) falls straight through to the full rebuild, unchanged.
    if ($TryFastPath -and -not $GenerateOnly) {
        $candidateModelPath = Join-Path $AppFolder 'definition\model.json'
        $configPath = Join-Path $AppFolder 'definition\config.json'
        $fastPathAttempted = $false
        if ((Test-Path -LiteralPath $candidateModelPath) -and (Test-Path -LiteralPath $configPath)) {
            $cfg = Get-Content -Raw -LiteralPath $configPath | ConvertFrom-Json
            $appId = $cfg.scenario.name
            $opsDir = Join-Path $BuildRoot "$appId\_ops"
            $appPlanPath = Join-Path $opsDir 'app-plan.json'
            if (Test-Path -LiteralPath $appPlanPath) {
                $fastPathAttempted = $true
                Write-Stage "TryFastPath: '$appId' is already built -- classifying the candidate model against its deployed baseline..."
                $updateScript = Join-Path $repoRoot 'scripts/appgen/Update-AppMetadata.ps1'
                & $updateScript -AppOpsRoot $opsDir -CurrentModelPath $candidateModelPath
                if ($LASTEXITCODE -eq 0) {
                    Write-Host ''
                    Write-Stage "TryFastPath: METADATA_ONLY -- fast path applied, full rebuild SKIPPED."
                    Write-Host 'Not a substitute for T2: this only changed what the running app has loaded, it does not prove the other 29 corpus models still build.' -ForegroundColor Yellow
                    Pop-Location
                    exit 0
                }
                Write-Stage "TryFastPath: not METADATA_ONLY (or the fast path itself failed, exit $LASTEXITCODE) -- falling through to a full rebuild."
            }
        }
        if (-not $fastPathAttempted) {
            Write-Stage "TryFastPath: no deployed baseline yet (first build) or no candidate model.json found -- falling through to a full rebuild."
        }
    }

    # Step 1: restage runtimehost libs (kernel/adapter jars the app compiles against).
    if ($SkipLibs) {
        Write-Stage "SKIP step 1/3: runtimehost-libs restage"
    } else {
        Write-Stage "Step 1/3: restaging runtimehost-libs -> $RuntimeHostLibsDir"
        & (Join-Path $repoRoot 'scripts/runtimehost/sync-runtimehost-libs.ps1') `
            -BuildLocalJars -RuntimeHostLibsDir $RuntimeHostLibsDir
        if ($LASTEXITCODE -ne 0) { throw "sync-runtimehost-libs failed (exit $LASTEXITCODE)" }
    }

    # Step 2: refresh the generator-runtime jar cache the AppGen builders read the generator from.
    if ($SkipGeneratorRuntime) {
        Write-Stage "SKIP step 2/3: generator-runtime cache refresh"
    } else {
        $prepare = Join-Path $GeneratorRuntimeRoot 'prepare-npdev-generator-runtime.ps1'
        if (-not (Test-Path -LiteralPath $prepare)) {
            throw "generator-runtime prepare script not found: $prepare (pass -GeneratorRuntimeRoot)"
        }
        Write-Stage "Step 2/3: refreshing generator-runtime -> $GeneratorRuntimeRoot"
        & $prepare -RuntimeRoot $GeneratorRuntimeRoot
        if ($LASTEXITCODE -ne 0) { throw "prepare-npdev-generator-runtime failed (exit $LASTEXITCODE)" }
    }

    # Step 3: generate + build the app, reading the SAME libs dir the sync just wrote.
    # REG-90: -BuildRoot MUST be threaded into Build-NpdevApp.ps1 too. It used to be read only by
    # step 4 (the provenance gate), while step 3 silently fell back to Build-NpdevApp's own default
    # -- so `-BuildRoot <alt>` generated the app into `generated-finalapps` and then looked for its
    # _ops under `<alt>`, i.e. the two halves of this wrapper disagreed about which app they were
    # operating on. That is precisely the stale-build-root class of failure this wrapper exists to
    # prevent, reproduced inside the wrapper itself.
    Write-Stage "Step 3/3: building app '$AppFolder' (libs: $RuntimeHostLibsDir, buildRoot: $BuildRoot)"
    $buildArgs = @{ AppFolder = $AppFolder; RuntimeHostLibsDir = $RuntimeHostLibsDir; SkipRuntimeHostLibs = $true; BuildRoot = $BuildRoot }
    if ($GenerateOnly) { $buildArgs.GenerateOnly = $true }
    & (Join-Path $repoRoot 'scripts/appgen/Build-NpdevApp.ps1') @buildArgs
    if ($LASTEXITCODE -ne 0) { throw "Build-NpdevApp failed (exit $LASTEXITCODE)" }

    # Step 4: start the app and run the panel-provenance impact gate (F4) against its live bundle.
    # R-G1 (docs/REMEDIATION_PLAN.md): the gate was proven live on a real field rename but never had
    # anywhere it actually ran -- "a protection nobody runs is a protection that does not exist."
    # This is precisely the moment it has to fire, so it runs by default rather than needing a
    # separate remembered step.
    if ($SkipProvenanceCheck -or $GenerateOnly) {
        $reason = if ($GenerateOnly) { '-GenerateOnly, no jar to run' } else { '-SkipProvenanceCheck' }
        Write-Stage "SKIP step 4/4: panel-provenance impact gate ($reason)"
    } else {
        $cfg = Get-Content -Raw -LiteralPath (Join-Path $AppFolder 'definition/config.json') | ConvertFrom-Json
        $appId = $cfg.scenario.name
        $opsDir = Join-Path $BuildRoot "$appId\_ops"
        $checkScript = Join-Path $opsDir 'Check-Provenance.ps1'
        $buildScript = Join-Path $opsDir 'Build-App.ps1'
        $startScript = Join-Path $opsDir 'Start-App.ps1'
        if (-not (Test-Path -LiteralPath $checkScript)) {
            Write-Stage "SKIP step 4/4: panel-provenance impact gate (Check-Provenance.ps1 not found at $opsDir -- rebuild with a current Build-NpdevApp.ps1)"
        } else {
            # Build-NpdevApp.ps1 (step 3 above) only GENERATES the app -- it does not compile the
            # jar. Build-App.ps1 is the separate, actual `gradle build` step (same recipe the _ops
            # README itself documents: Build-App -> Start-App -> Check-Provenance).
            Write-Stage "Step 4/4: building, starting '$appId', and running the panel-provenance impact gate"
            & $buildScript
            if ($LASTEXITCODE -ne 0) { throw "Build-App failed (exit $LASTEXITCODE) -- cannot run the provenance check without a runnable jar" }
            & $startScript
            if ($LASTEXITCODE -ne 0) { throw "Start-App failed (exit $LASTEXITCODE) -- cannot run the provenance check against a down app" }
            & $checkScript
            $provenanceExit = $LASTEXITCODE
            if ($provenanceExit -ne 0) {
                throw "Panel-provenance impact gate FAILED (exit $provenanceExit) -- see $opsDir\Check-Provenance.ps1 output above. Either regenerate the named screen(s) against the current bundle, or update the model."
            }
        }
    }

    Write-Host ""
    Write-Stage "DONE: all requested caches refreshed and app rebuilt."
}
finally {
    Pop-Location
}
