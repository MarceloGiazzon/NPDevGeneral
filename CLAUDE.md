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
| `NPDevKernel/kernel` | Java | Runtime: `KernelRunner`, FlowEngine, CapabilityDispatcher, EventStore |
| `NPDevKernel/adapters/*` | Java | Pluggable adapters, `*-inproc` (dev) / `*-postgres` (prod) pairs; plus `expression-cel`, `auth-context-jwt`, `authz-default`, `persistence-postgres`, … |
| `NPDevRuntimeHost` | Java/Spring | Spring Boot template **copied into every generated FinalApp** — not a built product subproject. Login/bootstrap/ControlPanel controllers live here (`com.finalexec.*`) |
| `NPDevEditor/ui-react` | TS/React | Authoring UI (30+ panels, Playwright E2E) |
| `NPDevSamples` | JSON/PS1 | Reference sample apps + browser-verification harness |
| `NPDevCli` / `NPDevMcp` | Python | Model-validation CLI / MCP server for AI authoring |
| `golden-ai-scenarios`, `schemas/ai` | JSON | AI safety/verification fixtures + schemas |

Main package roots: `com.npdev.dsl.v1` / `com.npdev.generator` / `com.npdev.kernel` /
`com.finalexec` (runtime-host).

## Large files — DO NOT full-read (Grep to a line, then Read with offset/limit)

These are the files most often edited; reading any one whole burns 40–100k tokens:

- `NPDevGenerator/.../npdev-templates/static-react/assets/app.js` (407 KB) — **generated bundle, ignore entirely**
- `NPDevKernel/adapters/expression-cel/.../GeneratedCrudRuntimeSupport.java` (198 KB)
- `NPDevGenerator/.../emitters/TrustedSourceEmitter.java` (197 KB)
- `NPDevKernel/kernel/.../KernelRunner.java` (177 KB)
- `NPDevContract/dsl/.../validation/SemanticValidator.java` (164 KB)
- `NPDevGenerator/.../npdev-templates/business-ui-app.mustache` (147 KB)
- Big JSON that is noise if read whole: `NPDevSamples/NPDevSamples_Tree.txt`, and test fixtures
  `NPDevRuntimeHost/src/test/resources/npdev/compiled-metadata.json` / `metadata/fields.manifest.json`

**`model.schema.json` is duplicated in 4 places** — edits must mirror to all four:
`NPDevContract/schemas/model.schema.json`, `NPDevContract/schemas/authoring/model.schema.json`,
`NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
`NPDevContract/dsl/resources/Schemas/model.schema.json` (NOT `schemas/archive/`).

## Build / run / test

- **Root Gradle wires only `:NPDevContract:dsl` (+ `:core`).** Other modules build via their own paths.
- **Generate + build + run a FinalApp:** `scripts/appgen/Build-NpdevApp.ps1` (AppGen apps),
  `Build-ClaudeApp.ps1` (Claude Support Desk), `Build-AppGenApp.ps1`. Per-app `_ops` toolbox emits
  `Start-App.ps1` / `Stop-App.ps1` / `Start-Environment.ps1` (starts H2Server TCP).
- **Validate a model:** `:NPDevContract:dsl:validateModel -PmodelPath=<p> -PreportOut=<p>`.
- **Quality gates:** `scripts/quality/run-generator-gate.ps1`, `run-runtimehost-gate.ps1`,
  `run-frontend-gate.ps1`, `run-beta-release-gate.ps1`, `run-ai-knowledge-gate.ps1`.
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
  `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs`
  — the sync default dir does NOT match `Build-NpdevApp.ps1`'s default, so pass `-RuntimeHostLibsDir`
  to both or the running app keeps a stale jar.
- **`AppGen\generator-runtime\current`** (the jar cache the AppGen builders read) is not auto-synced;
  refresh via `AppGen\generator-runtime\prepare-npdev-generator-runtime.ps1 -RuntimeRoot D:\WorkSpace\NPDev\AppGen\generator-runtime` (pass `-RuntimeRoot` explicitly).

## Environment notes

- Windows. Prefer **PowerShell** and the dedicated Grep/Glob/Read tools. Git Bash coreutils are on
  PATH via `C:\Program Files\Git\usr\bin` (applies to new sessions).
- Regenerating an app can hit a transient **VS Code Java/Gradle file lock** on the fresh build dir —
  the established workaround is to bump the build-root suffix (`-alt`/`-hNN`); a reboot clears it.

## Key docs

`docs/GETTING_STARTED.md`, `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`,
`docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md`,
`docs/architecture/INTERNAL_DB_SCHEMA_SOURCE_OF_TRUTH.md`, `docs/MATURITY_CLOSURE_LEDGER.md`,
`docs/adr/ADR-0002-box-object-truth-model.md`,
`docs/adr/ADR-0003-code-bearing-panel-procedure-objects.md`.
