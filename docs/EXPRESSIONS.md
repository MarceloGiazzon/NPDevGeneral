# The unified expression language (LNCH-15)

Every expression an app author writes — an invariant, a computed column, a field default, a
derived field — parses through the **same grammar**: `com.npdev.dsl.v1.expr.ComputedExpression`.
There is one parser, one syntax error vocabulary, and one set of compile-time checks
(`SemanticValidator`'s unknown-field and boolean-shape validation) across all of them.

## Syntax

- **Literals**: numbers (`42`, `3.14`), strings (`'x'` or `"x"`), `true`/`false`/`null`.
- **Field references**: bare identifiers, dotted for nested paths (`cliente.tipo`).
- **Arithmetic**: `+ - * / %`, with the usual precedence; `/` and `%` are safe against divide-
  by-zero (yield `0`).
- **Comparison**: `== != < <= > >=`.
- **Logical**: `&& ||`, unary `!`.
- **Parentheses**: override precedence, or group a sub-expression for clarity/negation:
  `(pos > 0 && cxPad != 0) || !(cxAvulsas > 0)`.
- **Function calls**: `name(arg1, arg2, ...)`, or `receiver.name(args...)` sugar (desugared at
  parse time into `name(receiver, args...)`) for a more method-call-like reading, e.g.
  `email.matches('.+@.+')`.
- **Lambdas**: `param => body`, valid only as a function-call argument — e.g.
  `allergies.all(a => a.severity != null)`.

## Built-in functions

**Invariant expressions** (`CelInvariantEngine`) get these, each returning a boolean:

| Form | Meaning |
|---|---|
| `field.matches(regex)` | regex test against a string field |
| `collection.uniqueBy(keyField)` | no two items share the same `keyField` value; `keyField` is a bare per-item field name, not a value lookup |
| `collection.all(x => predicate)` | every item satisfies `predicate` (vacuously true for an empty/missing collection) |
| `collection.exists(x => predicate)` | at least one item satisfies `predicate` (requires a non-empty collection) |
| `conflicts(resourceField, startsAtField, durationField[, excludeIdField])` (alias: `overlapsProvider`) | true if a scheduling conflict exists — almost always used negated: `!conflicts(...)` |
| `scope.exists("ConceptName", "fieldPath", valueOrPath)` | true if a matching record exists elsewhere in the tenant's data |

**Schema default/derived expressions** (`GeneratedCrudRuntimeSupport`) get a different, smaller
set — pure value functions, no boolean logic needed:

| Form | Meaning |
|---|---|
| `concat(a, b, ...)` | string-concatenates args; `null` if any arg is missing |
| `coalesce(a, b, ...)` | first non-missing arg |
| `trim(x)` / `uppercase(x)` / `lowercase(x)` | string transforms |

Both function sets are `ComputedExpression.FunctionRegistry` instances, built by their respective
callers (`CelInvariantEngine.buildFunctionRegistry`, `GeneratedCrudRuntimeSupport`'s
`SCHEMA_EXPRESSION_FUNCTIONS`) — a function registry is just a `Map<String, ExprFunction>`, so a
future call site can register its own without touching the grammar itself.

## Design notes for anyone extending this

- **Functions receive raw argument AST nodes, not pre-evaluated values.** A function decides for
  itself when/how to evaluate each argument (`node.eval(vars)`) — this is what lets `uniqueBy`
  treat its second argument as a literal per-item field name instead of a value lookup, and lets
  quantifiers (`all`/`exists`) pass a lambda argument to `node.invokeLambda(item, vars)` once per
  collection item instead of evaluating it eagerly against the outer scope.
- **A `FailureHint` side-channel carries rich failure detail through negation.** Functions return
  an ordinary boolean (so `!`/`&&`/`||` compose correctly around them) but can additionally record
  a human-readable `(details, fieldPath)` hint for the specific case where their own natural
  failure condition is true — `CelInvariantEngine.evaluateExpression` consults it after a `false`
  top-level result instead of falling back to a generic "expression evaluated to false" message.
  This is what lets `!conflicts(...)` report "Resource is already reserved during this time" with
  the right field path, even though the negation happens one level up in the AST from where the
  function itself runs.
- **The legacy CEL regex matcher (in `CelInvariantEngine`) and the legacy schema-expr evaluator
  (in `GeneratedCrudRuntimeSupport`) are still in the code, unused in practice, as defensive
  fallbacks** — each call site tries `ComputedExpression` first and only falls through to the old
  implementation on a genuine `ExpressionException` (syntax this grammar still doesn't recognize).
  They were deliberately not deleted: removing a working fallback the moment its callers are
  proven to be dead code is a bigger risk than the small amount of now-unreachable code they add.

## What's still two grammars, deliberately

`GeneratedCrudRuntimeSupport` also has a separate `cap.<capability>.<operation>(args)`
capability-invocation expression form (e.g. `cap.persistence.unique('User','email', email)`) that
this unification did NOT touch — it dispatches into the capability system, not field/value logic,
and is a different concern from "compute a value" or "check an invariant". `ComputedExpression`'s
grammar happens to *parse* this shape now (dotted function-call syntax is generic), and
`Call.looksBoolean()` is deliberately permissive for exactly this reason — an unrecognized
function name at the top level of an invariant is assumed intentional rather than flagged as a
shape error.
