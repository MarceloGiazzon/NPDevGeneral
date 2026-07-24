# Databases and Migrations in NPDev — a complete, honest reference

**Audience:** anyone who needs to understand how NPDev turns a model into a real database and keeps
that database in step with the model over time — app authors, operators, and platform contributors.

**Scope:** the whole story — the general problem, where NPDev sits in the design space, every moving
part, and an unsparing account of what works, what only partly works, and what is deliberately (or
accidentally) not solved yet.

**Companion docs:** [`SCHEMA_EVOLUTION.md`](SCHEMA_EVOLUTION.md) (the operator-facing feature
reference), [`SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md) (the planned rebuild of
the reconciliation engine), [`CONFIGURATION.md`](CONFIGURATION.md), [`DEPLOYMENT.md`](DEPLOYMENT.md).

---

## Table of contents

1. The general problem: why schema change is hard
2. The two schools of migration, and where NPDev sits
3. What NPDev actually generates (the artifacts)
4. The databases NPDev supports (storage engines)
5. The two families of tables (business vs. platform)
6. Platform-managed columns
7. How a schema is born (generation time)
8. How a schema changes (boot time): the reconciliation engine
9. The passes, in order, and what each decides
10. The bookkeeping tables (metadata, history, claim)
11. Safety mechanisms (the honest strengths)
12. Operator control surface (ownership, strategy, mark-done, recreate)
13. Data safety: snapshots, refusals, rollback
14. Tenant isolation and the `tenant_id` column
15. The honest limitations
16. The architectural debt (REG-6) and why it matters
17. Bugs of the same family found in practice
18. Practical guidance: what you do, and never do
19. Glossary
20. Where this is going

---

## 1. The general problem: why schema change is hard

A database schema is a *contract about shape*: which tables exist, which columns, their types,
nullability, defaults, keys, constraints, indexes. Application code is written against a particular
shape. The moment the code's expected shape and the database's actual shape disagree, you get errors,
silent data corruption, or a refusal to start.

The naive case — a brand-new, empty database — is trivial: create everything from the model. The hard
case is **evolution**: a database that already holds real data must be transformed to a new shape
*without losing or corrupting that data*, ideally with zero human SQL. Every hard sub-problem below is
intrinsic — no tool escapes them, they can only be handled well or badly:

- **Additive vs. destructive.** Adding a nullable column is safe. Dropping a column, narrowing a type,
  or adding a `NOT NULL` to a populated table can destroy or reject data. The tool must tell these
  apart and treat them differently.
- **Rename vs. drop-plus-add.** In a pure shape diff, "renamed `foo`→`bar`" and "dropped `foo`, added
  `bar`" look **identical**. Guessing wrong turns a safe rename into data loss. Distinguishing them
  requires *intent* the shape alone doesn't carry.
- **Backfilling required data.** Making a new column `NOT NULL` on a table that already has rows needs
  a value for every existing row. A literal default works; an expression or "it depends" does not,
  automatically.
- **Knowing the *current* state truthfully.** You can compare "what I want" against "what I last
  recorded" — but the record can lie if someone altered the database by hand. The only ground truth is
  the live database itself, and reading it *completely and portably* is surprisingly hard.
- **Rollback.** Deploying an older build onto a database a newer build already migrated can silently
  re-introduce dropped columns or mis-read the shape. Detecting "this database is ahead of me" needs
  history, not just shape.
- **Cross-engine portability.** The same logical schema reads and writes differently on H2 vs.
  PostgreSQL (type names, default formatting, catalog views, DDL transactionality). A migration that
  works on your dev H2 can fail on production Postgres — and vice versa.
- **Concurrency.** Two app instances booting against one database can interleave migrations and corrupt
  the bookkeeping. Doing this safely needs a real lock or a claim.
- **Drift.** Someone changes the database outside the tool. Now "what I last recorded" and "what's
  actually there" disagree, and the tool has to cope without making it worse.

Keep this list in mind: **everything NPDev does, and every limitation it has, is a position on one of
these axes.**

## 2. The two schools of migration, and where NPDev sits

There are two established philosophies:

**Imperative / versioned migrations** (Flyway, Liquibase, Rails, Django). *You* write an ordered
sequence of change scripts (`V1__init.sql`, `V2__add_column.sql`, …). The tool remembers which have
run and runs the new ones in order. Strengths: explicit, reviewable, you control exactly what happens.
Weaknesses: you hand-author every change, the scripts drift from the model, and "what should the schema
be right now?" is only answerable by replaying history.

**Declarative / state-based migrations** (Prisma, Atlas, Terraform-for-DB, Skeema). You declare the
*desired end state*; the tool reads the *current* state and computes the diff automatically. Strengths:
one source of truth (the desired shape), no hand-written scripts. Weaknesses: the diff is only as good
as the tool's ability to read current state and resolve ambiguity (renames!), and "compute a safe diff"
is genuinely hard.

**NPDev is fundamentally declarative** — you never write a migration; the model *is* the desired state.
But its **implementation is a hybrid**, and this is the single most important structural fact in this
document:

> NPDev generates *both* (a) a small set of **Flyway scripts** baked into each app, *and* (b) a
> **runtime reconciliation engine** (`SchemaLifecycleExecutor`) that runs on every boot and orchestrates
> those scripts. The engine is the declarative brain (decide what kind of change this is, whether it's
> safe, resolve renames, refuse the dangerous); Flyway is the idempotent hands (actually create the
> tables and add the columns).

Understanding *why there are two layers* — and how they interact — is the key to understanding NPDev's
relationship with databases.

## 3. What NPDev actually generates (the artifacts)

When the generator builds a FinalApp from your model, the schema-relevant outputs are:

- **`db/schema-realization/V1__npdev_schema_realization.sql`** — a **versioned** Flyway migration of
  `CREATE TABLE IF NOT EXISTS …` statements for every table (business + platform), plus indexes and
  the fresh-table constraints. "Versioned" means Flyway runs it **once** per database and never again.
- **`db/schema-realization/R__npdev_schema_additive_columns.sql`** — a **repeatable** Flyway migration
  of `ALTER TABLE … ADD COLUMN IF NOT EXISTS …` and (idempotent) `ADD CONSTRAINT` statements.
  "Repeatable" means Flyway re-runs it **whenever its checksum changes** — i.e. after any model edit
  that changes the generated SQL. This is how *new columns on existing tables* self-heal on upgrade.
- **`npdev/db/schema-realization-manifest.json`** — the **manifest**: a machine-readable description of
  the desired schema (per table: full column set, additive-eligible columns, required columns, renames,
  types, literal defaults, bonds). This is the "desired state" object the runtime engine reads. It is
  *derived from the model*, and it is the serialized form of the desired schema.
- **A schema fingerprint** — a SHA-256 hash of the model's schema-relevant inputs (`UserDatabaseDefinitionLoader#fingerprintInputs`
  includes column names, types, and per-field `required=` flags, among others). The fingerprint answers
  exactly one question cheaply: *"has the desired schema changed since the database last recorded one?"*
  It is a **change detector, not a diff** — it says *whether* something changed, never *what*.

Emitted by [`SchemaRealizationEmitter.java`](../NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java)
in the generator.

## 4. The databases NPDev supports (storage engines)

An app's database is declared in its **`db.definition.json`** (engine, connection, `createInternalTables`,
`createBusinessTables`, and the `schemaLifecycle` block). Supported engines:

| Engine | Mode | Typical use | DDL / migration behavior |
|---|---|---|---|
| **InMemory** | no physical database at all | fast dev / demos / tests | **The entire migration mechanism no-ops.** There is no schema to evolve; state lives in memory and vanishes on stop. Checked via `manifest.physicalDatabase()`. |
| **H2 (H2Local)** | embedded file DB in-process | single-process dev | Full migration path; H2 file on disk. |
| **H2 (H2Server)** | H2 over a TCP server | dev/demo where multiple processes or a DB browser attach | Full migration path; the app connects over `jdbc:h2:tcp://…`. |
| **PostgreSQL** | real client/server RDBMS | **production** | Full migration path; the only production-blessed engine. |

Two honest notes:

- **H2 masks Postgres.** H2 in its default/compat mode is lenient about things Postgres is strict about
  (types, DDL-in-transaction, catalog shapes). Multiple real bugs (a date/datetime bug, and the T-B1
  `tenant_id` weakening) only manifested under Postgres. **Never treat "green on H2" as "green."** The
  platform runs a full Postgres proof matrix under Testcontainers for exactly this reason.
- **The deployment posture is single-instance.** One app process per database. Concurrency is *detected
  and refused*, not *locked* (see §11).

## 5. The two families of tables (business vs. platform)

Every NPDev database contains two kinds of tables:

- **Business tables** — one per persisted concept in *your* model (`orders`, `customers`,
  `documento_fiscals`, …). Their columns are your fields, plus the four platform-managed columns (§6).
- **Platform / internal tables** — a fixed catalog NPDev needs to run, defined in
  [`NpdevInternalTables`](../NPDevKernel/kernel/src/main/java/com/npdev/kernel/dbschema/NpdevInternalTables.java):
  the flow-instance store, correlation-owner, event store, audit log, trace store, idempotency store,
  circuit-breaker state, scheduled events, publication execution/audit, **the schema metadata and
  history tables**, promotion state, the **tenant registry**, the **api-credential** store, and
  pack-install intent.

Both families flow through the same generator and the same runtime engine. `createInternalTables` /
`createBusinessTables` in `db.definition.json` gate whether each family is emitted (an
`ExternallyManaged` legacy database, for example, may already have its own tables).

Note a subtlety surfaced in practice: the **identity pack** (`identity_users`, `identity_roles`,
`identity_user_roles`, `identity_password_reset_tokens`) is *not* in `NpdevInternalTables` — it is a
**built-in pack** compiled into the model as ordinary concepts, so it is generated as *business* tables
via the pack mechanism. This matters because an app can carry a **private copy** of a built-in pack and
drift from the platform's (see §17, REG-39).

## 6. Platform-managed columns

Four columns belong to the platform on **every business table**, never to your model:

| Column | Type | Default | Purpose |
|---|---|---|---|
| `id` | the concept's id type (`UUID` if undeclared) | — | primary key |
| `version` | `BIGINT` | `0` | JPA optimistic-locking version on the generated entity |
| `row_version` | `BIGINT` | `0` | the compare-and-swap the ConceptGateway/ConceptStore perform |
| `tenant_id` | `VARCHAR(120)` | `'default'` | tenant scoping — every tenant-scoped read filters on it |

The platform enforces, on every upgrade:

- they are **always `NOT NULL`** (a fresh create emits them so; an upgrade never loosens them);
- their nullability **never** follows a model field's optionality (a model field can't even be *named*
  `version`/`row_version`/`tenant_id` — the generator rejects it — so a live column with that name is
  unambiguously platform-owned);
- a **missing** one is added back additively;
- a **loosened** one (left nullable by an older build) is **backfilled to the default and re-tightened**
  to `NOT NULL`, auditing a `TIGHTEN_PLATFORM_COLUMNS` history row.

You never declare, migrate, or acknowledge anything for these. (The `tenant_id` guarantee is not
decorative: a `NULL` `tenant_id` makes a row invisible to every tenant-scoped read — a data-isolation
hole. Weakening it was the T-B1 bug; see §16.)

## 7. How a schema is born (generation time)

1. You author/edit a JSON model (concepts, fields, bonds, invariants, packs).
2. The generator compiles it and the `SchemaRealizationEmitter` emits `V1` (create), `R__` (additive),
   the manifest, and the fingerprint.
3. Those land in the generated app, which is then compiled into a runnable jar.

Nothing has touched a database yet. Generation is pure model → artifacts.

## 8. How a schema changes (boot time): the reconciliation engine

On boot, Spring Boot would normally call Flyway's `migrate()`. NPDev intercepts this: it registers
[`SchemaLifecycleExecutor`](../NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java)
as the `FlywayMigrationStrategy` bean, so its `migrate(Flyway)` runs **instead of** the default. The
executor is the declarative brain; it decides *what to do*, then delegates the actual DDL to Flyway's
generated scripts. The high-level flow:

```
boot
 └─ SchemaLifecycleExecutor.migrate(flyway)
     ├─ InMemory?            → do nothing (no physical DB)
     ├─ ExternallyManaged?   → read-only compatibility check; NEVER call flyway.migrate();
     │                          proceed (EXTERNAL_VERIFIED) or refuse (EXTERNAL_REFUSED)
     ├─ read stored fingerprint (npdev_schema_metadata)
     ├─ take a single-instance migration claim (if this is an upgrade boot)
     ├─ a "mark-done" recorded for this exact fingerprint transition? → fast-forward, run no passes
     ├─ fingerprint unchanged? → fast path: no reconciliation needed
     └─ fingerprint changed (an UPGRADE):
         ├─ run the passes (rename → relax → tighten → schema-ahead refusal → classify → …)  [§9]
         ├─ decide the outcome: SAFE_ADDITIVE / RENAME / TYPE_CHANGE / DESTRUCTIVE(refuse or recreate)
         ├─ call flyway.migrate()  → applies V1 (CREATE IF NOT EXISTS) + R__ (ADD COLUMN/CONSTRAINT)
         │                            as the idempotent apply layer (or flyway.repair() to reconcile
         │                            checksums for a repeatable-migration change)
         ├─ backfill required fields / refuse if impossible
         └─ write an npdev_schema_history row recording exactly what happened
```

The crucial mental model: **the executor decides and protects; Flyway applies.** The generated
`V1`/`R__` scripts are deliberately idempotent (`IF NOT EXISTS`) so they are safe no-ops when the
executor's own in-place passes already did the work, and a safety net when they didn't.

## 9. The passes, in order, and what each decides

On an upgrade boot, the executor runs roughly **eight passes** in a *deliberate, load-bearing order*.
Order matters because an earlier pass changes what a later pass sees.

1. **`attemptInPlaceTableRenames`** — apply declared *table* renames first, so a renamed table isn't
   invisible to `classify` (which only enumerates tables under their current model name).
2. **`attemptInPlaceRenames`** — apply declared *column* renames next, for the same reason. Renames are
   driven by explicit `renamedFrom` hints in the model — the platform does **not** guess renames from
   shape (see §15). A rename that isn't declared is seen as drop-plus-add.
3. **`relaxNoLongerRequiredColumns`** — if a field went required→optional, drop its `NOT NULL`.
   Always safe (relaxing never loses data), so it runs unconditionally, before classify.
4. **`tightenPlatformColumns`** — repair any platform column an older build left nullable (backfill
   NULLs to the default, restore `NOT NULL`). Also always safe; runs right after relax.
5. **`databaseMigratedPastThisBuild`** (schema-ahead detection, REG-8) — consult `npdev_schema_history`;
   if a *newer* fingerprint was already applied, this is an older jar rolled back onto a migrated
   database → **refuse the boot** with an itemized message, rather than silently re-adding dropped
   columns.
6. **`classify`** — categorize the remaining diff: `SAFE_ADDITIVE` (only new non-bond columns on
   existing tables), `RENAME_DETECTED`, `TYPE_CHANGE_DETECTED`, or destructive. **Honest caveat:**
   classify compares column **name sets and types only** — it has *no nullability awareness* (that's
   why relax/tighten are separate passes, and it's the seam where T-B1 hid).
7. **In-place resolution** — for `RENAME_DETECTED`/`TYPE_CHANGE_DETECTED`, attempt safe in-place
   `RENAME COLUMN` / widening `ALTER COLUMN`, re-classifying after each until the diff is fully
   explained or it falls through.
8. **Destructive handling** — anything not resolved safely is a destructive change: **refused** unless
   the operator supplied the acknowledgment token (`I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED`), and even
   then only after a best-effort pre-drop snapshot. Plus **`refuseIfRequiredBondColumnMissing`**
   (a required bond/FK column can't be backfilled with a made-up key → refuse) and
   **unique-constraint** handling.

Then `flyway.migrate()` applies the idempotent scripts, `applyRequiredFieldBackfills` fills any new
required column with its literal default (or refuses if only an expression default exists), and a
history row is written.

**The structural wart (REG-6):** each of these passes *independently re-derives* the same per-column
facts ("platform-managed? required? a bond? additive?") from the raw manifest maps. There is no single
canonical object that answers them. That duplication is what manufactures a recurring family of bugs;
§16 covers it in full.

## 10. The bookkeeping tables (metadata, history, claim)

- **`npdev_schema_metadata`** — stores the **current fingerprint pointer**: "this database is at
  fingerprint X." Compared against the build's fingerprint to detect an upgrade. It is a *hash*, not a
  snapshot — you cannot diff against it, only test equality.
- **`npdev_schema_history`** — an **audit trail**, one row per boot outcome, with a state such as:
  `APPLIED`, `SAFE_ADDITIVE` outcomes, `TABLE_RENAME`, `COLUMN_RENAME`, `TYPE_WIDENING`,
  `RELAX_NOT_NULL`, `TIGHTEN_PLATFORM_COLUMNS`, `REQUIRED_BACKFILL`, `UNIQUE_PRECHECK`, `REFUSED`,
  `EXTERNAL_VERIFIED`, `EXTERNAL_REFUSED`, `MANUALLY_MARKED_DONE`. This is what the schema-ahead
  detector reads to know a database was migrated past the current build.
- **`npdev_schema_migration_claim`** — a single-row claim taken at the top of an upgrade boot and
  released in a `finally`; a held claim refuses a concurrent second boot, naming the holder (REG-7.3).

## 11. Safety mechanisms (the honest strengths)

NPDev's migration story is genuinely strong on *not silently doing damage*. What's real:

- **Destructive changes are refused, not guessed.** A drop/narrowing/incompatible change stops the boot
  with an **itemized** message and requires an explicit acknowledgment token to proceed.
- **Pre-drop snapshots.** Every destructive drop (surgical or whole-schema) is preceded by a best-effort
  JSONL snapshot under `runtime-data/schema-snapshot-before-drop/<timestamp>/`, 5 most recent retained.
  A snapshot failure is logged loudly (`DATA LOSS NOT SNAPSHOTTED`) but never blocks the drop.
- **Platform columns are protected** (§6) — never weakened, always repaired.
- **Schema-ahead / rollback protection (REG-8).** An older jar on a newer database is detected via
  history and refused, not allowed to silently re-add dropped columns.
- **Single-instance collision detection (REG-7.3).** Concurrent boots are refused loudly.
- **Renames preserve data** — declared renames become in-place `RENAME COLUMN`, never drop-plus-add.
- **Cross-engine proof.** A full Postgres proof matrix (Testcontainers) runs alongside the H2 one.

## 12. Operator control surface (ownership, strategy, mark-done, recreate)

The `schemaLifecycle` block in `db.definition.json` gives operators explicit control:

```json
"schemaLifecycle": {
  "strategy": "KeepExistingIfCompatible",
  "allowDestructiveRecreate": false,
  "destructiveRecreateConfirmation": "",
  "scope": "NpdevOwnedTablesOnly",
  "ownership": "NpdevManaged"
}
```

- **`ownership`** — `NpdevManaged` (default) or **`ExternallyManaged`**. `ExternallyManaged` means NPDev
  issues **zero DDL** — `flyway.migrate()` is never called — and instead runs a read-only compatibility
  check every boot, proceeding (`EXTERNAL_VERIFIED`) or refusing (`EXTERNAL_REFUSED`) with an itemized
  message. This is the "do nothing; I manage the schema by hand" mode.
- **`strategy`** — how NPDev migrates *when it owns the schema*. `KeepExistingIfCompatible` is the
  normal reconcile-and-preserve mode.
- **`allowDestructiveRecreate` + `destructiveRecreateConfirmation`** — the "delete and rebuild the
  tables" escape hatch: a whole-schema recreation, gated behind an explicit confirmation, framed as a
  safety net rather than a normal path.
- **`scope`** — e.g. `NpdevOwnedTablesOnly`, so NPDev never touches tables it didn't create.
- **Mark-migration-done** — a ControlPanel operation (`POST /api/admin/schema-migration/mark-done`,
  SUPERUSER-gated) that fast-forwards the stored fingerprint for an exact `from→to` transition, on the
  operator's word, running **zero** passes. The "I already migrated this by hand, stop trying" button.
  It trusts the operator completely — it does not verify the claim.

Mapping to the four operator "options" one naturally wants:

| Want | NPDev mechanism | State |
|---|---|---|
| Warn about data loss / migrate errors | refuse + itemize + snapshot + acknowledgment token | shipped (stronger than a warning) |
| Delete & rebuild tables | `allowDestructiveRecreate` + confirmation | shipped |
| Export & recover data | pre-drop JSONL snapshots (export) | **export only — restore is manual** |
| Manually program the SQL / do nothing | `ExternallyManaged` + `mark-done` | shipped (all-or-nothing; no per-step custom hook) |

## 13. Data safety: snapshots, refusals, rollback

- **Snapshots are export-only.** The JSONL files are a manual recovery artifact; **there is no
  automated restore.** Recovery means reading the `.jsonl` and re-inserting via SQL, the seed-data
  mechanism, or the REST API. Automating restore safely (conflict resolution against rows written since
  the drop, shape reconciliation) is real, unstarted work.
- **Refusals are not side-effect-free.** The safe convergent steps (renames, `NOT NULL` relaxations)
  apply *before* the acknowledgment decision — so a refused upgrade has already applied them. Recovery
  is roll-forward or restore, never redeploying the old jar (which the schema-ahead detector refuses in
  the cases it can see).
- **A pure drop + rollback is refused, not reconstructed (REG-8).** The data the drop destroyed is gone
  (recoverable only from a snapshot/backup); REG-8 makes the situation *visible and refused*, it does
  not rebuild the lost column.

## 14. Tenant isolation and the `tenant_id` column

NPDev is multi-tenant: every business row carries a `tenant_id`, and every tenant-scoped read filters on
it. Two migration-adjacent facts:

- `tenant_id` is a platform-managed column (§6) — always `NOT NULL DEFAULT 'default'`, never weakened.
- **Canonicalization (REG-25):** as of 2026-07-24 the runtime lowercases `tenant_id` at a single choke
  point (`ExecutionContext`), so two casings of one logical tenant land in one isolation bucket. Legacy
  mixed-case data is converged by a one-time operator tool
  (`scripts/ops/canonicalize-tenant-ids.ps1`, dry-run default, collision-aware) — see
  [`SCHEMA_EVOLUTION.md#tenant-id-canonicalization`](SCHEMA_EVOLUTION.md).

## 15. The honest limitations

Recorded plainly (these are known, not hidden):

- **No automatic rename inference.** Every rename must be declared via `renamedFrom`. Without it, a
  rename is a drop-plus-add (data loss on the destructive path).
- **No expression-valued backfills.** Only a literal `default` is backfilled; a `defaultExpression`-only
  required field on a populated table is refused, not evaluated.
- **No automated snapshot restore** (§13).
- **No cross-database data migration** (H2 → Postgres data movement is a different, unbuilt feature).
- **`ExternallyManaged` verification is column-shaped only.** It checks that declared tables/columns
  exist with compatible SQL types; it does **not** check nullability, uniqueness, indexes, or foreign
  keys. A schema can pass this check and still be incompatible in ways it doesn't look for.
- **`mark-done` trusts the operator completely** — it doesn't verify the live schema matches; it just
  stops trying to converge.
- **`classify` has no nullability awareness** — it diffs names + types only; nullability is handled by
  separate passes (the seam where T-B1 hid).
- **Single-instance only.** Concurrency is detect-and-refuse, **not** a lock. A true
  near-simultaneous-`INSERT` race remains theoretically possible; the first-ever boot of a brand-new
  database is not claim-protected. Do not run multi-instance deployments of the same app+database
  relying on this as a mutex.
- **Refusals are not side-effect-free** (§13).
- **Dropping a concept needs one boot of ownership history first** (LNCH-1-B7) — the executor only drops
  a table it can *prove* NPDev created.
- ~~**The additive migration never `CREATE`s new tables (REG-40).**~~ **FIXED tactically (2026-07-24)**
  — `R__` now emits `CREATE TABLE IF NOT EXISTS` for every business/junction table (ordered before its
  `ALTER … ADD COLUMN/CONSTRAINT` blocks), so a new concept redeployed against an *existing* database
  self-heals instead of failing boot with `Table not found`. Strategic dedup (routing this through the
  same `SchemaDiff` every other pass will eventually consume) still lands later, at
  [`SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md)'s P4.6 — this is the same missing
  abstraction as REG-6 (§16), just closed tactically first.

## 16. The architectural debt (REG-6) and why it matters

Under the strong safety story sits a real structural weakness. **NPDev manages schema by *inference*,
not from a canonical state model.** There is no single object that says "here is the complete current
schema" and "here is the complete desired schema." Instead, each of the ~8 passes re-infers fragments of
both from raw manifest maps and ad-hoc live JDBC reads.

Consequences:

- **The passes can disagree.** Two passes reading the same manifest can reach opposite conclusions about
  the same column. That produced **T-B1**: the relax pass thought `tenant_id` was an optional business
  field while the emitter emits it `NOT NULL` — the disagreement *silently weakened the tenant-isolation
  column on every upgrade*. And **T-B2**: two passes disagreed about `version`.
- **The current state is read shallowly and in fragments.** Nullability/defaults/constraints are read
  ad hoc and incompletely (hence `classify`'s name+type-only diff and `ExternallyManaged`'s
  column-shaped-only check). Reading current state *completely and portably* across H2 + Postgres is the
  genuinely hard, unbuilt piece.
- **The "recorded" state is a fingerprint, not a snapshot.** The only ground truth to diff against is the
  live database itself — which is actually the *right* instinct (a recorded snapshot can lie), but it's
  read too shallowly to diff cleanly.

**What has been done (REG-6 risk-core, landed):** a `ColumnFacts` projection now gives *the desired
side* a single, read-once, per-column view; the per-column *semantic* re-derivations were migrated onto
it; a class-load drift-guard makes the two platform-column sets unable to silently diverge; and a
code-site directive requires any **new** pass to read `ColumnFacts`. This closed the specific drift that
caused T-B1/T-B2 and CI-guards it.

**What remains (deferred, by owner decision):** the *set-algebra* passes still compute their own set
diffs, and there is still no *current-side* canonical model or complete portable reader. The full fix is
a **desired-vs-current diff engine** computed once, that every pass consumes — see
[`SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md) for the strangler-fig plan (build a
read-only shadow, prove parity across the whole H2+Postgres matrix, then migrate passes one at a time).
The deferral is a bet that the interest is cheaper than the rebuild *right now* — a defensible bet, but
an explicit one against architectural debt, not a closed problem.

## 17. Bugs of the same family found in practice

The clearest evidence for §16 is that the *same shape* of bug keeps recurring, all in this one subsystem:

- **T-B1** — passes disagreed about a column's nullability → `tenant_id` weakened. (fixed + guarded)
- **T-B2** — passes disagreed about `version`. (fixed + guarded)
- **REG-38** (2026-07-24) — the additive migration re-added a constraint it couldn't tell already
  existed; non-idempotent `ADD CONSTRAINT` on H2 broke *any* re-deploy against an existing database.
  (found + fixed: now emits `DROP CONSTRAINT IF EXISTS` first; verified live)
- **REG-40** (2026-07-24) — the additive migration never created a *new* table, so adding a new
  concept and re-deploying against an existing database failed at boot. (found + fixed tactically:
  R__ now also emits `CREATE TABLE IF NOT EXISTS` for business/junction tables, before its `ALTER`
  blocks; verified end-to-end on H2 and a real Postgres container — see
  `SchemaLifecycleExecutorNewTableOnExistingDbTest` / `SchemaLifecycleExecutorPostgresProofMatrixTest`)
- **REG-39** (2026-07-24) — not an engine bug, but migration-adjacent: an app carried a **private,
  drifted copy** of the built-in identity pack (missing the `token_version` column added platform-side),
  so the generated `identity_users` schema lacked a column `LoginController` unconditionally selects →
  **every login failed**. Fixed by syncing the app's pack to the platform's. The lesson: apps that pin
  private copies of built-in packs drift silently and break against newer platform code.

"Does this constraint exist?" (REG-38) and "does this table exist?" (REG-40) would be the *same question
asked of the same model* if that model existed. They're separate bugs only because "what exists" is
re-derived ad hoc every time. That is the debt paying interest.

## 18. Practical guidance: what you do, and never do

**As an app author, you:**
- Author and edit a **model**, never SQL and never a migration script.
- Redeploy to evolve the schema; additive changes (new nullable columns, new concepts on a *fresh* DB)
  just work.
- Declare a rename with **`renamedFrom`** so it's applied in place (never drop-plus-add).
- Give new required fields a **literal `default`** if the table may already have rows.
- Choose `ownership`/`strategy` in `db.definition.json` to match how much control you want.
- Acknowledge a genuinely destructive change with the token only when you mean it.

**You never:**
- Write a Flyway migration by hand (the generator owns `V1`/`R__`; hand edits are overwritten and the
  generated tree is hash-verified).
- Name a model field `id`, `version`, `row_version`, or `tenant_id` (rejected at generation).
- Rely on `mark-done` as verification (it's a trust operation).
- Run two instances against one database expecting a lock.

**Operational gotchas (learned the hard way):**
- ~~**New concept + existing DB → boot fails (REG-40).**~~ Fixed 2026-07-24 — R__ now self-heals a
  missing table on upgrade, the same way it already self-heals a missing column.
- **Built-in pack drift (REG-39).** If an AppGen app's login (or any pack feature) breaks after a
  platform bump, check whether the app's private `definition/packs/*/pack.json` has drifted from
  `NPDevContract/packs/*` and sync it.
- **After changing kernel/adapter Java, restage jars** before regenerating an app
  (`sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir …`), or the running app keeps a stale
  jar.
- **Verify on Postgres, not just H2** — H2 masks real production behavior.

## 19. Glossary

- **Manifest** — the serialized desired schema (`schema-realization-manifest.json`), read by the runtime.
- **Fingerprint** — a hash of the model's schema-relevant inputs; a change *detector*, not a diff.
- **Pass** — one step of the boot-time reconciliation (rename, relax, tighten, classify, …).
- **Classification** — `SAFE_ADDITIVE` / `RENAME_DETECTED` / `TYPE_CHANGE_DETECTED` / destructive.
- **`ColumnFacts`** — the read-once, per-column desired-side projection (REG-6 risk-core).
- **Versioned migration (`V1__`)** — Flyway script run once per database (the `CREATE`s).
- **Repeatable migration (`R__`)** — Flyway script re-run on checksum change (the additive `ALTER`s).
- **Ownership** — `NpdevManaged` vs `ExternallyManaged` (whether NPDev issues DDL at all).
- **Strategy** — how NPDev migrates when it owns the schema.
- **Acknowledgment token** — `I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED`, required to proceed through a
  destructive change.

## 20. Where this is going

The destination is the declarative ideal NPDev already aims at, done properly: a **canonical
desired-vs-current schema model**, with a **complete portable current-schema reader**, diffed **once**,
consumed by every pass — replacing reconciliation-by-inference with reconciliation-from-state. That one
abstraction also upgrades `ExternallyManaged` verification from column-shaped to full-shaped and folds in
REG-40 (new tables become a first-class diff item). The migration path is the strangler-fig in
[`SCHEMA_ENGINE_REBUILD_PLAN.md`](SCHEMA_ENGINE_REBUILD_PLAN.md): build it as a read-only shadow, prove
it agrees with today's engine across the entire H2 + Postgres proof matrix, then move the passes onto it
one at a time — behavior-preserving, Docker-verified, never big-bang, because this is the most
consequential code in the platform.

---

*Honesty note: every "shipped" claim here is grounded in the code and the companion docs as of
2026-07-24; every limitation is either documented in `SCHEMA_EVOLUTION.md#current-limitations` or was
found and filed in practice (REG-6/38/39/40). If a detail here and the code ever disagree, the code
wins — verify before relying.*
