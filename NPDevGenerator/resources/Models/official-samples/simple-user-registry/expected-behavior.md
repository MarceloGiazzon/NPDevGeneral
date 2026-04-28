# Expected Behavior

## Primary business path

- `CreateUser` accepts a valid user registration payload.
- The flow enforces the sample invariants before persistence completes.
- A persisted `User` record is produced through the governed runtime path.
- The flow emits `UserCreated` after the successful save.

## What should be observable

- `GET /api/flows` lists `CreateUser`.
- `POST /api/flows/CreateUser/execute` returns a successful execution result for `input/create-user.json`.
- Runtime evidence can be inspected through traces, summaries, or correlation-oriented surfaces after execution.

## Why this sample matters

- It proves the smallest useful NPDev business path.
- It shows that concepts, invariants, persistence, and event emission stay inside the semantic model instead of drifting into custom app code.
