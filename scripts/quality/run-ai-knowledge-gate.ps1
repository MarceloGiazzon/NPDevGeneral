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

    Check 8 (T1.3, docs/TREE1_LAUNCH_UNBLOCK_PLAN.md) makes the T1.2 bug class impossible to recur
    silently: a custom Gradle `Test` task (a new `behaviorTest`/`integrationTest`/`contractTest`
    source set) that is declared but never named by any CI workflow, and not check-wired with `check`
    itself invoked. Blocking, per this gate's own established pattern (check 6) -- a coverage gap that
    is merely reported is exactly the state that let `:generator:behaviorTest` run on one laptop and
    nowhere else for as long as it did.

    Check 9 (2.A.2, docs/DSL2_AND_DECOMPOSITION_PLAN.md) is the same shape again: model.schema.json is
    duplicated in four places with nothing previously enforcing they stay in sync, and they had
    already drifted by the time this check was written. Blocking.

    Check 10 (R-G1 static half, docs/REMEDIATION_PLAN.md) validates every *.panel.json manifest's own
    SHAPE against schemas/panel-provenance.schema.json (required fields, no unexpected fields, a
    well-formed invokes[] id) -- the half of the panel-provenance impact gate (F4) that needs no live
    app. The EXISTENCE half (does a field/invocation still exist in the current model) needs a real
    authenticated bundle and runs per-app instead, via that app's own `_ops/Check-Provenance.ps1`.

    Check 11 (C4, docs/CORPUS_INTEGRITY_PLAN.md) validates every model.json in the corpus
    (AppGen/apps + NPDevSamples) still parses against the real validator (JsonModelParser +
    SemanticValidator via the validateModel Gradle task) -- not a heuristic. Blocking, same rationale
    as checks 6/8/9: nothing previously noticed when 17 of 29 corpus models silently stopped parsing
    as the schema evolved (REG-63), and it stayed unnoticed for weeks until a manifest task tripped
    over two of them by accident. Calibrated RED against the pre-fix corpus (17 failures, captured
    2026-07-29) then GREEN after `npdev migrate dsl-2 --write`.

    Check 12 (C5/N5, docs/CORPUS_INTEGRITY_PLAN.md) fails on any relative markdown link that does not
    resolve. Blocking, same "nothing looked" shape as check 11: two separate doc reorganizations (the
    T1.15/2.A split, and R-P2's programme-history archival) each produced dangling links found only by
    ad-hoc grep, and a third genuinely broken link (a wrong relative-path depth) and a fourth stale
    claim (a doc believed never-written that in fact existed under a different directory) turned up
    building this very check. Repo-wide, ~270 files, a few hundred ms.

    Check 13 (docs/FINAL_OPEN_ITEMS_PLAN.md F1/F2) fails if any `.github/workflows/*.yml` is not
    valid YAML. Found by accident, not by design: verifying F1/F2 on a live PR turned up
    npdev-pr-gate.yml silently scheduling ZERO jobs for hours -- an unquoted colon inside a step
    `name:` (`findings: persistence, idempotency`) made the whole file unparseable, and nothing in
    this repo's own thorough gate suite had ever checked that a workflow file still parses. Same
    class as checks 11/12; syntax only, not GitHub Actions schema validation.

    Check 14 (F6) fails if a declared engine-variant family (corpus-roles.json -- currently
    simple-user-registry-* and p77-hookproof/-pg, byte-identical model bodies by design) has
    diverged. A DSL fix applied to one family member previously had no mechanism to surface that it
    was not propagated to the others.

    Check 15 (F8) fails if any tracked DSL feature has zero corpus models exercising it. The class
    fix behind F3/F4: NPDevSamples/dsl-conformance-max fixes today's 8 zero-coverage features once;
    this check is why a 9th can be added and never fixtured.

    Run before merging any change to the ledger, the cards, the AI knowledge scripts, model.schema.json
    (any of its four copies), any `.github/workflows/*.yml`, or any code the sweep's patterns cover
    (SQL building, auth catches, template authorization guards).

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

    # [1/15] Register self-check runs FIRST: platform-status.json is DERIVED from the same documents,
    # so a summary row contradicting its own detail section does not just mislead a human reader --
    # it propagates straight into the AI knowledge substrate the MCP tools serve. Catching it before
    # the projection is regenerated stops the drift at its source. (An audit on 2026-07-24/25 found
    # ~12 such rows; every one would have been caught here in under a second.)
    Write-Host "[1/15] Checking register/roadmap summary rows against their detail sections..."
    & $py "scripts/quality/check-register-consistency.py"
    if ($LASTEXITCODE -ne 0) { $failures += "register/roadmap summary rows contradict their own detail sections" }

    if ($Fix) {
        Write-Host "[2/15] Regenerating platform-status projection..." -ForegroundColor Yellow
        & $py "scripts/ai/extract_platform_status.py"
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status regeneration failed" }
    } else {
        Write-Host "[2/15] Checking platform-status projection is current..."
        & $py "scripts/ai/extract_platform_status.py" --check
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status projection is STALE (run with -Fix)" }
    }

    Write-Host "[3/15] Validating knowledge cards..."
    & $py "scripts/ai/build_knowledge.py" --validate-only
    if ($LASTEXITCODE -ne 0) { $failures += "knowledge-card validation failed" }

    Write-Host "[4/15] Checking failure-signature normalizer..."
    $sig = & $py "scripts/ai/failure_signatures.py" "Panel 'Orders' references unknown entity 'Customer'"
    $expected = "panel <id> references unknown entity <id>"
    if ($sig.Trim() -ne $expected) {
        $failures += "normalizer self-check failed: got '$($sig.Trim())' expected '$expected'"
    }

    # [5/15] Same question as [1/15], asked of a different mechanism: is this quality tool still doing
    # what its documentation claims? The sweep's fixtures are the REAL historical shapes of bugs this
    # repo shipped (LNCH13-F1, REG-39, REG-36) plus each one's fix, and it must separate them. A sweep
    # that reports 350 hits but would have walked past LNCH13-F1 does not just fail to help -- it
    # manufactures confidence. Note this checks the PATTERNS, not the codebase: it cannot fail because
    # someone wrote new code, only because someone broke the detector.
    Write-Host "[5/15] Checking the security pattern sweep still catches its known bugs..."
    & $py "scripts/quality/security-pattern-sweep.py" --self-test
    if ($LASTEXITCODE -ne 0) { $failures += "security-pattern-sweep self-test failed: a pattern no longer catches the bug it was written for" }

    # [6/15] Run the sweep against the CODEBASE and fail on anything untriaged. Until now the gate
    # proved the detector worked ([5/15]) but never actually pointed it at the repo, so a new hit could
    # sit unnoticed indefinitely -- which is exactly what happened: closing the triage loop drove the
    # count 307 -> 8, and REG-46's own fix then silently added 8 more in the adapter it modified.
    # A sweep whose "new" count is allowed to drift upward stops being read, and a real hit hides in
    # the noise (that is how REG-47 nearly stayed buried).
    #
    # This asks for triage AT WRITE TIME, when the author knows why their code is safe and it costs a
    # minute; the allowlist entry is a fingerprint + a reason. It is deliberately BLOCKING rather than
    # advisory, because an advisory count that nobody must act on is the state this replaces.
    # To relax it, drop `--fail-on-new` (the sweep still reports) -- but prefer triaging the hit.
    Write-Host "[6/15] Checking for untriaged security-pattern hits..."
    & $py "scripts/quality/security-pattern-sweep.py" --fail-on-new
    if ($LASTEXITCODE -ne 0) {
        $failures += "untriaged security-pattern hits: review each, then record a fingerprint + REASON in scripts/quality/security-pattern-sweep-allowlist.json and the rule in docs/SECURITY_PATTERN_SWEEP_2026-07.md (a false 'safe' is worse than a noisy hit)"
    }

    # [7/15] Report-only (Phase 2.4, docs/archive/programme-history/REMAINDER_CLOSURE_PLAN.md): calibrated against both real
    # 2026-07-27 instances (see the script's own --calibrate mode) and zero false positives on this
    # corpus at the time it shipped. Deliberately does not add to $failures yet -- promote once a
    # clean-tree run has stayed at zero for a while, per lesson #4.
    Write-Host "[7/15] Checking for narrative-status drift (report-only)..."
    & $py "scripts/quality/check-narrative-status-drift.py"

    # [8/15] T1.3: a custom Gradle Test task (behaviorTest/integrationTest/a future contractTest) that
    # is declared but reachable from no CI workflow is a test that only runs on one laptop -- exactly
    # what REG-49's residual behaviorTest was until T1.2 wired it in. Blocking, same rationale as [6/15].
    Write-Host "[8/15] Checking every custom Gradle Test task is reachable from a CI workflow..."
    & $py "scripts/quality/check-test-task-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a custom Gradle Test task is unreachable from CI: see scripts/quality/check-test-task-coverage.py output above, then either wire it into a workflow or record a reviewed exemption in scripts/quality/test-task-coverage-allowlist.json"
    }

    # [9/15] 2.A.2 (docs/DSL2_AND_DECOMPOSITION_PLAN.md): model.schema.json is duplicated in four
    # places with nothing previously enforcing they stay in sync -- they had already drifted by the
    # time this gate was written. Blocking, same rationale as [6/15] and [8/15]: an unsynced schema
    # copy silently teaches a stale contract to whichever consumer reads it (authoring UI, DSL module,
    # the legacy authoring location), with no error until something built against the stale copy fails
    # far away from the edit that caused it.
    Write-Host "[9/15] Checking the four model.schema.json copies are still semantically identical..."
    & $py "scripts/quality/check-schema-mirror-consistency.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "the four model.schema.json copies have drifted: see scripts/quality/check-schema-mirror-consistency.py output above for which key differs, then mirror the edit to all four (CLAUDE.md's own standing rule)"
    }

    # [10/15] R-G1 static half (docs/REMEDIATION_PLAN.md): the panel-provenance impact gate (F4)
    # needs a live authenticated bundle to check field/invocation EXISTENCE (that half now runs
    # per-app via _ops/Check-Provenance.ps1, wired 2026-07-28) -- but a manifest's own SHAPE
    # (required fields, no unexpected fields, a well-formed invokes[] id) needs no live app at all.
    # Runs against the AppGen apps workspace when present on this machine; 0 manifests found (e.g.
    # a bare CI checkout, which has no AppGen/apps at all) is a printed PASS, not a silent skip.
    Write-Host "[10/15] Checking *.panel.json manifests structurally validate (no live app needed)..."
    & $py "scripts/quality/check-panel-provenance-schema.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a *.panel.json manifest fails structural validation: see scripts/quality/check-panel-provenance-schema.py output above"
    }

    # [11/15] C4 (docs/CORPUS_INTEGRITY_PLAN.md): every model.json under AppGen/apps + NPDevSamples
    # must still parse against the REAL validator. Blocking, same rationale as [6/15]/[8/15]/[9/15] --
    # REG-63 is what happened when nothing checked this: 17 of 29 corpus models silently stopped
    # parsing as the schema evolved, unnoticed for weeks. Runs against AppGen/apps when present on
    # this machine (a bare CI checkout still gets full NPDevSamples coverage); a reviewed, REG-id'd
    # exception goes in scripts/quality/corpus-parse-allowlist.json -- never pre-cleared.
    Write-Host "[11/15] Checking every corpus model.json still parses..."
    & $py "scripts/quality/validate-corpus.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a corpus model no longer parses: see scripts/quality/validate-corpus.py output above, then either fix the model or record a reviewed exception (with a REG id) in scripts/quality/corpus-parse-allowlist.json"
    }

    Write-Host "[12/15] Checking every relative markdown link resolves..."
    & $py "scripts/quality/check-markdown-links.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a relative markdown link is broken: see scripts/quality/check-markdown-links.py output above"
    }

    # [13/15] Found by accident verifying F1/F2 on a live PR, 2026-07-29: an unquoted colon inside a
    # step name made npdev-pr-gate.yml invalid YAML -- GitHub scheduled ZERO jobs for it, silently,
    # on every push and PR for hours before anyone noticed. Same "nothing looked" shape as checks
    # 11/14 and 12/14. Syntax only (yaml.safe_load), not GitHub Actions schema validation -- cheapest
    # version of the fix, matching the failure mode that actually happened.
    Write-Host "[13/15] Checking every workflow file is valid YAML..."
    & $py "scripts/quality/check-workflow-yaml-syntax.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a workflow file is not valid YAML: see scripts/quality/check-workflow-yaml-syntax.py output above"
    }

    # [14/15] F6 (docs/FINAL_OPEN_ITEMS_PLAN.md): the simple-user-registry-* and p77-hookproof(-pg)
    # engine-variant families have byte-identical model bodies by design (differing only in DB
    # engine config) -- nothing previously asserted they STAY identical, so a fix applied to one
    # would not visibly propagate to its siblings. Membership is declared (hand-reviewed) in
    # corpus-roles.json; sameness is asserted here, every run.
    Write-Host "[14/15] Checking engine-variant families stay byte-identical..."
    & $py "scripts/quality/check-engine-variant-families.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an engine-variant family has diverged: see scripts/quality/check-engine-variant-families.py output above"
    }

    # [15/15] F8 (docs/FINAL_OPEN_ITEMS_PLAN.md): the corpus-parse gate (check 11) answers "does
    # every model parse?" -- nothing answered "is every DSL feature exercised by at least one
    # model?", the gap that let 8 schema features (selectors, externalAi, step forEach, step
    # generatedAction, onFailure compensation, flow.schedule, flow.hooks, flow.specializes) sit at
    # zero coverage until dsl-conformance-max (F3) closed them -- confirmed live: re-running this
    # exact check against the corpus with that fixture excluded reproduces all 8 as RED. Sequenced
    # after F4 (generatedAction only became reachable once FlowValidation's switch was fixed).
    Write-Host "[15/15] Checking every DSL feature has at least one corpus model exercising it..."
    & $py "scripts/quality/check-dsl-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a DSL feature has zero corpus coverage: see scripts/quality/check-dsl-coverage.py output above -- add a real example to NPDevSamples/dsl-conformance-max, or record a reviewed exception with a REG id"
    }

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
