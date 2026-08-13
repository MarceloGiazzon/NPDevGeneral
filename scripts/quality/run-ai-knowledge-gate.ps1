<#
.SYNOPSIS
    AI knowledge substrate gate: proves the derived corpora can't silently drift from their sources.

.DESCRIPTION
    Fails (non-zero exit) if:
      1. the gaps-roadmap generator (scripts/docs/generate_gaps_roadmap.py) fails to run, or
         knowledge/platform-status.json is stale vs a fresh extraction of ledger/gaps.yml --
         i.e. someone hand-edited the committed platform-status projection without regenerating it
         (md-zero-2026-08-11 PLAN.md Phase 6: the gaps-roadmap doc itself is no longer committed,
         so there is nothing left to compare it against -- only whether it still generates).
      2. any knowledge/cards/*.json fails knowledge-card validation.
      3. the shared failure-signature normalizer self-check fails.
      4. the security pattern sweep no longer catches the bug shapes it was written for.
      5. the security pattern sweep finds an UNTRIAGED hit in the codebase.

    md-zero-2026-08-11 PLAN.md Phase 2 deleted three checkers that guarded closed programme history:
    check-register-consistency.py (register/roadmap summary rows vs. detail sections -- the old item
    1 above), check-narrative-status-drift.py (report-only narrative-prose drift), and
    check-blocker-citation-freshness.py (see [37/35] below). All 177 items the first guarded were
    DONE; the docs moved to __OutsideRepo/md-zero-2026-08-11/archived-programme-docs/ (git history
    keeps them at their old repo path). "A checker reading markdown is itself the defect" is this
    plan's premise -- these guarded work that closed months ago, not a live drift surface.

    Checks 4 and 5 both answer one question: are the instruments still honest? 4 verifies the
    detector; 5 actually points one at the repo -- the step whose absence let REG-46's own fix add
    8 untriaged hits unnoticed.

    Check 6 (T1.3, docs/TREE1_LAUNCH_UNBLOCK_PLAN.md) makes the T1.2 bug class impossible to recur
    silently: a custom Gradle `Test` task (a new `behaviorTest`/`integrationTest`/`contractTest`
    source set) that is declared but never named by any CI workflow, and not check-wired with `check`
    itself invoked. Blocking, per this gate's own established pattern (check 5) -- a coverage gap that
    is merely reported is exactly the state that let `:generator:behaviorTest` run on one laptop and
    nowhere else for as long as it did.

    Check 7 (2.A.2, docs/DSL2_AND_DECOMPOSITION_PLAN.md) is the same shape again: model.schema.json is
    duplicated in four places with nothing previously enforcing they stay in sync, and they had
    already drifted by the time this check was written. Blocking.

    Check 8 (R-G1 static half, docs/REMEDIATION_PLAN.md) validates every *.panel.json manifest's own
    SHAPE against schemas/panel-provenance.schema.json (required fields, no unexpected fields, a
    well-formed invokes[] id) -- the half of the panel-provenance impact gate (F4) that needs no live
    app. The EXISTENCE half is check 17 below (it used to run ONLY per-app, against a live
    authenticated bundle, via that app's own `_ops/Check-Provenance.ps1`).

    Check 9 (C4, docs/CORPUS_INTEGRITY_PLAN.md) validates every model.json in the corpus
    (AppGen/apps + NPDevSamples) still parses against the real validator (JsonModelParser +
    SemanticValidator via the validateModel Gradle task) -- not a heuristic. Blocking, same rationale
    as checks 5/6/7: nothing previously noticed when 17 of 29 corpus models silently stopped parsing
    as the schema evolved (REG-63), and it stayed unnoticed for weeks until a manifest task tripped
    over two of them by accident. Calibrated RED against the pre-fix corpus (17 failures, captured
    2026-07-29) then GREEN after `npdev migrate dsl-2 --write`.

    Check 10 (C5/N5, docs/CORPUS_INTEGRITY_PLAN.md) fails on any relative markdown link that does not
    resolve. Blocking, same "nothing looked" shape as check 9: two separate doc reorganizations (the
    T1.15/2.A split, and R-P2's programme-history archival) each produced dangling links found only by
    ad-hoc grep, and a third genuinely broken link (a wrong relative-path depth) and a fourth stale
    claim (a doc believed never-written that in fact existed under a different directory) turned up
    building this very check. Repo-wide, ~270 files, a few hundred ms.

    Check 11 (docs/FINAL_OPEN_ITEMS_PLAN.md F1/F2) fails if any `.github/workflows/*.yml` is not
    valid YAML. Found by accident, not by design: verifying F1/F2 on a live PR turned up
    npdev-pr-gate.yml silently scheduling ZERO jobs for hours -- an unquoted colon inside a step
    `name:` (`findings: persistence, idempotency`) made the whole file unparseable, and nothing in
    this repo's own thorough gate suite had ever checked that a workflow file still parses. Same
    class as checks 9/10; syntax only, not GitHub Actions schema validation.

    Check 12 (F6) fails if a declared engine-variant family (corpus-roles.json -- currently
    simple-user-registry-* and p77-hookproof/-pg, byte-identical model bodies by design) has
    diverged. A DSL fix applied to one family member previously had no mechanism to surface that it
    was not propagated to the others.

    Check 13 (F8) fails if any tracked DSL feature has zero corpus models exercising it. The class
    fix behind F3/F4: NPDevSamples/dsl-conformance-max fixes today's 8 zero-coverage features once;
    this check is why a 9th can be added and never fixtured.

    Check 14 (docs/RECORD_SURFACES_PLAN.md P4): a different class than checks 1-13 -- not "a check
    exists, nothing runs it" but "a surface of record goes stale after successful work, and nothing
    notices." Two mechanical claims that went stale silently on 2026-07-29: `origin/main` reached 71
    commits behind (the third recurrence; public repo, default branch, what a clone gets) with nothing
    measuring the gap, and CLAUDE.md's own "Large files" block misstated three files' sizes (two had
    shrunk to ~12 KB after the 2.B split, one had grown 25 KB) with nothing checking a claim CLAUDE.md
    makes about itself. Blocking, same rationale as checks 5/6/7/9/10/11: a byte count and a commit
    count are exactly the kind of claim that should never be trusted from memory.

    Check 15 (docs/FAIL_OPEN_PLAN.md R3): three allowlists (corpus-parse, test-task-coverage,
    dsl-coverage) each already state in their own _comment header that a new entry needs a REG-nn/B-nn
    citation -- never machine-checked before this. Blocking on those three (all empty as of this
    check shipping, so zero grandfathering risk); the other two allowlists (plan-deferral-citation,
    security-pattern-sweep) use an established, different citation shape and are only counted, never
    enforced here -- explicitly not re-auditing 281 existing security-sweep entries. Also prints every
    allowlist's size every run, so growth is visible in the log instead of requiring someone to open
    five files to notice an allowlist stopped being empty.

    Check 16 (docs/INVOCATION_TOPOLOGY_PLAN.md T1): runs every scripts/quality/*.py script whose
    argparse declares --calibrate (the list is derived by scanning for that literal idiom, not
    hand-maintained -- a hand-list would drift exactly like the artifacts this whole plan is about).
    A calibration nobody runs is a claim, not evidence: two of these (REG-67, REG-68) had silently
    rotted -- both pinned a real-instance control to bare `HEAD`, which stopped proving anything once
    the target doc was edited again -- and nothing had ever run them in CI to notice. Blocking, same
    rationale as checks 5/6/7/9/10/11.

    Checks 17, 18 and 20 are O4 (Move 11 W2), and they are one finding, not three. Measured across
    all 13 scripts/quality/check-*.py: eleven were hosted here, and TWO were invoked by no
    scripts/quality/run-*.ps1 at all -- check-panel-provenance-impact.py (REG-93's own checker, which
    is why REG-93 stayed red for two moves while three consecutive move reports claimed "all gates
    green") and check-dsl-conformance-generates.py. This is the invocation-topology class that
    docs/INVOCATION_TOPOLOGY_PLAN.md closed once with four instances; these are five and six, and
    they appeared AFTER that plan closed. The plan fixed the instances, not the class.

    So: 17 and 18 host the two orphans, and 20's checker gained the rule that makes instance seven
    impossible -- any scripts/quality/check-*.py named by no run-*.ps1 now fails, whatever it
    declares. (`manual-runbook` was a legal invocation declaration, so "a check exists and nothing
    runs it" was a DECLARABLE, PASSING state for exactly the class of script where it must not be.)

    Check 19 is O5 (Move 11 W4), a different class again: not "nothing runs the check" but "the test
    starts downstream of the layer holding the bug." REG-71 used a noop semantic policy; REG-83's test
    gateway lacked the governed one; REG-89's kernel tests build a ProcedureStep directly and never go
    through SemanticValidator -- so createIfMissing was undeclarable in ANY model for two moves while
    every test for it passed. This check asks whether a model-level test EXISTS for each step type and
    whether it actually reaches the validator; Gradle still runs the tests themselves.

    Check 25 (S4 Phase B, S4_SPEC.md): a different class again -- not "a check exists and nothing
    runs it" but "a DECISION was accepted and nothing verifies it was ever implemented." ADR-0011's
    D4 ("no physical table prefixing") was ratified by the owner in S2's own gate and recorded in
    the ADR, and `ModelCompiler` did not do it -- found only by running the S3 codemod against real
    content, not by any control. Six controls already exist for "declared but unwired" in a FEATURE
    (REG-70, generatedAction, createIfMissing, ReleaseGateValidator, field.sensitive); none of them
    ever checked a DECISION RECORD. Deliberately narrow and opt-in: an ADR decision only gets checked
    if it carries an explicit ```decision-check fenced block naming a file and a substring that file
    must contain -- starts with ADR-0011's four decisions (D1-D4) only, not retrofitted across the
    other 10 ADRs. Blocking, same rationale as every other checker in this gate.

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

    # md-zero-2026-08-11 PLAN.md Phase 2: check-register-consistency.py, the old first check, is deleted --
    # it guarded four closed-programme ledgers (NPDEV_OPEN_ITEMS_REGISTER.md, LAUNCH_READINESS_GAPS.md,
    # DSL2_AND_DECOMPOSITION_PLAN.md, EXECUTION_TREES.md), all 177 items DONE, moved to
    # __OutsideRepo/md-zero-2026-08-11/archived-programme-docs/ (git history keeps them at their old
    # path).
    # md-zero-2026-08-11 PLAN.md Phase 6 (Group G): OPEN_GAPS_AND_ROADMAP.md is no longer committed
    # to the repo at all -- it always regenerates fresh to the external Build root now, so there is
    # nothing left to "check" for drift. What remains is a smoke test: does generation still run to
    # completion (a malformed ledger/gaps.yml or a broken narrative template fails loudly here).
    # platform-status.json IS still committed (Phase 1, unaffected by Group G) -- its own --check
    # stays a real drift comparison, unchanged.
    if ($Fix) {
        Write-Host "[1/35] Regenerating gaps roadmap + platform-status projection..." -ForegroundColor Yellow
        & $py "scripts/docs/generate_gaps_roadmap.py"
        if ($LASTEXITCODE -ne 0) { $failures += "gaps-roadmap generation failed" }
        & $py "scripts/ai/extract_platform_status.py"
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status regeneration failed" }
    } else {
        Write-Host "[1/35] Checking gaps roadmap generates + platform-status projection is current..."
        & $py "scripts/docs/generate_gaps_roadmap.py" | Out-Null
        if ($LASTEXITCODE -ne 0) { $failures += "gaps-roadmap generation failed: see scripts/docs/generate_gaps_roadmap.py output above" }
        & $py "scripts/ai/extract_platform_status.py" --check
        if ($LASTEXITCODE -ne 0) { $failures += "platform-status projection is STALE (run with -Fix)" }
    }

    Write-Host "[2/35] Validating knowledge cards..."
    & $py "scripts/ai/build_knowledge.py" --validate-only
    if ($LASTEXITCODE -ne 0) { $failures += "knowledge-card validation failed" }

    # Bundled into the same [2/35] slot (docs-decoupling-2026-08-11 PLAN.md Phase 1's precedent:
    # avoid renumbering every banner for one more freshness check). md-zero-2026-08-11 PLAN.md
    # Phase 4: docs/NPDEV_CONCEPTS_DEEP_DIVE.md, docs/NPDEV_USER_MANUAL.md, docs/ai/AUTHORING_FOR_AI.md
    # and docs/ai/UI_GENERATION_PROMPT.md are GENERATED from content/*.yml, the same prose
    # scripts/ai/build_rag_index.py chunks and scripts/ai/build_core_context.py concatenates whole
    # -- if a hand-edit touches the .md without touching its YAML source, this catches it.
    #
    # PLAN-11-to-4.md Item 2: this used to run with --check (render in memory, read the CURRENTLY
    # COMMITTED .md back off disk to compare) -- itself a script reading markdown content. Now it
    # always regenerates in place, and git (which diffs bytes without this process ever opening the
    # .md) answers "did that change anything already committed".
    & $py "scripts/docs/generate_group_e_docs.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "scripts/docs/generate_group_e_docs.py failed to run: see its output above"
    } else {
        git diff --exit-code -- docs/NPDEV_CONCEPTS_DEEP_DIVE.md docs/NPDEV_USER_MANUAL.md docs/ai/AUTHORING_FOR_AI.md docs/ai/UI_GENERATION_PROMPT.md content/npdev-concepts-deep-dive.json content/npdev-user-manual.json content/authoring-for-ai.json content/ui-generation-prompt.json
        if ($LASTEXITCODE -ne 0) { $failures += "a Group E doc or its content/*.json mirror was STALE relative to its content/*.yml source (regenerating changed a committed file, shown above) -- commit the regenerated result" }
    }

    # md-zero-2026-08-11 PLAN.md Phase 5: same freshness check for the four executable docs
    # (README.md, docs/GETTING_STARTED.md, docs/YOUR_FIRST_APP.md, docs/AUTHORING_WITH_AI.md) and
    # their content/*.json mirrors -- the first-run harness and extract_commands.py read the JSON,
    # never the .md, so this is what catches a hand-edited .md that was never regenerated.
    # Same PLAN-11-to-4.md Item 2 shape as Group E above: regenerate, then let git diff decide.
    & $py "scripts/docs/generate_group_d_docs.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "scripts/docs/generate_group_d_docs.py failed to run: see its output above"
    } else {
        git diff --exit-code -- README.md docs/GETTING_STARTED.md docs/YOUR_FIRST_APP.md docs/AUTHORING_WITH_AI.md content/readme.json content/getting-started.json content/your-first-app.json content/authoring-with-ai.json
        if ($LASTEXITCODE -ne 0) { $failures += "a Group D doc or its content/*.json mirror was STALE relative to its content/*.yml source (regenerating changed a committed file, shown above) -- commit the regenerated result" }
    }

    Write-Host "[3/35] Checking failure-signature normalizer..."
    $sig = & $py "scripts/ai/failure_signatures.py" "Panel 'Orders' references unknown entity 'Customer'"
    $expected = "panel <id> references unknown entity <id>"
    if ($sig.Trim() -ne $expected) {
        $failures += "normalizer self-check failed: got '$($sig.Trim())' expected '$expected'"
    }

    # [4/35] The same question check-register-consistency.py (deleted, md-zero-2026-08-11 PLAN.md
    # Phase 2) used to ask of the register, asked here of a different mechanism: is this quality tool
    # still doing what its documentation claims? The sweep's fixtures are the REAL historical shapes of bugs this
    # repo shipped (LNCH13-F1, REG-39, REG-36) plus each one's fix, and it must separate them. A sweep
    # that reports 350 hits but would have walked past LNCH13-F1 does not just fail to help -- it
    # manufactures confidence. Note this checks the PATTERNS, not the codebase: it cannot fail because
    # someone wrote new code, only because someone broke the detector.
    Write-Host "[4/35] Checking the security pattern sweep still catches its known bugs..."
    & $py "scripts/quality/security-pattern-sweep.py" --self-test
    if ($LASTEXITCODE -ne 0) { $failures += "security-pattern-sweep self-test failed: a pattern no longer catches the bug it was written for" }

    # [5/35] Run the sweep against the CODEBASE and fail on anything untriaged. Until now the gate
    # proved the detector worked ([4/35]) but never actually pointed it at the repo, so a new hit could
    # sit unnoticed indefinitely -- which is exactly what happened: closing the triage loop drove the
    # count 307 -> 8, and REG-46's own fix then silently added 8 more in the adapter it modified.
    # A sweep whose "new" count is allowed to drift upward stops being read, and a real hit hides in
    # the noise (that is how REG-47 nearly stayed buried).
    #
    # This asks for triage AT WRITE TIME, when the author knows why their code is safe and it costs a
    # minute; the allowlist entry is a fingerprint + a reason. It is deliberately BLOCKING rather than
    # advisory, because an advisory count that nobody must act on is the state this replaces.
    # To relax it, drop `--fail-on-new` (the sweep still reports) -- but prefer triaging the hit.
    Write-Host "[5/35] Checking for untriaged security-pattern hits..."
    & $py "scripts/quality/security-pattern-sweep.py" --fail-on-new
    if ($LASTEXITCODE -ne 0) {
        $failures += "untriaged security-pattern hits: review each, then record a fingerprint + REASON in scripts/quality/security-pattern-sweep-allowlist.json and the rule in docs/SECURITY_PATTERN_SWEEP_2026-07.md (a false 'safe' is worse than a noisy hit)"
    }

    # [6/35] T1.3: a custom Gradle Test task (behaviorTest/integrationTest/a future contractTest) that
    # is declared but reachable from no CI workflow is a test that only runs on one laptop -- exactly
    # what REG-49's residual behaviorTest was until T1.2 wired it in. Blocking, same rationale as [5/35].
    Write-Host "[6/35] Checking every custom Gradle Test task is reachable from a CI workflow..."
    & $py "scripts/quality/check-test-task-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a custom Gradle Test task is unreachable from CI: see scripts/quality/check-test-task-coverage.py output above, then either wire it into a workflow or record a reviewed exemption in scripts/quality/test-task-coverage-allowlist.json"
    }

    # [7/35] 2.A.2 (docs/DSL2_AND_DECOMPOSITION_PLAN.md): model.schema.json is duplicated in four
    # places with nothing previously enforcing they stay in sync -- they had already drifted by the
    # time this gate was written. Blocking, same rationale as [5/35] and [6/35]: an unsynced schema
    # copy silently teaches a stale contract to whichever consumer reads it (authoring UI, DSL module,
    # the legacy authoring location), with no error until something built against the stale copy fails
    # far away from the edit that caused it.
    Write-Host "[7/35] Checking the four model.schema.json copies are still semantically identical..."
    & $py "scripts/quality/check-schema-mirror-consistency.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "the four model.schema.json copies have drifted: see scripts/quality/check-schema-mirror-consistency.py output above for which key differs, then mirror the edit to all four (CLAUDE.md's own standing rule)"
    }

    # [8/35] R-G1 static half (docs/REMEDIATION_PLAN.md): the panel-provenance impact gate (F4)
    # needs a live authenticated bundle to check field/invocation EXISTENCE (that half now runs
    # per-app via _ops/Check-Provenance.ps1, wired 2026-07-28) -- but a manifest's own SHAPE
    # (required fields, no unexpected fields, a well-formed invokes[] id) needs no live app at all.
    # Runs against the AppGen apps workspace when present on this machine; 0 manifests found (e.g.
    # a bare CI checkout, which has no AppGen/apps at all) is a printed PASS, not a silent skip.
    Write-Host "[8/35] Checking *.panel.json manifests structurally validate (no live app needed)..."
    & $py "scripts/quality/check-panel-provenance-schema.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a *.panel.json manifest fails structural validation: see scripts/quality/check-panel-provenance-schema.py output above"
    }

    # [9/35] C4 (docs/CORPUS_INTEGRITY_PLAN.md): every model.json under AppGen/apps + NPDevSamples
    # must still parse against the REAL validator. Blocking, same rationale as [5/35]/[6/35]/[7/35] --
    # REG-63 is what happened when nothing checked this: 17 of 29 corpus models silently stopped
    # parsing as the schema evolved, unnoticed for weeks. Runs against AppGen/apps when present on
    # this machine (a bare CI checkout still gets full NPDevSamples coverage); a reviewed, REG-id'd
    # exception goes in scripts/quality/corpus-parse-allowlist.json -- never pre-cleared.
    Write-Host "[9/35] Checking every corpus model.json still parses..."
    & $py "scripts/quality/validate-corpus.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a corpus model no longer parses: see scripts/quality/validate-corpus.py output above, then either fix the model or record a reviewed exception (with a REG id) in scripts/quality/corpus-parse-allowlist.json"
    }

    # R4 Part C (MASTER-ROADMAP.md Step 9 / ledger QUAL-7): check-markdown-links.py moved to the
    # weekly paperwork gate (scripts/quality/run-weekly-paperwork-checks.ps1) -- a broken relative
    # link cannot be caused by the code change a reviewer is looking at right now in the same way a
    # broken build can, so gating every commit on it bought latency without buying safety. See
    # scripts/policy/gate-classification-policy.json.

    # [10/35] Found by accident verifying F1/F2 on a live PR, 2026-07-29: an unquoted colon inside a
    # step name made npdev-pr-gate.yml invalid YAML -- GitHub scheduled ZERO jobs for it, silently,
    # on every push and PR for hours before anyone noticed. Same "nothing looked" shape as checks
    # 11/14 and 12/14. Syntax only (yaml.safe_load), not GitHub Actions schema validation -- cheapest
    # version of the fix, matching the failure mode that actually happened.
    Write-Host "[10/35] Checking every workflow file is valid YAML..."
    & $py "scripts/quality/check-workflow-yaml-syntax.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a workflow file is not valid YAML: see scripts/quality/check-workflow-yaml-syntax.py output above"
    }

    # [11/35] F6 (docs/FINAL_OPEN_ITEMS_PLAN.md): the simple-user-registry-* and p77-hookproof(-pg)
    # engine-variant families have byte-identical model bodies by design (differing only in DB
    # engine config) -- nothing previously asserted they STAY identical, so a fix applied to one
    # would not visibly propagate to its siblings. Membership is declared (hand-reviewed) in
    # corpus-roles.json; sameness is asserted here, every run.
    Write-Host "[11/35] Checking engine-variant families stay byte-identical..."
    & $py "scripts/quality/check-engine-variant-families.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an engine-variant family has diverged: see scripts/quality/check-engine-variant-families.py output above"
    }

    # [12/35] F8 (docs/FINAL_OPEN_ITEMS_PLAN.md): the corpus-parse gate (check 11) answers "does
    # every model parse?" -- nothing answered "is every DSL feature exercised by at least one
    # model?", the gap that let 8 schema features (selectors, externalAi, step forEach, step
    # generatedAction, onFailure compensation, flow.schedule, flow.hooks, flow.specializes) sit at
    # zero coverage until dsl-conformance-max (F3) closed them -- confirmed live: re-running this
    # exact check against the corpus with that fixture excluded reproduces all 8 as RED. Sequenced
    # after F4 (generatedAction only became reachable once FlowValidation's switch was fixed).
    Write-Host "[12/35] Checking every DSL feature has at least one corpus model exercising it..."
    & $py "scripts/quality/check-dsl-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a DSL feature has zero corpus coverage: see scripts/quality/check-dsl-coverage.py output above -- add a real example to NPDevSamples/dsl-conformance-max, or record a reviewed exception with a REG id"
    }

    # R4 Part C: check-record-surfaces.py moved to the weekly paperwork gate -- branch-freshness and
    # record-surfaces.json drift are slow-moving, not something a single commit can introduce. See
    # scripts/policy/gate-classification-policy.json.

    # R4 Part C: check-allowlist-citations.py moved to the weekly paperwork gate -- allowlists change
    # rarely, and a missing citation on a new entry is caught on review, not by a per-commit gate.

    # [13/35] docs/INVOCATION_TOPOLOGY_PLAN.md T1: a calibration nobody runs is a claim, not
    # evidence -- REG-67 and REG-68 both rotted silently (a real-instance control pinned to bare
    # `HEAD`, which stopped proving anything once its target doc was edited again) and nothing
    # noticed until someone happened to run `--calibrate` by hand. The script list is DERIVED from
    # argparse (any scripts/quality/*.py declaring `--calibrate`), not hand-maintained, so a future
    # calibratable script is picked up automatically instead of needing this list updated too.
    Write-Host "[13/35] Running every --calibrate self-test (list derived from argparse, not hand-written)..."
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

    # [14/35] O4 (Move 11 W2): the EXISTENCE half of the panel-provenance impact gate. Its only
    # caller was the per-app `_ops/Check-Provenance.ps1` that Build-NpdevApp.ps1 EMITS -- which needs
    # a running, authenticated app, so no repo gate could ever run it, and REG-93 sat red across
    # three moves while three move reports said "all gates green". `--discover` pairs each built
    # app's own `_ops/app-plan.json` (it already declares webSourceDir) with the compiled metadata
    # beside it: no live app, no credentials, no new hand-maintained list. 0 built apps found is a
    # printed PASS, same convention as [8/35].
    Write-Host "[14/35] Checking panel-provenance manifests against each built app's current model..."
    & $py "scripts/quality/check-panel-provenance-impact.py" --discover
    if ($LASTEXITCODE -ne 0) {
        $failures += "a confirmed *.panel.json manifest references a model element that no longer exists: see scripts/quality/check-panel-provenance-impact.py output above, then either regenerate the screen or update the model"
    }

    # [15/35] O4 (Move 11 W2): the emit-side mirror of [13/35]. Also invoked by npdev-pr-gate.yml
    # directly, but by NO run-*.ps1 -- so running every gate on this machine never exercised it, and
    # "the generator gate passed" never meant "the generator still emits every DSL feature". Hosted
    # next to its parse-side twin, which is where a reader looking for one would expect the other.
    Write-Host "[15/35] Checking every asserted DSL feature survives GENERATION, not just parsing..."
    & $py "scripts/quality/check-dsl-conformance-generates.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a DSL feature parses but no longer survives generation: see scripts/quality/check-dsl-conformance-generates.py output above"
    }

    # [16/35] O5 (Move 11 W4): does every step type declared in model.schema.json have a MODEL-level
    # validation test -- one that goes through SemanticValidator, not one that hand-builds a step
    # object? REG-89 is why: patchConcept's createIfMissing shipped in Move 5, was "fixed" by REG-83,
    # was re-specced in Move 9, and for two moves could not be declared in ANY model -- while all its
    # kernel tests passed, because they construct a ProcedureStep and hand it to the executor, so the
    # validator that forbade the declaration was never in the picture. Structural half only (Gradle
    # runs the tests); this answers "is a model-level test even there to run?"
    Write-Host "[16/35] Checking every step type has a model-level validation test..."
    & $py "scripts/quality/check-step-type-test-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a step type has no model-level validation test: see scripts/quality/check-step-type-test-coverage.py output above -- add an example to the conformance test for that step kind"
    }

    # [17/35] Move 13 P6: makes the X0 silent-answer discipline a permanent gate instead of something
    # that only runs when someone remembers to re-audit. Eight silent-answer findings are confirmed;
    # the eighth (REG-108) was found BY ACCIDENT while building on top of the gap it named -- a
    # register nobody is forced to re-check is not a control. md-zero-2026-08-11 PLAN.md Phase 1
    # deleted docs/X0_SILENT_EXPRESSION_REGISTER.md (the narrative moved into the registry's own
    # why/note/detail fields) and with it the doc<->registry parity half of this check. What remains:
    # every FIXED/CLEAN entry's named regression test still exists and still contains its marker (a
    # proof that regressed is caught, not just a proof that once existed), and no evaluator-shaped
    # class under the registry's own tracked directories is missing an entry entirely.
    Write-Host "[17/35] Checking the X0 silent-answer registry..."
    & $py "scripts/quality/check-x0-evaluator-coverage.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a FIXED/CLEAN finding's proof test regressed, or a new evaluator-shaped class has no entry: see scripts/quality/check-x0-evaluator-coverage.py output above"
    }

    # R4 Part C: check-cadence-coverage.py moved to the weekly paperwork gate (still invoked there,
    # so script-inventory-check below still finds it hosted by SOME run-*.ps1) -- a missing cadence
    # entry is a slow-moving governance gap, not something one commit introduces.

    # [18/35] REG-110 (Move 14 Phase D): NPDevCli/tests/test_acceptance_runner.py -- the acceptance
    # runner (run_acceptance/LC-D2), the closed loop (run_closed_loop/LC-D3), and the JSONPath-lite
    # evaluator both depend on had been live-verified exactly once (Move 13 P2) but had ZERO
    # automated regression coverage; REG-110's own closure named that residual explicitly. Stdlib
    # unittest, no live boot needed -- the HTTP calls are mocked.
    Write-Host "[18/35] Checking NPDevCli's own test suite (acceptance runner + dsl-2 migration)..."
    python -m unittest discover -s "NPDevCli/tests" -p "test_*.py" -v 2>&1 | Out-Host
    if ($LASTEXITCODE -ne 0) {
        $failures += "NPDevCli/tests failed -- see output above (python -m unittest discover -s NPDevCli/tests)"
    }

    # [19/35] Move 14 Phase E item E1 (U2): "a rule applied in one place, not mirrored to its twin"
    # has hit three confirmed instances (REG-89, REG-104, REG-112) -- the same threshold that earned
    # X0 a permanent registry+gate. scripts/quality/twin-pair-registry.json is that registry for this
    # family; this checker fails when a registered twin-pair diverges.
    Write-Host "[19/35] Checking registered twin-pair rules (REG-89/104/112's family) haven't diverged..."
    & $py "scripts/quality/check-twin-pair-consistency.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a registered twin-pair rule has diverged: see scripts/quality/check-twin-pair-consistency.py output above, or scripts/quality/twin-pair-registry.json"
    }

    # R4 Part C: check-adr-decision-implementation.py moved to the weekly paperwork gate -- an ADR
    # decision going unimplemented is a slow-moving governance gap, not something one commit causes.

    # [20/35] storage/PLAN.md S1: the 41 dialect-bound SQL sites this repo used to carry inline were
    # moved into com.npdev.kernel.storage.sql, and the measured count outside that package went to 0.
    # A zero that nothing defends goes back up: the next adapter needing a page of rows writes
    # `LIMIT ? OFFSET ?` inline because that is what every neighbouring line looked like, and it works
    # perfectly until SQL Server -- which binds (offset, limit) in the OPPOSITE order -- returns the
    # wrong page without erroring. Blocking, because that class of defect is found in production.
    Write-Host "[20/35] Checking no dialect-bound SQL sits outside the dialect package..."
    & $py "scripts/quality/check-dialect-sites.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "dialect-bound SQL (LIMIT/OFFSET, ON CONFLICT, jsonb, information_schema, SERIAL, ...) was added outside com.npdev.kernel.storage.sql: see scripts/quality/check-dialect-sites.py output above, which names the file, the line and the SqlDialect method to use instead"
    }

    # [21/35] storage/FULL_SUPPORT_PLAN.md W1.1: the storage conformance suite must run on DIGESTS,
    # not moving tags, and the two places the digest is written must agree. Pinning had to come
    # before promoting that workflow to a push trigger -- a push-blocking gate on a moving tag cannot
    # tell "we broke it" from "the image changed", and a gate people cannot trust is a gate they
    # re-run instead of read. RED-verified two ways (2026-08-08): reverting the workflow's PINNED_MYSQL
    # to `mysql:8.4` and, separately, corrupting one digest in the Java map, each failed this check.
    Write-Host "[21/35] Checking conformance container images are pinned to digests..."
    & $py "scripts/quality/check-container-images-pinned.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a storage conformance container image is a moving tag, or the Java map and the workflow env block disagree: see scripts/quality/check-container-images-pinned.py output above (re-resolve with --resolve)"
    }

    # [22/35] storage/FULL_SUPPORT_PLAN.md W3, and the mechanical half of STOR-2. A storage message
    # that ASSERTS "nothing persisted" / "was rolled back" is a claim about the database's state, and
    # it is FALSE on every engine that commits implicitly on DDL (H2 today, MySQL now). A false
    # all-clear is what turns a recoverable half-migration into one nobody goes looking for. STOR-2
    # corrected three call sites by hand; this is what stops instance four -- the sentence has one
    # home (PartialApplicationTruth), which asks DDL_IN_TRANSACTION instead of assuming. RED-verified
    # (2026-08-08): re-inlining STOR-2's original literal into ConversionHookRunner failed this check.
    Write-Host "[22/35] Checking no storage message claims a rollback without asking the dialect..."
    & $py "scripts/quality/check-rollback-claims.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a storage/schema message asserts a rollback or 'nothing persisted' without consulting DDL_IN_TRANSACTION: see scripts/quality/check-rollback-claims.py output above -- call PartialApplicationTruth instead (STOR-2's defect, mechanised)"
    }

    # [23/35] storage/FULL_SUPPORT_PLAN.md W6.1. Every config.json carries a $schema pointer to
    # config.schema.json and NOTHING had ever read it -- Build-NpdevApp.ps1 only WRITES the property.
    # The first time anything validated: 27 corpus files, 93 errors, including the T1-frozen shipped
    # npdev-canary failing its own contract seven times. Most of that was the SCHEMA being wrong
    # (`database` required of fifteen apps that have never had one; Postgres connection fields
    # demanded of engines with no port; `console` forbidden though four official apps declare it and
    # a gate already branches on it; `packs` forbidden though GeneratorMain reads it every run), so
    # the contract was corrected first and this enforcement added second -- widening a schema nobody
    # checks is widening a comment. RED-verified two ways (2026-08-08): an unknown provider, and a
    # server provider with no host.
    Write-Host "[23/35] Validating every corpus config.json against config.schema.json..."
    & $py "scripts/quality/check-config-schema.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a config.json violates the config.schema.json its own `$schema property points at: see scripts/quality/check-config-schema.py output above -- either the file is wrong or the schema is, but the pointer must not be a lie"
    }

    # [24/35] storage/OPEN_ITEMS_PLAN.md W7 (ledger STOR-5): the guarded-DDL idioms NPDev emits must
    # be ones the target engine can actually run. check-dialect-sites.py [26/35] guards the SOURCE;
    # this guards the OUTPUT, and both are needed -- an emitter can assemble a statement in pieces no
    # source pattern would catch.
    #
    # SCOPED TO THE SAMPLE OUTPUT DIRECTORIES, not the whole build root, and that scoping is the
    # difference between a gate and a permanent red: a dev machine's build root holds months of
    # generated apps, including ones emitted BEFORE this fix, and those are stale artifacts rather
    # than findings. --allow-empty because a checkout that has generated nothing yet legitimately has
    # nothing to scan; engine-support.yml is where the check runs against freshly generated output
    # for MySQL and SQL Server, which is the case that matters.
    Write-Host "[24/35] Checking emitted SQL is portable to the engine each script was generated for..."
    & $py "scripts/quality/check-emitted-sql-portability.py" --allow-empty --search-root "NPDevSamples"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an emitted schema script contains a construct the target engine cannot run: see scripts/quality/check-emitted-sql-portability.py output above -- route it through SqlDialect's guarded* methods (STOR-5)"
    }

    # REG-142. A generated app mounts the runtime-host template's resources BEFORE its own generated
    # ones under DuplicatesStrategy.EXCLUDE, so a template file at a generated path wins and the
    # app's real one is dropped -- silently, with no Gradle warning and no boot error. Two resources
    # were doing it, one since 2026-04-23: every generated app served another app's model identity
    # from /api/admin/model/export, and every app's UI permission policy was unreadable.
    # E15 (storage/parity/ENGINE_PARITY_PLAN.md P4). A user's experience must not depend on which
    # engine they chose. Two emitters branched on Postgres and threw for MySQL and SQL Server -- and
    # BOTH of them emit the scripts a user runs, so every layer above said yes (the config schema,
    # `npdev init --engine`, the Manager's picker, 14 conformance vectors) and the toolbox said
    # "Unsupported engine 'MySQL'". This checker fails the moment an engine is special-cased without
    # its siblings, and it self-tests on temp files so it cannot pass merely because the repo is
    # clean today.
    Write-Host "[25/35] Checking every engine gets the same environment toolbox..."
    & $py "scripts/quality/check-engine-parity.py" --self-test
    if ($LASTEXITCODE -ne 0) {
        $failures += "check-engine-parity.py's own self-test failed: it can no longer tell a gap from completeness, so its verdict on the repo means nothing"
    }
    & $py "scripts/quality/check-engine-parity.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an engine is special-cased without its siblings: see scripts/quality/check-engine-parity.py output above -- handle every server engine, or refuse the unsupported one AT THE POINT OF CHOICE (E15)"
    }

    Write-Host "[26/35] Checking no template resource shadows a generated one..."
    & $py "scripts/quality/check-template-resource-shadowing.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a runtime-host template resource sits at a path the generator also writes, so the generated file is silently dropped from every app: see scripts/quality/check-template-resource-shadowing.py output above (REG-142)"
    }

    # [27/35] The mirror of [25/35]: that one fails when SQL is written OUTSIDE the dialect package,
    # this one when an answer INSIDE it is never requested -- the shape STOR-4/5/6 each had. Full
    # rationale in the checker's own docstring; it found nine more on its first run (STOR-13).
    Write-Host "[27/35] Checking every SqlDialect answer has a caller that asks it..."
    & $py "scripts/quality/check-dialect-methods-are-asked.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a SqlDialect method has no production caller, or its allowlist entry is stale: see scripts/quality/check-dialect-methods-are-asked.py"
    }

    # R4 Part C: check-readme-contract.py moved to the weekly paperwork gate -- its own docstring
    # frames it as testing whether DOCS are followable, not platform runtime behavior.

    # [28/35] QUAL-2 / round3 Part 2.1: a Files.list|walk|find|lines|newDirectoryStream whose Stream
    # is never closed. All five hold an OS handle; on Windows a leaked directory handle leaves the
    # directory DELETE-PENDING so its PARENT cannot be removed -- and the error names the parent, not
    # the leak. That misdirection is exactly how S1 presented: the generator gate sat permanently red
    # and was attributed to "a Windows file-lock in the harness" until the leak was found on line 128
    # of a test. RED-proven against ec20ae5^ (the commit before that fix): 11 findings including that
    # very line.
    Write-Host "[28/35] Checking every Files stream is closed (try-with-resources)..."
    & $py "scripts/quality/check-closeable-streams.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "an unclosed Files.list/walk/find/lines stream was added: see scripts/quality/check-closeable-streams.py output above, which names the file and line"
    }

    # [29/35] FOUR_AND_EXTERNAL.md A.1: the first-run harness's own command extractor, against a
    # corpus of documented command shapes. The harness tests the docs; nothing tested the harness --
    # and its extraction has now been wrong three DIFFERENT ways (a bare `npdev` treated as an
    # available command; an example OUTPUT block executed as shell; a trailing ` # comment` taken as
    # part of an argument, which made `cd /work/src     # back to the clone` fail as "directory does
    # not exist"). Every one of those cost a ~30-minute container run AND reported the defect
    # against the product. Three wrongs in three different ways means the next patch would not have
    # been the last, so the extraction moved into a module with tests that run in milliseconds here.
    # RED-proven both ways before landing: with comment-stripping removed, 4 cases fail; with the
    # NAIVE fix (strip at any `#`), 5 different cases fail -- `grep '#foo'`, a URL fragment and an
    # escaped `#` all corrupted. It also asserts STATICALLY that README still has the heading the
    # harness keys off, the rename that once produced thirteen false product failures.
    Write-Host "[29/35] Checking the first-run harness's command extractor against its corpus..."
    & $py "scripts/quality/check-firstrun-extractor.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "the first-run harness would extract a documented command wrongly, or README no longer has the heading it keys off: see scripts/quality/check-firstrun-extractor.py output above"
    }

    # [30/35] Any file declaring a shebang must have LF line endings. `core.autocrlf=true` is Git
    # for Windows' default, and a CRLF shebang is not untidy -- it is unrunnable: the kernel reads
    # `#!/usr/bin/env bash\r` and looks for an interpreter literally named `bash\r`. The first-run
    # harness, whose entire job is to prove NPDev's instructions work on a machine that starts with
    # nothing, could not start at all from a Windows checkout for exactly this reason (`COPY`
    # baked the CRLF shebang into the image). It fails ONLY on Windows, so CI -- LF on a Linux
    # runner -- stayed green throughout. `.gitattributes` pins the file types, and this enforces the
    # general rule, because a declaration drifts the moment a new extension or generator appears.
    Write-Host "[30/35] Checking every shebang'd file has LF line endings..."
    & $py "scripts/quality/check-executable-line-endings.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a tracked file declares a shebang and has CRLF line endings, so it cannot run where it is executed: see scripts/quality/check-executable-line-endings.py output above (fix `.gitattributes`, then `git add --renormalize <file>`)"
    }

    # R4 Part C: check-ledger-status-reverse-freshness.py moved to the weekly paperwork gate -- a
    # stale OPEN ledger item is a slow-moving bookkeeping gap, not something one commit causes.

    # R4 Part C: check-pinned-download-links.py moved to the weekly paperwork gate -- a pinned link
    # only goes stale on the NEXT release, never on the commit that happens to be under review.

    # [31/35] PORT-1's CLASS, which fixing its instance did not close. The committed React bundle
    # under npdev-templates/ carried `$NPDevRoot = 'D:\WorkSpace\NPDev_General'` -- an author path
    # and a folder-NAME assumption REG-144 had already removed from eleven places. The SOURCE was
    # correct; pipelineHandoff.ts emitted $env:NPDEV_ROOT and said so in a comment. Only the built
    # artefact was stale, and it shipped a deleted string to every generated app. Nothing could see
    # it: CLAUDE.md tells readers the bundle is "generated, ignore entirely", which is right for
    # reviewing 141 KB of minified output and exactly why a stale copy is invisible. Git timestamps,
    # not hashes -- hashing means building in the gate or enumerating every build input and silently
    # under-reporting when one is forgotten. "Was it rebuilt after the source changed?" is the real
    # question and git already knows.
    Write-Host "[31/35] Checking committed generated artefacts are not older than their source..."
    & $py "scripts/quality/check-generated-bundle-freshness.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a committed generated artefact is older than the source it is generated from, so what ships no longer matches the code: see scripts/quality/check-generated-bundle-freshness.py output above"
    }

    # [32/35] MONITOR_PLAN A3. The routine vocabulary is the ENGINE's, pinned into
    # schemas/ai/scrapforai-routine.schema.json; the corpus is a conformance test against that pin,
    # never the source of it (a schema induced from 42 routines rejects the 16 actions they happen
    # not to use, and misses five constraints only a runtime rejection reveals). It ALSO fails on a
    # routine outside a browser-routines/ directory: the corpus was split until 2026-08-10 -- 19
    # loose files plus 1 in a subdirectory -- and a glob written against either half passes while
    # silently ignoring the other, which is a conformance test reporting green about work it never
    # looked at. Self-tests first, for the same reason check-engine-parity.py does [30/35]: a
    # checker that can no longer tell a broken routine from a good one has a meaningless verdict.
    Write-Host "[32/35] Checking every checked-in browser routine conforms to the pinned engine schema..."
    & $py "scripts/quality/check-routine-corpus-conformance.py" --self-test
    if ($LASTEXITCODE -ne 0) {
        $failures += "check-routine-corpus-conformance.py's own self-test failed: it can no longer tell a broken routine from a good one, so its verdict on the corpus means nothing"
    }
    & $py "scripts/quality/check-routine-corpus-conformance.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a checked-in routine does not satisfy the pinned engine routine schema, or a routine lives outside browser-routines/: see scripts/quality/check-routine-corpus-conformance.py output above. A rejection is a defect in the ROUTINE -- never hand-edit the pinned schema to make one pass (re-pin with scripts/quality/pin-routine-schema.py against a running engine)"
    }

    # [33/35] docs/INVOCATION_TOPOLOGY_PLAN.md T2: every script under scripts/ must declare BOTH a
    # classification (what it is) and an invocation (what invokes it), and both must match reality --
    # generalizes check-test-task-coverage.py's "declared but never invoked" check from Gradle Test
    # tasks to every script. This checker was itself an orphan until that step: nothing had ever
    # invoked it (grep-confirmed at the time), the pattern in miniature. Move 11 W2 added the rule
    # that catches instance five and six of the same class -- a scripts/quality/check-*.py named by
    # no run-*.ps1 -- so it must run LAST, after every checker this gate hosts is in place.
    Write-Host "[33/35] Checking every script declares a classification + invocation matching reality..."
    pwsh -NoProfile -File "scripts/quality/run-script-inventory-check.ps1"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a script's classification/invocation declaration is missing or does not match reality, or a scripts/quality/check-*.py is invoked by no gate at all: see scripts/quality/run-script-inventory-check.ps1 output above, or scripts/reports/out/script-inventory-report.json"
    }

    # R4 Part C (MASTER-ROADMAP.md Step 9 / ledger QUAL-7): check-doc-inventory.py (the
    # process-document ban) and check-no-markdown-reads.py (the zero-rule gate) both moved to the
    # weekly paperwork gate (scripts/quality/run-weekly-paperwork-checks.ps1) -- both police
    # documentation/process hygiene, not platform runtime behavior, per
    # scripts/policy/gate-classification-policy.json's product-vs-paperwork split. A banned document
    # or a markdown-reading script sits in the tree until the next weekly run notices it, which is an
    # acceptable latency for a hygiene rule (unlike, say, [4/35]/[6/35]/[7/35]'s bug classes, which
    # stay in the per-commit gate).

    # [42/35] PK-4 Stage A/B (the diff engine + the publish gate): does a branch's own change to a
    # built-in pack (NPDevContract/packs/identity|workspace/pack.json) carry a version bump the real
    # Java classifier (com.npdev.dsl.v1.pack.PackDiffEngine/PackPublishGate, via the packPublish
    # Gradle task) says is big enough? Compares against `git merge-base HEAD origin/main`, not raw
    # history -- both packs accumulated real ADDITIVE/BREAKING content changes from BEFORE pack
    # versioning was an enforced concept at all (both still read "version": "1.0.0"), so a
    # history-relative comparison would fail on every single run for reasons no branch caused; see
    # the checker's own module docstring for the RED-proven reasoning. A branch that never touches
    # either pack.json (the common case) always passes trivially.
    Write-Host "[34/35] Checking every built-in pack's own branch-relative change has a correctly-sized version bump..."
    & $py "scripts/quality/check-pack-diff-gate.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "a built-in pack changed on this branch without a correctly-sized version bump: see scripts/quality/check-pack-diff-gate.py output above -- bump the version (major for a BREAKING change, minor for ADDITIVE, patch for PATCH-only), then `npdev pack publish <old> <new> --write` to also stamp the empty migrations chain entry"
    }

    # [35/35] R3 (MASTER-ROADMAP.md Step 9 / ledger QUAL-6): the coverage ratchet. Static by
    # construction -- it never builds anything itself, only looks for a JaCoCo XML report an
    # EARLIER step in the same run-all-gates.ps1 invocation may have already produced (
    # run-generator-gate.ps1 for dsl/generator, run-runtimehost-gate.ps1 for RuntimeHost via
    # -PenableCoverage=true; kernel has no such gate yet, a pre-existing gap this card does not
    # close). In the standalone ai-knowledge-gate.yml CI job, which never builds anything, every
    # module legitimately reads not-measured every run -- that is intended, not a bug; see
    # coverage-baseline.json's own _comment. Never fails on a missing report, only on a real
    # regression against a module's recorded floor.
    Write-Host "[35/35] Checking JaCoCo coverage hasn't dropped below its recorded floor..."
    & $py "scripts/quality/check-coverage-ratchet.py"
    if ($LASTEXITCODE -ne 0) {
        $failures += "coverage dropped below its recorded floor for at least one module: see scripts/quality/check-coverage-ratchet.py output above, and scripts/policy/coverage-baseline.json"
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
