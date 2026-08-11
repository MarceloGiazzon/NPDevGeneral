# Changelog

Format: see `docs/RELEASE_PROCESS.md`. Dates are release-tag dates, not commit dates.

## [beta1.8] - 2026-08-08

Everything below is scoped to the 12 commits that landed on `main` between `beta1.7`
(`6a87d8e`) and this tag, plus ROUND2_PLAN.md's R1 work landing directly before the tag — not a
retroactive pass over the much larger `[Unreleased]` backlog below, which predates this entry and
is tracked separately.

### Added
- Per-app Java level (`config.json`'s `build.javaVersion`) and declared third-party dependencies
  (`build.repositories[]`/`build.dependencies[]`) for generated apps (deps-and-java/PLAN.md,
  REG-140/REG-141). `AppDependenciesEmitterTest` added as regression cover for the dependency
  emitter, which had shipped with none (ROUND2_PLAN.md R1a).
- Semantic-behavior-writeback shipped for real (REG-138) — `execute()` now actually mutates the
  model instead of the excluded-by-default stub.

### Fixed
- REG-139: blank-page-on-fresh-boot crash in the editor's `ModelEditorPanel`, three-layer fix,
  verified live (RED/GREEN).
- Editor: removed dead structural-writeback code; fixed an asset-copy bug that dropped JS chunks.
- A generated app's "step0" zero-setup trial profile could fail to boot at all
  (`UnsatisfiedDependencyException` for `DataSource`, then a second one for a duplicate
  `TraceStore`/`FlowInstanceStore` bean) whenever its model resolved to the InMemory engine at
  generation time — exactly the case for any app generated without a live database connection, and
  exactly the path the Manager's "New app" zero-setup flow depends on. Found and fixed as part of
  the Gradle/Spring Boot bump below; see `REG-143`.

### Behavior changes
- **`build.javaVersion` widened from `enum [17, 21]` to a floor-only `minimum: 17` — any integer
  >= 17 is now accepted, with no upper bound (ROUND2_PLAN.md R1c).** Not a breaking change (every
  config valid under the old enum stays valid). To make values above 21 actually buildable, not
  just schema-accepted, `NPDevRuntimeHost`'s own bundled Gradle wrapper (copied into every
  generated app) was bumped 8.5 → 9.5.1, `foojay-resolver-convention` 0.8.0 → 1.0.0,
  `org.springframework.boot` plugin 3.3.2 → 3.5.16 (its `bootJar` task called a Copy API method
  Gradle 9 changed — the only thing in the whole corpus that caught this was the generator's own
  packaged-app boot-proof tests, which are also the only tests that run `bootJar` at all), ArchUnit
  1.3.0 → 1.4.2, and Mockito 5.11.0 → 5.23.0 (with an explicit Byte Buddy 1.17.7 override) — all
  three of the last found live to fail against Java 25's class file format otherwise. Platform
  modules (dsl/kernel/generator/adapters/runtimehost source) are unaffected; only the template
  shipped inside generated apps moved. See `BREAKING.md` and ledger `REG-143`.

### Chore
- Stale branches cleared, `docs/MANAGER.md` made discoverable from the README (FINAL_PLAN.md
  F1+F2).
- REG139_PLAN.md I2/I3: delete stale branch, sync remaining Java 17+ fixtures.

## [Unreleased]

### Added
- LNCH-12: scheduled/background flow execution (`flow.schedule`, cron-triggered, ControlPanel
  visibility at `/api/admin/cron-schedules`).
- LNCH-16: optimistic locking on concept writes (`row_version`, compare-and-swap through
  `ConceptGateway`, 409-with-current-record conflict contract). See `docs/reference/OPTIMISTIC_LOCKING.md`.
- LNCH-17: flow transaction-boundary contract — `onFailure` compensation steps (saga pattern,
  crash-resumable), documented atomicity/durability guarantees. See
  `docs/architecture/FLOW_TRANSACTION_CONTRACT.md`.
- LNCH-10 slice 1: CSV export (`GET /api/concepts/{concept}/export.csv`, streaming/paged, an
  Export CSV button on every generated grid). See `docs/reference/CSV_EXPORT.md`.
- LNCH-21: `docs/architecture/APP_UPGRADE_CONTRACT.md` — the platform-owned/app-owned boundary in
  a generated FinalApp, written down precisely for the first time.
- LNCH-22: `docs/DSL_REFERENCE.md` (generated from the schema —
  `scripts/docs/generate_dsl_reference.py`), `docs/TUTORIAL_FIRST_APP.md` (golden-path tutorial,
  gate-tested by reusing the `simple-contact-intake` sample).
- LNCH-23: `LICENSE` (Apache-2.0, copyright Marcelo Giazzon), `docs/RELEASE_PROCESS.md`,
  `docs/adr/ADR-0007-distribution-model.md`, `scripts/quality/run-release-checklist-gate.ps1`.
  License, telemetry ("none at launch"), and distribution model ("self-hosted, source-first")
  decisions ratified 2026-07-17; trademark clearance remains open (a preliminary search found a
  real naming collision — see ADR-0007 §5 — a professional search is still required).
- `docs/adr/ADR-0006-authoring-path.md`: the AI-first, editor-secondary authoring-path decision
  (LNCH-18); `docs/NON_AUTHOR_FRICTION_LOG_TEMPLATE.md` for recording the DoD's still-outstanding
  non-author test run.
- `docs/CONFIGURATION.md` (LNCH-22): full reference for every `npdev.*`/`NPDEV_*` startup-config
  property, matching the exact anchor IDs `StartupValidator`'s error messages already link to.
- `scripts/quality/run-app-upgrade-contract-gate.ps1` (LNCH-21): proves live that a `web/`
  customization survives two full regenerations byte-identical — the gap
  `docs/architecture/APP_UPGRADE_CONTRACT.md` had flagged as "true by construction but not yet
  proven by a test."

### Behavior changes
- **`npdev doctor`'s `git-present` check is a `warn`, not a `fail`.** Its detail named two reasons
  that were both false -- git is not needed to clone NPDev on the Manager's path (versions arrive as
  a zip) and, since the fix above, is not needed by `npdev init` either. A hard failure took the
  Manager's whole Ready screen red on a machine that works perfectly well, and
  `_scrapforai_check`'s own docstring already wrote the rule: a doctor that goes red over an
  optional tool teaches people to ignore red. The check id is unchanged.
- **`engine-support.yml` and `storage-dialect-conformance.yml` now run on `push: tags`.**
  `release_candidate.py` requires a run of both AT THE TAG'S EXACT SHA, and nothing produced one --
  the first is dispatch-only and the second's `paths:` filter matched nothing on a typical release
  commit. The RC gate refused twice on 2026-08-10, ~20 minutes each time. (GitHub does not apply
  path filters to tag pushes, which is why the conformance one is a `tags:` key inside the existing
  `push:` entry rather than a second trigger.) **Unverified until a real tag fires it.**
- **LNCH-1 T1 (data-integrity fix): an upgrade no longer relaxes `NOT NULL` on the platform-managed
  columns, and repairs databases an earlier build already loosened.** Every fingerprint-changing boot
  used to strip `NOT NULL` from `version`, `row_version` and `tenant_id`, because they appear in the
  manifest's full column set but never in its model-derived *required* set (only `id` escaped, via the
  live primary-key read). A `NULL` `tenant_id` makes a row invisible to every tenant-scoped read; a
  `NULL` `row_version` silently defeats LNCH-16's compare-and-swap. On the next boot, any already-
  loosened platform column is backfilled (`NULL`s only — real values are never overwritten) to its
  platform default and restored to `NOT NULL`, recorded as a `TIGHTEN_PLATFORM_COLUMNS` row in
  `npdev_schema_history`. **Operators upgrading an app built before this change will see one repair
  pass on the next boot.** Verified live on real Postgres with real data.
- **LNCH-1 T2: the platform `version` column is now additive-eligible, so a table missing it
  self-heals.** Previously `version` was declared by the manifest but never added by any migration, so
  a table missing it produced an `UNKNOWN` delta item — which, since closeout C1, refuses the boot
  unless an itemized token authorizing a **whole-schema wipe** is supplied. This removed the most
  likely real-world trigger of a total data-loss event. `version` and `row_version` remain separate
  columns. A consequence worth knowing: the missing-column route to the whole-schema recreation is now
  unreachable from any diff the current generator can emit; the path is deliberately kept, and kept
  token-gated, as a safety net for hand-modified schemas, pre-T2 manifests, and future non-additive
  column kinds — see `docs/SCHEMA_EVOLUTION.md`.
- `BuildInfoEmitter`'s `npdev.generator.version` (embedded in every generated app's
  `npdev-build-info.properties`) now reads `git describe --tags --always` against the platform's
  real release tags instead of a hardcoded `"0.1.0"` literal, falling back to that literal only
  when there's no git checkout to read.
- `JdbcBusinessConceptStore` now joins the ambient Spring transaction (via `DataSourceUtils`)
  instead of always opening an independent auto-committing connection. A generated concept's
  `create`/`update` service method (`@Transactional`) now genuinely rolls back its kernel-gateway
  write if a later step in the same method (e.g. the JPA persist) fails — previously these were
  two independent writes. No model-schema change; a pure correctness fix, but noted here since it
  changes observable rollback behavior under failure.

### Changed
- **LNCH-1 closeout C1 (operator-visible): the whole-schema recreation now requires an itemized
  acknowledgment token.** Hardening X4.4 established that destroying *one* table's data needs a
  token; the whole-schema recreation destroys *every* table's data and was still reachable on the
  deprecated blanket `destructiveAllowed` flag alone — the most destructive operation in the system
  had the weakest authorization requirement. On a blanket-posture app, a boot whose delta report
  cannot be executed item by item (any `UNKNOWN` item) now **refuses** instead of dropping and
  recreating every table. The refusal names the unexplainable item(s), states plainly that
  proceeding would destroy all data in every table, and prints the token that authorizes it.
  **The blanket flag now authorizes exactly two things: a surgical `DROP_COLUMN` and a
  `NARROW_TYPE`.** Dev/CI loops that relied on "recreate everything on boot" should delete the
  database between runs or use a `freshdb`-style definition — note that `RecreateOnAppStart` is
  inert and is *not* an escape hatch. See `docs/SCHEMA_EVOLUTION.md`.
- LNCH-1 closeout C3: `AppGen\apps\simple-user-registry-h2local` now ships the **recommended**
  `schemaLifecycle` posture (`KeepExistingIfCompatible` + `allowDestructiveRecreate: false`) as a
  copyable worked example, and the authoring docs no longer pair `allowDestructiveRecreate: false`
  with the `I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED` confirmation string. Existing definitions are
  untouched; most still carry the deprecated blanket posture for backward compatibility, which the
  docs now state explicitly is history rather than a recommendation.
- LNCH-1 remediation R1: a destructive-acknowledgment token for a dropped concept (`DROP_TABLE`) no
  longer includes the live row count in its hash, so a token copied from `-PlanOnly` now matches the
  executor's boot-time token on the first attempt (previously impossible for a concept drop). Both
  token producers also route every SQL-type string through one shared normalizer
  (`SqlTypeNormalization`). **Any acknowledgment token computed before this change no longer
  matches** — re-run `Build-NpdevApp.ps1 -PlanOnly` to obtain the new token (a stale token simply
  refuses the boot with the new expected token printed; no data is at risk).

### Fixed
- **The first ten minutes on a machine that is not the author's** (close-the-gaps-2026-08-10 Wave 1).
  Four defects that only appear on a machine the NPDev Manager was actually built for, none of them
  reachable by any gate this repo runs, because every gate runs where git and java are already
  installed:
  - `npdev init` called `git init` unconditionally. With git absent that raised `FileNotFoundError`,
    which `main()` catches nowhere, so the Manager's **Create** button died with a raw traceback at
    the very first step -- on the machine `docs/MANAGER.md` advertised as needing "no git". The
    scaffold never needed git: every file is written before that call. It now reports the missing
    repository, corrects the README sentence that promised one, returns `gitInitialised: false`, and
    exits 0.
  - The built app was launched with a bare `["java", "-jar", ...]` at both launch sites
    (`npdev run app`'s BOOT phase and `dev_loop.boot`). The Manager passes its private JDK as
    `JAVA_HOME` only -- Gradle honours that and a bare `java` cannot see it -- so generate and build
    succeeded on the private JDK and the app then failed to start with the working JDK sitting
    unused, printing nothing, because `Popen`'s exception went to a stream the Manager discards. Both
    sites now use `java_launcher()` (JAVA_HOME first, PATH second, `None` reported as a diagnostic),
    and `npdev doctor` consumes the same function so the two can no longer disagree.
  - `HANDOVER.md` 2.6, the step it calls "the whole product", asked the tester to rename a field --
    the one edit NPDev refuses by design (`ACCEPTED_BOUNDARIES.md` B1). It now asks for an added
    field and says why.
  - `docs/INSTALL_ON_A_NEW_MACHINE.md`, `docs/MANAGER.md` and `HANDOVER.md` described controls that
    do not exist ("Doctor" button, an Apps-screen Run button, **Refresh**/**Start** where the window
    says **Re-check**/**▶ Run**), invented six database labels none of which is in the picker, and
    promised a Reset refusal that `STOR-15` shows nothing can switch on. Re-audited row by row
    against the running UI and CLI; the code half is filed, not papered over.
- **LNCH-1 T5 (`GATE-OBS-1`): `run-runtimehost-gate.ps1`'s exit code is truthful again.** The gate had
  exited 1 for four consecutive rounds on one check, `runtime-surface-reports-current`, while
  contradicting itself: it already passed `-PendingOk` to the surface-evidence step for exactly those
  package-namespace convergence checks, then re-read the same reports in the observability step and
  failed on them. The six named checks are now accepted as advisory, and **only** when they are the
  sole failures — any other failing sub-check still fails the gate, naming it. Build-time allowlist
  enforcement was and remains green. The governance realignment is tracked separately as
  `GATE-OBS-1a`, still open and needing an owner.
- **LNCH-1 T9.2: `LNCH-1-B8`'s refusal printed an unactionable remedy.** It advised rebuilding
  "without `-PlanOnly`", but the guard covers `-PlanOnly` *or* `-Upgrade`, so an `-Upgrade` run hit the
  same refusal and the instruction looped. It now says to rebuild with neither flag.
- **LNCH-1 closeout C4 (`LNCH-1-B8`): a failed `-Upgrade` no longer degrades the next migration plan
  into a false "fresh install".** `Build-NpdevApp.ps1` reads the previous compiled model from the
  output root and then wipes that root; a run that failed after the wipe destroyed the model, so the
  next `-PlanOnly` reported *"Fresh install — no previous compiled model to diff against"* **and
  exited 0** — the documented script-friendly "safe to proceed" gate signal — for a database that
  may need a destructive change. The snapshot is now preserved durably beside the plan echoes (keyed
  by definition folder, since several shipped definitions deliberately share one `scenario.name`),
  and a plan requested with no model available but durable evidence of a prior deployment now
  **refuses** with an actionable message instead of degrading. `GeneratorMain` gained an opt-in
  `--requirePreviousCompiledModel` so other callers get the same protection.
- LNCH-1 closeout C7.1: the RuntimeHost gate's `health-indicator-coverage` check no longer fails
  vacuously. It scraped `NpdevRuntimeModeConfig` for `public <Type> postgresXxx(...)` beans, a
  naming convention that no longer exists (they are `jdbcXxx`), so it matched zero surfaces and
  reported a coverage gap that did not exist.
- **LNCH-1 X1 (critical, operator-visible): a destructive upgrade authorized only by the blanket
  `destructiveAllowed` flag no longer wipes the whole schema.** On any app with
  `allowDestructiveRecreate: true` — the shape of every shipped app definition — a destructive change
  fell through to the whole-schema recreation even when every item in the delta report was
  surgically executable. Because that path drops the tables the *new* manifest lists, dropping a
  concept destroyed the data of every concept still in the model, while the orphaned table the
  upgrade was meant to remove survived. Authorization and execution strategy are now separate:
  authorization decides *whether* destruction may happen, and the report's content decides *how* —
  the whole-schema recreation is reached only when the report contains an `UNKNOWN` item. This is
  strictly less destructive in every case it changes. The blanket-flag deprecation warning now also
  itemizes exactly what is about to be executed.
- LNCH-1 X2: a table dropped from the model but still physically present (it survived a wipe, a
  crash, or a refusal) no longer loses its NPDev-ownership record on the next boot, which had made
  it permanently un-droppable. `ownedBusinessTables` is now `(previous ∪ current manifest) ∩ live
  tables`.
- LNCH-1 X3: the schema-ahead-of-build detector (which refuses an old jar redeployed against a
  database a newer build already migrated) was effectively inert — it skipped every
  additive-eligible column, which in a real manifest is nearly every column. It now also fires when
  a missing column coincides with an *unexplained extra* live column on the same table (the
  signature of a rename by a newer build), and reports an entirely-absent table once instead of
  column by column. A pure column *drop* by a newer build remains undetectable — documented in
  `docs/SCHEMA_EVOLUTION.md#refusals-and-rollback`.
- LNCH-1-B7: dropping a concept now actually drops its table. Previously `-PlanOnly` previewed a
  `DROP_TABLE` and demanded an acknowledgment token, but at boot `classify()` only enumerated
  manifest-declared tables, so the orphaned table was invisible, the boot classified as
  safe-additive, and the destructive path (and the token check) was never entered -- the table and
  its rows survived and the acknowledgment was never consumed. The executor now records the business
  tables it owns (`npdev_schema_metadata.ownedBusinessTables`) on every successful boot and acts only
  on orphans it can prove it created, so a table an operator added by hand in the same schema is
  never dropped. Apps with no ownership recorded yet keep the previous behaviour for one boot.
- LNCH-1 remediation R8: `scripts/quality/run-stateful-additive-migrations-check.ps1` could not parse
  at all (its `findings` strings used `\"`, which PowerShell does not treat as an escape) — so the gate
  had been dead, not merely reporting oddities. Fixed, and its permanently-red steps were either
  replaced with a real assertion (`old-migration-authority-quarantined`, a direct filesystem invariant)
  or removed with recorded rationale (a task that does not exist; CLI flags that subcommand never had).
- LNCH-1 remediation R6: a stale `renamedFrom` marker (one naming a column the previous model no
  longer has — the "renamed twice, marker never updated" case, which silently degrades a rename into
  a destructive drop) now surfaces a `WARNINGS` block in the `-PlanOnly` preview and a WARN log line
  at boot. Documented the marker lifecycle in docs/SCHEMA_EVOLUTION.md.
- LNCH-1 remediation R4: every mutating migration pass (table rename, column rename, NOT NULL
  relaxation, type widening, required-field backfill) now writes a write-before-execute
  `npdev_schema_history` row carrying the step name and per-item detail; the unique-precheck refusal
  row now records the violating tuples with a `UNIQUE_PRECHECK` label instead of an empty row.
- LNCH-1 remediation R3: a schema-ahead-of-build detector now refuses a fingerprint-MATCH boot whose
  live database is missing a core (non-additive) column this build requires — the "redeployed the
  old jar against a database a newer build already migrated" case, which previously booted deceptively
  clean and then failed at runtime. Recovery is roll-forward or restore (see docs/SCHEMA_EVOLUTION.md).
- LNCH-1 remediation R2: a new required field added in the SAME upgrade as an acknowledged
  destructive item is now backfilled-and-tightened (or refused if it has no literal default) on that
  boot, instead of being silently skipped and left permanently nullable. Enforcement moved to a
  single `afterMigrate` call site every boot path crosses.
- A pre-existing gap where a `forEach` flow step's loop body (`collectionRef`/`itemKey`/
  `loopSteps`/`maxLoopIterations`) was silently dropped by the canonical-JSON round trip every
  generated app's `NPDevModelProvider` reads at boot — found while wiring LNCH-17's
  `onFailureSteps` through the same writer/reader.
- CSV export header repeating the `id` column twice (LNCH-10 slice 1).

## Prior history

Everything before this entry was tracked via `docs/LAUNCH_READINESS_GAPS.md`,
`docs/OPEN_GAPS_AND_ROADMAP.md`, and `docs/MATURITY_CLOSURE_LEDGER.md` rather than a changelog —
this file starts fresh from here forward. See those documents for the full history of Waves 1-4
and the platform's earlier maturity checkpoints.
