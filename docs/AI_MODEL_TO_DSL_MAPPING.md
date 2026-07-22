# AI Model To DSL Mapping

Checkpoint 6 locks the `ai-model.v1` contract to the existing NPDev DSL surfaces. The source of truth for field-level coverage is `scripts/policy/ai-model-to-dsl-mapping-policy.json`; this document explains the same mapping in reviewable human form.

## Schema Version Lock

Only `schemaVersion: "ai-model.v1"` is accepted. The normalizer rejects any other AI model version before emitting official DSL artifacts. Checkpoint 6 does not add new AI model fields or expand DSL capabilities.

## Top-Level Mapping

| AI model field | Classification | DSL target |
| --- | --- | --- |
| `schemaVersion` | diagnostic-only | Version gate for the normalizer; not emitted into official model DSL. |
| `app` | mapped | Official namespace/config naming metadata. |
| `entities` | mapped | `concepts`, concept fields, invariants, tenant id fields, and created events. |
| `flows` | mapped | Official `flows` with persistence/event steps. |
| `panels` | mapped | Official `panels` and panel sidecar contract. |
| `procedures` | mapped | Official `procedures` and procedure sidecar contract. |
| `workflows` | mapped | Concept lifecycle metadata and workflow sidecar contract. |
| `tenancy` | mapped | Tenant fields and official model metadata/security sidecar contract. |
| `auth` | mapped | Official model metadata/security sidecar contract. |
| `roles` | mapped | Permission requirements and official model metadata/security sidecar contract. |
| `verification` | diagnostic-only | Separate `ai-verification-report.v1` contract; not an `ai-model.v1` field. |

## Field Type Translation

| AI field type | DSL field type |
| --- | --- |
| `string` | `string` |
| `text` | `string` |
| `email` | `string` |
| `integer` | `integer` |
| `boolean` | `boolean` |
| `date` | `date` |
| `datetime` | `datetime` |
| `uuid` | `uuid` |

## Rejection Rules

The active contract rejects unsupported or incoherent AI model input before official DSL emission.

| Rule | Diagnostic code |
| --- | --- |
| Unsupported AI model schema version | `AI_MODEL_SCHEMA_VERSION_UNSUPPORTED` |
| Unsupported generator config version | `AI_CONFIG_SCHEMA_VERSION_UNSUPPORTED` |
| Unsupported app kind | `AI_MODEL_KIND_UNSUPPORTED` |
| Unsupported runtime/profile | `AI_CONFIG_RUNTIME_UNSUPPORTED`, `AI_CONFIG_PROFILE_UNSUPPORTED` |
| Unsafe output path | `AI_CONFIG_OUTPUT_PATH_UNSAFE` |
| Missing expanded Beta surface | `EXPANDED_SURFACE_MISSING` |
| Unresolved panel role/entity/procedure/workflow | `PANEL_ROLE_UNRESOLVED`, `PANEL_ENTITY_UNRESOLVED`, `PANEL_PROCEDURE_UNRESOLVED`, `PANEL_WORKFLOW_UNRESOLVED` |
| Unresolved procedure role/entity or unsafe bulk limit | `PROCEDURE_ROLE_UNRESOLVED`, `PROCEDURE_ENTITY_UNRESOLVED`, `PROCEDURE_BULK_LIMIT_MISSING` |
| Unresolved workflow entity/state/role | `WORKFLOW_ENTITY_UNRESOLVED`, `WORKFLOW_START_STATE_UNRESOLVED`, `WORKFLOW_TERMINAL_STATE_UNRESOLVED`, `WORKFLOW_TRANSITION_STATE_UNRESOLVED`, `WORKFLOW_TRANSITION_ROLE_UNRESOLVED` |
| Schema-level unknown or unsafe field shape | `AI_MODEL_FIELD_UNCLASSIFIED`, `TRUSTED_SOURCE_PATH_UNSAFE`, `ROLE_TENANT_BYPASS_REJECTED` |

## Golden Examples

`golden-ai-scenarios/base-ai-loop/ai-model.json` proves the simple entity and flow path: `User` maps to an official concept and `CreateUser` maps to an official flow.

`golden-ai-scenarios/tenant-workflow-ops/ai-model.json` proves expanded mapping: tenant-scoped `Ticket`, `CreateTicket`, the workflow panel, `advance-ticket`, roles, auth, tenancy, and lifecycle metadata all map to official DSL artifacts.

Negative scenarios stay in the contract as rejection examples. For example, `panel-unknown-entity` documents `PANEL_ENTITY_UNRESOLVED`, `procedure-unbounded-bulk` documents `PROCEDURE_BULK_LIMIT_MISSING`, and `workflow-invalid-transition` documents `WORKFLOW_TRANSITION_STATE_UNRESOLVED`.

CP3 reconciles scenario scope without adding new product support. Trusted-source scenarios are deferred under `golden-ai-scenarios/deferred/trusted-source/` and excluded from active mapping coverage. CP12 keeps trusted-source and custom procedures outside the active minimal support boundary, but admits bounded declarative custom panel metadata through the normal panel contract. Unsupported custom procedure kinds remain clean kind rejections with `AI_MODEL_KIND_UNSUPPORTED`.

## What This Does Not Solve

This checkpoint does not implement new AI model fields, does not expand DSL capabilities, and does not change golden scenario business intent except to classify existing fields and diagnostics.
