# NPDev Project Digest

## Mission

NPDev generates runnable applications from declarative contracts.

## Current Milestone

AI-only Beta 0: autonomous generation verification loop.

Beta 0 is not a public platform release. It is ready only when one command can validate AI-authored inputs, normalize them to official generator contracts, generate an app, build it, boot it, run formal REST smoke checks, and emit current machine-readable evidence without hidden human action.

## Core Projects

- NPDevContract: official schemas and examples.
- NPDevGenerator: generator implementation.
- NPDevKernel: core runtime abstractions.
- NPDevRuntimeHost: host/runtime surface.
- NPDevEditor: UI/editor surface, not the primary Beta 0 proof.
- NPDevSamples: official sample inputs and expected behavior.
- schemas/ai: AI-facing contracts and golden scenario schemas.
- golden-ai-scenarios: AI beta test scenarios.
- scripts/quality: release and quality gates.
- scripts/policy: beta policies and allowlists.
- scripts/reports/out: generated current evidence.

## Source Of Truth

- AI-only proof: `scripts/reports/out/ai-beta-gate-report.json`
- Release proof: `scripts/reports/out/beta-release-gate-report.json`
- Final Beta 0 closure proof: `scripts/reports/out/beta0-final-closure-report.json`

The target AI-only proof command is:

```powershell
pwsh ./scripts/quality/run-ai-beta-gate.ps1
```

The target release proof command is:

```powershell
pwsh ./scripts/quality/run-beta-release-gate.ps1
```

The final no-false-green closure command is:

```powershell
pwsh ./scripts/quality/run-beta0-final-release-check.ps1
```

If either command or report is missing, stale, manually edited, not tied to the current workspace fingerprint, or mixed across child-report `runId` values, Beta 0 is blocked.

## Beta 0 Non-Goals

- Public production deployment.
- Arbitrary AI shell execution.
- Arbitrary custom code execution.
- Manual smoke verification.
- Production auth modes.
- Multi-cloud deployment.
- UI-only verification as the required proof.
