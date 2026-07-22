# AI Scenario Directory Contract

An AI scenario is a directory rooted at `ScenarioRoot`.

```text
ScenarioRoot/
  model.json
  config.json
  expected-behavior.json
  scenario.manifest.json
  fixtures/
  assertions/
```

All paths in `scenario.manifest.json` are relative to `ScenarioRoot`. Paths must resolve inside that root.

## Active And Deferred Scope

Only top-level scenario directories under `golden-ai-scenarios/` are active golden scenarios. The `golden-ai-scenarios/deferred/` directory is a scope container, not a scenario, and active validation scripts exclude it from scenario coherence, schema validation, AI contract normalization, mapping coverage, and the AI beta gate.

CP3 uses the locked Path B decision for trusted-source scope. Trusted-source fixtures are preserved under `golden-ai-scenarios/deferred/trusted-source/` until a later approved checkpoint proves full trusted-source behavior from fresh evidence.

CP3 also uses the locked custom Path B decision for unsupported custom-only `app.kind` values. CP12 narrows that rule by admitting declarative custom panel metadata through the standard `expanded-beta-application` panel contract. Custom procedures and custom procedure-plus-panel app kinds remain unsupported and must fail cleanly at `ai-model-schema`; deferred custom source assets must not be referenced by active manifests.

## Manifest Contract

`scenario.manifest.json` declares:

- `schemaVersion`: `ai-scenario.v1`
- `scenarioId`: stable lower-case identifier
- `kind`: scenario family
- `files`: model, config, expected behavior, and active supported assets
- `runtime`: strict execution and supported-core profile
- `operations`: bounded actions the harness may execute
- `expectedOutcome`: expected pass or failure class

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
5. `expected-workflow-behavior.json`
6. `expected-verification-behavior.json`

`scenario.manifest.json` defines whether the scenario is positive or negative, the expected outcome, and the expected failure stage for negative scenarios. `expected-behavior.json` defines the root behavior class for the whole scenario and must align with the manifest: positive scenarios use `PASS_` classes, while negative scenarios use `FAIL_` or `NEGATIVE_` classes.

Specialized files inherit the manifest and root expected behavior. They can narrow a surface-specific assertion, but they cannot turn a root failure scenario into a positive scenario or make a positive scenario depend on a surface-level failure. If a negative scenario stops before specialized behavior is reached, the specialized behavior file must state that explicitly with a `FAIL_` or `NEGATIVE_` class such as `NEGATIVE_NOT_REACHED`.

When a scenario declares both `ai-model.json` and `model.json`, they must describe one coherent business vocabulary unless `scenario.manifest.json` explicitly allows a domain mismatch for that scenario. Expected behavior references must resolve to identifiers declared in sibling scenario files.
