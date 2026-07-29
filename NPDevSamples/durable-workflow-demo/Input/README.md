# Durable Workflow Demo

## One-command run (Windows PowerShell)

```powershell
Set-Location D:\WorkSpace\NPDev\NPDev_General\NPDevSamples
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\run-durable-resume-demo.ps1
```

That's it — the script generates the app, builds it, boots it, submits an expense, **kills the
process**, restarts it from the same on-disk database, publishes the approval event, and confirms
the same execution resumed and completed. It prints a clear timeline and exits non-zero if any step
fails or the expected evidence doesn't show up.

## Why this sample exists

`docs/EXECUTION_TREES.md` §0.1 names a durable, event-correlated, compensating workflow engine as
one of NPDev's three genuinely differentiated capabilities — "the category Temporal, Camunda, and
Zeebe sell." Documentation and unit tests proved the mechanism works *within one continuously
running JVM*. Nothing proved it survives what actually happens in production: the process dying and
coming back. This sample is that proof, runnable by anyone who clones the repo, with zero external
database setup.

## What it proves, concretely

1. `POST /api/flows/SubmitExpense/execute` with `Requests/submit-expense.json` — the flow validates,
   persists the `ExpenseRequest`, emits `ExpenseSubmitted`, and parks on `awaitEvent` because
   `needsManagerApproval` is true. Response: HTTP 202, `status: "WAITING_EVENT"`, an `executionId`
   and `correlationId`.
2. **The application process is killed** (not a graceful shutdown — `Stop-Process -Force`) and a
   **new** JVM is started from the same on-disk H2 database file. Nothing about step 1's execution
   was held in memory that survives this; if the wait state weren't durably persisted, it would be
   gone. The script waits 5s before killing — see `REG-57` (`docs/NPDEV_OPEN_ITEMS_REGISTER.md`), a
   real, separately-filed timing gap where a kill in roughly the first second after the response can
   catch the on-disk checkpoint before it's physically durable. That gap is real and worth fixing on
   its own terms; it is not the thing this sample exists to demonstrate, so the script works around
   it rather than making the demo flaky.
3. `POST /api/events/publish` with `Requests/publish-approval-event.json` (using the `correlationId`
   captured from step 1) — the **same** execution resumes and completes.
4. The script confirms this by matching the `executionId` from step 1 against
   `KernelRunner`'s own `npdev.flow.outcome` log line reporting `status: "OK"` after the restart —
   not a re-run from scratch, the same execution.

## Why H2Local, not the `step0` trial profile

Other samples use the `step0` Spring profile for zero-setup convenience — but `step0` always boots
against a **fixed in-memory** H2 sandbox (`application-step0.yml`) regardless of what
`db.definition.json` declares, specifically so first-time trials don't need a real database. That
would silently defeat this exact demo (in-memory data doesn't survive a process restart), so this
sample uses `db.definition.json`'s real `H2Local` file-backed engine via the `dev,trial` profile
instead — no Postgres or Docker required, but genuinely persistent across the restart.

## Standard sample contents

- `model.json`, `config.json`, `db.definition.json`, `manifest.json`
- `Requests/submit-expense.json`, `Requests/publish-approval-event.json`
- `../../scripts/run-durable-resume-demo.ps1` — the runnable proof itself
