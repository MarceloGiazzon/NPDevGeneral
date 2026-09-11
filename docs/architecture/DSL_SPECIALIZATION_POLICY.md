# DSL specialization policy (Decision D2)

`NPDEV_PATH_A_REALIGNMENT_PLAN.md` Decision D2 asked one question: *may a specialization override
an inherited field, or only add?* P3.1 collapsed every inheritance-walking site in
`NPDevContract/dsl` down to the single lane that answers it —
[`ModelResolver`](../../NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/resolution/ModelResolver.java).
P3.2 is this document: the per-element, per-field verdict that lane implements, with one
conformance fixture proving each row.

## Which element types specialize at all

Only five schema definitions in `model.schema.json` declare a `specializes` property: `concept`,
`invariant` (nested inside a concept), `capability`, `event`, `flow`. `panel`, `procedure`, `query`
and `aggregate` do not — checked directly against all four copies of `model.schema.json`
(`scripts/quality/check-schema-mirror-consistency.py` keeps them in sync). Those four element types
compose by *referencing* a concept or capability, not by specializing one another, and D2 does not
extend inheritance to them. That absence is itself the verdict for those types, not an oversight: a
schema that rejects `"specializes"` on a panel/procedure/query/aggregate (via
`additionalProperties: false`) is the enforcement mechanism, so there is no separate runtime guard
to test.

Every row below is implemented in `ModelResolver` and reached through the same
`resolve<Element>`/`merge<Element>` shape: resolve the base first (recursively, cycle-checked),
then merge the specialization onto it. `ModelResolver.resolveConcept`/`resolveCapability`/
`resolveEvent`/`resolveFlow` all guard against a specialization cycle identically regardless of
element type — proven for concepts by
`DslSpecializationTest.validatorRejectsSpecializesOnlyCycle` (P3.1's RED test).

## Concept (`ModelResolver.mergeConcept`)

| Field | Verdict | Rule | Fixture |
|---|---|---|---|
| `fields` | Add-only. Redeclaring a base field name is `ILLEGAL_OVERRIDE`. | D2 | `DslSpecializationTest.compilesInheritedFieldsAndInvariants` (positive), `ModelResolverSpecializationTest.emitsStableDiagnosticCodes` (negative) |
| `invariants` | Add-only unless `specializes` + `override:true` names an existing invariant, in which case it replaces that one. A duplicate identity without `override` is `CONFLICT_DUPLICATE_MEMBER`; `override:true` without `specializes` is `ILLEGAL_OVERRIDE`. | D2 | `NPDevSamples` golden fixture `specialization/valid-specialization.json` (`positiveTotal` override) |
| `events` (nested) | Add-only. A specialization re-declaring a base event name is `CONFLICT_DUPLICATE_MEMBER`. | D2 (same shape as fields) | `InheritancePolicyConformanceTest.conceptNestedEventsAreAddOnlyAndRejectDuplicates` |
| `ui` / label + locales | Override-allowed: the specialization's `(text, locales)` pair replaces the base's whole pair the moment it says anything at all (plain text OR a locale map); a specialization with no `ui` block at all inherits the base's whole pair. | D2, R5.6 | `LabelLocaleSpecializationMergeTest` (3 cases) |
| `access` | Override-allowed: specialization wins if declared, else base. Not ANDed/ORed with the base rule — a security-relevant default, so the least-surprising choice (explicit replacement) was picked over silent broadening or narrowing. | D2, LNCH-13 | `InheritancePolicyConformanceTest.conceptAccessSpecializationWinsElseBase` |
| `lifecycle` | Override-allowed: specialization wins if declared, else base. | D2 | `InheritancePolicyConformanceTest.conceptLifecycleSpecializationWinsElseBase` |
| `module` | Override-allowed: specialization wins if non-blank, else base (`firstNonBlank`). | D2 | `InheritancePolicyConformanceTest.conceptModuleSpecializationWinsElseBase` |
| `origin` | Override-allowed: specialization wins if it has its own pack origin, else base's. A specialization declared in a different pack (or in the app itself) is attributed to its own origin, never silently inherited. | PACK-2 | `InheritancePolicyConformanceTest.conceptOriginFallsBackToBaseWhenSpecializationIsPackless` proves the *else base* branch (an app-native concept specializing a pack concept keeps the pack's origin instead of losing it); the *specialization wins* branch is a one-line ternary with no branch of its own to lose, so it rides on the same code path as every other "specialization wins" field below rather than a second fixture. |
| `indexes` | Additive: base indexes ++ specialization indexes, no dedup, no override concept. | D2 | `InheritancePolicyConformanceTest.conceptIndexesAreAdditiveAcrossSpecialization` |
| `softDelete` | Sticky-true: `specialization.softDelete OR base.softDelete`. A base's durability guarantee (rows never physically removed) cannot be silently undone; a specialization may still opt in on its own even when the base does not. | R5.4 | `InheritancePolicyConformanceTest.conceptSoftDeleteIsStickyTrueAcrossSpecialization` |
| `temporal` | Sticky-true, same reasoning as `softDelete`. | R5.8 | `InheritancePolicyConformanceTest.conceptTemporalIsStickyTrueAcrossSpecialization` |
| `truthLevel`, `renamedFrom`, `satelliteOf`, `uid` | Specialization's own value always wins (not merged with base at all — these identify the specialization itself). | D2 | Exercised incidentally by every specialization fixture above; not independently row-worthy since there is no base/specialization conflict to arbitrate. |

## Capability (`ModelResolver.mergeCapability`)

| Field | Verdict | Rule | Fixture |
|---|---|---|---|
| `type` | Fixed once declared: a specialization may state the same type or omit it, but changing it is `ILLEGAL_OVERRIDE`. | D2 (capability analogue of fields) | `InheritancePolicyConformanceTest.capabilityTypeCannotBeChangedBySpecialization` |
| `operations` | Add-only, same shape as concept `fields`: redeclaring a base operation name is `ILLEGAL_OVERRIDE`. | D2 | `NPDevSamples` golden fixture `specialization/valid-specialization.json` (`medicalPersistence` adds `saveMedical`) positive; `InheritancePolicyConformanceTest.capabilityOperationsAreAddOnlyAndRejectOverride` negative |
| `origin` | Same "specialization wins else base" rule as concept `origin`. | PACK-2 | Rides the concept origin fixture above; identical ternary, same non-branch reasoning. |

## Event (`ModelResolver.mergeEvent`)

| Field | Verdict | Rule | Fixture |
|---|---|---|---|
| `conceptName` | Fixed once declared: a specialization may state the same owning concept or omit it, but changing it is `ILLEGAL_OVERRIDE`. Reachable in practice only when two *different* concepts each nest an event and one specializes the other's nested event — same-concept specialization trivially matches. | D2 | `InheritancePolicyConformanceTest.eventConceptCannotBeChangedBySpecialization` |
| `payload` + `version` | Override-allowed, but a payload-schema change without a version bump is `VERSION_REQUIRED`. | D2, event-versioning contract | `NPDevSamples` golden fixture `specialization/valid-specialization.json` (positive), `specialization/invalid-specialization.json` (negative, `ModelResolverSpecializationTest.rejectsInvalidSpecializationGoldenModelWithVersionRequired`) |
| `triggerMode` | Override-allowed: specialization wins if declared, else base. | D2 | `InheritancePolicyConformanceTest.eventTriggerModeSpecializationWinsElseBase` |
| `origin` | Same "specialization wins else base" rule as concept `origin`. | PACK-2 | Rides the concept origin fixture; identical ternary. |

## Flow (`ModelResolver.mergeFlow`)

| Field | Verdict | Rule | Fixture |
|---|---|---|---|
| `steps` | Illegal to redeclare when specializing — a specialization with its own `steps` is `ILLEGAL_OVERRIDE`; it must use `hooks` instead. | D2 (flows never merge steps positionally) | `InheritancePolicyConformanceTest.flowCannotRedefineStepsWhenSpecializing` |
| `hooks` | The only sanctioned mechanism for a specialization to change behaviour: applied onto the base's resolved steps by `applyHooks`. Declaring `hooks` on a flow that does *not* specialize anything is `ILLEGAL_OVERRIDE`. | D2 | `NPDevSamples` golden fixture `specialization/valid-specialization.json` (`SubmitMedicalInvoice` inserts `emit-medical` before `return-base`) positive; `InheritancePolicyConformanceTest.flowHooksWithoutSpecializesIsRejected` negative |
| `concept`, `mode`, `inputSchema`/`outputSchema` | Fixed once declared on the base: a specialization stating any of these is `ILLEGAL_OVERRIDE`, regardless of whether the value matches. `mode` is not independently reachable: the parser only ever sets it from `flow.input.mode`, and `flowInput` schema-requires `concept` on that same object, so any model redeclaring `mode` also redeclares `concept`, which throws first (same code, redundant by construction, not a missing guard). | D2 | `InheritancePolicyConformanceTest.flowCannotOverrideConceptModeOrSchemas` (`concept`, `inputSchema`) |
| `schedule` | Override-allowed: specialization wins if declared, else base — two cron expressions have no sensible merge. | LNCH-12 | `InheritancePolicyConformanceTest.flowScheduleSpecializationWinsElseBase` |
| `action` | Override-allowed: first non-null of (specialization, base). | D2 | `InheritancePolicyConformanceTest.flowActionFirstNonNullWinsElseBase` |
| `startEndpoint` | Sticky-true: `specialization.startEndpoint OR base.startEndpoint`, same reasoning family as `softDelete`/`temporal` — a base flow exposed as an entry point cannot be silently hidden by a specialization. | Same family as R5.4/R5.8 | `InheritancePolicyConformanceTest.flowStartEndpointIsStickyTrueAcrossSpecialization` |
| `origin` | Same "specialization wins else base" rule as concept `origin`. | PACK-2 | Rides the concept origin fixture; identical ternary. |

## Where the fixtures live

- `NPDevContract/dsl/src/test/resources/specialization/{valid,invalid}-specialization.json` plus
  `ModelResolverSpecializationTest` — the pre-existing golden-path fixtures (add-only fields,
  invariant override, capability operation add, event payload/version bump, flow hooks).
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/resolution/InheritancePolicyConformanceTest.java`
  — the rows P3.2 added: every remaining "specialization wins else base", every sticky-true field,
  every additive-list field, and every `ILLEGAL_OVERRIDE` guard on capability/event/flow that had no
  fixture before this pass.
- `LabelLocaleSpecializationMergeTest` — the `ui` label/locale merge, pre-existing.
- `DslSpecializationTest` — `extends`-alias parsing, unknown-base and cycle rejection (both
  `extends`-only and `specializes`-only), duplicate-field rejection.
