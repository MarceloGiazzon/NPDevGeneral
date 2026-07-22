# NPDev Build Output Location Policy

## Status

Permanent rule for Cursor, Codex, and any AI/code agent working on NPDev.

## Rule

Do not create Gradle, Node, Playwright, test, or generated build outputs inside:

```text
D:\WorkSpace\NPDev\NPDev_General
```

All build outputs, Gradle user home, generated apps, validation reports, temporary scripts, generated databases, and runtime test artifacts must go under:

```text
D:\WorkSpace\NPDev\Build
```

## Why

`D:\WorkSpace\NPDev\NPDev_General` is the source workspace. It must remain clean and reviewable.

Build artifacts under the source workspace create false diffs, stale compiled classes, wrong dependency resolution, and confusing zip/state snapshots.

## Required locations

Use these locations:

```text
D:\WorkSpace\NPDev\Build
D:\WorkSpace\NPDev\Build\_gradle-init
D:\WorkSpace\NPDev\Build\_chatgpt_patchs
D:\WorkSpace\NPDev\Build\generated-finalapps
D:\WorkSpace\NPDev\Build\databases
D:\WorkSpace\NPDev\Build\runtimehost-libs
```

## Forbidden source-local output folders

Agents must not create or modify these under `D:\WorkSpace\NPDev\NPDev_General`:

```text
.gradle
build
node_modules
dist
test-results
playwright-report
```

Examples of forbidden paths:

```text
D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator\.gradle
D:\WorkSpace\NPDev\NPDev_General\NPDevGenerator\generator\build
D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\.gradle
D:\WorkSpace\NPDev\NPDev_General\NPDevKernel\kernel\build
D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost\.gradle
D:\WorkSpace\NPDev\NPDev_General\NPDevEditor\.gradle
D:\WorkSpace\NPDev\NPDev_General\NPDevEditor\ui-react\node_modules
D:\WorkSpace\NPDev\NPDev_General\NPDevEditor\ui-react\dist
```

## Required Gradle practice

Use the external Build-scoped runner when building source subprojects:

```text
D:\WorkSpace\NPDev\Build\_chatgpt_patchs\move-other-subprojects-to-external-build\Invoke-NPDevExternalGradle.ps1
```

The build root must be:

```text
D:\WorkSpace\NPDev\Build
```

Do not run `gradlew.bat` directly from source subprojects unless the command explicitly redirects build outputs and Gradle user home to `D:\WorkSpace\NPDev\Build`.

## RuntimeHost rule

Do not build this as a product subproject:

```text
D:\WorkSpace\NPDev\NPDev_General\NPDevRuntimeHost
```

`NPDevRuntimeHost` is a template copied into generated FinalApps.

Build generated FinalApps under:

```text
D:\WorkSpace\NPDev\Build\generated-finalapps\<app-id>\App
```

## Required before/after hygiene check

Before finishing a task, agents must confirm that no new or modified source-local output folders were created under:

```text
D:\WorkSpace\NPDev\NPDev_General
```

If pre-existing source-local output folders already exist, report them as pre-existing hygiene debt. Do not mix them with new changes.

## Correct cleanup location

Cleanup scripts and reports must be written under:

```text
D:\WorkSpace\NPDev\Build\_chatgpt_patchs\source-build-hygiene
```

not inside the source repo, except for this policy document itself.

## Stop rule

Stop immediately and report a blocker if a required command cannot be run without creating build/temp output under:

```text
D:\WorkSpace\NPDev\NPDev_General
```
