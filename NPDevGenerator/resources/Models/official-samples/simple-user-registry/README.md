# Simple User Registry

## One-command run (Windows PowerShell)

```powershell
Set-Location D:\WorkSpace\NPDev_General\NPDevSamples
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-sample-app.ps1 -SampleId simple-user-registry
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-sample-app.ps1 -SampleId simple-user-registry
```

## Why this sample exists

This is the smallest official NPDev sample that still feels like a real business system.

It is meant to be the first sample a new user tries when they want to understand:

- what a concept looks like in practice
- what an invariant feels like during execution
- how persistence fits into the semantic flow
- how an event can be emitted after success
- how traces and timelines help explain what happened

## Standard sample contents

- `model.json`
- `config.json`
- `manifest.json`
- `Requests/create-user.json`
- `expected-behavior.md`
- `expected-endpoints.md`
- `expected-diagnostics.md`

## Main flow

- `CreateUser`

## Quick walkthrough after startup

1. Start the sample with one of the commands above.
2. Confirm the flow surface with `GET /api/flows`.
3. Execute `CreateUser` with `Requests/create-user.json`.
4. Verify `/actuator/health` returns `UP`.
5. Inspect runtime evidence after success.

## Expected outcome

After a valid execution, the user should observe that:

- the record was accepted and persisted
- an event was emitted
- execution evidence is available through traces, summaries, or correlation timeline surfaces
- generated output under `Output/App` and `Output/ArtifactNP` is available for inspection

