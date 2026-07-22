# Expanded AI-only Beta 0 Scope

## Purpose

AI-only Beta 0 proves the expanded release-closure product surface: an AI can author bounded model/config, custom panel, custom procedure, workflow, tenancy, auth, role, verification, and structured command inputs; NPDev can convert them into strict official inputs, generate runnable apps, verify them autonomously, and produce current evidence. It is not an open-ended agent shell or public app-building platform.

Beta 0 is ready when a clean environment can run one command that validates AI input, normalizes it, generates an app, builds it, boots it, verifies behavior, and emits current evidence.

## Supported In Beta 0

- AI-authored `ai-model.json`.
- AI-authored `ai-config.json`.
- AI-authored `ai-verification-report.json` or `ai-smoke-plan.json`.
- AI-authored bounded custom panel, custom procedure, workflow, tenancy, auth, and role definitions.
- Typed AI command requests for structured validation, normalization, generation, build, boot, smoke, and evidence operations.
- AI schema validation.
- AI-to-official normalizer.
- Official schema validation.
- Generator execution.
- Deterministic generation check.
- Controlled build command execution.
- Runtime boot under fixed `ai-beta-local` profile.
- REST health check.
- REST behavior smoke checks.
- Panel, procedure, workflow, tenant isolation, auth, and role smoke checks.
- Machine-readable evidence report.

## Excluded From Beta 0

- Free-form command execution.
- Arbitrary network access.
- Production auth.
- Unbounded workflow scripting.
- Unbounded custom procedure scripting.
- Inline arbitrary frontend/backend code.
- Multi-cloud deployment.
- Arbitrary custom code.
- UI-only verification as the required proof.
- Manual review as a pass condition.
- Public production deployment.

`embedded-test` is an AI-facing beta-local database mode. The normalizer maps AI-facing runtime intent into the currently supported official runtime/config shape used by the generated app evidence path.

## Required Commands And Reports

AI-only proof command:

```powershell
pwsh ./scripts/quality/run-ai-beta-gate.ps1
```

AI-only proof report:

```text
scripts/reports/out/ai-beta-gate-report.json
```

Expanded evidence proof command:

```powershell
pwsh ./scripts/quality/run-expanded-beta0-evidence.ps1
```

Release proof command:

```powershell
pwsh ./scripts/quality/run-beta-release-gate.ps1
```

Release proof report:

```text
scripts/reports/out/beta-release-gate-report.json
```

Until both commands exist and pass against current evidence, the AI-only Beta 0 release is not ready.

## Success Statement

Beta 0 is ready only when a clean official Windows CI environment can run `pwsh ./scripts/quality/run-beta0-final-release-check.ps1` and the final report sets `overallStatus`, `candidateReady`, `releaseReady`, `provenanceReady`, `officialReleaseEligible`, and `beta0TagAllowed` to passing/true.
