# REG-16-resid Round 4 — flow / `await` orchestration: adversarial review

> **Date:** 2026-07-25 · **Branch:** `beta1-vision-spine`
> **Surface:** `KernelRunner`'s suspend/resume path (`resumeExecution`, `resumeFlow`,
> `resumeAllWaitingExecutions`, `resumeWaitingExecutionsFor`), the generated `KernelFacade`'s
> execution/resume endpoints, `DefaultExecutionAuthorizationPolicy`, and loop-step bounding.
> **Plan:** [`ONE_PLAN_CLOSE_EVERYTHING.md`](ONE_PLAN_CLOSE_EVERYTHING.md) §4.1

---

## 0. Headline

**No CRITICAL, no HIGH. One MEDIUM filed, three INFO.**

The plan names one question as the highest-value in this round:

> *Can a resumed flow run under a **different** actor's or tenant's context than the one that
> suspended it?*

**It always does — and that is the safe direction.** Identity does not survive suspension at all.
There is no stored "run as the original actor" credential to steal, so the confused-deputy attack this
question is really about **cannot be constructed**. §1 sets out what actually happens instead.

| ID | Finding | Sev | State |
|---|---|---|---|
| **R4-F1** | Resume authorization is scoped by tenant but **not by actor** — any holder of `RESUME_EXECUTIONS` can resume another user's flow and receive its accumulated state | MED | filed **REG-45** |
| **R4-F2** | `forEach` materializes the whole iterable *before* checking `maxLoopIterations` | INFO | recorded |
| **R4-F3** | Resume authorization is skipped entirely when the instance lookup misses | INFO | recorded |
| **R4-F4** | `KernelFacade` falls back to `ExecutionAuthorizationPolicy.ALLOW_ALL` on a null policy | INFO | recorded |

---

## 1. The headline question: does identity survive a suspension?

**No — and the design is better for it.** There are three ways a suspended flow resumes, and none of
them replays the original actor's authority:

| Resume path | Context the remaining steps run under |
|---|---|
| `KernelFacade.resumeExecution` (HTTP) | the **resumer's** `ExecutionContext` |
| `resumeFlow(correlationId, event)` (event-driven) | `ExecutionContext.anonymous()` |
| `resumeAllWaitingExecutions` (scheduler sweep) | `ExecutionContext.anonymous()` |

The dangerous shape would be the opposite: a flow resuming under the **suspender's** stored authority
while the **resumer** supplies the event payload. That is a textbook confused deputy — attacker data
executing with a victim's privileges. It is not what happens here, because no authority is stored to
replay.

**`ExecutionContext.anonymous()` is `("default", "default", …)`** — and `"default"` is the platform's
reserved sentinel for "no tenant registered", which `DefaultExecutionAuthorizationPolicy` denies
across the board (the same sentinel `TenantRegistryService.create` refuses to register). So an
event-driven resume degrades to a context that **fails closed** on every subsequent authorization
check.

> Security-wise that is the right direction, and it is the answer to the round's headline question.
> **Functionally it is a sharp edge**, and worth being explicit about: the steps after an `await` in an
> event-resumed flow cannot touch tenant-scoped data, because the identity they would need was not
> carried across the suspension. This is the same reserved-`default`-sentinel behaviour already
> tracked as an open platform gap; recorded here as the flow-path manifestation of it, not as a new
> security defect.

---

## 2. R4-F1 (MED, filed as REG-45) — resume is tenant-scoped but not actor-scoped

```java
public boolean canResumeExecution(ExecutionContext requester, FlowInstance instance) {
    if (!isRequesterAuthorized(requester)
            || !hasPermission(requester, Permission.RESUME_EXECUTIONS)
            || instance == null) {
        return false;
    }
    return tenantIsolationPolicy.sameTenant(requester.tenantId(), instance.tenantId());
}
```

Cross-tenant resume is correctly refused. **Within** a tenant there is no per-instance check: any
holder of `RESUME_EXECUTIONS` may resume *any* waiting instance, and `resumeExecution` returns the
resulting `ExecutionResult` — which carries the flow's accumulated state.

A suspended flow's state is whatever its steps put there before the `await`: records read under the
**original** actor's row-level `access.read` scope. So the resumer can receive data they could not
have read directly. The row-level scoping LNCH-13 enforces on the concept surface has no equivalent
on the execution surface.

**Why MEDIUM, not HIGH.** It needs the `RESUME_EXECUTIONS` permission — an operator-grade permission,
not something an ordinary user holds — plus the target `executionId`, which is a UUID. It is a
missing defence-in-depth layer behind a permission gate, not an open door.

**Why filed rather than fixed:** the fix is a policy decision the platform has not made — whether
resume should require being the originating actor, holding an explicit override permission, or
neither. Inventing that answer inside a review round would be the wrong way to decide it. Filed as
**REG-45** with the options stated.

---

## 3. INFO findings

### 3.1 R4-F2 — the loop cap is enforced *after* the cost it exists to prevent

```java
List<Object> items = new ArrayList<>();
for (Object item : iterable) {
    items.add(item);          // ← the entire iterable is materialized first
}
int cap = step.getMaxLoopIterations() != null ? … : DEFAULT_MAX_LOOP_ITERATIONS;  // 10,000
if (items.size() > cap) { … fail … }
```

The bound is real and it is enforced — but only after the whole collection is already in the heap. It
therefore bounds **iterations**, not **memory**, and a caller-influenced collection of ten million
elements is fully loaded before being rejected.

This is R6-F1's shape exactly (a bound that is checked after the resource has been consumed), which is
why it is worth naming as a class rather than a one-off: **a limit checked after materialization is
not a limit on the thing that hurts.** The fix is to count while draining and abort at `cap + 1`.

`DEFAULT_MAX_STEPS` (1,000) and `DEFAULT_MAX_RECURSION_DEPTH` (16) are both present and correctly
enforced, so nested loops are bounded by construction.

### 3.2 R4-F3 — authorization skipped when the lookup misses

```java
Optional<FlowInstance> instanceOpt = flowInstanceStore.findByExecutionId(executionId);
if (instanceOpt.isPresent() && !executionAuthorizationPolicy.canResumeExecution(ctx, instanceOpt.get())) {
    throw forbidden();
}
ExecutionResult result = kernelRunner.resumeExecution(executionId, ctx);   // runs regardless
```

When the instance is absent the authorization check is skipped and `resumeExecution` is called anyway.
**Benign today**: `resumeExecution` re-fetches the instance and returns a "not found" failure, so
nothing executes. But the shape — *skip the check when the lookup misses, then act on the same key* —
is a check-then-act window, and it is the same family as REG-41. If the instance is created between
the two lookups, the resume proceeds with no authorization at all.

Reaching it needs the target `executionId` (a UUID) and a race against its creation, so it is INFO.
Restructuring to `resumeExecution`-then-authorize, or authorizing inside the kernel where the instance
is already loaded, removes the window entirely.

### 3.3 R4-F4 — `ALLOW_ALL` as a null fallback

```java
this.executionAuthorizationPolicy = executionAuthorizationPolicy == null
        ? ExecutionAuthorizationPolicy.ALLOW_ALL
        : executionAuthorizationPolicy;
```

A missing authorization policy silently becomes *permit everything* — flow execution, trace reads,
execution reads, event publishing, resume — with no error and no log.

**Not reachable in a generated app**, and that was checked: `KernelFacade` is a `@Service` with a
single constructor, so Spring fails fast if the bean is missing, and `NpdevAuthConfig` declares it
unconditionally with no `@ConditionalOn…`. But the constructor is public and the class is directly
instantiable, and the same `x == null ? NOOP : x` pattern on the same constructor also silently
disables **trace/event/execution redaction**.

Round 3 hit this exact decision from the other side and resolved it the opposite way: the new
`ConceptGateway.authorizeWrite` **denies** by default, precisely because a permissive default is the
bug. The same reasoning applies here — a `KernelFacade` with no authorization policy is a
misconfiguration, not a mode, and should fail fast rather than open.

---

## 4. Negative results

- **Is the resume token unguessable, single-use, expiring?** There is no separate resume token: the
  key is the `executionId`, a UUID, and authorization is a real policy check rather than knowledge of
  the identifier. That is a *stronger* design than a bearer token — nothing is single-use because
  nothing is a secret. Replay is bounded by status instead: `resumeExecution` refuses any instance not
  in `WAITING_EVENT` or `RUNNING`, so a completed flow cannot be re-driven.
- **Is loop iteration bounded under adversarial input? Nested loops?** Yes — `maxLoopIterations`
  (10,000 default, per-step overridable), `DEFAULT_MAX_STEPS` (1,000) and
  `DEFAULT_MAX_RECURSION_DEPTH` (16). See R4-F2 for the one weakness in *how* the first is applied.
- **On partial failure mid-flow, is durable state left readable/writable by the wrong actor?** The
  instance keeps its own `tenantId`, and every resume path re-checks tenancy against it
  (`canResumeExecution`) or degrades to the fail-closed anonymous context. The gap is actor-level, not
  tenant-level — that is R4-F1.

---

## 5. Follow-ups filed

| Item | Sev | Note |
|---|---|---|
| **REG-45** — resume is not actor-scoped | MED | Policy decision required; see §2 |
| R4-F2 — count while draining instead of materializing then checking | INFO | Same class as R6-F1 |
| R4-F3 — remove the check-then-act window on resume | INFO | Same family as REG-41 |
| R4-F4 — make a missing authorization policy fail fast, not open | INFO | Mirrors Round 3's `authorizeWrite` decision |
