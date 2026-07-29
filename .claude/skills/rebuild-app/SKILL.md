---
name: rebuild-app
description: Regenerate and rebuild an NPDev AppGen FinalApp after a platform code change, refreshing all three build caches in the correct order so the running app actually reflects the change. Use after editing kernel/adapter/generator Java, or whenever a rebuilt app "ignores" your change (stale jar / stale generator).
---

# Rebuild an NPDev FinalApp without stale-cache surprises

NPDev has **three independent build caches** that must be refreshed before a regenerated app reflects
a platform code change, and their default directories do **not** line up. Getting one wrong is the
single most common "my change had no effect" failure.

## When to run
- Changed **kernel/adapter Java** → the `runtimehost-libs` restage is required.
- Changed **generator Java / templates** → the `generator-runtime` cache refresh is required.
- Changed **both** → both. A pure **model/JSON** change needs neither (just regenerate the app).

## Do this
Prefer the one wrapper that threads a single `-RuntimeHostLibsDir` through the restage and the build
(so the sync writes where the build reads):

```powershell
pwsh -File scripts/appgen/Rebuild-And-Restage.ps1 -AppFolder <appFolder>
# generator-only change:  add -SkipLibs
# kernel-only change:     add -SkipGeneratorRuntime
```

The wrapper runs, in order: `sync-runtimehost-libs.ps1 -BuildLocalJars -RuntimeHostLibsDir <dir>` →
`AppGen/generator-runtime/prepare-npdev-generator-runtime.ps1 -RuntimeRoot <root>` →
`Build-NpdevApp.ps1 ... -RuntimeHostLibsDir <dir>` → starts the app and runs the panel-provenance
impact gate (`_ops/Check-Provenance.ps1`) against its live bundle, failing the rebuild if a
**confirmed** manifest now references a field/invocation the model no longer has (add
`-SkipProvenanceCheck` to skip; `-GenerateOnly` skips it automatically since there is no jar to run).

## Hard rules (don't skip)
- **Pass the SAME `-RuntimeHostLibsDir` to sync and build.** Their defaults differ; if you run the
  steps by hand and let each default, the app keeps a stale jar. (`runtimehost-libs-dir-mismatch`)
- **Never hand-edit files under an app's `npdev-generated/`.** It is hash-verified — regenerate from
  the model / template instead. (`hash-guarded-npdev-generated`)
- **Build output goes to `D:\WorkSpace\NPDev\Build`**, never inside the source repo.

## If the build dir hits a VS Code Java/Gradle file lock
Bump the build-root suffix (`-alt` / `-hNN`) to a fresh directory, or reboot to clear the lock — the
established workaround.

## Verify the change actually landed
Boot the app and confirm the behavior, or check the freshly built jar's timestamp is newer than your
edit. For UI/runtime behavior, follow up with the **verify-in-browser** skill.
