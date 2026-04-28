# NPDevContract Project Digest

## Purpose
NPDevContract defines the shared model, config, runtime, and evidence contracts that every other NPDev subproject must honor.

## Role In Workspace
- Owns schemas, examples, DSL parsing/validation, and compiled-model canonical JSON.
- Supplies the authoring and runtime contract surface used by Editor, Generator, Kernel, RuntimeHost, and Samples.
- Acts as the first line of drift control when schemas or contract semantics change.

## Main Deliverables
- JSON schemas under `schemas`.
- Java DSL module under `dsl`.
- Schema fixtures and mirrored sample fixtures under `dsl\resources`.
- Contract docs and examples.

## Key Paths
- `schemas\authoring`, `schemas\generator`, `schemas\runtime`, `schemas\kernel`
- `schemas\model.schema.json`, `schemas\config.schema.json`
- `dsl\src\main\java\com\npdev\dsl\v1\parser\JsonModelParser.java`
- `dsl\src\main\java\com\npdev\dsl\v1\validation`
- `dsl\src\main\java\com\npdev\dsl\v1\compiler\ModelCompiler.java`
- `dsl\src\main\java\com\npdev\dsl\v1\compiled`
- `dsl\src\test\java`
- `dsl\resources\Models`
- `dsl\resources\Schemas`

## Operational Expectations
- Schema evolution is explicit and dated in `dsl\resources\Schemas\MIGRATION_DIGEST.md`.
- Deprecated schema aliases remain visible until all official samples are migrated.
- Canonical JSON output remains deterministic for evidence and diffing.

## Typical Commands
Run from `NPDevContract\dsl`:

```powershell
gradle clean test --no-daemon --console=plain
gradle test --no-daemon --console=plain
```

## Current Maturity Focus
- Keep schema version/deprecation metadata explicit.
- Keep parser errors clear when deprecated schema targets are used.
- Keep catalog and manifest schemas aligned with canonical sample inputs.
