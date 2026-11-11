# NPDev authoring contract (for an AI author)

Terse rules for a valid NPDev `model.json`. After drafting, run `npdev validate model <path>` (or
the `npdev_validate` MCP tool) and fix every `error`; each diagnostic has `path` and `suggestedFix`.
`npdev normalize desire-paths <path> --output <path>` rewrites commonly guessed non-canonical
shapes first and reports each fix -- read the report; it is a list of mistakes.

## Ask before you write

The user has an intent, not a schema. In their vocabulary, settle these eight first:
1. What things are tracked? → concepts  2. What is known about each? → fields
3. How do they connect (one or many)? → references  4. Who uses it, who sees what? → roles
5. What happens automatically (notify, approve, call out)? → flows, events
6. Stages (draft → done)? → lifecycle  7. What must never happen? → invariants
8. Hundreds or millions of records? → H2 vs Postgres

**Start smallest:** the smallest model that runs; add only on request. Every speculative
concept is one the user must understand to reject.

## The minimal model (CRUD is automatic)

A model of only `concepts` + `fields` is a complete app: every concept gets REST
(`/api/concepts/<table>`: list/get/create/update/delete), UI list/detail/forms, and server-side
checks of `required`, `unique`, `min`/`max`/`maxLength`, enum, reference, `lifecycle` and
`invariants`. **Do not write:**
- create/update/delete flows or `invariantCheck` steps that only save a record;
- a `persistence` capability, or `persistence`/`eventBus` bindings, **while the model has no
  flow** (derived; `repository` and `inproc` are the only values anyway). A flow that saves
  needs them: see "Reuse and extend";
- an invariant restating a field flag (`x != null && x != ''` → `required`; one-field unique →
  `unique: true`; `x >= 0` → `"min": 0`). Invariants are for cross-field rules;
- `ui.label` equal to the humanized name (`dueDate` → "Due Date"). `missing_*_label` warnings
  are cosmetic.

Write a flow only for work beyond saving: notify, call out, branch, wait for an event. Declare a
capability + binding only for what a flow calls (e.g. `notification`→`notification-inproc`,
`webhook`→`webhook-inproc`). To publish an event on save without a flow:
`concepts[].events: [{"name":"XCreated","mode":"create"}]`.

## Non-negotiables

1. **Every object is closed (`additionalProperties: false`)** except the free-form `metadata` maps on
   panels and procedures. An invented key fails generation; it is not ignored.
2. **Exactly one field per concept has `"id": true`**; conventionally
   `{"name":"id","type":"uuid","id":true,"required":true}`.
3. **`reference.target` names a concept in the composed model** (own or from a pack/fragment).
4. **Root uses `concepts`, not `entities`** (rejected legacy shape).
5. Required root fields: `version`, `namespace` (or `model`), non-empty `concepts`. `dslVersion` is optional (omit it, or write `"1.0.0"`; any other value is an error).

## Renaming: `renamedFrom` is mandatory

NPDev cannot infer a rename: a new name on an app that has already run is a dropped column plus a
new one, which NPDev refuses as destructive. **User says "rename X to Y" → always declare it:**
`{ "name": "mobile", "renamedFrom": "phone", "type": "string" }` (fields and concepts; not needed
for a never-run model). Removing a field or concept is also destructive; never remove one because
it "looks unused" -- ask.

## Building blocks

| Block | Root key | One-line |
| --- | --- | --- |
| Concept | `concepts[]` | A stored record type: `name`, `fields[]`, optional `invariants`, `lifecycle`, `events`, `ui`. |
| Field | `concepts[].fields[]` | One column: `name`, `type`, `required`, `unique`, `id`, `min`/`max`/`maxLength`, `enumValues`, `reference`. A calculated column is the field's `derivedExpression` (string, e.g. `"quantity * unitPrice"`); `computed` is NOT a field key (it belongs to queries, as `{name, expression}` output columns). |
| Role | `roles[]` | `concepts: {"X": ["read","create","update","delete"]}`; `grants` only for platform permissions. |
| Flow | `flows[]` | A non-CRUD action (`POST /api/flows/<Name>/execute`) made of `steps[]`. |
| Capability | `capabilities[]` + `bindings[]` | A verb a flow calls (notification, webhook, http...), bound to an adapter. |
| Event | `events[]` (or nested in a concept) | A broadcast fact; emitted by a flow step or a lifecycle transition. |
| Orchestration | `orchestrations[]` | "when event X → do Y"; `trigger` + `actions[]`. |
| Query / Panel / Procedure | `queries[]` / `panels[]` / `procedures[]` | A named filtered list; a hand-designed screen; server logic for a panel button. Only when the generic UI is not enough. |

## Totals, reports and reactions

Exact shapes (verified); copy them, do not invent keys.

- **Calculated value on a row:** a field with `derivedExpression`, e.g.
  `{"name":"lineTotal","type":"decimal","derivedExpression":"quantity * unitPrice"}` (`+ - * /`,
  field names, `concat`, `coalesce`). Never an invariant: that only refuses writes, so the client would have to send the total.
- **Stored total over child rows** (on the parent concept, which also declares the numeric field):
  `"rollups":[{"field":"totalDonated","from":"Donation","via":"donor","fn":"sum","of":"amount"}]`.
  `via` = the child's reference field to this concept; `fn: count` takes no `of`.
- **List or report:** `"queries":[{"name":"LowStock","concept":"Product","where":"stock < 5","orderBy":["stock"]}]`,
  grouped: `{"name":"BySeller","concept":"Sale","groupBy":["seller"],"aggregates":[{"name":"total","fn":"sum","field":"amount"}]}`.
  `where` and `orderBy[]` entries are plain strings. A `groupBy[]` entry is a field name string OR, for a
  date/datetime field, `{"field":"createdAt","bucket":"month"}` (`bucket` required: day|week|month|quarter|year).
- **Status change through a flow** (only if the flow does more than the REST update; `updateConcept` takes the
  same `scope`/`input`/`output` as `createConcept`; the caller sends `{"id":...,"status":"CLOSED"}`):
  `"flows":[{"name":"CloseTicket","concept":"Ticket","input":{"concept":"Ticket","mode":"update"},"steps":[{"name":"save","type":"updateConcept","scope":"Ticket","input":"$input","output":"$saved"},{"name":"done","type":"return","value":"$saved"}]}]`.
  Flows and procedures use DIFFERENT `steps[].type` vocabularies: flow = invariantCheck|capabilityCall|generatedAction|emitEvent|
  scheduleEvent|return|branch|awaitEvent|createConcept|updateConcept|map|forEach|callProcedure; procedure = assign|condition|
  conceptQuery|readConcept|conceptCreate|conceptUpdate|conceptDelete|callProcedure|capabilityCall|eventPublish|computeValue|return|... (full enum in the value reference).
- **Lifecycle** (on the concept): `"lifecycle":{"statusField":"status","states":[{"value":"OPEN","initial":true},{"value":"CLOSED","terminal":true}],
  "transitions":[{"from":"OPEN","to":"CLOSED","event":"TicketClosed"}]}` (`from`/`to` are single states; `initial`/`terminal` live on the state, `event` on the transition).
- **Reaction to a status change:** the lifecycle transition names an `event`, `events[]` declares it with a
  `payload`, and an orchestration creates a record from it:
  `"orchestrations":[{"name":"LogReturn","trigger":{"type":"event","event":"LoanReturned"},"actions":[{"type":"create","concept":"ReturnLog","map":{"loan":"$event.id"}}]}]`.

## Reuse and extend: packs, fragments, your own code, outside APIs

**A flow that saves needs persistence declared.** `createConcept`/`updateConcept` steps fail at
app start (validation still passes) unless the model has
`"capabilities":[{"name":"persistence","type":"PersistenceCapability","operations":["save","unique","findById"]}]`
and `"bindings":[{"capability":"persistence","adapter":"repository"}]`. Pure CRUD needs neither.

**Packs: reuse a published concept set instead of inventing it.** Add to `packs[]` (no other keys
than `from`, optional `as`); concepts of the pack are named `<as or id>::<Concept>`, e.g.
`"reference":{"target":"identity::User"}`. The person then runs `npdev pack update --model <path>
--allow-unsigned`; you only write the entry:
`{"from":"git+https://github.com/MarceloGiazzon/NPR.git//packs/<id>@<id>-<version>"}`.
Published (id version): identity 1.4.0 (users, roles) · user 1.0.0 (password bond to identity::User) ·
contacts 1.0.0 (organizations, people, addresses) · sales 1.0.0 (customers, price list, orders, invoices) ·
purchasing 1.0.0 (suppliers, items, purchase orders, bills) · inventory 1.0.0 (warehouses, products,
stock movements) · approvals 1.0.0 (request/approve tasks) · leave 1.0.0 (leave requests) · helpdesk 1.0.0
(tickets, comments) · notification 1.0.0 (outgoing-notification log, `mailer.send` contract) ·
tracing 1.0.2 (trace viewer) · workspace 1.1.0 (menus, scoped properties) · project-tracker-demo 1.0.0 (demo).
Role-based packs name roles your app must bind to its own: if unsure, ask instead of guessing.
No pack exists for payments, geo or ride-hailing: model those yourself.

**Fragments: split a big model.** `"fragments":[{"$ref":"fragments/billing.json"}]` (relative `.json` path,
no `..`). A fragment file is a JSON object holding any of the model's arrays (`concepts`, `flows`,
`capabilities`, `bindings`, `roles`, `queries`, `procedures`...). Deliver each as its own file.
**In a chat reply that must be ONE `model.json`, do not use fragments**: keep everything in the model.

**Query rows, then run your logic on them.** A flow has no query step: use a procedure and `callProcedure` it.
Procedure steps: `{"type":"runQuery","query":"AvailableDrivers","concept":"Driver","target":"free"}`,
`{"type":"mapList","items":"$free","as":"d","select":{"id":"$d.id","lat":"$d.lat"},"target":"rows"}`,
`{"type":"capabilityCall","capability":"geo","operation":"findClosest","args":{"ride":"$input","drivers":"$rows"},"target":"best"}`,
`{"type":"if","condition":"$best.found","then":[...],"else":[...]}`,
`{"type":"patchConcept","concept":"Ride","id":"$input.id","set":{"status":"ASSIGNED"},"target":"ride"}`.
The `query` is declared in `queries[]`; the flow step is
`{"type":"callProcedure","procedure":"MatchDriver","input":"$created","output":"$matched"}`.

**Your own code (a custom capability).** For logic no built-in has (distance, pricing, scoring):
`"customCapabilities":[{"name":"geo","type":"GeoCapability","operations":["findClosest"]}]` plus
`"bindings":[{"capability":"geo","adapter":"plugin:java-source"}]`, and two files:
`capabilities/geo/src/main/java/<pkg>/GeoCapability.java` (a plain class with a public no-arg constructor;
one public method per operation taking `Map`/`List` parameters named like the `args` keys and returning
a `Map`; pure computation only: no files, network, threads, reflection) and
`capabilities/geo/capability.plugin.json`:
`{"packageFormatVersion":"1.0","packageId":"<app>-geo-pkg","pluginId":"<app>-geo-plugin","version":"1.0.0","capability":"geo","capabilityType":"GeoCapability","adapterId":"plugin:java-source","implementation":{"kind":"javaSource","sourceRoot":"capabilities/geo/src/main/java","mainClass":"<pkg>.GeoCapability","factory":{"kind":"defaultConstructor"}},"operationBindings":[{"operation":"findClosest","method":"findClosest","input":{"mode":"firstArgument","expectedType":"java.util.Map"},"output":{"mode":"returnValue","expectedType":"java.util.Map"}}],"trust":{"mode":"artifact-local-source","source":"external-artifact-bundle","level":"developer-supplied"},"generation":{"mount":"compileIntoGeneratedApp","emitManifestContribution":true,"emitPackageDescriptor":true,"emitBinding":true}}`.
The app's owner reviews these files before they apply. **In a chat reply that carries only `model.json`
and `npdev-submission.json` you cannot deliver them:** do not write Java; state the need in
`couldNotExpress` and model the rest, so the person can add the code later.

**An outside REST API, no code.** A capability of type `HttpConnector` bound to adapter `http-connector`:
`{"name":"maps","type":"HttpConnector","http":{"baseUrl":"https://maps.example.com/v1","auth":{"type":"apiKeyHeader","name":"X-Key","secretEnvVar":"MAPS_API_KEY"},"timeoutMs":5000,"maxRetries":1},"operations":[{"name":"route","sideEffects":"reads","http":{"method":"GET","path":"/route","query":{"from":"$from"}}}]}`
and `{"capability":"maps","adapter":"http-connector"}`. Secrets are environment-variable names, never values.

## Field types

`string`, `uuid`, `int`/`integer`, `long`, `boolean`, `date`, `datetime`, `enum` (needs
`enumValues`), `reference` (needs `reference.target`), `object` (needs `properties`), `array`
(needs `items`), `decimal`, `file`.

## Gotchas

- **Invariant key:** `"expression"` (`"expr"` is an alias). A multi-field unique invariant is
  `{"type":"unique","fields":[...]}`.
- **`reference.onDelete`:** `restrict` (default), `cascade`, `nullify`; `nullify` is REJECTED on a
  `required` field.
- **Natural-key reference:** the target field is `unique:true` + `connectable:"anchor"`; the
  pointing field sets `reference.via:"<field>"`.
- **A flow needs a concept** (`flow.concept` or `flow.input.concept`); its `return` value comes
  back under `.output`.

## The loop

Existing app? `npdev_inspect_app` first: extend, don't duplicate. Unsure of a key?
`npdev_get_schema`. Draft → `npdev_validate` → fix every `error` → re-validate → `npdev_generate`
only after validate passes.
