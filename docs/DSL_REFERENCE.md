# NPDev DSL reference

**Generated from `NPDevContract/schemas/model.schema.json` and `FieldWidgetDefaults.java` — do not hand-edit.** Regenerate with `python scripts/docs/generate_dsl_reference.py` after any schema change; the schemas are the source of truth (LNCH-22).

Schema version: `1.0.0`. DSL version: `1.0.0`.

## Root model shape

| Field | Type | Required | Description |
|---|---|---|---|
| `$schema` | `string` |  |  |
| `schemaVersion` | `"1.0.0"` |  |  |
| `dslVersion` | `"1.0.0"` | yes |  |
| `namespace` | `string` |  |  |
| `model` | `string` |  |  |
| `version` | `string` | yes |  |
| `concepts` | `array<concept | localModelRef>` |  |  |
| `domainTypes` | `array<domainType | localModelRef>` |  |  |
| `capabilities` | `array<capability | localModelRef>` |  |  |
| `customCapabilities` | `array<capability | localModelRef>` |  |  |
| `bindings` | `array<binding | localModelRef>` |  |  |
| `events` | `array<event | localModelRef>` |  |  |
| `flows` | `array<flow | localModelRef>` |  |  |
| `orchestrationRules` | `array<orchestrationRule | localModelRef>` |  |  |
| `orchestrations` | `array<orchestrationRule | localModelRef>` |  |  |
| `queries` | `array<query | localModelRef>` |  |  |
| `ruleProfiles` | `array<ruleProfile | localModelRef>` |  |  |
| `procedures` | `array<procedure | localModelRef>` |  |  |
| `panels` | `array<panel | localModelRef>` |  |  |
| `documents` | `array<document | localModelRef>` |  |  |
| `guidePages` | `array<guidePage | localModelRef>` |  |  |
| `aggregates` | `array<aggregate | localModelRef>` |  |  |
| `autoPanels` | `array<autoPanel | localModelRef>` |  |  |
| `selectors` | `array<selector | localModelRef>` |  |  |
| `metadata` | `object` |  |  |
| `fragments` | `array<localModelRef>` |  |  |
| `packs` | `array<packRef>` |  |  |
| `externalAi` | `externalAi` |  |  |
| `settings` | `settings` |  |  |
| `roles` | `array<role>` |  |  |
| `propertyScopes` | `array<propertyScope>` |  | Wave 6 (RC-A1): the scoped-property cascade's declared levels, ORDER = resolution order, most specific first. The least-specific level is not declared here -- it is each property's own 'default'. |
| `properties` | `array<property>` |  | Wave 6 (RC-A1): declared runtime properties resolved through the scoped-property cascade (see propertyScopes). |

## Concept (`#/$defs/concept`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `extends` | `string` |  |  |
| `specializes` | `string` |  |  |
| `truthLevel` | `"T0" | "T1" | "T2" | "T3" | "T4" | "T5" | "T6"` |  |  |
| `module` | `string` |  |  |
| `renamedFrom` | `string` |  | Declares this concept is a rename of a previously-existing concept with this name, so a regeneration's schema-lifecycle classifies its table as a rename instead of an unrelated drop+create. |
| `fields` | `array<field>` | yes |  |
| `invariants` | `array<invariant>` |  |  |
| `indexes` | `array<index>` |  |  |
| `access` | `conceptAccess` |  |  |
| `events` | `array<event>` |  |  |
| `lifecycle` | `lifecycle` |  |  |
| `ui` | `presentationMetadata` |  |  |

## Field (`#/$defs/field`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `type` | `"string" | "uuid" | "int" | "integer" | "long" | "boolean" | "date" | "datetime" | "enum" | "reference" | "object" | "array" | "file"` | yes |  |
| `id` | `boolean` |  |  |
| `required` | `boolean | array<string>` |  |  |
| `unique` | `boolean` |  |  |
| `sensitive` | `boolean` |  | ADR-0009: marks this field for redaction before it may appear in any external-AI review pack (docs/adr/ADR-0009-external-ai-delegation.md). Authoring-time, and specific to pack building -- independent of the platform's runtime EventRedactionPolicy family. Redaction is by FIELD NAME and applies globally: marking any concept's field sensitive redacts that key name in traces and event payloads across all concepts. Deliberately over-redacts rather than risk missing an occurrence. |
| `connectable` | `"anchor"` |  |  |
| `renamedFrom` | `string` |  | Declares this field is a rename of a previously-existing column with this name, so a regeneration's schema-lifecycle classifies it as a rename instead of an unrelated remove+add. |
| `picker` | `object` |  | B16/B19 (Move 9 A3): declares a filter/multiSelect for this field's auto-picker (a reference field's browse/pick dialog). Same two properties a band's bandPickers entry accepts -- one picker shape for both surfaces. |
| `file` | `object` |  | Metadata for a file-typed field (LIFT-UPLOAD). The persisted value is a FileHandle (or list, if multiple), never raw bytes. |
| `enumValues` | `array<string | enumOption>` |  |  |
| `values` | `array<string | enumOption>` |  |  |
| `ref` | `string` |  |  |
| `reference` | `string | referenceDefinition` |  |  |
| `domainType` | `string` |  |  |
| `description` | `string` |  |  |
| `default` | `any` |  |  |
| `defaultExpression` | `string` |  |  |
| `derivedExpression` | `string` |  |  |
| `minLength` | `integer` |  |  |
| `maxLength` | `integer` |  |  |
| `minItems` | `integer` |  |  |
| `maxItems` | `integer` |  |  |
| `uniqueItems` | `boolean` |  |  |
| `itemIdentityField` | `string` |  |  |
| `duplicationPolicy` | `"allow" | "deny"` |  |  |
| `properties` | `object` |  |  |
| `items` | `schemaObject` |  |  |
| `ui` | `uiField` |  |  |

## Concept row-level access (LNCH-13) (`#/$defs/conceptAccess`)

LNCH-13: declarative row-level (data-scoped) authorization. Each expression is evaluated per-record through the platform's unified expression language (ComputedExpression), with $user.id/$user.tenantId/$user.actorId/$user.roles available alongside the record's own fields. read scopes which rows a query/list may return; write scopes which rows a save/delete may affect. Absent = no additional row-level restriction beyond tenant isolation and role permissions.

| Field | Type | Required | Description |
|---|---|---|---|
| `read` | `string` |  |  |
| `write` | `string` |  |  |

## Concept index (`#/$defs/index`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` |  |  |
| `fields` | `array<string>` | yes |  |
| `unique` | `boolean` |  |  |

## Flow (`#/$defs/flow`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `concept` | `string` |  |  |
| `specializes` | `string` |  |  |
| `input` | `flowInput` |  |  |
| `inputSchema` | `schemaObject` |  |  |
| `outputSchema` | `schemaObject` |  |  |
| `action` | `actionMetadata` |  |  |
| `startEndpoint` | `boolean` |  |  |
| `schedule` | `flowSchedule` |  |  |
| `hooks` | `array<flowHook>` |  |  |
| `steps` | `array<flowStep>` |  |  |

## Flow step (`#/$defs/flowStep`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` |  |  |
| `type` | `"invariantCheck" | "capabilityCall" | "generatedAction" | "emitEvent" | "scheduleEvent" | "return" | "branch" | "awaitEvent" | "createConcept" | "updateConcept" | "map" | "forEach" | "callProcedure"` | yes |  |
| `checkpoint` | `"pre" | "post"` |  |  |
| `phase` | `"pre" | "post"` |  |  |
| `scope` | `string` |  |  |
| `invariants` | `array<string>` |  |  |
| `capability` | `string` |  |  |
| `operation` | `string` |  |  |
| `input` | `string` |  |  |
| `output` | `string` |  |  |
| `args` | `array<string>` |  |  |
| `procedure` | `string` |  | Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md): the procedure a callProcedure flow step invokes synchronously. Reuses this step's own input/output for the procedure's input map / result binding. |
| `policy` | `capabilityPolicy` |  |  |
| `event` | `string` |  |  |
| `payload` | `string` |  |  |
| `from` | `string` |  |  |
| `data` | `object` |  |  |
| `condition` | `string` |  |  |
| `action` | `actionMetadata` |  |  |
| `actionName` | `string` |  |  |
| `then` | `array<flowStep>` |  |  |
| `else` | `array<flowStep>` |  |  |
| `awaitEvent` | `string` |  |  |
| `awaitRef` | `string` |  |  |
| `match` | `awaitMatch` |  |  |
| `delaySeconds` | `integer` |  |  |
| `delayMinutes` | `integer` |  |  |
| `delayMs` | `integer` |  |  |
| `value` | `string` |  |  |
| `collection` | `string` |  | LIFT-LOOP: state ref to the collection a forEach flow step iterates. |
| `itemKey` | `string` |  | LIFT-LOOP: state variable name each forEach iteration's item is bound to. |
| `steps` | `array<flowStep>` |  | LIFT-LOOP: the forEach loop body, executed once per item. |
| `maxLoopIterations` | `integer` |  | LIFT-LOOP: safety cap on forEach iterations, mirroring Procedures' forEach. |
| `onFailure` | `array<flowStep>` |  | LNCH-17: compensation steps run in reverse completion order if a later step in the same flow terminally fails. Not a distributed transaction -- best-effort cleanup, per the saga pattern; see docs/architecture/FLOW_TRANSACTION_CONTRACT.md. |

## Flow schedule (LNCH-12) (`#/$defs/flowSchedule`)

LNCH-12: recurring execution for a flow (cron expression + tenant scope). The runtime scheduler invokes the flow exactly like an HTTP-triggered run -- same authorization, same event emission -- under a system principal, not the superuser key.

| Field | Type | Required | Description |
|---|---|---|---|
| `cron` | `string` | yes |  |
| `tenantScope` | `string | array<string>` |  |  |

## Capability (`#/$defs/capability`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `type` | `string` |  |  |
| `specializes` | `string` |  |  |
| `operations` | `array<string | capabilityOperation>` |  |  |

## Capability execution policy (`#/$defs/capabilityPolicy`)

| Field | Type | Required | Description |
|---|---|---|---|
| `retryCount` | `integer` |  |  |
| `retryDelayMs` | `integer` |  |  |
| `timeoutMs` | `integer` |  |  |
| `circuitOpenAfterFailures` | `integer` |  |  |
| `circuitOpenMs` | `integer` |  |  |
| `bulkheadMaxConcurrent` | `integer` |  |  |
| `idempotencyKeyField` | `string` |  |  |
| `idempotencyKey` | `string` |  |  |
| `failureClassification` | `"TRANSIENT" | "PERMANENT" | "CONTRACT" | "transient" | "permanent" | "contract"` |  |  |

## Lifecycle (`#/$defs/lifecycle`)

| Field | Type | Required | Description |
|---|---|---|---|
| `statusField` | `string` |  |  |
| `states` | `array<lifecycleState>` |  |  |
| `transitions` | `array<lifecycleTransition>` | yes |  |

## Event (`#/$defs/event`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `specializes` | `string` |  |  |
| `version` | `string` |  |  |
| `payloadSchemaRef` | `string` |  |  |
| `payload` | `array<string | eventPayloadField>` |  |  |
| `mode` | `"create" | "update" | "delete"` |  | When set on a concept-nested event, generated CRUD publishes this event directly from the matching mutation step (in addition to any Flow's own emitEvent step) -- no Flow required to reach it. |

## Panel (`#/$defs/panel`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `route` | `string` | yes |  |
| `title` | `string` |  |  |
| `dataSources` | `array<panelDataSource>` |  |  |
| `layout` | `panelLayout` |  |  |
| `fieldBindings` | `array<panelFieldBinding>` |  |  |
| `visibility` | `string` |  |  |
| `enabledWhen` | `string` |  |  |
| `actions` | `array<panelAction>` |  |  |
| `explainability` | `object` |  |  |
| `metadata` | `object` |  |  |
| `guidePage` | `string` |  |  |

## Document (LNCH-10 Slice 3 -- server-rendered PDF) (`#/$defs/document`)

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | `string` | yes |  |
| `concept` | `string` | yes |  |
| `title` | `string` |  |  |
| `pageSize` | `"A4" | "Letter"` |  |  |
| `marginMm` | `number` |  |  |
| `metadata` | `object` |  |  |

## Field widgets

Every widget a `field.widget` may declare (`FieldWidgetDefaults.SUPPORTED_WIDGETS`), compatibility with a field's declared `type` enforced at compile time by `SemanticValidator` (`WidgetCompatibilitySupportTest` is the executable spec):

- `autocomplete`
- `checkbox`
- `color`
- `custom`
- `date`
- `datetime-local`
- `email`
- `group`
- `image-select`
- `list`
- `lookup`
- `multiselect`
- `number`
- `search-dialog`
- `select`
- `tel`
- `text`
- `textarea`
- `url`

## Where to look next

- `docs/TUTORIAL_FIRST_APP.md` — a golden-path walkthrough building a real app through the AI authoring loop (see `docs/adr/ADR-0006-authoring-path.md`).
- `docs/NPDEV_CONCEPTS_DEEP_DIVE.md` — the conceptual model behind concepts/flows/capabilities/panels.
- `knowledge/cards/*.json` — durable platform findings; `npdev_search_examples`/`npdev_search_fix` (MCP) query this corpus directly.
- Validation error codes carry a `suggestedFix`/`helpKey` (`ValidationDiagnostic`) — every `ModelValidatorMain`/`npdev validate model` JSON report includes them per-diagnostic.
- Truth-level release gating (`ReleaseGateValidator.validatePromotion`, `--releaseGate --targetTruthLevel=<T0..T6>`): see `NPDevContract/docs/MODEL-CONTRACT.md`'s `truthLevel` section for the full contract, including where it now runs automatically (`scripts/quality/run-generator-gate.ps1`'s `releaseGateT2` step, Move 8 item G3) and its current known-red state (REG-85).
