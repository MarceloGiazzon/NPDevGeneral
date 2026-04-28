# Simple Contact Intake

## One-command run (Windows PowerShell)

```powershell
Set-Location D:\WorkSpace\NPDev_General\NPDevSamples
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-sample-app.ps1 -SampleId simple-contact-intake
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-sample-app.ps1 -SampleId simple-contact-intake
```

## Why this sample exists

This is the second official beginner sample.

It keeps the learning curve simple, but adds one important idea:
a governed capability can be part of the business flow without turning the system into ad hoc application code.

## Standard sample contents

- `model.json`
- `config.json`
- `manifest.json`
- `Requests/submit-contact-message.json`
- `expected-behavior.md`
- `expected-endpoints.md`
- `expected-diagnostics.md`

## Main flow

- `SubmitContactMessage`

## Quick walkthrough after startup

1. Start the sample with one of the commands above.
2. Confirm `SubmitContactMessage` with `GET /api/flows`.
3. Execute the payload in `Requests/submit-contact-message.json`.
4. Verify `/actuator/health` returns `UP`.
5. Inspect the result and diagnostics surfaces afterward.

## Expected outcome

After a valid execution, the user should observe that:

- the message was validated and persisted
- a notification-related capability path was executed
- runtime evidence shows more than a pure CRUD-style action
- generated output under `Output/App` and `Output/ArtifactNP` is available for inspection

