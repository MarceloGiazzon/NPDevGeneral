# NPDevSamples Project Digest

## Purpose
NPDevSamples holds canonical examples, official release samples, tenant demonstrations, request payloads, and generated-output evidence used by the rest of NPDev.

## Role In Workspace
- Canonical source of sample model/config inputs.
- Source for authoring chooser data, generator smoke tests, and schema regression fixtures.
- Safe place for business-domain vocabulary that must stay out of platform code.

## Main Deliverables
- `NPDevSamples\sample-catalog.json`
- Canonical, official, tenant, and fixture sample folders.
- Expected behavior, diagnostics, and endpoint docs.
- Request payload examples under `Input\Requests`.
- Generated-output folders under `Output`.

## Key Paths
- `canonical-demo\Input`
- `simple-contact-intake\Input`
- `simple-user-registry\Input`
- `medium-expense-approval\Input`
- `restaurant-saas-multitenant\Input`
- `user-minimal\Input`
- `NPDevSamples\manifest.schema.json`
- `scripts`

## Operational Expectations
- Release samples carry `manifest.json`, `expected-behavior.md`, `expected-diagnostics.md`, and `expected-endpoints.md`.
- Request examples stay valid against the current generated contracts.
- Sample output cleanup preserves reports and removes disposable build caches.
- Sample catalog remains the authoritative sample registry for scripts and tooling.

## Useful Commands
Run from workspace root:

```powershell
pwsh -File scripts\samples\generate-sample.ps1
pwsh -File scripts\samples\verify-sample.ps1 -GenerateIfMissing
pwsh -File scripts\samples\clean-sample-output.ps1
```

## Current Maturity Focus
- Release-sample manifest completeness.
- Request validity checks in the sample matrix.
- Documentation freshness across samples and subprojects.
