# REG-16-resid Round 2 — Adversarial review of LNCH-13 row-level authorization

> **Status:** Round 2 COMPLETE — 2026-07-25. Independent, attack-first review of LNCH-13's
> declarative row-level (data-scoped) authorization: `ConceptGatewaySemanticPolicy`,
> `ConfiguredConceptGatewaySemanticPolicy`, `DefaultConceptGateway`, and the generated
> `{Concept}ServiceBase` (`service-base.mustache`) surface every generated app's REST controllers
> actually call. Per `docs/FINAL_FOUR_CLOSURE_PLAN.md` §1, Round 2 of REG-16-resid — the
> highest-consequence unreviewed surface named for this position ("an authz flaw is a direct
> data-exposure path").
>
> **Headline: one CRITICAL finding, remediated this round.** A concept declaring a custom
> create/update/delete Flow got **zero row-level `access.write` enforcement** on its generated REST
> endpoint — a complete bypass of LNCH-13's write-scoping guarantee for that (realistic, common)
> configuration. Fixed in `service-base.mustache`; RED-first proven against the real generator
> pipeline; full `GATE-GEN` regression suite green. Two further **MEDIUM** findings (an
> authorization-ordering info leak, and a row-scope-unaware pagination count) are filed as dated
> register items per the triage rule, not fixed in this pass. One INFO item recorded for the record.

---

## R0 — Scope actually read

**Core LNCH-13 mechanism** (`NPDevKernel/kernel/src/main/java/com/npdev/kernel/concepts/`):
`ConceptGatewaySemanticPolicy` (the extension-point interface — `isRowReadable`/`isRowWritable`),
`ConfiguredConceptGatewaySemanticPolicy` (the generic, data-driven implementation every generated app
wires up — `evaluateAccessRule`, `isRowReadable`, `isRowWritable`, plus its `normalizeAndValidate`/
`applyDefaultsAndDerivedValues`/`validateLifecycleTransition`/`evaluateRuleProfiles` neighbors, read
in full since F2 below traces through them), `DefaultConceptGateway` (539 lines, read in full —
`read`/`list`/`query`/`save`/`delete`, `enforceTenant`, `enforcePermission`, `enforceRowWritable`,
`runWriteSemantics`).

**The generated CRUD surface that calls it** (`NPDevGenerator/generator/src/main/resources/npdev-templates/service-base.mustache`,
622 lines, read in full): `createFromSource`/`updateFromSource`/`delete`, `enforceWithConceptGateway`/
`enforceDeleteWithConceptGateway`, `enforceWithCreateFlow`/`enforceWithUpdateFlow`/
`enforceWithDeleteFlow`, `findByIdFromConceptStore`/`findAllFromConceptStore`/`saveToConceptStore`/
`deleteFromConceptStore`, and how `ServiceEmitter.java` (`hasCreateFlow`/`hasUpdateFlow`/
`hasDeleteFlow`/`kernelControlled` context wiring, lines ~180-224) decides which branches a given
concept gets.

**Existing test coverage traced for its actual reach:** `RowLevelAuthorizationAttackTest`
(`NPDevRuntimeHost/src/test/java/com/finalexec/security/`) — confirmed it constructs
`DefaultConceptGateway` **directly** (never through generated `ServiceBase` code), which is exactly
why F1 below went undetected: it is the write-side twin of a gap `docs/ROW_LEVEL_AUTHORIZATION.md`
already documents for reads ("a first implementation left `{Concept}ServiceBase`'s
`findByIdFromConceptStore`/`findAllFromConceptStore` reading straight from `ConceptStore`... only
caught by live end-to-end verification, not by the hermetic gateway-level attack-suite tests").

**Not read this round** (named so they aren't mistaken for "reviewed clean"): the many-to-many bond
member endpoints (`listBondMembers`/`addBondMember`/etc. — no `access` interaction visible in
`service-base.mustache` but not traced into `GeneratedCrudRuntimeSupport`'s implementation);
`ComputedExpression`'s own evaluator internals (out of scope — it's a general-purpose expression
engine used everywhere, not LNCH-13-specific; its correctness for *this* feature is bounded by the
fact that `access` expressions are model-author-declared and `SemanticValidator`-checked at compile
time, not attacker-influenced at runtime); `ExecutionContext` construction / how `$user.id`/
`$user.tenantId`/`$user.roles` ultimately get populated from a JWT or API key (that is REG-16 Tier
A's LNCH-2/LNCH-4 scope, already reviewed 2026-07-21 — this round assumes, does not re-verify, that
`ExecutionContext` cannot be forged by request data).

---

## What is solid (recorded so the review is honest about the baseline)

- **The `$user.*` pseudo-variables are not attacker-controlled.** `evaluateAccessRule` sources them
  from `ExecutionContext` (`effectiveContext.actorId()`/`tenantId()`/`roles()`), never from the
  record data or request payload — and they're merged into the same scope map as the record's own
  fields only after the record data copy, so a field literally named `$user.id` could shadow it, but
  field names are restricted to `[A-Za-z_][A-Za-z0-9_]*` at the schema level, which can never produce
  a `$`-prefixed name. No injection path.
- **Fail-closed on malformed expressions**, not fail-open: a `ComputedExpression.ExpressionException`
  from `evaluateAccessRule` returns `false` (deny), with a comment correctly noting `SemanticValidator`
  already rejects this at compile time, so reaching it at runtime means something bypassed validation.
- **A denied read is worded like "not found," not "forbidden"** (`read`/`list`/`query` in
  `DefaultConceptGateway` all filter through `isRowReadable` before returning), consistent with the
  documented intent of never confirming a row exists outside the caller's scope.
- **The non-flow write path is correct and tested.** For a concept with no declared create/update/
  delete Flow, `enforceWithConceptGateway`/`enforceDeleteWithConceptGateway` route straight through
  `ConceptGateway.save`/`delete`, which calls `enforceRowWritable` before persisting.
  `RowLevelAuthorizationAttackTest` proves this mechanism itself (read/list/query/update/create/
  delete cross-user denial) against both the InMemory and JDBC/H2 adapters.
- **Tenant isolation is a genuinely separate, always-on mechanism**, unaffected by anything below:
  `enforceTenant` runs before any row-level or permission check in every `DefaultConceptGateway`
  method, and row-scope rules cannot widen it (confirmed by reading — `access.read`/`access.write`
  only ever narrow the already-tenant-scoped `store.findById`/`findAll`/`query` result).

---

## R1 — Findings → severity map

| ID | Sev | Area | One-line |
|---|---|---|---|
| LNCH13-F1 | **CRITICAL** | write authz bypass | A concept with a custom create/update/delete Flow got zero row-level `access.write` enforcement on its generated REST endpoint — **fixed this round** |
| LNCH13-F2 | MEDIUM | authz ordering / info leak | `DefaultConceptGateway.save()` runs lifecycle/invariant validation against the previous record BEFORE the coarse permission and row-level write checks, letting an otherwise-unauthorized caller learn a row's current lifecycle-status value via the resulting error |
| LNCH13-F3 | MEDIUM | pagination info leak | `ConceptGateway.query()`'s `total`/`hasMore` are computed before row-scope filtering, leaking the aggregate count of rows outside the caller's `access.read` scope (a security re-read of an already-documented "known limitation") |
| LNCH13-F4 | INFO | concurrency | Row-level write authorization is check-then-act (`findById` snapshot, then a later `save`/`delete`), not atomic against a concurrent ownership change — narrow, consistent with the platform's general (non-serializable) concurrency model elsewhere |

---

### LNCH13-F1 — Flow-backed create/update/delete bypasses row-level write authorization entirely · CRITICAL (FIXED)

**The mechanism.** `service-base.mustache`'s `createFromSource`/`updateFromSource`/`delete` branch on
whether the concept declares a custom Flow for that mode (`ServiceEmitter`'s `model.findFlow(concept,
"create"|"update"|"delete")`, wired as `hasCreateFlow`/`hasUpdateFlow`/`hasDeleteFlow`):

- **No Flow declared:** `enforceWithConceptGateway(conceptName, id, payload)` — calls
  `conceptGateway.save(...)`, which internally calls `enforceRowWritable` (LNCH-13's own check)
  before persisting. Correct.
- **Flow declared:** `enforceWithCreateFlow(...)` instead — calls **`kernelRunner.execute(flowName,
  ...)`** only. The flow's own `capabilityCall` steps (typically `persistence.save`/`saveConcept`)
  are bound, per the `ServiceBase` constructor, straight to `saveToConceptStore` →
  `conceptStore.save(new ConceptRecord(...))` — the raw store, with **no call to `ConceptGateway` or
  `ConceptGatewaySemanticPolicy.isRowWritable` anywhere in that path**. The outer
  `createFromSource`/`updateFromSource` methods then *also* call `saveWithIntegrityMapping(e)` (→
  `persistence.save(entity)` → the same raw `saveToConceptStore`) unconditionally afterward,
  regardless of whether a Flow ran — so even the SECOND, final persist bypasses the gateway.

**Attack.** Model: a `Ticket` concept with `access.write: "ownerId == $user.id"` and a custom
`CreateTicket`/`UpdateTicket` Flow (e.g. one that also sends a notification — an entirely ordinary,
encouraged authoring pattern: business-rule-rich concepts are exactly the ones likely to want both a
row-scope rule and custom side effects). Actor B, who has baseline `concept.write` permission on
`Ticket` (a coarse, all-or-nothing grant — most apps grant this per-role, not per-concept-instance)
but does **not** own a specific Ticket row belonging to Actor A, calls `PUT /api/tickets/{A's id}`.
Coarse permission passes (B can write *some* Ticket). Row-level scope is never checked. The Flow
runs, its own persistence step (or the outer `saveWithIntegrityMapping`) writes B's payload over A's
row. **LNCH-13's entire "only the owner may write this row" guarantee is void for this concept.**

**Why existing tests missed it.** `RowLevelAuthorizationAttackTest` never exercises generated
`ServiceBase` code (see R0) — the same blind spot the read-side twin of this bug had, per
`docs/ROW_LEVEL_AUTHORIZATION.md`'s own history, until caught by live E2E testing. No generator-level
test previously existed for the `hasCreateFlow`/`hasUpdateFlow`/`hasDeleteFlow` branches at all
(confirmed: zero hits for `enforceWithCreateFlow`/`enforceWithConceptGateway` across
`NPDevGenerator/generator/src/test`).

**Fix.** `service-base.mustache`: `enforceWithConceptGateway`/`enforceDeleteWithConceptGateway` now
run **unconditionally** whenever `kernelControlled` (i.e. always, in every app that has a
`ConceptGateway` at all), **before** the corresponding `enforceWithCreateFlow`/`enforceWithUpdateFlow`/
`enforceWithDeleteFlow` call when one exists — not only in that Flow's absence. This mirrors the
already-correct non-flow ordering exactly (the gateway call's own internal `store.save`/`deleteById`
becomes a second, harmless write/delete of already-enforced data, precisely the existing
double-write/double-delete pattern the non-flow path already relied on) and ensures a denied row-level
write throws **before** the Flow's own side effects (notifications, external calls) ever run.

**Verification.**
- **RED-first**, against the real production pipeline (`ModelCompiler` → `GeneratorFacade` →
  `TemplateEngine` → the actual `service-base.mustache`), not a hand-simulated fixture: a new test,
  `ServiceBaseFlowRowLevelAuthzTest#flowBackedCreateEnforcesRowLevelWriteAccessBeforeTheFlowRuns`,
  compiles a minimal model (a `Ticket` concept with `access.write` + a `CreateTicket` flow), generates
  the real `TicketServiceBase.java`, and asserts `enforceWithConceptGateway(...)` is present and
  precedes `enforceWithCreateFlow(...)` in the emitted source. Confirmed **RED** against the
  pre-fix template (assertion failure: the gateway call was absent), confirmed **GREEN** after.
- **Full `GATE-GEN` regression suite green** (`:generator:test`, the entire generator module) — the
  restructuring introduces no regression across the existing template/emitter test corpus.
- **Live HTTP end-to-end boot proof was not performed this round** (unlike the read-side historical
  fix, which was caught and confirmed via two real actor identities over real HTTP). Judgment call,
  recorded here rather than silently skipped: the *mechanism* being newly invoked
  (`ConceptGateway.save`/`isRowWritable`) is the exact same one `RowLevelAuthorizationAttackTest`
  already proves correct end-to-end against real store adapters; what was missing — and what this fix
  adds — is only that the flow-backed path now *calls* it, which the RED-first generator test proves
  directly against the real generated source. Recommended follow-up if deeper confidence is wanted:
  a live-boot IT mirroring `RowLevelAuthorizationAttackTest`'s intent but driven through a generated
  app's real `/api/{concept}` endpoint for a Flow-backed concept.

---

### LNCH13-F2 — Lifecycle/invariant validation runs before permission and row-scope checks, leaking a row's status via an error · MEDIUM

**The mechanism.** `DefaultConceptGateway.save()`'s order:

```
previous = store.findById(tenantId, conceptName, id);              // unconditional, no auth check
decision = runWriteSemantics(request.withPreviousRecord(previous), ...);  // can THROW here
enforcePermission(...);                                             // concept.write permission
enforceRowWritable(...);                                            // LNCH-13's own row-scope check
```

`runWriteSemantics` includes `validateLifecycleTransition`, which reads
`request.previousRecord()...data().get(statusField)` and, on an invalid transition, throws
`CONCEPT_LIFECYCLE_TRANSITION_INVALID` with a detail map containing `"from", previous` — the row's
**actual current lifecycle-status value** — before either the coarse `concept.write` permission check
or the row-level `access.write` check has run.

**Attack.** A caller with *any* baseline ability to reach the write endpoint at all (even zero
`concept.write` permission, even a row outside their `access.write` scope) submits a save with a
`status` field set to some valid-but-unreachable-from-current declared state. If the transition is
invalid, the thrown exception's detail includes the row's real current status — a one-field read
achieved with **no read permission, no row-read scope, and no write permission**, since none of those
gates have executed yet. No guessing is required: any `status` value different enough to trigger
`CONCEPT_LIFECYCLE_TRANSITION_INVALID` reveals `from` directly.

**Severity reasoning.** Narrower than F1 — this discloses one field's current value via an error
message, not read/write access to the row. Still a genuine authorization-ordering defect (semantic
work against another actor's data should never run before that actor's own authorization gates), and
`service-base.mustache`'s `enforceWithConceptGateway` wraps `ConceptGatewaySemanticException` into a
generic `InvariantViolationException` that still carries the same detail map through to the HTTP
response (confirmed: `exception.getMessage()`/the detail map is not stripped en route).

**Not fixed this round** (per the plan's triage rule — MEDIUM is filed, not necessarily remediated in
the same pass). Filed as **REG-41** below.

---

### LNCH13-F3 — `query()`'s `total`/`hasMore` leak the count of rows outside the caller's read scope · MEDIUM

**The mechanism.** `docs/ROW_LEVEL_AUTHORIZATION.md` already documents this as a "known limitation":
`ConceptGateway.query()` filters row-scope *after* the store computes `total`/`hasMore` for the
unfiltered page, so those counts can over-report relative to what the caller actually sees. The doc
frames this purely as a pagination-*accuracy* nuisance ("future work, not required for
`read`/`list`'s correctness"). Read as an attacker-first question instead — *what does an attacker
learn from this number?* — it is a genuine information-disclosure side channel: a caller whose
`access.read` scope excludes most rows in a tenant can still observe, via `total`, how many rows
*exist* in total (e.g. how many `SalaryReview` or `IncidentReport` records the tenant has, even ones
they can never open), independent of whatever business reason the row-scope rule exists for.

**Severity reasoning.** MEDIUM, not HIGH/CRITICAL: it leaks an aggregate count, not row content, and
only within the caller's own tenant (tenant isolation itself is unaffected). Still a genuine
side-channel a row-scope rule's author would reasonably expect to be closed by declaring `access.read`
at all. Re-filed as a security item (not just a UX nuisance) since the existing doc's framing
undersells it. Filed as **REG-42** below.

---

### LNCH13-F4 — Row-level write authorization is check-then-act, not atomic · INFORMATIONAL

`DefaultConceptGateway.save()`/`delete()` snapshot `previous` via `store.findById` before evaluating
`isRowWritable`, then persist later — a race window where a concurrent legitimate change to the row's
ownership field could make the authorization decision stale by the time the write actually commits.
Narrow: requires a second actor who *already* has legitimate write access to reassign ownership
inside the window, and the platform's general concurrency model elsewhere (e.g. `expectedRowVersion`
compare-and-swap is opt-in, not default) already accepts similar check-then-act patterns as the norm
rather than the exception. Recorded for the record; no register item filed (per the triage rule, INFO
= record only).

---

## R2 — Triage + remediation plan

Per `docs/FINAL_FOUR_CLOSURE_PLAN.md` §1.3/§1.4's triage rule:

- **CRITICAL — F1 — remediated this round** (mandatory per the plan's own STOP-and-remediate rule;
  the owner was informed of the finding and its remediation plan before the fix was written).
- **MEDIUM — filed as new dated register entries, not fixed in this pass:** F2 → **REG-41**, F3 →
  **REG-42**.
- **INFORMATIONAL:** F4 — no register item filed, recorded in this document only.

### New register items filed (dated 2026-07-25)

| New item | From | Sev | Fix sketch |
|---|---|---|---|
| REG-41 | LNCH13-F2 | MED | Reorder `DefaultConceptGateway.save()` so `enforcePermission` and `enforceRowWritable` run BEFORE `runWriteSemantics`/`validateLifecycleTransition` touches the previous record's data — the previous-record fetch itself can stay (needed for the row-scope check), but nothing derived from it should be allowed to leak via an exception before authorization passes. Regression test: an actor with no `concept.write` permission (or outside `access.write` scope) attempting an invalid lifecycle transition gets `PERMISSION_DENIED`/`ROW_SCOPE_DENIED`, never `CONCEPT_LIFECYCLE_TRANSITION_INVALID` with the previous status in the detail map. |
| REG-42 | LNCH13-F3 | MED | Push row-scope filtering into (or ahead of) the store-level count for `ConceptGateway.query()`, or at minimum recompute `total`/`hasMore` from the row-scope-filtered set when an `access.read` rule is declared for the concept (accepting the extra cost only when row-scoping is actually in effect). Regression test: a caller whose `access.read` excludes most of a tenant's rows sees `total` matching their own visible count, not the tenant's full count. |

### Recommended order if/when scheduled

1. **REG-41** first — smaller, more contained (a reordering inside one method), and closes an actual
   information leak rather than a UX/accuracy gap.
2. **REG-42** — needs a decision on cost (recomputing counts is not free) vs. correctness; scope
   whether it should be opt-in (only concepts with `access.read` pay the recompute cost) before
   implementing.

### Verification bar for either fix

RED-first (an unauthorized-caller-triggers-lifecycle-error test for REG-41; a row-scoped-count test
for REG-42) against a real generated app, mirroring this round's own bar for F1.

---

## Honest statement of what Round 2 did and did not establish

- **Did:** an independent, attack-first read of LNCH-13's full mechanism — the kernel-side gateway
  AND the generated CRUD surface every app's REST controllers actually call (explicitly named as
  "not read" by Round 1) — found and **fixed** a CRITICAL complete bypass of the write-scoping
  guarantee for Flow-backed concepts, RED-first proven against the real generator pipeline, full
  `GATE-GEN` green. Found and filed two further MEDIUM information-disclosure findings as dated
  register items. REG-16-resid's problem statement for this surface is resolved by this document plus
  the landed fix existing.
- **Did not:** perform a live HTTP boot-level proof of the F1 fix (see F1's verification section for
  the reasoning) — flagged explicitly rather than silently claimed. Did not review the bond-member
  endpoints, `ComputedExpression`'s own internals, or `ExecutionContext` construction (out of round
  scope per R0). Did not implement REG-41/REG-42 (MEDIUM, filed not fixed, per the triage rule).
  Round 2 covers LNCH-13 only — Rounds 3-6 (generator codegen output itself, loop/await/orchestration
  flow steps, the other Postgres adapters' own SQL, export/PDF) remain open, one session each, per
  §1.2's ordering. This session does not proceed to Round 3 (the plan's own anti-batching rule).
