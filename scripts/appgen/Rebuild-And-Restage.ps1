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

.EXAMPLE
    pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder D:\WorkSpace\NPDev\AppGen\apps\wmsoffice

.EXAMPLE
    # generator-only change: skip the libs restage
    pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder ...\wmsoffice -SkipLibs
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
    [switch]$SkipProvenanceCheck
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Write-Stage { param([string]$m) Write-Host "==> $m" -ForegroundColor Cyan }

Push-Location $repoRoot
try {
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
    Write-Stage "Step 3/3: building app '$AppFolder' (libs: $RuntimeHostLibsDir)"
    $buildArgs = @{ AppFolder = $AppFolder; RuntimeHostLibsDir = $RuntimeHostLibsDir; SkipRuntimeHostLibs = $true }
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
