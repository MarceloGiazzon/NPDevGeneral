# NPDev Runtime Bridge Contract

## Purpose

This document defines the runtime bridge contract between code-bearing Panel Objects and code-bearing Procedure Objects.

The bridge contract explains how Panel Object frontend code can call Procedure Object backend logic through a safe boundary such as `window.NPDev.procedures.call(procedureId, input, options)`.

## Scope

Phase 11 is contract only. It does not implement production runtime behavior, HTTP endpoints, Java execution through the bridge, auth enforcement, tenant enforcement, or release gate integration.

## Inputs

The runtime bridge contract consumes:

- `build/box-object/integrated-app/metadata/panel-routes.json`
- `build/box-object/integrated-app/metadata/procedure-registry.json`
- `build/box-object/box-object-graph.json`
- `examples/box-object-truth/salesflow/generator-adapter-contract.json`
- `examples/box-object-truth/salesflow/runtime-bridge-contract.json`
- truth, resource, integration, viewer, and promotion reports

## Runtime Bridge Surfaces

### Frontend Bridge Surface

Panel JavaScript calls the bridge through:

```javascript
window.NPDev.procedures.call(procedureId, input, options)
```

The frontend bridge surface accepts a Procedure Object id, an input object, and optional request metadata.

### Procedure Invocation Surface

The bridge maps `procedure.generate-monthly-invoices` to the planned Procedure Object invocation path and Java class:

```text
procedure.generate-monthly-invoices
com.npdev.examples.salesflow.billing.GenerateMonthlyInvoices
```

Phase 11 defines the invocation contract only. It does not execute the Java Procedure through the bridge.

### Request Envelope

Bridge requests must carry:

- `requestId`
- `correlationId`
- `tenantId`
- `userContext`
- `roles`
- `procedureId`
- `input`
- `requestedAt`
- `approvalContext`

### Response Envelope

Bridge responses must carry:

- `requestId`
- `correlationId`
- `procedureId`
- `status`
- `result`
- `errors`
- `warnings`
- `evidenceRefs`
- `completedAt`
- `truthLevelClaim`

### Error Envelope

Bridge errors must be classified as:

- `ValidationError`
- `AuthRequired`
- `TenantRequired`
- `RoleDenied`
- `ApprovalRequired`
- `ProcedureNotFound`
- `ExecutionFailed`
- `BridgeUnavailable`
- `UnsupportedInAdvisoryMode`

### Security and Policy Surface

The bridge contract carries:

- `requiresAuth`
- `requiresTenant`
- `allowedRoles`
- `aiExecution`
- human approval policy
- input validation policy
- audit/evidence policy

Panel JavaScript can call the bridge, but it cannot bypass policy. Procedure execution must respect `executionPolicy`. Tenant and role metadata must be present in the request envelope before a production runtime can execute the request.

### Truth Surface

Runtime bridge execution does not automatically promote truth levels.

The bridge must not claim T5 or T6 unless evidence supports that claim. It must not promote SalesFlow to release-approved status.

### Evidence Surface

Future bridge calls may create evidence references for request validation, execution, audit, and result verification. Phase 11 only defines where those evidence references appear in request and response contracts.

## Required Invariants

- Panel JS can call the bridge but cannot bypass policy.
- Procedure execution must respect executionPolicy.
- Tenant and role metadata must be carried in the request envelope.
- Bridge cannot claim backend execution in advisory/static mode.
- Bridge cannot promote T6.
- Bridge must return clear stub/not-implemented responses when not backed by runtime.
- Custom Panel and Procedure code remains supported.
- Existing generator behavior remains default.
- Release gates are not changed in Phase 11.

## Advisory Mode Behavior

Phase 11 advisory mode uses a `STUB_ONLY` or contract-only bridge.

Advisory mode means:

- no backend execution
- no auth/tenancy enforcement
- no production HTTP endpoint
- response status may be `STUB_ONLY` or `UNSUPPORTED_IN_ADVISORY_MODE`
- `releaseGateImpact = None`

## Future Production Behavior

Later phases must define and implement:

- HTTP endpoint adapter
- Java Procedure invocation adapter
- auth provider
- tenant provider
- audit trail
- evidence capture
- bridge tests

## Non-Goals

- no production runtime implementation
- no HTTP endpoints
- no Java execution through bridge
- no auth/tenancy enforcement
- no release gate changes
- no T6 promotion
- no release readiness claim
