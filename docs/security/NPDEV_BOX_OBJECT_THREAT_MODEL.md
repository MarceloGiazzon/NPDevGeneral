# NPDev Box/Object Executable Security Threat Model

## Purpose

Phase 34 turns the Box/Object/Truth security threat model into executable tests.

Phases 28-33 created provider interfaces, local provider adapters, a packaged local runtime, browser-level E2E, generalized discovery beyond SalesFlow, and a production-shaped local audit sink adapter. Phase 34 must prove key security boundaries through runnable tests, not only prose.

## Scope

Phase 34 is still local/generated beta work.

It does not implement production auth, production tenancy, production audit, public runtime exposure, release approval, or T6. It adds executable local threat fixtures and checks that prove the current generated runtime rejects dangerous or invalid behavior.

## Required threat cases

The executable threat model must test at least these scenarios:

| Threat ID | Scenario | Expected status |
|---|---|---|
| `unauthenticatedRequest` | missing or unknown user | `AUTH_REQUIRED` |
| `wrongTenant` | disabled/unknown tenant | `TENANT_DENIED` |
| `wrongRole` | role not allowed for Procedure | `ROLE_DENIED` |
| `missingApproval` | Procedure requires approval but approval context missing | `APPROVAL_REQUIRED` |
| `unauthorizedProcedure` | Panel/user attempts a Procedure not allowed by route/binding | `PROCEDURE_NOT_ALLOWED` |
| `publicBindingAttempt` | request attempts public/non-localhost binding | `PUBLIC_BINDING_BLOCKED` |
| `pathTraversalResource` | request/resource path contains traversal | `WORKSPACE_ESCAPE_BLOCKED` |
| `auditTamper` | audit event/hash is modified after write | `AUDIT_INTEGRITY_FAILED` |
| `protectedOverwriteAttempt` | protected generated custom resource overwrite attempt | `PROTECTED_RESOURCE_OVERWRITE_BLOCKED` |

## Required proof

The Phase 34 checker must prove:

- generated security threat test Java compiles;
- executable threat test runs from compiled classes;
- all required threat cases are evaluated;
- each expected status is observed;
- audit tamper detection is backed by Phase 33 integrity logic or an equivalent local verifier;
- protected overwrite attempt is checked in a sandbox, not against protected source resources;
- no production/release/T6 claim is made;
- no protected source examples are mutated.

## Non-claims

Phase 34 must not claim:

- production security certification;
- production auth provider;
- production tenant provider;
- production audit sink;
- production runtime;
- release readiness;
- SalesFlow or ClinicFlow T6.

## Implementation guidance

Prefer generator-emitted Java threat classes under:

`build/box-object/real-generator-adapter/generated-app/security/src/main/java/generated/npdev/security/`

The checker may also create Phase 34-specific test harness files under:

`build/box-object/real-generator-adapter/security-threat-model/`

The important requirement is that the final PASS is executable, not static-only.
