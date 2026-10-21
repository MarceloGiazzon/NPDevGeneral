# Leaving NPDev: running a generated app as a plain Gradle project

A generated app is an ordinary Spring Boot project. This page walks one out of NPDev's tree and builds
and runs it with nothing but a JDK and Gradle, and says plainly what you give up by doing so.

**What was actually tested.** The `npdev-canary` sample was generated, its app folder copied to an empty
directory outside the repo and the Build root, and built with a brand-new Gradle home (no cache):
`BUILD SUCCESSFUL in 10m 14s` on Windows 11 with JDK 17. The jar was then booted with `java -jar` and
answered `/actuator/health`, a flow call and a panel-metadata call. Not tested: Linux/macOS, and any
engine other than the default H2 file database. A server engine works the same way the generated app's
own `README.md` describes, but this page has not proven it.

## Steps

1. **Find the app folder.** It is the directory that contains `build.gradle`, `gradlew` and
   `npdev-generated/`.
2. **Copy it out of NPDev's tree**, to anywhere that is not inside the NPDev clone:
   `cp -r <app folder> ~/my-ejected-app` (Windows: `Copy-Item -Recurse`).
3. **Optional cleanup.** `_ops/`, `.npdev-root`, `reset-db.ps1` and `reset-db-docker.ps1` are NPDev's
   operations toolbox. The app builds and boots with them deleted (tested), and also with them present.
   Keep `libs/`, `npdev-generated/`, `src/`, `secrets/`, `gradle/`, `build.gradle`, `settings.gradle`,
   `gradle.properties`.
4. **Build.** Needs JDK 17+ and internet access (Gradle 9.5.1 and Maven Central; about 10 minutes the
   first time):
   ```sh
   ./gradlew bootJar
   ```
   Nothing else is needed. `gradle.properties` already points `npdevRuntimeHostLibsDir` at the app's own
   `libs/npdev-runtime`, so no NPDev clone or `npdev setup` is involved.
5. **Provide an API key.** Run from the app folder. `secrets/api-key.env` ships with a working
   `NPDEV_AUTH_API_KEYS=<key>=dev:developer:admin` line; to use your own, replace `<key>`:
   ```sh
   export $(grep NPDEV_AUTH_API_KEYS secrets/api-key.env)
   ```
6. **Run it from the app folder**, with the `prod` profile (an un-profiled boot fails closed on purpose):
   ```sh
   java -jar build/libs/FinalExec-0.1.0.jar --spring.profiles.active=prod
   ```
   Run it **from the app folder**: the integrity guard below looks for `./npdev-generated` relative to the
   current directory. From elsewhere, set `NPDEV_STRICT_EXECUTION_GENERATED_ROOT=<absolute path>`.
   The port is `PORT` (or `SERVER_PORT`), default 8080.
7. **Smoke it.** Every `/api/**` call needs `X-Api-Key: <key>`:
   ```sh
   curl localhost:8080/actuator/health
   curl -H "X-Api-Key: <key>" localhost:8080/api/runtime/metadata/ui/panels/<AnyPanel>
   ```

That is seven steps, so there is no `npdev eject` command and none is planned.

## The integrity guard (`StrictExecutionValidator`)

At boot the app hashes the whole `npdev-generated/` tree and compares it with
`npdev-generated/src/main/resources/npdev/support/generated-folder.signature.properties`. An untouched
tree passes with no flag (this is why steps 1-7 need none). **Any** change, an edited file, an extra
file or a deleted one, makes the app refuse to start with
`Strict execution signature mismatch under <path>: ...`.

Measured on the ejected canary after appending one comment line to a generated controller:

| Settings | Result |
|---|---|
| defaults (`governed`) | refuses to start, signature mismatch |
| `NPDEV_EXECUTION_MODE=relaxed` only | **still refuses**: relaxed mode still verifies a present tree |
| `NPDEV_STRICT_EXECUTION_ENABLED=false` only | refuses: "cannot be disabled while execution mode is governed" |
| `NPDEV_EXECUTION_MODE=relaxed` **and** `NPDEV_STRICT_EXECUTION_ENABLED=false` | boots, health UP |

So once you edit anything under `npdev-generated/`, set both variables (or the properties
`npdev.execution.mode=relaxed` and `npdev.strict-execution.enabled=false`). That turns off the guard that
exists to stop hand edits from drifting from the model, so do it only on a project that has left NPDev
for good. Customisations that do not need to touch generated files belong in `src/` and the app's `web/`
assets instead.

## What you lose

- **Regeneration from the model.** Edit the model and NPDev can regenerate the app; once you have
  edited generated files by hand, regenerating overwrites them. There is no merge.
- **Schema evolution from the model.** The model-driven schema migration and its destructive-change
  acknowledgement flow belong to NPDev's lifecycle tooling. Afterwards you own your DDL.
- **UI shell updates.** Platform improvements to the generated admin UI arrive only by regenerating.
- **The `_ops` toolbox:** launcher, backup/upgrade/rollback helpers, `npdev monitor`.
- **Source for the runtime.** `libs/npdev-runtime/*.jar` (about 50 jars) are NPDev's kernel and adapters
  as binaries. You are leaving the generator and the model, not the runtime: those jars stay in your
  project at the version they were built from.

## What stays

Your data (`data/`, `logs/`, `secrets/`), the Super User bootstrap key file `SUPER_USER_KEY.txt`, every
REST endpoint, and the build. The project builds from its own files with `./gradlew bootJar`.
