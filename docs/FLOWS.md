# Flows — NPDev's durable workflow engine

> **Status:** first documentation pass for this surface (T1.10,
> `docs/TREE1_LAUNCH_UNBLOCK_PLAN.md`, CORE C-1). Every claim below cites the code that makes it
> true — `FlowStepDefinition.java`, `KernelRunner.java`, `FlowInstance.java`,
> `FlowInstanceStatus.java` — not a description of intent. Where the engine's real behavior differs
> from what you might expect, that's called out explicitly rather than smoothed over.

## 1. What a flow is, and why "durable" matters

A **flow** is a named sequence of steps compiled from your model's `flows[]` declarations. It runs
inside the generated app's own JVM, driven by `KernelRunner` (the same class that backs every
capability call, event, and scheduled job). Nothing about that makes it different from an ordinary
function call — until a step needs to **wait for something that hasn't happened yet**: an approval,
a payment webhook, a reply to an email. An ordinary function call can't do that; it would have to
block a thread indefinitely, or the caller would have to hold the process open until the answer
arrives.

NPDev's flow engine instead **persists the wait**. When a flow reaches an `awaitEvent` step whose
event hasn't arrived, the engine writes the flow's entire state — where it is, what it's waiting for,
everything computed so far — to a database row, and the JVM is free to do anything else, including
restart. When the event eventually arrives (seconds or weeks later, in this process or the next one
after a deploy), a separate mechanism finds the waiting instance and resumes it exactly where it left
off. This is the same category of durability Temporal, Camunda, and Zeebe sell — the platform's own
`KernelRunner` implements it in one class, not a separate service.

The rest of this document is that engine's actual contract: what it guarantees, what it doesn't, and
where every claim is proven in code or by a test.

## 2. The flow-instance state machine

Every running flow is a `FlowInstance` row, and its `status` is one of exactly six values —
`FlowInstanceStatus.java:3-10` (`NPDevKernel/kernel/src/main/java/com/npdev/kernel/execution/`):

```
RUNNING, WAITING_EVENT, COMPLETED, FAILED, FAILED_PERMANENT, STUCK
```

`FlowInstance` is an immutable record (`FlowInstance.java`); every transition is a `mark*` factory
method that returns a **new** record — there is no in-place mutation to get wrong.

```
                         ┌────────────────────────────────────┐
                         │  step succeeds (checkpoint)          │
                 ┌───────▼────┐                                │
  flow starts ──►│   RUNNING  ├────────────────────────────────┘
                  └──┬───┬───┬┘
                     │   │   │
     hits AWAIT_EVENT│   │   │ all steps done, or resume
     (event not yet  │   │   │ finds nothing left to run
      present)       │   │   └───────────────────────────► COMPLETED  (terminal)
                     │   │
                     │   │ step fails -- terminal status chosen by
                     │   │ resolveFailureTerminalStatus(); if the flow
                     │   │ declares ANY onFailure steps, compensation
                     │   │ (§5) runs first, then the SAME status applies
                     │   ├───────────────────────────────► FAILED            (terminal)
                     │   ├───────────────────────────────► FAILED_PERMANENT  (terminal)
                     │   └───────────────────────────────► STUCK             (terminal in practice)
         ┌───────────▼──────────┐
         │     WAITING_EVENT     │
         └──┬────────────────┬──┘
   resumeExecution finds     │ resume attempted, event still missing
   the matching event,       │ (or a transient error) -- retried with
   steps proceed             │ exponential backoff, 5s .. 300s cap
            │                │
            ▼                ▼
         RUNNING        WAITING_EVENT (retry)
                              │
                              │ resumeAttemptCount reaches
                              │ RESUME_MAX_ATTEMPTS = 20
                              ▼
                            STUCK
```

Every arrow above is a real call site in `KernelRunner.java`:

| Transition | Where | Condition |
|---|---|---|
| *(start)* → RUNNING | `KernelRunner.java:806-815` | `FlowInstance.start(...)`, then persisted |
| RUNNING → RUNNING | `KernelRunner.java:1053-1057` | every successful step checkpoint |
| RUNNING → WAITING_EVENT | `KernelRunner.java:1119-1130` | an `AWAIT_EVENT` step's event isn't found yet |
| RUNNING → COMPLETED | `KernelRunner.java:1086-1098`, `1170-1173` | all steps done, or resume past the end |
| RUNNING → FAILED / FAILED_PERMANENT / STUCK | `KernelRunner.java:1149-1161`, `1197-1204` | a step fails; `resolveFailureTerminalStatus` (`:2641-2665`) picks the terminal status — compensation (if declared) runs first, then the same status applies either way |
| WAITING_EVENT → RUNNING | guard at `KernelRunner.java:876-892` | `resumeExecution` finds the awaited event |
| WAITING_EVENT → WAITING_EVENT | `KernelRunner.java:982-1011`, `2610-2617` | resume attempted, nothing found yet — backoff |
| WAITING_EVENT → STUCK | `FlowInstance.java:180-186` | `resumeAttemptCount + 1 >= RESUME_MAX_ATTEMPTS` (constant `20`, `KernelRunner.java:90`) |

**Terminal-status selection** (`resolveFailureTerminalStatus`, `KernelRunner.java:2641-2665`):

```
INPUT_VALIDATION_FAILED, INVARIANT_FAILED, EVENT_PAYLOAD_INVALID   → FAILED_PERMANENT
CAPABILITY_FAILED, sub-kind CONTRACT / AUTH / PERMANENT / NOT_FOUND → FAILED_PERMANENT
CAPABILITY_FAILED, sub-kind TRANSIENT / RATE_LIMIT / TIMEOUT        → FAILED
EVENT_PERSIST_FAILED, FAILED                                        → FAILED
```

**`COMPLETED`, `FAILED`, and `FAILED_PERMANENT` are true dead ends** — nothing in `KernelRunner.java`
ever transitions an instance back out of them. **`STUCK` is a dead end in practice, not by explicit
design**: `resumeExecution`'s status guard (`KernelRunner.java:882`) accepts `RUNNING` and
`WAITING_EVENT` but not `STUCK`, so a stuck instance cannot self-heal through the normal resume path.
There is no coded "un-stick" operation today — see §9 for what inspecting one actually looks like.

## 3. The 9 step kinds

The runtime enum is `FlowStepDefinition.Type` (`FlowStepDefinition.java:13-26`) — exactly these 9
values, each with its own factory method:

| Kind | Factory | What it does |
|---|---|---|
| `INVARIANT_CHECK` | `invariant(name, scope, checkpoint, invariants)` | Evaluates named invariants at a `PRE` or `POST` checkpoint (`checkpoint` is a field *of* this step, not its own step kind) |
| `CAPABILITY_CALL` | `capabilityCall(...)` | Dispatches to a capability adapter (a concept's generated CRUD, or a platform capability like `notification.send`) |
| `EMIT_EVENT` | `emitEvent(name, eventName, payloadRef, eventDataRefs)` | Publishes an event immediately |
| `SCHEDULE_EVENT` | `scheduleEvent(..., delaySeconds)` | Publishes an event immediately, stamped with delay metadata for a consumer to honor (§7) |
| `BRANCH` | `branch(name, condition, thenSteps, elseSteps)` | Evaluates a string expression, runs one nested step list |
| `AWAIT_EVENT` | `awaitEvent(name, eventName, awaitRef, ...)` | Suspends until a matching event arrives — §4 |
| `MAP` | `map(name, fromRef, toRef)` | Copies a value from one flow-state reference to another |
| `RETURN` | `returnValue(name, returnRef)` | Ends the flow, producing a value |
| `FOR_EACH` | `forEach(name, collectionRef, itemKey, loopSteps, maxLoopIterations)` | Bounded iteration over a collection — §6 |

Any step, regardless of kind, can additionally carry `onFailureSteps` (`withOnFailure`,
`FlowStepDefinition.java:257-308`) — used only for compensation, §5.

**A correction worth naming.** An earlier internal estimate of this list (repeated in
`docs/EXECUTION_TREES.md`) included `PRE` as a 9th step kind. That's wrong: `PRE`/`POST` are the two
values of the *checkpoint* field on an `INVARIANT_CHECK` step, not a step kind of their own. The real
9th kind is `FOR_EACH`. This document is the corrected version; `EXECUTION_TREES.md` was fixed to
match in the same session.

### The 23 schema-level synonyms, and how they collapse to 9

Authors don't write `FOR_EACH` in JSON — the schema's `flowStep.type` enum accepts **23** synonym
strings (`model.schema.json:2173-2200`, the copy actually loaded at
`NPDevContract/dsl/src/main/resources/schema/model.schema.json`):

```
validate · invariant · enforceInvariants
capability · capabilityCall · callCapability
generatedAction · generated_action
event · emitEvent · scheduleEvent
return
branch · if
await · awaitEvent · waitForEvent
createConcept · updateConcept
assign
evaluateInvariant
forEach · loop
```

Two collapsing passes turn these into the 9 real kinds:

1. **Parse-time canonicalization** — `JsonModelParser.normalizeStepType`
   (`NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java:2085-2103`) collapses
   the 23 strings to 12 AST-level names.
2. **Compile-to-kernel mapping** —
   `CompiledModelFlowDefinitionProvider.toFlowSteps`
   (`NPDevKernel/adapters/flow-compiled/.../CompiledModelFlowDefinitionProvider.java:94-193`) maps those
   12 down to the 9 `FlowStepDefinition.Type` values. Notably, `generatedAction`, `createConcept`, and
   `updateConcept` are **all author-facing sugar for `CAPABILITY_CALL`** — they compile to a call
   against the concept's own generated CRUD capability, not a distinct runtime behavior.

   **Resolved (2026-07-27, the DSL 2.0 planning gate in `docs/DSL2_AND_DECOMPOSITION_PLAN.md` §2.A.0):**
   confirmed directly in `ModelCompiler.java` — `isConceptPersistenceStep`/`isCapabilityLikeStep`
   (`:1539-1547`, `:1587-1592`) both name `createEntity`/`updateEntity`/`createConcept`/`updateConcept`
   as concept-persistence steps, and `resolveCapabilityNameForStep`/`resolveOperationNameForStep`
   (`:1549-1567`) resolve them unconditionally to capability `"persistence"`, operation `"save"`. So
   this is genuinely **(a) sugar, not (b) a distinct behavior**: the "9 step kinds" count above is
   correct as stated, not understated.

### Worked examples, from real sample models

`NPDevSamples/medium-expense-approval/Input/model.json`'s one flow, `SubmitExpense` (`:260-340`),
exercises 6 of the 9 kinds in sequence:

```jsonc
{ "type": "validate", ... }                                    // → INVARIANT_CHECK
{ "type": "createConcept", ... }                                // → CAPABILITY_CALL
{ "type": "emitEvent", "event": "ExpenseSubmitted", ... }       // → EMIT_EVENT
{ "type": "if", "condition": "$saved.needsManagerApproval==true",
  "then": [
    { "type": "waitForEvent", "awaitEvent": "ExpenseApproved",
      "match": { "correlation": true } },                       // → AWAIT_EVENT
    { "type": "callCapability", "cap": "notification", ... },    // → CAPABILITY_CALL
    { "type": "callCapability", "cap": "webhook", ... },         // → CAPABILITY_CALL
    { "type": "return", "value": "$approval" }                   // → RETURN
  ], "else": [ ... ] }                                           // → BRANCH
```

`NPDevSamples/canonical-demo/Input/model.json`'s `CreateAppointment` flow (`:1115-1191`) covers `MAP`
(`"type": "assign"`, `:1154-1158`) and `SCHEDULE_EVENT` with a real delay (`"delayMinutes": 1440`,
`:1165-1183`). `NPDevSamples/user-minimal/Input/model.json`'s `AwaitDemo` flow (`:179-198`) is a
minimal, dedicated `AWAIT_EVENT` example — two steps, nothing else.

**Honest gap:** no real sample model in this repo uses `FOR_EACH` or `onFailure` (compensation) —
only test code (`KernelRunnerForEachDurabilityTest`, `KernelRunnerCompensationTest`) and the hand-written
snippets in `docs/architecture/FLOW_TRANSACTION_CONTRACT.md` exercise them. If you're looking for a
worked model to copy, those two kinds don't have one yet — recorded here rather than hidden.

## 4. `AWAIT_EVENT` in depth — the differentiator

This is the reason the engine exists, so it gets the most space.

### Suspending

When `executeSteps` hits an `AWAIT_EVENT` step whose event hasn't arrived
(`KernelRunner.java:1883-1925`), it stamps a wait descriptor into flow state under the key
`_npdev.await` (`buildAwaitState`, `KernelRunner.java:2512-2525`) — the awaited event name, whether
correlation matching applies, any payload-match refs, and the step's own index — then returns a
`WAITING_EVENT` result. `executeFlowInstance` turns that into `FlowInstance.markWaiting(...)`, and the
whole thing is written to the `npdev_flow_instance` table
(`NpdevFlowInstanceTable.java:17-57` — indexed on correlation id, waiting-event name, tenant, and
`(tenant, next_eligible_resume_at)` for the poller in §8) via whichever `FlowInstanceStore` adapter is
wired: `JdbcFlowInstanceStore` (Postgres/H2, real deployments) or `InProcFlowInstanceStore`
(`ConcurrentHashMap`-backed, dev/tests).

### What "correlation" actually means

An arriving event has to find the *right* waiting instance, not just *an* instance waiting on the
same event name. The match, all inside `KernelRunner.java`, requires **every** one of:

- the instance is still `WAITING_EVENT`;
- same tenant (`sameTenant`, `KernelRunner.java:4313-4321` — a blank tenant on either side is a
  wildcard, otherwise exact match);
- the wait descriptor's event name equals the arriving event's name;
- if the descriptor recorded a step index, it matches the instance's current step index;
- **correlation id matches**, unless the step explicitly opted out (`matchCorrelation: false`) —
  `matchesCorrelation`, `KernelRunner.java:4054-4059`, a plain string-equality check;
- **payload match**, if the step declared `payloadMatchRefs`
  (`matchesAwaitPayload`, `KernelRunner.java:4070-4092`) — this is how a step waits for, say, "the
  approval event whose `expenseId` equals mine," not just any event of that name;
- the event hasn't already been consumed by *this* execution (next section).

`resumeWaitingExecutionsFor` (`KernelRunner.java:2300-2357`) is what runs this match, whether
triggered by a live event just published or by the resume poller finding a persisted one.

### Ownership — two separate mechanisms, solving two separate problems

**(i) Correlation-id ownership** stops two different tenants' flows from colliding on the same
correlation id. `CorrelationOwnershipStore` (`JdbcCorrelationOwnershipStore` /
`InProcCorrelationOwnershipStore`) is claimed via `enforceCorrelationOwnership`
(`KernelRunner.java:4296-4311`) on every flow start and every external event. The JDBC adapter's real
safety net isn't the initial read — it's the database's own unique-constraint violation on
`npdev_correlation_owner` (`isDuplicateKey`, SQLState `23505`); the initial lookup is an optimization,
not the race-safety mechanism.

**(ii) Exactly-once event consumption per execution** stops one event — or one duplicate delivery —
from resuming the same instance twice. `isResumeEventAlreadyProcessed`/`markResumeEventProcessed`
(`KernelRunner.java:2483-2510`) is backed by the generic `IdempotencyStore` under a reserved
capability name (`__flow_resume`), keyed by `(tenant, executionId, eventId)`. The moment
`findAwaitedEvent` selects a candidate event for an instance, it's marked processed
(`KernelRunner.java:2475-2477`) — and the match logic refuses to re-match an already-processed event
for that execution.

### Surviving a restart — `ResumeBootstrapRunner`

`ResumeBootstrapRunner` (`NPDevKernel/adapters/resume-bootstrap-spring/.../ResumeBootstrapRunner.java:13-26`)
is a Spring `@EventListener(ApplicationReadyEvent.class)` hook: once, on boot, it calls
`kernelRunner.resumeAllWaitingExecutions(1000)`. It is a one-shot rehydration pass, not a special
code path — it drives the same `resumeAllWaitingExecutions` the recurring poller uses (§8), which
pulls every `WAITING_EVENT` instance across tenants and tries to resume each one whose awaited event
can actually be found, applying exponential backoff (base 5s, cap 300s) to anything still unresolved.
**This is the whole answer to "what happens if the JVM restarts while a flow is waiting":** nothing
special. The instance was already durable before the crash; boot finds it and tries to resume it,
exactly like the poller would have on its next tick anyway.

## 5. Compensation (LNCH-17)

Full spec: `docs/architecture/FLOW_TRANSACTION_CONTRACT.md`. This section is the guarantee, stated
plainly.

A flow with **zero** `onFailure` declarations anywhere takes the exact pre-LNCH-17 failure path — a
failing step just picks a terminal status (§2) and stops. A flow with **any** `onFailure` declared,
anywhere, changes what happens on failure: `beginCompensation` (`KernelRunner.java:2208-2214`) stamps
five reserved state keys (compensation-active flag, next-index, terminal status, error kind/code/
message), then `runCompensations` (`KernelRunner.java:2216-2268`) runs every already-completed step's
declared `onFailure` block, **in reverse order**, from the failed step back to step 0 — durably
checkpointing after *each* compensated step, not just at the end. The step that itself failed does
**not** run its own `onFailure` — only the steps before it are compensated. A compensation block that
itself throws is caught, logged, and skipped; the rest of the unwind still runs (best-effort, not
all-or-nothing). Once compensation finishes, the terminal status is applied via the **same**
`markFailed`/`markFailedPermanent`/`markStuck` the no-compensation path uses — a caller cannot observe
a different outcome shape just because compensation ran.

**The crash-mid-compensation guarantee.** The very first thing a resumed execution checks
(`KernelRunner.java:1063-1084`) is whether the compensation-active flag is set. If it is, the resumed
execution **skips the forward step loop entirely** and jumps straight into the reverse-compensation
loop at its recorded resume point. A crash mid-compensation leaves the instance in status `RUNNING`
(never finalized) with that flag still set; resuming it re-enters compensation, not the forward flow.
Proven by `KernelRunnerCompensationTest.crashMidCompensationThenResumeOnFreshRunnerFinishesRemainingCompensationsExactlyOnce`
(`NPDevKernel/kernel/src/test/java/com/npdev/kernel/KernelRunnerCompensationTest.java:62`) — a real
crash-injection test (freezes a thread mid-checkpoint), resumed on a brand-new `KernelRunner` with no
shared in-memory state.

**Scope limit, by design, not oversight:** compensation triggering is scoped to top-level flow steps
in this slice. A step nested inside a `branch` or `forEach` body can declare its own `onFailure`, but
the containing `branch`/`forEach` step's `onFailure` compensates the whole branch/loop as one unit —
there is no per-nested-step compensation yet.

## 6. `forEach` resumability (LIFT-LOOP-P2)

A `forEach` loop occupies exactly **one flat position** in the step trace — like `MAP` or
`CAPABILITY_CALL` — it does not expand the trace the way `BRANCH`'s nested steps do. Durability is at
**iteration granularity**: after each iteration's nested steps all succeed, a step-scoped progress key
(`"__forEachProgress." + stepName"`, `KernelRunner.java:2122`) records "next iteration to run" into
flow state, checkpointed at the **same** outer step index — not `+1` — because the loop itself hasn't
finished; a crash-and-resume here must re-enter this step, not skip past it
(`KernelRunner.java:2162-2165`). On (re-)entry, the loop simply starts at the recorded index
(`KernelRunner.java:2123,2129`), so **completed iterations are skipped on resume** — no replay, no
special-casing beyond "start where the counter says." `maxLoopIterations` (default 10,000,
`ProcedureExecutionLimits.java:10`) is checked against the collection's resolved size before iterating
— note the collection is fully materialized into memory first, so the cap bounds iteration count, not
memory (a known INFO-severity finding, `R4-F2`, §11).

Proven by `KernelRunnerForEachDurabilityTest.crashMidLoopThenResumeOnFreshRunnerProcessesEachItemExactlyOnce`
(`NPDevKernel/kernel/src/test/java/com/npdev/kernel/KernelRunnerForEachDurabilityTest.java:41-110`) — same
crash-injection technique as the compensation test, resumed on a fresh runner with no shared state,
asserts every item ran exactly once in order.

**The documented boundary:** `SemanticValidator` rejects a nested `AWAIT_EVENT` inside a `forEach`
loop body **at compile time** —
`containsAwaitStep` (`NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/SemanticValidator.java:2453-2455`),
which also recurses into a `branch` nested inside the loop body, not just top-level steps
(`:2495-2507`). This is a deliberate design decision, not a missing feature: durable resume of an
*in-flight await inside an iteration* is a materially harder state problem than resuming a loop that
never suspends, and it's deferred rather than half-supported. Own test:
`FlowForEachValidationTest.nestedAwaitInsideLoopBodyIsRejected`
(`NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/validation/FlowForEachValidationTest.java:102-114`).
Tracked as accepted boundary B15 in `docs/ACCEPTED_BOUNDARIES.md`.

## 7. Scheduling — two mechanisms, same name, don't confuse them

**`SCHEDULE_EVENT` (a flow step, §3)** publishes an event **immediately** — the "delay" is metadata
(`delaySeconds`, `scheduledForEpochMs`) stamped onto the event envelope for a consumer to honor, not a
kernel-enforced deferred dispatch (`KernelRunner.java:1689-1811`). It calls the exact same
`resumeWaitingExecutionsFor` an `EMIT_EVENT` step does (`KernelRunner.java:1802` vs. `:1682`) — zero
special-casing in the `AWAIT_EVENT`/correlation machinery.

**Flow-level cron scheduling (LNCH-12)** is a completely different, model-top-level feature: a
`flowSchedule` declaration with a `cron` expression and a `tenantScope`. Full doc:
`docs/SCHEDULED_FLOWS.md`. Implementation: `NpdevCronSchedulerService`
(`NPDevRuntimeHost/src/main/java/com/finalexec/scheduler/`) registers one Spring `CronTrigger` per
`(flow, tenant)` pair; an empty `tenantScope` defaults to `["default"]`. A scheduled run goes through
`kernelRunner.execute(flowName, Map.of(), context)` — **the exact same entrypoint an HTTP-triggered
flow uses** — running as a real system principal (`ExecutionContext.system(tenantId)`, actor
`system:scheduler`, role `ADMIN`), through the same `PermissionEvaluator` a human request would hit;
this is not an authorization bypass. Missed windows are skipped, not caught up (Spring's `CronTrigger`
semantics, for free). Because the run goes through the same `execute → executeSteps` path, any
`AWAIT_EVENT`/`EMIT_EVENT`/`SCHEDULE_EVENT` steps inside a cron-triggered flow behave identically to
one triggered any other way.

## 8. Hooks and orchestration rules — inject behavior without editing the flow

Two independent mechanisms, both configured at the model's top level rather than inside a flow's own
`steps`:

**Before/after step hooks** apply only to a flow that `specializes` a base flow
(`Flow.hooks`, schema `flowHook` `$defs`). A plain flow is forbidden from declaring `hooks` at all; a
specializing flow is forbidden from redefining `steps` — it must use `hooks` instead
(`ModelResolver.java:809-841`). `applyHooks` (`ModelResolver.java:888-935`) splices each hook's steps
immediately before or after its named `targetStep`, in the **base** flow's own step list, before
compilation ever sees the result. In practice: you never touch the base flow's file; a new
specializing flow adds only the incremental behavior. Worked example (the only one in the repo):
`NPDevContract/dsl/src/test/resources/specialization/valid-specialization.json:75-102`.

**Event-triggered orchestration rules** are model-top-level `orchestrationRules[]` — not part of any
flow at all. Each has a trigger event, an optional condition expression over `$event`, and one or more
actions (`create`, `callCapability`, `scheduleEvent`). The runtime is a **genuinely separate mechanism**
from `KernelRunner`'s flow-step execution: `GeneratedCrudRuntimeSupport.initializeOrchestrationSubscribers`
subscribes directly to the event bus per orchestration
(`NPDevKernel/adapters/expression-cel/.../GeneratedCrudRuntimeSupport.java:1188-1210`), sitting alongside
the flow engine's own subscribers, not routed through `FlowStepDefinition`/`executeSteps`. Each firing
claims an exactly-once execution slot (`OrchestrationExecutionRegistry`, keyed by orchestration name +
source event id) before running its actions. Worked example:
`NPDevSamples/canonical-demo/Input/model.json:1064-1111` — `CompleteAppointmentFlow`, triggered on
`AppointmentCompleted`, conditioned on `$event.status`, creating an `InsuranceClaim` and calling
`notification.send`.

**Lifecycle rule profiles** are a third, distinct "config the model, not the code" mechanism —
`ConceptRuleProfile` (`ALWAYS`, `INTERACTIVE`, `HEADLESS`, `QUERY`, `BEFORE_COMMIT`, `AFTER_COMMIT`) —
but they gate *when a concept's own write-semantics rules apply* (interactive UI action vs. headless
procedure vs. commit phase vs. read), independent of any flow. Worked example:
`NPDevSamples/medium-expense-approval/Input/model.json:150-173`.

None of the three is currently covered by `docs/DSL_REFERENCE.md`'s own table of contents (an
auto-generated doc, `scripts/docs/generate_dsl_reference.py`) — genuinely undocumented surface until
now.

## 9. Operations

**Where instances live:** `npdev_flow_instance` is the sole system of record for every in-flight or
terminal execution; `npdev_correlation_owner` is the adjacent ownership table (§4).

**Inspecting a `STUCK` instance.** The store layer has a purpose-built query —
`ExecutionSummaryStore.listStuckSummaries` (implemented by both `JdbcFlowInstanceStore` and
`InProcFlowInstanceStore`) — but as of this writing **no REST controller calls it**; the capability
exists at the store/port layer without a wired endpoint. Today's actual operator-facing surfaces are:
`GET /api/executions/active` / `.../history` (`ExecutionMonitorController.java`, which does flag
`STUCK` as `NEEDS_ATTENTION` alongside `FAILED`/`FAILED_PERMANENT`), and the `npdev-cli`'s
`printTrace`/`resumeExecution` subcommands for direct, single-instance inspection or a forced retry.

**What actually triggers a resume attempt**, in order of how often each fires:

1. **Any event publish**, from anywhere — an external event, an `EMIT_EVENT` step, or a
   `SCHEDULE_EVENT` step — synchronously tries to wake matching waiters in the same call.
2. **The recurring poller** — `ResumeSchedulerRunner`, `@Scheduled` every 2s by default
   (`npdev.scheduler.tick-millis` / `npdev.resume.pollMs`), calling `resumeAllWaitingExecutions`.
3. **Boot** — `ResumeBootstrapRunner`, once, on `ApplicationReadyEvent` (§4).
4. **A generated action endpoint** — an authenticated caller submitting the awaited event's payload
   through a generated REST action is itself a resume trigger (`TrustedSourceEmitter`-generated code
   publishes the event, then calls `resumeExecution` if the target instance is `WAITING_EVENT`).

**A documented authorization gap, already dispositioned:** resume authorization is tenant-scoped, and
— after `REG-45`'s disposition — also actor-scoped (the owner chose "require the originating actor").
Identity does not survive suspension itself: event-driven and scheduler-triggered resumes run as
`ExecutionContext.anonymous()`; only the HTTP resume path runs as the resuming caller's own context.
See §11 for the review that found this.

## 10. Honest limits

- No per-nested-step compensation — only whole `branch`/`forEach` units (§5).
- `AWAIT_EVENT` cannot be nested inside a `forEach` loop body — rejected at compile time, not silently
  broken (§6, accepted boundary B15).
- `forEach` materializes its whole collection into memory before iterating; `maxLoopIterations` bounds
  iteration count, not memory (§6, `R4-F2`, INFO).
- No real sample model exercises `FOR_EACH`, `onFailure`/compensation, or hooks — only test code and
  hand-written spec snippets do (§3, §5, §8). If you're authoring one of these for the first time,
  there's no worked model to copy from yet.
- `STUCK` has no coded "un-stick" operation; `listStuckSummaries` exists at the store layer with no
  wired REST endpoint (§2, §9).
- Resume authorization did not originally consider the originating actor (only the tenant) — found and
  dispositioned as `REG-45` (§11).

## 11. Evidence

`REG16_FLOW_ORCHESTRATION_ADVERSARIAL_REVIEW.md` (Round 4, 2026-07-25) is the adversarial review of
exactly the surface this document describes: `KernelRunner`'s suspend/resume path, the generated
`KernelFacade`'s execution/resume endpoints, `DefaultExecutionAuthorizationPolicy`, and loop-step
bounding. Its headline question — *can a resumed flow run under a different actor's or tenant's
context than the one that suspended it?* — resolved in the safe direction: identity doesn't survive
suspension at all, so no confused-deputy attack is constructible. Verdict: **no CRITICAL, no HIGH**;
one MEDIUM (`R4-F1`, filed as `REG-45` — resume authorization was tenant-scoped but not
actor-scoped; disposed by requiring the originating actor, §9) and three INFO (`R4-F2` `forEach`
memory materialization, §6; `R4-F3` a benign lookup-miss skip; `R4-F4` an unreachable `KernelFacade`
null-policy fallback).

This review is filed alongside five sibling `REG16_*` adversarial reviews (kernel execution, tenant
auth, row-level authz, export/PDF, codegen output) in `docs/archive/programme-history/` — the same
completed-and-dispositioned-review convention this repo already uses, not singled out or buried. Full
text: `docs/archive/programme-history/REG16_FLOW_ORCHESTRATION_ADVERSARIAL_REVIEW.md`.

---

*See also: `docs/architecture/FLOW_TRANSACTION_CONTRACT.md` (compensation, full spec) ·
`docs/SCHEDULED_FLOWS.md` (cron scheduling, full spec) · `docs/BOUNDARY_LIFT_ROADMAP.md` (LIFT-LOOP-P1/P2) ·
`docs/ACCEPTED_BOUNDARIES.md` (B15, nested-await rejection) · `FlowEngine.java`'s own javadoc
(T1.16) for the port/implementation split.*
