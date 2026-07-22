# NPDev Generator Adapter Contract

## Purpose

This document defines how the real NPDev generator should consume Box/Object/Truth artifacts.

Phase 10 is a contract phase. It defines adapter responsibilities, inputs, outputs, safety invariants, and transition rules. It does not implement full production generator integration.

The contract exists to let NPDev move from advisory proof toward product architecture while preserving existing generator stability and protected custom code.

## Scope

This contract covers how a future generator adapter consumes:

- `build/box-object/box-object-graph.json`
- `examples/box-object-truth/salesflow/generator-adapter-contract.json`
- `examples/box-object-truth/salesflow/customization-registry.json`
- `scripts/reports/out/box-object-truth-report.json`
- `scripts/reports/out/code-bearing-object-resource-report.json`
- `scripts/reports/out/box-object-promotion-evidence-closure-report.json`
- `build/box-object/integrated-app/metadata/panel-routes.json`
- `build/box-object/integrated-app/metadata/procedure-registry.json`

The contract is additive and opt-in. Existing generator behavior remains the default unless the adapter is explicitly enabled in a later phase.

## Expected Generator Outputs

A future implementation of this adapter should produce:

- frontend route registrations
- Panel resource mount/copy plans
- Procedure backend registration plans
- protected resource preservation plans
- truth/evidence propagation plans
- adapter diagnostics reports

Phase 10 may generate planning maps under `build/box-object/generator-adapter`, but it does not generate production application code.

## Adapter Surfaces

### Graph Adapter

Reads the Box/Object graph and resolves ownership, containment, relationships, structural references, release inclusion, and evidence links.

### Panel Object Adapter

Handles code-bearing Panel Object HTML/CSS/JS resources, route mounting, bridge injection policy, frontend registration, and safe copy/mount planning.

### Procedure Object Adapter

Handles code-bearing Procedure Object Java resources, classpath and package alignment, endpoint or registry planning, and compile/test evidence references.

### Customization Protection Adapter

Enforces the never-overwrite policy for protected custom resources. Protected Panel and Procedure resources cannot be silently overwritten by regeneration.

### Truth Propagation Adapter

Prevents the generator from overclaiming runtime, evidence, or release maturity. Truth levels cannot be promoted without evidence.

### Evidence Adapter

Links generator outputs to validation, resource, integration, viewer, and promotion/evidence reports.

### Promotion Adapter

Keeps advisory, staged, generated, evidence-backed, release-approved, and released states separate.

## Safety Invariants

- Protected custom resources cannot be overwritten silently.
- Generated output must stay under declared generated output roots.
- Source examples, manifests, and the customization registry are read-only by default.
- Truth levels cannot be auto-promoted without evidence.
- Panel Object and Procedure Object remain Objects, not Boxes.
- Direct HTML/CSS/JS/Java customization remains supported.
- Existing generator behavior remains default unless the adapter is explicitly enabled.
- Release gates are not changed in Phase 10.

## Opt-In Model

The adapter contract is disabled by default:

```json
{
  "boxObjectAdapter": {
    "enabled": false,
    "mode": "Advisory"
  }
}
```

Allowed modes are:

- `Advisory`
- `Staged`
- `Active`

Phase 10 uses `Advisory`. The adapter is not release-blocking until a later release policy phase explicitly changes that status.

## Failure Policy

The adapter contract distinguishes:

- hard errors: unsafe paths, missing required inputs, missing protected-resource policy, invalid mappings
- advisory warnings: known deferred capabilities
- deferred capabilities: runtime bridge, backend endpoints, auth/tenancy hooks, release gate policy
- release-blocking issues: not used in Phase 10

Phase 10 must never convert advisory contract findings into release gate behavior.

## Transition Path

- Phase 10: generator adapter contract
- Phase 11: runtime bridge contract
- Phase 12: backend procedure endpoint adapter
- Phase 13: auth and tenancy enforcement hooks
- Phase 14: release gate integration policy
- Later phases: real generator implementation

## Non-Goals

- no production generator rewrite
- no runtime bridge implementation
- no HTTP endpoint implementation
- no auth/tenancy enforcement
- no release gate changes
- no T6 promotion
- no claim of production release readiness
