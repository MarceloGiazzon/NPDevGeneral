# Item 20 Postgres Proof

Item 20 adds an executable Postgres proof harness.

The proof requires Docker Desktop / Docker daemon to be running. The proof script now performs a fail-fast Docker preflight and supports `-StartDockerDesktop` to try the common Docker Desktop install paths before running.

The proof starts a temporary real Postgres container and executes transactional SQL covering:

- flow instance row persistence
- correlation owner row persistence
- event store row persistence
- idempotency replay record with `ON CONFLICT DO NOTHING`
- audit row persistence
- trace row persistence
- JSONB insert/read/update
- `FOR UPDATE SKIP LOCKED` query compatibility

This is a proof harness only. It is not the source of truth for internal tables and it must not become migration authority. Internal table source of truth remains under:

`D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\kernel\src\main\java\com\npdev\kernel\dbschema`

The old generator migration package must remain absent.
