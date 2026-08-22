# NPDev Concepts Deep Dive — the 8 building blocks, explained in detail

> **Companion to** [`NPDEV_USER_MANUAL.md`](NPDEV_USER_MANUAL.md). That manual introduced these
> eight words at a glance. This document goes one level deeper into each — what it's *for*,
> exactly what you're allowed to write, and a real example pulled from an app that has
> actually been generated and run in this workspace. Read the manual first if you haven't;
> this document assumes you already know the three-folder layout and the build/run commands.

Every one of the eight blocks below is just a JSON object inside your `model.json`. None of
them require you to write Java or HTML — except the one custom capability example at the end,
which is deliberately the smallest possible Java a person can write.

---

## 1. Concept — the shape of a "thing" your app stores

### What it is

A Concept is a category of record — the NPDev word for what a database calls a *table* and
what code calls a *class*. `User`, `Invoice`, `Product`, `Note` are all concepts. Every
concept is stored, gets a REST CRUD API automatically, and (unless you turn it off) gets a
web page automatically too.

### Anatomy

```jsonc
{
  "name": "Product",                  // REQUIRED. Its name — also the table name.
  "extends": "BaseEntity",            // optional — inherit fields from another concept
  "fields": [ /* at least 1, REQUIRED */ ],
  "invariants": [ /* optional validation rules */ ],
  "events": [ /* optional, concept-scoped events */ ],
  "lifecycle": { /* optional state machine, see below */ },
  "ui": { "label": "Product" }        // optional — how it's labeled in the web UI
}
```

A **field** is the smallest unit — one column:

```jsonc
{
  "name": "unitPrice",                // REQUIRED
  "type": "long",                     // REQUIRED — see the type list below
  "id": false,                        // true marks this field as the primary key
  "required": true,                   // boolean, or an array of field names for conditional requirement
  "unique": true,                     // no two rows may share this value
  "minLength": 1, "maxLength": 200,   // string length bounds
  "default": "…",                     // a fixed default value
  "defaultExpression": "…",           // a computed default (an expression string)
  "derivedExpression": "…",           // always recomputed from other fields, never stored by the user
  "ui": { "label": "Unit price" }
}
```

**Field types you can use:**
`string`, `uuid`, `int`, `integer`, `long`, `boolean`, `date`, `datetime`, `enum`,
`reference` (→ see §7), `object` (→ needs `properties`), `array` (→ needs `items`).

- `type: "enum"` needs `enumValues: ["A", "B", ...]`.
- `type: "object"` needs a nested `properties` block — a mini-concept embedded in a field
  (e.g. an `emergencyContact` field with its own `name`/`phone` sub-fields).
- `type: "array"` needs `items: { "type": "..." }` — a repeatable list on one record.

**Invariants** are the rules a record must satisfy before it can be saved. Two shapes:

```jsonc
{ "name": "PositiveUnitPrice", "type": "expression", "expression": "unitPrice > 0" }
{ "name": "EmailUnique", "type": "unique", "fields": ["email"] }
```

An expression invariant is a small boolean formula over the concept's own fields. A
`unique`-type invariant enforces that no two records share the listed field's value.

**Lifecycle** (optional) turns a field into a state machine — e.g. an `Invoice.status` that
can only move `DRAFT → ISSUED → PAID`, never backwards:

```jsonc
"lifecycle": {
  "statusField": "status",
  "states": [
    { "value": "DRAFT", "label": "Draft", "initial": true },
    { "value": "ISSUED", "label": "Issued" },
    { "value": "PAID", "label": "Paid", "terminal": true }
  ],
  "transitions": [
    { "from": "DRAFT", "to": "ISSUED" },
    { "from": "ISSUED", "to": "PAID" }
  ]
}
```

### Real example (from `simple-user-registry-inmemory`)

```json
{
  "name": "User",
  "ui": { "label": "User" },
  "fields": [
    { "name": "id", "type": "uuid", "id": true, "required": true },
    { "name": "name", "type": "string", "required": true, "ui": { "label": "Name" } },
    { "name": "email", "type": "string", "required": true, "ui": { "label": "Email" } },
    { "name": "active", "type": "boolean", "required": true, "ui": { "label": "Active" } }
  ],
  "invariants": [
    { "name": "EmailRequired", "expr": "email != null && email != ''" },
    { "name": "NameRequired", "expr": "name != null && name != ''" },
    { "name": "EmailUnique", "type": "unique", "fields": ["email"] }
  ]
}
```

### Gotchas

- Every JSON object in NPDev is validated strictly (`additionalProperties: false`) — a typo'd
  field name fails generation, it doesn't get silently ignored.
- Exactly one field should be marked `"id": true` (or you get the default synthetic `id`
  behavior most samples use — a plain `uuid` field named `id`).

---

## 2. Flow — an action your app can perform, callable from outside

### What it is

A Flow is a named sequence of steps that runs when someone calls
`POST /api/flows/<FlowName>/execute` (with header `X-Api-Key: <your key>`). It's the "verb"
layer on top of your concepts — "create a user", "issue an invoice", "submit a ticket". Every
flow is public API automatically; there's no extra wiring to expose it.

### Anatomy

```jsonc
{
  "name": "IssueInvoice",                        // REQUIRED
  "concept": "Invoice",                          // optional — the concept this flow is "about"
  "input": { "concept": "Invoice", "mode": "update" }, // optional — shapes the expected request body
  "steps": [ /* REQUIRED, at least 1 (unless specializes+hooks) */ ]
}
```

**Step types** — each step does one thing, and steps run top to bottom:

| Step `type` | What it does |
| --- | --- |
| `validate` / `enforceInvariants` | Checks the invariants you name; stops the flow with an error if any fail. |
| `capabilityCall` (`cap`+`op`, or `capability`+`operation`) | Calls a capability operation — save something, send a notification, run custom logic. |
| `createConcept` / `updateConcept` | Persists a concept record (the "obvious" built-in way to save data, simpler than calling the `persistence` capability yourself). |
| `emitEvent` | Broadcasts an event with data taken `from` a variable. |
| `scheduleEvent` | Same as `emitEvent`, but delayed (`delaySeconds`/`delayMinutes`/`delayMs`). |
| `if` / `branch` | Conditional: `condition`, then `then: [...]`, optional `else: [...]`. |
| `waitForEvent` (aka `awaitEvent`) | Pauses the flow until a matching event arrives — see §5/§6. |
| `generatedAction` | Invokes a specific generated action by name. |
| `return` | Ends the flow, producing `value` as the result. |

Steps pass data to each other through simple named variables, conventionally prefixed with
`$`: `$input` is always the incoming request body; a step can write `out: "$saved"` and a
later step can read `"$saved"`.

### Real example (from `simple-user-registry-inmemory`)

```json
{
  "name": "CreateUser",
  "input": { "concept": "User", "mode": "create" },
  "steps": [
    { "name": "validate-user", "type": "enforceInvariants", "scope": "User",
      "invariants": ["EmailRequired", "NameRequired", "EmailUnique"] },
    { "name": "save-user", "type": "capabilityCall", "cap": "persistence", "op": "save",
      "args": ["$input"], "out": "$saved" },
    { "name": "emit-user-created", "type": "emitEvent", "event": "UserCreated", "from": "$saved" },
    { "name": "return-user", "type": "return", "value": "$saved" }
  ]
}
```

Read top to bottom: *validate the incoming data → save it → announce that it happened → hand
the saved record back to whoever called the flow.*

### Gotchas

- The response body wraps your `return` value inside `.output` — expect
  `{ "output": { "id": "...", "name": "...", ... } }`, not the bare record.
- Every call needs the `X-Api-Key` header. Its value is generated per app at first launch and
  printed once to the console (also saved to `secrets\api-key.env` inside the generated app) --
  `config.json`'s `trialDefaults.apiKey` is only a pre-generation placeholder, not a working
  credential once the app has actually been launched.

**Going deeper:** `waitForEvent` above is the entry point to a full durable workflow engine — a flow
paused on it survives a JVM restart and resumes exactly where it left off, with event correlation,
compensation on failure, and resumable loops. See `docs/FLOWS.md` for the complete mechanics.

---

## 3. Capability — a verb your app needs but doesn't implement itself

### What it is

A Capability is an abstract operation your flows can call without caring how it's actually
done — "save this", "send a notification", "score this ticket". NPDev ships several
capabilities for free (you just *bind* them to a ready-made implementation); when the
built-in ones aren't enough, you can plug in your **own** implementation written in plain
Java, called a **custom capability**.

### Anatomy

Declaring a capability's *interface* (what operations it offers):

```jsonc
{ "name": "persistence", "type": "PersistenceCapability", "operations": ["save", "findById", "unique"] }
```

Wiring it to a real implementation (an **adapter**):

```jsonc
{ "capability": "persistence", "adapter": "repository" }
```

**Common built-in adapters** you'll see in real apps: `repository` (persistence to the
app's own database), `inproc` (an in-process event bus for `eventBus`), `notification-inproc`
(an in-process notification sink).

**Custom capability** — when you need logic no built-in adapter provides:

```jsonc
"customCapabilities": [
  { "name": "triageAssistant", "type": "TriageAssistantCapability", "operations": ["score"] }
],
"bindings": [
  { "capability": "triageAssistant", "adapter": "plugin:java-source" }
]
```

The `plugin:java-source` adapter means: *"the implementation is a small Java class I'm
supplying myself."* You put it under
`definition\capabilities\<name>\src\main\java\...\<Name>.java`, plus a
`capability.plugin.json` descriptor telling the generator which class/method to call:

```json
{
  "capability": "triageAssistant",
  "capabilityType": "TriageAssistantCapability",
  "adapterId": "plugin:java-source",
  "implementation": {
    "kind": "javaSource",
    "sourceRoot": "capabilities/triageAssistant/src/main/java",
    "mainClass": "com.claude.capabilities.triage.TriageAssistantCapability",
    "factory": { "kind": "defaultConstructor" }
  },
  "operationBindings": [
    { "operation": "score", "method": "score",
      "input": { "mode": "firstArgument", "expectedType": "java.util.Map" },
      "output": { "mode": "returnValue", "expectedType": "java.util.Map" } }
  ]
}
```

And the Java itself (this is the *entire* file — no framework boilerplate, no annotations):

```java
package com.claude.capabilities.triage;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TriageAssistantCapability {
    public Map<String, Object> score(Map<String, Object> ticket) {
        String subject = String.valueOf(ticket.get("subject"));
        int urgency = subject.toLowerCase().contains("down") ? 80 : 20;
        String recommendedPriority = urgency >= 70 ? "High" : "Normal";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triageScore", urgency);
        result.put("recommendedPriority", recommendedPriority);
        return result;
    }
}
```

Your flow then calls it exactly like any built-in capability:

```json
{ "name": "score-ticket", "type": "capabilityCall", "cap": "triageAssistant", "op": "score",
  "args": ["$input"], "out": "$triage" }
```

### Gotchas

- The generator compiles your Java straight into the generated application — if it doesn't
  compile, the whole app build fails. Keep custom capabilities small and dependency-free.
- A capability is an *interface*; the adapter is the *implementation*. You can swap adapters
  (e.g., move `persistence` from an in-memory adapter to a Postgres-backed one) without
  touching your flows, because flows only ever call the capability by name.

---

## 4. Panel — a hand-designed screen in the web UI

### What it is

A Panel is a screen you design yourself: which query feeds it, how it's laid out, which
fields show, and which buttons do what. **You usually don't need panels at all** — every
concept gets a working generic table+form page for free (see the manual, Level 2). Reach for
a Panel only when you want something that doesn't look like a generic grid: a dashboard, a
queue with specific columns, a console mixing several data sources.

### Anatomy

```jsonc
{
  "name": "InvoiceConsolePanel",             // REQUIRED
  "route": "/invoices/console",              // REQUIRED — the URL path for this screen
  "title": "Invoice console",
  "dataSources": [                           // named queries this panel pulls data from
    { "name": "pendingInvoices", "query": "PendingInvoices" }
  ],
  "layout": { "type": "table", "fields": ["invoiceNumber", "status", "totalAmount"] },
  "fieldBindings": [                         // maps a displayed column to a data-source field
    { "field": "invoiceNumber", "source": "pendingInvoices.invoiceNumber" }
  ],
  "actions": [                                // buttons on the panel
    { "name": "IssueInvoice", "binding": "flow", "flow": "IssueInvoice", "label": "Issue invoice" }
  ]
}
```

`layout.type` can be `form`, `table`, `detail`, `dashboard`, `stack`, or `grid`. An action's
`binding` says what happens on click: `flow` (call a Flow), `procedure` (call a Procedure —
see §8), `conceptQuery` (re-run a query), or `conceptMutation` (a direct create/update/delete).

### Real example (from `invoice-bonds-demo`) — two panels, two different action bindings

```json
{
  "name": "UserAdminPanel",
  "route": "/users/admin",
  "title": "User administration",
  "dataSources": [ { "name": "activeUsers", "query": "ActiveUsers" } ],
  "layout": { "type": "table", "fields": ["email", "displayName", "status"] },
  "fieldBindings": [
    { "field": "email", "source": "activeUsers.email" },
    { "field": "displayName", "source": "activeUsers.displayName" },
    { "field": "status", "source": "activeUsers.status" }
  ],
  "actions": [
    { "name": "RegisterUser", "binding": "procedure", "procedure": "RegisterUserProcedure", "label": "Register user" }
  ]
}
```

Note the button calls a **procedure**, not a flow — because `RegisterUserProcedure` is
transactional server-side logic that isn't meant to be its own public REST endpoint (see §8).
The sibling `InvoiceConsolePanel` above binds its button to a **flow** instead, because
`IssueInvoice` *is* meant to be independently callable over REST too.

### Gotchas

- A panel's `route` must be unique across the app.
- Panels are additive: authoring one doesn't remove the automatic generic page for that
  concept — you end up with both, at different URLs.

---

## 5. Event — an announcement your app broadcasts

### What it is

An Event is a fact that something happened, broadcast for anyone interested to react to.
Nobody "calls" an event the way you call a flow — a flow **emits** it, and zero or more
**orchestration rules** (§6) or paused **flows** (`waitForEvent`, §2) may be listening.

### Anatomy

```jsonc
{ "name": "InvoiceIssued", "payload": ["id", "invoiceNumber"] }
```

`payload` lists which fields travel with the event — either bare names (copied from whatever
record emitted it) or full field definitions (`{ "name": "id", "type": "uuid" }`) when you
want to be explicit about type.

A flow step broadcasts one with:

```jsonc
{ "name": "announce", "type": "emitEvent", "event": "InvoiceIssued", "from": "$issued" }
```

### Real examples

- `UserCreated` (`simple-user-registry-inmemory`) — fired right after a user is saved; nothing
  currently listens to it in that sample, but it's there for future orchestration or for
  auditing/trace purposes (every event is recorded).
- `InvoiceIssued` (`invoice-bonds-demo`) — fired by the `IssueInvoice` flow, and consumed by
  the `NotifyOnInvoiceIssued` orchestration rule (§6).
- `TicketResolved` (`Claude` Support Desk) — fired independently, hours after a ticket was
  submitted, and it's what wakes up the `SubmitTicket` flow's `waitForEvent` step (§2), matched
  by a correlation id so the *right* paused flow instance resumes.

### Gotchas

- Declaring an event that nothing ever emits, or emitting one nothing ever listens to, is
  perfectly valid — it's just inert. NPDev doesn't require a matching listener to exist.
- Events are the *only* mechanism for one flow to trigger behavior in another part of the app
  without calling it directly — that indirection is the whole point (§6 explains why).

---

## 6. Orchestration rule — automation with no explicit caller

### What it is

An Orchestration rule says: *"whenever event X happens, automatically do Y."* Nobody has to
remember to trigger it — it's not called, it *reacts*. This is how you model "and then also
notify someone" or "and then also create a follow-up record" without cluttering the original
flow with unrelated concerns.

Contrast with a Flow: a Flow is something a *client* decides to call. An Orchestration rule is
something the *platform* decides to run, because a condition (an event) became true.

### Anatomy

```jsonc
{
  "name": "NotifyOnInvoiceIssued",
  "trigger": { "type": "event", "event": "InvoiceIssued" },
  "action": {
    "type": "create",                          // create | callCapability | scheduleEvent
    "concept": "InvoiceNotification",
    "map": { "invoiceId": "id", "message": "invoiceNumber" }   // target field -> source event field
  }
}
```

`action.type` can be:
- `create` — make a new record of some concept, populated from the event's payload via `map`
  (or `fieldMap`).
- `callCapability` — call a capability operation in reaction to the event.
- `scheduleEvent` — chain into emitting a further (possibly delayed) event.

### Real example (from `invoice-bonds-demo`)

```json
{
  "name": "NotifyOnInvoiceIssued",
  "trigger": { "type": "event", "event": "InvoiceIssued" },
  "action": {
    "type": "create",
    "concept": "InvoiceNotification",
    "map": { "invoiceId": "id", "message": "invoiceNumber" }
  }
}
```

The `IssueInvoice` flow (§2) only knows how to mark an invoice issued and emit `InvoiceIssued`
— it has **no idea** a notification record gets created afterwards. That's deliberate: the
flow's job stays small and focused, and the "and also notify" behavior lives entirely in this
one rule. Delete the rule, and `IssueInvoice` keeps working exactly the same, just without the
side effect.

### Gotchas

- One event can have any number of orchestration rules reacting to it — they all fire.
- Because reactions are implicit, it's worth keeping orchestration rule names very
  descriptive (`NotifyOnInvoiceIssued`, not `Rule1`) — they're the only place this behavior is
  documented.

---

## 7. Reference / bond — a pointer from one concept to another

### What it is

A Reference (NPDev calls the underlying design a **bond**) is a link from a field on one
concept to another concept's record — the same idea as a foreign key in a traditional
database, but declared once in your `model.json` and enforced everywhere: in the database
schema (a real `FOREIGN KEY` constraint), in validation, and in what happens when the
referenced record is deleted.

### Anatomy

```jsonc
{
  "name": "userId",
  "type": "reference",
  "required": true,
  "reference": {
    "target": "User",          // REQUIRED — which concept this points at
    "via": "id",                // optional, default "id" — which field on the target it points at
    "onDelete": "restrict"       // restrict (default) | cascade | nullify
  }
}
```

**`onDelete` behavior, in plain terms:**

| `onDelete` | What happens when the target record is deleted |
| --- | --- |
| `restrict` (default) | The delete is **blocked** while any record still points at it — the safe default. |
| `cascade` | The pointing record is **deleted too**, automatically. |
| `nullify` | The pointing field is **cleared** (set to nothing), the pointing record survives. |

Most of the time you point at the target's own `id`. Sometimes you want to point at a
different unique field instead — a **natural key**. For that, the *target* concept marks that
field as an anchor:

```jsonc
{ "name": "sku", "type": "string", "unique": true, "connectable": "anchor" }
```

...and the pointing field says `via: "sku"` instead of the default `via: "id"`.

### Real example (from `invoice-bonds-demo`) — four references, three different shapes

```json
{
  "name": "Product",
  "fields": [
    { "name": "id", "type": "uuid", "id": true, "required": true },
    { "name": "sku", "type": "string", "required": true, "unique": true, "connectable": "anchor" }
  ]
},
{
  "name": "Invoice",
  "fields": [
    { "name": "userId", "type": "reference", "required": true,
      "reference": { "target": "User", "onDelete": "restrict" } },
    { "name": "approvedByUserId", "type": "reference", "required": false,
      "reference": { "target": "User", "onDelete": "nullify" } }
  ]
},
{
  "name": "InvoiceItem",
  "fields": [
    { "name": "invoiceId", "type": "reference", "required": true,
      "reference": { "target": "Invoice", "onDelete": "cascade" } },
    { "name": "productSku", "type": "reference", "required": true,
      "reference": { "target": "Product", "via": "sku", "onDelete": "restrict" } }
  ]
}
```

Reading each one:
- `Invoice.userId → User` (default `via: id`, `restrict`): you cannot delete a `User` while
  any `Invoice` still points at them.
- `Invoice.approvedByUserId → User` (`nullify`): if the approving user is deleted, the
  invoice survives — its "approved by" field is simply cleared.
- `InvoiceItem.invoiceId → Invoice` (`cascade`): deleting an invoice deletes its line items
  too — they can't meaningfully exist without their parent.
- `InvoiceItem.productSku → Product` **via the `sku` natural key**, not `Product.id` — because
  `Product.sku` was marked `connectable: anchor`.

### Gotchas

- The target concept's anchor field must be `unique: true` (plus `connectable: anchor` if
  it's not the plain `id`) — a reference can't point at a field that might repeat.
- `nullify` is rejected if the referencing field is itself `required` — you can't promise
  "always points at something" and "gets cleared on delete" at the same time.
- References are same-model only in the current version — you can't point at a concept
  defined in a different app.

---

## 8. Procedure — server-side logic that isn't its own public endpoint

### What it is

A Procedure is a small script of steps, like a Flow, but it is **not** automatically
exposed as `POST /api/flows/...`. Instead, it's meant to be called from a Panel action
(`"binding": "procedure"`), or from another procedure. Reach for a procedure instead of a flow
when the logic is a supporting operation for a screen — "read this list for the admin page",
"save this record through the full governed path" — rather than a standalone action you want
other systems to call directly over REST.

### Anatomy

```jsonc
{
  "name": "RegisterUserProcedure",
  "parameters": [ { "name": "id", "type": "uuid", "required": true } ],
  "locals": [ /* optional working variables, alias: "variables" */ ],
  "steps": [ /* REQUIRED, at least 1 */ ],
  "returns": { /* optional shape of the result */ },
  "tracePolicy": "detailed"     // none | summary | detailed
}
```

**Step types available inside a procedure** (a superset of what a flow can do, oriented
around data access): `assign`, `mapValue`, `condition`/`if`, `loop`/`forEach`, `conceptQuery`,
`readConcept`, `listConcepts`, `runQuery`, `conceptCreate`/`conceptUpdate`/`saveConcept`,
`conceptDelete`/`deleteConcept`, `procedureCall`/`callProcedure`, `capabilityCall`,
`eventPublish`/`publishEvent`, `return`.

`tracePolicy` controls how much execution detail gets recorded (useful while you're building
and debugging). Audit logging is NOT a per-procedure setting — every concept create, update,
delete and restore reached through the governed path (`saveConcept`/`deleteConcept` steps
included) is logged automatically, with a before/after field diff, no matter what the
procedure declares.

### Real examples (from `invoice-bonds-demo`) — a read procedure and a write procedure

A **read-oriented** procedure, running a governed query:

```json
{
  "name": "ListActiveUsersProcedure",
  "description": "Read-kind procedure: returns active users via the governed query path.",
  "steps": [
    { "name": "run-active-users", "type": "runQuery", "concept": "User", "query": "ActiveUsers",
      "target": "activeUsers", "trace": true },
    { "name": "return-active-users", "type": "return", "value": "$activeUsers", "trace": true }
  ],
  "tracePolicy": "detailed"
}
```

A **write-oriented** procedure, saving through the full governed path (permissions, tenant
isolation, audit — the same enforcement a business CRUD write gets):

```json
{
  "name": "RegisterUserProcedure",
  "description": "Write-kind procedure: saves a User through the Concept Gateway and returns the governed record.",
  "parameters": [ { "name": "id", "type": "uuid", "required": true } ],
  "steps": [
    { "name": "save-user-through-gateway", "type": "saveConcept", "concept": "User", "id": "$id",
      "data": { "input": "$input" }, "target": "savedUser", "trace": true, "audit": true },
    { "name": "return-saved-user", "type": "return", "value": "$savedUser", "trace": true }
  ],
  "tracePolicy": "detailed"
}
```

Both procedures are wired up as the click-action of a Panel button (§4,
`UserAdminPanel`/`RegisterUser`) — the person using the web page never sees a REST endpoint,
just a button that runs this logic.

### Gotchas

- A procedure has no REST route of its own — if you need something callable directly by an
  external client, use a Flow instead.
- `trace` can be set per-step (as shown above) in addition to the procedure-level
  `tracePolicy` — the per-step flag is for singling out the operations that matter most inside
  an otherwise ordinary procedure. The per-step `audit` flag shown on `save-user-through-gateway`
  above is a leftover marker with no effect — audit logging is unconditional, not opt-in.

---

## 9. Pack, context, fragment — three ways to split a model, and which to reach for

These three all take content out of one `model.json` and put it somewhere else, and until now
nothing said how they differ. They are not interchangeable:

| | `packs[]` | `contexts[]` | `fragments[]` |
|---|---|---|---|
| What it is | a distributable **Library** | an in-project **Module** | a file split |
| Namespaced | yes (`pack::X`, or an `as` alias) | yes (`context::X`) | no — flat merge |
| Versioned | semver + `npdev.lock` | no | no |
| Can live elsewhere | yes (`git+https`, `oci`) | no | no |
| Boundary enforcement | `requires` / `provides` | `imports[]`, acyclic, **compile error** | none |
| Physical DB isolation | no | `physicallyIsolate` | no |

**If you are organising ONE project, you want `contexts[]`.** It is the module mechanism, and
the reason is the `imports[]` column: a context may only reference another context it has
declared, the import graph must be acyclic, and a violation is a compile error rather than a
convention. That is what makes a boundary a boundary. `physicallyIsolate` additionally puts a
context's tables in their own physical database.

**If you are shipping something for OTHER projects to install, you want `packs[]`.** A pack is
versioned, resolvable from a remote coordinate, and declares what it `requires` and `provides`.

**`fragments[]` is not a boundary at all** — it is a file split. Its content merges flat into
the model with no namespace and no rules about who may reference what. Reach for it when one
`model.json` has simply become inconvenient to scroll, not when you want separation.

A pack or context contributes its members under a qualifier, and references *inside* the
contribution are rewritten to match: a pack-contributed panel naming `Label` gets
`labeling::Label`, because that is what the concept is called once composed.

## How the pieces fit together

```
Concept   — the data (a table + its fields + its rules)
   │
   ├── Reference/bond  — links one Concept's field to another Concept
   │
Flow       — a public action a client calls, made of steps
   │
   ├── Capability (capabilityCall step) — a verb the flow delegates to
   │
   └── Event (emitEvent step) — a broadcast the flow makes, with no idea who's listening
             │
             └── Orchestration rule — reacts to the event, runs its own action automatically

Panel     — a hand-designed screen; its buttons call either a Flow or a Procedure
Procedure — server-side logic for a Panel button (or another Procedure) to call,
            not its own public REST endpoint
```

Everything above is authored the same way: JSON inside `model.json`, validated strictly,
regenerated with the same `Build-AppGenApp.ps1` → `_ops\Build-App.ps1` →
`_ops\Start-App.ps1` sequence from the manual. Nothing here requires touching the NPDev
engine itself.
