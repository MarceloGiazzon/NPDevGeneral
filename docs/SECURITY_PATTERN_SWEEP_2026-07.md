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

> ### CLOSED 2026-07-25 — the routed hits have been consumed
>
> **The sweep now reports 355 hits, 355 cleared, 0 needing triage.** Rounds 3–6 resolved the routed
> hits; this section originally recorded the routing, and the loop was closed afterwards per
> `archive/programme-history/POST_PROGRAMME_AUDIT_PLAN.md` §2.1. Its own rule — *"when a routed hit is resolved, add its
> fingerprint to the allowlist with the reason"* — had not been executed at the end of the programme,
> leaving 307 permanent "new" hits. **That is the failure mode this document warned about**: at 307,
> nobody reads the output and a real hit hides in it.
>
> **Closing the loop found a new MEDIUM.** The `unbounded-caller-input` group was the one Rounds 3–6
> never systematically covered. Refusing to blanket-clear it surfaced **REG-47**: a caller-supplied
> `correlationId`, only `trim()`ed, written into `TEXT` columns that are btree index key material in
> **8 indexes across 4 tables** — including the primary key of `npdev_correlation_owner`. That is
> REG-36's exact failure mode on a different key. Had those 29 hits been waved through as "same class
> as REG-36, already fixed", it would still be there.
>
> **Count reconciliation (audit finding F3).** The "32" below was the hit count when this document was
> written; two more rules (D1) were added during Round 5, and content-keyed fingerprints mean one
> entry can clear several identical snippets — so 34 entries cleared 48 hits before this closure. The
> allowlist now holds **282 entries covering all 355 hits**. The lesson kept: a count written into
> prose goes stale, so the numbers that matter are the ones the tool prints.

| Verdict | Count (at routing time) | Where it is recorded |
|---|---|---|
| **(i) genuine finding** | 1 | REG-43, filed in the register + fixed this session (§3) |
| **(ii) safe, with the reason recorded** | 32 | `scripts/quality/security-pattern-sweep-allowlist.json`, rules below |
| **(iii) needs deep review** | 304 | routed per surface in §4 — **all consumed**, see §4.0 |
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
| **B3** | REG-46's `findByIdScoped` / `deleteScoped` / `existsScoped` in `PostgresPersistenceCapabilityAdapter` | The tenant predicate **is** applied — the statement is built from TWO literals (`"… where <id> = ? and "` + `tableColumns.columnName(TENANT_COLUMN)` + `" = ?"`) and binds `scope.tenantId()`; the sweep matches only the first literal (its documented second-literal limitation). Where the table has no tenant column the method delegates to the unscoped sibling, which is correct: a table with no tenant column holds no tenant-owned rows to scope. *Triaged 2026-07-25 by the post-implementation audit.* |
| **D2** | The same three REG-46 methods | The only concatenated identifiers are the table name and `resolveIdColumn(…)` / `resolveCriteriaColumn(…)`, which resolve against the live table's **actual** columns — Round 5's "identifiers are safe by construction via two whitelists". Every **value** is a bound parameter. *Triaged 2026-07-25.* |
| **I5** | `scope.tenantId()` bound by the same three methods | The tenant comes from the kernel's authenticated context (`RegistryCapabilityDispatcher` prepends `TenantScope`; it is not author- or caller-writable), and `tenant_id` is length-bounded by its own column definition. *Triaged 2026-07-25.* |
| **B4** | The `NPDevContract/dsl` module's `"select"` literals | **Not a database read.** The DSL module is the model *compiler* — no `DataSource`, no SQL anywhere. Every hit is the string `"select"` as an HTML widget type (`FieldWidgetDefaults.SELECT`, the `<select>` dropdown). *Triaged 2026-07-25 when this module was added to `SCAN_ROOTS`.* |
| **D3** | SQL-shaped English or URLs in the DSL module | Same class as D1. `AutoPanelExpander` builds the **route** `"/select/" + selector.name()`; `ValidationDiagnosticNormalizer` builds the **sentence** *"Update the model so it satisfies …"*. *Triaged 2026-07-25.* |
| **A5** | `NumberFormatException` catches in `ComputedExpression` / `TypeChangeMatrix` | Value coercion, not an authorization verdict — same reasoning as A1, one module over: "is this text numeric?" and "is this type's (precision,scale) parseable?". *Triaged 2026-07-25.* |
| **C1** | `checkCrudPermission` under `{{#referenceFinders}}` | `referenceFinders` is a **list** section, not a boolean: the guard is emitted once per finder, so there is no "else" shape in which a finder exists without its check. |

**B2 deliberately does *not* cover `MigrationMarkStore`, `MigrationClaimStore` or `PublicationStateStore`**,
even though they sit in the same package. ONE_PLAN §4.2 asks specifically whether the claim/mark stores
scope by tenant *in the key*; clearing them here would answer Round 5's question by assumption. They are
routed, not cleared.

### 2.2 2026-07-28 — relocation re-triage after the god-file decomposition (`DSL2_AND_DECOMPOSITION_PLAN.md` 2.B.2-2.B.5)

The allowlist's fingerprint is `sha1(pattern|relative_file_path|normalized_text)` — it includes the
**file path**, by design (editing the code should resurface a hit; the design does not distinguish
that from *moving* the code). Splitting `GeneratedCrudRuntimeSupport.java` (2.B.3) and
`SchemaLifecycleExecutor.java` (2.B.4) into many new files therefore orphaned every existing verdict
on the moved lines and made the sweep report them as brand-new hits — 12 hits from the 2.B.3 split, 25
from 2.B.4's (2.B.2 and 2.B.5 introduced none, by inspection of their diffs). Each one was checked
against the pre-split commit (`git show <pre-split-sha>:<old-path>`) to confirm byte-identical code at
its old location, then re-cleared under the **same rule** its old entry already carried (A1, F3, G1,
H1 for the 2.B.3 files; B2, F1, H2 for the 2.B.4 files) — this is a location update to an existing
verdict, not a fresh judgment call, and no new rule or reasoning was invented. The old,
now-orphaned entries at `GeneratedCrudRuntimeSupport.java`/`SchemaLifecycleExecutor.java` line numbers
were left in place rather than deleted (harmless: they simply never match anything again). Any future
god-file split (2.B.5 already done; any later ones) should expect the same mechanical churn and follow
the same process: confirm byte-identical content at the old location, then re-clear under the
established rule rather than re-deriving one.

**Superseded 2026-07-28 (`docs/POST_PUBLIC_PLAN.md` P3.1) — this is now structurally fixed, not just
documented.** `Hit.fingerprint()` no longer hashes in the relative file path (pattern + normalised
matched text only); moving code to a new file no longer orphans its verdict at all, so the manual
"check byte-identical, re-clear under the same rule" dance above is no longer needed for a pure move.
All 333 existing entries were migrated to the new content-only fingerprint (a script matched every
hit under both the old and new formula by `(file, line, pattern, snippet)` identity, then re-keyed
the allowlist — 275 entries after 31 same-content-different-location merges and 29 genuinely orphaned
entries left untouched). Proven RED-first: temporarily moved `ValueCoercionSupport.java` to a new
directory, confirmed all 10 of its hits still matched cleared (0 needing triage), moved it back. The
sweep now also **reports** (never fails) when a cleared hit's current file isn't mentioned in its
entry's `where` text — a real code move surfaces as an informational note so `where` can be kept
honest, not as a re-triage demand. `path` in each entry stays informational prose describing where a
reviewer actually looked, same as before.

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

### 4.0 What each round resolved (closure record, 2026-07-25)

| Rule | Group | Resolved by |
|---|---|---|
| **E1** | 13 × `{{#kernelControlled}}` guards | **REG-44** — one root cause, not 13 findings (Round 3 §3) |
| **F1–F4, F6** | 100 × SQL identifier splicing | **Round 5 §1** — safe *by construction*: `safeIdentifier` throws, `toSnake` whitelists, the persistence adapter resolves names against the live catalog. Zero injection findings |
| **F5** | 12 × identity-pack identifiers in auth SQL | **Round 5 §5.1 (R5-F2)** — recorded INFO: values parameterised, identifiers come from generator-written properties |
| **G1** | 9 × generated-CRUD tenant-less probes | **Round 3 §4.1/4.2 (R3-F4, R3-F5)** — refused earlier by `enforceBondTargetTenant`, no oracle |
| **G3** | 4 × persistence-adapter tenant-less reads | **REG-46** — the port has no tenant parameter at all |
| **G4** | 2 × `JdbcTraceStore` | **Round 5 §2** — enforced three layers up; the sweep's headline lead was *not* a vulnerability |
| **G5** | schema/claim/mark/publication stores | **Round 5 §4** — deliberately not tenant-scoped: the schema belongs to the database |
| **H1** | 15 × generated-CRUD swallowed exceptions | **Round 3** — data-integrity probes with a database-constraint backstop, not authorization verdicts |
| **H5** | `TenantRegistryService` | **REG-43's own fix** — the flagged `return false` is now the deliberate fail-**closed** branch |
| **I1** | idempotency / circuit stores | **REG-36** |
| **I2** | 29 × correlation-id binds | **REG-47 — NEW, found by this closure** |
| **J1** | `OperationalRunbookEmitter` | False positive: SQL-shaped words inside an emitted PowerShell runbook |

Rules **I3** and **I4** are the only ones cleared purely on reasoning rather than on a round's
findings document: identity/admin writes bounded by their own `VARCHAR` column definitions, and
payload columns that are not index key material. Both state the test that would falsify them — *is
this column part of an index key?* — which is exactly the question REG-47 turned on.

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
