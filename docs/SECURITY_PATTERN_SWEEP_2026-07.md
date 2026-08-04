# Security pattern sweep — 2026-07 triage

> **Run:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Tool:** `scripts/quality/security-pattern-sweep.py`
> **Purpose:** the mechanical pass required by [`ONE_PLAN_CLOSE_EVERYTHING.md`](archive/programme-history/ONE_PLAN_CLOSE_EVERYTHING.md) §2.1,
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
| **B2** | Schema/DDL engine reads (`SchemaLifecycleExecutor`, `ImpactReport`, `ProposedConversionSql`, `SchemaDeltaReport`, `SchemaDropSnapshotWriter`, `PendingSchemaAcknowledgmentStore`, `DatabaseIdentityStartupValidator`, `BackfillPass`, `DestructiveRecreationPass`) | The physical schema is a property of the **database**, not of a tenant — all tenants share one set of tables, so a tenant predicate would be meaningless. Separately admin-gated. `BackfillPass`/`DestructiveRecreationPass`'s row-count reads (REG-61, 2026-07-28) are the same class as `SchemaDropSnapshotWriter`'s own `SELECT COUNT(*) FROM <table>` -- boot-time, admin-gated, schema-migration bookkeeping applied uniformly across all tenants, not a tenant-scoped business-data read. |
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

### 2.3 2026-07-30 — Move 7 W1 triaged 3 new hits under the existing B4 rule

Move 7 W1 (`docs/MOVE7_IMPLEMENTATION_SPEC.md`) typed `transaction.actions`/`.visibleWhen`/
`.bandPickers`, adding a second call site for `CompiledSettings.resolveString("action.select")`
(`AutoPanelExpander.java`'s typed `bandPickers()` fallback, mirroring the pre-existing untyped one).
Content-keyed fingerprinting means a literal it had never seen before — `"action.select"` itself,
distinct from the bare `"select"` widget-type literal B4 already covered — read as brand new, plus
two unrelated pre-existing NPDevContract/dsl hits the sweep had apparently never been run against
before (`PlatformStrings.java`'s `"Select"` default label value, `PackValidation.java`'s `mapList`
step validation message naming its own `select` field). All three are the exact class B4 already
describes (`NPDevContract/dsl` has no `DataSource` at all; every hit is prose/a string key, never
SQL) — re-cleared under B4, not a new rule, in `security-pattern-sweep-allowlist.json`
(`608ee00abb10`, `f1deaeca3d08`, `686c826307c2`).

### 2.4 2026-07-31 — Wave 0.1 triaged the 11 schema-engine hits that had accumulated across Moves 9–10 (REG-94)

These 11 had been failing the AI-knowledge gate's step 6 for some time, unnoticed because the gate
itself had a second, unrelated red (see REG-94's other half). Every verdict below was **traced to the
identifier's source**, not inferred from a sibling that had already been cleared — the whole value of
this instrument is that a fingerprint means somebody actually followed the value.

**The `sql-string-building` seven** — `CrossEngineDataPromotion.java:147,148`, `BackfillPass.java:433`,
`ExpressionBackfillPreview.java:88`, `SchemaDropSnapshotRestorer.java:204,281`,
`JdbcBusinessConceptStore.java:98`. All spliced identifiers route through
`SchemaLifecycleExecutor.safeIdentifier()`, which **refuses** anything outside
`[A-Za-z_][A-Za-z0-9_]*` — it throws, it does not strip — so none can carry SQL syntax. Values are
bound with `setObject` everywhere. `CrossEngineDataPromotion` carries a second, independent guard:
its column list is intersected with the live columns of **both** databases before any SQL is built.

`JdbcBusinessConceptStore.java:98` (`findByIdForUpdate`, added by Move 9 A2) is the one that needed
real tracing rather than analogy with its already-cleared siblings at :67 and :119. `shape(conceptName)`
is a lookup into a map built at construction from the compiled model and **throws** for an unknown
concept, so `conceptName` is never spliced. `shape.tableName()` derives via
`SqlIdentifierSupport.toSnake()`, which replaces every non-letter-or-digit with `_` — confirmed
empirically, not by reading: a concept named `Ord"; DROP TABLE users; --` validates with 0 errors and
compiles to a table name with no SQL syntax left in it.

**One `sql-string-building` hit is a false positive**, and it is worth keeping on the record because
the shape will recur: `JdbcBusinessConceptStore.java:110` is not SQL at all. It is a catch block's
message, `"Failed reading (for update) concept " + conceptName + " from JDBC store"` — the
UPDATE-statement pattern matched the English words `(for update)`.

**The `read-without-tenant-predicate` three** — `CrossEngineDataPromotion.java:147`,
`MigrationClaimStore.java:267`, `SchemaDropSnapshotRestorer.java:204`. Each is genuinely
tenant-independent, and in two of the three **adding a tenant predicate would be the bug**:
cross-engine promotion copies every row of every business table, and the drop-snapshot restore
re-inserts every row a destructive migration removed — scoping either to the calling tenant would
silently discard every other tenant's data mid-migration. `npdev_schema_migration_claim` holds
exactly one row on a fixed key and has no tenant column; the migration lock is per-database.

**What the triage turned up that the sweep could not see.** `toSnake()` sanitizes by
*replacement* while `safeIdentifier()` refuses. Replacement means two distinct concept names can
collide onto one physical table — confirmed live: `OrderLine` and `Order Line` both compile to
`order_lines` with **0 validation errors**. That is a data-integrity bug, not an injection one, so it
is filed as **REG-98** rather than cleared here.

### 2.5 2026-08-03 — S7 Phase B triaged 16 new hits from `ConversionHookEmitter`'s declarative-conversion SQL generation, under the existing F2/B2-G6 rules

S7 Phase B added `ConversionHookEmitter.emitDeclared` (NPDevGenerator), which compiles a declared
`conversions[]` entry (`copy`/`split`/`lookup`) to the SAME `db/conversion-hooks/<id>/{hook.json,
convert.sql}` shape `ConversionHookRunner` already executes — see `docs/ACCEPTED_BOUNDARIES.md` B13.
The sweep found 16 new hits, all in that one method, all cleared under rules already established
elsewhere in this document rather than inventing new ones:

**13 `sql-string-building` hits** (fingerprints `6145981d8419`, `aaeb9131a3f1`, `83d0b3b988f5`,
`814d307bb65e`, `75a50a3f7183`, `995acdd8b42f`, `9b89a663f664`, `4f4350ae354c`, `f27e9a162c28`,
`c3ec577da23c`, `ff61950d9e27`, `71cf63d95978`, `abba6d858f8d`) — the `ALTER TABLE … ADD COLUMN`/
`UPDATE … SET`/`ALTER COLUMN … SET NOT NULL` statements each op builds. Cleared under **F2**, the
same rule already covering `SchemaDeltaReport.java:218`/`SchemaDropSnapshotWriter.java:106` (allowlist
fingerprints `8521985ae7fe`/`f2e73ed3bcba`): a generation-time emitter that writes SQL TEXT into a
file and executes nothing itself — `ConversionHookRunner` executes it later, at boot, an
already-reviewed trust boundary identical to an operator-authored `convert.sql`. Every table/column
identifier is traced to its source, not assumed by analogy: `table` comes from
`SqlIdentifierSupport.tableName(CompiledConcept)`, every column from
`SqlIdentifierSupport.columnName(CompiledField)` — both route through `toSnake()`'s `[a-z0-9_]`-only
whitelist, the same mechanism `docs/SECURITY_PATTERN_SWEEP_2026-07.md` §2.4 already traced empirically
(a hostile concept name compiles to a clean identifier, no SQL syntax survives). `portableSqlType(...)`
comes from `SqlTypeSupport.sqlType(CompiledField)`, a closed `switch` over the DSL's own type enum
(`uuid`/`integer`/`string`/…), never free text.

**3 `read-without-tenant-predicate` hits** (fingerprints `01ce4dd9c3d6`, `707358791f42`,
`996fee296fe4`) — the lookup op's correlated-subquery `UPDATE`/`SELECT` and the generated hook's own
`verifySql`. Cleared under **B2/G6**, the same rule already covering `BackfillPass.java`/
`CrossEngineDataPromotion.java`/`DestructiveRecreationPass.java` (fingerprint `4014f82e9bb8`): a
schema migration is a property of the DATABASE, not of one tenant, so `ConversionHookRunner` (like
`BackfillPass` one package over) applies its `UPDATE` uniformly across every tenant's rows in the
table by design — a tenant predicate here would be the bug, not its absence.

All 16 recorded in `scripts/quality/security-pattern-sweep-allowlist.json`.

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
| **H6** | `IdentityPermissionOverrideLookup.overridesFor` (Move 14 Phase C, RC-B3) | **New, 2026-08-02** — same class as H4 one module over (`IdentityRoleLookup.rolesFor`): a swallowed `SQLException` fails open to "no runtime override configured", so the role's full declared ceiling applies. It DOES log at `LOG.severe(...)` on a schema mismatch (mirrors `IdentityRoleLookup.tokenVersion`'s own style exactly) — the checker's regex just doesn't recognize `.severe(`, only `.error(`/`.warn(`. The fail-open direction can only ever skip an optional *narrowing*; `RolePermissions`'s own ceiling intersection is the actual, separately-tested enforcement of "never beyond the ceiling". |
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

### 4.5 → 2026-08-01, Move 10 B1 (new code, not a prior-round finding)

| Lead | Hits | Resolution |
|---|---|---|
| `ConceptAggregateEngine.toLocalDate` catches `DateTimeParseException` and returns `null` | 1 | Rule **H2** (general swallowed-exception, non-auth). Not a security verdict of any kind: this is a data-coercion helper turning a raw `groupBy` field value into a `LocalDate` for date bucketing (`day`/`week`/`month`/`quarter`/`year`). An unparseable value legitimately becomes "no bucket" (a `null` group key, grouped alongside any other un-parseable rows) rather than crashing the whole aggregate query for every caller over one bad row — the exact same fail-soft posture `ConceptQueryEngine.asNumber` already uses for a non-numeric comparison value. No permission, tenant-scope, or auth decision passes through this method at all.

**Also this same session:** a botched `git checkout --` on `security-pattern-sweep-allowlist.json` (recovering from an unrelated bad edit) discarded 13 uncommitted "safe" verdicts that predated this session — `CrossEngineDataPromotion.java` (rule A3 + F1, 3 fingerprints), `SchemaDropSnapshotRestorer.java` (rule G5 + F1, 2 fingerprints), `MigrationClaimStore.java:267` (rule G5), `JdbcBusinessConceptStore.java:101/113` (rule F1 + one false-positive), `BackfillPass.java:433` (rule F1), `ExpressionBackfillPreview.java:88` (rule F1), and 3 false-positives on the English word "select"/"Select" appearing in non-SQL text (`PlatformStrings.java:28`, `AutoPanelExpander.java:643,678`, `PackValidation.java:463` — same class as rule J1's runbook false positive). All 13 were fully reconstructed from the gate's own next-run diagnostic output (which prints the matched text, file:line, and category for every "new" hit) cross-referenced against this document's own closure-record rules (§4.0) — none were re-triaged from scratch; each maps cleanly onto an already-established rule. Re-added with `where`/`why` noting the 2026-08-01 re-add. See the maintainer memory `feedback_git_checkout_uncommitted_schema_risk` for the process lesson (this is the second such incident in the same session).

### 4.6 → 2026-08-02, Move 15 Phase A item A1 (REG-120)

| Lead | Hits | Resolution |
|---|---|---|
| `service-base.mustache:209` — `authorizeCreateFlowWithConceptGateway`/`enforceWithConceptGateway` under `{{#kernelControlled}}` with no opposite arm | 1 | Rule **E1** (`REG-44`'s already-closed class): one more `{{#kernelControlled}}`-guarded site in a file that already had 13 of them, all resolved by the SAME root cause — `crud.kernelControlled` is a single app-level off-switch, and `UnenforceableAccessRuleCheck` (REG-44's fix) already refuses generation outright if a concept declares `access.read`/`access.write` while it resolves false. Not a new question. |
| `service-base.mustache:664` — `conceptGateway.authorizeCreate(...)` nested inside `authorizeCreateFlowWithConceptGateway`'s `{{#hasCreateFlow}}`-guarded body | 1 | New sub-case of the `guard-in-one-branch` tautology exemption (§1.1): the OUTER call (`authorizeCreateFlowWithConceptGateway`) now carries "flow" in its name and is correctly excused by the existing discriminator, but the kernel-level `ConceptGateway.authorizeCreate` it calls internally does not (and should not — it is a general-purpose primitive, not flow-specific, mirroring the pre-existing `authorizeWrite`). Not LNCH13-F1's shape: the `{{^hasCreateFlow}}` arm doesn't need this call because it uses the PERSISTING `enforceWithConceptGateway` instead, a strictly broader guarantee (permission + row-scope + rule-profile enforcement, plus the actual write), not a missing one. `ServiceBaseFlowRowLevelAuthzTest` is the automated proof that the enforcement itself still runs before the flow in both branches. |
| `ServiceEmitter.java:226` — a generation-time `System.out.println` diagnostic containing the word "create" | 1 | Rule **D3** (SQL-shaped English in non-SQL text). No `DataSource`/JDBC/database interaction anywhere near this line — `flow.getName()` is spliced into a console log message only. |
| `ConceptGateway.java:92` — the new `authorizeCreate` default method's fail-closed exception message | 1 | Rule **D3**, same class as the row above: mirrors the pre-existing `authorizeWrite` method's identical-shape message one method above it (never flagged, since "authorize a WRITE" contains no SQL keyword) — a plain Java exception string. |

All four are the direct, mechanical fallout of REG-120's fix (a new kernel method + a new template call site + a new generation-time log line); none represent a new security question. See `ledger/items/REG-120.yml` for the fix itself.

### 4.7 → 2026-08-03, S5 (element-granularity authoring merge)

| Lead | Hits | Resolution |
|---|---|---|
| `AuthoringMergeGate.java:100` — a whole-document-conflict violation message | 1 | Rule **false-positive**, the same class as `JdbcBusinessConceptStore.java:113`'s "(for update)" hit (§2.4): not SQL at all. The matched text is `"... disjoint-element merge cannot be attempted (H1: never guess). Reasons: " + String.join("; ", reasons)` — a human-readable diagnostic naming which elements collided. The sweep's `sql-string-building` regex fired on the English word "merge" (from the class name `AuthoringMergeGate` and the message prose "element merge") sitting near a `String.join` concatenation; there is no `DataSource`/JDBC/SQL anywhere in this class, which does an in-memory JSON element merge (`__OutsideRepo\s5\S5_SPEC.md`). |

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
