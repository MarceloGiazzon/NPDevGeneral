# Security pattern sweep — 2026-07 triage

> **Run:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Tool:** `scripts/quality/security-pattern-sweep.py`
> **Purpose:** the mechanical pass required by [`ONE_PLAN_CLOSE_EVERYTHING.md`](ONE_PLAN_CLOSE_EVERYTHING.md) §2.1,
> run once across all four unreviewed surfaces. **This document is the input to sessions 2 and 3** —
> its job is to say *where the deep review should concentrate*, not to replace it.

---

## 0. The headline result

**356 hits. 32 cleared with a recorded reason. 1 genuine new finding (REG-43, MED). 304 routed to the
session that owns the surface.**

The single most important number is the first pattern's:

> **`guard-in-one-branch` — the LNCH13-F1 shape — returns ZERO hits across every template.**

That is the highest-value thing the sweep could have told us, and it is a real result rather than an
absence of effort: the pattern is proven against the actual pre-fix LNCH13-F1 source (see §1.1). The
CRITICAL found in Round 2 does **not** recur anywhere else in the generator.

The sweep also independently re-derived **REG-36** from scratch — it flagged
`JdbcIdempotencyStore` binding `idempotencyKey` with no length bound while the paired value *is*
bounded. An already-known finding being rediscovered by an independent mechanism is the cheapest
available evidence that the mechanism works.

---

## 1. Why a mechanical pass is trustworthy here (and where it is not)

### 1.1 The sweep proves it catches the bugs it claims to

A pattern sweep that finds 356 things but would have walked past LNCH13-F1 is worse than useless: it
manufactures confidence. So `--self-test` runs each pattern against **the real historical shape of a
bug this repo shipped, and against that bug's fix**, and requires it to separate them:

```
$ python scripts/quality/security-pattern-sweep.py --self-test
  PASS  guard-in-one-branch flags: LNCH13-F1 as it actually shipped
  PASS  guard-in-one-branch stays quiet on: LNCH13-F1 after the fix
  PASS  swallowed-security-exception flags: REG-39's shape
  PASS  swallowed-security-exception stays quiet on: the same catch, but it re-raises
  PASS  sql-string-building flags: an identifier concatenated into SQL
  PASS  sql-string-building stays quiet on: the same statement, fully parameterised
  PASS  read-without-tenant-predicate flags: a cross-tenant read
  PASS  read-without-tenant-predicate stays quiet on: the same read, scoped
  PASS  unbounded-caller-input flags: REG-36's shape
  PASS  unbounded-caller-input stays quiet on: the same write, after REG-36's fix
  10/10 fixtures behaved as documented
```

This is wired into **GATE-AI** next to the register self-check. Both answer the same question — *is
this quality mechanism still doing what its documentation claims?*

**The self-test earned its keep on the first run.** It failed 1/10 initially: the naive pattern flagged
`{{#hasCreateFlow}} enforceWithCreateFlow(...) {{/hasCreateFlow}}` as a missing guard. It is not one —
when there is no create flow there is no flow to enforce, so the opposite arm *cannot* carry that call.
The fix (`tautological()`) is the discriminator that matters: **does the condition's own subject appear
in the guard's name?** `hasCreateFlow` → `enforceWithCreateFlow` is a tautology; `hasCreateFlow` →
`enforceWithConceptGateway` is a gap, and that gap was LNCH13-F1. Without the fixture the sweep would
have shipped a pattern that cried wolf on every flow branch and would have been muted within a week.

### 1.2 What it cannot do

It matches shapes. It cannot tell whether an identifier spliced into DDL is reachable from
attacker-controlled input, whether a tenant-less read is genuinely tenant-independent, or whether a
resumed flow carries the right actor. **That is why 304 of 356 hits are routed to a human review rather
than cleared** — routing, not absolution, is the deliverable.

---

## 2. Triage verdicts

Every hit has one of three verdicts, per ONE_PLAN §2.1.

| Verdict | Count | Where it is recorded |
|---|---|---|
| **(i) genuine finding** | 1 | REG-43, filed in the register + fixed this session (§3) |
| **(ii) safe, with the reason recorded** | 32 | `scripts/quality/security-pattern-sweep-allowlist.json`, rules below |
| **(iii) needs deep review** | 304 | routed per surface in §4 |
| unchanged | 19 | `conditional-guard-no-else` on `{{#kernelControlled}}` — escalated as one question (§4.1) |

Verdicts are keyed by a **fingerprint of the matched text**, not its line number, so a verdict survives
reformatting — but is invalidated the moment the judged code itself changes. That is deliberate: what
was cleared was the code as it read, and if it no longer reads that way it has not been cleared.

### 2.1 The "safe" rules, in full

| Rule | Applies to | Why it is safe |
|---|---|---|
| **A1** | `catch (NumberFormatException \| DateTimeParseException \| IllegalArgumentException \| InvalidPathException)` returning null/false/empty | Value-**coercion** helpers, not authorization verdicts. The catch converts "this text is not a number/date/path" into "absent", and every caller treats absent as not-supplied. No security question is being answered. |
| **A2** | `IdentityRoleLookup.isTokenRevoked` / `rejectTvlessTokensNow` | These *are* security verdicts and both fail **open** — so they were checked individually, not by class. A malformed `tv` claim cannot be attacker-produced: the claim sits inside a JWT **the server itself signed**, so reaching the catch means the server emitted a non-numeric `tv`. Editing the token to change `tv` breaks the signature. The cutover system property is separately validated at boot by `StartupValidator`, so a malformed value never reaches the parse. |
| **A3** | `ConfiguredConceptGatewaySemanticPolicy.evaluateAccessRule` | Documented fail-**closed**: a row-level access rule that will not evaluate denies. Correct direction, and the reasoning is already in-line at the catch. |
| **A4** | `PasswordHasher.verify` | Fail-closed: a hash that cannot be verified is not a match. No other outcome is reachable. |
| **B1** | `SELECT 1` liveness probes, `pg_try_advisory_lock` | Reads no tenant rows at all, so there is no tenant scope to apply. |
| **B2** | Schema/DDL engine reads (`SchemaLifecycleExecutor`, `ImpactReport`, `ProposedConversionSql`, `SchemaDeltaReport`, `SchemaDropSnapshotWriter`, `PendingSchemaAcknowledgmentStore`, `DatabaseIdentityStartupValidator`) | The physical schema is a property of the **database**, not of a tenant — all tenants share one set of tables, so a tenant predicate would be meaningless. Separately admin-gated. |
| **C1** | `checkCrudPermission` under `{{#referenceFinders}}` | `referenceFinders` is a **list** section, not a boolean: the guard is emitted once per finder, so there is no "else" shape in which a finder exists without its check. |

**B2 deliberately does *not* cover `MigrationMarkStore`, `MigrationClaimStore` or `PublicationStateStore`**,
even though they sit in the same package. ONE_PLAN §4.2 asks specifically whether the claim/mark stores
scope by tenant *in the key*; clearing them here would answer Round 5's question by assumption. They are
routed, not cleared.

---

## 3. (i) The one genuine finding — REG-43

### REG-43 — `TenantRegistryService.isActive` fails **open**, silently, on any SQL error · **MED**

`TenantStatusFilter` is the single per-request chokepoint that, in its own words, "gives tenant *disable*
real teeth". It calls `TenantRegistryService.isActive(tenantId)`, which ends:

```java
} catch (SQLException exception) {
    return true;          // active
}
```

No log at any level. So if the registry query fails for **any** reason once a `DataSource` exists — the
table was dropped, a migration is mid-flight, the pool is exhausted, the column was renamed — every
**explicitly DISABLED** tenant silently regains full access, and nothing anywhere reports it. The
security control has an undetectable off-switch.

This is REG-39's class in its worse direction: REG-39 swallowed a fault into a security *negative*
(annoying); this swallows one into a security *positive* (a bypass).

**Why MED and not HIGH.** It needs an operator to have disabled a tenant *and* a database fault on that
specific query. An attacker cannot trigger it directly, and a disabled tenant still needs valid signed
credentials to exploit the window. It is a real control degradation, not a reachable bypass.

**The fix, and why it is not simply "fail closed".** Blanket fail-closed would brick every app that
legitimately has no `npdev_tenant` table — which is exactly the trap REG-39 fell into from the other
side. So the two cases are separated:

- **The registry table does not exist** (SQLState `42S02` H2 / `42P01` Postgres) → this app has no tenant
  registry. Fail **open**, log once at INFO. Unchanged behaviour, now explained.
- **Any other SQL error** → the registry exists and we could not read it. Fail **closed**, log at ERROR.
  This costs no availability that is not already lost: if the database is failing, the request's own data
  queries are failing too. It costs a 403 instead of a 500, and it keeps the control intact.

Fixed in this session with a runtime test (§5).

---

## 4. (iii) Routing — what each remaining session must actually look at

This is the section sessions 2 and 3 exist to consume. Counts are hits, not findings.

### 4.1 → SESSION 2, Round 3 (generator codegen output)

| Lead | Hits | The question to answer |
|---|---|---|
| **`crud.kernelControlled` is a single app-level off-switch for ALL generated authz** | 19 | Every `checkCrudPermission`, `enforceWithConceptGateway` and `auditCrudMutation` in `service-base.mustache` sits inside `{{#kernelControlled}}` with **no opposite arm**. The setting defaults to `true`, and a model author disabling it is within the trust boundary — **but does anything warn when a concept declares `access.read`/`access.write` and `crud.kernelControlled` is false?** A declared security rule that is silently never enforced is the finding shape. `SemanticValidator` appears to have no such check — confirm. |
| `GeneratedCrudRuntimeSupport` uniqueness / reference checks with no tenant predicate | 9 | `SELECT 1 FROM <t> WHERE <col> = :value` at lines ~4269 / 4375 / 4497 / 5008 / 5013. A uniqueness probe that ignores `tenant_id` both **leaks existence across tenants** and breaks isolation (tenant B cannot create a row whose unique value tenant A used). Highest-consequence lead in Round 3. |
| `GeneratedCrudRuntimeSupport` swallowed exceptions | 15 | Largest single concentration of the REG-39 class outside auth. |
| `GeneratedCrudRuntimeSupport` SQL assembly | 12 | Concept/field identifiers spliced into runtime SQL — trace each to whether a model author or a *caller* chooses it. |
| `BusinessUiEmitter` tenant-less reads | 7 | Emitted UI query code. Does generated read/list honour `access.read`? |
| `SchemaRealizationEmitter`, `PlanItem`, `OperationalRunbookEmitter`, `DockerDeploymentEmitter` | 29 | These emit SQL **as text into generated artifacts**. Not executed by the generator — but reproduced into every app. |

### 4.2 → SESSION 2, Round 6 (export/PDF)

The sweep has little to say here — the export path barely touches SQL, which is itself informative:
Round 6's risks (SSRF, traversal, exhaustion, scope-blind export) are **not pattern-matchable** and get
no shortcut. Two concrete starting points it did surface:

- `ConceptQueryController.java:145` — `concept.replaceAll("[^A-Za-z0-9_-]", "_")` building an export
  filename. Someone thought about traversal; verify it covers the whole path, not just this segment.
- `DocumentRenderStubAdapter` — confirm which render provider is the default, and whether the real one
  fetches remote resources.

### 4.3 → SESSION 3, Round 4 (flow / `await` orchestration)

| Lead | Hits | The question |
|---|---|---|
| `JdbcFlowInstanceStore` unbounded caller-influenced binds | 11 | Suspended-flow state is durable and caller-influenced. Which of these is the resume token? |
| `MigrationClaimStore` / `MigrationMarkStore` / `PublicationStateStore` | 6 | Deliberately **not** cleared by rule B2 — Round 5 asks whether these scope by tenant in the key. |

### 4.4 → SESSION 3, Round 5 (Postgres adapter SQL)

| Lead | Hits | The question |
|---|---|---|
| **`JdbcTraceStore` reads with no tenant predicate** | 2 + 6 | `SELECT trace_json FROM npdev_trace WHERE execution_id = ?` and a `WHERE 1 = 1` filter builder. A trace carries execution payloads. If a caller supplies the `execution_id`, this is a **cross-tenant read**. Highest-consequence lead in Round 5 — check first. |
| `SchemaLifecycleExecutor` identifier splicing | 27 | Largest concentration in the repo. All DDL, where identifiers genuinely cannot be bound as parameters — so the real question is whether **every** splice routes through the `safe*` quoting helper and whether that helper is sound. |
| Identity-pack identifiers spliced into auth SQL | 12 | `credentialTable` / `usernameColumn` / `userTable` reach `LoginController`, `PasswordResetController`, `IdentityProvisioning`, `ControlPanelTenantUsersController` from `@Value("${npdev.auth.login.*}")`. All **values** are parameterised; only identifiers are spliced. The chain is model JSON → generator → `application.properties` → SQL identifier, so a hostile model author is already inside the trust boundary (they have coda hooks). **Defence-in-depth gap, not a bypass:** nothing validates these against `^[A-Za-z_][A-Za-z0-9_]*$` at boot. Cheap `StartupValidator` hardening — rate in Round 5. |
| `PostgresPersistenceCapabilityAdapter` | 8 | `select * from ? where ? = ?` with no tenant predicate, in the generic persistence capability. |
| `JdbcIdempotencyStore` / `JdbcCircuitBreakerStateStore` / `JdbcEventStore` / `JdbcAuditLogStore` | 28 | Unbounded binds. REG-36 covers the idempotency key specifically; check whether the same asymmetry exists in the others. |

---

## 5. Reproducing this

```bash
python scripts/quality/security-pattern-sweep.py --self-test     # prove the patterns still work
python scripts/quality/security-pattern-sweep.py                 # un-triaged hits only
python scripts/quality/security-pattern-sweep.py --all           # everything, incl. cleared
python scripts/quality/security-pattern-sweep.py --pattern guard-in-one-branch
```

The sweep **reports; it does not fail a build** (`--fail-on-new` is opt-in). A heuristic gate that blocks
CI on regex noise gets bypassed within a week, and then it protects nothing — the same trade
`check-register-consistency.py` documents.

**When a routed hit is resolved**, add its fingerprint to the allowlist with the reason, and say here
which rule or which review resolved it. An allowlist entry with no reason is worse than no entry.
