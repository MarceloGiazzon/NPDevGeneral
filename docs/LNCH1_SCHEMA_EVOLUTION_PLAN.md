# LNCH-1 — Schema Evolution for Live Apps: Dedicated Implementation Plan

> **Status:** APPROVED PLAN — not started · **Priority:** P0 (the last existential launch blocker)
> **Effort:** XL, phased into 9 independently-committable phases
> **Written:** 2026-07-17, verified against the working tree at commit `996b939`
> **Audience:** an AI implementation session (or human) that has NOT read the project's history.
> Follow this document literally. Where it says VERIFY, do the verification before writing code —
> the codebase has changed before and will change again; this plan describes the state at
> `996b939`, and every claim was checked against real files on that commit.

---

## 0. Mission statement (read this twice)

NPDev generates a Spring Boot "FinalApp" from a JSON model. Today, when the model of an
**already-deployed app with real data** changes, the platform can only:

1. Auto-apply the change if it is *purely additive* (a new non-bond column on an existing table), or
2. **Drop every table and recreate the whole schema** (after writing a JSONL data snapshot), gated
   behind a single blanket `destructiveAllowed` flag.

Renames and type changes are already *detected and correctly classified* — but still executed by
the destructive whole-schema path. The mission of LNCH-1 is to close that gap:

- A **field rename** must become an in-place `ALTER TABLE ... RENAME COLUMN` — zero data loss.
- A **concept (table) rename** must become an in-place `ALTER TABLE ... RENAME TO` — zero data loss.
- A **safe type widening** (e.g. `INT → BIGINT`) must become an in-place `ALTER COLUMN` — zero data loss.
- A **destructive change** (drop field, drop concept, narrowing type change) must be executed
  **surgically** (only the named column/table), only after an **explicit, itemized, hash-bound
  acknowledgment** — never as a whole-schema wipe authorized by a stale boolean.
- A **tightened invariant** (new unique / compound unique) must be **pre-checked against existing
  data**, with violating rows reported, instead of letting the migration fail halfway.
- A **new required field** on a table with rows must be refused unless a default/backfill is
  declared, and the backfill must actually run.
- Every applied change must leave an **audit row** in the database.
- The person running the upgrade must be able to see the full plan — what will change, what is
  safe, what destroys data — **before** anything touches the database.

What is explicitly **out of scope** for LNCH-1 (record as future items, do not build):

- Automatic rename *inference* (uid-based identity). Renames are declared by the author via the
  existing `renamedFrom` marker. See §2.1 for why.
- Expression-valued backfills (only literal defaults are backfilled in v1; expression backfill is
  refused with a clear message).
- Automated restore from the JSONL pre-drop snapshots (they remain a manual recovery artifact).
- Cross-database data migration (H2 → Postgres). Different feature.
- InMemory-storage apps: they have no DDL. All phases must no-op cleanly for them (the existing
  `manifest.physicalDatabase()` guard already does this — preserve it).

---

## 1. Map of the existing machinery (verified 2026-07-17)

You are NOT building from scratch. The platform already has a working schema-lifecycle system.
Read every file in this table before writing any code (the two large ones: Grep for the named
methods, then Read with offset/limit — do NOT full-read files over ~100 KB).

| File (repo-relative) | What it does today |
|---|---|
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` | **The runtime authority.** A Spring `FlywayMigrationStrategy` (`@Component`). On boot: loads the generation-time `SchemaManifest` from classpath, reads the stored fingerprint from the `npdev_schema_metadata` table, and if they differ, classifies the change by **introspecting the live database** (`classify(...)`) into `SchemaChangeClassification`: `SAFE_ADDITIVE` (auto-applied via the repeatable migration + `flyway.repair()`), `RENAME_DETECTED` (a field's declared `renamedFrom` matches a column the live DB still has under the old name — *labeled but still destructive today*), `TYPE_CHANGE_DETECTED` (*labeled but still destructive today*), or destructive. The destructive path requires `manifest.destructiveAllowed()`, calls `SchemaDropSnapshotWriter.snapshotBeforeDrop(...)`, then `DROP TABLE ... CASCADE` for **every** manifest-listed table (declaration order reversed; CASCADE is deliberate — see the inline comment about FK dependency order). Contains the nested `public record SchemaManifest(...)` (~line 493) and manifest loader (~line 431). |
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaDropSnapshotWriter.java` | Before any destructive drop: dumps every table to `runtime-data/schema-snapshot-before-drop/<UTC-timestamp>/<table>.jsonl` + `_summary.json`, prunes old snapshot dirs, logs loudly ("DATA LOSS NOT SNAPSHOTTED" on failure but does not abort). No restore path exists — by design. |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java` | **The generation-time authority.** Emits the bootstrap DDL (under `classpath:db/schema-realization` in the generated app), the schema manifest JSON (fingerprint, `physicalDatabase`, `destructiveAllowed`, `businessTables`, `internalTables`, per-column SQL types, `renamedFrom` threading), the additive repeatable migration, tenant-scoped uniques (incl. compound), FK constraints from bonds, secondary indexes (LNCH-6), and the `row_version` column (LNCH-16) on both fresh CREATE and the additive path. Its test suite (`SchemaRealizationEmitter*Test.java`, ~13 classes in `NPDevGenerator/generator/src/test/java/com/npdev/generator/dbconfig/`) is the pattern to imitate for every emitter change. |
| `NPDevContract/dsl/.../ast/FieldAst.java`, `.../compiled/CompiledField.java` | `renamedFrom` already exists at the field level, threaded through parser → compiler → canonical JSON. |
| `NPDevContract/dsl/.../compiled/CompiledModelCanonicalJson.java` + `CompiledModelCanonicalJsonReader.java` | The canonical serialized compiled model. **Every generated app ships one and reads it at boot** (via the generated `NPDevModelProvider`, template `npdev-templates/npdev-runtime-model-provider.mustache`). This IS the "model snapshot in the app" — it already exists; do not invent a second one. |
| `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/StructuralSchemaAssetConformanceTest.java` | Asserts all **four** copies of `model.schema.json` stay in sync. It exists because the copies drifted twice, historically. |
| `NPDevGenerator/generator/src/test/java/com/npdev/generator/migration/StatefulMigrationPlannerTest.java` + `MigrationAuthorityQuarantineAssertions.java` | **A quarantine guard.** An older generator-side migration/model-diff authority was removed, and this test asserts it never returns: the package directory `NPDevGenerator/generator/src/main/java/com/npdev/generator/migration` MUST NOT exist, and versioned migrations `V5001..V5014` must not reappear in `NPDevGenerator/db-history/.../db/migration` or `NPDevRuntimeHost/.../db/migration`. **Constraint on you: put no production code in `com.npdev.generator.migration`.** See §2.2. |
| `NPDevGenerator/db-history/src/main/resources/db/migration/R__npdev_schema.sql`, `.../db/migration-plans/latest-model-delta.sql` | Residue of the old and current mechanisms. `FinalAppAssembler.java` (~line 319) whitelists `src/main/resources/db/migration-plans/` in the assembled app. **Phase 0 task: audit what currently writes/reads these and document it before touching them.** |
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/JdbcBusinessConceptStore.java` | The JDBC concept store. Uses `DataSourceUtils#getConnection` (joins ambient Spring transactions — LNCH-17 fix). Has the `row_version` CAS and a backward-compat fallback for tables without the column. Relevant because migrations change the tables this class reads. |
| `scripts/quality/run-stateful-additive-migrations-check.ps1` | Existing gate: runs the quarantine test + drives the `canonical-demo` model (`NPDevGenerator/resources/Models/canonical-demo/model.json`) through plan/generate cycles into `build/cp8-stateful-additive-migrations`. Extend it or add a sibling gate — do not silently change what it asserts. |
| `docs/beta/beta1-gap-analysis-vs-original-vision.md` | Narrative history of how the current classification mechanism was built and live-verified, including a real bug ("columns match exactly" fast path short-circuited type checking → pure type change misclassified as SAFE_ADDITIVE). Read the "Renames" and "Type change" sections. |
| `docs/architecture/APP_UPGRADE_CONTRACT.md` (LNCH-21) | The regeneration contract: `Build-NpdevApp.ps1` **wipes the entire FinalApp output root every build** (`deleteBeforeMount`); app customizations survive because they're re-mounted from `apps/<App>/web/` (outside the wiped tree). Consequence: nothing you write into the FinalApp output tree survives regeneration — durable state lives in the model, the generator, or the *database*. |
| `docs/architecture/FLOW_TRANSACTION_CONTRACT.md` (LNCH-17) | The platform's transaction semantics. Migration DDL has its own rules (Postgres DDL is transactional; H2's mostly is not) — see §6.3. |
| `docs/CONFIGURATION.md`, `docs/DEPLOYMENT.md` | Startup validation + docker-compose deploy (LNCH-7). Your new refusal messages must follow the same pattern: link a stable anchor in docs. |

**Key architectural fact to internalize:** classification happens **at app boot, from live-DB
introspection** — not from model-vs-model diffing at generation time. That design survived contact
with reality (the DB is the only truth about what actually exists) and this plan keeps the
runtime executor as the final authority. The generator gains a *preview* (the plan artifact,
Phase 6) — it never becomes the authority.

---

## 2. Design decisions (already made — do not re-litigate; §2.6 lists what still needs the owner)

### 2.1 Rename identity = the existing `renamedFrom` marker, not uids

An earlier draft of this roadmap proposed immutable `uid`s on concepts/fields for exact rename
detection. Decision: **stay with `renamedFrom`**. Reasons: (a) it already exists end-to-end and is
live-verified; (b) the platform's ratified authoring strategy is AI-first (ADR-0006) — the MCP
authoring loop can be *instructed* to always set `renamedFrom` when renaming, making the manual
marker reliable in practice; (c) uids retrofitted onto pre-uid models cannot disambiguate old
renames anyway. Record uid-based identity as a future hardening item in
`docs/OPEN_GAPS_AND_ROADMAP.md`; do not build it.

Hygiene rules you must implement for the marker (Phase 1): `renamedFrom` equal to the field's own
current name → `SemanticValidator` warning; `renamedFrom` naming a field that *still exists* in
the same concept → validation **error** (ambiguous); an unmatched `renamedFrom` (live DB has no
such column — e.g. a fresh install, or the rename already ran) → silent no-op at runtime.

### 2.2 New generator-side code lives in `com.npdev.generator.schemaevolution`

The quarantine test (§1) forbids `com.npdev.generator.migration` in `src/main`. That quarantine
exists because an older migration authority (model-diff + V5001–V5014 SQL) was deliberately
killed in favor of the fingerprint/classification design. **Respect it.** All new generator-side
classes go in `NPDevGenerator/generator/src/main/java/com/npdev/generator/schemaevolution/`.
Do not modify `MigrationAuthorityQuarantineAssertions` except in Phase 0, where you must add ONE
line of documentation to `StatefulMigrationPlannerTest` (a comment) stating that
`schemaevolution` is the sanctioned successor package and why the quarantine still stands.

### 2.3 The runtime executor stays the authority; the generator emits a preview

The upgrade flow is: generator computes a **migration plan** (model-vs-previous-canonical-model
diff, Phase 6) → human/AI reviews it → destructive items are acknowledged by hash →
`SchemaLifecycleExecutor` independently re-derives the classification from the live DB at boot
and **refuses to proceed if its derivation disagrees with the acknowledged plan**. Two
independent derivations that must agree is the safety mechanism; neither alone is trusted.

### 2.4 Execution order inside the executor (this ordering is load-bearing)

When a fingerprint mismatch is classified, apply changes in this order, as separate steps:

1. **Concept (table) renames** — so every later step sees current table names.
2. **Field (column) renames** — so additive/type classification sees current column names.
3. **Safe type widenings.**
4. **Additive changes** (new tables, new columns, new indexes) — the existing repeatable-migration
   path, unchanged.
5. **Backfills + NOT NULL promotion** (Phase 5).
6. **Constraint additions** (uniques/FKs), preceded by their data pre-checks (Phase 5).
7. **Surgical destructive items** (drop column / drop table), only with a matching acknowledgment.

Renames executed in steps 1–2 must be recorded in `npdev_schema_history` (Phase 4) **before**
executing, and the stored fingerprint must only be updated after ALL steps complete. If the app
crashes mid-sequence, the next boot re-classifies from the live DB — every individual operation
must therefore be **idempotent-by-check** (e.g., "rename A→B" is a no-op if A is gone and B
exists). Write a test for every operation's re-run.

### 2.5 Blanket `destructiveAllowed` is deprecated, not removed

Keep reading the old flag for backward compatibility (apps generated before this work), but when
the new itemized acknowledgment (Phase 4) is present it takes precedence, and when *neither* is
present a destructive classification refuses boot with the itemized report. Log a deprecation
warning whenever the blanket flag alone authorizes destruction.

### 2.6 Decisions that need the project owner (ask BEFORE the phase that needs them, not during)

1. **(Needed by Phase 4)** When a destructive change is acknowledged and executed surgically, do we
   still write the JSONL snapshot for just the affected table(s)? Recommended: yes (snapshot only
   the affected tables — cheap insurance). Confirm.
   **RATIFIED 2026-07-17: Yes — snapshot affected table(s) on the surgical path, reusing
   `SchemaDropSnapshotWriter` with a table-subset entry point, per the recommendation.**
2. **(Needed by Phase 6)** The `-Upgrade` UX: is acknowledgment via a CLI parameter
   (`-AcknowledgeDestructive <token>`) sufficient, or must there also be an interactive
   ControlPanel surface? Recommended: CLI-only for v1. Confirm.
   **RATIFIED 2026-07-17: CLI + ControlPanel UI — owner wants an interactive ControlPanel surface
   in addition to `-AcknowledgeDestructive <token>`, diverging from the CLI-only recommendation.
   Phase 6 scope is amended: 6.2 gains a ControlPanel screen (surfaced under the existing
   ControlPanel/SUPERUSER surface, see `controlpanel_superuser_feature` history) that displays the
   pending plan (safe items plainly, destructive items with the itemized report + token) and lets
   a SUPERUSER-authenticated operator submit the acknowledgment token, calling the same
   token-validation path the CLI flag uses. This is additive UI on top of the CLI mechanism, not a
   replacement — the CLI flag remains required for scripted/CI upgrades (Phase 7's gate scenarios
   drive the CLI path). Scope/design of the ControlPanel screen to be detailed at the start of
   Phase 6.**
3. **(Needed by Phase 7)** Which real app is the live-verification target? Recommended:
   `simple-user-registry-h2local` for H2 and the compose-stack sample from `docs/DEPLOYMENT.md`
   for Postgres. Confirm both still exist and boot before relying on them.
   **RATIFIED 2026-07-17: Confirmed — `simple-user-registry-h2local` for H2, the compose-stack
   Postgres sample from `docs/DEPLOYMENT.md` for Postgres, per the recommendation. Existence/boot
   to be verified at the start of Phase 7 per the original instruction.**

---

## 3. Non-negotiable guardrails (violating any of these has caused real bugs in this repo)

1. **The four schema copies.** Any change to `model.schema.json` must be applied to ALL FOUR files:
   `NPDevContract/schemas/model.schema.json`, `NPDevContract/schemas/authoring/model.schema.json`,
   `NPDevContract/dsl/src/main/resources/schema/model.schema.json`,
   `NPDevContract/dsl/resources/Schemas/model.schema.json` (capital-S `Schemas`; NOT anything under
   `schemas/archive/`). Then run `StructuralSchemaAssetConformanceTest`. It exists because this
   went wrong twice.
2. **The canonical-JSON field-loss bug class.** Four separate times, a new AST/compiled field was
   added but not threaded through `CompiledModelCanonicalJson` + `CompiledModelCanonicalJsonReader`
   and/or `ModelResolver`'s sanitize/merge/clone passes — each time producing an app that
   **silently lost the feature at boot** (worst case: every `forEach` loop body). For every field
   you add to any AST or Compiled record: (a) grep every construction site (`new ConceptAst(`,
   `new CompiledConcept(`, etc.) including `ModelResolver` and `CompiledModelCanonicalJson*`;
   (b) extend the round-trip test (`CompiledModelCanonicalJsonReaderTest`) with the new field
   populated. Phase 0 adds a reflective ratchet so the fifth occurrence is impossible.
3. **Do not run `:generator:test` and conclude the DSL is green.** `:generator:test` only
   *compiles* `:dsl`; it never runs `:dsl`'s tests. Run both suites explicitly.
4. **Never write into a generated app's `npdev-generated/` tree by hand** — it is SHA-256
   whole-tree hash-guarded (`StrictExecutionValidator`); the app will refuse to boot with a
   "signature mismatch". Change the *generator* and regenerate.
5. **Build output goes to `D:\WorkSpace\NPDev\Build`** — never inside the repo
   (`docs/BUILD_OUTPUT_LOCATION_POLICY.md`). Evidence/scratch goes to
   `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo`.
6. **After changing kernel/adapter/RuntimeHost Java, restage jars before regenerating an app:**
   `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs`
   (the `-RuntimeHostLibsDir` must be passed explicitly — the script's default does NOT match
   `Build-NpdevApp.ps1`'s default, and the running app silently keeps a stale jar). The
   `rebuild-app` maintainer skill (`.claude/skills/rebuild-app`) automates the full three-cache
   refresh — prefer it.
7. **Never use tenant id `"default"` in verification** — it is a reserved sentinel. Register a
   real tenant (`POST /api/admin/tenants`, e.g. `demo`) first.
8. **New gate scripts must invoke `Build-NpdevApp.ps1` as a child process**
   (`pwsh -NoProfile -File ...`), never in-runspace — `Set-StrictMode` leaks through the `&` call
   operator and breaks that script's optional-property checks (found and fixed 2026-07-17 in
   `run-app-upgrade-contract-gate.ps1`; imitate its invocation pattern).
9. **Windows file-lock gotcha:** regenerating an app can hit a transient VS Code Java/Gradle lock
   on the fresh build dir — bump the build-root suffix (`-alt`/`-hNN`) rather than fighting it.
10. **Small bounded commits, one phase (or sub-slice) per commit, no `git add .`**, evidence of
    verification in the commit message. No regex-patching of Java files — make real edits.
11. **Every SQL statement that interpolates an identifier must go through the existing
    `safeIdentifier(...)` pattern** (see `SchemaLifecycleExecutor`) — table/column names come from
    models, and models are author-supplied input.

---

## 4. The phases

Each phase ends with: all named tests green, the two relevant gates green
(`scripts/quality/run-stateful-additive-migrations-check.ps1` and
`scripts/quality/run-runtimehost-gate.ps1`), a commit, and a one-paragraph evidence note in
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\phase-<n>.md`.

---

### Phase 0 — Recon, ratchets, and decision ratification (S)

**Goal:** freeze the ground truth this plan builds on; make the recurring bug class impossible;
get §2.6's first answers.

Tasks:

0.1 Read every file in §1's table (respect the large-file rule). Write a short findings memo to
    the evidence directory. Specifically resolve these VERIFY items:
    - What exactly does `SchemaRealizationEmitter` emit as the manifest (exact JSON keys — read
      the manifest-writing method and one emitted manifest from a real build under
      `D:\WorkSpace\NPDev\Build`)?
    - What writes and what reads `NPDevGenerator/db-history/.../R__npdev_schema.sql` and
      `.../migration-plans/latest-model-delta.sql` today? (Grep for the filenames across the repo,
      including `scripts/`.) Document; if they are dead residue of the quarantined mechanism,
      propose deletion to the owner — do not delete unilaterally.
    - Confirm the exact `SchemaChangeClassification` enum values and the `classify(...)`
      introspection logic (which JDBC metadata calls, how bond/FK columns are excluded from the
      additive-eligible set).
    - Confirm how the stored fingerprint is written/read in `npdev_schema_metadata`.
0.2 **Reflective canonical-JSON round-trip ratchet.** In `NPDevContract/dsl/src/test/java/...`,
    add `CanonicalJsonRoundTripCompletenessTest`: for each AST/Compiled record type serialized by
    `CompiledModelCanonicalJson`, build a maximally-populated instance (every component non-null /
    non-empty; use reflection over record components to detect NEW components automatically),
    write → read → assert component-wise equality. The test must FAIL if someone adds a record
    component that the writer/reader ignores. This protects every phase below and everything
    after LNCH-1. Commit it separately — it is valuable even if LNCH-1 stalls.
0.3 Add the sanctioned-successor comment to `StatefulMigrationPlannerTest` (§2.2).
0.4 Ask the owner §2.6's questions 1–3 in one batch. Record answers in this file (edit §2.6
    in place, marking each `RATIFIED <date>: <answer>`).

**DoD:** findings memo written; ratchet test committed and red-proven (temporarily comment out one
field's serialization locally to see it fail, then restore); §2.6 answers recorded.

---

### Phase 1 — Field renames applied in place (M)

**Goal:** `RENAME_DETECTED` for a field stops being destructive.

Tasks:

1.1 **Validator hygiene** (`NPDevContract/dsl/.../validation/SemanticValidator.java` — 164 KB,
    Grep to the field-validation section, Read with offset/limit): implement §2.1's three rules
    (self-rename warning, still-exists error, plus: two fields in one concept declaring the same
    `renamedFrom` → error). Tests in the validator's existing test class pattern.
1.2 **Executor: rename step.** In `SchemaLifecycleExecutor`, when classification is
    `RENAME_DETECTED` **and every difference between live DB and manifest is explained by
    declared renames** (strict check: after mentally applying all declared renames, live columns
    == manifest columns, types equal), execute per-column
    renames instead of entering the destructive path:
    - Postgres: `ALTER TABLE <t> RENAME COLUMN <old> TO <new>`
    - H2: `ALTER TABLE <t> ALTER COLUMN <old> RENAME TO <new>`
    Detect the dialect the same way the surrounding code does (VERIFY: find how the executor or
    its callers already distinguish H2 vs Postgres — the manifest carries db-engine info or the
    JDBC URL does; imitate, do not invent). **Do not trust this plan's SQL syntax: write the
    dialect test first (1.4) and let the real engines arbitrate.**
    Idempotence: skip the rename if `<old>` is absent and `<new>` present (log "already applied").
    If a rename is combined with a type change on the same column, the rename applies here and
    the type change is Phase 3's problem — until Phase 3 lands, that combination stays on the
    destructive path (the existing classifier already reports the worse classification,
    `TYPE_CHANGE_DETECTED`; preserve that).
1.3 After a successful rename pass, re-run the classification; if the residual difference is now
    NONE → update stored fingerprint and skip destruction; if residual is SAFE_ADDITIVE → fall
    through to the existing additive path. Never update the fingerprint before residual == NONE.
1.4 **Tests.**
    - Unit: extract the "which renames explain the diff" computation into a small pure class
      (`com.finalexec.db.RenameResolution`) and unit-test it exhaustively (rename present, rename
      unmatched, rename ambiguous, rename+additive mix, rename+type-change mix).
    - Integration (H2, imitate `JdbcBusinessConceptStoreOptimisticLockTest`'s real-H2 style):
      create table with old column + data rows → run executor with a manifest declaring the
      rename → assert column renamed, **data intact**, fingerprint updated, second boot is a
      no-op.
    - Postgres: same scenario via the Testcontainers pattern the kernel Postgres adapters use
      (VERIFY where those tests live; imitate). Runs in the nightly CI job
      (`.github/workflows/npdev-ci-validation.yml`), not the PR gate.
1.5 **Live verification** (use the `rebuild-app` skill): take a real generated app with data
    (§2.6 answer 3), add `renamedFrom` to one field in its model, rebuild, boot, and confirm from
    the boot log the in-place rename ran and the data survived (REST-read the renamed field's
    values). Record the log lines in the evidence note.

**DoD:** a field rename on a live H2 and a live Postgres app preserves all data; re-boot is a
no-op; combined rename+type-change still safely refuses (destructive path) pending Phase 3.

---

### Phase 2 — Concept (table) renames (M)

**Goal:** renaming a concept preserves its table's data.

Tasks:

2.1 **Schema:** add optional `renamedFrom` (string) to the **concept** object in all four
    `model.schema.json` copies (guardrail 1). Run the conformance test.
2.2 **DSL threading:** add `renamedFrom` to `ConceptAst` and `CompiledConcept` (find exact class
    names via Glob under `NPDevContract/dsl/.../ast/` and `.../compiled/` — VERIFY, the concept
    AST class may be named differently), through `JsonModelParser`, `ModelCompiler`,
    `CompiledModelCanonicalJson` writer+reader, and **every** `ModelResolver`
    sanitize/merge/clone construction site (guardrail 2 — this is exactly where `schedule` and
    `forEach` fields were silently dropped before). The Phase 0 ratchet must pass with the new
    component populated.
2.3 **Validator:** same hygiene rules as fields, plus: `renamedFrom` must not equal another
    concept's *current* name; table-name derivation for the old name must use the same
    naming convention code the emitter uses (Grep `SchemaRealizationEmitterBusinessNamingTest`
    for the convention; reuse the production method, never re-derive the convention by hand).
2.4 **Manifest + emitter:** `SchemaRealizationEmitter` threads concept-level `renamedFrom` into
    the manifest (old table name + new table name pairs).
2.5 **Executor:** table-rename step runs FIRST (§2.4 ordering):
    - Both engines: `ALTER TABLE <old> RENAME TO <new>`.
    - Idempotence: skip when `<old>` absent and `<new>` present.
    - After table renames, field renames (Phase 1) run against the NEW table names.
    - VERIFY and handle: secondary indexes, unique constraints, and FK constraints referencing
      the renamed table. Postgres renames keep constraints/indexes attached (names keep the old
      prefix — acceptable; document). H2 likewise keeps them. Bonds FROM other tables reference
      the table by FK — renaming the referenced table keeps FKs valid on both engines. Write the
      integration test that proves each of these statements; where one fails, the classifier
      must route that case to the refusal path with a clear message rather than half-applying.
2.6 Tests + live verification mirroring Phase 1 (H2 + Postgres + one real app).

**DoD:** concept rename preserves data, FKs from bonded tables survive, indexes survive, reboot
no-op — proven on both engines.

---

### Phase 3 — In-place type changes with a safe-widening matrix (M/L)

**Goal:** `TYPE_CHANGE_DETECTED` splits into WIDENING (auto-applied) vs NARROWING (destructive,
itemized).

Tasks:

3.1 **The matrix.** New pure class
    `com.finalexec.db.TypeChangeMatrix` (RuntimeHost, beside the executor): given (fromSqlType,
    toSqlType) → `WIDENING` | `NARROWING` | `INCOMPARABLE`. v1 widenings, exactly these, nothing
    more: `SMALLINT→INT→BIGINT` (transitive), `REAL→DOUBLE`, `NUMERIC(p,s)→NUMERIC(p',s)` where
    p'≥p, `VARCHAR(n)→VARCHAR(m)` where m≥n, `VARCHAR(n)→TEXT/CLOB`, adding NULL-ability
    (NOT NULL → nullable). Everything else is NARROWING or INCOMPARABLE → refused/itemized.
    The matrix works on the SQL types the manifest already carries per column (VERIFY exact type
    string format from a real manifest — Phase 0.1 — and normalize case/whitespace before
    comparing).
3.2 **Executor step 3:** for pure-widening diffs:
    - Postgres: `ALTER TABLE <t> ALTER COLUMN <c> TYPE <newtype>` (add
      `USING <c>::<newtype>` only if a test proves it needed for one of the matrix's pairs).
    - H2: `ALTER TABLE <t> ALTER COLUMN <c> SET DATA TYPE <newtype>`.
    Then residual re-classification as in Phase 1.3. Mixed widening+narrowing on one table: apply
    nothing of Phase 3 — the whole table's type-diff goes to the itemized destructive report
    (partial application would leave a state neither fingerprint describes).
3.3 Combined rename+widening now composes: renames (Phases 1–2) run first, then the widening
    step sees post-rename names. Add the explicit integration test for rename+widen on the same
    column — this exact combination was historically the classifier's hardest case.
3.4 Tests: exhaustive unit tests on `TypeChangeMatrix`; H2 + Postgres integration tests proving
    a widened column keeps its data (insert boundary values first: max INT into the column being
    widened to BIGINT, an n-length string before VARCHAR(n)→VARCHAR(2n)); narrowing attempt
    refuses boot with the itemized message; live verification on the real app.

**DoD:** widenings apply in place with data intact on both engines; narrowings refuse with an
itemized report naming table, column, from-type, to-type.

---

### Phase 4 — Surgical destruction, itemized acknowledgment, and the history table (L)

**Goal:** kill the whole-schema wipe for ordinary drops; make destruction explicit, granular,
and audited.

Tasks:

4.1 **Delta itemization.** New pure class `com.finalexec.db.SchemaDeltaReport`: given the live-DB
    introspection and the manifest (post-rename, post-widening residual), produce a typed item
    list: `DROP_COLUMN(table, column, sqlType)`, `DROP_TABLE(table, rowCountAtClassification)`,
    `NARROW_TYPE(table, column, from, to)`, `UNKNOWN(description)` — each with a stable,
    order-independent string form.
4.2 **Acknowledgment token.** Token = `SHA-256` over: new schema fingerprint + `\n` + the sorted
    stable string forms of all destructive items. Implemented once, in a class shared verbatim by
    generator and RuntimeHost (put it in the DSL module, e.g.
    `com.npdev.dsl.v1.schemaevolution.DestructiveAckToken`, so both sides depend on the same
    bytes — the generator already depends on `:dsl`; VERIFY RuntimeHost's classpath includes the
    dsl jar in generated apps — it reads canonical JSON, so it does; confirm).
4.3 **Executor destructive path replacement:** when residual items are only
    `DROP_COLUMN`/`DROP_TABLE`/`NARROW_TYPE`:
    - Require `manifest.destructiveAcknowledgment()` (new manifest field) to equal the
      executor-computed token. Mismatch or absence → refuse boot; print the itemized report, the
      expected token, and the docs anchor (`docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes`,
      written in Phase 8). Blanket `destructiveAllowed` still works with a deprecation warning
      (§2.5).
    - On match: snapshot **only the affected tables** (per §2.6 answer 1) via the existing
      `SchemaDropSnapshotWriter` (add a table-subset entry point), then execute surgically:
      `ALTER TABLE <t> DROP COLUMN <c>` / `DROP TABLE <t> CASCADE` / narrowing as
      drop-and-recreate-column ONLY if itemized (data in that column is acknowledged lost;
      simpler and more honest than a cast that may fail per-row).
    - `UNKNOWN` items: the old whole-schema recreate remains the fallback, still behind the
      acknowledgment; log prominently that the surgical path could not explain the diff.
4.4 **`npdev_schema_history` table.** Created by the emitter alongside `npdev_schema_metadata`
    (additive — existing apps get it via the SAFE_ADDITIVE path, which is a nice self-test).
    Columns: id, applied_at_utc, from_fingerprint, to_fingerprint, classification, items_json,
    ack_token_used (nullable), outcome (`APPLIED`/`REFUSED`/`PARTIAL-CRASH`). The executor writes
    a `REFUSED` row on refusal too (if the metadata table is reachable) — refusals are audit
    events. Write-before-execute, update-after (per §2.4 crash semantics).
4.5 Tests: unit (report itemization, token stability across item order), integration on both
    engines (drop-column with ack succeeds and only that column's data is gone; without ack
    refuses and DB untouched; stale token from a *previous* plan refuses; history rows written),
    crash-mid-destruction test (freeze-thread technique from `KernelRunnerCompensationTest` /
    the forEach durability test — kill after the first of two drops, re-boot, assert
    re-classification handles the residue and history shows the trail).

**DoD:** an ordinary dropped field costs exactly one column; nothing is destroyed without a
token that names it; every schema change since this phase leaves a history row.

---

### Phase 5 — Data pre-checks and literal backfills (M)

**Goal:** constraint tightenings and new required fields stop being able to strand an app
half-migrated.

Tasks:

5.1 **Unique/compound-unique pre-check.** Before the executor lets the additive path add a new
    unique constraint (single or compound — the manifest knows; VERIFY how constraints appear in
    the additive diff), run
    `SELECT <cols>, COUNT(*) FROM <t> GROUP BY <cols> HAVING COUNT(*) > 1` (tenant column
    included — uniques are tenant-scoped in this platform). Violations → refuse boot, print up to
    20 violating key tuples + total count + docs anchor; write a `REFUSED` history row. Clean →
    proceed.
5.2 **New required field on a non-empty table.** If the manifest says NOT NULL and the live table
    has rows: with a **literal** default declared → emit `ALTER TABLE ADD COLUMN` (nullable) →
    `UPDATE <t> SET <c> = <literal> WHERE <c> IS NULL` → `ALTER COLUMN SET NOT NULL`, in that
    order, idempotently. With an expression default or no default → refuse with the itemized
    message ("declare a literal default or make the field optional"). VERIFY first how the
    additive path currently emits new NOT NULL columns on existing tables — it may already fail
    ugly today; write the failing test before the fix.
5.3 **FK/bond addition to populated tables:** adding a bond column creates an FK on possibly
    NULL data — VERIFY current behavior (bond columns are excluded from additive-eligible today,
    so this lands in the destructive/unknown bucket). v1 rule: new bond on an existing concept
    with rows → allowed if the column is nullable (FK permits NULLs); required bond → same
    refusal shape as 5.2. Move this case out of the UNKNOWN bucket accordingly.
5.4 Tests: both engines: dirty-uniques refusal (with exact violating rows in the message), clean
    pass; NOT-NULL-with-literal-default backfill visible via REST after boot; expression-default
    refusal message; each re-runs cleanly after a mid-sequence crash.

**DoD:** no tightening can crash mid-migration; refusals name the offending rows; literal-default
backfill proven live.

---

### Phase 6 — The migration plan artifact and `Build-NpdevApp.ps1 -Upgrade` (M)

**Goal:** the operator sees the full plan before the database does.

Tasks:

6.1 **Generator-side plan computation** (`com.npdev.generator.schemaevolution.MigrationPlanEmitter`):
    inputs = the NEW compiled model + the PREVIOUS canonical compiled model JSON. Where does the
    previous model come from? The previous FinalApp output's canonical JSON (the assembler knows
    where it puts it — Phase 0.1 located it). `Build-NpdevApp.ps1 -Upgrade` reads it from the
    existing output dir **before** the wipe (`deleteBeforeMount` — §1's upgrade-contract row) and
    passes it to the generator via a new CLI arg. If absent (first build) → plan = "fresh
    install", no file.
    Output: `migration-plan.json` (schema it declares:
    `NPDevContract/schemas/migration-plan.schema.json` — new file, single copy, it is not part of
    the 4-copy model schema) containing: fromFingerprint, toFingerprint, per-item classification
    using the SAME item vocabulary as Phase 4.1 (share the stable-string code via the DSL-module
    class), the SQL preview per item, the destructive-ack token if any destructive items exist.
    Written into the FinalApp output **and** echoed to
    `D:\WorkSpace\NPDev\Build\<app>\migration-plans\plan-<toFingerprint>.json` (outside the
    wiped tree, per the build-output policy — so plans survive rebuilds as an operator-facing
    trail).
6.2 **`Build-NpdevApp.ps1`** gains: `-PlanOnly` (compute + print the plan table, generate
    nothing else, exit non-zero if destructive items exist — script-friendly),
    `-AcknowledgeDestructive <token>` (threads the token into the generated manifest via the
    emitter). Print the plan in every `-Upgrade` run: SAFE items plainly, DESTRUCTIVE items with
    a red banner and the token. Follow guardrail 8 for any gate that calls this.
6.3 **Agreement check:** the executor (Phase 4) already refuses on token mismatch; add the
    friendlier variant — when the executor's derived items differ from the plan's items (model
    drift between plan and boot, e.g. someone edited the model again), print BOTH lists.
6.4 Tests: `MigrationPlanEmitter` unit tests over model-pairs covering every item type; a gate
    extension to `run-stateful-additive-migrations-check.ps1` (or sibling
    `run-schema-evolution-gate.ps1`) driving `canonical-demo` through: build v1 → mutate model
    (scripted JSON edit: add field, rename field, drop field) → `-PlanOnly` (assert exit code +
    plan content) → `-Upgrade -AcknowledgeDestructive` → boot → assert data outcomes via REST.

**DoD:** an operator can always answer "what will this upgrade do to my data" before running it,
from one file and one flag.

---

### Phase 7 — The proof matrix and CI wiring (M)

**Goal:** the DoD scenarios become a permanent gate, on both engines.

The scenario matrix (every row: seed data → mutate model → upgrade → assert; every row also
re-boots twice to prove idempotence):

| # | Scenario | Expected |
|---|---|---|
| 1 | Add optional field with literal default | SAFE_ADDITIVE, backfill applied, data intact |
| 2 | Add required field, literal default, rows exist | 3-step backfill path, NOT NULL after |
| 3 | Add required field, no default, rows exist | REFUSED with named remedy |
| 4 | Rename field (`renamedFrom`) | In-place, data intact |
| 5 | Rename concept | In-place, data + FKs + indexes intact |
| 6 | Rename + widen same column | Both applied, data intact |
| 7 | Widen INT→BIGINT with max-INT value present | Applied, value intact |
| 8 | Narrow type, no ack | REFUSED, itemized, token printed |
| 9 | Drop field with matching ack | Only that column gone; snapshot written; history row |
| 10 | Drop field with stale/foreign token | REFUSED, DB untouched |
| 11 | New unique on dirty data | REFUSED with violating tuples |
| 12 | New unique on clean data | Applied |
| 13 | New nullable bond to populated concept | Applied, FK valid |
| 14 | Crash mid-destructive (freeze-thread) → reboot | Residue re-classified, history shows trail, no double-drop |
| 15 | No model change, reboot | Pure no-op, fingerprint untouched |
| 16 | InMemory-storage app, any model change | Executor no-ops entirely |

Tasks: implement the matrix as a parameterized integration suite in RuntimeHost (H2) + the
Postgres Testcontainers twin; wire H2 subset into `run-runtimehost-gate.ps1` and the PR CI
workflow (`.github/workflows/npdev-pr-gate.yml` — keep it under its 60-min budget; if too slow,
PR runs scenarios 1,4,5,8,9,15 and nightly runs all); Postgres matrix goes in the nightly
(`npdev-ci-validation.yml`). Then one **manual full-stack rehearsal**: the compose-stack Postgres
app from `docs/DEPLOYMENT.md`, real data seeded via `SeedDataService`, a 3-change upgrade
(rename + add-with-default + acknowledged drop), verified in the browser via the
`verify-in-browser` skill. Evidence to the OutsideRepo directory.

**DoD:** matrix green on both engines in CI; the rehearsal note shows a real app surviving a
mixed upgrade with its data.

---

### Phase 8 — Docs, knowledge loop, ledger closure (S)

Tasks:

8.1 Write `docs/SCHEMA_EVOLUTION.md`: the mental model (fingerprint → classify → ordered steps),
    the safe/destructive taxonomy, `renamedFrom` how-to, the acknowledgment workflow with a
    worked example, the anchors the refusal messages link to (anchor IDs must match the Java
    string literals exactly — the `docs/CONFIGURATION.md` pattern), snapshot location and manual
    restore guidance, and the §0 out-of-scope list as "current limitations".
8.2 Regenerate `docs/DSL_REFERENCE.md` (`python scripts/docs/generate_dsl_reference.py`) — the
    new concept-level `renamedFrom` must appear; run its `--check` mode in whatever gate already
    runs it (VERIFY which).
8.3 Knowledge cards (`knowledge/cards/`, schema `schemas/ai/knowledge-card.schema.json`): one for
    the feature's authoring surface (how an AI author declares a rename/ack), one per refusal
    signature (unique-violation, missing-default, token-mismatch) so `npdev_search_fix` can
    answer them. Rebuild corpora: `python scripts/ai/build_knowledge.py`.
8.4 Ledger closure: mark LNCH-1 DONE in `docs/LAUNCH_READINESS_GAPS.md` (and fix that file's
    stale status table for the other closed items while there — it still says OPEN for
    everything as of `996b939`); add the roadmap rows for the §0 deferred items to
    `docs/OPEN_GAPS_AND_ROADMAP.md`; regenerate `knowledge/platform-status.json`
    (`python scripts/ai/extract_platform_status.py` — it is derived, never hand-edit).
8.5 Update `docs/TUTORIAL_FIRST_APP.md` with a short "change your model later" section pointing
    at `docs/SCHEMA_EVOLUTION.md`.

**DoD:** a stranger can execute an upgrade from the docs alone; the AI loop can answer "how do I
rename a field without losing data".

---

## 5. Sequencing, size, and commit protocol

```
Phase 0 (S) ──► Phase 1 (M) ──► Phase 2 (M) ──► Phase 3 (M/L) ──► Phase 4 (L) ──► Phase 5 (M) ──► Phase 6 (M) ──► Phase 7 (M) ──► Phase 8 (S)
```

Strictly sequential — each phase's executor step assumes the previous steps exist (§2.4 ordering).
Do not parallelize phases. Within a phase, commit sub-slices when they stand alone (e.g. Phase 0.2's
ratchet test). Commit messages: `LNCH-1 P<n>: <what>` + a `Verified:` line naming the tests/gates
run. If a phase uncovers a pre-existing bug (history says it will), fix it in its OWN commit with
its own test, before the feature commit that found it.

**Session budgeting note for the implementing AI:** Phases 1–4 each fit a focused session. Do not
start a phase you cannot finish through its live verification — a half-landed executor step is
worse than none, because it changes boot behavior for every generated app. If context runs short
mid-phase, finish the current test, commit the green sub-slice, and write a handoff note in the
evidence directory stating exactly which numbered task is next.

---

## 6. Reference appendix

### 6.1 DDL dialect crib (WRITE THE TEST FIRST — do not trust this table blindly)

| Operation | Postgres | H2 |
|---|---|---|
| Rename column | `ALTER TABLE t RENAME COLUMN a TO b` | `ALTER TABLE t ALTER COLUMN a RENAME TO b` |
| Rename table | `ALTER TABLE t RENAME TO u` | `ALTER TABLE t RENAME TO u` |
| Widen type | `ALTER TABLE t ALTER COLUMN c TYPE bigint` | `ALTER TABLE t ALTER COLUMN c SET DATA TYPE bigint` |
| Drop column | `ALTER TABLE t DROP COLUMN c` | same |
| Set NOT NULL | `ALTER TABLE t ALTER COLUMN c SET NOT NULL` | same |
| Drop table | `DROP TABLE IF EXISTS t CASCADE` | same (existing executor code) |

### 6.2 Commands you will run constantly

```powershell
# DSL tests (remember guardrail 3 — run these AND generator tests)
.\gradlew.bat :NPDevContract:dsl:test --no-daemon --console=plain
# Generator tests
.\gradlew.bat -p NPDevGenerator :generator:test --no-daemon --console=plain
# RuntimeHost gate (regenerates a sample app + runs its suite)
pwsh -NoProfile -File scripts\quality\run-runtimehost-gate.ps1
# Existing migrations gate
pwsh -NoProfile -File scripts\quality\run-stateful-additive-migrations-check.ps1
# Restage kernel/RuntimeHost jars after Java changes (guardrail 6) — or use the rebuild-app skill
pwsh -NoProfile -File scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs
```

### 6.3 Transactionality of DDL (affects crash tests)

Postgres DDL is transactional (a failed multi-statement migration rolls back if wrapped in one
transaction); H2 auto-commits most DDL (a crash between two H2 DDL statements leaves the first
applied). This is WHY §2.4 demands per-operation idempotence-by-check instead of relying on
transactional rollback: design every executor step so that re-running it against a half-applied
database converges. The Phase 7 crash scenarios (#14) exist to prove this property, on both
engines, not just the friendly one.

### 6.4 Prior art to imitate (pattern → exemplar)

| Need | Copy the pattern from |
|---|---|
| Real-H2 store integration test | `JdbcBusinessConceptStoreOptimisticLockTest` |
| Crash/resume durability test | `KernelRunnerCompensationTest` (freeze-thread) |
| Emitter DDL test | `SchemaRealizationEmitterAdditiveColumnsTest` |
| Gate script structure + child-process invocation | `scripts/quality/run-app-upgrade-contract-gate.ps1` |
| Refusal message linking docs anchors | `StartupValidator` + `docs/CONFIGURATION.md` |
| 409-style "here is the current state" payload | compound-unique violation body / `ConceptGatewayOptimisticLockException` |

---

*Companion documents: `docs/LAUNCH_READINESS_GAPS.md` (LNCH-1's origin),
`docs/architecture/APP_UPGRADE_CONTRACT.md` (regeneration semantics this plan builds on),
`docs/beta/beta1-gap-analysis-vs-original-vision.md` (history of the classification mechanism).*
