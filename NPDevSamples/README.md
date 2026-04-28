# NPDevSamples

## Purpose

Provide release and diagnostic samples that prove the NPDev model, generator, runtime, and evidence path end to end.

## Build

Generate a sample with the provided PowerShell helpers from `scripts\samples`.

## Test

Verify samples through the runtimehost gate, sample-matrix gate, and sample-specific helper scripts.

## Architecture

Each sample follows the same split: `Input` holds source model/config/docs, and `Output` holds generated artifact, assembled app, and runtime evidence.

Every sample now lives in its own top-level folder and follows one layout:

- `<sample>/Input`: `model.json`, `config.json`, docs, request payloads, and optional resources
- `<sample>/Output/ArtifactNP`: generated artifact tree
- `<sample>/Output/App`: assembled runnable app
- `<sample>/Output/RunOutput`: optional runtime evidence from helper scripts

Core scripts:

- `pwsh -File ..\scripts\samples\normalize-samples.ps1`
- `pwsh -File ..\scripts\samples\clean-sample-output.ps1`
- `pwsh -File ..\scripts\samples\sync-mirrored-samples.ps1`
- `pwsh -File ..\scripts\samples\generate-sample.ps1 -SampleIds <sample-id>`
- `pwsh -File ..\scripts\samples\run-sample.ps1 -SampleId <sample-id>`
- `pwsh -File ..\scripts\samples\verify-sample.ps1 -SampleIds <sample-id> -GenerateIfMissing`

Restaurant sample helpers:

- `scripts/restaurant-saas-multitenant/generate-restaurant-saas-sample.ps1`
- `scripts/restaurant-saas-multitenant/run-generated-app.ps1`
- `scripts/restaurant-saas-multitenant/populate-restaurant-tenants.ps1`
- `scripts/restaurant-saas-multitenant/verify-restaurant-tenant-data.ps1`
- `scripts/restaurant-saas-multitenant/verify-no-domain-leaks.ps1`
