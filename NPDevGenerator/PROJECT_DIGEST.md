# NPDevGenerator Project Digest

## Purpose
NPDevGenerator compiles NPDev models/configs into generated artifacts and assembles runnable final apps from the RuntimeHost base template.

## Role In Workspace
- Consumes DSL/compiler output from NPDevContract.
- Consumes Kernel abstractions used by generated runtime code.
- Emits generated code, manifests, migration plans, compiled assets, and final assembled app layouts.

## Main Deliverables
- Generator Java module under `generator`.
- Final app assembly support.
- Template rendering and artifact emitters.
- CLI and migration helper modules.

## Key Paths
- `generator\src\main\java\com\npdev\generator\api\GeneratorFacade.java`
- `generator\src\main\java\com\npdev\generator\assembly\FinalAppAssembler.java`
- `generator\src\main\java\com\npdev\generator\emitters`
- `generator\src\main\java\com\npdev\generator\guard\GeneratedProjectionGuard.java`
- `generator\src\main\java\com\npdev\generator\templates\TemplateEngine.java`
- `generator\src\test\java`
- `resources\Models`
- `db-history`

## Operational Expectations
- Same input should generate the same output.
- Generated projection guard should block internal-field and adapter leakage.
- RuntimeHost template dependencies must stay aligned with emitted code.

## Typical Commands
Run from `NPDevGenerator`:

```powershell
gradle clean build --no-daemon --console=plain
gradle :generator:test --no-daemon --console=plain
```

## Current Maturity Focus
- Template determinism.
- Regeneration safety with no unexpected diffs.
