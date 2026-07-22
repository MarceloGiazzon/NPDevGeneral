# NPDev Provider Adapter Implementations

## Purpose

Phase 29 implements executable provider adapters behind the provider interfaces introduced in Phase 28.

Phase 28 created the seam:

- `IdentityProvider`
- `TenantProvider`
- `RolePolicyProvider`
- `ApprovalPolicyProvider`
- `RuntimeEvidenceSink`
- `RuntimeConfigProvider`
- `RuntimeClock`
- `CorrelationProvider`
- `ProcedureDispatcher`

Phase 29 must prove these seams are not only interface files. The generated backend must use concrete adapter implementations through a provider profile.

## Scope

Phase 29 is still local-first. It does not implement real production auth, real production tenant registry, or production audit sink. It creates richer local adapters and adapter-profile behavior so later production providers can be swapped safely.

## Required adapter implementations

- `LocalIdentityProvider`
- `LocalTenantProvider`
- `LocalRolePolicyProvider`
- `LocalApprovalPolicyProvider`
- `LocalRuntimeConfigProvider`
- `SystemRuntimeClock`
- `UuidCorrelationProvider`
- `LocalProcedureDispatcher`
- `FileRuntimeEvidenceSink`

## Required behavior

The generated backend must load or construct a local provider profile, instantiate adapters through `RuntimeProviderContext` or equivalent, evaluate identity/tenant/role/approval through adapters, dispatch Procedures through `ProcedureDispatcher`, and record evidence through `RuntimeEvidenceSink`.

## Acceptance

Phase 29 is complete only if executable Java tests prove valid and denied scenarios, and evidence sink recording, through the adapter layer.

## Non-claims

Phase 29 must not claim production auth provider, production tenant provider, production audit sink, production runtime, release approval, SalesFlow T6, or default adapter enablement.
