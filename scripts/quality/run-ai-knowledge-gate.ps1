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
    app. The EXISTENCE half is check 19 below (it used to run ONLY per-app, against a live
    authenticated bundle, via that app's own `_ops/Check-Provenance.ps1`).

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

    Check 16 (docs/RECORD_SURFACES_PLAN.md P4): a different class than checks 1-15 -- not "a check
    exists, nothing runs it" but "a surface of record goes stale after successful work, and nothing
    notices." Two mechanical claims that went stale silently on 2026-07-29: `origin/main` reached 71
    commits behind (the third recurrence; public repo, default branch, what a clone gets) with nothing
    measuring the gap, and CLAUDE.md's own "Large files" block misstated three files' sizes (two had
    shrunk to ~12 KB after the 2.B split, one had grown 25 KB) with nothing checking a claim CLAUDE.md
    makes about itself. Blocking, same rationale as checks 6/8/9/11/12/13: a byte count and a commit
    count are exactly the kind of claim that should never be trusted from memory.

    Check 17 (docs/FAIL_OPEN_PLAN.md R3): three allowlists (corpus-parse, test-task-coverage,
    dsl-coverage) each already state in their own _comment header that a new entry needs a REG-nn/B-nn
    citation -- never machine-checked before this. Blocking on those three (all empty as of this
    check shipping, so zero grandfathering risk); the other two allowlists (plan-deferral-citation,
    security-pattern-sweep) use an established, different citation shape and are only counted, never
    enforced here -- explicitly not re-auditing 281 existing security-sweep entries. Also prints every
    allowlist's size every run, so growth is visible in the log instead of requiring someone to open
    five files to notice an allowlist stopped being empty.

    Check 18 (docs/INVOCATION_TOPOLOGY_PLAN.md T1): runs every scripts/quality/*.py script whose
    argparse declares --calibrate (the list is derived by scanning for that literal idiom, not
    hand-maintained -- a hand-list would drift exactly like the artifacts this whole plan is about).
    A calibration nobody runs is a claim, not evidence: two of these (REG-67, REG-68) had silently
    rotted -- both pinned a real-instance control to bare `HEAD`, which stopped proving anything once
    the target doc was edited again -- and nothing had ever run them in CI to notice. Blocking, same
    rationale as checks 6/8/9/11/12/13.

    Checks 19, 20 and 22 are O4 (Move 11 W2), and they are one finding, not three. Measured across
    all 13 scripts/quality/check-*.py: eleven were hosted here, and TWO were invoked by no
    scripts/quality/run-*.ps1 at all -- check-panel-provenance-impact.py (REG-93's own checker, which
    is why REG-93 stayed red for two moves while three consecutive move reports claimed "all gates
    green") and check-dsl-conformance-generates.py. This is the invocation-topology class that
    docs/INVOCATION_TOPOLOGY_PLAN.md closed once with four instances; these are five and six, and
    they appeared AFTER that plan closed. The plan fixed the instances, not the class.

    So: 19 and 20 host the two orphans, and 22's checker gained the rule that makes instance seven
    impossible -- any scripts/quality/check-*.py named by no run-*.ps1 now fails, whatever it
    declares. (`manual-runbook` was a legal invocation declaration, so "a check exists and nothing
    runs it" was a DECLARABLE, PASSING state for exactly the class of script where it must not be.)

    Check 21 is O5 (Move 11 W4), a different class again: not "nothing runs the check" but "the test
    starts downstream of the layer holding the bug." REG-71 used a noop semantic policy; REG-83's test
    gateway lacked the governed one; REG-89's kernel tests build a ProcedureStep directly and never go
    through SemanticValidator -- so createIfMissing was undeclarable in ANY model for two moves while
    every test for it passed. This check asks whether a model-level test EXISTS for each step type and
    whether it actually reaches the validator; Gradle still runs the tests themselves.

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

    # [1/22] Register self-check runs FIRST: platform-status.json is DERIVED from the same documents,
    # so a summary row contradicting its own detail section does not just mislead a human reader --
    # it propagates straight into the AI knowledge substrate the MCP tools serve. Catching it before
    # the projection is regenerated stops the drift at its source. (An audit on 2026-07-24/25 found
    # ~12 such rows; every one would have been caught here in under a second.)
    Write-Host "[1/22] Checking register/roadmap summary rows against their detail sections..."
    & $py "scripts/quality/check-register-consistency.py"
    if ($LASTEXITCODE -ne 0) { $failures += "register/roadmap summary rows contradict their own detail sections" }

    if ($Fix) {
        Write-Host "[2/22] Regenerating platform-status projection..." -ForegroundColor Yellow
        & $py "scripts/ai/extract_platform_status.py"
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status regeneration failed" }
    } else {
        Write-Host "[2/22] Checking platform-status projection is current..."
        & $py "scripts/ai/extract_platform_status.py" --check
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status projection is STALE (run with -Fix)" }
    }

    Write-Host "[3/22] Validating knowledge cards..."
    & $py "scripts/ai/build_knowledge.py" --validate-only
    if ($LASTEXITCODE -ne 0) { $failures += "knowledge-card validation failed" }

    Write-Host "[4/22] Checking failure-signature normalizer..."
    $sig = & $py "scripts/ai/failure_signatures.py" "Panel 'Orders' references unknown entity 'Customer'"
    $expected = "panel <id> references unknown entity <id>"
    if ($sig.Trim() -ne $expected) {
        $failures += "normalizer self-check failed: got '$($sig.Trim())' expected '$expected'"
    }

    # [5/22] Same question as [1/22], asked of a different mechanism: is this quality tool still doing
    # what its documentation claims? The sweep's fixtures are the REAL historical shapes of bugs this
    # repo shipped (LNCH13-F1, REG-39, REG-36) plus each one's fix, and it must separate them. A sweep
    # that reports 350 hits but would have walked past LNCH13-F1 does not just fail to help -- it
    # manufactures confidence. Note this checks the PATTERNS, not the codebase: it cannot fail because
    # someone wrote new code, only because someone broke the detector.
    Write-Host "[5/22] Checking the security pattern sweep still catches its known bugs..."
    & $py "scripts/quality/security-pattern-sweep.py" --self-test
    if ($LASTEXITCODE -ne 0) { $failures += "security-pattern-sweep self-test failed: a pattern no longer catches the bug it was written for" }

    # [6/22] Run the sweep against the CODEBASE and fail on anything untriaged. Until now the gate
    # proved the detector worked ([5/22]) but never actually pointed it at the repo, so a new hit could
    # sit unnoticed indefinitely -- which is exactly what happened: closing the triage loop drove the
    # count 307 -> 8, and REG-46's own fix then silently added 8 more in the adapter it modified.
    # A sweep whose "new" count is allowed to drift upward stops being read, and a real hit hides in
    # the noise (that is how REG-47 nearly stayed buried).
    #
    # This asks for triage AT WRITE TIME, when the author knows why their code is safe and it costs a
    # minute; the allowlist entry is a fingerprint + a reason. It is deliberately BLOCKING rather than
    # advisory, because an advisory count that nobody must act on is the state this replaces.
    # To relax it, drop `--fail-on-new` (the sweep still reports) -- but prefer triaging the hit.
    Write-Host "[6/22] Checking for untriaged security-pattern hits..."
    & $py "scripts/quality/security-pattern-sweep.py" --fail-on-new
    if ($LASTEXITCODE -ne 0) {
        $failures += "untriaged security-pattern hits: review each, then record a fingerprint + REASON in scripts/quality/security-pattern-sweep-allowlist.json and the rule in docs/SECURITY_PATTERN_SWEEP_2026-07.md (a false 'safe' is worse than a noisy hit)"
    }

    # [7/22] Report-only (Phase 2.4, docs/archive/programme-history/REMAINDER_CLOSURE_PLAN.md): calibrated against both real
    # 2026-07-27 instances (see the script's own --calibrate mode) and zero false positives on this
    # corpus at the time it shipped. Deliberately does not add to $failures yet -- promote once a
    # clean-tree run has stayed at zero for a while, per lesson #4.
    Write-Host "[7/22] Checking for narrative-status drift (report-only)..."
    & $py "scripts/quality/check-narrative-status-drift.py"

    # [8/22] T1.3: a custom Gradle Test task (behaviorTest/integrationTest/a future contractTest) that
    # is declared but reachable from no CI workflow is a test that only runs on one laptop -- exactly
    # what REG-49's residual behaviorTest was until T1.2 wired it in. Blocking, same rationale as [6/22].
    Write-Host "[8/22] Checking every custom Gradle Test task is reachable from a CI workflow..."
    & $py "scripts/quality/check-test-task-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a custom Gradle Test task is unreachable from CI: see scripts/quality/check-test-task-coverage.py output above, then either wire it into a workflow or record a reviewed exemption in scripts/quality/test-task-coverage-allowlist.json"
    }

    # [9/22] 2.A.2 (docs/DSL2_AND_DECOMPOSITION_PLAN.md): model.schema.json is duplicated in four
    # places with nothing previously enforcing they stay in sync -- they had already drifted by the
    # time this gate was written. Blocking, same rationale as [6/22] and [8/22]: an unsynced schema
    # copy silently teaches a stale contract to whichever consumer reads it (authoring UI, DSL module,
    # the legacy authoring location), with no error until something built against the stale copy fails
    # far away from the edit that caused it.
    Write-Host "[9/22] Checking the four model.schema.json copies are still semantically identical..."
    & $py "scripts/quality/check-schema-mirror-consistency.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "the four model.schema.json copies have drifted: see scripts/quality/check-schema-mirror-consistency.py output above for which key differs, then mirror the edit to all four (CLAUDE.md's own standing rule)"
    }

    # [10/22] R-G1 static half (docs/REMEDIATION_PLAN.md): the panel-provenance impact gate (F4)
    # needs a live authenticated bundle to check field/invocation EXISTENCE (that half now runs
    # per-app via _ops/Check-Provenance.ps1, wired 2026-07-28) -- but a manifest's own SHAPE
    # (required fields, no unexpected fields, a well-formed invokes[] id) needs no live app at all.
    # Runs against the AppGen apps workspace when present on this machine; 0 manifests found (e.g.
    # a bare CI checkout, which has no AppGen/apps at all) is a printed PASS, not a silent skip.
    Write-Host "[10/22] Checking *.panel.json manifests structurally validate (no live app needed)..."
    & $py "scripts/quality/check-panel-provenance-schema.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a *.panel.json manifest fails structural validation: see scripts/quality/check-panel-provenance-schema.py output above"
    }

    # [11/22] C4 (docs/CORPUS_INTEGRITY_PLAN.md): every model.json under AppGen/apps + NPDevSamples
    # must still parse against the REAL validator. Blocking, same rationale as [6/22]/[8/22]/[9/22] --
    # REG-63 is what happened when nothing checked this: 17 of 29 corpus models silently stopped
    # parsing as the schema evolved, unnoticed for weeks. Runs against AppGen/apps when present on
    # this machine (a bare CI checkout still gets full NPDevSamples coverage); a reviewed, REG-id'd
    # exception goes in scripts/quality/corpus-parse-allowlist.json -- never pre-cleared.
    Write-Host "[11/22] Checking every corpus model.json still parses..."
    & $py "scripts/quality/validate-corpus.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a corpus model no longer parses: see scripts/quality/validate-corpus.py output above, then either fix the model or record a reviewed exception (with a REG id) in scripts/quality/corpus-parse-allowlist.json"
    }

    Write-Host "[12/22] Checking every relative markdown link resolves..."
    & $py "scripts/quality/check-markdown-links.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a relative markdown link is broken: see scripts/quality/check-markdown-links.py output above"
    }

    # [13/22] Found by accident verifying F1/F2 on a live PR, 2026-07-29: an unquoted colon inside a
    # step name made npdev-pr-gate.yml invalid YAML -- GitHub scheduled ZERO jobs for it, silently,
    # on every push and PR for hours before anyone noticed. Same "nothing looked" shape as checks
    # 11/14 and 12/14. Syntax only (yaml.safe_load), not GitHub Actions schema validation -- cheapest
    # version of the fix, matching the failure mode that actually happened.
    Write-Host "[13/22] Checking every workflow file is valid YAML..."
    & $py "scripts/quality/check-workflow-yaml-syntax.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a workflow file is not valid YAML: see scripts/quality/check-workflow-yaml-syntax.py output above"
    }

    # [14/22] F6 (docs/FINAL_OPEN_ITEMS_PLAN.md): the simple-user-registry-* and p77-hookproof(-pg)
    # engine-variant families have byte-identical model bodies by design (differing only in DB
    # engine config) -- nothing previously asserted they STAY identical, so a fix applied to one
    # would not visibly propagate to its siblings. Membership is declared (hand-reviewed) in
    # corpus-roles.json; sameness is asserted here, every run.
    Write-Host "[14/22] Checking engine-variant families stay byte-identical..."
    & $py "scripts/quality/check-engine-variant-families.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an engine-variant family has diverged: see scripts/quality/check-engine-variant-families.py output above"
    }

    # [15/22] F8 (docs/FINAL_OPEN_ITEMS_PLAN.md): the corpus-parse gate (check 11) answers "does
    # every model parse?" -- nothing answered "is every DSL feature exercised by at least one
    # model?", the gap that let 8 schema features (selectors, externalAi, step forEach, step
    # generatedAction, onFailure compensation, flow.schedule, flow.hooks, flow.specializes) sit at
    # zero coverage until dsl-conformance-max (F3) closed them -- confirmed live: re-running this
    # exact check against the corpus with that fixture excluded reproduces all 8 as RED. Sequenced
    # after F4 (generatedAction only became reachable once FlowValidation's switch was fixed).
    Write-Host "[15/22] Checking every DSL feature has at least one corpus model exercising it..."
    & $py "scripts/quality/check-dsl-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a DSL feature has zero corpus coverage: see scripts/quality/check-dsl-coverage.py output above -- add a real example to NPDevSamples/dsl-conformance-max, or record a reviewed exception with a REG id"
    }

    # [16/22] docs/RECORD_SURFACES_PLAN.md P4: two mechanical record-surface claims that go stale
    # silently -- how far origin/main has drifted behind this branch, and whether CLAUDE.md's own
    # "Large files" block still matches the files on disk. Both found real drift on 2026-07-29 (71
    # commits, three misstated sizes) with nothing previously checking either. Blocking, same
    # rationale as [6/22]/[8/22]/[9/22]/[11/22]/[12/22]/[13/22].
    Write-Host "[16/22] Checking branch freshness vs. origin/main and CLAUDE.md's large-file size claims..."
    & $py "scripts/quality/check-record-surfaces.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a record surface has drifted: see scripts/quality/check-record-surfaces.py output above (branch gap vs. origin/main, or a stale CLAUDE.md file-size claim)"
    }

    # [17/22] docs/FAIL_OPEN_PLAN.md R3: three allowlists (corpus-parse, test-task-coverage,
    # dsl-coverage) each already promise in their own _comment header that a new entry needs a
    # REG-nn/B-nn citation -- never machine-checked until now. Blocking on those three (all empty
    # today, zero grandfathering risk); the other two (plan-deferral-citation, security-pattern-sweep)
    # use an established, different citation convention and are reported only, never enforced here --
    # explicitly not re-auditing 281 existing security-sweep entries. Also prints every allowlist's
    # size every run, so growth is visible in the log rather than requiring someone to open five files.
    Write-Host "[17/22] Checking allowlist entries carry a REG/B citation, reporting allowlist sizes..."
    & $py "scripts/quality/check-allowlist-citations.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an allowlist entry has no REG-nn/B-nn citation: see scripts/quality/check-allowlist-citations.py output above"
    }

    # [18/22] docs/INVOCATION_TOPOLOGY_PLAN.md T1: a calibration nobody runs is a claim, not
    # evidence -- REG-67 and REG-68 both rotted silently (a real-instance control pinned to bare
    # `HEAD`, which stopped proving anything once its target doc was edited again) and nothing
    # noticed until someone happened to run `--calibrate` by hand. The script list is DERIVED from
    # argparse (any scripts/quality/*.py declaring `--calibrate`), not hand-maintained, so a future
    # calibratable script is picked up automatically instead of needing this list updated too.
    Write-Host "[18/22] Running every --calibrate self-test (list derived from argparse, not hand-written)..."
    $calibratable = @(Get-ChildItem "scripts/quality/*.py" | Where-Object {
        (Get-Content $_.FullName -Raw) -match 'add_argument\(\s*"--calibrate"'
    })
    Write-Host "  found $($calibratable.Count) calibratable script(s): $($calibratable.Name -join ', ')"
    foreach ($s in $calibratable) {
        & $py $s.FullName --calibrate
        if ($LASTEXITCODE -ne 0) {
            $failures += "$($s.Name) --calibrate FAILED: it can no longer prove it detects its own target bug"
        }
    }

    # [19/22] O4 (Move 11 W2): the EXISTENCE half of the panel-provenance impact gate. Its only
    # caller was the per-app `_ops/Check-Provenance.ps1` that Build-NpdevApp.ps1 EMITS -- which needs
    # a running, authenticated app, so no repo gate could ever run it, and REG-93 sat red across
    # three moves while three move reports said "all gates green". `--discover` pairs each built
    # app's own `_ops/app-plan.json` (it already declares webSourceDir) with the compiled metadata
    # beside it: no live app, no credentials, no new hand-maintained list. 0 built apps found is a
    # printed PASS, same convention as [10/22].
    Write-Host "[19/22] Checking panel-provenance manifests against each built app's current model..."
    & $py "scripts/quality/check-panel-provenance-impact.py" --discover
    if ($LASTEXITCODE -ne 0) {
        $failures += "a confirmed *.panel.json manifest references a model element that no longer exists: see scripts/quality/check-panel-provenance-impact.py output above, then either regenerate the screen or update the model"
    }

    # [20/22] O4 (Move 11 W2): the emit-side mirror of [15/22]. Also invoked by npdev-pr-gate.yml
    # directly, but by NO run-*.ps1 -- so running every gate on this machine never exercised it, and
    # "the generator gate passed" never meant "the generator still emits every DSL feature". Hosted
    # next to its parse-side twin, which is where a reader looking for one would expect the other.
    Write-Host "[20/22] Checking every asserted DSL feature survives GENERATION, not just parsing..."
    & $py "scripts/quality/check-dsl-conformance-generates.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a DSL feature parses but no longer survives generation: see scripts/quality/check-dsl-conformance-generates.py output above"
    }

    # [21/22] O5 (Move 11 W4): does every step type declared in model.schema.json have a MODEL-level
    # validation test -- one that goes through SemanticValidator, not one that hand-builds a step
    # object? REG-89 is why: patchConcept's createIfMissing shipped in Move 5, was "fixed" by REG-83,
    # was re-specced in Move 9, and for two moves could not be declared in ANY model -- while all its
    # kernel tests passed, because they construct a ProcedureStep and hand it to the executor, so the
    # validator that forbade the declaration was never in the picture. Structural half only (Gradle
    # runs the tests); this answers "is a model-level test even there to run?"
    Write-Host "[21/22] Checking every step type has a model-level validation test..."
    & $py "scripts/quality/check-step-type-test-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a step type has no model-level validation test: see scripts/quality/check-step-type-test-coverage.py output above -- add an example to the conformance test for that step kind"
    }

    # [22/22] docs/INVOCATION_TOPOLOGY_PLAN.md T2: every script under scripts/ must declare BOTH a
    # classification (what it is) and an invocation (what invokes it), and both must match reality --
    # generalizes check-test-task-coverage.py's "declared but never invoked" check from Gradle Test
    # tasks to every script. This checker was itself an orphan until that step: nothing had ever
    # invoked it (grep-confirmed at the time), the pattern in miniature. Move 11 W2 added the rule
    # that catches instance five and six of the same class -- a scripts/quality/check-*.py named by
    # no run-*.ps1 -- so it must run LAST, after every checker this gate hosts is in place.
    Write-Host "[22/22] Checking every script declares a classification + invocation matching reality..."
    pwsh -NoProfile -File "scripts/quality/run-script-inventory-check.ps1"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a script's classification/invocation declaration is missing or does not match reality, or a scripts/quality/check-*.py is invoked by no gate at all: see scripts/quality/run-script-inventory-check.ps1 output above, or scripts/reports/out/script-inventory-report.json"
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
