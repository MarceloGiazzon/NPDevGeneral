# Changelog

Format: see `docs/RELEASE_PROCESS.md`. Dates are release-tag dates, not commit dates.

## [Unreleased]

### Added
- LNCH-12: scheduled/background flow execution (`flow.schedule`, cron-triggered, ControlPanel
  visibility at `/api/admin/cron-schedules`).
- LNCH-16: optimistic locking on concept writes (`row_version`, compare-and-swap through
  `ConceptGateway`, 409-with-current-record conflict contract). See `docs/OPTIMISTIC_LOCKING.md`.
- LNCH-17: flow transaction-boundary contract — `onFailure` compensation steps (saga pattern,
  crash-resumable), documented atomicity/durability guarantees. See
  `docs/architecture/FLOW_TRANSACTION_CONTRACT.md`.
- LNCH-10 slice 1: CSV export (`GET /api/concepts/{concept}/export.csv`, streaming/paged, an
  Export CSV button on every generated grid). See `docs/CSV_EXPORT.md`.
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
