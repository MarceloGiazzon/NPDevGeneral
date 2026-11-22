# Subsystem contracts

**Status: current engineering truth.** These are binding rules, not narrative — but each one applies
only when you are working inside its subsystem, so they live here rather than in `CLAUDE.md`, which
is loaded into every model request and every subagent spawn.

`CLAUDE.md` carries the trigger table that points here. If you are touching one of these subsystems,
read its section **before** the first edit.

---

## Monitor + Scrap Manager

Full reference: `docs/MONITOR.md`.

`npdev monitor scan|probe|engine|logs|ops` and
`npdev explore list|show|validate|preflight|run|record|prune|pin|accept|context` are the CLI half of
the Manager's two newest screens. The Tauri commands are thin wrappers — **no CLI behaviour in Rust.**

- **The routine schema is the ENGINE's, pinned** (`schemas/ai/scrapforai-routine.schema.json`).
  Never hand-edit it; re-pin with `scripts/quality/pin-routine-schema.py` against a running engine.
  All 35 routines live under a `browser-routines/` directory and are conformance-checked by
  `check-routine-corpus-conformance.py`.
- **"Green" is one function**, `npdev_explore.evaluate_verdict`. The PowerShell harness and the
  Playwright reporter record THROUGH `npdev explore record` rather than judging for themselves.
- **Records are never deleted.** `explore prune` prunes blobs only, exempts pinned and ledger-linked
  runs, and prints what it kept and why.
- **`probe --include-info`'s `modelSync` field (Wave 4, 2026-09-25) is gated behind include-info
  and a running app on purpose** — it costs a Gradle invocation
  (`:NPDevContract:dsl:canonicalizeModel`, `npdev_cli._canonicalize_model_for_sync`) plus a live
  `X-Super-User-Key`-authenticated POST to `ModelSyncStatusController`
  (`npdev_monitor.model_sync_status`), neither of which belongs in the 30s scan of every app.
  `canonicalizeModel` exists because `ModelSyncStatusController` does no pack resolution itself —
  posting a raw, unresolved `definition/model.json` would report every packs-using app as
  permanently "diverged" (the deployed side is always the pack-RESOLVED form).

## Generated-app filesystem contract

### A generated app keeps its own logs at `<app>\logs\`

`Run-FinalApp.ps1` tees stdout+stderr there, `npdev monitor ops` tees ops-script output there, and
the directory is spared by `Build-NpdevApp.ps1`'s wipe alongside `data`.
`npdev monitor logs export` bundles them with a redacted `resolved-db-plan.json` — **that plan
carries a DB password, so anything that copies it off the machine must go through
`npdev_monitor.redact()`.**

### Regeneration spares exactly three directories, across THREE seams that must agree

`data` (the app's database, PORT-1), `logs`, and `secrets` (the operator-written `agent-proxy.env`
holding a provider API key).

The seams are `Build-NpdevApp.ps1`'s and `Build-ClaudeApp.ps1`'s `$SparedInsideApp`, and
`FinalAppAssembler.PRESERVED_APP_DIRECTORIES`. **The Java layer runs LAST, in the same build**, so a
directory added to a PowerShell list and not to the Java one is spared and then deleted again with no
error. Pinned by twin-pair rule `app-secrets-dir-spared-three-seams` (token
`npdev-app-secrets-spared`).

### `.npdev-root` does not identify this repo

A generated FinalApp carries its own marker, so every root resolution pairs it with the module
directories. Only the marker's existence is ever tested; nothing parses its content.
`OperationalRunbookEmitter` writes it beside `_ops`, and `npdev monitor` accepts
`_ops/resolved-db-plan.json` as the alternative half of the pair so apps generated before 2026-08-10
stay discoverable. For "is this a generated app?", use `npdev_monitor.discovery_rule()` rather than
testing the marker yourself.

## The agent proxy

Lets a generated app's `agent-prompter.html` send its composed prompt without the browser ever
holding a key: `AgentProxyController` (`com.finalexec.api`) over the existing fail-closed
`external-ai-http` adapter, which speaks Anthropic and OpenAI alongside nvidia and gemini.

- `GET /api/agent-proxy/config` is open to any authenticated caller.
- `POST /api/agent-proxy/generate` is **SUPERUSER-gated, not ADMIN** — in an `auth.mode=none` app the
  generated `RuntimeContextService` grants ADMIN to every anonymous caller, so ADMIN is no gate at
  all in dev apps.
- The key comes from `<app>\secrets\agent-proxy.env`, which `_ops\Start-App.ps1` and
  `_ops\Run-FinalApp.ps1` load into the app process's environment. Only `agent-proxy.env.example` is
  ever emitted.

**A new supported controller belongs in `com.finalexec.api` and in
`runtime-supported-controllers.json`.** Three enforcement points read that manifest and only that
package satisfies all three: `build.gradle.template`'s compile-exclusion,
`RuntimeControllerAllowlistConfig`'s bean removal, and `run-runtime-surface-evidence.ps1`, which
fails the RuntimeHost gate for a listed controller whose file is not under `com/finalexec/api`.

## RuntimeHost tests naming `com.npdev.generated.`

**They DO run in the gate.** `build.gradle.template`'s exclusion of that test source lives inside the
`generatedRuntimeMountPresent()` guard, firing only when the mount is genuinely absent (a bare
template checkout) — `SchemaImpactControllerTest`, `AgentProxyControllerTest`'s SUPERUSER-guard
test (one of its 10 tests), and their siblings all run for real.

A green `run-runtimehost-gate.ps1` is real evidence these controller guards ran — but still confirm
against a running app for anything the gate doesn't itself assert on.

## Scoped-property cascade

Model `propertyScopes[]` / `properties[]` (Wave 6 / RC-A1), resolved at runtime by
`PropertyResolver` / `DefaultPropertyResolver` (`NPDevKernel/kernel/.../properties/`) against the
built-in `workspace::PropertyValue` concept.

- **Row presence is the is-set signal**: a stored row with a null value is an explicit override; no
  row means inherit from the next scope.
- Exposed over REST by `PropertyResolverController` (`GET/PUT /api/properties[/scopes|/{key}]`), open
  to every authenticated role — the raw generic-CRUD endpoint for `workspace::PropertyValue` stays
  admin-only like every other built-in-pack concept (REG-114).
- The generated admin surface (`scripts/appgen/New-PropertiesAdminPage.ps1` → `properties.html`, one
  section per scope) is the reference consumer.
- **`propertyScopes[]`'s declared order IS resolution order** (most specific first); the implicit root
  scope (no `from`) must be declared last, enforced at compile time (REG-116).

## Storage, dialects and engines

### Dialect-bound SQL goes in ONE package

`NPDevKernel/kernel/.../storage/sql/` (STOR-1). The 41 sites that used to spell `LIMIT ? OFFSET ?` /
`ON CONFLICT` / `jsonb` / `information_schema` inline are now `SqlDialect` calls, and
`scripts/quality/check-dialect-sites.py` fails the moment a new one appears outside that package.
**If the dialect has no method for what you need, add one.**

Pagination returns `PaginationClause`, not a String, because SQL Server binds `(offset, limit)` in the
REVERSED order: a hardcoded `setInt(n, limit); setInt(n+1, offset)` is correct on three engines and
silently returns the wrong page on the fourth. **A paginated query must declare `ORDER BY`** —
enforced on every engine (conformance P3).

### MySQL and SqlServer are supported, but not identical

Supported as of 2026-08-09 (STOR-3, CI run `31296993259`): a generated app boots, serves non-BMP
unicode, paginates, survives a restart, and passes Tier C's four schema-evolution vectors on each —
the same bar Postgres meets, in the same run.

The differences are declared at the point of choice by `npdev engines`:

- **H2 and MySQL COMMIT IMPLICITLY ON DDL**, so `DDL_IN_TRANSACTION` is absent from their
  capabilities and a "nothing persisted" claim is false there (STOR-2). Ask
  `SqlDialects.active().supports(...)` rather than assuming a rollback.
- **SQL Server has no suffix row cap** (boundary B29), so `SqlDialect` has no bare `rowLimit()` any more. `rowLimited(String, long)`
  builds the whole statement and every engine answers it.

`npdev capabilities` prints the matrix, generated from the dialects so it cannot drift.

### The environment toolbox is one script per operation, not one per engine

E15. The five `_ops` operations are emitted BYTE-IDENTICAL for Postgres, MySQL and SQL Server — they
branch on `profile.kind`, and every engine-specific fact (image, env, ready probe, database creation,
quirks) comes from `DockerEngineProfile` / `npdev/engine-profiles.json`. **Adding an engine is a row
in that JSON, never a sixth branch.** `check-engine-parity.py` fails the moment an engine is
special-cased without its siblings; `run-engine-toolbox-parity.py` generates all three and diffs the
result.

## AI knowledge substrate

Durable platform findings live as `NPDevMcp/knowledge/cards/*.json` (schema
`schemas/ai/knowledge-card.schema.json`). `NPDevMcp/knowledge/platform-status.json` is a **derived** projection
of the gaps ledger — regenerate via `scripts/ai/extract_platform_status.py`, never hand-edit.

`scripts/ai/build_knowledge.py` fans these plus the golden scenarios into `<Build>/npdev-ai/`
(`rag-index.json`, `failure-index.json`, `capabilities.json`) that the MCP tools
`npdev_search_examples` / `npdev_search_fix` / `npdev_check_support` consume.

See `docs/ai/AI_KNOWLEDGE_LOOP_AND_TOOLING_PLAN.md`.

## Nightly model-scale ladder (ledger `SCALE-1`)

`scripts/proofs/run-scale-proof.ps1` synthesizes a deterministic model
(`synthesize_scale_model.py`) at 26/50/100/260/520 concepts and drives
synthesize→generate→ddl→build→boot→firstRequest→latency→memory. All 8 measurements are written to
`scripts/policy/scale-proof-baseline.json` (schema `schemas/ai/scale-proof-report.schema.json`).
Wired to run nightly via `.github/workflows/nightly-scale-ladder.yml`. H2 only.

**There is a hard ceiling at 255 concepts** (SCALE-2): `GeneratedConceptCrudController` took one
constructor parameter per concept, and the JVM caps a method at 255 (JVMS 4.3.3), so 255+ concepts
emitted an app that did not *compile*.

**If this ladder is red, open the artifact** (`scale-proof-rung-<n>` →
`Output/App/scale-proof-*.log`) before assuming the runner is at fault.

## Manual verification scripts, not wired into any gate

- `scripts/quality/run-boundary-lock-check.ps1` — controller / deprecated-schema-alias classification
  vs. reality. Run by hand after adding a controller class.
- `scripts/proofs/run-item20-postgres-proof.ps1` — a one-off real-Postgres repro for a specific closed
  item, kept for re-running against a suspected regression.
- `scripts/proofs/classify_runtimehost_sources.py` — standalone app-coupled vs app-independent split
  of `NPDevRuntimeHost/src/main/java`, mirroring the inline Groovy heuristic in
  `build.gradle.template` as a re-runnable artifact.
- `scripts/quality/generate-pack-catalog.py` — regenerates the static built-in-pack catalog JSON that
  `npdev pack search` reads offline. Re-run by hand after adding, removing, or editing a pack under
  `NPDevContract/packs/`.

## Verification and tooling recipes (moved out of CLAUDE.md, 2026-11-22)

CLAUDE.md is re-billed on every request, so these long recipes live here. The HARD RULES they elaborate stay in
CLAUDE.md in one line each. The text below is verbatim from CLAUDE.md as it stood on 2026-11-22.

### Archives for review (zips)

- **Source-code-only archive:** `pwsh -NoProfile -File scripts/release/New-SourceZip.ps1` (`-ListOnly`
  to preview). Enumerates via `git ls-files`, filters through
  `scripts/policy/source-zip-manifest.json`, writes to `<BuildRoot>\source-zip\`.
  **To change what ships, edit the manifest — the script holds no path knowledge.**
- **Code+docs review snapshot:** `pwsh -NoProfile -File scripts/release/New-CodeDocsZip.ps1`
  (`-ListOnly` to preview). Same `git ls-files` + manifest pattern as `New-SourceZip.ps1`
  (`scripts/policy/code-docs-zip-manifest.json`), writes to `<BuildRoot>\code-docs-zip\`, but the
  TREE listing it emits (alongside the zip, and embedded as `TREE.txt` inside it) always names
  **every** tracked file — scripts, ledger items, test classes included — while only files matching
  a `contentInclude` pattern (production `src/main/`, schemas, packs, the CLI/MCP/Manager top-level
  source, current `docs/`) get their actual bytes staged into the zip. `-IncludeScripts` also zips
  `scripts/` (manifest's `optionalContentInclude.scripts`, off by default). `-EmitTextBundle` also
  writes one big `<name>.bundle.txt` (embedded as `BUNDLE.txt` too) — the TREE listing followed by
  every zipped file's content concatenated, a single paste-into-an-LLM document with no unzip step.
  `-PrefaceFile <path>` prepends a chosen doc (e.g. `docs/NPDEV_CONCEPTS_DEEP_DIVE.md`) ahead of the
  TREE, embedded as `PREFACE<ext>` in the zip either way — point it at current, verified `docs/`
  content, never an external/unversioned vision doc that could feed an AI stale claims as current.

### Which checks to run after a change

- **Which checks to run after a change (FORM P1, start here):** every check in `scripts/quality/verification-cadence.json` carries
  a `level` (core / standard / extended / release / paperwork), `protects`, path `triggers` and `costSeconds`;
  `scripts/policy/local-test-profile.json` `checkLevel` (default `low` = core + standard) says how much to run.
  `python scripts/quality/select_checks.py` prints ONLY the checks the changed paths need (docs-only change: none; DSL + schema change:
  about 6 checks, ~7 min). `--level high` adds the extended level (whole gates, once a day or at the end of a phase), `--level full` adds
  release + paperwork; `--list-levels` shows the map, `--json` is for scripts. **To RUN the selection instead of just printing it:**
  `python scripts/quality/run_selected.py [--level L] [--dry-run]`, or its two spellings `pwsh -NoProfile -File scripts/quality/run-all-gates.ps1
  -Level L` and `npdev verify --level L` (each registry entry's `run` argv; entries sharing one command run once; `run: null` = manual/CI-only,
  listed as NOT RUN, never counted green; bare `run-all-gates.ps1` is unchanged; `npdev verify --audit` lists checks that cost > 60 s and have
  not failed in 120 days as demotion candidates — report only, the owner decides). Run the selected checks and the touched module's own tests,
  not a bare `run-all-gates.ps1`. Tier (staleness deadline) and level (cost) are separate: keep both on every registry entry.

### Quality gates -- what "all gates green" means

- **Quality gates — "all gates green" means ONE command:**
  `pwsh -NoProfile -File scripts/quality/run-all-gates.ps1` (T2). It runs **four** gates by default,
  in this order, and keeps going past a failure so you see every red in one run:
  `run-generator-gate.ps1` (codegen engine; also checks the dsl/generator coverage ratchet)
  → `run-runtimehost-gate.ps1` (assembled sample app + its suite; + RuntimeHost coverage ratchet)
  → `run-kernel-quality-gate.ps1` (`kernelQualityGate`: `:kernel:test` + all 36+ `:adapters:*:test`,
  then the kernel aggregate ratchet) → `run-ai-knowledge-gate.ps1` (mostly static,
  but some checks generate and boot probe apps and it runs the NPDevCli suite -- never run a build
  alongside it; hosts 49 of the 63 `scripts/quality/check-*.py` across 57 numbered checks, the last
  being `check-ui-i18n.py`, and step 56 `run-portable-tooling-check.ps1`, the scan the Linux CI maturity job runs, so a drive-letter
  string in `NPDevCli` is red in T2 and not only in CI).
  **Twelve checkers are not in it:** `check-dsl-reference-output-floor.py` runs in
  `run-generator-gate.ps1` (needs a build), `check-a11y-baseline.py` runs only in
  `run-shell-scenarios.ps1`, and ten run only in `run-weekly-paperwork-checks.ps1`
  (`-IncludePaperwork`): `check-adr-decision-implementation`, `check-allowlist-citations`,
  `check-cadence-coverage`, `check-doc-inventory`, `check-external-ai-mission-coverage`,
  `check-markdown-links`, `check-no-markdown-reads`, `check-pinned-download-links`,
  `check-readme-contract`, `check-record-surfaces`. So a bare "all gates green" does NOT run the
  doc-inventory, no-markdown-reads, record-surfaces or cadence-coverage checks -- run them by hand
  (or with `-IncludePaperwork`) when you touch docs, policy JSON or `CLAUDE.md`.
  **Budget ~13.5 min for the first three gates, plus ~3 min for kernel.**
  Two more gates are **deferred by default**: `-IncludeReleaseGate` (or `-Only betaRelease`) for
  release posture, and `-IncludePaperwork` (or `-Only weeklyPaperwork`) for the 11 checkers
  `run-weekly-paperwork-checks.ps1` hosts. Run one gate with `-Only aiKnowledge`.
  **The bare no-args command covers the four T2 gates only** — the other two are each one flag away,
  never silently included. Every gate it knows about is declared in
  `scripts/quality/verification-cadence.json` (`check-cadence-coverage.py` fails if one is not).
  **Never report "gates green" from a single gate.** A new `scripts/quality/check-*.py` MUST be
  invoked by some `run-*.ps1`; `run-script-inventory-check.ps1` fails otherwise.

### Generator determinism

- **Generator determinism IS checked, and the checker is easy to miss.** It is
  `scripts/hygiene/check-deterministic-generation.ps1` — note `hygiene`, not `quality`, and
  `deterministic`, not `determinism`, which is why a `*determinism*` search finds nothing and
  wrongly concludes it does not exist. It generates one sample twice, SHA-256s every emitted file
  under `ArtifactNP/` + `App/`, and fails naming the differing paths. Declared exclusions:
  `npdev-build-info.properties` and `generation-run.json`. `run-generator-gate.ps1` runs it.

### Faster mid-plan verification (T1 fast gate)

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

### Browser scenarios, journeys, probes, showcase, roadmap tools, machine policy

- **Five golden browser scenarios (W1.7):** `scripts/quality/run-shell-scenarios.ps1` generates,
  builds and boots `NPDevSamples/dsl-conformance-max` once and drives list/detail/workbench/
  long-running-flow/permission-denied through it in a real headless browser (ScrapForAI), asserting
  each `NPDevSamples/scripts/browser/browser-routines/*-verify-routine.json` is green. Boots a real
  app, so it is T1/T2 (`shell-scenarios-golden-browser` in `verification-cadence.json`), never the
  static `aiKnowledge` gate.
- **Identity journeys (JRN-1):** `pwsh -NoProfile -File scripts/quality/run-journeys.ps1` boots
  `NPDevSamples/journeys` (identity pack, jwt) on a wiped DB with a local SMTP sink and drives signup,
  password reset, invitation, change-password, sign-out and session revocation over REST, then the
  login and reset-password pages in a real browser (`-SkipBrowser` opts out). T1/T2
  (`jrn-journeys`), never the static gate; refuses a taken port (8190 app, 2526 sink).
- **Behaviour probes (ENF-1, "accepted implies enforced"):** `pwsh -NoProfile -File
  scripts/quality/run-behavior-probes.ps1` boots `dsl-conformance-max` on a wiped DB (port 8120) and runs
  the table-driven probes in `NPDevSamples/dsl-conformance-max/behavior-probes/*.json`. T1/T2
  (`enf-behavior-probes`). A probe carrying `knownFailure: REG-nn` is green only while it still fails.
  Its static half, `check-dsl-behavior-witness.py` (aiKnowledge gate), needs every keyword of the tracked
  schema families witnessed by a probe's `keywords`, by a `generationWitnesses` test (generation-time-only keywords) or allowlisted in
  `scripts/policy/dsl-behavior-witness.json` (ratchet: `frozenCount` only shrinks). `-Explore` holds the booted app up for authoring probes.
- **F6 frontend showcase (layoutSlot, operator console, dashboard, third nesting level, client-side
  expressions):** `pwsh -NoProfile -File scripts/quality/run-f6-showcase.ps1` generates
  `NPDevSamples/f6-showcase` into `<BuildRoot>/samples/f6-showcase` (never into the repo), boots it on 8110
  against a freshly wiped database and drives its five `f6-*-verify-routine.json` browser routines in a fixed
  order. Boots a real app, so T1/T2 (`f6-showcase-browser` in `verification-cadence.json`), never the static
  `aiKnowledge` gate. Refuses to run if port 8110 is taken and never kills a process it did not start.
- **Roadmap reporting/publishing tools, all manual, none gates:** `python
  scripts/quality/emit-capability-matrix.py` (Session 12) emits the app × capability matrix with a
  witness column (`--out <dir>` for `matrix.json`/`matrix.html`, default
  `<BuildRoot>/reports/capability-matrix/`) — deliberately a report, never a gate; a witness hole is
  a human/future-session decision, not a build failure. `python
  scripts/pack-repo/publish-pack-repo.py` (Session 5) publishes the built-in packs
  (`NPDevContract/packs/<id>/pack.json`) into a versioned local git repo (default
  `Build/npdev-pack-repo`, idempotent per-version tag `<packId>-<version>`) so a model can consume
  them by `packs[].from` remote coordinate instead of a local `$ref` — the coordinate needs the
  `//packs/<id>` subpath (`git+file:///<repo>//packs/<id>@<id>-<version>`, leading slash before the
  drive letter) since that's where the publisher lays each pack out, then `python
  NPDevCli/npdev_cli.py pack update --model <path> --allow-unsigned` resolves and writes
  `npdev.lock`. `python scripts/pack-repo/generate-pack-rules-sheet.py <packId>` (DOM-0) renders one pack as a single reviewable page of business rules (concepts, lifecycles, invariants, who may read/write, derived views, and the pack's own `metadata.reviewNotes` gaps) into `<BuildRoot>/reports/pack-rules-sheets/`, the sign-off artifact for each domain pack. `python scripts/load/seed.py --target sales --volume 10000 --key rep=.. --key mgr=..` then `python scripts/load/run_load.py --target sales --users 8 --seconds 30 --key ..` (LOAD-1..3) seed a booted consumer sample (`NPDevSamples/dom-sales`, port 8421) through its REST API and drive the weighted scenarios in `NPDevSamples/load/workload.json` (stdlib Python; k6 can read the same file later), writing a p50/p95/p99 report to `<BuildRoot>/load/` and exiting 1 on a broken threshold; 10k/100k rows, a 1M seed needs direct SQL. `python scripts/docs/capture-capability-docs.py` (Session 13) points at a running
  app and a route list, captures screenshots via the `verify-in-browser` skill's ScrapForAI harness,
  and renders an HTML capability doc — reusable across capabilities, not bespoke per feature.
- **Local machine resource policy:** `scripts/policy/local-test-profile.json` (read via
  `scripts/quality/test_profile.py`) declares a `checkLevel` and which DB engines are enabled on
  THIS machine — default `enabledEngines: [h2, sqlserver]`, so Postgres/MySQL/Docker are OFF for
  local/agent work. `PostgresTestSupport.dataSource()` and `run-item20-postgres-proof.ps1` /
  `run-docker-linux-proof.ps1` check it and skip cleanly instead of pulling Docker.
  **`CI=true` always bypasses the profile**, so CI coverage is untouched. To opt in for one run:
  `NPDEV_TEST_PROFILE_ENGINES=postgres`, or `-Force` on the two proof scripts, or edit the file.

### Session prep and close, skills, restaging jars, the AppGen jar cache

- **Session prep (cheap, run before a large session):**
  `pwsh -NoProfile -File scripts/ai/Prepare-Session.ps1` — refreshes the symbol map and knowledge
  index and prints a one-screen orientation: the previous session's handoff (what was done, the
  exact next step), branch, dirty files, stale worktrees, open ledger items.
- **`/prep`, `/close`, `/hist`** (`.claude/commands/`) are the shortcuts — prefer them over typing
  the scripts. The `SessionStart` hook (`scripts/hooks/session-start-handoff.py`) already injects
  the last handoff automatically, so **a bare "continue" means the `NEXT` line you were given at
  startup.** `NEXT` is also the **default action after a `/prep`** — state it and start it,
  no permission round-trip, after checking it was not already finished late in the last
  session. If for any reason it is absent, run `/prep` — never reconstruct the resume point by
  reading files or git history, which is the expensive habit this replaces.
- **Session close — write the handoff BEFORE `/clear`:**
  `pwsh -NoProfile -File scripts/ai/Close-Session.ps1 -Summary "..." -NextStep "..." [-Plan ... -Blocked ... -Verified ...]`.
  Writes `ledger/session-state/current.json` (schema `schemas/ai/session-handoff.schema.json`,
  read back at startup) and appends to `history.jsonl`. **It is in-repo and versioned on purpose** —
  it is structured data under `ledger/`, not a process document, and its git history is the record
  of how the project was actually worked. Still never add a `CURRENT_IMPLEMENTATION.md`: prose
  status files are what the ban targets. Ending a session is only cheap if resuming is reliable.
- **Maintainer skills** (tracked under `.claude/skills/`): `rebuild-app` (three-cache refresh via
  `scripts/appgen/Rebuild-And-Restage.ps1`) and `verify-in-browser` (ScrapForAI).
- **After changing kernel/adapter Java, restage jars before regenerating an app:**
  `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars`. Both defaults now resolve to
  `D:\WorkSpace\NPDev\Build\runtimehost-libs` via `Get-NPDevRuntimeHostLibsDir`. Passing
  `-RuntimeHostLibsDir` explicitly still wins; prefer `scripts/appgen/Rebuild-And-Restage.ps1`,
  which threads one value through every step.
- **`AppGen\generator-runtime\current`** (the jar cache the AppGen builders read) is not auto-synced;
  refresh via `AppGen\generator-runtime\prepare-npdev-generator-runtime.ps1 -RuntimeRoot D:\WorkSpace\NPDev\AppGen\generator-runtime`.

### Repo-root resolution and what regeneration spares

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

### Adding a new top-level model array field (the four places)

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
