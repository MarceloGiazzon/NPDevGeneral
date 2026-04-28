# Expected Endpoints

## Primary flow and event surfaces

- `GET /api/flows`
- `POST /api/flows/SubmitExpense/execute`
- `POST /api/events/publish`

## Runtime evidence surfaces

- `GET /api/audit`
- `GET /api/correlations/{correlationId}`
- `GET /api/admin/model/export`

## Minimum expectation

The sample should expose one runnable flow named `SubmitExpense`, support a later approval event publish, and expose enough evidence surfaces to inspect waiting and resumed behavior.
