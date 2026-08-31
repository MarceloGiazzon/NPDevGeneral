# NPDev_General — repo guide for Claude

NPDev is a model-driven app platform: author a JSON model → generate a Spring Boot "FinalApp" →
build/run it. This repo holds the platform (DSL, generator, kernel, runtime-host template, editor);
app definitions and build output live **outside** it (see Layers below).

Rules live here. The reasoning behind the hard-won ones lives in
[`docs/archive/PLATFORM_HISTORY.md`](docs/archive/PLATFORM_HISTORY.md) — read it when you want to
know *why* a rule exists, never for current status.

## Session economics

This file is re-billed on every request, in this session and in every subagent. So is everything
you pull into context. Cost is `requests × average context`; both multiplicands are yours to control.

- **Never prefix `cd`** — the Bash tool's working directory persists across calls. Measured
  2026-08-30: 9,016 of 19,221 shell calls opened with a redundant `cd`, in five path spellings.
- **Batch independent shell work into one call.** Three `&&`-chained commands cost one context
  re-read; three calls cost three.
- **`Grep` to a line, then `Read` with `offset`/`limit`.** Never a bare `Read` on a file over ~500
  lines. 40% of reads were unscoped.
- **Never re-read a file you already read this session** unless you edited it with something other
  than Edit/Write. There were 2,443 redundant same-session re-reads.
- **Never poll in the foreground.** No `sleep`/`true`/`echo waiting` loops — use `run_in_background`
  and let completion re-invoke you, or one blocking wait with a real condition.
- **Long or noisy commands go through the digest runner**, so the log lands on disk and only the
  verdict enters context: `python scripts/ai/run_digest.py -- <command>` (see Build/run/test).
- **Ask before spawning a subagent** unless the user requested one. Subagents were 42% of spend.

The status line (`scripts/ai/session_meter.py`, wired via `.claude/settings.json`) shows the live
context size and tells the *user* when to `/clear`. It renders in the terminal and never enters the
context, so it costs nothing per turn — which is why the advice lives there and not in this file.

## Critical: where things go

- **Build output → `D:\WorkSpace\NPDev\Build`. NEVER write generated/build artifacts inside this
  repo.** (`docs/BUILD_OUTPUT_LOCATION_POLICY.md`)
- **Evidence / scratch → `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`**, not the repo.
- **Process documents are BANNED from this repo — do not write one.** A plan, checklist, findings
  log, handoff, retrospective, session digest, snapshot or status register is your WORKING STATE,
  not documentation. It goes to the evidence directory above, or into `ledger/` as structured data.
  Enforced by `scripts/quality/check-doc-inventory.py` against
  `scripts/policy/doc-inventory-policy.json`; the `legacy` list of pre-ban files may only **shrink**.
  Durable machine truth → `ledger/*.yml` with a schema. Durable human docs → `docs/`, few, curated,
  generated where possible, indexed in `docs/README.md`. Everything else → outside the repo.
- **NO SCRIPT MAY READ A `.md` FILE — and the exemption list may never grow.** Markdown is output
  for humans; facts a script needs live in JSON/YAML. Enforced by
  `scripts/quality/check-no-markdown-reads.py` against `scripts/policy/markdown-read-exemptions.json`.
  Exactly **5 exemptions** remain, all markdown *linters*, pinned by `frozenCount: 5` — the checker
  fails if the list grows, and equally if it shrinks without `frozenCount` being lowered in the same
  commit, so the ceiling only ratchets down. **A 6th exemption is an owner decision, never a checker
  change** — the correct answer to a new markdown read is to invert it into structured data.
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
| `NPDevKernel/kernel` | Java | Runtime: `KernelRunner` (also hosts the durable flow engine — `docs/FLOWS.md`), `FlowEngine` port, CapabilityDispatcher, EventStore |
| `NPDevKernel/adapters/*` | Java | Pluggable adapters, `*-inproc` (dev) / `*-postgres` (prod) pairs; plus `runtime-support`, `auth-context-jwt`, `authz-default`, `persistence-postgres`, … |
| `NPDevRuntimeHost` | Java/Spring | Spring Boot template **copied into every generated FinalApp** — not a built product subproject. Login/bootstrap/ControlPanel controllers live here (`com.finalexec.*`) |
| ~~`NPDevEditor/ui-react`~~ | — | **REMOVED** (2026-08-17/20). A generated app no longer serves `/npdev-ui-react/`. Replacement is `static/model-authoring.html`, emitted by `ModelAuthoringEmitter`. See `BREAKING.md`. |
| `NPDevSamples` | JSON/PS1 | Reference sample apps + browser-verification harness |
| `NPDevCli` / `NPDevMcp` | Python | Model-validation CLI / MCP server for AI authoring |
| `golden-ai-scenarios`, `schemas/ai` | JSON | AI safety/verification fixtures + schemas |

Package roots: `com.npdev.dsl.v1` / `com.npdev.generator` / `com.npdev.kernel` / `com.finalexec`.

## Large files — DO NOT full-read (Grep to a line, then Read with offset/limit)

Reading any one whole burns 40–100k tokens. The authoritative list is
`scripts/policy/record-surfaces.json` — `scripts/quality/check-record-surfaces.py` fails if a
declared size drifts more than ±25%. This prose list is a summary of that JSON, not itself read by
anything:

- `NPDevGenerator/.../npdev-templates/business-ui-app.mustache` (169 KB)
- `NPDevKernel/adapters/runtime-support/.../GeneratedCrudRuntimeSupport.java` (158 KB)
- `NPDevRuntimeHost/.../db/SchemaLifecycleExecutor.java` (138 KB)
- `NPDevKernel/kernel/.../KernelRunner.java` (126 KB)
- Big JSON that is noise if read whole: `NPDevSamples/NPDevSamples_Tree.txt`, and test fixtures
  `NPDevRuntimeHost/src/test/resources/npdev/compiled-metadata.json` / `metadata/fields.manifest.json`

**Faster than any of this:** `python scripts/ai/build_symbol_map.py` emits a symbol index of the
hot files (class/def name → line number) to the Build root. Grep that, then `Read(offset, limit)`.

`SemanticValidator.java` (10 KB) and `TrustedSourceEmitter.java` (11 KB) are **no longer large** —
both are now orchestrators over sibling classes. Grep the package, not the file.

**`model.schema.json` is duplicated in 4 places** — edits must mirror to all four:
`NPDevContract/schemas/model.schema.json`, `NPDevContract/schemas/authoring/model.schema.json`,
`NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
`NPDevContract/dsl/resources/Schemas/model.schema.json` (NOT `schemas/archive/`).
Verify with `python scripts/quality/check-schema-mirror-consistency.py` — the four copies are
**semantically** identical, not byte-identical; the `dsl/resources/Schemas/` copy carries
`canonicalSchema` and `deprecated` keys the checker excuses. Never compare by file hash.

## Build / run / test

- **Root Gradle wires only `:NPDevContract:dsl` (+ `:core`).** Other modules build via their own paths.
- **Generate + build + run a FinalApp:** `scripts/appgen/Build-NpdevApp.ps1` (AppGen apps),
  `Build-ClaudeApp.ps1` (Claude Support Desk), `Build-AppGenApp.ps1`. Per-app `_ops` toolbox emits
  `Start-App.ps1` / `Stop-App.ps1` / `Start-Environment.ps1` (starts H2Server TCP).
- **Validate a model:** `:NPDevContract:dsl:validateModel -PmodelPath=<p> -PreportOut=<p>`.
- **Source-code-only archive:** `pwsh -NoProfile -File scripts/release/New-SourceZip.ps1` (`-ListOnly`
  to preview). Enumerates via `git ls-files`, filters through
  `scripts/policy/source-zip-manifest.json`, writes to `<BuildRoot>\source-zip\`.
  **To change what ships, edit the manifest — the script holds no path knowledge.**
- **Wrap long/noisy runs in the digest runner** so the full log goes to disk and only the verdict
  enters context: `python scripts/ai/run_digest.py -- pwsh -NoProfile -File scripts/quality/run-all-gates.ps1`.
  Patterns live in `scripts/policy/output-digest-policy.json`; add a family there, never in the script.
- **Quality gates — "all gates green" means ONE command:**
  `pwsh -NoProfile -File scripts/quality/run-all-gates.ps1` (T2). It runs **four** gates by default,
  in this order, and keeps going past a failure so you see every red in one run:
  `run-generator-gate.ps1` (codegen engine; also checks the dsl/generator coverage ratchet)
  → `run-runtimehost-gate.ps1` (assembled sample app + its suite; + RuntimeHost coverage ratchet)
  → `run-kernel-quality-gate.ps1` (`kernelQualityGate`: `:kernel:test` + all 36+ `:adapters:*:test`,
  then the kernel aggregate ratchet) → `run-ai-knowledge-gate.ps1` (static — no build, no boot;
  hosts 40 of the 42 `scripts/quality/check-*.py` across 39 numbered checks).
  **Two checkers are not in it:** `check-dsl-reference-output-floor.py` runs in
  `run-generator-gate.ps1` (needs a build), and `check-external-ai-mission-coverage.py` runs only in
  `run-weekly-paperwork-checks.ps1`.
  **Budget ~13.5 min for the first three gates, plus ~3 min for kernel.**
  Two more gates are **deferred by default**: `-IncludeReleaseGate` (or `-Only betaRelease`) for
  release posture, and `-IncludePaperwork` (or `-Only weeklyPaperwork`) for the 11 checkers
  `run-weekly-paperwork-checks.ps1` hosts. Run one gate with `-Only aiKnowledge`.
  **The bare no-args command covers the four T2 gates only** — the other two are each one flag away,
  never silently included. Every gate it knows about is declared in
  `scripts/quality/verification-cadence.json` (`check-cadence-coverage.py` fails if one is not).
  **Never report "gates green" from a single gate.** A new `scripts/quality/check-*.py` MUST be
  invoked by some `run-*.ps1`; `run-script-inventory-check.ps1` fails otherwise.
- **Generator determinism IS checked, and the checker is easy to miss.** It is
  `scripts/hygiene/check-deterministic-generation.ps1` — note `hygiene`, not `quality`, and
  `deterministic`, not `determinism`, which is why a `*determinism*` search finds nothing and
  wrongly concludes it does not exist. It generates one sample twice, SHA-256s every emitted file
  under `ArtifactNP/` + `App/`, and fails naming the differing paths. Declared exclusions:
  `npdev-build-info.properties` and `generation-run.json`. `run-generator-gate.ps1` runs it.
- **Faster mid-plan verification:** `scripts/quality/run-fast-gate.ps1` is the T1 tier — T0's checks
  plus generate+build+boot+REST-smoke of the frozen canary app (`NPDevSamples/npdev-canary`) and
  three T1-scoped corpus checks. **Measured ~4.4 min.** Use it at the end of a wave/step, not as a
  substitute for T2 before closing a Move.
  **If the canary reports `health: failed` with "connection refused", read the boot log before
  believing it.** The app starts in ~24 s; the rest is `gradlew --no-daemon bootRun` forking a
  single-use daemon. Default `-CanaryBootTimeoutSeconds` is 300 for that reason. A genuinely crashed
  app reports "Process exited before health check passed" — a different message and a real failure.
  `npdev verify --tier T0|T1|T2|T3` is the one CLI entry point for all four tiers, reading the same
  staleness ledger every tier writes to (`verification-cadence.json` + `cadence_state.py`) — a check
  past its `maxStaleness` shows as a blocking OVERDUE line, never a silent skip.
- **Local machine resource policy:** `scripts/policy/local-test-profile.json` (read via
  `scripts/quality/test_profile.py`) declares a `checkLevel` and which DB engines are enabled on
  THIS machine — default `enabledEngines: [h2, sqlserver]`, so Postgres/MySQL/Docker are OFF for
  local/agent work. `PostgresTestSupport.dataSource()` and `run-item20-postgres-proof.ps1` /
  `run-docker-linux-proof.ps1` check it and skip cleanly instead of pulling Docker.
  **`CI=true` always bypasses the profile**, so CI coverage is untouched. To opt in for one run:
  `NPDEV_TEST_PROFILE_ENGINES=postgres`, or `-Force` on the two proof scripts, or edit the file.
- **Session prep (cheap, run before a large session):**
  `pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1` — refreshes the symbol map and knowledge
  index and prints a one-screen orientation (branch, dirty files, open ledger items, last gate
  result). Cuts the discovery calls a cold session would otherwise spend.
- **Maintainer skills** (tracked under `.claude/skills/`): `rebuild-app` (three-cache refresh via
  `scripts/appgen/Rebuild-And-Restage.ps1`) and `verify-in-browser` (ScrapForAI).
- **After changing kernel/adapter Java, restage jars before regenerating an app:**
  `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars`. Both defaults now resolve to
  `D:\WorkSpace\NPDev\Build\runtimehost-libs` via `Get-NPDevRuntimeHostLibsDir`. Passing
  `-RuntimeHostLibsDir` explicitly still wins; prefer `scripts/appgen/Rebuild-And-Restage.ps1`,
  which threads one value through every step.
- **`AppGen\generator-runtime\current`** (the jar cache the AppGen builders read) is not auto-synced;
  refresh via `AppGen\generator-runtime\prepare-npdev-generator-runtime.ps1 -RuntimeRoot D:\WorkSpace\NPDev\AppGen\generator-runtime`.
- **NEVER resolve the repo root by its directory NAME, and never hardcode `D:\WorkSpace\...` as a
  default** (REG-144). GitHub checks this repo out as `NPDevGeneral`, so any walk looking for a
  directory literally named `NPDev_General` falls through to its own fallback — and eleven such
  copies produced three different build roots in one checkout. Identify the root by its CONTENTS
  (the directory holding `NPDevContract` + `NPDevGenerator` + `NPDevKernel`), the predicate
  `WorkspaceRootLocator.java` established. The eleven copies are pinned by twin-pair rule
  `build-root-resolution-eleven-place`: each carries an `npdev-build-root-resolution` token, and
  dropping it from any one fails `run-ai-knowledge-gate.ps1`. A script default needing the repo root
  should use `$PSScriptRoot`-relative arithmetic or **call** `Get-NPDevBuildRoot` /
  `Get-NPDevRuntimeHostLibsDir` — never repeat their answer as a literal.
- **Regeneration spares exactly three directories** — `data`, `logs`, `secrets` — and the list lives
  in THREE seams that must agree (`Build-NpdevApp.ps1` / `Build-ClaudeApp.ps1` `$SparedInsideApp`,
  and `FinalAppAssembler.PRESERVED_APP_DIRECTORIES`). The Java layer runs LAST, so a directory added
  to a PowerShell list and not to the Java one is spared and then deleted again with no error. Pinned
  by twin-pair rule `app-secrets-dir-spared-three-seams`.
- **Dialect-bound SQL goes in ONE package** (`NPDevKernel/kernel/.../storage/sql/`, STOR-1).
  `check-dialect-sites.py` fails the moment `LIMIT ? OFFSET ?` / `ON CONFLICT` / `jsonb` /
  `information_schema` appears outside it. If the dialect has no method for what you need, add one.

## When you touch a subsystem, read its contract first

These are binding rules kept out of this file because each applies only inside its own subsystem.
[`docs/maintainers/SUBSYSTEM_CONTRACTS.md`](docs/maintainers/SUBSYSTEM_CONTRACTS.md) — read the
relevant section **before** the first edit.

| Touching | Section |
|---|---|
| `npdev monitor` / `npdev explore`, browser routines, the Manager's Rust wrappers | Monitor + Scrap Manager |
| A generated app's `data` / `logs` / `secrets`, `.npdev-root`, `resolved-db-plan.json` | Generated-app filesystem contract |
| `AgentProxyController`, `external-ai-http`, or **any new controller** | The agent proxy |
| RuntimeHost tests naming `com.npdev.generated.` | RuntimeHost tests |
| `propertyScopes[]` / `properties[]`, `PropertyResolver` | Scoped-property cascade |
| `SqlDialect`, pagination, adding a DB engine, `_ops` toolbox | Storage, dialects and engines |
| `knowledge/cards/*.json`, `platform-status.json`, the MCP search tools | AI knowledge substrate |
| `run-scale-proof.ps1` or a red nightly scale ladder | Nightly model-scale ladder |
| Adding a controller class; a one-off Postgres repro; pack catalog | Manual verification scripts |

## Stability policy

NPDev is pre-1.0 and deliberately unstable (`README.md`'s "Stability policy" and `BREAKING.md`).
**Every breaking change to the model DSL, generated code layout, or internal APIs ships its
`npdev migrate` codemod in the same commit**, plus a one-line `BREAKING.md` entry — never land the
break first and the codemod later.

## Environment notes

- Windows. Prefer **PowerShell** and the dedicated Grep/Glob/Read tools. Git Bash coreutils are on
  PATH via `C:\Program Files\Git\usr\bin`.
- Regenerating an app can hit a transient **VS Code Java/Gradle file lock** on the fresh build dir —
  the workaround is to bump the build-root suffix (`-alt`/`-hNN`); a reboot clears it.

## Where the truth lives (read before filing or editing status)

- **Open items:** `ledger/items/*.yml` is authoritative. The rendered `OPEN_ITEMS.md` view is
  GENERATED (`scripts/quality/generate_open_items.py`) and **not committed** — run the generator if
  you want it; never hand-edit it.
- **Accepted boundaries:** `ledger/boundaries/*.yml` is the structured record;
  `docs/ACCEPTED_BOUNDARIES.md` is the prose companion. Both must be updated together — the doc's
  classification table is what tells you whether a boundary is HITTABLE or POSTURAL.
- **The gaps ledger:** `ledger/gaps.yml` is authoritative for the rendered `OPEN_GAPS_AND_ROADMAP.md`
  view (`scripts/docs/generate_gaps_roadmap.py`) — same never-hand-edit discipline, also not
  committed. `ledger/gaps.yml` is deliberately empty today: it is reserved for cross-cutting gaps
  that do not fit a single ledger item, so per-item gaps live in `ledger/items/*.yml` and a zero
  there does not mean nothing is broken.
- **DSL is at 2.0** — retired `flowStep.type` aliases collapsed to canonical values. See
  `BREAKING.md`. Codemods live in `NPDevCli/dsl_v2_migration.py`.
- **Adding a DSL feature?** Add a real example to `NPDevSamples/dsl-conformance-max` in the same
  commit — `scripts/quality/check-dsl-coverage.py` fails any DSL feature with zero corpus coverage.
- **Adding a new top-level model array field** (like `roles`, `propertyScopes`, `properties`)? It has
  to be threaded through **four** places, or a pack/fragment that declares it gets the field silently
  dropped with no error anywhere (REG-108):
  1. `ModelSourceResolver.MODEL_ARRAY_KEYS` — the JSON-level pack/fragment composer only concatenates
     pack- or fragment-contributed content for keys in this set. A field missing from it is silently
     discarded for anyone but the app's own model root, which has a separate "pass through any
     unrecognized key" fallback — which is why this class of bug hides for a long time.
  2. Both copies of `pack.schema.json` (`NPDevContract/schemas/` and
     `NPDevContract/dsl/src/main/resources/schema/`) — `additionalProperties:false` rejects a pack
     declaring the field before the composer even runs.
  3. `ModelResolver.resolve()` — the AST-level resolver's constructor call must pass the new field
     through to the resolved `ModelAst`.
  4. The canonical JSON writer/reader pair (`CompiledModelCanonicalJson` /
     `CompiledModelCanonicalJsonReader`) — the generator only ever reads canonical JSON.
- **The same field also needs a live path through `JsonModelParser` → `ModelCompiler` → the
  canonical writer/reader pair → `ModelResolver`** even for an app's own model root (REG-104).
  Both this chain and the pack-composition one are enforced by
  `scripts/quality/check-twin-pair-consistency.py` against `scripts/quality/twin-pair-registry.json`.
  **Add a new rule there the next time a "one place updated, its twin forgotten" bug is found.**
- **Citing a `REG-nn`/ledger id as a live blocker?** Check that id's own ledger status BEFORE writing
  "blocked by REG-nn" — a doc calling an id a live blocker after its `ledger/items/REG-nn.yml` says
  DONE/PARTIAL is stale prose. This happened five times.
- **Adding a script under `scripts/`?** It needs a classification (pattern-matched in
  `scripts/policy/script-inventory-policy.json`) and a declared `invocation` in
  `scripts/policy/script-invocation-declarations.json`; `run-script-inventory-check.ps1` enforces both.
- **Adding a corpus model** (`AppGen/apps` or `NPDevSamples`)? It needs a `corpusRole` entry in
  `scripts/quality/corpus-roles.json` (`dsl-fixture` / `engine-variant` / `repro-case` / `showcase`).
- **Frontend contract:** `docs/UI_CONTRACT.md` · screen classes: `docs/SCREEN_TAXONOMY.md` · the
  durable flow engine: `docs/FLOWS.md`.
## Key docs

`docs/README.md` is the full documentation index — start there. Frequently needed:
`docs/GETTING_STARTED.md`, `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`,
`docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md`,
`docs/architecture/INTERNAL_DB_SCHEMA_SOURCE_OF_TRUTH.md`, `docs/maintainers/MATURITY_CLOSURE_LEDGER.md`.
`docs/` root holds only current product/engineering truth; closed programme history lives in
`docs/archive/` and `docs/beta/`, both classified `historical` — read for narrative, never for status.
