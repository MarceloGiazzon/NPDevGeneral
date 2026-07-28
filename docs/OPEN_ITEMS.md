# Open Items — generated

> **GENERATED FILE — do not hand-edit.** Source: `ledger/items/*.yml`. Regenerate with
> `python scripts/quality/generate_open_items.py`. See `ledger/README.md` for the schema
> and this prototype's honest scope (only a subset of the full register is migrated so far;
> `docs/NPDEV_OPEN_ITEMS_REGISTER.md` remains authoritative until migration completes).

**9 item(s) migrated: 1 open/partial, 8 done.**

| ID | Title | Type | Sev | Status | Opened |
|---|---|---|---|---|---|
| REG-54 | Two dead private methods left behind by the T2.B.4 SchemaLifecycleExecutor split | GAP | LOW | DONE | 2026-07-27 |
| REG-55 | Sandboxed plugin overload resolution matched by arg count only, not type -- "Ambiguous" false positive | BUG | MEDIUM | DONE | 2026-07-27 |
| REG-56 | Flow resume rebuilds ExecutionContext with the wrong actor/role, three different wrong ways | BUG | HIGH | DONE | 2026-07-28 |
| REG-57 | H2's default 500ms WRITE_DELAY can lose committed flow-instance checkpoints across a hard kill | BUG | HIGH | DONE | 2026-07-28 |
| REG-58 | Narrow-type DROP COLUMN crashed mid-migration on WmsOffice's real database -- composite unique index not dropped first | BUG | HIGH | DONE | 2026-07-28 |
| REG-59 | WmsOffice live database recovery record (REG-58 fix re-verification) -- recovery only, NOT the platform gap | GAP | MEDIUM | DONE | 2026-07-28 |
| REG-60 | Aggregate Workbench post-commit "Saved." confirmation is wiped by the next render before a user can see it | BUG | LOW | DONE | 2026-07-28 |
| REG-61 | Narrow-type recreate loses NOT NULL; no per-row-unique default expressible for a required UNIQUE column | GAP | HIGH | DONE | 2026-07-28 |
| REG-62 | allowedActions is an untyped, unvalidated CSV-in-metadata escape hatch -- a typo silently drops an action-rail button | GAP | LOW | OPEN | 2026-07-28 |

## Detail

### REG-54 — Two dead private methods left behind by the T2.B.4 SchemaLifecycleExecutor split

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-28)
**Verification:** UNIT_TESTED
**Source:** T2.B.4 file-split verification, 2026-07-27
**Surface:** `runtimehost/schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`

While splitting SchemaLifecycleExecutor.java (docs/DSL2_AND_DECOMPOSITION_PLAN.md §2.B.4),
worse(SchemaChangeClassification, SchemaChangeClassification) and hasTypeChange(...) (both
private static) were found to have zero callers anywhere in com.finalexec.db, confirmed by
direct grep repo-wide before deleting, not just within the package. Both methods deleted; a
dangling {@link #hasTypeChange} javadoc reference and three test files' doc-comments that
referenced hasTypeChange()/classify() as if still live were updated to describe the historical
pre-SER-P4.8 behavior instead. NPDevRuntimeHost SchemaLifecycleExecutor* suite green after.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-54`

### REG-55 — Sandboxed plugin overload resolution matched by arg count only, not type -- "Ambiguous" false positive

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-28)
**Verification:** UNIT_TESTED
**Source:** T2.B.4 live rehearsal, 2026-07-27; seen 3 times before being fixed while building the CORE C-3 durable-workflow demo
**Surface:** `kernel/sandboxed-plugin-execution`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/plugin/SandboxedPluginExecutionEngine.java`

SandboxedPluginExecutionEngine.resolveOperation matched a candidate handler method by name +
parameter count only, so PostgresPersistenceCapabilityAdapter's two 2-argument save overloads
(save(Object,Object) and save(TenantScope,Object)) always threw "Ambiguous," regardless of the
actual runtime argument types -- in the real call path adaptCallForHandler enriches a 1-arg save
into 2 args by prepending the concept name as a String, which is never a TenantScope, so exactly
one overload was ever actually legal. Fix: resolveOperation now disambiguates same-name/
same-argCount candidates by checking which ones the actual argument values are assignable to
(boxing primitives first); falls back to the original errors only when that doesn't narrow to
exactly one method. RED->GREEN: new
SandboxedPluginExecutionEngineTest#disambiguatesOverloadsBySameArgCountByActualArgumentType,
confirmed RED against the pre-fix code, GREEN after. Full NPDevRuntimeHost suite 404/0, no
regression.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-55`

### REG-56 — Flow resume rebuilds ExecutionContext with the wrong actor/role, three different wrong ways

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found while building the CORE C-3 durable-workflow demo, 2026-07-28
**Surface:** `kernel/flow-resume`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/ExecutionContext.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/ResumeCoordinator.java`

Two filed hypotheses were refuted by tracing code, not guessed away. Actual root cause: a real
permission-context bug, confirmed live via a debug log -- the resumed flow's capability.invoke
check ran as roles=[user] (denied), moments after the SAME request's event.publish check had run
as roles=[admin] for the SAME actor/tenant. Three call sites each built a resume ExecutionContext
a different wrong way (the publisher's own context; ExecutionContext.of, which defaults to USER;
ExecutionContext.anonymous()) because FlowInstance never persisted roles in the first place. Fix:
new ExecutionContext.resuming(tenantId, actorId), granting the trusted resume-level role
(mirroring ExecutionContext.system's ADMIN trust for the cron scheduler), wired into all three
call sites; the now-unused caller-supplied-context parameter removed from
resumeWaitingExecutionsFor, updating its four callers. RED->GREEN, freshly reproduced on this
checkout: the notify-approval capabilityCall step re-added to the durable-workflow-demo model
reproduced CAPABILITY_FAILED on a real kill+restart before the fix; 3/3 clean runs after. Plus
ExecutionContextResumingTest (3/3) and the full NPDevKernel:kernel suite (163/163), no regression.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-56`

### REG-57 — H2's default 500ms WRITE_DELAY can lose committed flow-instance checkpoints across a hard kill

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found while building the CORE C-3 durable-workflow demo, 2026-07-28
**Surface:** `generator/database-config`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/UserDatabaseDefinitionLoader.java`

Ack-ordering was eliminated first, by code: flowInstanceStore.update(waiting) is a plain blocking
call on a fully synchronous, single-threaded servlet call chain, no thread hop or async layer
anywhere between the kernel and the JDBC statement. That leaves physical durability: H2's MVStore
defaults to a 500ms WRITE_DELAY, buffering committed writes in memory before flushing to disk,
and this was not set anywhere in the repo. A hard kill inside that window loses however many
commits landed since the last flush even though each JDBC call had already returned success --
a contiguous tail of at least three commits lost together (a signature consistent with a
time-windowed buffer loss, not one dropped write). Fix: ;WRITE_DELAY=0 added to the H2 JDBC URL
construction (UserDatabaseDefinitionLoader.jdbcUrl, both H2_LOCAL and H2_SERVER branches -- the
only production call site), forcing a physical flush on every commit. Postgres unaffected (COMMIT
is synchronous to WAL there). RED->GREEN, freshly reproduced: with the fix reverted and the
demo's workaround sleep removed, run-durable-resume-demo.ps1 reproduced the exact failure fresh;
with the fix restored, 3/3 clean passes. Plus UserDatabaseDefinitionLoaderWriteDelayTest (2/2).
The 5-second sleep workaround was deleted from run-durable-resume-demo.ps1.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-57`

### REG-58 — Narrow-type DROP COLUMN crashed mid-migration on WmsOffice's real database -- composite unique index not dropped first

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Live, real destructive migration on WmsOffice's production database, 2026-07-28
**Surface:** `runtimehost/schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/DestructiveRecreationPass.java`

WmsOffice's real, user-acknowledged destructive migration (26 DESTRUCTIVE_NARROW_TYPE items)
crashed 8/26 items in, on identity_password_reset_tokens.token_hash, with an H2
JdbcSQLSyntaxErrorException: column may be referenced by a unique index. The partially-migrated
database file was backed up immediately. Root cause: executeNarrowTypeDropAndRecreate issued a
plain ALTER TABLE ... DROP COLUMN with no regard for a unique index/constraint still referencing
that column -- every model field declared unique gets a tenant-scoped, COMPOSITE bootstrap index
(ux_<table>_<column> ON <table> (tenant_id, <column>)), and H2/Postgres both refuse to silently
drop a column that is only one of a composite index's columns (a single-column index sharing the
dropped column DOES get auto-dropped, which is why an initial single-column repro attempt failed
to reproduce -- the composite shape was the load-bearing detail). Several other columns in the
same batch were equally likely unique-constrained business keys and would have hit the identical
crash later in the same run. Fix: new dropIndexesReferencingColumn (portable
DatabaseMetaData#getIndexInfo, not a naming-convention assumption) finds and drops every index
touching the narrowed column before the DROP COLUMN/ADD COLUMN pair. Deliberately does not
recreate the constraint itself -- UniqueConstraintPass already idempotently re-adds any declared
unique constraint on every boot's afterMigrate, so recreating it here would race that pass.
RED->GREEN: new DestructiveRecreationPassNarrowTypeUniqueColumnTest reproduces the identical
exception byte-for-byte against the real composite-index shape with the fix disabled; passes
(2/2) with it restored. Full NPDevRuntimeHost suite 406/0, no regression. Not yet closed
end-to-end on WmsOffice itself as of this fix -- see REG-59 for the live-database recovery.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-58`

### REG-59 — WmsOffice live database recovery record (REG-58 fix re-verification) -- recovery only, NOT the platform gap

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found and resolved-on-live-data while re-verifying the REG-58 fix against WmsOffice's real, partially-migrated database, 2026-07-28
**Surface:** `runtimehost/schema-lifecycle`

THIS ROW COVERS THE MANUAL RECOVERY PERFORMED AGAINST WMSOFFICE'S REAL DATABASE ONLY -- it does
not cover the platform gap that recovery exposed; that gap is filed separately, OPEN, as REG-61.
DestructiveRecreationPass.executeNarrowTypeDropAndRecreate's ADD COLUMN never re-applies NOT NULL
even when the model declares the field required. BackfillPass DOES catch this on the next clean
boot (refuses to boot rather than silently leaving columns nullable) unless the model declares a
literal default to backfill with -- so the exposure window is only during a crashed/interrupted
boot, not permanent, correcting this filing's own first-draft framing. The deeper gap: the
sanctioned recovery mechanism (a literal default, backfilled via one UPDATE) cannot satisfy a
UNIQUE constraint across more than one existing row -- confirmed live (identity_roles.name 5
rows, identity_users.username 6 rows, both tenant-scoped unique). Resolved on WmsOffice's live
database via direct out-of-band SQL (not a model or platform-code change): backfilled all 18
blocked columns (flat placeholder for 16 non-unique, per-row-unique placeholder for the 2 unique
ones), then ALTER COLUMN ... SET NOT NULL directly. Verified via Impact-Only.ps1: verdict SAFE, 0
destructive/0 attention, then a real boot succeeded (/actuator/health UP). Consequence: WmsOffice's
identity/user data for its then-existing 6 users/5 roles are now placeholder values, not original
data -- the destructive DDL had already committed before backfill-refusal was reached, so this was
already true before the manual recovery; recovery only unblocked the boot.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-59`

### REG-60 — Aggregate Workbench post-commit "Saved." confirmation is wiped by the next render before a user can see it

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-28)
**Verification:** VERIFIED_LIVE
**Source:** Found during F5-V.2 live Aggregate Workbench re-verification, 2026-07-28
**Surface:** `generator/workbench-page-template`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/workbench-page.html.mustache`

commitDraft()'s success handler set msg.className="msg ok" on the CURRENT render's message
element, then immediately called render(), which rebuilds #app from scratch -- including a
fresh, blank <span class="msg"> -- wiping the confirmation before a user could ever see it.
invokeAction()'s success handler had the identical shape, so it was fixed too. Fix: a
module-level pendingMsg variable, set by the success handlers instead of mutating the doomed
message element directly; render() now applies any pending message to the freshly-created
<span class="msg"> before clearing it. Verified live (not just unit-tested): WmsOffice
regenerated + rebuilt, ExpedicaoWorkbench.html, real browser via ScrapForAI -- logged in as
trial/admin, opened a real PreExpedicao record, clicked Save, DOM readback + screenshot confirm
the green "Saved." text is visible next to the Save button after the re-render.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-60`

### REG-61 — Narrow-type recreate loses NOT NULL; no per-row-unique default expressible for a required UNIQUE column

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-28)
**Verification:** UNIT_TESTED
**Source:** Split from REG-59 during its live-recovery filing, 2026-07-28
**Surface:** `runtimehost/schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/DestructiveRecreationPass.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/BackfillPass.java`

Both needs carried verbatim from REG-59's filing, both fixed. (a)
DestructiveRecreationPass.executeNarrowTypeDropAndRecreate now looks up the model's declared
required-ness for the narrowed column (via DesiredSchemaFactory) and re-applies NOT NULL directly
when the table is currently empty -- a zero-row table no longer needs the backfill dance at all. A
non-empty table still adds the column nullable exactly as before, leaving (b)'s refusal as the
correct next line of defense. New DestructiveRecreationPassRequiredColumnPreservationTest (3/3).
(b) BackfillPass now detects required + UNIQUE-constrained (single- or compound-field) + more
than one row that would receive the same literal, and refuses by name (table.column, affected row
count, a documented recovery recipe generalizing the out-of-band SQL WmsOffice used) instead of
proceeding to a confusing duplicate-key failure once UniqueConstraintPass re-adds the constraint
later. Did NOT invent a per-row-unique default expression language, per the plan's own scope
decision. New BackfillPassUniqueColumnRefusalTest (2/2), RED-first. docs/SCHEMA_EVOLUTION.md
documents the new refusal case and recipe. Full com.finalexec.db suite 273/273, no regression.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-61`

### REG-62 — allowedActions is an untyped, unvalidated CSV-in-metadata escape hatch -- a typo silently drops an action-rail button

**Type:** GAP · **Severity:** LOW · **Status:** OPEN
**Verification:** NOT_VERIFIED
**Source:** Investigated while closing F5-R1, 2026-07-28
**Surface:** `dsl/autopanel-lifecycle`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/AutoPanelExpander.java`

allowedActions (per-lifecycle-state action-rail gating, AW-P5) is authored today as a
comma-separated string inside a lifecycle state's generic metadata map
(AutoPanelExpander.java:310), not a typed schema field, and nothing validates an entry resolves
to a real declared action -- a typo silently yields a missing button in production, same class as
REG-52/REG-53. Investigation found the natural fix (typed array + validate against declared
actions) is blocked on a real prerequisite gap: AutoPanelSurfaceAst has no actions field at all --
an AutoPanel section's action list lives inside that surface's own untyped metadata, the same
escape hatch allowedActions itself uses. JSON Schema alone cannot validate against a per-model
dynamic set of action names, so typing the array without resolving the action side first would
look done without fixing the actual failure mode. Fix, when picked up: give AutoPanel section
actions a typed AST home first, then add the typed allowedActions array (all 4 model.schema.json
mirrors) plus a cross-reference check, likely in PanelValidation.validateAutoPanels (which already
has both the concept's lifecycle and the AutoPanel's surfaces in scope). Not urgent: 0 of 27
corpus models use allowedActions at all.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-62`

