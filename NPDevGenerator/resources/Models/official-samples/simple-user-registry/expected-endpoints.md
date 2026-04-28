# Expected Endpoints

## Primary flow surface

- `GET /api/flows`
- `POST /api/flows/CreateUser/execute`

## Runtime evidence surfaces

- `GET /api/audit`
- `GET /api/correlations/{correlationId}`
- `GET /api/admin/model/export`

## Minimum expectation

The sample should expose one clearly runnable flow named `CreateUser` and enough runtime surfaces to inspect the resulting event and execution evidence.
