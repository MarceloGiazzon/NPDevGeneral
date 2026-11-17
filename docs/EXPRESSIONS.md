# The unified expression language (LNCH-15)

Every expression an app author writes — an invariant, a computed column, a field default, a
derived field — parses through the **same grammar**: `com.npdev.dsl.v1.expr.ComputedExpression`.
There is one parser, one syntax error vocabulary, and one set of compile-time checks
(unknown-field validation, and the boolean-shape check `ConceptValidation.validateInvariantExpressionShape`, both orchestrated by `SemanticValidator`) across all of them.

## Syntax

- **Literals**: numbers (`42`, `3.14`), strings (`'x'` or `"x"`), `true`/`false`/`null`.
- **Field references**: bare identifiers, dotted for nested paths (`cliente.tipo`).
- **Arithmetic**: `+ - * / %`, with the usual precedence. Numbers are **decimal, not binary floating point**
  (FORM P3-T2): `0.1 + 0.2` is exactly `0.3`. `+ - *` are exact, `/` keeps 16 significant digits (`1 / 3` is
  `0.3333333333333333`), and a whole result stays an integer (`3 + 4` is `7`, `1.5 + 1.5` is `3`). The browser
  engine rounds each result to 15 significant digits, which gives the same answers for money-sized values.
- **Lenient and strict evaluation**: the default (lenient) treats `null` as `0` and makes `x / 0` and `x % 0`
  equal to `0`. `ComputedExpression.evaluateStrict` (browser: `{strict: true}`) throws instead, naming the
  sub-expression: `null operand `discount` in `price * discount``, `division by zero in `total / count``. Use
  `??` to make a default explicit: `price * (discount ?? 1)`.
- **Comparison**: `== != < <= > >=`.
- **Logical**: `&& ||`, unary `!`.
- **Parentheses**: override precedence, or group a sub-expression for clarity/negation:
  `(pos > 0 && cxPad != 0) || !(cxAvulsas > 0)`.
- **Function calls**: `name(arg1, arg2, ...)`, or `receiver.name(args...)` sugar (desugared at
  parse time into `name(receiver, args...)`) for a more method-call-like reading, e.g.
  `email.matches('.+@.+')`.
- **Lambdas**: `param => body`, valid only as a function-call argument — e.g.
  `allergies.all(a => a.severity != null)`.
- **List literals and membership** (FORM P3-T2): `['REQUESTED', 'ASSIGNED']`; `status in [...]` and
  `status not in [...]` test membership with the same loose equality as `==`. `in` is infix only, so a field
  called `in` still works elsewhere.
- **Ternary**: `distanceKm > 5 ? 'far' : 'near'` evaluates only the chosen branch and nests to the right.
- **Null coalescing**: `discount ?? 0` is the left side unless it is `null` (an empty string is not `null`).
  Precedence, loosest first: `?:`, `??`, `||`, `&&`, `== !=`, `< <= > >= in`, `+ -`, `* / %`, unary.
  The browser engine (`npdev-expr.js`) implements the same forms, so they are valid in `visibleWhen`.
  `queries[].where` does not accept them yet (P3-T4 lowers it from this grammar).

## Built-in functions

**Invariant expressions** (`CelInvariantEngine`) get these, each returning a boolean:

| Form | Meaning |
|---|---|
| `field.matches(regex)` | regex test against a string field |
| `collection.uniqueBy(keyField)` | no two items share the same `keyField` value; `keyField` is a bare per-item field name, not a value lookup |
| `collection.all(x => predicate)` | every item satisfies `predicate` (vacuously true for an empty/missing collection) |
| `collection.exists(x => predicate)` | at least one item satisfies `predicate` (requires a non-empty collection) |
| `conflicts(resourceField, startsAtField, durationField[, excludeIdField])` (alias: `overlapsProvider`) | true if a scheduling conflict exists — almost always used negated: `!conflicts(...)`. Arguments are **field names of the row being saved**, not values: `resourceField` is the field naming the thing being booked (a provider, a room), `startsAtField` its start (an ISO date-time), `durationField` its length in **whole minutes** (a number), and the optional `excludeIdField` the row's own id so an update does not conflict with itself. |
| `scope.exists("ConceptName", "fieldPath", valueOrPath)` | true if a matching record exists elsewhere in the tenant's data |

**Value functions — the standard library** (default/derived expressions, and anything else handed
`StdlibFunctions.registry()`; in the browser `npdev.expr.stdlib.functions`). The list lives in ONE file,
`NPDevContract/dsl/src/main/resources/expr/functions-stdlib.json`; the validator's allowed names, the Java
implementation and the browser function table are all derived from it, and `functions-parity.json` replays one
case table on both engines. Every function is total: a bad argument gives `null` (a predicate gives `false`),
never an exception.

| Group | Functions |
|---|---|
| text | `concat(a, b, ...)` (null if any arg is null), `coalesce(a, b, ...)` (first non-null, non-blank), `trim`, `upper` (alias `uppercase`), `lower` (alias `lowercase`), `length`, `contains(text, part)`, `startsWith(text, prefix)` |
| numbers | `round(x, places)` (half away from zero; negative places round to tens, hundreds), `floor`, `ceil`, `abs`, `min(a, b)`, `max(a, b)`, `clamp(x, lo, hi)` |
| dates and time | `now()`, `today()`, `addDays(date, n)`, `addHours(dt, n)`, `addMinutes(dt, n)`, `addSeconds(dt, n)`, `daysBetween(from, to)`, `minutesBetween(from, to)`, `isToday(x)` — UTC throughout |
| other | `uuid()`, `distanceKm(lat1, lon1, lat2, lon2)` (haversine, rounded to metres), `withinKm(lat1, lon1, lat2, lon2, km)`, `filter(list, x => ...)`, `map(list, x => ...)` |

`now()` and `uuid()` return a new value on every call, so they are accepted in a `defaultExpression` (evaluated
once, at create) and refused in a `derivedExpression` (it would change on every read).

### Whole-expression defaults: `now()`, `uuid()`, `nextNumber('name')`

`now()` and `uuid()` are ordinary functions since FORM P3-T2 and combine freely in a `defaultExpression`
(`concat('P-', uuid())`, `addHours(now(), 24)`). `nextNumber('name')` is still **not combinable**: a field's
`defaultExpression` may be *exactly* that and nothing else.

| `defaultExpression` | Value written when the row is created |
|---|---|
| `now()` | the current instant as UTC ISO text, `"2026-10-30T21:36:16.678Z"` — use it on a `datetime` field |
| `uuid()` | a fresh random UUID |
| `nextNumber('name')` | the next value of the named counter, allocated when the row is saved |

On a live write each is real. During the boot-time backfill and its shadow proof they are deterministic (`now()`
is the epoch instant, `uuid()` one constant, `today()` the epoch date), so two passes always agree. For a calendar
date use `today()` below (`addDays(today(), 7)`).

### Static type check (FORM P3-T2)

Model validation type-checks every field `defaultExpression`/`derivedExpression` and every expression invariant
(`ExpressionTypeChecker`). It reports only **definite** mistakes — an unresolved reference or an unknown function
is "can't tell", never an error:

- arithmetic on a date, text, boolean or list (`startDate * 2`); `date + n` points to `addDays`, `date - date` to
  `daysBetween`;
- ordering text (`name < 'b'`: the engine orders numbers and dates only) or a boolean/list;
- `x in y` where `y` is known not to be a list; a `?:` condition that is not a boolean; `??` or `?:` branches of
  different types;
- a stdlib function with the wrong argument count (`round(price)`) or an argument of the wrong kind
  (`round(active, 2)`, `addDays(5, 2)`);
- the expression's own type against its context: an invariant must be boolean, a number field's default must be a number.

In a **pushable** context (lowered to SQL: `access.read`, `queries[].where`) only `sql: true` stdlib functions and no
lambdas are allowed; wiring those contexts to the checker lands with P3-T4.

### Parent lookup: `parentField('refField', 'targetField')` (SEC-16)

Available **only in a concept's `access.write` rule**: the value of `targetField` on the row that the
reference field `refField` of this row points at, e.g. `parentField('shelfLayoutId', 'keeperEmail') == $user.id`.
No such parent (unset reference, or no such row in the tenant) denies the write. Refused in `access.read` and in
field-level rules; see [Row-level authorization](reference/ROW_LEVEL_AUTHORIZATION.md).

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

- **A date is a UTC calendar day**, the same reading the `<`/`>=` comparison gives a date against a date-time
  (REG-318): `visitAt <= windowEnd` is true for a visit at 14:00 on `windowEnd` itself, no `+ 'T23:59:59'`
  needed. Two date-times still compare as instants, two dates as days. There is no per-app time zone setting, so
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
  `{"name":"asOf","type":"date","defaultExpression":"addDays(today(), -7)"}` with `where: "dueDate < $params.asOf"`
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
- **There is no legacy fallback any more.** The CEL regex matcher in `CelInvariantEngine` and the
  hand-rolled literal/identifier/call evaluator in `SchemaExpressionSupport` were deleted (FORM
  P3-T4) after a per-JVM counter on each stayed at zero across the module tests, the behaviour
  probes, ride-hailing and the identity journeys. An expression `ComputedExpression` cannot
  evaluate is an error at both sites (`unsupported expression format` for an invariant, the
  `ExpressionException` for a default/derived value), never a second dialect's guess.

## A query `where` (FORM P3-T4)

A `where` is the same language, **lowered** rather than evaluated: the shared AST becomes OR-groups of
AND-combined clauses that the SQL builder and the in-memory filter both consume. Pushable:
`&& || !` and parentheses; `== != < <= > >=` between a field (or a reference path
`customerId.name`, at most 3 hops) and a literal, `$params.name` or `$user.id|actorId|tenantId`;
`x == null` / `x != null`; `x in ['a','b']` and `x not in [..]`; `contains(x, 'a')` and
`startsWith(x, 'a')`; `withinKm(lat1, lng1, lat2, lng2, km)` and `distanceKm(lat1, lng1, lat2, lng2) < km`
(great-circle distance, rounded to metres; each coordinate is a field, a reference path, `$params.x` or
`$user.*`; a row with a missing coordinate never matches, and neither does its negation); a bare boolean
field. A literal may stand on the left (`5 > qty`). Not pushable,
and refused by name: any other function, arithmetic, `?:`, `??`, a negated `contains`/`startsWith`,
and a condition that distributes into more than 64 OR-groups.

## Conditions: procedures, orchestration rules, lifecycle guards (FORM P3-T3)

These three used to carry their own small grammars. They are now plain expressions of this language:

| Where | Names in scope | Result |
|---|---|---|
| procedure `if` `condition` | `$ref` into procedure state (`$input.status`, `$match.found`); a name without `$` is refused | strict boolean; a malformed or non-boolean condition fails the step `CONDITION_UNSUPPORTED` |
| orchestration rule `condition` | `$event`, `$event.<path>`, or a bare payload field | boolean; an undecidable condition skips the rule and says why |
| lifecycle transition `guard` | row fields (the new values) and `$user` | boolean; fails closed (the transition is refused) |

A bare `$ref` is a legal condition only when it is a boolean; for presence write `$x != null`, for a non-empty
string `length($x) > 0`.

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