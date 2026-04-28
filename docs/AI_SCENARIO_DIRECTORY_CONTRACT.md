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

## Manifest Contract

`scenario.manifest.json` declares:

- `schemaVersion`: `ai-scenario.v1`
- `scenarioId`: stable lower-case identifier
- `kind`: scenario family
- `files`: model, config, expected behavior, and optional custom assets
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
