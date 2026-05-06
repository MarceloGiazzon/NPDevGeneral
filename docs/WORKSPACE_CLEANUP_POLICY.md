# Workspace Cleanup Policy

NPDev source should stay separate from generated state. Build output, sample output, local dependency caches, release reports, logs, and workspace snapshots are disposable unless explicitly promoted into a fixture or release artifact.

## Intentionally Versioned

These remain source and should not be cleaned as residue:

- `README.md`, `PROJECT_DIGEST.md`, `MIGRATION_DIGEST.md`, and committed sample input/reference docs.
- Canonical sample `Input` trees and deliberately checked-in generated app scaffolds such as sample `Output\App\.gitignore`, `PROJECT_DIGEST.md`, and `MIGRATION_DIGEST.md`.
- The current authoritative release decision in `scripts\reports\out\beta-release-gate-report.json` while a release run is active.

## Disposable By Default

- Gradle/npm/build caches: `.npdev-gradle`, `.gradle`, `build`, `node_modules`, `dist`, `coverage`, `target`. New Gradle caches default outside the workspace under the user cache directory, unless `NPDEV_GRADLE_USER_HOME` or `NPDEV_LOCAL_CACHE_ROOT` is set.
- RuntimeHost local jars: staged outside the workspace under the user cache directory by default, unless `NPDEV_RUNTIMEHOST_LIBS_DIR` is set. Generated apps reference that external cache instead of copying jars into source, evidence, or sample output folders.
- Generated app/sample output: `Output`, `RunOutput`.
- Rebuildable RuntimeHost assembly residue: `NPDevRuntimeHost\libs`, `NPDevRuntimeHost\npdev-generated`, `NPDevRuntimeHost\npdev-meta`, generated `NPDevRuntimeHost\build.gradle`, and `npdev-build-info.properties`.
- IDE-local metadata: `.idea`, `.vscode`, `*.iml`.
- Local runtime residue: `*.log`, `*.pid`, temporary files.
- Root workspace archives: `*.zip`, `*.7z`, `*.rar`.
- Empty archived-source placeholders such as empty `src-disabled` folders.

Empty `NPDevSamples\<sample>\Output\Reports` scaffolds are intentionally retained because layout checks expect them. Generated files inside sample `Output` directories remain disposable.

## Evidence Handling

The active release decision remains `scripts\reports\out\beta-release-gate-report.json`. Release bundles under `scripts\reports\releases` are artifacts and should be uploaded or archived outside source control when needed.

Local retention keeps only the newest 5 release bundles by default. If a bundle is a milestone worth keeping longer, export it outside the workspace first or pass it explicitly to the prune script as a preserved bundle.

Do not manually combine focused reports into a release claim. Rerun:

```powershell
pwsh -File scripts\quality\run-beta-release-gate.ps1
```

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

Full rebuildable-artifact cleanup:

```powershell
pwsh -File scripts\hygiene\clean-rebuildable-artifacts.ps1
```

That wrapper removes heavyweight disposable workspace state and local RuntimeHost synced jars/build residue, while preserving release evidence under `scripts\reports\out` and `scripts\reports\releases` by default. By default it writes its report to the OS temp directory so the workspace does not get a fresh cleanup artifact immediately after being cleaned.

Evidence cleanup is intentionally opt-in:

```powershell
pwsh -File scripts\hygiene\clean-rebuildable-artifacts.ps1 -CleanReportsOut -CleanReleaseBundles
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
