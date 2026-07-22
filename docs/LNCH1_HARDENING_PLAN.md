# LNCH-1 Hardening Plan — Fixing the Post-Remediation Review Findings

> **Status:** APPROVED PLAN — not started
> **Written:** 2026-07-20, verified against the working tree at commit `98e8410` (branch `beta1-vision-spine`)
> **Origin:** an independent review of the completed LNCH-1 **remediation** round (`docs/LNCH1_REMEDIATION_PLAN.md`,
> phases R0–R9, 12 commits) found **1 critical regression, 2 real bugs, 4 incoherences, and 5 gaps**.
> This plan fixes all of the bugs and incoherences, and closes 4 of the 5 gaps.
> **Audience:** an AI implementation session (or human) that has NOT read this project's history.
> Follow this document literally, in order. Where it says **VERIFY**, check the real code before
> writing any — line numbers below are from commit `98e8410` and may have drifted.
>
> **Read before touching code, in this order:**
> 1. This document, end to end.
> 2. `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` §1 (map of the machinery), §2 (design decisions — still
>    binding), **§3 (guardrails — ALL still binding)**.
> 3. `docs/LNCH1_REMEDIATION_PLAN.md` §1 (file orientation map).
> 4. `docs/SCHEMA_EVOLUTION.md` — the user-facing contract you are repairing.
> 5. `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\remediation-R0-R8.md` — the
>    previous session's honest evidence record, including what it did NOT verify.

---

## 0. Findings → phase map

| # | Severity | Finding | Fixed in |
|---|---|---|---|
| **X-B1** | **CRITICAL** | Dropping a concept from the model now **wipes every table in the database** on any app with `allowDestructiveRecreate: true` — which is *every* shipped app definition. The B7 fix escalates the classification to `DESTRUCTIVE`; the blanket flag then authorizes the *whole-schema wipe* path. Before B7 this was a benign no-op. The orphan table itself survives the wipe (it is not manifest-listed) while all real data is destroyed. | X1 |
| **X-B2** | **HIGH** | The R3 "schema-ahead-of-build" detector excludes every additive-eligible column, and in real manifests *every ordinary non-bond field is additive-eligible* — so the detector can never fire for the rename-rollback case F4 was written for. Scenario 21 passes only because its fixture declares no additive columns (a shape no real manifest has). F4 is effectively still open. | X3 |
| **X-B3** | MEDIUM | `afterMigrate` rewrites `ownedBusinessTables` from the new manifest unconditionally. An orphan that survived a pass (X-B1's wipe path, a crash, a refusal-then-partial state) drops out of the ownership set permanently and can never be dropped by a later token-based upgrade. | X2 |
| **X-G3** | MEDIUM | No test covers blanket-flag + destructive classification — the direct root cause of X-B1 going unnoticed. | X1 |
| **X-B2b** | MEDIUM | Test fixtures diverge from production manifests (`businessTableAdditiveColumns` empty in fixtures, fully populated in reality). This is the third occurrence of fixture-vs-production divergence in this feature's history. | X3 |
| **X-G1** | MEDIUM | The Postgres Testcontainers twin was never updated with remediation scenarios 17/18/18b/19/21/22, nor with the R2 `afterMigrate` call-site change. The Postgres leg cannot go green in CI. | X5 |
| **X-G2** | MEDIUM | The R9.2 live rehearsal (real Postgres Compose stack + **ControlPanel pre-authorization round-trip** for a concept drop) was never performed — Docker was down. This is F2's headline DoD and the previous evidence file names it *"the single most important outstanding item."* | X6 |
| **X-I4** | MEDIUM | The blanket `destructiveAllowed` flag is documented as a deprecated backward-compatibility escape hatch, but is `true` in every current app definition and template — the deprecated path is the default path. Docs and reality contradict each other. | X4 |
| **X-I1** | LOW | The previous session's *summary* claims live end-to-end rehearsals that its own *evidence file* says were not performed. The record must be reconciled so future sessions trust the right document. | X7 |
| **X-I2** | LOW | `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md`'s header still says the Postgres Testcontainers legs "were NOT run" — stale; they later ran green (12 tests). | X7 |
| **X-I3** | LOW | `docs/SCHEMA_EVOLUTION.md`'s worked-example log block was never re-captured from a live run; it is still the phase-7 capture. | X6 |
| **X-G4** | LOW | R4's freeze-thread crash variant for the new step-pass history rows was never written. | X8 |
| **X-G5** | LOW | Two quality gates exit non-zero, attributed to pre-existing drift but never independently re-verified. | X8 |

**Not in scope (deliberately deferred, record only):** replacing the blanket flag entirely across all
app definitions (X4 produces the decision + migration path, but mass-editing 30+ app definitions in
layer 2 is its own task); multi-instance migration locking (already recorded as `LNCH-1-B6`).

---

## 1. Orientation — what you are working on

All the machinery lives in three files. **Do not full-read the first one (≈130 KB)** — Grep to a
method name, then Read with `offset`/`limit`.

| File | What matters here |
|---|---|
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` | The runtime authority. Key methods for this plan: `beforeMigrate` (~line 111 — the decision flow), the authorization block (~lines 280–341), `executeSurgicalDestruction` (~line 404), `executeWholeSchemaWipe` (~line 490), `findSchemaAheadMissingColumns` (~line 1817), `classify` (~line 1540), `droppedConceptTables` (~line 1627), `afterMigrate` (~line 1929), `ownedTablesJson` (~line 1995), `readOwnedBusinessTables` (~line 2020) |
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaDeltaReport.java` | Itemizes the residual destructive diff. `generate` (~line 163), `itemizeTableLevelDiff` (~line 188, ownership-gated), `hasOnlyNamedDestructiveKinds` |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorProofMatrixTest.java` | The H2 proof matrix (29 tests). The `manifest(...)` fixture helper is at ~line 1406 — **14 positional parameters**, see §3.2 |

Plus, for X5: the Postgres twin `SchemaLifecycleExecutorPostgresProofMatrixTest` (VERIFY exact path
via Glob — it lives in the RuntimeHost `integrationTest` source set, `@Tag("integration")`, added by
commit `3903e40`).

### 1.1 The decision flow you are modifying (current behaviour, commit `98e8410`)

```
beforeMigrate(dataSource, manifest):
  stored = readFingerprint()
  if stored is blank                        -> none()            [fresh install]
  if stored == manifest.fingerprint         -> [X-B2: schema-ahead detector] -> none()
  attemptInPlaceTableRenames()              [unconditional, safe]
  attemptInPlaceRenames()                   [unconditional, safe]
  relaxNoLongerRequiredColumns()            [unconditional, safe]
  classification = classify()               [X-B1 lives here: orphan -> DESTRUCTIVE]
  if SAFE_ADDITIVE                          -> safeAdditiveOutcome()
  if RENAME_DETECTED / TYPE_CHANGE_DETECTED -> retry renames/widenings, maybe safeAdditiveOutcome()
  refuseIfRequiredBondColumnMissing()
  report        = SchemaDeltaReport.generate()
  expectedToken = DestructiveAckToken.compute(fingerprint, report.stableStrings())
  tokenMatches  = static manifest token OR pending ControlPanel ack row
  hasUnknown    = report has an UNKNOWN item
  blanket       = manifest.destructiveAllowed()

  if !tokenMatches && !blanket   -> REFUSE (throw)                      [correct]
  if tokenMatches && !hasUnknown -> executeSurgicalDestruction()        [correct]
  otherwise                      -> executeWholeSchemaWipe()   <---- X-B1 IS HERE
```

The last line is the bug: when authorization comes from the blanket flag alone, the code drops to a
**whole-schema wipe even though the report contains only named, surgically-executable items.**

---

## 2. Design decisions — already made, do NOT re-derive

These were decided by the review. Implement them as written. If you believe one is wrong, STOP and
ask the owner — do not silently substitute your own design.

### 2.1 (X-B1) Blanket authorization must route to the surgical path, never the whole wipe

Change the routing so the **whole-schema wipe is reached only when the report contains an `UNKNOWN`
item** — i.e. only when the surgical path genuinely cannot explain the diff. Authorization
(token vs blanket flag) decides *whether* destruction is allowed; the report's content decides
*how* it is executed. Those two concerns are currently tangled, which is the bug.

New routing:

```
  if !tokenMatches && !blanket   -> REFUSE (throw)                    [unchanged]
  if !hasUnknown                 -> executeSurgicalDestruction()      [token OR blanket]
  else                           -> executeWholeSchemaWipe()          [token OR blanket]
```

**Why this is safe to do unilaterally:** the surgical path is strictly *less* destructive than the
wipe in every case it now handles — it drops exactly the itemized tables/columns instead of every
table. No app can be harmed by the change; apps that today lose everything will instead lose only
what was actually removed from the model. Keep the existing deprecation warning for blanket-only
authorization, and extend it to name the items that are about to be executed surgically.

**Do NOT** additionally refuse blanket-authorized `DROP_TABLE` items in this phase — that is a
behaviour restriction requiring the owner's call (§2.5 question 1).

### 2.2 (X-B3) Ownership is a union, intersected with reality

Replace `ownedTablesJson(manifest)`'s "just the current manifest" logic with:

```
owned = ( previouslyOwned ∪ manifest.businessTableColumns().keySet() ) ∩ liveTables
```

- **Union with previous** so a table that was dropped from the model but still physically exists
  (survived a wipe, a crash, a refusal) stays *owned* and can therefore still be recognised as a
  dropped concept on a later boot.
- **Intersect with live tables** so anything actually gone drops out naturally — the set never grows
  unboundedly and never claims ownership of something that no longer exists.
- A hand-created table can still never enter the set: entries only ever originate from a manifest.
- When `previouslyOwned` is `null` (never recorded), the union degenerates to the current manifest —
  identical to today's behaviour for legacy apps.

All lower-cased, exactly as today (`Locale.ROOT`).

### 2.3 (X-B2) The schema-ahead detector needs a second, orthogonal trigger

The current single rule — "a non-additive manifest column is missing" — is nearly dead in production
because almost every column is additive-eligible. But the exclusion cannot simply be deleted: its
javadoc (~line 1813) records that it also prevents false positives in direct-call unit tests, where
manifests declare SAFE_ADDITIVE columns that were never physically added because those tests bypass
`flyway.migrate()`. Deleting the exclusion would turn a large part of the existing suite red.

Implement **two independent triggers**; refuse if either fires:

- **Trigger A (existing, keep as-is):** a **non-additive-eligible** manifest column is missing live.
  Covers bond/FK columns.
- **Trigger B (new):** for a given table, **a manifest column is missing live AND that same table has
  at least one *unexplained extra* live column.** An "unexplained extra" is a live column that is
  (a) not in the manifest's column list for that table, (b) not a platform-managed column
  (`tenant_id`, `row_version`, `version`, `id` — **VERIFY** this list against
  `SchemaRealizationEmitter`'s reserved-column handling and reuse the production constant if one
  exists rather than hard-coding a second copy), and (c) not the old side of a declared rename.

Trigger B is precise for the rollback case (`name` missing + `full_name` extra = a newer build
renamed it) and is silent for the unit-test case (columns declared but never added, with no extras).

**Residual limitation to document, not fix:** a newer build that *dropped* a column leaves no extra
column behind, so neither trigger fires; the old jar boots and the R__ migration may re-add the
column empty. Record this in `docs/SCHEMA_EVOLUTION.md#refusals-and-rollback` as a known limit —
do not invent a third trigger for it.

### 2.4 (X-B2b) Fixtures must mirror production manifests

Every new or modified proof-matrix scenario must populate `businessTableAdditiveColumns` the way a
real manifest does: **every non-bond, non-primary-key column of the table**. The current habit of
passing `Map.of()` is what hid X-B2. See §3.2 for the concrete helper you must add.

### 2.5 Questions for the owner — ASK BEFORE the phase that needs them

Batch these into one message at the start of X0. Do not guess.

1. **(needed by X4)** Should new app definitions default `allowDestructiveRecreate` to **false**
   (making the itemized token the only route to destruction), with existing apps left untouched?
   *Recommendation: yes* — the flag is documented as deprecated, and X-B1 shows how dangerous the
   default is. This changes generated-app behaviour, so it is the owner's call.

   > **RATIFIED 2026-07-20: YES — default false for newly authored definitions; existing apps untouched.**
   > Implementation note established during X0: `UserDatabaseDefinitionLoader` (line ~48) *already*
   > defaults the flag to `false` when the key is absent — apps carry `true` because their definitions
   > set it explicitly, and `schemas/ai/user-db-definition.schema.json` **forces** `const: true`
   > whenever `strategy` is `DropAndRecreateOnStructureChange`. X4 therefore changes authoring
   > templates/docs/guidance (steering new definitions to `KeepExistingIfCompatible` +
   > `allowDestructiveRecreate: false`), **not** the loader and **not** that schema constraint.

2. **(needed by X4)** If yes to (1), should the platform additionally **refuse** blanket-only
   authorization for `DROP_TABLE` items specifically (forcing a token for concept drops, while
   still allowing blanket-authorized column drops)? *Recommendation: yes, as a follow-on increment
   after X1 lands.*

   > **RATIFIED 2026-07-20: YES — as a follow-on increment, in X4.4, NOT folded into X1.**
   > X1 lands the surgical routing alone so the operator-visible regression fix stays reviewable and
   > revertable on its own; X4.4 then adds the `DROP_TABLE` refusal plus scenario 26, and updates
   > scenario 24 to supply a token instead of relying on blanket-authorized concept drop.

3. **(needed by X6)** Which app is the live-rehearsal target for the Postgres Compose run?
   *Recommendation: `D:\WorkSpace\NPDev\AppGen\apps\lnch1-rehearsal`* (the previous session already
   built it against real Postgres 15 on port 5434, DB `npdev_lnch1_rehearsal`). Confirm it still
   exists and that Docker is available before X6 starts — if Docker is down, X6 must be deferred
   *explicitly*, not silently skipped.

   > **RATIFIED 2026-07-20: `D:\WorkSpace\NPDev\AppGen\apps\lnch1-rehearsal`.**
   > Preconditions verified during X0: the app folder exists, and Docker is running (server 29.6.1).
   > X6 is therefore **in scope**, not deferred.

---

## 3. Guardrails — violating these has caused real bugs in this repo

All of `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` §3 still applies. The ones most likely to bite here:

1. **Never claim a verification you did not run.** If Docker is unavailable, write "NOT RUN — Docker
   unavailable" in the evidence file and in your final summary. The previous session's evidence file
   did this correctly while its summary did not (X-I1); do not repeat that.
2. **Restage jars after any RuntimeHost Java change, before regenerating an app:**
   `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs`
   — the script's default directory does NOT match `Build-NpdevApp.ps1`'s default; pass it to both
   or the running app keeps a stale jar. Prefer the `rebuild-app` skill, which does all three caches.
3. **`:generator:test` does not run `:dsl` tests.** Run each module's suite explicitly.
4. **Never hand-edit a generated app's `npdev-generated/` tree** — SHA-256 whole-tree hash-guarded;
   the app will refuse to boot with "signature mismatch". Change the generator and regenerate.
5. **Build output → `D:\WorkSpace\NPDev\Build`. Evidence/scratch →
   `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\hardening-<phase>.md`.** Never
   inside the repo.
6. **Never use tenant id `"default"`** in REST verification — reserved sentinel, silently 403s.
   Register a real tenant first (`POST /api/admin/tenants`, e.g. `demo`).
7. **New/changed gate scripts must invoke `Build-NpdevApp.ps1` as `pwsh -NoProfile -File` child
   processes** — `Set-StrictMode` leaks through the `&` call operator.
8. **Small bounded commits**, one per phase or sub-slice, `LNCH-1 X<n>: <what>` plus a `Verified:`
   line naming the suites/gates actually run. **No `git add .`.** No regex-patching of Java.
9. **A pre-existing bug found on the way gets its own commit, with its own test, before the feature
   commit that found it.**
10. **Prove each bug-fix test can detect the bug.** For X1 and X3, run the new test against the
    unfixed code first, observe it red, record that in the evidence file, then fix. A green test that
    was never red proves nothing.

### 3.1 Commands you will run constantly

```powershell
# H2 proof matrix (the main suite for this plan)
.\gradlew.bat -p NPDevRuntimeHost test --tests "*SchemaLifecycleExecutorProofMatrixTest" --no-daemon --console=plain
# Whole RuntimeHost unit suite
.\gradlew.bat -p NPDevRuntimeHost test --no-daemon --console=plain
# Postgres twin (needs Docker; @Tag("integration"), excluded from `test`)
.\gradlew.bat -p NPDevRuntimeHost integrationTest --no-daemon --console=plain
# DSL + generator
.\gradlew.bat :NPDevContract:dsl:test --no-daemon --console=plain
.\gradlew.bat -p NPDevGenerator :generator:test --no-daemon --console=plain
# Gates
pwsh -NoProfile -File scripts\quality\run-runtimehost-gate.ps1
pwsh -NoProfile -File scripts\quality\run-generator-gate.ps1
pwsh -NoProfile -File scripts\quality\run-stateful-additive-migrations-check.ps1
# Restage jars (guardrail 2) BEFORE regenerating any app
pwsh -NoProfile -File scripts\runtimehost\sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir D:\WorkSpace\NPDev\Build\runtimehost-libs
```

### 3.2 The fixture helper you must add first (used by X1, X3, X5)

The existing `manifest(...)` helper takes **14 positional parameters** (~line 1406) — error-prone,
and its callers routinely pass `Map.of()` for `businessTableAdditiveColumns`, which is what hid
X-B2. Before writing any new scenario, add this helper beside it:

```java
/**
 * Computes businessTableAdditiveColumns the way a REAL manifest does -- every column except the
 * primary key and any bond/FK column -- so a fixture can never silently disagree with production
 * about what is additive-eligible (LNCH-1 hardening X-B2b: that divergence hid a live bug).
 *
 * @param columnsByTable the same map passed as businessTableColumns
 * @param bondColumnsByTable bond/FK columns per table (usually empty)
 */
private static Map<String, List<String>> realisticAdditiveColumns(
        Map<String, List<String>> columnsByTable,
        Map<String, List<String>> bondColumnsByTable) {
    Map<String, List<String>> additive = new LinkedHashMap<>();
    for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
        List<String> bonds = bondColumnsByTable.getOrDefault(entry.getKey(), List.of());
        List<String> eligible = entry.getValue().stream()
                .filter(column -> !"id".equalsIgnoreCase(column))
                .filter(column -> !bonds.contains(column))
                .toList();
        additive.put(entry.getKey(), eligible);
    }
    return additive;
}
```

**VERIFY the eligibility rule** against `SchemaRealizationEmitter#isAdditiveEligible` /
`additiveColumnNames` before relying on it — if production excludes anything else (it may also treat
`tenant_id`/`row_version` specially), mirror that exactly and note the confirmation in the evidence
file.

---

## 4. The phases

Strictly sequential. Each phase ends with: its named tests green, the relevant gates green, **one
commit**, and an evidence note at
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\hardening-<phase>.md`.

---

### Phase X0 — Baseline, decisions, and the reproduction (S)

**Goal:** a green starting point, the owner's three answers, and a *failing* test that proves X-B1
is real before anything is changed.

**X0.1** `git status --short` must be clean. If not, STOP and report to the owner. Then run the full
baseline: RuntimeHost unit suite, DSL suite, generator suite, `run-runtimehost-gate.ps1`,
`run-generator-gate.ps1`, `run-stateful-additive-migrations-check.ps1`. Record every result —
including the two gates the previous session reported as exiting non-zero (X-G5). You need to know
which failures pre-date your work; you will re-check them in X8.

**X0.2** Ask the owner §2.5's three questions in **one** message. Record the answers by editing
§2.5 of this document in place, marking each `RATIFIED 2026-__-__: <answer>`.

**X0.3** Read the four key methods so you understand the flow before editing:
`beforeMigrate` (the authorization block ~280–341), `executeSurgicalDestruction`,
`executeWholeSchemaWipe`, `afterMigrate` + `ownedTablesJson`. Write a 10-line summary of the current
routing into the evidence note. If your summary disagrees with §1.1 above, the code has drifted —
STOP and re-plan with the owner.

**X0.4 — The reproduction test (write it now, expect it RED).** Add to
`SchemaLifecycleExecutorProofMatrixTest`:

```
Scenario 24 (X-B1): a concept drop on a blanket-authorized app must NOT destroy unrelated tables
```

Setup, using real H2:
1. Create tables `widgets` (id, name) and `gadgets` (id, label); insert 2 rows into each.
2. Seed the stored fingerprint AND the ownership record so both tables are owned. **VERIFY** how the
   ownership row is written — reuse the production writer (`afterMigrate` with an old manifest that
   declares both tables) rather than hand-inserting the JSON, so the fixture cannot drift from the
   format `readOwnedBusinessTables` expects.
3. Build a NEW manifest that declares **only** `widgets`, with `allowDestructiveRecreate = true`
   (parameter 8 of the `manifest(...)` helper) and `destructiveAcknowledgment = ""` (no token), and
   realistic additive columns per §3.2.
4. Call `executor.beforeMigrate(dataSource, newManifest)`.

Assertions:
- `gadgets` no longer exists (the dropped concept's table IS removed).
- **`widgets` still exists and still holds its 2 rows** ← this is the assertion that fails today.
- The history row's outcome is `APPLIED`.
- A second `beforeMigrate` with the same manifest is a clean no-op.

Run it. **It must fail**, with `widgets` gone. Paste the failure output into the evidence note. Only
then proceed to X1.

**DoD:** clean tree; baseline recorded; owner answers recorded; scenario 24 written and observed RED.
**Commit:** none yet (scenario 24 lands with its fix in X1).

---

### Phase X1 — Fix the critical regression (X-B1, X-G3) (M)

**Goal:** blanket-authorized destruction executes surgically; only genuinely unexplainable diffs
reach the whole-schema wipe.

**X1.1 — Re-route.** In `beforeMigrate`'s authorization block (~lines 313–340), change the two
branches per §2.1:

- `if (tokenMatches && !hasUnknown)` becomes `if (!hasUnknown)`.
- The trailing wipe call is now reached only when `hasUnknown` is true.
- Keep the refusal branch exactly as-is.
- Keep `consumePendingAcknowledgmentIfAny` gated on `tokenMatches` in **both** branches (a blanket-
  authorized pass must not consume somebody's pending acknowledgment row).
- `effectiveToken` stays `null` for blanket-only authorization, so the history row honestly records
  that no token authorized this pass.

**X1.2 — Improve the two log messages.** They are an operator's only warning:

- The blanket deprecation warning must now also list what is about to be executed, e.g.
  `"... authorized by the blanket 'destructiveAllowed' flag alone. Executing surgically: [DROP_TABLE:gadgets, DROP_COLUMN:widgets:legacy_flag]. The blanket flag is deprecated; switch to the itemized acknowledgment token (expected: <token>) -- see docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes."`
- The `hasUnknown` wipe message must state plainly that a whole-schema recreation is starting and
  that every table's data will be lost, naming the UNKNOWN item(s) that forced it.

**X1.3 — Run scenario 24.** It must now pass. Record the before/after in the evidence note.

**X1.4 — Full-suite fallout.** Run the whole RuntimeHost unit suite. Some existing tests may assert
the *old* routing (e.g. that a blanket-authorized destructive change produced a wipe). For each
failure, decide deliberately:
- If the test encoded the buggy behaviour → update it to the new expectation and add a comment
  naming X-B1 (precedent: the previous session did exactly this for the Phase-7 rename fix).
- If the test encodes something you did not intend to change → your change is wrong; re-read §2.1.
Never weaken an assertion to make it pass.

**X1.5 — Add scenario 24b:** the `UNKNOWN`-item path still wipes. Construct a report containing an
UNKNOWN item (**VERIFY** the easiest way: a manifest that expects a column which is missing live and
is *not* additive-eligible — see `SchemaDeltaReport.itemizeColumnLevelDiff`'s `Unknown` branch), with
blanket authorization, and assert the whole-schema wipe still runs. This proves you narrowed the
wipe rather than deleting it.

**DoD:** scenarios 24 and 24b green; full RuntimeHost suite green; `run-runtimehost-gate.ps1` green
(or failing only for the pre-existing reason recorded in X0.1).
**Commit:** `LNCH-1 X1: blanket-authorized destruction executes surgically, not as a whole-schema wipe`
— body must state the before/after behaviour in one paragraph, since this is an operator-visible
change.

---

### Phase X2 — Ownership is a union intersected with reality (X-B3) (S)

**Goal:** a surviving orphan stays recognisable as a dropped concept forever.

**X2.1** Rewrite `ownedTablesJson(manifest)` per §2.2. It needs live table names and the previous
set, so change the signature to
`ownedTablesJson(DataSource dataSource, DatabaseMetaData metadata, SchemaManifest manifest)` — or
compute the set in `afterMigrate` and pass the finished list in. **VERIFY** `afterMigrate` already
holds an open `Connection` at that point (~line 1953) and reuse it rather than opening a second one.

Rules, restated as code contract:
- `previous = readOwnedBusinessTables(dataSource)` — `null` means "never recorded".
- `candidate = (previous == null ? emptySet() : previous) ∪ lowercase(manifest.businessTableColumns().keySet())`
- `owned = candidate ∩ lowercase(readActualTableNames(metadata))`
- Sort, serialize as a JSON array, same as today. On any failure, fall back to today's behaviour
  (current-manifest-only) rather than writing `[]` — losing ownership is the failure mode this whole
  phase exists to prevent. **VERIFY** the current catch block returns `"[]"` (~line 2006) and change
  it accordingly.

**X2.2 — Tests** (add to the proof matrix):
- **Scenario 25:** boot v1 (owns `widgets`, `gadgets`) → boot v2 which drops `gadgets` but takes the
  `UNKNOWN` wipe path so `gadgets` survives → assert the ownership record **still contains
  `gadgets`** → boot v3 with a valid token → assert `gadgets` is now surgically dropped. Without the
  fix, v3 cannot see the orphan at all.
- **Scenario 25b:** a hand-created table (`CREATE TABLE scratch_notes(...)`, never in any manifest)
  is never added to the ownership set across three boots, and is never itemized as a `DROP_TABLE`.
  This is the safety property the whole ownership mechanism exists for — pin it.

**DoD:** scenarios 25/25b green; full suite green.
**Commit:** `LNCH-1 X2: ownership set is a union intersected with live tables (orphans stay recognisable)`

---

### Phase X3 — Make the schema-ahead detector actually fire (X-B2, X-B2b) (M)

**Goal:** an old jar redeployed against a newer build's schema refuses, for the ordinary-field
rename case that real apps actually hit.

**X3.1 — Add the realistic fixture helper** from §3.2, and **VERIFY** the eligibility rule against
`SchemaRealizationEmitter#additiveColumnNames` / `isAdditiveEligible` first. Note the confirmed rule
in the evidence file.

**X3.2 — Prove the current detector is dead.** *Before* changing production code, rewrite scenario
21's fixture to use `realisticAdditiveColumns(...)` instead of `Map.of("widgets", List.of())`. Run
it. **It must now fail** (no refusal thrown) — that is X-B2 reproduced. Record the failure output.

**X3.3 — Implement Trigger B** in `findSchemaAheadMissingColumns` per §2.3. Suggested shape:

```java
for each table in manifest.businessTableColumns():
    live            = readActualColumns(metadata, table)          // lower-cased
    if live.isEmpty(): continue                                    // table absent entirely -> X3.4
    declared        = lower-cased manifest columns for this table
    additiveOk      = lower-cased manifest additive columns for this table
    renameOldNames  = lower-cased values of manifest.businessTableRenamedColumns().get(table)
    platformColumns = <the reserved set, VERIFIED against the emitter>

    missingColumns  = declared - live
    extraColumns    = live - declared - platformColumns - renameOldNames

    for column in missingColumns:
        if !additiveOk.contains(column):        -> report (Trigger A)
        else if !extraColumns.isEmpty():        -> report (Trigger B)
```

Keep the `catch (SQLException) -> return List.of()` behaviour: this guard must never turn a healthy
boot into a refusal on a transient introspection hiccup.

**X3.4 — Missing table.** If a manifest-declared table has **no** live columns at all, `readActualColumns`
returns empty. Today the loop then reports every column (Trigger A) or nothing (Trigger B), depending
on eligibility — neither is a clear message. Handle it explicitly: report a single entry
`"<table> (entire table missing)"` and `continue`. **But first VERIFY** this cannot false-positive on
a legitimate first boot — the detector only runs on the fingerprint-MATCH path, where every table
should already exist; add a scenario for a matched fingerprint with a legitimately absent table if
one is reachable, otherwise note in the evidence file why it is not.

**X3.5 — Update the javadoc.** The current block (~1808–1815) explains why additive columns are
excluded; it must now explain the two-trigger design and why Trigger B does not fire in direct-call
unit tests (no extra live columns present).

**X3.6 — Tests:**
- Scenario 21 (rewritten fixture, from X3.2) now **passes** — the refusal fires via Trigger B.
- **Scenario 21b:** the unit-test-shaped case — manifest declares an additive column that was never
  physically added, and there are **no** extra live columns → **no** refusal (proves you did not
  re-break the suite).
- **Scenario 21c:** a bond/FK column missing → refusal via Trigger A (proves the original trigger
  survives).
- **Scenario 21d:** entire table missing → refusal with the "entire table missing" message.

**X3.7 — Docs.** In `docs/SCHEMA_EVOLUTION.md#refusals-and-rollback`, replace any claim that the
detector catches all schema-ahead states with an accurate description of the two triggers, plus the
documented residual limitation from §2.3 (a pure column *drop* by a newer build leaves no trace the
detector can see).

**DoD:** scenarios 21, 21b, 21c, 21d green; full RuntimeHost suite green; the X3.2 red run recorded.
**Commit:** `LNCH-1 X3: make the schema-ahead detector fire for ordinary renamed columns (two triggers)`

---

### Phase X4 — Resolve the blanket-flag default contradiction (X-I4) (S/M — depends on owner answers)

Execute only what §2.5's ratified answers authorize.

**X4.1 (if question 1 = yes).** Find where the default is produced for **new** app definitions —
**VERIFY** by Grepping for `allowDestructiveRecreate` across `NPDevGenerator`, `scripts/appgen`, the
authoring schema (`schemas/ai/user-db-definition.schema.json`), and the app-definition templates/docs
(`D:\WorkSpace\NPDev\AppGen\apps\APP_DEFINITION_FORMAT.md`). Change **only the default for newly
authored definitions** — do NOT mass-edit the 30+ existing app definitions in layer 2, and do NOT
change how the executor reads the flag.

**X4.2** Update `docs/SCHEMA_EVOLUTION.md`'s "deprecated escape hatch" paragraph to describe reality:
what the flag now defaults to, what it does when true (**surgical execution after X1**, whole wipe
only on UNKNOWN items), and the recommended migration (set it false, use `-PlanOnly` +
`-AcknowledgeDestructive`).

**X4.3** Add a boot-time nudge: when the blanket flag is `true` **and** no token was supplied, the
existing deprecation warning (improved in X1.2) is already printed. Additionally log a one-line
notice at **every** boot of an app whose manifest has the flag true — even when nothing destructive
is happening — so the posture is visible before the day it matters. Keep it to one line; **VERIFY**
it does not spam the proof-matrix test output to the point of obscuring failures.

**X4.4 (if question 2 = yes).** Refuse blanket-only authorization for `DROP_TABLE` items: in the
authorization block, if `!tokenMatches && blanket && report contains a DropTable`, refuse with the
standard itemized message plus an explanation that concept drops require an explicit token. Add
scenario 26 covering it, and update scenario 24 (which relies on blanket-authorized concept drop) to
supply a token instead. **If question 2 = no, skip X4.4 entirely and note the deferral.**

**DoD:** whatever was ratified is implemented and tested; docs match the shipped default exactly.
**Commit:** `LNCH-1 X4: align the destructiveAllowed default and docs with the post-X1 behaviour`

---

### Phase X5 — Mirror the proof matrix into the Postgres twin (X-G1) (M)

**Goal:** the Postgres leg can go green in CI, covering the same scenarios as H2.

**X5.1** Locate the twin (Glob `**/SchemaLifecycleExecutorPostgresProofMatrixTest.java`) and read it
end to end — note how it obtains its Testcontainers `DataSource`, how it seeds fingerprints, and how
its own fixture helper differs from the H2 one.

**X5.2** Mirror these scenarios: **17, 18, 18b, 19, 21 (+21b/21c/21d), 22, 24, 24b, 25, 25b**. Where
a scenario is engine-independent and the twin has no reason to differ, prefer extracting the shared
body rather than copy-pasting — **but only if** the two classes can share a helper without contorting
the Testcontainers lifecycle. If sharing is awkward, copy and add a comment on both copies naming the
other, so a future edit finds its twin. Copy-paste that nobody can find again is the worse outcome.

**X5.3** Apply the R2 `afterMigrate` call-site change to the twin (the previous session fixed
`requiredFieldBackfillOnPostgres` but did not sweep the rest) — **VERIFY** every twin test that calls
`afterMigrate` uses the correct arity/overload.

**X5.4** Watch for genuine engine differences and do **not** paper over them: identifier case folding
(H2 upper vs Postgres lower), `DROP COLUMN` with dependent constraints, index-vs-constraint namespace
collisions (the class of bug commit `5339671` fixed). If a scenario needs different SQL or different
assertions on Postgres, that is a finding worth its own note — record it in the evidence file.

**X5.5** Run `integrationTest` with Docker up. Expect the 10 unrelated Spring-context IT failures the
previous session documented (`JwtAuthExternalBetaIT`, `PublicationRollbackE2EIT`, `TenantIsolationE2EIT`
— all needing an externally-configured Postgres). Confirm they are unchanged and unrelated; do not
chase them here.

**DoD:** the twin runs all mirrored scenarios green under Docker; unrelated pre-existing IT failures
documented as unchanged.
**Commit:** `LNCH-1 X5: mirror the remediation + hardening scenarios into the Postgres twin`

---

### Phase X6 — The live rehearsal that was never done (X-G2, X-I3) (M)

**Goal:** meet the bar the previous plan set and the previous session could not: a real Postgres
stack, real data, the **ControlPanel pre-authorization channel**, for a **concept drop**.

**Prerequisite:** Docker must be running. If it is not, STOP, do X7/X8, and report X6 as explicitly
deferred — do not substitute an H2 run and call it done.

**X6.1 — Prepare.** Use the app from §2.5 answer 3. Restage jars (guardrail 2), regenerate, bring up
the Compose stack per `docs/DEPLOYMENT.md`. Register a real tenant (guardrail 6) and seed at least 3
rows into two concepts (one to be dropped, one to survive) over REST. Record every command and its
output.

**X6.2 — Preview.** Stop the app (the FinalApp output directory is wiped on every build — see
`docs/architecture/APP_UPGRADE_CONTRACT.md`). Edit the model to drop one concept **and** add a
required field with a literal default to the surviving concept. Run
`Build-NpdevApp.ps1 -AppFolder <app> -Upgrade -PlanOnly`. Assert:
- The plan lists the `DROP_TABLE` as destructive and the new field as safe.
- The exit code is non-zero (destructive items present).
- A token is printed.

**X6.3 — Pre-authorize via ControlPanel.** Restart the **old** app, then
`POST /api/admin/schema-migration/acknowledge` with `{toFingerprint, ackToken}` from the plan, using
the `X-Super-User-Key` header. Confirm via `GET /api/admin/schema-migration/pending`. **This is the
channel that has never been exercised end to end — it is the point of this phase.**

**X6.4 — Deploy and verify.** Build and boot the new app **without** passing
`-AcknowledgeDestructive` (the pending row must be what authorizes it). Assert from the boot log and
over REST:
- The boot succeeds on the **first attempt** (no refusal).
- The log line names "via a ControlPanel pending acknowledgment".
- The dropped concept's table is gone (its REST endpoint 404s).
- The surviving concept keeps all its rows, each carrying the new field's literal default, and the
  column is NOT NULL (attempt a null write; expect rejection).
- A pre-drop snapshot exists under `runtime-data/schema-snapshot-before-drop/`.
- The pending acknowledgment row was consumed.
- A second boot is a clean no-op.

**X6.5 — Re-capture the docs.** Replace `docs/SCHEMA_EVOLUTION.md`'s worked-example log block with
the **real** log lines from this run (X-I3), and update the surrounding prose if any detail differs.

**DoD:** all X6.4 assertions observed and pasted into
`hardening-X6.md`; docs log block re-captured from the real run.
**Commit:** `LNCH-1 X6: live Postgres rehearsal of the ControlPanel pre-authorization concept-drop flow`

---

### Phase X7 — Reconcile the record (X-I1, X-I2) (S)

**Goal:** future sessions can trust the documents.

**X7.1** In `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md`'s status header, correct the stale "Postgres
Testcontainers legs … were NOT run" note: they were later run green (12 tests), and X5/X6 update the
picture again. State the current truth with its date.

**X7.2** Append a short, dated "Verification ledger" section to
`D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\remediation-R0-R8.md` (or a new
`hardening-verification-ledger.md` that links to it) listing, per claim: **VERIFIED LIVE** /
**VERIFIED BY SUITE** / **NOT VERIFIED**. This is the artifact that resolves X-I1 — the previous
summary and evidence file disagreed, and there was no single place to look.

**X7.3** Update `docs/LAUNCH_READINESS_GAPS.md`'s LNCH-1 entry: it currently reads DONE with no
mention of the hardening round. State that LNCH-1 core is DONE, that a hardening round followed
(link this document), and — if X6 was deferred — say so explicitly rather than leaving an implied
"fully rehearsed".

**X7.4** `CHANGELOG.md` under `[Unreleased]`: one line each for the X1 routing change (operator-
visible), the X3 detector change, and the X4 default change if it happened. **Do not cut a release
tag** — the owner has deferred tagging repeatedly.

**DoD:** no document contradicts another; the verification ledger exists.
**Commit:** `LNCH-1 X7: reconcile the LNCH-1 verification record across plan, ledger, and changelog`

---

### Phase X8 — Close the small gaps (X-G4, X-G5) (S/M, timeboxed)

**X8.1 (X-G4) — The R4 crash variant.** Write the freeze-thread crash test for the new step-pass
history rows: interrupt mid-rename (imitate the existing technique — **VERIFY** by reading
`SchemaLifecycleExecutorDestructiveCrashRecoveryTest` and the forEach durability test that
established the pattern), then assert (a) a `COLUMN_RENAME` history row is left at `PARTIAL-CRASH`,
(b) a fresh executor on the next boot converges, and (c) the retry's own row ends `APPLIED` — with
no duplicated DDL.

**X8.2 (X-G5) — Independently re-verify the two failing gates.** Run
`run-runtimehost-gate.ps1` and the generator suite. For each failure: read the produced report,
identify the failing check, and determine whether any file *you* touched appears in it. Then either
(a) fix it if it is yours, (b) confirm and document it as pre-existing with the specific evidence
(report path + the check name + why it is unrelated), or (c) if it is a flake, run it in isolation
three times and record the pass/fail ratio. **Timebox: one hour of investigation per gate**, then
write down what you know and stop. Do not open an unbounded audit.

**DoD:** crash variant green; both gates' statuses independently explained with evidence, not
inherited claims.
**Commits:** one per item.

---

### Phase X9 — Full regression and closure (S/M)

**X9.1** Run everything: DSL, generator, RuntimeHost unit suite, Postgres `integrationTest` (Docker),
`run-runtimehost-gate.ps1`, `run-generator-gate.ps1`, `run-stateful-additive-migrations-check.ps1`,
`run-app-upgrade-contract-gate.ps1`. Compare against the X0.1 baseline; **every** delta must be
explained in the evidence note.

**X9.2** Knowledge loop: review `knowledge/cards/` for any card describing the blanket flag, the
destructive routing, or the rollback/refusal behaviour — update anything X1/X3/X4 changed. Rebuild:
`python scripts/ai/build_knowledge.py`. If `docs/OPEN_GAPS_AND_ROADMAP.md` gained rows (e.g. the
deferred X4.4, the X3 residual limitation), regenerate the derived projection with
`python scripts/ai/extract_platform_status.py` — never hand-edit `knowledge/platform-status.json`.

**X9.3** Write the final evidence note `hardening-final.md`: what was fixed, what was verified live
vs by suite, what remains open. **Mirror that honesty in your closing summary to the owner** —
matching the evidence file exactly, with no upgrade in confidence between the two (X-I1).

**X9.4** `git status` clean. No release tag.

**DoD:** all suites green or explained; records updated; summary matches evidence.

---

## 5. Sequencing and the minimum bar

```
X0 (S) → X1 (M) → X2 (S) → X3 (M) → X4 (S/M) → X5 (M) → X6 (M) → X7 (S) → X8 (S/M) → X9 (S/M)
```

Sequential. X1 must land before X2 (X2's scenario 25 depends on X1's routing), and X3 must land
before X5 (X5 mirrors X3's scenarios). X6 requires Docker.

**If the effort must be cut short, the non-negotiable core is X0 → X1 → X2 → X3.** X1 is an active
data-destruction regression on the default configuration of every shipped app; X3 restores a safety
net that is currently inert; X2 prevents the cleanup path from being permanently lost. X5–X6 are what
earn the word "verified"; X7–X9 are record-keeping and small gaps. If you stop early, say exactly
where you stopped and which findings remain open — in both the evidence file and your summary.

---

*Companion documents: `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` (original 9-phase implementation, guardrails)
· `docs/LNCH1_REMEDIATION_PLAN.md` (the R0–R9 round this hardens) · `docs/SCHEMA_EVOLUTION.md`
(user-facing contract) · `..\NPDev_General__OutsideRepo\lnch1-evidence\` (evidence trail).*
