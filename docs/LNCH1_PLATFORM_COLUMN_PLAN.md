# LNCH-1 Platform-Column Plan — Closing the Last Four Findings

> **Status:** APPROVED PLAN — not started
> **Written:** 2026-07-21, verified against the working tree at commit `a49af58` (branch `beta1-vision-spine`)
> **Origin:** an independent review of the completed LNCH-1 **closeout** round
> (`docs/LNCH1_CLOSEOUT_PLAN.md`, phases C0–C8, 9 commits) confirmed all six closeout findings and
> `LNCH-1-B8` are genuinely fixed, and found **1 real bug with two faces (MEDIUM), 1 measurement-
> integrity gap (MEDIUM), 1 partially-closed finding, and 1 typo** — plus the items the closeout
> round itself named as still open.
> **Audience:** an AI implementation session (or human) that has NOT read this project's history.
> Follow it literally, in order. Where it says **VERIFY**, check the real code before writing any —
> line numbers are from `a49af58` and may drift.
>
> **Read before touching code, in this order:**
> 1. This document, end to end.
> 2. `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md`
>    — **the tiebreaker.** If anything else disagrees with it, it wins.
> 3. `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\closeout-final.md` — the previous
>    round's record, including its own honest "not verified" list.
> 4. `docs/LNCH1_CLOSEOUT_PLAN.md` §2 (design decisions — still binding) and §3 (guardrails — ALL
>    still binding, and §3.1's **corrected** commands).
> 5. `docs/SCHEMA_EVOLUTION.md` — the user-facing contract.
>
> **Inherited corrections — do not re-introduce these mistakes:**
> - `NPDevRuntimeHost` is a **template**, not a buildable Gradle subproject. Its tests run inside an
>   assembled sample app (`D:\WorkSpace\NPDev\Build\lnch1-harden-app`, invoked with
>   `-PnpdevRuntimeHostLibsDir=...` using **forward slashes**). See §3.1.
> - The blanket destructive posture requires **all four** `schemaLifecycle` fields to line up — never
>   infer it from `allowDestructiveRecreate: true` alone.
> - `strategy: RecreateOnAppStart` is **inert**. It has no distinct runtime behaviour; the string is
>   read only to evaluate the blanket posture. Never offer it as a dev/CI escape hatch.

---

## 0. Findings → phase map

| # | Severity | Finding | Fixed in |
|---|---|---|---|
| **T-B1** | **MEDIUM** | `relaxNoLongerRequiredColumns` drops `NOT NULL` from the platform-managed columns `tenant_id`, `row_version` and `version` on **every** fingerprint-changing boot, because they appear in `businessTableColumns` but never in `businessTableRequiredColumns` (which is model-derived). Proven live — the rehearsal log now quoted in `docs/SCHEMA_EVOLUTION.md` reads `relaxed NOT NULL on no-longer-required column(s): [users.version, users.row_version, users.tenant_id]`. `tenant_id`'s `NOT NULL` is the guard the emitter explicitly added so an in-place upgrade cannot leave rows unreachable to every tenant-scoped read; `row_version` NULL silently defeats LNCH-16's compare-and-swap. | T1 |
| **T-B2** | **MEDIUM** | `version` is emitted by `fullColumnNames` but omitted from `additiveColumnNames`, so **no migration can ever add it to an existing table**. A table missing it yields an `UNKNOWN` delta item, and since the closeout round's C1 that now **refuses the boot** unless an itemized token is supplied — the only recovery being a full token-gated wipe. It is also how proof scenarios 24b/27 manufacture their UNKNOWN: the suite's canonical "unexplainable diff" is a platform column shaped exactly like `row_version`, which *is* additive. | T2 |
| **T-M1** | MEDIUM | Ten uncommitted build-configuration files (`gradle.properties` / `build.gradle` across six modules) add `org.gradle.parallel`, `caching`, `workers.max=4`, `-Xmx3g`, `daemon.idletimeout`. They have been uncommitted for several rounds (the HYG-2 at-risk pattern), and — more consequentially — the `SandboxedPluginExecutionEngineTest` flake rate ("~1 in 5 under load") and the 179-of-257 concurrent-run failure were both measured **under this local-only config**, so neither result describes CI. | T3 |
| **T-P1** | LOW/MED | `C-D2` is **PARTIAL, not closed**: one definition of ~33 was flipped to the recommended posture (owner-ratified scope, honestly recorded in `closeout-final.md` item 7), but the finding was about the corpus the AI authoring loop learns from, and the corpus is ~3% changed. | T4 |
| **GATE-OBS-1** | MEDIUM | The RuntimeHost gate still exits 1. `:test` passes; the sole red check is `runtime-surface-reports-current`, now filed with its six sub-checks and counts. Carried across four rounds. | T5 |
| **T-F1** | LOW/MED | The `SandboxedPluginExecutionEngineTest` flake is tagged and rate-measured but its **root cause was never established**. | T6 |
| **IT-EXTPG-1** | LOW | The 10 `integrationTest` `ApplicationContext` failures need an externally-configured Postgres. Filed; the precondition to run them has never actually been exercised. | T6 |
| **T-V1** | LOW | `run-generator-gate.ps1` and `run-beta-release-gate.ps1` were **not run** in the closeout round, despite that round changing generator-module code (C2's new test) and `Build-NpdevApp.ps1` (C4). | T7 |
| **T-R1** | LOW | Record hygiene: `closeout-final.md`'s "not verified" list skips number 3; two `AppGen` `model.json` edits (2026-07-18/19) remain unexplained. | T8 |

**Explicitly NOT in scope (boundaries — record only, do not build):**
- `LNCH-1-B9` — the schema-ahead detector cannot see a pure column *drop* (no residue exists).
  Documented WONTFIX for v1.
- `LNCH-1-B6` — multi-instance migration advisory lock.
- Unifying the `version` (JPA) and `row_version` (LNCH-16 CAS) columns. They were deliberately kept
  separate to carry zero regression risk; **T2 does not merge them.**

---

## 1. Orientation

| File | What matters here |
|---|---|
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` (≈145 KB — **never full-read**; Grep to a method, Read with `offset`/`limit`) | `PLATFORM_MANAGED_COLUMNS` (~line 57, `Set.of("id","version","row_version","tenant_id")`). `relaxNoLongerRequiredColumns` (~1262) — T1's subject. `applyRequiredFieldBackfills` (~984) and `addBackfillAndTightenColumn` (~1071) — the add→backfill→tighten pattern T1/T2 reuse. `findSchemaAheadMissingColumns` (~1934). `afterMigrate` (~2090). |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java` (≈197 KB — **never full-read**) | Fresh `CREATE TABLE` emits all three platform columns **NOT NULL with DEFAULTs** (~lines 370–377). The additive path adds `tenant_id` and `row_version` **without** `NOT NULL` (~203–207) and never adds `version` at all. `RESERVED_BUSINESS_COLUMN_NAMES` (~318). `fullColumnNames` (~640). `additiveColumnNames` (~661). |
| `NPDevGenerator/generator/src/test/java/com/npdev/generator/dbconfig/PlatformColumnContractTest.java` | The C2 conformance test pinning `PLATFORM_MANAGED_COLUMNS` to the emitter. T2 changes what the emitter emits, so **this test is in your blast radius**. |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorProofMatrixTest.java` | H2 proof matrix (38 tests). Scenarios 24b and 27 manufacture their UNKNOWN via a **missing `version` column** — T2 changes that, so both must be re-based (§2.3). |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorPostgresProofMatrixTest.java` | Postgres twin (25 tests, `@Tag("integration")`, Docker-gated). Same re-basing applies. |
| `scripts/quality/run-runtimehost-gate.ps1` + its observability step | T5's subject (`GATE-OBS-1`). |
| `docs/SCHEMA_EVOLUTION.md` | The contract. Its worked-example log block (~line 271) contains the T-B1 evidence and must be re-captured after T1. |

### 1.1 The three overlapping column sets (know these before you touch anything)

There are already **three** near-duplicate notions of "platform column". Do **not** add a fourth.

| Set | Where | Contents | Purpose |
|---|---|---|---|
| `RESERVED_BUSINESS_COLUMN_NAMES` | emitter (~318) | `version`, `row_version`, `tenant_id` | Fail-fast at generation time if a **model field** would collide |
| `PLATFORM_MANAGED_COLUMNS` | executor (~57) | `id`, `version`, `row_version`, `tenant_id` | Trigger B's "unexplained extra" exclusion; pinned by `PlatformColumnContractTest` |
| (implicit) `fullColumnNames` tail | emitter (~640) | `id` (when undeclared), `version`, `row_version`, `tenant_id` | What the manifest declares |

**A crucial consequence for T1:** because `RESERVED_BUSINESS_COLUMN_NAMES` makes a model field named
`version`/`row_version`/`tenant_id` a hard generation-time error (see
`SchemaRealizationEmitterReservedColumnTest`), those three names in a live table are **always**
platform columns and never a user's field. Excluding them from the relax pass is therefore
unambiguously safe — there is no case where a user legitimately wants `tenant_id`'s `NOT NULL`
relaxed because their model made it optional.

---

## 2. Design decisions — already made, do NOT re-derive

If you believe one is wrong, STOP and ask the owner.

### 2.1 (T-B1) The relax pass must skip platform-managed columns — and repair what it already relaxed

Two halves; both are required. Half one alone leaves every already-upgraded app permanently nullable.

**Half A — stop the bleeding.** In `relaxNoLongerRequiredColumns`, skip any column in
`PLATFORM_MANAGED_COLUMNS` (case-insensitively, matching how the rest of that method normalizes).
`id` is already excluded via the live primary-key read; keep that exclusion too — a concept may
declare its own id field, and the PK read is the honest source for which column that is.

**Half B — re-tighten what was relaxed.** Add a repair pass that restores `NOT NULL` on the three
platform columns when it is safe. Reuse the existing three-step pattern from
`addBackfillAndTightenColumn` rather than inventing a second one:

1. If the column is already `NOT NULL` → no-op (idempotent).
2. `UPDATE <table> SET <col> = <platform default> WHERE <col> IS NULL` — the defaults are fixed and
   known: `version` → `0`, `row_version` → `0`, `tenant_id` → `'default'`. **VERIFY** these against
   the emitter's fresh-CREATE lines (~370–377) rather than trusting this document.
3. `ALTER TABLE <table> ALTER COLUMN <col> SET NOT NULL`.

Run it in the same place the relax pass runs (unconditionally, before `classify()`), for the same
reason: tightening a platform column with a known default can never lose data, and leaving it for a
later phase would mean the very next boot re-relaxes it. Record it as its own step-pass in
`npdev_schema_history` (step name `TIGHTEN_PLATFORM_COLUMNS`) using the existing `recordStepPass`
helper — the audit trail must show a repair happened.

**One deliberate exception to think about, then implement as written:** a table whose `tenant_id`
column is *absent entirely* (a very old app) is not this pass's problem — the additive migration adds
it. Only act on columns that exist and are nullable.

### 2.2 (T-B2) Make `version` additive-eligible, and stop the additive path emitting nullable platform columns

Two changes in `SchemaRealizationEmitter`:

**A. Add `version` to the additive path.** `additiveColumnNames` gains `"version"`, and
`appendAdditiveColumns` gains an `ALTER TABLE ... ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0`
line beside the existing `tenant_id` / `row_version` ones. **VERIFY** the exact `renderType` call and
statement shape by copying the adjacent `row_version` line — it is the same type and the same
default, which is precisely why this is safe.

Effect: a table missing `version` self-heals on the next boot instead of producing an `UNKNOWN`
item, which removes the most likely real-world cause of a token-gated whole-schema wipe.

**B. Fix the NOT NULL asymmetry.** The fresh `CREATE TABLE` emits all three platform columns as
`NOT NULL ... DEFAULT`; the additive path emits them with a `DEFAULT` but **no** `NOT NULL`. So a
table's platform columns are strict if the table was created fresh and loose if it was upgraded into
existence. Rather than adding `NOT NULL` to the `ADD COLUMN` statement (which fails on a table with
existing rows before the default is applied — engine-dependent and fragile), let **T1's Half-B
repair pass** converge them: it runs after the additive migration on the following boot and tightens
any nullable platform column. Add a comment on the additive lines pointing at that pass, so the
asymmetry is documented as intentional-and-converging rather than looking like an oversight.

### 2.3 (T-B2) Re-base the two proof scenarios that depend on `version` being un-addable

Scenarios 24b and 27 (H2, plus their Postgres twins) construct their `UNKNOWN` item by creating
`widgets` **without** the `version` column. After T2 that column becomes additive-eligible, so those
fixtures will no longer produce an `UNKNOWN` and both scenarios will stop testing what they claim.

**Do not weaken the scenarios to make them pass.** Re-base them onto an UNKNOWN that is genuinely
un-addable by construction. The candidate the code itself points to is a **missing required bond /
FK column** — `isAdditiveEligible` excludes bond columns, and `SchemaDeltaReport.itemizeColumnLevelDiff`
itemizes a missing non-additive-eligible column as `Unknown`. **VERIFY** which shape actually yields
an `Unknown` (and not the dedicated bond refusal `refuseIfRequiredBondColumnMissing` intercepts —
that refusal fires before the report, so an *optional* bond column is the likelier vehicle) by
asserting `report.hasOnlyNamedDestructiveKinds()` is false in the new fixture **before** relying on
it. Update each scenario's `@DisplayName` and comments to say what the new UNKNOWN actually is
(guardrail: a test name is documentation).

### 2.4 (T-M1) Commit the build tuning, and qualify every measurement made under it

The tuning is legitimate and useful; the problem is that it is invisible to CI and to anyone reading
a recorded measurement. Decide with the owner (§2.6 question 3), then either:

- **Commit it** (recommended) with a comment block on each file explaining the memory math and that
  these settings are what local gate runs are measured under; and add one line to
  `docs/` wherever the gates are documented saying that CI does not use them, so a flake rate
  measured locally is not assumed to hold in CI; **or**
- **Revert it** and re-measure the flake without it.

Either way, **re-state the T-F1 flake rate with its configuration recorded** (see T6).

### 2.5 (T-P1) Finish the corpus flip, in one deliberate batch

The owner ratified one app last round. Ask again (§2.6 question 4) now that the mechanism is proven
in practice on `simple-user-registry-h2local`. Recommended batch: the remaining apps the gates and
tutorial actually exercise — `simple-user-registry-postgres`, `simple-product-h2local`,
`simple-consumer-h2server`, and `NPDevSamples\12works\gift-idea-tracker`. For **each** app flipped:
edit, regenerate, boot, and take one additive change through it. Do not bulk-edit without
regenerating — a posture the toolchain rejects would be worse than the status quo.

Leave `lnch1-rehearsal` on the blanket posture deliberately (its `README.md` explains why: it exists
to rehearse upgrades on a definition shaped like the ones that actually shipped).

### 2.6 Questions for the owner — ASK ALL AT ONCE IN T0, DO NOT GUESS

1. **(T1)** Confirm the repair pass should actively re-tighten `NOT NULL` on `tenant_id`,
   `row_version` and `version` for apps already relaxed, backfilling NULLs to the platform defaults
   first. *Recommendation: yes — otherwise every already-upgraded app stays permanently loose.*
   **RATIFIED 2026-07-21: YES — both halves. Skip platform columns in the relax pass AND repair
   already-loosened apps (backfill NULLs to 0 / 0 / `'default'`, then restore `NOT NULL`, recorded as
   a `TIGHTEN_PLATFORM_COLUMNS` history row).**
2. **(T2)** Confirm `version` should become additive-eligible. *Recommendation: yes — it is the same
   shape as `row_version`, which already is, and it removes the most likely real-world UNKNOWN.*
   **RATIFIED 2026-07-21: YES — make it additive-eligible. `version` and `row_version` are NOT
   merged (§0 boundary stands).**
3. **(T3)** Commit the Gradle tuning files, or revert them? *Recommendation: commit, with the memory
   math documented and a note that CI does not use them.*
   **RATIFIED 2026-07-21: COMMIT**, with the memory math on each file and an explicit note wherever
   the gates are documented that CI does not use these settings — so a locally-measured flake rate or
   timing is never assumed to transfer to CI.
4. **(T4)** Which further definitions should flip to `KeepExistingIfCompatible` +
   `allowDestructiveRecreate: false`? *Recommendation: the four in §2.5.*
   **RATIFIED 2026-07-21: the four in §2.5** — `simple-user-registry-postgres`,
   `simple-product-h2local`, `simple-consumer-h2server`, `NPDevSamples\12works\gift-idea-tracker`.
   Each regenerated, booted, and taken through one additive change **individually**.
   `lnch1-rehearsal` deliberately stays on the blanket posture.
5. **(T5)** How much effort for `GATE-OBS-1` — fix it, or formally accept it with a tracked owner and
   a documented "known red" status? *Recommendation: one timeboxed session to fix; if it is
   governance drift rather than a code defect, convert it to a non-blocking advisory check so the
   gate's exit code stops lying.*
   **RATIFIED 2026-07-21: one timeboxed session to fix; if it proves to be governance/evidence drift
   rather than a code defect, convert it to a non-blocking advisory and file the governance work as
   its own tracked roadmap row.**

---

## 3. Guardrails

All of `docs/LNCH1_CLOSEOUT_PLAN.md` §3 still binds. The ones that matter most here:

1. **Never claim a verification you did not run.** Docker has gone down mid-session twice in this
   feature's history. If a Postgres leg cannot run, write "NOT RUN — Docker unavailable" in the
   evidence file, add a ledger row, and say it in your closing summary.
2. **Never measure under a config you have not recorded.** The previous round nearly published a
   flake ratio derived from one cached Gradle result replayed five times, and nearly attributed a
   concurrent-run failure to its own change. Before recording any timing or flake number, state:
   serial or parallel, which Gradle properties were in effect, and whether `--rerun-tasks` was used.
3. **Prove each bug-fix test can detect the bug.** For T1 and T2, run the new test against the
   unfixed code first, observe it red, paste the failure into the evidence note, then fix.
4. **Restage jars after any RuntimeHost change, before regenerating an app**, and pass the **same**
   `-RuntimeHostLibsDir` to both the sync script and `Build-NpdevApp.ps1`. Prefer the `rebuild-app`
   skill.
5. **`:generator:test` does not run `:dsl` tests.** Run each module explicitly. And this round
   changes the generator — **run `run-generator-gate.ps1`** (T7 exists because last round did not).
6. **Never hand-edit a generated app's `npdev-generated/` tree** (SHA-256 hash-guarded).
7. **Build output → `D:\WorkSpace\NPDev\Build`. Evidence → `...__OutsideRepo\lnch1-evidence\platcol-<phase>.md`.**
8. **Layer-2 edits (`AppGen\apps\*`, `NPDevSamples\*`) are source of truth** — record every one in the
   evidence note (T8 exists because two went unrecorded).
9. **Small bounded commits**, `LNCH-1 T<n>: <what>` + a `Verified:` line naming what actually ran.
   **No `git add .`.** No regex-patching of Java.
10. **A test's `@DisplayName` is documentation.** T2 changes what scenarios 24b/27 test — rename them
    in the same commit.

### 3.1 Commands that actually work (inherited, corrected)

```powershell
# RuntimeHost tests run inside the ASSEMBLED app, not the template:
#   cd D:\WorkSpace\NPDev\Build\lnch1-harden-app
#   .\gradlew.bat test          -PnpdevRuntimeHostLibsDir=D:/WorkSpace/NPDev/NPDev_General__OutsideRepo/runtimehost-libs
#   .\gradlew.bat integrationTest -PnpdevRuntimeHostLibsDir=<same>   # Docker required
# NOTE: forward slashes in that property (Git Bash mangles backslashes).
# If that app no longer exists, rebuild it as closeout-final.md describes -- never inside the repo.

.\gradlew.bat :NPDevContract:dsl:test --no-daemon --console=plain
.\gradlew.bat -p NPDevGenerator :generator:test --no-daemon --console=plain
pwsh -NoProfile -File scripts\quality\run-runtimehost-gate.ps1
pwsh -NoProfile -File scripts\quality\run-generator-gate.ps1
pwsh -NoProfile -File scripts\quality\run-stateful-additive-migrations-check.ps1
pwsh -NoProfile -File scripts\quality\run-beta-release-gate.ps1
pwsh -NoProfile -File scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs
```

**Run gates serially.** The previous round produced a 179-of-257 failure by running the RuntimeHost
gate concurrently with the Postgres twin; the serial re-run was clean.

---

## 4. The phases

Sequential. Each ends with its named tests green, relevant gates green (or failing only for a
baseline-recorded reason), **one commit**, and an evidence note at
`...__OutsideRepo\lnch1-evidence\platcol-<phase>.md`.

---

### Phase T0 — Baseline, decisions, and two reproductions (S)

**T0.1** Record the working tree exactly as you find it. Expected: the ten uncommitted
build-config files from T-M1 and nothing else. If anything else is modified, STOP and report.

**T0.2** Run and record the full baseline **serially**: assembled-app RuntimeHost suite, Postgres
twin (if Docker is up), DSL, generator, and the four gates in §3.1 — including the two that were
not run last round. Note each result with its configuration (guardrail 2).

**T0.3** Ask the owner §2.6's **five** questions in one message. Record answers by editing §2.6 in
place, marking each `RATIFIED 2026-__-__: <answer>`.

**T0.4 — VERIFY the three column sets** (§1.1) and the platform-column defaults (§2.1 step 2)
against the real emitter source. Write the confirmed facts into the evidence note. If any differ
from this document, STOP and re-plan with the owner.

**T0.5 — Reproduction A (T-B1), expect RED.** Add to the H2 proof matrix:

```
Scenario 28 (T-B1): an ordinary upgrade must NOT relax NOT NULL on the platform-managed columns
```

Setup: create a table with all three platform columns `NOT NULL DEFAULT ...` exactly as the fresh
`CREATE TABLE` emits them (reuse `seedTwoRealisticConceptsWithData`, **VERIFY** it declares them NOT
NULL — if it does not, add a variant that does, since the production shape is what matters here).
Seed fingerprint + ownership through the production writer (`afterMigrate` with the v1 manifest).
Then run `beforeMigrate` with a v2 manifest carrying any ordinary change (e.g. one new optional
field). Assert: `tenant_id`, `row_version` and `version` are **still NOT NULL** afterwards (query
`isColumnNotNull`-equivalent metadata, or attempt a NULL insert and expect rejection — prefer the
metadata check, it is deterministic).

Run it. **It must fail today.** Paste the failure into the evidence note.

**T0.6 — Reproduction B (T-B2), expect RED.** Add:

```
Scenario 29 (T-B2): a table missing the platform 'version' column self-heals additively -- it is
                    not an unexplainable diff that demands a destructive token
```

Setup: create `widgets` **without** `version` (24b's construction). Seed fingerprint + ownership.
Assert `SchemaDeltaReport.generate(...).hasOnlyNamedDestructiveKinds()` is **true** (i.e. no UNKNOWN),
and that a `beforeMigrate` + `flyway.migrate()`-equivalent path leaves `version` present. **VERIFY**
how the existing scenarios drive the additive migration in this test class — if they cannot invoke
Flyway, assert the manifest-level property (`version` ∈ additive columns → no UNKNOWN) and prove the
DDL half in a `SchemaRealizationEmitter*Test` instead. Split the scenario across the two suites if
that is what the harness supports; say so in the evidence note rather than forcing it.

Run it. **It must fail today.**

**DoD:** tree recorded; baseline recorded with configurations; five answers recorded; §1.1 verified;
scenarios 28 and 29 written and observed RED.
**Commit:** none (both land with their fixes).

---

### Phase T1 — Stop relaxing platform columns, and repair what was relaxed (T-B1) (M)

**T1.1 — Half A.** In `relaxNoLongerRequiredColumns` (~1285–1293), add the
`PLATFORM_MANAGED_COLUMNS` exclusion alongside the existing required/PK exclusions. Comment it with
the reason from §1.1 (a model field can never carry these names — `RESERVED_BUSINESS_COLUMN_NAMES`
makes it a generation-time error — so a live column with one of these names is always platform-owned).

**T1.2 — Half B.** Implement `tightenPlatformColumns(dataSource, manifest)` per §2.1, called
immediately after `relaxNoLongerRequiredColumns` in `beforeMigrate`. Three idempotent steps per
column, `recordStepPass` with step name `TIGHTEN_PLATFORM_COLUMNS`, and a log line naming what was
tightened. Skip columns that do not exist live. **VERIFY** `ALTER COLUMN ... SET NOT NULL` needs no
engine branch (the backfill pass's javadoc already claims this for H2 and Postgres — confirm, do not
assume).

**T1.3** Scenario 28 passes. Add **scenario 28b**: a table whose platform columns are *already*
nullable (simulating an app relaxed by the old behaviour, with a NULL value present in each) is
repaired — NULLs backfilled to the platform defaults, then `NOT NULL` restored, with a
`TIGHTEN_PLATFORM_COLUMNS` history row, and a second boot is a clean no-op.

**T1.4 — Full-suite fallout.** Run the assembled-app suite. Any test asserting the old relax
behaviour on platform columns encodes removed behaviour — update it and comment with T-B1. Never
weaken an assertion to make it pass.

**T1.5 — Docs.** `docs/SCHEMA_EVOLUTION.md`: the worked-example log block currently contains the
T-B1 evidence line. It must be re-captured after this fix (T9.2) — for now, add a short subsection
under the platform/limitations material stating that `id`, `version`, `row_version` and `tenant_id`
are platform-managed, always `NOT NULL` with fixed defaults, and never affected by a model field's
optionality.

**DoD:** scenarios 28/28b green; full suite green; platform columns provably survive an upgrade
strict, and an already-loosened app is repaired.
**Commit:** `LNCH-1 T1: never relax platform-managed columns, and re-tighten apps the old pass loosened`

---

### Phase T2 — Make `version` additive-eligible (T-B2) (M)

**T2.1** Emitter change A per §2.2: add `"version"` to `additiveColumnNames` and the matching
`ADD COLUMN IF NOT EXISTS` line to `appendAdditiveColumns`, copying the adjacent `row_version` line's
shape.

**T2.2** Emitter change B per §2.2: comment the additive platform-column lines to record that they
are emitted nullable deliberately and converge to `NOT NULL` via T1's repair pass.

**T2.3 — The C2 conformance test is in your blast radius.** `PlatformColumnContractTest` pins
`PLATFORM_MANAGED_COLUMNS` against the emitter's platform columns. T2 changes `additiveColumnNames`,
not `fullColumnNames`, so the test should be unaffected — **VERIFY that, and if it does fail, do not
"fix" it by editing the constant** until you have established which side is right.

**T2.4 — Re-base scenarios 24b and 27** per §2.3, in both the H2 matrix and the Postgres twin.
Assert the new fixture genuinely yields an UNKNOWN (`hasOnlyNamedDestructiveKinds() == false`) as a
**precondition** inside each test, so a future change that makes the new vehicle addable fails loudly
instead of silently hollowing the scenario out. Rename both scenarios to describe the new UNKNOWN.

**T2.5** Scenario 29 passes. Run the generator suite and the assembled-app suite.

**T2.6 — Docs.** Update the Trigger A/B description in `docs/SCHEMA_EVOLUTION.md` (~line 396): it
currently cites `version` as an example of a column "nothing re-adds automatically". After T2 that is
false — replace the example with whatever §2.3's re-basing established as genuinely un-addable.

**DoD:** scenario 29 green; 24b/27 re-based and green on both engines; generator suite green; no
stale claim about `version` anywhere in the docs.
**Commit:** `LNCH-1 T2: make the platform 'version' column additive-eligible so a missing one self-heals`

---

### Phase T3 — Commit (or revert) the build tuning, and qualify the measurements (T-M1) (S)

**T3.1** Per §2.6 answer 3. If committing: add a comment block to each of the ten files explaining
the memory math (six independent Gradle builds, one daemon each, 3 GB heap against 32 GB total,
`workers.max=4` bounding the adapter test fan-out) — most already carry one; make them consistent.
Commit them as **one** commit, separate from any code change.

**T3.2** Document that CI does not use these settings, wherever the gates are described. State the
consequence in one line: a flake rate or timing measured locally does not transfer to CI.

**T3.3** Re-state the T-F1 flake measurement with its configuration attached (feeds T6).

**DoD:** tree clean of build-config drift; the local-vs-CI difference is written down.
**Commit:** `LNCH-1 T3: commit the local Gradle tuning and record that CI does not use it`

---

### Phase T4 — Finish the corpus posture flip (T-P1) (S/M)

**T4.1** For each app ratified in §2.6 answer 4: edit `db.definition.json` to
`strategy: "KeepExistingIfCompatible"`, `allowDestructiveRecreate: false`, and **VERIFY** the correct
values for `destructiveRecreateConfirmation` and `scope` under that strategy by copying the already-
proven `simple-user-registry-h2local` definition rather than inventing them.

**T4.2** For **each** app: regenerate, boot, and take one additive change through it (add a field →
regenerate → boot → column present, data intact). Record each app's result individually. An app that
fails to regenerate is a finding — stop and report it rather than reverting quietly.

**T4.3** Update the knowledge card and rebuild the corpus (`python scripts/ai/build_knowledge.py`),
then **verify the loop learned it**: query the rebuilt index for what `schemaLifecycle` a new app
should use and confirm the recommended posture ranks first. Record query and result.

**T4.4** Update `docs/SCHEMA_EVOLUTION.md`'s "Default for new apps" paragraph with the current count
of definitions on each posture, so the claim is checkable rather than aspirational.

**DoD:** every flipped app regenerates, boots, and takes an additive change; the corpus query returns
the recommended posture; the count in the docs is real.
**Commit:** `LNCH-1 T4: flip the gate- and tutorial-exercised apps to the recommended schemaLifecycle posture`

---

### Phase T5 — GATE-OBS-1 (S/M, timeboxed per §2.6 answer 5)

**T5.1** Read `scripts/quality/run-runtimehost-gate.ps1`'s observability step and the report it
produces. The sole red check is `runtime-surface-reports-current`, already filed with six sub-checks
and counts — start from that filing, do not re-derive it.

**T5.2** Determine which of the three it is: (a) governance/evidence drift with an owner elsewhere in
the docs, (b) a genuine code defect, (c) obsolete. Then act:
- (a) → convert the check to a **non-blocking advisory** that prints its drift but does not fail the
  gate, and file the governance work as its own tracked roadmap row. A gate whose exit code always
  says "fail" trains everyone to ignore it — that is worse than an honest advisory.
- (b) → fix it.
- (c) → remove it with a dated comment explaining what it used to assert.

**T5.3** Whatever the outcome, the RuntimeHost gate must end this phase with an exit code that
**means something**: 0 when the platform is healthy, non-zero only for a real, actionable failure.
Record the before/after exit codes.

**DoD:** the gate's exit code is truthful; `GATE-OBS-1` is either closed or converted to a tracked
advisory with a named owner.
**Commit:** `LNCH-1 T5: make the RuntimeHost gate's exit code truthful (GATE-OBS-1)`

---

### Phase T6 — The flake root cause and the external-Postgres precondition (S/M, timeboxed) (T-F1, IT-EXTPG-1)

**T6.1 — Flake root cause.** One hour, then stop and write down what you know.
- Re-measure **properly**: `--rerun-tasks` every time (the previous round nearly recorded five
  replays of one cached result), 5 runs serial and 5 under the parallel config, with the
  configuration recorded per guardrail 2.
- Read the test. Identify the timing assumption (a sleep, a timeout, a wall-clock assertion, a
  thread-scheduling expectation). Say **which** one it is even if you do not fix it — "root cause not
  established" is acceptable; "root cause not investigated" is not.
- If the cause is a fixed timeout under contention, raise it **with the measured margin quoted in the
  comment** (e.g. "observed max 1.9 s under 4-way parallelism; timeout 5 s"). Do not invent a
  tolerance that does not follow from a measurement — the previous round was right to refuse that.

**T6.2 — IT-EXTPG-1.** Actually exercise the precondition once: stand up a Postgres reachable at
whatever the `test,postgres` profile expects (**VERIFY** the exact properties from the failing ITs'
configuration), run the 10 tests, and record whether they pass. Then write the precondition into the
roadmap row as a runnable recipe (env vars / compose snippet / connection string shape), so the next
session can run them in five minutes instead of re-establishing the attribution a fifth time.
If standing one up is not feasible in the timebox, record exactly what was missing.

**DoD:** the flake's timing assumption is named; the external-Postgres precondition is a runnable
recipe, with the ITs' pass/fail recorded if it could be run.
**Commits:** one per item.

---

### Phase T7 — Run the two gates that were skipped (T-V1) (S)

**T7.1** `run-generator-gate.ps1` — this round changes generator code (T2), and the last round added
a generator test without running this gate. Run it; investigate any failure; record.

**T7.2** `run-beta-release-gate.ps1` — run it. It is the largest evidence gate and has not run in
several rounds; expect it to surface drift. **Timebox investigation to one hour**, then file whatever
remains as tracked roadmap rows rather than chasing it into an unbounded audit.

**T7.3** If either gate cannot run for an environmental reason, say so explicitly in both the
evidence file and the summary — do not silently omit it, which is exactly the gap T-V1 records.

**DoD:** both gates have a recorded outcome from this session.
**Commit:** `LNCH-1 T7: run the generator and beta-release gates, and file what they surfaced`

---

### Phase T8 — Record hygiene (T-R1) (S)

**T8.1** Fix `closeout-final.md`'s skipped list number.

**T8.2** The two unexplained `AppGen` `model.json` edits (2026-07-18/19): `AppGen` is not a git repo,
so use file timestamps and the surrounding evidence notes to determine what changed and why. Then
either explain them in the relevant app's README (the `lnch1-rehearsal/README.md` precedent) or
record them as permanently unexplained with the dates and file paths. **Do not revert them** — you
cannot know what depends on them.

**T8.3** Add a "Platform-column round (T0–T9)" section to the verification ledger with one row per
claim: VERIFIED LIVE / VERIFIED BY SUITE / NOT VERIFIED.

**DoD:** no known record defect remains; the ledger covers this round.
**Commit:** `LNCH-1 T8: reconcile the record for the platform-column round`

---

### Phase T9 — Full regression, live re-capture, closeout (M)

**T9.1** Run everything in §3.1 **serially**, and compare against T0.2. Every delta explained.

**T9.2 — Live re-capture (this is what earns "fixed" for T1).** Take a real app with data through an
upgrade and confirm from the boot log that the platform columns are **not** relaxed — the log line
`relaxed NOT NULL on no-longer-required column(s): [...users.tenant_id]` must be gone, and any
already-loosened app must show the `TIGHTEN_PLATFORM_COLUMNS` repair instead. Then verify by metadata
that `tenant_id`, `row_version` and `version` are `NOT NULL`. Prefer the Postgres rehearsal app
(`AppGen\apps\lnch1-rehearsal`, container `npdev-lnch1-rehearsal-pg`, port 5434, DB
`npdev_lnch1_rehearsal`); its README describes the current v2 state. **If Docker is unavailable, do
this on H2 and say so** — do not skip it.

**T9.3** Replace the worked-example log block in `docs/SCHEMA_EVOLUTION.md` with the **new** verbatim
capture from T9.2 (the current one contains the T-B1 evidence line and would otherwise ship a fixed
bug as documentation).

**T9.4** Roadmap and records: close `GATE-OBS-1`/`IT-EXTPG-1` if T5/T6 closed them, or update their
descriptions; regenerate `knowledge/platform-status.json` via
`python scripts/ai/extract_platform_status.py` (never hand-edit); `CHANGELOG.md` under
`[Unreleased]` — one line each for the T1 and T2 behaviour changes. **Do not cut a release tag.**

**T9.5** Write `platcol-final.md` and make your closing summary **match it exactly**. If something
was not run, both say so.

**DoD:** suites green or explained; platform columns proven strict on a live upgrade; docs carry a
current capture; ledger, roadmap and changelog current; tree clean.

---

## 5. Sequencing and the minimum bar

```
T0 (S) → T1 (M) → T2 (M) → T3 (S) → T4 (S/M) → T5 (S/M) → T6 (S/M) → T7 (S) → T8 (S) → T9 (M)
```

Sequential. T2 must follow T1 (T2's re-based scenarios rely on T1's exclusions being in place, and
T2's nullable-additive-column decision depends on T1's repair pass existing). T4 needs the owner's
answer. T5/T6/T7 are independent of T1/T2 and may be reordered among themselves if one is blocked.

**If the effort must be cut short, the non-negotiable core is T0 → T1 → T2.** T1 is a live,
documented weakening of the tenant-isolation column on every upgrade; T2 removes the most likely
real-world trigger of a token-gated whole-schema wipe and un-hollows two proof scenarios in the
process. T3–T8 are measurement integrity, corpus health, and record-keeping; T9's live re-capture is
what converts T1 from "green suite" to "verified". **If you stop early, say exactly where and which
findings remain open — in the evidence file, in the verification ledger, and in your summary.**

---

*Companion documents: `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` (original 9-phase build) ·
`docs/LNCH1_REMEDIATION_PLAN.md` (R0–R9) · `docs/LNCH1_HARDENING_PLAN.md` (X0–X9) ·
`docs/LNCH1_CLOSEOUT_PLAN.md` (C0–C8) · `docs/SCHEMA_EVOLUTION.md` (user-facing contract) ·
`..\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md` (the tiebreaker).*
