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
  All 42 routines live under a `browser-routines/` directory and are conformance-checked by
  `check-routine-corpus-conformance.py`.
- **"Green" is one function**, `npdev_explore.evaluate_verdict`. The PowerShell harness and the
  Playwright reporter record THROUGH `npdev explore record` rather than judging for themselves.
- **Records are never deleted.** `explore prune` prunes blobs only, exempts pinned and ledger-linked
  runs, and prints what it kept and why.

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
template checkout) — `SchemaImpactControllerTest`, `AgentProxyControllerTest`'s 9 SUPERUSER-guard
tests, and their siblings all run for real.

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
- **SQL Server has no suffix row cap**, so `rowLimit()` throws (boundary B29). Call `rowLimited()`,
  which every engine answers.

`npdev capabilities` prints the matrix, generated from the dialects so it cannot drift.

### The environment toolbox is one script per operation, not one per engine

E15. The five `_ops` operations are emitted BYTE-IDENTICAL for Postgres, MySQL and SQL Server — they
branch on `profile.kind`, and every engine-specific fact (image, env, ready probe, database creation,
quirks) comes from `DockerEngineProfile` / `npdev/engine-profiles.json`. **Adding an engine is a row
in that JSON, never a sixth branch.** `check-engine-parity.py` fails the moment an engine is
special-cased without its siblings; `run-engine-toolbox-parity.py` generates all three and diffs the
result.

## AI knowledge substrate

Durable platform findings live as `knowledge/cards/*.json` (schema
`schemas/ai/knowledge-card.schema.json`). `knowledge/platform-status.json` is a **derived** projection
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
