<#
.SYNOPSIS
    AI knowledge substrate gate: proves the derived corpora can't silently drift from their sources.

.DESCRIPTION
    Fails (non-zero exit) if:
      1. a register/roadmap summary row contradicts its own detail section.
      2. knowledge/platform-status.json is stale vs a fresh extraction of the gaps ledger
         (docs/OPEN_GAPS_AND_ROADMAP.md) -- i.e. someone edited the ledger without regenerating.
      3. any knowledge/cards/*.json fails knowledge-card validation.
      4. the shared failure-signature normalizer self-check fails.
      5. the security pattern sweep no longer catches the bug shapes it was written for.
      6. the security pattern sweep finds an UNTRIAGED hit in the codebase.

    Additionally REPORTS (never fails; see step 7) narrative-status drift -- a prose sentence
    naming an id and asserting a status that contradicts that id's own row. This is a distinct blind
    spot from check 1: the register checker only ever parses table rows and heading-led detail
    sections, so a stale claim in a narrative PARAGRAPH (found twice on 2026-07-27, once in this
    project's own register, once in ADR-0009's header) is invisible to it. Report-only for one cycle
    per this project's own lesson #4 ("a gate that cries wolf gets bypassed") -- promote to blocking
    once a clean-tree run has shown zero false positives for a while.

    Checks 1, 5 and 6 all answer one question: are the instruments still honest? 1 and 5 verify the
    detectors; 6 actually points one at the repo -- the step whose absence let REG-46's own fix add
    8 untriaged hits unnoticed.

    Run before merging any change to the ledger, the cards, the AI knowledge scripts, or any code the
    sweep's patterns cover (SQL building, auth catches, template authorization guards).

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-ai-knowledge-gate.ps1
#>
param(
    [switch]$Fix  # regenerate the projection instead of only checking it
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {
    $py = (Get-Command python -ErrorAction Stop).Source
    $failures = @()

    Write-Host "== AI knowledge gate ==" -ForegroundColor Cyan

    # [1/7] Register self-check runs FIRST: platform-status.json is DERIVED from the same documents,
    # so a summary row contradicting its own detail section does not just mislead a human reader --
    # it propagates straight into the AI knowledge substrate the MCP tools serve. Catching it before
    # the projection is regenerated stops the drift at its source. (An audit on 2026-07-24/25 found
    # ~12 such rows; every one would have been caught here in under a second.)
    Write-Host "[1/7] Checking register/roadmap summary rows against their detail sections..."
    & $py "scripts/quality/check-register-consistency.py"
    if ($LASTEXITCODE -ne 0) { $failures += "register/roadmap summary rows contradict their own detail sections" }

    if ($Fix) {
        Write-Host "[2/7] Regenerating platform-status projection..." -ForegroundColor Yellow
        & $py "scripts/ai/extract_platform_status.py"
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status regeneration failed" }
    } else {
        Write-Host "[2/7] Checking platform-status projection is current..."
        & $py "scripts/ai/extract_platform_status.py" --check
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status projection is STALE (run with -Fix)" }
    }

    Write-Host "[3/7] Validating knowledge cards..."
    & $py "scripts/ai/build_knowledge.py" --validate-only
    if ($LASTEXITCODE -ne 0) { $failures += "knowledge-card validation failed" }

    Write-Host "[4/7] Checking failure-signature normalizer..."
    $sig = & $py "scripts/ai/failure_signatures.py" "Panel 'Orders' references unknown entity 'Customer'"
    $expected = "panel <id> references unknown entity <id>"
    if ($sig.Trim() -ne $expected) {
        $failures += "normalizer self-check failed: got '$($sig.Trim())' expected '$expected'"
    }

    # [5/7] Same question as [1/7], asked of a different mechanism: is this quality tool still doing
    # what its documentation claims? The sweep's fixtures are the REAL historical shapes of bugs this
    # repo shipped (LNCH13-F1, REG-39, REG-36) plus each one's fix, and it must separate them. A sweep
    # that reports 350 hits but would have walked past LNCH13-F1 does not just fail to help -- it
    # manufactures confidence. Note this checks the PATTERNS, not the codebase: it cannot fail because
    # someone wrote new code, only because someone broke the detector.
    Write-Host "[5/7] Checking the security pattern sweep still catches its known bugs..."
    & $py "scripts/quality/security-pattern-sweep.py" --self-test
    if ($LASTEXITCODE -ne 0) { $failures += "security-pattern-sweep self-test failed: a pattern no longer catches the bug it was written for" }

    # [6/7] Run the sweep against the CODEBASE and fail on anything untriaged. Until now the gate
    # proved the detector worked ([5/7]) but never actually pointed it at the repo, so a new hit could
    # sit unnoticed indefinitely -- which is exactly what happened: closing the triage loop drove the
    # count 307 -> 8, and REG-46's own fix then silently added 8 more in the adapter it modified.
    # A sweep whose "new" count is allowed to drift upward stops being read, and a real hit hides in
    # the noise (that is how REG-47 nearly stayed buried).
    #
    # This asks for triage AT WRITE TIME, when the author knows why their code is safe and it costs a
    # minute; the allowlist entry is a fingerprint + a reason. It is deliberately BLOCKING rather than
    # advisory, because an advisory count that nobody must act on is the state this replaces.
    # To relax it, drop `--fail-on-new` (the sweep still reports) -- but prefer triaging the hit.
    Write-Host "[6/7] Checking for untriaged security-pattern hits..."
    & $py "scripts/quality/security-pattern-sweep.py" --fail-on-new
    if ($LASTEXITCODE -ne 0) {
        $failures += "untriaged security-pattern hits: review each, then record a fingerprint + REASON in scripts/quality/security-pattern-sweep-allowlist.json and the rule in docs/SECURITY_PATTERN_SWEEP_2026-07.md (a false 'safe' is worse than a noisy hit)"
    }

    # [7/7] Report-only (Phase 2.4, docs/REMAINDER_CLOSURE_PLAN.md): calibrated against both real
    # 2026-07-27 instances (see the script's own --calibrate mode) and zero false positives on this
    # corpus at the time it shipped. Deliberately does not add to $failures yet -- promote once a
    # clean-tree run has stayed at zero for a while, per lesson #4.
    Write-Host "[7/7] Checking for narrative-status drift (report-only)..."
    & $py "scripts/quality/check-narrative-status-drift.py"

    if ($failures.Count -gt 0) {
        Write-Host ""
        Write-Host "AI knowledge gate FAILED:" -ForegroundColor Red
        $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 1
    }
    Write-Host ""
    Write-Host "AI knowledge gate PASSED." -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
