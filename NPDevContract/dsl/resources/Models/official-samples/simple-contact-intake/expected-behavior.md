# Expected Behavior

## Primary business path

- `SubmitContactMessage` accepts a valid inbound contact payload.
- The flow validates and persists the `ContactMessage` record.
- A governed notification capability runs as part of the same business execution.
- The flow emits `ContactMessageReceived` after the successful path.

## What should be observable

- `GET /api/flows` lists `SubmitContactMessage`.
- `POST /api/flows/SubmitContactMessage/execute` succeeds for `input/submit-contact-message.json`.
- Runtime evidence shows both persistence and notification-related behavior after execution.

## Why this sample matters

- It proves that NPDev can model a richer simple app than pure CRUD.
- It shows that notification behavior can remain governed by the model instead of being hand-wired around it.
