# AI Session Digest — 2026-07-22 — Launch-ledger closure + the LNCH-22 saga

> **Audience: a future AI agent (or human) picking up this project cold.** This is a dense,
> navigable record of what one working session did, why, and what remains. Absolute paths throughout.
> Written 2026-07-22. Branch of record: `beta1-vision-spine`. Everything below is committed + pushed
> unless stated otherwise.
>
> **One-line state:** the launch-readiness ledger is **fully closed — 24 DONE / 0 PARTIAL / 0 OPEN**
> (`D:\WorkSpace\NPDev\NPDev_General\docs\LAUNCH_READINESS_GAPS.md` §2). The last hard item (LNCH-22)
> was closed *honestly* — a real build-tooling bug was found, fixed, and proven with a project-blind
> in-sandbox validation, not asserted.

---

## 0. TL;DR

| Thing | State |
|---|---|
| Launch ledger (`docs/LAUNCH_READINESS_GAPS.md` §2) | **24 DONE / 0 PARTIAL / 0 OPEN** |
| LNCH-10 (reporting/export, incl. server-side PDF) | **DONE** — verified: CI-green + code present |
| LNCH-18 (a non-author authors an app) | **DONE** — independent cold-tester authored an app unaided |
| LNCH-22 (a newcomer builds from docs alone) | **DONE** — but only after reverting an overstated close, fixing a real bug, and a blind in-sandbox re-validation |
| Head commit (session close-out) | `a872123` |
| Not launch-ledger gaps, still tracked | REG-6 ColumnFacts (~40%), promotion-panel retry-loop bug, 3 latent items |
| Floated but unplanned | "AI Studio" in-product builder feature |

---

## 1. Orientation (read `CLAUDE.md` for the full map)

- **Platform repo:** `D:\WorkSpace\NPDev\NPDev_General` (this repo). Truth for platform code/scripts/schemas.
- **Build output (NEVER in the repo):** `D:\WorkSpace\NPDev\Build`.
- **Evidence / scratch (NEVER in the repo):** `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`.
- **App definitions:** `D:\WorkSpace\NPDev\AppGen\apps` (layer 2, not a git repo).
- **Model → generated Spring Boot "FinalApp".** Modules: `NPDevContract/dsl` (DSL/compiler/validator),
  `NPDevGenerator/generator` (codegen), `NPDevKernel/kernel` + `NPDevKernel/adapters/*` (runtime),
  `NPDevRuntimeHost` (Spring template copied into every FinalApp, `com.finalexec.*`), `NPDevEditor`,
  `NPDevSamples`, `NPDevCli`/`NPDevMcp`.

---

## 2. What triggered this session

The user pasted a completion report from a prior **implementation session** claiming the launch
ledger was fully closed (24 DONE / 0 PARTIAL), covering two task groups:

- **Task 1 — `docs/REG28_30_REG12S2_CLOSURE_PLAN.md`** (3 phases): REG-29 (a claim-release test),
  REG-28/30 (bind a schema-migration mark to its `from→to` transition in `MigrationMarkStore`), and
  REG-12 **Slice 2** (print stylesheet / `#printRoot` / `@media print` / Print button).
- **Task 2 — `docs/FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md`** (2 parts): **Part A** = LNCH-10 **Slice 3**
  (server-side PDF), **Part B** = LNCH-18/22 (a cold-start external-tester run).

The user's standing directive on this project: **verify, don't trust.** So the session's job was to
independently verify those claims against the committed code — which is exactly where the value came
from.

---

## 3. Work done, by workstream

### 3.1 Verification verdict (headline)

- **Part A (PDF) = genuinely solid.** Confirmed independently (see 3.2).
- **Part B: LNCH-18 defensible, LNCH-22 overstated.** The closure run's *own* friction log proved a
  newcomer could **not** build the tutorial "from docs alone." The DONE was reverted to PARTIAL, a
  real bug was root-caused and fixed, and a blind re-validation earned the DONE back (see 3.3, 3.4).

### 3.2 Part A — LNCH-10 Slice 3 (server-side PDF) — VERIFIED SOLID

Implemented by the prior session; independently confirmed present + green:

- New DSL kind **`document`** wired through schema (4-copy `model.schema.json` mirror) / AST
  (`NPDevContract/dsl/.../ast/DocumentAst.java`) / compiler (`CompiledDocument.java`) / canonical-JSON.
- New port **`DocumentRenderContract`**
  (`D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\kernel\src\main\java\com\npdev\kernel\ports\DocumentRenderContract.java`)
  + adapter pair `NPDevKernel\adapters\document-render-inproc\` (OpenHTMLtoPDF, pure-JVM HTML→PDF) and
  `document-render-stub\`.
- New endpoint `@GetMapping("/{document}/render.pdf")` in
  `NPDevRuntimeHost\src\main\java\com\finalexec\api\DocumentRenderController.java` (+ `NpdevDocumentRenderConfig.java`).
- **CI:** GitHub Actions run **`29943008077` = success** on SHA `b5c7c88` (the PDF commit). Verified
  via `bash D:\WorkSpace\NPDev\NPDev_General\scripts\ci\gh-api.sh GET actions/runs/29943008077`.
- The prior session fixed 3 real bugs (two silent field-drop reconstructions in AST/compiled rebuild
  sites; one silent controller-allowlist exclusion). A later cold run reported "3 generator packaged-app
  tests failed" — that was a **worktree jar-staging artifact, not a regression** (CI ran those exact
  tests green on `b5c7c88`).

Design reference: `D:\WorkSpace\NPDev\NPDev_General\docs\REG12_DOCUMENT_EXPORT_PLAN.md`.

### 3.3 Part B — the external-tester runs (LNCH-18 / LNCH-22)

The tester mechanism is a **project-blind AI subagent** given only the cold-start brief
(`D:\WorkSpace\NPDev\NPDev_General\docs\EXTERNAL_TESTER_COLDSTART.md`) + the repo, forbidden from
reading platform source/plans/register, and never coached.

- **LNCH-18 (authoring) = DONE.** A cold subagent authored an issue-tracker app from scratch
  (model → validate → generate) and drove full CRUD over REST, unaided (no MCP tools were registered
  in the sandbox; it used the CLI validator fallback).
- **LNCH-22 (docs) = the saga.** *Two independent* cold runs could not build the tutorial **from docs
  alone** — both hit an unstated `runtimehost-libs` staging wall and only escaped by reading the
  generated `build.gradle` + relying on a pre-populated libs dir a fresh clone wouldn't have. That is
  the DoD ("builds from docs alone") unmet. **Reverted DONE → PARTIAL** and root-caused (3.4).

### 3.4 The LNCH-22 `runtimehost-libs` bug — root cause, 3 fix iterations, validation

**Symptom:** `pwsh -File scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars` (the tutorial's
one-time setup) reports gradle `BUILD SUCCESSFUL` but then fails discovery: *"No RuntimeHost jars were
discovered under build/libs after local jar build"*, writes an empty manifest, exit 1 → the generated
app's `verifyNpdevRuntimeHostLibs` task then fails, no jar is produced.

**Root cause (proven by an in-sandbox diagnostic probe):** the sync script and `build.gradle` resolved
the **external build root by different algorithms**, so they scanned/wrote different directories:
- `build.gradle`'s `resolveNpdevBuildRoot` (in `NPDevKernel\build.gradle` and `NPDevGenerator\build.gradle`)
  walks **up** for a dir literally named `NPDev_General`; if found → `<that>/../Build`, else
  `<gradleRootDir>/../Build`.
- The sync script computed `<workspaceRoot.parent>/Build`.
- These **diverge** whenever the repo folder isn't named exactly `NPDev_General`, e.g.:
  - a real `git clone` names the folder **`NPDevGeneral`** (repo name) → walk misses → fallback differs;
  - a subagent worktree lives at **`NPDev_General/.claude/worktrees/agent-XXX`** (nested) → the walk
    finds the *outer* `NPDev_General` → the real `D:\WorkSpace\NPDev\Build`, while the sync scanned the
    worktree-parent `Build`. Gradle wrote 61 jars to the real `Build`; discovery found 0.

**Why it took three attempts (the masking traps — see the memory note):**
1. **`0c161a7`** — first fix: sync exported one resolved `NPDEV_BUILD_ROOT`. **Insufficient** — relied
   on the env var propagating into the gradle child, which didn't hold in the subagent sandbox.
2. **`e9d89ff`** — second fix: pass `-PnpdevBuildRoot` on the gradle command line + `build.gradle`
   reads that property first. Provably redirects `buildDirectory` locally — **but still failed the
   blind run.** (The `-P` didn't take effect in the sandbox; the build used the walk value.)
3. **`2adf8ec`** — **the real fix:** the sync script now **mirrors `build.gradle`'s walk exactly**, so
   its jar-discovery scans wherever gradle *actually* writes, regardless of whether `-P`/env take
   effect. `-P` retained as belt-and-suspenders. Unit-checked the walk for nested-worktree /
   `NPDevGeneral`-clone / normal layouts.

**Every local reproduction PASSED and masked the bug**, because (a) worktrees under
`D:\WorkSpace\NPDev\*` share the already-populated `D:\WorkSpace\NPDev\Build` and the machine-wide
gradle cache (`%LOCALAPPDATA%\NPDev\gradle`), and (b) the local shell's gradle honored `-P`. The bug
only manifests in a clean subagent sandbox.

**Second confound discovered:** the blind subagents were running **stale `main` (`3e29cca`)**, not the
working branch — `Agent isolation:worktree` worktrees are created from the **default branch**, not the
active branch, so the earlier blind "failures" were testing code *without the fix*. Confirmed:
`2adf8ec` is **not** an ancestor of `3e29cca`.

**Airtight validation (`a872123`):** the user added `Bash(git checkout:*)` + `Bash(git fetch:*)` allow
rules to `.claude/settings.local.json` (I'm blocked by the classifier from self-adding those). A
genuinely project-blind subagent then, in the sandbox where the failure occurs:
- **Phase A** checked out `2adf8ec` and verified it (HEAD + grep of the fix strings),
- **Phase B** built the tutorial **from docs alone**: sync staged **41 jars** (the exact step that
  failed on old code), `generate` OK, `bootJar` → **`FinalExec-0.1.0.jar` (80 MB)**, booted, verify
  returned **201** (valid) / **422** (`NameRequired` invariant). No source-reading, no leftover libs.

That meets LNCH-22's DoD → flipped to DONE, ledger restored to 24/0/0.

Also independently confirmed the fix on the **real newcomer layout**: a standalone checkout in a folder
named `NPDevGeneral` at `2adf8ec` ran the full documented tutorial (sync → generate → bootJar) → green,
80 MB jar.

### 3.5 Tutorial doc fixes (`docs/TUTORIAL_FIRST_APP.md`)

- Added an explicit **one-time libs-staging step** in Section 3 (`sync-runtimehost-libs.ps1 -BuildLocalJars`).
- Corrected the invariant-failure HTTP status from **`400` → `422`**.
- Moved the CLI `--output` example **outside the repo** (build-output policy).
- **Windows verify-curl fix** (surfaced by the passing blind run): Step 4 was POSIX-`sh` only; inline
  `-d '{...}'` mangles in PowerShell → misleading `400`. Now leads with a cross-shell
  `--data "@body.json"` form + an `Invoke-RestMethod` variant; documents the `version`/`tenantId`
  response fields.

### 3.6 Ledger + register reconciliation

- `docs/LAUNCH_READINESS_GAPS.md`: LNCH-22 walked DONE → PARTIAL (23/1) → DONE (24/0) with honest,
  cited reasoning at each step (addendum banner, LNCH↔REG crosswalk, roll-up table, detail entry).
- `docs/NPDEV_OPEN_ITEMS_REGISTER.md`: REG-12/13/14 tracked to closure.

### 3.7 Memory updates (`C:\Users\Marcelo\.claude\projects\d--WorkSpace-NPDev-NPDev-General\memory\`)

- New: `feedback_agent_worktree_validation.md` (the two durable lessons in §5).
- Updated: `MEMORY.md` index (ledger now 24/0/0) and `project_launch_readiness.md` (top note).

---

## 4. Commits this session (branch `beta1-vision-spine`)

| Commit | Summary |
|---|---|
| `65f55e0` | Plan: `docs/FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md` (close the last 3 launch gaps) |
| *(prior impl session)* | REG-29 (`10797a6`), REG-28/30 (`2c22df5`), REG-12 Slice 2 (`7e8e7af`), PDF Slice 3 + tester (`b5c7c88`), CI-green record (`dcfe1c9`) |
| `0c161a7` | Verify closure summary; **revert LNCH-22 DONE → PARTIAL**; env-export fix attempt #1 + tutorial one-time-setup step |
| `e9d89ff` | Fix attempt #2: deterministic `-PnpdevBuildRoot` (insufficient alone) |
| `2adf8ec` | **The real fix:** sync mirrors `build.gradle`'s walk |
| `a872123` | **LNCH-22 DONE:** in-sandbox blind validation passed; ledger → 24/0/0; tutorial Windows-curl fix |

---

## 5. Two durable validation lessons (saved to memory — [[feedback_agent_worktree_validation]])

1. **Agent `isolation: worktree` worktrees are based on the DEFAULT branch (`main`), NOT the active
   working branch.** A blind subagent validating branch work silently tests stale code. **Always**
   make Phase A `git fetch` + `git checkout <your-commit>` and verify HEAD before the real test. Needs
   `Bash(git checkout:*)` + `Bash(git fetch:*)` allow rules in `.claude/settings.local.json` (the
   classifier blocks self-adding them; the user must). Sanity-check with
   `git merge-base --is-ancestor <commit> HEAD`.
2. **Never validate a build-environment fix in a location that shares state with a working setup.**
   Worktrees under `D:\WorkSpace\NPDev\*` share the populated `D:\WorkSpace\NPDev\Build` and the
   machine-wide gradle cache — they MASK build-root/discovery divergence. Local passes were false
   positives twice. Reproduce in a truly isolated dir, or trust only an in-sandbox agent on the fixed
   commit.

---

## 6. What is OPEN / not done

**None of these are launch-ledger gaps** (the ledger is 24/0/0). They are tracked in
`docs/NPDEV_OPEN_ITEMS_REGISTER.md`:

| Item | State | Notes |
|---|---|---|
| **REG-6 — ColumnFacts** | ~40% / OPEN | Refactor of the ~2,900-line `SchemaLifecycleExecutor` to read one `ColumnFacts` projection everywhere. Deliberately deferred — all-or-nothing, needs many full H2+Postgres proof-matrix runs; a partial migration is worse per its own rationale. |
| **Promotion-panel retry-loop bug** | OPEN, filed (register §2.4) | On InMemory apps `/api/admin/promotion` 503s; the 503 path never sets `loaded=true`, so `render()` re-triggers `loadPromotion()` → unbounded loop. Found during REG-12 Slice 2; not fixed. |
| **Latent item — wmsoffice `D:/` JWT paths** | latent | A generated app carries absolute `D:/` paths for JWT keys (portability). |
| **Latent item — adapter-list fragility** | latent | New adapter jars must be manually added to the 3 `*PackagedGeneratedAppRuntimeProofTest` adapter lists + the sync/build-local-jars path, or the generated app won't compile on clean CI (the mail-adapter + document-render precedents). |
| **Latent item — platform-status drift** | latent | `knowledge/platform-status.json` is a derived projection; regen via `python scripts/ai/extract_platform_status.py` — no automation guards staleness. |
| **REG-17 (third-party reproduction)** | advanced, not closed | The cold Task-C run showed the quality gates don't fully run clean from a fresh worktree (the `runtimehost-libs` fix now addresses the biggest blocker, but a full REG-17 re-run was not done). `run-generator-gate.ps1` also had 3 packaged-app test failures in that run (the staging artifact, per §3.2). |

**Floated by the owner, assessed, NOT planned:** an in-product **"AI Studio" builder page** — a
web UI holding external AI-tool credentials + URLs that calls those services with platform format
context, has the AI generate app files, then runs the existing verify/build/start/smoke/screenshot
scripts. Assessment: a strong *post-launch* feature and largely an assembly of what already exists
(NPDevMcp + RAG + `ModelValidatorMain` + the build/ScrapForAI scripts); the one genuinely hard piece is
credential security (superuser-gated, never returned over HTTP, never shipped into a FinalApp). Needs a
phased plan with the security posture and an `ai-provider` port/adapter pair (like `mail-*`) before any
code.

**Plans written this session (execution status):**

| Plan file (under `docs\`) | Status |
|---|---|
| `FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md` | Executed — all three gaps closed |
| `REG12_DOCUMENT_EXPORT_PLAN.md` | Executed — LNCH-10 Slice 3 DONE |
| `EXTERNAL_TESTER_COLDSTART.md` | Used — drove the LNCH-18/22 cold runs |
| `REG28_30_REG12S2_CLOSURE_PLAN.md` | Executed (by the prior impl session) |

---

## 7. Key files & evidence (absolute paths)

**Fix / changed code:**
- `D:\WorkSpace\NPDev\NPDev_General\scripts\runtimehost\sync-runtimehost-libs.ps1` — the walk-mirroring build-root resolution
- `D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\build.gradle` and `...\NPDevGenerator\build.gradle` — `resolveNpdevBuildRoot` (reads `-PnpdevBuildRoot` first, then env, then the `NPDev_General` walk)
- `D:\WorkSpace\NPDev\NPDev_General\docs\TUTORIAL_FIRST_APP.md` — one-time-setup step, 422 fix, Windows-safe verify-curl

**Ledgers / registers / plans:**
- `D:\WorkSpace\NPDev\NPDev_General\docs\LAUNCH_READINESS_GAPS.md` (the launch ledger; §2 is the table)
- `D:\WorkSpace\NPDev\NPDev_General\docs\NPDEV_OPEN_ITEMS_REGISTER.md`
- `D:\WorkSpace\NPDev\NPDev_General\docs\FINAL_LAUNCH_GAPS_CLOSURE_PLAN.md`
- `D:\WorkSpace\NPDev\NPDev_General\docs\REG12_DOCUMENT_EXPORT_PLAN.md`
- `D:\WorkSpace\NPDev\NPDev_General\docs\EXTERNAL_TESTER_COLDSTART.md`

**Tooling:**
- `D:\WorkSpace\NPDev\NPDev_General\scripts\ci\gh-api.sh` — repo-scoped GitHub API helper (reads token from Git Credential Manager at runtime, never prints/stores it; scoped to `MarceloGiazzon/NPDevGeneral`). Usage: `bash scripts/ci/gh-api.sh <METHOD> <repo-relative-path> [json-body]`.

**Evidence (in `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\external-tester-evidence\2026-07-22\`):**
- `lnch22-insandbox-blind-PASS.md` — the decisive close (blind, on the fixed commit)
- `lnch22-fix-validation.md` — tooling + end-to-end fix validation
- `cold-run-1-report.md`, `friction-log-task-{a,b,c}.md`, `SUMMARY.md` — the original cold runs that surfaced the blocker
- `task-a-issue-tracker\` — the app a cold tester authored for LNCH-18

**Memory (`C:\Users\Marcelo\.claude\projects\d--WorkSpace-NPDev-NPDev-General\memory\`):**
`feedback_agent_worktree_validation.md`, `project_launch_readiness.md`, `quality_gate_health.md`, `MEMORY.md`.

---

## 8. Environment gotchas for the next agent

- **Two masking traps** — see §5. If a build-tooling fix "passes locally," suspect shared `Build` +
  warm gradle cache; validate in isolation or in-sandbox on the fixed commit.
- **Agent worktree base = `main`** — check out your commit in Phase A of any in-sandbox validation.
- **Slimness pre-commit hook** — `D:\WorkSpace\NPDev\NPDev_General` must stay under 3000 files; a commit
  fails if generated `Output\` trees or `.claude\worktrees\{Build,agent-*__OutsideRepo}` junk remain.
  Clean them (`git worktree remove … --force`, `rm -rf .claude/worktrees/Build .claude/worktrees/agent-*__OutsideRepo`,
  `rm -rf NPDevSamples/*/Output`) before committing. `scripts\hygiene\clean-workspace-state.ps1` also does this.
- **RuntimeHost-libs staging** — a fresh clone/worktree must run
  `pwsh -File scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars` once before any generated
  app builds (now documented in the tutorial). With the `2adf8ec` fix this works regardless of the repo
  folder name.
- **`model.schema.json` is duplicated in 4 places** — mirror every schema edit (a conformance test pins it).
- **The GitHub repo is `MarceloGiazzon/NPDevGeneral`** (folder `NPDevGeneral` on clone — the exact
  name-mismatch that caused the LNCH-22 bug). CI = `.github/workflows/npdev-pr-gate.yml`.
- **Windows env:** PowerShell primary; Git Bash coreutils on PATH. `curl.exe` inline `-d '{...}'` JSON
  mangles in PowerShell — use `--data "@file.json"`.

---

## 9. Suggested next actions (if resuming)

1. If desired, **merge `beta1-vision-spine` → `main`** so a real `git clone` (folder `NPDevGeneral`)
   and the agent worktrees carry the LNCH-22 fix. (Optional; the branch is the source of truth.)
2. Decide the **promotion-panel retry-loop bug** (register §2.4) — small, real, InMemory-only.
3. If the "AI Studio" feature is wanted, write a phased plan first (security posture + `ai-provider`
   adapter pair) — do not start code blind.
4. REG-6 ColumnFacts remains the largest deliberately-deferred engineering item.
