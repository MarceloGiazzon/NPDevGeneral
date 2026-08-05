# NPDev features

> **Verified 2026-08-05.** Every row is a declared capability in `model.schema.json`, and every
> row previously marked now has a confirmed working example in the corpus:
> `extends`, `specializes`, `sensitive`, `selectors`, `documents` and tenancy all appear in
> `NPDevSamples/dsl-conformance-max`; `derivedExpression`/`defaultExpression` appear in a corpus
> model each. **A feature list is a promise — this one is backed by examples that a gate keeps
> exercising.**

---

## The shape of it

**One JSON file describes your whole application.** Not a schema — an application: data, rules,
processes, screens, permissions, and how it all changes over time.

| Area | What you declare |
|---|---|
| **Data** | concepts, fields, types, constraints, relationships, inheritance |
| **Rules** | invariants, expressions, validation, lifecycle |
| **Behaviour** | flows, procedures, events, scheduled work |
| **Screens** | panels, workbenches, documents, guides |
| **Access** | roles, grants, row-level rules, sensitive fields |
| **Change** | renames, conversions, destructive-change policy |
| **Scale** | packs, fragments, bounded contexts, scoped properties |

---

## 1. Data model

| Feature | What it does for you |
|---|---|
| **13 field types** — `string`, `uuid`, `int`, `long`, `boolean`, `date`, `datetime`, `enum`, `reference`, `object`, `array`, `file`, `integer` | covers real business data without escape hatches; `file` and `array` included |
| **`domainTypes`** | define `MRN` or `Money` **once** — format, validation, widget, label — then reuse it everywhere. Change the rule in one place |
| **`invariants`** | `unique` (single or compound) and `expression` rules become **real DB constraints plus validation plus a clear error** — not a comment nobody enforces |
| **`reference` fields / bonds** | foreign keys, joins, cascade behaviour, and a **searchable picker in the UI** — from one declaration |
| **`extends` / `specializes`** | concept inheritance. Shared fields declared once; specialized concepts add to them |
| **`indexes`** | declare the indexes your queries need; they are created and tracked |
| **`derivedExpression` / `defaultExpression`** | computed and defaulted values evaluated **server-side**, so every client agrees |
| **`sensitive`** | mark a field and it is handled accordingly in logs, traces, and exports |
| **`enumValues`, `minLength`, `maxItems`, `uniqueItems`, …** | 29 field properties in total — the ordinary constraints you would otherwise hand-write in three places |
| **`truthLevel` (T0–T6)** | classify how *trustworthy* each concept's data is; a reference to less-trusted data raises a warning at authoring time |

## 2. The database, and changing it

**This is the part most generators do not do.**

| Feature | What it does for you |
|---|---|
| **Schema evolution, not drop-and-recreate** | regenerate against a live database and **your data survives** |
| **`renamedFrom`** | renames move the column *and its data*, instead of being seen as a drop plus an add |
| **Destructive-change refusal** | dropping a column or concept is refused unless you explicitly acknowledge it. **Your data is not collateral damage of an edit** |
| **`conversions`** — `copy`, `split`, `lookup`, `merge`, `convert` | reshape existing data as part of a migration, declaratively: split a name into two columns, look a value up against another table |
| **Raw-SQL hooks** | when the declarative vocabulary is not enough, run SQL, guarded by a claim that must match the real schema diff |
| **H2 → PostgreSQL** | develop on a file database, promote to Postgres with a real operator command |
| **Snapshots** | table-level snapshot and restore around risky migrations |
| **Multi-instance safety** | a real lock, so two instances cannot migrate the same database at once |

## 3. API and queries

| Feature | What it does for you |
|---|---|
| **Generated REST API** | full CRUD for every concept, consistent, documented |
| **`queries`** with `where`, `orderBy`, `limit`, `parameters` | named, parameterised, permission-checked queries — no string-built SQL in a controller |
| **`groupBy` + `aggregates` + `having`** | roll-ups (`sum`, `count`, `avg`) as declarations, including **joins across concepts** — dashboards without a reporting layer |
| **`permissionRequirements` per query** | a query carries its own access rule, so it cannot be exposed by accident |
| **`tracePolicy` / `auditPolicy`** | declare what gets traced and audited per query or procedure, instead of remembering to log |
| **`selectors`** | reusable pickers/lookups shared across screens |

## 4. Business logic

| Feature | What it does for you |
|---|---|
| **`flows` — 13 step types** | `invariantCheck`, `capabilityCall`, `generatedAction`, `emitEvent`, `scheduleEvent`, `return`, `branch`, `awaitEvent`, `createConcept`, `updateConcept`, `map`, `forEach`, `callProcedure` |
| **Durable execution** | a flow **survives a process restart** and resumes where it stopped. Long-running approvals do not evaporate on a deploy |
| **`awaitEvent`** | pause a process until something happens — an approval, a delivery, a webhook — for as long as it takes |
| **`forEach`, including parallel** | iterate a collection; parallel iterations stay **isolated from each other** and each resumes correctly after a crash |
| **`scheduleEvent` + `schedule`** | delayed and recurring work without a separate scheduler |
| **`procedures`** | reusable server-side logic with parameters, locals, and a return value |
| **`orchestrationRules`** | react to events: when *this* happens, run *that* |
| **`capabilities` / `bindings`** | name an operation, bind it to an implementation — swap adapters without touching the model |
| **`customCapabilities`** | your own Java, called from a flow, when the declarative vocabulary runs out |

## 5. Screens

| Feature | What it does for you |
|---|---|
| **Generated admin UI** | list, create, edit, search for every concept, from day one, with no work |
| **`autoPanels`** | richer screens derived automatically from the model |
| **`panels`** | declared screens with a real `route`, multiple `dataSources`, layout, field bindings, and actions |
| **`visibility` / `enabledWhen`** | show and enable things conditionally, by rule rather than by JavaScript |
| **`actions`** | buttons that call flows or procedures, including ones that **stream a generated file back** |
| **`aggregates` (workbenches)** | master-detail-detail screens editing a root and its collections **in one transaction**, with `onValidate` and `onCommit` hooks |
| **`documents`** | generated documents and printable output |
| **`guidePages` / `explainability`** | attach guidance to a screen so users are told what it is for — in the model, beside the thing it explains |
| **Widget catalogue** | field types map to appropriate inputs, validated for compatibility at authoring time |

## 6. Security

| Feature | What it does for you |
|---|---|
| **`roles` + `grants`** | declare roles and what they may do |
| **`requiredRole`** | on flows, queries, panels — **enforced at the API, not just hidden in the UI** |
| **Row-level authorization** | restrict *which rows* a user sees, under a real transaction |
| **`access` per concept** | per-concept access rules |
| **`sensitive` fields** | flagged for special handling in logs and exports |
| **JWT or API-key auth** | pick per deployment; API keys map to roles |
| **Super-user bootstrap** | a key issued on first boot, written to disk — no default password to forget to change |
| **ControlPanel** | built-in admin surface for users, roles, credentials, schedules |

## 7. Composition and scale

| Feature | What it does for you |
|---|---|
| **`fragments`** | split a large model across files; compose by `$ref` |
| **`packs`** | reusable model modules shared across applications |
| **`contexts`** (bounded contexts) | partition a big model into named contexts with **explicit `imports`** — so one team's `Order` is not another's |
| **`physicallyIsolate`** | opt in to context-qualified table names when two contexts genuinely need separate tables |
| **`propertyScopes` / `properties`** | configuration that **cascades** — a value set globally, overridden per tenant, per user; resolution order is declared |
| **Multi-tenancy** | tenant isolation is built in, not bolted on |
| **`externalAi`** | declare external AI usage explicitly, with egress validated |

## 8. Operating it

| Feature | What it does for you |
|---|---|
| **Docker Compose generated** | with an optional TLS-terminating proxy profile |
| **Seed data** | named JSON seed files for demos, tests, and fresh environments |
| **Audit + trace policies** | declared per operation, not scattered through code |
| **`npdev dev`** | watch the model; rebuild, restart, and evolve the schema on every save |
| **`npdev doctor`** | check the machine before you spend an hour finding out |
| **`npdev migrate`** | when NPDev's own DSL changes, a codemod rewrites your models |
| **`npdev validate`** | typed, machine-readable diagnostics with the exact path and a suggested fix |
| **MCP server + AI authoring** | 16 tools so an agent can author, validate, correct itself, and generate |

---

## The honest frame

**These are declarations, not code you write.** The whole list above is JSON in one file. That is
the ease.

**And it is a real Spring Boot application at the other end** — source you own, no runtime
dependency on NPDev. That is the quality.

**What is deliberately *not* here:** a designed consumer-facing UI, a microservice generator, hot
reload without restart. Those and every other designed limit are in
`docs/ACCEPTED_BOUNDARIES.md` — **kept accurate on purpose, because a stale limitations page costs
more trust than a short feature list.**

---
