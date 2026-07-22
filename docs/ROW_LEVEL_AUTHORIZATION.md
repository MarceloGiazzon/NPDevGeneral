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

Each rule is a boolean [`ComputedExpression`](EXPRESSIONS.md) evaluated against a scope built from
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

## Known limitation: `query()` paging is an approximation under row-scope

`ConceptGateway.query` (LNCH-5's filter/sort/page endpoint) filters row-scope *after* the store
already computed `total`/`hasMore` for the unfiltered page. If a page mixes readable and
unreadable rows, the returned `total`/`hasMore` can overcount relative to what the caller actually
sees. Pushing row-scope into the store-level query (e.g. as a SQL predicate) instead of a
post-fetch filter is future work, not required for `read`/`list`'s correctness.

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
