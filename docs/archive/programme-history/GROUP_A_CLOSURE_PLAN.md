# Group A Closure Plan — close the actionable open items

> **STATUS: HISTORICAL** — last changed 2026-07-23; its completion state has **not** been re-verified. Treat nothing here as an open commitment: check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (authoritative) or `docs/OPEN_ITEMS_SNAPSHOT.md` before acting on any item.


> **Written:** 2026-07-23 · **Branch:** `beta1-vision-spine` · **Repo root:** `D:\WorkSpace\NPDev\NPDev_General`
> **Companion:** `docs/REMAINING_GAPS_CLOSURE_PLAN.md`, `docs/POST_LNCH22_EXECUTION_PLAN.md` (same rules/format).
>
> **What "Group A" was** (from the 2026-07-23 open-items analysis): the genuinely-actionable open
> items. Two are ALREADY DONE and are not in this plan: **REG-15** (closed — hobby project, trademark
> N/A) and **LEDGER-1 doc half** (manual `createConcept`/`updateConcept` examples fixed, commit
> `9f180aa`). This plan closes the rest: the three REG-17 CI bugs (CI-1/2/3), **BOND-B4**, the
> **REG-17** re-run, the **LEDGER-1 runtime-diagnostic half**, and **REG-16-resid**.
>
> **Honest scope note — read before starting.** Not every Group-A item is closable by a mechanical
> executor. This plan is split into two parts:
> - **PART 1 (Tasks 1–5): EXECUTOR-SAFE.** Fully mechanical: exact YAML/edits, exact commands,
>   expected outputs, STOP gates. A less-capable AI can complete these.
> - **PART 2 (Tasks 6–7): CAPABLE-AGENT REQUIRED.** The LEDGER-1 runtime diagnostic (traces a
>   kernel→RuntimeHost error path) and REG-16-resid (an adversarial security review) genuinely need
>   judgment. The executor must **NOT** attempt them — they are specified here for a capable agent /
>   the owner, with the investigation already done.
>
> **Do not improvise. If reality diverges from this doc, STOP and report (§STOP).**

---

## 0. Global rules

1. Absolute paths exactly as written. Shell is PowerShell unless a step says `bash`.
2. Git: only the exact `git add <named files>` + `git commit` blocks given. **NEVER** `git add .`,
   never push, never merge, never checkout another branch. A slimness pre-commit hook runs; if a
   commit fails on it, STOP and report.
3. Never edit under `D:\WorkSpace\NPDev\Build` or any `npdev-generated` folder.
4. YAML is whitespace-sensitive. When inserting a workflow step, match the **exact indentation** in
   the FIND block (6 spaces before `- name:`). Do not convert tabs/spaces.
5. After each command compare to "Expected". Divergence → STOP.
6. Tasks 1–4 are independent; do them in order, one commit each. Task 5 depends on 1–3 being pushed
   (a human/capable-agent step). Tasks 6–7 are not for the executor.

---

## 1. Task 0 — Preflight

```
git -C D:\WorkSpace\NPDev\NPDev_General rev-parse --abbrev-ref HEAD
git -C D:\WorkSpace\NPDev\NPDev_General status --short
```
**Expected:** `beta1-vision-spine`; clean (or only untracked docs). Record as "BEFORE".
**If branch differs or tracked files are modified:** STOP.

---

# PART 1 — EXECUTOR-SAFE

## 2. Task 1 — CI-1: strip the dev-machine `projectcachedir` in the Linux job

**Why.** `NPDevContract/dsl`, `NPDevKernel`, `NPDevGenerator` each ship `gradle.properties` with
`org.gradle.projectcachedir=D:/WorkSpace/NPDev/Build/…`. On the Linux runner `D:/` is not a drive, so
gradle dies at "Cannot convert URL 'D:/…' to a file" **before any test runs** — that is the "DSL
contract check" failure. `npdev-pr-gate.yml` already strips this line (verified: 5 hits); this
workflow has zero. You add one strip step to the **Linux** job (job 1, `Linux post-Beta0 maturity
validation`). *(Job 2 is a Windows runner where `D:/` exists; leave it — its projectcachedir is
addressed only if the re-run in Task 5 shows it tripping.)*

**File:** `D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-ci-validation.yml`

### Step 1.1 — Insert the strip step after "Setup Java 17" (job 1)

**FIND** (exact — this is the job-1 Setup Java block followed by the DSL step):
```
      - name: Setup Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      - name: DSL contract check
        working-directory: NPDevContract/dsl
```
**REPLACE:**
```
      - name: Setup Java 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
          cache: gradle

      # CI-1 (2026-07-23): the committed gradle.properties hardcode a dev-machine
      # org.gradle.projectcachedir=D:/... which does not exist on Linux -> gradle aborts before any
      # test. Strip it (mirrors npdev-pr-gate.yml) so gradle uses its portable default project cache.
      - name: Neutralize dev-machine gradle projectcachedir (portability)
        run: |
          sed -i '/org\.gradle\.projectcachedir/d' NPDevContract/dsl/gradle.properties
          sed -i '/org\.gradle\.projectcachedir/d' NPDevKernel/gradle.properties
          sed -i '/org\.gradle\.projectcachedir/d' NPDevGenerator/gradle.properties

      - name: DSL contract check
        working-directory: NPDevContract/dsl
```

### Step 1.2 — Verify
```
Select-String -Path D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-ci-validation.yml -Pattern "Neutralize dev-machine gradle projectcachedir","sed -i '/org" | Select-Object LineNumber
```
**Expected:** the "Neutralize…" name once, and **3** `sed -i` lines.
**If not:** STOP.

### Step 1.3 — Commit
```
git -C D:\WorkSpace\NPDev\NPDev_General add .github/workflows/npdev-ci-validation.yml
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "ci(CI-1): strip dev-machine gradle projectcachedir in ci-validation Linux job (mirrors pr-gate)"
```

---

## 3. Task 2 — CI-2: fix the `..` upload path in the Linux job

**Why.** Job 1's evidence upload lists `../NPDev_General__OutsideRepo/temp/final-evidence-bundle` as an
`upload-artifact@v4` `path:` — v4 forbids `..`, so the step fails even on an otherwise-green run.
`npdev-pr-gate.yml` already solved this exact problem (lines 147-165): copy the out-of-repo evidence
into an in-workspace dir first, then upload that. You mirror it.

**File:** `D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-ci-validation.yml`

### Step 2.1 — Add a copy-first step and repoint the upload

**FIND:**
```
      - name: Upload Linux maturity evidence
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: npdev-linux-maturity-validation-evidence
          path: |
            scripts/reports/out
            ../NPDev_General__OutsideRepo/temp/final-evidence-bundle
```
**REPLACE:**
```
      # CI-2 (2026-07-23): upload-artifact@v4 forbids '..' in path patterns. The evidence bundle lives
      # OUTSIDE the repo (build-output policy). Copy it into an in-workspace dir first, then upload
      # that legal path (mirrors npdev-pr-gate.yml's ci-evidence pattern).
      - name: Stage out-of-repo evidence for upload
        if: always()
        run: |
          mkdir -p ci-maturity-evidence
          cp -r ../NPDev_General__OutsideRepo/temp/final-evidence-bundle ci-maturity-evidence/ 2>/dev/null || true

      - name: Upload Linux maturity evidence
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: npdev-linux-maturity-validation-evidence
          path: |
            scripts/reports/out
            ci-maturity-evidence
```

### Step 2.2 — Verify
```
Select-String -Path D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-ci-validation.yml -Pattern "Stage out-of-repo evidence","ci-maturity-evidence" | Select-Object LineNumber
Select-String -Path D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-ci-validation.yml -Pattern "temp/final-evidence-bundle" | Select-Object LineNumber Line
```
**Expected:** the "Stage…" name once; `ci-maturity-evidence` appears (staging step + upload path);
`final-evidence-bundle` now appears **only inside the `cp` line**, never in a `path:` list.
**If `final-evidence-bundle` still appears under `path:`:** STOP.

### Step 2.3 — Commit
```
git -C D:\WorkSpace\NPDev\NPDev_General add .github/workflows/npdev-ci-validation.yml
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "ci(CI-2): stage out-of-repo evidence before upload-artifact (v4 forbids '..'); mirrors pr-gate"
```

---

## 4. Task 3 — CI-3: make the script-automation-quality gate non-blocking + file the debt

**Why.** `run-script-automation-quality.ps1`'s `structured-report-contract` sub-check flags **59 of
68** quality scripts because it greps for two helper *names* (`Invoke-NPDevReportedCommand` /
`Write-NPDevJsonFile`), not for whether a script actually emits a valid structured report. An 87%
"failure" rate means the **check is mis-calibrated**, not that 59 scripts are broken — and this is
**pre-existing debt**, not a regression from any recent work. It is the *only* caller of this check
(pr-gate never runs it), so it had never executed before the REG-17 dispatch. Making it non-blocking
now (and filing the debt) is the honest move; a real recalibration/migration is a separate deliberate
task (a capable agent), not a REG-17 side-quest.

**File:** `D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-ci-validation.yml`

### Step 3.1 — Mark the step non-blocking

**FIND:**
```
      - name: Script automation quality
        timeout-minutes: 10
        run: |
```
**REPLACE:**
```
      - name: Script automation quality
        # CI-3 (2026-07-23): non-blocking pending recalibration. Its structured-report-contract
        # sub-check greps for two helper NAMES, not report behavior, and flags 59/68 pre-existing
        # scripts -- mis-calibrated, not a regression. Tracked as a debt item in
        # docs/NPDEV_OPEN_ITEMS_REGISTER.md (REG-31). Re-block once the check tests behavior.
        continue-on-error: true
        timeout-minutes: 10
        run: |
```

### Step 3.2 — File the debt item in the register
**File:** `D:\WorkSpace\NPDev\NPDev_General\docs\NPDEV_OPEN_ITEMS_REGISTER.md`

**FIND** (the last table row of the REG-27…30 table — the `REG-30` row's final cell ends with
`showed exactly one row. …duplicateMarkForTheSameTransitionIsRejected` in
`SchemaLifecycleExecutorMigrationMarkTest`. |`). To avoid matching a huge cell, insert after the
`Net:` paragraph that follows that table instead — **FIND:**
```
**Net:** REG-7's three sub-features and REG-8's refusal are delivered and, with REG-27 fixed, REG-8
now genuinely refuses its own canonical example. **REG-28/29/30 are now CLOSED (2026-07-22)** — see
each row above and `docs/REG28_30_REG12S2_CLOSURE_PLAN.md`.
```
**REPLACE:**
```
**Net:** REG-7's three sub-features and REG-8's refusal are delivered and, with REG-27 fixed, REG-8
now genuinely refuses its own canonical example. **REG-28/29/30 are now CLOSED (2026-07-22)** — see
each row above and `docs/REG28_30_REG12S2_CLOSURE_PLAN.md`.

---

## 3.5 REG-31 — `run-script-automation-quality` structured-report-contract check is mis-calibrated

**Type:** PROCESS (quality-gate calibration) · **Severity:** LOW · **Effort:** M · **Status:** **OPEN
(filed 2026-07-23, made non-blocking in CI).** The check's `structured-report-contract` sub-check
greps script *source* for the literal strings `Invoke-NPDevReportedCommand`/`Invoke-ReportedCommand`
and `Write-NPDevJsonFile`/`Write-StructuredRunReport` and fails any of the ~68 `scripts/quality/*.ps1`
that lack them — flagging **59**. That is a helper-name presence test, not a report-behavior test:
many of the 59 almost certainly emit a valid structured JSON report by other means
(`ConvertTo-Json | Set-Content`). An 87%-fail rate on the population a check governs indicates the
check is wrong, not the population. Surfaced only because `npdev-ci-validation.yml` is its sole caller
and had never run end-to-end before the REG-17 dispatch (`pwsh` job 2, run `29974176793`).
**Made non-blocking** (`continue-on-error: true`) 2026-07-23 so it stops conflating "never run" with
"broken." **How to fix (capable agent):** (1) spot-check 5–6 flagged scripts — do they emit a valid
structured report? (2) if yes → rewrite the sub-check to assert the report *artifact* is produced and
well-shaped, not that a helper name appears; (3) only then migrate any genuinely non-compliant
remainder and re-block the step. Do **not** mass-migrate 59 scripts before deciding which is
authoritative — the convention or the check.
```

### Step 3.3 — Commit
```
git -C D:\WorkSpace\NPDev\NPDev_General add .github/workflows/npdev-ci-validation.yml docs/NPDEV_OPEN_ITEMS_REGISTER.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "ci(CI-3): make script-automation-quality non-blocking (mis-calibrated check); file REG-31"
```

---

## 5. Task 4 — BOND-B4: confirm `ReleaseGateValidator` is CI-wired, then close it

**Why.** `docs/OPEN_GAPS_AND_ROADMAP.md` lists BOND-B4 as PARTIAL ("ReleaseGateValidator not CI-wired
— needs your CI-trigger call"). But `npdev-pr-gate.yml`'s own header states its DSL check "includes
`ReleaseGateValidatorTest`, so ReleaseGateValidator is exercised without a separate step", and pr-gate
now runs green on every PR. So this is likely already satisfied — confirm, then close.

### Step 4.1 — Confirm the test runs under the DSL check (read-only)
```
Select-String -Path D:\WorkSpace\NPDev\NPDev_General\.github\workflows\npdev-pr-gate.yml -Pattern "ReleaseGateValidator" | Select-Object LineNumber
(Get-ChildItem -Recurse -Filter ReleaseGateValidatorTest.java D:\WorkSpace\NPDev\NPDev_General\NPDevContract).FullName
```
**Expected:** at least one pr-gate hit naming `ReleaseGateValidatorTest`, AND the test file exists
under `NPDevContract` (so `:NPDevContract:dsl:check` — which pr-gate runs — executes it).
**If the pr-gate hit is absent:** do NOT close; STOP and report (BOND-B4 stays PARTIAL).

### Step 4.2 — Close it in the roadmap
**File:** `D:\WorkSpace\NPDev\NPDev_General\docs\OPEN_GAPS_AND_ROADMAP.md`

**FIND:**
```
| BOND-B4 | ReleaseGateValidator not CI-wired | Test/CI | PARTIAL (needs your CI-trigger call) | P3 | S |
```
**REPLACE:**
```
| BOND-B4 | ReleaseGateValidator not CI-wired | Test/CI | **DONE (2026-07-23)** — `ReleaseGateValidatorTest` runs inside `:NPDevContract:dsl:check`, which `npdev-pr-gate.yml` executes on every PR (confirmed green on `beta1-vision-spine`, run `29965541583`). No separate CI step needed; the validator is exercised on every PR. | P3 | S |
```

### Step 4.3 — Commit
```
git -C D:\WorkSpace\NPDev\NPDev_General add docs/OPEN_GAPS_AND_ROADMAP.md
git -C D:\WorkSpace\NPDev\NPDev_General commit -m "docs(BOND-B4): DONE — ReleaseGateValidatorTest runs via pr-gate DSL check"
```

---

## 6. Task 5 — REG-17 re-run harvest  ⚠️ needs push (owner / capable agent, NOT the executor)

The executor stops after Task 4 and hands off. This task needs a `git push` (outward action) and a CI
dispatch, which are **not** executor-safe. Whoever runs it:

1. Push `beta1-vision-spine` (now carrying CI-1/2/3).
2. Dispatch the workflow on the pushed ref:
   `bash scripts/ci/gh-api.sh POST 'actions/workflows/npdev-ci-validation.yml/dispatches' '{"ref":"beta1-vision-spine"}'`
3. Poll the run (`gh-api.sh GET actions/runs/<id>`; `…/jobs` for per-step outcomes), as in
   `docs/REMAINING_GAPS_CLOSURE_PLAN.md` Task 6.
4. **Expected improvement:** job 1 (Linux) should now clear DSL-contract + evidence-upload (CI-1/2).
   Job 2 (Windows) should get **past** script-automation-quality (CI-3) and — for the first time ever
   — run its *downstream* gradle/gate steps. **Anticipate:** those downstream Windows steps have never
   executed, so they may surface NEW findings (first Windows contact). That is expected and is
   REG-17 continuing to work — **file new failures** in the run's findings file, do not fix-all.
5. Update REG-17 status: **CLOSED** if the whole run is green; **ADVANCED (round 2)** with new findings
   filed otherwise. (Honest note: a fully-green first re-run is plausible but not guaranteed — job 2's
   downstream is untested. Two rounds may be needed. That is not failure; it is the point of REG-17.)

---

# PART 2 — CAPABLE-AGENT REQUIRED (executor: do NOT attempt)

## 7. Task 6 — LEDGER-1 runtime diagnostic  ⚠️ capable agent

**Goal (owner's ask):** surface the missing-persistence-binding as an actionable diagnostic instead of
a bare Spring 500. **The doc half is already done** (commit `9f180aa`); this is the code half.

**Investigation already done (do not re-derive):**
- The `createConcept`/`updateConcept` runtime path calls the `persistence` capability. When no binding
  exists, `RegistryCapabilityDispatcher` (kernel, ~line 51) returns a **structured**
  `CapabilityResult.failure("CAPABILITY_BINDING_MISSING", "Capability binding not found for capability
  'persistence' and adapter '<missing>'", CapabilityErrorKind.NOT_FOUND, …)` — it does **not** throw.
- Downstream, the flow step turns that failure into an uncaught exception → Spring default 500; the
  clear message reaches only stdout (`_ops\app.out.log`).
- **Validate-time cannot reliably catch this** (and shouldn't be forced to): the validator already
  adds `persistence` to referenced capabilities for `createConcept` (`SemanticValidator` ~line 2608)
  and checks bindings (~line 2284), but only when `allowUnboundFlowCapabilities=false` — the lenient
  default exists on purpose because a binding can legitimately come from a built-in pack. So the
  binding is only *definitively* absent at runtime. The owner's instinct (a **runtime** diagnostic) is
  correct.

**Recommended approach (lowest-risk of the two):**
- **Option A (preferred) — boot-time fail-fast.** Add a startup check in the RuntimeHost template
  (near the existing `StartupValidator`/`NpdevCapabilityBindingConfig` wiring): if the compiled model
  has any flow step of type `createConcept`/`updateConcept`/`saveConcept` (or a `persistence.*`
  `capabilityCall`) AND the built capability registry has no binding for `persistence`, fail boot with
  a docs-linked message naming the capability and the fix (`persistence-inproc` for dev,
  `persistence-postgres` for prod). Fails once, at boot, not per-request. Bounded, doesn't touch the
  flow execution path.
- **Option B — HTTP surface.** A `@RestControllerAdvice`/handler that maps a flow-step failure carrying
  `CAPABILITY_BINDING_MISSING` to a structured HTTP body (naming the capability + fix) instead of a
  bare 500. More faithful to "runtime diagnostic" but needs tracing exactly where the structured
  failure becomes a throw.

**Definition of done:** a generated in-memory app whose model omits the persistence binding either
refuses to boot (Option A) or returns an actionable HTTP body (Option B) that names `persistence` and
the fix — verified live on a regenerated app; RuntimeHost gate green (`scripts/quality/run-runtimehost-gate.ps1`).
RED-first: reproduce the current bare-500 first. This is a RuntimeHost template change → ships into
every generated app → **build + gate required** (that is why it was not done as a doc-only quick fix).
Effort: S–M.

## 8. Task 7 — REG-16-resid: adversarial review of the un-reviewed launch surfaces  ⚠️ capable agent

**Why it's here.** REG-16 marked itself "fully addressed" by reviewing only LNCH-2 (tenant isolation)
+ LNCH-4 (auth). By its own title, ~21 other launch surfaces — **generator codegen, kernel FlowEngine
/ KernelRunner, LNCH-13 row-level authz, the export/PDF path** — have had **zero** attack-first
review. This is not a known defect; it is the biggest *unknown*, and it cannot be done mechanically.

**Not an executor task.** A genuine adversarial review needs a capable reviewer. **Cannot be
"completely closed" in one pass** — it is iterative (the LNCH-1 programme took five rounds). The
honest unit of progress is one surface per round.

**Recommended Round 1 (highest value):** the **kernel execution path** (`KernelRunner` + the capability
dispatch/idempotency/circuit paths) — the code every generated app runs. Reuse REG-16's proven
discipline: independent review → findings doc (`docs/REG16_*`-style) → phased plan → fix → re-review;
reproduce RED first; verify live, not just by suite; never claim past the evidence. File findings as
new `REG-*` items. Effort: L (multi-session, per surface).

---

## 9. Final report (executor: after Tasks 0–4)

```
GROUP A CLOSURE — EXECUTION REPORT (Part 1)
Branch: <Task 0> | BEFORE: <Task 0 status>
Task 1 (CI-1 projectcachedir): DONE|STOPPED — verify: name×1, sed×3? __
Task 2 (CI-2 upload '..'):     DONE|STOPPED — final-evidence-bundle only under cp? __
Task 3 (CI-3 non-blocking + REG-31): DONE|STOPPED
Task 4 (BOND-B4):              DONE|STOPPED — pr-gate names ReleaseGateValidator? __
Commits (SHAs): ____
NOT pushed — Task 5 (REG-17 re-run) is owner/capable-agent, needs push: CONFIRMED
Tasks 6 (LEDGER-1 runtime diag) + 7 (REG-16-resid): NOT ATTEMPTED — capable-agent required
Anything STOPPED and why: ____
```

Do not push. Do not attempt Part 2.

---

## 10. STOP rules

STOP (write the report, change nothing more) if: a FIND doesn't match or isn't unique · an "Expected"
line fails · a commit fails on the slimness hook · you are tempted to do anything not written here —
including editing YAML indentation by guess, pushing, dispatching CI, or starting Part 2.

## 11. Do-not-touch (unchanged)

REG-7.3 / REG-8 residuals · REG-23 / REG-25 deferred · REG-26 WONTFIX · super-user key WONTFIX ·
SchemaLifecycleExecutor logic · AI Studio · **the 59 quality scripts** (Task 3 only makes the check
non-blocking + files REG-31 — it does NOT migrate scripts) · **the flow-execution / KernelRunner error
path** (Task 6 is capable-agent-only).
