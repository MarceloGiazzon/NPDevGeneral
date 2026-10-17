# NPDev Box/Object Threat Model

## Status of this document

This began as the Phase 34 plan to turn the Box/Object/Truth security threat model into **executable threat tests**
(a generated threat-test class, a checker, nine expected statuses). **That work was never built**: no threat fixture,
generated threat class or Phase 34 checker exists in the repository, and none of the nine threat IDs or most of the nine
status codes below appear anywhere in code. The provider interfaces and local provider adapters that the plan assumed
(`TenantProvider`, `LocalIdentityProvider`, a production-shaped local audit sink, ...) do not exist either.

What follows is the original threat list, mapped to the **existing tests that cover each scenario today**, or marked
**not covered**. It claims no production security certification, production auth/tenant/audit provider or release
readiness.

## Threat to coverage

| Threat ID | Scenario | Original expected status | Covered today by | State |
|---|---|---|---|---|
| `unauthenticatedRequest` | missing or unknown user | `AUTH_REQUIRED` | `CurrentUserControllerTest`, `SessionTokenControllerTest` (runtimehost-core `auth/`) | partial — auth endpoints, not every route |
| `wrongTenant` | disabled/unknown tenant | `TENANT_DENIED` | `DefaultExecutionAuthorizationPolicyTest` (tenant isolation, `tenantId=default` denial) in `authz-default` | covered at the policy level |
| `wrongRole` | role not allowed for a procedure | `ROLE_DENIED` | `DefaultExecutionAuthorizationPolicyTest`, `DefaultExecutionAuthorizationPolicyModelReloadTest` (grants added/dropped by reload) | covered at the policy level |
| `missingApproval` | procedure requires approval but the approval context is missing | `APPROVAL_REQUIRED` | none | **not covered** |
| `unauthorizedProcedure` | panel/user attempts a procedure not allowed by route/binding | `PROCEDURE_NOT_ALLOWED` | none | **not covered** |
| `publicBindingAttempt` | request attempts a public/non-localhost binding | `PUBLIC_BINDING_BLOCKED` | `AuthExposureBootBannerTest` | **warns only** — `AuthExposureBootBanner` logs a banner when auth is off and the bind address is not loopback (Spring's unset `server.address` binds 0.0.0.0); nothing blocks |
| `pathTraversalResource` | request/resource path contains traversal | `WORKSPACE_ESCAPE_BLOCKED` | `FileSystemFileStoreAdapterTest` (file store), `UntrustedExtensionSecurityHardeningTest` (generator), `PackCoordinateTest` (pack coordinates) | covered per surface, no single workspace-escape test |
| `auditTamper` | audit event/hash modified after write | `AUDIT_INTEGRITY_FAILED` | none | **not covered** — the audit adapters (`audit-inproc`, `audit-postgres`) contain no tamper, integrity or hash-chain logic |
| `protectedOverwriteAttempt` | protected generated custom resource overwrite attempt | `PROTECTED_RESOURCE_OVERWRITE_BLOCKED` | none | **not covered** |

## What would make this executable

If this model is to be enforced rather than described, each **not covered** and **partial** row needs a real test
beside the code it protects (the policy, the controller, the file store), not a separate generated harness, and the
`auditTamper` row first needs the integrity logic it assumes. Until then this table is the honest state: two
scenarios have policy-level tests, three have narrower coverage, and four have none.
