# NPDev — Open Gaps, Bugs & Remediation Roadmap

> **Generated:** 2026-07-12 · **Branch at capture:** `beta1-vision-spine`
> **Purpose:** Single authoritative, AI-digestible ledger of every open gap, bug, and unfinished task
> on the NPDev platform, plus a fully-specified remediation roadmap. Each roadmap item carries a
> stable ID and the same six fields (What / Where / Why / How / Definition of Done / Verify) so an
> autonomous agent can pick up any item without re-deriving context.
>
> **Sources of truth this document consolidates:**
> - `C:\Users\Marcelo\.claude\projects\d--WorkSpace-NPDev-NPDev-General\memory\npdev_platform_gaps.md`
> - `d:\WorkSpace\NPDev\NPDev_General\docs\architecture\AGGREGATE_WORKBENCH_PLAN.md`
> - `d:\WorkSpace\NPDev\NPDev_General\NPDevContract\docs\BOND-GAPS-IMPLEMENTATION-PLAN.md`
> - `d:\WorkSpace\NPDev\NPDev_General\docs\MATURITY_CLOSURE_LEDGER.md`
> - Live `git status` / working tree at capture time.

---

## 0. How to read this document (agent instructions)

- Every actionable item has a **stable ID** (`BUG-*`, `ARCH-*`, `BOND-*`, `AW-*`, `HYG-*`). Cite it in
  commits and PRs.
- **Status vocabulary:** `OPEN` (not started) · `PARTIAL` (some slices done, more remain) ·
  `NEEDS-VERIFY` (believed done but must be reconciled against code) · `DONE` (verified live) ·
  `BOUNDARY` (accepted design constraint, not scheduled for a fix).
- **Before acting on any `NEEDS-VERIFY` item, run the "Verify" step first** — several statuses in the
  source plans are stale relative to committed code.
- **Global build/restage rule (applies to every kernel/adapter/generator code change):**
  after changing kernel/adapter/generator Java, restage jars before regenerating an app:
  ```powershell
  scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs
  ```
  The sync default dir does **not** match `Build-NpdevApp.ps1`'s default — pass `-RuntimeHostLibsDir`
  to both or the running app keeps a stale jar.
- **Schema-mirror rule:** `model.schema.json` is duplicated in 4 places; every schema edit must mirror
  to all four:
  - `NPDevContract/schemas/model.schema.json`
  - `NPDevContract/schemas/authoring/model.schema.json`
  - `NPDevContract/dsl/src/main/resources/schema/model.schema.json`
  - `NPDevContract/dsl/resources/Schemas/model.schema.json`
- **Build-output rule:** generated/build artifacts go to `D:\WorkSpace\NPDev\Build`, evidence/scratch to
  `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`. Never inside this repo.

---

## 1. Priority index (machine-parseable)

| ID | Title | Category | Status | Priority | Est. size |
|---|---|---|---|---|---|
| BUG-16 | InMemory apps cannot boot ControlPanel | Runtime bug | DONE | P1 | S |
| BOND-0 | Verify bond foundation is committed | Structural | DONE | P1 | S (verify) / L (if uncommitted) |
| AW-RECONCILE | Reconcile AW phase-status markers against code | Planning | DONE | P2 | S |
| AW-P0b | Aggregate compiled layer + load endpoint | Feature | DONE | P2 | M |
| AW-P1 | AutoPanel primitive + single-concept expansion | Feature | DONE | P2 | L |
| BUG-14 | Shell rewrite broke sample browser routines | Test harness | DONE | P2 | M |
| ARCH-15 | tenant `"default"` silently 403s all flow auth | Runtime footgun | DONE | P3 | S |
| ARCH-8b | Flow field-default application not applied | Runtime footgun | DONE | P3 | M |
| ARCH-10b | Panel dataSource `orderBy` unapplied | Runtime bug | DONE | P3 | S |
| BOND-B2 | Duplicate anchor-resolution in FlywayEmitter | Tech debt | DONE (moot) | P3 | S |
| BOND-B4 | ReleaseGateValidator not CI-wired | Test/CI | PARTIAL (needs your CI-trigger call) | P3 | S |
| BOND-B6 | Cross-pack bond untested end-to-end | Test coverage | DONE | P3 | M |
| BOND-B7 | Pack table-name convention untested | Test coverage | DONE | P4 | S |
| AW-P2 | selectors[]/bandPickers unification + FK auto-Prompt wiring | Feature | PARTIAL (picker unification DONE; FK auto-Prompt re-scoped out) | P4 | S |
| AW-P3 | computed[] client evaluator vs recompute: procedure | Feature | DONE (folded via warning, not evaluator) | P4 | S |
| AW-P5 | Per-state allowedActions gating | Feature | DONE | P4 | S |
| AW-DisplayAll | Validate `display:all` DOM weight / virtualization | Perf risk | DONE (mode never built — moot) | P4 | M |
| HYG-1 | Uncommitted working-tree + unignored dirs | Hygiene | DONE | P3 | S |
| HYG-2 | Uncommitted completed features at risk | Hygiene | DONE (stale memory) | P2 | M |

**Design boundaries (not scheduled — reference only):** `ARCH-7`, `ARCH-loop`, `ARCH-upload`,
`ARCH-compound-unique`, `ARCH-6` (see §6).

---

## 2. Runtime & generator bugs (OPEN)

### BUG-16 — InMemory-storage apps cannot boot the ControlPanel
- **Status:** DONE (fixed 2026-07-12, commit `f78ae60`) · **Priority:** P1 · **Category:** Runtime bug
- **What:** Any FinalApp using **InMemory** storage mode with ControlPanel enabled fails at startup
  with `APPLICATION FAILED TO START` (UnsatisfiedDependency), regardless of app content. H2/Postgres
  apps are unaffected.
- **Where:** `ControlPanelAdminUserController` in
  `NPDevRuntimeHost/src/main/java/com/finalexec/controlpanel/` (package `com.finalexec.controlpanel`).
- **Why:** The controller's constructor hard-requires a `javax.sql.DataSource`. InMemory storage mode
  excludes Spring's `DataSourceAutoConfiguration`, so no `DataSource` bean exists → bean construction
  fails. Passing `--spring.datasource.*` does not help because the autoconfig is excluded. Found
  2026-07-11 running canonical-demo (InMemory) standalone.
- **How to solve:**
  1. Change the `DataSource` dependency to optional: inject `ObjectProvider<DataSource>` or use
     `@Autowired(required = false)`.
  2. When no `DataSource` is present, disable the controller path gracefully (return HTTP 503 /
     "ControlPanel unavailable in InMemory mode") instead of failing construction — **or** gate the
     entire ControlPanel wiring on a JDBC storage profile via `@ConditionalOnBean(DataSource.class)`.
  3. Restage jars (global build/restage rule, §0).
  4. Regenerate + boot an InMemory app with ControlPanel enabled.
- **Definition of Done:** An InMemory FinalApp with ControlPanel enabled **starts successfully**;
  ControlPanel endpoints return a graceful 503 (not a startup crash); H2/Postgres ControlPanel
  behavior is unchanged (regression-checked on WmsOffice).
- **Verify:** Generate canonical-demo (InMemory) standalone → app boots. Generate an H2-backed sample
  (widget-showcase-demo) → ControlPanel still fully functional.

---

### BUG-14 — Shell/GuidePage rewrite broke sample browser routines ✅ DONE (fixed + verified live 2026-07-12)
- **Status:** DONE · **Priority:** P2 · **Category:** Test-harness rot (not product runtime)
- **What was wrong:** (a) `#sideNav` container id was removed when the shell moved to
  `shell.js.mustache` (`npdev-shell-nav-*` classes, no stable id). (b) Create/edit forms now default
  to inline (`formPresentation: "standard"`, rendered in `#concept-<Name>`) instead of the old
  `#modalRoot` popup — every sample routine's `#modalRoot ...` selectors were stale.
- **Fix applied — option (a), not the data-* recommendation:** restored `sidebar.id = "sideNav"` in
  `NPDevGenerator/generator/src/main/resources/npdev-templates/shell.js.mustache` (one line) — the
  individual `<a href="#concept-X">` hrefs were already stable/deterministic, so only the missing
  container id needed restoring; no need to invent a new `data-nav-target` contract. Rewrote all 33
  affected `NPDevSamples/**/browser-routines/*.json` files, replacing every stale `#modalRoot ...`
  selector with the concept-scoped `#concept-<CurrentConcept> ...` equivalent (current concept
  tracked per-step through each routine's own nav sequence — several files touch 2-3 concepts in one
  routine, so this was not a blind find-replace).
- **Verified live:** generated + built + booted `12works/gift-idea-tracker` (H2Local), ran its full
  `01-giftidea-crud` routine via the `NPDevSamples/scripts/browser/scrapforai-harness.ps1` +
  `demonstrate-browser.ps1` pattern — **28/28 steps passed**: nav via `#sideNav a[href="#concept-
  GiftIdea"]`, inline form open/fill/submit via `#concept-GiftIdea form`, grid assertion, re-open
  edit to verify the select-widget FK round-tripped, cancel. The harness's `Assert-RoutineGreen`
  additionally flags any console.error as a failure; it flagged 5 here, all pre-existing and
  unrelated to this fix — a harmless `theme.css` 404 (apps without a custom theme get this by
  design, see [[platform_theming_tokens]]) and `401`s from the very first unauthenticated page load
  before the routine's own `#apiKey` fill+reload step. Not a BUG-14 regression.
- **Not yet re-verified:** the other 32 rewritten files (only `gift-idea-tracker` was run live this
  pass) — the generator-level `#sideNav` fix applies uniformly to all of them and the per-file
  rewrites followed the same verified rule, but a full sweep is still pending.
- **Verify:** `NPDevSamples/scripts/browser/scrapforai-harness.ps1`-based run, as above; full sample
  sweep across all 33 files still open as a follow-up if higher confidence is wanted.

---

### ARCH-15 — tenant id `"default"` is a reserved sentinel that silently 403s all flow auth ✅ DONE (2026-07-12)
- **Status:** DONE · **Priority:** P3 · **Category:** Runtime footgun
- **What was done — both options, not just one:**
  1. `DefaultExecutionAuthorizationPolicy.isRequesterAuthorized` now logs a `WARNING` (`java.util.logging`,
     matching the existing kernel-adapter convention) naming the reserved sentinel and pointing at
     tenant registration, before returning the (unchanged) deny.
  2. `TenantRegistryService.create()` now fails fast with `IllegalArgumentException` (→ HTTP 400,
     already mapped by `TenantAdminController`) when `tenantId` normalizes to `"default"`, so it can
     never be registered as a real tenant in the first place.
- **Tests added:** `DefaultExecutionAuthorizationPolicyTest
  .deniesTenantIdDefaultEvenWithFullRolesAndPermissions` (proves the denial is keyed on the tenantId
  itself, not merely missing roles — a fully-ADMIN-roled requester under literal tenantId `"default"`
  is still denied); `TenantRegistryServiceTest.creatingTheReservedDefaultTenantIdIsRejected`.
- **Bonus fix along the way:** the `TenantRegistryServiceTest` fixture's hand-rolled `CREATE TABLE
  npdev_tenant` was missing the `persistence_mode` column that `list()` selects (added for the
  tenant persistence-mode feature after the test was written) — every test calling `.create()` then
  `.list()` was failing with `Column "PERSISTENCE_MODE" not found`. Fixed the fixture; this was the
  root cause of the `TenantRegistryServiceTest.createThenListReturnsTheTenant` failure noted during
  BUG-16 verification (not a flake).
- **Verify:** `:adapters:authz-default:test` and a generated app's
  `TenantRegistryServiceTest` both green (confirmed live via a freshly generated
  `simple-user-registry-h2local`).

---

### ARCH-8b — Flow `createConcept`/`updateConcept` does not apply field defaults ✅ DONE (2026-07-12)
- **Status:** DONE · **Priority:** P3 · **Category:** Runtime footgun
- **What was done:** both flow-facing persistence adapters — `PostgresPersistenceCapabilityAdapter`
  and `InMemoryPersistenceCapabilityAdapter` (kernel adapters `persistence-postgres`/
  `persistence-inproc`) — now run an `applyFieldDefaults` pass at the top of `save()`, before id
  assignment/lifecycle enforcement: for each field with a declared literal `defaultValue`
  (`CompiledField.getSchema().getDefaultValue()`), if the record omits it or supplies a blank string,
  the default is applied. Caller-supplied values always win. Scoped to literal `defaultValue` only —
  `defaultExpression` (computed from other fields) is **not** evaluated on this path, a smaller,
  explicitly deferred follow-up if needed.
- **InMemory adapter previously had zero `CompiledModel` wiring at all** (not even the lifecycle-
  transition-validation fix from bug #8 — that was Postgres-only). Added a `CompiledModel`-aware
  constructor (mirroring Postgres's existing pattern), added `:dsl` as a `persistence-inproc`
  Gradle dependency (was missing), and wired `compiledModel` into both Spring bean call sites in
  `NpdevPluginConfig.java` that construct it.
- **Tests added:** `PostgresPersistenceCapabilityAdapterNullToleranceTest
  .saveAppliesDeclaredFieldDefaultWhenOmitted`/`.saveDoesNotOverrideAnExplicitlySuppliedValue`,
  same two cases in `InMemoryPersistenceCapabilityAdapterTest`.
- **Verify:** `:adapters:persistence-postgres:check` and `:adapters:persistence-inproc:check` both
  green. Boot-regression-checked live: generated + built + booted a fresh
  `simple-user-registry-inmemory` FinalApp after the `NpdevPluginConfig` bean-signature change —
  `/actuator/health` returned 200 (no regression to the InMemory boot path BUG-16 fixed).

---

### ARCH-10b — Panel dataSource `orderBy` is unapplied ✅ DONE (2026-07-12)
- **Status:** DONE · **Priority:** P3 · **Category:** Runtime bug
- **What was done:** `PanelRuntime.loadDataSource` now applies `applyQueryOrderBy` immediately after
  `applyQueryWhereFilter` (`NPDevRuntimeHost/.../PanelRuntime.java`, same class as the `where`
  post-filter). `orderBy` (`List<String>`) supports a stable multi-field sort; each entry is a plain
  field name (ascending by default) or `"<field> desc"`/`"<field> asc"` (no direction syntax existed
  before this — introduced the SQL-like `desc`/`asc` suffix convention since the schema only declares
  `orderBy` as `string[]` with no separate direction field). Numbers compare numerically, everything
  else lexically as strings; nulls sort last.
- **Tests added:** `PanelRuntimeTest.loadPanelAppliesDeclaredOrderByDescendingNumericField` (pins
  `"priority desc"` on an int field) and `.loadPanelAppliesDeclaredOrderByAscendingByDefault` (pins
  plain `"subject"` on a string field, no suffix) — both build a real `CompiledQuery`/`CompiledPanel`,
  save out-of-order concept rows through `DefaultConceptGateway`, and assert `loadPanel`'s returned
  row order.
- **Verify:** `com.finalexec.PanelRuntimeTest` green (7/7, confirmed live via a freshly generated app
  since `NPDevRuntimeHost` isn't independently buildable).

---

## 3. Bond feature — open implementation gaps

> ⚠️ **Context:** `BOND-GAPS-IMPLEMENTATION-PLAN.md` targets branch
> `beta0-no-false-green-release-hardening`. Current branch is `beta1-vision-spine`. **Run BOND-0
> first** to establish whether the foundation is committed before touching sub-gaps.

### BOND-0 — Verify the bond foundation is committed
- **Status:** DONE (verified 2026-07-12, all 3 gates green; fixed a stale-test drift bug found along
  the way, commit `dcfe007`) · **Priority:** P1 · **Category:** Structural
- **What:** Determine whether the full bond feature (AST/schema/compiled/validation + generator/kernel)
  is committed or still working-tree-only. If uncommitted, committing it is blocking for everything
  else (a `git checkout`/merge would break compilation).
- **Where:** Canonical marker file:
  `NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java`
  (plus `SqlIdentifierSupport.java`, `SqlTypeSupport.java`,
  `CompiledReferenceSemantics.via/onDelete`).
- **Why:** Commits referencing these classes exist, but the classes may live only in the working tree.
- **How to solve:**
  1. `git log --oneline -- NPDevGenerator/generator/src/main/java/com/npdev/generator/bonds/BondModelSupport.java`
     and `git status` the bond file set from the plan.
  2. If uncommitted: execute **Phase 0-A** (contract layer: AST, schema ×4, compiled, validation,
     packs, 7 test classes) then **Phase 0-B** (generator + kernel: `BondModelSupport`, emitters,
     templates, N:M runtime) as two sequential commits — exact file lists in
     `NPDevContract/docs/BOND-GAPS-IMPLEMENTATION-PLAN.md` §Phase 0.
  3. Run the three gates green: `:NPDevContract:dsl:check`, `:NPDevGenerator:generator:check`,
     `:NPDevKernel:adapters:expression-cel:check`.
- **Definition of Done:** `BondModelSupport.java` and the full bond file set are tracked in git; all
  three `:check` tasks pass; a fresh `git clean`-style checkout compiles.
- **Verify:** The three gradle `:check` tasks above return green.

### BOND-B2 — Remove duplicate anchor-resolution logic from FlywayEmitter ✅ DONE (moot — already resolved)
- **Status:** DONE · **Priority:** P3 · **Category:** Tech debt (three-way sync risk)
- **What happened:** `FlywayEmitter.java` (the file this item targeted) was deleted outright in commit
  `22899bf` ("Phase 6 test backfill + remove dead FlywayEmitter") — it was "confirmed dead in the live
  generation pipeline (never [invoked])"; `FlywayEmitterTest`/`FlywayEmitterBondsTest` were deleted
  with it, and their coverage was ported to `SchemaLifecycleExecutorAdditiveChangeTest`/
  `SchemaRealizationEmitterAdditiveColumnsTest` against the actually-live path.
- **Verified 2026-07-12:** `SchemaRealizationEmitter` (the live generator emitter) already delegates
  anchor/type resolution to `BondModelSupport.resolveBond(...)` throughout — no private duplicate
  logic exists there. The only remaining anchor-resolution code lives in `BondModelSupport`
  (generator) and kernel's `GeneratedCrudRuntimeSupport` (runtime) — an intentional generator/kernel
  module boundary the original item never targeted, not tech debt to remove.
- **Verify:** `find . -iname "FlywayEmitter*.java"` returns nothing; `grep -n "resolveAnchorField|
  columnType|idFieldOrNull|fieldByName" SchemaRealizationEmitter.java` finds no private
  reimplementation — confirmed.

### BOND-B4 — Wire ReleaseGateValidator into the test suite / CI — mostly DONE, one finding needs your call
- **Status:** PARTIAL (test coverage DONE; CI-trigger question open) · **Priority:** P3 · **Category:** Test/CI
- **What was done 2026-07-12:** `ReleaseGateValidatorTest` extended with the 3 planned cases
  (`releaseGatePassesWhenAllDependenciesMeetTarget`, `semanticValidatorDoesNotBlockOnTruthEdgeViolation`,
  `bondClosureIncludesTransitiveDependencies`) — `:NPDevContract:dsl:check` green (4/4 tests, one
  constant fix needed: the plan's example used a nonexistent `TruthLevel.T3_INTEGRATED`; the real
  enum value is `T3_RUNS_LOCALLY`).
- **Finding — needs a decision, not fixed:** `.github/workflows/npdev-ci-validation.yml` already has a
  "DSL contract check" step (`gradlew check` in `NPDevContract/dsl`, in both jobs) — so `:dsl:check`
  *is* wired in whenever this workflow runs. But the workflow trigger is `workflow_dispatch` only
  (manual), not `pull_request`/`push` — **and so are the other two workflows**
  (`ai-beta-gate.yml`, `npdev-release-gate.yml`). None of the three run automatically on a PR today;
  a top-of-file comment on `npdev-ci-validation.yml` says this was deliberate ("Re-add the
  pull_request/push triggers below to restore automatic runs") because the full workflow is heavy
  (~120 min: Playwright, Postgres Testcontainers, RuntimeHost integration). Flipping that trigger
  wasn't done here — it's a real cost/policy decision (CI minutes, whether a GitHub remote is even
  wired up for this repo) that deserves an explicit answer, not a silent change buried in a bond-gap
  fix. **If you want `:dsl:check` (or a slimmer subset) to run automatically on every PR, say so and
  I'll wire a lightweight `pull_request`-triggered workflow rather than flip the heavy one.**
- **How to solve (remaining):** Decide the CI-trigger question above; nothing else outstanding.
- **Verify:** `:NPDevContract:dsl:check` green (confirmed, 2026-07-12) — CI-trigger automation still
  pending your call.

### BOND-B6 — Cross-pack bond end-to-end test ✅ DONE (2026-07-13)
- **Status:** DONE · **Priority:** P3 · **Category:** Test coverage
- **What was done:** all three planned tests added, adapted to the live code (the plan targeted
  `FlywayEmitter`, which BOND-B2 found had already been deleted — used the live
  `SchemaRealizationEmitter` path instead):
  (a) `ModelSourceResolverTest.packConceptWithConnectableAnchorIsPreservedInResolvedModel` — pack
  merge preserves `connectable:anchor` and the bond's namespaced reference target;
  (b) `BondSemanticsSupportTest.crossPackBondPassesSemanticValidation` — cross-pack bond validates
  with no errors through the full parse pipeline;
  (c) new `NPDevGenerator/generator/src/test/java/com/npdev/generator/dbconfig/
  CrossPackBondEndToEndTest.crossPackBondProducesCorrectFkDdlThroughSchemaRealizationEmitter` —
  `catalog::Product` produces table `catalog_products`, FK `product_id → catalog_products(sku_id)`,
  `ON DELETE RESTRICT`, zero `::` in generated SQL.
- **Verify:** `:NPDevContract:dsl:check` green; `:NPDevGenerator:generator:test --tests
  "*.CrossPackBondEndToEndTest"` green (full `:generator:check` run in progress). Production code
  unchanged — coverage-only, as planned.

### BOND-B7 — Pack concept table-name convention test ✅ DONE (2026-07-13)
- **Status:** DONE · **Priority:** P4 · **Category:** Test coverage
- **What was done:** the exact `catalog::Product` → `catalog_products` case already existed
  (`packNamespacedConceptFallbackBecomesSafePluralTableName`, using `cat::Product`) — added the
  other two planned cases to `SqlIdentifierSupportTest`: an explicit `tableName` on a pack concept
  is preserved as-is (not derived from the name); a junction-table name for a pack-namespaced N:M
  bond field contains no `::` characters.
- **Verify:** `:NPDevContract:dsl:test --tests "*.SqlIdentifierSupportTest"` green.

---

## 4. Aggregate Workbench / AutoPanel — unfinished phases

> **Status inconsistency — RESOLVED 2026-07-12 (AW-RECONCILE).** P0, P1, P4, P6, P7, and Polish are
> **DONE**, all committed. P2, P3, P5 are **PARTIAL**: schema/DSL/validation landed for each, but
> each has one concrete missing slice, re-scoped below. Full evidence (file paths + commit hashes)
> lives in `docs/architecture/AGGREGATE_WORKBENCH_PLAN.md` §5, updated alongside this reconciliation.

### AW-RECONCILE — Reconcile phase-status markers against committed code ✅ DONE (2026-07-12)
- **Result:** P0 slice 2 DONE (`887ab34` compiled layer, `f57b84c` `AggregateApiController` +
  `AggregateRuntime` nested-read — the plan's proposed `AggregateController`/`RuntimeApiEmitter`
  names never landed; a hand-written RuntimeHost controller satisfies the acceptance criterion
  instead). P1 DONE (`ee4b083` schema/AST/`AutoPanelExpander`, `96c0773` `PanelRuntime` — the plan's
  proposed `WorkbenchRuntime`/`autopanel-expander` names never landed either). P2 PARTIAL (`3757336`
  landed the `selectors[]` schema/DSL/expansion; no distinct SelectorGrid component, and the Polish
  band-picker is a separate mechanism that doesn't consume it). P3 PARTIAL (`8756812`/`842801c`
  landed `computed[]` schema/expr-engine/server-validation; the generated page never consumes
  `metadata.computed` — Polish's `recompute:` is a server-round-trip procedure, a different
  mechanism that happens to satisfy the same UX). P5 PARTIAL (`4f133b1` landed region-level
  editable/read-only gating off the pre-existing singular `lifecycle`; no `lifecycles[]` plural
  construct, no per-state `allowedActions` action-gating, no dedicated transition endpoint/FlowEngine
  binding).
- **Verify:** `docs/architecture/AGGREGATE_WORKBENCH_PLAN.md` §5 now carries the commit hash + exact
  gap for every phase.

### AW-P0b — Aggregate compiled layer + load endpoint (P0 slice 2) ✅ DONE
- **Status:** DONE · **Priority:** P2 · **Category:** Feature (foundation)
- **Evidence:** `CompiledAggregate`/`CompiledAggregateCollection` in `NPDevContract/dsl` wired into
  `ModelCompiler`/`CompiledModel` with canonical JSON round-trip (`887ab34`).
  `AggregateApiController` (`GET /api/runtime/aggregate/{aggregateName}/{rootId}`) +
  `AggregateRuntime` in `NPDevRuntimeHost` (`f57b84c`). No generator-emitted nested-read path exists
  or is needed — the endpoint is fixed platform code shared by every generated app.
- **Verify:** `GET /api/runtime/aggregate/Expedicao/{id}` returns the nested tree — confirmed live via
  P4/P6/P7 evidence (WmsOffice).

### AW-P1 — AutoPanel primitive + single-concept expansion (ADR-0005 "heart") ✅ DONE
- **Status:** DONE · **Priority:** P2 · **Category:** Feature
- **Evidence:** schema `autoPanels[]` in all 4 mirrors; `AutoPanelAst`/`CompiledAutoPanel`/
  `compiler/AutoPanelExpander.java` (default-derivation pass) + `SemanticValidator` validation
  (`ee4b083`); emission in `BusinessUiEmitter` + `workbench-page.html.mustache`; runtime served by
  `PanelRuntime` (`96c0773`). Class/file names differ from the plan's original proposal
  (`WorkbenchRuntime`/`autopanel-expander`) but every functional piece exists and is committed.
- **Verify:** `autoPanels:[{concept:"Cliente"}]` → working list+detail+form applet — confirmed live
  (Expedicao/Recebimento AutoPanels, P7 evidence).

### AW-P2 — Unify `selectors[]`/`bandPickers` + FK auto-Prompt wiring (re-scoped) — PARTIAL, narrowed scope confirmed
- **Status:** PARTIAL (the picker-unification half is DONE; the FK auto-Prompt half is correctly
  scoped-out below, not abandoned) · **Priority:** P4 · **Category:** Feature
- **Key finding that reframes this item:** `bandPickers.<band>.panel` was **already** able to
  reference a `selectors[]`-expanded panel with zero code changes — `expandSelector` compiles a
  selector into an ordinary `CompiledPanel` (table layout, standard concept data source), and
  `bandPickers` resolves its `panel` reference generically by name via the same runtime API any
  panel uses. The two constructs were never structurally incompatible; the actual gap was narrower:
  `PanelRuntime.loadPanel`'s response **never echoed the panel's own `metadata`**, so even when a
  `bandPicker` pointed at a `selectors[]` panel, the client never received that selector's declared
  `returnMapping`/`multiSelect` contract — `openBandPicker`'s row-copy always fell back to blind
  overlapping-column-name copying, silently ignoring `returnMapping` even when declared.
- **What was done (2026-07-13):** (1) `PanelRuntime.loadPanel` now includes `response.put("metadata",
  panel.metadata())` — a one-line additive change (no key was reused, so nothing regresses).
  (2) `workbench-page.html.mustache`'s `openBandPicker` now reads `p.metadata.returnMapping` when
  present and uses it (`targetField → sourceField`) instead of the overlapping-column fallback, which
  still applies for a hand-authored panel with no `returnMapping`. This makes `bandPickers` and
  `selectors[]` **one mechanism** in practice: point a `bandPicker` at a `selectors[]` name and its
  full pick contract (multiSelect, filters, returnMapping) now actually takes effect, not just its
  column list.
- **FK auto-Prompt wiring — re-scoped, not implemented:** every FK field on a generated form
  **already** gets an automatic picker today via the pre-existing `field.widget === "lookup"`
  mechanism in `business-ui-app.mustache` (browse dialog, full CRUD-form integration) — the original
  premise that FK fields lack a Prompt was wrong; the memory note citing a `promptsByConcept` path
  was an inaccurate name for this. The **actual** remaining gap is narrower: no way to opt a specific
  FK field into a **custom, filtered, `selectors[]`-declared** picker (with `multiSelect`/custom
  columns) instead of the generic single-value lookup. Implementing that means adding a
  `field.ui.selectorRef` override hook into `business-ui-app.mustache` (3639 lines, the platform's
  largest/most-used generated template) and wiring it through the existing lookup-dialog machinery.
  Deliberately **not done in this pass** — real regression risk to the default FK UX every generated
  app already depends on, and this session's remaining budget favored finishing AW-P3/AW-DisplayAll
  over a rushed change to that file. Scoped out as a separate, smaller follow-up item if wanted.
- **Tests added:** `PanelRuntimeTest.loadPanelEchoesPanelMetadataForABandPickerToConsume`.
- **Verified live:** a scratch generated app's `SelecionaRuas` selector panel — `GET
  /api/runtime/metadata/ui/panels/SelecionaRuas` returned `metadata.returnMapping:{"local":"rua",
  "maxPos":"maxPos"}` and `metadata.multiSelect:true`, exactly as declared.
- **Verify:** `com.finalexec.PanelRuntimeTest` green; live REST check as above.

### AW-P3 — Reconcile `computed[]` vs `recompute:` (re-scoped) ✅ DONE (2026-07-13)
- **Status:** DONE (folded, per the DoD's alternate option) · **Priority:** P4 · **Category:** Feature
- **Decision, informed by AW-DisplayAll:** AW-DisplayAll found that the workbench only ever renders
  one row's band cells live at a time (the `all`-mode DOM-weight risk the plan worried about was
  never built) — so there is no performance pressure that would justify building a client CEL
  evaluator for `computed[]` just to avoid `recompute:`'s network round-trip; the round-trip is
  already proven fine at the actual shipped scale. Building a real client expression evaluator (a
  CEL→JS transpiler or interpreter) for a mechanism nothing currently needs would be speculative
  scope, not a fix. Chose the DoD's other option: **formally fold `computed[]` into `recompute:`**
  rather than build a redundant evaluator.
- **What was done:** `SemanticValidator` now warns (does not block — authoring stays unblocked, same
  pattern as the existing truth-edge warning) whenever an AutoPanel surface declares `computed[]`
  without also declaring `transaction.metadata.recompute`: *"declares computed\[\] but no
  transaction.metadata.recompute procedure -- computed\[\] stays panel metadata only and will NOT
  recompute live in the generated page unless a recompute procedure is also declared."* This makes
  the situation explicit and author-visible instead of silently inert — `computed[]` remains valid
  declarative metadata (useful for introspection/tooling even without live recompute), but an author
  relying on it alone for live UX now gets told why nothing happens.
- **Tests added:** `ComputedExpressionValidationTest.computedWithoutRecomputeProcedureWarnsItStaysMetadataOnly`,
  `.computedWithRecomputeProcedureDoesNotWarn`.
- **Verify:** `:NPDevContract:dsl:check` green (7 tests in this file, no regressions — the live
  WMS/Expedicao model already declares both `computed[]` and `recompute` together per the plan's own
  DSL example, so the new warning doesn't fire there).

### AW-P5 — Per-state `allowedActions` gating (re-scoped) ✅ DONE (2026-07-13)
- **Status:** DONE · **Priority:** P4 · **Category:** Feature
- **What was done:** added optional per-state `allowedActions`, authored as a comma-separated string
  in the lifecycle state's existing `metadata` map (`{"value":"aberta","metadata":{"allowedActions":
  "GerarDemanda"}}`) — the schema's `lifecycleState.metadata` is a flat `string→string` map, so this
  reuses the same convention as the existing `editable` metadata flag rather than adding a new
  `lifecycles[]` plural construct or touching any of the 4 schema mirrors.
  `AutoPanelExpander.lifecycleDescriptor` (`NPDevContract/dsl`) parses it into an
  `allowedActions: [...]` array on the compiled workbench descriptor (absent = no restriction).
  `workbench-page.html.mustache`'s action rail now filters `store.descriptor.actions` against
  `si.allowedActions` (matched by `procedure` name) before rendering — same shape as the existing
  `EDITABLE` whole-panel gate. No dedicated `/transition` endpoint or FlowEngine binding added
  (the procedure-driven transition path already works; not needed).
- **Tests added:** `AggregateWorkbenchExpansionTest` — pins `allowedActions:["GerarDemanda"]` on the
  `aberta` state and `null` (no restriction) on the terminal `confirmada` state.
- **Verified live:** generated + built + booted a scratch app (`Expedicao` aggregate, one lifecycle
  state declaring `allowedActions`) — `GET /api/runtime/metadata/ui/panels/ExpedicaoWorkbench`
  returned `lifecycle.states[0].allowedActions:["GerarDemanda"]` for `aberta` and no `allowedActions`
  key for `confirmada`, confirming the DSL→API plumbing end to end. Client-side button-filter logic
  reviewed against the already-proven `EDITABLE` pattern it mirrors (not separately browser-driven).
- **Verify:** `:NPDevContract:dsl:check` green; live REST check as above.

### AW-DisplayAll — Validate `display:all` DOM weight / virtualization ✅ DONE (2026-07-13 — resolved by finding: the mode was never built)
- **Status:** DONE (no code change needed) · **Priority:** P4 · **Category:** Performance risk
- **Finding:** the `transaction.sections.band.display` toggle (`selected`|`all`|`paged`) described in
  `AGGREGATE_WORKBENCH_PLAN.md` §4's DSL surface example was **never implemented** — confirmed by
  grep: zero references to `display` on the band schema (`model.schema.json`), zero parsing of it in
  `AutoPanelExpander`, zero handling of it in `workbench-page.html.mustache`. The shipped
  `render()` function always renders the band section for **only the currently-selected parent
  row** (`var selRow = sec.rows.filter(r => r.id === selected)[0]; if (sec.bandDefs.length && selRow)
  {...}`) — there is no code path that renders every parent row's band simultaneously.
- **Why this closes the item, not just narrows it:** the risk this item worried about — "live
  editable cells across many cards degrading the page" — can only occur if an `all` mode exists that
  renders every row's cells at once. Since that mode was never built, the shipped behavior (bounded
  DOM: one row's band, regardless of how many total rows exist) is inherently safe by construction,
  not by an unproven mitigation. There is nothing to A/B, virtualize, or warn about, because the
  unbounded-rendering scenario doesn't exist in the code.
- **Consequence for the plan:** `AGGREGATE_WORKBENCH_PLAN.md`'s DSL surface example (§4) documents a
  `display` knob that was never built; either implement it as a genuinely new (opt-in) feature in a
  future phase with its own DOM-weight validation at that time, or update the plan's example to drop
  it. Not scheduled now — no live app declares or needs it.
- **Verify:** `grep -rn "\"display\"" NPDevContract/schemas/model.schema.json` — no band-display
  property exists; `grep -n "selected\|\ball\b\|paged" workbench-page.html.mustache`'s render logic
  — only single-selected-row band rendering exists. Confirmed 2026-07-13.

### AW-Deferred — WMS surfaces intentionally stubbed (boundary)
- **Status:** BOUNDARY · **Priority:** —
- **What:** `Imprimir` (report generation), `Add Doctos`/NFe attach (file-upload — see ARCH-upload),
  `Histórico` viewer. Currently stub actions, not implementations. Out of scope for the current
  primitive; revisit when report-generation and file-upload primitives exist.

---

## 5. Repository hygiene

### HYG-1 — Uncommitted working-tree changes + unignored dirs ✅ DONE (2026-07-12)
- **Status:** DONE · **Priority:** P3 · **Category:** Hygiene
- **What was done:** the 4 AppGen script diffs were legitimate finished work (wiring
  `New-AppTreePage.ps1` into both builders to fix dead `app-tree.html` links, plus surfacing the
  Super User key file path prominently on `info.html`/`control-panel.html`) — committed `8b70e21`.
  `.claude/` turned out to be an auto-accumulated per-session permission-approval log (hundreds of
  one-off Bash command entries in `settings.json`), not shared config — added `.claude/` and
  `**/__pycache__/` to `.gitignore`, committed `f075f5f`.
- **Verify:** `git status` clean except intentionally-ignored dirs — confirmed.

### HYG-2 — Completed features remain uncommitted (at risk) ✅ DONE (2026-07-12 — false alarm)
- **Status:** DONE · **Priority:** P2 · **Category:** Hygiene / risk
- **What actually happened:** all four features memory flagged as "not committed" — ControlPanel/
  SuperUser, Seed/Mock Data, Widget Input-Type system, AI Authoring Bridge — were already fully
  committed in `ed5ef43` ("Add GuidePages, widget input-type system, ControlPanel/SuperUser, seed
  data, and workspace menu shell"). `git log -1 -- <key file>` for each feature's canonical file
  confirmed this (`ControlPanelAdminUserController.java`, `DataSeedAdminController.java`,
  `NPDevMcp/server.py`, plus the widget-catalog/schema changes). The memory entries were simply
  stale from before that commit landed.
- **Fix applied:** corrected the 4 memory files (`controlpanel_superuser_feature.md`,
  `widget_input_type_system.md`, `ai_authoring_bridge.md`, `MEMORY.md` index) to say "committed
  `ed5ef43`" instead of "not committed" — no code change needed.
- **Verify:** `git log --oneline -1 -- <path>` for each feature's canonical file returns `ed5ef43` or
  later, not empty.

---

## 6. Accepted design boundaries (reference — not scheduled)

These are documented constraints. Design around them; do not file as bugs. **Boundaries marked
"✅ re-verified 2026-07-13" were re-checked against committed code during the roadmap-implementation
audit; the two struck-through entries were found stale and corrected below.**

| ID | Boundary | Workaround |
|---|---|---|
| ARCH-7 | Procedure cannot feed live filtered DB data into a `plugin:java-source` capability (`listConcepts`/`runQuery` ignore `where`, return all rows; `List<ConceptRecord>` not importable) | Keep capabilities pure functions over caller-supplied data. Multi-arg `capabilityCall`s work (matched by name + exact param count). *(Not re-verified 2026-07-13 — trust prior evidence.)* |
| **ARCH-loop** *(CORRECTED 2026-07-13)* | **No loop step in FLOWS only.** ~~No loop step type in Flows or Procedures~~ — **Procedures DO loop.** The `procedureStep` `type` enum declares `"loop"` and `"forEach"` (both alias to `ProcedureStepType.FOR_EACH`, [`ProcedureStep.java:209`](../NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/ProcedureStep.java#L209)) and are fully executed by [`DefaultProcedureExecutor.forEach`](../NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java#L154) with a `maxLoopIterations` safety cap. Flow step enum has `branch`/`if` but no iteration (✅ re-verified). | Iterate inside a **Procedure** (`forEach`/`loop` step). Only genuine Flow-level bulk logic needs a hand-authored `web/` CRUD-REST loop. |
| ARCH-upload | No server-side multipart / file-upload primitive (✅ re-verified 2026-07-13 — 0 `multipart`/`MultipartFile` refs in runtime-host main) | Browser `FileReader` + client-side parse, then CRUD writes. |
| ARCH-compound-unique | Compound (multi-field) `unique` invariants rejected by generator (✅ re-verified 2026-07-13 — `SemanticValidator.java:363` still throws "compound unique … not supported yet") | A Flow (not Procedure): caller supplies existing rows, `capabilityCall` compares, `branch` with `==` returns-or-creates. `createConcept` input must ref only the concept's own fields (`$input.dados`). |
| ARCH-6 | Invariant `expression` grammar (`CelInvariantEngine`) is hand-rolled: top-level `\|\|`/`&&` over fixed atoms, no parens, no unary `!`, no arithmetic | Write rules in pure DNF via De Morgan's law. For arithmetic value expressions, reuse `com.npdev.dsl.v1.expr.ComputedExpression` (arithmetic/comparison/logical/parens/field-refs). *(Not re-verified 2026-07-13 — trust prior evidence.)* |
| **ARCH-13** *(NARROWED 2026-07-13)* | Boundary now applies **only to the standalone declared `panel{}` (Tier 2)** — it still has no generic create-row/delete-row (only per-row update). The **aggregate Workbench** surface (a *different* surface) **now supports `rowOps: [add, delete]`** — 6 references in [`workbench-page.html.mustache`](../NPDevGenerator/generator/src/main/resources/npdev-templates/workbench-page.html.mustache), landed in Aggregate Workbench P4. So the WMS multi-level add/remove-child need is met; only the plain declared Panel remains gapped. | For a standalone declared Panel: hand-authored `web/*.html` (Tier 1) against CRUD REST. For multi-level child add/delete: use an **aggregate-bound AutoPanel / Workbench** (`rowOps`). |

---

## 7. Fixed engine bugs (closed — for regression awareness)

All in the working tree at capture; confirm committed. Full root-cause narratives archived at
`C:\Users\Marcelo\.claude\projects\...\memory-archive\npdev_platform_gaps.full.md`.

| ID | Fix |
|---|---|
| #2 | `onDelete` now enforced under InMemory (was physical-DB only) |
| #3/#4 | Bare `new ObjectMapper()` in 4 JDBC store adapters → 500 on date fields with lifecycle; fixed |
| #5 | Orchestration `create` wrote rows under wrong tenant (fell back to DB default); fixed |
| #8 | Flow `createConcept`/`updateConcept` bypassed lifecycle-transition validation; adapter now enforces transitions. Field-default application also now fixed (ARCH-8b, 2026-07-12), both Postgres and InMemory. |
| #9 | `emitEvent` NPE'd on null payload field (`Map.copyOf` rejects nulls); fixed |
| #10 | Panel dataSource `where` was dead code; now post-filters `field ==`/`!=`. `orderBy` also now applied (ARCH-10b, fixed 2026-07-12). |
| #11 | date/datetime fields failed under real Postgres (H2 masked); now model-driven via `CompiledField.getDslType()` |
| #12 | `Build-NpdevApp.ps1` rewrote pack `$ref` to absolute path (resolver rejected); rewrite removed |

---

## 8. Changelog of this document

- **2026-07-12** — Initial consolidation from platform-gaps memory, Aggregate Workbench plan, bond
  gaps plan, maturity ledger, and live git state.
- **2026-07-13** — Post-implementation audit. Verified every claimed-DONE item against commits **and**
  ran the test suites (DSL, kernel persistence-inproc/postgres/authz-default, generator cross-pack —
  all green). §6 corrected: **ARCH-loop** was factually wrong (Procedures DO loop via `forEach`/`loop`
  → `FOR_EACH` with `maxLoopIterations`; only Flows lack iteration) and **ARCH-13** narrowed (the
  aggregate Workbench now supports `rowOps:[add,delete]`; only the standalone declared Panel remains
  gapped). ARCH-upload / ARCH-compound-unique re-verified as still-valid boundaries. FlywayEmitter
  confirmed fully deleted (BOND-B2 moot). Corresponding correction mirrored to the
  `npdev-platform-gaps` memory.
