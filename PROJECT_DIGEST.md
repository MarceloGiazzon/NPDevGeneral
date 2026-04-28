# NPDev General Project Digest

## Purpose
NPDev is a model-driven low-code platform. The user authors model/config contracts, NPDev validates and compiles them, the Generator emits application artifacts, and the RuntimeHost runs the assembled result for the final user.

## Root Rules
- Model/config JSON is the source of truth.
- Generated files are outputs and can be regenerated.
- Kernel owns reusable runtime semantics.
- Business-domain vocabulary belongs in samples or generated apps, not NPDev core.
- Release evidence must come from the aggregate beta gate, not ad hoc focused reports.

## Workspace Map
- `NPDevContract`: shared schemas, DSL parsing, validation, canonical JSON.
- `NPDevEditor`: authoring UI for model/config editing and preview.
- `NPDevGenerator`: generation, assembly, migration planning, projection guard.
- `NPDevKernel`: runtime engine, ports, adapters, tracing, validation.
- `NPDevRuntimeHost`: reusable Spring Boot host template copied into assembled apps.
- `NPDevSamples`: canonical sample inputs, requests, generated-output evidence, and docs.
- `scripts`: quality gates, sample automation, state zips, maturity controls, and reports.

## Core Flow
1. User authors or edits `model.json` and `config.json`.
2. Contract layer validates structure and semantics.
3. Generator emits code, manifests, compiled assets, and migration plans.
4. Generator assembles a final app from RuntimeHost + generated output.
5. Kernel and adapters provide runtime behavior.
6. Quality gates and maturity controls evaluate release readiness.

## Current Governance Expectations
- `scripts\quality\run-beta-release-gate.ps1` is the release source of truth.
- `statezip-npdev-general.ps1 -ReleaseReady` must derive readiness from the aggregate beta gate.
- Evidence bundles must include hashed child reports and provenance details.
- Maturity suites must read structured reports, not guess from console output.

## Useful Entry Points
- `scripts\quality\run-beta-release-gate.ps1`
- `scripts\maturity_adv\run-maturity-adv-suite.ps1`
- `scripts\maturity_adv\run-maturity-12-domain-suite.ps1`
- `scripts\samples\generate-sample.ps1`
- `scripts\samples\verify-sample.ps1`
- `scripts\statezip-npdev-general.ps1`

## Current Maturity Focus
- Release truthfulness and evidence completeness.
- Full release sample matrix coverage.
- Deterministic generation and contract stability.
- Runtime, security, observability, performance, and AI control evidence.
