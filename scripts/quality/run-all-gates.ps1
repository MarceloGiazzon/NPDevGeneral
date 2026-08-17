<#
.SYNOPSIS
    Runs every NPDev quality gate, in order, and reports one verdict.

.DESCRIPTION
    O4 (Move 11 W2). Until this script existed, "all gates green" was a claim no single command
    could make. CLAUDE.md listed five gate scripts; nothing ran them together, so a report saying
    "gates passed" in practice meant "the one gate I happened to run passed" -- which is how
    check-panel-provenance-impact.py stayed red across three moves while three consecutive move
    reports said otherwise.

    Order is deliberate, cheapest-and-most-diagnostic first:

      1. ai-knowledge   -- static, seconds; hosts 13 of the repo's 13 check-*.py, including the
                           script-inventory rule that fails if any checker is hosted by no gate.
                           Run first: if the instruments are broken, later results mean less.
      2. generator      -- the codegen engine.
      3. runtimehost    -- generates an assembled sample app and runs its test suite.
      4. frontend       -- the authoring UI.
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

    Also records every gate's pass/fail into scripts/reports/out/verification-cadence-state.json
    (via scripts/quality/cadence_state.py) against the tier/maxStaleness verification-cadence.json
    declares for it -- the mechanism that makes "deferred" a checkable claim instead of an assertion
    nobody can verify.

.PARAMETER Only
    Run just these gates by short name (aiKnowledge, generator, runtimeHost, frontend, betaRelease).
    Passing betaRelease here runs it even without -IncludeReleaseGate.

.PARAMETER StopOnFirstFailure
    Stop at the first failing gate instead of running the rest.

.PARAMETER IncludeReleaseGate
    Also run betaRelease (T3) on a bare, no-args invocation. Off by default since item 4 -- pass
    this, or -Only betaRelease, or use `npdev verify --tier T3`, when you actually want release
    posture evaluated.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1 -Only aiKnowledge,generator
    pwsh -NoProfile -File scripts/quality/run-all-gates.ps1 -IncludeReleaseGate
#>
[CmdletBinding()]
param(
    [string[]]$Only = @(),
    [switch]$StopOnFirstFailure,
    [switch]$IncludeReleaseGate
)

$ErrorActionPreference = "Continue"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

$gates = @(
    [pscustomobject]@{ Name = "aiKnowledge"; Script = "scripts/quality/run-ai-knowledge-gate.ps1"; Why = "static instrument checks + all check-*.py"; Tier = "T2" }
    [pscustomobject]@{ Name = "generator";   Script = "scripts/quality/run-generator-gate.ps1";    Why = "codegen engine"; Tier = "T2" }
    [pscustomobject]@{ Name = "runtimeHost"; Script = "scripts/quality/run-runtimehost-gate.ps1";  Why = "assembled sample app + its test suite"; Tier = "T2" }
    [pscustomobject]@{ Name = "betaRelease"; Script = "scripts/quality/run-beta-release-gate.ps1"; Why = "aggregate release checks"; Tier = "T3" }
)

if ($Only.Count -gt 0) {
    $unknown = @($Only | Where-Object { $_ -notin $gates.Name })
    if ($unknown.Count -gt 0) {
        Write-Host ("Unknown gate name(s): " + ($unknown -join ", ") + ". Known: " + ($gates.Name -join ", ")) -ForegroundColor Red
        exit 2
    }
    $gates = @($gates | Where-Object { $_.Name -in $Only })
}
elseif (-not $IncludeReleaseGate) {
    $deferred = @($gates | Where-Object { $_.Tier -eq "T3" })
    $gates = @($gates | Where-Object { $_.Tier -ne "T3" })
    foreach ($d in $deferred) {
        Write-Host ("[{0}] DEFERRED (T3, item 4): not run on a bare invocation. Run with -IncludeReleaseGate, -Only {0}, or `npdev verify --tier T3`." -f $d.Name) -ForegroundColor Yellow
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
