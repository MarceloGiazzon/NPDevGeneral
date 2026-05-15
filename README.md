# NPDev General Workspace

This folder is a workspace, not a root build project.

Do not add a root build.gradle, settings.gradle, .gradle folder, or package.json here unless the architecture decision changes.

Start with PROJECT_DIGEST.md in this folder. It is the high-level architecture and philosophy guide that explains how the subprojects fit together.

Each buildable subproject should own its own build and dependencies. `NPDevRuntimeHost` is the static host base/template; the Generator materializes its `build.gradle.template` inside assembled final apps.

- NPDevContract
- NPDevRuntimeHost
- NPDevEditor
- NPDevGenerator
- NPDevKernel
- NPDevSamples

After reading the root PROJECT_DIGEST.md, read PROJECT_DIGEST.md inside each subproject before editing that area.

Current root automation entrypoints:

- `./npdev --version`
- `./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json`
- `./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json`
- `./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output build/npdev-generated`
- `./npdev report bootstrap`
- `pwsh -File scripts/quality/run-generator-gate.ps1`
- `pwsh -File scripts/quality/run-runtimehost-gate.ps1`
- `pwsh -File scripts/quality/run-json-schema-validator-tests.ps1`
- `pwsh -File scripts/quality/run-ai-schema-validation.ps1`
- `pwsh -File scripts/quality/run-ai-contract-normalizer-tests.ps1`
- `pwsh -File scripts/quality/run-controlled-command-runner-tests.ps1`
- `pwsh -File scripts/quality/run-ai-rest-smoke-verifier-tests.ps1`
- `pwsh -File scripts/quality/run-sample-matrix.ps1`
- `pwsh -File scripts/quality/run-runtimehost-staged-jar-preflight.ps1`
- `pwsh -File scripts/hygiene/Test-WorkspaceSlimness.ps1`
- `pwsh -File scripts/quality/run-docker-linux-proof.ps1`
- `pwsh -File scripts/quality/run-ai-beta-gate.ps1`
- `pwsh -File scripts/quality/run-report-schema-validation.ps1`
- `pwsh -File scripts/quality/run-doc-entrypoint-validation.ps1`
- `pwsh -File scripts/quality/run-report-provenance-tests.ps1`
- `pwsh -File scripts/quality/run-beta-release-gate.ps1`
- `pwsh -File scripts/quality/run-beta0-final-closure-gate.ps1`
- `pwsh -File scripts/quality/run-beta0-final-release-check.ps1`
- `pwsh -File scripts/quality/run-traceable-local-release.ps1`
- `pwsh -File scripts/quality/run-roadmap-closure-check.ps1`

## AI-only Beta 0

Expanded Beta 0 scope is enforced by `scripts/policy/beta0-scope.json`.
The current release contract includes custom UI panels, custom procedures, multi-tenancy, authentication, roles, and workflow engine as blocking surfaces.
Older simple CRUD-only Beta 0 reports are diagnostic only and cannot satisfy the expanded final release check.
No-false-green release hardening is documented in `docs/beta/ai-only-beta-0-no-false-green-scope.md`.

Target AI-only proof:

```powershell
pwsh ./scripts/quality/run-ai-beta-gate.ps1
```

Target release proof:

```powershell
pwsh ./scripts/quality/run-beta-release-gate.ps1
```

No-false-green closure proof:

```powershell
pwsh ./scripts/quality/run-traceable-local-release.ps1 -WorkspaceRoot .
pwsh ./scripts/quality/run-roadmap-closure-check.ps1 -WorkspaceRoot .
```

Docker/Linux proof is blocking Beta 0 evidence. Use the canonical proof script so CI compatibility, command timeouts, logs, and report schema fields are captured:

```powershell
pwsh ./scripts/quality/run-docker-linux-proof.ps1
```

Current source-of-truth reports:

- `scripts/reports/out/ai-beta-gate-report.json`
- `scripts/reports/out/beta-release-gate-report.json`
- `scripts/reports/out/beta0-final-closure-report.json`
- `scripts/reports/out/beta-release-evidence-manifest.json`
- `scripts/reports/out/ai-beta-reproducibility-report.json`
- `scripts/reports/out/runtimehost-staged-jar-preflight-report.json`
- `scripts/reports/out/docker-linux-parity-report.json`

These gate scripts are required for AI-only Beta 0. If either script or report is missing, stale, manually edited, not tied to the current workspace fingerprint, or mixed across different child-report `runId` values, Beta 0 is blocked.

Release readiness has one source of truth: `scripts/reports/out/beta-release-gate-report.json`, produced by the aggregate beta release gate. Focused gate reports are evidence only and must be interpreted through that aggregate report. See `docs/RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md`.
`release-ready-summary.json` is release-grade only when that aggregate report is `passed` and the evidence provenance is traceable to Git or CI. Local unanchored runs remain diagnostic-only.

Runbook and closure checklist:

- `docs/beta/ai-only-beta-0-runbook.md`
- `docs/beta/ai-only-beta-0-closure-checklist.md`

Beta 0 tag gate dry-run:

```powershell
pwsh ./scripts/release/create-beta0-tag.ps1 -Version beta0 -DryRun
```

The release blocker execution roadmap and closing evidence are tracked in `docs/RELEASE_BLOCKER_EXECUTION_ROADMAP.md`.

Frontend release evidence is blocking for Beta0: `scripts/reports/out/frontend-gate-report.json` records toolchain versions, lockfile fingerprints, the Gradle/npm command, output tail, and generated-residue checks. See `docs/FRONTEND_GATE_REPRODUCIBILITY.md`.

Sample matrix release evidence requires full policy-defined coverage. `scripts/reports/out/sample-matrix-report.json` records coverage by sample kind, input fingerprints, and release eligibility. See `docs/SAMPLE_MATRIX_RELEASE_POLICY.md`.

Keep new scripts product-facing: doctor checks, component gates, sample helpers, or AI/custom procedure utilities.

## Portable NPDev CLI

Use `NPDEV_ROOT` when invoking NPDev from outside the workspace. From the repository root on Linux or macOS:

```sh
./npdev validate model NPDevContract/dsl/resources/Models/canonical-demo/model.json
./npdev normalize ai-model golden-ai-scenarios/base-ai-loop/ai-model.json
./npdev generate app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json --config NPDevContract/dsl/resources/Models/canonical-demo/config.json --output build/npdev-generated
./npdev report bootstrap
```

On Windows, `npdev.bat` provides the same commands. The PowerShell scripts remain available as compatibility wrappers for existing automation.
