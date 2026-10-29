# The unified expression language (LNCH-15)

Every expression an app author writes — an invariant, a computed column, a field default, a
derived field — parses through the **same grammar**: `com.npdev.dsl.v1.expr.ComputedExpression`.
There is one parser, one syntax error vocabulary, and one set of compile-time checks
(unknown-field validation, and the boolean-shape check `ConceptValidation.validateInvariantExpressionShape`, both orchestrated by `SemanticValidator`) across all of them.

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

**Schema default/derived expressions** (`SchemaExpressionSupport`, in `NPDevKernel/adapters/runtime-support/.../crud/schemaexpr/`) get a different, smaller
set — pure value functions, no boolean logic needed:

| Form | Meaning |
|---|---|
| `concat(a, b, ...)` | string-concatenates args; `null` if any arg is missing |
| `coalesce(a, b, ...)` | first non-missing arg |
| `trim(x)` / `uppercase(x)` / `lowercase(x)` | string transforms |

### Date functions (REG-313)

One implementation, `com.npdev.dsl.v1.expr.DateFunctions`, available **everywhere an expression runs**: a
field `defaultExpression` / `derivedExpression`, an invariant, a procedure `computeValue` step, and a query
parameter's `defaultExpression`.

| Form | Meaning |
|---|---|
| `today()` | the current **UTC** calendar date, `"2026-10-29"` |
| `addDays(date, n)` | `date` plus `n` whole days (negative goes back). A date stays a date (`"2026-12-24"`); a date-time keeps its time of day and offset (`"2026-10-02T10:15:30Z"`) |
| `daysBetween(from, to)` | whole days from `from` to `to` (`to` minus `from`; negative if `to` is earlier), as a number |

```
visitDate >= addDays(enrolmentDate, 77) && visitDate <= addDays(enrolmentDate, 91)   // invariant: day 84 ± 7
addDays(enrolmentDate, 84)                                                           // derivedExpression
addDays(today(), 7)                                                                  // defaultExpression
daysBetween(enrolmentDate, today()) <= 84                                            // invariant
```

- **A date is a UTC calendar day**, the same reading the `<`/`>=` comparison already gives dates (a date is
  00:00 UTC; a date-time counts as the UTC date it falls on). There is no per-app time zone setting, so
  `today()` flips at 00:00 UTC, not local midnight.
- **Total, never throwing.** An operand that is not a date, a `n` that is not a whole number (`1.5`, `"3"`),
  a wrong argument count, or a result outside the representable range gives `null` — the rule `concat`
  already follows. In an invariant, `null` cannot be ordered, so a comparison around it is false — which
  means **write the rule as the positive condition** (`daysBetween(a, b) >= 0`), not as a negated one
  (`!(daysBetween(a, b) < 0)`, which is true when `a` or `b` is not a date).
- **`today()` is deterministic where it has to be:** the boot-time expression-default backfill evaluates it
  to the epoch date `1970-01-01`, exactly as it does `now()`, so its two shadow passes always agree.
- **In a procedure** the same three are `computeValue` operations, not expressions:
  `{"type":"computeValue","operation":"addDays","left":"$p.enrolmentDate","right":"$vt.dayOffset","target":"targetDate"}`,
  `"operation":"daysBetween"` (`left`/`right` are the two dates) and `"operation":"today"` (no operands).
  `add`/`subtract` stay numeric: handing them a date is refused at validation
  (`compute_value_invalid_operand`) and at runtime (`COMPUTE_VALUE_INVALID_OPERAND`).
- **In a query**, a parameter may declare a `defaultExpression` that is evaluated when the caller omits it:
  `{"name":"asOf","type":"date","defaultExpression":"addDays(today(), -7)"}` with `where: "dueDate < :asOf"`
  serves "overdue by a week" with no parameter, and `?asOf=2026-01-01` still wins. Only literals and the
  value functions are allowed (no record is in scope, so a field reference is refused), a defaulted
  parameter cannot also be `required: true`, and the OpenAPI document advertises it as optional and names
  the default. A parameter **without** a default is still refused with 400 when omitted. The default is
  query-only: on a procedure parameter it is refused at validation, because nothing would evaluate it.

Both function sets are `ComputedExpression.FunctionRegistry` instances, built by their respective
callers (`CelInvariantEngine.buildFunctionRegistry`, `SchemaExpressionSupport`'s
`SCHEMA_EXPRESSION_FUNCTIONS`, which is `ValueExpressionFunctions.base()`) — a function registry is just a `Map<String, ExprFunction>`, so a
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
  (in `SchemaExpressionSupport`) are still in the code, unused in practice, as defensive
  fallbacks** — each call site tries `ComputedExpression` first and only falls through to the old
  implementation on a genuine `ExpressionException` (syntax this grammar still doesn't recognize).
  They were deliberately not deleted: removing a working fallback the moment its callers are
  proven to be dead code is a bigger risk than the small amount of now-unreachable code they add.

## What's still two grammars, deliberately

There used to be a separate `cap.<capability>.<operation>(args)` capability-invocation expression form
(e.g. `cap.persistence.unique('User','email', email)`) that this unification did NOT touch. No evaluator for
it exists any more: at runtime an unknown function call throws `unknown function`. `ComputedExpression`'s
grammar still *parses* the dotted call shape (dotted function-call syntax is generic), and
`Call.looksBoolean()` is deliberately permissive — an unrecognized function name at the top level of an
invariant is assumed intentional rather than flagged as a shape error.

The concept gateway (`ConfiguredConceptGatewaySemanticPolicy`) no longer has a grammar of its own
either: it evaluates invariants with `ComputedExpression` (ENG-7), so a rule such as
`!(qty > 0 && price == 0)`, `total >= subtotal * 0.9` or `email.matches('.+@.+')` holds on every
write path that reaches the gateway, not only on the CRUD service's kernel CEL pass. Only the
store-dependent functions (`conflicts`/`overlapsProvider`, `scope.exists`) are left to the kernel
CEL engine, because the gateway has no store access; the gateway reports those as
`skippedUnsupportedExpression`.