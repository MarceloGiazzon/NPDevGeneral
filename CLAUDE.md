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

- `NPDevGenerator/.../npdev-templates/static-react/assets/app.js` (141 KB; sibling chunks
  `AuthoringApp.js`/`ReactWorkbenchApp.js` in the same `assets/` dir) — **generated bundle, ignore
  entirely**
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
  `run-ai-knowledge-gate.ps1` (static — no build, no boot; hosts 31 of the 32
  `scripts/quality/check-*.py` across 40 numbered checks — the one exception,
  `check-dsl-reference-output-floor.py`, runs in `run-generator-gate.ps1` because it needs a build.
  **Measured 811 s / ~13.5 min on 2026-08-08**, not the "seconds" this line used to claim;
  budget for it) → `run-generator-gate.ps1`
  → `run-runtimehost-gate.ps1` → `run-frontend-gate.ps1`. `run-beta-release-gate.ps1` (release
  posture, T3) is **deferred by default** since the Fast Lane plan's item 4 — pass
  `-IncludeReleaseGate` or `-Only betaRelease` to run it too. Run one gate with `-Only aiKnowledge`.
  **Never report "gates green" from a single gate** — that claim was made in three consecutive move
  reports while a checker sat red, which is what `run-all-gates.ps1` exists to prevent. A new
  `scripts/quality/check-*.py` MUST be invoked by some `run-*.ps1`; `run-script-inventory-check.ps1`
  fails otherwise (Move 11 W2/O4).
- **Generator determinism IS checked, and the checker is easy to miss.** It is
  `scripts/hygiene/check-deterministic-generation.ps1` — note `hygiene`, not `quality`, and
  `deterministic`, not `determinism`, which is why a `*determinism*` search finds nothing and
  concludes it does not exist (the stabilize plan did exactly that and specified a duplicate). It
  generates one sample **twice**, SHA-256s every emitted file under `ArtifactNP/` + `App/`, and
  fails naming the differing paths. Its exclusions are declared, not blanket:
  `npdev-build-info.properties` and `generation-run.json`, both deliberately non-reproducible
  provenance. `run-generator-gate.ps1` runs it, so it is in T2 — and per the cadence ledger's
  entry-point granularity rule it is covered transitively by the `generator` entry rather than
  listed separately. Measured 2026-08-09: 786 files vs 786, 0 differing.
- **Faster mid-plan verification (the Fast Lane plan, 2026-08-01):** `scripts/quality/run-fast-gate.ps1`
  is the T1 tier — T0's checks (schema-mirror-consistency, plus an optional touched model/DSL-test
  check) plus generate+build+boot+REST-smoke of the ONE frozen canary app
  (`NPDevSamples/npdev-canary`) and the three T1-scoped corpus checks. Designed for < 3 min;
  **measured 263 s / ~4.4 min on 2026-08-08** since the machine's RAM was halved. Use it at the end
  of a wave/step, not as a substitute for T2 before closing a Move.
  **If the canary reports `health: failed` with "connection refused", read the boot log before
  believing it.** The app starts in ~24 s; the rest of the budget goes on `gradlew --no-daemon
  bootRun` forking a single-use Gradle daemon. That overhead — not the app — produced two false REDs
  on 2026-08-08 while health/smoke/acceptance all passed at a longer timeout, so the default
  `-CanaryBootTimeoutSeconds` is now 300. A genuinely crashed app is reported as "Process exited
  before health check passed", which is a different message and a real failure.
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
- **NEVER resolve the repo root by its directory NAME, and never hardcode `D:\WorkSpace\...` as a
  default** (REG-144). Eleven copies of the external-build-root resolution walked up looking for a
  directory literally named `NPDev_General`. GitHub checks this repo out as `NPDevGeneral`, so every
  copy fell through to its own fallback — and those fallbacks started from different directories.
  Measured in a real clone renamed to `NPDevGeneral`, running real Gradle: **three** different build
  roots in one checkout (`<clone>/Build`, `<clone>/NPDevContract/Build`, `<clone>/../Build`). Gradle
  wrote jars to one and `sync-runtimehost-libs.ps1` searched another, so three packaged-app proof
  tests failed on Linux CI for twelve days.
  **It passed locally the whole time because this machine's directory really is named
  `NPDev_General` — the walks agreed by coincidence, not by construction, so no local gate could
  ever have caught it.** Identify the root by its CONTENTS (the directory holding `NPDevContract` +
  `NPDevGenerator` + `NPDevKernel`), the predicate `WorkspaceRootLocator.java` already established.
  The eleven copies are pinned by twin-pair rule `build-root-resolution-eleven-place`: each carries
  an `npdev-build-root-resolution` token, and dropping it from any one fails
  `run-ai-knowledge-gate.ps1`. A script default that needs the repo root should use
  `$PSScriptRoot`-relative arithmetic (exact, adds no twelfth walk) or **call**
  `Get-NPDevBuildRoot` / `Get-NPDevRuntimeHostLibsDir` — never repeat their answer as a literal, which
  is precisely what `run-fast-gate.ps1` did while its own docs claimed it called the function.
- **A generated FinalApp carries its own `.npdev-root` marker**, so that file alone does NOT identify
  this repo — every root resolution pairs it with the module directories. Only the marker's existence
  is ever tested; nothing parses its content.
  **This became true on 2026-08-10 (MON-2) and was false before it.** Nothing had ever written a
  marker into a generated app: `git ls-files` showed exactly one, this repo's own, while CLAUDE.md
  said the above and `clean-sample-output.ps1` retained `App\.npdev-root` as evidence. So a scan keyed
  on the marker pair found ZERO of the 118 apps in this machine's Build root.
  `OperationalRunbookEmitter` now writes it beside `_ops`, and **`npdev monitor` accepts
  `_ops/resolved-db-plan.json` as the alternative half of the pair** so every app generated before
  today stays discoverable. If you need "is this a generated app?", use
  `npdev_monitor.discovery_rule()` rather than testing the marker yourself.

- **The Monitor + Scrap Manager (`docs/MONITOR.md`).** `npdev monitor scan|probe|engine|logs|ops` and
  `npdev explore list|show|validate|preflight|run|record|prune|pin|accept|context` are the CLI half of
  the Manager's two newest screens; the Tauri commands are thin wrappers (no CLI behaviour in Rust).
  Three rules worth knowing before touching any of it:
  - **The routine schema is the ENGINE's, pinned** (`schemas/ai/scrapforai-routine.schema.json`).
    Never hand-edit it; re-pin with `scripts/quality/pin-routine-schema.py` against a running engine.
    All 42 routines live under a `browser-routines/` directory and are conformance-checked by
    `check-routine-corpus-conformance.py` (ai-knowledge gate [43/44]).
  - **"Green" is one function**, `npdev_explore.evaluate_verdict`. The PowerShell harness and the
    Playwright reporter record THROUGH `npdev explore record` rather than judging for themselves.
  - **Records are never deleted**; `explore prune` prunes blobs only, exempts pinned and
    ledger-linked runs, and prints what it kept and why.

- **A generated app keeps its own logs at `<app>\logs\`** (MON-6): `Run-FinalApp.ps1` tees stdout+
  stderr there, `npdev monitor ops` tees ops-script output there, and the directory is spared by
  `Build-NpdevApp.ps1`'s wipe alongside `data`. `npdev monitor logs export` bundles them with a
  redacted `resolved-db-plan.json` — **that plan carries a DB password, so anything that copies it
  off the machine must go through `npdev_monitor.redact()`.**

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
- **The same field also needs a live path through `JsonModelParser` → `ModelCompiler` → the
  canonical writer/reader pair → `ModelResolver`** even for an app's own model root (not just a
  pack) — this is the separate hazard `REG-104` found: `roles[]` existed at the AST layer with no
  diagnostic anywhere once RolePermissions tried to use it, because that full chain wasn't wired yet.
  Both this rule and the pack-composition one above are enforced mechanically, not just by this
  doc — `scripts/quality/check-twin-pair-consistency.py` (wired in `run-ai-knowledge-gate.ps1`)
  reads `scripts/quality/twin-pair-registry.json`, a small human-curated list of "these locations
  must move together" pairs (currently: this four-place chain, the pack-composition four-place
  chain, and `REG-112`'s test-exclusion sibling pair), and fails the gate the moment any of them
  diverges — add a new rule there the next time a "one place updated, its twin forgotten" bug is
  found, rather than letting a fourth instance go unnoticed the way REG-89/104/112 did.
- **Citing a `REG-nn`/ledger id as a live blocker** in `docs/SCREEN_TAXONOMY.md`, `docs/MOVE*_CHECKLISTS.md`,
  `docs/MOVE*_FINDINGS.md`, or `docs/MOVE1_PANEL_GAPS.md`? Check the id's own ledger status BEFORE
  writing "blocked by REG-nn" or "REG-nn (open ...)" — `scripts/quality/check-blocker-citation-freshness.py`
  (wired in `run-ai-knowledge-gate.ps1`) fails the moment that id's `ledger/items/REG-nn.yml` says
  DONE/PARTIAL while the doc still calls it a live blocker. Move 15 Phase D item D1: five separate
  times a console/screen record said "blocked by X" while X had already been closed in a later move,
  and `SCREEN_TAXONOMY.md` itself sat four moves stale before a human re-read caught it — this is the
  mechanical control that was missing (five other defect families already had one).
- **Adding a script under `scripts/`?** It needs both a classification (pattern-matched in
  `scripts/policy/script-inventory-policy.json`) and a declared `invocation` in
  `scripts/policy/script-invocation-declarations.json`; `run-script-inventory-check.ps1` enforces
  both match reality.
- **Adding a corpus model** (`AppGen/apps` or `NPDevSamples`)? It needs a `corpusRole` entry in
  `scripts/quality/corpus-roles.json` (`dsl-fixture` / `engine-variant` / `repro-case` / `showcase`)
  — a model with no role fails the corpus gate, no silent default.
- **Frontend contract:** `docs/UI_CONTRACT.md` · screen classes: `docs/SCREEN_TAXONOMY.md` · the
  durable flow engine (hosted inside `KernelRunner`): `docs/FLOWS.md`.
- **Scoped-property cascade** (model `propertyScopes[]`/`properties[]`, Wave 6/RC-A1): resolved at
  runtime by `PropertyResolver`/`DefaultPropertyResolver` (`NPDevKernel/kernel/.../properties/`)
  against the built-in `workspace::PropertyValue` concept (row presence is the is-set signal — a
  stored row with a null value is an explicit override, no row means inherit from the next scope).
  Exposed over REST by `PropertyResolverController` (`GET/PUT /api/properties[/scopes|/{key}]`,
  open to every authenticated role — the raw generic-CRUD endpoint for `workspace::PropertyValue`
  stays admin-only like every other built-in-pack concept, see REG-114). The generated admin surface
  (`scripts/appgen/New-PropertiesAdminPage.ps1` → `properties.html`, one section per scope) is the
  reference consumer. `propertyScopes[]`'s declared order IS resolution order (most specific first);
  the implicit root scope (no `from`) must be declared last, enforced at compile time (REG-116).

- **Dialect-bound SQL goes in ONE package** (`NPDevKernel/kernel/.../storage/sql/`, STOR-1). The
  41 sites that used to spell `LIMIT ? OFFSET ?` / `ON CONFLICT` / `jsonb` / `information_schema`
  inline are now `SqlDialect` calls, and `scripts/quality/check-dialect-sites.py`
  (`run-ai-knowledge-gate` [28/29]) fails the moment a new one appears outside that package. **Do
  not add a dialect-bound construct anywhere else** — if the dialect has no method for it, add one.
  Pagination in particular returns `PaginationClause`, not a String, because SQL Server binds
  `(offset, limit)` in the REVERSED order: a hardcoded `setInt(n, limit); setInt(n+1, offset)` is
  correct on three engines and silently returns the wrong page on the fourth. **A paginated query
  must declare `ORDER BY`** — enforced on every engine (conformance P3), and free today because
  every existing one already does.
- **`MySQL` and `SqlServer` ARE supported** as of 2026-08-09 (STOR-3 DONE, CI run `31296993259`):
  a generated app boots, serves non-BMP unicode, paginates, survives a restart, and passes Tier C's
  four schema-evolution vectors on each — the same bar Postgres meets, in the same run. This line
  said "NOT supported" for a long time and was right to: **eight** defects stood between a complete
  dialect and a working app (STOR-4/5/7/9/10/11/12), every one found only by building the artifact a
  user runs. If you are tempted to widen a claim about an engine, that history is the argument for
  measuring first.
  **Supported does not mean identical**, and the differences are declared at the point of choice by
  `npdev engines`: **H2 and MySQL COMMIT IMPLICITLY ON DDL**, so `DDL_IN_TRANSACTION` is absent from
  their capabilities and a "nothing persisted" claim is false there (STOR-2) — ask
  `SqlDialects.active().supports(...)` rather than assuming a rollback. SQL Server has no suffix row
  cap, so `rowLimit()` throws (boundary B29); call `rowLimited()`, which every engine answers.
  `npdev capabilities` prints the matrix, generated from the dialects so it cannot drift.
- **The environment toolbox is one script per operation, not one per engine** (E15). The five `_ops`
  operations are emitted BYTE-IDENTICAL for Postgres, MySQL and SQL Server — they branch on
  `profile.kind`, and every engine-specific fact (image, env, ready probe, database creation, quirks)
  comes from `DockerEngineProfile` / `npdev/engine-profiles.json`. Adding an engine is a row in that
  JSON, never a sixth branch. `check-engine-parity.py` (AI-knowledge gate [33/35]) fails the moment
  an engine is special-cased without its siblings; `run-engine-toolbox-parity.py` generates all three
  and diffs the result.

## Key docs

`docs/GETTING_STARTED.md`, `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`,
`docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md`,
`docs/architecture/INTERNAL_DB_SCHEMA_SOURCE_OF_TRUTH.md`, `docs/MATURITY_CLOSURE_LEDGER.md`.
