# Expected Endpoints

## Primary flow surface

- `GET /api/flows`
- `POST /api/flows/SubmitContactMessage/execute`

## Runtime evidence surfaces

- `GET /api/audit`
- `GET /api/correlations/{correlationId}`
- `GET /api/admin/model/export`

## Minimum expectation

The sample should expose one runnable flow named `SubmitContactMessage` and enough runtime evidence surfaces to inspect both persistence and notification-oriented behavior.
