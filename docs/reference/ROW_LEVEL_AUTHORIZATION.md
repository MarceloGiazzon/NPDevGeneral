# Row-level (data-scoped) authorization (LNCH-13)

A concept can declare **which individual rows** a caller may read or write, on top of the
existing coarse-grained CRUD permission check (`concept.read`/`concept.write`/... via
`PermissionEvaluator`) and tenant isolation. Coarse permission answers "can this actor call READ
on ContactMessage at all"; row-level access answers "can this actor read *this* ContactMessage
row" — e.g. "only the row's own owner may see it."

## Declaring an access rule

```json
{
  "name": "ContactMessage",
  "fields": [ ... ],
  "access": {
    "read": "email == $user.id",
    "write": "email == $user.id"
  }
}
```

Both `read` and `write` are optional and independent — a concept can restrict one without the
other. Omitting `access` entirely (or the whole block) means unrestricted row access, same as
today.

Each rule is a boolean [`ComputedExpression`](../EXPRESSIONS.md) evaluated against a scope built from
the row's own field values plus a set of `$`-prefixed pseudo-variables describing the caller:

| Pseudo-variable | Value |
|---|---|
| `$user.id` / `$user.actorId` | the caller's actor id from `ExecutionContext` |
| `$user.tenantId` | the caller's tenant id |
| `$user.roles` | the caller's role list |

`$` is a valid leading character for an expression identifier specifically to support this sigil
(see `ComputedExpression`'s tokenizer) — it's not a general-purpose prefix operator.

- **`read`** is evaluated against the record being read.
- **`write`** is evaluated against the record's *previous* state on update/delete (the row as it
  exists before the mutation), or the incoming payload on create (there is no previous state yet).

## Semantics: fail closed, worded like "not found"

- A malformed or unparsable `access` expression makes `isRowReadable`/`isRowWritable` return
  `false` for every row (fail closed), not throw — a broken rule denies access rather than
  granting it or crashing the request.
- A denied read behaves like a **404**, not a 403: `read`/`list`/`query` simply omit the row, the
  same way they would for a nonexistent id. This avoids confirming a resource's existence to a
  caller who isn't allowed to see it.
- A denied write throws `ConceptGatewayAccessDeniedException("ROW_SCOPE_DENIED", ...)` from
  `DefaultConceptGateway.save`/`delete`.

## Where it's enforced

`ConceptGatewaySemanticPolicy.isRowReadable(record, context)` / `isRowWritable(context)` are the
extension points (default: always `true`, i.e. unrestricted). `ConfiguredConceptGatewaySemanticPolicy`
is the generic, data-driven implementation every generated app wires up automatically via
`RuntimeConceptGatewaySemanticPolicies` — **no generator emitter or per-concept generated code is
needed**; the row-scope rule lives in the compiled model and is interpreted at request time.

`DefaultConceptGateway` is the single choke point that applies it:

- `read` — filters the result through `isRowReadable` before field-visibility filtering.
- `list` / `query` — filters every candidate row the same way (see the note below on `query`'s
  paging behavior).
- `save` / `delete` — call `enforceRowWritable` before the mutation, throwing on denial.

**Every generated REST surface for a concept goes through this gateway for reads as well as
writes** — the per-concept `{Concept}ServiceBase` (`getById`/`list`/`page`, backing the default
`/api/{concept_plural}` controller) and the generic `/api/concepts/{conceptName}` controller both
route through `ConceptGateway.read`/`list`/`query`, not `ConceptStore` directly. This was not true
initially: a first implementation left `{Concept}ServiceBase`'s `findByIdFromConceptStore`/
`findAllFromConceptStore` reading straight from `ConceptStore`, which is a plain per-tenant KV/SQL
lookup with no notion of row scoping — writes went through the gateway (and were correctly denied)
while reads on the exact same default REST endpoints silently returned every row regardless of
`access.read`. This was only caught by live end-to-end verification (two real actor identities
over real HTTP against a booted generated app), not by the hermetic gateway-level attack-suite
tests, since those construct `DefaultConceptGateway` directly and never exercise the generated
`ServiceBase` code path. Fixed in `service-base.mustache`.

## `query()` pagination metadata is row-scoped too (REG-42, fixed 2026-07-25)

`ConceptGateway.query` (LNCH-5's filter/sort/page endpoint) still filters the returned `items` *after*
the store computes its initial page, but `total`/`hasMore` are no longer trusted from that unfiltered
count. REG-16-resid Round 2 reclassified the old "pagination approximation" framing here as a genuine
information-disclosure defect (LNCH13-F3): a caller whose `access.read` excludes most of a tenant's
rows could learn how many rows exist outside their own scope via `total`, even though `items` correctly
hid them. `ConceptGatewaySemanticPolicy.hasRowReadScope(conceptName)` now tells `DefaultConceptGateway`
when a concept declares `access.read` at all; only then does `query()` pay the cost of an unpaged
re-query (bounded by `ConceptQuery.MAX_LIMIT`, the same ceiling every single query already has) to
recompute `total`/`hasMore` against the row-scoped result set. Every other concept's `query()` is
unaffected. See `docs/archive/programme-history/REG16_LNCH13_ROWLEVEL_AUTHZ_ADVERSARIAL_REVIEW.md` (LNCH13-F3) and the register's
REG-42 row.

## Check-then-act, not atomic against a concurrent ownership change (LNCH13-F4, accepted boundary B18)

`DefaultConceptGateway.save()`/`delete()` snapshot the previous row (`store.findById`) before
evaluating `isRowWritable`, then persist later. A concurrent actor who *already has* legitimate write
access to the row could reassign its ownership inside that window, making the authorization decision
stale by the time the write commits. This is **not** a way for an unauthorized actor to gain access —
it requires a second actor who already passed the same `access.write` check — and it is accepted as a
documented boundary (`docs/ACCEPTED_BOUNDARIES.md` B18; disposition recorded 2026-07-27, F8,
`docs/DECISION_BRIEFS_2026-07.md`), with a named revisit trigger: if any concept's `access.write` rule
ever becomes reassignable by a role other than the row's own owner/admin, revisit this.

**If you need the stronger guarantee today:** pass `ConceptWriteRequest.expectedRowVersion`. This is
an existing, opt-in optimistic-CAS mechanism (`NPDevKernel/kernel/.../concepts/`,
`ConceptGatewayOptimisticLockException` thrown on a stale version) already used for concurrent-*edit*
conflicts — it is not wired to also gate the row-level authorization re-check specifically, but a
caller who supplies it gets a hard failure instead of a silent stale-authorization write whenever the
row changed underneath them, which closes the practical risk for any concept where it matters.

## What's deliberately out of scope

- **Uniqueness pre-checks** (`existsUniqueInConceptStore` and friends) intentionally scan the
  whole tenant, ignoring `access.read` — a `unique:true` field must stay globally unique within
  the tenant regardless of who can see which rows, or two callers could each create a row with the
  same "unique" value because neither could see the other's.
- Row-level rules do not affect tenant isolation, which remains a separate, always-on mechanism.

## Verification

- `ConceptAccessValidationTest` — compile-time validation (unknown fields, non-boolean rule,
  syntax errors) via `SemanticValidator`.
- `CompiledModelCanonicalJsonReaderTest` — `access` survives the canonical JSON round trip that
  every generated app's `NPDevModelProvider` actually reads at boot (a gap that existed here too,
  fixed alongside the `ServiceBase` gap above).
- `RowLevelAuthorizationAttackTest` — `@ParameterizedTest` over both the InMemory and JDBC/H2
  `ConceptStore` adapters, proving read/list/query/update/create/delete all deny cross-user access
  through the real `RuntimeConceptGatewaySemanticPolicies` production bridge.
- Live end-to-end: two real actor identities (`X-Api-Key` → distinct `$user.id`) against a booted
  generated app's default REST controller, confirming read/list/update denial over real HTTP.
