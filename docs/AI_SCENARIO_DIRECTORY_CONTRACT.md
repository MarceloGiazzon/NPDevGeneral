# AI Scenario Directory Contract

An AI scenario is a directory rooted at `ScenarioRoot`. The authoritative shape of its manifest is
`schemas/ai/scenario-manifest.schema.json`; this page summarizes it and the rules around it.

Only `scenario.manifest.json` is required. Which other files exist depends on the scenario: the real corpus
(`NPDevSamples/ai-scenarios/`, 31 scenarios) uses

```text
ScenarioRoot/
  scenario.manifest.json        every scenario
  ai-model.json                 the authored AI model (most scenarios)
  ai-config.json                the authored AI config (many)
  model.json / config.json      the compiled-shape model and config (some)
  expected-behavior.json        root expected behavior (about half)
  ai-verification-report.json   the verification report a negative scenario is checked against (some)
```

plus scenario-specific assets (for example `create-users-panel-procedure/custom-panel.json` and `custom-procedure.json`).
There are no `fixtures/` or `assertions/` directories in the current corpus.

All paths in `scenario.manifest.json` are relative to `ScenarioRoot`. Paths must resolve inside that root.

## Scope

Only top-level scenario directories under `NPDevSamples/ai-scenarios/` are scenarios. The former `deferred/` container (which held
the trusted-source and custom-asset fixtures) was deleted on 2026-08-21: `create-users-panel-procedure` and
`trusted-source-path-traversal` are ordinary top-level scenarios now, and custom assets sit inside the scenarios that use them. A few
validation scripts still carry a dead `deferred` name filter (`run-ai-schema-validation.ps1`, `run-ai-contract-normalizer-tests.ps1`,
`run-ai-beta-gate.ps1`); it matches nothing.

Unsupported custom-only `app.kind` values must fail cleanly at `ai-model-schema`. Declarative custom panel metadata is admitted through the
standard `expanded-beta-application` panel contract; custom procedures and custom procedure-plus-panel app kinds remain unsupported.

## Manifest Contract

`scenario.manifest.json` requires `schemaVersion`, `scenarioId`, `kind`, `expectedOutcome` and `files`:

- `schemaVersion`: `ai-scenario-manifest.v1` (all 31 manifests use it); the schema also accepts `ai-scenario.v1`
- `scenarioId`: stable lower-case identifier
- `kind`: `positive` or `negative` (23 negative, 8 positive today); the schema also allows four `ai-*-functional-beta` values
- `files`: the model, config, expected-behavior and any other assets
- `expectedOutcome`: `pass` or `fail`

Optional keys: `expectedFailureStage` (used by 23 scenarios, a stage such as `ai-model-schema`, `scope-policy` or `command-policy`),
`requestedSurfaces`, `expectedErrorCode`, `runtime` (strict execution and supported-core profile) and `operations` (bounded actions the
harness may execute). No manifest in the corpus declares `runtime` or `operations` today.

## Behavior Contract

`expected-behavior.json` declares:

- scenario id
- comparison mode
- expected result class
- stable JSON-path-like assertions over runtime evidence

Expected behavior must use stable identifiers such as concept, procedure, panel, action, field, and manifest names. It must not rely on UI copy as the only assertion target.

## Behavior Inheritance and Precedence

Scenario behavior is resolved from broad intent to specialized proof. The manifest is the root contract; behavior files refine it but may not contradict it.

Precedence, from highest authority to most specialized refinement:

1. `scenario.manifest.json`
2. `expected-behavior.json`
3. `expected-panel-behavior.json`
4. `expected-procedure-behavior.json`

(`expected-workflow-behavior.json` and `expected-verification-behavior.json` were once listed here; no schema, scenario or script defines them.)

`scenario.manifest.json` defines whether the scenario is positive or negative, the expected outcome, and the expected failure stage for negative scenarios. `expected-behavior.json` defines the root behavior class for the whole scenario and should align with the manifest: positive scenarios use `PASS_` classes, while negative scenarios use `FAIL_` or `NEGATIVE_` classes. The current corpus conforms (all 14 `expected-behavior.json` files align with their manifest `kind`), but **no live checker enforces it**: the coherence validator (`validate-scenario-coherence.ps1`) was deleted on 2026-08-13, and `expectedClass` is read only by `scripts/ai/build_knowledge.py`.

Specialized files inherit the manifest and root expected behavior. They can narrow a surface-specific assertion, but they cannot turn a root failure scenario into a positive scenario or make a positive scenario depend on a surface-level failure. If a negative scenario stops before specialized behavior is reached, the specialized behavior file must state that explicitly with a `FAIL_` or `NEGATIVE_` class such as `NEGATIVE_NOT_REACHED`.

When a scenario declares both `ai-model.json` and `model.json`, they must describe one coherent business vocabulary unless `scenario.manifest.json` explicitly allows a domain mismatch for that scenario. Expected behavior references must resolve to identifiers declared in sibling scenario files.
