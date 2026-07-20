# Schema evolution for live apps (LNCH-1)

> Headings below use the exact anchor IDs the runtime's refusal messages link to — do not rename
> them (same convention as `docs/CONFIGURATION.md`).

When you change a model whose generated app is already deployed and holds real data, the platform
does not silently guess what you meant. `SchemaLifecycleExecutor`
(`NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`), a Spring
`FlywayMigrationStrategy`, decides at **boot time** — from live database introspection, not from
diffing two model files — exactly what changed and how to apply it safely, or refuses with a
message telling you precisely what to do.

## The mental model

Every boot against a physical database (H2Local/H2Server/Postgres — InMemory apps have no DDL and
this entire mechanism no-ops for them):

1. **Fingerprint.** The generator stamps a `schemaFingerprint` (a hash of the compiled model's
   structural shape) into the app's manifest. The executor reads the LAST stored fingerprint from
   `npdev_schema_metadata` (a self-bootstrapped table). If they match, nothing happened — pure
   no-op.
2. **Classify.** If they differ, `classify(...)` introspects every business table's live columns
   and types against what the manifest now expects, and returns one of four verdicts:
   - `SAFE_ADDITIVE` — every difference is a brand-new, non-bond column. Applied automatically via
     a Flyway repeatable migration.
   - `RENAME_DETECTED` — every extra/missing column is explained by a declared `renamedFrom`.
   - `TYPE_CHANGE_DETECTED` — column names match, but a shared column's SQL type changed.
   - `DESTRUCTIVE` — anything else (a genuine drop, a narrowing type change, an unresolved column).
3. **Apply the safe steps first, unconditionally.** Table renames, then field renames, then safe
   type widenings (`INT → BIGINT`, `VARCHAR(20) → VARCHAR(50)`, etc.) are attempted **before**
   anything destructive is even considered — regardless of what else on the same table still needs
   the destructive path. A rename and an unrelated, separately-acknowledged drop on the same table
   both apply correctly in the same boot: the rename preserves its column's data in place; the drop
   is scoped to only the column it names.
4. **Required-field backfill and uniqueness pre-checks** run next (see the two sections below),
   before anything destructive is even computed.
5. **Whatever residual diff is left** (if any) goes through `SchemaDeltaReport`, which itemizes it
   into exactly four kinds — `DROP_COLUMN`, `DROP_TABLE`, `NARROW_TYPE`, or `UNKNOWN` (anything the
   first three can't cleanly explain) — and either executes it **surgically** (only the named
   column/table) if every item is one of the first three named kinds and acknowledged, or falls back
   to a whole-schema wipe if an `UNKNOWN` item is present.
6. **Every applied pass — safe or destructive — leaves one row** in `npdev_schema_history` (outcome
   `APPLIED`/`REFUSED`/`PARTIAL-CRASH`), so the audit trail survives even a crash mid-migration.

Nothing here infers intent. A rename is a rename because you said so
(`renamedFrom`), not because the platform guessed two columns "look similar."

## Declaring a rename

Renames are never auto-detected — declare them with `renamedFrom`, and the in-place path (steps
preserving all data) is used automatically the next time you deploy.

**Field rename** — on the field object, in its concept's `fields` array:

```json
{
  "name": "fullName",
  "type": "string",
  "required": true,
  "renamedFrom": "name"
}
```

**Concept (table) rename** — on the concept object itself:

```json
{
  "name": "Customer",
  "renamedFrom": "Client",
  "fields": [ ... ]
}
```

Both fire an in-place `ALTER TABLE ... RENAME COLUMN` / `ALTER TABLE ... RENAME TO` on the next
boot against a database that still has the old name — zero data loss, no acknowledgment needed.
Table renames are attempted before field renames, before anything else, on every boot with a
fingerprint mismatch — so a table you renamed is never mistaken for "a table that no longer exists
plus a brand-new one."

### Marker lifecycle

`renamedFrom` names the **immediately-previous** column/table name, not the original:

- **On a SECOND rename** of the same field (`A → B`, then later `B → C`), set `renamedFrom` to
  `"B"` — the name the currently-deployed database actually has — never back to `"A"`.
- A marker whose old name no longer exists anywhere (every deployed database is already past it) is
  harmless and may be kept or removed at will.
- **The hazard, spelled out:** renaming `B → C` while the marker still says `A` makes the platform
  see "drop `B` + add `C`" — a **destructive** item that will be offered for acknowledgment.
  Acknowledging it drops `B`'s column and loses its data. **If a plan (`-PlanOnly`) shows a `DROP` of
  a column you meant to rename, STOP and fix the marker** before proceeding. The plan preview emits a
  `WARNINGS` block when a `renamedFrom` names a column the previous model has no record of — the
  earliest signal that a marker has gone stale.

## Dropping a concept

Removing a concept from the model drops its table — but only once the platform can **prove it owns
that table**, and only behind the same acknowledgment token any other destructive change needs.

Every successful boot records the business tables that build owned, in `npdev_schema_metadata`
under `ownedBusinessTables`. On a later boot, a live table that (a) the current model no longer
declares, (b) is not the old side of a declared rename, and (c) **appears in that recorded owned
set** is a genuine dropped concept: the boot classifies as destructive, `SchemaDeltaReport` itemizes
it as `DROP_TABLE`, and it is dropped surgically once acknowledged.

A table that is **not** in the recorded owned set is never touched — that is what keeps a table an
operator created by hand in the same schema from being swept away just because the model does not
mention it. If no ownership has ever been recorded (an app upgrading from a build older than this
mechanism), nothing is dropped and the app behaves exactly as it did before; the record is written
on that boot, so the next upgrade can act on it.

> Before this was fixed (`LNCH-1-B7`), `-PlanOnly` previewed the `DROP_TABLE` and demanded a token,
> but the boot classified the change as safe-additive and never entered the destructive path — the
> table survived and the acknowledgment was never consumed.

## New required fields

Adding a `required` field to a concept whose table already has rows needs a value for every
existing row. The executor backfills it automatically **only if the field declares a literal
`default`**:

```json
{
  "name": "status",
  "type": "string",
  "required": true,
  "default": "pending"
}
```

On boot, this runs `ADD COLUMN IF NOT EXISTS` (nullable) → `UPDATE ... SET status = 'pending' WHERE
status IS NULL` → `ALTER COLUMN ... SET NOT NULL`, each step idempotent-by-check (a crash between
any two steps converges correctly on the next boot, it never re-runs a step that already succeeded
or skips one that didn't).

This required-field enforcement runs on **every upgrade boot regardless of what else the upgrade
contains** — including an upgrade that also carries an acknowledged destructive item (a dropped
field or concept). It lives at a single call site every boot path crosses, so a required field
added in the same upgrade as an acknowledged drop is still backfilled-and-tightened (or refused if
it has no literal default), never silently left permanently nullable.

**Refused, not silently guessed, when:**
- No `default` is declared (an optional field, or a `defaultExpression`-only field — **expression
  backfills are not supported in v1**; only literal defaults are backfilled automatically).
- The new required field is a **bond/FK reference** — there is no automatic literal-default backfill
  for a bond (its value would have to reference an existing row's actual key). Make the field
  optional, or use the [acknowledged destructive path](#acknowledging-destructive-changes) to
  recreate the table.

Either refusal names every offending `table.column` and tells you exactly what to change; the
stored fingerprint is left stale so a fixed retry re-attempts cleanly.

## Tightened uniqueness

Declaring a new `unique` invariant (or a new compound unique) on a table with existing rows is
pre-checked against the live data **before** the constraint is ever applied:

```json
{
  "name": "EmailUnique",
  "type": "unique",
  "fields": ["email"]
}
```

If every row already satisfies it, the constraint is added (or, on Postgres, recognized as already
satisfied by the ordinary bootstrap unique index the generator emits for single-field uniques — no
duplicate `ADD CONSTRAINT` attempt). If any rows collide, the boot refuses and names the violating
tuples (up to 20 as a sample) — resolve the duplicates or relax the constraint, then retry.

## Acknowledging destructive changes

A genuine destructive change — a dropped field, a dropped concept, a narrowing/incompatible type
change — never applies silently. It requires an explicit, itemized token computed from the EXACT
change being made, so an acknowledgment can never be reused for a different, later change by
accident.

**The token is `sha256(newFingerprint \n item1 \n item2 \n ...)`**, items sorted for determinism
(`DestructiveAckToken.compute`, `NPDevContract/dsl/.../schemaevolution/DestructiveAckToken.java`).
It changes if the target fingerprint changes OR if the set of destructive items changes — it is
bound to both.

The token is computed **identically at plan time (`-PlanOnly`) and at boot time, for every item
kind** — including a dropped concept (`DROP_TABLE`). Each item's hashed form uses only inputs that
are derivable the same way from a live database and from a model diff; in particular a `DROP_TABLE`
item's live row count is **display metadata only** (shown as "row count unknown until boot" in the
plan preview) and is deliberately kept out of the hash, so a concept-drop token copied from
`-PlanOnly` matches the executor's boot-time token on the first attempt.

### Worked example

Model change: rename `User.name` → `User.fullName` (safe, in-place), add optional `User.notes`
(safe, additive), drop `User.active` (destructive).

> **Stop the running app before regenerating.** `Build-NpdevApp.ps1` wipes the entire FinalApp
> output directory on every run (`deleteBeforeMount`, per `docs/architecture/APP_UPGRADE_CONTRACT.md`)
> — a real, load-bearing operational constraint, not just a style preference. If the app is actively
> serving from that directory, the wipe can fail outright or leave it in an inconsistent state.
> `-PlanOnly` and `-Upgrade` are no exception: stop the app first, run the command, then rebuild and
> restart. `-PlanOnly`'s own diff is computed against the app's own prior `compiled-model.json`
> captured just before the wipe, so this is safe to do even while planning an upgrade to that same
> running app.

**1. Preview the plan** before touching the live app, against the currently-deployed app's own
prior state:

```
Build-NpdevApp.ps1 -AppFolder <app> -Upgrade -PlanOnly
```

```
================ NPDev Migration Plan =================
SAFE changes (2):
  [RENAME_COLUMN] Field renamed: 'users.name' becomes 'full_name' (data preserved in place).
  [ADD_COLUMN] New optional field adds column 'users.notes'.

DESTRUCTIVE changes (1) -- DATA WILL BE LOST for these items:
  [DROP_COLUMN] Field removed: column 'users.active' will be dropped, deleting its data.
      SQL: ALTER TABLE users DROP COLUMN active

Acknowledgment token (copy exactly; pass to -AcknowledgeDestructive, or submit via
the ControlPanel schema-migration screen on the CURRENTLY RUNNING app):
  4a9128a3d8c10cd8428a0a7e6af7c5e4f9fbf11c004d227e4acf6d6e6bc03404
========================================================
```

`-PlanOnly` exits non-zero whenever any destructive item is present (a script-friendly gate signal)
— it never touches anything live.

**2. Authorize it**, either of two equally-trusted channels:

- **CLI, at deploy time** — thread the copied token straight through:
  ```
  Build-NpdevApp.ps1 -AppFolder <app> -Upgrade -AcknowledgeDestructive "4a9128a3d8c10cd..."
  ```
  This lands verbatim in the generated manifest's `destructiveAcknowledgment` field, which the
  executor compares against its own independently-computed expected token at boot.

- **ControlPanel, before deploying** — an operator reviews the same plan and submits it on the
  **currently running (old)** app, since a refused boot has no server left to serve a ControlPanel
  page on:
  ```
  POST /api/admin/schema-migration/acknowledge
  { "toFingerprint": "<the plan's To fingerprint>", "ackToken": "<the plan's token>" }
  ```
  (SUPERUSER-gated, `X-Super-User-Key` header — same pattern as every other ControlPanel endpoint.)
  This writes a row the NEW app's executor also checks at boot, in addition to the static manifest
  field — either source authorizing the same expected token is sufficient. Check what's pending
  with `GET /api/admin/schema-migration/pending`.

**3. Boot the upgrade.** With a matching token, the executor logs (in order):

```
NPDev schema lifecycle: applied in-place field renames: [users.name -> full_name]
NPDev schema lifecycle: destructive change acknowledged by itemized token; executing surgically
  (only the affected table(s)/column(s), LNCH-1 Phase 4). Report: [DROP_COLUMN:users:active:BOOLEAN]
NPDev schema lifecycle: pre-drop snapshot written to runtime-data/schema-snapshot-before-drop/...
NPDev schema lifecycle: surgical destructive changes applied: [DROP_COLUMN users.active]
```

The rename and the drop each apply through their own mechanism, on the same boot, without either
one waiting on or blocking the other. Verified live against a real Postgres compose stack with real
seeded data (`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\phase-7.md`) — the
renamed column's data survives intact, the dropped column is genuinely gone.

**Without a matching token**, the boot refuses outright — the app never starts with a half-applied
or silently-guessed **destructive** change (the safe convergent steps — renames, relaxations — may
already have applied; see [Refusals and rollback](#refusals-and-rollback)):

```
Schema fingerprint changed from <old> to <new> and includes destructive change(s) requiring an
explicit, itemized acknowledgment. Itemized destructive report: [...]. Expected acknowledgment
token: <token>. Set the generated manifest's destructiveAcknowledgment to this token, or submit it
via the ControlPanel schema-migration screen on the currently running app, to proceed.
```

**The deprecated escape hatch.** A blanket `destructiveAllowed: true` on the manifest still
authorizes a destructive change with no itemized token — it prints a loud deprecation warning
(`NPDev schema lifecycle: DEPRECATION WARNING`) every time it's used and should not be relied on
for new work; it exists only for backward compatibility with apps generated before this feature.

## Refusals and rollback

A refused upgrade is **not** side-effect-free. By design, the safe, convergent steps run
**before** the acknowledgment decision is even reached:

- **Table renames, field renames, and NOT NULL relaxations** are applied unconditionally at the
  top of every fingerprint-mismatch boot (they are convergent toward the new model and lose no
  data). A boot that then refuses an unacknowledged destructive item has **already** applied those
  renames/relaxations.
- On the **combined path** — an acknowledged destructive item plus a new required field with no
  literal default — the destructive item executes (it was acknowledged), and the required-field
  refusal fires **afterward**, at the post-migration enforcement step. So the destructive change
  MAY already be applied when that refusal is thrown. A subsequent boot with a fixed model (declare
  the literal default, or make the field optional) converges cleanly.

Because a refused or partially-applied upgrade has already moved the live schema toward the new
model, the supported recovery directions are exactly two:

1. **Roll forward** — fix the model (supply the token, declare the missing default, resolve the
   duplicate data) and redeploy the newer build. This is the normal path.
2. **Restore** — recover the database from a backup/snapshot taken before the upgrade.

**Never redeploy the OLD jar against a database a newer build already migrated.** Its stored
fingerprint would match the old build, so the executor would otherwise boot "clean" against a
schema whose columns have been renamed away or dropped, and then fail at runtime with no
diagnostics.

The executor guards this on every fingerprint-MATCH boot with a **schema-ahead-of-build detector**.
It is a best-effort safety net, not a complete guarantee — here is exactly what it does and does not
catch:

| Situation | Detected? | How |
|---|---|---|
| A newer build **renamed an ordinary field** (`name` → `full_name`) | **Yes** | Trigger B |
| A newer build **renamed or dropped a whole concept's table** | **Yes** | whole-table rule |
| A **required bond/FK column** is missing | **Yes** | Trigger A |
| A newer build **purely dropped a column** (nothing left behind) | **No** | see below |

- **Trigger A — a missing column that is not additive-eligible.** Catches `id`, `version`, and
  required/many-to-many bond columns: things nothing re-adds automatically.
- **Trigger B — a missing column on a table that also carries an *unexplained extra* live column.**
  An unexplained extra is a live column this build's manifest does not declare, that is not a
  platform column (`id`, `version`, `row_version`, `tenant_id`), and is not the old side of a
  declared rename. `name` missing while `full_name` is present is the signature of a rename by a
  newer build.
- **Whole-table rule.** If a declared table has no live columns at all, the refusal says
  `<table> (entire table missing)` rather than listing every column separately.

**Known limitation — a pure column drop is invisible.** If a newer build simply *removed* a field,
it leaves no extra column behind, so neither trigger has anything to see. The old jar will boot, and
its repeatable additive migration may re-add that column **empty**. Rolling an old jar back onto a
migrated database is unsupported regardless; this detector reduces the damage, it does not make the
operation safe. Recover by rolling forward, or by restoring from a snapshot/backup.

## Snapshots (manual recovery only)

Every destructive drop — surgical or whole-schema — is preceded by a best-effort snapshot:
`runtime-data/schema-snapshot-before-drop/<yyyyMMdd-HHmmss-SSS>/<table>.jsonl` (one JSON object per
row) plus a `_summary.json` describing what was captured. The 5 most recent snapshot directories are
retained; older ones are pruned automatically. A snapshot failure is logged loudly
(`DATA LOSS NOT SNAPSHOTTED`) but never blocks the drop it's protecting — a safety net that could
itself crash the boot would be worse than none.

**There is no automated restore.** Recovery is manual: read the `.jsonl` file(s) for the
table/column you need, and re-insert via SQL, the seed-data mechanism
(`docs/SEED_DATA.md`-equivalent, `SeedDataService`), or the REST API. This is a deliberate v1
scoping decision — automating restore safely (conflict resolution against rows written since the
drop, schema-shape reconciliation) is real, unstarted work; see Current limitations below.

## Current limitations

Deliberately out of scope for this feature (recorded here as known future work, not silently
missing):

- **No automatic rename inference.** Every rename must be declared via `renamedFrom` — there is no
  identity-tracking (uid-based) mechanism that infers "this looks like the same field, renamed."
- **No expression-valued backfills.** Only a literal `default` is backfilled automatically; a
  `defaultExpression`-only required field on a populated table is refused, not evaluated.
- **No automated snapshot restore.** The JSONL snapshots are a manual recovery artifact, not a
  one-command undo.
- **No cross-database data migration.** Moving data between database engines (e.g. H2 → Postgres) is
  a different, unrelated feature — not something an app upgrade does.
- **InMemory-storage apps have no DDL.** This entire mechanism no-ops cleanly for them (checked via
  `manifest.physicalDatabase()`) — there is nothing to migrate.
- **Refusals are not side-effect-free.** The safe convergent steps (table/field renames, NOT NULL
  relaxations) apply before the acknowledgment decision, so a refused upgrade has already applied
  them; recovery is roll-forward or restore, never redeploying the old jar (which the schema-ahead
  detector refuses in the cases it can see). See [Refusals and rollback](#refusals-and-rollback).
- **The schema-ahead detector cannot see a pure column drop.** If a newer build removed a field and
  was rolled back, nothing is left in the live schema to detect, so the old jar boots and may re-add
  the column empty. The detector's exact coverage is tabulated under
  [Refusals and rollback](#refusals-and-rollback).
- **Dropping a concept needs one boot of ownership history first (`LNCH-1-B7`).** The executor only
  drops an orphaned table it can *prove* NPDev created — see
  [Dropping a concept](#dropping-a-concept). An app upgraded from a build older than this mechanism
  has no ownership record yet, so its first upgrade will not drop a concept's table; the boot after
  that will. This is deliberate: without ownership evidence, a table someone created by hand in the
  same schema is indistinguishable from a dropped concept, and the executor refuses to guess.
- **Single-instance migrations.** The schema-lifecycle executor assumes exactly one app instance
  boots against a given database at a time (the platform's deployment posture — see
  `docs/DEPLOYMENT.md`). Concurrent boots of two instances are **not** guarded by a database lock; do
  not roll out multi-instance deployments of the same app+database until a migration lock exists
  (tracked as `LNCH-1-B6` in `docs/OPEN_GAPS_AND_ROADMAP.md`).

## See also

- `docs/DEPLOYMENT.md` — the Postgres-first Docker Compose deployment this feature is exercised
  against in practice.
- `docs/CONFIGURATION.md` — the sibling reference for startup-validation refusal messages (same
  anchor-ID convention as this document).
- `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\phase-0.md` through `phase-7.md` —
  the full implementation history, live-verification evidence, and every real bug found along the
  way.
