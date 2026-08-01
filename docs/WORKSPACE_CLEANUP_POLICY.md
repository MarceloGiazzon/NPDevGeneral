# Workspace Cleanup Policy

NPDev source should stay separate from generated state. Build output, sample output, local dependency caches, release reports, logs, and workspace snapshots are disposable unless explicitly promoted into a fixture or release artifact.

## Intentionally Versioned

These remain source and should not be cleaned as residue:

- `README.md`, `PROJECT_DIGEST.md`, `MIGRATION_DIGEST.md`, and committed sample input/reference docs.
- Canonical sample `Input` trees and deliberately checked-in generated app scaffolds such as sample `Output\App\.gitignore`, `PROJECT_DIGEST.md`, and `MIGRATION_DIGEST.md`.
- The current authoritative release decision in `scripts\reports\out\beta-release-gate-report.json` while a release run is active.

## Disposable By Default

- Gradle/npm/build caches: `.npdev-gradle`, `.gradle`, `build`, `node_modules`, `dist`, `coverage`, `target`. New Gradle caches default outside the workspace under the user cache directory, unless `NPDEV_GRADLE_USER_HOME` or `NPDEV_LOCAL_CACHE_ROOT` is set.
- IDE Java-language-server compile output: `bin` (Eclipse JDT/VSCode Java extension default output folder, independent of Gradle's `build`).
- RuntimeHost local jars: staged outside the workspace in `..\NPDev_General__OutsideRepo\runtimehost-libs` by default, unless `NPDEV_RUNTIMEHOST_LIBS_DIR` is set. Generated apps reference that external folder instead of copying jars into source, evidence, or sample output folders.
- Generated app/sample output: `Output`, `RunOutput`.
- Rebuildable RuntimeHost assembly residue: `NPDevRuntimeHost\libs`, `NPDevRuntimeHost\npdev-generated`, `NPDevRuntimeHost\npdev-meta`, generated `NPDevRuntimeHost\build.gradle`, and `npdev-build-info.properties`.
- IDE-local metadata: `.idea`, `.vscode`, `*.iml`.
- Local runtime residue: `*.log`, `*.pid`, temporary files.
- Root workspace archives: `*.zip`, `*.7z`, `*.rar`.
- Empty archived-source placeholders such as empty `src-disabled` folders.

Empty `NPDevSamples\<sample>\Output\Reports` scaffolds are intentionally retained because layout checks expect them. Generated files inside sample `Output` directories remain disposable.

## Slim Workspace Gate

`NPDev_General` is source, not an artifact cache. The blocking slimness policy is enforced by:

```powershell
pwsh -File scripts\hygiene\Test-WorkspaceSlimness.ps1
```

Default limits:

- maximum workspace size, excluding `.git`: `75 MB`
- maximum workspace file count, excluding `.git`: `3400` (raised from 3000 in Move 12 —
  Moves 6-11 landed ~180 legitimate new tracked files (typed-surface AST/compiled classes,
  validation tests, ledger items); the residue causing the original overage was rebuildable
  trees, not source, and those are cleaned by the commands below, not by raising this number)
- maximum `scripts` size: `10 MB`
- maximum `scripts` file count: `500`
- maximum `scripts\reports\out` size: `15 MB`
- forbidden residue: `scripts\reports\tmp`, `scripts\reports\cache`, subproject `.gradle`, `build`, `bin`, `target`, `dist`, `coverage`, `node_modules`, `RunOutput`, sample `Output` (at any nesting depth), RuntimeHost generated assembly folders, and archives.
- forbidden jars: all `*.jar` files except `gradle\wrapper\gradle-wrapper.jar`.

Any generated app, release scratch area, local dependency jar, state zip, or diagnostic bundle that would violate these limits must be written under `..\NPDev_General__OutsideRepo` or an explicit external cache path. Set `NPDEV_WORKSPACE_SCRATCH_ROOT` only when a gate needs a different external scratch root.

## Evidence Handling

The active release decision remains `scripts\reports\out\beta-release-gate-report.json`. Release bundles under `scripts\reports\releases` are artifacts and should be uploaded or archived outside source control when needed.

Local retention keeps only the newest 5 release bundles by default. If a bundle is a milestone worth keeping longer, export it outside the workspace first or pass it explicitly to the prune script as a preserved bundle.

Do not manually combine focused reports into a release claim. Rerun:

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
```

## Enforcement Hook

Git hooks are not versioned by git itself, so each clone must install the pre-commit hook once:

```powershell
pwsh -File scripts\hooks\install.ps1
```

This installs `scripts\hooks\pre-commit.ps1` as `.git\hooks\pre-commit`, which runs `Test-WorkspaceSlimness.ps1` before every commit and blocks the commit (with a pointer to the cleanup command) if the workspace has drifted out of policy. Without this hook installed, residue can silently accumulate until someone happens to run the gate manually.

## Cleanup Command

Preview cleanup:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1 -DryRun
```

Preview IDE-local cleanup only:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1 -OnlyIdeMetadata -DryRun
```

Apply cleanup:

```powershell
pwsh -File scripts\hygiene\clean-workspace-state.ps1
```

The cleanup script verifies every recursive delete target is inside the workspace and belongs to an explicit disposable category before removal.
It removes nested subproject `.gradle` directories, build outputs, sample outputs, node/npm outputs, and `scripts\reports\tmp`.

Full rebuildable-artifact cleanup:

```powershell
pwsh -File scripts\hygiene\clean-rebuildable-artifacts.ps1
```

That wrapper removes heavyweight disposable workspace state and local RuntimeHost synced jars/build residue, while preserving release evidence under `scripts\reports\out` and `scripts\reports\releases` by default. By default it writes its report to the OS temp directory so the workspace does not get a fresh cleanup artifact immediately after being cleaned.

Evidence cleanup is intentionally opt-in:

```powershell
pwsh -File scripts\hygiene\clean-rebuildable-artifacts.ps1 -CleanReportsOut -CleanReleaseBundles
```

## Other Workspace Hygiene Checks

Gradle wrapper consistency across the repo's multiple Gradle roots (root, `NPDevContract/dsl`,
`NPDevGenerator`, `NPDevKernel`) is checked manually, not wired into a gate:

```powershell
pwsh -File scripts\hygiene\Test-GradleWrapperConsistency.ps1
```

A fresh clone should move the React editor's `node_modules` outside the source tree via a directory
junction (keeps the workspace slim per the size limits above), run once after cloning or whenever the
junction is missing:

```powershell
pwsh -File scripts\hygiene\Setup-EditorNodeModules.ps1
```

## Release Bundle Retention

Preview release bundle pruning:

```powershell
pwsh -File scripts\hygiene\prune-release-evidence.ps1 -DryRun
```

Apply the default retention window:

```powershell
pwsh -File scripts\hygiene\prune-release-evidence.ps1 -KeepLatest 5
```

Preserve a specific bundle while pruning:

```powershell
pwsh -File scripts\hygiene\prune-release-evidence.ps1 -KeepLatest 5 -PreserveReleaseBundle runtimehost-beta-YYYYMMDD-HHMMSS
```
