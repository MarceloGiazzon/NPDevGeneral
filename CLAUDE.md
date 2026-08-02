# NPDev_General — repo guide for Claude

NPDev is a model-driven app platform: author a JSON model → generate a Spring Boot "FinalApp" →
build/run it. This repo holds the platform (DSL, generator, kernel, runtime-host template, editor);
app definitions and build output live **outside** it (see Layers below).

## Critical: where things go

- **Build output → `D:\WorkSpace\NPDev\Build`. NEVER write generated/build artifacts inside this
  repo.** (`docs/BUILD_OUTPUT_LOCATION_POLICY.md`)
- **Evidence / scratch → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`**, not the repo.
- **Source-of-truth layers:** (1) this repo = truth for platform code/scripts/schemas;
  (2) `D:\WorkSpace\NPDev\AppGen\apps` = truth for app *definitions* only (not a git repo);
  (3) `Build` = ephemeral. You may edit layer 2/3 for speed, but propagate any code/script change
  back to layer 1.

## Module map

| Module | Lang | Purpose |
|---|---|---|
| `NPDevContract/dsl` | Java | DSL AST, compiled model, `JsonModelParser`, `ModelCompiler`, `SemanticValidator` |
| `NPDevContract/schemas` | JSON Schema | Runtime/kernel/generator/authoring contracts |
| `NPDevGenerator/generator` | Java | Codegen engine — emitters + `npdev-templates/*.mustache` + assembly |
| `NPDevKernel/kernel` | Java | Runtime: `KernelRunner` (also hosts the durable flow engine — see `docs/FLOWS.md`), `FlowEngine` port, CapabilityDispatcher, EventStore |
| `NPDevKernel/adapters/*` | Java | Pluggable adapters, `*-inproc` (dev) / `*-postgres` (prod) pairs; plus `expression-cel`, `auth-context-jwt`, `authz-default`, `persistence-postgres`, … |
| `NPDevRuntimeHost` | Java/Spring | Spring Boot template **copied into every generated FinalApp** — not a built product subproject. Login/bootstrap/ControlPanel controllers live here (`com.finalexec.*`) |
| `NPDevEditor/ui-react` | TS/React | Authoring UI — real surface is `src/authoring/` (~15.6k LOC); `src/workbench/` is a thin shell. Tests: 7 vitest files + 1 Playwright spec (`e2e/editor-core.spec.ts`) |
| `NPDevSamples` | JSON/PS1 | Reference sample apps + browser-verification harness |
| `NPDevCli` / `NPDevMcp` | Python | Model-validation CLI / MCP server for AI authoring |
| `golden-ai-scenarios`, `schemas/ai` | JSON | AI safety/verification fixtures + schemas |

Main package roots: `com.npdev.dsl.v1` / `com.npdev.generator` / `com.npdev.kernel` /
`com.finalexec` (runtime-host).

## Large files — DO NOT full-read (Grep to a line, then Read with offset/limit)

These are the files most often edited; reading any one whole burns 40–100k tokens. Sizes are checked
against disk by `scripts/quality/check-record-surfaces.py` (±25% tolerance) — if this list drifts, that
gate fails, so it should stay accurate without needing a manual re-audit:

- `NPDevGenerator/.../npdev-templates/static-react/assets/app.js` (407 KB) — **generated bundle, ignore entirely**
- `NPDevGenerator/.../npdev-templates/business-ui-app.mustache` (169 KB)
- `NPDevKernel/adapters/expression-cel/.../GeneratedCrudRuntimeSupport.java` (158 KB)
- `NPDevRuntimeHost/.../db/SchemaLifecycleExecutor.java` (138 KB)
- `NPDevKernel/kernel/.../KernelRunner.java` (126 KB)
- Big JSON that is noise if read whole: `NPDevSamples/NPDevSamples_Tree.txt`, and test fixtures
  `NPDevRuntimeHost/src/test/resources/npdev/compiled-metadata.json` / `metadata/fields.manifest.json`

**No longer large** — split by the 2.B decomposition (2026-07-27/28), read them normally: `SemanticValidator.java`
(10 KB, now an orchestrator over sibling `*Validation` classes in `NPDevContract/dsl/.../validation/`) and
`TrustedSourceEmitter.java` (11 KB, now an orchestrator over sibling classes in
`NPDevGenerator/.../emitters/`). Their logic moved to those sibling classes — grep the package, not the file.

**`model.schema.json` is duplicated in 4 places** — edits must mirror to all four:
`NPDevContract/schemas/model.schema.json`, `NPDevContract/schemas/authoring/model.schema.json`,
`NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
`NPDevContract/dsl/resources/Schemas/model.schema.json` (NOT `schemas/archive/`).
Verify with `python scripts/quality/check-schema-mirror-consistency.py` — the four copies are
**semantically** identical, not byte-identical; the `dsl/resources/Schemas/` copy carries
`canonicalSchema` and `deprecated` registry keys the checker excuses. Never compare by file hash.

## Build / run / test

- **Root Gradle wires only `:NPDevContract:dsl` (+ `:core`).** Other modules build via their own paths.
- **Generate + build + run a FinalApp:** `scripts/appgen/Build-NpdevApp.ps1` (AppGen apps),
  `Build-ClaudeApp.ps1` (Claude Support Desk), `Build-AppGenApp.ps1`. Per-app `_ops` toolbox emits
  `Start-App.ps1` / `Stop-App.ps1` / `Start-Environment.ps1` (starts H2Server TCP).
- **Validate a model:** `:NPDevContract:dsl:validateModel -PmodelPath=<p> -PreportOut=<p>`.
- **Quality gates — "all gates green" means ONE command:**
  `pwsh -NoProfile -File scripts/quality/run-all-gates.ps1` (T2). It runs four gates by default, in
  this order, and keeps going past a failure so you see every red in one run:
  `run-ai-knowledge-gate.ps1` (static, seconds; hosts all 16 `check-*.py`) → `run-generator-gate.ps1`
  → `run-runtimehost-gate.ps1` → `run-frontend-gate.ps1`. `run-beta-release-gate.ps1` (release
  posture, T3) is **deferred by default** since the Fast Lane plan's item 4 — pass
  `-IncludeReleaseGate` or `-Only betaRelease` to run it too. Run one gate with `-Only aiKnowledge`.
  **Never report "gates green" from a single gate** — that claim was made in three consecutive move
  reports while a checker sat red, which is what `run-all-gates.ps1` exists to prevent. A new
  `scripts/quality/check-*.py` MUST be invoked by some `run-*.ps1`; `run-script-inventory-check.ps1`
  fails otherwise (Move 11 W2/O4).
- **Faster mid-plan verification (the Fast Lane plan, 2026-08-01):** `scripts/quality/run-fast-gate.ps1`
  is the T1 tier — T0's checks (schema-mirror-consistency, plus an optional touched model/DSL-test
  check) plus generate+build+boot+REST-smoke of the ONE frozen canary app
  (`NPDevSamples/npdev-canary`) and the three T1-scoped corpus checks. Target < 3 min vs. T2's
  ~13-15 min; use it at the end of a wave/step, not as a substitute for T2 before closing a Move.
  `npdev verify --tier T0|T1|T2|T3` is the one CLI entry point for all four tiers, reading the same
  staleness ledger every tier writes to (`scripts/quality/verification-cadence.json` +
  `scripts/quality/cadence_state.py`) — a check that goes stale past its declared `maxStaleness`
  shows up as a visible, blocking OVERDUE line, never a silent skip.
- **AI knowledge substrate:** durable platform findings live as `knowledge/cards/*.json`
  (schema `schemas/ai/knowledge-card.schema.json`); `knowledge/platform-status.json` is a **derived**
  projection of the gaps ledger (regen via `scripts/ai/extract_platform_status.py`, never hand-edit).
  `scripts/ai/build_knowledge.py` fans these + golden scenarios into `<Build>/npdev-ai/`
  (`rag-index.json`, `failure-index.json`, `capabilities.json`) that the MCP tools
  `npdev_search_examples` / `npdev_search_fix` / `npdev_check_support` consume. See
  `docs/ai/AI_KNOWLEDGE_LOOP_AND_TOOLING_PLAN.md`.
- **Maintainer skills** (tracked, un-ignored under `.claude/skills/`): `rebuild-app` (three-cache
  refresh via `scripts/appgen/Rebuild-And-Restage.ps1`) and `verify-in-browser` (ScrapForAI).
- **After changing kernel/adapter Java, restage jars before regenerating an app:**
  `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars`. **The defaults now agree**
  (both resolve to `D:\WorkSpace\NPDev\Build\runtimehost-libs` via `Get-NPDevRuntimeHostLibsDir`,
  LC-C4 / Wave 1.4) — previously the sync defaulted to `__OutsideRepo\runtimehost-libs` while
  `Build-NpdevApp.ps1` defaulted to the Build root, so letting each default meant the app silently
  kept a stale jar. Passing `-RuntimeHostLibsDir` explicitly still works and still wins; prefer
  `scripts/appgen/Rebuild-And-Restage.ps1`, which threads one value through every step.
- **`AppGen\generator-runtime\current`** (the jar cache the AppGen builders read) is not auto-synced;
  refresh via `AppGen\generator-runtime\prepare-npdev-generator-runtime.ps1 -RuntimeRoot D:\WorkSpace\NPDev\AppGen\generator-runtime` (pass `-RuntimeRoot` explicitly).

## Stability policy

NPDev is pre-1.0 and deliberately unstable (see `README.md`'s "Stability policy" and
`BREAKING.md`). Standing convention: **every breaking change to the model DSL, generated code
layout, or internal APIs ships its `npdev migrate` codemod in the same commit**, plus a one-line
`BREAKING.md` entry — never land the break first and the codemod later.

## Environment notes

- Windows. Prefer **PowerShell** and the dedicated Grep/Glob/Read tools. Git Bash coreutils are on
  PATH via `C:\Program Files\Git\usr\bin` (applies to new sessions).
- Regenerating an app can hit a transient **VS Code Java/Gradle file lock** on the fresh build dir —
  the established workaround is to bump the build-root suffix (`-alt`/`-hNN`); a reboot clears it.

## Where the truth lives (read before filing or editing status)

- **Open items:** `ledger/items/*.yml` is authoritative. `docs/OPEN_ITEMS.md` is GENERATED from it
  (`scripts/quality/generate_open_items.py`) — never hand-edit. `docs/NPDEV_OPEN_ITEMS_REGISTER.md`
  is HISTORICAL/archived-in-place: read for narrative (root causes, fix rationale), never for status.
- **DSL is at 2.0** — retired `flowStep.type` aliases collapsed to their canonical values. See
  `BREAKING.md`. Every breaking change to the DSL, generated code layout, or internal APIs ships its
  `npdev migrate` codemod (`NPDevCli/dsl_v2_migration.py`) in the same commit.
- **Adding a DSL feature?** Add a real example to `NPDevSamples/dsl-conformance-max` in the same
  commit — `scripts/quality/check-dsl-coverage.py` (wired in `run-ai-knowledge-gate.ps1`) fails any
  DSL feature with zero corpus coverage.
- **Adding a new top-level model array field** (like `roles`, `propertyScopes`, `properties`)? It has
  to be threaded through **four** places, not one, or a pack/fragment that declares it gets the field
  silently dropped with no error anywhere (REG-108): (1) `ModelSourceResolver.MODEL_ARRAY_KEYS` —
  the JSON-level pack/fragment composer only concatenates pack- or fragment-contributed content for
  keys in this set; a field missing from it is silently discarded for anyone but the app's own model
  root (the root has its own separate "pass through any unrecognized key" fallback, which is why this
  class of bug hides for a long time — root-only usage works by accident); (2) both copies of
  `pack.schema.json` (`NPDevContract/schemas/` and `NPDevContract/dsl/src/main/resources/schema/`) —
  `additionalProperties:false` rejects a pack file that tries to declare the field before the composer
  even runs; (3) `ModelResolver.resolve()` — the AST-level specialization resolver's constructor call
  must actually pass the new field through to the resolved `ModelAst`; (4) the canonical JSON
  writer/reader pair (`CompiledModelCanonicalJson`/`CompiledModelCanonicalJsonReader`) — the generator
  only ever reads canonical JSON, so a field that survives resolution but never reaches the canonical
  round-trip is just as invisible downstream. `REG-108`'s root cause was (1)+(2) only — `roles`
  (RC-B1) and `propertyScopes`/`properties` (RC-A1) had (3)/(4) from day one but were never added to
  (1)/(2), so a pack (not an app root) declaring any of the three silently lost it.
- **Adding a script under `scripts/`?** It needs both a classification (pattern-matched in
  `scripts/policy/script-inventory-policy.json`) and a declared `invocation` in
  `scripts/policy/script-invocation-declarations.json`; `run-script-inventory-check.ps1` enforces
  both match reality.
- **Adding a corpus model** (`AppGen/apps` or `NPDevSamples`)? It needs a `corpusRole` entry in
  `scripts/quality/corpus-roles.json` (`dsl-fixture` / `engine-variant` / `repro-case` / `showcase`)
  — a model with no role fails the corpus gate, no silent default.
- **Frontend contract:** `docs/UI_CONTRACT.md` · screen classes: `docs/SCREEN_TAXONOMY.md` · the
  durable flow engine (hosted inside `KernelRunner`): `docs/FLOWS.md`.

## Key docs

`docs/GETTING_STARTED.md`, `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`,
`docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md`,
`docs/architecture/INTERNAL_DB_SCHEMA_SOURCE_OF_TRUTH.md`, `docs/MATURITY_CLOSURE_LEDGER.md`.
