<#
.SYNOPSIS
    Weekly paperwork-checker gate (R4 Part C, MASTER-ROADMAP.md Step 9 / ledger QUAL-7): the 11
    checkers classified "paperwork" in scripts/policy/gate-classification-policy.json (verifies
    documentation/process hygiene, not platform runtime behavior) that were removed from
    run-ai-knowledge-gate.ps1's per-commit invocation and moved here.

.DESCRIPTION
    scripts/policy/gate-classification-policy.json records the full product-vs-paperwork split
    (measured: 23 product / 14 paperwork of the 37 scripts/quality/check-*.py at the time of this
    card). Of the 14 paperwork checkers, 3 (twin-pair-consistency, test-task-coverage,
    workflow-yaml-syntax) stayed in the per-commit ai-knowledge gate because they catch real bug
    classes despite being process-adjacent -- see that policy file's own notes. The other 11 run
    here instead, on a weekly schedule (.github/workflows/weekly-paperwork-checks.yml), not on every
    push/PR: none of them can fail because of a code change a reviewer is looking at right now, so
    gating every commit on them bought latency without buying safety.

    Same failure-accumulation shape as run-ai-knowledge-gate.ps1: every check runs even if an
    earlier one failed, so one run reports every problem instead of the first.

    check-external-ai-mission-coverage.py: reachable from no gate at all after R4 Part A retired
    run-external-ai-gate.ps1 (its only caller) as one of the 18 confirmed-orphan scripts. Hosting it
    here (rather than leaving it stranded) is what keeps it a real, exercised check instead of a
    second orphan created by this same card.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-weekly-paperwork-checks.ps1
#>
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
Push-Location $repoRoot
try {
    $py = (Get-Command python -ErrorAction Stop).Source
    $failures = @()

    Write-Host "== Weekly paperwork checks (R4 Part C) ==" -ForegroundColor Cyan

    Write-Host "[1/11] Checking accepted ADR decisions carrying a decision-check claim are implemented..."
    & $py "scripts/quality/check-adr-decision-implementation.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an ADR decision-check block's claim no longer holds: see scripts/quality/check-adr-decision-implementation.py output above"
    }

    Write-Host "[2/11] Checking allowlist entries carry a REG/B citation, reporting allowlist sizes..."
    & $py "scripts/quality/check-allowlist-citations.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an allowlist entry has no REG-nn/B-nn citation: see scripts/quality/check-allowlist-citations.py output above"
    }

    Write-Host "[3/11] Checking every run-all-gates.ps1 gate has a verification-cadence.json staleness deadline..."
    & $py "scripts/quality/check-cadence-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a run-all-gates.ps1 gate has no entry (or the wrong tier) in scripts/quality/verification-cadence.json: see scripts/quality/check-cadence-coverage.py output above"
    }

    Write-Host "[4/11] Checking no new process document (plan/checklist/findings/register) entered the repo..."
    & $py "scripts/quality/check-doc-inventory.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a banned process document was added, or the frozen legacy list grew/rotted: see scripts/quality/check-doc-inventory.py output above"
    }

    Write-Host "[5/11] Checking external-AI mission run coverage + provenance audit (ADR-0009)..."
    & $py "scripts/quality/check-external-ai-mission-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "external-AI mission coverage check failed: see scripts/quality/check-external-ai-mission-coverage.py output above -- missing mission run records, or a stale/unverified pack"
    }

    Write-Host "[6/11] Checking no OPEN ledger item has a fix that already landed..."
    & $py "scripts/quality/check-ledger-status-reverse-freshness.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a ledger item marked OPEN has its remedy already in the tree: see scripts/quality/check-ledger-status-reverse-freshness.py output above"
    }

    Write-Host "[7/11] Checking every relative markdown link resolves..."
    & $py "scripts/quality/check-markdown-links.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a relative markdown link is broken: see scripts/quality/check-markdown-links.py output above"
    }

    Write-Host "[8/11] Checking no script reads markdown content with no exemption..."
    & $py "scripts/quality/check-no-markdown-reads.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a script reads markdown content with no exemption: see scripts/quality/check-no-markdown-reads.py output above, and scripts/policy/markdown-read-exemptions.json for the exemption discipline"
    }

    Write-Host "[9/11] Checking no install guide pins a specific release in a download link..."
    & $py "scripts/quality/check-pinned-download-links.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a user-facing doc links to a version-pinned release asset: see scripts/quality/check-pinned-download-links.py output above"
    }

    Write-Host "[10/11] Checking every documented command/default a reader would copy..."
    & $py "scripts/quality/check-readme-contract.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a documented command or default cannot be followed as written: see scripts/quality/check-readme-contract.py output above"
    }

    Write-Host "[11/11] Checking branch freshness vs. origin/main and record-surfaces.json's large-file size claims..."
    & $py "scripts/quality/check-record-surfaces.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a record surface has drifted: see scripts/quality/check-record-surfaces.py output above"
    }

    if ($failures.Count -gt 0) {
        Write-Host ""
        Write-Host "Weekly paperwork checks FAILED:" -ForegroundColor Red
        $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 1
    }
    Write-Host ""
    Write-Host "Weekly paperwork checks PASSED." -ForegroundColor Green
    exit 0
}
finally {
    Pop-Location
}
