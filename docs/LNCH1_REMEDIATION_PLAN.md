# LNCH-1 Remediation Plan — Fixing the Post-Implementation Review Findings

> **Status:** APPROVED PLAN — not started
> **Written:** 2026-07-19, verified against the working tree at commit `1948129`
> **Origin:** an independent review of the completed LNCH-1 implementation
> (`docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md`, all 9 phases DONE) found 2 high-severity bugs,
> 1 systemic fragility, and 7 incoherences/gaps. This plan fixes **all 10 findings**.
> **Audience:** an AI implementation session (or human) that has NOT read the project's history.
> Follow it literally. Where it says VERIFY, verify against the real code before writing any —
> the review was performed at commit `1948129`; line numbers cited below are from that commit and
> may have drifted.
>
> **Read first, in this order:** (1) this document end to end; (2) `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md`
> §1 (map of the machinery), §2 (design decisions — all still binding), §3 (guardrails — ALL still
> binding, especially the four-schema-copies rule, the canonical-JSON ratchet, the jar-restage rule,
> and "no `git add .`"); (3) `docs/SCHEMA_EVOLUTION.md` (the user-facing contract you are repairing).

---

## 0. The findings, and where this plan fixes each

| # | Severity | Finding (one line) | Fixed in phase |
|---|---|---|---|
| F1 | HIGH | Required-field backfill AND its refusal are silently skipped on the surgical-destruction and whole-wipe paths → a required field added in the same upgrade as any acknowledged destructive item lands permanently nullable with NULL legacy rows | R2 |
| F2 | HIGH | `DropTable.stableString()` embeds the live row count; the generator hashes `-1` → the plan's acknowledgment token can NEVER match at boot for a concept drop; ControlPanel pre-authorization cannot work at all for that case | R1 |
| F3 | MEDIUM | `DropColumn`/`NarrowType` stable strings use live-normalized types on the executor side but raw model-declared types on the generator side → token mismatch for any type whose spellings diverge; no conformance test forces agreement | R1 |
| F4 | MEDIUM | Renames/relaxations execute before the acknowledgment check → a refused boot has already mutated the schema; redeploying the OLD jar then boots "clean" (fingerprint matches) against a renamed schema and breaks at runtime with no diagnostics | R3 |
| F5 | LOW/MED | Audit-trail deviations from plan §2.4: no write-before-execute history rows for rename/widen/relax/backfill passes; safe-path applied rows carry no `items_json`; unique-refusal row carries no detail | R4 |
| F6 | LOW | Documentation contradictions: `SchemaDeltaItem` javadoc ("byte-identical BY CONSTRUCTION") vs `MigrationPlanEmitter` javadoc (admits mismatch) vs `docs/SCHEMA_EVOLUTION.md` (silent); R__ migration's emitted comment and the docs' "refused, not silently guessed" claim are false per F1 | R1, R2, R5 |
| F7 | MEDIUM | `renamedFrom` marker lifecycle undocumented; a stale marker on a later second rename silently degrades a safe rename into an itemized destructive drop of real data, with no warning that it looks like a mis-declared rename | R6 |
| F8 | LOW | No concurrency guard on the executor (two instances booting concurrently could interleave DDL); accepted single-instance posture, but unrecorded | R7 |
| F9 | LOW | Uncommitted working-tree change: the Linux `gradlew` execute-bit fix (`Files.setPosixFilePermissions`) in `HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java` — load-bearing for LNCH-19, at risk of being lost | R0 |
| F10 | INFO | `run-stateful-additive-migrations-check.ps1`'s two surfaced-but-uninvestigated issues: a Flyway proof test whose `--tests` filter matches zero tests, and a Gradle invocation against a non-buildable template | R8 |

Phase order is dependency-driven: R0 (land what exists) → R1 (token format — everything else's
tests will compute tokens, so fix the format first) → R2 (the data-integrity bug) → R3–R7
(hardening + docs) → R8 (gate cleanup) → R9 (full regression + live rehearsal + ledger).

---

## 1. Files you will touch (orientation map)

| File | Role in this plan |
|---|---|
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/schemaevolution/SchemaDeltaItem.java` | R1: change `DropTable.stableString()`; javadoc corrections |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/schemaevolution/DestructiveAckToken.java` | R1: javadoc note on the format change (the algorithm itself does not change) |
| `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/schemaevolution/` (new file) | R1: `SqlTypeNormalization.java` — the single shared type normalizer |
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` | R2 (backfill call-site move), R3 (schema-ahead detector), R4 (history discipline). ~130 KB — Grep to methods, Read with offset/limit, never full-read |
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaDeltaReport.java` | R1: route type strings through the shared normalizer |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/schemaevolution/MigrationPlanEmitter.java` | R1 (normalizer + `-1` removal), R6 (stale-marker warning) |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java` | R2/R5: correct the emitted SQL comment whose premise F1 falsified |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorProofMatrixTest.java` (VERIFY exact name via Glob) | R1/R2/R3: new scenarios 17–22 |
| The Postgres Testcontainers twin suite (VERIFY name; lives in the `integrationTest` sourceSet added by LNCH-1 P7 commit `3903e40`) | Same scenarios, Postgres side |
| `docs/SCHEMA_EVOLUTION.md` | R1/R2/R3/R5/R6/R7: contract corrections + new sections |
| `docs/OPEN_GAPS_AND_ROADMAP.md`, `CHANGELOG.md`, `knowledge/cards/` | R9: record keeping |
| `scripts/quality/run-stateful-additive-migrations-check.ps1` | R8 |
| `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java` | R0: commit the pending diff |

**Guardrail reminders that WILL bite you if skipped** (full list: plan §3):
- After ANY RuntimeHost/kernel Java change, restage jars before regenerating an app:
  `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs` (or use the `rebuild-app` skill).
- `:generator:test` does NOT run `:dsl` tests — run both suites explicitly after touching the DSL module.
- Never write into a generated app's `npdev-generated/` tree by hand (hash-guarded).
- Evidence notes go to `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\remediation-<phase>.md`.
- Small bounded commits, `LNCH-1 R<n>: <what>` + a `Verified:` line; a pre-existing bug found on the way gets its own commit first.
- New/changed gate scripts invoke `Build-NpdevApp.ps1` as `pwsh -NoProfile -File` child processes (strict-mode leak).

---

## 2. Phase R0 — Land the pending working-tree fix (F9) and freeze a green baseline (S)

**Goal:** nothing this plan builds sits on top of uncommitted drift.

R0.1 Inspect the working tree: `git status --short` + `git diff`. Expected: exactly one modified
     file, `HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java` (the
     `gradlewPath` change: existence check + `Files.setPosixFilePermissions(gradlew, "rwxr-xr-x")`
     replacing `File.setExecutable`, per its own inline comment "confirmed live NOT to unblock
     this"). If MORE files are modified, stop and report to the owner before proceeding.
R0.2 Confirm the file compiles and its imports are complete (`java.nio.file.Files` must already be
     imported or the new code fully qualifies it — read the imports block). Run the generator test
     suite compile at minimum: `.\gradlew.bat -p NPDevGenerator :generator:testClasses --no-daemon`.
     Do NOT try to run this specific proof test on Windows to "verify" the POSIX branch — it is
     Linux-only by construction; the CI run is its verification vehicle (LNCH-19).
R0.3 Commit it alone: `LNCH-1 R0: land the Linux gradlew POSIX-permission fix (LNCH-19 residual risk)`.
     Mention in the body that the `File.setExecutable` inadequacy was observed live and that the
     LNCH-19 verification PR is the test bed.
R0.4 Baseline: run the DSL, kernel-relevant, and generator suites plus
     `pwsh -NoProfile -File scripts\quality\run-runtimehost-gate.ps1`. All green before R1 starts.
     Record durations in the evidence note (you will re-run these repeatedly).

**DoD:** clean `git status`; baseline suites green; evidence note written.

---

## 3. Phase R1 — One token, two producers, provably identical (F2 + F3 + part of F6) (M)

**Goal:** the token printed by `-PlanOnly` is byte-identical to the token the executor expects at
boot, for EVERY destructive item kind and EVERY type in the platform's catalog, on both engines —
enforced by a permanent conformance test, not by luck.

### R1.1 — Remove the row count from `DropTable`'s hash input (F2)

In `SchemaDeltaItem.DropTable`:
- Change `stableString()` to return `"DROP_TABLE:" + table` — nothing else. KEEP the
  `rowCountAtClassification` record component: it stays valuable as **display metadata** (the
  operator seeing "~1,240 rows will be lost" in the plan, the report, `items_json`, and log lines)
  — it just must never participate in the hash.
- Update the class javadoc: delete the "byte-identical BY CONSTRUCTION" overclaim and replace it
  with the accurate statement: identical **because both producers call this exact method and the
  method uses no live-only inputs** — which after this change is true.
- In `MigrationPlanEmitter`, delete the "Known, documented fidelity limitation: DROP_TABLE row
  counts" javadoc section entirely (the limitation no longer exists) and keep constructing
  `DropTable(table, -1L)` — the `-1` now only affects display ("row count unknown at plan time"),
  which is honest. Make the plan's human-readable rendering print "row count unknown until boot"
  when the value is `-1`.

**Where existing tests will break (expected, fix them to the new truth):** Grep the repo for
`DROP_TABLE:` — every test asserting a stable string or a token computed over one (Phase 4/6/7
tests on both the RuntimeHost and generator sides) must be updated. Do NOT weaken assertions to
"contains" — assert the exact new format.

### R1.2 — One shared SQL-type normalizer (F3)

Today: the executor normalizes live JDBC metadata types via its own package-private
`SchemaLifecycleExecutor.normalizeSqlType` (+ `qualifyTypeWithSize`; the Postgres aliases INT4/
INT8/BOOL/etc. were added by commit `74de76c`), and `SchemaDeltaReport` builds `DropColumn`
stable strings from that (`normalizedOrRaw(actualTypes.get(...))`, ~line 216). The generator
builds them from raw model-declared strings (`oldTypes.get(column)`, ~line 262). Two spellings of
the same type → two tokens → refused boot with a confusing "expected token" that the plan never
showed.

Do this:

1. **Create** `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/schemaevolution/SqlTypeNormalization.java`
   — a final class with one public static method `normalize(String sqlType)`. Its body is the
   MOVED logic of the executor's current `normalizeSqlType` (VERIFY the current body at
   `SchemaLifecycleExecutor.java` ~line 1552 first — move it faithfully, including the JDBC alias
   table, uppercase/whitespace canonicalization, and size/precision handling conventions). Add
   normalizations for every **model-side** spelling the generator can produce: enumerate the type
   catalog from `SqlTypeSupport` (Grep for it under `NPDevGenerator`) and the per-engine
   `renderType` outputs, and make sure each maps to the same canonical form as its live-metadata
   reflection. Document each alias with the engine + source that motivated it.
2. **Executor side:** `SchemaLifecycleExecutor.normalizeSqlType` becomes a one-line delegate to
   `SqlTypeNormalization.normalize` (keep the package-private method so its existing direct unit
   tests keep passing unchanged; they now transitively test the shared class).
3. **Generator side:** `MigrationPlanEmitter` routes EVERY type string it puts into a
   `DropColumn`/`NarrowType` (and its `TypeChangeMatrix.classify` inputs — VERIFY whether
   `TypeChangeMatrix` normalizes internally already; if it does, do not double-normalize)
   through `SqlTypeNormalization.normalize`.
4. **Classpath check:** the DSL module is already on both sides' classpaths (this is why the
   `schemaevolution` package lives there — see `DestructiveAckToken`'s javadoc). No build wiring
   should be needed; VERIFY by compiling both modules.

### R1.3 — The cross-producer conformance test (the permanent ratchet)

New integration test in RuntimeHost (H2) + its Postgres Testcontainers twin, named
`TokenAgreementConformanceTest` (H2) / mirrored in the twin suite:

- For **every** type in the platform's catalog (drive the list from `SqlTypeSupport` itself so a
  future new type automatically joins the test — do not hand-copy the list): create a real table
  with a column of that type (via the same `renderType(engine)` path the emitter uses), read it
  back via `SchemaLifecycleExecutor.readActualColumnTypes`, and assert
  `SqlTypeNormalization.normalize(liveType).equals(SqlTypeNormalization.normalize(modelDeclaredType))`.
- Then the end-to-end half: build a manifest declaring a drop of each such column, generate the
  executor-side `SchemaDeltaReport`, compute its token; build the same old/new model pair on the
  generator side, run `MigrationPlanEmitter`, compute the plan's token; assert **byte equality**.
  Include one `DROP_TABLE` case (proves R1.1) and one `NARROW_TYPE` case per distinct
  `TypeChangeMatrix` comparison branch (numeric, varchar-length, family-mismatch).
- A failure message must print both normalized spellings side by side — this test exists to catch
  the NEXT drifted alias with a five-second diagnosis, not just to pass today.

### R1.4 — Compatibility + docs fallout

- **Token-format break:** any acknowledgment token computed before this change (a stored
  `destructiveAcknowledgment` in a not-yet-booted generated manifest, or an unconsumed row in
  `npdev_pending_schema_acknowledgment`) will no longer match. This is acceptable — tokens are
  per-upgrade ephemera and the failure mode is a refusal (safe direction) whose message prints the
  new expected token. Record it in `CHANGELOG.md` under `[Unreleased]` → "Changed", with one
  sentence telling operators to re-run `-PlanOnly`.
- `docs/SCHEMA_EVOLUTION.md` "Acknowledging destructive changes": add one sentence stating the
  token is computed identically at plan time and boot time for all item kinds (now true), and that
  a `DROP_TABLE` plan item shows "row count unknown until boot" (display-only).

**Tests to run:** DSL suite, generator suite, RuntimeHost gate, full H2 proof matrix, Postgres
twin (Docker required — if Docker is unavailable in your session, say so explicitly in the
evidence note and mark the Postgres leg as pending CI; do NOT claim it ran).

**DoD:** conformance test green on H2 (and Postgres, or explicitly pending); a scripted
`-PlanOnly` → `-Upgrade -AcknowledgeDestructive <copied token>` round-trip for a **concept drop**
boots successfully on the FIRST attempt (this exact flow was impossible before R1). Add that
round-trip as proof-matrix scenario 19.

**Commits:** R1.1+R1.4 docs (`LNCH-1 R1: make DROP_TABLE ack tokens plan-computable (row count out of the hash)`),
R1.2+R1.3 (`LNCH-1 R1: shared SqlTypeNormalization + cross-producer token conformance test`).

---

## 4. Phase R2 — Backfills and refusals on EVERY path (F1 + part of F6) (M)

**Goal:** a new required field behaves identically whether or not the same upgrade also contains
an acknowledged destructive item: literal-default → backfilled + NOT NULL; no default → refused.

### R2.1 — The decided design (do not re-derive)

Move the enforcement to a single call site that every path crosses: **`afterMigrate`, immediately
BEFORE `applyUniqueConstraints`,** gated on "this boot had a fingerprint mismatch".

Why this placement (recorded so the implementer does not "improve" it):
- `afterMigrate` runs after `flyway.migrate()`, i.e. after V1/R__ have run — but
  `addBackfillAndTightenColumn` starts with `ADD COLUMN IF NOT EXISTS`, so it is indifferent to
  whether R__ already added the column (it then just backfills + tightens) or not. Idempotent
  either way; crash-recovery convergence (the existing "present but not yet NOT NULL" re-entry
  check at ~line 930) is unchanged.
- It must run BEFORE `applyUniqueConstraints` (a new unique may include the new required column)
  and BEFORE the fingerprint write (a refusal must leave the fingerprint stale so the next boot
  re-attempts — same rule `applyUniqueConstraints` already documents).
- It must NOT run on fingerprint-match boots: a legacy app that converged with an (old-bug)
  nullable-but-required column would otherwise suddenly refuse on a routine restart with no model
  change — a breaking surprise. Healing legacy drift is out of scope; only mismatch boots enforce.

### R2.2 — Mechanical steps

1. In `migrate(Flyway, SchemaManifest)`: capture `String stored = readFingerprint(dataSource)`
   BEFORE `beforeMigrate` runs (VERIFY: `beforeMigrate` reads it itself at ~line 99 — lift the
   read up and pass it in, so `migrate` knows it too; keep `beforeMigrate`'s behavior identical).
   Compute `boolean fingerprintChanged = stored != null && !stored.isBlank() && !stored.equals(manifest.schemaFingerprint())`.
2. Change `afterMigrate`'s signature to `afterMigrate(DataSource, SchemaManifest, String storedAtBootStart, boolean fingerprintChanged)`
   (it is package-private with direct tests — update them). Inside, when `fingerprintChanged`,
   call `applyRequiredFieldBackfills(dataSource, manifest, storedAtBootStart, null)` FIRST, then
   `applyUniqueConstraints`, then the fingerprint write. (Passing `null` classification is the
   existing convention for refusal rows written outside a classification context — see the
   unique-refusal row at ~line 1275.)
3. DELETE the five existing `applyRequiredFieldBackfills` call sites inside `beforeMigrate`
   (~lines 144, 160, 178, 213, 225). Do not leave any of them "just in case" — double execution is
   harmless (idempotent) but makes the control flow lie about where enforcement lives. The
   surrounding `writeAppliedHistoryRow` calls stay where they are (R4 will restructure history
   anyway).
4. `refuseIfRequiredBondColumnMissing` (~line 1163) — VERIFY whether it is also skipped on any
   path. It is called at ~line 241, BEFORE the destructive section, so it already covers the
   combined case; confirm with a test rather than assuming (scenario 18's bond variant).
5. Fix the two now-false pieces of prose:
   - `SchemaRealizationEmitter`'s emitted R__ comment (~lines 182–190 and the per-column NOTE
     lines ~219–230): reword to "NPDev's schema-lifecycle executor backfills … and enforces
     NOT NULL after this migration runs, on the same boot" — no longer "before … never reached".
   - `MigrationPlanEmitter` ~line 254's plan-item text: "boot refuses until a literal default is
     declared" is now true again in all cases — VERIFY the wording still matches the actual
     refusal message and keep them aligned.
   - `docs/SCHEMA_EVOLUTION.md` "New required fields": the step description "On boot, this runs
     ADD COLUMN IF NOT EXISTS (nullable) → UPDATE → SET NOT NULL" stays accurate; add one sentence:
     "This enforcement runs on every upgrade boot regardless of what else the upgrade contains —
     including upgrades that also carry acknowledged destructive items."

### R2.3 — Proof-matrix scenarios (add to BOTH H2 and Postgres suites)

- **Scenario 17:** seed rows → upgrade = acknowledged `DROP_COLUMN` on table B **+** new required
  field with literal default on table A → boot with valid token → assert: drop applied, A's new
  column exists, every legacy row backfilled to the default, column is NOT NULL, second boot
  no-ops. (This is F1's exact reproduction; it MUST fail before your fix and pass after — run it
  once against the pre-fix code to prove the test can detect the bug, note the red run in the
  evidence file, then fix.)
- **Scenario 18:** same but the required field has NO default → boot refuses with the
  `#new-required-fields` message even though the destructive token is valid; fingerprint stays
  stale; DB state: destructive item MAY already be applied (document this ordering consequence in
  the refusal-behavior part of `docs/SCHEMA_EVOLUTION.md` — the refusal arrives after
  `flyway.migrate` on this path; a subsequent fixed-model boot converges cleanly — assert exactly
  that with a follow-up boot in the test).
- **Scenario 18b:** required **bond** column + acknowledged unrelated drop → the dedicated bond
  refusal still fires (guards step R2.2.4).

**DoD:** scenarios 17/18/18b green on H2 (Postgres or pending-CI as in R1); full matrix + gates
green; prose corrections landed.

**Commits:** `LNCH-1 R2: enforce required-field backfill/refusal on destructive paths (single afterMigrate call site)`
(+ separate commit for the emitter prose if you prefer smaller diffs).

---

## 5. Phase R3 — Refusals are not free: document the mutation, detect the rollback (F4) (M)

**Goal:** an operator who refuses/aborts an upgrade cannot silently break the app by redeploying
the old jar; the docs stop overclaiming side-effect-free refusals.

### R3.1 — The schema-ahead detector

New check, first thing inside the **fingerprint-MATCH** fast path (current ~line 104 early
return): before returning `none()`, verify the live schema actually contains what this build's
manifest requires:

- For each table in `manifest.businessTableColumns()`: read live columns
  (`readActualColumns`); if the table exists but a manifest-declared column is missing from it,
  collect `table.column`.
- If anything is missing → throw with a new, specific message: "Stored schema fingerprint matches
  this build, but the live database is missing column(s) this build requires: [...]. This usually
  means a NEWER build already migrated this database (e.g. an upgrade was attempted and then
  rolled back to this older jar). Roll forward to the newer build, or restore from
  backup/snapshot — see docs/SCHEMA_EVOLUTION.md#refusals-and-rollback." Write a `REFUSED`
  history row (reuse `writeHistoryRow` with classification `null`).
- Missing TABLE entirely: treat identically (a renamed-away table is the classic cause).
- Cost note for the implementer: this adds one `getColumns` metadata read per business table per
  boot on the happy path. That is acceptable (the additive R__ migration already runs per boot);
  do NOT add caching complexity.
- False-positive audit (do this BEFORE coding): run the full existing H2 proof matrix with the
  detector in place — every scenario must still pass. Special attention: scenario 15 (no-change
  reboot) and the InMemory scenario 16 (guard is inside the physical-database path only). H2
  case-folding: compare using the same case-normalization `readActualColumns` already applies —
  VERIFY how existing code compares column names (Grep `toLowerCase` in the executor) and imitate
  exactly.

### R3.2 — Test

- **Scenario 21:** apply an upgrade whose rename succeeded but whose destructive item was refused
  (no token) → then boot with the OLD manifest (old fingerprint, old column names) → assert the
  detector refuses with the schema-ahead message, rather than booting "clean". Also assert the
  NEW manifest with a token then completes normally (the detector must not block the legitimate
  roll-forward).

### R3.3 — Docs

`docs/SCHEMA_EVOLUTION.md`:
- New section `## Refusals and rollback` (anchor `#refusals-and-rollback` — the detector's message
  links to it, and anchors must match Java string literals exactly, per the established
  convention): state plainly that safe steps (table renames, field renames, NOT NULL relaxations)
  apply BEFORE the acknowledgment decision, so a refused upgrade has already applied them; that
  this is by design (they are convergent toward the new model and lose nothing); and that the
  supported recovery directions are **roll forward** (fix the model / supply the token) or
  **restore** — never redeploying the old jar against the migrated database, which the detector
  now refuses explicitly.
- Soften the "the app never starts with a half-applied or silently-guessed destructive change"
  sentence to scope it to destructive changes specifically (which remains true).
- Add the same point in brief to `## Current limitations`.

**DoD:** scenario 21 green; full matrix green (no false positives); docs section + anchor landed.
**Commit:** `LNCH-1 R3: schema-ahead-of-build detector + refusal/rollback contract docs`.

---

## 6. Phase R4 — History rows that say what actually happened (F5) (M)

**Goal:** `npdev_schema_history` records every mutating pass — renames, widenings, relaxations,
backfills — with write-before-execute discipline and item detail, matching plan §2.4's original
requirement.

### R4.1 — A tiny step-recording helper

Private helper in the executor:
`recordStepPass(DataSource ds, String stored, String to, String stepName, List<String> itemDetails, Runnable ddl)`
— semantics: if `itemDetails` is empty, run nothing and write nothing (no noise rows on no-op
boots); otherwise insert a history row `outcome='PARTIAL-CRASH'`, `classification=stepName`
(VERIFY the history table's `classification` column type is TEXT — it is, per `ensureHistoryTable`
~line 1788 — so a step name string is fine), `items_json` = a JSON array of `itemDetails`; run the
DDL; then update that row to `APPLIED` (reuse `markHistoryRowApplied`). Follow the existing
`insertPendingHistoryRow`/`markHistoryRowApplied` pair — VERIFY their exact signatures and imitate
rather than inventing a parallel mechanism. (They currently take a `SchemaDeltaReport`; add
overloads that take a plain items list — do not force fake reports.)

### R4.2 — Wire it into the four passes

Refactor each of `attemptInPlaceTableRenames`, `attemptInPlaceRenames`,
`relaxNoLongerRequiredColumns`, `attemptInPlaceTypeWidenings`, and the apply-half of
`applyRequiredFieldBackfills` so that each FIRST computes its worklist (they all already do —
resolve/scan, then execute), then routes execution through `recordStepPass` with human-readable
item strings (`"RENAME_COLUMN users.name -> full_name"`, `"RELAX_NOT_NULL users.nickname"`,
`"WIDEN users.count INT -> BIGINT"`, `"BACKFILL users.status DEFAULT 'pending'"`). Step names:
`TABLE_RENAME`, `COLUMN_RENAME`, `RELAX_NOT_NULL`, `TYPE_WIDENING`, `REQUIRED_BACKFILL`.

Also:
- Give the unique-refusal row (~line 1275) its detail: `items_json` = the violation messages list
  (they exist right there), and `classification='UNIQUE_PRECHECK'` instead of `null`.
- The safe-path `writeAppliedHistoryRow` calls in `beforeMigrate` stay as the pass-level summary
  rows — unchanged scope for this plan (the per-step rows now carry the detail).

### R4.3 — Test

- **Scenario 22 (H2 only is sufficient):** one combined upgrade (table rename + column rename +
  widen + relax + backfill + acknowledged drop) → after boot, SELECT the history table and assert:
  one row per non-empty step with the right step name and item strings, all `APPLIED`, in
  execution order (rows have insertion order / timestamps), plus the surgical row. Crash variant:
  reuse the existing freeze-thread technique mid-rename → assert a `TABLE_RENAME`/`COLUMN_RENAME`
  row is left at `PARTIAL-CRASH`, and the resumed boot converges and marks its own new row
  `APPLIED`.

**DoD:** scenario 22 + crash variant green; full matrix green.
**Commit:** `LNCH-1 R4: write-before-execute history rows with item detail for every mutating pass`.

---

## 7. Phase R5 — Documentation truth sweep (F6 residue) (S)

Most of F6 is fixed inside R1/R2/R3. This phase is the closing sweep — do it AFTER those land so
you sweep the final state, not an intermediate one.

R5.1 Grep the repo for every occurrence of: `BY CONSTRUCTION` (SchemaDeltaItem area), `never
     reached` / `before this migration ever runs` (emitter comments), `fidelity limitation`
     (MigrationPlanEmitter), `never starts with a half-applied` (docs) — confirm each was updated
     or delete/replace stragglers.
R5.2 Re-read `docs/SCHEMA_EVOLUTION.md` end-to-end against the final code and fix any remaining
     stale claim (in particular: the worked example's log lines — re-capture them from a real R9
     rehearsal run rather than hand-editing, and the token workflow now including DROP_TABLE).
R5.3 `python scripts/docs/generate_dsl_reference.py --check` — schema didn't change in this plan,
     so expect clean; if dirty, regenerate and investigate why.

**DoD:** grep sweep clean; docs match code.
**Commit:** `LNCH-1 R5: documentation truth sweep post token/backfill fixes`.

---

## 8. Phase R6 — `renamedFrom` marker lifecycle (F7) (S/M)

**Goal:** the stale-marker data-loss edge gets a documented lifecycle rule and an active warning.

R6.1 **Docs** (`docs/SCHEMA_EVOLUTION.md`, extend `## Declaring a rename`): add a subsection
     "Marker lifecycle": (a) on a SECOND rename of the same field/concept, set `renamedFrom` to
     the immediately-previous name (never the original); (b) a marker whose old name no longer
     exists anywhere is harmless and may be kept or removed at will; (c) the concrete hazard,
     spelled out: renaming `B→C` while the marker still says `A` makes the platform see "drop B +
     add C" — a destructive item that will be OFFERED for acknowledgment; acknowledging it loses
     B's data. "If a plan shows a DROP of a column you meant to rename, STOP and fix the marker."
R6.2 **Plan-side warning** (`MigrationPlanEmitter`): when diffing, if a field's `renamedFrom`
     names a column that exists in NEITHER the old model's columns for that table NOR (obviously
     unavailable: live DB — plan side has no DB; the old-model check is the available half),
     attach a WARNING line to the plan output: "field '<c>' declares renamedFrom '<old>' but the
     previous model has no such column — if you renamed twice, update renamedFrom to the
     immediately-previous name; a stale marker can turn a rename into a destructive drop." VERIFY
     how the plan's human-readable rendering emits its SAFE/DESTRUCTIVE sections
     (`Build-NpdevApp.ps1` or the emitter's own writer — find the rendering code by Grep for
     `"NPDev Migration Plan"`) and add a `WARNINGS (n):` block, exit code unchanged.
R6.3 **Executor-side log warning** (cheap symmetry): inside `RenameResolution` usage, when a
     declared rename's old column is absent live AND its new column is also absent (i.e. the
     marker explained nothing and the new column is still missing → this table is heading to the
     destructive path), log one WARN-level line naming the marker as possibly stale. No behavior
     change.
R6.4 **Tests:** a `MigrationPlanEmitter` unit test for the warning (old model lacks the marker
     name → warning present; ordinary first rename → no warning); one executor log-behavior test
     if the existing test infrastructure captures stdout (VERIFY — several existing tests read
     boot log lines; imitate), otherwise assert via the resolution result and skip log capture.

**DoD:** warning appears in a real `-PlanOnly` output for a stale-marker fixture; docs landed.
**Commit:** `LNCH-1 R6: renamedFrom marker lifecycle docs + stale-marker warnings (plan + boot)`.

---

## 9. Phase R7 — Record the concurrency boundary (F8) (S)

Deliberately documentation-only. Do NOT implement advisory locking in this plan.

R7.1 `docs/SCHEMA_EVOLUTION.md` `## Current limitations`: add — "**Single-instance migrations.**
     The schema-lifecycle executor assumes exactly one app instance boots against a given database
     at a time (the platform's deployment posture — see `docs/DEPLOYMENT.md`). Concurrent boots of
     two instances are not guarded by a database lock; do not roll out multi-instance deployments
     of the same app+database until a migration lock exists."
R7.2 `docs/OPEN_GAPS_AND_ROADMAP.md`: add a scoped future row (suggested ID `LNCH1-B6`):
     "Migration advisory lock (Postgres `pg_advisory_lock` / H2 equivalent) for multi-instance
     deployments" — status OPEN, priority tied to any future horizontal-scaling work, with a
     one-line implementation sketch pointing at the `migrate(Flyway)` entry as the lock scope.
R7.3 Regenerate `knowledge/platform-status.json` if (VERIFY) the extractor consumes that roadmap
     section: `python scripts/ai/extract_platform_status.py` — never hand-edit the JSON.

**DoD:** both docs updated; derived projection regenerated if applicable.
**Commit:** `LNCH-1 R7: record single-instance migration boundary (LNCH1-B6)`.

---

## 10. Phase R8 — The stateful-migrations gate's two open issues (F10) (S/M, timeboxed)

The gate `scripts/quality/run-stateful-additive-migrations-check.ps1` now runs to completion but
its own report documents two unresolved oddities. Investigate BOTH to a conclusion; timebox each
to one focused session-hour of investigation before choosing the honest fallback.

R8.1 **Zero-matching Flyway proof test.** Find the step whose `--tests "<filter>"` matches zero
     tests (read the gate's report output from a fresh run; the filter string is in the script).
     Determine: was the test class renamed/moved (Grep for likely names), deleted deliberately, or
     never written? Outcomes, in order of preference: (a) point the filter at the real, existing
     test; (b) if the coverage genuinely no longer exists, write the small missing test (a Flyway
     schema-realization boot proof — imitate the nearest existing proof test); (c) if neither is
     achievable in the timebox, REMOVE the dead step and add a dated comment in the script + a
     line in the gate report explaining what was removed and why — a step that asserts nothing is
     worse than an honest absence. Gradle note: `--tests` with a non-matching filter FAILS the
     task by default — VERIFY how the script survived this (it may pass `--continue` or ignore
     exit codes; whatever you find, make the step's pass/fail honest).
R8.2 **Gradle invocation against a non-buildable template.** Same procedure: identify the step,
     determine what it was ever supposed to build (the template has no standalone build — likely a
     leftover from the quarantined old migration authority, see the plan §2.2 history), and either
     retarget it at the real buildable artifact or remove it with the same dated-comment
     discipline.
R8.3 Re-run the gate end to end; its report must now contain zero "documented skip" entries that
     are actually silent failures.

**DoD:** gate green with every step either genuinely asserting something or removed with recorded
rationale; evidence note includes the before/after report diff.
**Commit(s):** one per issue, `LNCH-1 R8: <which issue> in run-stateful-additive-migrations-check`.

---

## 11. Phase R9 — Full regression, live rehearsal, ledger (M)

R9.1 **Suites:** DSL, generator, kernel-touching modules, `run-runtimehost-gate.ps1`,
     `run-generator-gate.ps1`, full H2 proof matrix (now 22+ scenarios), Postgres Testcontainers
     twin (Docker), `run-stateful-additive-migrations-check.ps1`, `run-app-upgrade-contract-gate.ps1`.
R9.2 **Live rehearsal** (the review's two headline bugs, proven dead against a real stack):
     the Postgres Docker Compose app from `docs/DEPLOYMENT.md` + real seeded data via REST
     (register a real tenant — never `"default"`), then:
     (a) an upgrade that **drops a whole concept**: `-PlanOnly` → copy token → ControlPanel
     `POST /api/admin/schema-migration/acknowledge` on the RUNNING app → deploy → boots on the
     first attempt (F2 dead, including the pre-authorization channel);
     (b) an upgrade combining an acknowledged column drop + a new required-with-default field →
     after boot, REST-verify legacy rows carry the default and a null write is rejected (F1 dead);
     (c) re-capture the worked-example log block for `docs/SCHEMA_EVOLUTION.md` (R5.2).
     Jar-restage guardrail applies before regenerating (§1).
R9.3 **Records:** CHANGELOG entries (token format change, backfill enforcement change,
     schema-ahead detector — each one line); update the 4 LNCH-1 knowledge cards if any encodes
     the old token workflow or refusal text (Grep `knowledge/cards` for `ack` / `token` /
     `required`), rebuild corpora via `python scripts/ai/build_knowledge.py`; append a
     "Remediation round (R0–R9)" section to `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md`'s status header
     pointing here; evidence note `remediation-final.md` with the rehearsal transcript.
R9.4 **Do not cut a release tag** — the owner has explicitly deferred tagging twice; leave
     `CHANGELOG.md` under `[Unreleased]`.

**DoD:** everything in R9.1 green (Postgres legs run, or explicitly marked pending-CI with the
reason); both rehearsal flows verified live with transcripts; records updated; `git status` clean.

---

## 12. Appendix — quick command crib

```powershell
# DSL tests (ALWAYS alongside generator tests when the DSL module changed)
.\gradlew.bat :NPDevContract:dsl:test --no-daemon --console=plain
# Generator tests
.\gradlew.bat -p NPDevGenerator :generator:test --no-daemon --console=plain
# RuntimeHost gate (regenerates a sample app + runs its suite)
pwsh -NoProfile -File scripts\quality\run-runtimehost-gate.ps1
# Generator gate (includes the DSL_REFERENCE --check sub-step)
pwsh -NoProfile -File scripts\quality\run-generator-gate.ps1
# Stateful-migrations gate (Phase R8's subject)
pwsh -NoProfile -File scripts\quality\run-stateful-additive-migrations-check.ps1
# H2 proof matrix only (VERIFY exact test-class name first via Glob)
.\gradlew.bat -p NPDevRuntimeHost test --tests "*ProofMatrix*" --no-daemon --console=plain
# Postgres twin (needs Docker)
.\gradlew.bat -p NPDevRuntimeHost integrationTest --no-daemon --console=plain
# Restage jars after RuntimeHost/kernel Java changes, BEFORE regenerating any app
pwsh -NoProfile -File scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs
```

Severity-ordered minimum bar if the effort must be cut short: **R0 → R1 → R2 are non-negotiable**
(they are the data-integrity and broken-workflow fixes); R3–R8 harden and document; R9's rehearsal
is what earns the word "fixed" for R1/R2 — do not skip it even under time pressure, shrink it to
flows (a) and (b) only.

---

*Companion documents: `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` (the original implementation plan and
its guardrails, all still binding) · `docs/SCHEMA_EVOLUTION.md` (the user-facing contract this
plan repairs) · review findings recorded in the conversation of 2026-07-19 and summarized in §0.*
