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
- a `persistence` capability, or `persistence`/`eventBus` bindings (derived; `repository` and
  `inproc` are the only values anyway);
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
5. Required root fields: `dslVersion` (`"1.0.0"`), `version`, `namespace` (or `model`), non-empty `concepts`.

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
| Field | `concepts[].fields[]` | One column: `name`, `type`, `required`, `unique`, `id`, `min`/`max`/`maxLength`, `enumValues`, `reference`. |
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
  `where`, `orderBy[]` and `groupBy[]` entries are plain strings.
- **Reaction to a status change:** the lifecycle transition names an `event`, `events[]` declares it with a
  `payload`, and an orchestration creates a record from it:
  `"orchestrations":[{"name":"LogReturn","trigger":{"type":"event","event":"LoanReturned"},"actions":[{"type":"create","concept":"ReturnLog","map":{"loan":"$event.id"}}]}]`.

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
