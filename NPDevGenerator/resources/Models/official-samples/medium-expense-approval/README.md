# Medium Expense Approval

## One-command run (Windows PowerShell)

```powershell
Set-Location D:\WorkSpace\NPDev_General\NPDevSamples
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\generate-sample-app.ps1 -SampleId medium-expense-approval
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-sample-app.ps1 -SampleId medium-expense-approval
```

## Why this sample exists

This is the first official medium-complexity NPDev sample.

It exists to prove that NPDev can model and run a business process that:

- starts with a form-like submission
- persists a business record
- enters a waiting state
- later resumes when an event arrives
- follows the correct branch after resumption
- leaves traceable evidence of the whole path

## Standard sample contents

- `model.json`
- `config.json`
- `manifest.json`
- `Requests/submit-expense.json`
- `Requests/publish-approval-event.json`
- `expected-behavior.md`
- `expected-endpoints.md`
- `expected-diagnostics.md`

## Main flow

- `SubmitExpense`

## Quick walkthrough after startup

1. Start the sample with one of the commands above.
2. Execute `SubmitExpense` with `Requests/submit-expense.json`.
3. Capture the returned correlation id or execution context.
4. Publish the approval event using `Requests/publish-approval-event.json`.
5. Verify `/actuator/health` returns `UP`.
6. Inspect diagnostics before and after resume.

## Expected outcome

After the walkthrough, the user should observe that:

- the expense was persisted
- the process entered a waiting state
- a later event resumed the process
- branch behavior after resume was visible through runtime evidence
- generated output under `Output/App` and `Output/ArtifactNP` is available for inspection

