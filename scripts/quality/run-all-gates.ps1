<#
.SYNOPSIS
    Runs every NPDev quality gate, in order, and reports one verdict.

.DESCRIPTION
    O4 (Move 11 W2). Until this script existed, "all gates green" was a claim no single command
    could make. CLAUDE.md listed five gate scripts; nothing ran them together, so a report saying
    "gates passed" in practice meant "the one gate I happened to run passed" -- which is how
    check-panel-provenance-impact.py stayed red across three moves while three consecutive move
    reports said otherwise.

    Order is deliberate -- the build gates run FIRST so their fresh JaCoCo reports are on disk when
    each one's own coverage-ratchet step reads them (W3.2/QUAL-32: generator, runtimehost and kernel
    each now check their own ratchet right after producing fresh evidence, not just ai-knowledge).
    Running ai-knowledge first left its ratchet step reading a PREVIOUS run's reports and produced a
    false "regression" off a partial report (QUAL-27):

      1. generator      -- the codegen engine (builds dsl + generator, runs tests -> JaCoCo). Now also
                           checks the dsl/generator coverage ratchet itself, right after producing it
                           (W3.2/QUAL-32) -- see that gate's own comment for why this moved.
      2. runtimehost    -- generates an assembled sample app and runs its test suite -> JaCoCo. Now
                           also checks the RuntimeHost coverage ratchet itself, same reason.
      3. kernel         -- NPDevKernel/kernel + all 36+ :adapters:* suites via kernelQualityGate, then
                           the kernel aggregate coverage ratchet (W3.2/QUAL-32: this Gradle task
                           existed and nothing ever invoked it -- the recorded floor drifted 10 points
                           from what the full suite actually measures before this gate existed).
      4. ai-knowledge   -- static instrument checks + all check-*.py, including the coverage ratchet
                           (which still runs here too, for NPDevCli/NPDevMcp/scripts -- the modules
                           this gate genuinely measures for real) and the script-inventory rule that
                           fails if any checker is hosted by no gate.
      5. beta-release   -- the aggregate release checks (it does NOT invoke the four above; it is
                           an additional gate, not a superset -- verified, not assumed).

    Every gate runs even after an earlier one fails (-StopOnFirstFailure opts out). A gate suite
    that stops at the first red tells you one thing per run; this repo's failures cluster, and
    seeing four reds at once is what distinguishes "one broken change" from "the tree is broken".

    Fast Lane plan item 4 (2026-08-01): betaRelease is release-ceremony evidence (T3 in the plan's
    tiering, scripts/quality/verification-cadence.json), not part of T2's "mandatory before closing
    a Move" set -- it used to ride along on every bare invocation of this script whether or not the
    work in flight was release-shaped. It is now DEFERRED by default (not skipped -- see below) and
    only runs when explicitly requested via -IncludeReleaseGate or -Only betaRelease, same as
    npdev verify --tier T3 requests it. This is a scheduling change only: nothing about betaRelease
    itself changed, and "deferred" is a printed, visible line every run, per Sec.7 of the plan --
    never a silent omission.

    GATE-SPLIT (2026-08-25 remediation plan W4.5): "all gates green" was a claim this script could
    make truthfully about the four gates above while being FALSE about the platform as a whole --
    11 of 42 checkers run only in run-weekly-paperwork-checks.ps1, which this script never invoked,
    and three of those eleven were red in CI with nobody noticing for a week (one of the three was
    two gates contradicting each other, W5.2). Per the recommendation this card settled on: this
    script narrows its own claim rather than silently absorbing the weekly checks into the T2
    critical path (they are genuinely slower-moving and gate no commit's correctness). weeklyPaperwork
    joins betaRelease as a second DEFERRED-by-default gate, opted into with -IncludePaperwork or
    -Only weeklyPaperwork -- so "ALL N GATES GREEN" only ever claims what N actually names, and a
    bare invocation's own printed DEFERRED line makes the narrower scope visible every run instead
    of an unstated assumption nobody re-checks.

    Also records every gate's pass/fail into scripts/reports/out/verification-cadence-state.json
    (via scripts/quality/cadence_state.py) against the tier/maxStaleness verification-cadence.json
    declares for it -- the mechanism that makes "deferred" a checkable claim instead of an assertion
    nobody can verify.

.PARAMETER Only
    Run just these gates by short name (aiKnowledge, generator, runtimeHost, kernel, betaRelease,
    weeklyPaperwork). Passing betaRelease or weeklyPaperwork here runs it even without the matching
    -Include switch.

.PARAMETER StopOnFirstFailure
    Stop at the first failing gate instead of running the rest.

.PARAMETER IncludeReleaseGate
    Also run betaRelease (T3) on a bare, no-args invocation. Off by default since item 4 -- pass
    this, or -Only betaRelease, or use `npdev verify --tier T3`, when you actually want release
    posture evaluated.

.PARAMETER IncludePaperwork
    Also run weeklyPaperwork (the 11 checkers in run-weekly-paperwork-checks.ps1) on a bare, no-args
    invocation. Off by default (GATE-SPLIT, 2026-08-25 W4.5) -- these are genuinely slower-moving
    and gate no commit's correctness, so folding them into the mandatory T2 path would buy latency
    without buying safety, the same reasoning that already applied to betaRelease. Pass this, or
    -Only weeklyPaperwork, when you want the weekly checks' own current status.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1 -Only aiKnowledge,generator
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1 -IncludeReleaseGate
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1 -IncludePaperwork
#>
[CmdletBinding()]
param(
    [string[]]$Only = @(),
    [switch]$StopOnFirstFailure,
    [switch]$IncludeReleaseGate,
    [switch]$IncludePaperwork
)

$ErrorActionPreference = "Continue"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

$gates = @(
    [pscustomobject]@{ Name = "generator";       Script = "scripts/quality/run-generator-gate.ps1";          Why = "codegen engine"; Tier = "T2" }
    [pscustomobject]@{ Name = "runtimeHost";     Script = "scripts/quality/run-runtimehost-gate.ps1";        Why = "assembled sample app + its test suite"; Tier = "T2" }
    [pscustomobject]@{ Name = "kernel";          Script = "scripts/quality/run-kernel-quality-gate.ps1";     Why = "kernel + all adapters via kernelQualityGate"; Tier = "T2" }
    [pscustomobject]@{ Name = "aiKnowledge";     Script = "scripts/quality/run-ai-knowledge-gate.ps1";       Why = "static instrument checks + all check-*.py"; Tier = "T2" }
    [pscustomobject]@{ Name = "betaRelease";     Script = "scripts/quality/run-beta-release-gate.ps1";       Why = "aggregate release checks"; Tier = "T3" }
    [pscustomobject]@{ Name = "weeklyPaperwork"; Script = "scripts/quality/run-weekly-paperwork-checks.ps1"; Why = "11 doc/process-hygiene checkers, weekly cadence"; Tier = "T3" }
)

# GATE-SPLIT (2026-08-25 W4.5): both gates below share cadence tier T3 (check-cadence-coverage.py's
# GATE_TIER_OVERRIDES/verification-cadence.json entry for each -- T3 is the only "deferred, not
# mandatory before closing a Move" tier this repo's cadence system has; inventing a fifth tier just
# for weeklyPaperwork would be a much larger change than this decision warrants). They ARE deferred
# for two DIFFERENT reasons -- betaRelease is release-ceremony evidence, weeklyPaperwork is genuinely
# slower-moving process/doc hygiene -- so each keeps its OWN opt-in switch and DEFERRED message,
# keyed by gate NAME here rather than folded into one generic "-IncludeExtras".
$deferrableGates = @{
    "betaRelease"     = @{ Included = [bool]$IncludeReleaseGate; Switch = "-IncludeReleaseGate"; Note = "T3, item 4";        ThirdOption = "``npdev verify --tier T3``" }
    "weeklyPaperwork" = @{ Included = [bool]$IncludePaperwork;   Switch = "-IncludePaperwork";   Note = "T3, GATE-SPLIT";    ThirdOption = "the weekly GitHub Actions schedule" }
}

if ($Only.Count -gt 0) {
    $unknown = @($Only | Where-Object { $_ -notin $gates.Name })
    if ($unknown.Count -gt 0) {
        Write-Host ("Unknown gate name(s): " + ($unknown -join ", ") + ". Known: " + ($gates.Name -join ", ")) -ForegroundColor Red
        exit 2
    }
    $gates = @($gates | Where-Object { $_.Name -in $Only })
}
else {
    $deferred = @($gates | Where-Object { $deferrableGates.ContainsKey($_.Name) -and -not $deferrableGates[$_.Name].Included })
    $gates = @($gates | Where-Object { -not ($deferrableGates.ContainsKey($_.Name) -and -not $deferrableGates[$_.Name].Included) })
    foreach ($d in $deferred) {
        $gateInfo = $deferrableGates[$d.Name]
        Write-Host ("[{0}] DEFERRED ({1}): not run on a bare invocation. Run with {2}, -Only {0}, or via {3}." -f $d.Name, $gateInfo.Note, $gateInfo.Switch, $gateInfo.ThirdOption) -ForegroundColor Yellow
    }
}

function Record-CadenceRun {
    param([string]$Id, [string]$Tier, [string]$Result)
    $py = if (Get-Command python -ErrorAction SilentlyContinue) { "python" } else { "py" }
    & $py "scripts/quality/cadence_state.py" record --id $Id --tier $Tier --result $Result 2>&1 | Out-Null
}

Push-Location $repoRoot
$results = @()
try {
    Write-Host "== NPDev: all gates ==" -ForegroundColor Cyan
    Write-Host ("Running {0} gate(s) in order: {1}" -f $gates.Count, ($gates.Name -join " -> "))
    Write-Host ""

    foreach ($gate in $gates) {
        $path = Join-Path $repoRoot $gate.Script
        if (-not (Test-Path -LiteralPath $path)) {
            Write-Host ("[{0}] MISSING: {1}" -f $gate.Name, $gate.Script) -ForegroundColor Red
            $results += [pscustomobject]@{ Name = $gate.Name; Status = "missing"; Seconds = 0 }
            continue
        }

        Write-Host ("--- [{0}] {1} ({2}) ---" -f $gate.Name, $gate.Script, $gate.Why) -ForegroundColor Cyan
        $started = Get-Date
        pwsh -NoProfile -File $path
        $exit = $LASTEXITCODE
        $seconds = [math]::Round(((Get-Date) - $started).TotalSeconds, 1)
        $status = if ($exit -eq 0) { "passed" } else { "failed" }
        $results += [pscustomobject]@{ Name = $gate.Name; Status = $status; Seconds = $seconds }
        Record-CadenceRun -Id $gate.Name -Tier $gate.Tier -Result $status
        Write-Host ("--- [{0}] {1} in {2}s ---" -f $gate.Name, $status.ToUpperInvariant(), $seconds) `
            -ForegroundColor $(if ($exit -eq 0) { "Green" } else { "Red" })
        Write-Host ""

        if ($exit -ne 0 -and $StopOnFirstFailure) {
            Write-Host "-StopOnFirstFailure: not running the remaining gate(s)." -ForegroundColor Yellow
            break
        }
    }
}
finally {
    Pop-Location
}

Write-Host "== Summary ==" -ForegroundColor Cyan
foreach ($r in $results) {
    $color = switch ($r.Status) { "passed" { "Green" } default { "Red" } }
    Write-Host ("  {0,-12} {1,-8} {2,7}s" -f $r.Name, $r.Status, $r.Seconds) -ForegroundColor $color
}
$notRun = @($gates | Where-Object { $_.Name -notin $results.Name })
foreach ($g in $notRun) {
    Write-Host ("  {0,-12} {1,-8}" -f $g.Name, "not-run") -ForegroundColor Yellow
}

$failed = @($results | Where-Object { $_.Status -ne "passed" })
if ($failed.Count -gt 0 -or $notRun.Count -gt 0) {
    Write-Host ""
    Write-Host ("NOT ALL GATES GREEN: {0} failed, {1} not run." -f $failed.Count, $notRun.Count) -ForegroundColor Red
    exit 1
}
Write-Host ""
Write-Host ("ALL {0} GATES GREEN." -f $results.Count) -ForegroundColor Green
exit 0
