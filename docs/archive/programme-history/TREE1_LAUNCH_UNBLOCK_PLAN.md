# TREE 1 — Launch Unblock Plan

> **STATUS: DONE (2026-07-28).** All 16 tasks complete. `beta1-vision-spine` merged to `main`
> (commit `89eb945`, PR #5, both CI gates green), tagged `beta1.2`, and the repo flipped to
> **public**. Written 2026-07-27 against branch `beta1-vision-spine` (`97a2491` + uncommitted
> working tree). Supersedes nothing; complements the ledgers rather than replacing them.
>
> **Map:** [EXECUTION_TREES.md](EXECUTION_TREES.md). This document is the executable detail.
>
> **What this plan is.** Fifteen tasks, ~4 working days, **zero inter-tree dependencies**. Every one
> is either (a) something the repo currently *claims* that is not true, (b) a verification hole found
> on 2026-07-27, or (c) a front door that does not exist. None of it is feature work.
>
> **Exit criterion.** *The repo can be made public without any statement in it being false.*
> That is the whole bar. Not "the platform is finished" — "the platform does not lie."
>
> **Why this is urgent and the rest is not.** Every strategic question in TREE 2 (which frontend
> strategy, which crown jewel leads, is the HTML gap a dealbreaker) is answered by users, and users
> are blocked on ~4 days of work in this document. No amount of additional internal review substitutes.

---

## How to read a task

Every task has: **Goal** · **Why** (the consequence, not the abstraction) · **Files** ·
**Steps** (runnable) · **Acceptance** (how you know it is done) · **Rollback** · **Effort** ·
**Depends on**.

**Effort key:** ⚡ ≤ 15 min · S ≤ 2 hr · M ≤ 1 day · L > 1 day.
**Marks:** 🔴 do first · ★ high value · 🔒 security · ⬥ owner decision required.

**Verification discipline (non-negotiable, borrowed from this project's own standard):**
run the two gates after each phase, not at the end.

```powershell
python scripts/quality/check-register-consistency.py
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/quality/run-ai-knowledge-gate.ps1
```

---

## Current state — what is actually on disk right now

Established by direct inspection on 2026-07-27, not from documentation:

| Fact | Value |
|---|---|
| Working tree | **28 renames + ~25 modified + 5 untracked, ALL UNCOMMITTED** |
| Branch vs `main` | **136 ahead**, 2 behind |
| Latest tag | `beta1.1` — **predates** the REG-48/50/51 security fixes |
| DSL suite | 355 tests, 0 failures (run 2026-07-27) |
| Kernel suite | 159 tests, 0 failures (run 2026-07-27) |
| `:generator:behaviorTest` | 1 test, passes — **runs in no workflow** |
| Editor root `.tsx` | 43 files; **11 reachable, 32 classified `deferred`** |
| `docs/*.md` top level | 52 (was 80; 29 archived 2026-07-27) |
| Both quality gates | green |

---

# PHASE A — Stop the bleeding

**~2 hours. Do this before anything else.** Two of the three items protect work that already exists.

---

## T1.1 🔴 Commit the working tree

**Goal.** Get 2026-07-27's work — two real bug fixes, a new detector, a new test, a decision brief,
and a 29-file doc reorganization — into git.

**Why.** 483 commits of discipline, and the current state is entirely unstaged. This is the single
largest at-risk surface in the project right now: an editor crash, a bad `git checkout`, or a
`gradlew clean` in the wrong directory loses REG-52, REG-53, the drift detector, and the REG-49
behavioral test. All of it was verified working today and none of it is durable.

**Files.** Everything in `git status`.

**Steps.**

```powershell
cd D:\WorkSpace\NPDev\NPDev_General
git status --short          # review; expect ~28 R, ~25 M, ~5 ??
```

Commit in **three** logical commits, not one — they are genuinely separate concerns and the
project's own convention is bounded steps:

```powershell
# 1 — the engineering fixes (REG-52, REG-53, REG-49 residual, REG-51 residual)
git add NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/SqlTypeSupport.java `
        NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/SqlTypeSupportTest.java `
        NPDevKernel/kernel/src/main/java/com/npdev/kernel/ports/TenantIsolationPolicy.java `
        NPDevKernel/kernel/src/test/java/com/npdev/kernel/concepts/DefaultConceptGatewayTest.java `
        NPDevRuntimeHost/src/test/java/com/finalexec/db/Reg53MaxLengthSchemaDiffTest.java `
        NPDevGenerator/generator/build.gradle `
        NPDevGenerator/generator/src/behaviorTest/ `
        scripts/quality/check-register-consistency.py
git commit   # message below

# 2 — the drift detector + its wiring
git add scripts/quality/check-narrative-status-drift.py scripts/quality/run-ai-knowledge-gate.ps1
git commit

# 3 — the doc reorganization + link fixes + the two new plan documents
git add -u docs/ ; git add docs/archive/ docs/DECISION_BRIEFS_2026-07.md `
          docs/REMAINDER_CLOSURE_PLAN.md docs/EXECUTION_TREES.md docs/TREE1_LAUNCH_UNBLOCK_PLAN.md
git commit
```

Suggested message for commit 1:

```
fix(security,schema): REG-52 tenant-id case normalization; REG-53 maxLength reaches DDL

REG-52: TenantIsolationPolicy.STRICT_EQUALS.normalize() trimmed but did not lowercase,
while ExecutionContext.normalizeTenantId() does (per its REG-25 comment). A per-request
tenantId reaching sameTenant() without passing ExecutionContext's constructor could see
the same logical tenant in two cases and deny it. Fail-closed direction, so a
correctness/consistency gap rather than a hole. RED->GREEN.

REG-53: SqlTypeSupport hardcoded VARCHAR(255) at four sites, so a declared maxLength was
enforced on input at DefaultSchemaValidator but never reached the physical column -- and
the schema differ therefore could not see a narrowing or widening, because the desired
type string never varied. 255 remains the default when no maxLength is declared, so
existing generated DDL and fingerprints are unchanged. RED->GREEN on both engines.

REG-49 residual: ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest compiles and runs a real
generated *ServiceBase through the real GeneratedCrudRuntimeSupport, replacing the manual
exception-path trace with an executable assertion. New behaviorTest source set (needs
spring-web/jakarta-servlet-api on the loader that defines GeneratedCrudRuntimeSupport,
which the main test task cannot provide -- see build.gradle note).

REG-51 residual: provenance_audit_gaps() in check-register-consistency.py; caught one
real pre-existing case on M7.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

> ⚠️ **Do not `git add .`** — the project's standing rule, and the working tree currently contains a
> mix of two sessions' output. Stage by path.

**Acceptance.** `git status --short` is empty. `git log --oneline -3` shows the three commits.

**Rollback.** N/A (committing is the safe direction). If a commit is wrong: `git reset --soft HEAD~1`.

**Effort.** S (30 min). **Depends on.** Nothing.

---

## T1.2 🔴 Wire `:generator:behaviorTest` into both workflows

**Goal.** Make the REG-49 residual test actually run in CI.

**Why.** Found 2026-07-27 by direct inspection. The build wiring is correct —

```gradle
tasks.named('check') { dependsOn tasks.named('behaviorTest') }   // generator/build.gradle:111
```

— but **both workflows invoke `test`, not `check`**:

```
.github/workflows/npdev-pr-gate.yml:96        ./gradlew :generator:test
.github/workflows/npdev-ci-validation.yml:100 ./gradlew :generator:test
```

So `ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest` runs on one laptop and nowhere else. The test was
built specifically because REG-49's withdrawal note conceded *"it stops short of an automated JUnit
runtime assertion."* A test that only runs locally is a manual trace with extra steps — it is exactly
the thing it was written to replace.

**Files.** `.github/workflows/npdev-pr-gate.yml` · `.github/workflows/npdev-ci-validation.yml`

**Steps.** In both files:

```diff
-          ./gradlew :generator:test --no-daemon --console=plain
+          ./gradlew :generator:test :generator:behaviorTest --no-daemon --console=plain
```

Prefer the explicit task list over switching to `check` — `check` also pulls in whatever else is
wired to it now or later, which would change PR-gate runtime silently.

Verify locally first:

```powershell
cd D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator
.\gradlew :generator:test :generator:behaviorTest --rerun-tasks --console=plain
```

**Acceptance.** Both workflow files name `:generator:behaviorTest`. Local run green. Test-result XML
exists at `<Build>/gradle/npdev-generator/generator/test-results/behaviorTest/`.

**Rollback.** Revert the two lines.

**Effort.** ⚡ (10 min). **Depends on.** Nothing.

---

## T1.3 Gate: every registered `Test` task must appear in ≥ 1 workflow

**Goal.** Make T1.2's bug class impossible rather than fixing one instance.

**Why.** This is the project's own established standard (REG-51 chose refuse-not-warn precisely so a
false-positive class "cannot recur silently"). A new source set that is invisible to CI will happen
again — `integrationTest`, `behaviorTest`, and any future `contractTest` all have this shape. The
detection is cheap and static.

**Files.** New: `scripts/quality/check-test-task-coverage.py`. Wire into
`scripts/quality/run-ai-knowledge-gate.ps1` (the project's prior art for lightweight gates).

**Steps.**

1. Enumerate every `tasks.register('<name>', Test)` and `sourceSets { <name> { ... } }` across
   `NPDevContract/dsl`, `NPDevKernel`, `NPDevGenerator`, `NPDevRuntimeHost` build files.
2. Enumerate every `gradlew` invocation in `.github/workflows/*.yml`, expanding `check` to its
   `dependsOn` closure where statically determinable.
3. Report any Test task reachable from neither. Exit 1.
4. Seed the allowlist with any task deliberately nightly-only (document the reason inline, per the
   `security-pattern-sweep-allowlist.json` precedent).

**Acceptance.** Running it **before** T1.2 reports `:generator:behaviorTest` (RED). Running it after
T1.2 is clean (GREEN). That RED→GREEN pair is the calibration — do not ship it without demonstrating
both, matching the standard `check-narrative-status-drift.py --calibrate` set today.

**Rollback.** Delete the script; unwire the one gate line.

**Effort.** S (2 hr). **Depends on.** T1.2 (needed for the GREEN half).

---

# PHASE B — Make the claims true

**~1 day. Everything here is a statement in the repo that is currently inaccurate.**

---

## T1.4 Resolve the 32 deferred editor panels

**Goal.** Remove ~4,400 LOC of unreachable UI from the live source root.

**Why — with an important correction.** My first pass reported this as "governance certifying a
surface that does not exist." **That was wrong, and the correction matters.** `ui-boundary.json`
classifies exactly these 32 files as `deferred`, not `allowed`:

```
surfaceClassifications: allowed 81 · deferred 32 · test-only 0
```

I verified the `deferred` set is **identical** to the set of root `.tsx` files that nothing imports —
a perfect 32/32 match. **The governance file is accurate.** The real problems are smaller and
different:

1. **CLAUDE.md overstates** (see T1.5) — that is the claim feeding every new session's mental model.
2. **The 32 sit in `src/` root, interleaved with the 11 live ones**, which is what made them read as
   live to a reader (including me) until the import graph was checked.
3. `App.tsx` is 47 lines and routes to two surfaces: `authoring/` (15,619 LOC, the real editor) and
   `workbench/` (196 LOC). Nothing else is reachable.

**Files.** 32 files under `NPDevEditor/ui-react/src/*.tsx` · `NPDevEditor/ui-react/ui-boundary.json` ·
`scripts/quality/run-editor-complexity-check.ps1`

**Steps.** Two acceptable options — pick one, do not straddle:

*Option A — delete (recommended).* They are in git history; recovery is `git show`.

```powershell
cd D:\WorkSpace\NPDev\NPDev_General\NPDevEditor\ui-react\src
git rm BetaReadinessConsolidationPanel.tsx BusinessCapabilityMarketplacePanel.tsx `
       BusinessWorkspacePanel.tsx ChangeImpactPreviewPanel.tsx EndUserLaunchChecklistPanel.tsx `
       ErrorRecoveryAssistantPanel.tsx ExplainabilityBundlePanel.tsx FriendlyExplanationCatalogPanel.tsx `
       GuidedTaskWorkspace.tsx GuidedTaskWorkspacePanel.tsx HumanReadableAuditExportPanel.tsx `
       ImportConflictAnalysisPanel.tsx ImportCorrectionWorkspacePanel.tsx ImportExecutionPanel.tsx `
       MultiCapabilityCompositionPanel.tsx OperationalReadinessDashboardPanel.tsx `
       OwnershipIsolationPanel.tsx PolicyPackGovernancePresetPanel.tsx RegulatoryAnnotationPanel.tsx `
       RoleWorkspacePanel.tsx RuntimeRefreshPanel.tsx ScenarioTemplatePanel.tsx `
       SemanticBehaviorWriteBackPanel.tsx SemanticGovernancePanel.tsx SemanticRollbackPanel.tsx `
       SpreadsheetOnboardingPanel.tsx StructuralWriteBackEditor.tsx StructuralWriteBackPanel.tsx `
       TemplateLibraryManagementPanel.tsx TemplateVariantSpecializationPanel.tsx `
       TenantOperationalAdministrationPanel.tsx WorkingDraftSystemPanel.tsx
```

*Option B — relocate.* `git mv` them to `src/deferred/`, so the live root shows only what ships.
Choose this only if a concept in there is actively planned for the next quarter.

Then, either way:

```powershell
# drop the now-empty `deferred` bucket and the 32 file entries from ui-boundary.json
# (files: 112 -> 80 under Option A)
cd D:\WorkSpace\NPDev\NPDev_General\NPDevEditor\ui-react
npm run build          # must still succeed
npm run test           # vitest: 7 files, must still pass
```

> ⚠️ `BusinessWorkspacePanel` is also named in `scripts/quality/run-editor-complexity-check.ps1`.
> Update that script in the same commit or the gate will fail on a missing path.

**Acceptance.** `npm run build` and `npm run test` green. `ui-boundary.json` `deferred` is empty (or
gone) and `files` matches what is on disk. `run-editor-complexity-check.ps1` green.

**Rollback.** `git revert` the commit; the files return intact.

**Effort.** S (1 hr). **Depends on.** T1.1 (commit first, so the revert boundary is clean).

---

## T1.5 Fix CLAUDE.md's editor claim

**Goal.** Correct the one line that misinforms every future session.

**Why.** [CLAUDE.md:27](../../../CLAUDE.md) reads:

```
| `NPDevEditor/ui-react` | TS/React | Authoring UI (30+ panels, Playwright E2E) |
```

Two inaccuracies. **"30+ panels"** counts files, not reachable features — the real number is 11 root
components plus the `authoring/` tree. **"Playwright E2E"** implies breadth; there is **one** spec
file (`e2e/editor-core.spec.ts`) plus 7 vitest files for 22,841 LOC.

This is the highest-leverage single line in the repo, because CLAUDE.md is loaded into every session's
context and this claim then propagates into summaries, plans, and eventually the README.

**Files.** `CLAUDE.md`

**Steps.** Replace line 27 with something true:

```
| `NPDevEditor/ui-react` | TS/React | Authoring UI — real surface is `src/authoring/` (~15.6k LOC);
  `src/workbench/` is a thin shell. Tests: 7 vitest files + 1 Playwright spec (`e2e/editor-core.spec.ts`) |
```

**Acceptance.** The line matches what T1.4 leaves on disk. Grep `CLAUDE.md` for any other count-style
claim and verify each the same way.

**Rollback.** Trivial.

**Effort.** ⚡ (5 min). **Depends on.** T1.4 (so the number is final).

---

## T1.6 ✅ DONE Merge `beta1-vision-spine` → `main`, re-tag

**Done 2026-07-28.** PR #5 opened, both gates (`AI knowledge substrate gate`, `PR gate`) green,
merged as `89eb945`. Tagged `beta1.2` on the merge commit, pushed. `git log --oneline HEAD..main`
is empty from `beta1-vision-spine`. Blocked for most of this session on `gh auth login` (owner
action); unblocked once a PAT was supplied and set as `GH_TOKEN` for the session.

**Goal.** Make `main` and the release tag contain the security fixes.

**Why.** `main` is **136 commits behind**. The `beta1.1` tag therefore does **not** contain REG-48
(delete() authz ordering), REG-50 (Postgres metadata fail-open), REG-51 (pack provenance), REG-52, or
REG-53. Anyone who clones the default branch — which is what publishing means — gets a version
missing five security/correctness fixes that the register describes as closed. **That is the most
directly falsifiable claim in the repo.**

**Files.** git refs only.

**Steps.**

```powershell
cd D:\WorkSpace\NPDev\NPDev_General
git fetch origin
git log --oneline main..HEAD | Measure-Object -Line     # expect 136 (+ today's commits)
git log --oneline HEAD..main                            # expect 2 — inspect them
```

Inspect those 2 `main`-only commits before merging; if they are already superseded, a merge is still
correct (it records both histories). Then open a PR so **both CI gates run on the merge** — do not
fast-forward locally and push:

```powershell
gh pr create --base main --head beta1-vision-spine `
  --title "beta1-vision-spine -> main: schema engine, REG-36..53, external-AI delegation, CI" `
  --body "..."
```

After both gates are green and the PR is merged, tag the merge commit:

```powershell
git checkout main; git pull
git tag -a beta1.2 -m "beta1.2 -- includes REG-48/50/51/52/53 and the schema-engine rebuild"
git push origin beta1.2
```

> Do **not** move or delete `beta1.1`. This project's own tag-immutability rule applies; supersede,
> never rewrite.

**Acceptance.** `git log --oneline HEAD..main` is empty from the branch. `beta1.2` exists on the merge
commit. Both workflows green on the PR.

**Rollback.** The merge commit can be reverted; the tag stays as a historical marker.

**Effort.** S (1 hr, plus CI wait). **Depends on.** T1.1, T1.2 (so CI runs the new test on the PR).

---

## T1.7 Register machine-contract warning header

**Goal.** Stop the next editor from silently breaking seven parsers.

**Why.** `NPDEV_OPEN_ITEMS_REGISTER.md` is parsed by
`check-register-consistency.py`, `check-narrative-status-drift.py`,
`run-script-automation-quality.ps1`, `npdev-ci-validation.yml`, and referenced from
`SchemaLifecycleExecutor.java`. Its prose format is a machine contract, and nothing in the file says
so. A heading-level change can make a gate parse zero rows and still exit 0.

**Files.** `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (top of file, before `## 0. Status summary`).

**Steps.** Insert:

```markdown
> ⚠️ **This file is a machine contract.** Seven consumers parse it —
> `scripts/quality/check-register-consistency.py`, `scripts/quality/check-narrative-status-drift.py`,
> `scripts/quality/run-script-automation-quality.ps1`, `.github/workflows/npdev-ci-validation.yml`,
> and a comment reference in `NPDevRuntimeHost/.../SchemaLifecycleExecutor.java`.
> Changing a heading level, a table column, or a `**Status:**` prefix can make a gate parse **zero
> rows and still exit 0**. After ANY edit, run:
>
> ```
> python scripts/quality/check-register-consistency.py
> python scripts/quality/check-narrative-status-drift.py
> ```
>
> **An item's status lives HERE and nowhere else.** Updating a status in a plan document instead will
> NOT be caught: `check-narrative-status-drift.py` is report-only by design and exits 0 regardless.
```

**Acceptance.** Header present; both scripts still parse the file (17 cross-checked rows,
0 contradictions).

**Rollback.** Trivial.

**Effort.** ⚡ (15 min). **Depends on.** Nothing.

---

# PHASE C — Build the front door

**~2 days. This is what a stranger sees.**

---

## T1.8 ★ Rewrite the README

**Goal.** Replace an internal architecture doctrine with a description of what the software does.

**Why.** The current README opens on Box/Object/Truth, the T0–T6 ladder, and an
Application-Box → Module-Box hierarchy. Verified against the code:

- `"box"` appears **0 times** in `model.schema.json` — the Box/Object hierarchy itself is genuinely
  not implemented.
- **Correction (found during T1.13, 2026-07-27):** the original claim here — "`TruthLevel.java`
  exists and is referenced in 0 files across generator, kernel, and runtime host" — was wrong.
  `truthLevel` IS in the schema (all four copies) and IS enforced: `SemanticValidator` warns on an
  upward truth edge, and `ReleaseGateValidator.validatePromotion` hard-blocks a promotion when the
  reachable bond closure's truth level is below the target (`NPDevContract/docs/BONDS.md` Phase 6,
  DONE, tested). So "Truth" specifically has a real, if partial, implementation; it's the Box/Object
  *hierarchy* that doesn't exist. The README rewrite below states this distinction accurately rather
  than repeating the blanket claim.

So the front door describes an architecture that is not implemented, and says nothing about what
NPDev does, who it is for, or how to run it. There is no quickstart, no screenshot, no limitations
section. For a repo about to go public this is the highest-cost inaccuracy in the project.

**Files.** `README.md` (full rewrite). Move the current content to
`docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md` if not already there, and link it as *future
direction*, clearly labelled as unimplemented.

**Steps.** Structure, in this order:

1. **One sentence.** Recommended framing — spec-driven development, which is what the platform
   actually does and the frame that covers all three crown jewels:

   > **NPDev is spec-driven development for business applications.** You write a specification in
   > domain language; NPDev derives a complete, deterministic system — database schema and its
   > migrations, REST API, authorization, and long-running business processes — as Spring Boot source
   > you own outright.

2. **What it generates** — the honest table:

   | Layer | Generated |
   |---|---|
   | Entities, persistence, REST API, OpenAPI | ✅ |
   | Schema migrations + evolution planning | ✅ |
   | Row-level authorization, tenant isolation | ✅ |
   | Durable flows, events, orchestration | ✅ |
   | Auth, JWT, password reset, ControlPanel | ✅ |
   | Docker + compose + Caddy | ✅ |
   | Generic CRUD admin UI | ✅ |
   | **Custom business screens** | ❌ **hand-written against the generated API** |

3. **The three differentiators**, each in two sentences: schema evolution that preserves data;
   a durable workflow engine (flows park on events, survive restart, compensate on failure);
   AI-authored specs against a schema-constrained validator.

4. **Quickstart** — from clone to a running app, copy-pasteable, tested on a clean machine.

5. **Honest limitations** — the hand-written-UI gap; single bounded context per model; pre-1.0
   instability (T1.9); Windows-first tooling.

6. **License** (Apache-2.0) and **status** (pre-1.0, `beta1.2`).

7. **Future direction** — Box/Object/Truth, linked, explicitly labelled *not yet implemented*.

**Acceptance.** Every factual claim in the README is verifiable by running something in the repo.
Ask a project-blind agent to read only the README and answer: *What does this do? Who is it for? What
does it not do?* If any answer is wrong, the README is not done.

**Rollback.** Old README preserved in git and in `docs/architecture/`.

**Effort.** M (4 hr). **Depends on.** T1.4/T1.5 (so counts are accurate).

---

## T1.9 Breaking Change Charter

**Goal.** Convert pre-1.0 instability from a liability into a stated, defensible policy.

**Why.** You are about to publish a platform whose DSL should change aggressively — 14 of 23
`flowStep.type` values are pure synonyms, 12 field-alias pairs exist, and all 20 app definitions are
machine-authored and regenerable in one session. That freedom is a **depreciating asset**: it costs
one regeneration today and 50–100× more once one external user has an app in production. Saying so
publicly both protects the freedom and filters for the right early users.

**Files.** `README.md` (a section) and `BREAKING.md` (new, initially just a header).

**Steps.** Add verbatim:

```markdown
## Stability policy (pre-1.0)

NPDev is pre-1.0 and **deliberately unstable**. The model DSL, generated code layout, and internal
APIs will change without deprecation cycles.

We do this on purpose. NPDev models are machine-authored — an agent writes them from your
specification. A breaking DSL change costs one regeneration, not a migration project. We would
rather fix a design mistake than carry it for a decade.

Every breaking change ships with:
  • a `npdev migrate` codemod that rewrites existing models automatically
  • a one-line entry in BREAKING.md
  • the reason it was worth breaking

If you need frozen APIs today, NPDev is not ready for you yet. We will freeze at 1.0, and not one
release before.
```

**Acceptance.** Section present in README; `BREAKING.md` exists. The rule *"every breaking change
ships its codemod in the same commit"* is recorded as a standing convention (add it to CLAUDE.md).

**Rollback.** Trivial.

**Effort.** ⚡ (30 min). **Depends on.** T1.8.

---

## T1.10 ★★ Write `docs/FLOWS.md`

**Goal.** Give the durable workflow engine a front door.

**Why — this is the highest-value item in TREE 1.** Verified inventory:

- **23** `flowStep.type` values in the schema; **9** implemented step kinds in `FlowStepDefinition`:
  `AWAIT_EVENT · BRANCH · CAPABILITY_CALL · EMIT_EVENT · INVARIANT_CHECK · MAP · PRE · RETURN · SCHEDULE_EVENT`
- **6** flow-instance statuses: `RUNNING · WAITING_EVENT · COMPLETED · FAILED · FAILED_PERMANENT · STUCK`
- Durable suspend/resume — `JdbcFlowInstanceStore`, `NpdevFlowInstanceTable`, `ResumeBootstrapRunner`
- Correlation ownership — `JdbcCorrelationOwnershipStore`
- Compensation — LNCH-17: a crash mid-compensation resumes into *finish compensating*, not re-run forward
- Resumable `forEach` — LIFT-LOOP-P2, one flat step index, completed iterations skipped on resume;
  nested `AWAIT_EVENT` rejected at compile time by `SemanticValidator`
- Cron schedules, before/after step hooks, event-triggered orchestration rules, lifecycle rule profiles

This is a durable, event-correlated, compensating workflow engine — the category Temporal, Camunda,
and Zeebe sell. **It has one documentation page (`SCHEDULED_FLOWS.md`, covering the scheduling corner
only) and no demo.** Meanwhile the schema engine has six docs and a proof matrix.

Three specific surface problems, all cheap to fix:

1. **No `FLOWS.md`.** This task.
2. **No discoverability.** `FlowEngine.java` is a correct, well-formed 26-line hexagonal port
   (`startFlow` / `resumeFlow` / `ResumeOutcome`) — *not* a stub. But nothing in it points at the
   implementation, which lives inside `KernelRunner.java`'s 4,423 lines. Anyone exploring by filename
   finds the interface and stops. → **T1.16**.
3. **No demo.** `medium-expense-approval` — the sample literally named for approval flows — is
   1 concept, 5 fields, 1 flow. Nothing in the corpus shows a flow parking on an event, surviving a
   restart, and resuming. → **TREE 2 item 2.F / CORE C-3**.

> **Owner note (2026-07-27).** The core — flows, events, orchestration — is the platform's mechanics,
> and the first pass of this analysis under-weighted it by measuring surface area (files, LOC,
> reachable UI) instead of depth. The consolidated core workstream is
> [EXECUTION_TREES.md §0.1](EXECUTION_TREES.md#01-the-core-track--read-this-before-the-trees).
> This task is **C-1**, its highest-value item.

**Files.** New `docs/FLOWS.md`. Link from `README.md`, `CLAUDE.md`, and
`NPDEV_CONCEPTS_DEEP_DIVE.md`.

**Steps.** Cover, in order:

1. **What a flow is**, and why durable matters — one paragraph, no jargon.
2. **The state machine** — an ASCII diagram of the 6 statuses and the transitions between them.
3. **All 9 step kinds** — one worked example each, taken from a real sample model.
4. **`awaitEvent` in depth** — correlation matching, ownership claim, what happens on restart.
   This is the differentiator; give it the most space.
5. **Compensation** — the LNCH-17 crash-mid-compensation rule, stated as a guarantee.
6. **`forEach`** — resumability, and the documented boundary that nested `AWAIT_EVENT` is rejected.
7. **Scheduling** — cron, tenant scope.
8. **Hooks and orchestration rules** — injecting behavior without editing the flow.
9. **Operations** — where instances live, how to inspect a `STUCK` one, how resume is triggered.
10. **Honest limits** — name them explicitly, in the register's own voice.
11. **Evidence** (CORE **C-5**) — cite `REG16_FLOW_ORCHESTRATION_ADVERSARIAL_REVIEW.md`. It is
    currently in `archive/programme-history/` among 28 process docs, where a genuine adversarial
    review of a headline capability reads as bookkeeping. Either link it from here or move it back to
    `docs/` as evidence rather than history.

**Acceptance.** A reader who has never seen the project can explain, after reading only this file,
what happens when a flow awaiting an event experiences a JVM restart. Cross-check every claim against
`FlowStepDefinition.java`, `KernelRunner.java`, and `FlowInstanceStatus.java` — no claim without a
code reference.

**Rollback.** N/A (additive).

**Effort.** M (1 day). **Depends on.** Nothing.

> **Pairs with TREE 2 item 2.F / CORE C-3** (a demo app that parks on an event, survives
> `docker restart`, and resumes). The doc explains it; the demo proves it. If only one gets done this
> month, do the doc — 1 day versus 1 week, and it unblocks the other.

---

# PHASE D — Owner decisions

---

## T1.11 ⬥ Fill the four verdict lines

**Goal.** Unblock the decisions no AI is permitted to make.

**Why.** `docs/DECISION_BRIEFS_2026-07.md` is marked `STATUS: ACTIVE` and contains four briefs
(C1/D4/D5/F8), each with options, consequences, a recommendation, and an **unfilled**
`**Verdict:**` line. ADR-0009's own honesty contract names this class of call as owner-only. The
briefs are written; the decisions are not.

**C1 (repo visibility) gates T1.4→T1.10's entire purpose.** Everything in this plan exists to make
publishing safe; if the answer to C1 is "private forever," TREE 1 should be re-scoped and TREE 3's
3.1/3.2 deleted.

> **Note.** This document was accidentally swept into `docs/archive/programme-history/` during the
> 2026-07-27 doc reorganization and has been **restored to `docs/`**. The archive pass classified it
> as history because it had zero inbound references — which was true precisely because it was new.
> Future archive passes must check for `STATUS: ACTIVE` and unfilled verdict markers, not just
> inbound references.

**Files.** `docs/DECISION_BRIEFS_2026-07.md`

**Steps.** Read each brief; write `(a)`, `(b)`, or `(c)` on its `**Verdict:**` line; propagate the
answer to the documents each brief names.

**Acceptance.** Four verdict lines filled. Downstream documents updated.

**Effort.** ⬥ owner. **Depends on.** Nothing — and blocks 3.1.

---

# PHASE E — Cheap hardening

**Optional within the same window. Each is independent.**

---

## T1.12 Editor vitest into the PR gate

**Goal.** Give 22,841 LOC of authoring UI per-PR coverage.

**Why.** The editor's 7 vitest files run only in the nightly/on-demand
`npdev-ci-validation.yml` Windows job (via Playwright setup). The PR gate does not touch the editor
at all. It is the second-largest untested-per-PR surface after the Postgres adapters (which are
blocked by REG-4 — see 3.5).

**Files.** `.github/workflows/npdev-pr-gate.yml`

**Steps.** Add before the RuntimeHost step:

```yaml
      - name: Editor unit tests
        working-directory: NPDevEditor/ui-react
        timeout-minutes: 10
        run: |
          npm ci
          npm run test
```

Do **not** add Playwright here — it needs a browser download and a staged static host, which is why
it belongs in the nightly job. Vitest alone is fast.

**Acceptance.** PR gate runs the 7 vitest files and stays under its 60-minute budget.

**Effort.** S (1 hr). **Depends on.** T1.4 (do not wire a gate to files you are about to delete).

---

## T1.13 ⬥ TruthLevel: make it load-bearing or delete it

**Goal.** Resolve a vision artifact with no runtime consequence.

**Why — with an important correction (verified 2026-07-27, before implementing).** This task's
premise was wrong, and the correction matters. The claim was: *"referenced in 0 files across
generator, kernel, and runtime host — the rule is written in a javadoc and enforced nowhere."*
Direct inspection found the opposite: `SemanticValidator.validateBondTruthEdge`
(`SemanticValidator.java:1934-1953`, wired in at `:326`) already emits a warning on an upward truth
edge, **and** `ReleaseGateValidator.validatePromotion`
(`NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ReleaseGateValidator.java:31-65`)
already walks the *reachable bond closure* and **hard-blocks promotion** when a dependency's truth
level is below the target — a real, tested, working release gate, not a javadoc promise. Both are
covered by green tests (`TruthLevelSupportTest`, `ReleaseGateValidatorTest`, confirmed passing
2026-07-27). `NPDevContract/docs/BONDS.md` documents this as **Phase 6 — DONE** (`:195-203`) of a
9-phase roadmap; Phases 0–4, 6–8 are DONE, Phase 5 is PARTIAL, Phase 9 (end-to-end proof on a live
FinalApp) is the only phase still open.

The plan-writer's "0 files in generator/kernel/runtime host" was checking the wrong altitude: this
feature genuinely lives (and is enforced) *inside the DSL module itself* — the release-gate check,
not codegen or runtime — which the plan's own "Files" line below had already scoped to correctly, but
the "enforced nowhere" conclusion drawn from it did not hold up.

**Disposition:** no code change needed. **(a) is already true** — the rule already is a compiler
(well, validator) guarantee, not merely documentation. What remains is Phase 9 (real FinalApp
end-to-end proof), which is a roadmap item in `BONDS.md`, not a launch-blocker, and BONDS.md already
tracks it there.

**Files.** `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/TruthLevel.java` and its 14 sibling
references inside the DSL module (unchanged by this correction).

**Acceptance.** Verified: `TruthLevelSupportTest` and `ReleaseGateValidatorTest` both green
(2026-07-27). No enum deletion, no new SemanticValidator check — both would have been redundant with
or a regression against the existing, deliberate "warn at authoring time, hard-block at release time"
design.

**Effort.** Spent: verification only (~15 min), not the estimated half-day. **Depends on.** T1.8.

---

## T1.14 🔒 Verify the LNCH-4 auth-sibling claim

**Goal.** Establish whether a known-deferred auth flaw still exists — or was never real.

**Why, stated carefully.** Project memory records that `JwtBearerAuthFilter` had a bug (it clobbered
already-authenticated requests), that it was fixed, and that *"RuntimeApiKeyAuthFilter has the same
latent flaw, deferred, reconfirmed 2026-07-12."*

**Direct inspection on 2026-07-27 found no class named `RuntimeApiKeyAuthFilter` anywhere in the
tree.** The only auth filters present are `JwtBearerAuthFilter` and `SuperUserCredentialAuthFilter`.
So the memory is stale, misnamed, or refers to generator-emitted code
(`RuntimeApiEmitter.java` is the only production source matching `RuntimeApiKey`).

**This task is therefore verification, not a fix.** Do not carry an unverified security claim into a
public repo in either direction — neither as an unfixed bug nor as a silently dropped one.

**Files.** `NPDevRuntimeHost/src/main/java/com/finalexec/config/JwtBearerAuthFilter.java` ·
`NPDevRuntimeHost/src/main/java/com/finalexec/controlpanel/SuperUserCredentialAuthFilter.java` ·
`NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/RuntimeApiEmitter.java`

**Steps.**

1. Determine the exact shape of the original `JwtBearerAuthFilter` fix (`git log -p` on that file).
2. Grep every filter — including generator-emitted ones — for that shape: writing to
   `SecurityContextHolder` without first checking for an existing authentication.
3. If a real instance exists: file it as a REG item with a RED-first reproduction, then fix.
4. If none exists: **correct the memory and any doc that repeats the claim**, recording that the
   named class is absent as of `beta1.2`.

**Acceptance.** A written answer either way, with the grep and the commit that settles it.

**Effort.** S (2 hr). **Depends on.** Nothing.

---

## T1.15 Split `SemanticValidator`

**Goal.** Start the god-file decomposition with the safest, highest-ratio file.

**Why.** 4,244 lines, 69 methods, **137 `private static` helpers, and zero instance fields.** It is a
stateless function library in one file. The split is mechanical, the compiler proves each move, and
355 DSL tests prove behavior is unchanged. It is also the file a new contributor is most likely to
open first, since it owns every validation error message they will see.

**Files.** `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java`

**Steps.** Split by model section so the result mirrors `model.schema.json`'s own `$defs`:

```
ConceptValidation · FlowValidation · PanelValidation
AggregateValidation · ExpressionValidation · PackValidation
```

`SemanticValidator` remains a ~200-line orchestrator that calls each and merges diagnostics.

> **Rule:** ONE pass, ZERO behavior changes, no bug fixes mixed in. A refactor that also fixes bugs is
> a refactor nobody can review. If you find a bug, file it and keep moving.

**Acceptance.** `:NPDevContract:dsl:test` → 355 tests, 0 failures, **identical count**. No file over
~800 lines. Diagnostic messages byte-identical (they are asserted in tests, which is the safety net).

**Rollback.** `git revert`; it is a pure-move commit.

**Effort.** M (1 day). **Depends on.** T1.1.

---

## T1.16 `FlowEngine` javadoc → implementation + `FLOWS.md`  · CORE **C-2**

**Goal.** Make the durable workflow engine findable by anyone browsing the source.

**Why.** `FlowEngine.java` is a correct hexagonal port — 26 lines, `startFlow`, `resumeFlow`, and a
`ResumeOutcome` record. Nothing wrong with it. But it carries **no javadoc**, so a reader who opens
the file whose name announces the platform's hardest capability learns only the method signatures and
has no path to the implementation inside `KernelRunner.java`'s 4,423 lines, nor to the
`WAITING_EVENT` machinery in `FlowInstanceStatus` / `JdbcFlowInstanceStore` /
`JdbcCorrelationOwnershipStore` / `ResumeBootstrapRunner`.

This is the cheapest possible fix for the discoverability half of the core's surface problem: a
class-level javadoc costs fifteen minutes and permanently changes what a browsing reader finds.

**Files.** `NPDevKernel/kernel/src/main/java/com/npdev/kernel/FlowEngine.java`

**Steps.** Add a class-level javadoc naming the implementation, the durability guarantee, and the doc:

```java
/**
 * Port for NPDev's durable flow engine.
 *
 * <p><b>Implementation:</b> {@code KernelRunner} (this interface is the port; the engine is not a
 * separate class today — see {@code docs/EXECUTION_TREES.md} item 2.B.5 for the planned split into
 * per-step-kind classes).
 *
 * <p><b>Durability contract.</b> A flow that reaches an {@code AWAIT_EVENT} step is persisted as
 * {@code WAITING_EVENT} ({@code FlowInstanceStatus}) via {@code FlowInstanceStore}
 * ({@code JdbcFlowInstanceStore} / {@code InProcFlowInstanceStore}) and survives JVM restart;
 * {@code ResumeBootstrapRunner} rehydrates waiters on boot. Correlation ownership — which waiting
 * instance claims an arriving event — is held by {@code CorrelationOwnershipStore}. A failure after
 * partial progress is compensated rather than re-run forward (LNCH-17), and a {@code forEach} loop
 * resumes at the first not-yet-completed iteration (LIFT-LOOP-P2).
 *
 * <p><b>Full documentation:</b> {@code docs/FLOWS.md} — the 9 step kinds, the 6-status state
 * machine, event correlation, compensation, scheduling, hooks, and the documented limits.
 *
 * @see FlowStepDefinition
 * @see com.npdev.kernel.execution.FlowInstanceStatus
 */
```

Add the same pointer to `CLAUDE.md`'s kernel row so future sessions inherit it:

```
| `NPDevKernel/kernel` | Java | Runtime: `KernelRunner` (also hosts the durable flow engine —
  see `docs/FLOWS.md`), `FlowEngine` port, CapabilityDispatcher, EventStore |
```

**Acceptance.** `FlowEngine.java` names its implementation, its durability guarantee, and
`docs/FLOWS.md`. `CLAUDE.md`'s kernel row points at `FLOWS.md`. `:kernel:test` still 159/0.

**Rollback.** Trivial (comment-only).

**Effort.** ⚡ (15 min). **Depends on.** T1.10 (so the referenced doc exists).

---

# Execution order

```
Day 1 AM   T1.1  commit ──► T1.2  behaviorTest in CI ──► T1.3  coverage gate
Day 1 PM   T1.4  deferred panels ──► T1.5  CLAUDE.md ──► T1.7  register header
Day 2      T1.6  merge to main + beta1.2 tag  (CI wait — run T1.10 in parallel)
Day 2-3    T1.10 docs/FLOWS.md  ★★ [CORE C-1, C-5] ──► T1.16 FlowEngine javadoc [CORE C-2]
Day 3-4    T1.8  README rewrite ──► T1.9  Breaking Change Charter
Day 4      T1.12 editor vitest · T1.13 TruthLevel · T1.14 auth verify · T1.15 SemanticValidator
Anytime    T1.11 ⬥ four verdict lines  ← owner; blocks 3.1
                                             │
                                             ▼
                                    🚀 3.1  PUBLISH
```

**Gate check after each phase:**

```powershell
python scripts/quality/check-register-consistency.py
pwsh -NoProfile -ExecutionPolicy Bypass -File scripts/quality/run-ai-knowledge-gate.ps1
```

---

# Definition of done for TREE 1

- [x] Working tree committed; `main` merged (`89eb945`, PR #5); `beta1.2` tagged and containing
      REG-48/50/51/52/53 (confirmed via `git log origin/main --grep`)
- [x] `:generator:behaviorTest` runs in both workflows; a coverage gate prevents the recurrence
- [x] No unreachable component in the editor's live source root; `ui-boundary.json` matches disk
- [x] Every count in `CLAUDE.md` verified against the code
- [x] `README.md` describes what the software does, with a working quickstart and honest limits
- [x] Breaking Change Charter published; codemod rule adopted as a convention
- [x] **CORE:** `docs/FLOWS.md` exists, every claim in it has a code reference, `FlowEngine` and
      `CLAUDE.md` both point at it, and the flow/orchestration adversarial review is cited as
      evidence rather than buried in `archive/` (C-1, C-2, C-5)
- [x] Four verdict lines filled (C1=public, D4=REG-17 closed, D5=E5 permanently open, F8=accept
      + document escape hatch)
- [x] Both gates green (PR #5, re-confirmed after the 2.B.4/2.B.5 push:
      `AI knowledge substrate gate` pass 38s, `PR gate` pass 5m44s)
- [x] **Repo flipped to public** (`gh repo view` confirms `visibility: PUBLIC`) — the earlier
      items above are this session's own basis for believing no false statement remains; not a
      claim independently re-derived by a fresh project-blind read on top of them

**Published 2026-07-28.**

---

*Map: [EXECUTION_TREES.md](EXECUTION_TREES.md) · Ledger: [NPDEV_OPEN_ITEMS_REGISTER.md](NPDEV_OPEN_ITEMS_REGISTER.md) ·
Decisions: [DECISION_BRIEFS_2026-07.md](DECISION_BRIEFS_2026-07.md)*
