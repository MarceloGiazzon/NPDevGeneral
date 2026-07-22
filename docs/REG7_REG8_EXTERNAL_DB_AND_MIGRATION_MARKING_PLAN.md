# REG-7 + REG-8 → Features: External-DB Ownership, Migration Marking, Collision Detection

> **Status:** APPROVED PLAN — not started.
> **Written:** 2026-07-21, against commit `25b4e20` (branch `beta1-vision-spine`). Working tree clean
> except this file.
> **Origin.** REG-7 (`LNCH-1-B6`, no multi-instance migration lock) and REG-8 (`LNCH-1-B9`,
> schema-ahead detector blind to a pure column drop) were both filed as **boundaries** — deliberate
> limits, not bugs. The project owner (Marcelo) has decided to **convert them into features** rather
> than leave them as documented limits, with an explicit "advanced users solve manually" posture:
> fail loud and clear, give the operator the tools to resolve it by hand, and only add automatic
> guard rails later *if* the failure turns out to be frequent. This plan turns that decision into
> buildable work.
> **Audience.** An AI implementation session (or human) that has **not** read this project's history.
> Follow it phase by phase, in order. Where it says **VERIFY**, check the real code first — every line
> number and method name here was captured 2026-07-21 by reading the actual source and **will drift.**
> **This is the platform's most adversarially-reviewed subsystem.** `SchemaLifecycleExecutor` went
> through all five LNCH-1 rounds (HIGH → CRITICAL → HIGH → MEDIUM → none). Treat every change as
> capable of creating the next round's finding (retrospective §5 lesson 1). The guardrails in §3 are
> not optional.

---

## 0. What the owner actually asked for (do not re-scope this)

Direct paraphrase of the decision, so no implementer "improves" it into something else:

**REG-7 → three distinct capabilities:**
1. **A database-ownership property.** Let a project declare that NPDev **does not own** the database
   schema — the DB pre-exists (legacy system), or the operator runs the DDL themselves. In that mode
   NPDev must **never issue schema DDL**; it only verifies compatibility and refuses to boot (clearly)
   if the live schema can't serve the model.
2. **A "mark migration as done" operation.** GeneXus-style. Let an advanced operator declare "the
   schema is already at fingerprint X; record that and don't try to migrate to it." This
   fast-forwards NPDev's stored fingerprint pointer with **no DDL executed.**
3. **Collision detection, manual resolution.** If two instances ("two KBs" in GeneXus terms) collide
   on the same database, **give a clear error and let the advanced user solve it manually.** Explicitly
   *not* an automatic lock in v1. "If this kind of error becomes frequent, we add guard rails later."

**REG-8 → error + documented limitation, marked done for this version:**
- When a newer build has migrated the DB past this build (including the pure-column-drop case the
  detector is currently blind to), **give an error** instead of silently re-adding an empty column.
  Advanced user resolves manually. Mark the item **done for the current version** — this closes REG-8
  as "handled by a clear refusal," not as "made fully automatic."

**The through-line:** every one of these is *fail-loud + operator-resolves*, reusing the platform's
existing acknowledgment-token / pending-store machinery rather than inventing new automation.

---

## 1. Read before touching anything, in this order

1. This document, end to end.
2. `docs/SCHEMA_EVOLUTION.md` — the user-facing contract. Its "Current limitations" section documents
   REG-7 and REG-8 as limits **today**; this plan rewrites those two paragraphs into feature docs.
3. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.7 (REG-7) and §1.8 (REG-8) — the boundary rationale and the
   register's own "how to fix (if ever)" notes, which this plan supersedes.
4. `docs/LNCH1_PROGRAMME_RETROSPECTIVE.md` §5 (why fixes to this subsystem create new findings) and §6
   (the working discipline — reproduce RED first, live > suite, fixtures mirror production).
5. The four executor methods this plan touches, read in full before editing (see §2 orientation).

---

## 2. Orientation — every integration point, verified 2026-07-21

Line numbers are from `25b4e20` and **will drift** — the method/field names are the stable anchors.

### 2.1 The runtime executor

`NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` (2,884 lines,
**never full-read** — Grep to a method, Read with `offset`/`limit`):

| Anchor | Line (VERIFY) | What it does / why it matters here |
|---|---|---|
| `migrate(Flyway, SchemaManifest)` | ~214 | **The top-level orchestration.** Order: dataSource-null guard → `manifest == null \|\| !manifest.physicalDatabase()` guard (the InMemory no-op) → deprecated-posture notice → `storedAtBootStart = readFingerprint()` → `beforeMigrate()` → `flyway.migrate()` → `afterMigrate()`. **The external-DB ownership gate (REG-7.1) and the collision claim (REG-7.3) both belong at the top of this method**, right after the `physicalDatabase()` guard at ~221. |
| `beforeMigrate(DataSource, SchemaManifest)` | ~266 | Reads `stored = readFingerprint()`. Three branches: (a) blank → first boot, `DestructiveRecreation.none()`; (b) `stored.equals(manifest.schemaFingerprint())` → runs the **schema-ahead detector** `findSchemaAheadMissingColumns` (~280), throws + writes a `REFUSED` history row if anything is missing; (c) mismatch → renames/relax/classify/destructive passes. **REG-8's new trigger and REG-7.1's verify-only compatibility check both hook branch (a)/(b).** |
| `findSchemaAheadMissingColumns(DataSource, SchemaManifest)` | ~2236 | **The exact template for REG-8.** Iterates `manifest.businessTableColumns()`, compares to live `readActualColumns`. Trigger A = missing non-additive column; Trigger B = missing additive-eligible column + an "unexplained extra" live column (rename signature). Returns a `List<String>` of missing descriptors; the *caller* (branch b, ~281-288) throws `IllegalStateException` + writes the `REFUSED` history row. **REG-8 adds Trigger C here** (or a sibling method — see §5). |
| `readFingerprint(DataSource)` | ~2771 | Reads `metadata_value` for `FINGERPRINT_KEY` from `npdev_schema_metadata`. **This is the "current fingerprint pointer" REG-7.2 fast-forwards.** |
| `afterMigrate(...)` | ~2376 | At its end (~2413/2418) `upsertMetadata(FINGERPRINT_KEY, ...)` + `upsertMetadata(OWNED_TABLES_KEY, ...)`. The write side of the pointer. |
| `FINGERPRINT_KEY` / `OWNED_TABLES_KEY` / `METADATA_TABLE` | 42 / 45 / 41 | `"schemaFingerprint"` / `"ownedBusinessTables"` / `"npdev_schema_metadata"`. The metadata table DDL is at ~2408: `(metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)`. `upsertMetadata` at ~2426 is a ready-made writer for any new key. |
| `writeHistoryRow` / `insertRawHistoryRow` | ~2596 / ~2632 | `npdev_schema_history` writers (`outcome` column is free-text). REG-7.2 and REG-8 both write new outcome values here (`MANUALLY_MARKED_DONE`, `REFUSED`). |
| `SchemaManifest` record | ~2912 | 21-arg `public record`. Fields include `strategy`, `scope`, `allowDestructiveRecreate`, `destructiveRecreateConfirmation`, `destructiveAcknowledgment`. **REG-7.1 adds one field: `ownership`.** Note the existing back-compat convenience constructor at ~2943 — a new field means updating it (or adding another overload) so the ~20 hand-built test manifests keep compiling. |
| `loadManifest()` manifest parse | ~2791 | Reads the generated `schema-realization-manifest.json` into `SchemaManifest`. Add `lifecycle.path("ownership").asText("NpdevManaged")` here — **defaulting to the current behavior** so every pre-existing manifest is unaffected (same pattern the `destructiveAcknowledgment` field documents at ~2809-2814). |

### 2.2 The acknowledgment/pending machinery to reuse (do NOT reinvent)

| File | What it gives you |
|---|---|
| `com/finalexec/db/PendingSchemaAcknowledgmentStore.java` | **The template for both new stores.** Self-bootstrapping (`CREATE TABLE IF NOT EXISTS` it issues itself, ~55), `insert`/`findMatching`/`consume`/`listAll`, never-throws-on-missing-table discipline. REG-7.2's "mark done" pending store and REG-7.3's collision-claim table both follow this exact shape. |
| `com/finalexec/controlpanel/SchemaAcknowledgmentController.java` | The ControlPanel template. `@RequestMapping("/api/admin/schema-migration")`, SUPERUSER-gated via a manual `hasRole("SUPERUSER")` check through `RuntimeContextService` (not an annotation — ~32). REG-7.2's "mark done" endpoint is a sibling `@PostMapping` here. |
| Manifest field `destructiveAcknowledgment` (read at ~2814) | The proof that the **runtime reads operator input from the generated manifest**, baked at generation time. The token is threaded CLI → generator → emitter → manifest JSON, *not* via a Spring property. REG-7.2's CLI flag follows the same path — see §2.3. |

### 2.3 The generator side (for the new `ownership` field + the `-MarkMigrationDone` flag)

| File | What matters |
|---|---|
| `NPDevGenerator/.../dbconfig/SchemaLifecyclePolicy.java` | 4-field record (`strategy`, `allowDestructiveRecreate`, `destructiveRecreateConfirmation`, `scope`) + `destructiveConfirmedFor(engine)`. **REG-7.1 adds a 5th field `ownership`** (an enum or validated string). |
| `NPDevGenerator/.../dbconfig/SchemaLifecycleStrategy.java` | The 3-value enum precedent for how to add `DatabaseOwnership` as a new enum with `parse()` + `externalName()`. |
| `NPDevGenerator/.../dbconfig/UserDatabaseDefinitionLoader.java` (~44-51) | Parses the `schemaLifecycle` JSON node into `SchemaLifecyclePolicy`. Add `text(lifecycle, "ownership")` here. `validate()` at ~112 is where a bad ownership+strategy combination is rejected at generation time. |
| `NPDevGenerator/.../dbconfig/SchemaRealizationEmitter.java` | Emits `schema-realization-manifest.json`. Where the new `ownership` field must be written into the manifest so the runtime `loadManifest` can read it. **This is a `Map.of`/Jackson emitter — heed `NoMultiEntryMapOfInGeneratedManifestEmittersTest` (GATE-DET-1): any multi-entry `Map.of` feeding Jackson is a determinism hazard.** |
| `scripts/appgen/Build-NpdevApp.ps1` (~38-57, 299-310) | The `-AcknowledgeDestructive`/`-PlanOnly`/`-Upgrade` param + generator-flag threading. **REG-7.2's `-MarkMigrationDone <fingerprint>` is a new param threaded the identical way** (or, simpler and preferred — see §4 decision D2 — a ControlPanel-only operation that needs no generator flag at all). |
| `GeneratorMain` (grep it) + `GeneratorMainDestructiveAcknowledgmentCliTest.java` | The CLI-flag-under-test pattern if the generator-flag route is chosen for `-MarkMigrationDone`. |

### 2.4 The contracts (schema + docs)

| File | Change |
|---|---|
| `schemas/ai/user-db-definition.schema.json` (~47-70) | The `schemaLifecycle` JSON Schema. Currently `required: [strategy, allowDestructiveRecreate, destructiveRecreateConfirmation, scope]`, `additionalProperties: false`. **REG-7.1 adds `ownership` as an optional enum** (`["NpdevManaged", "ExternallyManaged"]`), defaulting to `NpdevManaged` when absent so every existing definition still validates. |
| `docs/SCHEMA_EVOLUTION.md` | Rewrite the "Current limitations" entries for REG-7/REG-8 into feature documentation. Add an "external database / unmanaged schema" section and a "marking a migration as done" section with a verbatim CLI/ControlPanel example (the doc's existing style). |
| `docs/DEPLOYMENT.md` | The multi-instance warning becomes "collision is now detected and refused" + how to clear a stale claim. The external-DB mode gets a deployment note. |
| `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.7/§1.8 + `docs/OPEN_GAPS_AND_ROADMAP.md` (`LNCH-1-B6`/`LNCH-1-B9`) | Reclassify from BOUNDARY to a delivered feature (REG-7) / a delivered refusal + documented limit marked done (REG-8). |

---

## 3. Guardrails — binding across every phase

1. **This subsystem had 5 adversarial rounds. Reproduce RED first for every behavioral change** —
   write the test that fails against today's code, watch it fail, then fix. A green test that was
   never red proves nothing (retrospective §6).
2. **Both proof matrices must stay green with unchanged expectations for existing scenarios.**
   `SchemaLifecycleExecutorProofMatrixTest` (41 H2 scenarios) and
   `SchemaLifecycleExecutorPostgresProofMatrixTest` (25 Postgres). New behavior = new scenarios;
   never edit an existing scenario's expectation to accommodate a change (that means the change
   altered existing semantics — rework it).
3. **Live > suite for anything touching real migration.** Every new refusal/mark-done path gets a
   real boot rehearsal (H2 minimum; Postgres for REG-7.3 collision, which is inherently concurrent) —
   output recorded under `NPDev_General__OutsideRepo/reg7-reg8-evidence/`, not the repo.
4. **Default every new field/mode to today's behavior.** `ownership` absent → `NpdevManaged`.
   No manifest, definition, or test that predates this plan may change behavior. This is the single
   most important compatibility rule — the manifest parse (~2809-2814) documents the exact pattern.
5. **New tables self-bootstrap** exactly like `PendingSchemaAcknowledgmentStore` — never routed
   through the generator's `internalTables` catalog. Never-throw-on-missing-table on every read path.
6. **`Map.of` + Jackson = determinism hazard.** Any manifest-emitter change must respect
   `NoMultiEntryMapOfInGeneratedManifestEmittersTest` (GATE-DET-1) — single-entry `Map.of` or an
   ordered map, never multi-entry `Map.of` feeding a serialized manifest.
7. **`model.schema.json` is duplicated in 4 places; `user-db-definition.schema.json` may have
   mirrors too — VERIFY and mirror any schema edit to all copies** (CLAUDE.md's four-copy rule).
8. **Small bounded commits, one per phase/sub-slice, `Verified:` line naming what ran.** No
   `git add -A`. A pre-existing bug found on the way gets its own commit + test first.
9. **`ColumnFacts` note (REG-6):** REG-6 says "unify column semantics before adding a new *pass*."
   REG-8's Trigger C is column-shaped and SHOULD read through the existing `platformManagedColumnNames()`
   / `ColumnFacts` helpers where the detector already does (~2261), **not** re-derive its own column
   sets. REG-7's ownership gate and collision claim are orchestration-level, not column-level — they
   don't touch `ColumnFacts` and don't conflict with REG-6.

---

## 4. Design decisions — pre-made, do NOT re-derive

**D1 — Ownership is a new `schemaLifecycle.ownership` field, orthogonal to `strategy`.**
`strategy` answers *how NPDev migrates when it owns the schema*. `ownership` answers *whether NPDev
touches schema DDL at all*. They compose: `ExternallyManaged` makes `strategy`/`allowDestructiveRecreate`
**inert** (NPDev issues no DDL, so there is nothing for them to govern). Do not overload `strategy`
with a fourth "external" value — that conflates two independent axes and would break the existing
`destructiveConfirmedFor` logic. Enum values: `NpdevManaged` (default, current behavior),
`ExternallyManaged`.

**D2 — "Mark migration as done" is a ControlPanel + CLI-parity operation, not a generation-time
manifest field.** Unlike `destructiveAcknowledgment` (which is intrinsic to a *specific generated
build* and rightly bakes into its manifest), "mark done" is an *operational act against a running
database*, independent of any particular regenerate. So the primary surface is a ControlPanel
endpoint (sibling to `SchemaAcknowledgmentController`) writing a new pending/marker store, consulted
by `beforeMigrate` on the next boot — **exactly** the pre-authorization pattern that already exists,
because it has the identical "the refusing boot has no server" constraint. A `Build-NpdevApp.ps1
-MarkMigrationDone <fingerprint>` CLI flag is offered for parity/scriptability, threaded like
`-AcknowledgeDestructive`. **Preferred minimal v1: ControlPanel endpoint only**, add the CLI flag in a
follow-up slice if wanted — this avoids a generator round-trip for a pure runtime operation.

**D3 — Collision detection is a claim row, not an advisory lock.** The register's original "how to fix"
proposed `pg_advisory_lock` (Postgres) + a lock table (H2). The owner explicitly does **not** want a
lock in v1 — they want a loud error and manual resolution. So: a single-row **claim** in a
self-bootstrapped `npdev_schema_migration_claim` table, `INSERT`ed (guarded by a PK/unique constraint
on a fixed claim key) at the very top of `migrate()` before any schema work, carrying instance
identity + timestamp, `DELETE`d in a `finally`. If the INSERT fails because a row exists → refuse with
a message naming the existing claimant and when it started. This is **portable** (ordinary SQL, works
identically on H2 and Postgres — no engine-specific lock primitive), and it fails loud exactly as
asked. A stale claim (crashed holder) is cleared via a new `-ClearMigrationClaim` / ControlPanel
button — the manual escape hatch. **This is deliberately weaker than a lock** (a true TOCTOU race
between two near-simultaneous inserts is possible on engines without strict insert serialization);
that is acceptable per the owner's "if it becomes frequent, add guard rails later." Document the
limitation honestly.

**D4 — REG-8 is closed by a refusal, not by full detection.** The pure-column-drop blind spot is
genuinely undetectable from live schema shape alone (that is why it was a boundary). The fix is the
register's own "how to fix (if ever)" note: **consult `npdev_schema_history` at boot** — if a row
exists whose `to_fingerprint` is *newer* (later `applied_at_utc`, or provably subsequent) than this
build's fingerprint, the DB has been migrated past this build regardless of live shape. That is a
clean, already-available signal. REG-8 is then "closed for v1" as *a clear refusal exists*, not as
*every drop is reconstructed*. This matches the owner's "error + documented limitation, mark done."

**D5 — `ExternallyManaged` boot is verify-only, and a compatibility failure REFUSES.** In
`ExternallyManaged` mode the executor: skips all DDL passes (no rename/relax/backfill/classify/
destructive/`flyway.migrate()` of schema-realization scripts), runs a **read-only** compatibility
check (every `manifest.businessTableColumns()` entry exists live with a compatible type — reuse
`readActualColumns` and the existing type-compatibility logic), and either proceeds to serve data or
throws a clear `IllegalStateException` naming exactly what is missing/incompatible. It must still let
Flyway manage its *own* internal bookkeeping tables if needed, but must not apply NPDev's
schema-realization migrations. **VERIFY** how `flyway.migrate()` interacts with an externally-owned
schema — the safest shape is to not register the schema-realization scripts at all in this mode, or
to treat them as already-applied. This is the phase with the most unknowns; §5 P1 front-loads it.

---

## 5. Phases

### Phase P0 — Questions for the owner (answer before P1; most have a safe default)

| # | Question | Default if unanswered |
|---|---|---|
| Q1 | For `ExternallyManaged`, should NPDev **verify** compatibility at boot (refuse if the live schema can't serve the model) or **trust blindly** (serve, fail at query time)? | **Verify + refuse** — matches the "fail loud" posture; blind-trust contradicts it. |
| Q2 | "Mark migration as done": ControlPanel-only for v1, or CLI flag too? | **ControlPanel-only v1** (D2), CLI flag as a follow-up slice. |
| Q3 | Collision claim identity: what identifies an "instance" (hostname? a generated instance UUID per boot? a configured node name)? | **A per-boot UUID + hostname + start timestamp**, all three in the claim row for diagnosis. |
| Q4 | Should `ExternallyManaged` also disable the destructive-acknowledgment machinery entirely (since NPDev issues no DDL), or leave it inert-but-present? | **Inert but present** — one less special case; the manifest fields just never fire. |

### Phase P1 — `ExternallyManaged` ownership mode (REG-7.1) — do this first, most unknowns

1. **Generator side:** add `ownership` to `SchemaLifecyclePolicy` (+ a `DatabaseOwnership` enum
   mirroring `SchemaLifecycleStrategy`), parse it in `UserDatabaseDefinitionLoader` (default
   `NpdevManaged`), emit it into the manifest in `SchemaRealizationEmitter` (heed guardrail #6),
   and add the `ownership` enum to `user-db-definition.schema.json` (+ mirror per guardrail #7).
   `validate()` rejects nonsensical combinations (e.g. `ExternallyManaged` + `RecreateOnAppStart`
   should warn/refuse — you cannot recreate a DB you don't own).
2. **Runtime side:** add `ownership` to the `SchemaManifest` record (+ update the back-compat
   constructor), parse it in `loadManifest` (default `NpdevManaged`).
3. **The gate:** at the top of `migrate(Flyway, SchemaManifest)`, right after the `physicalDatabase()`
   guard (~221), branch on `ExternallyManaged` → call a new `verifyExternallySchemaCompatible()` and
   return without any DDL / without the schema-realization `flyway.migrate()` (per D5 — **VERIFY the
   Flyway interaction**).
4. **The verify method:** reuse `readActualColumns` + existing type-comparison logic to confirm every
   modelled table/column exists and is type-compatible live; throw a clear, itemized
   `IllegalStateException` (same message discipline as `findSchemaAheadMissingColumns`'s caller) if
   not; write a history row (`outcome = 'EXTERNAL_VERIFIED'` or `'EXTERNAL_REFUSED'`).
5. **Tests:** new proof-matrix scenarios — `ExternallyManaged` + compatible live schema → boots, no
   DDL issued (assert no schema-realization migration ran); `ExternallyManaged` + missing column →
   refuses with the itemized message. RED-first. Live H2 rehearsal recorded.
6. **DoD:** an app declared `ExternallyManaged` boots against a hand-created compatible DB with zero
   NPDev DDL, and refuses clearly against an incompatible one. Existing 41+25 matrix green unchanged.

### Phase P2 — "Mark migration as done" (REG-7.2)

1. **Store:** new `MigrationMarkStore` (self-bootstrapping, `PendingSchemaAcknowledgmentStore` shape)
   OR reuse the metadata table with a new marker — **decision:** a dedicated store, because you want
   an audit trail (who/when), which the k-v metadata table doesn't carry. Row: `{id, markedFingerprint,
   markedAtUtc, markedBy, note}`.
2. **ControlPanel endpoint:** `@PostMapping("/api/admin/schema-migration/mark-done")` in a controller
   sibling to `SchemaAcknowledgmentController` (or a method on it), SUPERUSER-gated the same manual
   way. Body: `{fingerprint, note}`.
3. **Boot consumption:** in `beforeMigrate`, before the mismatch-path work, check the mark store: if a
   mark exists for `manifest.schemaFingerprint()`, **fast-forward** — `upsertMetadata(FINGERPRINT_KEY,
   manifest.schemaFingerprint())`, write a history row (`outcome = 'MANUALLY_MARKED_DONE'`), consume
   the mark, and return `DestructiveRecreation.none()` **without running any migration passes.** This
   is the GeneXus "the DB is already at this version, stop trying to migrate to it" semantic.
4. **CLI parity (optional, per Q2):** `-MarkMigrationDone <fingerprint>` in `Build-NpdevApp.ps1`
   threaded like `-AcknowledgeDestructive`; simplest is to have it POST to the running app's endpoint
   rather than bake into the manifest (keeps D2's "operational, not build-intrinsic" separation).
5. **Tests:** RED-first — a fingerprint mismatch that *would* migrate, with a mark present, no-ops and
   records `MANUALLY_MARKED_DONE`. Live rehearsal recorded.
6. **DoD:** an operator can mark a fingerprint done on a running app; the next boot at that fingerprint
   applies zero DDL and records the mark in history.

### Phase P3 — Collision detection (REG-7.3)

1. **Table:** self-bootstrapping `npdev_schema_migration_claim` — single logical claim keyed on a
   fixed constant (e.g. `claim_key TEXT PRIMARY KEY DEFAULT 'schema-migration'`), plus
   `{instance_id, hostname, claimed_at_utc}` (per Q3). The PK on the fixed key is what makes a second
   concurrent `INSERT` fail.
2. **Claim/release:** at the very top of `migrate(Flyway, SchemaManifest)` (after the `physicalDatabase`
   guard, before the ownership branch), attempt the claim INSERT. Success → proceed, and `DELETE` the
   claim in a `finally` around the whole migration body. Failure (row exists) → throw a clear
   `IllegalStateException` naming the existing claimant's `instance_id`/`hostname`/`claimed_at_utc`
   and pointing at the clear-claim escape hatch. **Skip the claim entirely in `ExternallyManaged`
   mode** (NPDev issues no DDL, so there's nothing to serialize).
3. **Stale-claim escape hatch:** `-ClearMigrationClaim` CLI flag + a ControlPanel button that deletes
   the claim row (SUPERUSER-gated), for the crashed-holder case. Document that clearing a claim while
   another instance genuinely holds it re-introduces the race — that's the operator's call.
4. **Honest limitation:** document (code comment + `SCHEMA_EVOLUTION.md`) that this is
   detect-and-refuse, not a lock — a true simultaneous-insert race is possible and acceptable per the
   owner's "add guard rails if frequent." If it *does* become frequent, the upgrade path is D3's
   originally-scoped `pg_advisory_lock` + H2 lock table.
5. **Tests:** RED-first is harder here (concurrency). At minimum: a pre-existing claim row → the next
   `migrate()` refuses with the naming message; a normal run leaves no claim behind (finally-delete
   verified). A real two-connection Postgres rehearsal for the concurrent case, recorded — **do not
   claim the race is closed**, only that a *held* claim is detected.
6. **DoD:** a second migration attempt while a claim is held refuses with a diagnostic naming the
   holder; a clean run claims and releases; the escape hatch clears a stale claim.

### Phase P4 — REG-8: refuse when the DB is ahead, including pure drops

1. **New signal:** a method (sibling to `findSchemaAheadMissingColumns`, or a new branch in
   `beforeMigrate`) that reads `npdev_schema_history` for the latest applied row and compares its
   `to_fingerprint` / `applied_at_utc` against this build. **Trigger C:** if history shows a
   successfully-applied migration whose `to_fingerprint` differs from this build's fingerprint AND is
   provably later than what this build last wrote, refuse — the DB has moved past this build even
   though live shape shows no residue. Reuse the exact throw + `REFUSED` history-row pattern branch (b)
   already uses (~281-288).
2. **Interaction with P2:** a `MANUALLY_MARKED_DONE` fingerprint must **not** trip Trigger C — a mark
   is the operator saying "this build is legitimately at this fingerprint." Order the checks so a
   mark short-circuits first.
3. **Message:** "This database was migrated past this build (history shows fingerprint X applied at
   T, newer than this build's Y). Roll forward to the newer build, restore from backup, or — if you
   deliberately intend this older build to take over — mark fingerprint Y done (see …)." I.e. REG-8's
   escape hatch is **P2's mark-done mechanism**, which is why P4 comes after P2.
4. **Tests:** RED-first — the register's own practical example (build N+1 drops `users.nickname`,
   rollback to build N) currently boots silently re-adding an empty column; after P4 it refuses.
   Add it as a proof-matrix scenario. Live rehearsal recorded.
5. **DoD:** the pure-column-drop rollback scenario refuses with a clear message instead of silently
   re-adding an empty column; a `MANUALLY_MARKED_DONE` fingerprint is exempt.

### Phase P5 — Docs + register reclassification

1. `docs/SCHEMA_EVOLUTION.md`: new "External / unmanaged database" section, new "Marking a migration
   as done" section (verbatim ControlPanel + CLI example), and rewrite the REG-7/REG-8 "Current
   limitations" entries into "collision is detected and refused" + "DB-ahead is refused (including
   pure drops)" with the honest limitation notes from D3/D4.
2. `docs/DEPLOYMENT.md`: multi-instance section → "collisions are detected; here's how to clear a
   stale claim"; add the external-DB deployment note.
3. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.7/§1.8 + `docs/OPEN_GAPS_AND_ROADMAP.md`
   `LNCH-1-B6`/`LNCH-1-B9`: reclassify REG-7 from BOUNDARY to **feature delivered** (with the
   race-not-a-lock limitation named), REG-8 to **refusal delivered, marked done for v1** (with the
   detection-vs-reconstruction limitation named).
4. **DoD:** no doc still describes REG-7/REG-8 as an unaddressed limitation; each names its residual
   honestly.

---

## 6. Minimum bar (if effort runs short)

The non-negotiable core, in order: **P4 (REG-8 refusal) → P1 (external-DB mode) → P3 (collision
detect).** Rationale:
- **P4 is the cheapest and highest-safety** — it converts a *silent data-integrity trap* (empty
  column re-added, no warning) into a loud refusal, reusing machinery that entirely exists. If only
  one thing ships, ship this.
- **P1 is the feature with the most standalone user value** (legacy/external DB support) and is
  self-contained.
- **P3 makes the two-instance footgun loud** instead of silently corrupting.
- **P2 (mark-done) is the connective tissue** — it's the escape hatch P4 points at and the operator
  affordance for P1/P3 — but if truncated, P4's message can temporarily point at "restore from
  backup / roll forward" instead of "mark done," and P2 lands next.

Each phase is independently shippable and independently valuable. **None may weaken an existing
proof-matrix guarantee** — that is the one hard invariant across all of them.

---

*Companion documents: `docs/NPDEV_OPEN_ITEMS_REGISTER.md` §1.7/§1.8 (the boundaries this converts) ·
`docs/SCHEMA_EVOLUTION.md` (the contract this extends) · `docs/LNCH1_PROGRAMME_RETROSPECTIVE.md`
(why this subsystem needs the guardrails in §3) · `docs/REGISTER_CLOSURE_PLAN.md` (the sibling plan
for the other open items).*
