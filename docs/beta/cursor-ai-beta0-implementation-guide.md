# Cursor AI Beta 0 Implementation Guide

## Branch

Use `beta0-ai-autonomous-loop` for the AI-only Beta 0 work.

## Batch Order

Implement the roadmap in order. Do not skip a batch to make a later gate look green.

1. Truth and scope.
2. AI schemas and scenario layout.
3. Normalizer implementation.
4. Controlled executor.
5. REST smoke verification.
6. AI beta gate core.
7. Build, boot, smoke, runtime profile.
8. Golden scenarios and samples.
9. Release evidence and aggregate gate.
10. Reproducibility, CI, security, docs, closure.

## Scope Rules

Beta 0 proves one loop: AI-authored inputs normalize into official contracts, generate an app, build, boot, smoke test, and emit current evidence.

Do not expand Beta 0 into public SaaS deployment, arbitrary shell execution, arbitrary custom code, external network dependencies, UI-only proof, or production auth.

## Commands

Run after focused changes:

```powershell
pwsh ./scripts/quality/run-ai-schema-validation.ps1
pwsh ./scripts/quality/run-ai-contract-normalizer-tests.ps1
pwsh ./scripts/quality/run-controlled-command-runner-tests.ps1
pwsh ./scripts/quality/run-ai-rest-smoke-verifier-tests.ps1
pwsh ./scripts/quality/run-runtime-null-context-tests.ps1
pwsh ./scripts/quality/run-sample-matrix.ps1
```

Run before closure:

```powershell
pwsh ./scripts/quality/run-ai-beta-gate.ps1
pwsh ./scripts/quality/run-beta-release-gate.ps1
```

## Source Of Truth

- AI proof: `scripts/reports/out/ai-beta-gate-report.json`
- Release proof: `scripts/reports/out/beta-release-gate-report.json`
- Evidence manifest: `scripts/reports/out/beta-release-evidence-manifest.json`
- Reproducibility: `scripts/reports/out/ai-beta-reproducibility-report.json`

Focused reports are evidence only. The release decision comes from `beta-release-gate-report.json`.

## Expected Failures

Negative golden scenarios must report `status=passed` only when they fail at the declared stage with the expected behavior or command-policy code.

## Command Safety

Generated or AI-proposed commands must go through `scripts/security/Invoke-ControlledCommand.ps1`. Direct free-form shell evaluation is outside Beta 0 scope.
