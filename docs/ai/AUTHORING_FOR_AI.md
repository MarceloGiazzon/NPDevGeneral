# NPDev authoring contract (for an AI author)

Terse, unambiguous rules for generating a valid NPDev `model.json`. This is the stable core an
external AI should keep in context. After drafting, ALWAYS run `npdev validate model <path>
--semantic` (or the `npdev_validate` MCP tool) and fix every `error` diagnostic before generating.

## Non-negotiables

1. **Every object is validated `additionalProperties: false`.** A typo'd or invented key fails
   generation — it is not ignored. Only emit keys defined in the schema for that object.
2. **Exactly one field per concept has `"id": true`** (or use the conventional `{"name":"id",
   "type":"uuid","id":true,"required":true}`).
3. **References are same-model only.** A `reference.target` must name a concept defined in this
   same `model.json`.
4. **Root uses `concepts`, not `entities`.** `entities` is a rejected legacy shape.
5. Required root fields: `dslVersion` (`"1.0.0"`), `version`, `namespace` (or alias `model`),
   and a non-empty `concepts` array.

## The 8 building blocks (what each is)

| Block | Root key | One-line |
| --- | --- | --- |
| Concept | `concepts[]` | A stored record type (table/class): `name`, `fields[]`, optional `invariants`, `lifecycle`, `events`, `ui`. |
| Field | `concepts[].fields[]` | One column: `name`, `type`, `required`, `unique`, `id`, `enumValues`, `reference`. |
| Flow | `flows[]` | A public action (`POST /api/flows/<Name>/execute`), made of `steps[]`. |
| Capability | `capabilities[]` / `customCapabilities[]` + `bindings[]` | An abstract verb a flow calls; bound to an adapter. |
| Panel | `panels[]` | A hand-designed screen; `route`, `dataSources`, `layout`, `actions`. |
| Event | `events[]` (or nested in a concept) | A broadcast fact; emitted by a flow step, reacted to by orchestrations. |
| Orchestration | `orchestrations[]` | "when event X → do Y" automation; `trigger` + `action`. |
| Procedure | `procedures[]` | Server-side logic for a Panel button; not its own REST endpoint. |

## Field types

`string`, `uuid`, `int`/`integer`, `long`, `boolean`, `date`, `datetime`, `enum` (needs
`enumValues`), `reference` (needs `reference.target`), `object` (needs `properties`), `array`
(needs `items`).

## Common gotchas (these are the ones that bite)

- **Invariant expression key:** prefer `"expression"`. `"expr"` is accepted as an alias, but be
  consistent. A `unique` invariant uses `{"type":"unique","fields":[...]}` instead.
- **`reference.onDelete`:** `restrict` (default, blocks delete while referenced), `cascade`
  (deletes the pointing record too), `nullify` (clears the pointing field). `nullify` is REJECTED
  if the pointing field is `required`.
- **Natural-key references:** to point at a non-`id` field, the target field must be
  `unique:true` + `connectable:"anchor"`, and the pointing field sets `reference.via:"<field>"`.
- **Flow response is wrapped:** a `return` step's value comes back under `.output`.
- **A flow needs a concept:** via `flow.concept` or `flow.input.concept`.
- **UI label warning:** fields without `ui.label` produce a `missing_field_label` warning (not an
  error) — add `ui.label` for a clean report.

## The loop (how to author reliably)

```
1. npdev_inspect_app   → does the concept already exist? extend, don't duplicate.
2. npdev_get_schema    → exact keys for the object you're adding.
3. draft model.json    → (optionally constrained by the derived output_config schema).
4. npdev_validate      → typed diagnostics. Fix every `error`. Re-validate.
5. npdev_generate      → real app. Only after validate passes.
```

Treat `npdev_validate` `status:"failed"` as your to-do list: each diagnostic has `path`,
`concept`, `field`, and `suggestedFix`. Apply the fix, re-validate, repeat to zero errors.
