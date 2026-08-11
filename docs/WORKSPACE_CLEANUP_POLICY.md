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
- maximum workspace file count, excluding `.git`: `4000`

  **This number has been raised four times (3000 → 3400 → 3500 → 3600 → 4000) while the size limit
  has never moved.** Each raise was justified and recorded in `Test-WorkspaceSlimness.ps1`'s own
  parameter comment, and that is exactly how a limit stops meaning anything, so the history matters
  more than the current value:

  - `3000 → 3400` (Move 12) — ~180 legitimate new tracked files; the actual overage was rebuildable
    trees, cleaned by the commands below rather than by the number.
  - `3400 → 3500` (2026-08-08) and `3500 → 3600` (2026-08-10) — small tracked fixtures and modules.
  - `3600 → 4000` (2026-08-11, owner decision on close-the-gaps-2026-08-10 D-c, which had proposed
    retiring the count instead).

  **Be precise about what 4000 buys, because the obvious story is wrong.** The count blocked two
  commits during that session, and it is tempting to call that a false positive on whoever was
  running the gates. Reading the report says otherwise: the violations were
  `NPDevSamples\simple-contact-intake\Output` (824 files), `dsl-conformance-max\Output` (243) and
  three `.gradle` trees — a generated sample Output tree in the source workspace, which is exactly
  what this check exists to catch. It was right, and `clean-workspace-state.ps1` fixed it.

  Cleaned, the tree measures **3542**; mid-gate-run it measures **4639**. So 4000 does *not* stop a
  gate run from tripping this, and it should not — those files do not belong here. What it buys is
  ~450 of headroom for legitimately tracked files, so ordinary work stops bumping the ceiling every
  few days and the next trip is more likely to mean something.

  **If it needs raising a fifth time, retire it rather than raise it.** The size limit is the one
  that has actually tracked bloat (28 of 75 MB through all four raises).
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

---

## Quarantining removal candidates

`scripts/hygiene/quarantine-to-outside-repo.ps1` moves candidates **out of the repo** into a dated
folder under `NPDev_General__OutsideRepo/quarantine/`, writing a manifest that restores every one of
them exactly.

It exists because "is this still used?" should be a **measurement, not an opinion**:

1. quarantine the candidate
2. run `scripts/quality/run-all-gates.ps1`
3. green → it was genuinely unused · red → restore it, and you have just learned why it exists

Being wrong is free, which is the only reason it is reasonable to try.

```powershell
# see what would move, touch nothing
pwsh -File scripts\hygiene\quarantine-to-outside-repo.ps1 -WhatIf

# regenerable byproducts only (node_modules, sample Output, .gradle, __pycache__)
pwsh -File scripts\hygiene\quarantine-to-outside-repo.ps1 -Scope ephemeral

# the documents and scripts measured as referenced by nothing
pwsh -File scripts\hygiene\quarantine-to-outside-repo.ps1 -Scope orphans

# test one specific thing, e.g. whether a sample app is still load-bearing
pwsh -File scripts\hygiene\quarantine-to-outside-repo.ps1 -Scope candidate -Path NPDevSamples/durable-workflow-demo

# put a batch back
pwsh -File scripts\hygiene\quarantine-to-outside-repo.ps1 -Restore quarantine-20260810-143000
```

### What it refuses, and why

**Process docs** — `*_PLAN.md`, `*_CHECKLIST*.md`, `*_FINDINGS*.md`, `MOVE*`, `SCREEN_TAXONOMY` —
are refused outright. Measured 2026-08-10: all 23 tested are cited by live files including
`CLAUDE.md`, `CONTRIBUTING.md` and a CI workflow. Worse,
`scripts/quality/check-blocker-citation-freshness.py` is **scoped to those exact filenames**, so
moving them makes that check pass while checking zero files — a silent green.

### What the audit actually found

The tracked repo is not bloated. 79 of 83 docs are referenced by something; 201/201 scripts are
classified and invocation-declared; there are **zero** tracked build artefacts. The true orphan set
is 4 documents and 1 retired script, about 7 KB in total. The accumulation worth cleaning is the
untracked byproducts, and `Test-WorkspaceSlimness.ps1` already blocks a commit when they build up.
