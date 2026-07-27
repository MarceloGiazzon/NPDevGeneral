# REG-16-resid Round 1 — Adversarial review of the kernel execution path

> **Status:** Round 1 COMPLETE — 2026-07-24. Independent, attack-first review of `KernelRunner` +
> `RegistryCapabilityDispatcher` + the idempotency/circuit-breaker/bulkhead mechanisms, per
> `docs/POST_REG17_CLOSURE_PLAN.md` Task 4. This is the code every generated app runs (the shared
> execution engine), the highest-value surface REG-16-resid names for Round 1. It is *not* a review
> of a diff — REG-16-resid's point is that this surface has had **zero** attack-first review before
> now (REG-16's original Tier-A scoped only tenant-isolation + auth).
>
> **Headline:** **no CRITICAL or HIGH finding.** Tenant scoping of durable resilience state
> (idempotency/circuit/bulkhead all key on `CapabilityOpKey(tenantId, capability, operation)`) is
> correct, and the reflection-based capability dispatch never lets request data choose *which*
> method gets invoked — only argument *values* are caller-influenced. The residual findings are
> **2 MEDIUM + 2 INFO**: a lost-update race in the circuit breaker's failure counter (present in
> both the in-proc and Postgres stores — the bug is in `KernelRunner`'s orchestration, not either
> store), and an unbounded idempotency-key value that can crash the post-success cache write and
> defeat the very duplicate-suppression guarantee idempotency exists to provide. Per the plan's
> triage rules, with no CRITICAL/HIGH the mandatory Tier-B work is empty; both MEDIUMs are filed as
> new dated register items (REG-36, REG-37) rather than silently dropped.

---

## R0 — Scope actually read

**Core execution engine:** `com.npdev.kernel.KernelRunner` (4446 lines) — specifically
`invokeCapabilityWithPolicy` (circuit-gate → bulkhead-acquire → idempotency-check → retry loop →
idempotency-cache-write → circuit-failure-accounting), `invokeCapabilityOnce`/the dispatch call
site, `gateCircuit`, `onCapabilityFailure`/`onCapabilitySuccess`, `resolveIdempotencyKey`,
`encodeIdempotencyResult`, the persistence `query`/`list` tenant-stamping enrichment, and
`normalizeTenantOrDefault`.

**Dispatch:** `com.npdev.kernel.RegistryCapabilityDispatcher` (300 lines, read in full) — contract
validation, adapter resolution, the 2-arg-save enrichment heuristic, reflection-based
`resolveOperation`/`method.invoke`, and `classifyInvocationError`.

**Resilience state stores (read both the port and both backing adapters for each):**
`CircuitBreakerStateStore` (port + `InProcCircuitBreakerStateStore` + `JdbcCircuitBreakerStateStore`),
`BulkheadStore` (port + `InProcBulkheadStore`), `IdempotencyStore` (port + `JdbcIdempotencyStore`),
plus `RuntimeOverrideCapabilityBindingResolver` + `GeneratedRuntimeOverridesLoader` (traced to
confirm the binding-override mechanism is boot-time/build-generated, not request-influenced) and
`ExecutionContext` (traced the `"default"` tenant sentinel referenced in F4 below).

**Not read this round** (candidates for a later round, named so they aren't mistaken for "reviewed
clean"): `FlowEngine` step types beyond the capability-call path (loop/await/orchestration steps),
`DefaultProcedureExecutor`, the Postgres `flowinstance`/`eventstore`/`tracestore` adapters' own SQL,
and the generated-app CRUD/service layer (`UserServiceBase`-style generated code) that calls into
`KernelRunner` from the HTTP boundary.

---

## What is solid (recorded so the review is honest about the baseline)

- **Resilience state is tenant-scoped.** `CapabilityOpKey(tenantId, capabilityName, operationName)`
  is the key for all three mechanisms (idempotency/circuit/bulkhead) — confirmed in both the
  in-proc (`ConcurrentHashMap<CapabilityOpKey,_>`) and Postgres (`WHERE tenant_id = ? AND ...`)
  stores. A cached idempotent result, an open circuit, or bulkhead pressure in tenant A cannot leak
  into or be observed by tenant B.
- **Bulkhead admission control is genuinely atomic.** `InProcBulkheadStore.tryAcquire` uses a real
  `java.util.concurrent.Semaphore.tryAcquire()` — even though the circuit breaker's own half-open
  gate (see F2 below) can let multiple threads believe they're the trial request, the bulkhead's
  `maxConcurrent=1` during a half-open trial correctly admits only one of them; the rest are
  cleanly rejected (`CAPABILITY_BULKHEAD_FULL`, `TRANSIENT`). The race in F2 does not translate
  into extra concurrent execution against a struggling downstream.
- **Reflection-based dispatch cannot be steered to an arbitrary method by request data.**
  `RegistryCapabilityDispatcher.resolveOperation` selects a method by name + arg-count, but the
  `operation` string always originates from the **compiled model** (`step.getOperation()`,
  author-time), never from the HTTP request. Only the argument *values* (resolved from the flow's
  runtime `state`/`input`) are caller-influenced — the normal, intended trust model for a
  config-driven flow engine, not a confused-deputy/RCE surface.
- **The binding-override mechanism is not request-influenced.** `RuntimeOverrideCapabilityBindingResolver`
  reads `RuntimeOverridesManifest` from `GeneratedRuntimeOverridesLoader`, a generated class reading
  a generated, hash-guarded resource file — boot-time/build-time configuration, not a runtime API a
  caller (even an authenticated tenant admin) can influence per-request.
- **Query/list enrichment stamps the caller's own tenant, not an attacker-suppliable one.**
  `KernelRunner`'s persistence `query`/`list` enrichment (line ~3617) sets
  `stamped.put("tenantId", flowStateTenantId(state))` from the already-resolved execution context,
  not from caller-supplied criteria — consistent with the auth review's finding that tenant
  stamping on writes is similarly non-overridable.
- **Error classification is principled, not a blanket catch-all.** `classifyInvocationError` walks
  the cause chain (guarding against infinite self-referential cause loops) to distinguish a genuine
  DB integrity violation (CONTRACT — caller's fault) from a permanent system error, and separately
  recognizes auth/rate-limit/timeout/transient signatures — a real taxonomy, not "everything is
  PERMANENT."

---

## R1 — Findings → severity map

| ID | Sev | Area | One-line |
|---|---|---|---|
| REG16K-F1 | **MEDIUM** | idempotency | Unbounded idempotency-key value can crash the post-success cache write, reporting a successful call as failed and defeating dedup on retry |
| REG16K-F2 | **MEDIUM** | circuit breaker | Failure-counter lost-update race under concurrent failures (both in-proc and Postgres stores) weakens the failure-threshold guarantee |
| REG16K-F3 | INFO | error disclosure | Adapter exception messages flow verbatim into `CapabilityResult.failure(...)`, which at least one HTTP surface returns to the caller as-is |
| REG16K-F4 | INFO | tenancy (cross-reference) | Resilience-state keying shares the already-tracked `"default"` tenant sentinel (gap #15 / REG16-F7 / REG-24) — same root cause, new manifestation site, already guarded per that item's Tier-B outcome |

### REG16K-F1 — Unbounded idempotency-key value defeats dedup on retry · MEDIUM

- **Where:** `KernelRunner.resolveIdempotencyKey` (`~line 3812`) resolves the key as
  `String.valueOf(resolved)`, where `resolved` comes from `policy.idempotencyKeyField()` evaluated
  against the flow's runtime `state`/`input` — i.e. a **caller-controlled value** at a
  **model-author-chosen field** (a normal, encouraged pattern: "use the request's `orderId` as the
  idempotency key"). `NpdevIdempotencyTable` declares `idempotency_key` as `TEXT` and includes it in
  the table's **primary key**. `KernelRunner` bounds the *cached success value*
  (`encodeIdempotencyResult`, `IDEMPOTENCY_RESULT_MAX_CHARS = 16_384`, truncates if longer) but
  applies **no equivalent bound to the key itself**, anywhere.
- **Why it matters:** PostgreSQL's default B-tree index has a per-entry size ceiling (historically
  ~2704 bytes on an 8 KB page). A caller who supplies an oversized value for whatever field is
  configured as the idempotency key produces a key long enough to exceed that ceiling. The resulting
  `SQLException` on `INSERT`/`UPDATE INTO npdev_idempotency` is **not a duplicate-key** case, so
  `JdbcIdempotencyStore` rethrows it as `IllegalStateException("Failed inserting idempotency
  record", …)`. This call (`idempotencyStore.saveSuccess(...)`, `KernelRunner.java:3022`) sits
  **after** the real capability call already returned success, and is **not caught** anywhere in
  `invokeCapabilityWithPolicy`'s try block (only a `finally { bulkheadStore.release(opKey); }`
  follows) — so the exception propagates out of a call that, from the business logic's point of
  view, **already succeeded**.
- **Failure scenario:** an app declares `idempotencyKeyField: "$input.orderId"` on a `persistence.save`
  step (a normal, documented pattern). A caller POSTs a request with `orderId` set to a ~3 KB string
  (well inside any reasonable `apiMaxBodyBytes` cap — no request-size limit is violated). The save
  succeeds (the row is written). The idempotency-cache write then throws; the caller receives an
  error (whatever wraps the uncaught `IllegalStateException` — a bare 500 on the generated CRUD path,
  by the same mechanism LEDGER-1 just fixed for a different missing-binding cause). Believing the
  call failed, the caller **retries with the same request** (the entire point of supplying an
  idempotency key). Because no record was ever successfully cached, the retry finds no cached hit
  and **re-executes the save** — a duplicate row, defeating the exact guarantee the idempotency key
  exists to provide.
- **Fix:** bound the key symmetrically with the already-bounded value — e.g. if the resolved key
  exceeds a small threshold (a few hundred chars is generous for a real business key), store a
  fixed-length digest (SHA-256 hex) of it instead of the raw value, both when writing and when
  looking up. Add a regression test: a `persistence.save` with an oversized idempotency-key-field
  value succeeds and produces a cacheable, re-findable idempotency record (RED: current code throws
  on the cache write for a large-enough key; GREEN: digested key stays within any index limit).

### REG16K-F2 — Circuit-breaker failure-counter lost-update race · MEDIUM

- **Where:** `KernelRunner.onCapabilityFailure` (`~line 3376`): `CircuitBreakerState current =
  circuitBreakerStateStore.get(key)`, then `int failures = current.consecutiveFailures() + 1`,
  then (later) `circuitBreakerStateStore.put(key, new CircuitBreakerState(..., failures, ...))`.
  This is a plain read-modify-write with **no compare-and-swap, no per-key lock, no atomic
  increment**. Confirmed at *both* ends: `CircuitBreakerStateStore` (the port interface) exposes only
  plain `get`/`put`, no versioned/CAS variant; `InProcCircuitBreakerStateStore` backs it with a
  bare `ConcurrentHashMap.get`/`.put` (thread-safe *per call*, not across the read-then-write pair);
  `JdbcCircuitBreakerStateStore.update` is a blind `UPDATE ... SET consecutive_failures = ? WHERE
  …` with the **client-computed** value — no `SET consecutive_failures = consecutive_failures + 1`,
  no `SELECT … FOR UPDATE`, no optimistic-lock version column. The bug is in `KernelRunner`'s
  orchestration, so switching backing stores does not fix it.
- **Why it matters:** the circuit breaker exists precisely for the scenario where many **concurrent**
  calls to the same capability are failing (a struggling downstream dependency). That is exactly the
  scenario in which this lost-update race fires: N threads all read the same `consecutiveFailures`
  before any of them writes back, so N concurrent failures can increment the stored counter by as
  little as 1 instead of N. The circuit's configured `circuitOpenAfterFailures` threshold (default 5)
  can then be reached far later than intended, or never, under sustained concurrent failure load —
  the mechanism silently protects less than it is configured to.
- **Failure scenario:** a downstream Postgres instance starts timing out. `effectivePolicy
  .bulkheadMaxConcurrent()` (default 10, or whatever the app configures) concurrent calls are in
  flight when this begins; several fail at nearly the same moment. Each racing thread reads
  `consecutiveFailures=N` before any writes `N+1` back — the stored value undercounts real failures.
  The circuit stays CLOSED well past the point 5 *sequential* failures would have opened it,
  continuing to send full concurrent load at an already-failing dependency instead of shedding it.
- **Fix:** make the read-modify-write atomic at the point of use — either (a) a per-`CapabilityOpKey`
  lock in `KernelRunner` around the get/decide/put sequence (simplest, in-process only — a Postgres
  deployment with multiple RuntimeHost instances would still race across instances), or (b) push the
  increment into the store contract itself (a `recordFailure(key, now, thresholds) -> CircuitBreakerState`
  method that the in-proc store implements via `ConcurrentHashMap.compute` and the Postgres store
  implements via `UPDATE … SET consecutive_failures = consecutive_failures + 1 … RETURNING *`, which
  is safe under Postgres's own row-level locking during the update). (b) is the only option that
  also fixes the multi-instance case. Regression test: N concurrent synthetic failures against
  the same `CapabilityOpKey` must leave `consecutiveFailures == N` (or trip the circuit), not fewer.

### REG16K-F3 — Adapter exception messages reach the caller verbatim · INFORMATIONAL

- **Where:** `RegistryCapabilityDispatcher.invoke` and `KernelRunner.invokeCapabilityOnce`/
  `invokeCapabilityWithPolicy` both do `exception.getMessage()` (or the `InvocationTargetException`
  cause's message) straight into `CapabilityResult.failure(code, message, kind, details)`, with no
  redaction step. Confirmed reaching an HTTP caller verbatim during this session's LEDGER-1
  verification: `POST /api/flows/CreateUser/execute` returned
  `"error":"Capability binding not found for capability 'persistence' and adapter '<missing>'"` —
  benign in that specific case, but the mechanism is generic: **whatever `getMessage()` returns on
  any adapter exception** is what the caller sees, for every capability and every adapter.
- **Why it matters:** this is not a cross-tenant or auth-bypass leak — the caller is seeing detail
  about the *operation they themselves invoked*, gated by the same `capability.invoke`
  permission check the auth review already covers. But it is an **implicit contract**: an adapter
  author writing a new capability (or a driver upgrade changing an exception's message format) could
  unknowingly start surfacing internal detail (a JDBC driver's message occasionally includes
  connection-string fragments, internal table/column names beyond the ones already deliberately
  allow-listed by `classifyInvocationError`'s integrity-violation keyword match, or stack-adjacent
  detail) to any caller with permission to invoke that one capability.
- **Recommendation (no change made — informational):** if this becomes a review priority, an
  allow-list-based message rather than raw `getMessage()` passthrough (mirroring how
  `classifyInvocationError` already allow-lists specific *keywords* rather than trusting the whole
  message) would remove the implicit-contract risk. Not filed as a dated item since there is no
  concrete instance of an actual harmful message today — recorded for the next reviewer.

### REG16K-F4 — Resilience-state tenant keying shares the `"default"` sentinel · INFORMATIONAL (cross-reference)

- **Where:** `KernelRunner.normalizeTenantOrDefault` falls back to `ExecutionContext.anonymous()
  .tenantId()`, which is the literal string `"default"` (`ExecutionContext.DEFAULT_TENANT_ID`) — the
  same sentinel already tracked as platform gap **#15** and reviewed as **REG16-F7 / REG-24** in the
  tenant/auth adversarial review.
- **Why it matters:** if a caller ever reaches `invokeCapabilityWithPolicy` with a null/blank tenant
  context, its idempotency/circuit/bulkhead state would bucket under the same `CapabilityOpKey` as a
  **real** tenant literally named `default` — a resource-interference angle (one could trip the
  other's circuit breaker or exhaust its bulkhead) layered on top of the already-known isolation
  concern. REG-24's Tier-B outcome recorded that **every tenant-insert path already reserves
  `default`** (a real tenant cannot be named `default`), so today there is no actual collision
  partner — this entry exists so a future reviewer doesn't have to re-derive that the same root
  cause reaches this mechanism too, and re-checks it if REG-24's guard is ever relaxed.

---

## R2 — Triage + remediation plan

Per `docs/POST_REG17_CLOSURE_PLAN.md` Task 4 / the REG-16 triage convention:

- **CRITICAL / HIGH — none.** No data-breach, auth-bypass, or cross-tenant read/write was found in
  this surface. Tier B therefore has **no mandatory blocking work** for this round.
- **MEDIUM — filed as new dated register entries, not fixed in this pass:** F1 → **REG-36**, F2 →
  **REG-37**. Both have a concrete RED-first test sketch above; neither is implemented here —
  a concurrency-mechanism fix (F2) and a keying-scheme change touching a primary-key column (F1)
  both deserve their own RED-first verification pass rather than being rushed inside the review
  itself, consistent with "REG-16-resid... is iterative... its output is findings, not a diff."
- **INFORMATIONAL:** F3 and F4 — no register item filed; F3 is a recommendation for a future
  reviewer, F4 is a cross-reference confirming an already-tracked, already-guarded item also reaches
  this mechanism.

### New register items filed (dated 2026-07-24)

| New item | From | Sev | Fix sketch |
|---|---|---|---|
| REG-36 | F1 | MED | Bound/digest the idempotency key (SHA-256 hex when the resolved value exceeds a small threshold) symmetrically with the already-bounded cached value; regression test with an oversized `idempotencyKeyField` value. |
| REG-37 | F2 | MED | Make the circuit-breaker failure-counter update atomic — push the increment into the store contract (`recordFailure`) so both the in-proc (`ConcurrentHashMap.compute`) and Postgres (`SET consecutive_failures = consecutive_failures + 1 … RETURNING *`) implementations are race-free, including across multiple RuntimeHost instances for the Postgres case. Regression test: N concurrent synthetic failures leave the counter at N (or an open circuit), not fewer. |

### Recommended order if/when scheduled

1. **REG-37** first — the fix is more self-contained (one store-contract method + two
   implementations) and directly strengthens the mechanism meant to protect a downstream dependency
   under exactly the load pattern this review is about.
2. **REG-36** — needs a decision on the digest threshold and whether existing idempotency records
   (keyed on raw values) need a migration note (a digest scheme is a breaking change to key
   *encoding*, though not to the guarantee); scope the migration story before implementing.

### Verification bar for either fix

RED-first (a concurrent-failure test for REG-37; an oversized-key test for REG-36) against a real
generated app exercising both the in-proc and Postgres adapters (mirroring the auth review's "live
rehearsal, not just unit tests" bar) before either is marked closed.

---

## Honest statement of what Round 1 did and did not establish

- **Did:** an independent, attack-first read of the kernel execution path named in
  `docs/POST_REG17_CLOSURE_PLAN.md` Task 4 — `KernelRunner`'s capability-invocation path,
  `RegistryCapabilityDispatcher`, and the idempotency/circuit/bulkhead mechanisms, across both the
  in-proc and Postgres-backed adapters where relevant. Produced 4 findings (2 MEDIUM + 2 INFO) with
  concrete failure scenarios; filed the MEDIUMs as dated register items so neither is dropped.
  **REG-16-resid's problem statement for this surface — "zero adversarial review of the code every
  generated app runs" — is resolved by this document existing.**
- **Did not:** implement fixes. With no CRITICAL/HIGH finding, there is no *mandatory* Tier-B work;
  REG-36/REG-37 are scheduled, not dropped. Did not review the surfaces listed under "not read this
  round" (loop/await/orchestration step types, `DefaultProcedureExecutor`, the other durable-state
  Postgres adapters' own SQL, generated-CRUD-to-KernelRunner call sites) — those remain **zero
  adversarial review** and are candidates for Round 2+, per the plan's explicit framing that full
  closure is "all high-value surfaces reviewed — a multi-round programme, not one task."
