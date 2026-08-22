# REG-16-resid Round 5 — durable-state Postgres adapters' own SQL: adversarial review

> **Date:** 2026-07-25 · **Branch:** `beta1-vision-spine`
> **Surface:** hand-written SQL in `NPDevKernel/adapters/*-postgres` (trace, event, audit, idempotency,
> circuit, bulkhead, flow-instance, persistence) and the RuntimeHost stores that back them
> (`SchemaLifecycleExecutor`, `MigrationClaimStore`, `MigrationMarkStore`, `PublicationStateStore`).
> **Plan:** [`ONE_PLAN_CLOSE_EVERYTHING.md`](ONE_PLAN_CLOSE_EVERYTHING.md) §4.2 ·
> **Steered by:** [`SECURITY_PATTERN_SWEEP_2026-07.md`](../SECURITY_PATTERN_SWEEP_2026-07.md) §4.4

---

## 0. Headline

**No CRITICAL, no HIGH. One MEDIUM filed, two INFO. Zero SQL-injection findings.**

The sweep's headline lead for this round was `JdbcTraceStore` — `SELECT trace_json FROM npdev_trace
WHERE execution_id = ?` with no tenant predicate, plus a `WHERE 1 = 1` filter builder. Both are real
descriptions of the SQL, and **neither is a vulnerability**: §2 shows why, and it is the most useful
result in this document because it is exactly the kind of hit a sweep cannot resolve on its own.

| ID | Finding | Sev | State |
|---|---|---|---|
| **R5-F1** | `PersistenceCapabilityContract` has **no tenant parameter at all** — the flow-step persistence path is tenant-blind, while generated CRUD is tenant-scoped | MED | filed **REG-46** |
| **R5-F2** | Identity-pack identifiers reach auth SQL unvalidated | INFO | recorded |
| **R5-F3** | A non-`tenantScoped` unique constraint is a cross-tenant existence oracle | INFO | recorded |

---

## 1. Is every statement parameterised? — **Yes, and identifiers are safe by construction**

Every **value** in every adapter is bound as a `?` parameter. Not one string-concatenated value was
found. What *is* concatenated is **identifiers** — table and column names — which SQL cannot
parameterise at all, so the only question that matters is whether they can carry attacker input.

They cannot, and there are two independent whitelists doing it:

| Where | Mechanism | Behaviour on a hostile name |
|---|---|---|
| Runtime/generated SQL | `SqlIdentifierSupport.toSnake` | **Coerces**: emits only `isLetterOrDigit` (lowercased) or `_`, so `users; DROP TABLE x --` becomes `users_drop_table_x` |
| Schema/DDL engine (`SchemaLifecycleExecutor`) | `safeIdentifier` / `safeSqlType` | **Throws**: `^[A-Za-z_][A-Za-z0-9_]*$`, else `IllegalStateException` naming the offending identifier |

The DDL engine's 27 concatenation sites — the largest concentration in the repo — were checked
individually. Every one routes through `safeIdentifier`. The three the sweep flagged as *not* prefixed
`safe*` resolve cleanly:

- two are a `System.out.println` warning containing `report.stableStrings()` — a **log line**, not SQL,
  matched because it sits beside SQL-shaped text (a sweep false positive, now allowlisted);
- one is `String.join(", ", columns)` in `executeAddUniqueConstraint`, where every element is either
  the literal `"tenant_id"` or `safeIdentifier(column)`.

`PostgresPersistenceCapabilityAdapter` deserves its own note: its identifiers come from
`resolveColumn`/`resolveIdColumn`/`resolveCriteriaColumn`, which look names up **against the live
database catalog**. An identifier that does not correspond to a real column never reaches a statement.
That is a third valid strategy — validate against reality rather than against a pattern.

> **The answer to "is anything escaped by convention rather than by construction?" is: nothing is
> escaped at all.** Identifiers are whitelisted or rejected; values are bound. That is the right
> architecture, and it is why this round found no injection.

---

## 2. Why the sweep's top lead is not a vulnerability

`JdbcTraceStore.findByExecutionId` really does read by `execution_id` with no tenant predicate, and
`search` really does build `WHERE 1 = 1` with `tenant_id` as an *optional* filter. Read in isolation
both look like cross-tenant reads. Neither is, and the reasons differ:

**`search`** — its only caller, `KernelFacade.searchTraces`, passes the query through
`tenantScopedTraceQuery`, which **rebuilds** the `TraceQuery` with `requester.tenantId()` rather than
merging it. A caller cannot omit the tenant filter or forge it: whatever they send is discarded.
`DefaultExecutionAuthorizationPolicy.canSearchTraces` then independently refuses a query whose
`tenantId` is blank or not the requester's, and every returned row is filtered again through
`canReadTrace`. Three layers, and the store's permissiveness is the innermost one.

**`findByExecutionId`** — the tenant check happens *after* the read, in
`canReadTrace(requester, trace)`, which requires `sameTenant(requester.tenantId(),
trace.meta().tenantId())` and throws `forbidden()` otherwise. The row is fetched before it is
authorized, but it is never returned unauthorized.

One residue is worth recording: a **miss** returns `Optional.empty()` and audits `ALLOW/not_found`,
while a **hit the caller may not read** audits `DENY/forbidden` and throws. Those are distinguishable
by the caller, so an `executionId` oracle exists across tenants. Execution ids are UUIDs, so it is not
practically exploitable — noted, not filed.

**This is the round's methodological point.** A pattern sweep correctly flagged code that reads
cross-tenant; only tracing every caller showed the boundary is enforced one layer up. That is exactly
why the sweep **routes** rather than rates, and why 304 of its 356 hits were handed to a human instead
of being auto-cleared.

---

## 3. R5-F1 (MED, filed as REG-46) — the persistence capability is tenant-blind by contract

```java
public interface PersistenceCapabilityContract {
    Object save(Object entity);
    Object findById(Object concept, Object id);
    Object query(Object concept, Object criteria);
    Object delete(Object concept, Object id);
    Object exists(Object concept, Object field, Object value);
    Object unique(Object concept, Object field, Object value);
}
```

**There is no tenant parameter anywhere in it.** `PostgresPersistenceCapabilityAdapter` emits
`select * from <table> where <id_col> = ?`, `select 1 from <table> where <col> = ? limit 1` and
friends — correct against that contract, and unable to scope by tenant even if it wanted to. The
in-memory adapter is identical in this respect, so this is not a backend asymmetry: it is a gap in the
port itself.

The consequence is **two persistence routes with different isolation guarantees**:

| Route | Isolation |
|---|---|
| Generated CRUD → `ConceptGateway` | tenant-scoped **and** row-scoped (`access.read`/`access.write`) |
| Flow `persistence` capability step → `PostgresPersistenceCapabilityAdapter` | **none** |

The adapter is not exotic: `NpdevPluginConfig` wires it for every app whose `npdev.storage.mode` is
not `in-memory`. A flow step such as `persistence.findById(concept: Order, id: $input.orderId)` — an
entirely ordinary thing for a model author to write — returns **any tenant's** order.

**Why MEDIUM.** Reaching it requires a model that routes caller input into a persistence capability
step, and knowledge of a target id (a UUID). Flow execution itself is gated by `canExecuteFlow`. It is
the absence of a control on an alternate path, not the bypass of one — but the platform's tenant-
isolation story does not hold on that path, and a model author has no way to make it hold.

**Why filed rather than fixed:** the fix is a breaking change to a published port — threading
`ExecutionContext` (or at least a tenant) through `PersistenceCapabilityContract` and both adapters.
That is a contract decision, not a review-round edit. Filed as **REG-46**.

---

## 4. Tenant scoping elsewhere — asked and answered per the plan

> *Is `tenant_id` in the `WHERE` of every read **and** part of the key of every write? Do the
> idempotency / circuit-breaker / bulkhead / claim / mark stores each scope by tenant in the key?*

**Idempotency, circuit-breaker, bulkhead, flow-instance, event, audit, trace — yes.** All are keyed by
`CapabilityOpKey(tenantId, capability, operation)` or an explicit `tenant_id` primary-key column
(`npdev_idempotency`'s PK is `(tenant_id, capability, operation, idempotency_key)`;
`npdev_circuit_breaker`'s is `(tenant_id, capability, operation)`). Round 1 established this and
Round 5 re-confirmed it while editing both stores for REG-36/REG-37.

**Migration claim/mark and publication state — deliberately not, and that is correct.**
`MigrationClaimStore` keys on `claim_key`, `MigrationMarkStore` on `id`, with no tenant column
anywhere. The plan asked whether they scope by tenant "in the key"; the answer is that **they must
not**. What they guard is the physical schema, which is a property of the *database* — every tenant
shares one set of tables — so a tenant predicate would be meaningless, and a per-tenant migration
claim would let two tenants migrate the same schema concurrently. These endpoints are separately
admin-gated.

This is why the sweep's clearing rule B2 explicitly **excluded** these three stores from its
schema-engine exemption: clearing them by rule would have answered Round 5's question by assumption
instead of by reading them.

---

## 5. INFO findings

### 5.1 R5-F2 — identity-pack identifiers reach auth SQL unvalidated

`LoginController`, `PasswordResetController`, `IdentityProvisioning` and
`ControlPanelTenantUsersController` splice `credentialTable`, `credentialPasswordColumn`,
`credentialUserIdColumn`, `userTable` and `usernameColumn` straight into SQL. Every **value** in those
statements is parameterised; only identifiers are spliced — and unlike everywhere else in the
codebase, they go through **neither** whitelist.

They arrive from `@Value("${npdev.auth.login.*}")`, i.e. `application.properties`, written by the
generator from the model's identity pack. So the chain is model JSON → generator → properties → SQL
identifier, and a hostile model author is already inside the trust boundary (they have coda hooks).
**Not a bypass — a missing layer**, and a cheap one: validating these against
`^[A-Za-z_][A-Za-z0-9_]*$` in `StartupValidator` would fail fast at boot instead of producing a broken
or hostile statement at the first login attempt.

### 5.2 R5-F3 — a non-`tenantScoped` unique constraint is a cross-tenant existence oracle

`executeAddUniqueConstraint` includes `tenant_id` in the constraint columns only when
`decl.tenantScoped()`. A unique constraint **without** it is unique across the whole database, so
tenant B cannot create a record whose value tenant A already used — and learns from the 409 that the
value exists somewhere.

This is a declared model choice rather than a defect (a genuinely global unique key is sometimes what
the author means), and `service-base.mustache` already documents that its own pre-check is deliberately
tenant-narrow while a real collision surfaces later as a 409. Recorded so the disclosure is a known
consequence of the declaration rather than a surprise.

---

## 6. Follow-ups filed

| Item | Sev | Note |
|---|---|---|
| **REG-46** — no tenant in `PersistenceCapabilityContract` | MED | Breaking port change; see §3 |
| R5-F2 — validate identity-pack identifiers at boot | INFO | `StartupValidator`, `^[A-Za-z_][A-Za-z0-9_]*$` |
| R5-F3 — document the cross-tenant unique-constraint oracle in authoring docs | INFO | Declared behaviour, not a defect |
