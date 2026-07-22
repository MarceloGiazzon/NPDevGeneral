# Release process (LNCH-23)

Formalizes tag → gate → changelog → artifact, building on `run-beta-release-gate.ps1` (the
existing gate seed) and the beta0 tag-immutability rules already written into
`docs/MATURITY_CLOSURE_LEDGER.md`.

## The process

1. **Land the work.** All changes for the release are committed on the release branch (today:
   `beta1-vision-spine` or whatever the current mainline is), gates green (DSL/kernel/generator/
   RuntimeHost suites; `run-beta-release-gate.ps1` for anything claiming beta-release status).
2. **Update `CHANGELOG.md`.** A new top-level entry, dated, under the version being released.
   Categorize entries (Added / Changed / Fixed / Security) — see the seed entry in
   `CHANGELOG.md` for the format. Any **behavior change** per
   `docs/architecture/APP_UPGRADE_CONTRACT.md`'s compatibility rule gets its own explicit line
   under a "Behavior changes" sub-heading, so an upgrader auditing what changed between versions
   has a single place to look.
3. **Run the release-checklist gate**:
   ```powershell
   pwsh -File scripts/quality/run-release-checklist-gate.ps1 -ExpectedVersion <version>
   ```
   Refuses to pass if: `LICENSE` is missing, `CHANGELOG.md` has no entry for `<version>`, or the
   current `HEAD` is not tagged `v<version>`. This is deliberately a separate, lightweight script
   from `run-beta-release-gate.ps1`'s much larger evidence-report machinery — it checks the
   mechanical release-hygiene items LNCH-23 asks for, not code quality (the other gates already
   own that).
4. **Tag.** `git tag -a v<version> -m "<one-line summary>"`, then push the tag. Per the beta0
   tag-immutability precedent: once pushed, a release tag is never force-moved or deleted — a
   mistake gets a new patch tag, not a rewritten one.
5. **Build the artifact.** For now: the tagged commit itself *is* the artifact (self-hosted,
   source-first — see `docs/adr/ADR-0007-distribution-model.md`). A packaged MCP/CLI/knowledge-
   corpus distribution (ADR-0006's P0 slice) will get its own build/publish step here once that
   packaging work lands.
6. **Before any *public* release announcement specifically** (not required for an internal/tagged
   release): confirm the trademark-check item in `docs/adr/ADR-0007-distribution-model.md` has
   actually been done, not just noted as pending.

## Versioning

Semantic-ish: `MAJOR.MINOR.PATCH`.

- **MAJOR** — a breaking model-schema change (`dslVersion` bump) or a runtime behavior change
  serious enough that an unreviewed upgrade could break a running app (per the compatibility rule
  in `docs/architecture/APP_UPGRADE_CONTRACT.md`).
- **MINOR** — additive schema changes (new optional `$defs` fields — this session's `onFailure`,
  `flowSchedule`, `conceptAccess`, `row_version`, etc. are all this shape), new capabilities/
  adapters, new gate coverage.
- **PATCH** — bug fixes with no schema or intentional-behavior change.

`BuildInfoEmitter.GENERATOR_VERSION` (currently the hardcoded literal `"0.1.0"`, embedded into
every generated FinalApp's `npdev-build-info.properties`) should track this tag going forward —
flagged in `APP_UPGRADE_CONTRACT.md` as a follow-up wiring task, not yet automated.

## What the gate does NOT check

The release-checklist gate is deliberately narrow. It does not re-run the DSL/kernel/generator/
RuntimeHost test suites (that's `run-generator-gate.ps1`/`run-runtimehost-gate.ps1`/module test
tasks — run those first, separately, as step 1 above already says) and does not check trademark
clearance (a human step, not a scriptable one — see ADR-0007).

## Local gate runs are tuned; CI is not

The checked-in `gradle.properties` / `build.gradle` files set `org.gradle.parallel`,
`org.gradle.caching`, `org.gradle.workers.max=4`, a 3 GB heap and a long daemon idle timeout. The
memory math is documented in each file: six independent Gradle builds, one daemon each, against
32 GB total.

**CI does not use these settings.** The practical consequence, and the reason this is written down:

- **A flake rate or timing measured locally does not transfer to CI.** A test that fails "about 1 in
  5 under load" on a 4-way-parallel local run says nothing about its rate on a CI worker with
  different parallelism, different core count and no build cache.
- **Any recorded measurement must state the configuration it was taken under** — serial or parallel,
  which properties were in effect, and whether `--rerun-tasks` was used. A cached Gradle task reports
  `UP-TO-DATE` and executes **zero** tests while still printing `BUILD SUCCESSFUL`; treating that as a
  measurement has produced false results in this repo more than once.
- **Several similarly-named `test-results\test` directories coexist** under
  `D:\WorkSpace\NPDev\Build\gradle\`, because the module builds redirect `layout.buildDirectory` out
  of the repo. Confirm a results directory's modification time falls inside the run you are recording
  before quoting a count from it.
