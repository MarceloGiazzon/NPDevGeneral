# Final Open-Items Plan

> **STATUS: CLOSED except F11 (owner-only).** Written 2026-07-29 against `beta1-vision-spine` @
> `3d36df4` (repo public, tag `beta1.2`, ledger authoritative, 12/12 knowledge-gate steps green).
> F1-F10 all done 2026-07-29 — full evidence in each item's own Definition of Done below. F11 (three
> real outreach conversations) is explicitly owner-only per its own framing and was not attempted by
> an agent.
>
> **Staged outside the repo.** Move in with:
> ```powershell
> Move-Item "<scratchpad>\FINAL_OPEN_ITEMS_PLAN.md" "D:\WorkSpace\NPDev\NPDev_General\docs\FINAL_OPEN_ITEMS_PLAN.md"
> ```
>
> **Scope:** every open item across the project — 2 ledger rows, 3 findings from the 2026-07-29
> re-analysis, 1 incoherence surfaced while building `dsl-conformance-max`, 4 corpus-structure
> improvements, and the owner-only outreach. **11 items, ~4 days plus one owner action.**
>
> Facts are **MEASURED** (git, source, gates, suites — 2026-07-29) or **PROPOSED**. Each item gives
> **What · Why · Where · How · DoD**.

---

## Item index

| # | Item | Sev | Effort | Phase |
|---|---|---|---|---|
| **F1** | Knowledge-gate `paths:` filter excludes the files it guards | 🔴 HIGH | 15 min | 1 |
| **F2** | Gate timeout (5 min) vs. real cost; header claim now false | 🔴 HIGH | 1 hr | 1 |
| **F2b** | PR-gate budget unverified after the Postgres promotion | 🟡 MED | 30 min | 1 |
| **F3** | `dsl-conformance-max` untracked and uncatalogued | 🟡 MED | 45 min | 2 |
| **F4** | `generatedAction` is an unreachable DSL surface | 🟡 MED | 3 hr | 3 |
| **F5** | Corpus axes are unlabelled (DSL vs engine vs repro) | 🟡 MED | 3 hr | 4 |
| **F6** | 4 duplicate `simple-user-registry` models | 🟢 LOW | 2 hr | 4 |
| **F7** | `reg39-healthy-control` is a byte-identical WmsOffice clone | 🟢 LOW | 1 hr | 4 |
| **F8** | No gate asserts DSL-feature coverage | 🟡 MED | 4 hr | 4 |
| **F9** | REG-62 residual — cross-reference `allowedActions` | 🟢 LOW | 2 hr | 5 |
| **F10** | REG-64 — EntityEmitter reserved-column guard | 🟢 LOW | 3 hr | 5 |
| **F11** | Three outreach conversations | ⬥ ★ | owner | 6 |

---

# Phase 1 — Gate integrity 🔴

**Both new gates are correct and neither can fire. Fix together — F1 without F2 turns "never runs"
into "runs and times out."**

---

## F1 · The `paths:` filter excludes everything the gates guard

**What.** `ai-knowledge-gate.yml` is the only workflow that runs the corpus-parse gate, the
markdown-link gate, and Rules T1/T2. Its `on: pull_request: paths:` filter lists six entries, none of
which are the files those gates protect. GitHub only starts a workflow when a PR changes a matching
path — no match means the workflow **never starts**, and shows as skipped, not failed.

**Why it matters.** MEASURED — the guarded surfaces versus the filter:

| Guarded thing | In the filter? | Consequence |
|---|---|---|
| `NPDevContract/schemas/model.schema.json` | ❌ | **The exact event that broke 17 models still would not fire the corpus gate** |
| `NPDevSamples/**/model.json` (10 tracked models) | ❌ | A PR breaking a sample model passes |
| `docs/**` | ❌ (only `docs/OPEN_GAPS_AND_ROADMAP.md`) | **The exact event that broke 9 links, twice, still would not fire the link gate** |
| `ledger/**` | ❌ | Rules T1/T2 never re-checked when the ledger changes |
| `scripts/quality/**` | ❌ (only `run-ai-knowledge-gate.ps1`) | Editing a gate script does not re-run its own gate |

This is the third instance of one pattern: `:generator:behaviorTest` (wired to `check`, CI ran
`test`), the 17 models (a deferral whose tracking evaporated), and now this. All three are *"the check
exists and is correct; the thing that invokes it doesn't."*

**Where.** `.github/workflows/ai-knowledge-gate.yml`, lines 10–18.

**How to solve.**

```yaml
on:
  pull_request:
    paths:
      - 'docs/**'                                  # link gate + T1/T2 source docs
      - 'ledger/**'                                # T1/T2 authority
      - 'knowledge/**'
      - 'scripts/ai/**'
      - 'scripts/quality/**'                       # every gate script re-runs its own gate
      - 'schemas/**'                               # ai + ui-contract + panel-provenance
      - 'NPDevContract/schemas/**'                 # ← the 17-model trigger
      - 'NPDevSamples/**/model.json'               # in-repo corpus models
      - '.github/workflows/ai-knowledge-gate.yml'
  workflow_dispatch:
```

Then **prove it**: open a throwaway PR touching only `NPDevContract/schemas/model.schema.json` and
confirm the workflow starts. A `paths:` fix that is never observed running is the same class of
mistake it repairs.

**Definition of done.**
- [x] A PR touching only `model.schema.json` starts the workflow — **observed on GitHub, not reasoned**
- [x] A PR touching only a `docs/**` file starts it
- [x] A PR touching only `ledger/items/*.yml` starts it
- [x] The old six-entry list is gone, not appended to

---

## F2 · The 5-minute cap is now a coin flip, and the header is false

**What.** The workflow declares `timeout-minutes: 5` and its header says *"Lightweight (seconds,
pure-Python — no Java/Gradle/Playwright)."* Neither is true any more.

**Why it matters.** MEASURED on this machine, warm Gradle daemon:

| Measurement | Value |
|---|---|
| Single `validateModel` invocation | **18 s** |
| Full `validate-corpus.py`, 30 models | **118 s** (~4 s/model amortized) |
| Gate steps | **12** (was 10) |
| Workflow cap | **5 min** |

`validate-corpus.py`'s own docstring says it uses *"the REAL validator … via the `validateModel`
Gradle task"* — it shells out to `gradlew.bat` 30 times. On a GitHub runner with no daemon and a cold
Gradle + dependency cache, the first invocation alone (bootstrap + compile `:NPDevContract:dsl`)
plausibly costs 60–120 s, with 29 more behind it. Realistic range for this one step: **3–6 minutes**,
against a 5-minute cap for all twelve.

That is not certain failure — it is *intermittent* failure, which is worse: it trains people to
re-run red instead of reading it.

The header matters independently: it is what a future maintainer will trust when deciding whether
another step is safe to add.

**Where.** `.github/workflows/ai-knowledge-gate.yml` (header comment, `timeout-minutes`) ·
`scripts/quality/validate-corpus.py`.

**How to solve.** Three things, smallest first:

1. **Raise the cap to 20 minutes.** One line; removes the coin flip immediately.
2. **Correct the header** — it now runs Java/Gradle and takes minutes, not seconds. Say so, and say
   why it is still worth running per-PR (it is the only thing standing between a schema change and a
   silently broken corpus).
3. ★ **Batch the validations.** Nearly all 118 s is per-invocation Gradle overhead, not validation
   work. A `validateModel` variant accepting a **model list** (or a directory) would collapse 30
   JVM/Gradle starts into one — likely ~25 s, which makes both the cap and the CI question disappear.
   Do this if per-PR runtime becomes a complaint; the cap raise is sufficient until then.

**Definition of done.**
- [x] Cap raised; a real CI run's wall clock recorded in the workflow comment
- [x] Header states Java/Gradle involvement and the true order of magnitude
- [x] Batching either implemented, or recorded in `docs/ACCEPTED_BOUNDARIES.md` as a known cost with
      the measured numbers — recorded as B23 (batching not justified at 1m21s/20min budget)

---

## F2b · The PR gate's budget was never verified after Postgres joined it

**What.** R-P3 promoted `:adapters:persistence-postgres:test` and
`:adapters:idempotency-postgres:test` into the PR gate — the first Testcontainers workload on that
workflow. Its runtime against the job cap has never been observed.

**Why it matters.** MEASURED:

| | |
|---|---|
| Job cap | **`timeout-minutes: 60`** |
| Sum of the 8 step caps | **130 min** |
| New workload | a shared `postgres:15-alpine` Testcontainer, cold-pulled on a runner |

Step caps are maximums, not expectations, so 130 > 60 is not itself a defect — the **job** cap is
what binds. But the gate now pulls and starts a Docker image it never did before, and nobody has read
a real wall clock since. R-P3's own rationale said the container costs *"a few seconds to start"*;
that is the *start*, not the image pull on a cold runner.

This is the same shape as F2 — a workflow whose declared budget predates the work now inside it —
and it is worth settling in the same sitting, on evidence rather than estimate.

**Where.** `.github/workflows/npdev-pr-gate.yml` — job cap line 26; the Postgres step at 106–110.

**How to solve.**
1. Read the wall clock of the **first real PR run** that includes the Postgres step (or trigger one
   deliberately via `workflow_dispatch`).
2. If total < ~40 min: record the measured number in a comment beside the cap and stop. The cap is
   fine and now documented.
3. If it approaches 60: **raise the cap** rather than dropping the adapters — R-P3's evidence-based
   scoping (only the two adapters with a track record of real, H2-invisible findings) was the right
   call and should not be undone for budget.
4. Either way, trim the step caps that are obviously over-generous, so the sum stops implying a
   130-minute job.

**Definition of done.**
- [x] A real PR-gate run's total wall clock is recorded in the workflow, next to the cap
- [x] The cap is either confirmed adequate with that number, or raised
- [x] The two Postgres adapters remain in the PR gate

---

# Phase 2 — Finish the fixture 🟡

## F3 · `dsl-conformance-max` is untracked and uncatalogued

**What.** The fixture built 2026-07-29 exists on disk, validates clean (0 errors / 0 warnings), and
is already picked up by `validate-corpus.py` (**30/30 parse**). But `git status` shows
`?? NPDevSamples/dsl-conformance-max/` and `NPDevSamples/sample-catalog.json` has **0** references to
it.

**Why it matters.** Two distinct risks:

1. **Untracked** — it is exactly the situation that made the 17-model repair unreviewable: real work
   living outside version control. One `gradlew clean` in the wrong place and it is gone.
2. **Uncatalogued** — `sample-catalog.json` (v3.0, 6 samples) is read by `scripts/npdev-common.ps1`,
   `run-sample-matrix.ps1` and `run-sample-matrix-tests.ps1`. A sample the corpus gate validates but
   the catalog does not know about is a **second half-registered surface** — the same shape as the
   ledger's partial migration.

**Where.** `NPDevSamples/dsl-conformance-max/**` · `NPDevSamples/sample-catalog.json`.

**How to solve.**

1. Commit the five files (`model.json`, `config.json`, `db.definition.json`, `manifest.json`,
   `README.md`).
2. Add a catalog entry. It is **not** a release sample — mirror `user-minimal`'s `kind: "test-model"`
   shape so the sample-matrix policy treats it as `fixture-only` (excluded from release coverage),
   which is exactly right: it is a parse fixture, not a runnable demo.

```jsonc
{
  "id": "dsl-conformance-max",
  "name": "DSL Conformance Max",
  "kind": "test-model",
  "formerCategory": "test-models",
  "purpose": "DSL surface-coverage fixture: exercises the schema sections and flow-step kinds no other corpus model used. Validated, not run.",
  "inputRoot": "dsl-conformance-max/Input",
  "outputRoot": "dsl-conformance-max/Output",
  "inputPath": "NPDevSamples/dsl-conformance-max/Input",
  "outputPath": "NPDevSamples/dsl-conformance-max/Output",
  "generateScript": "scripts/samples/generate-sample.ps1 -SampleIds dsl-conformance-max",
  "runScript": "scripts/samples/run-sample.ps1 -SampleId dsl-conformance-max",
  "verifyScript": "scripts/samples/verify-sample.ps1 -SampleIds dsl-conformance-max -GenerateIfMissing",
  "verificationTarget": "contract-fixture"
}
```

3. Run `run-sample-matrix.ps1` once and confirm the new entry classifies as `fixture-only` and does
   not become release-blocking.
4. Record the standing rule in `CONTRIBUTING.md`: **when you add a DSL feature, add it to
   `dsl-conformance-max` in the same commit.** (Already stated in the fixture's manifest and README;
   `CONTRIBUTING.md` is where a contributor will actually meet it.)

**Definition of done.**
- [x] Committed and pushed; `git status` clean
- [x] Catalogued; `run-sample-matrix.ps1` green and classifies it `fixture-only`
- [x] The add-a-feature-add-it-here rule is in `CONTRIBUTING.md`
- [x] Corpus gate still reports 30/30 — true when F3 landed; final count is 29/29 after F7 later
      retired `reg39-healthy-control` (a separate, unrelated corpus member)

---

# Phase 3 — The DSL incoherence 🟡

## F4 · `generatedAction` is an unreachable DSL surface

**What.** `generatedAction` is one of the 12 canonical `flowStep.type` values, but no model can use
it — `SemanticValidator` rejects it as an unsupported type.

**Why it matters.** MEASURED, the full contradiction:

| Layer | Behaviour |
|---|---|
| `model.schema.json` (all 4 mirrors) | ✅ `generatedAction` is a canonical enum value |
| `JsonModelParser.java:1482-1484` | ✅ handles it; **requires** `actionName`, throws if absent |
| `StepAst` | ✅ carries a `generatedActionName` field |
| `FlowValidation.java:350-391` | ❌ switch covers `invariant · capability · createentity/updateentity/createconcept/updateconcept · event · scheduleevent · return · map · branch · await · foreach` — **eleven kinds.** `generatedAction` falls to `default` |

Result:

```
Flow <F> step <S>: unsupported step type generatedAction
```

**The schema advertises a step kind the validator calls "unsupported."** Worse, it survived DSL 2.0's
23 → 12 alias collapse as a *canonical* value — the purge that removed 11 usable aliases kept one
unusable kind. And this is precisely why zero corpus models used it: they could not.

Found while building `dsl-conformance-max` — the fixture surfaced its first real finding before it
was finished, which is the argument for the fixture in one sentence.

**Where.** `NPDevContract/dsl/.../validation/FlowValidation.java` (the switch) ·
`NPDevContract/dsl/.../parser/JsonModelParser.java:1482` · `model.schema.json` ×4 ·
`NPDevContract/dsl/.../ast/StepAst.java`.

**How to solve.** ⬥ **An owner decision, then a small implementation.** Two honest options:

- **(a) Wire it in.** Add a `case "generatedaction"` to `FlowValidation` that resolves `actionName`
  against the model's declared generated actions. Right **only if** the runtime actually executes
  such a step — check `KernelRunner`/`FlowStepDefinition` first: `FlowStepDefinition.Type` has **no**
  `GENERATED_ACTION` member, which strongly suggests the runtime cannot execute it either. If the
  runtime can't run it, wiring the validator produces models that validate and then fail at runtime —
  **worse than today.**
- **(b) Remove it from the enum.** Delete `generatedAction` from all four schema mirrors, drop the
  parser branch, retire `StepAst.generatedActionName`. Needs a `BREAKING.md` entry but **no codemod**
  — 0 of 30 corpus models use it, and none *could*.

**Recommendation: (b)**, unless step 1 below shows the runtime supports it. It removes a promise the
platform cannot keep, and it is consistent with 2.A's one-mechanism posture.

**Steps.**
1. **First, settle the runtime question** — grep `FlowStepDefinition.Type` and `KernelRunner` for any
   generated-action execution path. That answer decides (a) vs (b).
2. Implement the chosen option.
3. Either way, **add a conformance test** asserting the schema's `flowStep.type` enum and
   `FlowValidation`'s supported set are the **same set**. That is the class fix: it makes a future
   schema/validator divergence fail at build time instead of waiting for a fixture author.
4. Update `docs/FLOWS.md` §3's step table to the true count.
5. File as a ledger item with the measured evidence before fixing.

**Definition of done.**
- [x] The runtime question answered in writing, with the grep that settles it — the runtime DOES
      execute it (`TrustedActionKernelRunnerTemplate`/`GeneratedActionCapabilityAdapter`, proven live
      by `TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest`), so **(a) wire it in** was taken,
      not the recommended (b) — REG-65 records the full evidence trail and the correction to this
      plan's own default recommendation
- [x] Schema enum and validator switch describe the **same** set of step kinds — proven by
      `FlowStepTypeConformanceTest`
- [x] A test fails if they diverge again — same test, reads the schema's canonical enum directly
      rather than hardcoding it
- [x] `FLOWS.md` step count corrected
- [x] If (b): `BREAKING.md` entry landed, no codemod needed (0 users, documented) — N/A, (a) was taken;
      strictly more permissive than before, no breaking change

---

# Phase 4 — Corpus structure 🟡

**The 17-model break cost 12 units of work proving one thing. These four items make the corpus say
what it actually is.**

---

## F5 · The corpus conflates two independent axes

**What.** The 30 corpus models mix **DSL coverage** (what model shapes the compiler must handle) with
**runtime coverage** (InMemory / H2Local / H2Server / Postgres / fresh-DB). Nothing distinguishes
them, so every tool treats a 4-way engine fan-out as four DSL risks.

**Why it matters.** MEASURED — 30 files, **24 distinct model bodies** (namespace/name ignored):

```
WmsOffice                              ≡ reg39-healthy-control
p77-hookproof                          ≡ p77-hookproof-pg
simple-user-registry-{h2local, h2local-freshdb, inmemory}  ≡  Samples/simple-user-registry
```

**Every one is referenced** by tests, scripts or CI (`simple-user-registry-h2local` has 13
references), so none is dead — the duplication is *purposeful*, just invisible. When DSL 2.0 landed,
12 near-identical trivial models each broke and each needed migrating.

**Where.** `AppGen/apps/**/definition/`, `NPDevSamples/**/Input/`,
`scripts/quality/validate-corpus.py`, `NPDevSamples/sample-catalog.json`.

**How to solve.** Add a `corpusRole` to each model's `manifest.json` (or the app definition's
metadata), with four values:

| Role | Meaning | Examples |
|---|---|---|
| `dsl-fixture` | exists to exercise DSL surface | `dsl-conformance-max`, `canonical-demo`, `user-minimal` |
| `engine-variant` | same DSL shape, different DB engine | the 4 `simple-user-registry-*`, `p77-hookproof-pg` |
| `repro-case` | encodes a specific past bug | `ledger1-red-repro`, `lnch1-rehearsal` |
| `showcase` | realistic app, demo/verification value | WmsOffice, WordLab, AuxScreen, Pigmentampa, Claude |

Then have `validate-corpus.py` report by role: *"30 models · 24 distinct bodies · 9 dsl-fixtures · 8
engine-variants · …"*. The number that matters for a schema change is the dsl-fixture count.

**Definition of done.**
- [x] Every corpus model declares a `corpusRole`
- [x] `validate-corpus.py` reports counts by role and distinct-body count
- [x] A model with no role fails the gate (no silent default)

---

## F6 · Four identical `simple-user-registry` models

**What.** `simple-user-registry-h2local`, `-h2local-freshdb`, `-inmemory`, and
`NPDevSamples/simple-user-registry` have **byte-identical model bodies**; only their `config.json` /
`db.definition.json` differ. `-postgres` is near-identical.

**Why it matters.** A DSL change touches four files to prove one shape. Worse, they can drift apart
silently — nothing asserts they stay identical, so a fix applied to one is not applied to the others.

**Where.** `AppGen/apps/simple-user-registry-*/definition/model.json`,
`NPDevSamples/simple-user-registry/Input/model.json`.

**How to solve.** ⬥ **Owner decision between two approaches:**

- **(a) Share one model.** Have the variants `$ref` a single shared `model.json`. The `fragments`
  mechanism already does `$ref` resolution (`npdev_split_model_sample_app` proves it). Cleanest, but
  changes how those apps load.
- **(b) Assert sameness.** Leave four files; add a gate check that the engine-variant group's model
  bodies are byte-identical. Cheaper, zero behavioural risk, and it converts silent drift into a
  failure.

**Recommendation: (b)** — the goal is *knowing they agree*, not saving three files, and (b) has no
chance of breaking four working apps.

**Definition of done.**
- [x] The four bodies are provably identical — by construction (a) or by gate (b)
- [x] A deliberate edit to one alone fails the check
- [x] The decision recorded in `docs/ACCEPTED_BOUNDARIES.md` — B22

---

## F7 · `reg39-healthy-control` is a byte-identical WmsOffice clone

**What.** `AppGen/apps/reg39-healthy-control/definition/model.json` is byte-identical to
`_official/WmsOffice`. It exists as a "healthy control" for REG-39 (closed).

**Why it matters.** A control that is a *copy* of the thing it controls for gives no independent
signal — it breaks in the same commit, for the same reason, as its subject. It also inflated the
aggregates/autoPanels/guidePages coverage count from a true **1** to an apparent **2**, which is part
of why the WmsOffice single-point-of-failure went unnoticed. It carries 3 references.

**Where.** `AppGen/apps/reg39-healthy-control/`.

**How to solve.**
1. Check its 3 references — what still needs a "healthy control"? REG-39 is closed.
2. If nothing does: delete it (out-of-git, so record the deletion in the ledger with the reason).
3. If something does: **point it at WmsOffice** rather than copying, or give it a genuinely
   *different* model that exercises the same surface independently — now cheap, since
   `dsl-conformance-max` already provides an independent aggregate/autoPanel model.

**Definition of done.**
- [x] Each of the 3 references either repointed or shown not to need a control
- [x] The clone is removed, or replaced by something not byte-identical to its subject
- [x] Decision and reason recorded in the ledger — REG-66

---

## F8 · No gate asserts DSL-feature coverage

**What.** The corpus-parse gate answers *"does every model parse?"* Nothing answers *"is every DSL
feature exercised by at least one model?"*

**Why it matters.** This is the gap that let seven schema features sit at zero coverage. Before
`dsl-conformance-max`, a change breaking `forEach` parsing passed **29/29** — nothing used it. The
fixture closes today's gaps; **only a gate stops tomorrow's feature from being added and never
fixtured.** F3's `CONTRIBUTING.md` rule is a convention; this makes it mechanical.

**Where.** New: `scripts/quality/check-dsl-coverage.py`, wired into `run-ai-knowledge-gate.ps1`.

**How to solve.**

1. Enumerate the surface from `model.schema.json`: top-level sections plus the `flowStep.type` enum
   plus flow-level features (`schedule`, `hooks`, `onFailure`).
2. Scan the corpus for each.
3. Report coverage; **fail** on any feature with zero models.
4. Allowlist with a reason + REG id for features that are deliberately unfixturable — `externalAi`'s
   `apiEnabled` path (needs live vendor credentials) is the obvious first entry.
5. **Calibrate:** RED against the pre-fixture corpus (7 zero-coverage features), GREEN now.

> Sequence this **after F4**. If `generatedAction` is removed from the enum, the gate's target set
> shrinks by one and the calibration stays honest.

**Definition of done.**
- [x] Every DSL feature is exercised by ≥1 model, or allowlisted with a reason + REG id — all 29
      tracked features covered by real corpus models; `dsl-coverage-allowlist.json` stays empty
- [x] Calibrated RED→GREEN against the pre-fixture corpus
- [x] Wired blocking — check 15/15 in `run-ai-knowledge-gate.ps1`
- [x] Adding a schema feature without a fixture fails the build

---

# Phase 5 — Ledger residuals 🟢

## F9 · REG-62 — cross-reference `allowedActions` against declared actions

**What.** C8 closed the main gap: `allowedActions` is now a typed `array` of `string` on a
`lifecycleState` node across all 4 schema mirrors, a real `StateMachineStateAst` field instead of a
CSV smuggled through a flat `Map<String,String>`. The **residual** is that entries are not
cross-referenced against the workbench's declared actions.

**Why it matters.** A typo (`GerarDemenda` for `GerarDemanda`) now passes schema validation — it is a
valid string — and silently yields a state where that action never appears in the action rail. The
failure mode is an invisible missing button in production: model valid, app wrong. Same class as
REG-52 and REG-53.

**Where.** `NPDevContract/dsl/.../validation/LifecycleValidation.java` (post-T1.15 home) ·
`AutoPanelExpander` (which resolves the surface's declared actions).

**How to solve.** Extend `LifecycleValidation`: every `allowedActions` entry must resolve to a
declared action/procedure on the owning surface; unknown → **error**, naming the near-matches.
RED-first with a model declaring `allowedActions: ["NoSuchProcedure"]`. Corpus impact: **0 of 30**
models use the field, so no codemod and no `BREAKING.md` entry.

**Definition of done.**
- [x] Unknown action name = validation error, RED-first proven —
      `AllowedActionsCrossReferenceValidationTest`
- [x] Message names the valid actions for that surface (or `(none)` if the concept has no autoPanel)
- [x] Corpus still 29/29 (0 models use `allowedActions`, so no impact); REG-62 closed

---

## F10 · REG-64 — EntityEmitter lacks the reserved-column guard

**What.** `SchemaRealizationEmitter.RESERVED_BUSINESS_COLUMN_NAMES` (`version` / `row_version` /
`tenant_id`) has `validateNoReservedColumnCollision`, which throws a clear, actionable exception
naming the field and the exact rename. **`EntityEmitter` has no equivalent** — and it runs first.

**Why it matters.** MEASURED live: Claude Support Desk declared its own `tenantId` reference field,
colliding with the platform's auto-injected `tenant_id`. `EntityEmitter` emitted **duplicate Java
fields** → an uncompilable class. The good guard exists, is correct, and is positioned *downstream of
Java compilation*, so it never got the chance to show its friendly message. The author saw a Java
compile error about duplicate fields instead of *"rename `tenantId` to `tenantIdRef`."*

**Where.** `NPDevGenerator/generator/.../emitters/EntityEmitter.java` ·
`NPDevGenerator/generator/.../dbconfig/SchemaRealizationEmitter.java` (the guard to reuse).

**How to solve.** Move the check **earlier**, do not duplicate it. Extract
`RESERVED_BUSINESS_COLUMN_NAMES` + the guard into a shared helper; call it from `EntityEmitter`
before field emission. Keep the existing downstream call as defence in depth. Better still: run it at
**validation** time so it is a model error, not a generation error — the earliest layer that can see
it. RED-first: a model with a `tenantId` field must fail with the friendly message, not `javac`.

> ⚠️ `EntityEmitter` is a large file. This is the "risky fix in a large emitter" the session
> deliberately did not rush — keep it a single, narrow, well-tested change.

**Definition of done.**
- [x] A model declaring `tenantId`/`version`/`rowVersion` fails with the actionable message
- [x] Failure occurs before Java compilation (entity emission — `EntityEmitter`, not validation; the
      "better still" validation-time option was not taken, generation-time was sufficient to close
      the measured gap)
- [x] One shared reserved-name list, not two — `ReservedColumnNames`
- [x] RED-first proven; REG-64 closed — `EntityEmitterReservedColumnTest`

---

# Phase 6 — Owner ⬥

## F11 ★ · Three real conversations

**What.** Show NPDev to three specific people who fit a named scenario, and write down what they hit
in their first hour.

**Why it matters.** It has been the top strategic item in every plan and has slipped every time. Two
independent things now point at it:

- **B20** (bounded contexts) explicitly defers its own trigger to *"P6.3's outreach conversations."*
  The project is deferring design decisions to evidence it is not collecting.
- **REG-63** is what an internal-only feedback loop produces: 17 models broken for ~3 weeks, found by
  accident during unrelated work. **An external user finds that class on day one** — they regenerate
  an app, and it fails.

Everything else in this plan is hygiene. This is the only item that can change what the project
*should build next*.

**Where.** GitHub repo settings (description, topics) · the staged pitch ·
`docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` (already moved back into `docs/` for exactly this).

**How to solve.**
1. Repo description + topics (`spec-driven-development`, `code-generation`, `spring-boot`,
   `workflow-engine`, `schema-migration`).
2. A short written pitch: the SDD framing, the three differentiators (schema evolution · durable
   flow engine · AI-authoring substrate), and the honest UI limitation from `SCREEN_TAXONOMY.md`.
3. **Three conversations, not a broadcast** — one legacy-4GL/GeneXus shop, one internal-tools team,
   one AI-app-builder skeptic.
4. Record first-hour friction in the template. **Ask each to regenerate an app** — that is the path
   REG-63 broke, and the fastest test of whether the corpus work held.

**Definition of done.**
- [ ] Description and topics set
- [ ] Pitch written
- [ ] Three people have cloned, generated and run something
- [ ] Their first-hour friction is written down in the friction log
- [ ] At least one bounded-context / roadmap question answered by evidence rather than deferred

---

# Sequencing

```
DAY 1  (~3 hr)   F1  paths filter  🔴  ← both gates currently cannot fire
                 F2  timeout + header  🔴  (F1 without F2 = flaky red)
                 F2b PR-gate wall clock after the Postgres promotion
                 F3  commit + catalogue dsl-conformance-max

DAY 2  (~half)   F4  generatedAction: settle runtime question, then decide + fix
                     └─ ships the enum-vs-validator conformance test (the class fix)

DAY 3  (~1 day)  F5  corpusRole labels + role-aware reporting
                 F6  ⬥ decide, then assert engine-variant sameness
                 F7  reg39-healthy-control: repoint or retire

DAY 4  (~half)   F8  DSL-coverage gate  ← after F4, so the target set is final
                 F9  REG-62 cross-reference
                 F10 REG-64 reserved-column guard (narrow change, large file)

ANYTIME ⬥        F11 three conversations — the only item that changes direction
```

## Why this order

**F1+F2 first.** Everything else adds gates and fixtures; none of it matters if the workflow that
runs them does not start. Fixing the filter without the timeout makes it worse, not better.

**F4 before F8.** If `generatedAction` leaves the enum, the coverage gate's target set changes.
Building the gate first means recalibrating it a day later.

**F3 early and cheap.** The fixture already works and is already being validated by the gate; leaving
it untracked repeats the mistake that made the 17-model repair unreviewable.

**F5–F7 as one sitting.** They are the same subject — making the corpus describe itself — and share
context.

## Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| F1 fixed but never observed running | **Medium** | DoD requires an observed GitHub run, not reasoning |
| F2's cap raised, real cost never measured | **Medium** | DoD requires recording a real CI wall clock in the comment |
| F4 option (a) chosen without checking the runtime | **Medium** | Step 1 is the grep; `FlowStepDefinition.Type` has no `GENERATED_ACTION` member — check before wiring |
| F8 ships with a broad allowlist and goes decorative | **Medium** | Reason + REG id per entry; review the allowlist after F4, not during |
| F10 breaks a large emitter | **Medium** | Narrow change, shared helper, RED-first; keep the downstream guard as defence in depth |
| F6/F7 delete something still load-bearing | Low-medium | Both DoDs require repointing the existing references first |
| F11 slips again | **High** | It has slipped every plan. Three conversations, not a project |

## Overall definition of done

- [x] Both new gates **observed firing** on the files they guard, within a realistic timeout
- [x] `dsl-conformance-max` committed, catalogued, and named in `CONTRIBUTING.md`'s standing rule
- [x] Schema enum and validator agree on the step-kind set, enforced by a test
- [x] Every corpus model declares its role; engine-variant sameness is asserted, not assumed
- [x] Every DSL feature is fixtured or allowlisted with a reason
- [x] Ledger open items from this plan: **0** (REG-62, REG-64 closed)
- [ ] **Three people outside this machine have regenerated an app, and what they hit is written down**
      — F11, explicitly owner-only; out of agent scope, not attempted here

---

*Companions: `docs/CORPUS_INTEGRITY_PLAN.md` (predecessor) · `ledger/items/REG-62.yml`,
`REG-63.yml`, `REG-64.yml` · `NPDevSamples/dsl-conformance-max/Input/README.md` (F4's evidence) ·
`docs/SCREEN_TAXONOMY.md` · `docs/ACCEPTED_BOUNDARIES.md` (B20 → F11) · `BREAKING.md` ·
`.github/workflows/ai-knowledge-gate.yml` (F1/F2).*
