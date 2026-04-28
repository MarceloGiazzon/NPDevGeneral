# Expected Behavior

## Primary business path

- `SubmitExpense` accepts a valid expense submission and persists the `ExpenseRequest`.
- The process branches on manager-approval need and enters an await-event path when approval is required.
- The waiting execution resumes when `ExpenseApproved` is published with the matching correlation information.
- Post-resume behavior includes the governed notification and webhook path before completion.

## What should be observable

- `GET /api/flows` lists `SubmitExpense`.
- `POST /api/flows/SubmitExpense/execute` succeeds for `input/submit-expense.json`.
- The first execution leaves runtime evidence of a waiting state when approval is required.
- `POST /api/events/publish` with `input/publish-approval-event.json` resumes the waiting execution.
- Runtime evidence before and after resume makes the branch behavior inspectable.

## Why this sample matters

- It proves the first official medium-complexity NPDev orchestration.
- It shows resumable business flow, event-driven continuation, and branch behavior without leaving the governed runtime model.
