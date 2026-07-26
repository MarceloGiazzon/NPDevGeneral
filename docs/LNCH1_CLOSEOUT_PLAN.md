# LNCH-1 Closeout Plan — The Last Six Findings

> **STATUS: HISTORICAL** — last changed 2026-07-20; its completion state has **not** been re-verified. Treat nothing here as an open commitment: check `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (authoritative) or `docs/OPEN_ITEMS_SNAPSHOT.md` before acting on any item.


> **Status:** APPROVED PLAN — not started
> **Written:** 2026-07-20, verified against the working tree at commit `0d96cf9` (branch `beta1-vision-spine`)
> **Origin:** an independent review of the completed LNCH-1 **hardening** round
> (`docs/LNCH1_HARDENING_PLAN.md`, phases X0–X9, 11 commits) confirmed every hardening fix landed as
> designed, and found **1 high-severity authorization hole, 2 medium drift risks, and 3 low
> record/hygiene defects**. This plan closes all six, plus the open `LNCH-1-B8` tooling bug.
> **Audience:** an AI implementation session (or human) that has NOT read this project's history.
> Follow it literally, in order. Where it says **VERIFY**, check the real code before writing any —
> line numbers are from `0d96cf9` and may drift.
>
> **Read before touching code, in this order:**
> 1. This document, end to end.
> 2. `D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md`
>    — **the tiebreaker document.** If anything else disagrees with it, it wins.
> 3. `docs/LNCH1_HARDENING_PLAN.md` §2 (design decisions — still binding) and §3 (guardrails — ALL
>    still binding).
> 4. `docs/SCHEMA_EVOLUTION.md` — the user-facing contract, especially "The deprecated blanket flag".
> 5. `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` §1 (map of the machinery) if you have not worked in this
>    subsystem before.
>
> **Corrections to earlier documents, established by this review — do not re-inherit the mistakes:**
> - Earlier plans said the blanket destructive posture is on in *"every shipped app definition."*
>   **That is wrong.** `SchemaManifest#destructiveAllowed` requires **all four** `schemaLifecycle`
>   fields to line up (see §2.1). Verified count at `0d96cf9`: **15** definitions under
>   `NPDevSamples` and **18** under `D:\WorkSpace\NPDev\AppGen\apps` carry the full posture —
>   including every `_official` app (WmsOffice, WordLab, AuxScreen, Pigmentampa, Claude). Apps like
>   `canonical-demo` and the InMemory samples use `RecreateOnAppStart` +
>   `NpdevOwnedLogicalStoresOnly` and do **not** have it. Dominant, not universal.
> - `docs/LNCH1_HARDENING_PLAN.md` §3.1's commands were wrong (`gradlew -p NPDevRuntimeHost test` —
>   RuntimeHost is a **template**, not a buildable subproject) and its §3.2 fixture helper disagreed
>   with the real additive-eligibility rule. The corrected versions are in
>   `lnch1-evidence\hardening-X0.md`. **Use those, and §3.1 below.**

---

## 0. Findings → phase map

| # | Severity | Finding | Fixed in |
|---|---|---|---|
| **C-B1** | **HIGH** | The whole-schema wipe (the UNKNOWN-item path) still executes under blanket authorization with **no itemized token** — destroying *every* table's data. X4.4 just established that destroying *one* table's data requires a token. The most destructive operation in the system has the weakest authorization requirement, which inverts the principle the previous round set. | C1 |
| **C-D1** | MEDIUM | `PLATFORM_MANAGED_COLUMNS` in `SchemaLifecycleExecutor` is a deliberate hand-copy of `SchemaRealizationEmitter#fullColumnNames`' platform columns, with **no conformance test**. If the emitter ever appends a fifth platform column, Trigger B treats it as an "unexplained extra" and can refuse a **healthy** boot. Same drift class as the four `model.schema.json` copies — which have a conformance test *because* they drifted twice. | C2 |
| **C-D2** | MEDIUM | `docs/SCHEMA_EVOLUTION.md` says "**Default for new apps: OFF**", but nothing defaults or enforces it: 33 shipped definitions demonstrate the opposite posture, and those are exactly the examples the AI authoring loop's RAG corpus learns from. The guidance will lose to the examples. | C3 |
| **C-B2** | MEDIUM | `LNCH-1-B8` (already recorded, OPEN): a failed `-Upgrade` destroys the previous compiled model, so the next `-PlanOnly` silently reports "Fresh install — no previous compiled model to diff against" instead of erroring. A wrong plan presented as a valid one. | C4 |
| **C-R1** | LOW | Scenario 24's `@DisplayName` says "authorized by the blanket flag **alone**" but the test supplies a token (X4.4 forced that). The inline comment is honest; the name is not. Anyone scanning test names — or grepping for coverage — gets the wrong picture. | C5 |
| **C-R2** | LOW | The verification ledger contradicts itself on X-I3: its "Earlier rounds" table says the worked-example log block was NOT re-captured and "X-I3 remains open", while its "Outstanding" table marks X-I3 CLOSED. The file whose whole job is to be the tiebreaker must not disagree with itself. | C5 |
| **C-R3** | LOW | `D:\WorkSpace\NPDev\AppGen\apps\lnch1-rehearsal\definition` was changed from H2Local to Postgres and left in its **v2** state (Project dropped, User.department added) with no note. That is a layer-2 source-of-truth change; the next session will assume the original premise. | C5 |

**Explicitly NOT in scope (boundaries — record only, do not build):**
- `LNCH-1-B9` — the schema-ahead detector cannot see a pure column *drop* by a newer build (no
  residue exists to detect). Already documented WONTFIX for v1.
- `LNCH-1-B6` — multi-instance migration advisory lock.
- The 10 `ApplicationContext`-load `integrationTest` failures needing an externally-configured
  Postgres (`JwtAuthExternalBetaIT` ×8, `PublicationRollbackE2EIT`, `TenantIsolationE2EIT`) — not
  schema-lifecycle, unchanged across three rounds. C7 only re-confirms the attribution.

---

## 1. Orientation

| File | What matters here |
|---|---|
| `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java` (≈140 KB — **never full-read**; Grep to a method, then Read with `offset`/`limit`) | The authorization block (~lines 300–430: the refusal, the X4.4 `DROP_TABLE` gate ~338–367, the surgical branch ~386–408, the UNKNOWN wipe ~410–430). `PLATFORM_MANAGED_COLUMNS` (~line 57). `findSchemaAheadMissingColumns` (~1934). `ownedTablesJson` (~2178). `afterMigrate` (~2090). |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java` (≈197 KB — **never full-read**) | `fullColumnNames` (~line 627) — appends `id` (when the concept declares none), then `version`, `row_version`, `tenant_id`. This is the set C2 pins. `additiveColumnNames` (~653). |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorProofMatrixTest.java` | The H2 proof matrix (37 tests). Scenarios 24/24b (~1303–1394), 26 (~1504). Fixture helpers `realisticAdditiveColumns`, `seedTwoRealisticConceptsWithData` (~1542), `realisticConceptDropManifest`. |
| `NPDevRuntimeHost/src/test/java/com/finalexec/db/SchemaLifecycleExecutorPostgresProofMatrixTest.java` | The Postgres twin (24 tests, `@Tag("integration")`, Docker-gated). |
| `NPDevGenerator/generator/src/main/java/com/npdev/generator/schemaevolution/MigrationPlanEmitter.java` | C4's subject: where the previous compiled model is read for the diff. |
| `scripts/appgen/Build-NpdevApp.ps1` | C4's other half: `-Upgrade` / `-PlanOnly` capture the previous compiled model **before** the output-directory wipe. |
| `docs/SCHEMA_EVOLUTION.md` | The contract. "The deprecated blanket flag" section (~lines 301–337) is rewritten by C1 and C3. |

### 1.1 The authorization flow you are changing (current, `0d96cf9`)

```
  report        = SchemaDeltaReport.generate()
  expectedToken = DestructiveAckToken.compute(fingerprint, report.stableStrings())
  tokenMatches  = static manifest token OR pending ControlPanel ack row
  hasUnknown    = report contains an UNKNOWN item
  blanket       = manifest.destructiveAllowed()      // all FOUR schemaLifecycle fields, see 2.1

  if !tokenMatches && !blanket        -> REFUSE                              [correct]
  if !tokenMatches && report has DROP_TABLE -> REFUSE (X4.4)                 [correct]
  if !hasUnknown                      -> executeSurgicalDestruction()        [correct, X1]
  else                                -> executeWholeSchemaWipe()   <---- C-B1 IS HERE
                                          drops EVERY manifest table.
                                          Reachable with blanket alone, no token.
```

**Why C-B1 is reachable in practice.** An `UNKNOWN` item arises whenever a manifest column is
missing live, is not additive-eligible, and is not explained by a declared rename — for example the
platform `version` column (the one column a real manifest declares but never marks additive; this is
exactly how proof-matrix scenario 24b constructs its UNKNOWN). Scenario 24b only needed a token
because its report *also* carried a `DROP_TABLE`. Remove the concept drop and the identical database
state wipes every table on a blanket-posture app with no token at all.

---

## 2. Design decisions — already made, do NOT re-derive

If you believe one is wrong, STOP and ask the owner. Do not silently substitute your own.

### 2.1 What `destructiveAllowed` actually means (know this before you touch C1 or C3)

`SchemaManifest#destructiveAllowed` is **true only when all four** `schemaLifecycle` fields line up
(**VERIFY** in `SchemaLifecycleExecutor`'s manifest loader / `SchemaManifest` constructor):

```
strategy                        == "DropAndRecreateOnStructureChange"
allowDestructiveRecreate        == true
scope                           == "NpdevOwnedTablesOnly"
destructiveRecreateConfirmation == "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED"
```

A definition with `allowDestructiveRecreate: true` but `strategy: RecreateOnAppStart` and
`scope: NpdevOwnedLogicalStoresOnly` (e.g. `canonical-demo`, the InMemory samples) does **not** have
the posture. Never infer the posture from the boolean alone — that mistake produced a wrong claim in
two earlier plans.

### 2.2 (C-B1) The whole-schema wipe requires an itemized token, always

Extend X4.4's principle to its logical end: **any pass that will destroy an entire table's data
requires the itemized token.** The wipe destroys *every* table, so it qualifies a fortiori.

New routing:

```
  if !tokenMatches && !blanket              -> REFUSE                     [unchanged]
  if !tokenMatches && report has DROP_TABLE -> REFUSE (X4.4)              [unchanged]
  if !tokenMatches && hasUnknown            -> REFUSE (C1, new)           <---- the fix
  if !hasUnknown                            -> executeSurgicalDestruction()
  else                                      -> executeWholeSchemaWipe()   [token-authorized only]
```

Implementation note: the cleanest expression is to **merge the X4.4 gate and the new gate into one
block** that computes the reason(s) a token is mandatory (`DROP_TABLE` items present, and/or the
report is unexplainable) and refuses once with a message naming which applies. Do not add a third
near-duplicate `if (!tokenMatches)` block — three sequential refusal branches with overlapping
conditions is how this method becomes unreadable.

The refusal message must state plainly what would otherwise happen: *"this change cannot be executed
item by item, so proceeding would drop and recreate EVERY table in this app, destroying all data. That
requires an explicit itemized acknowledgment token — the blanket 'destructiveAllowed' flag does not
authorize it."* Include the itemized report, the UNKNOWN items specifically, the expected token, both
submission channels, and the `#acknowledging-destructive-changes` anchor. Write a `REFUSED` history
row first, exactly as the sibling refusals do.

**After this change the blanket flag authorizes exactly two things:** a `DROP_COLUMN` and a
`NARROW_TYPE`, both executed surgically. Everything else needs a token. Say that in the docs table.

### 2.3 (C-B1) Preserve a deliberate dev/CI escape hatch — but a separate, honest one

Requiring a token for the wipe removes the "just recreate everything on boot" behaviour some
dev/CI loops may rely on. **Do not** solve that by weakening C1. The correct answers, in order of
preference, are: (a) delete the database/volume between runs (what dev actually wants); (b) use a
`freshdb`-style app definition (several already exist, e.g. `simple-user-registry-h2local-freshdb`);
(c) `strategy: RecreateOnAppStart`, which is a different mechanism from this one.

**VERIFY before C1 lands** that `RecreateOnAppStart` genuinely does not route through
`beforeMigrate`'s destructive branch (trace what `strategy` does to `destructiveAllowed` and to
schema realization). If it *does*, report that to the owner as a finding before proceeding —
it would mean C1 changes behaviour for a strategy nobody intended it to touch.

### 2.4 (C-D1) Pin the platform-column set with a test, do not "fix" the duplication

The duplication is justified: the RuntimeHost template cannot depend on the generator module. Keep
both copies; add a test that **fails loudly** when they diverge. Put it on the **generator** side
(which can read files anywhere in the workspace, and where `MigrationAuthorityQuarantineAssertions`
already establishes the "assert against a path in the repo" precedent):

- Compute the platform columns the emitter appends by calling the real code path — build a tiny
  `CompiledModel` with one concept that declares no id field, run `computeBusinessTableMetadata`, and
  take its columns minus the concept's own declared fields. **VERIFY** this is reachable via the
  public `computeBusinessTableMetadata` (it is public per its javadoc) rather than reflecting into
  the private `fullColumnNames`.
- Read `SchemaLifecycleExecutor.java` as **text**, extract the `PLATFORM_MANAGED_COLUMNS` literal
  (a `Set.of("...", ...)` on one or two lines — parse with a small regex over the source file, the
  way the quarantine assertions parse paths), and assert set equality.
- The failure message must say exactly what to do: *"SchemaRealizationEmitter now emits platform
  column 'X'. Add it to SchemaLifecycleExecutor.PLATFORM_MANAGED_COLUMNS or Trigger B will treat it
  as an unexplained extra column and refuse healthy boots."*

### 2.5 (C-D2) Make the corpus teach the recommended posture

Docs alone cannot beat 33 worked examples. Flip a **small, deliberate** set of definitions to the
safe posture so the corpus demonstrates it, and leave the rest alone (mass-editing every app remains
out of scope — see §0 and the hardening plan's own scoping note):

- The apps to flip come from the owner (§2.6 question 2). Recommended default: the three
  `simple-user-registry-*` apps that the gates and tutorial actually exercise, plus
  `NPDevSamples\12works\gift-idea-tracker` (the sample most often used for browser verification).
- Flipping means: `strategy: "KeepExistingIfCompatible"`, `allowDestructiveRecreate: false`. **VERIFY**
  what `destructiveRecreateConfirmation` and `scope` must be under that strategy — the AI authoring
  schema (`schemas/ai/user-db-definition.schema.json`) only constrains those two fields when strategy
  is `DropAndRecreateOnStructureChange`, so confirm the generator accepts (and the validator does not
  reject) the combination you write, by regenerating one of the flipped apps end to end.
- Add a knowledge card so the AI loop states the posture explicitly rather than inferring it from
  examples. **VERIFY** whether the existing card
  `knowledge/cards/schema-evolution-declare-rename-and-acknowledge-drop.json` already covers it and
  extend that instead of adding a near-duplicate.

**Do not** add a hard validator error for the blanket posture — 33 existing definitions would fail
their own gates. A `SemanticValidator`/startup **warning** is acceptable if it is cheap; ask the
owner (§2.6 question 3) before adding one.

### 2.6 Questions for the owner — ASK ALL AT ONCE IN C0, DO NOT GUESS

1. **(C1)** Confirm: the whole-schema wipe should require the itemized token even on a blanket-posture
   app, making a refusal the outcome for an unexplainable diff. *Recommendation: yes — a refusal is
   fail-safe, prints the token, and the operator can proceed deliberately.*
   **RATIFIED 2026-07-20: YES** — require the itemized token. After C1 the blanket flag authorizes
   exactly two things: a surgical `DROP_COLUMN` and a `NARROW_TYPE`.
2. **(C3)** Which definitions should flip to `KeepExistingIfCompatible` + `allowDestructiveRecreate:
   false`? *Recommendation: `simple-user-registry-h2local`, `simple-user-registry-postgres`,
   `simple-product-h2local`, and `NPDevSamples\12works\gift-idea-tracker`.* Note that flipping an
   app changes how **its own** future upgrades behave.
   **RATIFIED 2026-07-20: `simple-user-registry-h2local` ONLY** — narrower than the recommendation.
   One worked example, regenerated and booted end to end, plus the docs and knowledge card. The other
   three stay on their current posture.
3. **(C3)** Should authoring a blanket posture produce a **warning** (validator or startup), or is the
   existing per-boot `NOTICE` enough? *Recommendation: the NOTICE is enough; skip the warning.*
   **RATIFIED 2026-07-20: NO warning** — the per-boot `NOTICE` is enough. C3.5 is deferred, not built.
4. **(C5)** May the `lnch1-rehearsal` app definition stay in its v2/Postgres state (documented), or
   should it be restored to H2Local/v1? *Recommendation: keep v2 + add a README — it is a working
   Postgres upgrade fixture, which is scarce and useful.*
   **RATIFIED 2026-07-20: KEEP v2/Postgres + add the README** per C5.3.

### 2.7 Plan defects found during C0's VERIFY step (recorded, per guardrail 1)

- **§2.3's escape-hatch option (c) is wrong — do NOT propagate it into the docs.** `RecreateOnAppStart`
  is *inert at runtime*. The manifest's `strategy` string is read in exactly one place in
  `SchemaLifecycleExecutor` — `destructiveAllowed()` (line 2672) — and on the generator side it is only
  serialized into the manifest, folded into the fingerprint, and validated
  (`UserDatabaseDefinitionLoader` line 114). Nothing recreates anything "on app start". So it is true
  that C1 does not change `RecreateOnAppStart` behaviour (the §2.3 VERIFY passes), but it is **not** a
  usable escape hatch — it behaves identically to `KeepExistingIfCompatible`. C1.5's docs must offer
  only options (a) delete the database/volume and (b) a `freshdb`-style definition.
- **§2.5's C3 VERIFY resolved:** `UserDatabaseDefinitionLoader#validate` (line 114) constrains
  `destructiveRecreateConfirmation` and `scope` **only** when strategy is
  `DropAndRecreateOnStructureChange`. `KeepExistingIfCompatible` + `allowDestructiveRecreate: false`
  is therefore accepted with any scope/confirmation. Confirmed by reading; still to be confirmed by a
  real regeneration in C3.1.

---

## 3. Guardrails

Everything in `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` §3 still applies. The ones that matter most here:

1. **Never claim a verification you did not run.** Docker may be down (it stopped twice on its own
   during the last session). If a Postgres leg cannot run, write "NOT RUN — Docker unavailable" in
   the evidence file **and** in your closing summary, and add a row to the verification ledger. The
   ledger is the tiebreaker; keep it true.
2. **Prove each bug-fix test can detect the bug.** For C1 and C4, run the new test against the
   unfixed code first, observe it red, paste the failure into the evidence note, then fix.
3. **Restage jars after any RuntimeHost change, before regenerating an app:**
   `scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir <dir>` — and
   pass the **same** `-RuntimeHostLibsDir` to `Build-NpdevApp.ps1`, or the app keeps a stale jar.
   Prefer the `rebuild-app` skill.
4. **`:generator:test` does not run `:dsl` tests.** Run each module explicitly.
5. **Never hand-edit a generated app's `npdev-generated/` tree** (SHA-256 hash-guarded).
6. **Build output → `D:\WorkSpace\NPDev\Build`. Evidence → `...__OutsideRepo\lnch1-evidence\closeout-<phase>.md`.**
   Never inside the repo.
7. **Never use tenant id `"default"`** in REST verification — reserved sentinel, silently 403s.
8. **Layer-2 edits (`AppGen\apps\*`, `NPDevSamples\*`) are source of truth for app definitions** —
   changing one is a real change, not scratch work. Record every one in the evidence note (this is
   what C-R3 exists to fix).
9. **Small bounded commits**, `LNCH-1 C<n>: <what>` + a `Verified:` line naming what actually ran.
   **No `git add .`.** No regex-patching of Java.
10. **A test's `@DisplayName` is documentation.** If you change what a test does, change its name in
    the same commit (this is C-R1's whole lesson).

### 3.1 Commands that actually work in this repo (the hardening plan's §3.1 was wrong)

RuntimeHost is a **template**, not a buildable subproject. The RuntimeHost tests run inside an
**assembled sample app**. **VERIFY the exact incantation in `lnch1-evidence\hardening-X0.md`, which
records the corrected commands, and re-confirm against `scripts/quality/run-runtimehost-gate.ps1`.**
The shape used by the previous session was:

```powershell
# Assembled test app (built once, outside the repo per policy)
#   D:\WorkSpace\NPDev\Build\lnch1-harden-app
# Run its suite with the staged libs:
#   .\gradlew.bat test -PnpdevRuntimeHostLibsDir=D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\runtimehost-libs
# Postgres twin (Docker required):
#   .\gradlew.bat integrationTest -PnpdevRuntimeHostLibsDir=<same>

# Repo-level suites and gates (these are correct as written):
.\gradlew.bat :NPDevContract:dsl:test --no-daemon --console=plain
.\gradlew.bat -p NPDevGenerator :generator:test --no-daemon --console=plain
pwsh -NoProfile -File scripts\quality\run-runtimehost-gate.ps1
pwsh -NoProfile -File scripts\quality\run-generator-gate.ps1
pwsh -NoProfile -File scripts\quality\run-stateful-additive-migrations-check.ps1
```

If the assembled-app path no longer exists, rebuild it the way `hardening-X0.md` describes; do not
invent a new location inside the repo.

---

## 4. The phases

Sequential. Each ends with: named tests green, relevant gates green (or failing only for a
baseline-recorded reason), **one commit**, and an evidence note at
`...__OutsideRepo\lnch1-evidence\closeout-<phase>.md`.

---

### Phase C0 — Baseline, decisions, and the C1 reproduction (S)

**C0.1** `git status --short` must be clean. If not, STOP and report. Run and record the full
baseline: assembled-app RuntimeHost suite, Postgres twin (if Docker is up), DSL, generator, and the
three gates. You must know which failures pre-date your work — the expected pre-existing set is:
the RuntimeHost gate's observability-report step (fails *after* `:test` reports BUILD SUCCESSFUL),
`SandboxedPluginExecutionEngineTest` (load-dependent flake, green in isolation), and 10
`ApplicationContext` `integrationTest` failures needing an external Postgres.

**C0.2** Ask the owner §2.6's **four** questions in one message. Record the answers by editing §2.6
in place, marking each `RATIFIED 2026-__-__: <answer>`.

**C0.3 — VERIFY §2.1 and §2.3.** Read the manifest loader and confirm the four-field predicate for
`destructiveAllowed`. Trace `RecreateOnAppStart` and confirm it does not reach the destructive branch
(§2.3). Write both confirmations into the evidence note. If either differs from what this plan
states, STOP and re-plan with the owner.

**C0.4 — The reproduction test (write it now, expect RED).** Add to the H2 proof matrix:

```
Scenario 27 (C-B1): an UNKNOWN-item change on a blanket-posture app must be REFUSED, not
                    silently allowed to wipe every table
```

Setup — take scenario 24b's fixture and **remove the concept drop**, so the report contains an
UNKNOWN item and **no** `DROP_TABLE`:
1. `seedTwoRealisticConceptsWithData()`, then drop the `version` column from `widgets` physically
   (or create `widgets` without it, as 24b does) so it becomes UNKNOWN — **VERIFY** by asserting
   `report.hasOnlyNamedDestructiveKinds()` is false and no item is a `DropTable`.
2. Seed fingerprint + ownership through the **production writer** (`afterMigrate` with the v1
   manifest), never by hand-inserting JSON.
3. Build a manifest that still declares **both** concepts (no drop), blanket posture ON,
   `destructiveAcknowledgment = ""`.
4. `assertThrows(IllegalStateException.class, () -> executor.beforeMigrate(...))`.

Assertions: the refusal message names the UNKNOWN item(s) and the expected token; **both tables and
all their rows still exist**; the latest history row is `REFUSED`. Then repeat the same pass **with**
the computed token and assert the wipe proceeds (proving C1 gates on the token, not on the item kind).

Run it. **It must fail** today — the wipe executes and the tables are gone. Paste the failure into
the evidence note.

**DoD:** clean tree; baseline recorded; four answers recorded; §2.1/§2.3 verified; scenario 27 RED.
**Commit:** none (scenario 27 lands with its fix in C1).

---

### Phase C1 — The whole-schema wipe requires a token (C-B1) (M)

**C1.1 — Merge the gates.** In the authorization block, replace the standalone X4.4 `DROP_TABLE`
refusal with a single "token is mandatory" gate per §2.2 that collects reasons:

- reason 1: the report contains one or more `DropTable` items (name them);
- reason 2: `hasUnknown` — the diff cannot be executed item by item, so proceeding means a
  whole-schema recreation.

If `!tokenMatches` and at least one reason applies → write the `REFUSED` history row, then throw with
a message that lists the applicable reason(s), the itemized report, the UNKNOWN items specifically
(when reason 2 applies), the expected token, both submission channels, and the docs anchor. Preserve
`agreementCheckSuffix(manifest, report)` on the message, as the sibling refusals do.

Keep the existing comment block explaining X4.4's rationale — extend it rather than replacing it, so
the history of *why* both reasons exist stays readable.

**C1.2 — Simplify what follows.** After C1.1, the wipe branch is only reachable when `tokenMatches`
is true. Remove the now-dead `tokenMatches ? ... : "the deprecated blanket flag"` conditional in the
wipe's warning message and the `tokenMatches ? effectiveToken : null` argument — pass
`effectiveToken` unconditionally, and `consumePendingAcknowledgmentIfAny` unconditionally in that
branch. **VERIFY** each of these is genuinely unreachable before deleting it; leaving a stale
ternary that implies a state that can no longer occur is its own small lie.

**C1.3 — Scenario 27 passes.** Record before/after in the evidence note.

**C1.4 — Full-suite fallout.** Run the assembled-app suite. Any test asserting that a blanket-only
pass performs a wipe now encodes removed behaviour: update it to expect the refusal and add a comment
naming C-B1. **Never weaken an assertion to make it pass.** Pay particular attention to
`SchemaLifecycleExecutorDestructiveItemizationTest` (which owns the blanket-only surgical test) and
scenario 24b (which supplies a token already, so it should be unaffected — confirm).

**C1.5 — Docs.** Rewrite `docs/SCHEMA_EVOLUTION.md`'s "The deprecated blanket flag" decision table:

| Change | Blanket flag alone | Needs an itemized token |
|---|---|---|
| Drop a column | authorizes it | no |
| Narrow a column's type | authorizes it | no |
| **Drop a whole concept (table)** | **does NOT authorize it** | **yes** |
| **A diff that cannot be explained item by item (whole-schema recreation)** | **does NOT authorize it** | **yes** |

Replace the bullet that currently says the whole-schema recreation "applies regardless of how the
pass was authorized" — that is exactly what C1 removes. State the new rule in one sentence: *the
blanket flag authorizes only surgical column drops and type narrowings; anything that destroys a whole
table's data requires the token.* Add the dev/CI guidance from §2.3.

**DoD:** scenario 27 (both halves) green; full suite green; docs table updated; the wipe branch is
provably token-only.
**Commit:** `LNCH-1 C1: the whole-schema recreation requires an itemized token, like every other whole-table destruction`
— the body must state the operator-visible behaviour change in one paragraph.

---

### Phase C2 — Pin the platform-column set (C-D1) (S)

**C2.1** Implement the conformance test per §2.4, in the generator test source set (suggested:
`NPDevGenerator/generator/src/test/java/com/npdev/generator/dbconfig/PlatformColumnContractTest.java`).
**VERIFY** the workspace-root resolution helper pattern in
`NPDevGenerator/generator/src/test/java/com/npdev/generator/migration/MigrationAuthorityQuarantineAssertions.java`
and reuse it rather than writing a third copy of "walk up until you find NPDevGenerator".

**C2.2** Prove the test can fail: temporarily add a fake fifth column to the emitter's appended set
(or remove one from the executor's literal), observe red, revert. Record it.

**C2.3** Add a pointer comment on **both** sides (`PLATFORM_MANAGED_COLUMNS` and `fullColumnNames`)
naming the test that pins them together — so whoever edits either one finds the contract.

**DoD:** test green, proven red-capable, both sides cross-referenced.
**Commit:** `LNCH-1 C2: pin PLATFORM_MANAGED_COLUMNS against the emitter's real platform columns`

---

### Phase C3 — Make the corpus teach the safe posture (C-D2) (S/M)

Execute only what §2.6's ratified answers authorize.

**C3.1** Flip the agreed definitions per §2.5. For **each** one: edit `db.definition.json`, then
regenerate and boot that app end to end to confirm the generator and validator accept the
combination — a posture that is documented but rejected by the toolchain would be worse than the
status quo. Record each app's regenerate/boot result individually.

**C3.2** Verify the flipped apps still behave correctly on an ordinary additive change (add a field →
regenerate → boot → column present, data intact). The safe posture must not break the everyday path;
that is the claim `docs/SCHEMA_EVOLUTION.md` makes ("Nothing non-destructive changes").

**C3.3** Update `D:\WorkSpace\NPDev\AppGen\apps\APP_DEFINITION_FORMAT.md` and
`docs/NPDEV_USER_MANUAL.md` so their worked example shows the **recommended** posture, with a short
note that the blanket posture appears in older definitions and why.

**C3.4** Knowledge card per §2.5 (extend the existing one if it already covers the ground). Rebuild:
`python scripts/ai/build_knowledge.py`. Then **verify the loop actually learned it**: query the
rebuilt index for something like "what schemaLifecycle should a new app use" and confirm the card
ranks; record the query and result (the previous rounds established this verification style).

**C3.5 (only if question 3 = yes)** Add the authoring warning. If no, note the deferral explicitly.

**DoD:** flipped apps regenerate, boot, and take an additive change cleanly; docs and card teach the
recommended posture; corpus query verified.
**Commit:** `LNCH-1 C3: make the sample corpus demonstrate the recommended schemaLifecycle posture`

---

### Phase C4 — A failed `-Upgrade` must not silently degrade the next plan (C-B2 / LNCH-1-B8) (M)

**C4.1 — Reproduce first.** Establish the exact mechanism before fixing: run `-Upgrade` against an
app so that generation fails partway (**VERIFY** the simplest reliable trigger — e.g. an invalid
model that fails validation after the output directory has been wiped), then run `-PlanOnly` and
observe it report "Fresh install — no previous compiled model to diff against". Capture both
transcripts. Identify precisely **where** the previous compiled model is read and **when** it is
destroyed (`Build-NpdevApp.ps1`'s `deleteBeforeMount` wipe versus `MigrationPlanEmitter`'s input).

**C4.2 — Fix.** Two complementary changes; implement both:
- **Preserve:** `Build-NpdevApp.ps1` must copy the previous compiled model to a location **outside**
  the wiped output root before wiping — the build-output area
  (`D:\WorkSpace\NPDev\Build\<app>\migration-plans\`, where plan artifacts already survive rebuilds)
  is the natural home. **VERIFY** how the existing plan echo writes there and follow the same
  convention.
- **Refuse, don't degrade:** when a plan is requested for an app that has **evidence of prior
  deployment** but no previous compiled model available, `MigrationPlanEmitter` (or its caller) must
  **error** with a clear message — never silently emit "Fresh install". "Evidence of prior
  deployment" must be something durable: a preserved snapshot from the step above, or a recorded
  build/plan artifact. **VERIFY** what signal is actually available at plan time; if the only honest
  signal is "a previous compiled model was preserved and then lost", say exactly that in the message.
  Do not consult the live database — the generator has no database connection, by design (§2.3 of
  the original plan: the generator previews, the executor decides).

**C4.3 — Tests.** A `MigrationPlanEmitter` unit test for the refusal path, plus a gate-level or
scripted test that a failed `-Upgrade` followed by `-PlanOnly` produces the error rather than
"Fresh install". Keep the scripted half in the existing schema-evolution gate if one covers this
surface; otherwise add it there rather than creating a new gate script.

**C4.4** Update `docs/OPEN_GAPS_AND_ROADMAP.md`: mark `LNCH-1-B8` DONE with the commit reference.

**DoD:** the failed-upgrade→plan sequence errors clearly; the previous compiled model survives a
failed run; B8 closed in the ledger.
**Commit:** `LNCH-1 C4: preserve the previous compiled model across a failed -Upgrade and refuse to emit a false "fresh install" plan (LNCH-1-B8)`

---

### Phase C5 — Record and hygiene truth sweep (C-R1, C-R2, C-R3) (S)

**C5.1 (C-R1)** Rename scenario 24's `@DisplayName` to describe what it actually proves — e.g.
*"Scenario 24 (X-B1): an acknowledged concept drop on a blanket-posture app drops ONLY that
concept's table — never the data of concepts this build still declares"* — and keep the existing
inline comment that points at where blanket-only routing is proven. Then **sweep the rest**: check
every scenario name in both proof matrices against what the test body does, and fix any other
mismatch you find (C1's changes may have created more).

**C5.2 (C-R2)** Fix the verification ledger's self-contradiction: its "Earlier rounds" table still
says the worked-example log block was NOT re-captured and "X-I3 remains open", while its
"Outstanding" table marks X-I3 CLOSED. Update the earlier-rounds row to point at the X6 capture.
Then re-read the whole ledger for any other internal disagreement — it is the tiebreaker document,
so it carries a higher bar than the rest.

**C5.3 (C-R3)** Add `D:\WorkSpace\NPDev\AppGen\apps\lnch1-rehearsal\README.md` recording: what the app
is for, that its definition is deliberately in the **v2 / Postgres** state (Project dropped,
User.department added), that it was changed from H2Local on 2026-07-20 and why, the container details
(`npdev-lnch1-rehearsal-pg`, port 5434, DB `npdev_lnch1_rehearsal`, npdev/npdev), and that both the
app and container are currently **stopped**. Per §2.6 answer 4, restore it to v1/H2Local instead if
that is what the owner chose.

**C5.4** Sweep for any other layer-2 change made during the last three rounds that was not recorded
(guardrail 8): `git status` in the repo will not show these — check `AppGen\apps` and `NPDevSamples`
for definitions whose modification dates fall in the LNCH-1 window and confirm each was intentional.

**DoD:** no test name lies; the ledger agrees with itself; every layer-2 change is documented.
**Commit:** `LNCH-1 C5: reconcile test names, the verification ledger, and layer-2 fixture state`

---

### Phase C6 — Mirror the new scenarios into the Postgres twin (S/M)

**C6.1** Mirror scenario 27 (both halves) into `SchemaLifecycleExecutorPostgresProofMatrixTest`,
following whatever sharing convention C5's predecessors established (the hardening round's X5 note
applies: prefer a shared body if the Testcontainers lifecycle allows, otherwise copy and
cross-reference both copies).

**C6.2** Re-run the full twin. Expected: 24 existing + the new ones, 0 failures, with the 10
unrelated `ApplicationContext` failures unchanged.

**C6.3** If Docker is unavailable: **do not fake it.** Commit the mirrored test, mark it NOT RUN in
the evidence note and add a ledger row, and say so in your closing summary.

**DoD:** twin green including scenario 27, or an honest NOT-RUN record.
**Commit:** `LNCH-1 C6: mirror the whole-schema-wipe token requirement into the Postgres twin`

---

### Phase C7 — Triage the three pre-existing failures, once and for all (S/M, hard timebox)

These have been carried as "pre-existing, unrelated" for three rounds. Resolve their *status*, not
necessarily their code. **Timebox: one hour per item, then write down what you know and stop.**

**C7.1 — RuntimeHost gate observability-report step.** It fails *after* `:test` reports BUILD
SUCCESSFUL. Read the gate script and the report it produces; identify the failing check by name;
determine whether it is (a) a governance/evidence-drift item with a known owner elsewhere in the
docs, (b) genuinely broken, or (c) obsolete. Then either fix it, file it as its own tracked row in
`docs/OPEN_GAPS_AND_ROADMAP.md` with a concrete description, or remove the dead step with a dated
comment — the same three-outcome discipline the previous round applied to the stateful-migrations
gate. **A gate step that always fails trains everyone to ignore the gate.**

**C7.2 — `SandboxedPluginExecutionEngineTest` flake.** Run it 5× in the full suite and 5× in
isolation; record the ratio. If it is timing-dependent, either raise the tolerance with a comment
explaining the measured margin, or tag it appropriately — do not leave a known-flaky test unmarked.

**C7.3 — The 10 `ApplicationContext` `integrationTest` failures.** Confirm (do not re-derive from
memory) that they fail only for want of an externally-configured Postgres. Then record them in the
roadmap as a single tracked item with the exact precondition needed to run them, so a future session
does not spend a fourth round re-establishing the same attribution.

**DoD:** each of the three has a status backed by evidence from *this* session and a tracked home.
**Commits:** one per item.

---

### Phase C8 — Full regression and closeout (S/M)

**C8.1** Run everything from §3.1 and compare against the C0.1 baseline. **Every** delta must be
explained in the evidence note.

**C8.2** Update the verification ledger with a "Closeout round (C0–C8)" table: one row per claim,
marked VERIFIED LIVE / VERIFIED BY SUITE / NOT VERIFIED, with evidence pointers. Move C-B1, C-D1,
C-D2, C-B2, C-R1/R2/R3 out of "Outstanding".

**C8.3** `docs/OPEN_GAPS_AND_ROADMAP.md`: close `LNCH-1-B8`; confirm `LNCH-1-B9` and `LNCH-1-B6`
remain OPEN with accurate one-line descriptions; add anything C7 filed. Regenerate the derived
projection: `python scripts/ai/extract_platform_status.py` (never hand-edit
`knowledge/platform-status.json`).

**C8.4** `CHANGELOG.md` under `[Unreleased]`: one line for the C1 authorization change
(operator-visible), one for the C3 posture change, one for the C4 plan-integrity fix. **Do not cut a
release tag** — the owner has deferred tagging repeatedly.

**C8.5** Write `closeout-final.md` and make your closing summary to the owner **match it exactly** —
no upgrade in confidence between the two. If something was not run, both documents say so.

**DoD:** suites green or explained; ledger and roadmap current; summary matches evidence; clean tree.

---

## 5. Sequencing and the minimum bar

```
C0 (S) → C1 (M) → C2 (S) → C3 (S/M) → C4 (M) → C5 (S) → C6 (S/M) → C7 (S/M) → C8 (S/M)
```

Sequential. C6 mirrors C1's scenario, so C1 must land first. C2 is independent and cheap — do not
defer it, it protects C1's Trigger B from a silent future break. C3 requires the owner's answers.

**If the effort must be cut short, the non-negotiable core is C0 → C1 → C2.** C1 closes the last
authorization hole in the destructive path; C2 stops a future emitter change from silently turning
the schema-ahead detector into a source of false refusals. C3–C7 are drift prevention, tooling
integrity, and record-keeping. **If you stop early, say exactly where and which findings remain
open — in the evidence file, in the verification ledger, and in your summary.**

---

*Companion documents: `docs/LNCH1_SCHEMA_EVOLUTION_PLAN.md` (original 9-phase build, guardrails) ·
`docs/LNCH1_REMEDIATION_PLAN.md` (R0–R9) · `docs/LNCH1_HARDENING_PLAN.md` (X0–X9) ·
`docs/SCHEMA_EVOLUTION.md` (user-facing contract) ·
`..\NPDev_General__OutsideRepo\lnch1-evidence\hardening-verification-ledger.md` (the tiebreaker).*
