# NPDev Runtime Bridge Contract

## Purpose

This document defines the runtime bridge contract between code-bearing Panel Objects and code-bearing Procedure Objects.

The bridge lets Panel Object frontend code (an untrusted-extension panel) call Procedure and Flow backend logic through a fixed boundary: the `window.NPDev` script that every generated app with an untrusted-extension panel serves at `/generated/trusted-source/npdev-panel-runtime.js` (the `trusted-source` segment is historical; the route keeps that name). Panels use `window.NPDev.callProcedure(name, payload)`, `window.NPDev.startFlow(flowName, payload)` and `window.NPDev.resumeFlow(flowName, eventName, payload)`; there is no `window.NPDev.procedures` object.

## Scope

The bridge is live. In every generated app that has an untrusted-extension panel, the calls below are real HTTP requests handled by `GeneratedTrustedSourceRuntimeController`. A procedure runs through the generated action kernel runner, and a flow through the flow runner, under the caller's runtime context (`RuntimeContextService.currentContext(request)`: tenant and roles come from the session or API key the request carries). The sections titled "Planned envelope (not implemented)" below describe a richer request/response envelope that nothing implements yet.

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

A panel page loads the script and then calls it:

```html
<script src="/generated/trusted-source/npdev-panel-runtime.js"></script>
```

```javascript
const result = await window.NPDev.callProcedure('CreateUsers', { users: [] });
```

| Call | HTTP request | Notes |
|---|---|---|
| `window.NPDev.callProcedure(name, payload)` | `POST /generated/procedures/{name}` | Auto-renders the result (`renderActionResult(null, body)`), returns the response body, and on a non-2xx response throws an `Error` carrying `.responseBody` and `.httpStatus`. |
| `window.NPDev.startFlow(flowName, payload)` | `POST /generated/flows/{flow}/start` | Auto-renders the flow result and returns the body (a failed request returns a body with `httpStatus`, `status: 'failed'` and `error`; it does not throw). |
| `window.NPDev.resumeFlow(flowName, eventName, payload)` | `POST /generated/flows/{flow}/events/{event}` | Publishes the event for a flow waiting on it; same return behaviour as `startFlow`. |
| `window.NPDev.renderActionResult(container, response)` / `renderActionResultHtml(response)` | none | Renders a procedure/action response (status, execution and correlation ids, dispatch/event/trace/audit/idempotency status, counts, message, error). |
| `window.NPDev.renderFlowResult(container, response)` / `renderFlowResultHtml(response)` | none | Renders a flow response, with links to the flow and correlation evidence pages. |
| `window.NPDev.authHeaders(extra)` | none | Builds request headers. The credential is read from `window.NPDevApiKey`, or from `localStorage` (`npdev.shell.token`, `npdev.businessUi.apiKey`, `npdev.apiKey`) and sent as both `Authorization: Bearer` and `X-Api-Key`. |

The request body is the JSON payload object. `POST /generated/actions/{action}/run` is the same runner addressed by action name.

The response body of `callProcedure` has these keys: `status`, `actionName`, `procedureName`, `executionId`, `correlationId`, `capabilityId`, `capabilityDispatchStatus`, `createdCount`, `sideEffectCountBefore`, `sideEffectCountAfter`, `eventStatus`, `auditStatus`, `traceStatus`, `idempotencyStatus`, `correlationStatus`, `message`, `error`, and `result` (an object, present only when the procedure returned data).

### Procedure Invocation Surface

The bridge maps `procedure.generate-monthly-invoices` to the planned Procedure Object invocation path and Java class:

```text
procedure.generate-monthly-invoices
com.npdev.examples.salesflow.billing.GenerateMonthlyInvoices
```

The example above is the original contract sample (the Java class path is illustrative). In the shipped bridge a procedure is addressed by its name in `POST /generated/procedures/{name}`.

### Planned envelope (not implemented): Request Envelope

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

### Planned envelope (not implemented): Response Envelope

The shipped response keys are listed under "Frontend Bridge Surface". A bridge response must additionally carry:

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

### Planned envelope (not implemented): Error Envelope

Bridge errors are to be classified as:

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

## Runtime Behavior

The bridge executes for real: a call reaches the generated action kernel runner (procedures) or flow runner (flows) and returns the runner's response map. There is no advisory or stub mode in the shipped bridge. The `releaseGateImpact = None` rule of this document still holds: calling the bridge does not change any release gate.

## Not Yet Implemented

These parts of the original contract are still future work:

- the request/response/error envelopes above (request ids, `evidenceRefs`, `truthLevelClaim`, classified errors)
- `approvalContext` and per-procedure `allowedRoles` enforcement at the bridge itself
- dedicated bridge tests for the full planned envelope

## Non-Goals

- no new envelope fields beyond what the runtime returns today
- no change of the `/generated/...` route names
- no release gate changes
- no T6 promotion
- no release readiness claim
