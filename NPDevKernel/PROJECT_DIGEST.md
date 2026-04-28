# NPDevKernel Project Digest

## Purpose
NPDevKernel is the domain-neutral runtime engine that executes flows, capabilities, persistence, events, tracing, audit, idempotency, and reliability behaviors.

## Role In Workspace
- Provides stable runtime contracts used by generated apps and RuntimeHost.
- Hosts adapter modules for in-memory, Postgres, tracing, auth, validation, and execution concerns.
- Stays domain-neutral and sample-agnostic.

## Main Deliverables
- Kernel interfaces and execution types.
- Port definitions and capability binding infrastructure.
- Adapter modules under `adapters`.
- Runtime validation, health, trace, and store implementations.

## Key Paths
- `kernel\src\main\java\com\npdev\kernel`
- `kernel\src\main\java\com\npdev\kernel\capabilities`
- `kernel\src\main\java\com\npdev\kernel\ports`
- `adapters\persistence-*`
- `adapters\flowinstance-*`
- `adapters\tracing-*`
- `adapters\tracestore-postgres`
- `adapters\runtime-validation`
- `adapters\expression-cel`

## Operational Expectations
- Capability binding precedence is explicit and tested.
- Flow resume behavior is reliable across in-proc and durable stores.
- Tenant isolation remains enforceable under normal and concurrent use.
- Adapter coverage evidence is visible in `scripts\reports\out`.

## Typical Commands
Run from `NPDevKernel`:

```powershell
gradle clean build --no-daemon --console=plain
gradle :kernel:test --no-daemon --console=plain
```

## Current Maturity Focus
- Adapter coverage completeness.
- Resume and tenant-isolation coverage.
- Observability and runtime health evidence.
