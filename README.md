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

- `pwsh -File scripts/quality/run-beta-release-gate.ps1`
- `pwsh -File scripts/doctor/npdev-doctor.ps1`
- `pwsh -File scripts/quality/run-contract-gate.ps1`
- `pwsh -File scripts/quality/run-editor-gate.ps1`
- `pwsh -File scripts/quality/run-frontend-gate.ps1`
- `pwsh -File scripts/quality/run-generator-gate.ps1`
- `pwsh -File scripts/quality/run-kernel-gate.ps1`
- `pwsh -File scripts/quality/run-runtimehost-gate.ps1`
- `pwsh -File scripts/quality/run-traceable-local-release.ps1`
- `pwsh -File scripts/maturity_adv/run-prioritized-control-board.ps1`

Release readiness has one source of truth: `scripts/reports/out/beta-release-gate-report.json`, produced by `run-beta-release-gate.ps1`. Focused gate reports are evidence only and must be interpreted through that aggregate report. See `docs/RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md`.
`release-ready-summary.json` is release-grade only when that aggregate report is `passed` and the evidence provenance is traceable to Git or CI. Local unanchored runs remain diagnostic-only.

The release blocker execution roadmap and closing evidence are tracked in `docs/RELEASE_BLOCKER_EXECUTION_ROADMAP.md`.

Frontend release evidence is intentionally diagnostic: `scripts/reports/out/frontend-gate-report.json` records toolchain versions, lockfile fingerprints, the Gradle/npm command, output tail, and generated-residue checks. See `docs/FRONTEND_GATE_REPRODUCIBILITY.md`.

Sample matrix release evidence requires full policy-defined coverage. `scripts/reports/out/sample-matrix-report.json` records coverage by sample kind, input fingerprints, and release eligibility. See `docs/SAMPLE_MATRIX_RELEASE_POLICY.md`.

Keep new scripts product-facing: doctor checks, component gates, sample helpers, or AI/custom procedure utilities.
