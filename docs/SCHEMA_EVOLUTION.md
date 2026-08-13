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
- **A pack-derived `renamedFrom` can be synthesized, not hand-typed.** When a pack import skips one
  or more published versions in a single regenerate (e.g. `identity@1.0` straight to `@3.0`, never
  generating `@2.0`), `PackMigrationComposer` composes every intervening version's declared
  `migrations` chain entry into the single immediately-previous marker this section describes —
  collapsing a multi-hop history down to the one string the engine actually consumes, exactly as if
  a human had typed it. This is tracked via `npdev.lock`'s `migratedVersion` field: **regenerate
  (and, ideally, boot) after every pack-version bump you actually intend to apply to a real
  database**, rather than batching several bumps into a single skipped regenerate you never boot in
  between — an app that regenerates repeatedly without booting the intermediate build has no way for
  the platform to know which hops were actually reached live, the same class of staleness a
  hand-authored marker risks if left too long.

## Dropping a concept

Removing a concept from the model drops its table — but only once the platform can **prove it owns
that table**, and only behind an explicit acknowledgment token.

> **A concept drop always requires the itemized token.** Unlike a column drop, it is *not*
> authorized by the deprecated blanket `destructiveAllowed` flag: that flag is set once at authoring
> time and would otherwise silently authorize every future concept drop for the life of the app.
> Supply the token via the manifest's `destructiveAcknowledgment` or the ControlPanel channel.

Every successful boot records the NPDev-owned business tables in `npdev_schema_metadata` under
`ownedBusinessTables`. The recorded set is the tables this build declares **unioned with what was
already recorded**, then **intersected with the tables that actually exist**. The union matters: a
table that was dropped from the model but still physically exists — because a previous pass was
refused, crashed, or took the whole-schema path — stays owned, and so remains cleanable by a later
upgrade instead of becoming permanently orphaned. The intersection keeps the set honest: anything
genuinely gone falls out, so it never grows without bound.

On a later boot, a live table that (a) the current model no longer declares, (b) is not the old side
of a declared rename, and (c) **appears in that recorded owned set** is a genuine dropped concept:
the boot classifies as destructive, `SchemaDeltaReport` itemizes it as `DROP_TABLE`, and it is
dropped surgically once acknowledged.

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
- The field is declared **unique** (single- or compound-field) and **more than one existing row**
  would receive the backfill (REG-61(b)): a literal default writes the SAME value into every
  affected row, so it cannot satisfy uniqueness once more than one row needs it — proceeding would
  only trade this refusal for a confusing duplicate-key failure once the unique constraint is
  applied later in the same boot. There is no model-level `default` mechanism expressive enough for
  "a value unique per row" (e.g. derived from the row's own id), so v1 refuses by name instead of
  guessing one. **Recovery recipe** (out-of-band SQL, run once before the next boot):
  ```sql
  -- Repeat per named column, using a value guaranteed unique per row (here: the row's own id).
  ALTER TABLE <table> ADD COLUMN IF NOT EXISTS <column> <type>;
  UPDATE <table> SET <column> = '<prefix>-' || CAST(id AS VARCHAR(36)) WHERE <column> IS NULL;
  ALTER TABLE <table> ALTER COLUMN <column> SET NOT NULL;
  ```
  Then retry the boot: it sees the column already converged and moves on. (This is exactly what
  WmsOffice's live recovery did for `identity_roles.name` / `identity_users.username`, generalized.)

Every refusal names every offending `table.column` and tells you exactly what to change; the
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

**3. Boot the upgrade.** With a matching token, the executor logs (in order). The block below is a
**verbatim capture from a real run** — a concept drop authorized purely through the ControlPanel
channel above, against real Postgres 15, with real seeded data
(`..\NPDev_General__OutsideRepo\lnch1-evidence\hardening-X6.md`, 2026-07-20). Note that the new
build was deployed with **no** `-AcknowledgeDestructive` flag at all: the pending row is what
authorized it, which is why the second line says *via a ControlPanel pending acknowledgment*.

```
NPDev schema lifecycle: destructive change acknowledged by itemized token (via a ControlPanel pending
  acknowledgment); executing surgically (only the affected table(s)/column(s), LNCH-1 Phase 4).
  Report: [DROP_TABLE:projects]
NPDev schema lifecycle: pre-drop snapshot written to ...\runtime-data\schema-snapshot-before-drop\20260720-232647-895
NPDev schema lifecycle: surgical destructive changes applied: [DROP_TABLE projects]
NPDev destructive schema recreation cleared Flyway history for schema-realization scripts: [R__npdev_schema_additive_columns.sql, V1__npdev_schema_realization.sql]
NPDev schema lifecycle: added and backfilled new required column(s) to their declared literal default,
  then enforced NOT NULL (LNCH-1 Phase 5): [users.department]
```

The concept drop and the new required field each apply through their own mechanism, on the same
boot, without either one waiting on or blocking the other. Observed in that run: the `projects`
table was gone (its REST endpoint 404s) with all 3 of its rows preserved in the pre-drop snapshot's
`projects.jsonl`; all 3 `users` rows survived, each carrying `department = "unassigned"`, and the
column ended genuinely `NOT NULL` (a direct SQL `INSERT ... department NULL` was rejected by
Postgres); the pending acknowledgment row was consumed; and a second boot was a clean no-op
(`stored schema fingerprint matches generated schema fingerprint`).

> **One line was removed from the capture above, deliberately and with disclosure.** The 2026-07-20
> run also emitted, as its first line:
> `NPDev schema lifecycle: relaxed NOT NULL on no-longer-required column(s): [users.version, users.row_version, users.tenant_id]`
> That line was **a defect, not correct behaviour** — finding `T-B1`. The relax pass was stripping
> `NOT NULL` from the platform-managed columns on every fingerprint-changing boot, because they appear
> in the manifest's full column set but never in its model-derived *required* set. It is fixed (see
> [Platform-managed columns](#platform-managed-columns)), so a current build cannot emit it, and
> leaving it in a block labelled "verbatim capture" would ship a fixed bug as documentation. It is
> quoted here rather than silently deleted so the capture stays honest about what it originally read.

### Worked example: an ordinary additive upgrade repairing an already-loosened database

Verbatim capture from a real run, 2026-07-21, against **real Postgres 15** (container
`npdev-lnch1-rehearsal-pg`, database `npdev_lnch1_rehearsal`) holding **3 real rows** — an app whose
platform columns had been left nullable by an earlier build, taking one ordinary optional field
(`User.notes`) as its upgrade:

```
NPDev schema lifecycle: NOTICE -- this app is configured with the deprecated blanket
  'destructiveAllowed' posture ...
NPDev schema lifecycle: restored NOT NULL on platform-managed column(s) relaxed by an earlier build
  (LNCH-1 T-B1 repair): [users.version, users.row_version, users.tenant_id]
NPDev schema lifecycle: fingerprint changed from sha256:b8d3e882... to sha256:3d793ad0... but every
  difference is a new non-bond column on an already-existing table; skipping destructive recreation
  (handled by the additive repeatable migration).
NPDev schema lifecycle: flyway.repair() reconciled schema-realization checksums for the additive change.
```

Note what is **absent**: there is no `relaxed NOT NULL on no-longer-required column(s)` line. The
repair line appears in its place.

Verified against the live database immediately afterwards:

| | Before the upgrade | After |
|---|---|---|
| `users.version` | nullable | **`NOT NULL`**, default `0` |
| `users.row_version` | nullable | **`NOT NULL`**, default `0` |
| `users.tenant_id` | nullable | **`NOT NULL`**, default `'default'` |
| `users.id` | `NOT NULL` | `NOT NULL` (the primary key, never affected) |
| rows | 3 | **3, intact** |

The three existing rows carried `tenant_id = 'dev'`, and **kept it** — the repair backfills only
`NULL`s, it never overwrites a real value. One audit row records it:

```
classification            | outcome | items_json
TIGHTEN_PLATFORM_COLUMNS  | APPLIED | ["TIGHTEN_PLATFORM_COLUMN users.version DEFAULT 0",
                                       "TIGHTEN_PLATFORM_COLUMN users.row_version DEFAULT 0",
                                       "TIGHTEN_PLATFORM_COLUMN users.tenant_id DEFAULT default"]
```

Evidence: `..\NPDev_General__OutsideRepo\lnch1-evidence\platcol-T9.md`.

**Without a matching token**, the boot refuses outright — the app never starts with a half-applied
or silently-guessed **destructive** change (the safe convergent steps — renames, relaxations — may
already have applied; see [Refusals and rollback](#refusals-and-rollback)):

```
Schema fingerprint changed from <old> to <new> and includes destructive change(s) requiring an
explicit, itemized acknowledgment. Itemized destructive report: [...]. Expected acknowledgment
token: <token>. Set the generated manifest's destructiveAcknowledgment to this token, or submit it
via the ControlPanel schema-migration screen on the currently running app, to proceed.
```

### The deprecated blanket flag

A blanket "destruction is pre-authorized" posture is ON only when **all four** `schemaLifecycle`
fields line up (`SchemaManifest#destructiveAllowed`):

```
strategy == "DropAndRecreateOnStructureChange"  &&  allowDestructiveRecreate == true
  &&  scope == "NpdevOwnedTablesOnly"
  &&  destructiveRecreateConfirmation == "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED"
```

**What it does when ON:**

| Change | Blanket flag alone | Needs an itemized token |
|---|---|---|
| Drop a column | authorizes it | no |
| Narrow a column's type | authorizes it | no |
| **Drop a whole concept (table)** | **does NOT authorize it** | **yes** |
| **A diff that cannot be explained item by item (whole-schema recreation)** | **does NOT authorize it** | **yes** |

**The rule in one sentence:** the blanket flag authorizes only surgical column drops and type
narrowings; anything that destroys a whole table's worth of data requires the itemized token.

- Authorized changes are **executed surgically** — only the itemized tables/columns are touched.
- The whole-schema recreation is reached only when the delta report contains an `UNKNOWN` item that
  cannot be explained item by item. Because it drops and recreates **every** table in the app, it
  requires the itemized token too — the blanket flag alone is **refused**, and the refusal prints
  the UNKNOWN item(s) and the token that would authorize the pass.

### The whole-schema recreation is a safety net, not a path you can reach normally

**A model change made through the normal authoring flow cannot produce an `UNKNOWN` item.** A missing
column is itemized as `UNKNOWN` only when it is declared by the manifest, not additive-eligible, and
not explained by a declared rename. In a manifest emitted by the current generator, every such column
is either a **required bond** — which is intercepted earlier by its own dedicated refusal naming the
field — or a **many-to-many bond**, which has no column at all. The one remaining exception used to
be the platform column `version`; it is now additive-eligible and self-heals.

**The path is deliberately kept anyway.** It exists for states the generator cannot emit but that do
occur in the field:

- a schema modified **by hand** outside NPDev (a column dropped or renamed directly in the database);
- an app still running a **build older than this behaviour**, whose manifest declares `version` as
  non-additive;
- a **future change to the generator** that introduces a new kind of non-additive column.

The last is the important one: "no current model change reaches this" is a property of today's
generator, not a guarantee of the design. So the path stays, and stays behind the itemized token —
it destroys every table's data, and the cost of keeping an unreachable guard is nothing next to the
cost of removing one that later becomes reachable.

**Practical consequence for operators:** if a boot ever refuses with an `UNKNOWN` item, treat it as a
signal that the database has diverged from what NPDev generated — not as a routine upgrade prompt.
Investigate the named column before supplying the token, because supplying it destroys every table.

**If you relied on "just recreate everything on boot" in dev/CI**, that behaviour is gone on a
blanket-only app. Use one of these instead, in order of preference:

1. **Delete the database file/volume between runs** — this is what a dev loop actually wants, and it
   is the only option that guarantees a clean slate.
2. **Use a `freshdb`-style app definition** (several ship already, e.g.
   `simple-user-registry-h2local-freshdb`).

> Note: `strategy: RecreateOnAppStart` is **not** an escape hatch. Despite the name it has no
> distinct runtime behaviour — the strategy string is only read to evaluate the blanket posture
> above, so `RecreateOnAppStart` behaves exactly like `KeepExistingIfCompatible` and recreates
> nothing.
- Every use prints a loud deprecation warning (`NPDev schema lifecycle: DEPRECATION WARNING`) naming
  exactly what is about to be executed, plus a one-line `NOTICE` on **every** boot of an app
  configured this way — so the posture is visible before the day it matters.

**Default for new apps: OFF.** New app definitions should use `strategy: KeepExistingIfCompatible`
with `allowDestructiveRecreate: false`, which makes the itemized token the only route to destruction.
The flag exists for backward compatibility with apps generated before this feature; existing apps
keep working unchanged.

> **Where the shipped corpus actually stands (as of REG-1, 2026-07-21).** The recommended posture is
> the documented default, and after the REG-1 flip batch the corpus now agrees with it. Counting all
> **20** `db.definition.json` files under `AppGen\apps` and `NPDevSamples`:
> - **13 on the recommended posture** (`KeepExistingIfCompatible` + `allowDestructiveRecreate: false`):
>   the four `_official` apps `WmsOffice`/`WordLab`/`AuxScreen`/`Pigmentampa`, plus `invoice-bonds-demo`,
>   `restaurant-saas-multitenant`, `superuser-admin-console` (all flipped in REG-1), plus the six
>   already on it — `simple-user-registry-h2local`, `simple-product-h2local`, `simple-consumer-h2server`,
>   `simple-user-registry-postgres`, `NPDevSamples\12works\gift-idea-tracker`, `npdev_split_model_sample_app`
>   (the last is `InMemory`, so the posture is moot, but its strategy is the recommended one).
> - **2 still on the blanket posture, both DELIBERATELY** — `lnch1-rehearsal` (exists to rehearse
>   upgrades on a shipped-shaped definition) and `simple-user-registry-h2local-freshdb` (the cited
>   "freshdb" CI pattern). Flipping either would defeat the scenario it exists to test.
> - **5 `InMemory` + `RecreateOnAppStart`** (`_official\Claude`, `pack-sample`, `simple-user-registry-inmemory`,
>   `canonical-demo`, `simple-contact-intake`) — non-persistent, so the posture does not apply.
>
> Every remaining blanket-posture definition is now blanket **for a documented reason**, not by
> default — that is REG-1's "done" state. Recount with:
> `grep -rl '"strategy": "KeepExistingIfCompatible"'` vs
> `grep -rl '"strategy": "DropAndRecreateOnStructureChange"'` across those two trees.

**Migrating an existing app off it:** set `strategy` to `KeepExistingIfCompatible` and
`allowDestructiveRecreate` to `false` in `db.definition.json`, then use
`Build-NpdevApp.ps1 -Upgrade -PlanOnly` to review the plan and
`-AcknowledgeDestructive <token>` (or the ControlPanel channel) to authorize each destructive change
deliberately. Nothing non-destructive changes: new columns, renames, safe widenings, and NOT NULL
relaxations still apply automatically.

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

The executor guards this on every fingerprint-MATCH boot with a **schema-ahead-of-build detector**
(Triggers A/B), plus a **history-based check** (Trigger C, REG-8) that runs on the MISMATCH branch
instead — together they are a best-effort safety net, not a complete guarantee, but REG-8 closed the
one gap that used to be silent:

| Situation | Detected? | How |
|---|---|---|
| A newer build **renamed an ordinary field** (`name` → `full_name`) | **Yes** | Trigger B |
| A newer build **renamed or dropped a whole concept's table** | **Yes** | whole-table rule |
| A **required bond/FK column** is missing | **Yes** | Trigger A |
| A newer build **purely dropped a column** (nothing left behind) | **Yes** | Trigger C |

- **Trigger A — a missing column that is not additive-eligible.** Catches required and
  many-to-many bond columns: things nothing re-adds automatically. It no longer catches `version` —
  that column is additive-eligible and self-heals, along with `row_version` and `tenant_id` (see
  [Platform-managed columns](#platform-managed-columns)). `id` is the primary key and is present by
  construction on any table that exists at all.
- **Trigger B — a missing column on a table that also carries an *unexplained extra* live column.**
  An unexplained extra is a live column this build's manifest does not declare, that is not a
  platform column (`id`, `version`, `row_version`, `tenant_id`), and is not the old side of a
  declared rename. `name` missing while `full_name` is present is the signature of a rename by a
  newer build.
- **Whole-table rule.** If a declared table has no live columns at all, the refusal says
  `<table> (entire table missing)` rather than listing every column separately.
- **Trigger C — this database was migrated past this build (REG-8, closed 2026-07-22).** Triggers
  A/B only run on a fingerprint-**MATCH** boot and only see LIVE SCHEMA SHAPE — a pure column drop
  leaves no shape residue at all, which is exactly why it used to be invisible. Trigger C instead runs
  on the fingerprint-**MISMATCH** branch (the actual shape a rollback-after-a-real-upgrade takes) and
  consults `npdev_schema_history`, not live shape: it finds the most recent successfully-applied row
  for THIS build's *own* target fingerprint (none — a legitimate first-time deploy of this fingerprint,
  stays silent) and checks whether a *later* row exists recording a different fingerprint. If so, some
  other build has moved this exact database past the point this build last owned it, and the boot
  refuses — before `classify()` ever runs, ahead of every resolution (safe-additive, rename, type
  change, or destructive) alike, not just the column-drop case that originally motivated it. A
  [manually-marked-done](#marking-a-migration-as-done) fingerprint is exempt by construction (the mark
  check runs earlier in the same method and returns before Trigger C's code is ever reached) — an
  operator's explicit "this older build legitimately takes back over" always wins.

**Residual limitation.** Trigger C's signal depends on `npdev_schema_history` staying intact — if that
audit table were reset or tampered with independently of the business schema it describes (the same
trust assumption every self-bootstrapped NPDev bookkeeping table in this system makes), the signal is
lost. This is a materially smaller gap than the pre-REG-8 state, where a pure column drop was
*unconditionally* invisible regardless of history.

## Snapshots and restore

Every destructive drop — surgical or whole-schema — is preceded by a best-effort snapshot:
`runtime-data/schema-snapshot-before-drop/<yyyyMMdd-HHmmss-SSS>/<table>.jsonl` (one JSON object per
row) plus a `_summary.json` describing what was captured. The 5 most recent snapshot directories are
retained; older ones are pruned automatically. A snapshot failure is logged loudly
(`DATA LOSS NOT SNAPSHOTTED`) but never blocks the drop it's protecting — a safety net that could
itself crash the boot would be worse than none.

**Restoring (Move 9 B3, `SchemaDropSnapshotRestorer`).** An operator-driven ControlPanel command,
never automatic and never "restore everything" — every call names one exact `(snapshot, table)`
pair:

- `GET /api/admin/schema-migration/snapshots` — every snapshot directory available, most recent first.
- `GET /api/admin/schema-migration/snapshots/{snapshot}/tables` — every table that snapshot captured.
- `GET /api/admin/schema-migration/snapshots/{snapshot}/tables/{table}/preview` — read-only: compares
  the snapshot's rows against the CURRENTLY LIVE table by `id` and reports how many would be
  inserted, how many already match live, and which ids **conflict** (present live with *different*
  content since the drop).
- `POST /api/admin/schema-migration/snapshots/{snapshot}/tables/{table}/restore` — inserts every
  missing row. A row already live and identical is skipped; a **conflicting row is reported but never
  overwritten** — that is always left for the operator to resolve by hand. Refuses outright if the
  live table does not exist yet (restore is data-only, never schema — boot the app normally first so
  the table exists).

This deliberately does not attempt schema-shape reconciliation (a snapshot column since removed from
the model is simply not restored; a live table's new required column since added is not backfilled by
this path) or a bulk "restore every table" mode — see [Current limitations](#current-limitations).

## Platform-managed columns

Four columns on every business table belong to the platform, not to your model:

| Column | Type | Default | Purpose |
|---|---|---|---|
| `id` | per the concept's id field (`UUID` when undeclared) | — | Primary key |
| `version` | `BIGINT` | `0` | The generated entity's JPA optimistic-version check |
| `row_version` | `BIGINT` | `0` | The compare-and-swap the ConceptGateway/ConceptStore perform (`LNCH-16`) |
| `tenant_id` | `VARCHAR(120)` | `'default'` | Tenant scoping — every tenant-scoped read filters on it |

Three properties follow, and the platform enforces all of them:

- **They are always `NOT NULL`.** A fresh `CREATE TABLE` emits them `NOT NULL` with the defaults
  above, and an upgrade never loosens them. A `NULL` `tenant_id` would make a row invisible to every
  tenant-scoped read; a `NULL` `row_version` would silently defeat the compare-and-swap.
- **Their nullability never follows a model field's optionality.** Making one of your fields optional
  relaxes *that* column's `NOT NULL` — never a platform column's. This is unambiguous because a model
  field can never be *named* `version`, `row_version` or `tenant_id`: the generator rejects it at
  generation time, so a live column with one of those names is always platform-owned.
- **A missing one is added back.** `version`, `row_version` and `tenant_id` are additive-eligible: if
  an existing table lacks one, the ordinary additive migration adds it with the default above. A
  missing platform column is never treated as an unexplainable difference, and therefore never
  requires a destructive acknowledgment.
- **A loosened one is repaired.** If an earlier build left one nullable, the next boot backfills any
  `NULL`s to the default above and restores `NOT NULL`, recording a `TIGHTEN_PLATFORM_COLUMNS` row in
  `npdev_schema_history` so the repair is auditable.

You never declare, migrate, or acknowledge anything for these columns.

## External unmanaged database

**REG-7.1.** Some apps run against a database NPDev does not own — a pre-existing legacy schema, or
one an operator manages by hand. Declare this in `db.definition.json`:

```json
{
  "database": { "engine": "Postgres", "host": "...", "port": 5432, "username": "...", "password": "...",
                "createInternalTables": true, "createBusinessTables": true },
  "schemaLifecycle": {
    "strategy": "KeepExistingIfCompatible",
    "allowDestructiveRecreate": false,
    "destructiveRecreateConfirmation": "",
    "scope": "NpdevOwnedTablesOnly",
    "ownership": "ExternallyManaged"
  }
}
```

`ownership` is optional and defaults to `NpdevManaged` (today's behavior, unaffected). It is
**orthogonal to `strategy`**: `strategy` answers *how* NPDev migrates when it owns the schema;
`ownership` answers *whether it touches schema DDL at all*. Setting `ExternallyManaged` makes
`strategy`/`allowDestructiveRecreate` inert — there is no DDL left for them to govern — so the
generator requires `strategy: KeepExistingIfCompatible` and `allowDestructiveRecreate: false`
alongside it and refuses to generate otherwise (a recreate/destructive strategy is nonsensical when
NPDev issues no DDL).

**At boot, `ExternallyManaged` mode:**

1. Issues **zero** schema DDL — `flyway.migrate()` is never called at all, so Flyway does not even
   initialize its own bookkeeping table in this database.
2. Runs a **read-only compatibility check** every boot (there is no fingerprint-match fast path here —
   nothing converges the schema, so there is nothing to converge *toward*, only to verify, cheaply,
   every time): every table/column this build's model declares must exist live with a compatible SQL
   type.
3. **Proceeds** if compatible (records an `EXTERNAL_VERIFIED` row in `npdev_schema_history`), or
   **refuses** with an itemized message naming exactly what is missing or type-mismatched (records
   `EXTERNAL_REFUSED`):

   ```
   This app declares schemaLifecycle.ownership=ExternallyManaged (NPDev does not own this database's
   schema and will never issue DDL against it), but the live schema cannot serve this build's model.
   Incompatibilities: [orders.total (column missing)]. Either alter the external schema by hand to
   match the model, or fix the model to match the external schema.
   ```

Resolve a refusal by altering the external schema by hand (the operator's responsibility in this
mode), or by changing the model to match what the external schema actually has.

## Marking a migration as done

**REG-7.2**, GeneXus-style: "the schema is already at this fingerprint; stop trying to migrate to it."
An operational act against a *running database*, independent of any specific build — so unlike the
destructive-acknowledgment token, there is no generator/CLI round-trip in v1: it is a ControlPanel-only
endpoint, submitted on the currently running app (SUPERUSER-gated, `X-Super-User-Key` header, same
pattern as every other ControlPanel endpoint):

```
POST /api/admin/schema-migration/mark-done
{ "fromFingerprint": "sha256:...", "toFingerprint": "sha256:...", "note": "verified by hand, already migrated" }
```

```
GET /api/admin/schema-migration/marks
```

lists every recorded mark. **REG-28**: the mark is bound to that exact `fromFingerprint -> toFingerprint`
transition, not just the target — it only fires on a boot whose OWN live stored fingerprint still equals
`fromFingerprint`. Read both values off the SAME migration plan printed by
`Build-NpdevApp.ps1 -PlanOnly`/`-Upgrade` (it already prints the pair) or from the currently running
app's own stored fingerprint. A leftover/abandoned mark can no longer fast-forward an unrelated boot
just because its target happens to match. **REG-30**: a duplicate mark for the identical transition is
rejected outright (unique constraint) instead of leaving a second copy that could fast-forward a later
boot a second time.

On the **next boot** whose OWN live stored fingerprint equals the mark's `fromFingerprint` and whose
target equals `toFingerprint`, the executor:

1. Fast-forwards the stored fingerprint pointer (`npdev_schema_metadata`) straight to `toFingerprint`.
2. Records a `MANUALLY_MARKED_DONE` row in `npdev_schema_history`.
3. Consumes the mark (deleted after use — a mark authorizes exactly one boot, not every future one).
4. Runs **zero** rename/relax/tighten/classify/destructive passes — the operator's claim *is* that the
   live schema already matches this build's model, so there is nothing for the executor to converge.
   (Flyway's own idempotent `CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` scripts still run
   afterward as normal — harmless no-ops if the claim holds, a safety net if it does not.)

This is also [Trigger C's](#refusals-and-rollback) escape hatch: if REG-8 refuses because history
shows a newer fingerprint already applied, and you deliberately intend an older build to take back
over, mark that older build's fingerprint done and redeploy.

## Collision detection

**REG-7.3.** The platform's deployment posture is single-instance (`docs/DEPLOYMENT.md`) — exactly one
app instance boots against a given database at a time. This feature makes a violation **loud** instead
of silently interleaving migrations, without introducing a real lock: a single-row **claim** in a
self-bootstrapped `npdev_schema_migration_claim` table (`claim_key` fixed at `'schema-migration'`,
primary-keyed so a second concurrent `INSERT` fails), taken at the top of every upgrade boot and
released in a `finally`:

- **Held by someone else** → the boot refuses immediately, naming the holder:
  ```
  Another NPDev instance is currently migrating this database (instance <uuid> on host <hostname>,
  claimed at epoch-ms <t>). Concurrent schema migrations are not supported (REG-7.3) -- wait for it to
  finish and retry, or if it crashed mid-migration, clear the stale claim via POST
  /api/admin/schema-migration/clear-claim (SUPERUSER)...
  ```
- **Crashed holder (stale claim)** — the manual escape hatch, SUPERUSER-gated:
  ```
  GET  /api/admin/schema-migration/claim        # inspect: { "held": true/false, ... }
  POST /api/admin/schema-migration/clear-claim   # unconditionally deletes the row
  ```
  Clearing a claim while another instance genuinely holds it **re-introduces the exact race** this
  feature detects — that is a deliberate operator decision this endpoint trusts the caller to make.

**Honest limitations (D3):**
- **This is detect-and-refuse, not a lock.** A true TOCTOU race between two near-simultaneous
  `INSERT`s is possible on an engine without strict insert serialization. If this becomes frequent in
  practice, the documented upgrade path is a real `pg_advisory_lock` (Postgres) + an H2 lock table —
  deliberately not built for v1 per the owner's "add guard rails later if needed."
- **The very first-ever boot of a brand new database is not claim-protected.** The claim is only
  attempted when a schema fingerprint is already stored (an upgrade/repeat boot). Claiming
  unconditionally on a genuinely virgin database would self-bootstrap this table *before*
  `flyway.migrate()` ever runs, which makes Flyway see a non-empty schema with no history table and
  refuse outright — a real bug this scoping decision avoids (found and fixed via a live boot
  rehearsal). In practice this narrows the gap to something *smaller* than the register's own
  practical example (two containers racing against an *already-initialized* database), which — the
  common real-world case — is fully protected.
- **`ExternallyManaged` apps never claim.** NPDev issues no DDL against them, so there is nothing to
  serialize.

## Current limitations

Deliberately out of scope for this feature (recorded here as known future work, not silently
missing):

- **No automatic rename inference.** Every rename must be declared via `renamedFrom` — there is no
  identity-tracking (uid-based) mechanism that infers "this looks like the same field, renamed."
- **Expression-valued backfills require an explicit dry-run + acknowledgment (Move 9 B1).** A
  `defaultExpression`-only required field on a populated table is no longer refused outright: `GET
  .../expression-backfill-preview` reports what it WOULD compute for every existing row, and
  `BackfillPass` only applies it once the operator acknowledges (same channel as a destructive
  change) and a fresh re-evaluation still has zero rows that fail to produce a value. A row that
  cannot be evaluated (a referenced field absent from it) still blocks the backfill entirely — see
  [Snapshots and restore](#snapshots-and-restore) for the analogous data-recovery command.
- **Snapshot restore is operator-driven, one table at a time (Move 9 B3).** `SchemaDropSnapshotRestorer`
  restores exactly one `(snapshot, table)` pair per call — never "restore everything" — and never
  overwrites a row that is live with content different from the snapshot; see
  [Snapshots and restore](#snapshots-and-restore).
- **H2→Postgres data promotion is a real command now (Move 9 A4), but data-only.** `CrossEngineDataPromotion`
  (`POST /api/admin/schema-migration/promote/preview` then `/promote/apply`) copies rows typed per
  column; it never realizes schema on the target and never reconciles a shape that has drifted since
  the target's schema was last realized — that target table must already exist.
- **`ExternallyManaged` compatibility verification is column-shaped only (REG-7.1).** It confirms
  every declared table/column exists live with a compatible SQL type; it does NOT check nullability,
  uniqueness constraints, indexes, or foreign keys. A schema that passes this check can still be
  incompatible in ways the check does not look for.
- **"Mark migration as done" trusts the operator completely (REG-7.2).** It is not a verification —
  fast-forwarding the stored fingerprint does not confirm the live schema actually matches the model.
  If the claim is wrong, nothing in this mechanism catches that; the executor simply stops trying to
  converge the schema, on the operator's word alone.
- **InMemory-storage apps have no DDL.** This entire mechanism no-ops cleanly for them (checked via
  `manifest.physicalDatabase()`) — there is nothing to migrate.
- **Refusals are not side-effect-free.** The safe convergent steps (table/field renames, NOT NULL
  relaxations) apply before the acknowledgment decision, so a refused upgrade has already applied
  them; recovery is roll-forward or restore, never redeploying the old jar (which the schema-ahead
  detector refuses in the cases it can see). See [Refusals and rollback](#refusals-and-rollback).
- **A pure column drop + rollback is refused, not silently reconstructed (REG-8, closed 2026-07-22).**
  Trigger C consults `npdev_schema_history` (not live shape) to detect that this exact database was
  migrated past this build, and refuses instead of letting the old jar boot and silently re-add the
  dropped column empty. This is a detection-vs-reconstruction distinction, not a full guarantee: the
  data the drop destroyed is still gone (recoverable only via the pre-drop snapshot or a backup, per
  [Snapshots and restore](#snapshots-and-restore)) — REG-8 makes the *situation visible and refused*, it
  does not reconstruct the lost column. See [Refusals and rollback](#refusals-and-rollback) for
  Trigger C's exact scope and residual limitation.
- **Dropping a concept needs one boot of ownership history first (`LNCH-1-B7`).** The executor only
  drops an orphaned table it can *prove* NPDev created — see
  [Dropping a concept](#dropping-a-concept). An app upgraded from a build older than this mechanism
  has no ownership record yet, so its first upgrade will not drop a concept's table; the boot after
  that will. This is deliberate: without ownership evidence, a table someone created by hand in the
  same schema is indistinguishable from a dropped concept, and the executor refuses to guess.
- **Single-instance migrations: now detected and refused, not silent (REG-7.3, closed 2026-07-22).**
  The platform's deployment posture is still single-instance (see `docs/DEPLOYMENT.md`), but a
  concurrent second boot against the same database no longer silently interleaves migrations — it is
  refused loudly, naming the holder (see [Collision detection](#collision-detection)). This is
  detect-and-refuse, **not** a lock: a true near-simultaneous-insert race remains possible in theory,
  and the very first-ever boot of a brand new database is not claim-protected (only
  upgrade/repeat boots are). Do not roll out multi-instance deployments of the same app+database
  relying on this as a strict mutex; if collisions become frequent in practice, the documented upgrade
  path is a real database lock (`pg_advisory_lock` + an H2 lock table), not built for v1.

## Tenant-id canonicalization (REG-25)

As of REG-25 the runtime canonicalizes `tenant_id` to **lowercase** at a single choke point
(`com.npdev.kernel.ExecutionContext`), so every *new* read and write already uses one bucket per
logical tenant regardless of casing (`Acme` and `acme` are the same tenant). This matches the tenant
registry, which has always lowercased on insert.

**Existing data written before REG-25** may still hold mixed-case `tenant_id` values (e.g. business
rows under `Acme` while the registry says `acme`). A one-time, operator-run migration tool converges
them — it is **never run automatically and never on boot**:

```
# dry-run (default): report per-table row counts + any collision buckets, write nothing
pwsh -File scripts/ops/canonicalize-tenant-ids.ps1 -JdbcUrl 'jdbc:h2:tcp://localhost:9092/npdevdb'

# apply: lowercase tenant_id everywhere; SKIP + report any table whose casings would MERGE
pwsh -File scripts/ops/canonicalize-tenant-ids.ps1 -JdbcUrl '<url>' -Apply

# force: also merge the collision tables (DANGEROUS: can join two tenants' data / violate a PK)
pwsh -File scripts/ops/canonicalize-tenant-ids.ps1 -JdbcUrl '<url>' -Apply -Force
```

- Tenant-id-bearing tables are discovered from `information_schema` (no hard-coded table list); H2
  (bundled `h2-*.jar`) and PostgreSQL (`psql` on PATH) are auto-detected from the JDBC URL.
- **Collision detection is primary-key-agnostic:** any `LOWER(tenant_id)` bucket that more than one
  distinct casing maps to is a collision (a blind lowercase would merge those rows). `-Apply` skips
  and reports those tables so the operator resolves them deliberately before re-running (or opts in
  with `-Force`). No forced in-place migration is ever performed for you.
- Run it once per deployment during a maintenance window, dry-run first.

## See also

- `docs/DEPLOYMENT.md` — the Postgres-first Docker Compose deployment this feature is exercised
  against in practice.
- `docs/CONFIGURATION.md` — the sibling reference for startup-validation refusal messages (same
  anchor-ID convention as this document).
- `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\phase-0.md` through `phase-7.md` —
  the full implementation history, live-verification evidence, and every real bug found along the
  way.
