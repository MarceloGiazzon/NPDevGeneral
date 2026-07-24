# Schema Engine Rebuild v2 — the Impact-Analysis Engine (REG-6 closure + GeneXus-grade impacts)

**Status:** PLAN v2 (supersedes v1 of 2026-07-24; nothing built yet) · **Owner decision:** rebuild
the engine strangler-fig AND deliver the operator-facing impact/conversion experience on top of it
· **Risk:** HIGHEST in the platform (this code decides whether a live production database is
altered, refused, or has its data migrated).

> **Reframing from v1.** v1 scoped this as debt repayment (kill the REG-6 bug family). v2 keeps
> every v1 phase and constraint, but the *product* is bigger: NPDev gets a **GeneXus-grade database
> impact experience** — (a) an **Impact Report** computed against the *live database* before
> anything is touched, (b) **data-loss warnings with concrete row counts**, and (c) **conversion
> hooks**: operator-supplied SQL (later Java) that migrates data during an upgrade, verified by the
> engine instead of trusted. Discipline (refuse, itemize, verify) plus freedom (bring your own
> migration). REG-6, REG-40, and the `ExternallyManaged` limitation all close as consequences of
> the same abstraction.

---

## Part I — Executor contract (READ FIRST, EVERY SESSION)

This plan is written to be executed across many sessions by an AI agent that may be less capable
than the plan's author. If you are that agent, these rules are not advice — they are the contract.

### I.1 Global rules (non-negotiable)

1. **Behavior-preserving until Phase 4.** Phases 0–3 add read-only code and tests ONLY. If any
   existing test's *expectation* has to change to go green, semantics changed → **STOP, revert
   your edit, report**. Never "update the test to match."
2. **RED-first for every new capability.** Write the failing test, run it, confirm it fails **for
   the expected reason** (read the failure message), then implement. If a new test passes before
   you implement anything, the test is wrong — fix the test.
3. **Both proof matrices green at the end of every step** (commands in §I.4). The Postgres matrix
   is **not optional** — H2 masks real type/DDL differences (that is literally how T-B1 and bug
   #11 hid). If Docker is unavailable, do Phase work that doesn't need a gate (docs, inventory),
   or STOP — never declare a step done on H2 alone.
4. **Strangler-fig, never big-bang.** The new model runs as a read-only *shadow* proving parity
   with the live engine before a single pass is switched over (Phase 3 gate).
5. **Safety invariants may never regress** (each pinned by an existing RED-capable guard):
   - platform columns (`id`, `version`, `row_version`, `tenant_id`) stay `NOT NULL` (T-B1);
   - a declared rename is never executed as drop+add (data loss);
   - a destructive change is refused + itemized + snapshotted unless explicitly acknowledged;
   - the fingerprint fast-path still short-circuits an unchanged boot.
6. **Bounded steps, bounded commits.** One numbered step = one commit. Commit message format:
   `feat(SER-P<phase>.<step>): <what>` / `test(SER-…)` / `docs(SER-…)`. **Never `git add .`** —
   add files by explicit path. **Never regex-patch Java** — use anchored exact-string edits.
7. **Evidence outside the repo:** every session writes a digest to
   `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\schema-engine-rebuild\SESSION_<yyyy-mm-dd>_<n>.md`
   (what was done, gate outputs pasted, what's next). Build/test scratch also goes there or to
   `D:\WorkSpace\NPDev\Build` — **never inside the repo**.
8. **Do not full-read the big files.** `SchemaLifecycleExecutor.java` is ~3,300 lines (177 KB).
   Grep for the method name, then read a window (offset/limit). Same for
   `SchemaRealizationEmitter.java`, `KernelRunner.java`, `SemanticValidator.java`.
9. **Line numbers in this plan are anchors from 2026-07-24 and WILL drift.** Always locate code by
   grepping the quoted method/class name; use the line number only to disambiguate multiple hits.
10. **When stuck or surprised → STOP and report.** A gate that won't go green, a FIND anchor that
    doesn't exist, a test failing for an unexpected reason: do not improvise around it. Write the
    session digest with the exact failure and stop. A stopped step is recoverable; a wrong guess
    in this subsystem destroys user data.
11. **Update the Progress Ledger (§I.6) in this file** at the end of every completed step (status
    + date + commit hash). That is how the next session knows where to resume.

### I.2 Where everything lives (verified 2026-07-24)

| Thing | Absolute path |
|---|---|
| Runtime reconciliation engine (the ~8 passes) | `D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost\src\main\java\com\finalexec\db\SchemaLifecycleExecutor.java` |
| Manifest record (desired-state input) | inner `public record SchemaManifest(...)` in `SchemaLifecycleExecutor.java` (~line 3189) |
| `ColumnFacts` (desired-side per-column projection, REG-6 risk-core, landed) | inner type + `columnFactsFor(manifest, table)` in `SchemaLifecycleExecutor.java`; guard test `SchemaLifecycleExecutorColumnFactsTest` |
| Destructive itemization (stable item strings + token input) | `NPDevRuntimeHost\src\main\java\com\finalexec\db\SchemaDeltaReport.java` |
| Acknowledgment stores | `PendingSchemaAcknowledgmentStore.java`, `MigrationMarkStore.java`, `MigrationClaimStore.java` (same folder) |
| Pre-drop snapshot writer | `SchemaDropSnapshotWriter.java` (same folder) |
| ControlPanel schema endpoints (mark-done lives here) | `NPDevRuntimeHost\src\main\java\com\finalexec\controlpanel\SchemaAcknowledgmentController.java` (`@PostMapping("/mark-done")` ~line 123) |
| DDL emitter (V1 / R__ / manifest / fingerprint) | `D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator\generator\src\main\java\com\npdev\generator\dbconfig\SchemaRealizationEmitter.java` |
| Build-time model-vs-model plan (NOT live-DB) | `NPDevGenerator\generator\src\main\java\com\npdev\generator\schemaevolution\MigrationPlanEmitter.java` |
| Shared type-change vocabulary | `D:\WorkSpace\NPDev\NPDev_General\NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\schemaevolution\` → `TypeChangeMatrix.java`, `SqlTypeNormalization.java`, `SchemaDeltaItem.java`, `DestructiveAckToken.java`, `RenameResolution.java` |
| DSL→SQL type mapping | `NPDevContract\dsl\src\main\java\com\npdev\dsl\v1\compiled\SqlTypeSupport.java` |
| Internal tables source of truth | `NPDevKernel\kernel\src\main\java\com\npdev\kernel\dbschema\NpdevInternalTables.java` |
| **Dead second lineage (to be retired in Phase 9)** | `NPDevRuntimeHost\src\main\java\com\finalexec\npdev\migration\` (12 classes: `MigrationRiskAssessmentBuilder`, `ModelDiffPreviewBuilder`, `StorageSchemaSnapshot`, …) — referenced only by generator tests |
| Operator build script (PlanOnly/Upgrade/Acknowledge flags) | `D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-NpdevApp.ps1` |
| H2 test matrix (~27 classes) | `NPDevRuntimeHost\src\test\java\com\finalexec\db\SchemaLifecycleExecutor*Test.java` |
| Postgres proof matrix (Testcontainers, Docker required) | `...\SchemaLifecycleExecutorPostgresProofMatrixTest.java` |

### I.3 Session bootstrap (run before any Java work in NPDevRuntimeHost)

`NPDevRuntimeHost\build.gradle` is a **generated** file (hygiene scripts delete it; only
`build.gradle.template` is tracked). If it is missing, materialize it:

```powershell
Copy-Item D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost\build.gradle.template `
          D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost\build.gradle
```

Then ensure the local dependency jars exist and are current (required after ANY kernel/adapter
change, and on a fresh machine):

```powershell
D:\WorkSpace\NPDev\NPDev_General\scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs
```

For the Postgres matrix, verify Docker is up: `docker info` must succeed. If it fails → STOP for
any step whose gate includes the Postgres matrix.

### I.4 The gates (exact commands + expected outcomes)

**GATE-H2** — full H2 matrix:
```powershell
cmd /c "cd /d D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost && gradlew.bat test --tests com.finalexec.db.SchemaLifecycleExecutor* -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs"
```
Expected: `BUILD SUCCESSFUL`, zero failed tests.

**GATE-PG** — Postgres proof matrix (Docker):
```powershell
cmd /c "cd /d D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost && gradlew.bat test --tests com.finalexec.db.SchemaLifecycleExecutorPostgresProofMatrixTest -PincludePostgresMatrix -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/Build/runtimehost-libs"
```
Expected: `BUILD SUCCESSFUL`. If it fails with a Docker/Testcontainers startup error, that is an
environment problem, not a code problem — fix Docker or STOP; do not skip.

**GATE-GEN** — generator tests (needed when `SchemaRealizationEmitter`/`MigrationPlanEmitter` change):
```powershell
cmd /c "cd /d D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator && gradlew.bat :generator:test"
```

**GATE-STATEFUL** — the stateful additive-migration scenario harness (existing script; exercises
upgrade-against-existing-DB flows):
```powershell
D:\WorkSpace\NPDev\NPDev_General\scripts\quality\run-stateful-additive-migrations-check.ps1
```

Every step below names which gates apply. "All gates" = GATE-H2 + GATE-PG (+ GATE-GEN and
GATE-STATEFUL when the step touched generator code).

### I.5 Vocabulary (fixed for the whole programme — do not rename)

- **`CurrentSchema`** — the live database's actual shape, read once per boot.
- **`DesiredSchema`** — the model's intended shape, projected from the manifest + `ColumnFacts`.
- **`SchemaDiff`** — the structured delta `(DesiredSchema, CurrentSchema, renameHints) → items`.
- **Safety class** — one enum on every diff item (§Phase 2 pins it).
- **Impact Report** — the operator-facing rendering (JSON + text) of a `SchemaDiff` + row-count
  probes. One engine, three surfaces (pre-deploy CLI, boot refusal, ControlPanel).
- **Conversion hook** — operator-supplied migration artifact that *claims* specific diff items;
  the engine runs it, **re-diffs**, and only accepts the claim if the item actually disappeared.
- **Item key** — the stable string identity of one diff item. MUST reuse the exact stable-string
  format `SchemaDeltaReport` already uses for token computation (pinned in Phase 0) so tokens,
  reports, and hook claims all name changes identically.

### I.6 Progress Ledger (executor updates this table; do not delete rows)

| Step | Status | Date | Commit | Notes |
|---|---|---|---|---|
| GATE bootstrap (bare-template runnability) | DONE | 2026-07-24 | 6480d19 | 3 pre-existing mount-assumptions blocked GATE-H2/GATE-PG on a bare template: 2 test files transitively depend on excluded generated-runtime main classes (now excluded only when mount absent); `test` hard-depended on `enforceSingleSchemaRealizationSource` (now conditional on mount); the Postgres matrix was excluded from the sourceSet outright (now includable via `-PincludePostgresMatrix`). Reviewer-verified GATE-H2 + GATE-PG green bare-template. |
| R.1–R.3 (tactical REG-40) | DONE | 2026-07-24 | 113b7cf (reviewed+verified by 2nd agent) | R.1 RED test `SchemaRealizationEmitterAdditiveColumnsTest#additiveScriptCreatesMissingBusinessTablesBeforeAnyAlterTable`; R.2 split `appendBusinessTable`→shape+constraints, `appendBonds`→junction-shapes+bond-constraints in `SchemaRealizationEmitter`, R__ now emits CREATE-TABLE→ADD-COLUMN→constraints in that order; R.3 proved end-to-end on H2 (`SchemaLifecycleExecutorNewTableOnExistingDbTest`, new class) and real Postgres (`SchemaLifecycleExecutorPostgresProofMatrixTest#newConceptAddedOnUpgradeGetsItsTableOnPostgres`, new method) — both drive real Flyway migrations via the production `migrate(Flyway, SchemaManifest)` entry point, not hand-crafted classification fixtures. GATE-GEN green, GATE-STATEFUL green. Docs updated (REG-40 FIXED in `NPDEV_OPEN_ITEMS_REGISTER.md`, `DATABASES_AND_MIGRATIONS.md` §15/§17/§18). **Environment finding:** GATE-H2/GATE-PG as literally specified (`cd NPDevRuntimeHost && gradlew.bat test`) cannot run against the bare template checkout right now — `compileTestJava` fails on 2 pre-existing, unrelated test files (`ConceptQueryControllerTest`, `ActuatorAdminGuardFilterTest`) that need the generated-runtime mount (`npdev-generated/`), which a bare template checkout never has (confirmed pre-existing by running the same command against an untouched existing test file). Worked around by resolving the real Gradle test classpath and compiling/running the specific test classes directly via javac + JUnit Platform Launcher — see session digest for the exact repro and recommended fix (exclude those 2 files from `sourceSets.test` in `build.gradle.template` the same way their generated-runtime-dependent siblings already are). |
| P0.1–P0.3 (inventory) | DONE | 2026-07-24 | (read-only; docs in OutsideRepo) | P0.1 `PASS_INVENTORY.md` (10 passes + destructive executors: method@line, manifest inputs, ≥12 ad-hoc `DatabaseMetaData` read sites to consolidate, ordering deps, pinning tests). P0.2 `MANIFEST_AND_ITEMKEY.md` — `SchemaManifest`@3189 (17 components); **finding: manifest carries columns/types/nullability/defaults/uniques but NO explicit FK or index lists** (bonds/indexes derived), an asymmetry Phase 1/2 must resolve (DesiredSchema synthesizes FK/index from bonds+uniques, or reader scopes to desired-expressible). P0.3: item-key = `SchemaDeltaItem.stableString()` (`KIND:field:…` colon-joined) in shared `com.npdev.dsl.v1.schemaevolution`, already byte-identical across build-time + live-DB via `DestructiveAckToken`; row-counts deliberately excluded from the hashed string (F2) — Phase 2 reuses `SchemaDeltaItem`, extends KINDs additively only. Both docs under `__OutsideRepo/schema-engine-rebuild/`. |
| P1.1–P1.6 (CurrentSchema reader) | DONE | 2026-07-24 | 391252f (H2) + this commit (PG) | **Cross-engine proven: GATE-H2 + GATE-PG both green.** Golden test refactored to an abstract base (`AbstractCurrentSchemaReaderGoldenTest`) run by both `CurrentSchemaReaderH2Test` (in-mem) and `CurrentSchemaReaderPostgresTest` (Testcontainers, `@Tag("integration")`, gated by `-PincludePostgresMatrix`), sharing a `UrlDataSource` helper — every dimension (types normalized cross-engine via `SqlTypeNormalization`, nullability, defaults, PK, unique, FK ON DELETE CASCADE, index) verified on H2 AND real Postgres. P1.1 6 records in new pkg `com.finalexec.db.schemastate` (CurrentSchema/Table/Column/UniqueConstraint/ForeignKey/Index). P1.2 `CurrentSchemaReader` — one read-once portable reader consolidating the ≥12 ad-hoc DatabaseMetaData sites: tables (skip system schemas), columns (name/normalizedSqlType via shared `SqlTypeNormalization`/size/scale/nullable/default), PK (KEY_SEQ-ordered), indexes (getIndexInfo), uniques (derived from unique indexes minus the PK), FKs (getImportedKeys, KEY_SEQ-ordered, DELETE_RULE→CASCADE/SET NULL/…). All names lower-cased; per-table scoped by schema for PG. Wired NOWHERE (behavior-preserving). P1.3 RED→GREEN golden test `CurrentSchemaReaderH2Test` (NOT NULL DEFAULT + nullable + unique + PK + FK ON DELETE CASCADE + secondary index, every dimension asserted). **GATE-H2 green** (full SchemaLifecycleExecutor* + schemastate). **Remaining for P1 DONE: the Postgres golden twin (GATE-PG) — the cross-engine proof that is the whole point (H2 masks PG).** |
| P2.1–P2.5 (DesiredSchema + SchemaDiff) | IN PROGRESS (desired side done; diff engine next) | 2026-07-24 | (checkpoint below) | P2.1+P2.2 DONE: `SafetyClass` enum (11 values, merges live + dead-lineage taxonomy) + desired records (`DesiredSchema/Table/Column/UniqueConstraint`, schemastate) + `DesiredSchemaFactory` (in `com.finalexec.db` — MUST live there for package access to the package-private `ColumnFacts`/`columnFactsFor`; sub-packages get no package access). Factory consumes `ColumnFacts` per the code directive, lower-cases names, carries platformManaged/requiredByModel/nullable/literalDefault/bond/renamedFrom. Pure, unwired. `DesiredSchemaFactoryTest` green (GATE-H2). **NEXT (P2.3–P2.5):** `SchemaDiffItem`/`SchemaDiff` + `SchemaDiffEngine` (rename-resolution FIRST; reuse `SchemaDeltaItem.stableString()` for destructive item keys; `TypeChangeMatrix` for widen/narrow) + the RED-first pure diff test. FK/index diffing deferred (P0.2 asymmetry). |
| P3.1–P3.4 (shadow parity) | NOT STARTED | | | |
| P4.1–P4.9 (pass migration) | NOT STARTED | | | |
| P5.1–P5.4 (finalize core) | NOT STARTED | | | |
| P6.1–P6.6 (Impact Report) | NOT STARTED | | | |
| P7.1–P7.7 (conversion hooks) | NOT STARTED | | | |
| P8.1–P8.3 (type-conversion proposals) | NOT STARTED | | | |
| P9.1–P9.3 (retire dead lineage, docs) | NOT STARTED | | | |

---

## Part II — Optional tactical track: REG-40 hotfix (can run BEFORE the rebuild)

**Why it exists:** REG-40 (additive migration never `CREATE`s new tables) is the only OPEN bug
that makes the platform *unusable* for a real upgrade today: add a new concept, redeploy against
an existing database → boot fails with `Table not found`. The strategic fix arrives in Phase 4.6,
but that is many sessions away. This track is a small, bounded, fully-tested hotfix. Doing it
first is **recommended** but not required; if skipped, Phase 4.6 covers it.

**The fix concept:** the repeatable migration `R__npdev_schema_additive_columns.sql` currently
emits only `ALTER TABLE … ADD COLUMN`. Because every `CREATE TABLE` the platform emits is already
`CREATE TABLE IF NOT EXISTS` (idempotent), the R__ script can safely *also* carry the
`CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` blocks for **business** tables — a
no-op on databases that have them, self-healing on databases that don't.

### R.1 — RED test (generator side)
In `NPDevGenerator\generator\src\test\java\com\npdev\generator\dbconfig\SchemaRealizationEmitterAdditiveColumnsTest.java`
(this class already exists and pins R__ content; REG-38's test is at ~line 76 as a pattern to
imitate) add a test: *emitting a model with concepts A and B produces an R__ file that contains
`CREATE TABLE IF NOT EXISTS` for both business tables (and their junction tables), positioned
BEFORE any `ALTER TABLE` statement.* Run GATE-GEN; confirm it fails because R__ has no CREATEs.

### R.2 — Implement in the emitter
File: `SchemaRealizationEmitter.java`. Locate `appendAdditiveColumns` (grep the name; ~line 195).
Change the R__ assembly so that, before the additive `ALTER` section, it appends the same
business-table `CREATE TABLE IF NOT EXISTS` blocks (and index + junction-table blocks) that the
V1 path builds via `appendBusinessTable` / `appendBonds`. **Extract the shared block-building into
a private method used by both V1 and R__ rather than duplicating string code.** Internal tables
stay V1-only (they never change per-model; `NpdevInternalTables` self-heals elsewhere). Ordering
rule inside R__: all CREATE TABLE blocks → all ADD COLUMN blocks → all constraint blocks (FKs must
come after both endpoint tables exist).

### R.3 — Prove it end-to-end (runtime side)
Add one scenario to the H2 matrix (new test class
`SchemaLifecycleExecutorNewTableOnExistingDbTest.java`, modeled on
`SchemaLifecycleExecutorAdditiveChangeTest.java`): boot with model v1 (one concept) → insert a row
→ "upgrade" to model v2 (adds a second concept) → boot again → assert the new table exists, the
old row survived, and history recorded a normal applied outcome. Mirror the scenario into the
Postgres proof matrix. Also run GATE-STATEFUL.
**Gate:** all gates. **Also:** update `docs/NPDEV_OPEN_ITEMS_REGISTER.md` REG-40 → FIXED
(tactical; strategic dedup lands in P4.6) and `docs/DATABASES_AND_MIGRATIONS.md` §15/§17.

---

## Part III — The core rebuild (Phases 0–5, from v1, expanded)

### Phase 0 — Inventory (no code) — ~0.5 session

**P0.1** Produce the pass inventory table in
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\schema-engine-rebuild\PASS_INVENTORY.md`:
one row per pass — name, source method + current line, exact inputs (which manifest maps, which
live `DatabaseMetaData`/`information_schema` reads), outputs/side effects, ordering dependencies
(what earlier pass it relies on), safety class(es) it can emit, and which existing test classes
pin it. The passes to inventory (grep each name in `SchemaLifecycleExecutor.java`):
`attemptInPlaceTableRenames`, `attemptInPlaceRenames`, `relaxNoLongerRequiredColumns` (~1570),
`tightenPlatformColumns` (~1679), `findSchemaAheadMissingColumns` (~437),
`databaseMigratedPastThisBuild` (~495), `classify` (~2102, note the REG-40 skip at ~2115),
`refuseIfRequiredBondColumnMissing` (~1840), `applyRequiredFieldBackfills` (~1434),
`applyUniqueConstraints` (~2658), plus the destructive executors (`executeDropColumn`,
`executeDropTableCascade`, `executeNarrowTypeDropAndRecreate`, `executeSurgicalDestruction`,
`executeWholeSchemaWipe`).

**P0.2** Pin the `SchemaManifest` record shape (all components, ~line 3189) and every place
"current state" is read today (grep `DatabaseMetaData`, `getColumns`, `information_schema` within
the file; list call sites).

**P0.3** Pin the **item-key format**: read `SchemaDeltaReport.java` fully (it is small) and
document the exact stable strings it feeds into `DestructiveAckToken`. These strings become the
universal item keys for diff items, impact-report lines, and hook claims. If the format is
ambiguous or lossy (can two different changes produce the same string?), record that finding —
Phase 2 must then extend the format *additively* (existing tokens must keep verifying).

**Deliverable:** the two OutsideRepo docs. **Gate:** none (read-only). Commit only the Progress
Ledger update.

### Phase 1 — `CurrentSchema`: the complete portable reader — ~1–2 sessions

New package: `com.finalexec.db.schemastate` in
`NPDevRuntimeHost\src\main\java\com\finalexec\db\schemastate\`. Wire it **nowhere** yet.

**P1.1** Define the immutable model (records):

```java
public record CurrentSchema(Map<String, CurrentTable> tables) {}            // key: lower-case table name
public record CurrentTable(String name, Map<String, CurrentColumn> columns, // key: lower-case column name
                           List<String> primaryKeyColumns,
                           List<CurrentUniqueConstraint> uniques,
                           List<CurrentForeignKey> foreignKeys,
                           List<CurrentIndex> indexes) {}
public record CurrentColumn(String name, String normalizedSqlType, Integer size, Integer scale,
                            boolean nullable, String defaultValueNormalized) {}
public record CurrentUniqueConstraint(String name, List<String> columns) {}
public record CurrentForeignKey(String name, List<String> columns, String referencedTable,
                                List<String> referencedColumns, String onDelete) {}
public record CurrentIndex(String name, List<String> columns, boolean unique) {}
```

Types are stored **normalized** through the existing
`com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization` (do NOT invent a second normalizer).
Defaults are stored normalized too (strip engine noise: Postgres `::character varying` casts,
H2 quoting) — write the tiny normalizer beside the reader with its own unit test.

**P1.2** `CurrentSchemaReader` — reads one schema from a JDBC `Connection` via
`DatabaseMetaData` + `information_schema`, engine-branching ONLY where catalogs genuinely differ
(document every branch with a comment naming the difference). Read: tables (filterable to a
name set), columns w/ type+size+scale+nullability+default, PK, uniques, FKs w/ ON DELETE, indexes.

**P1.3 (RED first)** Golden test `CurrentSchemaReaderGoldenTest` — creates via raw DDL a fixture
table exercising every dimension: nullable column, `NOT NULL DEFAULT` (literal + expression),
`VARCHAR(120)`, `NUMERIC(10,2)`, `TIMESTAMP WITH TIME ZONE`, a two-column unique constraint, an
FK with `ON DELETE`, a non-unique index. Assert every field of the read-back `CurrentSchema`.
**Run the same test class on H2 AND on the Postgres Testcontainer** (parameterize the way the
existing proof-matrix pair does — copy its container setup). Known landmines to assert explicitly:
`VARCHAR` vs `character varying`, `BIGINT` vs `int8`, `bool` vs `BOOLEAN`, default formatting,
H2 upper-casing of identifiers vs Postgres lower-casing (the reader lower-cases all identifiers).

**P1.4** Portability hardening: run the golden test against a schema created by the *platform's
own* V1 output (generate a small sample app's V1 SQL, execute it, read it back) so the reader is
proven against real NPDev DDL, not just hand-written fixtures.

**P1.5** Negative-space test: reader must not throw on exotic objects it doesn't model (views,
sequences, check constraints) — it skips them silently. Add them to the fixture.

**P1.6** Gate + ledger. **Gate:** new tests green on both engines; GATE-H2 + GATE-PG unchanged
green (nothing is wired in, so any change in existing results means you touched something you
shouldn't have).

### Phase 2 — `DesiredSchema` + `SchemaDiff` (pure, unwired) — ~1–2 sessions

**P2.1** `DesiredSchema` mirrors the `CurrentSchema` shape, built by a pure function from the
`SchemaManifest` (+ `ColumnFacts` for per-column semantics — the class-header directive in
`SchemaLifecycleExecutor` REQUIRES new code to consume `ColumnFacts`, never re-derive). Include
per-column provenance flags the diff needs: `platformManaged`, `requiredByModel`,
`literalDefault`, `bond`, `renamedFrom` (column + table level).

**P2.2** Pin the safety-class enum (this is THE central vocabulary; it deliberately merges the
live path's classes with the dead lineage's `MigrationRiskAssessmentBuilder` taxonomy so Phase 9
can retire that class without losing its idea):

```java
public enum SafetyClass {
    SAFE_TABLE_CREATE,     // new table, no data at risk (REG-40 as a first-class item)
    SAFE_ADDITIVE,         // new nullable column / additive constraint
    SAFE_RELAX,            // required -> optional
    SAFE_RENAME,           // declared renamedFrom, applied in place
    SAFE_WIDEN,            // TypeChangeMatrix WIDENING
    NEEDS_BACKFILL,        // new required column, literal default exists (auto) …
    NEEDS_HOOK,            // … or no literal default / expression-only (operator must supply hook)
    DESTRUCTIVE_DROP_COLUMN,
    DESTRUCTIVE_DROP_TABLE,
    DESTRUCTIVE_NARROW_TYPE,   // TypeChangeMatrix NARROWING / INCOMPARABLE
    MANUAL_REVIEW              // engine cannot decide (e.g. ambiguous default drift)
}
```

**P2.3** `SchemaDiffItem`: `{ itemKey (P0.3 format), table, column?, constraint?, SafetyClass,
before, after, resolution }` where `resolution` starts `UNRESOLVED` and later becomes
`AUTO / HOOK_CLAIMED / ACKNOWLEDGED`. `SchemaDiff` = ordered list + convenience filters
(`destructiveItems()`, `hookEligibleItems()`, `isEmpty()`).

**P2.4** The pure diff function
`SchemaDiffEngine.diff(DesiredSchema, CurrentSchema, RenameHints) -> SchemaDiff` with
**rename-resolution FIRST** (consume `renamedFrom` hints to pair old→new before classifying —
a diff alone cannot tell rename from drop+add; this is the highest-stakes correctness rule in the
entire programme), then table-level items, then column-level, then constraint-level. Platform
columns follow the special rules (§6 of `DATABASES_AND_MIGRATIONS.md`): missing → SAFE_ADDITIVE
with tighten; loosened → its own item class mapped to the tighten pass, never SAFE_RELAX.

**P2.5 (RED first, pure-unit, no DB)** `SchemaDiffEngineTest` covering AT MINIMUM: each
SafetyClass reachable; rename-vs-drop+add resolution (declared hint → SAFE_RENAME; no hint →
DESTRUCTIVE_DROP_COLUMN + SAFE_ADDITIVE pair); table rename; widening vs narrowing via
`TypeChangeMatrix`; required-no-default → NEEDS_HOOK; platform-column loosened → tighten item;
empty diff on identical schemas; new-table (REG-40) → SAFE_TABLE_CREATE.
**Gate:** new tests green; GATE-H2 + GATE-PG unchanged green.

### Phase 3 — Read-only shadow parity harness — ~1–2 sessions — **the crux**

**P3.1** After the live engine finishes its boot decision (success or refusal), compute
`CurrentSchema` (as it was BEFORE the engine acted — read it at the top of `beforeMigrate` and
hold it), build `DesiredSchema`, run `SchemaDiffEngine`, and compare the shadow's verdict with
what the live engine actually did (from the outcome it recorded in `npdev_schema_history` + the
classification it chose). Production behavior: **log-only** — a single line
`SHADOW_DIVERGENCE: expected=<...> actual=<...> items=<...>` at WARN. It must be impossible for
the shadow to change behavior: wrap the entire shadow computation in a try/catch that logs and
swallows.

**P3.2** Test-mode hard assertion: a hook (system property `npdev.schema.shadow.assert=true`, set
in test setup) that throws on divergence. Flip it on in a shared base/utility used by ALL
`SchemaLifecycleExecutor*Test` classes so **every existing matrix scenario becomes a parity
scenario for free**.

**P3.3** Drive parity to 100%. Every divergence is a **bug in the shadow** by definition (the
live engine is the authority until Phase 4). Fix the shadow; never adjust live behavior in this
phase. Keep a divergence log in the OutsideRepo folder — each entry: scenario, cause, shadow fix.
This log is the seed of Phase 4 confidence.

**P3.4 Gate (go/no-go for Phase 4):** GATE-H2 + GATE-PG fully green **with shadow assertion on**.
If parity cannot reach 100%, STOP the programme here and report — switching passes onto a
diverging shadow is how data gets destroyed.

### Phase 4 — Migrate passes onto the diff, one at a time — ~1 session per 2–3 passes

Rules for EVERY sub-step: one pass per commit; the pass's existing tests stay green **with
unchanged expectations**; all gates green before the next pass; shadow parity stays 100%
(the shadow now agrees with itself — the assertion stays on forever as a regression guard).

Order (lowest-risk / best-covered first):

- **P4.1** additive-columns decision (consumes `SAFE_ADDITIVE` items)
- **P4.2** delta-report / destructive itemization (renders `destructiveItems()` — token strings
  MUST remain byte-identical; `SchemaLifecycleExecutorDestructiveItemizationTest` pins this)
- **P4.3** required-bond refusal (`NEEDS_HOOK` bond items)
- **P4.4** relax (`SAFE_RELAX`)
- **P4.5** tighten platform columns (its own item class)
- **P4.6** **REG-40 strategic closure**: classify's missing-table skip (~line 2115) is replaced by
  consuming `SAFE_TABLE_CREATE` items. If Part II ran, this step just deletes the skip and pins
  the diff item; if Part II did NOT run, this step also does R.1–R.3's emitter work. Either way
  the end state is identical: new tables are a first-class, tested diff item.
- **P4.7** backfill (`NEEDS_BACKFILL`)
- **P4.8** classify itself (the classification IS the diff now; `classify` reduces to reading it)
- **P4.9** renames + unique constraints (last: highest stakes, best understood by then)

### Phase 5 — Finalize the core — ~1 session

- **P5.1** Delete the now-dead per-pass re-derivations and the conformance tests that existed only
  to pin duplication (e.g. `AdditiveColumnMirrorContractTest`, multi-entry-map pins) — keep the
  emitter-side reserved-name validation (different job) and keep the class-load drift guard.
- **P5.2** Upgrade `ExternallyManaged` verification (`verifyExternallyManagedSchemaCompatible`,
  ~line 313) to run `SchemaDiffEngine` against the full `CurrentSchema` → it now checks
  nullability, uniques, FKs, indexes. New matrix scenarios: externally-managed DB that passes
  column-shape but fails nullability → `EXTERNAL_REFUSED` with itemized reasons.
- **P5.3** Flip the `ColumnFacts` class-header directive and the REG-6 register entry to fully
  CLOSED; update `docs/SCHEMA_EVOLUTION.md` + `docs/DATABASES_AND_MIGRATIONS.md` §15/§16.
- **P5.4** Live proof: generate a fresh sample app AND an upgrade-across-model-change (use the
  GATE-STATEFUL harness + one real AppGen app, e.g. a WmsOffice clone on a scratch DB under
  `D:\WorkSpace\NPDev\Build`), verified booting green. **Gate:** everything.

---

## Part IV — The product phases (this is what the user asked for)

### Phase 6 — The Impact Report (GeneXus IAR equivalent) — ~1–2 sessions

One engine (`SchemaDiff`), one report object, three surfaces. **Depends on Phases 1–3 only**
(the report is read-only; it can ship while Phase 4 is still in progress).

**P6.1** `ImpactReport` (new class in `com.finalexec.db.schemastate`): wraps a `SchemaDiff` +
per-item **row-count probes** + verdict. Probes (read-only `SELECT COUNT(*)`, each item type gets
exactly one):

| Item class | Probe |
|---|---|
| DESTRUCTIVE_DROP_COLUMN | rows where the column IS NOT NULL (how much data dies) |
| DESTRUCTIVE_DROP_TABLE | total row count |
| DESTRUCTIVE_NARROW_TYPE (varchar shrink) | rows where `LENGTH(col) > newSize` |
| DESTRUCTIVE_NARROW_TYPE (numeric/other) | total non-null count (worst case) + `MANUAL_REVIEW` note |
| NEEDS_BACKFILL / NEEDS_HOOK | rows where the column would violate the new NOT NULL (for a new column: total row count) |

Probes run with a statement timeout (5 s each; on timeout record `count=-1, note="probe timed out"`).
A probe failure NEVER fails the report — it degrades to "unknown".

**P6.2** Two renderers, both deterministic and unit-tested against a fixed diff fixture:
- `ImpactReportJson` → machine shape (schema below, add to `NPDevContract/schemas/` as
  `impact-report.schema.json`):
```json
{ "generatedAt": "...", "fingerprintFrom": "...", "fingerprintTo": "...",
  "verdict": "NO_CHANGES | SAFE | NEEDS_ATTENTION | DESTRUCTIVE",
  "acknowledgmentToken": "present only when verdict=DESTRUCTIVE",
  "items": [ { "itemKey": "...", "table": "...", "column": "...", "safetyClass": "...",
               "before": "...", "after": "...", "rowsAffected": 0, "probeNote": "...",
               "resolution": "UNRESOLVED|AUTO|HOOK_CLAIMED|ACKNOWLEDGED",
               "proposedConversionSql": "phase 8" } ] }
```
- `ImpactReportText` → the human table (aligned columns, one line per item, DESTRUCTIVE items
  prefixed `!!`, summary footer `N safe / N attention / N destructive`). This text is what boot
  refusals and the PowerShell surface print — converge, don't fork.

**P6.3 Surface 1 — boot refusal.** The destructive refusal path (currently built from
`SchemaDeltaReport`) renders `ImpactReportText` instead. Token strings stay byte-identical
(pinned by the P4.2 tests). Additionally, EVERY upgrade boot (refused or not) writes
`impact-report.json` to `runtime-data/impact-reports/<timestamp>-<from>-<to>.json` (retain last
10, same retention pattern as `SchemaDropSnapshotWriter`).

**P6.4 Surface 2 — pre-deploy, against the live DB.** New Spring profile behavior: property
`npdev.schema.lifecycle.mode=REPORT_ONLY` (default `APPLY`). In REPORT_ONLY the executor computes
the report, writes JSON + prints text to stdout, **issues zero DDL and zero writes** (claim not
taken, history not written), and exits the JVM with code `0` (NO_CHANGES/SAFE), `2`
(NEEDS_ATTENTION), `3` (DESTRUCTIVE). Then wire the operator surface: add `-ImpactOnly` to
`D:\WorkSpace\NPDev\NPDev_General\scripts\appgen\Build-NpdevApp.ps1` — builds the jar, runs it
once with `--npdev.schema.lifecycle.mode=REPORT_ONLY` against the app's configured DB, prints the
table, propagates the exit code. Document clearly in the script header: `-PlanOnly` = model-vs-
previous-model (no DB needed, existing); `-ImpactOnly` = model-vs-**live-database** (the GeneXus
impact; DB must be reachable). RED-first: a scripted test in GATE-STATEFUL style asserting exit
codes for a safe upgrade and a destructive one.

**P6.5 Surface 3 — ControlPanel.** `GET /api/admin/schema-migration/impact` in a new
`SchemaImpactController` beside `SchemaAcknowledgmentController.java` (same auth: SUPERUSER via
`X-Super-User-Key`, same pattern — copy its gating exactly). Returns the JSON report computed
on demand (read-only, safe on a running app). A minimal static page
(`controlpanel` static assets, follow the existing seed-data.html pattern) renders the table +
the acknowledgment token when destructive. RED-first: controller test with a mocked diff.

**P6.6** Docs: new `docs/IMPACT_REPORTS.md` (operator how-to, all three surfaces, exit codes) +
cross-link from `DATABASES_AND_MIGRATIONS.md` §12. **Gate:** all gates + GATE-STATEFUL.

### Phase 7 — Conversion hooks (the freedom pillar) — ~2 sessions

GeneXus "conversion programs", NPDev-disciplined: the operator brings SQL; the engine verifies
outcomes. **v1 is SQL-only.** (A `DataMigrationHook` Java interface is deliberately deferred —
code-bearing objects belong to the ADR-0003 track; leave a one-paragraph design note, do not
build it now.)

**P7.1 The artifact.** In the app definition (layer 2, e.g.
`D:\WorkSpace\NPDev\AppGen\apps\<app>\definition\migrations\`):

```
definition/migrations/<ordinal>-<slug>/hook.json
definition/migrations/<ordinal>-<slug>/convert.sql          (common)
definition/migrations/<ordinal>-<slug>/convert.h2.sql       (optional engine override)
definition/migrations/<ordinal>-<slug>/convert.postgres.sql (optional engine override)
```

`hook.json` (add `conversion-hook.schema.json` to `NPDevContract/schemas/`):
```json
{ "id": "2026-07-30-split-customer-name",
  "description": "Copy name into first_name/last_name before dropping name",
  "claims": [ "<itemKey exactly as printed in the Impact Report>" ],
  "verifySql": "SELECT COUNT(*) FROM customers WHERE first_name IS NULL AND name IS NOT NULL",
  "verifyExpect": 0 }
```
Rules: `claims` is non-empty; every claim must use the canonical item-key format (the Impact
Report prints keys precisely so operators can copy-paste them); `verifySql` is optional but
recommended (a read-only post-check the engine runs after the hook).

**P7.2 Generator plumbing.** `SchemaRealizationEmitter` (or a sibling
`ConversionHookEmitter` — prefer sibling, keep the emitter single-purpose) validates hook.json
against the schema at generation time (bad hook = generation error, not a boot surprise) and
copies the folder into the FinalApp at `src/main/resources/db/conversion-hooks/<id>/`. GATE-GEN
test: hooks land in the jar; invalid hook.json fails generation with a clear message.

**P7.3 The execution contract (runtime).** New `ConversionHookRunner` in `com.finalexec.db.schemastate`,
invoked by the executor at ONE fixed pipeline point: **after** the safe convergent passes
(renames, relax, tighten) and **before** the destructive decision. Algorithm (implement exactly;
each numbered rule gets its own RED test in P7.5):

1. Compute the diff. If no unresolved items → hooks are skipped entirely (idempotent re-boot).
2. Select hooks whose `claims` intersect the unresolved item set. A hook none of whose claims
   match is skipped with an INFO log (stale hook ≠ error — the diff may already be converged).
3. Execute selected hooks in `<ordinal>` order, each in its own transaction (engine variant file
   if present, else common). SQL runs with the same connection rights as the migration itself.
4. After each hook: run its `verifySql` if present — mismatch → **abort the boot** (history row
   `HOOK_VERIFY_FAILED`, itemized message). Nothing destructive has happened yet.
5. After all hooks: **RE-DIFF against the live DB.** For every claimed item: if the item is gone
   → mark `resolution=HOOK_CLAIMED` in history detail. If a claimed item is STILL present →
   **refuse the boot**: `hook '<id>' claimed '<itemKey>' but the change is still required`.
   Claims are promises the engine checks, never trusts.
6. **Sanctioned destruction rule:** a DESTRUCTIVE item that a hook resolved (e.g. the hook itself
   copied the data and dropped the column) requires NO acknowledgment token — authoring the hook
   IS the acknowledgment, and history records `HOOK_APPLIED {id, claims, sqlHash}`. Destructive
   items NOT claimed by any hook still require the token exactly as today. The two mechanisms
   compose: token for "yes, delete it", hook for "here's how to migrate it".
7. Every hook execution (start, success, failure, verify result, re-diff verdict) writes an
   `npdev_schema_history` row. A failed hook aborts before any destructive step, always.

**P7.4 Impact Report integration.** Report items show `resolution`: an item claimed by a present
hook renders as `HOOK: <id>` instead of `!!` — the operator sees, pre-deploy, exactly which
changes are covered and which still need a token or a new hook. The DESTRUCTIVE verdict/exit-code
3 applies only to *unclaimed* destructive items.

**P7.5 Tests (RED first, one per contract rule above), H2 + Postgres:** hook resolves a
NEEDS_HOOK backfill → boot green, no token; hook claims but doesn't resolve → refused; verifySql
mismatch → refused before destruction; unclaimed destructive item still token-gated; re-boot
after success → hooks skipped (rule 1); hook SQL error → transaction rolled back, boot refused,
history row written; two hooks ordered by ordinal.

**P7.6 Operator flow doc** in `docs/IMPACT_REPORTS.md`: run `-ImpactOnly` → copy item keys →
write hook folder → re-run `-ImpactOnly` (items show HOOK-claimed) → deploy. This loop IS the
GeneXus reorganization experience.

**P7.7 Live proof:** on a scratch copy of a real app DB (under `D:\WorkSpace\NPDev\Build`,
never the live WmsOffice DB): perform a real split-column conversion end-to-end via a hook.
Evidence to OutsideRepo. **Gate:** all gates + GATE-STATEFUL + GATE-GEN.

### Phase 8 — Proposed conversion SQL (platform drafts, operator decides) — ~1 session

**P8.1** For `DESTRUCTIVE_NARROW_TYPE` and convertible `MANUAL_REVIEW` items, the Impact Report's
`proposedConversionSql` field carries a platform-drafted, **never auto-executed** script using the
copy-convert pattern:

```sql
ALTER TABLE t ADD COLUMN col__new <newtype>;
UPDATE t SET col__new = CAST(col AS <newtype>);   -- or SUBSTRING for varchar shrink
-- verify: SELECT COUNT(*) FROM t WHERE col IS NOT NULL AND col__new IS NULL;  -- expect 0
ALTER TABLE t DROP COLUMN col;
ALTER TABLE t RENAME COLUMN col__new TO col;
```

Generated per engine via the existing `TypeChangeMatrix` knowledge; emitted in both JSON and the
text report as a ready-to-paste hook body (`convert.sql` + suggested `verifySql`).

**P8.2** Unit tests: one proposal per narrowing family (varchar shrink, numeric precision,
incompatible-cast → proposal omitted with note "no safe automatic conversion — write a custom
hook"). Proposals must be deterministic (no timestamps inside the SQL text).

**P8.3** Doc the pattern in `docs/IMPACT_REPORTS.md`. Explicit non-goal, recorded: NPDev never
auto-runs a proposal; adoption is always the operator copying it into a hook (GeneXus auto-runs
conversions; NPDev deliberately keeps a human between draft and execution — that is the
discipline half of the brief). **Gate:** GATE-H2 + GATE-PG.

### Phase 9 — Retire the dead lineage + programme closure — ~0.5 session

**P9.1** Prove-then-delete `com.finalexec.npdev.migration.*` (12 classes) and their generator-side
tests (`NPDevGenerator\generator\src\test\java\com\npdev\generator\migration\*Test.java`) and the
`db-history` snapshot/`model_delta.sql` emit path. Procedure: grep each class name across the
whole repo; paste the grep output into the commit message proving only the deleted tests
reference them. If ANY main-code reference exists → STOP, report (this plan believes there are
none as of 2026-07-24). Also remove the `FinalAppAssembler` preserved-path entry for
`db/migration-plans/` (grep `migration-plans` in `FinalAppAssembler.java`, ~line 319).

**P9.2** Keep `MigrationPlanEmitter` (model-vs-model preview is still useful without a DB) but
re-document it honestly: in `Build-NpdevApp.ps1` help and docs, `-PlanOnly` = "offline estimate",
`-ImpactOnly` = "the truth (live DB)".

**P9.3** Final docs sweep: `DATABASES_AND_MIGRATIONS.md` §12 operator matrix gains the hook row
("Manually program the SQL — per-item, verified: conversion hooks"); §15 limitations prune
everything this programme closed (REG-40, ExternallyManaged column-shaped-only, all-or-nothing
freedom); §20 rewritten. `NPDEV_OPEN_ITEMS_REGISTER.md`: REG-6 CLOSED, REG-40 CLOSED. Add a
knowledge card `knowledge/cards/` for the hook workflow (follow the existing card schema).
**Gate:** all gates one final time + one full live app rebuild via the `rebuild-app` skill flow.

---

## Part V — Risk register (each risk has a RED-capable guard; extend, never shrink)

| Risk | Guard |
|---|---|
| Platform column `NOT NULL` weakened (T-B1 recurrence) | tighten/relax matrix tests + shadow-parity case on `tenant_id` + P2.5 platform-column diff test |
| Rename read as drop+add → silent data loss | P2.5 rename-resolution unit tests + `SchemaLifecycleExecutorInPlaceRenameTest` + P4.9 last-position rule |
| Destructive change applied without refusal/itemization/snapshot | `SchemaLifecycleExecutorDestructiveItemizationTest` + P4.2 byte-identical token pin |
| Shadow silently diverges in prod | P3.1 log-only + try/catch-swallow; P3.2 hard-assert in every test; Phase 4 gated on 100% parity |
| Hook claims more than it does | P7.3 rule 5 re-diff refusal + its RED test |
| Hook destroys data outside its claim | hooks run BEFORE the destructive decision; unclaimed destructive items remain token-gated (rule 6 test) |
| Probe queries slow/hang a huge production table | P6.1 5-second statement timeout + degrade-to-unknown test |
| REPORT_ONLY mode accidentally writes | P6.4 test asserting zero claim/history/DDL after a REPORT_ONLY run (diff DB state before/after) |
| Cross-engine type mapping wrong (H2 masks Postgres) | P1.3 golden reader test on both engines + GATE-PG on every step |
| Token/item-key format drift breaks existing acknowledgments | P0.3 pin + P4.2 byte-identical assertion |
| Fingerprint fast-path broken (perf/behavior) | unchanged-boot scenario in both matrices, now also asserting the shadow is skipped |

## Part VI — Rollback

Phases 0–3, 6 (report only), 8: read-only additions — reverting any commit is a no-op on runtime
behavior. Phase 4: one pass per commit; a regression reverts exactly that commit (earlier
migrated passes already proved green independently). Phase 7: gated by the presence of hook files
— an app with no `definition/migrations/` folder is bit-for-bit on the old behavior; runner code
reverts in one commit. Phase 9 deletions: single commits with grep-proof in the message; revert
restores the dead code untouched.

## Part VII — Honest budget

Core rebuild (P0–P5): ~5–8 sessions. Product phases (P6–P9): ~4–6 sessions. Tactical REG-40
(Part II): ~1 session, can land first and pays for itself immediately. Docker required for every
gated step. This is deliberately slow: it is the most-fixed, highest-consequence code in the
platform, and the point is to *stop manufacturing* REG-6-class bugs while adding the operator
experience — not to add one more bug in a hurry. If a phase's gate can't go green, **STOP and
report**; never proceed to the next phase on a red or skipped gate.

## Part VIII — Items this programme closes

- **REG-6** — canonical desired-vs-current model; closed at P5.3.
- **REG-40** — new tables on existing DBs; tactically at Part II or strategically at P4.6.
- **REG-38 class** — "does X exist?" becomes a first-class `SchemaDiff` query; the bug class
  cannot recur once passes stop re-deriving existence.
- **`ExternallyManaged` column-shaped-only verification** — closed at P5.2.
- **Operator matrix "no per-step custom hook" limitation** (`DATABASES_AND_MIGRATIONS.md` §12) —
  closed at Phase 7.
- **No pre-deploy live-DB visibility** — closed at Phase 6 (`-ImpactOnly` + ControlPanel).
- **Dead `com.finalexec.npdev.migration` lineage** — retired at Phase 9 (its risk taxonomy lives
  on inside `SafetyClass`).
