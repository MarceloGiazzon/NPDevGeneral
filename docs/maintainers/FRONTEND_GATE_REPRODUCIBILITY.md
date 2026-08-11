# Frontend Gate Reproducibility

The NPDevEditor frontend lane is part of release evidence, not a local smoke test. It must produce enough context to diagnose npm, esbuild, Vite, Vitest, and Windows file-lock failures without relying on stale terminal output.

## Gate Command

Run:

```powershell
pwsh -File scripts\quality\run-frontend-gate.ps1
```

The gate executes:

```powershell
NPDevEditor\gradlew.bat npmTest npmBuild --no-daemon --console=plain
```

Gradle runs `npm ci --no-audit --fund=false --foreground-scripts` so dependency installation remains lockfile-based, audit noise stays in the dedicated audit gate, and package postinstall failures such as `esbuild` are visible in the command output.

## Evidence Contract

The authoritative frontend evidence file is:

```text
scripts\reports\out\frontend-gate-report.json
```

That report records:

- Input fingerprints for `package.json`, `package-lock.json`, and `NPDevEditor\build.gradle`.
- Node, npm, and Java versions.
- The exact Gradle command, exit code, duration, and output tail.
- The local Gradle/npm cache locations used during the run.
- A generated-residue check for UI output directories after Gradle finalizers run.

The frontend gate fails if the command exits non-zero or if generated UI residue remains under `NPDevEditor\ui-react`.

## Cleanup

The gate may recreate disposable caches such as `.npdev-gradle`, `NPDevEditor\.gradle`, and `NPDevEditor\build`. These are not source and should be removed with:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1
```

Do not manually interpret the frontend report as a release decision. It is copied into the aggregate beta release evidence bundle and must be interpreted through `scripts\reports\out\beta-release-gate-report.json`.
