# Scheduled / background execution (LNCH-12)

A flow can declare a recurring `schedule` (cron expression + tenant scope). The runtime scheduler
invokes it exactly like an HTTP-triggered run — same authorization, same event emission — under a
system principal, never the ControlPanel superuser key.

## Declaring it

```json
{
  "name": "CloseStaleOrders",
  "concept": "Order",
  "steps": [ ... ],
  "schedule": {
    "cron": "0 0 2 * * *",
    "tenantScope": ["default"]
  }
}
```

- `cron` — a 5-field classic cron (minute hour day month weekday) or Spring's 6-field
  seconds-first form (`second minute hour day month weekday`), parsed by
  `org.springframework.scheduling.support.CronTrigger`. `SemanticValidator` only checks field
  count at compile time (5 or 6 space-separated fields) — full cron-grammar validation happens at
  boot, when the scheduler actually constructs each `CronTrigger`; a malformed expression logs and
  skips that one schedule rather than failing the whole app's startup.
- `tenantScope` — a tenant id, or an array of them. Omitted/empty defaults to `["default"]`. One
  `CronTrigger` registration exists per (flow, tenant) pair, so the same flow runs once per
  declared tenant on the same cron, each independently tracked (own outcome, own run count).

Input to a scheduled run is always an empty map (`Map.of()`) — there's no HTTP request to draw it
from. A scheduled flow should be self-sufficient (query what it needs internally), not one that
expects a caller-supplied payload.

## Authorization: the system principal

`ExecutionContext.system(tenantId)` — `actorId = "system:scheduler"`, role `ADMIN`, tag
`trigger=schedule`. This goes through the exact same `PermissionEvaluator` check a human ADMIN's
request would; it is **not** a bypass like the ControlPanel superuser key. The distinct actorId is
what lets an event/audit trail tell a scheduled run apart from one a real admin triggered by hand
— every event/log line the flow produces carries it (see `PermissionDebugConfig`/`KernelRunner`
outcome logs).

## Missed-window policy: skip, don't catch up

This falls out of `CronTrigger`'s own semantics for free — it always computes the next fire time
from "now" when the app boots (or when a schedule is (re)registered); it never queues up firings
that were missed while the app was down. This is a deliberate v1 choice, not an oversight:
inventing catch-up semantics (how many missed runs? in what order? with what data staleness?) is
exactly the kind of undocumented behavior that corrupts data quietly. If catch-up is ever needed,
it should be a new, separately-specified feature — not something an author silently gets for free.

## ControlPanel visibility

`GET /api/admin/cron-schedules` (SUPERUSER-gated) lists every declared (flow, tenant) schedule
with its cron, last-run status (`PENDING`/`SUCCESS`/`FAILURE`), last-run timestamp, last error (if
any), and run count. Backed by `ScheduleOutcomeTracker`, an in-memory record populated by
`NpdevCronSchedulerService` on every run — deliberately not durable across restarts in v1 (the
flow's own normal event emission, `npdev.flow.outcome` log lines + the event store, is the durable
record of what actually happened; this tracker is just a fast summary for the ControlPanel list).

**Not** `/api/admin/schedules`, which was already taken by
`com.finalexec.api.RuntimeSchedulesController` — a pre-existing, unrelated feature (the
`scheduleEvent` orchestration action's delayed-event queue, a "wait N seconds then re-fire this
event" primitive, not cron-based recurring flow execution). Discovered via a route-collision
`IllegalStateException` at boot during live verification, not caught by any hermetic test since
Spring only resolves ambiguous `@RequestMapping`s at first-request time, not at context-startup.

## Verification

- `CompiledModelCanonicalJsonReaderTest` — extended to assert `schedule` survives the canonical
  JSON round trip every generated app's `NPDevModelProvider` actually reads at boot (same class of
  gap LNCH-6's `indexes` and LNCH-13's `access` field had — checked proactively this time by
  grepping every `new FlowAst(`/`new CompiledFlow(` construction site, which also caught
  `ModelResolver.sanitizeFlow`/`mergeFlow` silently dropping the field during model resolution).
- `NpdevCronSchedulerServiceTest` — a schedule shrunk to `"* * * * * *"` (every second, the DoD's
  own suggested gate technique) proves the full path through real production classes
  (`CapabilityRegistry`/`RegistryCapabilityDispatcher`/`KernelRunner`): the flow runs at least
  twice within 5 seconds, recorded as `SUCCESS` in the tracker.
- Live: a temporary scheduled flow added to `simple-contact-intake` (reverted after, clean `git
  diff`), booted for real — server logs show `ScheduledHeartbeat` executing once per second under
  `actorId=system:scheduler`, `outcome=success`, via the real `KernelRunner` flow-outcome log line
  (`docs/`'s LNCH-8 correlation-id/observability work). This is what caught the route collision
  above.
