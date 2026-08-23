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

    REG-158 (2026-08-13): this script shipped in the same commit as run-script-automation-quality.ps1's
    structured-report-contract check but never adopted it (nor was it added to that checker's
    reportContractMigrationBacklog), so `NPDev CI Validation`'s "Script automation quality" step had
    been red on every push to main since. Now emits the standard structured report
    (generatedAt/runId/scriptPath/workspaceRoot/overallStatus via Write-NPDevJsonFile, same pattern as
    e.g. run-generator-governance.ps1) alongside the original console output and exit-code contract,
    which is unchanged.

.EXAMPLE
    pwsh -NoProfile -File scripts/quality/run-weekly-paperwork-checks.ps1
#>
[CmdletBinding()]
param(
    [string]$WorkspaceRoot = "",
    [string]$RunId = "",
    [string]$ReportPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

. (Join-Path $PSScriptRoot "..\npdev-common.ps1")

if ([string]::IsNullOrWhiteSpace($WorkspaceRoot)) {
    $WorkspaceRoot = $repoRoot
}
$WorkspaceRoot = Normalize-NPDevPath $WorkspaceRoot
$RunId = Resolve-NPDevRunId $RunId "weekly-paperwork-checks"
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Resolve-NPDevWorkspacePath $WorkspaceRoot "scripts\reports\out\weekly-paperwork-checks-report.json"
}
else {
    $ReportPath = Normalize-NPDevPath $ReportPath
}

Push-Location $repoRoot
try {
    $py = (Get-Command python -ErrorAction Stop).Source
    $failures = @()
    $checks = [System.Collections.Generic.List[object]]::new()
    # T6.3 (2026-08-23): check-adr-decision-implementation.py's own verification-cadence.json entry
    # said `invokedBy: run-ai-knowledge-gate.ps1` -- stale since R4 Part C moved the check HERE, so
    # cadence_state.py could never see a run this script actually performed. Fixed the metadata and
    # wired the same record-on-run pattern run-fast-gate.ps1 already uses.
    $cadenceScript = Join-Path $repoRoot "scripts\quality\cadence_state.py"
    function Record-Cadence {
        param([string]$Id, [string]$Tier, [string]$Result)
        & $py $cadenceScript record --id $Id --tier $Tier --result $Result 2>&1 | Out-Null
    }

    Write-Host "== Weekly paperwork checks (R4 Part C) ==" -ForegroundColor Cyan

    Write-Host "[1/11] Checking accepted ADR decisions carrying a decision-check claim are implemented..."
    & $py "scripts/quality/check-adr-decision-implementation.py"
    $exitCode = $LASTEXITCODE
    Record-Cadence -Id "check-adr-decision-implementation" -Tier "T1" -Result $(if ($exitCode -eq 0) { "passed" } else { "failed" })
    [void]$checks.Add((New-NPDevCheckResult "adr-decision-implementation" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-adr-decision-implementation.py passed." } else { "an ADR decision-check block's claim no longer holds." }) @{ scriptPath = "scripts/quality/check-adr-decision-implementation.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "an ADR decision-check block's claim no longer holds: see scripts/quality/check-adr-decision-implementation.py output above"
    }

    Write-Host "[2/11] Checking allowlist entries carry a REG/B citation, reporting allowlist sizes..."
    & $py "scripts/quality/check-allowlist-citations.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "allowlist-citations" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-allowlist-citations.py passed." } else { "an allowlist entry has no REG-nn/B-nn citation." }) @{ scriptPath = "scripts/quality/check-allowlist-citations.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "an allowlist entry has no REG-nn/B-nn citation: see scripts/quality/check-allowlist-citations.py output above"
    }

    Write-Host "[3/11] Checking every run-all-gates.ps1 gate has a verification-cadence.json staleness deadline..."
    & $py "scripts/quality/check-cadence-coverage.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "cadence-coverage" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-cadence-coverage.py passed." } else { "a run-all-gates.ps1 gate has no entry (or the wrong tier) in verification-cadence.json." }) @{ scriptPath = "scripts/quality/check-cadence-coverage.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a run-all-gates.ps1 gate has no entry (or the wrong tier) in scripts/quality/verification-cadence.json: see scripts/quality/check-cadence-coverage.py output above"
    }

    Write-Host "[4/11] Checking no new process document (plan/checklist/findings/register) entered the repo..."
    & $py "scripts/quality/check-doc-inventory.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "doc-inventory" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-doc-inventory.py passed." } else { "a banned process document was added, or the frozen legacy list grew/rotted." }) @{ scriptPath = "scripts/quality/check-doc-inventory.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a banned process document was added, or the frozen legacy list grew/rotted: see scripts/quality/check-doc-inventory.py output above"
    }

    Write-Host "[5/11] Checking external-AI mission run coverage + provenance audit (ADR-0009)..."
    & $py "scripts/quality/check-external-ai-mission-coverage.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "external-ai-mission-coverage" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-external-ai-mission-coverage.py passed." } else { "external-AI mission coverage check failed." }) @{ scriptPath = "scripts/quality/check-external-ai-mission-coverage.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "external-AI mission coverage check failed: see scripts/quality/check-external-ai-mission-coverage.py output above -- missing mission run records, or a stale/unverified pack"
    }

    Write-Host "[6/11] Checking no OPEN ledger item has a fix that already landed..."
    & $py "scripts/quality/check-ledger-status-reverse-freshness.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "ledger-status-reverse-freshness" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-ledger-status-reverse-freshness.py passed." } else { "a ledger item marked OPEN has its remedy already in the tree." }) @{ scriptPath = "scripts/quality/check-ledger-status-reverse-freshness.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a ledger item marked OPEN has its remedy already in the tree: see scripts/quality/check-ledger-status-reverse-freshness.py output above"
    }

    Write-Host "[7/11] Checking every relative markdown link resolves..."
    & $py "scripts/quality/check-markdown-links.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "markdown-links" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-markdown-links.py passed." } else { "a relative markdown link is broken." }) @{ scriptPath = "scripts/quality/check-markdown-links.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a relative markdown link is broken: see scripts/quality/check-markdown-links.py output above"
    }

    Write-Host "[8/11] Checking no script reads markdown content with no exemption..."
    & $py "scripts/quality/check-no-markdown-reads.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "no-markdown-reads" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-no-markdown-reads.py passed." } else { "a script reads markdown content with no exemption." }) @{ scriptPath = "scripts/quality/check-no-markdown-reads.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a script reads markdown content with no exemption: see scripts/quality/check-no-markdown-reads.py output above, and scripts/policy/markdown-read-exemptions.json for the exemption discipline"
    }

    Write-Host "[9/11] Checking no install guide pins a specific release in a download link..."
    & $py "scripts/quality/check-pinned-download-links.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "pinned-download-links" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-pinned-download-links.py passed." } else { "a user-facing doc links to a version-pinned release asset." }) @{ scriptPath = "scripts/quality/check-pinned-download-links.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a user-facing doc links to a version-pinned release asset: see scripts/quality/check-pinned-download-links.py output above"
    }

    Write-Host "[10/11] Checking every documented command/default a reader would copy..."
    & $py "scripts/quality/check-readme-contract.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "readme-contract" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-readme-contract.py passed." } else { "a documented command or default cannot be followed as written." }) @{ scriptPath = "scripts/quality/check-readme-contract.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a documented command or default cannot be followed as written: see scripts/quality/check-readme-contract.py output above"
    }

    Write-Host "[11/11] Checking branch freshness vs. origin/main and record-surfaces.json's large-file size claims..."
    & $py "scripts/quality/check-record-surfaces.py"
    $exitCode = $LASTEXITCODE
    [void]$checks.Add((New-NPDevCheckResult "record-surfaces" $(if ($exitCode -eq 0) { "passed" } else { "failed" }) $(if ($exitCode -eq 0) { "check-record-surfaces.py passed." } else { "a record surface has drifted." }) @{ scriptPath = "scripts/quality/check-record-surfaces.py"; exitCode = $exitCode }))
    if ($exitCode -ne 0) {
        $failures += "a record surface has drifted: see scripts/quality/check-record-surfaces.py output above"
    }

    $failedChecks = @($checks | Where-Object { $_.status -eq "failed" })
    $report = [pscustomobject]@{
        generatedAt = (Get-Date).ToString("o")
        runId = $RunId
        scriptPath = Get-NPDevWorkspaceRelativePath $WorkspaceRoot $PSCommandPath
        workspaceRoot = $WorkspaceRoot
        overallStatus = if ($failedChecks.Count -eq 0) { "passed" } else { "failed" }
        checks = @($checks)
        summary = [pscustomobject]@{
            failed = $failedChecks.Count
            passed = @($checks | Where-Object { $_.status -eq "passed" }).Count
            total = $checks.Count
        }
    }
    Write-NPDevJsonFile $ReportPath $report

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
