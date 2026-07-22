# Flow transaction-boundary contract (LNCH-17)

What is atomic when step 4 of a multi-step flow fails after steps 1-3 already wrote rows? This
document is the honest answer, written down rather than left implicit.

## The contract

1. **Each step is atomic.** A single step's own write (a `ConceptGateway` CRUD write, an event
   append, a capability call) either fully lands or doesn't happen at all. See "CRUD write
   atomicity" below for the JDBC mechanism that makes this true for concept writes specifically.

2. **The flow as a whole is *not* atomic.** There is no distributed transaction spanning
   multiple steps, multiple capability adapters, and (for a scheduled flow or a flow running
   across a process restart) potentially multiple process lifetimes. If step 3 of a 5-step flow
   fails, steps 1-2's writes already happened and stay happened by default.

3. **Failures leave a durable execution record with completed-step state.** Every
   `KernelRunner` execution is backed by a `FlowInstance` (`currentStepIndex`, `state`, terminal
   status, last-error kind/code/message) persisted through `FlowInstanceStore` after every step
   checkpoint — this is not new for LNCH-17, it is the same durability `LIFT-LOOP-P2` already
   proved for `forEach`. A crash mid-flow resumes from the last durable checkpoint, not from
   scratch (`KernelRunner.resumeExecution`, accepts `RUNNING` status for exactly this recovery
   case).

4. **The recommended pattern for multi-write consistency is the saga pattern: compensating
   steps.** Not distributed transactions (2PC/XA across heterogeneous capability adapters is not
   a goal of this platform and would not compose with external capabilities like email/SMTP or
   S3 anyway) — a flow author who needs steps 1-3's side effects undone if step 4 fails declares
   `onFailure` compensation steps on steps 1-3.

## Declaring compensation

Any flow step may declare `onFailure`: an array of steps (same shape as `then`/`else`/`steps`,
recursively — a compensation step can itself declare `onFailure`, though this is rarely useful in
practice).

```json
{
  "name": "reserve-inventory",
  "type": "capability",
  "capability": "inventory",
  "operation": "reserve",
  "input": "input",
  "output": "reserved",
  "onFailure": [
    { "name": "release-inventory", "type": "capability", "capability": "inventory", "operation": "release", "input": "input" }
  ]
}
```

**Semantics:**

- If a *later* step in the same flow terminally fails (a modeled failure — invariant violation,
  capability failure — or an unhandled exception), every **already-completed** top-level step
  that declared `onFailure` runs its compensation, **in reverse completion order** (last
  completed, first compensated — the standard saga unwind order).
- The step that itself failed does **not** run its own `onFailure` (it didn't complete; there's
  nothing on its side to undo). Only steps 1..N-1 (relative to the failing step N) are
  candidates.
- Compensation is **best-effort**: a compensation block that itself throws is logged and
  skipped, not allowed to abort compensating the remaining earlier steps too. A saga's whole
  point is "make a best effort to clean up"; a broken compensation shouldn't leave a *worse* mess
  by stopping the rest of the rollback.
- This applies only to **top-level flow steps** in this v1 slice — a step nested inside a
  `branch`'s `then`/`else` or a `forEach`'s loop body may still declare its own `onFailure` (it's
  the same step shape, recursively), but compensation triggering is scoped to the top-level step
  sequence, not per-nested-step. A `branch`/`forEach` step's own `onFailure` (if declared)
  compensates the *whole* branch/loop as one unit when a later top-level step fails.
- The final terminal status (`FAILED`/`FAILED_PERMANENT`/`STUCK`) and failure info are computed
  exactly as before — compensation runs *before* that status lands, not instead of it. A caller
  cannot observe "succeeded via compensation"; a compensated flow is still a failed flow, just one
  that cleaned up after itself.

## Crash-mid-compensation durability

Compensation checkpoints durably after each top-level step's compensation finishes — the same
per-step checkpoint discipline `StepProgressRecorder` already uses for forward execution. If the
process crashes mid-compensation (after some steps' `onFailure` ran and checkpointed, before
others have), the instance is left `RUNNING` (not yet finalized to a terminal status) with a
reserved flow-state marker recording where compensation left off. `resumeExecution` on a fresh
`KernelRunner` detects this marker and finishes running the remaining compensations — each already
-compensated step is not re-compensated — before finalizing the terminal status. Proven by
`KernelRunnerCompensationTest.crashMidCompensationThenResumeOnFreshRunnerFinishesRemainingCompensationsExactlyOnce`,
reusing the exact freeze-thread crash-injection technique
`KernelRunnerForEachDurabilityTest` established for `forEach`.

## CRUD write atomicity (item 3: a real bug, not a design choice)

**The gap.** A generated concept's `create`/`update` service method is `@Transactional`
(`service-base.mustache`) and does two separate writes in the same method: a kernel-gateway write
(`enforceWithConceptGateway` → `ConceptGateway.save` → `JdbcBusinessConceptStore.save`, its own
JDBC connection) and a JPA entity persist (`saveWithIntegrityMapping` → `persistence.save`,
Hibernate's own connection). Before this fix, `JdbcBusinessConceptStore` acquired connections via
plain `dataSource.getConnection()` — a brand-new physical connection, uncoordinated with whatever
Spring-managed transaction the enclosing `@Transactional` method was running (which is what binds
JPA's own connection). The two writes were two independently auto-committing operations, not one
atomic unit: if the JPA write failed *after* the kernel-gateway write had already landed, the
kernel-gateway write stayed landed. Silent partial-write data corruption on the platform's own
default generated-CRUD path, not a hypothetical.

**The fix.** `JdbcBusinessConceptStore` now acquires connections via
`org.springframework.jdbc.datasource.DataSourceUtils#getConnection`/`#releaseConnection` — the
same mechanism `JdbcTemplate` uses internally — which joins the ambient Spring transaction when
one is active and transparently falls back to a fresh auto-committing connection when there isn't
one (every non-Spring caller — every hermetic test, `KernelRunner`'s own direct
`ConceptGateway.save` calls outside an HTTP request — is unaffected). This makes the two writes
genuinely one unit: if either fails, the `@Transactional` method's rollback now undoes both.

**Verified**: `JdbcBusinessConceptStoreTransactionalWriteTest` — a write inside a transaction that
rolls back is not visible afterward (proves the connection actually joined the ambient
transaction); a write inside a committed transaction is visible; a write with no ambient
transaction (today's default shape for every existing caller) still auto-commits exactly as
before the fix.

## ControlPanel visibility

`GET /api/executions/history` and `GET /api/executions/active`
(`ExecutionMonitorController`/`ExecutionMonitorService`, pre-existing) already surface a failed
execution's `currentStepIndex`, terminal status, and `lastErrorKind`/`lastErrorCode`/
`lastErrorMessage` per execution card. LNCH-17 adds compensation visibility to the same card: a
`compensationStatus` (`NONE`/`COMPENSATED`) derived from whether the flow-state compensation
marker is present in the persisted `FlowInstance.state()` at the time it's read (cleared once
compensation finishes, so a fully-compensated failure and a still-in-progress one are both
distinguishable from a flow that had no `onFailure` steps to run at all).

## Explicitly out of scope

- **Distributed transactions / 2PC across capability adapters.** Not attempted; the saga pattern
  above is the platform's answer, matching the doc's own "recommend the saga pattern" guidance.
- **Compensating a step nested inside `branch`/`forEach` individually** (as opposed to the
  containing branch/loop step's own `onFailure`, compensated as one unit). A future slice could
  add finer-grained nested compensation if a real need for it shows up; the v1 top-level-only
  scope keeps the checkpoint/resume model (a flat top-level index) simple and matches how
  `StepProgressRecorder` already tracks progress today.
- **Event-store append atomicity with a concept write.** Investigated and found *not* coupled in
  the same request/method today — event emission is its own separate flow step
  (`emitEvent`/`event`), not something `DefaultConceptGateway.save` does automatically. There was
  therefore no "write + event append" atomicity gap to close for that specific pairing; the real
  gap found and fixed was the kernel-gateway-write + JPA-persist double-write described above.
