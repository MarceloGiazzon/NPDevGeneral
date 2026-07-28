# Next Execution Plan — remaining items + 2.CD Frontend Strategy

> **STATUS: ACTIVE.** Live backlog. Written 2026-07-28 against `beta1-vision-spine` @ `46dbabd`
> (origin/main `89eb945`, tag `beta1.2`, repo public, **5 commits unpushed**).
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\NEXT_EXECUTION_PLAN.md" "D:\WorkSpace\NPDev\NPDev_General\docs\NEXT_EXECUTION_PLAN.md"
> ```
>
> **Scope:** every open item in `docs/EXECUTION_TREES.md` **except the three that are genuinely
> blocked** (3.4 ⛔ 2.E — but 2.E is *in* this plan, so 3.4 unblocks inside it; 3.5's stated blocker
> (REG-4) turned out to be stale too, found and corrected 2026-07-28 during P2.1 — see the note below;
> 3.8 ⛔ 2.CD), plus the five open REG rows, plus the full 2.CD Frontend Strategy.
>
> **Correction, found 2026-07-28 while building P2.1's Rule T1 (tree/ledger cross-check):** 3.5's
> stated blocker — "REG-4 flake root cause, still unresolved" — was itself stale, a **fourth**
> instance of the exact drift class Part 2 exists to close. REG-4 was CLOSED 2026-07-21 (root cause
> fixed). The real, current reason Postgres/full validation stays nightly-only is documented in
> `npdev-ci-validation.yml`'s own header: runtime cost (up to 120 min per run), not a flake.
> `docs/EXECUTION_TREES.md` 3.5 corrected to say so. **3.5 is therefore UNBLOCKED, not excluded** —
> but re-scoping it (accept the cost/time tradeoff as a documented BOUNDARY, or design a fast
> Postgres PR-subset) is a real scoping decision, not done here alongside the doc fix.
>
> Facts are **MEASURED** (git, source, filesystem, gates, run 2026-07-28) or **PROPOSED**.

---

# Part 0 — Verified state

## 0.1 Closed

| | |
|---|---|
| TREE 1 | all 16 tasks (2026-07-28) |
| 2.A DSL 2.0 | `flowStep.type` 23 → **12**; codemod `NPDevCli/dsl_v2_migration.py` + tests; 4-mirror gate live |
| 2.B all five splits | SemanticValidator 202 · TrustedSourceEmitter 212 · SchemaLifecycleExecutor 2,120 · GeneratedCrud 3,651 · KernelRunner 3,071 (+ step classes ⇒ **CORE C-4** closed) |
| 2.F / CORE C-3 | durable demo **shipped** — `NPDevSamples/durable-workflow-demo` + `NPDevSamples/scripts/run-durable-resume-demo.ps1` |
| 3.1 | repo public |
| F5-V.2 | live re-verify **7/11**, step 11 (REG-48 cascade) PASSED |
| Gates | register-consistency OK, **12/12** planning docs declare status |

## 0.2 Open (updated 2026-07-28, end of day — Part 1/2/3 fully executed)

**All five REG rows CLOSED same day:** REG-54, REG-56, REG-57, REG-59/61 split, REG-60. See Part 3
below for each fix's evidence; nothing in the five-REG backlog remains open.

**EXECUTION_TREES still open:** 2.CD (F1–F6) · 2.E · 3.2 ⬥ · 3.6 · 3.7. (3.5's stale REG-4 blocker,
found and fixed during P2.1, is UNBLOCKED-but-not-scheduled, not one of the "three genuinely
blocked" items — see the header note above.)

## 0.3 ★ What the demo proved, and now proves in full (updated 2026-07-28)

MEASURED, as originally written. The demo flow this plan was written against was:

```
SubmitExpense: invariantCheck → createConcept → emitEvent → branch( awaitEvent → return )
```

**There was no `capabilityCall` step in it**, and the runner slept **5 seconds** before the kill.
Both were deliberate, disclosed workarounds around REG-56 and REG-57 respectively.

**Both are now closed, and both workarounds are gone:**
- REG-57 fixed (H2 `WRITE_DELAY=0`, root-caused — see P3.1) → the 5-second sleep is deleted from
  `run-durable-resume-demo.ps1`; **3/3 clean** with it gone.
- REG-56 fixed (`ExecutionContext.resuming`, root-caused — see P3.2) → the `notify-approval`
  `capabilityCall` step is back in the demo's model; **3/3 clean** across a real kill+restart with it
  present, `NPDEV-PLUGIN-SANDBOX :: phase=finish status=SUCCESS` confirmed in the log.

The completion criterion this plan set for both (P3.1/P3.2) is met. The public showcase now
demonstrates the engine's full path, not a narrowed one.

---

# Part 1 — 🔴 Immediate corrections (~1 hour) ✅ DONE 2026-07-28

**Do first. Every item here is something the repo currently asserts that is false.**

## P1.1 Push the 5 unpushed commits · ⚡ 2 min ✅ DONE

MEASURED — `git log origin/beta1-vision-spine..HEAD` = 5, including **`07af4b9` the REG-58 fix**
(drop indexes referencing a narrowed column before `DROP COLUMN`).

The repo is public. A HIGH-severity migration-crash fix that exists only on your laptop is not
shipped. `git push`.

## P1.2 `EXECUTION_TREES.md` 2.F is stale · ⚡ 5 min ✅ DONE

Still reads:

```
└─ 2.F  Durable-workflow demo app                                  [1 week] ★★
         You have a capability Temporal charges for and zero public evidence it exists.
```

**"Zero public evidence" is now false** — it shipped 2026-07-28 (`0384966`). Replace with:

```
└─ 2.F  Durable-workflow demo app  ✅ DONE 2026-07-28 (0384966, CORE C-3)
         NPDevSamples/durable-workflow-demo + NPDevSamples/scripts/run-durable-resume-demo.ps1
         Park on awaitEvent → hard kill → new JVM → publish → same execution resumes. One command.
         ⚠️ Currently demonstrates a narrower path than the engine: no capabilityCall step
            (REG-56) and a 5s pre-kill delay (REG-57). Both disclosed in the runner.
            Closing REG-56/57 → re-add the capability step, drop the sleep.
```

## P1.3 `EXECUTION_TREES.md` 3.3 is stale · ⚡ 5 min ✅ DONE

Still reads *"User impact is high: 'add a new entity to an existing app' **currently fails**. Not yet
scheduled."*

MEASURED: `~~**REG-40**~~ | CLOSED (2026-07-24, SER-P9)`, and `DATABASES_AND_MIGRATIONS.md` records
the fix twice (lines 416, 513). **That sentence would send someone to fix a fixed bug.** Mark
3.3 ✅ DONE, delete the claim.

## P1.4 ★ REG-59 is struck through while its own text says the gap is open · S 30 min ✅ DONE

MEASURED. The row is `~~**REG-59**~~` — strikethrough, which in this register means **CLOSED**. Its
status text opens:

> **DONE for WmsOffice (manual live-data recovery); the underlying platform gap is FILED, not fixed.**

and closes with two explicit platform-level Needs. The prose is scrupulously honest; the marker is
not. **Anyone scanning for open work misses it.**

I ran the detector built for exactly this class:

```
python scripts/quality/check-narrative-status-drift.py   →   0 candidates found
```

It does not catch this shape (its rules are prose-vs-row *within* an item, not
strikethrough-vs-own-status), and it is report-only regardless.

**Why this matters more than MEDIUM implies.** Need (b) is a dead end in the platform's crown-jewel
subsystem: the literal-default backfill **cannot express a per-row-unique value**, so any app with a
`UNIQUE` + required column and more than one existing row hitting a narrow-type migration has **no
sanctioned recovery** — only out-of-band SQL, as WmsOffice needed. The filing proves it live:
`identity_roles.name` (5 rows), `identity_users.username` (6 rows). The schema engine's whole promise
is that version 2 does not destroy your data; this is a documented case where it stops and offers no
way forward.

**Fix — split, do not re-open:**

- Leave `~~REG-59~~` struck as the **WmsOffice recovery record** (accurate for what it covers).
- File **REG-61, OPEN, HIGH** carrying the two platform Needs verbatim:
  - **(a)** `executeNarrowTypeDropAndRecreate` must preserve original `NOT NULL`-ness, so a table
    with zero rows never needs the backfill dance.
  - **(b)** the literal-default backfill has no expression for a per-row-unique default on a
    `UNIQUE` + required column with existing rows → **make it an explicit, named refusal** (like the
    bond-column refusal) with a documented recovery recipe, rather than a second differently-worded
    boot failure after the first is fixed.
- Cross-link both directions.

## P1.5 REG-58's own text is now stale · ⚡ 2 min ✅ DONE

It ends: *"the live database is still sitting at its backed-up, partially-migrated (8/26) state as of
this filing."* REG-59 superseded that — the DB was recovered, backfilled, `ALTER COLUMN ... SET NOT
NULL` applied, `Impact-Only.ps1` → `verdict: SAFE`, boot succeeded, `/actuator/health` → UP. Add one
sentence pointing at REG-59.

---

# Part 2 — 🟡 Close the drift class, not the instances (1 day) ✅ DONE 2026-07-28

## P2.1 Tree-vs-ledger cross-check ✅ DONE — shipped as Rules T1+T2, calibrated, wired in blocking

Three stale-tree instances in two days — §2.D (fixed by hand in P1.1 of the last plan), 2.F, 3.3 —
plus REG-59's strikethrough contradiction. `check-register-consistency.py` covers the three ledgers
and planning-doc `STATUS:` headers. **Nothing cross-checks a tree entry against a ledger row.**

**PROPOSED — extend `check-register-consistency.py` with two rules:**

**Rule T1 — tree/ledger agreement.** For every `REG-nn` mentioned in `docs/EXECUTION_TREES.md` (and
any doc declaring `STATUS: ACTIVE`), compare its tree-side framing against its register row. A row
that is struck through (closed) while the tree still describes it as pending work → **FAIL**. This
is the same shape as the existing summary-row-vs-detail-section check, pointed at a fourth document.

**Rule T2 — strikethrough/status contradiction.** A struck row whose status text contains
`not fixed` · `FILED` · `remains` · `still open` → **FAIL**. REG-59 is a real calibration instance;
its corrected split (P1.4) is the GREEN counterpart. Ship with `--calibrate` proving both
directions, per this repo's own standard (`check-narrative-status-drift.py`).

**Acceptance.** RED against the pre-P1.4 tree (fires on REG-59, 2.F, 3.3), GREEN after. Wired into
`run-ai-knowledge-gate.ps1` as **blocking** — unlike the narrative-drift checker, this one has no
false-positive risk because it compares two machine-readable markers, not prose.

---

# Part 3 — 🟡 The five open REG rows ✅ ALL FIVE CLOSED 2026-07-28

## P3.1 ★★ REG-57 — re-rate to HIGH and fix (2–3 days) ✅ DONE — root cause: H2 WRITE_DELAY (not ordering)

**Currently MEDIUM. It should be HIGH, and the reason is the core promise.**

MEASURED, from the filing: a hard kill landing within ~1 s of the `POST /api/flows/{name}/execute`
response — a response that **already told the caller `status: WAITING_EVENT`** — can leave
`npdev_flow_instance` at `status=RUNNING, currentStepIndex=1`, i.e. the initial
`flowInstanceStore.save(initialInstance)` checkpoint, never reaching the
`flowInstanceStore.update(waiting)` call in `KernelRunner.executeFlowInstance`'s `WAITING_EVENT`
branch. Reproduced **3/3** at ~0 s delay; **absent 1/1** at 5 s.

> **The API acknowledges durability before the state is durable.** For a durable workflow engine that
> is not a timing curiosity — it is the one guarantee the whole subsystem exists to provide. A caller
> that receives `WAITING_EVENT` is entitled to assume the flow survives a crash. Today, sometimes, it
> does not.

The filing already did the elimination work: `JdbcFlowInstanceStore.update` is straightforward
per-call synchronous JDBC, no batching/async/decorator layer, and only two `FlowInstanceStore`
implementations exist repo-wide.

**Investigation order — PROPOSED:**
1. **Ordering.** Does the HTTP response return before `update(waiting)` is *called*, or after it
   returns? If before, this is an ack-ordering bug in the controller/kernel boundary and the fix is
   ordering, not durability.
2. **Durability.** If ordering is correct, the write is issued but not physically durable. Check H2
   `WRITE_DELAY` (default 500 ms — a strong match for a "roughly the first second" window) and
   whether the connection is committing. `MV_STORE` async flush is the leading suspect the filing
   names.
3. **Fix candidates, in preference order:**
   - `WRITE_DELAY=0` on the flow-instance write path (correctness over throughput for *this* table);
   - or an explicit checkpoint/flush after the `WAITING_EVENT` update;
   - or return `202` and only report `WAITING_EVENT` from a read-after-durable path.
4. **Postgres parity.** Confirm whether Postgres exhibits it (likely not — synchronous commit), and
   say so explicitly in the fix note. If H2-only, that is still a real bug: H2Local is the default
   dev engine and the demo's engine.

**RED→GREEN:** a test that kills at ~0 s delay and asserts the persisted row reads `WAITING_EVENT`.
The filing's 3/3-vs-1/1 empirical result is the calibration.

**Completion criterion:** **delete the 5-second sleep from `run-durable-resume-demo.ps1`** and have
it still pass. That is the honest proof, not a green unit test.

## P3.2 ★ REG-56 — capabilityCall fails on cross-JVM resume (2–3 days) ✅ DONE — root cause: resume lost the flow's own role

MEASURED: a `capabilityCall` step (`notification`/`send`, `notification-inproc`) executed while
resuming an `awaitEvent`-parked flow throws `CAPABILITY_FAILED` **only when the resuming process is a
genuinely different JVM**. Identical call succeeds in one continuously-running process (confirmed
twice). Rehydration is confirmed correct up to that point — `WAITING_EVENT`, `resumeAttemptCount=0`,
eligible — so the failure is *after* rehydration, in capability dispatch.

**Hypothesis to test first — PROPOSED:** the in-proc adapter's registration/state is
process-local, and the resume path expects a registry populated by the *submitting* process. Check
`CapabilityRegistry` / `SandboxedPluginExecutionEngine` bootstrap ordering on a resume-triggered
execution versus a request-triggered one — specifically whether `ResumeBootstrapRunner` runs before
adapters finish registering. Note REG-55 (already fixed) was in exactly this area
(`resolveOperation` matching by name+argCount).

**First actionable step is diagnostic, not a fix.** The filing says *"no further detail surfaced by
`ExecutionQueryController`'s response shape."* **Surface the cause**: a `CAPABILITY_FAILED` with no
underlying exception detail is itself a defect — it is the kind of blindness REG-50 and REG-51 were
about. Fix the observability first; the root cause may then be obvious.

**Completion criterion:** **add the `capabilityCall` step back into the demo flow** and have the
runner pass across a real restart.

## P3.3 REG-60 — cosmetic, but on the showcase surface · ⚡ 1 hr ✅ DONE

`commitDraft()` sets `msg.className="msg ok"` then immediately calls `render()`, which rebuilds
`#app` including a fresh blank message span — so the "Saved." confirmation is never visible.
`workbench-page.html.mustache` ~line 260.

LOW severity, but it is on the Aggregate Workbench, which is the thing you point people at. **Fix:**
render the confirmation after `render()`, or have `render()` preserve a pending message. One-line
class of change; verify in-browser.

## P3.4 REG-54 — dead private methods · ⚡ 30 min ✅ DONE

`worse(...)` and `hasTypeChange(...)` in `SchemaLifecycleExecutor` have zero callers since SER-P4.8
switched `classify()` to `ClassificationReducer`. Three test doc-comments still reference them as
live. Delete both; fix the comments. Trivial, and it removes a false trail for the next reader.

## P3.5 REG-61 — the platform gap split out of REG-59 (see P1.4) · M 2 days ✅ DONE (a)+(b)

**(a) Preserve `NOT NULL` on narrow-type recreate.** Small, mechanical, high value: a zero-row table
then needs no backfill dance at all.

**(b) Name the refusal.** The literal-default mechanism cannot express a per-row-unique default. Make
`BackfillPass` detect *"required + `UNIQUE`-constrained + >1 affected row + only a literal default
available"* and refuse with a **specific, named diagnostic** plus a documented recovery recipe (the
out-of-band SQL shape WmsOffice used, generalized). Do **not** invent a per-row-unique default
expression language for this — the refusal with a recipe is the right scope, matching the bond-column
refusal precedent.

**RED-first:** a model with a required unique field, an existing 2-row table, and a literal default →
current behavior is a confusing duplicate-key failure after `UniqueConstraintPass`; post-fix it is a
named refusal naming the rows and the recipe.

---

# Part 4 — 🔵 2.CD Frontend Strategy (~3 weeks)

**Status: NOT STARTED, except F5-V.2 (done).** Full detail in `docs/FRONTEND_STRATEGY_PLAN.md`;
this section is the current-state delta and the execution order.

## P4.0 Preconditions — both now met

- **2.A.0 resolved** — `createConcept`/`updateConcept` kept as distinct kinds (enum is 12, not 9).
  This was the gate on F2.1's `intent` inference. **Cleared.**
- **Helpers staged, none executed** — `<scratchpad>/helpers/`:
  `preflight-accessors.py` · `extract-routes.py` · `prototype-invocations.py` ·
  `classify-screens.py` · `bootstrap-panel-provenance-v2.py` · `README.md`;
  plus `<scratchpad>/2c-staging/check-panel-provenance-impact.py` (calibration green).

> **Run `preflight-accessors.py` before writing any F2.1 Java.** It already found
> `getInvocableProcedures` does not exist — a compile failure the plan's sketch would otherwise hit.
> Others in that sketch are equally unverified.

## P4.1 F1 — Screen taxonomy ★★ · 1 day · ✅ DONE 2026-07-28

Routes everything else. `classify-screens.py` does the measurement; a human does the judgment.

Promotion rule, encoded: **a hand-written class in ≥ 2 apps with ≥ 2 screens is a primitive
candidate.**

**Correction against a fresh, reasoned re-measurement:** the "6 of 13 screens are operator
consoles" figure above does not survive re-running the tool and reviewing the borderline cases by
hand on this checkout. Measured result: **5 of 13** (one screen, `crossdocking`, human-overridden
from the mechanical classifier's `detail-form` default — see `docs/SCREEN_TAXONOMY.md`'s footnote).
More importantly: **the promotion rule finds ZERO candidates**, mechanically or after the human
review. Every hand-written, no-primitive-covers-it class (`operator-console`, `dashboard`,
`spatial-map`, `admin-tool`) lives in WmsOffice **alone** — no other app (official or sample) has a
second instance of any of them, so the `≥ 2 apps` half of the rule is never met. `operator-console`
is the strongest signal (WmsOffice's plurality class) but is one real second app away from
qualifying, not already qualifying — building a primitive from n=1 would be exactly what the rule
exists to prevent. **Do not build a new primitive yet; this directly gates F6 to "nothing to build
until a second app proves recurrence."**

**Deliverable:** `docs/SCREEN_TAXONOMY.md` — every screen in all five official apps classified (two
of the five, WordLab and Claude Support Desk, have **zero** hand-written screens at all — fully
generated); each class names its covering primitive or is explicitly `hand-written → contract`; the
promotion-rule verdict and its evidence are stated plainly, not forced to match this plan's own
earlier, less-precise claim.

**Bonus, done:** the README-precision goal is met by `docs/SCREEN_TAXONOMY.md`'s own "Bonus" section
— a measured generated-vs-hand-written breakdown per app, not the prior vague "custom business
screens are hand-written."

## P4.2 F2 — Contract substrate · 5 days

- **F2.1 `invocations` catalog (2 days). ✅ DONE 2026-07-28.** Inserted at
  `CompiledMetadataCanonicalJson.java`, `toInvocationCatalog` + ~15 helpers. The catalog's core
  value is **`preferred` / `prefer` / `preferReason`** — a concept typically has several write
  routes (generic CRUD, flow execution) with *different semantics*, and using direct CRUD on a
  flow-backed concept silently bypasses invariants, orchestration and compensation. Derived via
  `CompiledModel.findFlow(concept, mode)` — the platform's OWN definition of flow-backed, not a
  separately-maintained lookup that could drift from it.
  **★ The single most important test in 2.CD, done and green: every `invocations[].path` (+
  `pathAliases`) matches a real controller route.** Proven two ways: (1) a fresh
  `extract-routes.py` run against a real regenerated WmsOffice found **zero mismatches** across
  343 real paths spanning all 252 invocation entries the model produces (32 concepts, 15 flows,
  panels, 2 aggregates) — this run ALSO found and fixed a real bug in `extract-routes.py` itself
  (its regex couldn't parse a path variable nested inside a multi-value `@PostMapping` array,
  silently dropping every flow-execute route from its output); (2) the committed, permanent
  regression test `InvocationCatalogRouteConformanceTest` (`NPDevContract/dsl`), run against the
  in-repo `medium-expense-approval` sample, pattern-matching every entry against the small, stable
  set of real controller route shapes rather than a large brittle per-entity fixture.
  **Several factual corrections against the original sketch** (see `docs/FRONTEND_STRATEGY_PLAN.md`
  §2.2's own correction note): the generic-CRUD path is keyed by TABLE name not concept name; flow-
  execute returns 200/202/422 depending on outcome, not a flat 202; `requiredPermission` is
  `"<op>:<concept>"` not the reverse; `CompiledAggregate` has no `getInvocableProcedures()` and no
  aggregate↔procedure binding exists anywhere at runtime (one templated entry per aggregate names
  this explicitly, rather than guessing a closed list); the `isStartEndpoint()` gate would have
  emitted zero flow entries (no sample sets it, and the real route has no such filter) — dropped.
- **F2.2 bundle endpoint (1 day). ✅ DONE 2026-07-28.** `GET /api/v1/runtime/metadata/ui/bundle[?concept=X|?panel=Y]`
  in `RuntimeUiMetadataController`, composing `PermissionAwareUiMetadataService.fields`/`actions`
  verbatim (the anti-drift array-equality property) plus a new `bundle()` method for the rest.
  **Two real gaps found before this could compose anything, neither invented — both closed as part
  of this task:** the `invocations` catalog (F2.1) and the pre-existing `transitions` catalog were
  both present in `compiled-metadata.json` but **never split into their own manifest file** by
  `MetadataManifestAssetEmitter` (a hardcoded 9-entry `CatalogDefinition` list that predates F2.1),
  so `RuntimeMetadataService.catalog("invocations"/"transitions", …)` had no manifest to load and the
  bundle's arrays would have 404'd. Fixed: emitter now emits 11 manifests (`invocations.manifest.json`,
  `transitions.manifest.json` + index entries); 3 generator tests hardcoded the old count of 9 and
  needed updating (`RuntimeApiEmitterMetadataManifestTest`, `CanonicalDemoGenerationSmokeTest`,
  `OfficialSamplesGenerationSmokeTest`).
  **Scope decision, stated plainly:** the bundle's `layout`/`enums`/`references`/`transitions`/
  `validation`/`invocations` arrays are passed through **unfiltered** — no per-actor permission filter
  exists anywhere in the platform for those six catalogs (only `fields`/`actions` have one), so
  inventing six new filters would be a much larger, uncosted addition than "compose the existing
  filters" asks for. Only `fields`/`actions` are permission-aware, matching the acceptance test's own
  scope (there is no individual filtered endpoint for the other six to diff against anyway).
  **`modelHash` reuses `SchemaLifecycleExecutor`'s fingerprint verbatim** (new
  `RuntimeMetadataService.schemaFingerprint()`, reading the same `schema-realization-manifest.json`),
  per the plan's explicit instruction. Noted honestly: that fingerprint covers table/column/type/
  required/unique shape only (`UserDatabaseDefinitionLoader#fingerprintInputs`) — it will NOT change
  for a panel-action/permission-hint/flow/lifecycle-only edit, only a schema-shaped one. F4's drift
  detection is therefore precise for the scenario it exists to catch (a field rename) but under-fires
  for the other six categories — an accepted boundary, not a bug.
  **Verified two ways:** (1) 5 new unit tests (2 in `RuntimeMetadataServiceTest`, 2 in
  `PermissionAwareUiMetadataServiceTest`, 1 in `RuntimeUiMetadataControllerStandaloneTest`) proving
  the catalog wiring, the anti-drift array-equality, and role-based field-visibility divergence — run
  green inside a regenerated WmsOffice (these three test classes are excluded from the bare
  template's `test` task by a pre-existing, unrelated `modelSpecificGeneratedAppTests` gradle list,
  same as before this change); (2) **live REST proof against a real running WmsOffice** (32 concepts):
  `GET .../bundle?concept=Area` returned all 8 catalog arrays correctly concept-scoped (5 fields, 5
  layout, 5 enums, 1 reference, 7 validation hints, 7 invocations incl. `createDirect:Area`), and
  `bundle.fields`/`bundle.actions` were byte-for-byte equal to the individual `/fields`/`/actions`
  endpoints' own output for the same JWT-authenticated caller — the exact anti-drift assertion the
  plan's acceptance criterion names.
- **F2.3 docs + schema + agent prompt (2 days). ✅ DONE 2026-07-28.** Shipped
  `docs/UI_CONTRACT.md`, `schemas/ui-contract.schema.json`, `docs/ai/UI_GENERATION_PROMPT.md`.
  Carried the staged draft over with corrections earned by actually building F2.1/F2.2, not assumed:
  the draft's "Everything is permission-filtered" claim is false (only `fields`/`actions` are; the
  other six catalogs are raw pass-through — now stated plainly, with a dedicated warning section);
  the "twelve catalogs" table conflated the platform's full catalog set with what the bundle actually
  exposes (`panels`/`procedures`/`domainTypes` and the plural `concepts` list are NOT in the bundle —
  table now says so explicitly); the direct-CRUD path example (`POST /api/{pluralTable}`) was wrong,
  corrected to the real `/api/concepts/{tableName}`; found a real, previously-undocumented gateway
  route (`POST /api/v1/execute/flow`, `DirectExecutionGatewayController`) that exists but is **not**
  represented in the `invocations` catalog at all — documented as a known gap rather than silently
  treated as covered. `ui-contract.schema.json` (one copy, not the 4-way `model.schema.json` mirror —
  this shape is produced entirely at runtime, never read by the DSL) validated against a real live
  bundle response (`GET .../bundle?concept=Area` on WmsOffice) with the `jsonschema` library, for
  both the concept-scoped and unscoped-bundle shapes.

## P4.3 F3 — Provenance: one manifest, three producers ★ · 4 days · ✅ DONE 2026-07-28

Shipped `schemas/panel-provenance.schema.json`, `ADR-0010-panel-provenance-manifests.md`, and all
three producers. See the ADR for full detail; the plan-level corrections:

- **Generator producer was NOT "nearly free."** `AutoPanelExpander` does already stamp
  `metadata.generatedBy`/`concept` on every expanded panel, exactly as claimed — but
  `CompiledMetadataCanonicalJson#toPanelCatalog` **never serialized `CompiledPanel.metadata()` at
  all**, so that stamp never reached any HTTP consumer before this task. The real "nearly free" work
  was closing that gap plus deriving `reads`/`writes`/`invokes` from already-compiled data
  (`CompiledMetadataCanonicalJson#toPanelProvenance`, new). Also found and avoided a real bug before
  it shipped: a read-only "selection"/table surface carries its fields on `CompiledPanel.layout()`
  with an EMPTY `fieldBindings()` (only the editable "form" surface populates bindings, confirmed
  against `AutoPanelExpanderTest`'s own assertions) — deriving `reads` from bindings alone would have
  silently emitted zero reads for every read-only generated surface. Verified with 2 new tests
  (`CompiledPanelProvenanceTest`, `NPDevContract/dsl`) built on a real `AutoPanelExpander` output
  (not a hand-built fixture), covering both the confirmed-generator-provenance case and the
  hand-declared-panel-has-no-provenance case.
- **Human producer bootstrapper had a real, previously-unrun bug.** The staged
  `bootstrap-panel-provenance-v2.py` assumed panel-action invocation ids use a DOT
  (`panelAction:<panel>.<action>`); the real id format (`CompiledMetadataCanonicalJson
  #panelActionInvocation`) uses a COLON (`panelAction:<panel>:<action>`) — the dot form would have
  silently produced zero panel-action `invokes` on every real screen that calls one. Fixed and
  committed as `scripts/quality/bootstrap-panel-provenance.py` (the canonical version now).
  **Run for real** against 3 genuine WmsOffice screens (`inventario.html`, `crossdocking.html`,
  `centro-trabalho.html`) using a live-captured unscoped bundle (32 concepts, 188 fields, 252
  invocations) — correctly recovered all 4/3/1 real flow invocations per screen (cross-validated
  against `docs/SCREEN_TAXONOMY.md`'s independent "crossdocking has 3 flow invocations" measurement).
  **Confirmed all 3 by hand**, finding two genuine, demonstrable inference errors along the way (now
  documented in the ADR): a `name`/`label` HTML-token false-positive class, and a field
  (`CrossDocking.dataAtivacao`) spread into a bare flow-payload object literal that the writes-heuristic
  missed and classified as a read instead. The 3 confirmed manifests are written to
  `AppGen/apps/_official/WmsOffice/web/*.panel.json` (AppGen is layer 2, not this git repo).
- **Agent producer needed no new work** — F2.3's `docs/ai/UI_GENERATION_PROMPT.md` already requires
  the `{screen}.panel.json` output in this exact shape as its second required output.
- **`Build-NpdevApp.ps1` already copies `*.panel.json` alongside `web/*`** — its existing "mount
  companion web assets" step (`Get-ChildItem $WebSrc -Force | Copy-Item -Recurse`) copies the whole
  `web/` directory verbatim, which already includes any `.panel.json` sibling files. Verified live: a
  regenerated WmsOffice build placed all 3 confirmed manifests under
  `App/src/main/resources/static/`. **No script change was needed** for this task item — checked,
  not assumed.
- `"slotOf"` reserved from day one, per the original plan — `null` until ADR-0004's L5 `layoutSlot`
  ships.

## P4.4 F4 — Impact gate ★★ · 2 days · ✅ DONE 2026-07-28 (script + proof); wiring point corrected

Shipped `scripts/quality/check-panel-provenance-impact.py`. `--calibrate` passes both controls
(fires on a stale confirmed manifest, silent on a correct one).

**The money demo, done for real, not simulated in the abstract:** took the 3 hand-confirmed WmsOffice
manifests from F3, renamed one field reference in a scratch copy of `crossdocking.panel.json`
(`CrossDocking.dataAtivacao` → `dataAtivacaoContada`, mirroring this section's own worked example),
and ran the gate against the real live-captured WmsOffice bundle (32 concepts, 188 fields, 252
invocations): **FAIL, exit code 1, naming the exact screen** —
`web/crossdocking.panel.json: references field 'CrossDocking.dataAtivacaoContada', which the model
no longer has`. Run clean (no simulated rename) against the same 3 confirmed manifests: 0 blocking
problems, 10 advisory warnings for WmsOffice's other unconfirmed screens (exactly `13 - 3`) — both
runs prove the gate works end-to-end on real data, not just the synthetic `--calibrate` fixtures.

**Correction: "wire it into `run-ai-knowledge-gate.ps1`" was the wrong instruction, not executed as
written.** That gate checks THIS repo's own static state (register consistency, knowledge cards,
security patterns) and runs on every PR with no external dependency. This gate needs two things that
gate cannot supply: (1) `*.panel.json` files, which live in `AppGen/apps/*/web/` — a different,
non-git workspace (layer 2), never inside `NPDev_General` — so there is nothing for it to find in
this repo; (2) a live bundle response with a real `modelHash`, which requires an authenticated,
running FinalApp (JWT login, per-app credentials) — something a fast, offline PR gate structurally
cannot do. Forcing it into `run-ai-knowledge-gate.ps1` would make check-10 either silently find
nothing (every run, forever, in this repo) or require embedding one specific app's login credentials
into a platform-wide gate, neither of which is honest. The gate is real, calibrated, and proven
against real data (above); it is a **per-app, post-deploy verification tool** (same category as
`_ops/Test-App.ps1`/`Smoke-Test.ps1`), not a platform CI check — run it via:
```
curl -s -X POST <baseUrl>/api/auth/login -d '{"tenantId":"...","username":"...","password":"..."}' \
  | jq -r .token > /tmp/t
curl -s <baseUrl>/api/runtime/metadata/ui/bundle -H "Authorization: Bearer $(cat /tmp/t)" -o bundle.json
python scripts/quality/check-panel-provenance-impact.py --root <AppGen-app-dir> --metadata bundle.json
```
No generic `_ops/Check-Provenance.ps1` template was added — it would need to embed app-specific
credentials to be non-interactive, which is worse than documenting the manual recipe above.

**No competitor can do this** — Lovable/v0/Bolt have no model to diff against; OutSystems/Mendix
cannot emit source you own.

## P4.5 F5 — Workbench residuals · mostly ✅ DONE 2026-07-28, one item honestly deferred

- **F5-V.1 Suites.** ✅ DONE. `:dsl:test --rerun-tasks` and `:generator:test :generator:behaviorTest`
  both green. Grepped `NPDevContract/dsl/src/test` and `NPDevGenerator/generator/src/test` for
  `@Disabled` on any workbench/aggregate/band/region test — zero hits, so no acceptance test has
  silently decayed into a skip.
- **F5-V.2 Live re-verification.** ✅ DONE 7/11 (this was completed earlier the same session, before
  F1–F4; see `docs/architecture/AGGREGATE_WORKBENCH_PLAN.md`'s own STATUS header and REG-60).
- **F5-V.3 Status header.** ✅ Already DONE — same commit that recorded F5-V.2
  (`38da6c7`) added the `> **STATUS: EXECUTED.**` header to
  `AGGREGATE_WORKBENCH_PLAN.md`'s first line, satisfying `check-register-consistency.py`'s
  planning-document coverage. No new work needed; verified by reading the live file, not assumed.
- **F5-R3** phantom `display` toggle. ✅ DONE. Added a correction note directly at the `display`
  documentation in `AGGREGATE_WORKBENCH_PLAN.md` (3 occurrences in the worked JSON example, not the
  1 the plan implied) — `ff4acba` found no code path reads it (`BandRegion` always renders the
  `selected` mode); kept as recorded design intent, not documentation of current behavior. Corpus
  stays clean (0 files in `golden-ai-scenarios/**`/`knowledge/**` teach it).
- **F5-R4** two-picker boundary. ✅ DONE. Added `B19` to `docs/ACCEPTED_BOUNDARIES.md`: `bandPickers`
  (`selectors[]` + `picker:`) and the plain FK auto-picker (already `B16`) are two real, independently
  working mechanisms that were deliberately not unified (`7e1096e`) — not "one is broken."
- **F5-R2 `computed[]` warn-only.** ✅ Confirmed already correctly implemented — no code change
  needed. Read `PanelValidation.java`'s `validateSurfaceComputed`: it already warns (does not error)
  when a surface declares `computed[]` with no `transaction.metadata.recompute` procedure, exactly
  the "warn, don't lie" behavior this item asked for. The plan's own remaining recommendation
  ("delete the field in DSL 2.0") is explicitly a future breaking-change-track item, not this task.
- **F5-R1 `allowedActions` untyped — investigated, NOT implemented, scoped honestly.** The plan's fix
  ("typed array + validate every entry resolves to a declared action") assumes the AutoPanel's
  declared action names are already a resolvable, typed set at DSL-validation time. They are not:
  `AutoPanelSurfaceAst` has no `actions` field at all — an AutoPanel section's action list (e.g.
  `"actions": ["gerarDemanda"]` inside a `transaction.sections[]` entry) lives inside that surface's
  untyped `Map<String, Object> metadata`, the same escape hatch `allowedActions` itself uses today
  (`AutoPanelExpander.java:310`, reading a CSV string off `state.getMetadata().get("allowedActions")`).
  Typing `allowedActions` alone (a JSON-Schema `string[]`) would enforce array SHAPE but cannot catch
  the actual failure mode this item exists to prevent — a typo'd action name (`GerarDemenda`) — since
  JSON Schema has no way to validate against a per-model, dynamically-declared set of valid names.
  Real semantic validation needs the action-declaration side resolved out of untyped metadata FIRST,
  which is a separate, larger prerequisite refactor than this item's 4 hr estimate accounted for.
  Shipping a typed-but-unvalidated field would look done without fixing the REG-52/53-class problem
  it's named after — worse than leaving it visibly open. **Left as a genuinely open item**, not
  silently dropped: `allowedActions` typing + cross-reference validation, blocked on first giving
  AutoPanel section actions a typed AST home. 0/27 corpus usage still holds, so there is no urgency
  and no regression from deferring.

## P4.6 F6 — Coverage roadmap · gated on F1 — **still gated, now with a measured answer**

F1 is done and found **zero recurring hand-written classes** (see P4.1) — build nothing. This is
not "not yet measured," it is "measured, and the answer is not yet." **F6-1 `layoutSlot`** (3 days)
remains the one to keep warm for whenever a second app produces an `operator-console`-shaped (or
other) recurrence; re-run `classify-screens.py` periodically as new apps are built, rather than
re-deriving this by hand.

---

# Part 5 — 🔵 2.E Ledger migration (3 days) — unblocks 3.4 · 🟡 PROTOTYPE shipped 2026-07-28, full migration NOT done

Prose register → `ledger/items/*.yml` + generated `docs/OPEN_ITEMS.md`.

**Why now, and why it is worth more than the 3 days:** this plan's Part 1 and Part 2 exist *because*
status lives in prose. Four drift instances in two days (§2.D, 2.F, 3.3, REG-59) is not a discipline
problem — it is a format problem. YAML rows make Part 2's gates ~20 lines instead of regex-over-prose,
and make REG-59's contradiction structurally impossible (`status: OPEN` cannot be struck through).

**What actually shipped, honestly scoped — a proof of concept, not the migration:** `ledger/README.md`
(schema + rationale), `scripts/quality/generate_open_items.py` (validates + renders
`docs/OPEN_ITEMS.md` from `ledger/items/*.yml`, `--check` mode for CI), and **9 of ~106 total
entries migrated** (`REG-54` through `REG-62` — this session's own work, chosen because I could
verify their fidelity against the source register precisely, having written them). Proven: schema
validation genuinely rejects a bad value (RED test: `status: CLOSED` → exit 2, naming the exact file
and field); `--check` is genuinely idempotent (GREEN after regenerating); the new
`docs/OPEN_ITEMS.md` needed a `LEDGER_EXCLUSIONS` entry in `check-register-consistency.py`
(it's ledger-shaped and the coverage-gap check correctly caught it) — added, with the reasoning that
its own drift check (`--check`, exact-byte comparison) is a *stronger* guarantee than the prose
register's regex-based cross-check, not a weaker one.

**What did NOT ship, stated plainly rather than silently claimed:**
- **~97 entries remain prose-only** — the rest of `NPDEV_OPEN_ITEMS_REGISTER.md`, all 19 of
  `OPEN_GAPS_AND_ROADMAP.md`, all 24 of `LAUNCH_READINESS_GAPS.md`. Migrating them is mechanical
  (one YAML file per row) but was not attempted here — bulk-transcribing ~100 entries whose detail
  sections run to thousands of words each (see REG-58's own prose row) risked fidelity errors far
  outweighing what a 9-entry proof of concept needed to establish.
- **The T1/T2 rules and `ledger_coverage_gaps` still read the PROSE register**, not
  `ledger/items/*.yml`. Repointing them now — before migration is complete — would make the gate
  blind to the 90%+ of items still only in prose. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` remains
  authoritative.
- **"The 13 process docs currently hard-wired into gates"** (this plan's own phrase) were not
  identified. `check-register-consistency.py`'s actual document set is 3 checked ledgers + 4 named
  exclusions (`LEDGER_EXCLUSIONS`) — nowhere close to 13 by any reading found in this repo's current
  scripts. This claim likely refers to something in `OPEN_GAPS_AND_ROADMAP.md`'s own §3.4 (a
  cross-document reference this plan's own line 12 gestures at), not investigated this round.
- **Cutover (retiring the prose register, switching gates to `ledger/items/*.yml` as the sole
  source) is a separate, later decision** — not attempted, and should not be attempted casually:
  this is the platform's core governance mechanism, read by every quality gate that ran throughout
  today's entire session.

---

# Part 6 — 🔵 Unblocked but unscheduled

## P6.1 3.7 — Aggregate transactional boundary (2–3 days) · ✅ DONE 2026-07-28

Shipped. `AggregateValidation.ownedConceptToAggregate(ModelAst)` (new) maps every aggregate's root
concept plus every `owned` (not `referenced`) collection concept, recursively, to its aggregate name
— `referenced` collections are excluded by design, since a reference is a normal cross-aggregate
pointer in DDD, not a boundary to cross. `FlowValidation.validateAggregateTransactionalBoundary`
(new) walks each flow's steps (recursing through `branch`/`foreach` nesting) collecting the `scope`
of every `createConcept`/`updateConcept`/`createEntity`/`updateEntity` step, maps each to its owning
aggregate via the helper above, and errors naming the flow and every distinct aggregate it touches
when that set has more than one member.

**Scope, stated rather than assumed complete:** only the four alias step types are traced, via their
required `scope` field — the one reliable, statically-resolvable concept-write signal. A raw
`capability: persistence, operation: save|delete` step (also legal, per the pre-existing
`hasPersistenceSemantics` check) is **not** traced: its target concept lives in opaque `input`/`args`,
not a structured field, so it cannot be resolved without runtime argument evaluation this validator
does not do.

**Verified 3 ways, not just unit-tested:** (1) new
`AggregateTransactionalBoundaryValidationTest` (`NPDevContract/dsl`), RED-confirmed by temporarily
disabling the new call and watching exactly the expected test fail (the other two, which assert
*absence* of an error, correctly stayed green — proving the RED was real, not a broken fixture);
(2) full `:dsl:test`, `:generator:test`, `:generator:behaviorTest` — green, so no existing
git-tracked sample/golden model in this repo crosses an aggregate boundary; (3) WmsOffice's real,
currently-deployed model (2 real aggregates, `Expedicao`/`Recebimento`) validated directly via
`:NPDevContract:dsl:validateModel` — 0 errors, 0 aggregate-boundary diagnostics, confirming the
platform's own richest real aggregate model was already compliant. `BREAKING.md` entry added (no
codemod — splitting a boundary-crossing flow is a real design decision, not a mechanical rewrite,
same "refuse rather than guess" posture as `docs/ACCEPTED_BOUNDARIES.md`'s B1).

## P6.2 3.6 — Bounded contexts / multi-namespace (1–2 weeks)

Unblocked by 2.A. One `namespace` per model = one bounded context per app. **This decides whether
NPDev can model a company or only a department** — a positioning question as much as a technical one.
Scope it only after F1, because the taxonomy may reveal that per-app contexts are sufficient for the
apps people actually build.

## P6.3 ⬥ 3.2 — Tell three specific people (owner)

**Still the item everything strategic is downstream of.** Public ≠ known. Every open strategic
question — is the hand-written-UI gap a dealbreaker; is the durable flow engine the killer feature or
an unused subsystem; does anyone want to author a model or only prompt an agent — is answered by
users and by nothing else.

Smallest useful version: repo description + topics; a short written pitch (SDD framing + the three
differentiators + the honest UI limitation); **three conversations**, not a broadcast; record first-hour
friction in the existing `NON_AUTHOR_FRICTION_LOG_TEMPLATE.md`.

---

# Part 7 — Sequencing

```
DAY 1 (~2 hr) ✅ DONE   P1.1 push  🔴 · P1.2 2.F stale · P1.3 3.3 stale · P1.5 REG-58 sentence
                        P1.4 split REG-59 → REG-61 OPEN/HIGH  ★

DAY 2 ✅ DONE           P2.1 tree-vs-ledger gate (Rules T1+T2, calibrated) -- found a 4th stale
                        instance (3.5/REG-4) while building it, fixed same day
                        P3.4 REG-54 dead methods · P3.3 REG-60 cosmetic

WEEK 1 ✅ DONE          P3.1 REG-57 durability/ack ordering  ★★ -- root cause: H2 WRITE_DELAY, not
                        ordering (ordering was eliminated by tracing code). 3/3 clean, sleep removed.
                        P3.2 REG-56 cross-JVM capability     ★  -- root cause: resume lost the
                        flow's own authorized role (ExecutionContext.resuming). 3/3 clean,
                        capabilityCall step restored.
                        P3.5 REG-61 (a)+(b) also done same day (NOT NULL preservation + named
                        UNIQUE-backfill refusal) -- pulled forward from Week 2, same subsystem as
                        REG-58/59 so doing it right after was cheaper than context-switching back.

WEEK 2 (started) ✅ P4.1 F1 taxonomy [1 d] DONE same day as Part 3 -- zero recurring classes found,
                mechanically and after human review; F6 stays gated. docs/SCREEN_TAXONOMY.md.
                P4.2 F2 contract substrate [5 d]  ← next

WEEK 3          P4.3 F3 provenance [4 d]
                P4.4 F4 impact gate ★★ [2 d]  → the money demo
                P4.5 F5 residuals [1 d]

WEEK 4          Part 5  2.E ledger migration [3 d]  → unblocks 3.4 (then 30 min)
                P6.1 3.7 aggregate tx boundary [2-3 d]

GATED           P4.6 F6 — only what F1 proves recurs
LATER           P6.2 3.6 bounded contexts — scope after F1
OWNER ⬥         P6.3 3.2 — three conversations. Everything strategic is downstream.
EXCLUDED ⛔     3.8 (needs all of 2.CD) -- 3.5 is no longer excluded, see the Part 0 correction:
                its stated REG-4 blocker was itself stale (REG-4 closed 2026-07-21); the real
                constraint is CI runtime cost, and re-scoping it is an unscheduled owner decision
```

## Why this order

**Part 1 before anything** — a public repo missing a HIGH-severity migration fix, plus two tree
entries that would send someone to redo finished work, plus a HIGH platform gap marked closed.

**Part 2 before the long work** — four drift instances in two days is a class, and every week of
Part 4 adds more status to keep honest. Fix the checker before generating more to check.

**REG-57/56 before 2.CD** — the durable engine is on public display *now*, and the demo works partly
by avoiding both bugs. Three weeks of frontend work does not change that; two weeks of engine work
does. And the completion criteria are concrete: the demo re-adds the capability step and drops the
sleep.

**F1 first inside 2.CD** — it routes F2–F6, it is one day, and its preconditions are met.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| ~~REG-57 is H2-only and gets deprioritised~~ | RESOLVED | Fixed: `WRITE_DELAY=0` at the URL level (both H2_LOCAL/H2_SERVER); Postgres confirmed unaffected (synchronous WAL commit, no analogous parameter) |
| ~~REG-56 root cause resists tracing again~~ | RESOLVED | Root cause found without needing new observability first: a live `PermissionDebugConfig` log line already showed the mismatched roles directly |
| `invocations` drifts from real routes | **High** | The dual-tree path-assertion test; `extract-routes.py` emits the fixture |
| Only one source tree gets searched again | **High** | Same test — it is the automated form of a mistake made twice by hand |
| F5-R1/R2 ship without a `BREAKING.md` entry | Medium | They missed the 2.A window; they are standalone breaking changes now. Still codemod-free (0/27) |
| 2.E slips again and drift recurs | **Medium** | Part 2's gate is the stopgap; 2.E is the cure |
| Frontend programme starts before F1 | Medium | F1 is 1 day and routes everything. Do not reorder |

## Definition of done

**Part 1 — ✅ DONE.** Pushed; 2.F and 3.3 marked DONE with the stale claims deleted; REG-59 split,
REG-61 filed OPEN/HIGH (then fixed, see Part 3); REG-58 cross-links REG-59.

**Part 2 — ✅ DONE.** Rules T1+T2 calibrated RED→GREEN (both real-git-history AND synthetic
controls), wired blocking via the existing `check-register-consistency.py` invocation; the pre-fix
tree fires on REG-59, 2.F, 3.3, **and a 4th instance found while building it (3.5/REG-4)**.

**Part 3 — ✅ DONE, all five REG rows.** REG-57 fixed and **the demo passes with the 5s sleep
deleted (3/3)**; REG-56 fixed and **the demo passes with a `capabilityCall` step restored (3/3)**;
REG-54/60 closed; REG-61 (a)+(b) with a named refusal and a documented recovery recipe. Every fix
root-caused by tracing real code, not guessed; every fix has a fast unit-test regression guard plus
live/integration proof; full relevant test suites green throughout (NPDevKernel:kernel 163/163,
com.finalexec.db 273/273).

**Part 4** — `SCREEN_TAXONOMY.md` covering all five apps; 12 catalogs with every path asserted against
both trees; bundle permission-filtered with `modelHash`; one manifest with three producers and every
generated surface emitting one automatically; **a real field rename names the exact screens that
break**; the 3 unexercised F5-V.2 steps closed against a new sample; F5-R1..R4 closed.

**Part 5** — `ledger/items/*.yml` is the source of truth; `docs/OPEN_ITEMS.md` generated; the 13
process docs unwired and archived (3.4).

**Part 6** — aggregate transactional boundary enforced with a test; **three people outside this
machine have tried it and what they hit is written down.**

---

*Companions: `docs/EXECUTION_TREES.md` (P1.2/P1.3) · `docs/FRONTEND_STRATEGY_PLAN.md` (Part 4 detail) ·
`docs/POST_PUBLIC_PLAN.md` (predecessor) · `docs/NPDEV_OPEN_ITEMS_REGISTER.md` (Part 1/Part 3) ·
`docs/architecture/AGGREGATE_WORKBENCH_PLAN.md` (F5-V.3) · `docs/FLOWS.md` ·
`NPDevSamples/scripts/run-durable-resume-demo.ps1` (REG-56/57 completion criteria).
Staged helpers: `<scratchpad>/helpers/`, `<scratchpad>/2c-staging/`.*
