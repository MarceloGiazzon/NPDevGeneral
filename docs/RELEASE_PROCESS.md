# Release process (LNCH-23)

Formalizes tag → gate → changelog → artifact, building on `run-beta-release-gate.ps1` (the
existing gate seed) and the beta0 tag-immutability rules already written into
`docs/MATURITY_CLOSURE_LEDGER.md`.

## The process

1. **Land the work.** All changes for the release are committed on `main` (the working branch since
   2026-08-07 — see the "Merge cadence" section below), gates green (DSL/kernel/generator/
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

## Publishing the Manager (FINAL_PLAN.md/F2)

**Deliberately manual, not part of tag-push automation, as of 2026-08-07.**
`.github/workflows/publish-runtimehost-libs.yml` auto-builds and attaches `runtimehost-libs-
<tag>.zip` to every pushed tag because that artifact is plain JVM bytecode — platform-independent,
built once on `ubuntu-latest`. The Manager installer is the opposite shape: a platform-specific
Tauri/Rust build (Windows NSIS `.exe`, Linux `.AppImage`) that needs a matching runner per
platform, and (for Linux) system packages like `webkit2gtk` the CI image may not carry. Wiring that
up as real, tested automation is real work, not a few lines copied from the JVM-artifact workflow —
so rather than ship an unverified CI job, this is recorded as a known gap instead (grouped with the
other explicitly-deferred items — macOS, code signing — in `FINAL_PLAN.md`'s §7).

**Until that automation exists, publishing a Manager build is a manual step, run from a clean
checkout of the tag being released:**

```powershell
cd NPDevManager
cargo tauri build
gh release upload <tag> "$env:NPDEV_BUILD_ROOT\manager-target\release\bundle\nsis\NPDev Manager_<version>_x64-setup.exe" --clobber
```

(Linux `.AppImage` publishing needs a Linux build host — not done from this Windows-only
development machine; tracked as part of the same CI-automation gap, not a separate one.)

**Before uploading:** confirm the binary was actually built from the tagged commit, not an older
local build — `NPDevManager/src/`, `NPDevManager/ui/`, and `NPDevManager/fixtures/` all changed in
sessions after the previous installer build (F1's Java 17→17+ doctor relaxation, among others), so
an installer built before that change would ship a UI that disagrees with what `docs/MANAGER.md`
documents.

## Merge cadence: keep `origin/main` current

`main` is public and the default branch — it is what a clone gets, what GitHub renders, and what any
first impression is formed from. **As of 2026-08-07 (REG-139/I2), `main` is also the sole working
branch** — the separate `beta1-vision-spine` branch this section used to describe was deleted (both
locally and on `origin`) after sitting 6 commits behind `main` with no further work landing on it;
every commit had been going directly to `main` for a long time before that made it official. This
section's history is kept because the RISK it describes is still real if a second long-lived branch
is ever reintroduced:

`main` used to only advance on an explicit merge from that separate branch, so **nothing about
normal development kept it current on its own.** Left alone, this had silently reached 150 commits
behind (pre-T1.6) and 71 commits behind (2026-07-29, `docs/RECORD_SURFACES_PLAN.md` P1) — twice,
with nothing measuring the gap either time. With `main` itself as the working branch, this specific
failure mode cannot recur — but the tripwire below stays wired in case a future working branch is
ever spun off again.

- **If a working branch is ever reintroduced: merge forward whenever a plan closes.** A plan closing
  is a natural, memorable checkpoint — don't let a gap accumulate across several.
- **Merge via PR, not a local fast-forward**, so both quality gates run on the merge itself before
  it lands — this is often the first *observed CI run* for changes to the gates themselves.
- **Tag the merge** `v<version>` per the versioning scheme below. Per the beta0 tag-immutability
  rule (step 4 above): never move a previously pushed tag to point at the new merge — supersede it
  with the next tag.
- **The tripwire, not the only defense:** `scripts/quality/check-record-surfaces.py`, wired into
  `run-ai-knowledge-gate.ps1`, WARNs above 20 commits ahead of `origin/main` and FAILs above 50. A
  working branch is *meant* to run some commits ahead between merges — the threshold exists so a
  second unmerged release can't again reach 71 (or 150) unnoticed, not to force a merge on every
  commit. With `main` as the working branch this stays at 0 by construction, but the check stays
  wired rather than removed.

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
