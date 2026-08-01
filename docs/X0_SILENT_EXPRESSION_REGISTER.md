# X0 — the silent-answer register

> **Wave 0.2** of `MASTER_AI_PLATFORM_PROGRAMME_v2.md` §2.1. Opened 2026-07-31.
>
> **The one question, asked of every expression/predicate evaluator in the platform:**
> *what does it do with input it cannot handle?*
>
> **The one rule:** an input the evaluator cannot handle is an **error** — never a default answer.
> Silently returning everything, nothing, `false`, or `null` is the defect.
>
> The programme opened this task with two confirmed instances and predicted a third.
> **The audit found five, and two of them were found by using the feature rather than reading it.**

## Why a register and not a bug list

Every instance below is individually small and individually defensible — "fail open so nothing
disappears", "an absent query means no filter", "a missing path is just null". Together they are a
platform in which **a declaration that does nothing looks exactly like a declaration that works**.
That matters more than usual here: the programme's whole direction is an AI author writing this JSON.
An author who can see the wrong answer will debug it. An author who is told nothing will ship it.

## Status

| # | Evaluator | Input it cannot handle | What it does today | Verdict |
|---|---|---|---|---|
| X0-1 | `ConceptQueryFilterSupport` — declared `queries[].where` | any clause outside one `field == literal` | **passes every row through, unfiltered** | OPEN — LC-P0, Wave 0.3 |
| X0-2 | Workbench `derivedFields` client subset | `filter(...)` — its own documented syntax | **returned `0`** | **FIXED** — REG-95 |
| X0-3 | procedure `condition` / `if` | any comparison at all | truthiness-only; `== 'X'` is inexpressible | OPEN — REG-96 |
| X0-4 | band `transaction.visibleWhen` | the only spelling the validator accepts | **predicate silently dropped before the evaluator saw it** | **FIXED** — REG-99 |
| X0-5 | `RolePermissions.toRole()` | an app-defined role name | returns `null`, loop `continue`s → **the role grants nothing** | **FIXED** — REG-104 (Wave 3, `RC-B1`) |
| X0-6 | `DefaultProcedureExecutor.resolve()` | an unresolvable `$ref` path | returns `null` → written as a null field value | **OPEN — filed here, see below** |
| X0-7 | `runQuery` step | a query name absent from `queriesByName` | **unfiltered list**, acknowledged in its own comment | **OPEN — filed here, see below** |
| X0-8 | `visibleWhen` / `$ui` client predicate | an unparseable or typo'd predicate | fails **OPEN** (surface stays visible) | ACCEPTED, with one gap — see below |
| X0-9 | `enabledWhen` (business-ui `evaluateWhen`) | same grammar, same evaluator family | same fail-open | ACCEPTED, same rationale |
| X0-10 | `computeValue` | an unknown operator | **refused at model level** (`PackValidation`) | **CLEAN — the shape to copy** |
| X0-11 | `expression-cel` (invariants, `access.*`, `defaultExpression`) | — | **NOT AUDITED** — see "What this audit did not cover" |
| X0-12 | `having` (proposed, `LC-B1`) | — | does not exist yet; **must be born loud** |

## The two this audit added

### X0-6 — `resolve()` returns `null` for an unresolvable path, and the same class disagrees with itself

`DefaultProcedureExecutor.resolve(state, ref)` walks a dotted path and `return null` the moment a
segment is missing. It is the resolver behind `resolveSetValue`, which is what `patchConcept.set`,
`mapList.select` and `mapValue` use. So:

```json
{ "type": "patchConcept", "concept": "Lote", "id": "$loteId",
  "set": { "quantidade": "$item.quantidad" } }
```

writes `quantidade: null` — a typo in a field ref becomes a null write, with no error anywhere.

**What makes this a finding rather than a design choice is that the same class already disagrees with
itself.** `requireString` and `requireMap` — used for `idRef` and for map-shaped inputs — call the
same `resolve` and then **throw** `IllegalArgumentException` when it comes back null/blank. Id refs
are loud; value refs are silent. One of the two is wrong, and nothing records which.

Note the interaction with **REG-89**'s history: `patchConcept` + `createIfMissing` builds a brand-new
record out of `set` alone. A typo'd `$ref` there creates a record with a null field rather than
failing, and a governed gateway will then reject it for a *required-field* violation naming a field
the author never mentioned — the error surfaces one layer away from its cause.

### X0-7 — `runQuery` with an unknown query name returns an unfiltered list

`DefaultProcedureExecutor.runQuery` says so itself:

```java
// LIFT-QUERY-P1: the query name is threaded through the (legacy-named) "operation" slot.
// Absent from queriesByName -> unfiltered, same as before this fix.
CompiledQuery query = step.operation() == null ? null : queriesByName.get(...);
```

This is **X0-1's exact shape one layer up** — LC-P0 is about a declared `where` that does not filter;
this is about a declared *query* that does not filter, because the name did not resolve. It is
already acknowledged in a comment, which is the most dangerous state for this class: known, written
down, and not surfaced to the author. `PackValidation` does check that a `runQuery` step's `query`
names a declared query, so the model-level door is shut — but the runtime lookup is keyed by a
normalized name and falls back to unfiltered rather than failing, so any drift between the two
(pack-provided queries, a rename, a case/normalization mismatch) reopens it silently.

**These two should be fixed with LC-P0 in Wave 0.3, not separately.** All three are the same
sentence: *a filter that cannot be resolved must be an error, never an empty filter.*

## The two this audit accepted, and the one gap inside them

`evaluateVisibleWhen` (X0-8/X0-9) fails **open** deliberately, and its own comment argues the case:
a hidden surface whose rows are still committed is worse than a visible one, so "worst case something
stays visible, it never disappears incorrectly and silently drops data from view". That reasoning
holds and is **accepted** — the failure mode it avoids is the more dangerous one.

**But fail-open is only safe when a wrong predicate is caught at authoring time, and half of them
are not.** Move 11 W6 added validation for `$ui.<name>` predicates: an undeclared control, or a
literal outside the control's declared values, is refused. **Nothing validates a `$root.<field>`
predicate** — `$root.tpio == 'X'` (typo) validates clean and then silently shows everything, forever.

That is the concrete, bounded follow-up this register leaves behind: **validate `$root.<field>`
predicates against the root concept's declared fields, exactly as `$ui.<name>` is now validated.**
Small, and it converts the accepted fail-open from a hazard into a genuinely safe default.

## The one that is already right — copy this shape

`computeValue` refuses an unknown operator **at model level**, in `PackValidation`:

```
computeValue requires operation to be one of [add, subtract], got: multiply
```

The author is told at authoring time, by name, with the legal set. No runtime default, no silent
answer. **This is the shape every row above should converge on**, and it is the argument for fixing
this class in the validator wherever the validator can see it — which is most places.

## What this audit did not cover, stated plainly

- **`expression-cel`** (invariants, `access.read`/`access.write`, `defaultExpression`,
  `derivedExpression`) — the largest evaluator in the platform and the only one that is a real
  expression engine rather than a narrow subset. Not audited. It is also the one where a silent
  default would matter most, because `access.*` is an authorization answer: an `access.read` that
  silently evaluated to `true` for an expression it could not compile would be an information
  disclosure, and an `access.read` that silently evaluated to `false` would be a support ticket.
  **This is the highest-value single item left in X0**, and it deserves its own pass rather than a
  paragraph at the end of this one.
- **Generated CRUD runtime support** (`GeneratedCrudRuntimeSupport`, 158 KB) — not read.
- The **frontend `evaluateWhen`** family beyond `visibleWhen`/`enabledWhen`.

## Rule for anything added later

A new evaluator, subset, or predicate dialect must answer this register's one question **in its own
doc comment**, before it ships. `having` (`LC-B1`) is the next one due, and the programme already
records that it must be born loud.
