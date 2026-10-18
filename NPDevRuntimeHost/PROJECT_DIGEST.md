# NPDevRuntimeHost Project Digest

## Purpose
NPDevRuntimeHost is the reusable Spring Boot base template that the Generator copies into assembled final apps.

## Role In Workspace
- Provides static host code, resources, migrations, and dependency template wiring.
- Receives generated artifacts only after assembly into a disposable final app copy.
- Is not the place where NPDev users edit domain models or generated code.

## Main Deliverables
- `build.gradle.template` used by assembled apps.
- Host REST APIs and runtime/admin support code.
- Runtime schema realization in `runtimehost-core` (`com.finalexec.db.*`: `SchemaLifecycleExecutor`, the `Migration*Store` classes); there is no `db\migration` resource directory.
- Host-side security, scheduling, plugin, and runtime config.

## Key Paths
- `build.gradle.template`
- `src\main\java\com\finalexec`
- `src\main\java\com\finalexec\config`
- `src\main\java\com\finalexec\api`
- `src\main\java\com\finalexec\npdev\service`
- `runtimehost-core\src\main\java\com\finalexec\db`
- `<Build>\runtimehost-libs` (staged by `sync-runtimehost-libs.ps1` via `Get-NPDevRuntimeHostLibsDir`; there is no `libs` folder here)

## Operational Expectations
- Emitted generator dependencies must be declared in the template.
- Runtime readiness evidence must be truthful and derived from the aggregate gate.
- Security and plugin sandbox controls should be covered by focused tests.
- Runtime host sample verification should clean build residue after success or failure.

## Typical Commands
Do not build or run directly from `NPDevRuntimeHost`.
Generated final apps are assembled by `NPDevGenerator`.
Run from the assembled final app root instead.

## Current Maturity Focus
- RuntimeHost dependency completeness.
- Async resume and tenant isolation proof.
- Observability and security hardening evidence.
