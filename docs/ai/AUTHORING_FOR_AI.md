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
- a `persistence` capability, or `persistence`/`eventBus` bindings (derived: a flow with
  `createConcept`/`updateConcept` gets `persistence` bound to `repository` on its own, and
  `repository` and `inproc` are the only values anyway);
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

**A flow that saves needs nothing declared.** A `createConcept`/`updateConcept` step gets the
`persistence` capability and its `repository` binding derived, with the operations the flows
use. Declare them yourself only to bind a different adapter; an explicit declaration wins.

**Packs: reuse a published concept set instead of inventing it.** Add to `packs[]` (no other keys
than `from`, optional `as`); concepts of the pack are named `<as or id>::<Concept>`, e.g.
`"reference":{"target":"identity::User"}`. Import only a pack from the "Pack catalog" list in this
brief (when it has one), copying its `from` coordinate exactly: never invent a pack, a version or a
coordinate. NPDev downloads and locks the pack when it checks your answer (on the command line the
person runs `npdev pack update --model <path>`); you only write the entry. Role-based packs name
roles your app must bind to its own: if unsure, ask instead of guessing. With no catalog list, or
no pack that fits, model the concepts yourself (your own `User`, `Customer`...).
No pack exists for payments or ride-hailing: model those yourself. Distances and "nearest" are built in (see `geo`).

**Fragments: split a big model.** `"fragments":[{"$ref":"fragments/billing.json"}]` (relative `.json` path,
no `..`). A fragment file is a JSON object holding any of the model's arrays (`concepts`, `flows`,
`capabilities`, `bindings`, `roles`, `queries`, `procedures`...). Deliver each as its own file.
**In a chat reply that must be ONE `model.json`, do not use fragments**: keep everything in the model.

**Query rows, then run your logic on them.** A flow has no query step: use a procedure and `callProcedure` it.
Procedure steps: `{"type":"runQuery","query":"AvailableDrivers","concept":"Driver","target":"free"}`,
`{"type":"mapList","items":"$free","as":"d","select":{"id":"$d.id","lat":"$d.lat"},"target":"rows"}`,
`{"type":"capabilityCall","capability":"geo","operation":"closest","args":{"lat":"$input.pickupLat","lng":"$input.pickupLng","candidates":"$rows"},"target":"best"}`,
`{"type":"if","condition":"$best.found","then":[...],"else":[...]}`,
`{"type":"patchConcept","concept":"Ride","id":"$input.id","set":{"driver":"$best.id","status":"ASSIGNED"},"target":"ride"}`.
A query with parameters is run with `{"type":"runQuery","query":"ByCustomer","concept":"Order","params":{"who":"$input.customer"},"target":"rows"}`
(every required parameter must be given; a literal or a `$ref`).
An outside call that may fail as a normal answer (a declined card, a route service down): add `"errorTarget":"chargeError"` to the
`capabilityCall`; on failure the step still succeeds, `target` stays unset and `$chargeError` holds `{code,message,kind,retryable,httpStatus}`;
follow it with an `if` on `$chargeError`. Without `errorTarget` a failed call fails the whole procedure.
The `query` is declared in `queries[]`; the flow step is
`{"type":"callProcedure","procedure":"MatchDriver","input":"$created","output":"$matched"}`.
A computed price is a field with `derivedExpression` (`+ - * /`), e.g. `"300 + 120 * tripDistanceKm"`; `computeValue`
`computeValue` does `add`, `subtract`, `multiply`, `divide` (by zero fails the step), `min`, `max`, `round` (`right` = decimals 0..10),
`addDays`, `daysBetween`, `today`.

**A procedure that must act on rows the caller may not touch** (a rider's request marking a driver busy, writing the driver's login
onto the ride) declares `"runAs":"system"`: its steps, and the procedures it calls, run as the tenant's system principal
(`$user.isSystem`), and the audit records who it acted for. Rules written `... || $user.isSystem` let only it through. Only a flow
or another procedure may call it (not an action); check the state first with an `if` step.

**Distances and nearest (built in, no code): the `geo` capability.** Declare
`{"name":"geo","type":"GeoCapability","operations":["distanceKm","closest"]}` in `capabilities[]` and
`{"capability":"geo","adapter":"geo-inproc"}` in `bindings[]`. Both calls take one object in `args`, plain numbers:
`distanceKm` `{"fromLat":..,"fromLng":..,"toLat":..,"toLng":..}` returns `{distanceKm}`;
`closest` `{"lat":..,"lng":..,"candidates":[{"id":..,"lat":..,"lng":..}],"limit":3,"maxKm":10,"speedKmh":30}`
(`limit`, `maxKm`, `speedKmh`, `excludeIds` optional; `excludeIds` = ids already tried) returns `{found, id, distanceKm, etaMinutes, withinCount, ranked:[{id,distanceKm,etaMinutes}]}`,
nearest first; with no candidate `found` is false (never an error). Straight-line distance, not road distance.

**Your own code (a custom capability).** For logic no built-in has (surge pricing, scoring):
`"customCapabilities":[{"name":"surge","type":"SurgeCapability","operations":["multiplier"]}]` plus
`"bindings":[{"capability":"surge","adapter":"plugin:java-source"}]`, and two files:
`<app>/capabilities/surge/src/main/java/<pkg>/SurgeCapability.java` (a plain class with a public no-arg constructor;
one public method per operation taking `Map`/`List` parameters named like the `args` keys and returning
a `Map`; pure computation only: no files, network, threads, reflection) and
`<app>/capabilities/surge/capability.plugin.json`:
`{"packageFormatVersion":"1.0","packageId":"<app>-surge-pkg","pluginId":"<app>-surge-plugin","version":"1.0.0","capability":"surge","capabilityType":"SurgeCapability","adapterId":"plugin:java-source","implementation":{"kind":"javaSource","sourceRoot":"capabilities/surge/src/main/java","mainClass":"<pkg>.SurgeCapability","factory":{"kind":"defaultConstructor"}},"operationBindings":[{"operation":"multiplier","method":"multiplier","input":{"mode":"firstArgument","expectedType":"java.util.Map"},"output":{"mode":"returnValue","expectedType":"java.util.Map"}}],"trust":{"mode":"artifact-local-source","source":"external-artifact-bundle","level":"developer-supplied"},"generation":{"mount":"compileIntoGeneratedApp","emitManifestContribution":true,"emitPackageDescriptor":true,"emitBinding":true}}`.
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

## Writing a pack

Only when asked for a PACK (a reusable, versioned set of definitions many apps import; not an app). The file is
`pack.json`; concepts, fields, `access`, queries, roles, flows and the rest follow the same contract as `model.json`.
- **In a pack:** concepts (with `access` and lifecycle), queries, roles, domain types, events, flows that use only the
  pack's own concepts, and `customCapabilities` as CONTRACTS (name, inputs, outputs, meaning).
- **Never in a pack:** code; `bindings` to an adapter of one app; `signature`, `migrations`, `firstPublishedVersion`,
  `forkedFrom` (NPDev fills them); keys, passwords, tokens, personal data; references to another app's concepts.
- **Names:** `pack` is EXACTLY the id you were given (`Pack id:` line of the request) (lowercase letters, digits, `-`, `_`). Concept and field names are plain and
  NOT prefixed with the pack id. `version` is `MAJOR.MINOR.PATCH`: a new pack is `1.0.0`; for a new version of an existing
  pack, patch = fix, minor = something added, major = something removed, renamed or made stricter; never lower it.
- **Skeleton:** `{"dslVersion":"1.0.0","pack":"ratings","version":"1.0.0","namespace":"com.example.ratings","description":"Star ratings with a comment.","category":"other","concepts":[{"name":"Rating","fields":[{"name":"id","type":"uuid","id":true,"required":true},{"name":"authorAccount","type":"string","required":true},{"name":"stars","type":"integer","required":true,"min":1,"max":5}],"access":{"read":"authorAccount == $user.id || $user.isSystem","write":"authorAccount == $user.id || $user.isSystem"}}],"queries":[{"name":"RatingsOfAuthor","concept":"Rating","where":"authorAccount == :author","parameters":[{"name":"author","type":"string","required":true}]}]}`
  `category` is one of `security`, `web-ui`, `crypto`, `bots`, `ai-tools`, `finance`, `math`, `other`. A query `where` compares a field to a `:name` parameter, a quoted string or a number; a bare word that is not a field is an error.
- **It must compose:** an app imports it with `"packs":[{"$ref":"packs/<id>/pack.json","as":"x"}]` and still validates.

## The loop

Existing app? `npdev_inspect_app` first: extend, don't duplicate. Unsure of a key?
`npdev_get_schema`. Draft → `npdev_validate` → fix every `error` → re-validate → `npdev_generate`
only after validate passes.
