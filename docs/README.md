# NPDev documentation

**New here?** [GETTING_STARTED.md](GETTING_STARTED.md) → [YOUR_FIRST_APP.md](YOUR_FIRST_APP.md) →
[DSL_REFERENCE.md](DSL_REFERENCE.md). That is the whole path.

`docs/` root holds current product and engineering truth — one file, one purpose, no historical
narrative. Closed programmes, checklists, and superseded plans live in [archive/](archive/) and
[beta/](beta/) instead; they are not part of this index because they are not current instructions.

## Use it

[GETTING_STARTED.md](GETTING_STARTED.md) ·
[YOUR_FIRST_APP.md](YOUR_FIRST_APP.md) ·
[TUTORIAL_FIRST_APP.md](TUTORIAL_FIRST_APP.md) ·
[INSTALL_ON_A_NEW_MACHINE.md](INSTALL_ON_A_NEW_MACHINE.md)

## Look it up

[DSL_REFERENCE.md](DSL_REFERENCE.md) ·
[FEATURES.md](FEATURES.md) ·
[CONFIGURATION.md](CONFIGURATION.md) ·
[DEPLOYMENT.md](DEPLOYMENT.md) ·
[DATABASES_AND_MIGRATIONS.md](DATABASES_AND_MIGRATIONS.md) ·
[FLOWS.md](FLOWS.md) ·
[EXPRESSIONS.md](EXPRESSIONS.md) ·
[SCHEMA_EVOLUTION.md](SCHEMA_EVOLUTION.md) ·
[UI_CONTRACT.md](UI_CONTRACT.md) ·
[SCREEN_TAXONOMY.md](SCREEN_TAXONOMY.md) ·
[MONITOR.md](MONITOR.md) ·
[MANAGER.md](MANAGER.md) ·
[RELEASE_PROCESS.md](RELEASE_PROCESS.md) ·
[ADAPTER_REGISTRATION_CHECKLIST.md](ADAPTER_REGISTRATION_CHECKLIST.md)

**Feature docs:** [CSV_EXPORT.md](CSV_EXPORT.md) ·
[EMAIL_NOTIFICATIONS.md](EMAIL_NOTIFICATIONS.md) ·
[OPTIMISTIC_LOCKING.md](OPTIMISTIC_LOCKING.md) ·
[PASSWORD_RESET.md](PASSWORD_RESET.md) ·
[ROW_LEVEL_AUTHORIZATION.md](ROW_LEVEL_AUTHORIZATION.md) ·
[SCHEDULED_FLOWS.md](SCHEDULED_FLOWS.md) ·
[BACKUP_RESTORE.md](BACKUP_RESTORE.md) ·
[USING_MYSQL_AND_SQL_SERVER.md](USING_MYSQL_AND_SQL_SERVER.md) ·
[LEGACY_SCHEMA_MIGRATION.md](LEGACY_SCHEMA_MIGRATION.md)

## Understand it

[NPDEV_CONCEPTS_DEEP_DIVE.md](NPDEV_CONCEPTS_DEEP_DIVE.md) ·
[NPDEV_USER_MANUAL.md](NPDEV_USER_MANUAL.md) ·
[architecture/](architecture/) ·
[adr/](adr/)

## Contribute

[AUTHORING_WITH_AI.md](AUTHORING_WITH_AI.md) ·
[AI_MODEL_TO_DSL_MAPPING.md](AI_MODEL_TO_DSL_MAPPING.md) ·
[AI_CUSTOM_PANEL_CONTRACT.md](AI_CUSTOM_PANEL_CONTRACT.md) ·
[AI_CUSTOM_PROCEDURE_CONTRACT.md](AI_CUSTOM_PROCEDURE_CONTRACT.md) ·
[AI_SCENARIO_DIRECTORY_CONTRACT.md](AI_SCENARIO_DIRECTORY_CONTRACT.md) ·
[ai/](ai/) ·
[ACCEPTED_BOUNDARIES.md](ACCEPTED_BOUNDARIES.md) ·
[OPEN_ITEMS.md](OPEN_ITEMS.md) ·
[BUILD_OUTPUT_LOCATION_POLICY.md](BUILD_OUTPUT_LOCATION_POLICY.md) ·
[WORKSPACE_CLEANUP_POLICY.md](WORKSPACE_CLEANUP_POLICY.md)

## Generated / live registers

Never hand-edit these — each has its own regenerate command, checked by `run-ai-knowledge-gate.ps1`.

[OPEN_ITEMS.md](OPEN_ITEMS.md) (`ledger/items/*.yml` → `python scripts/quality/generate_open_items.py`) ·
[OPEN_GAPS_AND_ROADMAP.md](OPEN_GAPS_AND_ROADMAP.md) (`ledger/gaps.yml` → `python scripts/docs/generate_gaps_roadmap.py`) ·
[X0_SILENT_EXPRESSION_REGISTER.md](X0_SILENT_EXPRESSION_REGISTER.md) ·
[SECURITY_PATTERN_SWEEP_2026-07.md](SECURITY_PATTERN_SWEEP_2026-07.md)

## Release & maturity roadmap (live, not historical)

These declare `STATUS: ACTIVE` or are read for their content by a currently-runnable (if
occasionally-invoked) maturity/release gate script — found live during the 2026-08-11 docs
decoupling pass, when a first attempt to archive several of them turned out to be exactly the
mistake [EXTERNAL_SECURITY_REVIEW_BRIEF.md](EXTERNAL_SECURITY_REVIEW_BRIEF.md) itself records
having suffered once before (2026-07-27: archived, then restored, because it was still active).
Check a file's own `STATUS:` line before assuming it is safe to move.

[FAIL_OPEN_PLAN.md](FAIL_OPEN_PLAN.md) ·
[FRONTEND_STRATEGY_PLAN.md](FRONTEND_STRATEGY_PLAN.md) ·
[INVOCATION_TOPOLOGY_PLAN.md](INVOCATION_TOPOLOGY_PLAN.md) ·
[NEXT_EXECUTION_PLAN.md](NEXT_EXECUTION_PLAN.md) ·
[RECORD_SURFACES_PLAN.md](RECORD_SURFACES_PLAN.md) ·
[MATURITY_CLOSURE_LEDGER.md](MATURITY_CLOSURE_LEDGER.md) ·
[ROADMAP_BOUNDARY_POLICY.md](ROADMAP_BOUNDARY_POLICY.md) ·
[POST_BETA0_HUMAN_ACTION_REGISTER.md](POST_BETA0_HUMAN_ACTION_REGISTER.md) ·
[OFFICIAL_BETA_RELEASE_RUNBOOK.md](OFFICIAL_BETA_RELEASE_RUNBOOK.md) ·
[SAMPLE_MATRIX_RELEASE_POLICY.md](SAMPLE_MATRIX_RELEASE_POLICY.md) ·
[RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md](RELEASE_EVIDENCE_SOURCE_OF_TRUTH.md) ·
[FRONTEND_GATE_REPRODUCIBILITY.md](FRONTEND_GATE_REPRODUCIBILITY.md) ·
[EXTERNAL_SECURITY_REVIEW_BRIEF.md](EXTERNAL_SECURITY_REVIEW_BRIEF.md) ·
[HUMAN_VS_AI_VERIFICATION.md](HUMAN_VS_AI_VERIFICATION.md)

## Front matter

[PITCH.md](PITCH.md) · [PROJECT_POSTURE.md](PROJECT_POSTURE.md) ·
[EXTERNAL_TESTER_COLDSTART.md](EXTERNAL_TESTER_COLDSTART.md)

## History

[archive/](archive/) is programme history — kept for provenance, not current instructions. Every
document there was checked for a live `STATUS:` marker before being moved; a `historical`
classification in `scripts/policy/doc-entrypoint-classification-policy.json` means a stale script
reference inside it will not fail the release gate.

[beta/](beta/) is Beta0-era runbook material, also classified `historical`.

[security/](security/) holds the current security-review process document.
