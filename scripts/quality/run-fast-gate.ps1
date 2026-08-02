<#
.SYNOPSIS
    T1 fast gate (Fast Lane plan, Sec.4/Sec.5, 2026-08-01): T0's checks, plus generate + build +
    boot ONE frozen canary app (NPDevSamples/npdev-canary) and a REST smoke pass over it (health,
    one flow, one panel), plus the three T1-scoped corpus checks named in the plan. Target < 3 min.

.DESCRIPTION
    Cadence: end of every wave/step inside a plan -- this is the gate your inner loop actually
    wants, cheaper than the full run-all-gates.ps1 (T2, ~13-15 min) but real: every step here is a
    real build+boot+REST-check against a real generated app, not a heuristic.

    Sec.7.3 rule 3 ("a tier can never be green for a change outside its scope"): T1 defers the
    other 29 corpus models, the frontend bundle, migration-with-real-data, and WmsOffice's
    interaction/scale surface to T2 -- this script prints that disclaimer on every run rather than
    letting a green T1 be mistaken for "the whole platform is fine." When -ModelDiffCurrentPath is
    given alongside -ModelDiffBaselinePath, it additionally reuses :generator:classifyModelChange
    (REG-102/REG-103's own classifier) to REFUSE (exit 1, no canary run) when the supplied diff is
    not METADATA_ONLY -- the one case this script can mechanically confirm is out of T1's depth,
    rather than silently claiming coverage a metadata-only-sized gate cannot actually provide.

    Every check's pass/fail is recorded into scripts/reports/out/verification-cadence-state.json
    (scripts/quality/cadence_state.py) against verification-cadence.json's declared tier/maxStaleness
    -- Sec.7's ledger, built in the SAME change as this gate per the plan's own instruction ("a T1
    gate without the ledger is exactly the 'hides what it skipped' pipeline this plan exists to
    avoid").

.PARAMETER ModelPath
    Optional -- the model.json currently being edited (T0's own scope). When given, runs
    `npdev validate model` against it and records model-validate-touched.

.PARAMETER DslTestFilter
    Optional -- a --tests filter passed to `gradlew :NPDevContract:dsl:test`. When given, records
    dsl-test-touched-area.

.PARAMETER ModelDiffCurrentPath / ModelDiffBaselinePath
    Optional pair -- when both given, classifies the diff (same classifier Update-AppMetadata.ps1
    uses) and REFUSES if it is not METADATA_ONLY, per Sec.7.3 rule 3.

.PARAMETER SkipCanary
    Skip the canary generate+build+boot+smoke step. Mainly for iterating on this script itself --
    a T1 run that skipped its own reason for existing should not report "T1 passed".

.PARAMETER RuntimeHostLibsDir
    Defaults to the canonical D:\WorkSpace\NPDev\Build\runtimehost-libs (Get-NPDevRuntimeHostLibsDir's
    own default). NOT refreshed by this script -- T1 targets <3 min; if kernel/adapter Java changed,
    refresh it yourself first (scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars) or run T2.

.PARAMETER Tier
    T0 (default T1) runs ONLY the T0 checks below -- no canary build, no corpus checks -- for
    `npdev verify --tier T0`. T1 (the default) runs T0's checks plus everything this script exists
    for.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-fast-gate.ps1
    pwsh -NoProfile -File scripts/quality/run-fast-gate.ps1 -Tier T0 -ModelPath NPDevSamples/npdev-canary/Input/model.json
#>
param(
    [ValidateSet("T0", "T1")]
    [string]$Tier = "T1",
    [string]$ModelPath = "",
    [string]$DslTestFilter = "",
    [string]$ModelDiffCurrentPath = "",
    [string]$ModelDiffBaselinePath = "",
    [switch]$SkipCanary,
    [string]$RuntimeHostLibsDir = "D:\WorkSpace\NPDev\Build\runtimehost-libs",
    [int]$CanaryBootTimeoutSeconds = 90,
    [string]$ReportPath = "scripts/reports/out/fast-gate-report.json"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
# Every path below is built from $repoRoot and passed absolute -- deliberately never relying on
# the process's current directory staying put across a run this long (several child pwsh.exe/java
# processes, one of which failed mid-run once and left the caller's $PWD pointed at the generated
# app's own directory instead of the repo root -- absolute paths make that class of bug impossible
# rather than needing to find which child changed it).
$cadenceScript = Join-Path $repoRoot "scripts\quality\cadence_state.py"

function Write-Section { param([string]$m) Write-Host "== $m ==" -ForegroundColor Cyan }
function Record-Cadence {
    param([string]$Id, [string]$Tier, [string]$Result)
    & $py $cadenceScript record --id $Id --tier $Tier --result $Result 2>&1 | Out-Null
}

Push-Location $repoRoot
$stages = [System.Collections.Generic.List[object]]::new()
$overallFailed = $false
$sw = [System.Diagnostics.Stopwatch]::StartNew()
try {
    Write-Host "== NPDev: $Tier fast gate ==" -ForegroundColor Cyan
    if ($Tier -eq "T1") {
        Write-Host "Defers to T2 (run-all-gates.ps1): the other 29 corpus models, the frontend bundle," -ForegroundColor Yellow
        Write-Host "migration-with-real-data, and WmsOffice's interaction/scale surface. T1 passing is" -ForegroundColor Yellow
        Write-Host "not evidence for those -- run T2 before closing a Move." -ForegroundColor Yellow
    }
    else {
        Write-Host "T0 only: no canary build, no corpus checks. Run without -Tier T0 (or 'npdev verify --tier T1') for those." -ForegroundColor Yellow
    }
    Write-Host ""

    # -- Optional refusal: Sec.7.3 rule 3, the one case this script can mechanically confirm. ----
    if ($Tier -eq "T1" -and $ModelDiffCurrentPath -and $ModelDiffBaselinePath) {
        Write-Section "Classifying supplied model diff (refuse if not METADATA_ONLY)"
        $classifyReport = Join-Path ([System.IO.Path]::GetTempPath()) ("npdev-fastgate-classify-" + [Guid]::NewGuid().ToString("N") + ".json")
        $generatorRoot = Join-Path $repoRoot "NPDevGenerator"
        Push-Location $generatorRoot
        try {
            & (Join-Path $generatorRoot "gradlew.bat") ":generator:classifyModelChange" `
                "-PcurrentPath=$ModelDiffCurrentPath" "-PbaselinePath=$ModelDiffBaselinePath" `
                "-PreportOut=$classifyReport" "--console=plain" | Out-Host
        } finally {
            Pop-Location
        }
        if (-not (Test-Path -LiteralPath $classifyReport)) {
            throw "Classifier did not produce a report -- see Gradle output above."
        }
        $classification = (Get-Content -Raw -LiteralPath $classifyReport | ConvertFrom-Json).classification
        Write-Host "Classification: $classification"
        if ($classification -ne "METADATA_ONLY") {
            Write-Host ""
            Write-Host "REFUSED (T1, Sec.7.3 rule 3): this diff is $classification, not METADATA_ONLY." -ForegroundColor Red
            Write-Host "T1's one frozen canary cannot stand in for a change outside metadata scope -- run T2." -ForegroundColor Red
            exit 1
        }
    }

    # -- T0: check-schema-mirror-consistency (every run) -----------------------------------------
    Write-Section "T0: check-schema-mirror-consistency"
    & $py (Join-Path $repoRoot "scripts\quality\check-schema-mirror-consistency.py")
    $result = if ($LASTEXITCODE -eq 0) { "passed" } else { "failed" }
    Record-Cadence -Id "check-schema-mirror-consistency" -Tier "T0" -Result $result
    $stages.Add([pscustomobject]@{ id = "check-schema-mirror-consistency"; tier = "T0"; status = $result })
    if ($result -ne "passed") { $overallFailed = $true }

    # -- T0: model-validate-touched (context-dependent) -------------------------------------------
    if ($ModelPath) {
        Write-Section "T0: npdev validate model $ModelPath"
        & $py (Join-Path $repoRoot "NPDevCli\npdev_cli.py") validate model $ModelPath --semantic 2>&1 | Out-Host
        $result = if ($LASTEXITCODE -eq 0) { "passed" } else { "failed" }
        Record-Cadence -Id "model-validate-touched" -Tier "T0" -Result $result
        $stages.Add([pscustomobject]@{ id = "model-validate-touched"; tier = "T0"; status = $result })
        if ($result -ne "passed") { $overallFailed = $true }
    }

    # -- T0: dsl-test-touched-area (context-dependent) ---------------------------------------------
    if ($DslTestFilter) {
        Write-Section "T0: gradlew :NPDevContract:dsl:test --tests $DslTestFilter"
        Push-Location (Join-Path $repoRoot "NPDevContract")
        try {
            & (Join-Path (Get-Location) "gradlew.bat") ":dsl:test" "--tests" $DslTestFilter "--console=plain" | Out-Host
            $exit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        $result = if ($exit -eq 0) { "passed" } else { "failed" }
        Record-Cadence -Id "dsl-test-touched-area" -Tier "T0" -Result $result
        $stages.Add([pscustomobject]@{ id = "dsl-test-touched-area"; tier = "T0"; status = $result })
        if ($result -ne "passed") { $overallFailed = $true }
    }

    if ($Tier -ne "T1") {
        # T0-only run: skip everything below (canary + corpus checks).
    }
    else {

    # -- T1: the canary -----------------------------------------------------------------------------
    $generateCanaryScript = Join-Path $repoRoot "NPDevSamples\scripts\generate-sample-app.ps1"
    if (-not $SkipCanary) {
        Write-Section "T1: generating npdev-canary"
        & pwsh -NoProfile -File $generateCanaryScript -SampleId "npdev-canary" | Out-Host
        $generateExit = $LASTEXITCODE
        $canaryAppRoot = Join-Path $repoRoot "NPDevSamples\npdev-canary\Output\App"

        if ($generateExit -ne 0) {
            Record-Cadence -Id "canary-build-boot-smoke" -Tier "T1" -Result "failed"
            $stages.Add([pscustomobject]@{ id = "canary-build-boot-smoke"; tier = "T1"; status = "failed"; note = "generation failed" })
            $overallFailed = $true
        }
        else {
            Write-Section "T1: build + boot + REST smoke npdev-canary"
            $env:NPDEV_RUNTIMEHOST_LIBS_DIR = $RuntimeHostLibsDir
            $smokeReportPath = Join-Path $repoRoot "scripts\reports\out\fast-gate-canary-smoke.json"
            & pwsh -NoProfile -File (Join-Path $repoRoot "scripts\quality\invoke-ai-beta-app-smoke.ps1") `
                -AppRoot $canaryAppRoot `
                -VerificationPath (Join-Path $repoRoot "NPDevSamples\npdev-canary\canary-verification.json") `
                -ReportPath $smokeReportPath `
                -Port 8103 `
                -Profiles "dev,trial" `
                -BootTimeoutSeconds $CanaryBootTimeoutSeconds | Out-Host
            $smokeExit = $LASTEXITCODE
            $result = if ($smokeExit -eq 0) { "passed" } else { "failed" }
            Record-Cadence -Id "canary-build-boot-smoke" -Tier "T1" -Result $result
            $stages.Add([pscustomobject]@{ id = "canary-build-boot-smoke"; tier = "T1"; status = $result; report = $smokeReportPath })
            if ($result -ne "passed") { $overallFailed = $true }
        }
    }
    else {
        Write-Host "SKIP: canary build+boot+smoke (-SkipCanary)." -ForegroundColor Yellow
    }

    # -- T1: check-dsl-coverage ----------------------------------------------------------------------
    Write-Section "T1: check-dsl-coverage"
    & $py (Join-Path $repoRoot "scripts\quality\check-dsl-coverage.py") | Out-Host
    $result = if ($LASTEXITCODE -eq 0) { "passed" } else { "failed" }
    Record-Cadence -Id "check-dsl-coverage" -Tier "T1" -Result $result
    $stages.Add([pscustomobject]@{ id = "check-dsl-coverage"; tier = "T1"; status = $result })
    if ($result -ne "passed") { $overallFailed = $true }

    # -- T1: check-panel-provenance-impact --discover ------------------------------------------------
    Write-Section "T1: check-panel-provenance-impact --discover"
    & $py (Join-Path $repoRoot "scripts\quality\check-panel-provenance-impact.py") --discover | Out-Host
    $result = if ($LASTEXITCODE -eq 0) { "passed" } else { "failed" }
    Record-Cadence -Id "check-panel-provenance-impact" -Tier "T1" -Result $result
    $stages.Add([pscustomobject]@{ id = "check-panel-provenance-impact"; tier = "T1"; status = $result })
    if ($result -ne "passed") { $overallFailed = $true }

    # -- T1: dsl-conformance-max emission proof -------------------------------------------------------
    # The working equivalent of "dsl-conformance-max --GenerateOnly": generate-sample-app.ps1's own
    # -NoAssembleFinalApp is emission-only, no build/boot -- see check-dsl-conformance-generates.py's
    # own docstring, which documents this exact invocation.
    Write-Section "T1: dsl-conformance-max emission proof (-NoAssembleFinalApp)"
    & pwsh -NoProfile -File $generateCanaryScript -SampleId "dsl-conformance-max" -NoAssembleFinalApp | Out-Host
    $result = if ($LASTEXITCODE -eq 0) { "passed" } else { "failed" }
    Record-Cadence -Id "dsl-conformance-max-generate" -Tier "T1" -Result $result
    $stages.Add([pscustomobject]@{ id = "dsl-conformance-max-generate"; tier = "T1"; status = $result })
    if ($result -ne "passed") { $overallFailed = $true }

    } # end T1-only block
}
finally {
    Pop-Location
    Set-Location -LiteralPath $repoRoot
}
$sw.Stop()

Write-Host ""
Write-Host "== Summary ==" -ForegroundColor Cyan
foreach ($s in $stages) {
    $color = if ($s.status -eq "passed") { "Green" } else { "Red" }
    Write-Host ("  {0,-32} {1,-8} ({2})" -f $s.id, $s.status, $s.tier) -ForegroundColor $color
}
Write-Host ("Elapsed: {0}s" -f [math]::Round($sw.Elapsed.TotalSeconds, 1))

$reportOut = Join-Path $repoRoot $ReportPath
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $reportOut) | Out-Null
[pscustomobject]@{
    schemaVersion = "npdev-fast-gate-report.v1"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    tier = $Tier
    elapsedSeconds = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    status = if ($overallFailed) { "failed" } else { "passed" }
    stages = $stages
} | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $reportOut -Encoding UTF8

Write-Host ""
& $py $cadenceScript report --tier $Tier

if ($overallFailed) {
    Write-Host ""
    Write-Host "$Tier FAST GATE FAILED." -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host "$Tier FAST GATE PASSED." -ForegroundColor Green
exit 0
