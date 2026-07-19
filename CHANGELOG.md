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
- LNCH-1 remediation R1: a destructive-acknowledgment token for a dropped concept (`DROP_TABLE`) no
  longer includes the live row count in its hash, so a token copied from `-PlanOnly` now matches the
  executor's boot-time token on the first attempt (previously impossible for a concept drop). Both
  token producers also route every SQL-type string through one shared normalizer
  (`SqlTypeNormalization`). **Any acknowledgment token computed before this change no longer
  matches** — re-run `Build-NpdevApp.ps1 -PlanOnly` to obtain the new token (a stale token simply
  refuses the boot with the new expected token printed; no data is at risk).

### Fixed
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
