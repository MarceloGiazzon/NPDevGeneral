# dsl-conformance-max

**A DSL surface-coverage fixture. Not an authoring example — do not copy this to start an app.**

## Why it exists

A corpus-wide measurement on 2026-07-29 (across all 29 `model.json` files under `AppGen/apps` and
`NPDevSamples`) found that the DSL corpus exercised roughly 60% of `model.schema.json`:

| Coverage before this fixture | Sections |
|---|---|
| **Zero models** | `selectors`, `externalAi`, step `forEach`, step `generatedAction`, `onFailure` (compensation), `flow.schedule` (cron), `flow.hooks` |
| **One model** | `domainTypes` (canonical-demo), `documents` (superuser-admin-console), `fragments` (npdev_split_model_sample_app), step `map`, step `scheduleEvent` |
| **"Two" models — but byte-identical copies** | `aggregates`, `autoPanels`, `guidePages` — WmsOffice and `reg39-healthy-control` |

That last row is the sharp one: the **Aggregate Workbench**, one of the platform's three
differentiated capabilities, had exactly *one* independent model exercising it. `docs/FLOWS.md`
already conceded the loop/compensation half of this ("no real sample model in this repo uses
`FOR_EACH` or `onFailure`"); the measurement showed it was seven features, not two.

A schema, parser, or compiler change that broke any of those would have passed the corpus-parse gate
29/29, because nothing used them.

## What it covers

`externalAi` (`egress: "denied"` — declared, inert, no vendor opt-in) · `domainTypes` · `selectors` ·
`aggregates` · `autoPanels` · `documents` · `guidePages` · `queries` · `procedures` · `panels` ·
`ruleProfiles` · `flow.schedule` (cron) · `flow.specializes` + `flow.hooks` · steps `forEach`
(with `maxLoopIterations`), `onFailure`, `map`, `scheduleEvent` (BOTH delivery modes — R2.4 made
these two different code paths, so `delaySeconds: 0` publishes inline and `delaySeconds: 3600`
writes a durable `npdev_scheduled_event` row instead; one witness each is required),
`invariantCheck`, `createConcept`, `emitEvent`, `capabilityCall`, `return`, **`generatedAction`**
(`PlaceWidgetOrder`'s `score-order-risk` step — see below, fixed 2026-07-29).

## What it deliberately does NOT cover

- **`awaitEvent`** — `NPDevSamples/durable-workflow-demo` owns that, end to end, including a real
  process restart. Duplicating it here would add a second fixture to maintain for no new coverage.
- **`fragments`** — needs a second `$ref`-ed file; `npdev_split_model_sample_app` already covers
  `$ref` resolution.

## Finding surfaced while building this, now fixed: `generatedAction` was an unreachable DSL surface

`generatedAction` is one of the 12 canonical `flowStep.type` values in `model.schema.json` (all four
mirrors). `JsonModelParser` handles it and *requires* `actionName`
(`JsonModelParser.java:1482-1484`). `StepAst` carries a `generatedActionName` field. `ModelCompiler`
already compiled it into a `CompiledCapabilityCall("GeneratedActionCapability", ...)`, and the
generator/runtime (`TrustedActionKernelRunnerTemplate`, `GeneratedActionCapabilityAdapter`) already
had full, tested support for executing one — proven live by
`TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest`, which builds and boots a real packaged
app with a `generatedAction`-shaped compiled step.

But `SemanticValidator` had no case for it: `FlowValidation.java`'s step-type switch handled
`invariant · capability · createentity/updateentity/createconcept/updateconcept · event ·
scheduleevent · return · map · branch · await · foreach` — 11 kinds, not `generatedAction`'s 12th —
so it fell through to:

```
Flow <F> step <S>: unsupported step type generatedAction
```

The schema advertised a step kind the validator refused, and the error message called it
"unsupported" while the schema called it valid, despite the compiler and generator/runtime already
supporting it end to end. That runtime-proof test never surfaced this because it hand-constructs
`CompiledModel` objects directly, bypassing `JsonModelParser`/`SemanticValidator` entirely.

**Fixed 2026-07-29** (`docs/FINAL_OPEN_ITEMS_PLAN.md` F4, `ledger/items/REG-65.yml`): added the
missing case to `FlowValidation`'s switch. This fixture's own `PlaceWidgetOrder` flow now includes a
real `generatedAction` step (`score-order-risk`) as the closing proof — filed and worked around no
longer applies; it is used.

## How it is used

**Validated and generated** (2026-07-29, `docs/CLOSEOUT_PLAN.md` G2 — was "validated, not run" until
then). `scripts/quality/validate-corpus.py` parses it on every AI-knowledge-gate run;
`scripts/quality/check-dsl-conformance-generates.py` additionally generates it for real
(emission only, no build/boot) on every PR gate run, and asserts the rare features it exists to
carry actually survive into the compiled output — not just that generation exits 0.

```powershell
.\gradlew :NPDevContract:dsl:validateModel `
  -PmodelPath=<this dir>\model.json -PreportOut=<somewhere>\report.json

# or generate it for real (what the PR gate does):
pwsh .\NPDevSamples\scripts\generate-sample-app.ps1 -SampleId dsl-conformance-max -NoAssembleFinalApp
```

Current status: **passed, 0 errors, 0 warnings; generates cleanly, all tracked features present.**

## The rule that keeps this useful

> **When you add a DSL feature, add it here in the same commit.**

That is what turns "the corpus covers 60% of the schema" from something someone has to go measure
into something the gate tells you.
