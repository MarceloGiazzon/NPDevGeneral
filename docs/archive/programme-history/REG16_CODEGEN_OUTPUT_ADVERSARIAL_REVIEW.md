# REG-16-resid Round 3 — generator codegen **output**: adversarial review

> **Date:** 2026-07-25 · **Branch:** `beta1-vision-spine` · **Surface:** the code the generator EMITS
> (and the templates that emit it), not the emitter's own internals.
> **Plan:** [`ONE_PLAN_CLOSE_EVERYTHING.md`](../../ONE_PLAN_CLOSE_EVERYTHING.md) §3.1 ·
> **Steered by:** [`SECURITY_PATTERN_SWEEP_2026-07.md`](../../SECURITY_PATTERN_SWEEP_2026-07.md) §4.1

---

## 0. Headline

**One HIGH, one MEDIUM, both fixed this session. One MEDIUM filed. Two INFO.**

> **R3-F2 (HIGH) — every many-to-many bond in every generated app exposed four HTTP endpoints with
> no authorization of any kind.** No coarse permission check, no row-level `access.write` gate, no
> tenant predicate, no audit — on a write surface, in an app whose create/update/delete paths had all
> four. This is LNCH13-F1's class of bug on a surface LNCH13-F1 never covered, and worse in one
> respect: LNCH13-F1 bypassed only the row-level gate, this bypassed the coarse permission check too.

Per ONE_PLAN §0 guardrail 1, the HIGH **stopped this session on the spot**: it was reported,
remediated with a runtime proof, and re-verified before anything else was attempted. Round 6
(export/PDF), Round 3's scheduled partner, was **not** started in this session — see §5.

The scope of these findings is the reason Round 3 mattered: *a flaw here reproduces into every
generated app*, and both defects had been shipping.

| ID | Finding | Sev | State |
|---|---|---|---|
| **R3-F2** | Many-to-many bond endpoints have zero authorization | **HIGH** | **FIXED** + runtime proof |
| **R3-F1** | XSS sinks in the generated business UI; `text()` is not an escaper | MED | **FIXED** + regression test |
| **R3-F3** | `crud.kernelControlled: false` silently disables `access.write` while `access.read` stays enforced | MED | filed **REG-44** |
| **R3-F4** | `existsByAnchor` is not tenant-scoped, though its in-memory twin is | INFO | recorded |
| **R3-F5** | `resourceHasConflict` reads across tenants | INFO | recorded |

---

## 1. R3-F2 (HIGH, FIXED) — many-to-many bond endpoints had no authorization

### What shipped

`controller-custom.mustache` emits four endpoints per many-to-many bond, on every concept that
declares one:

```
GET    /{id}/{bond}                 list members
POST   /{id}/{bond}/{targetAnchor}  add a member
DELETE /{id}/{bond}/{targetAnchor}  remove a member
PUT    /{id}/{bond}                 replace all members
```

Each went straight through to `runtimeSupport.{list,add,remove,replace}BondMember(s)`, whose SQL is:

```sql
INSERT INTO <junction> (<source_col>, <target_col>) VALUES (?, ?)
DELETE FROM <junction> WHERE CAST(<source_col> AS VARCHAR) = ? AND CAST(<target_col> AS VARCHAR) = ?
```

So the complete list of authorization applied to a bond mutation was: **none**.

- no `checkCrudPermission` — not even inside `{{#kernelControlled}}`
- no `enforceWithConceptGateway`, so no row-level `access.write`
- no tenant predicate: the junction table has no `tenant_id` column and the `WHERE` keys on the
  source id alone
- no `auditCrudMutation`, so a tampering event left **no evidence anywhere**

`enforceBondTargetTenant` — the check that closes the cross-tenant hole for *scalar* bonds — does not
apply, and says so explicitly:

```java
if (semantics != null && semantics.isMultiple()) {
    continue; // many-to-many lives in a junction table, not a column on this payload
}
```

### Why HIGH, and why not CRITICAL

**Within a tenant the bypass is unconditional.** A caller who can list their own tenant's records has
the record ids, and can then rewrite the relationships of records they have no write scope over. For
a concept whose `access.write` is `ownerId == $user.id`, that is a complete defeat of the rule on
this surface. No guessing is involved.

**Cross-tenant it needs a UUID guess**, which is not practical. And the blast radius is confined to
junction-table membership rather than arbitrary field writes — hence HIGH rather than CRITICAL.

### The fix

The gate is the **source record's** write authorization, not the junction row's. A junction row has
no owner of its own, and "may I change what this record is linked to" is exactly "may I write this
record". Routing through the gateway supplies the tenant check for free: the record is resolved
within the caller's tenant, so a cross-tenant id fails there without needing a tenant column the
junction table does not have.

That required something that did not exist. `enforceWithConceptGateway` answers "may I write this?"
**by writing it** — useless here, since a bond mutation has no concept row to save. So:

**New `ConceptGateway.authorizeWrite(ConceptReadRequest, ExecutionContext)`** runs exactly the two
gates `save()` runs — `concept.write` permission, then row-level `access.write` against the record's
current state — and stops. It throws the same `ConceptGatewayAccessDeniedException` `save` throws, so
callers need no new error handling.

> **Its default implementation denies.** That is the opposite of the usual default-method convention,
> and it is the right way round here: the failure mode of a permissive default is precisely the bug
> being fixed. A gateway that has not implemented the check must not silently allow.

`service-base.mustache` now emits, per concept with bonds, one shared `enforceBondMembershipWrite(id)`
(coarse permission + `authorizeWrite`) called **before** every junction mutation, one
`requireReadableBondSource(id)` for the list endpoint, and an `auditCrudMutation` after each mutation.

> **A check that can only be performed by causing the side effect is a check people will skip.** That
> is the general lesson here, and it is why the fix adds an API rather than only adding call sites.

### Proof

- **Behavioural** — `RowLevelAuthorizationAttackTest#userBCannotAuthorizeAWriteAgainstUserARow`
  (both adapter families): user B is denied with `ROW_SCOPE_DENIED` against user A's row. Plus
  `authorizeWriteAllowsTheRowsOwnerAndPersistsNothing`, which a deny-everything implementation would
  fail — the owner passes **and** the record is byte-identical afterwards.
- **Structural** — `ServiceBaseBondMembershipAuthzTest` against real generator output: all four
  assertions confirmed **RED** against the pre-fix template, GREEN after. It checks that the gate
  *precedes* the mutation, because a gate that runs after the write is not a gate.

Both halves are needed: a structural test shows the call is emitted, not that it denies; the runtime
test shows it denies, not that generated code calls it.

---

## 2. R3-F1 (MED, FIXED) — XSS sinks in the generated business UI

`business-ui-app.mustache` built every empty/loading/error placeholder as an HTML string:

```js
container.innerHTML = "<div class='empty'>" + message + "</div>";
```

Three of those concatenated `text(error.message)`. **`text()` is not an escaper** — despite a name
that reads exactly like one, it is:

```js
function text(value) { return value === null || value === undefined ? "" : String(value); }
```

`error.message` is composed by the **server**, which echoes request data into it (`"Unknown field for
X: …"`, `"Status transition from 'A' to 'B' is not allowed"`). A fourth site concatenated the user's
raw filter string into a "No matches for …" message.

**Reachable impact today is self-XSS**, and that was checked rather than assumed: the panel filter is
not restorable from the URL (the hash carries only the concept/section id), and the error belongs to
the caller's own request. That is a fact about the current feature set, not about the code — the next
feature that renders a server-authored message here turns it into reflected XSS.

**So the sink was removed rather than escaped.** A new `setEmptyState(container, message)` builds the
element and assigns `textContent`; all 13 placeholder sites now route through it. `text()` carries a
comment saying what it is not.

**Proof:** `BusinessUiEmitterEmptyStateXssTest`, asserted against the **emitted asset** rather than
the template, since that is what ships into every app. Its central assertion is a whitelist —
*`innerHTML` may only be used to clear a container* — which is far more durable than enumerating
unsafe forms. All three tests confirmed RED against the pre-fix template.

---

## 3. R3-F3 (MED, filed as REG-44) — `crud.kernelControlled: false` silently voids `access.write`

The sweep flagged 19 `conditional-guard-no-else` hits, all `{{#kernelControlled}}`, and this is what
they add up to.

With `crud.kernelControlled: false` (an app-level author setting, default `true`):

| Guarantee | Still enforced? |
|---|---|
| `access.read` row-level scope | **yes** — reads go through `conceptGateway.read/list/query`, which is emitted unconditionally |
| Invariants, bond target tenant | **yes** — `enforceWithKernel` / `enforceBondTargetTenant` are unconditional |
| Coarse `concept.*` permission | **no** |
| **`access.write` row-level scope** | **no** — `enforceWithConceptGateway` is inside `{{#kernelControlled}}` |
| Audit of mutations | **no** |

`SemanticValidator` validates that `access.read`/`access.write` parse and are boolean-shaped, but it
does not reference the settings layer at all. So **a model can declare `access.write` and disable the
only thing that enforces it, and compile clean.** A declared security rule that is silently never
enforced is the finding; the read/write asymmetry makes it materially harder to notice, because
spot-checking read scope suggests the rules are live.

A model author disabling the setting is inside the trust boundary — this is not privilege escalation.
It is a **silent security regression** with no diagnostic, and the fix is cheap: reject (or warn on)
the combination at compile time. Filed as **REG-44 (MED)** rather than fixed here, because whether it
should be an error or a warning is a product decision, and ONE_PLAN's triage sends MEDIUMs to a dated
register row.

---

## 4. INFO findings and the negative results

### 4.1 R3-F4 (INFO) — `existsByAnchor` is not tenant-scoped, but its twin is

`GeneratedCrudRuntimeSupport.existsByAnchor` (reference-existence validation) runs
`SELECT 1 FROM <t> WHERE CAST(<col> AS VARCHAR) = :value` with **no tenant predicate**, while the
in-memory fallback `existsByAnchorViaConceptGateway` scopes by `context.tenantId()`. The two backends
disagree, which is usually the signature of a bug.

**It is not exploitable today**, and the reason is ordering: `enforceBondTargetTenant` runs at
`service-base.mustache:188` (create) and `:264` (update), *before* `enforceWithKernel` at `:190`/`:265`
reaches this check — and it rejects a cross-tenant reference with the same `bond_target_not_found`
message it uses for a genuinely absent one, so no oracle is exposed.

Recorded as INFO rather than closed, because **defence by ordering is what REG-41 was**. Scoping the
JDBC query by tenant to match the in-memory path would make the defence local instead of positional.

### 4.2 R3-F5 (INFO) — `resourceHasConflict` scans across tenants

The scheduling-conflict check reads every row whose resource column matches, with no tenant
predicate, so a conflict in another tenant can block a booking. It is defended transitively —
`enforceBondTargetTenant` stops a caller referencing another tenant's resource, so no foreign row
should carry that resource id — but that holds only when the resource field is a *reference*. A model
declaring it as a plain `uuid` gets no such protection. Exploitation still requires guessing a UUID.

### 4.3 The negative results (what was checked and found sound)

These matter as much as the findings — each was an attack question from ONE_PLAN §3.1:

- **Any other `{{#hasX}}/{{^hasX}}` pair with a guard in one arm only?** **No.** The sweep's
  `guard-in-one-branch` pattern — proven against the real pre-fix LNCH13-F1 source — returns zero
  hits across every template. That shape does not recur.
- **Is anything escaped by convention rather than by construction?** For SQL identifiers, **no —
  it is safe by construction.** Every table/column name routes through
  `SqlIdentifierSupport.safeSqlIdentifier` → `toSnake`, which is a **whitelist**: it emits only
  `Character.isLetterOrDigit` (lowercased) or `_`, so quotes, semicolons and parens cannot survive.
  `"users; DROP TABLE x --"` becomes `users_drop_table_x`. That is the right answer to this question.
  *(The identity-pack identifiers reaching auth SQL from `@Value("${npdev.auth.login.*}")` do NOT go
  through it — routed to Round 5, where it is a defence-in-depth gap rather than a bypass.)*
- **Does every generated write route through the gateway?** Yes for authorization. Persistence itself
  then goes directly through `conceptStore` — which is fine, and is exactly the arrangement LNCH13-F1
  established: the gateway is the gate, not the writer. R3-F2 was the one surface that had neither.
- **Does generated read/list/query honour `access.read`?** Yes — `findByIdFromConceptStore`,
  `findAllFromConceptStore` and `page()` all go through `conceptGateway`, unconditionally.
- **Is REG-41's lifecycle-status leak reachable here?** **No.** `validateLifecycleTransition` does
  disclose the previous status in its error message, and reads it with an unscoped query — but it is
  only reachable via `enforceWithKernel`, which on the update path runs *after* `persistence.findById`
  has already gone through the gateway. A caller who reaches the message could already read the row.
  `validateMapRequest` was checked too: it is purely structural (field names, enum values, id match)
  and touches no database.

---

## 5. Session status — Round 6 not started

ONE_PLAN §0 guardrail 1: *"A CRITICAL or HIGH finding STOPS the session immediately. Report,
remediate, re-verify. Do not start the paired surface."*

R3-F2 is HIGH. It was remediated and re-verified (GATE-KERNEL, GATE-H2, GATE-GEN all green), and
**Round 6 (export/PDF) was deliberately not started in this session.** Finishing one surface well and
deferring its partner beats skimming both — guardrail 3 — and this is that case, stated rather than
papered over.

---

## 6. Follow-ups filed

| Item | Sev | Where |
|---|---|---|
| **REG-44** — reject/warn on `access.*` declared with `crud.kernelControlled: false` | MED | register |
| R3-F4 — tenant-scope `existsByAnchor` to match its in-memory twin | INFO | this document |
| R3-F5 — tenant-scope `resourceHasConflict` | INFO | this document |
