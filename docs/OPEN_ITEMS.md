# Open Items — generated

> **GENERATED FILE — do not hand-edit.** Source: `ledger/items/*.yml`, the authoritative
> record for every tracked id. Regenerate with `python scripts/quality/generate_open_items.py`.
> See `ledger/README.md` for the schema. `docs/NPDEV_OPEN_ITEMS_REGISTER.md` is archived-in-
> place (its prose investigation narrative, linked from each item's `legacyDetailRef`) and is
> no longer hand-edited for status.

**165 item(s) migrated: 2 open/partial, 163 done.**

## Open / partial

| ID | Title | Type | Sev | Status | Opened |
|---|---|---|---|---|---|
| QUAL-4 | The maturity-bootstrap CI step wraps everything in continue-on-error, which discards the exact exit-2-vs-exit-1 distinction REG-32 built -- a real failure there would keep CI green | GAP | MEDIUM | OPEN | 2026-08-10 |
| STOR-13 | EIGHT SqlDialect methods have no production caller -- exercised only by their own tests, which is the exact state STOR-4, STOR-5 and STOR-6 were each found in (filed as nine; see round3_correction -- six of the original nine were false alarms, `supports` most importantly of all, and three of the eight are asked by the conformance vectors) | BUG | MEDIUM | OPEN | 2026-08-09 |

### Detail

### QUAL-4 — The maturity-bootstrap CI step wraps everything in continue-on-error, which discards the exact exit-2-vs-exit-1 distinction REG-32 built -- a real failure there would keep CI green

**Type:** GAP · **Severity:** MEDIUM · **Status:** OPEN
**Verification:** VERIFIED_LIVE
**Source:** Spotted in a beta1.12-era run log: "PRECONDITION-UNMET: 21 of 21 required reports were never generated (producers not run) ... npdev command failed with exit code 2 ... Error: Process completed with exit code 2." The job was green, because the step carries continue-on-error.
**Surface:** `ci/npdev-ci-validation`
**Files:**
- `.github/workflows/npdev-ci-validation.yml`

Two separate problems in one step (.github/workflows/npdev-ci-validation.yml:251, "Bootstrap post-Beta0 maturity reports").
1. THE DISTINCTION IS DISCARDED. REG-32 deliberately taught this chain to separate
   precondition-unmet (exit 2, not a defect -- the ~21 producer gates do not run in this job) from
   check-failed (exit 1, a real failure). The step then sets `continue-on-error: true` across the
   whole block, which tolerates BOTH identically. So if `npdev report bootstrap` or
   `./gradlew postBeta0MaturityCheck` ever fails for a real reason, NPDev CI Validation stays green
   and nobody sees it.

   This is the shape the project keeps finding: a distinction built carefully at one layer and
   thrown away at the layer above. Tier B was green while no app could boot; the dialect answered
   correctly while the emitter never asked; here the exit code is computed correctly and then
   ignored.

   The step's own comment is honest that this was deferred on purpose -- "flipping the flag for the
   whole step is a separate, higher-stakes CI-gating decision this fix did not make" -- which was
   reasonable when only two of the four commands in the block had been audited.

2. THE COMMENT IS NOW A STALE RECORD. It states the step "prints PRECONDITION-UNMET: 19 of 21".
   The observed log says 21 of 21. Two reports that were once produced in this job no longer are.
   Not fatal (everything here is advisory today), but the number in the comment is the only record
   of what the expected state IS, and it no longer matches.

### STOR-13 — EIGHT SqlDialect methods have no production caller -- exercised only by their own tests, which is the exact state STOR-4, STOR-5 and STOR-6 were each found in (filed as nine; see round3_correction -- six of the original nine were false alarms, `supports` most importantly of all, and three of the eight are asked by the conformance vectors)

**Type:** BUG · **Severity:** MEDIUM · **Status:** OPEN
**Verification:** NOT_VERIFIED
**Source:** storage/closeout/CLOSEOUT_PLAN.md section 7 ("what test would have caught all three?"). Found by the check that section asked for -- scripts/quality/check-dialect-methods-are-asked.py -- on its first run, against the tree at the moment STOR-6 closed.
**Surface:** `kernel/storage-dialect`

The plan's closing question was: STOR-4 (drivers), STOR-5 (guarded DDL) and STOR-6 (`quoteIdentifier`, zero calls in the generator) are the same defect three times, so what check catches the family rather than the instances? The answer -- a check that the thing a user runs ASKS the dialect, not merely that the dialect answers correctly -- was built, and it immediately found nine more.
Each of these is declared on `SqlDialect`, implemented by all four dialects, and called from nowhere in `NPDevKernel/*/src/main`, `NPDevGenerator/generator/src/main`, `NPDevRuntimeHost/src/main` or `NPDevContract/dsl/src/main`. The test-caller counts below are dialect-receiver calls found under `src/test` -- proven correct, wired to nothing:

    supports                       12 test callers, 0 production
    autoIncrementColumn             9 test callers, 0 production
    rowLimit                        7 test callers, 0 production
    returning                       5 test callers, 0 production
    listColumnsSql                  4 test callers, 0 production
    limitOnly                       4 test callers, 0 production
    listTablesSql                   3 test callers, 0 production
    listIndexesSql                  3 test callers, 0 production
    cast                            0 test callers, 0 production
    timestampColumnType             0 test callers, 0 production
    requiresOrderByForPagination    0 test callers, 0 production

`supports` is the one worth reading twice. CLAUDE.md instructs the reader to "ask `SqlDialects.active().supports(...)` rather than assuming a rollback" -- STOR-2's whole remedy -- and no production code does. The capability set is consulted through `capabilities()` by `StorageCapabilityGate` at GENERATION time; nothing asks at RUNTIME, which is where the DDL-in-transaction question actually gets decided.
Not necessarily nine bugs. Some of these may be genuinely premature -- an answer prepared before its consumer exists is not wrong, it is early. What was wrong is that nothing distinguished "prepared early" from "wired and forgotten", and that is the distinction the three closed items each turned out to need. They are now enumerated in that checker's INTERNAL_ONLY allowlist with this item's id, so the list is a visible backlog rather than an invisible one, and any NEW dialect method must be wired or explicitly recorded here.

## Done (163)

<details>
<summary>Expand the closed-item table and full detail archive</summary>

| ID | Title | Type | Sev | Status | Opened |
|---|---|---|---|---|---|
| PORT-1 | Six generated artefacts carry an absolute path from the AUTHORING machine into output a stranger runs -- including npdev.database.data-root, which is resolved at RUNTIME, so a generated app tries to open its database on a drive the user does not have | BUG | HIGH | DONE | 2026-08-10 |
| PORT-2 | The _ops toolbox baked the generation-time location into four files, so a COPIED app silently built and ran the ORIGINAL -- and the check that had just declared this class closed was blind to it by construction | BUG | HIGH | DONE | 2026-08-10 |
| QUAL-1 | check-dsl-coverage.py is 913 lines against a 400-line hard stop -- a genuine split candidate that keeps blocking unrelated work, recorded so the ceiling it was given is a decision rather than an oversight | GAP | LOW | DONE | 2026-08-09 |
| QUAL-2 | Ten unclosed Files.list/walk/lines streams in NPDevRuntimeHost production services -- the same leaked-directory-handle defect that made the local generator gate permanently red | BUG | MEDIUM | DONE | 2026-08-09 |
| QUAL-3 | Two apps in one folder became ONE database -- a shared `_ops` toolbox AND a shared appId, so container name and data root collided; resetting either destroyed the other's data | BUG | HIGH | DONE | 2026-08-09 |
| REG-1 | 9 app definitions remain on the deprecated blanket destructive posture (down from 27) | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-10 | LNCH-19: Linux CI observed green for the first time | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-100 | CLOSED -- three silent-answer sites found by the X0 audit, now fixed: a $ref that could not resolve wrote null (while the SAME class threw for id refs), a runQuery step naming an undeclared query returned an UNFILTERED list, and a typo'd $root.<field> visibleWhen predicate went unvalidated | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-101 | A declared query can carry parameters[] and a ':name' bind placeholder in its where, and NOTHING substitutes it -- pack-sample's SalesByStore has therefore returned zero rows since it was written, and the DSL accepts the shape with no error | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-102 | npdev migration diff (and the MCP tool npdev_migration_diff that shells out to it) is completely non-functional -- it always throws CONFIG_MIGRATIONS_DISABLED, because it passes generator CLI flags that the generator's own arg parser unconditionally rejects | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-103 | RuntimeMetadataService's compiled-metadata.json/npdev/metadata/* catalogs (concept/panel UI labels among them) are classpath-only with no external-path override, unlike NPDevModelProvider's compiled-model.json -- a metadata-only model change cannot be hot-swapped into a running app without also touching these, or a static frontend asset | GAP | LOW | DONE | 2026-08-01 |
| REG-104 | RolePermissions.toRole() returned null for any app-defined role name and the caller loop `continue`d, so an app-declared role (e.g. WarehouseManager) silently granted nothing at the platform-permission layer -- no error, no log line (X0-5) | BUG | MEDIUM | DONE | 2026-08-01 |
| REG-105 | Move 10 B1's groupBy/aggregates query primitive is single-concept only -- no cross-concept join, so a dashboard rollup that needs one (e.g. WmsOffice's retired analytics.html 'Estoque por Produto' widget: sum LocalArmazenagemLote.quantidade grouped via a join through Lote to Produto) cannot be expressed | GAP | LOW | DONE | 2026-08-01 |
| REG-106 | SchemaLifecycleExecutor.migrate() skipped flyway.repair() whenever the schema fingerprint was unchanged, but V1's generated migration SQL text can drift (comments/emission order) independently of the structural fingerprint -- a plain model.json edit with zero concept/table changes crashed the boot with a Flyway 'Migration checksum mismatch' on the next regeneration | BUG | MEDIUM | DONE | 2026-08-01 |
| REG-107 | PanelRuntime.executeAction's conceptquery binding fetches an entire concept unbounded via ConceptGateway.list -- the same memory/scale defect LC-P0 fixed for the declared-Panel dataSource path, out of that fix's stated scope | BUG | LOW | DONE | 2026-08-01 |
| REG-108 | roles/propertyScopes/properties were absent from ModelSourceResolver's MODEL_ARRAY_KEYS (and from pack.schema.json's allowlist) -- a pack or local fragment declaring any of the three had its declaration silently dropped during composition, with no error | BUG | MEDIUM | DONE | 2026-08-01 |
| REG-109 | generated-ui-manifest.json (and the rest of static/npdev-business-ui/*) was baked into the packaged jar at generation time with no external-path override, the same class of gap REG-103 fixed for RuntimeMetadataService's JSON catalogs -- named but explicitly not sized by REG-103, given the same external-path-before-classpath fix here | GAP | LOW | DONE | 2026-08-01 |
| REG-11 | LNCH-20: cross-platform build scripts (gradlew.bat literals, portable cache dir) | GAP | LOW | DONE | 2026-07-21 |
| REG-110 | LC-D2 (the acceptance-scenario runner) and LC-D3 (the closed authoring loop) were already fully implemented in NPDevCli/npdev_cli.py -- apparently from an earlier Move 10 session -- but had never been run, tested, or documented anywhere; a closure spec (Move 13) re-described them as needing to be built | GAP | LOW | DONE | 2026-08-01 |
| REG-111 | Long-running generate/build/boot cycles (Build-NpdevApp.ps1, Rebuild-And-Restage.ps1, npdev run app, plain gradlew clean build) emit no incremental progress signal -- whatever is waiting on one (a human operator, an AI agent, a CI step) cannot tell 'still working normally' apart from 'silently stuck' without reaching past the tool into raw filesystem/process state | GAP | LOW | DONE | 2026-08-01 |
| REG-112 | PanelRuntimeTest.java (single-arg RuntimeMetadataService constructor, hardcoded 'Appointment'/'AppointmentPanel' fixture data that exists in no corpus model) was missing from build.gradle's modelSpecificGeneratedAppTests exclusion list, unlike its sibling RuntimeMetadataServiceTest.java -- fails with NoSuchElementException whenever :test runs inside a generated app whose own model has no matching concept/panel | BUG | LOW | DONE | 2026-08-02 |
| REG-113 | NPDevRuntimeHost/build.gradle.template silently shadowed the actively-maintained NPDevRuntimeHost/build.gradle in every assembled/generated app -- FinalAppAssembler.materializeRootTemplate() prefers *.template over the legacy file whenever both exist, but nothing has kept .template in sync since before commit 067b987 ('Moves 6-11'), so every FinalApp assembled since then (including the very REG-112 fix landed earlier this same session) got a build.gradle missing everything written after that point | BUG | MEDIUM | DONE | 2026-08-02 |
| REG-114 | workspace::PropertyValue (RC-A2's cascade storage) inherited blanket admin-only CRUD permissions from isAdminConcept()'s built-in-pack default, with no carve-out for a user to read their own resolved property values -- the same latent-bug class workspace::Menu already needed a carve-out for, now reproduced on a newer built-in-pack concept | BUG | MEDIUM | DONE | 2026-08-02 |
| REG-115 | A new com.finalexec.api.*Controller added to NPDevRuntimeHost compiles into every OTHER app fine but silently produces 404-on-every-route with zero errors anywhere unless its simple class name is also added to runtime-supported-controllers.json's allowedControllers -- an allowlist gate with no companion check that a new controller was actually added to it | GAP | LOW | DONE | 2026-08-02 |
| REG-116 | dsl-conformance-max's own propertyScopes declaration (the RC-A1 corpus witness) listed the implicit root scope (tenant, no 'from') BEFORE the more specific 'user' scope -- compiled clean and validated clean since Wave 6, but silently inverts cascade precedence, undetected until Move 14's PropertyResolver (RC-A3) finally read propertyScopes' order for the first time | BUG | MEDIUM | DONE | 2026-08-02 |
| REG-117 | The generated business UI's hardcoded 'My Preferences' panel (business-ui-app.mustache/shell.js.mustache) references the now-retired workspace::Preference concept and its old userId/category/prefKey/prefValue fields -- silently vanishes from the nav (no crash, no error) for the one app that had it, WmsOffice, since RC-A2 (Move 14 item B1) renamed the concept to PropertyValue with a different shape | GAP | MEDIUM | DONE | 2026-08-02 |
| REG-118 | C1's own plan guidance ('bind $prop.<name> where $user.* is already bound') points at a binding site that the SAME item's hard rule makes permanently dead code -- ConfiguredConceptGatewaySemanticPolicy.evaluateAccessRule's scope is used EXCLUSIVELY by access.read/access.write, the one place $prop.* is now compile-time forbidden | GAP | LOW | DONE | 2026-08-02 |
| REG-119 | An app-declared role (RC-B1 roles[]/grants[]) holding EXECUTE_FLOW can never actually call the generated POST /api/flows/{name}/execute endpoint unless the actor ALSO independently holds the built-in 'user' role or the configured super-user role -- RuntimeApiEmitter's static permission manifest only ever grants the 'flow.execute' permission to those two role names, never to any app-declared one | GAP | MEDIUM | DONE | 2026-08-02 |
| REG-12 | LNCH-10: Excel/PDF/print export beyond CSV -- all 3 slices shipped | GAP | HIGH | DONE | 2026-07-21 |
| REG-120 | A concept whose create is delegated to a declared Flow (input.mode: create) AND is also exposed via the generic CRUD create endpoint gets DOUBLE-PERSISTED on every create -- the flow's own createConcept step writes the row through the kernel persistence capability, then the SAME generated service method immediately writes it AGAIN via saveWithIntegrityMapping -- and the two writes can race, throwing a spurious 500 (or, when they don't race, silently perform a wasted redundant write) | BUG | MEDIUM | DONE | 2026-08-02 |
| REG-121 | Two release-evidence producers (run-ai-beta-gate.ps1, run-trusted-source-beta0-proof.ps1) still invoke `:generator:run` with the disabled `--migrationsDir` flag -- GeneratorMain.migrationsDisabled() rejects it outright (CONFIG_MIGRATIONS_DISABLED), so every ai-beta-gate scenario whose model reaches generation fails there, cascading into expanded-beta0-evidence, sample-matrix, docker-linux-parity, and final-regression-coverage-audit | BUG | MEDIUM | DONE | 2026-08-02 |
| REG-122 | Normalize-AiContract.ps1 emitted retired pre-DSL-2.0 flow-step syntax (enforceInvariants / cap / op / out) for every AI-authored model's generated flow, failing official JSON Schema validation for every golden AI scenario that declares flows[] -- masking the true outcome of ~20 of 28 ai-beta-gate scenarios behind an early, uninformative official-validation failure instead of their own designed stage | BUG | MEDIUM | DONE | 2026-08-02 |
| REG-123 | doc-entrypoint-validation fails on ~20+ stale script-path references and unmapped report references scattered across historical/archived docs (docs/beta/*, docs/architecture/*, docs/NEXT_EXECUTION_PLAN.md, etc.) -- a documentation-drift backlog, not a single defect, that has never been triaged since this checker's own scope was expanded to cover the full docs/ tree | GAP | LOW | DONE | 2026-08-02 |
| REG-124 | golden-ai-scenarios/tenant-workflow-ops's ai-model.json declares tenancy.tenantIdField: "tenantId", which Normalize-AiContract.ps1 turns into an EXPLICIT "tenantId" field on the Ticket concept -- colliding with the platform's own implicit, reserved tenant_id column (ReservedColumnNames), so generation now fails with CONCEPT_FIELD_RESERVED_COLLISION instead of reaching build/boot/smoke | BUG | LOW | DONE | 2026-08-03 |
| REG-125 | PROJECT_DIGEST.md names scripts/quality/run-box-vision-doc-check.ps1 as its own 'Phase 0 validation script' (expected to write scripts/reports/out/box-vision-doc-check-report.json), but neither the script nor any equivalent under a different name was ever built -- a real, never-fulfilled commitment, not a stale path | GAP | LOW | DONE | 2026-08-03 |
| REG-126 | Normalize-AiContract.ps1 translates requiredRole for panels, procedures, and workflow transitions, but never for flows[] (the generic concept create/update declaration) -- the role gate is silently dropped, and the generated REST create endpoint ends up denying every role including the one the scenario intended to allow | BUG | LOW | DONE | 2026-08-03 |
| REG-127 | tracestore-postgres's PersistentExecutionTracerTest is a stub that asserts nothing (assertTrue(true)) but counts toward the module's '2 test files' coverage figure -- found while assessing the six nightly-only *-postgres adapters for B21 promotion (S1_SPEC.md O2) | BUG | LOW | DONE | 2026-08-03 |
| REG-128 | NPDevRuntimeHost/build.gradle's embedded runtimehost-libs-dir fallback (resolveNpdevRuntimeLibsDir) still defaults to <repo>__OutsideRepo/runtimehost-libs, never updated by the LC-C4/Wave 1.4 unification that moved sync-runtimehost-libs.ps1 and Build-NpdevApp.ps1 to Build/runtimehost-libs -- and run-runtimehost-gate.ps1 never bridges the gap with NPDEV_RUNTIMEHOST_LIBS_DIR, so its assembled-app test run can silently read stale jars from a directory the gate's own sync step never writes to | BUG | MEDIUM | DONE | 2026-08-04 |
| REG-129 | businessTableIndexes (the schema-realization manifest field B3 surplus-constraint classification depends on) has a documented scope of unique-constraint + bond-lookup indexes only -- it does not capture LNCH-6's implicit panel/query-driven secondary indexes or the author-declared concept.indexes[] escape hatch, both of which emit real DDL. Confirmed on WmsOffice's live database: 17 live indexes across 13 tables, every one idx_<table>_<field> on (tenant_id, field) -- LNCH-6's own exact naming/shape -- classified FOREIGN by an otherwise-correct, 15/15-vector-tested classifier, purely because the manifest never told it these indexes exist. | BUG | MEDIUM | DONE | 2026-08-04 |
| REG-13 | LNCH-18: non-author usability test (ADR-0006 DoD) run for the first time | GAP | HIGH | DONE | 2026-07-21 |
| REG-130 | npdev --version's story is only half-resolved: npdev_cli.py's own VERSION constant is 0.9.0, NPDevContract/dsl/build.gradle's is 0.1.0, and the git tag is beta1.4, with no documented relationship between the three -- a user reading any one of them has no way to know it is not the whole picture | GAP | LOW | DONE | 2026-08-04 |
| REG-131 | npdev run app is broken on any machine other than the author's own: npdev_cli.py's _build_phase hardcodes env.setdefault("NPDEV_RUNTIMEHOST_LIBS_DIR", str(Path("D:/WorkSpace/NPDev/Build/runtimehost-libs"))) -- an absolute Windows D:\ path with no fallback for a machine where that drive/path does not exist | BUG | HIGH | DONE | 2026-08-04 |
| REG-132 | No gate exists on the claim 'the documented setup instructions actually work on a clean machine' -- six other defect-family shapes (four-place field threading, pack-composition, twin-pair drift, blocker-citation freshness, script-inventory/invocation, corpus-role coverage) all have a mechanical control; this one had none, and its absence is what let F3/F6/F8, a stale beta1.1 claim, and all three onboarding walls ship undetected | GAP | MEDIUM | DONE | 2026-08-04 |
| REG-133 | Doc/report generators (generate_dsl_reference.py and siblings) are undeclared consumers of model.schema.json -- nothing enumerates what reads the schema, so an edit to it can silently degrade a generator with no error anywhere, the same shape commit 8cd9860 demonstrated live | GAP | MEDIUM | DONE | 2026-08-04 |
| REG-134 | main is left 29+ commits behind beta1-vision-spine with no tag covering S2-S8 or F1-F9 -- a fresh clone of the repo's own default branch gets none of this session's (or the last several sessions') work, including the first-run fixes (I0-I8) this same plan produces | GAP | HIGH | DONE | 2026-08-04 |
| REG-135 | Accepted boundaries (NPDev's designed limits, e.g. B13's 'no Java data-migration hooks') carry no machine-readable identity: ValidationDiagnostic has code/helpKey/suggestedFix but no boundaryId, B-numbers (B1/B2/B15/B27/...) appear in the validation package as Java comments only, and docs/ACCEPTED_BOUNDARIES.md is a markdown table nothing can query except a human reading it | GAP | MEDIUM | DONE | 2026-08-04 |
| REG-136 | root/NPDevGenerator/NPDevKernel gradle.properties hardcode org.gradle.projectcachedir to this machine's own D:/WorkSpace/NPDev/Build/gradle-project-caches/<module> -- a Gradle START PARAMETER read before any -P/env override can apply, so every gradlew invocation the CLI or sync-runtimehost-libs.ps1 makes fails on any machine without that exact path, breaking the FIRST command in README's own Quickstart (./npdev validate model) | BUG | HIGH | DONE | 2026-08-04 |
| REG-137 | NPDevRuntimeHost/build.gradle.template's resolveNpdevRuntimeLibsDir checked the gradle property before the NPDEV_RUNTIMEHOST_LIBS_DIR env var, so REG-128's generation-time-baked gradle.properties default permanently shadowed any build-time env var override -- breaking 3 generator packaged-app runtime proof tests on Linux CI | BUG | MEDIUM | DONE | 2026-08-05 |
| REG-138 | semantic-behavior-writeback (controller+service+canonicalization) is compiled out of EVERY generated app by the supported-runtime-surface allowlist (deferredControllers), so all 5 /api/admin/model/semantic-behavior-writeback[...] endpoints 404 by default -- and even the one directly-executable mutation only appends to a side JSON file nothing reads back | GAP | MEDIUM | DONE | 2026-08-06 |
| REG-139 | ModelEditorPanel.tsx crashes with an uncaught TypeError on a fresh generated app: GET /api/admin/model/editor/draft's no-draft-yet fallback returns the raw compiled model.json (concepts/procedures/panels) verbatim, but the frontend blindly casts it to ModelEditorDraft (entities), so draft.entities.find(...) throws on undefined | BUG | HIGH | DONE | 2026-08-07 |
| REG-14 | LNCH-22: newcomer documentation test run for the first time | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-140 | Every generated app was hard-pinned to Java 17 (build.gradle.template's toolchain literal), with no per-app way to request a newer JDK -- deps-and-java/PLAN.md P2 | GAP | MEDIUM | DONE | 2026-08-07 |
| REG-141 | A custom capability (plugin:java-source) had no supported way to declare a third-party Maven dependency or a local jar -- deps-and-java/PLAN.md P3 | GAP | MEDIUM | DONE | 2026-08-07 |
| REG-142 | Runtime-host template resources SHADOW the generated app's own at the same classpath path, so every generated app served another app's model identity from /api/admin/model/export and threw away its own UI permission policy | BUG | MEDIUM | DONE | 2026-08-07 |
| REG-143 | build.javaVersion's upper enum [17, 21] removed -- floor-only (>=17), future-proofed against every Java version to come, not just 21 -- ROUND2_PLAN.md R1c | GAP | LOW | DONE | 2026-08-07 |
| REG-144 | Every external-build-root resolver found the repo root by its NAME ('NPDev_General'), so a clone named anything else resolved THREE different build roots and Linux CI stayed red for twelve days | BUG | HIGH | DONE | 2026-08-08 |
| REG-15 | LNCH-23: trademark clearance N/A, release tag cut | PROCESS | LOW | DONE | 2026-07-21 |
| REG-16 | The other 23 launch items had zero adversarial review | PROCESS | HIGH | DONE | 2026-07-21 |
| REG-16-resid | Adversarial review of the other ~21 launch surfaces (6-round programme) | PROCESS | HIGH | DONE | 2026-07-24 |
| REG-17 | No third party had ever reproduced any verification | PROCESS | MEDIUM | DONE | 2026-07-21 |
| REG-18 | Login timing side-channel enables username enumeration | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-19 | LoginThrottle.windowsByKey unbounded -- memory-exhaustion DoS via unique-username spray | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-2 | IT-EXTPG-1: 10 integration tests unrunnable; root cause re-opened then found | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-20 | No defense against password-spraying (limiter was per-(tenant,username) only) | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-21 | password-reset/request endpoint unthrottled (email-bomb / token-row spam) | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-22 | ActuatorAdminGuardFilter trusted a JWT claim-role without live re-resolution or tokenVersion check | BUG | MEDIUM | DONE | 2026-07-21 |
| REG-23 | tv-less (tokenVersion-less) JWTs are never revocation-checked, by backward-compat design | GAP | LOW | DONE | 2026-07-21 |
| REG-24 | "default" tenant sentinel collides with a real tenant literally named default | GAP | LOW | DONE | 2026-07-21 |
| REG-25 | Tenant match was case-sensitive -- isolation-bucket fragmentation (not a cross-tenant bypass) | BUG | LOW | DONE | 2026-07-21 |
| REG-26 | Granular JWT error codes disclose why a token failed validation (informational, WONTFIX) | GAP | LOW | DONE | 2026-07-21 |
| REG-27 | REG-8 Trigger C false-negative for a fresh-installed build (rollback silently re-added a dropped column) | BUG | MEDIUM | DONE | 2026-07-22 |
| REG-28 | Stale mark-done fast-forward (REG-7.2): a leftover mark could authorize an unrelated future boot | BUG | MEDIUM | DONE | 2026-07-22 |
| REG-29 | Claim-release-on-refusal was correct but untested (migration collision claim) | BUG | LOW | DONE | 2026-07-22 |
| REG-3 | GATE-REL-1: node_modules/slimness conflict was already fixed; the real gap was stale evidence reports | GAP | LOW | DONE | 2026-07-21 |
| REG-30 | Duplicate mark-done rows each survive one consume, letting a second future boot fast-forward | BUG | LOW | DONE | 2026-07-22 |
| REG-31 | run-script-automation-quality's structured-report-contract check was mis-calibrated (helper-name grep, not a behavior test) | PROCESS | LOW | DONE | 2026-07-24 |
| REG-32 | npdev-ci-validation.yml Bootstrap step aggregated ~21 maturity reports its producers never generated | PROCESS | MEDIUM | DONE | 2026-07-24 |
| REG-33 | CLI's on-demand npm install for the JSON-schema validator failed on Windows from a Python subprocess | BUG | LOW | DONE | 2026-07-24 |
| REG-34 | Windows CI job runs Testcontainers (Linux-container) tests that windows-latest can't run | PROCESS | LOW | DONE | 2026-07-24 |
| REG-35 | Gradle-native postBeta0MaturityCheck had the same missing-vs-invalid conflation REG-32 fixed in PowerShell, plus an overly strict nested artifact schema | PROCESS | LOW | DONE | 2026-07-24 |
| REG-36 | Oversized idempotency keys could exceed the Postgres btree index-entry size limit | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-37 | Circuit-breaker failure-count read-decide-write was not a single atomic critical section | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-38 | Additive-migration constraints were not idempotent on H2 -- redeploy failed with duplicate constraint | BUG | MEDIUM | DONE | 2026-07-24 |
| REG-39 | Stale built-in identity pack copy caused a silent, unhelpful auth failure -- fixed platform-wide | BUG | HIGH | DONE | 2026-07-24 |
| REG-4 | T-F1: load-sensitive SandboxedPluginExecutionEngine test flake, root cause fixed | BUG | LOW | DONE | 2026-07-21 |
| REG-40 | Additive migration never emitted CREATE TABLE -- a new concept on an existing DB failed to boot | BUG | MEDIUM | DONE | 2026-07-24 |
| REG-41 | DefaultConceptGateway.save() leaked a row's lifecycle status to an unauthorized caller before authz ran | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-42 | ConceptGateway.query() leaked a row-scoped count through total/hasMore pagination metadata | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-43 | TenantRegistryService.isActive silently fail-opened on any read failure, with no log at any level | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-44 | crud.kernelControlled=false silently removed ALL coarse permission/audit checks, not just access.write | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-45 | Flow resume was tenant-scoped but not actor-scoped -- any same-tenant user could resume another's flow | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-46 | Persistence capability port had no tenant parameter -- flow-step persistence writes were unscoped | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-47 | Correlation ids had no length cap -- an oversized caller-chosen id could hit the same btree limit as REG-36 | BUG | MEDIUM | DONE | 2026-07-25 |
| REG-48 | DefaultConceptGateway.delete() had the same authz-after-invariant-eval ordering bug REG-41 fixed in save() | BUG | HIGH | DONE | 2026-07-27 |
| REG-49 | M1-SEC-GENCODE finding withdrawn as a false positive -- the reviewed pack was stale, not the platform | BUG | LOW | DONE | 2026-07-27 |
| REG-5 | GATE-OBS-1a: surface-governance drift checks were advisory and unowned | PROCESS | LOW | DONE | 2026-07-21 |
| REG-50 | PostgresPersistenceCapabilityAdapter fell back to UNSCOPED reads/writes on a transient metadata-read failure | BUG | HIGH | DONE | 2026-07-27 |
| REG-51 | External-AI review packs sliced from generated code carried no provenance -- exactly how REG-49 became a false positive | BUG | HIGH | DONE | 2026-07-27 |
| REG-52 | TenantIsolationPolicy.STRICT_EQUALS normalize() only trimmed, never lowercased -- inconsistent with ExecutionContext | BUG | MEDIUM | DONE | 2026-07-27 |
| REG-53 | SqlTypeSupport hardcoded VARCHAR(255) for every string/enum field, ignoring a declared maxLength | BUG | HIGH | DONE | 2026-07-27 |
| REG-54 | Two dead private methods left behind by the T2.B.4 SchemaLifecycleExecutor split | GAP | LOW | DONE | 2026-07-27 |
| REG-55 | Sandboxed plugin overload resolution matched by arg count only, not type -- "Ambiguous" false positive | BUG | MEDIUM | DONE | 2026-07-27 |
| REG-56 | Flow resume rebuilds ExecutionContext with the wrong actor/role, three different wrong ways | BUG | HIGH | DONE | 2026-07-28 |
| REG-57 | H2's default 500ms WRITE_DELAY can lose committed flow-instance checkpoints across a hard kill | BUG | HIGH | DONE | 2026-07-28 |
| REG-58 | Narrow-type DROP COLUMN crashed mid-migration on WmsOffice's real database -- composite unique index not dropped first | BUG | HIGH | DONE | 2026-07-28 |
| REG-59 | WmsOffice live database recovery record (REG-58 fix re-verification) -- recovery only, NOT the platform gap | GAP | MEDIUM | DONE | 2026-07-28 |
| REG-6 | ColumnFacts: eight SchemaLifecycleExecutor passes each re-derived column semantics independently | GAP | MEDIUM | DONE | 2026-07-21 |
| REG-60 | Aggregate Workbench post-commit "Saved." confirmation is wiped by the next render before a user can see it | BUG | LOW | DONE | 2026-07-28 |
| REG-61 | Narrow-type recreate loses NOT NULL; no per-row-unique default expressible for a required UNIQUE column | GAP | HIGH | DONE | 2026-07-28 |
| REG-62 | allowedActions is a typed array and is cross-referenced against the surface's declared actions | GAP | LOW | DONE | 2026-07-28 |
| REG-63 | 17 of 29 corpus models (not 2) used pre-DSL-2.0 flow-step/orchestration shapes the current schema rejects | GAP | MEDIUM | DONE | 2026-07-29 |
| REG-64 | EntityEmitter has no reserved-column collision guard -- a model field named tenantId/version/rowVersion produces uncompilable duplicate-field Java, not a clear message | GAP | LOW | DONE | 2026-07-29 |
| REG-65 | generatedAction was a canonical flowStep.type value FlowValidation always rejected, despite full compiler/generator/runtime support downstream | BUG | MEDIUM | DONE | 2026-07-29 |
| REG-66 | reg39-healthy-control retired -- a byte-identical WmsOffice clone with no independent signal, closed REG-39's own one-time verification artifact | PROCESS | LOW | DONE | 2026-07-29 |
| REG-67 | check-register-consistency.py's --calibrate mode uses bare "HEAD" for its real-instance controls, which silently stops proving anything once the target doc is edited again | GAP | LOW | DONE | 2026-07-29 |
| REG-68 | check-narrative-status-drift.py's Rule P2 real-instance control also used bare "HEAD" and rotted the same way as REG-67 | GAP | LOW | DONE | 2026-07-29 |
| REG-69 | 3 DSL features (fragments, packs, step.updateConcept) have zero coverage on a bare CI checkout -- only exercised by AppGen/apps-only models | GAP | LOW | DONE | 2026-07-29 |
| REG-7 | LNCH-1-B6: no migration advisory lock (multi-instance) -- converted to a feature | BOUNDARY | — | DONE | 2026-07-21 |
| REG-70 | panel.action.binding: "flow" is schema-valid, compiler-accepted, and unimplemented at runtime -- 2 shipping WmsOffice panels already have a dead primary action | BUG | HIGH | DONE | 2026-07-29 |
| REG-71 | panelAction scope="row" + binding="conceptMutation" blanked every other required field to null (executeConceptMutation stripped "id" from a flat body; ConceptGatewaySemanticPolicy separately requires "id" present in the data map) | BUG | HIGH | DONE | 2026-07-29 |
| REG-72 | AggregateRuntime.commit performs N+1 writes and reconcile-deletes with no transaction boundary -- a failure partway leaves a half-written aggregate, and a failure after a reconcile-delete does not restore what was deleted | BUG | HIGH | DONE | 2026-07-29 |
| REG-73 | ProcedureRunner never resolved a capability adapter from the model's bindings list -- every procedure-side capabilityCall step (panel action procedure bindings, and AggregateRuntime.invoke()) reached the dispatcher with a null adapterId and failed CAPABILITY_BINDING_MISSING even with a real binding declared | BUG | HIGH | DONE | 2026-07-29 |
| REG-74 | The plugin-mount/requirement-discovery pipeline only scanned FLOW steps for capabilityCall usage -- a custom Java-source capability referenced ONLY by a procedure (never by any flow) was never mounted (no Java source compiled in, no plugin-manifest entry), so the app failed to boot with "Adapter ... is not declared in active plugin manifest" even though ProcedureRunner's own dispatch (REG-73) correctly resolved the adapter id | BUG | HIGH | DONE | 2026-07-29 |
| REG-75 | Procedures have no way to read an existing concept record, override one field, and pass the merged result onward -- a readConcept result can only be consumed as a whole map by saveConcept (via requireMap's ConceptRecord unwrap), never by capabilityCall's args (which never unwraps ConceptRecord), and no step exists to construct/merge a map literal at all | GAP | MEDIUM | DONE | 2026-07-29 |
| REG-76 | Workbench action inputFields rendered as a single-line <input type="text">, which silently strips/collapses newlines on assignment -- a 'paste multi-line text' propose action (e.g. paste a CSV) had its payload collapsed to one line client-side, so the server-side parser's own header-row detection consumed the entire pasted text and returned zero data rows | BUG | HIGH | DONE | 2026-07-29 |
| REG-77 | Neither procedures nor flows can create a brand-new sibling concept record with an auto-generated id from within a read-modify-write side effect -- patchConcept (REG-75/Move 4) only works on records that already exist, and flows have no patch step at all | GAP | LOW | DONE | 2026-07-30 |
| REG-78 | Procedures have no find-by-non-id-fields lookup usable inline with patchConcept, and no arithmetic/accumulation primitive -- blocking SyncOcupacaoProcedure's real find-or-increment semantics (M8/M9) | GAP | LOW | DONE | 2026-07-30 |
| REG-79 | A callCapability procedure step's args map is compiled with an unspecified, per-JVM-run-random iteration order (Map.copyOf), silently scrambling positional reflective dispatch for any multi-arg capability method | BUG | MEDIUM | DONE | 2026-07-30 |
| REG-8 | LNCH-1-B9: schema-ahead detector blind to a pure column drop on rollback | BOUNDARY | — | DONE | 2026-07-21 |
| REG-80 | field.sensitive is dead wiring -- parsed, compiled, and canonical-JSON round-tripped, but never consumed by anything, including its own documented external-AI-review-pack redaction purpose | GAP | MEDIUM | DONE | 2026-07-30 |
| REG-81 | ReleaseGateValidator.validatePromotion (concept.truthLevel promotion gating) is fully implemented and unit-tested but invoked by no real pipeline -- truth-level promotion is effectively dormant | GAP | LOW | DONE | 2026-07-30 |
| REG-82 | NPDevCliMainTest.idempotencyHitReturnsCachedResultMetadata fails deterministically (IOException loading its own temp model file) -- pre-existing, unrelated to Move 5 | BUG | LOW | DONE | 2026-07-30 |
| REG-83 | saveConcept's blank-idRef auto-generate fallback and patchConcept's createIfMissing create half both silently denied CONCEPT_FIELD_REQUIRED against a real governed ConceptGateway -- the auto-generated id was never folded into the write's own data map | BUG | HIGH | DONE | 2026-07-30 |
| REG-84 | Java DataMigrationHook / code-bearing conversion hooks deferred by ADR-0008 to a never-written ADR-0003 | GAP | LOW | DONE | 2026-07-30 |
| REG-85 | dsl-conformance-max fails ReleaseGateValidator's T2 promotion bar (7 concepts stuck at T1) | GAP | MEDIUM | DONE | 2026-07-31 |
| REG-86 | procedure mapValue/return forced their value through a String ref-only path -- a literal array or object was impossible, only a $ref into procedure state could survive | GAP | LOW | DONE | 2026-07-31 |
| REG-87 | B10: H2->Postgres promotion is a chosen product arc (A4.0 answer) -- build the real command | BOUNDARY | — | DONE | 2026-07-31 |
| REG-88 | B15: await-inside-forEach investigated for closure -- hard stop fired, boundary kept | BOUNDARY | — | DONE | 2026-07-31 |
| REG-89 | patchConcept's author-time 'id is required' rule was never relaxed for createIfMissing, so REG-77's shipped create-only runtime path was unreachable from any model | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-9 | LNCH-4: auth secrets management -- JWT key env-var delivery | GAP | HIGH | DONE | 2026-07-21 |
| REG-90 | Rebuild-And-Restage.ps1 accepted -BuildRoot but never passed it to Build-NpdevApp.ps1 -- the wrapper generated one app and then gated a different one | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-91 | MigrationClaimStore swallows every SQLException from its canonical-row seed as 'row already exists', then reads an unchecked empty ResultSet -- a claim table whose columns are NOT NULL makes the app permanently unbootable with the opaque message 'No data is available' | BUG | HIGH | DONE | 2026-07-31 |
| REG-92 | REG-76 was fixed for workbench inputFields but never mirrored to panel inputFields -- a declared Panel action still renders <input type=text>, which silently collapses newlines, so no panel action can take multi-line input | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-93 | The panel-provenance impact gate failed on manifests whose screen had been DELETED -- it has been RED since Move 8's crossdocking deletion, and structurally contradicted the bytes-deleted metric it is supposed to coexist with | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-94 | run-ai-knowledge-gate.ps1 has been RED on two independent steps and nobody noticed -- 11 untriaged security-pattern hits from the Moves 9-10 schema-engine work, and a script-inventory scan that walked into node_modules and failed on vendored third-party .js files | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-95 | The Aggregate Workbench's derived-field expression subset split its path on '.' before matching, so every filter(...) form -- including the subset's OWN documented example -- silently evaluated to 0; M6's balanced-quantity banner had been declared and shipping a wrong number since Move 6 | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-96 | A procedure's condition/if step can only test a reference's TRUTHINESS -- there is no equality or comparison primitive -- so no aggregate onCommit hook can be guarded on 'the record reached state X', and a lifecycle-transition side effect cannot be expressed declaratively | GAP | MEDIUM | DONE | 2026-07-31 |
| REG-97 | CompiledModelCanonicalJson is not idempotent under write -> read -> write: the READER back-filled a concept invariant's empty fields[] from its field, so the CANONICAL form of a model depended on how many times it had been round-tripped | BUG | MEDIUM | DONE | 2026-07-31 |
| REG-98 | Two differently-named concepts can compile to the SAME physical table and the model validates with zero errors -- SqlIdentifierSupport.toSnake() sanitizes by REPLACEMENT (every non-alphanumeric becomes '_'), and nothing checks the derived table names for collisions | BUG | HIGH | DONE | 2026-07-31 |
| REG-99 | A band's transaction.visibleWhen was unreachable in EVERY spelling -- the validator accepts only the derived address 'collection.band', the expander read only the bare band name, so the predicate validated and was silently dropped | BUG | MEDIUM | DONE | 2026-07-31 |
| STOR-1 | 41 dialect-bound SQL sites were inlined across 19 files, so a second database engine was a rewrite rather than a dialect -- and two files had already grown a hand-rolled H2-vs-Postgres fork | GAP | MEDIUM | DONE | 2026-08-08 |
| STOR-10 | Five more two-engine assumptions between "the app boots" and "the app works" -- a Postgres-by- default dialect probe, UUID and timestamp values bound and read in shapes only two engines accept, a schema differ comparing the catalog against a type it never emitted, and a two-way column rename | BUG | HIGH | DONE | 2026-08-08 |
| STOR-11 | On MySQL a create that violates a unique constraint returns 200 and OVERWRITES the row that held the value, because ON DUPLICATE KEY UPDATE reacts to every unique index -- the dialect's own javadoc said no NPDev schema could produce this shape, and any `unique: true` field does | BUG | HIGH | DONE | 2026-08-08 |
| STOR-12 | A MySQL or SQL Server app boots once and never again -- the migration-claim store tested for Postgres's SQLSTATE 23505, so the ordinary "the canonical row already exists" case was reported as a hard failure, with a message asserting the exact opposite of the truth | BUG | HIGH | DONE | 2026-08-09 |
| STOR-14 | No way to say "this database is not mine to manage" -- the `_ops` toolbox assumes it provisioned every server engine, and `Reset-Environment.ps1` recursively deletes a data root that may be the user's own | GAP | HIGH | DONE | 2026-08-09 |
| STOR-2 | A conversion hook's refusal claimed "the hook's changes were rolled back; nothing persisted" on engines that COMMIT IMPLICITLY ON DDL -- false on H2 today, and the decision MySQL forced | BUG | HIGH | DONE | 2026-08-08 |
| STOR-3 | MySQL, PostgreSQL and SQL Server each pass 13/13 Tier B vectors against REAL containers -- but none is supported until that run is repeatable rather than a manual dispatch of unpinned images | GAP | MEDIUM | DONE | 2026-08-08 |
| STOR-4 | MySQL and SqlServer were selectable, dialect-complete and conformance-green -- and no generated app could ever have connected to either, because the app template carried no JDBC driver for them | BUG | HIGH | DONE | 2026-08-08 |
| STOR-5 | The schema-realization script is written in Postgres/H2 guarded-DDL idioms (IF NOT EXISTS), which MySQL supports only partly and SQL Server not at all -- so NPDev's own V1 migration cannot run | GAP | HIGH | DONE | 2026-08-08 |
| STOR-6 | The generator never quotes business identifiers, so a model field named after a reserved word (value, order, group) produces a schema script no engine will run -- conformance Q1, proven at the dialect layer and never exercised at application level | BUG | MEDIUM | DONE | 2026-08-08 |
| STOR-7 | A text column plays three roles -- payload, key, defaulted -- and only two were ever asked about; MySQL rejected a TEXT DEFAULT and SQL Server could not index the runtime host's own bootstrap tables, so no generated app booted on either engine | BUG | HIGH | DONE | 2026-08-08 |
| STOR-8 | db.definition.json's `jdbcUrl`/`h2FilePath` could contradict the real connection silently -- a user pointing one at an existing database got no error and a connection somewhere else | BUG | MEDIUM | DONE | 2026-08-08 |
| STOR-9 | A row lock is a suffix on three engines and a table hint on SQL Server, and three sites spelled the suffix inline -- so every app's FIRST boot on SQL Server died taking the migration lock, after the schema had already realized correctly | BUG | HIGH | DONE | 2026-08-08 |

### Detail

### PORT-1 — Six generated artefacts carry an absolute path from the AUTHORING machine into output a stranger runs -- including npdev.database.data-root, which is resolved at RUNTIME, so a generated app tries to open its database on a drive the user does not have

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-10)
**Verification:** VERIFIED_LIVE
**Source:** Found by the first out-of-tree generation this repo has ever performed (scripts/hygiene/check-out-of-tree-generation.ps1, written for exactly this purpose on 2026-08-10 after THIRD_PERSON_TRIAL_ANALYSIS_2026-08-10.md observed that every existing gate verifies THE REPO and none verifies the experience of generating from somewhere else).
npdev-canary generated to C:\npdev-oot\... -- outside both the workspace and AppGen ancestry -- 807 emitted files scanned, 15 hits, of which 4 are provenance or comments ABOUT the defect and ten are real. The check reproduced F7 from the third-person trial independently, without being told to look for it.
**Surface:** `generator/emitted-output`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator`
- `NPDevRuntimeHost/src/main/resources/application-wmsoffice.yml`
- `scripts/hygiene/check-out-of-tree-generation.ps1`
- `scripts/hygiene/out-of-tree-generation-baseline.json`

The forbidden token set is DERIVED from the live machine at runtime (workspace root, build root, runtimehost-libs, AppGen root, user profile), so this is not a D:-drive quirk: the same emitters will bake in whatever absolute paths the generating machine happens to have. On a contributor's machine they leak that contributor's paths instead.
Ranked by how badly a stranger is hurt:
1. RESOLVED AT RUNTIME -- the app does not work.
     src/main/resources/application-npdev-db.properties
       spring.datasource.url=jdbc:h2:file:D:/WorkSpace/NPDev/Build/databases/<app>/<db>
       npdev.database.data-root=D:/WorkSpace/NPDev/Build/databases/<app>
     _ops/resolved-db-plan.json  (the same root, restated in the ops plan the user is told to read)

   The one that matters is spring.datasource.url. UserDatabaseDefinitionLoader builds the H2_LOCAL
   URL as "jdbc:h2:file:" + identity.resolvedDataRoot(), so the author's absolute path is baked
   INTO THE JDBC URL, and Spring resolves it at boot. A generated app handed to anyone else tries
   to open its database on a drive letter they may not have.

   Recorded precisely because the first version of this row got it wrong: it blamed
   npdev.database.data-root, which NO Java code reads (grep-confirmed across RuntimeHost and
   Kernel main source -- its only consumers are the _ops PowerShell scripts). The check had
   reported only the FIRST offending line per file and hid the datasource URL two lines below.
   The scanner now reports every offending line, for exactly this reason.

2. SHIPPED INSIDE THE APP -- wrong, and leaks the author's filesystem layout to whoever receives
   the artefact.
     src/main/resources/npdev/model-source-manifest.json      rootModel: <abs authoring path>
     src/main/resources/npdev/db/schema-realization-manifest.json  sourceOfTruth.business/database
   Provenance is legitimate; putting it in src/main/resources rather than Reports/ is not.

3. AN APP-SPECIFIC PROFILE LEAKING INTO EVERY APP (this is F7).
     src/main/resources/application-wmsoffice.yml
   Carries D:/WorkSpace/NPDev/Build/wmsoffice-keys/jwt-public.pem and its private-key sibling.
   Every generated app -- for every user, whatever they modelled -- ships a WmsOffice JWT profile
   pointing at the author's key directory. Two defects in one: the profile should not be there at
   all, and it names key paths.

4. A STALE COMMITTED BUNDLE -- and this one is a DIFFERENT defect from the other five.
     static/npdev-ui-react/assets/AuthoringApp.js
   The bundle emits a PowerShell snippet defaulting to
   `[string]$NPDevRoot = 'D:\WorkSpace\NPDev_General'` -- an author path AND a hardcoded repo
   folder NAME, the exact pair REG-144 eliminated from eleven resolution sites.

   But the SOURCE was already fixed. NPDevEditor/ui-react/src/authoring/pipeline/pipelineHandoff.ts
   emits `$env:NPDEV_ROOT` today and carries a REG-144 comment saying so. What shipped is the
   BUNDLE, committed under npdev-templates/static-react/assets/ and never rebuilt after that fix.

   So the live defect is DRIFT between a committed generated artefact and the source it is
   generated from, with nothing checking. CLAUDE.md tells every reader the bundle is "generated,
   ignore entirely" -- correct advice for reviewing it, and exactly why a stale copy could ship a
   string the source had already deleted. Rebuilding closes the instance; the absent
   bundle-freshness check is the class, and is NOT closed by this item.

WHY NO GATE SAW ANY OF THIS: all generation in this repo happens under the author's own layout, where an absolute path to that layout is indistinguishable from a correct one. The defect is not visible from inside; it requires generating somewhere else and looking. That is now check scripts/hygiene/check-out-of-tree-generation.ps1, wired into run-generator-gate.ps1.

### PORT-2 — The _ops toolbox baked the generation-time location into four files, so a COPIED app silently built and ran the ORIGINAL -- and the check that had just declared this class closed was blind to it by construction

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-10)
**Verification:** VERIFIED_LIVE
**Source:** A post-implementation review of 9fd1f74e (the commit that closed PORT-1 and STOR-14), by hand, against the checklist written before that commit landed. The review copied a generated app to a directory sharing no ancestry with its birthplace and read what the copy still pointed at. The gates did not find this; a human moving a directory did.
**Surface:** `generator/emitted-output, quality/out-of-tree-check`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/OperationalRunbookEmitter.java`
- `NPDevCli/npdev_cli.py`
- `scripts/hygiene/check-out-of-tree-generation.ps1`
- `scripts/hygiene/out-of-tree-generation-baseline.json`

TWO FINDINGS, AND THE SECOND ONE IS WHY THE FIRST SHIPPED AS "CLOSED". They are filed as one item deliberately: separating them would record a leak that was fixed and lose the reason nobody saw it.
FINDING 1 -- FOUR FILES NAMED THEIR BIRTHPLACE.

  _ops/Run-FinalApp.ps1        Set-Location '<abs app path>'; java -jar '<abs>/build/libs/...jar'
  _ops/Build-FinalApp.ps1      Set-Location '<abs app path>'; -PnpdevRuntimeHostLibsDir='<abs>'
  _ops/resolved-db-plan.json   finalAppPath, opsRoot, runtimeHostLibsDir -- all absolute
  _ops/README_RUNBOOK.md       seven absolute paths, in the commands a user is told to type

The consequence is worse than "does not work", which is what makes it expensive. A moved app does not fail: it builds and runs the ORIGINAL app at the original path, successfully. Someone who copies an app, edits the copy and runs _ops/Run-FinalApp.ps1 is running the app they did not edit, and nothing anywhere says so. If the original has since been deleted they get an error naming a directory they never chose.
This is PORT-1's family and was not covered by it: PORT-1 was about paths from the AUTHOR'S LAYOUT (the build root, a key directory, ~/.gradle) reaching a stranger's machine. This is a path that is perfectly correct on the generating machine and wrong the instant the artefact is handed on.
FINDING 2 -- THE CHECK COULD NOT SEE IT, AND NOT BY OVERSIGHT.
scripts/hygiene/check-out-of-tree-generation.ps1 reported "807 files scanned, 0 violations" on the very tree that produced Finding 1, and was right to by its own rules. It scans emitted output for a set of forbidden absolute paths DERIVED from the live machine, and it REFUSES TO RUN unless the output root it generates into is itself token-free -- a guard added on purpose, so that a file legitimately referring to where it was generated would not be a false positive.
That guard is precisely what hid the defect. If the output root contains no forbidden token, then a file hardcoding THE OUTPUT ROOT ITSELF contains no forbidden token either, and is invisible. The blindness is structural, not a missing pattern: it cannot be fixed by adding tokens, because the offending string IS the one string the check has guaranteed is not forbidden. A checker whose false-positive guard is also its false-negative mechanism will keep reporting zero for as long as the defect exists.

### QUAL-1 — check-dsl-coverage.py is 913 lines against a 400-line hard stop -- a genuine split candidate that keeps blocking unrelated work, recorded so the ceiling it was given is a decision rather than an oversight

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/parity/ENGINE_PARITY_PLAN.md P7. Found by storage/helpers/check-script-budget.py while clearing the parity round's housekeeping.
**Surface:** `scripts/quality`
**Files:**
- `scripts/quality/check-dsl-coverage.py`

`scripts/quality/check-dsl-coverage.py` is 913 lines. The budget for a `check-*.py` is a 250-line
target and a 400-line hard stop, and the baseline had frozen it at 894 -- so it has also GROWN past
its own frozen ceiling, which the budget tool treats as blocking on purpose: "the baseline records
a ceiling, not a licence."

It did not grow during this workstream. It is recorded here because the alternative was to raise
its ceiling silently, and a limit quietly moved is indistinguishable from a limit that was never
enforced.

The other two over-budget scripts have arguments that this one does not:

  run-ai-knowledge-gate.ps1 (577)   a HOST for 35 checks; a 500-line stop was sized for a script
                                   that does one thing, and splitting the host would scatter the
                                   ordering guarantee that makes "all gates green" mean something
  check-emitted-sql-portability.py  2% over, and the overage is the CONSTRUCTS table where each
                                   (409)  entry records a measured engine failure

913 against 400 is not 2%. It is a checker that has accumulated several jobs.

### QUAL-2 — Ten unclosed Files.list/walk/lines streams in NPDevRuntimeHost production services -- the same leaked-directory-handle defect that made the local generator gate permanently red

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-10)
**Verification:** UNIT_TESTED
**Source:** storage/stabilize/STABILIZE_PLAN.md S1. Found by sweeping for the family after fixing the instance: ConversionHookEmitterTest line 128 called `Files.list(hooksOut).count()` without closing the returned Stream, which on Windows left the directory DELETE-PENDING and made JUnit's @TempDir teardown fail -- reported for long enough that it was believed to be "a Windows file-lock in the harness, not the test body". It was the test body.
**Surface:** `runtimehost/services`

`Files.list`, `Files.walk`, `Files.find` and `Files.lines` all return a Stream backed by an open OS handle, and all four javadocs say the Stream must be closed (try-with-resources). The one in the generator test was fixed under S1; the sweep that followed found ten more, all in NPDevRuntimeHost MAIN source rather than tests:

    service/experimental/TemplateLibraryManagementService.java:54          Files.list
    service/FileRuntimePluginExecutionSummaryStore.java:94                 Files.lines
    service/internal/SemanticBehaviorWriteBackCanonicalizationService.java:57  Files.list
    service/internal/SemanticBehaviorWriteBackService.java:451             Files.list
    service/internal/TenantNativeGovernanceService.java:57                 Files.list
    service/internal/TenantOperationalAdministrationService.java:75        Files.list
    service/internal/TenantOperationalAdministrationService.java:221       Files.list
    service/internal/TenantOperationalAdministrationService.java:250       Files.list
    service/internal/WorkingDraftSystemService.java:440                    Files.list
    tenant/TenantPartitionRealityVerifier.java:63                          Files.walk

Why this is worth an item rather than a quiet fix: the symptom is PLATFORM-DEPENDENT and delayed. POSIX permits unlinking a directory that is still open, so on Linux CI these leak a file descriptor and nothing else observable; on Windows the directory becomes undeletable (or delete-pending, which blocks its PARENT from being removed and names the parent in the error, not the leaked path). A generated app that runs long enough on Windows can therefore fail to clean up a tenant or draft directory for a reason whose error message points somewhere else entirely -- which is exactly how the generator instance got misdiagnosed as a harness problem for so long.
Deliberately NOT fixed in the same pass. STABILIZE_PLAN.md S4 freezes main from the release tag until the second machine reports, and states that anything discovered which does not block that machine goes to the backlog unfixed -- the point of the freeze being that the second machine tests one thing rather than a moving target. None of these ten is on the path a first-run user walks (create an app, build it, boot it, change a field): they are tenant-administration, working-draft and template-library services.

### QUAL-3 — Two apps in one folder became ONE database -- a shared `_ops` toolbox AND a shared appId, so container name and data root collided; resetting either destroyed the other's data

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/stabilize/STABILIZE_PLAN.md M14 (found while wiring the Manager's five database buttons), re-rated and closed under storage/stabilize/TAG_PLAN.md section 2.
**Surface:** `generator/dbconfig`

Re-rated MEDIUM -> HIGH on filing evidence, then found to be WIDER than filed.
Two sites encoded "this belongs to the PARENT DIRECTORY, not to the app":

  1. OperationalRunbookEmitter.emit  ->  finalAppRoot.getParent().resolve("_ops")
  2. UserDatabaseDefinitionLoader.resolveAppId  ->  two directory levels up from db.definition.json

Site 2 was NOT in the original filing and is the one that destroys data. Walking two levels up is right for the corpus layouts (`<App>/definition/...` and `<App>/Input/...`) and wrong for the `npdev init` layout, which writes the definition directly into the app directory -- so two levels up is the PARENT FOLDER, shared by every app in it.
MEASURED RED (two apps really generated into one folder, not inferred):

    app-a: appId=qual3  container=npdev-qual3  dataRoot=Build/databases/qual3
    app-b: appId=qual3  container=npdev-qual3  dataRoot=Build/databases/qual3
    npdev db status --app <app-a>   ->   [qual3 | Postgres | .../app-b-app]

So they were not two apps sharing a toolbox. They were one database with two front doors. Fixing only the `_ops` location would have left both apps pointed at one container and one data root, and Reset for either would still have destroyed the other's data -- the fix would have looked complete and changed nothing that mattered.
Why HIGH, not MEDIUM: it destroys data and reports success (the class STOR-11 was rated HIGH for); two apps in one folder is what evaluating the product looks like, not an exotic setup; and the acknowledgement token cannot help, because the user types it correctly for the app they intend and different data is deleted. M14 had just made Reset a button, so the risk rose the day the toolbox got easier to reach.
A THIRD and FOURTH site encoded the same assumption and had to move with it (the plan predicted only two): `scripts/quality/run-engine-toolbox-parity.py` read `output.parent / "_ops"`, and `scripts/appgen/Build-NpdevApp.ps1` writes its own separate toolbox at `$OutRoot/_ops`. The latter is per-appId by construction (`$OutRoot = $BuildRoot/$AppId`) so it cannot collide and was left alone; the former follows the emitter. A FIRST ATTEMPT AT SITE 2 WAS WRONG AND IS WORTH RECORDING. Keying identity on the directory NAME ("if it is not called `definition/`, that directory is the app") looked right and passed the new tests. Measured against the corpus, it was worse than the defect: 25 definitions live in a directory called `Input` with no manifest.json, and the rule collapsed all 25 onto `appId=Input`. T2 caught it as an unrelated-looking STOR-8 failure (`UserDatabaseDefinitionDeclaredConnectionTest`, "an h2FilePath that agrees with the derived path still loads") because that test's two temp definitions stopped resolving to one app. Path shape cannot distinguish an app directory from a wrapper directory; asking it to was the mistake.

### REG-1 — 9 app definitions remain on the deprecated blanket destructive posture (down from 27)

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `appgen/schema-lifecycle-policy`

A repo-wide audit found 18 unreferenced sample-app definitions still on the deprecated
DropAndRecreateOnStructureChange blanket posture; moved to an outside-repo archive (recoverable),
shrinking the tracked pool from 38 to 20. All 7 remaining flip-worthy apps (4 AppGen _official +
invoice-bonds-demo + 2 NPDevSamples) flipped to KeepExistingIfCompatible +
allowDestructiveRecreate:false. Verified per app: clean regeneration, manifest carries the new
lifecycle, and a live additive-change proof on superuser-admin-console showed the boot log
correctly skipping destructive recreation. 2 apps (lnch1-rehearsal,
simple-user-registry-h2local-freshdb) are deliberately kept on blanket by documented design.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-1`

### REG-10 — LNCH-19: Linux CI observed green for the first time

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `ci/pr-gate`

Every prior quality claim had run only on one Windows machine; nobody had watched a real GitHub
Actions run go green. npdev-pr-gate.yml ran green on ubuntu-latest (run 29899362276, commit
3dcc51e) -- every step success: DSL contract, kernel inproc adapters, all 168 generator unit tests
(incl. 3 packaged-app boot/HTTP/JDBC proof tests), RuntimeHost libs sync, sample generation, and the
RuntimeHost generated-app suite. Took six root-caused fixes across seven runs: a hardcoded Windows
pwsh.exe path, NPDEV_BUILD_ROOT disagreement (repo checked out as "NPDevGeneral" without the
underscore), a real product portability bug (every generated FinalApp inherited a hardcoded
D:/WorkSpace/NPDev/Build gradle projectcachedir -- see REG-11), a hand-maintained adapter list
missing mail-inproc/mail-smtp, missing CI diagnostics, and a ".." in an artifact upload path.
Caveat noted at closure: the green run was on an older branch line; confirming green on the latest
line was a scheduled follow-up.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-10`

### REG-100 — CLOSED -- three silent-answer sites found by the X0 audit, now fixed: a $ref that could not resolve wrote null (while the SAME class threw for id refs), a runQuery step naming an undeclared query returned an UNFILTERED list, and a typo'd $root.<field> visibleWhen predicate went unvalidated

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Wave 0.2's X0 silent-expression sweep (MASTER_AI_PLATFORM_PROGRAMME_v2.md §2.1), which asked every
expression evaluator in the platform one question: what does it do with input it cannot handle?
Full register: docs/X0_SILENT_EXPRESSION_REGISTER.md.

The programme opened X0 with two confirmed instances and predicted a third. The audit found five;
two were already filed (REG-95 fixed, REG-96 open) and one was found live during the same wave
(REG-99). This item carries the remaining three.

**Surface:** `kernel/procedures, dsl, generator/workbench`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/PanelValidation.java`

**X0-6 — `resolve()` returns null for an unresolvable path, and the same class disagrees with itself.**

`DefaultProcedureExecutor.resolve(state, ref)` walks a dotted path and returns `null` the moment a
segment is missing. It backs `resolveSetValue`, which is what `patchConcept.set`, `mapList.select`
and `mapValue` all use. So a typo:

    { "type": "patchConcept", "concept": "Lote", "id": "$loteId",
      "set": { "quantidade": "$item.quantidad" } }

writes `quantidade: null`, with no error on any path.

What makes this a finding rather than a design choice: **the same class already disagrees with
itself.** `requireString` and `requireMap` call the same `resolve` and then THROW
`IllegalArgumentException` on null/blank. Id refs are loud; value refs are silent. One of the two
is wrong and nothing records which.

Interaction with REG-89's history worth naming: `patchConcept` + `createIfMissing` builds a new
record from `set` alone, so a typo'd ref there creates a record with a null field, and a governed
gateway then rejects it for a required-field violation naming a field the author never wrote --
the error surfaces one layer from its cause.

**X0-7 — an unresolved `runQuery` name returns an UNFILTERED list.** From the runtime's own comment:

    // Absent from queriesByName -> unfiltered, same as before this fix.
    CompiledQuery query = step.operation() == null ? null : queriesByName.get(...);

This is LC-P0's shape one layer up: LC-P0 is a declared `where` that does not filter; this is a
declared QUERY that does not filter because its name did not resolve. Already acknowledged in a
comment, which is the most dangerous state for this class -- known, written down, invisible to the
author. `PackValidation` does check that a `runQuery` step names a declared query, so the
model-level door is shut; the runtime lookup is keyed by a normalized name and falls back to
unfiltered rather than failing, so any drift between the two (pack-provided queries, a rename, a
normalization mismatch) reopens it silently.

**X0-6 and X0-7 should be fixed WITH LC-P0 in Wave 0.3, not separately** -- all three are one
sentence: a filter or reference that cannot be resolved is an error, never an empty filter or a
null value.

**X0-8 residual — `$root.<field>` visibleWhen predicates are never validated.** `evaluateVisibleWhen`
fails OPEN by design, and that reasoning is accepted (a hidden surface whose rows still commit is
the worse failure). But fail-open is only safe if a wrong predicate is caught at authoring time,
and only half are: Move 11 W6 added validation for `$ui.<name>` (undeclared control, or a literal
outside the control's declared values -> refused), while `$root.tpio == 'X'` validates clean and
then silently shows everything forever.

**Fix shape**: validate `$root.<field>` against the root concept's declared fields exactly as
`$ui.<name>` is now validated -- the code is a near-copy of `validateUiStateReference`. Small, and
it turns the accepted fail-open from a hazard into a genuinely safe default.

**The shape to converge on** is already in the codebase: `computeValue` refuses an unknown operator
at MODEL level, naming the legal set ("computeValue requires operation to be one of [add,
subtract], got: multiply"). No runtime default, no silent answer.

**Not audited, and the highest-value item left in X0**: `expression-cel` (invariants, `access.read`
/`access.write`, `defaultExpression`, `derivedExpression`) -- the only real expression engine in the
platform, and the one where a silent default matters most, since `access.*` is an authorization
answer. It deserves its own pass, not a paragraph.

---

**Move 12 P1.1-P1.3 closure (2026-08-01):**

- **X0-6** -- added `DefaultProcedureExecutor.resolveStrict`, a strict counterpart to `resolve()`
  used by `resolveSetValue` (so it covers every consumer in one choke point: `patchConcept.set`
  x2, `mapList.select`, `mapValue`, `computeValue`'s left/right, `return`'s valueRef -- not just the
  three named in the original finding). Throws a new `UnresolvableReferenceException`, caught in
  `executeStepWithBudget` and surfaced as a named `REF_UNRESOLVABLE` step failure carrying the ref
  and step name -- the same exception-based propagation shape `requireString` already uses, just
  with a specific code instead of the generic `PROCEDURE_STEP_FAILED` fallback. Distinguishes "key
  never bound" (refused) from "key bound to an explicit null" (still a legitimately resolved value)
  via `containsKey`, not a null check -- otherwise the fix would trade one silent defect for a
  spurious failure on real nulls. `patchConcept`'s own `idRef` resolution stays on the LENIENT
  `resolve()` by design (Move 5 Wave 1B's `createIfMissing`: an unresolved/blank idRef means
  "nothing to look up yet, create new", not a typo) -- the one legitimate-absence site point 3
  asked to name. RED/GREEN proof:
  `NPDevKernel/kernel/src/test/java/com/npdev/kernel/procedures/DefaultProcedureExecutorRefUnresolvableTest.java`
  (6 tests, governed gateway per R4), full kernel suite re-run green after the change.
- **X0-7** -- found already implemented in the uncommitted Move 12 P0 tree (runtime `QUERY_NOT_FOUND`
  in `DefaultProcedureExecutor.runQuery`, author-time `PackValidation.validateProcedureSteps`'s
  `PROCEDURE_QUERY_STEP_TYPES` check), just never marked DONE here nor covered by a model-level
  test. Added the missing DoD piece: `ProcedureRunQueryUndeclaredNameValidationTest` in
  `NPDevContract/dsl`, going through the real `JsonModelParser` + `SemanticValidator` front door
  per the REG-89 lesson (kernel-only tests build a `ProcedureStep` directly and cannot see a
  validator gap).
- **X0-8** -- added `PanelValidation.validateRootFieldReference` (near-copy of
  `validateUiStateReference`, as the finding specified), wired into `validateVisibleWhen` alongside
  the existing `$ui.` check. A `$root.<field>` predicate must now name a field declared on the
  aggregate's root concept; unlike `$ui.` there is no fixed value set (root fields are typed, open
  domains), so only the field name is checked, not the literal. Full DSL suite re-run green
  (472 tests, 106 classes, 0 failures) -- existing `visibleWhen` fixtures already used real field
  names (`tipo`), so nothing broke.

Left open for a future pass, not attempted here: the `expression-cel` audit named above.

### REG-101 — A declared query can carry parameters[] and a ':name' bind placeholder in its where, and NOTHING substitutes it -- pack-sample's SalesByStore has therefore returned zero rows since it was written, and the DSL accepts the shape with no error

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Found by LC-P0's own detector on its first run (Wave 0.4,
MASTER_AI_PLATFORM_PROGRAMME_v2.md) -- i.e. the corpus scan that exists precisely to find models
whose declared filter never worked found one immediately:

  $ python scripts\quality\check-query-predicate-compilable.py
  Query-predicate compilability (29 corpus model(s))
    14 declared predicate(s) checked, 1 uncompilable

  FAIL: a declared `where` cannot be compiled ...
    - AppGen/apps/pack-sample/definition/model.json: queries[SalesByStore].where =
      'storeId == :storeId' -- literal ':storeId' is neither a quoted string, a number,
      nor a boolean

The declaration:

    { "name": "SalesByStore", "concept": "Sale",
      "where": "storeId == :storeId",
      "orderBy": ["soldAt"],
      "parameters": [ { "name": "storeId", "type": "uuid", "required": true } ] }

The author's intent is unambiguous -- a declared parameter, bound into the predicate. The schema
has a `parameters` property on `query`, so the DSL invites this.

**Surface:** `kernel/concepts, dsl`
**Files:**
- `AppGen/apps/pack-sample/definition/model.json`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/concepts/ConceptQueryPredicateCompiler.java`

**What actually happened before LC-P0.** The old `ConceptQueryFilterSupport` split on the first
`==`, took `:storeId` as the literal text (unquoted and non-numeric, so returned verbatim), and
compared every `Sale.storeId` against the seven-character string `":storeId"`. Nothing matched.
**This query has returned zero rows for its whole life**, with no error at any layer -- and, being
the "returns nothing" variant of the silent-answer class, it looks exactly like "this store has no
sales" rather than like a bug.

**What happens after LC-P0.** The predicate is refused by name, quoting the offending literal and
saying a `$`/bind reference must be substituted before compiling. The query is still broken; the
author is now told. That is the whole thesis of LC-P0, and this item is its first real witness.

**Two things are missing, and they are separable:**

1. **Substitution.** `queries[].parameters` is declared, schema-valid, and bound by nothing. A
   query parameter is a real feature the DSL advertises and the runtime does not implement.
2. **Validation.** Nothing checks that a `where` is compilable at MODEL level, so the shape passes
   `validateModel` with 0 errors. LC-P0 put the refusal in the RUNTIME because that is where the
   compiler lives (kernel); the DSL module cannot depend on the kernel, so it cannot reuse it.

**Fix shape**, in the order that keeps each step honest:
  (a) decide the placeholder spelling ONCE -- `:name` (this model), `$name` (procedures' own
      convention), or both -- and write it down; today two conventions exist in one platform;
  (b) substitute declared parameters into the predicate before compiling, with a named error for
      an unbound parameter (never a default answer -- X0's rule applies to substitution too);
  (c) move `ConceptQueryPredicateCompiler` to a module both the DSL validator and the kernel can
      use, so an uncompilable `where` is refused at authoring time and
      `check-query-predicate-compilable.py` (which today mirrors the grammar in Python) can be
      deleted rather than maintained in two languages.

**Not fixed here.** Wave 0.3's brief is "make a declared query actually filter, and fail loudly on
what it cannot compile" -- both done. Parameter substitution is a distinct feature, and widening
the grammar to *accept* `:storeId` without substituting it would put the silent wrong answer back,
which is the one thing LC-P0 exists to prevent.

The corpus instance is recorded in `scripts/quality/query-predicate-allowlist.json` citing this id,
so the detector measures NEW breakage rather than re-reporting a known, filed one.

---

**Move 12 P1.4 closure (2026-08-01):** (a) already settled -- `:name` (this model's own convention,
matching the corpus witness and this item's own text) over `$name` (procedures'). Did (b) and (c):

- **(b) substitution.** `ConceptQueryPredicateCompiler.compile(String, List<CompiledProcedureParameter>,
  Map<String,Object>)` -- a new overload alongside the original single-string one (which stays
  strict: no bound values means a `:name` placeholder is still refused, unchanged). Resolves a
  `:name` literal against the query's declared `parameters[]` (an undeclared name is refused) and a
  caller-supplied value map (an unbound declared name is refused -- X0's rule: never default to
  null or drop the clause). RED/GREEN proven against the real corpus shape --
  `pack-sample`'s `SalesByStore` (`storeId == :storeId`) -- seeded rows, bound `storeId=store-a`,
  and a real `ConceptGateway.query()` call returning exactly that store's 2 rows, not 0 and not all
  3 (`ConceptQueryPredicateCompilerParameterSubstitutionTest`, `NPDevKernel`).
- **(c) shared module.** The grammar (tokenizing `where` into field/operator/literal, now
  recognizing `:name` as a `Literal.Placeholder` instead of failing) moved to
  `NPDevContract/dsl`'s new `com.npdev.dsl.v1.query.QueryPredicateGrammar` -- kernel already
  depends on `dsl` (not the reverse), so this is usable from both sides without kernel-side types
  (`ConceptQuery.Filter`) leaking into the DSL module. `ConceptQueryPredicateCompiler` now maps the
  shared grammar's output onto kernel types; `PackValidation.validateQueries` calls the SAME
  grammar directly, so `where` is refused at AUTHORING time
  (`QueryWhereCompilabilityValidationTest`, `NPDevContract/dsl`, through the real
  `JsonModelParser`+`SemanticValidator` front door per the REG-89 lesson). Confirmed live via
  `:NPDevContract:dsl:validateModel` against the real `pack-sample/definition/model.json`: 0
  errors (28 unrelated ux-metadata warnings).

`check-query-predicate-compilable.py` and `query-predicate-allowlist.json` are both **deleted**
(`scripts/reports/out/*` regenerate on next gate run) -- the corpus-wide check they existed for is
now done by the real Java validator via `scripts/quality/validate-corpus.py`, which already runs
`SemanticValidator` over every corpus model. `check-allowlist-citations.py`'s `ENFORCED` tuple and
`run-ai-knowledge-gate.ps1`'s step numbering (23 -> 22 steps) updated to match; see `BREAKING.md`
2026-08-01 for the grammar-widening note (no `npdev migrate` codemod needed -- every previously
accepted `where` still compiles unchanged, only new grammar became legal).

### REG-102 — npdev migration diff (and the MCP tool npdev_migration_diff that shells out to it) is completely non-functional -- it always throws CONFIG_MIGRATIONS_DISABLED, because it passes generator CLI flags that the generator's own arg parser unconditionally rejects

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found while scoping MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.2 (LC-C1: add METADATA_ONLY to "the
existing classification (SAFE_ADDITIVE/BACKFILL_REQUIRED/MANUAL_REVIEW), already in npdev migration
diff / MCP npdev_migration_diff"). Before extending that classification, tried to run it once to see
its current output shape -- it does not run at all.

Reproduced live:

    $ python NPDevCli/npdev_cli.py migration diff --baseline <x> --current <x>
    Exception in thread "main" java.lang.IllegalArgumentException: CONFIG_MIGRATIONS_DISABLED:
      stateful upgrade management is not supported by this generation path (source: --migrationsDir).
      Use recreate-style generation and schema realization instead.
      at com.npdev.generator.GeneratorMain.migrationsDisabled(GeneratorMain.java:399)
      at com.npdev.generator.GeneratorMain$Args.parse(GeneratorMain.java:730)
    BUILD FAILED
    npdev command failed with exit code 1

**Surface:** `cli, mcp, generator`
**Files:**
- `NPDevCli/npdev_cli.py`
- `NPDevMcp/server.py`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`

`run_migration_diff` (NPDevCli/npdev_cli.py:960) invokes `:generator:run` with
`--migrationsDir`, `--migrationMode=additive-only`, `--migrationPlanOnly`,
`--migrationRiskThreshold=...`, `--migrationDecisionReport` -- five flags, every one of them
starting with the literal substring `--migration`.

`GeneratorMain.Args.parse` (GeneratorMain.java:729) contains:

    if (cur.startsWith("--migration") || cur.startsWith("--enableMigrations")) {
        throw migrationsDisabled(cur);
    }

This check exists to reject the OLD, unsupported migration-management CONFIG surface (model.json
declaring `migrationManagement`/`migrations`/`schemaEvolution` -- see
`rejectUnsupportedMigrationManagement`, a different, correctly-scoped guard over the model JSON).
But the CLI-ARGS version of the guard matches on a bare string prefix, so it ALSO catches the two
real, supported migration-plan flags before they were renamed to dodge it
(`--previousCompiledModel`, `--schemaMigrationPlanOut` -- see the comments at GeneratorMain.java
~633,648 explaining exactly why those two were "deliberately named without a --migration prefix for
this reason") -- and it catches `run_migration_diff`'s five flags, which were never renamed and so
are caught on the very first one (`--migrationsDir`).

**The generator has NO support at all today** for `--migrationMode`, `--migrationPlanOnly`,
`--migrationRiskThreshold`, or `--migrationDecisionReport` -- grepped the whole `NPDevGenerator`
tree for each name; zero hits. This is not a renamed-flag bug fixable by search-and-replace; the
offline "classify this change as SAFE_ADDITIVE/BACKFILL_REQUIRED/MANUAL_REVIEW/METADATA_ONLY by
diffing two model.json snapshots" feature the CLI subcommand and MCP tool both advertise has never
been built. The REAL classification (`SafetyClass`, `SchemaDiffEngine`, `ClassificationReducer` in
`NPDevRuntimeHost/src/main/java/com/finalexec/db/`) is computed only at APP BOOT TIME, diffing the
compiled model against the LIVE database's stored schema history via `SchemaHistoryStore` -- there
is no offline, two-model-snapshots equivalent.

**Consequence for the programme.** Wave 1.2 (LC-C1) cannot honestly be scoped as "add METADATA_ONLY
to the existing classification" -- there is no existing classification to add to at this layer.
Wave 2's AC-4 ("MCP submit") and the contract's own diff-gate (AC-2) likely inherit the same
dependency and should be re-verified live, not assumed working, before being built on.

**Fix shape when taken up**: either (a) build the offline classifier for real -- a
`ModelChangeClassifier` that diffs two `CompiledModel`s' concept/field/index shapes directly (no
Gradle subprocess, no generator CLI involvement) and returns SAFE_ADDITIVE/BACKFILL_REQUIRED/
MANUAL_REVIEW/METADATA_ONLY -- or (b) rename `run_migration_diff`'s five flags to route through the
generator's real, already-supported `--previousCompiledModel`/`--schemaMigrationPlanOut` pair and
reuse `MigrationPlanEmitter`'s existing diff, which does NOT currently classify by risk level at
all (it only lists additive-vs-destructive migration items) -- so (b) still needs new classification
logic layered on top, just via a different entry point. Neither is a small fix; do not attempt as a
side effect of LC-C1.

---

## CLOSED (2026-07-31, MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.2, hybrid of fix shapes (a)+(b))

Re-reading `MigrationPlanEmitter` (LNCH-1 Phase 6) found it is ALREADY the offline, no-database,
pure model-vs-model diff engine fix shape (a) described building from scratch -- it just had no
working CLI/MCP entry point (the actual root cause) and no coarse risk-level classification on top
of its `PlanItem.Kind` vocabulary (ADD_TABLE/ADD_COLUMN/ADD_COLUMN_BACKFILL/RENAME_*/WIDEN_TYPE/
ADD_UNIQUE_CONSTRAINT/DROP_COLUMN/DROP_TABLE/NARROW_TYPE/UNKNOWN). So the real fix reuses it rather
than re-deriving a diff engine "no Gradle subprocess" would have required building twice:

1. **`ModelChangeClassifier`** (new, `NPDevGenerator/generator/.../schemaevolution/`): maps
   `PlanItem.Kind` to the coarse METADATA_ONLY/SAFE_ADDITIVE/BACKFILL_REQUIRED/MANUAL_REVIEW the
   CLI/MCP surface advertises. `METADATA_ONLY` is definitionally exact, not approximated:
   `MigrationPlanEmitter` diffs exactly the concept/field shape that feeds
   `UserDatabaseDefinitionLoader#fingerprintInputs`'s business-table lines, so an empty item list
   (on a non-fresh-install) means the fingerprint provably did not move. 16 unit tests (every
   `PlanItem.Kind` -> `Level` mapping, worst-item-wins aggregation, fresh-install, no-change) plus
   **2 corpus property tests** over all 10 in-repo `NPDevSamples/*/Input/model.json` models
   (LC-C1's own DoD line: "a change to a concept field is never classified METADATA_ONLY --
   property test over the corpus, not a single example") -- 20/20 tests green.
2. **`ModelChangeClassifierMain`** + a new `:generator:classifyModelChange` Gradle task
   (`-PcurrentPath=... -PbaselinePath=... -PreportOut=...`, mirroring `:validateModel`'s own
   pattern) -- deliberately its OWN entry point, never a new `GeneratorMain` flag, since THAT
   class's arg parser is the thing that rejected every one of the five broken flags. Every flag
   here is named without the `--migration` prefix, matching the precedent
   `--previousCompiledModel`/`--schemaMigrationPlanOut` already set.
3. **`npdev_cli.py`'s `run_migration_diff`** rewired to call the new task instead of the broken
   `:generator:run --migration*` invocation; the now-meaningless `--migrationRiskThreshold` input
   (a threshold gating a classifier that never existed) removed from both the CLI arg parser and
   the MCP tool schema/call -- the classifier now always reports its own level, the caller decides.

**Verified live, all four layers, not just unit tests**: the exact repro command from this item's
own `source:` block now runs clean --

    $ python NPDevCli/npdev_cli.py migration diff --baseline user-minimal/model.json \
          --current simple-contact-intake/model.json
    migration diff classification: MANUAL_REVIEW
      - [SAFE_ADDITIVE] New concept adds table 'contact_messages'.
      - [MANUAL_REVIEW] Concept removed: table 'users' will be dropped ...

and the MCP tool (`server.tool_migration_diff`, called directly, not mocked) returns
`classification: METADATA_ONLY` for two identical models and `MANUAL_REVIEW` for the same
destructive pair above.

`:generator:test` (31 tasks, full suite) green after the change.

### REG-103 — RuntimeMetadataService's compiled-metadata.json/npdev/metadata/* catalogs (concept/panel UI labels among them) are classpath-only with no external-path override, unlike NPDevModelProvider's compiled-model.json -- a metadata-only model change cannot be hot-swapped into a running app without also touching these, or a static frontend asset

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Found while building MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.3 (LC-C2)'s metadata-only fast
path (scripts/appgen/Update-AppMetadata.ps1). The fast path correctly hot-swaps the app's
CompiledModel bean (NPDevModelProvider checks an external file path before its classpath
fallback) and re-signs npdev-generated/ so StrictExecutionValidator accepts the change -- proven
live: the app rebooted cleanly with the new compiled-model.json in place.

But the plan's own chosen example for this feature -- "a panel-title change is live in the
running app" -- is NOT satisfied. Live-verified in a real browser: changed the Recebimento
concept's ui.label, ran the fast path successfully, confirmed the app rebooted with the new
compiled-model.json and no StrictExecutionValidator failure, then checked two PanelRuntime/
business-ui-rendered surfaces (the generic CRUD panel heading and the RecebimentoWorkbench.html
page's <title>) -- both still showed the OLD label. Screenshots + full routine result:
D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\move11\browser-lc-c2\.

**Surface:** `runtimehost, generator`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/RuntimeMetadataService.java`
- `NPDevGenerator/generator/src/main/resources/npdev-templates/npdev-runtime-model-provider.mustache`

`RuntimeMetadataService` loads `npdev/compiled-metadata.json` and `npdev/metadata/index.json`
(plus the per-catalog manifest files) via `ClassPathResource` ONLY -- no `@Value`-injected external
path, unlike `NPDevModelProvider` (the app's compiled-model loader), whose constructor explicitly
checks a configurable external file (`npdev.compiled-model.path`/`npdev.model.path`) BEFORE its own
classpath fallback. Grepped `RuntimeMetadataService` clean for any such override.

Concept/panel UI label text (and everything else `RuntimeMetadataService`'s catalogs carry) is
therefore baked into the packaged jar at generation time and cannot be refreshed by overwriting a
file on disk -- only a full rebuild (or `jar --update` surgery on the packaged fat jar) updates it.
The generated frontend's own static assets under `static/npdev-business-ui/`
(`generated-ui-manifest.json`, `app.js`, `shell.js`) are a SEPARATE, likely also-static, likely
also-affected surface -- not confirmed by this finding's own live check, which only exercised the
two PanelRuntime-rendered pages named above, but named here as the same class of gap since both
are pre-rendered at generation time rather than read from the CompiledModel bean per request.

**Fix shape when taken up**: mirror `NPDevModelProvider`'s own pattern -- add a
`@Value("${npdev.compiled-metadata.path:${npdev.metadata.path:<classpath-default>}}")`
constructor parameter to `RuntimeMetadataService`, checking that external path before the existing
`ClassPathResource` fallback, exactly the precedent already proven safe for the model itself. The
static frontend assets are a materially different problem (client-side JS/JSON, not a Spring bean)
and were not sized here.

**Not a regression, not urgent** -- the fast path this finding was found while building is real,
additive infrastructure (procedure/query/permission/validation-rule changes DO flow through the
hot-swapped CompiledModel bean correctly); this item only narrows which subset of "metadata-only"
changes the fast path currently makes visibly live. Rated LOW: the mechanism is safe (refuses
correctly, never silently applies a schema-shaped change, never leaves the app unable to boot) --
it is a completeness gap, not a correctness or safety one.

---

**Move 12 P2.1 closure (2026-08-01):** implemented the fix shape named above, for the two
top-level catalogs (`compiled-metadata.json`, `metadata/index.json`). Added a second, `@Autowired`
constructor taking
`@Value("${npdev.compiled-metadata.path:npdev-generated/src/main/resources/npdev/compiled-metadata.json}")`
and `@Value("${npdev.metadata-index.path:npdev-generated/src/main/resources/npdev/metadata/index.json}")`
-- the SAME `npdev-generated/src/main/resources/npdev/...` relative-path convention
`NPDevModelProvider`'s own default (`compiledModelPathDefault`) uses, confirmed against a real
generated app's directory layout. `loadJsonMap` gained a `(classpathLocation, externalPath)`
overload: `Files.exists(externalPath)` wins, else falls back to the original
`ClassPathResource(classpathLocation)` unchanged. The pre-existing single-arg
`RuntimeMetadataService(ObjectMapper)` constructor now delegates to the new one with the same
default path strings, so its behavior is unchanged for all 12 existing call sites (none of which
have `npdev-generated/` at their working directory, so `Files.exists` is false and every call
falls through to classpath exactly as before).

**Narrowed, not fully closed**, and said so rather than overclaimed: `loadManifest`'s per-catalog
manifest files (`npdev/metadata/*.manifest.json`, e.g. `panels.manifest.json` -- where an
individual field/panel LABEL actually lives) and `schema-realization-manifest.json` stay
classpath-only, out of THIS item's named scope (only `compiled-metadata.json` and
`metadata/index.json` were named). The static frontend assets
(`static/npdev-business-ui/generated-ui-manifest.json`, `app.js`, `shell.js`) remain unaddressed,
as this item's own text already said when filed. `sourceRoot`/`generatedFrom` in the response
payload still say "classpath:/npdev" unconditionally even when the external path was used -- a
cosmetic gap, not fixed here. So the panel-title-not-live browser finding this item documents is
NOT yet resolved end-to-end; this closes the prerequisite (the loading mechanism), not the whole
chain.

Verified live against a real generated app
(`D:\WorkSpace\NPDev\Build\generated-finalapps\pack-sample`, RuntimeHost libs synced via
`scripts/runtimehost/sync-runtimehost-libs.ps1`): `compileJava`/`compileTestJava` succeed; the new
`RuntimeMetadataServiceTest.externalCompiledMetadataAndIndexFilesOverrideTheClasspathCopy` (a real
temp-dir JSON file, pointed at via the new constructor directly) proves the external file wins for
BOTH catalogs; the file's other 5 pre-existing tests (via the unchanged 1-arg constructor) still
pass -- except `loadsRuntimeMetadataOverviewFromGeneratedClasspathArtifacts`, which fails in THIS
SPECIFIC app for an unrelated, pre-existing reason (that test's own fixture assumes the
"canonical.clinicdemo" model; pack-sample's real compiled-metadata.json says "StoreManagement"
instead -- this test is normally excluded from every generated app's `test` task for exactly this
reason, and is meant to run against `NPDevSamples/canonical-demo`, confirmed by the failure message
and the static, checked-in `src/test/resources/npdev/compiled-metadata.json` fixture).

---

**Move 13 P5.1 (2026-08-01, commit bccab1d — retroactive ledger note):** the code widened this
item's own "narrowed, not fully closed" gap further, but this file was not updated at the time,
which the Fast Lane plan's research pass caught while grounding item 1a below (`git log --
ledger/items/REG-103.yml` showed only the Move 12 P2.1 commit touching this file, despite
`RuntimeMetadataService.java`'s own class javadoc citing "Move 13 P5.1" for a third external-path
parameter). Recorded now for accuracy: `RuntimeMetadataService` gained a generic
`externalPathFor(classpathLocation)` derivation plus a `npdev.generated-resources.path`
`@Value` (default `npdev-generated/src/main/resources`), so `loadManifest`'s per-catalog manifest
files (`npdev/metadata/*.manifest.json`) and `schema-realization-manifest.json` now ALSO check an
external path before their classpath fallback -- not just the two top-level catalogs Move 12 P2.1
named. The 3-arg constructor was kept, delegating to a new 4-arg one with the root defaulted, so
behavior is unchanged with no property set. This also sized (did not fix) the static-frontend-asset
half as its own item, filed as REG-109.

**Fast Lane plan item 1a (2026-08-01):** the fix shape this item named ("mirror
`NPDevModelProvider`'s own pattern") was live in the runtime since Move 12 P2.1/13 P5.1 above, but
nothing on the WRITE side fed it -- `scripts/appgen/Update-AppMetadata.ps1` (LC-C2's fast-path
script) only ever wrote `compiled-model.json`, leaving the three REG-103 paths above unused. Closed
that gap: `ModelChangeClassifierMain` gained a matching `--emitMetadataTo <dir>` flag (same
METADATA_ONLY-gated refusal contract as the existing `--emitCompiledModelTo`) that writes
`compiled-metadata.json` (via the same `CompiledMetadataCanonicalJson.toJson` call
`RuntimeApiEmitter` uses) and every `metadata/*.manifest.json` catalog + `metadata/index.json` (via
the unchanged `MetadataManifestAssetEmitter`) directly from the already-in-memory `CompiledModel` --
no `GeneratorFacade.generate()`, no Java source/UI bundle emission, no Gradle build of the app.
`Update-AppMetadata.ps1` now calls this alongside `--emitCompiledModelTo`, copies both outputs onto
the app's own `npdev-generated/src/main/resources/npdev/` tree, and re-signs once (the existing
`resignGeneratedFolder` step already covers the whole tree, so no second re-sign was needed).

Verified live, same `pack-sample` app REG-103 itself used: changed `Store`'s `ui.label` to
`"Store (fast-lane-item1a-test)"`, ran `Update-AppMetadata.ps1 -SkipRestart`; classification came
back `METADATA_ONLY`; `compiled-model.json`, `compiled-metadata.json`, and
`metadata/concepts.manifest.json` all landed the new label on disk (confirmed by direct read of
each file's `Store` entry); `resignGeneratedFolder` succeeded (no `StrictExecutionViolationException`
would occur at next boot). New test `ModelChangeClassifierMainTest` (generator module) covers
`--emitMetadataTo`'s happy path against a real in-repo corpus model. Unit test suite green
(`:generator:test`).

Both REG-103's own residual gaps are now closed: the loading mechanism (Move 12/13) and the write
side that feeds it (this item). The only remaining piece from REG-103's original text is the
static frontend bundle, tracked as its own item, REG-109.

### REG-104 — RolePermissions.toRole() returned null for any app-defined role name and the caller loop `continue`d, so an app-declared role (e.g. WarehouseManager) silently granted nothing at the platform-permission layer -- no error, no log line (X0-5)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Found and fixed while building MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 3 (RC-B1,
MOVE11_RUNTIME_CONFIGURATION_PLAN Part B.1). Already named in
docs/X0_SILENT_EXPRESSION_REGISTER.md as X0-5, filed OPEN with no REG id at the time that
register was written (2026-07-31); this item is that fix, closing X0-5's OPEN status to
FIXED.

RolePermissions.hasPermission(context, permission) iterated context.roles(), called the
private toRole(rawRole) helper, and on any name outside the closed USER/OPERATOR/ADMIN enum
toRole returned null via a caught IllegalArgumentException -- the loop then `continue`d with
no diagnostic anywhere. An app that declared its own role vocabulary in the model (which the
DSL had no way to express at all before this fix) would have that role silently mean nothing
once implemented naively, exactly the same "silent-wrong-answer" defect class as the
platform's other X0 findings.

**Surface:** `dsl, kernel, runtimehost`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/RoleAst.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/ModelAst.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/resolution/ModelResolver.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/RoleValidation.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledRole.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModel.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/ModelCompiler.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModelCanonicalJson.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModelCanonicalJsonReader.java`
- `NPDevContract/schemas/model.schema.json`
- `NPDevContract/schemas/authoring/model.schema.json`
- `NPDevContract/dsl/src/main/resources/schema/model.schema.json`
- `NPDevContract/dsl/resources/Schemas/model.schema.json`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/auth/RolePermissions.java`
- `NPDevKernel/adapters/authz-default/src/main/java/com/npdev/adapters/authz/defaultpolicy/DefaultExecutionAuthorizationPolicy.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevAuthConfig.java`
- `NPDevSamples/dsl-conformance-max/Input/model.json`
- `scripts/quality/check-dsl-coverage.py`

Fix shape: the model gained an optional top-level `roles[]` (name + grants, structurally
validated -- non-blank name, unique names, non-empty/unique grants -- but grant names are NOT
checked against the real Permission enum at the DSL layer, since dsl has no dependency on
kernel). RolePermissions gained a model-aware 3-arg hasPermission(context, permission,
appDeclaredRoles) overload: a role matching neither a built-in Role NOR an app-declared role
now logs a named WARNING (actor, role, "neither built-in nor declared") before denying,
instead of silently continuing. DefaultExecutionAuthorizationPolicy gained a constructor
taking a CompiledModel, resolving every declared grant name against the real Permission enum
at CONSTRUCTION (app boot) time -- an unrecognized grant name (a typo, a renamed permission)
throws IllegalStateException at boot, not silently the first time an affected user makes a
request.

Live-verified: WmsOffice's model declared "WarehouseManager": ["EXECUTE_FLOW",
"READ_EXECUTIONS", "READ_FLOW_DEFINITIONS"]; the app booted cleanly with real kernel jars
built from source, and GET /api/admin/roles (the new RC-B2 vocabulary endpoint) round-tripped
the declaration correctly through the full parse -> resolve -> validate -> compile ->
canonical-JSON -> NPDevModelProvider boot-load -> REST chain. Full detail:
D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\move11\move11-b1-app-roles.txt

Regression coverage: RolePermissionsTest and DefaultExecutionAuthorizationPolicyTest both
gained a test proving an app declaring NO roles (empty map / null CompiledModel -- what every
pre-existing app is) behaves identically to the pre-fix code path for the built-in
USER/OPERATOR/ADMIN trio.

### REG-105 — Move 10 B1's groupBy/aggregates query primitive is single-concept only -- no cross-concept join, so a dashboard rollup that needs one (e.g. WmsOffice's retired analytics.html 'Estoque por Produto' widget: sum LocalArmazenagemLote.quantidade grouped via a join through Lote to Produto) cannot be expressed

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Named while replacing WmsOffice's analytics.html with a real Move 10 B1/B2 aggregate-query +
gadget dashboard (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 5, MOVE10_AI_LOWCODE_PLAN Part B.2).
analytics.html's provenance doc names 3 client-side rollups; 2 of the 3 ("Ocupacao por Rua",
"Movimentos por Tipo e Situacao") are single-concept groupBy/count and were faithfully
replaced with real query.groupBy + guidePageGadget declarations. The third ("Estoque por
Produto") sums LocalArmazenagemLote.quantidade grouped by Lote.produtoId -- two hops across
three concepts (LocalArmazenagemLote -> Lote -> Produto) -- which `query.groupBy`/
`query.aggregates` (Move 10 B1) cannot express: a query names exactly one `concept`, and
`groupBy`/`aggregates` operate only on that concept's own fields.

This is an ACCEPTED BOUNDARY, not an oversight: B1's own plan explicitly scoped joins out
("translate access.read to SQL is a second expression compiler, not in this move" is the same
shape of decision -- a join translator is a comparable-sized undertaking). The old widget's
logic (fetch 3 endpoints, join client-side, sum) was deleted along with the rest of
analytics.html's hand-rolled JS; it is not silently kept as dead code, nor faked as "migrated."

**Surface:** `dsl, kernel`

Trigger to lift: B1's aggregate query primitive gains either (a) a `join`/`via` clause letting
`groupBy`/`aggregates` reference a field on a referenced concept (e.g. `groupBy:
["lote.produtoId"]` where `lote` is a declared reference field on LocalArmazenagemLote), or
(b) a denormalized field (e.g. a derived/computed `produtoId` copied onto
LocalArmazenagemLote at write time) that lets the existing single-concept query express the
same rollup without a real join. Neither exists today; do not attempt a partial join
(aggregating in the JVM after a manual multi-fetch) as a workaround inside the declarative
query primitive -- that reintroduces exactly the "hand-written arithmetic" this whole feature
exists to retire.

Full detail (byte metric, live verification, named parity gaps): move10-b2-charts.txt.

---

**Move 12 P2.2 closure (2026-08-01):** decided autonomously per the spec's own rule -- investigated
whether (b) (a denormalized `produtoId` field on `LocalArmazenagemLote`) could be done in
WmsOffice's model alone. It cannot without real engine-adjacent work: `LocalArmazenagemLote` has
THREE write paths (`RegistrarLocalArmazenagemLoteProcedure`, `SyncOcupacaoProcedure`,
`Movimento.onCommit`'s `somarQuantidadePorLocalLote` capability recompute), and
`ValueExpressionEvaluator` (the engine behind `derivedExpression`/`defaultExpression`) only
resolves `$field` against the current record's own in-memory data map -- it performs no I/O, so it
structurally cannot read `Lote.produtoId` through a `loteId` reference. Populating the denormalized
field correctly would mean touching Java capability logic across all three write paths in sync (a
missing path leaves it silently stale, worse than not having the rollup); that is real,
multi-path, synchronized code, not the single declarative field-authoring step (b) was framed as.
Per the spec's rule ("if (b) needs engine work, do neither"), converted REG-105 into
`docs/ACCEPTED_BOUNDARIES.md` B27, with the trigger stated (both the (a) join-clause and the
now-more-precisely-scoped (b) I/O-capable-evaluator options named as what would actually lift it).
No partial join shipped. REG-105 -> boundary (B27).

---

**S4 addendum (2026-08-03): B27's own named trigger (a) fired.** `groupBy` now supports a
one-hop join through a declared `reference` field, same-context or context-qualified
(`GroupByJoinGrammar`), exactly the shape option (a) above named
(`groupBy: ["lote.produtoId"]`). `docs/ACCEPTED_BOUNDARIES.md` B27 updated in place (struck
through, marked LIFTED, not deleted) rather than closed silently -- see that row for the full
scope: one hop only, `groupBy` only (`where`/`aggregates` unchanged), `access.read` widened to
the whole join path. The original two-hop `LocalArmazenagemLote -> Lote -> Produto` case this
ledger item named is still out of reach (still deferred; the capability-backed workaround this
item's own detail names still applies to it).

### REG-106 — SchemaLifecycleExecutor.migrate() skipped flyway.repair() whenever the schema fingerprint was unchanged, but V1's generated migration SQL text can drift (comments/emission order) independently of the structural fingerprint -- a plain model.json edit with zero concept/table changes crashed the boot with a Flyway 'Migration checksum mismatch' on the next regeneration

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Found and fixed while rebuilding WmsOffice for Move 10 B2 (LC-B2, MOVE10_AI_LOWCODE_PLAN Part
B.2) live verification -- the model change that triggered it (3 new queries + 1 new
guidePage, no concept/table changes at all) could not possibly have altered the schema
structurally, yet the app failed to boot with:

  org.flywaydb.core.api.exception.FlywayValidateException: Validate failed: Migrations have
  failed validation
  Migration checksum mismatch for migration version 1
  -> Applied to database : -2143873802
  -> Resolved locally    : -642233137

SchemaLifecycleExecutor.migrate() already had a repair() call for exactly this class of
problem (V1's SQL is regenerated from the full model on every generation pass, so its
checksum "legitimately changes... even though it must not be re-executed"), but it was
gated behind `recreation.safeAdditive()` -- true only when the schema FINGERPRINT changed in
a known-additive way. When the fingerprint is UNCHANGED (the log even printed "stored schema
fingerprint matches generated schema fingerprint; no destructive recreation required"), the
code assumed the migration file's literal bytes were therefore also unchanged and skipped
repair() entirely, going straight to a bare flyway.migrate() -- which then validated the
freshly generated (but byte-different, e.g. comment/table-ordering drift accumulated across
many generator runs over time) V1 file against the OLD checksum recorded in
flyway_schema_history from whenever this database was last migrated, and failed.

**Surface:** `runtimehost`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`

Fix: widened the repair() call from the `recreation.safeAdditive()`-only branch to the whole
`else` (non-destructive-recreation) branch, i.e. whenever `recreation.performed()` is false.
V1 is entirely generated, never hand-authored, so trusting the freshly generated file (via
repair(), which only reconciles Flyway's bookkeeping checksums -- it does not re-execute or
alter any table data) is correct whether the fingerprint changed additively or did not change
at all; only the destructive-recreation branch (which already clears schema-realization
history outright) needs no repair() call.

Live-verified: WmsOffice failed to boot with the checksum-mismatch error on the first rebuild
attempt after this session's Move 10 B2 model.json change; after the fix, the SAME database
(not wiped, not recreated) booted clean on the next rebuild with the identical model. Full
detail: move10-b2-charts.txt.

### REG-107 — PanelRuntime.executeAction's conceptquery binding fetches an entire concept unbounded via ConceptGateway.list -- the same memory/scale defect LC-P0 fixed for the declared-Panel dataSource path, out of that fix's stated scope

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** Move 12 P1.5 (item 6 of MOVE12_CLOSE_ALL_14_SPEC.md). LC-P0
(MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 0) migrated the declared-Panel DATA-SOURCE path
(`PanelRuntime.loadDataSource`) from `ConceptGateway.list(...)` (fetch every row, filter/sort/page
in the JVM) to `ConceptGateway.query(...)` with the compiled predicate/orderBy/limit pushed down
to the store (PanelRuntime.java:439-445). `panelAction: "conceptQuery"` -- a distinct binding on a
PANEL ACTION button, not a data source -- was never touched: it still calls
`requireConceptGateway().list(new ConceptListRequest(conceptName, null), effectiveContext)`
(PanelRuntime.java:354, in the `"conceptquery"` action-binding branch), no filter, no limit, no
paging. Same memory/scale class LC-P0 fixed, in adjacent code, out of LC-P0's stated scope (data
sources only).

**Surface:** `runtimehost`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/PanelRuntime.java`

Fix: the `"conceptquery"` action-binding branch now builds a `ConceptQuery` (no filters -- the
action binding declares none today, same as a data source with no bound query -- empty sorts, and
`PANEL_ROW_CAP` as the limit) and calls `ConceptGateway.query(...)`, mirroring
`loadDataSource`'s :439-445 pushdown exactly rather than reintroducing a second, divergent
bounded-fetch shape.

`ConceptGatewayOperation` has no distinct QUERY value -- `DefaultConceptGateway.query()` traces as
`LIST` too (confirmed: `PanelRuntimeTest`'s own existing `loadPanel` assertion already expects
`ConceptGatewayOperation.LIST` for a data-source query call) -- so "proven by SQL log or EXPLAIN"
is not available at this layer (no real JDBC adapter is wired into a kernel-level RuntimeHost
unit test); the achievable proof is row-count boundedness at real scale, genuinely RED/GREEN
tested rather than asserted from code reading alone.

Verified: `PanelRuntimeTest.conceptQueryActionBindingReturnsAtMostThePanelRowCapNotEveryRow`
seeds 1005 rows via a real in-memory `DefaultConceptGateway` and asserts the action returns
exactly 1000 (`PANEL_ROW_CAP`/`ConceptQuery.MAX_LIMIT`), not 1005. RED proven live: reverting the
fix to the original `ConceptGateway.list(...)` call in a generated app
(`D:\WorkSpace\NPDev\Build\generated-finalapps\pack-sample`) and rerunning made this exact test
fail with all 1005 rows returned; restoring the fix made it pass again. Full generated-app build
+ all 9 `PanelRuntimeTest` cases green throughout (`:test` via the app's own Gradle wrapper, libs
synced via `scripts/runtimehost/sync-runtimehost-libs.ps1`).

### REG-108 — roles/propertyScopes/properties were absent from ModelSourceResolver's MODEL_ARRAY_KEYS (and from pack.schema.json's allowlist) -- a pack or local fragment declaring any of the three had its declaration silently dropped during composition, with no error

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-01)
**Verification:** UNIT_TESTED
**Source:** Found while executing Move 13 (MOVE13_CLOSE_EVERYTHING_SPEC.md) Phase P3's own R4 rule ("pack
composition drops fields silently"), which cited REG-104/ModelResolver as precedent. Reading
REG-104.yml showed that ledger item is actually about RolePermissions.toRole() returning null for
an undeclared role name (X0-5) -- a kernel-layer authorization bug, not a parser/pack-composition
one. No ledger item existed yet for a pack-composition bug against propertyScopes/properties, so
this item files the real one, with the real root cause, found by reading
NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java directly rather
than trusting the spec's citation.

ModelResolver.resolve() (the AST-level specialization resolver) already threads propertyScopes/
properties through correctly (it just passes source.getPropertyScopes()/getProperties() straight
into the resolved ModelAst) -- that class was never the problem. The real gap is one layer
earlier, in ModelSourceResolver (the JSON-level pack/fragment composer): its private
MODEL_ARRAY_KEYS set is the fixed list of top-level array keys mergePackNonConceptArrays and
appendFragment loop to concatenate pack/fragment content into the resolved model. roles (RC-B1),
propertyScopes and properties (RC-A1) are all schema-declared top-level arrays exactly like
domainTypes/capabilities/events/etc, which are all already in that set -- but none of the three
were ever added to it when they shipped. A pack or local $ref fragment declaring any of them had
the declaration silently discarded (mergePackNonConceptArrays only iterates MODEL_ARRAY_KEYS, no
fallback), with no error at any layer -- an X0-shaped defect (this register's very question:
"what does it do with input it cannot handle?" -- here, "silently drop it").

Root-level declarations (an app's own model.json declaring roles/propertyScopes/properties
directly, not through a pack) were NOT affected -- resolveRoot's own generic
"pass through any unrecognized top-level key verbatim" fallback (for keys outside
MODEL_ARRAY_KEYS/ROOT_SCALAR_KEYS) already covered the root case by accident, which is exactly why
this had not yet been noticed: every app that uses these three fields today (WmsOffice's roles,
Move 12 P4's propertyScopes/properties) declares them at the app's own root, never inside a pack.
The gap was live but dormant -- it would have fired the moment Move 13 P3.1 (RC-A2, "changes the
built-in workspace pack") or P3.4 (RC-A6, folding `settings` into property declarations at the
pack level) tried to have the workspace PACK declare its own properties.

**Surface:** `dsl`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/ModelSourceResolver.java`
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/ModelSourceResolverTest.java`
- `NPDevContract/schemas/pack.schema.json`
- `NPDevContract/dsl/src/main/resources/schema/pack.schema.json`

Fix: added "roles", "propertyScopes", "properties" to MODEL_ARRAY_KEYS (a one-line change --
every consumer of that set, resolveRoot/appendFragment/mergePackNonConceptArrays/
isRecognizedRootKey, is generic over its contents, so no other code change was needed) and to
both copies of pack.schema.json's property allowlist (additionalProperties:false was rejecting a
pack file that tried to declare any of the three before the composer even got a chance to drop
them -- a second, independent place the same class of gap could bite).

Verified: a new test, packContributedRolesPropertyScopesAndPropertiesAreMergedNotDropped
(ModelSourceResolverTest), declares all three from a pack alongside root-level declarations of the
same three keys and asserts the resolved model carries both (root entries first, pack entries
appended -- the same order convention every other MODEL_ARRAY_KEYS member already follows).
Confirmed root-level-only usage (the shape every existing app, including WmsOffice, uses today)
is behaviourally unchanged: a plain inline array item with no $ref is still deepCopy()'d verbatim
by resolveArray, the same as the generic passthrough it replaces for these three keys. Full DSL
test suite green after the fix (`gradlew :NPDevContract:dsl:test`, 0 failures).

Not done here, left to Move 13 P3.1/P3.4 itself: no dsl-conformance-max corpus model yet exercises
a PACK contributing roles/propertyScopes/properties (only root-level declarations are corpus-
covered today) -- P3.1/P3.4 is exactly the work that will need this shape for real, and should add
the corpus fixture alongside it (R6).

### REG-109 — generated-ui-manifest.json (and the rest of static/npdev-business-ui/*) was baked into the packaged jar at generation time with no external-path override, the same class of gap REG-103 fixed for RuntimeMetadataService's JSON catalogs -- named but explicitly not sized by REG-103, given the same external-path-before-classpath fix here

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Filed while executing Move 13 P5.1 (MOVE13_CLOSE_EVERYTHING_SPEC.md), which named this residual
explicitly and said: "Static frontend assets: client-side JS/JSON, not a Spring bean -- a
materially different problem. Do not size it here. If it is not bounded after 30 minutes of
reading, file it as its own ledger item and stop." Per that instruction, this item files it
rather than attempting a fix.

What this pass DID confirm, that REG-103 itself had not (its own text: "not confirmed by this
finding's own live check"): `generated-ui-manifest.json` is not inlined into the JS bundle at
generation time -- it is fetched at RUNTIME over HTTP from a static path
(`business-ui-app.mustache`: `state.manifest = await fetchJson("./generated-ui-manifest.json")`,
confirmed by direct read of the template). This narrows the problem usefully: it is a static-file-
serving question (does Spring serve `static/npdev-business-ui/*` from an external, override-able
location, or only from the packaged jar's classpath?), not a "JS bundle has data baked into its
source text" question -- structurally closer to RuntimeMetadataService's own classpath-only
catalogs than the word "bundle" suggests.

A quick, bounded check (the 30-minute box this item's own filing instruction sets) found no
existing `spring.web.resources.static-locations` (or equivalent `WebMvcConfigurer.
addResourceLocations`) override anywhere in NPDevRuntimeHost's Java sources or any checked-in
`application.yml`/`.properties` -- so, unlike `compiled-model.json`/`compiled-metadata.json`,
there is currently no evidence this file can be served from anywhere but the packaged jar's
classpath. Not exhaustively confirmed (would need tracing Spring Boot's default static-resource
handler chain and how/whether `Build-NpdevApp.ps1` packages `static/npdev-business-ui/*`), which is
exactly why this is filed rather than fixed.

**Surface:** `generator, runtimehost`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-app.mustache`
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/RuntimeMetadataService.java`

Sizing, not fixing: if the fix shape mirrors REG-103's own precedent (an external directory Spring
checks before its classpath-packaged static resources, e.g. via
`WebMvcConfigurer.addResourceLocations("file:${npdev.static-ui.path}/", "classpath:/static/")`),
this is a bounded, same-shape fix -- but confirming that shape actually applies to Spring Boot's
static-resource pipeline (as opposed to `app.js`/`shell.js` needing something structurally
different, e.g. because they are also referenced by a content hash somewhere) needs its own pass,
not assumed here. Left OPEN, not attempted, per this item's own filing instruction.

---

**Fast Lane plan item 1b closure (2026-08-02):** confirmed the shape and applies it. Added
`NPDevRuntimeHost/src/main/java/com/finalexec/config/StaticUiResourceConfig.java`, a
`WebMvcConfigurer` registering `/**` with `file:${npdev.static-ui.path}/` ahead of Spring Boot's
own four default classpath locations (kept identical to what `WebMvcAutoConfiguration` would
otherwise register, so replacing `/**` here drops no coverage). `npdev.static-ui.path` defaults to
`npdev-generated/src/main/resources/static` -- the SAME relative layout the generator already
writes, mirroring `RuntimeMetadataService`'s own `npdev.generated-resources.path` default -- so an
unconfigured app's behavior is unchanged until something writes fresh content there.

Confirmed (not assumed, closing this item's own "not exhaustively confirmed" gap): `app.js`/
`shell.js`/`generated-ui-manifest.json` are referenced by plain relative URL in
`business-ui-index.mustache`/`business-ui-app.mustache` -- no content hash, no cache-busting query
string -- so no HTML/JS reference needed to change, only the resource-location resolution order.

**Verified live**, same `pack-sample` app REG-103 used: overwrote
`npdev-generated/src/main/resources/static/npdev-business-ui/generated-ui-manifest.json`'s
`appName` field on disk while the app was already running (no restart) and curled
`/npdev-business-ui/generated-ui-manifest.json` -- the new content was served immediately (`file:`
resource locations are resolved per-request, not baked in at boot the way classpath resources
are, so this is actually a step FASTER than the compiled-model/compiled-metadata fast paths, which
still need a restart). Restored the original file afterward.

Static UI bundle override is done. `Update-AppMetadata.ps1`'s fast path does not yet WRITE to this
external directory automatically (it only refreshes the three REG-103 metadata paths, item 1a) --
not attempted here, since the static bundle only changes on a UI-shaped model edit (new field/
panel/action), which is schema-shaped generation work, not the metadata-only case that fast path
targets.

### REG-11 — LNCH-20: cross-platform build scripts (gradlew.bat literals, portable cache dir)

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, corrected 2026-07-21
**Surface:** `scripts/cross-platform`

13 scripts (18 occurrences) hardcoded gradlew.bat instead of resolving per-OS; a working helper
(Get-NPDevGradleWrapperExecutable in scripts/npdev-common.ps1) already existed but these call sites
hadn't been migrated to it -- mechanical work, not new plumbing. A separate repo-wide D:\ literal
sweep of the scoped files found zero matches (that part of the original claim didn't hold).
Migrated all genuine gradlew.bat call sites to the shared helper. Closed/proven via REG-10's green
Linux CI run, which additionally exposed and fixed a real cross-platform DISTRIBUTION bug this
item's "code-complete" state had missed: every generated FinalApp shipped
NPDevRuntimeHost/gradle.properties's hardcoded org.gradle.projectcachedir=D:/WorkSpace/NPDev/Build/...,
copied verbatim by FinalAppAssembler, so a generated app's own gradlew bootJar could not run on any
machine but the original dev box. Removed from the template so generated apps use gradle's portable
default cache.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-11`

### REG-110 — LC-D2 (the acceptance-scenario runner) and LC-D3 (the closed authoring loop) were already fully implemented in NPDevCli/npdev_cli.py -- apparently from an earlier Move 10 session -- but had never been run, tested, or documented anywhere; a closure spec (Move 13) re-described them as needing to be built

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-01)
**Verification:** VERIFIED_LIVE
**Source:** MOVE13_CLOSE_EVERYTHING_SPEC.md's Phase P2 described LC-D2 as "the format and fixtures already
exist; nothing runs them" and LC-D3 as "every piece exists. This is integration" -- both phrased as
if the runner/loop logic itself needed to be authored. Reading NPDevCli/npdev_cli.py before writing
any new code found `run_acceptance` (~line 1337) and `run_closed_loop` (~line 1391), both complete,
both wired into real `npdev acceptance run` / `npdev loop run` CLI subcommands with full argument
parsing already in place. Grepping the whole repo for "run_acceptance", "run_closed_loop", "LC-D2",
"LC-D3" outside npdev_cli.py itself found zero hits -- no test, no doc, no ledger item. This is the
"claimed/assumed done, never verified live" pattern this project's culture repeatedly names (see
e.g. REG-93's three move-reports claiming "gates green" while a checker sat red) -- here the failure
mode is milder (nobody claimed it worked; a later spec just assumed it needed building because
nothing recorded that it already existed and worked).

**Surface:** `cli`
**Files:**
- `NPDevCli/npdev_cli.py`
- `NPDevSamples/user-minimal/Input/config.json`

Verified live rather than assumed, closing Move 13 P2.1/P2.2 as verification (not construction):

LC-D2: `npdev acceptance run` against NPDevSamples/user-minimal (a real generate+build+boot, port
8181) ran all 4 existing *.scenario.json fixtures over real HTTP. Real results: 01 passed (status
200, totalElements=2, allEqual name), 02 failed for its designed reason (expected 999, actual 1 --
the report names the exact assertion and actual value), 03 proved a WHERE-clause pushdown filter
really filters (2 rows returned out of 3 seeded, allEqual on the filtered set), 04 (unapproved) ran
but was excluded from the pass count (`summary.excludedUnapproved: 1`). NPDevSamples/user-minimal
had a model.json and acceptance/ fixtures but NO Input/config.json, so it could not previously be
generated/run at all -- added one (modeled on simple-user-registry's), the one real gap this item's
investigation found and fixed.

LC-D3: `npdev loop run` tested three ways, proving the diffGate -> validate -> classify ->
run+acceptance ordering is real, not decorative:
  (A) no --manifest -> stops at diffGate (AUTHORING_MANIFEST_MISSING), nothing downstream runs
  (B) a manifest but a submitted model with a flow step referencing a nonexistent invariant name
      -> diffGate passes (correctly -- the diff itself was properly authorized), stops at validate
      with the exact semantic error named, nothing downstream (classify/run/acceptance) runs
  (C) a real additive change (User gains an optional phone field, v1->v2, correct manifest) -> the
      FULL pipeline actually executes: diffGate passed, validate passed (0 errors), classification
      correctly computed SAFE_ADDITIVE (Move 10 C1's real classifier), a second real generate+
      build+boot succeeded (port 8183), all 4 acceptance fixtures ran again with the same real
      result as LC-D2's own run -- overall ok:false/stoppedAt:acceptance, which is the CORRECT
      honest answer (fixture 02 is deliberately wrong by its own design), not a defect in the loop.

Full evidence, commands, and complete JSON output for all four runs:
D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\move13\p2-lc-d2-d3-verification.md

Not done here, named as residual: no automated regression test exists for run_acceptance/
run_closed_loop/the JSONPath-lite evaluator -- this closure is a live-verified proof, not a
checked-in test suite. A future session should add one (e.g. against a lightweight HTTP stub for
the JSONPath/assertion logic specifically) so this does not silently regress the way it silently
went unverified for however long it has existed.

### REG-111 — Long-running generate/build/boot cycles (Build-NpdevApp.ps1, Rebuild-And-Restage.ps1, npdev run app, plain gradlew clean build) emit no incremental progress signal -- whatever is waiting on one (a human operator, an AI agent, a CI step) cannot tell 'still working normally' apart from 'silently stuck' without reaching past the tool into raw filesystem/process state

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Surfaced directly during Move 13 (MOVE13_CLOSE_EVERYTHING_SPEC.md) Phase P1's execution, not by
design review. A delegated agent running a real WmsOffice regenerate+build+boot cycle had its
wrapper shell call killed by the harness with an empty output buffer AFTER the underlying Gradle
build had already finished successfully -- so there was no clean signal, to the agent or to the
session waiting on it, that the work was done. This happened twice in the same session. The only
way either side could tell real state was by reaching past the tool boundary: comparing a jar
file's mtime against a baseline, tailing `_ops/app.out.log`/`_logs/generator-direct-java.log`
directly, and curling `/actuator/health`. Nothing in the generate/build/boot pipeline itself emits
a structured, polls-cleanly "still alive, currently in phase X" signal -- the closest thing,
`npdev run app`'s own `result.phase` field (GENERATE/BUILD/BOOT/READY), only exists in the FINAL
JSON result once the whole call returns; there is no live/incremental readout of it while the call
is still in flight.

Generalizes beyond this one session: the same blindness would recur for any human operator running
`Build-NpdevApp.ps1`/`Rebuild-And-Restage.ps1` directly in a terminal and wondering, after several
quiet minutes, whether it is building or hung -- not just for an AI agent delegating to another
agent. Filed as its own platform-shaped item rather than left as a one-off session annoyance,
per the user's own explicit framing: "this is a gap... have to deliver a better experience on this."

**Surface:** `appgen, cli, docs`
**Files:**
- `scripts/appgen/Build-NpdevApp.ps1`
- `scripts/appgen/Rebuild-And-Restage.ps1`
- `NPDevCli/npdev_cli.py`

Not sized or fixed here -- this is a filed observation, not a design. A few shapes worth weighing
when this is picked up (not a commitment to any of them):

- A structured, append-only progress log every phase writes a line to as it ENTERS the phase (not
  just a final result blob) -- something a caller can `tail -f` or poll cheaply, independent of
  whether the wrapping process/tool call itself survives to report a clean exit.
- `npdev run app`'s own internal phases (GENERATE/BUILD/BOOT/READY) are already named in
  `result["phase"]` -- the gap is narrower than "no phase model exists," it is "the phase model is
  only visible in the final return value, never while still in flight." Making it write that same
  field to a small sidecar file on each transition would close most of the gap cheaply, reusing a
  shape that already exists rather than inventing one.
- Document, for anyone (human or agent) invoking these scripts, an explicit "how to check real
  progress independently" recipe (jar mtime, `_ops/*.log` tails, health endpoint) -- even without
  a code change, writing this down once turns "reach for whatever seems plausible under time
  pressure" into a known, reliable recipe.

Left deliberately unsized on scope/cost -- the point of filing this now is that it stays visible
and does not silently disappear the way an unfiled frustration would, not that it is ready to be
picked up as a bounded task yet.

---

**Fast Lane plan item 4b closure (2026-08-02, PARTIAL):** implemented this item's own first named
shape -- `npdev run app`'s `result["phase"]` now also gets written to
`<finalAppOut>/npdev-run-app-progress.json` (schemaVersion `npdev-run-app-progress.v1`: phase,
updatedAt, pid) on every transition (GENERATE at start, BUILD, BOOT, READY, plus the two
METADATA_ONLY-fast-path-specific transitions), best-effort (a write failure never fails the run
itself). Closes the gap for `npdev run app` specifically: a caller can now poll or `tail` that file
instead of reaching past the tool into jar mtimes/log tails/health endpoints.

**Not closed, still open (at the time)**: `Build-NpdevApp.ps1` and `Rebuild-And-Restage.ps1` (this
item's other two named files) still emit no equivalent sidecar -- they are separate PowerShell entry
points, not touched by this Python-side fix, and were out of this pass's scope. The third suggested
shape (a written-down "how to check progress independently" recipe) was also not done. Rated PARTIAL,
not DONE: the specific, cheapest-cited shape landed and is real, but two of the three named
surfaces in this item's own title are still exactly as blind as when it was filed.

---

**Move 14 item A2 closure (2026-08-02, DONE):** extended the SAME sidecar shape (no second phase
model invented) to the two remaining named entry points, closing the gap for real:

- `Build-NpdevApp.ps1` writes `GENERATE` to `<FinalAppRoot>/npdev-run-app-progress.json` right
  before invoking the generator (the same file path `npdev run app` already uses for that app's
  output root, so one file is tailable regardless of which entry point drove the run).
- The `Build-App.ps1`/`Start-App.ps1` scripts `Build-NpdevApp.ps1` emits into every app's `_ops/`
  now themselves write `BUILD` (before `gradlew clean build`), `BOOT` (once the process is started,
  before the health-check wait), and `READY` (once `/actuator/health` confirms UP) to that same
  sidecar file -- so a plain `.\Build-App.ps1; .\Start-App.ps1` sequence, run standalone with no
  `Rebuild-And-Restage.ps1` involved, still produces a continuous GENERATE->BUILD->BOOT->READY trail.
- `Rebuild-And-Restage.ps1` writes its own `GENERATE` marker at the very start (before step 1,
  resolving the app's output root from its own `config.json`), so the sidecar exists and reads
  GENERATE through steps 1-2 (runtimehost-libs restage + generator-runtime refresh) -- the
  ~345s-of-573s slice that dominates a cold run and previously had no signal of any kind. Steps
  3-4 come for free: step 3 calls `Build-NpdevApp.ps1` (rewrites GENERATE, harmless no-op), step 4
  calls the same emitted `Build-App.ps1`/`Start-App.ps1` above.

**Live-verified** (not just written): one full `Rebuild-And-Restage.ps1 -AppFolder .../pack-sample`
run (no `-Skip*`/`-TryFastPath`), watched via the sidecar file itself rather than waiting blindly.
Phases advanced GENERATE (06:11:46.77Z) -> BUILD (06:12:34.10Z, +47.3s) -> BOOT (06:12:54.29Z,
+20.2s) -> READY (06:13:06.78Z, +12.5s), all in `npdev-run-app-progress.json`, across all three
scripts in sequence. Raw evidence + a first attempt's transient VS Code file-lock failure (and its
recovery) recorded in
`__OutsideRepo/move-fastlane/rebuild-calibration-2026-08-01.txt`. The written-down "how to check
progress independently" recipe (the third suggested shape, still not done as of the PARTIAL note
above) now exists too, in the emitted `_ops/README.md`.

Status raised PARTIAL -> DONE: all three named entry points (`npdev run app`, `Build-NpdevApp.ps1`,
`Rebuild-And-Restage.ps1`) now write the same sidecar shape.

### REG-112 — PanelRuntimeTest.java (single-arg RuntimeMetadataService constructor, hardcoded 'Appointment'/'AppointmentPanel' fixture data that exists in no corpus model) was missing from build.gradle's modelSpecificGeneratedAppTests exclusion list, unlike its sibling RuntimeMetadataServiceTest.java -- fails with NoSuchElementException whenever :test runs inside a generated app whose own model has no matching concept/panel

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found by the Fast Lane plan's T2 gate sweep (run-all-gates.ps1 -> run-runtimehost-gate.ps1),
which generates a real sample app (this run picked simple-contact-intake) and runs its full test
suite. 459 tests ran, 1 failed: `PanelRuntimeTest.rendersPermissionAwarePanelViewModelFromRuntimeMetadata`
threw `java.util.NoSuchElementException` at line 52.

Not caused by this session's Fast Lane plan changes -- none of them touch
RuntimeMetadataService.java, PanelRuntime.java, or PanelRuntimeTest.java. Root-caused instead: the
test constructs `RuntimeMetadataService` via its single-arg constructor
(`new RuntimeMetadataService(new ObjectMapper())`), which per REG-103 (Move 12 P2.1, landed earlier
the same day) now checks an EXTERNAL path (`npdev-generated/src/main/resources/npdev/
compiled-metadata.json`, relative to the process's working directory) BEFORE falling back to its
classpath-baked default. When `:test` runs inside a fully generated+mounted app (e.g.
`NPDevSamples/simple-contact-intake/Output/App`), that external path genuinely exists and IS the
real sample's own compiled-metadata.json -- which has zero panels declared (confirmed: grepped the
whole corpus, "AppointmentPanel" appears in NO checked-in model.json anywhere). So the external
path wins, the test's intended classpath-packaged fixture (whatever originally supplied the
"Appointment"/"AppointmentPanel" data) never loads, and `PanelRuntime.renderConceptPanel("Appointment", ...)`
throws.

REG-103's OWN closure text already documents this exact class of bug for a sibling test:
`RuntimeMetadataServiceTest`'s `loadsRuntimeMetadataOverviewFromGeneratedClasspathArtifacts` "fails
in THIS SPECIFIC app for an unrelated, pre-existing reason ... this test is normally excluded from
every generated app's `test` task for exactly this reason" -- and indeed
`com/finalexec/RuntimeMetadataServiceTest.java` is already in `build.gradle`'s
`modelSpecificGeneratedAppTests` exclusion list. `PanelRuntimeTest.java` uses the identical
vulnerable pattern (same single-arg constructor, same fixed-fixture assumption) but was never added
to that list -- an omission, not a new defect class.

**Surface:** `runtimehost`
**Files:**
- `NPDevRuntimeHost/build.gradle`
- `NPDevRuntimeHost/src/test/java/com/finalexec/PanelRuntimeTest.java`

Fix: added `'com/finalexec/PanelRuntimeTest.java',` to `modelSpecificGeneratedAppTests` in
`NPDevRuntimeHost/build.gradle`, alphabetically between `NonDefaultRuntimeSurfaceProfileIntegrationTest.java`
and `PermissionAwareUiMetadataServiceTest.java` -- the exact same exclusion mechanism
`RuntimeMetadataServiceTest.java` already uses, one line, no new mechanism.

Verified live: patched the already-generated `NPDevSamples/simple-contact-intake/Output/App`'s own
(ephemeral, merged-copy) `build.gradle` identically and re-ran `gradlew test` -- PanelRuntimeTest
no longer runs, the rest of the 458 tests still pass.

Scope note: this fixes the EXCLUSION gap, not the test's own portability. PanelRuntimeTest still
cannot run against an arbitrary generated app's real data (same as RuntimeMetadataServiceTest) --
if it is meant to be a real regression test for panel rendering, it should eventually move to a
`@SpringBootTest`-style test that boots against a purpose-built fixture model, or be converted to
use the external-path override directly rather than relying on classpath-fallback timing. Not
attempted here -- out of the Fast Lane plan's scope, and the existing sibling
(`RuntimeMetadataServiceTest`) has carried the same limitation without anyone picking it up.

### REG-113 — NPDevRuntimeHost/build.gradle.template silently shadowed the actively-maintained NPDevRuntimeHost/build.gradle in every assembled/generated app -- FinalAppAssembler.materializeRootTemplate() prefers *.template over the legacy file whenever both exist, but nothing has kept .template in sync since before commit 067b987 ('Moves 6-11'), so every FinalApp assembled since then (including the very REG-112 fix landed earlier this same session) got a build.gradle missing everything written after that point

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found re-running Move 14 Phase A's mandatory T2 gate sweep (run-all-gates.ps1) after REG-111's
closure: run-runtimehost-gate.ps1 failed with the EXACT same PanelRuntimeTest NoSuchElementException
REG-112 (opened and closed earlier this same session, commit 904fae1) already fixed by adding
'com/finalexec/PanelRuntimeTest.java' to build.gradle's modelSpecificGeneratedAppTests list.

Root cause: NPDevGenerator's FinalAppAssembler.materializeRootTemplate() (assembly/FinalAppAssembler.java)
copies the bootstrap build.gradle into every assembled app via:
    Path template = sourceRoot.resolve(fileName + ".template");
    Path legacy = sourceRoot.resolve(fileName);
    Path source = Files.exists(template) ? template : legacy;
i.e. it prefers NPDevRuntimeHost/build.gradle.template over NPDevRuntimeHost/build.gradle whenever
the .template twin exists -- copied byte-for-byte, no token substitution happens (materializeRootTemplate
is a plain Files.copy), so the ".template" naming carries no templating behavior at all; it is purely
a stale-twin trap.

git log confirms the drift's age: build.gradle.template's last real commit was fbf3319 (an old
SER-P7.3+P7.5 commit), while build.gradle (the file everyone has actually been editing) was updated
by 067b987 ("feat(Moves 6-11): RuntimeHost -- typed surfaces, silent-answer fixes, query pushdown")
and then 904fae1 (REG-112, same session) with neither commit touching .template. So EVERY app
assembled by NPDevGenerator since 067b987 -- every AppGen FinalApp, every NPDevSamples fixture,
every T1/T2 gate's own generated fixture -- has been built from a build.gradle missing at least
Moves 6-11's RuntimeHost changes, not just REG-112's one line. Confirmed by diffing the two files:
build.gradle had 41 lines with no counterpart in .template (real content: the Move 11 W1 derived-
exclusion mechanism, REG-112's PanelRuntimeTest line, etc.); .template had 8 lines with no counterpart
in build.gradle, all an older wording of ONE comment block build.gradle later reworded -- i.e.
build.gradle is a strict functional superset, .template is purely behind, never ahead.

This is the same "rule applied in one place, not mirrored to its twin" family MOVE14_AGILE_SPEC.md's
Phase E item U2 already names three instances of (REG-89: runtime createIfMissing vs. the validator;
REG-104: parser/compiler/canonical vs. ModelResolver; REG-112: one test-exclusion list vs. its
sibling). This is a fourth, more severe instance: not a single omitted line but an entire file nobody
realized was still load-bearing, silently overriding its own more-current twin for an unknown number
of prior moves.

**Surface:** `generator, runtimehost`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/assembly/FinalAppAssembler.java`
- `NPDevRuntimeHost/build.gradle`
- `NPDevRuntimeHost/build.gradle.template`

Fix applied (minimal, safe): synced build.gradle.template's content from build.gradle (a straight
copy -- confirmed build.gradle is a strict superset first, see source above) rather than changing
FinalAppAssembler's copy-preference logic or deleting .template outright. Chosen because several
OTHER gate scripts read NPDevRuntimeHost/build.gradle.template directly as their own source of truth
(grepped: scripts/quality/run-runtime-surface-evidence.ps1, scripts/hygiene/check-csrf-posture.ps1,
scripts/quality/run-runtimehost-staged-jar-preflight.ps1,
scripts/quality/run-runtimehost-integration-infrastructure-check.ps1) -- deleting the file outright
risked breaking those on a file-not-found rather than fixing the actual defect (those checks were
ALSO silently reading stale content this whole time; syncing content fixes them for free, without a
wider audit of each one's tolerance for the file's absence).

Verified live: re-ran run-runtimehost-gate.ps1 (T2, part of Move 14 Phase A's mandatory phase-boundary
sweep) against simple-contact-intake after the sync -- PASSED, no PanelRuntimeTest failure. The other
three run-all-gates.ps1 gates (aiKnowledge, generator, frontend) had already passed in the same T2
sweep before this fix, so the fix was verified with the minimal necessary re-run rather than a full
re-sweep.

Left open / not attempted here (out of Move 14 Phase A's scope, flagged for Phase E's U2 item):
- The underlying twin-drift MECHANISM is not fixed, only this one instance of its damage. Nothing
  stops build.gradle.template from silently going stale again the next time someone edits
  build.gradle without remembering its shadow twin exists. Phase E's U2 mirror-rule gate (already
  scoped to cover REG-89/104/112's twin-pairs) should add this exact pair
  (NPDevRuntimeHost/build.gradle vs. build.gradle.template) as a fourth check, or -- arguably the
  better structural fix -- FinalAppAssembler.materializeRootTemplate() should stop supporting a
  silently-preferred .template twin at all (it performs no actual template substitution, so the
  distinction serves no purpose Files.copy(build.gradle) doesn't already serve) and
  NPDevRuntimeHost/build.gradle.template should be deleted once every script that reads it directly
  is confirmed to tolerate that.
- Not audited: whether any PREVIOUSLY-assembled, already-deployed FinalApp (AppGen apps, WmsOffice,
  etc.) is running on a build compiled against the stale template's content in some way that matters
  beyond test exclusions (e.g. a dependency version, a plugin block) -- this fix only guarantees the
  NEXT assembly of any app picks up current build.gradle; it does not retroactively rebuild anything.

### REG-114 — workspace::PropertyValue (RC-A2's cascade storage) inherited blanket admin-only CRUD permissions from isAdminConcept()'s built-in-pack default, with no carve-out for a user to read their own resolved property values -- the same latent-bug class workspace::Menu already needed a carve-out for, now reproduced on a newer built-in-pack concept

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Move 14 Phase B item B3 (RC-A5)'s own gating instruction: "Built-in-pack reads are believed
admin-gated. A user must be able to read their own preferences. Confirm the current gating first;
if it is admin-only, fixing it is part of RC-A5." Confirmed live, not assumed:

Booted WmsOffice (the one app in the corpus including the workspace pack), logged in as the
existing admin (POST /api/auth/login, tenant "trial", roles=["ADMIN"]), created a fresh non-admin
account via POST /api/auth/create-user (roleName="USER"), logged in as it too. Issued the IDENTICAL
read (GET /api/concepts/workspace_property_values) as both:
  ADMIN     -> HTTP 200  {"content":[],...}
  NON-ADMIN -> HTTP 403  {"status":403,"error":"Forbidden",...}

Root cause (read, not just observed): NPDevGenerator/generator/src/main/java/com/npdev/generator/
emitters/RuntimeApiEmitter.java's generatePermissionManifest() grants create/update/delete/read/list
on every built-in-pack concept (isAdminConcept(), true for any "workspace::"/"identity::"-namespaced
concept) ONLY to the configured superuser role. The one existing exception is workspace::Menu
(read/list opened to role "user" -- "a platform-default navigation source read by every logged-in
user's own shell/UI, not an admin surface", per that fix's own comment, dated 2026-07-05 per
[[workspace_menu_shell_platform_default]]). workspace::PropertyValue (added this same Move, RC-A2)
never got an equivalent carve-out -- a newer built-in-pack concept did not automatically inherit
Menu's reasoning, only the surrounding code it was declared next to. Evidence recorded at
__OutsideRepo/move13-helpers/rc-a5-admin-gating-evidence.txt.

**Surface:** `generator, runtimehost, security`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/api/PropertyResolverController.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevCapabilityBindingConfig.java`

Fix folded into RC-A5 (B3), not treated as a footnote. Rather than widening the raw generic-CRUD
"read:workspace::propertyvalue" grant to role "user" the way Menu's fix did -- which here would let
ANY authenticated user list/read EVERY row of the table, including other users' own "user"-scope
rows, a real privacy regression PropertyValue's row-level design specifically exists to avoid (see
RC-A2/BREAKING.md's own scopeType/scopeId shape) -- added a DEDICATED REST surface instead:
GET/PUT /api/properties/{key}, open to every authenticated user regardless of role, backed by
PropertyResolver.resolve()/.explain()/.set(). Because PropertyResolver only ever resolves/mutates
the CALLER's own ExecutionContext-derived cascade (tenant/user/tag-scoped, never an arbitrary row by
id), opening it to every role cannot leak another user's or tenant's data the way opening the raw
CRUD grant would. The raw generic-CRUD grant on workspace::PropertyValue is left admin-only,
unchanged -- an admin/superuser can still use it for bulk inspection/cleanup -- but it is no longer
the only read path, so its admin-only posture is no longer a user-facing bug.

Left open / not attempted here: auditing every OTHER built-in-pack concept for the same
"needs-a-carve-out-but-never-got-one" pattern (this finding is a second confirmed instance after
Menu -- Phase E's item U2 mirror-rule gate, already scoped to catch a few other twin-pairs, should
arguably grow a check for "every built-in-pack concept either has an explicit carve-out or an
explicit comment saying why it doesn't need one" rather than this being found by hand a third time).

### REG-115 — A new com.finalexec.api.*Controller added to NPDevRuntimeHost compiles into every OTHER app fine but silently produces 404-on-every-route with zero errors anywhere unless its simple class name is also added to runtime-supported-controllers.json's allowedControllers -- an allowlist gate with no companion check that a new controller was actually added to it

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found live while building Move 14 Phase B item B3's fix (REG-114): added
NPDevRuntimeHost/src/main/java/com/finalexec/api/PropertyResolverController.java, wired its bean,
regenerated + built WmsOffice cleanly (BUILD SUCCESSFUL, no warnings), booted it healthy -- but
every /api/properties/* route 404'd. `jar tf` on the built FinalExec jar confirmed the class was
simply ABSENT (not a routing/Spring-scanning problem -- the .class file never existed).

Root cause: NPDevRuntimeHost/build.gradle's sourceSets.main.java block computes
`unsupportedRuntimeHostControllerSources` -- every `com/finalexec/api/*Controller.java` whose
simple name is NOT in `runtime-supported-controllers.json`'s `allowedControllers` array -- and
`exclude`s it from compilation UNCONDITIONALLY (not gated on generatedRuntimeMountPresent() the way
the OTHER two exclusion mechanisms in the same file are). A new controller under
com.finalexec.api/ is invisible-by-default; it must be explicitly opted into the allowlist, and
nothing checks that every controller FILE has a corresponding allowlist ENTRY (the inverse check --
"does every allowlist entry name a real file" -- may or may not exist; not audited here). The
failure mode is a plain compileJava skip with no diagnostic of any kind: no warning at generate
time, no error at build time (BUILD SUCCESSFUL), no error at boot time (the app starts healthy) --
only a 404 on whatever routes that controller was supposed to serve, discoverable only by actually
calling them.

**Surface:** `runtimehost, generator`
**Files:**
- `NPDevRuntimeHost/build.gradle`
- `NPDevRuntimeHost/src/main/resources/npdev/runtime-supported-controllers.json`

Immediate fix: added "PropertyResolverController" to allowedControllers (same commit as
PropertyResolverController.java itself). Verified live: rebuilt WmsOffice, `jar tf` confirmed the
class now compiles into the jar, and its routes now respond (200, not 404).

Left open / not attempted here (out of Move 14 Phase B's scope, flagged for whoever next touches
this manifest or Phase E's item U2): no gate currently checks the OTHER direction -- that every
`com/finalexec/api/*Controller.java` file in the repo has a matching
allowedControllers/deferredControllers/testOnlyControllers entry. A check-*.py comparing the file
tree against the three arrays (flag any controller present in neither) would close this
"silent-by-default" trap the same way run-script-inventory-check.py already closes the analogous
"an orphaned check-*.py nothing calls" trap for gate scripts -- the identical shape of bug, one
layer up.

### REG-116 — dsl-conformance-max's own propertyScopes declaration (the RC-A1 corpus witness) listed the implicit root scope (tenant, no 'from') BEFORE the more specific 'user' scope -- compiled clean and validated clean since Wave 6, but silently inverts cascade precedence, undetected until Move 14's PropertyResolver (RC-A3) finally read propertyScopes' order for the first time

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found live while end-to-end verifying Move 14 Phase B item B4 (RC-A6, folding settings into
properties) against a rebuilt WmsOffice: after an admin set a TENANT-wide pageRows=100 and the
non-admin user had already set their OWN user-scope pageRows=50, re-resolving pageRows AS THE USER
returned 100 (tenant), not 50 (their own more-specific override) -- exactly backwards.

Root cause: NPDevContract/dsl/.../validation/PropertyValidation.java's own javadoc and
PropertyScopeAst's javadoc both document that propertyScopes' declared array ORDER *is* the
resolver's cascade precedence (most specific first) -- but nothing ever validated that order, and
WmsOffice's model.json (authored this same Move, B4, copying the pattern from
NPDevSamples/dsl-conformance-max's PRE-EXISTING declaration) listed `[{tenant}, {user, from:
$user.id}]` -- tenant, the least-specific level, FIRST. dsl-conformance-max itself has carried this
exact ordering since Wave 6 (RC-A1's original authoring, docs/MOVE11_RUNTIME_CONFIGURATION_PLAN.md
Part A.1) -- compiled clean, validated clean (PropertyValidationTest's own
`aWellFormedDeclarationValidatesClean` fixture used the same tenant-before-user order and asserted
it clean), and stayed invisible because NOTHING read propertyScopes' order at runtime until
DefaultPropertyResolver (this same Move, item B2) was built to actually walk the array.

Confirmed live: PropertyResolverController's PUT/GET against a rebuilt WmsOffice reproduced the
inversion exactly as described above before the fix, and produced the correct precedence (user's
own 50 wins over the tenant's 100) after reordering both models' propertyScopes to
`[user, tenant]`.

**Surface:** `dsl, generator`
**Files:**
- `NPDevSamples/dsl-conformance-max/Input/model.json`
- `AppGen/apps/_official/WmsOffice/definition/model.json`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/PropertyValidation.java`
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/validation/PropertyValidationTest.java`

Two-part fix, matching this repo's own "codemod ships beside the data fix" convention even though
no npdev migrate entry was warranted (only 2 corpus models are affected, both fixed directly in this
commit, and the field shape itself did not change -- only declaration order):

1. Data fix: reordered propertyScopes to `[user, tenant]` (most specific first) in both
   dsl-conformance-max and WmsOffice.
2. Durable compile-time check (PropertyValidation.validatePropertyScopesAndProperties): the implicit
   root scope (no `from`, always resolves to $ctx.tenantId) is by definition the LEAST specific
   level, so it is now REFUSED if declared anywhere but last. This is the one mechanically-checkable
   ordering rule available -- nothing signals relative specificity between two scopes that both
   declare a `from`, so those remain the author's own responsibility, same as before. Two new tests
   (PropertyValidationTest: a positive control declaring the root scope last, and the RED case
   declaring it first) plus a fix to the pre-existing `aWellFormedDeclarationValidatesClean` fixture,
   which itself used the wrong order and would have started failing under the new rule.

Left open / not attempted here: whether any OTHER already-declared propertyScopes ordering across
the wider corpus (not just these 2 models) has the SAME class of mistake between two scopes that
both declare a `from` (e.g. "estabelecimento" before "user" when the intended precedence is the
reverse) -- the new validation cannot catch that case (no signal exists to check it against), so
this is a real, class-limited coverage gap this fix does not close.

### REG-117 — The generated business UI's hardcoded 'My Preferences' panel (business-ui-app.mustache/shell.js.mustache) references the now-retired workspace::Preference concept and its old userId/category/prefKey/prefValue fields -- silently vanishes from the nav (no crash, no error) for the one app that had it, WmsOffice, since RC-A2 (Move 14 item B1) renamed the concept to PropertyValue with a different shape

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found while investigating why the T2 generator gate's own BuiltinPackComposerTest failed
(that failure was a narrower, already-fixed regression -- see below). A broader grep across the
whole repo for "Preference" after fixing that one test turned up two generator templates,
NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-app.mustache and
shell.js.mustache, both hardcoding `workspace::Preference` by name:

- shell.js.mustache line ~404: `hasPreferences = manifest.concepts.some(c => c.conceptName ===
  "workspace::Preference")` gates whether a "Preferencias" nav link is rendered at all.
- business-ui-app.mustache: `PREFERENCE_CONCEPT_NAME = "workspace::Preference"` (line 19), plus a
  full ~150-line "My Preferences" panel (loadPreferences/savePreference/addPreference/
  deletePreference/renderPreferencesPanel, lines ~1704-1850) -- a free-form, user-driven key/value
  note store: any authenticated user could add an arbitrary category/prefKey/prefValue row via the
  generic CRUD API, filtered client-side to `row.userId === state.actorId`.

Since `workspace::Preference` no longer exists in any compiled model (renamed to
`workspace::PropertyValue` with an incompatible shape -- scopeType/scopeId/propKey/propValue, no
userId/category fields at all -- by RC-A2, Move 14 Phase B item B1, this same session),
`findConcept("workspace::Preference")` now always returns undefined everywhere both templates check
it. The failure mode is SILENT, not a crash: `hasPreferences` is always false, so the "Preferencias"
nav link simply never renders, and the entire panel's code becomes dead/unreachable (nothing ever
calls `showPreferences()`/`renderPreferencesPanel()` without that nav link to trigger it). WmsOffice
is the only app in the corpus that ever included the workspace pack (per B0's preflight), so it is
the only app whose users lost a real, working end-user feature -- silently, with no error anywhere a
developer would notice unless they specifically went looking for the "Preferencias" link they used
to see.

Two OTHER instances of this exact class of regression (a test hardcoding the old concept
name/fields, not caught by B1's own verification pass because it only ran :kernel:test + live boots,
never the full :dsl:test or :generator:test suites) were found and fixed in this same T2 sweep:
WorkspacePackResolutionTest (NPDevContract/dsl) and BuiltinPackComposerTest (NPDevGenerator) --
both DONE. This third instance, in the generated UI template rather than a test, is left OPEN
because the correct fix is a real design decision, not a mechanical rename (see detail).

**Surface:** `generator`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-app.mustache`
- `NPDevGenerator/generator/src/main/resources/npdev-templates/shell.js.mustache`

NOT fixed here, deliberately -- this is a real design decision, not a mechanical find-replace, and
a 4067-line intricate generated-JS template is the wrong place for a rushed edit late in an already
long session with no time budgeted left to verify a change to it live in a real browser.

Why a simple rename (s/Preference/PropertyValue/ + adjust field names) is NOT the right fix: the
old "My Preferences" panel's use case -- an authenticated user adding ANY arbitrary
category/prefKey/prefValue row they want, with no schema, no declared keys, no type -- is a
fundamentally different mechanism from the new scoped-property cascade (RC-A1/A2/A3), which is
explicitly DECLARED, typed, and developer-authored (a fixed set of `properties[]` with a `type`,
`default`, and `settableAt`, never an open-ended user-added key). There is no faithful 1:1 mapping
from "arbitrary user note-taking" onto "resolve a declared property through a scope cascade" --
porting the panel to call PropertyResolver's new REST surface (`GET/PUT /api/properties/*`, built
this same Move for RC-A5's admin surface) would only work for the app's DECLARED properties, not
for arbitrary ad-hoc keys a user might have typed into the old panel.

Two real options for whoever picks this up, neither attempted here:
1. Retire the old panel and its hasPreferences/PREFERENCE_CONCEPT_NAME machinery entirely from both
   mustache templates (the cleaner cut -- CLAUDE.md's own "delete what's certain to be unused"
   instruction) -- the new properties.html generated admin surface (RC-A5, B3) is the intended
   successor for "let a user see/edit their own settings", just scoped to DECLARED properties.
2. Build a genuinely new, generic "declared properties" panel INSIDE the business UI shell itself
   (rather than a separate properties.html page) backed by PropertyResolver's REST surface -- more
   work, but keeps the feature inside the one shell a user already navigates, rather than a second
   page.

Either way: WmsOffice's app-specific web/ pages should be checked for any hand-authored link to the
old "Preferencias" nav target before whichever fix ships, in case something outside the generated
shell itself also points at it.

### REG-118 — C1's own plan guidance ('bind $prop.<name> where $user.* is already bound') points at a binding site that the SAME item's hard rule makes permanently dead code -- ConfiguredConceptGatewaySemanticPolicy.evaluateAccessRule's scope is used EXCLUSIVELY by access.read/access.write, the one place $prop.* is now compile-time forbidden

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found while implementing Move 14 Phase C item C1 (RC-A4). The plan's implementation hint reads:
"Bind $prop.<name> where $user.* is already bound -- ConfiguredConceptGatewaySemanticPolicy, anchor
scope.put(\"$user.id\", effectiveContext.actorId()); One binding site." A kernel-wide research pass
(grepping every expression-evaluation surface: CelInvariantEngine, KernelRunner's flow-branch
evaluator, DefaultProcedureExecutor's condition evaluator, and the one that matters here,
ConfiguredConceptGatewaySemanticPolicy) confirmed that binding site's scope map is constructed in
exactly one method, evaluateAccessRule, and that method has exactly one family of callers: the
access.read/access.write evaluation path -- the same path C1's own hard rule (already shipped,
ConceptValidation.validateAccessExpression, this same commit) makes it a COMPILE-TIME ERROR to
reference $prop.* inside. So literally following the plan's own binding hint would add a
scope.put("$prop.<name>", ...) line that can never be exercised by any model that passes validation
-- dead code guarded by the very rule it was meant to feed.
No other expression-evaluation surface in the kernel binds $user.* today, so "bind it where $user.*
is already bound" has no OTHER site to mean. Making $prop.* usable somewhere legitimate (invariants
via CelInvariantEngine/GeneratedCrudRuntimeSupport -- closest candidate, already has both
CompiledModel and ExecutionContext reachable; flow branch conditions in KernelRunner; procedure
conditions in DefaultProcedureExecutor) would require threading ExecutionContext and/or CompiledModel
through port contracts that do not carry them today, i.e. new plumbing, not a one-line bind.

**Surface:** `kernel`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/concepts/ConfiguredConceptGatewaySemanticPolicy.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ConceptValidation.java`

Not fixed here, deliberately -- C1's own bolded acceptance criterion is only the hard rule ("$prop.*
is FORBIDDEN inside access.read/access.write. Compile-time error, verified RED on both keys"), which
is DONE and verified (ConceptAccessValidationTest#propReferenceInAccessReadIsRefused/
propReferenceInAccessWriteIsRefused, full :dsl:test green, all 31 corpus models still parse clean).
The plan's binding hint was implementation guidance, not a separate mandatory deliverable, and this
finding documents why following that specific hint literally would be pointless under the current
architecture -- left here as a signpost for whoever next wants $prop.* usable in a legitimate,
non-authorization expression surface (invariants is the closest candidate; see the three-surface
comparison above), rather than silently rediscovering the same dead end.

### REG-119 — An app-declared role (RC-B1 roles[]/grants[]) holding EXECUTE_FLOW can never actually call the generated POST /api/flows/{name}/execute endpoint unless the actor ALSO independently holds the built-in 'user' role or the configured super-user role -- RuntimeApiEmitter's static permission manifest only ever grants the 'flow.execute' permission to those two role names, never to any app-declared one

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found live while verifying Move 14 Phase C item C2 (RC-B3, runtime permission-subset binding) on
WmsOffice. Granted the app-declared role WarehouseManager (grants: EXECUTE_FLOW, READ_EXECUTIONS,
READ_FLOW_DEFINITIONS) to a test user (nonadmin1) who also still held the built-in USER role, and
POST /api/flows/ConfirmarMovimentacao/execute worked (reached deep into the flow, failing later on an
unrelated capability permission). Revoked USER, leaving WarehouseManager as the actor's ONLY role, and
the exact same call was refused at the OUTER gate with "Permission 'flow.execute' denied for
actor='nonadmin1', tenant='trial'" -- a completely different error shape than
DefaultExecutionAuthorizationPolicy's own canExecuteFlow denial (a bare 403 with no such message),
which was the tell that a SECOND, EARLIER, unrelated gate was actually the one firing.

Root cause, confirmed by reading RuntimeApiEmitter.generatePermissionManifest
(NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/RuntimeApiEmitter.java, ~line
418-543): this method builds a STATIC, compile-time-baked PermissionGrant list (consumed at runtime
by StaticPermissionEvaluator) that is COMPLETELY SEPARATE from the kernel-level Permission-enum
ceiling (RolePermissions/DefaultExecutionAuthorizationPolicy/ExecutionAuthorizationPolicy, the
mechanism RC-B1's roles[]/grants[] and this same session's C2 both extend). "flow.execute" is granted
to exactly two role keys: the hardcoded string "user" (line ~483) and whatever the app configures as
its super-user role key (line ~467, ~516 -- every collected permission, unconditionally). No code
path in this method ever reads model.getRoles() (the app-declared roles[] CompiledRole list) to grant
"flow.execute" (or any other collected permission) to an app-declared role name. So RC-B1's entire
"app-declared roles can hold platform permissions" feature has -- since RC-B1 shipped, this is not a
C2 regression -- never actually let a role OTHER than the built-in "user"/super-user reach the
generated flow-execution HTTP endpoint at all, regardless of what that role's grants[] declares.
DefaultExecutionAuthorizationPolicy's own kernel-level check (which DOES honor app-declared roles, and
which C2 extends) never even gets reached for such an actor -- the static manifest gate is earlier in
the request path and denies first.

**Surface:** `generator/runtimehost`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/RuntimeApiEmitter.java`

Not fixed here, deliberately -- this is a real, separate gap in RC-B1 (a prior Move), not something
C2 introduced or is responsible for closing, and a correct fix (deciding whether/how an app-declared
role's grants[] should also translate into static PermissionGrantSpec rows -- e.g. granting
"flow.execute" to any app-declared role whose CompiledRole.grants() includes EXECUTE_FLOW) needs its
own design and verification pass, not a same-session patch appended to an unrelated item's closing.

Practical implication for anyone using RC-B1 today: an app-declared role is fully honored by every
KERNEL-level operation reached directly (trace read/search, resume, list executions, audit, admin
ops, debug view -- i.e. everything DefaultExecutionAuthorizationPolicy itself gates, including C2's
new runtime permission-subset narrowing), but NOT by the generated HTTP flow-EXECUTE endpoint
specifically, unless the actor also separately holds "user" or the super-user role. This was
confirmed to NOT affect C2's own verification: C2's live proof used the kernel-level
GET /api/executions endpoint (ExecutionQueryController -> KernelFacade.listExecutions ->
canListExecutions -> READ_EXECUTIONS), which has no such static gate and cleanly demonstrated both
the reject-outside-ceiling proof (400 on POST .../permissions with a permission outside the role's
declared grants) and the live narrowing-takes-effect proof (403 once READ_EXECUTIONS was narrowed
away, restored to 200 once the WarehouseManager-only test role held it again).

### REG-12 — LNCH-10: Excel/PDF/print export beyond CSV -- all 3 slices shipped

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `generator/export-pdf`

Slice 1 (streaming CSV export) pre-existed. Slice 2 (print stylesheet/render mode): a "Print" button
on every declared panel builds a self-contained #printRoot document and calls window.print(); a new
@media print block hides app chrome. Verified live via real-browser ScrapForAI. Found+fixed a
pre-existing unrelated bug during this verification: an InMemory-storage app's promotion panel
retried a 503'ing endpoint in an unbounded render loop; fixed with an "attempted" guard flag. Slice
3 (server-side PDF): a new declarative `document` DSL kind bound to a concept's query, a
DocumentRenderContract kernel port with a pure-JVM openhtmltopdf adapter (proven headless-safe by a
spike) plus an honest no-op stub adapter, a DocumentRenderController mirroring the CSV export
controller's discipline, and a "Download PDF" toolbar link. Verified live: a real PDF streamed with
exact title/timestamp/row/column/footer content confirmed via PDFBox text extraction. Found+fixed 3
real bugs during wiring: two model-reconstruction call sites silently dropping the new `documents`
field (and, at one of them, also pre-existing dropped guidePages/aggregates/autoPanels for
pack-composing apps), a static controller allowlist silently 404ing the new endpoint, and a missing
Gradle dependency declaration causing NoClassDefFoundError. Verified green on real Linux GitHub
Actions (run 29943008077), not just Windows.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-12`

### REG-120 — A concept whose create is delegated to a declared Flow (input.mode: create) AND is also exposed via the generic CRUD create endpoint gets DOUBLE-PERSISTED on every create -- the flow's own createConcept step writes the row through the kernel persistence capability, then the SAME generated service method immediately writes it AGAIN via saveWithIntegrityMapping -- and the two writes can race, throwing a spurious 500 (or, when they don't race, silently perform a wasted redundant write)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found live while authoring Move 14 Phase D item D1's canary acceptance scenario (folding the
already-built acceptance runner into run-fast-gate.ps1). NPDevSamples/npdev-canary's own model
declares CanaryTask with a create-mode flow (CreateCanaryTask: invariantCheck -> createConcept ->
return) -- exactly the "Flow-CRUD wrapper" pattern RuntimeApiEmitter.generatePermissionManifest's own
comment already names (NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/
RuntimeApiEmitter.java:474-484, "If this CRUD operation's mutation is delegated to a declared Flow").

POST /api/flows/CreateCanaryTask/execute (the direct flow endpoint) succeeded reliably, every time,
in isolation. POST /api/concepts/canary_tasks (the generic CRUD create endpoint, for the SAME
concept) failed deterministically -- 100% reproducible, on a completely fresh/empty database, cold
boot, single isolated call, no concurrent client requests -- with:
  org.h2.jdbc.JdbcSQLTimeoutException: Timeout trying to lock table {0}
  Caused by: org.h2.message.DbException: Concurrent update in table "canary_tasks": another
  transaction has updated or deleted the same row [90131-224]
(full repro log: a single `curl -X POST .../api/concepts/canary_tasks` against a canary instance
whose D:\WorkSpace\NPDev\Build\databases\npdev-canary\npdev_canary.mv.db had just been deleted and
the app freshly rebooted -- ruling out accumulated state, concurrent callers, or the
SandboxedPluginExecutionEngine's 1000ms default timeout (raised to 5000ms for this same
investigation, made no difference) as the cause.)

Root cause, confirmed by reading the generated
CanaryTaskServiceBase.createFromSource (NPDevSamples/npdev-canary/Output/App/npdev-generated/
src/main/java/com/npdev/generated/services/CanaryTaskServiceBase.java:131-173, itself generated
from NPDevGenerator's service-base.mustache): the method calls, in sequence,
`enforceWithConceptGateway("CanaryTask", generatedId, createPayload)` (an authorization CHECK, per
its own adjoining comment, not a persistence write) then
`enforceWithCreateFlow(crudCtx, generatedId, createPayload)` -- which runs the declared
CreateCanaryTask flow, whose own `createConcept` step ALREADY persists the row through the kernel's
registered persistence capability (confirmed in the boot log: `com.npdev.kernel.KernelRunner ::
cap=persistence op=save ... concept=CanaryTask` fires here) -- and THEN, four lines later,
unconditionally calls `saveWithIntegrityMapping(e)` on a freshly-constructed CanaryTask entity
carrying the SAME generatedId, persisting the identical logical row a SECOND time through a
completely separate path (the CRUD service's own JPA/Hibernate save, not the kernel persistence
capability the flow just used). Two independent writes to the same (tenant, id) row through two
different persistence mechanisms, each opening its own transaction/session -- exactly what H2
reports as a concurrent-update conflict, and what any real (Postgres) deployment under write
contention would be at genuine risk of also hitting, just with different error text.

**Surface:** `generator/runtimehost`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/service-base.mustache`
- `NPDevSamples/npdev-canary/Output/App/npdev-generated/src/main/java/com/npdev/generated/services/CanaryTaskServiceBase.java`

Not fixed here, deliberately -- this is a generator-template-level defect (service-base.mustache's
createFromSource shape) affecting EVERY app with a concept that combines a create-mode Flow with
CRUD-create exposure, not something specific to npdev-canary; a correct fix needs to decide, and
then verify against the existing REG-16-resid R2 enforcement-ordering guarantee (the adjoining
comment this bug sits right next to), which of the two writes should actually persist the row --
most likely: keep enforceWithCreateFlow's flow-driven write (it already runs
invariantCheck/authorization/side-effects in the declared order) and make saveWithIntegrityMapping
a no-op (or skip it entirely) whenever a create flow was invoked for this concept, rather than
having two independent code paths that both believe they own writing the row. That's a real design
decision touching a security-adjacent enforcement path (REG-16-resid R2), not a same-session patch.

Workaround used to unblock Move 14 Phase D item D1 (NOT a fix): the canary acceptance scenario
(NPDevSamples/npdev-canary/acceptance/01-canary-task-list-filters-by-title.scenario.json) seeds
through the direct flow endpoint (POST /api/flows/CreateCanaryTask/execute) instead of the raw CRUD
create endpoint, since only the CRUD path double-writes. Confirms the bug is specific to the CRUD
wrapper, not the flow itself or the persistence capability.

### REG-121 — Two release-evidence producers (run-ai-beta-gate.ps1, run-trusted-source-beta0-proof.ps1) still invoke `:generator:run` with the disabled `--migrationsDir` flag -- GeneratorMain.migrationsDisabled() rejects it outright (CONFIG_MIGRATIONS_DISABLED), so every ai-beta-gate scenario whose model reaches generation fails there, cascading into expanded-beta0-evidence, sample-matrix, docker-linux-parity, and final-regression-coverage-audit

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-03)
**Verification:** VERIFIED_LIVE
**Source:** Found re-checking the betaRelease (T3) gate for Move 14 Phase E item E2 -- last verified at Move
12 (red on 23 missing + stale evidence). Ran `run-beta-release-gate.ps1 -GenerateReports` (the
full ~540s-935s evidence orchestration) for the first time since then: went from 25 precondition
blockers + 5 real failures to 0 failed-no-evidence (every producer now runs and writes real
evidence) + 19, then 21, real check failures -- a genuinely more honest state, per REG-3's own
exit-code distinction (PRECONDITION-UNMET vs CHECK-FAILED).

Fixed one real bug in the same session (REG-122): the AI-authoring contract normalizer was
emitting `enforceInvariants`/`cap`/`op`/`out` (DSL 1.0 step syntax, retired when DSL 2.0 shipped)
instead of `invariantCheck`/`capability`/`operation`/`output`, failing official JSON Schema
validation for every golden scenario with a `flows[]` declaration. Fixing that took 22 of 28
ai-beta-gate scenarios from failing at official-validation (a cheap, early, uninformative stage)
to correctly reaching and failing (or passing) at their own DESIGNED stage -- 22 now pass entirely
(negative scenarios correctly matching their expected failure stage), confirming the fix is
correct, not just less-early-failing.

The REMAINING failures (6 positive scenarios: base-ai-loop, custom-panel-unsupported,
tenant-approval-portal, tenant-service-desk, tenant-workflow-ops, and one more) now correctly pass
official-validation too, but fail at the NEXT stage, "generation", with:
  java.lang.IllegalArgumentException: CONFIG_MIGRATIONS_DISABLED: stateful upgrade management is
  not supported by this generation path (source: --migrationsDir). Use recreate-style generation
  and schema realization instead.
    at com.npdev.generator.GeneratorMain.migrationsDisabled(GeneratorMain.java:399)
`run-ai-beta-gate.ps1` (and `run-trusted-source-beta0-proof.ps1`, which passed only because its
own scenario apparently never reaches this code path) still construct the `:generator:run`
invocation with `--migrationsDir <path>`, a generation mode `GeneratorMain` now refuses
unconditionally -- every other invocation path in this repo (Build-NpdevApp.ps1,
generate-sample-app.ps1, and everything this session's own work generated/built/booted, e.g.
npdev-canary, dsl-conformance-max, WmsOffice) already uses the current "recreate-style generation
+ schema realization" contract instead. This is the SAME "one place updated, its twin not
mirrored" class Move 14 Phase E item E1 (U2) just built a permanent gate for -- not yet added as
a registry rule there because the correct twin-pair locations (which exact flags the current
generation contract expects) were not fully mapped in this investigation.

docker-linux-parity's own failure is this SAME root cause, one layer down: its `docker-run-ai-beta-gate`
command literally runs `run-ai-beta-gate.ps1` inside the Linux container (Docker build/version
both passed cleanly -- real Docker infrastructure works), so it fails identically.
final-regression-coverage-audit's failure is a downstream reflection of doc-entrypoint-validation's
(a separate, unrelated finding -- see REG-122) and ai-beta-gate's own failed status, not an
independent third root cause.

**Surface:** `quality-gates/ai-beta-pipeline`
**Files:**
- `scripts/quality/run-ai-beta-gate.ps1`
- `scripts/quality/run-trusted-source-beta0-proof.ps1`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`

Not fixed here, deliberately -- per this Move's own explicit instruction for E2 ("Run it, then
either regenerate the missing evidence or name each report that cannot be generated and why...
Prohibited: removing a report from the required list, extending the staleness window, or
allowlisting. A red gate honestly reported is the correct outcome"). Understanding and updating
run-ai-beta-gate.ps1's generator invocation to the current recreate-style/schema-realization
contract is a real, separate, bounded fix, but the AI-beta pipeline is a widely-depended-on shared
producer (feeds ai-beta-gate, expanded-beta0-evidence, sample-matrix, docker-linux-parity, and
final-regression-coverage-audit all at once) and deserves its own dedicated verification pass
(regenerate the ~10 minute evidence orchestration again after the change, confirm all 6 positive
scenarios now reach boot/smoke, confirm no negative scenario's expected-failure-stage shifted)
rather than a rushed same-session patch on top of an already very long session.

Recommended next step for whoever picks this up: read Build-NpdevApp.ps1's or
generate-sample-app.ps1's own `:generator:run` invocation (both already migrated) to find the
exact replacement flag set for `--migrationsDir`, apply it to both listed scripts, then add a new
rule to scripts/quality/twin-pair-registry.json (E1's mirror-rule gate) tracking "the generator's
currently-supported CLI flags" against every script that invokes `:generator:run` directly, so a
FUTURE generator CLI contract change cannot silently strand one of these evidence producers again.

### REG-122 — Normalize-AiContract.ps1 emitted retired pre-DSL-2.0 flow-step syntax (enforceInvariants / cap / op / out) for every AI-authored model's generated flow, failing official JSON Schema validation for every golden AI scenario that declares flows[] -- masking the true outcome of ~20 of 28 ai-beta-gate scenarios behind an early, uninformative official-validation failure instead of their own designed stage

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-02)
**Verification:** VERIFIED_LIVE
**Source:** Found re-checking the betaRelease (T3) gate for Move 14 Phase E item E2. The ai-beta-gate
producer's own report showed every scenario with a `flows[]` declaration failing at
"official-validation" with real JSON Schema errors: `/flows/0/steps/0/type enum: must be equal to
one of the allowed values`, `/flows/0/steps/1 required: must have required property 'capability'`,
`.../'operation'`, plus cascading `additionalProperties`/`oneOf` errors one level up at the flow
object itself (ajv's own multi-branch error reporting for a `oneOf` that matched neither
candidate schema).

Root-caused to scripts/ai/Normalize-AiContract.ps1:261-296, which hardcodes each AI-authored
flow's generated step sequence using DSL 1.0 spellings retired when DSL 2.0 shipped (see
BREAKING.md / NPDevCli/dsl_v2_migration.py, which migrates exactly this class of rename in a
checked-in model, but golden-ai-scenarios/*/ai-model.json is not part of the migrated corpus since
it's the AI-authoring INPUT contract, not a DSL model file itself -- the normalizer that turns it
INTO a DSL model was simply never updated when DSL 2.0's canonical spellings shipped): `type:
"enforceInvariants"` (canonical: `invariantCheck`), and a capabilityCall step using `cap`/`op`/`out`
(canonical: `capability`/`operation`/`output`, confirmed against NPDevSamples/dsl-conformance-max's
own working capabilityCall steps and NPDevContract/schemas/model.schema.json's flowStep `allOf`
requirements).

**Surface:** `quality-gates/ai-beta-pipeline`
**Files:**
- `scripts/ai/Normalize-AiContract.ps1`

Fix: renamed the four fields at the exact two step declarations (lines 264-274) to their DSL 2.0
canonical spellings. No other logic changed.

Verified two ways:
1. Isolated: re-ran Normalize-AiContract.ps1 against golden-ai-scenarios/behavior-mismatch (the
   scenario whose report first surfaced the schema error), then Invoke-JsonSchemaValidation.ps1
   against the resulting model.json -- 0 errors, 0 failures (previously 12 schema violations).
2. End to end: re-ran the full ~540-935s betaRelease evidence orchestration before and after.
   Before: ai-beta-gate's 28 scenarios showed widespread official-validation failures masking
   their real designed outcome. After: 22 of 28 scenarios now pass cleanly, including EVERY
   negative scenario correctly reaching and matching its own declared `expectedFailureStage`
   (proving the fix didn't just relax validation, it let each scenario's real behavior surface --
   a scenario designed to fail at "smoke-verification" now genuinely reaches that stage instead of
   failing three stages earlier for an unrelated schema reason).

Residual, NOT fixed here (filed separately as REG-121): the remaining 6 scenarios that reach
official-validation cleanly now fail one stage later, at "generation", for a completely different,
unrelated root cause (a disabled `--migrationsDir` generator flag two other scripts still use).

### REG-123 — doc-entrypoint-validation fails on ~20+ stale script-path references and unmapped report references scattered across historical/archived docs (docs/beta/*, docs/architecture/*, docs/NEXT_EXECUTION_PLAN.md, etc.) -- a documentation-drift backlog, not a single defect, that has never been triaged since this checker's own scope was expanded to cover the full docs/ tree

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-03)
**Verification:** VERIFIED_LIVE
**Source:** Found re-checking the betaRelease (T3) gate for Move 14 Phase E item E2, via the same full
evidence-orchestration run that produced REG-121/REG-122. scripts/reports/out/
doc-entrypoint-validation-report.json's own `failures` list (scanned 149 documents) names ~20+
distinct violations, three shapes:
  1. "references missing release-relevant script <path>" -- e.g. docs/beta/
     sample-browser-verification-methodology.md:59 points at the old top-level `scripts` folder's
     `generate-sample-app.ps1`, which now lives under `NPDevSamples`' own `scripts` folder (moved at
     some point, the doc never updated); similarly `run-durable-resume-demo.ps1`,
     `export-concept-to-pack.ps1` (under `scripts/packs`), `scrapforai-harness.ps1` (under
     `scripts/browser`), the `superuser-admin-console` folder's `demonstrate-*.ps1` scripts --
     several distinct historical script moves, never reconciled against every doc that names them.
  2. "references blocking report with unresolved mapping <path>" -- e.g.
     docs/architecture/NPDEV_GENERATOR_ADAPTER_CONTRACT.md:18-20 names three report files
     (box-object-truth-report.json, code-bearing-object-resource-report.json,
     box-object-promotion-evidence-closure-report.json) that no current script or policy entry
     produces or maps -- either aspirational/never-built reports from an early architecture doc, or
     reports that were produced under different names since.
  3. Downstream: final-regression-coverage-audit's own failure (see REG-121's detail) is a
     reflection of this same finding, not independent.

**Surface:** `docs`
**Files:**
- `scripts/reports/out/doc-entrypoint-validation-report.json`
- `scripts/quality/run-doc-entrypoint-validation.ps1`
- `scripts/policy/doc-entrypoint-classification-policy.json`

Not fixed here, deliberately -- this is Move 14 Phase E item E2's own explicitly permitted outcome
("name each report that cannot be generated and why... a red gate honestly reported is the correct
outcome"), not a quick patch. This is genuinely a documentation-triage backlog, not a single root
cause: each reference needs a real decision (is the referencing doc a live, currently-relevant
entrypoint that should be corrected to the script's new path, or a historical/archived planning doc
whose stale reference is acceptable and should be excluded from this checker's scope instead --
docs/POST_PUBLIC_PLAN.md's own precedent for "fingerprints survive a file move by design" is the
right shape of fix for category 1, but applying it needs a person to look at each doc, not a bulk
script-path rewrite that might silently paper over a doc that SHOULD have been updated in
substance, not just re-pointed). Prohibited by this Move's own item text from being resolved by
removing these reports from the required list or allowlisting the violations away.

Recommended next step for whoever picks this up: triage each of the ~20 failures into (a) update
the doc's stale path (the script/report genuinely still exists, just moved/renamed), (b) mark the
doc's own status as archived/historical in scripts/policy/doc-entrypoint-classification-policy.json
if it is not meant to be currently accurate, or (c) file a distinct REG-nn if the referenced
report/script was a real, never-fulfilled commitment.

### REG-124 — golden-ai-scenarios/tenant-workflow-ops's ai-model.json declares tenancy.tenantIdField: "tenantId", which Normalize-AiContract.ps1 turns into an EXPLICIT "tenantId" field on the Ticket concept -- colliding with the platform's own implicit, reserved tenant_id column (ReservedColumnNames), so generation now fails with CONCEPT_FIELD_RESERVED_COLLISION instead of reaching build/boot/smoke

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-08-03)
**Verification:** VERIFIED_LIVE
**Source:** Found while live-verifying Move 15 Phase C item C1 (REG-121's fix: replacing run-ai-beta-gate.ps1's
disabled --migrationsDir flag with --dbDefinitionPath). REG-121's fix works -- verified live against
3 of the 6 previously-generation-blocked scenarios in isolation (base-ai-loop, custom-panel-unsupported
both reached FULL green: generation -> deterministic-generation -> build -> boot -> health -> smoke,
all passed). tenant-workflow-ops is the one exception: generation now reaches past
CONFIG_MIGRATIONS_DISABLED cleanly (the DB-definition/schema-fingerprint log lines print
successfully) but then fails with a NEW, unrelated error:

  java.lang.IllegalStateException: Concept Ticket has a field 'tenantId' whose column name
  'tenant_id' collides with a platform-reserved business-table column (every generated table
  implicitly gets 'version' for optimistic concurrency, 'row_version' for LNCH-16 CAS updates
  through ConceptGateway, and 'tenant_id' for tenant isolation). Rename this field in the model to
  something else (e.g. 'tenantIdRef').
    at com.npdev.generator.dbconfig.ReservedColumnNames.validateNoCollision(ReservedColumnNames.java:41)

Root cause: golden-ai-scenarios/tenant-workflow-ops/ai-model.json declares
`"tenancy": { "tenantIdField": "tenantId", ... }` (an AI-authoring-contract-level tenancy
declaration, also referenced by auth.principalFields and testUsers[].tenantId). Normalize-AiContract.ps1
reads this and adds an EXPLICIT "tenantId" field to the Ticket concept in the normalized model.json
(confirmed: D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\temp\ai-beta-gate\tenant-workflow-ops\normalized\model.json's
Ticket concept has fields ["id", "tenantId", "title", "status"]). This predates (or was never
reconciled against) the platform's own later convention that EVERY generated business table
implicitly gets a reserved `tenant_id` column for isolation -- so this scenario's own
AI-authoring-level tenancy design now collides with a platform guarantee added after the fixture was
written. Same general family as REG-122 (the normalizer emitting a shape that predates a later
platform contract change) but a distinct root cause and fix site -- REG-122 is already closed and
scoped only to flow-step syntax.

**Surface:** `quality-gates/ai-beta-pipeline`
**Files:**
- `golden-ai-scenarios/tenant-workflow-ops/ai-model.json`
- `scripts/ai/Normalize-AiContract.ps1`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/ReservedColumnNames.java`

Not fixed here, deliberately -- out of REG-121's own narrow scope (a disabled CLI flag), and the
right fix needs a real decision this session did not make: (a) rename the scenario's own
tenancy.tenantIdField to something that does not collide (e.g. "tenantIdRef"), threading the rename
through ai-model.json's tenancy/auth/testUsers blocks consistently, since it is NOT an isolated
single-field rename (three sections reference the same name); or (b) teach
Normalize-AiContract.ps1 to recognize when tenancy.tenantIdField collides with a platform-reserved
column and either skip emitting the redundant explicit field (the platform's implicit tenant_id
already covers exactly this use case) or auto-rename it. (b) is likely the more durable fix since it
would prevent every FUTURE AI-authored scenario from hitting the same collision, not just this one
fixture -- but deciding that needs its own verification pass (does anything else read the emitted
explicit tenantId field expecting it to exist as a real column?), not a same-session patch appended
to REG-121's own closing.

Practical implication for REG-121: this does NOT block REG-121's own DoD (the disabled-flag
producers now run to completion) -- 2 of 3 scenarios spot-checked in isolation reach complete
green (generation through smoke); this is scenario-tenant-workflow-ops's own separate, pre-existing
defect, unmasked by REG-121's fix rather than caused by it.

### REG-125 — PROJECT_DIGEST.md names scripts/quality/run-box-vision-doc-check.ps1 as its own 'Phase 0 validation script' (expected to write scripts/reports/out/box-vision-doc-check-report.json), but neither the script nor any equivalent under a different name was ever built -- a real, never-fulfilled commitment, not a stale path

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-03)
**Verification:** VERIFIED_LIVE
**Source:** Found while triaging Move 15 Phase C item C2 (REG-123, doc-entrypoint-validation's stale-reference
backlog). PROJECT_DIGEST.md §"Phase 0 validation" reads:

  Phase 0 validation script:
  scripts/quality/run-box-vision-doc-check.ps1
  Expected report:
  scripts/reports/out/box-vision-doc-check-report.json

A repo-wide search (case-insensitive, for "box-vision" and "box_vision" in any form) found zero
matches anywhere other than this one PROJECT_DIGEST.md reference -- the script was never written,
under this name or any other. Unlike the sibling finding in
docs/architecture/NPDEV_GENERATOR_ADAPTER_CONTRACT.md (three report references that doc's own text
explicitly frames as "Phase 10 is a contract phase... does not implement full production generator
integration" -- i.e. deliberately-future-by-design), PROJECT_DIGEST.md's wording ("Phase 0 validation
script:" followed by a bare path) does not read as an explicit future-phase disclaimer -- it reads
like a real, if small, planned validation tool (presumably: does the digest's own Box/Object/Truth
doctrine text stay consistent with docs/architecture/NPDEV_BOX_OBJECT_TRUTH_VISION.md and its
ADRs) that was simply never picked up.

**Surface:** `docs/quality-gates`
**Files:**
- `PROJECT_DIGEST.md`
- `scripts/policy/doc-entrypoint-classification-policy.json`

Not fixed here, deliberately -- this item's own scope (REG-123) is the validator's false-positive
and stale-path backlog, not building new tooling. Classified scripts/quality/run-box-vision-doc-check.ps1
as "future-non-release" and its report as "future-non-release-report" in
scripts/policy/doc-entrypoint-classification-policy.json (mirroring the existing
scripts/doctor/npdev-doctor.ps1 / doctor-report.json precedent) so doc-entrypoint-validation stops
blocking on it while this real gap is tracked here, rather than either silently building a rushed
checker or silently allowlisting the reference away with no record.

Recommended next step for whoever picks this up: decide whether this Phase 0 doc-consistency check
is still wanted (PROJECT_DIGEST.md's own doctrine section is short and has not visibly drifted from
NPDEV_BOX_OBJECT_TRUTH_VISION.md/its ADRs in practice) -- if yes, build it (likely a simple
keyword/section presence diff, not a large tool); if the digest doc is considered stable enough not
to need one, retire the "Phase 0 validation script" section from PROJECT_DIGEST.md instead and close
this as "intentionally not built."

### REG-126 — Normalize-AiContract.ps1 translates requiredRole for panels, procedures, and workflow transitions, but never for flows[] (the generic concept create/update declaration) -- the role gate is silently dropped, and the generated REST create endpoint ends up denying every role including the one the scenario intended to allow

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-08-03)
**Verification:** VERIFIED_LIVE
**Source:** Found while live-verifying Move 16 Phase A2 (REG-124's fix: renaming
golden-ai-scenarios/tenant-workflow-ops's tenancy.tenantIdField from "tenantId" to
"tenantIdRef" to stop it colliding with the platform's reserved tenant_id column).
REG-124's own fix works -- verified live: generation, deterministic-generation, build,
boot, and health ALL now pass for tenant-workflow-ops, where they previously failed at
generation with CONCEPT_FIELD_RESERVED_COLLISION. The scenario now reaches its final
"smoke" (REST verification) stage, which was never reached before this session.

One of the scenario's own designed smoke checks now fails:
  id: scenario-authenticated-create-ticket
  POST /api/tickets (X-Api-Key for testUsers[0], userId=agent-a, tenantId=tenant-a,
  roles=["agent"]), body {"tenantIdRef":"tenant-a","title":"Beta workflow request","status":"open"}
  expectedStatus: 201, actualStatus: 403 ("Forbidden", bare Spring Boot default error body,
  no npdev-specific reason/error field -- i.e. rejected before reaching any npdev-specific
  authorization-decision code path that would normally return a richer JSON envelope).

Traced the root cause by comparing golden-ai-scenarios/tenant-workflow-ops/ai-model.json
against the normalizer's OWN output for this run
(D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\temp\ai-beta-gate\tenant-workflow-ops\normalized\model.json):
the AI model declares `flows: [{"name": "CreateTicket", ..., "requiredRole": "agent", ...}]`
and a top-level `roles: [{"roleId": "agent", "permissions": ["ticket:create", ...]}, ...]`.
The normalized/official model.json's own `flows[0]` (CreateTicket) has NO requiredRole field
and no role-gate step anywhere in its `steps[]`; the official model.json has no `roles` key
at its root at all. Confirmed by grepping scripts/ai/Normalize-AiContract.ps1 for every
`requiredRole` reference: it is read and threaded through for `panels[].requiredRole` (line
~88, ~371, ~380), `procedures[].requiredRole` (line ~102, ~332), and workflow
`transitions[].requiredRole` (line ~131, ~246, confirmed present in this scenario's own
compiled Ticket.lifecycle.transitions[].metadata.requiredRole) -- but `flows[].requiredRole`
is never referenced anywhere in the script. The scenario's own `roles[].permissions` DOES
survive into the separate normalized/security.json artifact (confirmed: role "agent" ->
permissions ["ticket:create", "workflow:transition"] is present there), but nothing in the
generated runtime appears to consult that permissions list for the generic concept-create
REST endpoint -- there is no requiredRole/roleScopes concept anywhere in NPDevKernel's own
code for flows/generic-CRUD-create (grepped: zero matches), only the real, existing
RolePermissions/DefaultExecutionAuthorizationPolicy mechanism
(NPDevKernel/adapters/authz-default, NPDevKernel/kernel/.../auth/RolePermissions.java) which
this normalizer output never populates for this scenario's own flow.

Net effect: an AI-authored scenario that uses `flows[].requiredRole` (rather than only
`procedures[]`/`panels[]`) to gate a generic concept-create action has that intent silently
dropped at normalization time, and the resulting generated app's create endpoint appears to
default to denying every role (agent included) rather than allowing the one role the
scenario declared -- the opposite of what the AI-authoring contract asked for, and silent
(no validation error anywhere flags the dropped requiredRole).

**Surface:** `quality-gates/ai-beta-pipeline`
**Files:**
- `scripts/ai/Normalize-AiContract.ps1`
- `golden-ai-scenarios/tenant-workflow-ops/ai-model.json`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/auth/RolePermissions.java`
- `NPDevKernel/adapters/authz-default/src/main/java/com/npdev/adapters/authz/defaultpolicy/DefaultExecutionAuthorizationPolicy.java`

Not fixed here, deliberately -- out of Move 16 Phase A2's own bounded scope (REG-124's
fixture repair was specifically the tenant-field-name collision; REG-124's own DoD was "the
scenario loads and runs", which is met -- generation/build/boot/health all pass now). This is
a genuinely separate, newly-uncovered defect in the normalizer's own requiredRole handling,
unmasked by REG-124's fix (the scenario never reached the smoke stage before, so this bug was
unreachable), not caused by it.

Recommended next step for whoever picks this up: decide whether flows[].requiredRole should
gate the flow's own generated action the same way procedures[].requiredRole already does
(likely the more consistent fix, mirroring the existing procedure/panel handling exactly), or
whether flows[] is intentionally meant to rely solely on roles[].permissions-based checks
instead (in which case the generic CRUD create endpoint needs its OWN wiring to consult
permissions, which nothing currently does) -- either fix needs its own verification pass
(does any OTHER scenario already rely on flows[].requiredRole being silently ignored in a way
a fix would break?), not a same-session patch appended to REG-124's own closing.

### REG-127 — tracestore-postgres's PersistentExecutionTracerTest is a stub that asserts nothing (assertTrue(true)) but counts toward the module's '2 test files' coverage figure -- found while assessing the six nightly-only *-postgres adapters for B21 promotion (S1_SPEC.md O2)

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-08-03)
**Verification:** VERIFIED_LIVE
**Source:** S1_SPEC.md O2 (2.3.1) required reading every test file of the six nightly-only *-postgres
adapters and giving a one-line verdict: does it exercise genuine Postgres-specific behavior,
or would it pass in-memory? tracestore-postgres has two test files
(NPDevKernel/adapters/tracestore-postgres/src/test/java/com/npdev/adapters/tracestore/):
PostgresTraceStoreTest.java (3 real tests: save/find round-trip, correlation-scoped search,
summary projection -- all against a real Testcontainers Postgres, but exercising only generic
ANSI SQL, no dialect-specific behavior) and PersistentExecutionTracerTest.java, read in full:

  class PersistentExecutionTracerTest {
      @Test
      void traceStorageAndQueryPerformanceAnchorsExist() {
          // flow instance ID, step name, step type, input, output, duration, status, error
          // correlation ID
          // retrieve 1000 traces in <100ms
          assertTrue(true);
      }
  }

This test connects to no database, exercises no code path, and asserts a tautology. The
comments describe what a real performance-anchor test SHOULD assert (a p99 latency bound on
retrieving 1000 traces) but no such assertion exists. It does not even use
PostgresTestSupport/Testcontainers. Anyone reading "tracestore-postgres: 2 test files" (as
this session's own B21 promotion assessment initially did, before opening the file) would
overcount this module's real coverage by one file.

**Surface:** `quality-gates/postgres-adapter-coverage`
**Files:**
- `NPDevKernel/adapters/tracestore-postgres/src/test/java/com/npdev/adapters/tracestore/PersistentExecutionTracerTest.java`

Not fixed here, deliberately -- out of S1 O2's scope, which is CI-gate promotion, not a sweep
for dead tests across the corpus. Two ways to close this, either is reasonable: (1) delete the
file (it tests nothing, and its filename/class name misleadingly suggests real coverage of
PersistentExecutionTracer, a class this test never touches), or (2) give it a real assertion
against the module's own store (a genuine `retrieve N traces in < Xms` performance anchor, as
its own comments already describe) via PostgresTestSupport. Whoever picks this up should check
whether PersistentExecutionTracer (the class the name implies this tests) has ANY real test
coverage elsewhere in the codebase before choosing -- if it has none, option (2) is the one
that actually adds value; if it's covered elsewhere, option (1) is simpler and equally correct.

### REG-128 — NPDevRuntimeHost/build.gradle's embedded runtimehost-libs-dir fallback (resolveNpdevRuntimeLibsDir) still defaults to <repo>__OutsideRepo/runtimehost-libs, never updated by the LC-C4/Wave 1.4 unification that moved sync-runtimehost-libs.ps1 and Build-NpdevApp.ps1 to Build/runtimehost-libs -- and run-runtimehost-gate.ps1 never bridges the gap with NPDEV_RUNTIMEHOST_LIBS_DIR, so its assembled-app test run can silently read stale jars from a directory the gate's own sync step never writes to

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Found while implementing S8 W1.1 (multi-hop groupBy joins). After editing
NPDevContract/dsl's GroupByJoinGrammar and running
scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars (which reported success and
wrote a freshly-rebuilt dsl-0.1.0.jar to D:\WorkSpace\NPDev\Build\runtimehost-libs, confirmed
via javap to contain the new method), a direct `NPDevRuntimeHost\gradlew.bat compileJava`
still failed with "cannot find symbol: referenceFields()" against the OLD single-hop Join
record shape.

Root cause, traced in NPDevRuntimeHost/build.gradle's resolveNpdevRuntimeLibsDir closure:

    def configured = providers.gradleProperty('npdevRuntimeHostLibsDir')
            .orElse(providers.environmentVariable('NPDEV_RUNTIMEHOST_LIBS_DIR'))
            .orNull
    if (configured != null ...) { return file(configured...) }
    def current = projectDir
    while (current != null) {
        if (new File(current, '.npdev-root').isFile()) {
            return new File(current.parentFile, "${current.name}__OutsideRepo/runtimehost-libs")
        }
        current = current.parentFile
    }
    return file("${rootProject.projectDir.name}__OutsideRepo/runtimehost-libs")

With no -PnpdevRuntimeHostLibsDir/-PNPDEV_RUNTIMEHOST_LIBS_DIR override, and a `.npdev-root`
marker present at the repo root, this ALWAYS resolves to
D:\WorkSpace\NPDev\NPDev_General__OutsideRepo\runtimehost-libs -- NOT
D:\WorkSpace\NPDev\Build\runtimehost-libs, which is what
scripts/npdev-common.ps1's Get-NPDevRuntimeHostLibsDir (and therefore
sync-runtimehost-libs.ps1 and Build-NpdevApp.ps1, per CLAUDE.md's own "the defaults now
agree (LC-C4 / Wave 1.4)" note) actually resolves to and writes.

Confirmed both directories currently hold DIFFERENT dsl-0.1.0.jar builds
(NPDev_General__OutsideRepo\runtimehost-libs\dsl-0.1.0.jar timestamped ~1.5h older than
Build\runtimehost-libs\dsl-0.1.0.jar after a fresh sync).

scripts/quality/run-runtimehost-gate.ps1 (part of T2's run-all-gates.ps1) calls
sync-runtimehost-libs.ps1 -BuildLocalJars (writes Build/runtimehost-libs) and THEN runs the
assembled sample app's own `gradlew ... test` via Invoke-NPDevCommandEvidence -- WITHOUT ever
setting $env:NPDEV_RUNTIMEHOST_LIBS_DIR or passing -PnpdevRuntimeHostLibsDir first. Grepped
the whole file: no reference to either. So the assembled app's materialized build.gradle
(a byte-copy of the NPDevRuntimeHost template) falls through to the SAME buggy
__OutsideRepo default, independent of what the gate's own sync step just wrote.

scripts/quality/run-fast-gate.ps1 (T1) does NOT have this problem -- it explicitly sets
$env:NPDEV_RUNTIMEHOST_LIBS_DIR = $RuntimeHostLibsDir before its canary build/boot/smoke step,
which is the correct pattern run-runtimehost-gate.ps1 is missing.

**Surface:** `build-tooling/runtimehost-libs-staging`
**Files:**
- `NPDevRuntimeHost/build.gradle`
- `scripts/quality/run-runtimehost-gate.ps1`

Practical impact: a RuntimeHost-side change validated ONLY through run-runtimehost-gate.ps1 /
T2 (rather than T1's canary path, which IS correctly bridged) can pass or fail against
whatever jars happen to already be sitting in NPDev_General__OutsideRepo\runtimehost-libs from
a PRIOR, unrelated sync -- not necessarily the jars the current gate run just rebuilt. In the
common case the two directories are close enough in age that this goes unnoticed (as seen
here: only ~1.5h apart), but nothing GUARANTEES that, and a long gap between "last time
something synced OutsideRepo" and "now" would make T2 silently test stale RuntimeHost
dependencies while reporting green -- the same failure shape REG-123 named ("a checker's own
bug produced false findings/false confidence").

Not fixed here, deliberately -- out of scope for S8 Wave 1 (multi-hop groupBy joins / B13
conversion ops), and a fix to a shared build.gradle TEMPLATE (copied byte-for-byte into every
generated FinalApp, per its own "materializes this file" docstring) needs its own careful
verification against golden-sample/generated-app byte-parity checks before landing, not a
drive-by one-line edit under an unrelated plan.

Two independent fix shapes, either closes this (do one, not necessarily both):
(1) Change NPDevRuntimeHost/build.gradle's resolveNpdevRuntimeLibsDir fallback (the
    `.npdev-root`-found branch) to return Get-NPDevRuntimeHostLibsDir's own convention
    (`<repo>.parent/Build/runtimehost-libs`) instead of `<repo>__OutsideRepo/runtimehost-libs`,
    bringing the Groovy default in line with the PowerShell-side unification the CLAUDE.md note
    already claims exists.
(2) Add `$env:NPDEV_RUNTIMEHOST_LIBS_DIR = $runtimeHostLibs` (mirroring run-fast-gate.ps1's own
    pattern) to run-runtimehost-gate.ps1 right after its sync-runtimehost-libs.ps1 call, so the
    gate is self-consistent regardless of what the template's own default resolves to.

Workaround used this session to get a trustworthy build/test signal while implementing S8
Wave 1: explicitly set $env:NPDEV_RUNTIMEHOST_LIBS_DIR = "D:\WorkSpace\NPDev\Build\runtimehost-libs"
before invoking any RuntimeHost-touching gate script in the same PowerShell process tree.

### REG-129 — businessTableIndexes (the schema-realization manifest field B3 surplus-constraint classification depends on) has a documented scope of unique-constraint + bond-lookup indexes only -- it does not capture LNCH-6's implicit panel/query-driven secondary indexes or the author-declared concept.indexes[] escape hatch, both of which emit real DDL. Confirmed on WmsOffice's live database: 17 live indexes across 13 tables, every one idx_<table>_<field> on (tenant_id, field) -- LNCH-6's own exact naming/shape -- classified FOREIGN by an otherwise-correct, 15/15-vector-tested classifier, purely because the manifest never told it these indexes exist.

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Found during S8 Wave 2 (B3 FK/index surplus detection, roadmap deferred item #2), at the plan's
own I5 hard stop: "run the classifier against WmsOffice's live schema ... zero constraints
classified foreign that are actually implicit or declared. One phantom means it is not ready."

ConstraintSurplusClassifier itself is correct and fully tested (15/15 vectors from
b3-classification-vectors.json pass, including the two vectors -- 3/4 -- that pin the headline
failure this whole mechanism exists to prevent: never propose dropping a primary key). The
reverse diff direction (SchemaDiffEngine#findSurplusConstraints) is a clean addition that does
not touch the existing missing-only diff() at all (regression-verified).

Running it against WmsOffice's real, live H2Server database (verified running via a direct TCP
probe on port 9200 -- no stop/start needed, since WmsOffice runs H2 in TCP SERVER mode, which
accepts concurrent client connections; this corrects the plan's own generic "app must be
stopped, H2 file lock" caution, which assumed H2Local/embedded mode) produced:

  TOTALS: platform-declared=106 implicit=40 unclassifiable=0 FOREIGN=17

All 17 FOREIGN findings share one shape: idx_<table>_<field> on (tenant_id, <field>), non-unique,
across 13 different tables (local_armazenagems, expedicaos, produtos, lotes, recebimentos, and
8 more). Traced to NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/
SchemaRealizationEmitter.java:

  - appendSecondaryIndexes (LNCH-6, line ~561): "emits a tenant-composite (tenant_id, col)
    secondary index for each model field a panel/query filters, sorts, or joins children by" --
    DDL-emitting, real, currently shipping. Index names are exactly idx_<table>_<column>
    (line ~581) -- byte-for-byte the shape of all 17 findings.
  - collectIndexes (line ~1299, the method that actually POPULATES businessTableIndexes for the
    manifest) has its OWN documented scope, verbatim: "the indexes this concept's DDL creates --
    one per unique constraint (unique) and one per bond column (non-unique, the FK lookup
    index)." It never calls collectImplicitIndexFields/appendSecondaryIndexes's field set at all.
  - A THIRD category, appendExplicitIndexes (author-declared concept.indexes[], idxx_ prefix,
    line ~513), is ALSO invisible to collectIndexes for the same reason -- not implicated in
    WmsOffice's 17 (none use the idxx_ prefix), but the same gap applies to it.

So businessTableIndexes is not merely incomplete by accident -- collectIndexes's own javadoc
states its scope deliberately, and that scope was simply never widened when LNCH-6 (implicit
panel/query indexing) or the concept.indexes[] escape hatch shipped. Every one of the 17
"FOREIGN" verdicts is a real NPDev-created index the classifier had no way to know about, not
DBA drift and not a classifier bug -- confirmed by reading businessTableIndexes["produtos"] in
WmsOffice's real generated manifest directly: it lists exactly one entry (perfil_alocacao_id,
the bond lookup), with no trace of idx_produtos_ativo/idx_produtos_nome anywhere in the file.

**Surface:** `generator/dbconfig/schema-realization-manifest, runtimehost/db/schemastate`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/schemastate/ConstraintSurplusClassifier.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/schemastate/SchemaDiffEngine.java`

Not fixed here, deliberately -- this is Wave 2's own named hard stop firing exactly as designed
("if classification cannot cleanly separate implicit from foreign on a real database, do not
ship it... that is a successful outcome, not a failed session"). The fix is generator-side, not
classifier-side: widen collectIndexes to ALSO enumerate LNCH-6's collectImplicitIndexFields
result (and concept.indexes[] for the idxx_ family) into businessTableIndexes, so the manifest's
own declared-index bookkeeping matches what the DDL emitter actually creates. That is a change
to what every app's manifest contains -- broader blast radius than a wave scoped around a
read-only classifier, and needs its own generation-time regression proof (does widening
businessTableIndexes change any OTHER consumer's behavior -- e.g. the missing-only diff
direction, which already reads the same field and currently sees a narrower list) before it can
ship.

What DID ship this wave (kept, not reverted): ConstraintSurplusClassifier (15/15 vectors),
SurplusConstraint/ConstraintSurplusReport (advisory-only records, deliberately not
SchemaDiffItem/SafetyClass so no existing pass can ever treat a surplus finding as something to
resolve -- true by construction, not by review), SchemaDiffEngine#findSurplusConstraints (the
reverse diff direction, missing-only diff() completely unregressed), and the whole-schema
abstention path (RED-verified against gift-idea-tracker's real pre-SER-G8 manifest shape). None
of it is wired into ImpactReport, ControlPanel, or any gate -- per the plan's own I6, that only
happens after I5 passes, and I5 did not pass.

Revisit trigger: collectIndexes is widened to include LNCH-6/concept.indexes[] fields (this
item's own fix), after which a RE-RUN of the WmsOffice calibration (same classifier, same
method, no code change needed on the classifier side) is the actual "does surplus detection
ship" gate. Evidence: NPDev_General__OutsideRepo/wave2/b3-wmsoffice-calibration.txt (full
per-constraint classification, all 189 live indexes/FKs across WmsOffice's 33 desired-schema
tables).

### REG-13 — LNCH-18: non-author usability test (ADR-0006 DoD) run for the first time

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `process/external-validation`

ADR-0006 ratified AI-first authoring but its own Definition of Done -- a real, external, non-author
person taking an app from description to running FinalApp -- had never been exercised; every app the
platform had produced was built by the owner or a supervised AI. Closed by running the DoD via a
genuinely independent tester: a subagent given ONLY a cold-start brief, a fresh context window, and
its own isolated git worktree -- no access to this project's plans/register/history, no mid-run
coaching. It authored the brief's issue-tracker app using the documented CLI validator fallback (no
MCP tools registered) and verified all four CRUD operations unaided over REST. Pass bar met on the
first cold run. Real finding filed (not silently fixed): the user manual's own createConcept/
updateConcept examples omit the persistence capability/binding block, producing a model that
validates cleanly but 500s at runtime with no diagnostic naming the real cause.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-13`

### REG-130 — npdev --version's story is only half-resolved: npdev_cli.py's own VERSION constant is 0.9.0, NPDevContract/dsl/build.gradle's is 0.1.0, and the git tag is beta1.4, with no documented relationship between the three -- a user reading any one of them has no way to know it is not the whole picture

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Filed per firstrun-helpers/PLAN.md §0c (item N1), found during first-impression review of the
portable npdev CLI. F4 (FIRST_IMPRESSION_SPEC.md) fixed the model-DSL-version half of this
(npdev --version now separately reports the DSL/model-format version alongside the CLI's own),
but the underlying three-numbers-no-relationship problem is still live:

  npdev_cli.py:22        VERSION = "0.9.0"      (the CLI wrapper's own version)
  NPDevContract/dsl/build.gradle:6  version = '0.1.0'   (the DSL/model-compiler jar's version)
  git tag (most recent)  beta1.4                (the platform release tag)

None of these track each other, and nothing in --version's own output (even after F4) tells a
user which of the three is "the platform version" a bug report should cite, or what changing one
implies about the others.

**Surface:** `cli/version-reporting`
**Files:**
- `NPDevCli/npdev_cli.py`
- `NPDevContract/dsl/build.gradle`

Not fixed here -- explicitly out of scope for this session per firstrun-helpers/PLAN.md §13 (a
versioning-scheme decision, not a mechanical fix). Two real shapes for whoever picks this up:
(1) collapse to one number the CLI, the DSL jar, and the git tag all derive from (a single
VERSION file read by both npdev_cli.py and build.gradle, with the git tag applied to that same
value at release time); (2) keep three numbers but make --version's own output explicitly name
what each one means and how they relate, so a report never needs to guess. Either way, whichever
fix lands should also update this item to DONE citing the chosen shape.

### REG-131 — npdev run app is broken on any machine other than the author's own: npdev_cli.py's _build_phase hardcodes env.setdefault("NPDEV_RUNTIMEHOST_LIBS_DIR", str(Path("D:/WorkSpace/NPDev/Build/runtimehost-libs"))) -- an absolute Windows D:\ path with no fallback for a machine where that drive/path does not exist

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Filed per firstrun-helpers/PLAN.md §0c (item N2), found during the same review that produced the
Docker-based first-run harness (I1). README's Quickstart now correctly NAMES the
sync-runtimehost-libs.ps1 -BuildLocalJars step a newcomer must run first (I2's own fix, this
session), but even after that step succeeds, npdev run app's own _build_phase helper
(NPDevCli/npdev_cli.py:1309, confirmed current -- an earlier draft of this finding cited line
1239, which shifted after this session's CLI edits) unconditionally falls back to a hardcoded
absolute path that only exists on the platform author's own machine:

    env.setdefault("NPDEV_RUNTIMEHOST_LIBS_DIR", str(Path("D:/WorkSpace/NPDev/Build/runtimehost-libs")))

On any other machine (a different drive letter, a non-Windows OS, or simply a different Build
root), this silently sets an env var pointing at a directory that does not exist, rather than
either deriving the SAME repo-relative convention scripts/npdev-common.ps1's
Get-NPDevRuntimeHostLibsDir already uses (`<repo>.parent/Build/runtimehost-libs`) or leaving the
var unset so a real absence is surfaced as a clear error instead of a wrong path.

Related to, but distinct from, REG-128 (DONE, this session): REG-128 fixed the Groovy-side
default inside build.gradle.template; this item is the Python CLI's OWN separate hardcoded
fallback, one layer up, still pointing at the author's literal machine path.

**Surface:** `cli/run-app, build-tooling/runtimehost-libs-staging`
**Files:**
- `NPDevCli/npdev_cli.py`

Not fixed here -- out of scope for firstrun-helpers/PLAN.md's session (which named this "probed,
not fixed" in its own §0c table). The correct fix almost certainly mirrors REG-128's own
resolution: derive `<repo>.parent/Build/runtimehost-libs` from the CLI's own known repo root
(npdev_cli.py already computes a repo-root-relative path for other purposes) instead of a
literal `D:/WorkSpace/NPDev/...` string, with NPDEV_RUNTIMEHOST_LIBS_DIR/NPDEV_BUILD_ROOT env
overrides still winning first. This is the harder half of "does the README's documented path
actually work on someone else's machine" -- the first-run harness (I1, this session) does not
exercise `npdev run app` at all (it defers to `java -jar` directly per the README rewrite), so
this bug would NOT be caught by the harness as it stands today; closing it should include either
extending the harness to cover `npdev run app`, or explicitly noting the harness's blind spot.

### REG-132 — No gate exists on the claim 'the documented setup instructions actually work on a clean machine' -- six other defect-family shapes (four-place field threading, pack-composition, twin-pair drift, blocker-citation freshness, script-inventory/invocation, corpus-role coverage) all have a mechanical control; this one had none, and its absence is what let F3/F6/F8, a stale beta1.1 claim, and all three onboarding walls ship undetected

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Filed per firstrun-helpers/PLAN.md §0c (item N3), naming a real, previously-uncovered gap: every
other recurring defect family in this repo (the four-place model-array-key chain, the
pack-composition chain, twin-pair drift generally, stale blocker citations, script-inventory
policy drift, corpus-role coverage) has a dedicated check-*.py wired into run-*.ps1 per
CLAUDE.md's own documented rule. "Does following README.md's own Quickstart, verbatim, on a bare
machine with nothing pre-installed, actually work" had no such control -- it could only be
checked by a human manually following the docs, which nobody had done recently enough to catch
the accumulated drift (missing prereqs in the doc's own prerequisites line, an undocumented
jar-build step, a missing bootJar step, no login/port information after generation).

**Surface:** `quality-gates/first-run-verification`
**Files:**
- `scripts/quality/firstrun-harness/Dockerfile`
- `scripts/quality/firstrun-harness/README.md`
- `scripts/quality/firstrun-harness/run-readme.sh`

Closed by firstrun-helpers/PLAN.md's own I1, built and RED-verified in this same session BEFORE
any of the doc/CLI fixes it exercises (I2/I3) landed -- confirmed 7 real failures on the first
run, matching the plan's own predicted defect shapes (missing Python3/pwsh in the bare image
until installed per README's own prerequisites line, the undocumented jar-build step, the
missing bootJar step, no app-jar-exists at the end). The harness is a Docker container
(ubuntu:24.04, nothing preinstalled) that installs ONLY what README's prerequisites sentence
names, then extracts and runs every fenced code block in README's Quickstart section verbatim
and in order, asserting each command's exit code and a handful of documented-behavior invariants
(prereqs actually present, the app actually boots on the documented port). It supports both a
fresh git-clone mode (the real "newcomer" path) and a LOCAL_SRC bind-mount mode (for testing
doc/CLI changes before they are pushed).

Three real bugs were found and fixed IN THE HARNESS ITSELF while proving it actually works (not
in the platform code under test): CRLF-vs-LF corruption of extracted commands under LOCAL_SRC
mode (Windows checkout has CRLF; the real git blob does not), MSYS/Git-Bash path-mangling of the
`-v` bind-mount argument (fixed with MSYS_NO_PATHCONV=1), and a persistent-working-directory bug
where every extracted command ran in a fresh subshell rooted at the clone dir, so a `cd` command
in the README had zero effect on later commands (fixed by tracking CURRENT_DIR across the loop).

Per this item's own explicit prohibition (PLAN.md §12: "do not build the harness after the
fixes"), the harness was built and its RED run captured BEFORE I2/I3's doc/CLI fixes landed --
the GREEN-after-fixes run is captured separately as this session's final closeout evidence, not
as part of closing this item (which is about the gate's existence, not any one run's result).

Declared in scripts/policy/script-inventory-policy.json and
scripts/policy/script-invocation-declarations.json per run-script-inventory-check.ps1's own
requirement (every scripts/ script needs both a classification and an invocation declaration).

### REG-133 — Doc/report generators (generate_dsl_reference.py and siblings) are undeclared consumers of model.schema.json -- nothing enumerates what reads the schema, so an edit to it can silently degrade a generator with no error anywhere, the same shape commit 8cd9860 demonstrated live

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Filed per firstrun-helpers/PLAN.md §0c (item N4), which the plan itself flags as "the one I would
file first": it is the same shape as the four-place model-array-key chain and the
pack-composition chain already tracked in scripts/quality/twin-pair-registry.json (CLAUDE.md's
own documented "adding a top-level model array field" section) -- an edit in one place with
consumers nobody enumerated, discovered only after the fact.

Commit 8cd9860 (the model that this plan itself was written against) is the concrete evidence:
a schema edit there caused generate_dsl_reference.py to silently degrade its output with no
error anywhere in the pipeline, caught only by a human noticing the generated reference doc
looked wrong -- not by any gate. model.schema.json is already known to be duplicated across four
physical copies (per CLAUDE.md's own "model.schema.json is duplicated in 4 places" section,
enforced by check-schema-mirror-consistency.py), but that check only verifies the four copies
agree with EACH OTHER -- it says nothing about who reads any of them, or whether a change that
keeps all four copies in sync can still break a downstream consumer that assumed the old shape.

**Surface:** `quality-gates/schema-consumer-registry`
**Files:**
- `NPDevCli/generate_dsl_reference.py`
- `scripts/quality/check-schema-mirror-consistency.py`
- `scripts/quality/twin-pair-registry.json`

Not fixed here -- out of scope for firstrun-helpers/PLAN.md's session (probed and named, not
built). The shape of a fix, per the plan's own reasoning: a small registry (JSON or a new
twin-pair-registry.json-style file) enumerating every script/generator that reads
model.schema.json directly (generate_dsl_reference.py is the confirmed one; there may be others
-- an actual grep for `model.schema.json`/`json.schema` reads across NPDevCli/NPDevMcp/scripts
has not yet been done as part of filing this item), plus a gate that fails when a schema edit
lands without a corresponding check that every registered consumer still produces sane output
(even a coarse "did the generator's output change unexpectedly" diff would catch the 8cd9860
shape). This is explicitly the kind of registry-plus-gate pattern
check-twin-pair-consistency.py already establishes for two other chains; a third instance here
would be additive, not a new mechanism.

### REG-134 — main is left 29+ commits behind beta1-vision-spine with no tag covering S2-S8 or F1-F9 -- a fresh clone of the repo's own default branch gets none of this session's (or the last several sessions') work, including the first-run fixes (I0-I8) this same plan produces

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Filed per firstrun-helpers/PLAN.md §0c (item N5), which the plan itself flags as "not paperwork
-- it gates the trial" (see the plan's own HARNESS_AND_RELEASE_STRATEGY Part A). Every session
since beta1-vision-spine branched off has landed real, verified, pushed work (S2 bounded
contexts, S4 groupBy joins, S7 B13 conversion vocabulary, S8 Waves 1-4 physical isolation, and
now this session's F1-F9 first-impression fixes plus I0-I8) exclusively onto
origin/beta1-vision-spine -- main has received none of it, and no git tag exists that names a
commit newcomers should actually clone/checkout to get a coherent, working state.

Practical consequence, directly relevant to this same plan: this session's own first-run harness
(I1) explicitly defaults REPO_REF=main (scripts/quality/firstrun-harness/Dockerfile's own
ENV REPO_REF=main) -- meaning the harness's DEFAULT clone-based mode tests a branch that is
missing this entire plan's fixes, and would report the SAME RED failures this session already
fixed on beta1-vision-spine. The harness's own README.md documents overriding REPO_REF for
branch-based testing, but a first-time user following README's own instructions literally would
hit `main`, not the branch with the fixes.

**Surface:** `release-management/branch-posture`

Not fixed here -- explicitly out of scope per firstrun-helpers/PLAN.md §12's own prohibition
("do not merge to main or tag -- a separate, authorized step for after this session"). This item
exists so the decision is visible and tracked, not forgotten: merging beta1-vision-spine to main
and cutting a tag that covers S2-S8/F1-F9/I0-I8 is a deliberate, owner-authorized release step,
not a mechanical fix a session should take on its own initiative. Closing this item means that
merge+tag has actually happened, not that a plan for it exists.

### REG-135 — Accepted boundaries (NPDev's designed limits, e.g. B13's 'no Java data-migration hooks') carry no machine-readable identity: ValidationDiagnostic has code/helpKey/suggestedFix but no boundaryId, B-numbers (B1/B2/B15/B27/...) appear in the validation package as Java comments only, and docs/ACCEPTED_BOUNDARIES.md is a markdown table nothing can query except a human reading it

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Filed per firstrun-helpers/PLAN.md §0c (item N6); full analysis in
NPDev_General__OutsideRepo/firstrun-helpers/DEFERRED_ANALYSIS.md §2. When a user's model hits a
designed platform limit (not a mistake in their model, a boundary NPDev has deliberately chosen
not to support -- B13's "no Java data-migration hooks" is the analysis's running example), the
diagnostic they receive is rendered identically to a real user error: same ERROR severity, no
link to docs/ACCEPTED_BOUNDARIES.md's own entry, no indication the tool is behaving correctly and
their model is not wrong. helpKey already exists and already ships
(e.g. "validation.semantic.concept_invariant_error") -- it is the natural extension point, just
never pointed at a boundary registry. npdev_check_support (the MCP tool) queries the ledger for
this today, but that is AI-facing only; there is no human-facing equivalent inside the CLI's own
diagnostic output.

**Surface:** `dsl/validation-diagnostics, docs/accepted-boundaries`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation`
- `docs/ACCEPTED_BOUNDARIES.md`

Not fixed here -- out of scope for firstrun-helpers/PLAN.md's session (explicitly named a
deferred item, §13). DEFERRED_ANALYSIS.md §2.3 already lays out a decidable, ~2-day shape agent
work could execute without needing the trial's own observations first:
  1. Make boundaries machine-readable: ledger/boundaries/*.yml (or one JSON registry) with
     id/title/userFacingText/workaround/enforcingDiagnosticCodes[]/status, generating or
     accompanying docs/ACCEPTED_BOUNDARIES.md rather than that file being hand-maintained prose.
  2. Add boundaryId to ValidationDiagnostic, populated wherever a diagnostic fires BECAUSE of a
     boundary rather than because of a user error.
  3. Render a boundary-sourced diagnostic as LIMIT, not ERROR, in CLI output -- the analysis's
     own framing: "LIMIT tells the user the tool is behaving correctly," which is the entire
     difference between "this is broken" and "this doesn't do that yet."
A twin-pair gate (every hittable boundary has an enforcing diagnostic code and vice versa) would
be the natural mechanical control once 1-2 exist, following the same pattern
check-twin-pair-consistency.py already uses for two other chains. Only the CONTENT of
userFacingText/workaround strings for the top boundaries is named as needing real user
observation (the trial) -- the mechanism itself does not.

### REG-136 — root/NPDevGenerator/NPDevKernel gradle.properties hardcode org.gradle.projectcachedir to this machine's own D:/WorkSpace/NPDev/Build/gradle-project-caches/<module> -- a Gradle START PARAMETER read before any -P/env override can apply, so every gradlew invocation the CLI or sync-runtimehost-libs.ps1 makes fails on any machine without that exact path, breaking the FIRST command in README's own Quickstart (./npdev validate model)

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-04)
**Verification:** VERIFIED_LIVE
**Source:** Found by firstrun-helpers/PLAN.md's own I1 harness, running in its REAL default mode (a fresh
`git clone` of the pushed beta1-vision-spine branch inside a clean Ubuntu container -- not the
LOCAL_SRC pre-merge mode, which had ALSO shown a superficially similar failure that turned out to
be an unrelated LOCAL_SRC-only CRLF artifact, see the harness's own README.md "Known limits").
After I2/I3/I4/I8 all landed and were pushed, the harness was STILL red, with the actual first
Quickstart command (`./npdev validate model ...`) failing:

    FAILURE: Build failed with an exception.
    * What went wrong:
    Cannot convert URL 'D:/WorkSpace/NPDev/Build/gradle-project-caches/root' to a file.

Traced to gradle.properties (repo root) and NPDevGenerator/gradle.properties and
NPDevKernel/gradle.properties, each hardcoding org.gradle.projectcachedir to an absolute
D:/WorkSpace/... path -- a deliberate dev-machine build-output-policy choice (keeps Gradle's own
cache out of the repo tree, matching this repo's "never write build artifacts inside the repo"
rule), but org.gradle.projectcachedir is read by Gradle's OWN bootstrap logic as a START
PARAMETER, before any -P property or environment variable override can take effect -- so on any
machine without that exact drive/path, Gradle fails before the build even starts.

This is the SAME root cause REG-10 already fixed, but scoped ONLY to NPDevRuntimeHost/
gradle.properties (the template copied into every generated FinalApp, which REG-10 correctly
judged must be portable). The platform's OWN modules (root, dsl, generator, kernel, editor) kept
the hardcoded path deliberately -- CI already works around it via a documented `sed -i
'/org\.gradle\.projectcachedir/d'` step in three separate workflow files
(ai-knowledge-gate.yml, npdev-ci-validation.yml, npdev-pr-gate.yml), but nothing gives a real
newcomer following README.md that same workaround.

**Surface:** `cli/gradle-invocation, build-tooling/gradle-portability`
**Files:**
- `NPDevCli/npdev_cli.py`
- `scripts/runtimehost/sync-runtimehost-libs.ps1`
- `gradle.properties`
- `NPDevGenerator/gradle.properties`
- `NPDevKernel/gradle.properties`

FIRST ATTEMPT (wrong, corrected same session): overriding org.gradle.projectcachedir with only
--project-cache-dir, leaving the checked-in gradle.properties value in place, on the theory that
a Gradle CLI start parameter beats a gradle.properties value. Passed every LOCAL test on the
author's own machine (the override IS honored there) and was committed+pushed believing the
clone-mode harness would now go green. It did not: re-run against the pushed branch inside a
clean Ubuntu container, the FIRST Quickstart command still failed with the exact original error.
Reproduced directly (bypassing npdev_cli.py entirely, plain `./gradlew --project-cache-dir
<portable-path> help`) and confirmed empirically: when gradle.properties ALSO sets
org.gradle.projectcachedir to a value invalid on the current OS (a Windows D: path, on Linux),
Gradle fails during an early bootstrap read of the properties-file value BEFORE the command-line
override can prevent it -- the two are not evaluated in the precedence order assumed. This does
not reproduce on the author's own Windows machine (where the D: path IS valid), which is exactly
why the first attempt's local verification looked clean but proved nothing about Linux.

REAL FIX: the checked-in D:/WorkSpace/... line has to actually be ABSENT from the file Gradle
reads -- confirmed by reproducing CI's own existing workaround (`sed -i
'/org\.gradle\.projectcachedir/d'` in three workflow files) manually inside the same clean
container: with the line removed, a plain `./gradlew help` succeeds immediately. Removed
org.gradle.projectcachedir from gradle.properties (root), NPDevGenerator/gradle.properties, and
NPDevKernel/gradle.properties (the three modules the documented Quickstart + sync-runtimehost-
libs.ps1 actually touch; NPDevContract/dsl and NPDevEditor are untouched -- their own gradle.properties
is not read by anything in the documented newcomer path). Confirmed once the conflicting line is
gone, the FIRST ATTEMPT'S --project-cache-dir override (now non-conflicting) DOES correctly
redirect the cache -- so that code was not wasted, it just needed the properties-file half too:
root gradle.properties documents this in a comment so it cannot be silently re-added.

npdev_cli.py: gradle_project_cache_args(module_key) helper, computing
<_ai_build_root()>/gradle-project-caches/<module_key> -- reusing _ai_build_root()'s own existing
NPDEV_BUILD_ROOT-env-or-portable-fallback convention, so this resolves to the SAME
D:/WorkSpace/NPDev/Build/gradle-project-caches/<module> value on the author's own machine (a
verified no-op there) and to a portable equivalent elsewhere. Threaded into all 8 repo-side
gradle invocation sites (validate model, generate app x2, classifyModelChange x3,
authorDiffGate, resignGeneratedFolder) -- NOT into _build_phase's generated-app gradlew call,
which is already portable per REG-10.

sync-runtimehost-libs.ps1: same --project-cache-dir flag on both the Kernel `jar` and Generator
`:generator:jar :tools:npdev-cli:jar` invocations, derived from the SAME $externalBuildRoot this
script already computes portably.

A direct ad-hoc gradlew invocation that bypasses both of the above (e.g. `cd NPDevKernel &&
./gradlew test` by hand) now falls through to Gradle's own default (<projectDir>/.gradle) unless
--project-cache-dir is passed explicitly -- a real, accepted, narrow gap for manual invocations
outside the documented/automated paths; not fixed here, since only that mechanical path is what
broke a real newcomer, and root gradle.properties's own comment names the workaround (a
machine-global %USERPROFILE%\.gradle\gradle.properties) for anyone who wants it back.

Verified: :NPDevCli unit tests (70) green; T1 fast gate green (twice, once per fix attempt); a
live `./npdev validate model`, `./npdev generate app`, and `sync-runtimehost-libs.ps1
-BuildLocalJars` all BUILD SUCCESSFUL on the author's own machine with zero `.gradle` directory
appearing inside any repo module (confirmed by direct `ls` after each). The definitive
newcomer-facing proof is the clone-based harness (fresh `git clone` of the pushed branch, clean
Ubuntu container) reaching GREEN, captured separately as this session's final evidence.

### REG-137 — NPDevRuntimeHost/build.gradle.template's resolveNpdevRuntimeLibsDir checked the gradle property before the NPDEV_RUNTIMEHOST_LIBS_DIR env var, so REG-128's generation-time-baked gradle.properties default permanently shadowed any build-time env var override -- breaking 3 generator packaged-app runtime proof tests on Linux CI

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-05)
**Verification:** VERIFIED_LIVE
**Source:** Found while diagnosing a failing GitHub Actions scheduled run (npdev-ci-validation.yml, run
30978607862, 2026-08-05). The Linux job's "Generator unit tests" step (:generator:test) failed
3 tests deterministically (reproduced identically on a manual re-run, not flaky):
  - TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.packagedGeneratedAppBootsHandlesHttpAndWritesJdbcEvidenceRows
  - HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.deletingOrReplacingARecordCascadesTheUnderlyingFileBytes
  - HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest.packagedGeneratedAppUploadsAndDownloadsAFileThroughARealObjectStore

Gradle's default console reporter only prints "AssertionFailedError at Foo.java:1009" with no
message text, and the workflow's evidence-upload step had a separate, independent bug (its
artifact glob looked for `<Module>/**/build/test-results/test`, but dsl/kernel/generator/editor
all redirect layout.buildDirectory to a sibling `Build/gradle/<rootProject>/<projectPath>` per
this repo's build-output policy, so the glob never matched and the real JUnit XML was silently
dropped every run -- fixed separately in the same commit as this item, see
.github/workflows/npdev-ci-validation.yml). Fixing that glob first surfaced the real message:

    > Task :verifyNpdevRuntimeHostLibs FAILED
    Missing NPDev RuntimeHost libs manifest in /home/runner/work/NPDevGeneral/Build/runtimehost-libs.
    Run scripts/runtimehost/sync-runtimehost-libs.ps1 -BuildLocalJars.

Root cause, in NPDevRuntimeHost/build.gradle.template's resolveNpdevRuntimeLibsDir:

    def configured = providers.gradleProperty('npdevRuntimeHostLibsDir')
            .orElse(providers.environmentVariable('NPDEV_RUNTIMEHOST_LIBS_DIR'))
            .orNull

REG-128 (2026-08-04) made FinalAppAssembler#appendRuntimeHostLibsDirDefault ALWAYS bake a
resolved npdevRuntimeHostLibsDir line into every generated app's gradle.properties at
generation time (belt-and-suspenders fix for apps generated outside the source repo, which
previously fell through to a nonsensical relative-path default). But providers.gradleProperty()
cannot distinguish "explicit -P on the command line" from "value read from the properties file"
-- once gradle.properties always carries a baked value, the `.orElse(environmentVariable(...))`
fallback branch is permanently dead. These 3 tests generate the app once (no
NPDEV_RUNTIMEHOST_LIBS_DIR set at generation time, so the baked default is the global
`Build/runtimehost-libs`), then try to override the libs dir via the NPDEV_RUNTIMEHOST_LIBS_DIR
env var when invoking `bootJar`, pointing at their own test-local, freshly-built jar set
(OUTSIDE_ROOT/runtimehost-libs) -- that override was silently ignored, and the build looked for
a manifest at the (unpopulated, at that point in the CI pipeline) global Build/runtimehost-libs
instead.

Passed on the author's own dev machine only because D:\WorkSpace\NPDev\Build\runtimehost-libs
already has valid jars there from routine platform work, masking the bug -- a clean CI checkout
has nothing there until the workflow's later "Stage RuntimeHost libs" step runs (which happens
AFTER "Generator unit tests" in the pipeline).

**Surface:** `build-tooling/runtimehost-libs-staging`
**Files:**
- `NPDevRuntimeHost/build.gradle.template`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/assembly/FinalAppAssembler.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/HardenObjstoreFileUploadPackagedGeneratedAppRuntimeProofTest.java`
- `.github/workflows/npdev-ci-validation.yml`

Two independent, complementary fixes landed together (both applied, not "either/or" like
REG-128's own two fix shapes were):

(1) NPDevRuntimeHost/build.gradle.template's resolveNpdevRuntimeLibsDir now checks
    NPDEV_RUNTIMEHOST_LIBS_DIR BEFORE providers.gradleProperty(), restoring the build-time env
    var override path for any caller (not just these 3 tests) while leaving REG-128's original
    fix intact for the no-override case (no env var, no -P -> uses the baked
    gradle.properties default). The only behavior change is the unusual case of an env var AND
    an explicit -P being set simultaneously (env var now wins instead of -P) -- no caller in
    this repo does that.

(2) The 3 affected tests now ALSO pass -PnpdevRuntimeHostLibsDir=<path> on the bootJar command
    line, alongside the existing NPDEV_RUNTIMEHOST_LIBS_DIR env var, as defense-in-depth --
    this works regardless of fix (1) since an explicit -P always wins.

Also fixed in the same commit: the CI workflow's evidence-upload artifact glob (see files list),
which is what made the real error message visible at all instead of the bare
"AssertionFailedError at line 1009" console summary.

### REG-138 — semantic-behavior-writeback (controller+service+canonicalization) is compiled out of EVERY generated app by the supported-runtime-surface allowlist (deferredControllers), so all 5 /api/admin/model/semantic-behavior-writeback[...] endpoints 404 by default -- and even the one directly-executable mutation only appends to a side JSON file nothing reads back

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-07)
**Verification:** VERIFIED_LIVE
**Source:** Found while implementing editor/ANALYSIS.md's E2 ("does semantic writeback actually apply?",
__OutsideRepo, 2026-08-06 analysis against 6b1b3fb). That analysis's own §2.1 correction claimed
"SemanticBehaviorWriteBackService accepts five request types ... validates them, assigns a
requestId, and journals them" as evidence the editor "has a real write path", contrasting it with
structural-writeback's missing controller/service (§3). Re-checking that claim against a REAL
built app rather than trusting the source tree turned up a bigger gap than either §3 or E2
anticipated.

NPDevRuntimeHost/build.gradle(.template) computes `unsupportedRuntimeHostControllerSources` /
`unsupportedRuntimeHostServiceSources` from src/main/resources/npdev/runtime-supported-
controllers.json's `allowedControllers` / `supportedCoreServiceComponents` /
`supportedCoreServicePatterns` ONLY -- it never consults `deferredControllers`, and this exclusion
is applied unconditionally in `sourceSets.main.java` (not gated behind
npdev.runtime.supported-surface-enforced or any -P flag). SemanticBehaviorWriteBackController is
listed in the manifest's `deferredControllers` (not `allowedControllers`), and
SemanticBehaviorWriteBackService / SemanticBehaviorWriteBackCanonicalizationService match none of
supportedCoreServiceNames/-Patterns (both instead match the `Semantic*` entry in
`nonDefaultServicePatterns`, an array the gradle exclusion logic never reads -- only
scripts/quality/run-runtime-surface-evidence.ps1's governance classifier reads it, for a separate,
non-blocking self-consistency check). Net effect: the three classes are excluded from
`sourceSets.main.java` at compile time for every generated app, unconditionally -- this is by
design (the manifest's `enforcedByProperty`/`surfaceProfileProperty` fields document a RUNTIME
bean-filter toggle in RuntimeControllerAllowlistConfig, but that toggle can never re-add a class
that was never compiled in the first place; the actual, load-bearing enforcement is this
compile-time exclusion, as run-runtime-surface-evidence.ps1's own comment at line 587 already
says: "the actual allowlist enforcement is the build-time controller exclusion in
build.gradle.template").

Verified empirically against an already-built real generated app (not just static analysis):
D:\WorkSpace\NPDev\Build\generated-finalapps\claude-support-desk\App has the three .java source
files under src/main/java (copied from the RuntimeHost template, as expected), but
build/classes/java/main contains ONLY SemanticBehaviorWriteBackRequest.class (the DTO, in
com.finalexec.npdev.dto, a package the exclusion logic never filters) -- no Controller.class, no
Service.class, no CanonicalizationService.class anywhere in the compiled output. Cross-checked
against com.finalexec.api.internal's actual compiled class list in the same app: it matches
allowedControllers exactly (PublicationExecutorController, RealPublicationExecutorController,
RollbackExecutionController, SemanticPublicationMappingController,
StructuralPublicationMappingController, SourceMutation*GateController/-AuditRecordController/-
RollbackAnchorController, PublicationRollbackExecutorController,
PublicationTransactionRecordController -- 10 classes), confirming the allowlist is the actual
mechanism and SemanticBehaviorWriteBackController is not merely late-loaded some other way.

This is a DIFFERENT shape from structural-writeback (§3 of the same analysis): structural has zero
implementation ever written. Semantic-behavior-writeback is fully implemented (request validation,
canonicalization rules, execution, history, journaling to runtime-data/) but deliberately gated
behind a profile (npdev.runtime.surface-profile=non-default) that no generated app enables by
default -- and the gate is enforced so early (compile time) that even opting into that profile at
runtime cannot resurrect it without a build change. Given the surrounding manifest also defers a
large, clearly-intentional list of speculative/governance features (Explainability*, FlowBuilder*,
GuidedTaskWorkspace*, Template*, etc.), this LOOKS like deliberate platform governance policy
(minimize default attack surface / unfinished-feature exposure) rather than an accidental gap --
unlike REG-104/REG-108's silent-drop shape, nothing here silently loses data. But two consequences
were not previously documented anywhere:

(1) NPDevEditor/ui-react/src/promptHistoryData.ts's HISTORY_SOURCES array unconditionally queries
BOTH "structural" and "semantic-behavior" sources' three endpoints each. Exactly like structural's
known-broken screen (analysis §3's "What a user sees"), the semantic-behavior source will also
warn/empty-list in every default-profile generated app -- fetchPromptHistorySource already
degrades gracefully via Promise.allSettled (a warning per failed endpoint, not a hard crash), so
the failure mode is "silently empty history panel" rather than a thrown error, but it is still a
UI panel presented as live that is unreachable in the shipped default.

(2) Even in the one profile where it WOULD compile (npdev.runtime.surface-profile=non-default,
which per this item's finding above cannot actually be reached without a build change today
anyway), SemanticBehaviorWriteBackService.execute()'s only directly-executable path
(isDirectlyExecutable: outcome==CANONICALIZABLE && actionType==addNotificationStep, i.e. only
addOrchestrationStep requests with stepKind=notification; addInvariant, addLifecycleState,
addLifecycleTransition, addAwaitEventStep, and addOrchestrationStep/stepKind=approval all land on
status=REVIEW_REQUIRED and stop there) applies its mutation by appending to
runtime-data/canonical-workspace/semantic-behavior/semantic-behavior-workspace.json --
a repo-wide grep for readers of that path (or of "appliedMutations"/"canonical-workspace" in that
sense) found none. So even a request that DOES reach EXECUTED status does not change the running
app's actual flow/model behavior -- it only appends an audit-trail entry to a file nothing
consumes. This is the exact "silent-answer" shape ANALYSIS.md's E2 flagged as the bigger possible
finding ("a complete-looking pipeline whose last step is missing").

**Surface:** `runtimehost/supported-surface-allowlist, editor/prompt-history`
**Files:**
- `NPDevRuntimeHost/src/main/resources/npdev/runtime-supported-controllers.json`
- `NPDevRuntimeHost/src/test/java/com/finalexec/SupportedRuntimeSurfacePackagingTest.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/internal/SemanticBehaviorWriteBackService.java`
- `NPDevRuntimeHost/src/test/java/com/finalexec/npdev/service/internal/SemanticBehaviorWriteBackServiceTest.java`
- `NPDevRuntimeHost/src/main/resources/npdev-semantic-behavior-canonicalization/semantic-behavior-canonicalization-rules.json`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/provenance/BuildInfoEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/provenance/BuildInfoEmitterTest.java`

Owner decision (asked directly, three options presented): **enable it for real**, not leave
deferred or remove. Two more scope checks happened before writing code, both changing the shape of
the fix -- documented here rather than silently absorbed, since each is exactly the kind of "looked
bigger on inspection" finding this item itself is about:

1. There is NO live flow-mutation mechanism ANYWHERE in this platform.
   CompiledModelFlowDefinitionProvider builds an immutable flow map once at JVM boot from the
   compiled model baked into the jar; nothing ever touches it again. This is true of every model
   change in this platform, not special to this service -- so "EXECUTED" can never honestly mean
   "took effect in this JVM." The only real primitive a "notification step" maps to is a
   `capabilityCall` flowStep targeting the model's own `notification` capability's `send`
   operation (there is no "notification" flowStep type in the DSL at all -- confirmed against
   model.schema.json's 13-entry flowStep type enum).
2. The running app had no way to know where its OWN source model.json lives on disk --
   npdev-build-info.properties tracked git commit/version/timestamp but never the --model path
   used at generation time. Fixed by threading modelPath/a.configPath through
   BuildInfoEmitter.emit() (new 4-arg overload; the 2-arg overload used elsewhere is preserved,
   recording UNKNOWN) into two new keys, npdev.model.sourcePath / npdev.config.sourcePath,
   absolute paths so a running app's own CWD can't skew resolution.

With both of those actually necessary (not speculative), the real fix:

(a) Allowlist: SemanticBehaviorWriteBackController moved from deferredControllers to
allowedControllers; SemanticBehaviorWriteBackService + SemanticBehaviorWriteBackCanonicalizationService
added to supportedCoreServiceComponents. SupportedRuntimeSurfacePackagingTest's three
assertNotPackaged assertions flipped to assertPackaged.

(b) A THIRD, deeper root cause found while proving this live, not from static reading:
npdev-semantic-behavior-canonicalization-rules.json (the file SemanticBehaviorWriteBackCanonicalizationService
reads to decide CANONICALIZABLE vs REVIEW_REQUIRED) was a completely empty seeded template
placeholder -- no `supportedActions` key at all, same shape as its still-empty sibling
npdev-canonical-source-mutation-rules.json. This meant isDirectlyExecutable() could NEVER return
true for ANY app, ever, regardless of the allowlist fix -- a live curl against a freshly-generated
app confirmed outcome=UNSUPPORTED / status=REVIEW_REQUIRED even after (a). Populated the file with
real entries for all 4 action types the code already recognizes by name (addNotificationStep ->
CANONICALIZABLE; addApprovalStep/setRetryPolicy/setTimeoutPolicy -> REVIEW_REQUIRED, each with a
reason -- none of the three map to an existing DSL primitive the way addNotificationStep does).

(c) SemanticBehaviorWriteBackService.execute()'s addNotificationStep path rewritten:
applyMutationToWorkspace (append-only, unread JSON) replaced by applyMutationToModelSourceAt
(package-private, testable against a @TempDir path independent of the classpath-resource lookup),
which: resolves npdev.model.sourcePath (REVIEW_REQUIRED with a clear reason if UNKNOWN or the file
is missing -- never guesses a fallback location); parses the model as a Jackson ObjectNode;
confirms the model actually declares a `notification` capability (REVIEW_REQUIRED otherwise, since
the generated step would otherwise fail generation); finds the target flow by name
(REVIEW_REQUIRED if absent); checks for a step-name collision (REVIEW_REQUIRED if duplicate); then
appends a real `{"name","type":"capabilityCall","capability":"notification","operation":"send"}`
step and writes the file back. Every failure path declines with a reason rather than throwing or
writing something invalid -- a malformed automatic edit that corrupts the model source is worse
than asking a human to apply it by hand. Response fields renamed/added (modelSourcePath,
requiresRebuild:true) and the message text states plainly that a regenerate+rebuild is required --
"EXECUTED" now means "wrote a real change to the source," never "took effect now."

(d) Diff-fidelity, found and fixed during live verification, not anticipated up front: the first
live round-trip produced a 929-insertion/1166-deletion diff on canonical-demo's model.json for a
ONE-STEP insertion -- Jackson's writerWithDefaultPrettyPrinter() (i) always emits LF (this repo's
model.json files are checked in CRLF), (ii) uses `"key" : value` (space before the colon; this
repo's convention has none), and (iii) puts array-of-object elements on the same line as `[`/`,`
(a well-known Jackson default quirk) instead of one per line. All three fixed: read the original
text first to detect its line-ending style and whether it ends with a trailing newline, write with
a custom DefaultPrettyPrinter (Separators.Spacing.AFTER + DefaultIndenter for both objects and
arrays), then normalize line endings/trailing newline to match the original. Final verified diff
for the SAME one-step insertion: 7 insertions, 1 deletion -- exactly the new step.

### REG-139 — ModelEditorPanel.tsx crashes with an uncaught TypeError on a fresh generated app: GET /api/admin/model/editor/draft's no-draft-yet fallback returns the raw compiled model.json (concepts/procedures/panels) verbatim, but the frontend blindly casts it to ModelEditorDraft (entities), so draft.entities.find(...) throws on undefined

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-07)
**Verification:** VERIFIED_LIVE
**Source:** Found while verifying editor/ANALYSIS.md E3's Playwright spec (e2e-generated-app/editor-in-
generated-app.spec.ts) against a completely fresh app, generated end-to-end via
`npdev run app --model NPDevContract/dsl/resources/Models/canonical-demo/model.json ...` (not a
reused/previously-exercised build) -- done specifically to prove a REG-138-adjacent asset-copy
fix (npdev-templates/static-react-manifest.json) actually ships working chunk files, per a
reviewer's request to confirm chunks resolve rather than just index.html. The asset-copy fix
itself verified clean (curl 200 on all 5 manifested files); this is a SEPARATE, real bug the
same verification pass surfaced.

Reproduced live: opening a freshly-booted app's /npdev-ui-react/ (root, default Workbench
surface, "Model Editor" the default active tab) throws
`TypeError: Cannot read properties of undefined (reading 'find')` before the shell even paints
(React has no error boundary here, so the whole tree -- including the <h1> -- fails to mount).
Traced to ModelEditorPanel.tsx:53's `draft.entities.find(...)` inside a useMemo, fed by
`loadDraft()` -> `npdevClient.fetchModelEditorDraft()` -> a bare `get<ModelEditorDraft>(...)`
fetch with NO runtime shape validation, just a compile-time type assertion.

Root cause on the backend: npdev-runtime-admin-controller.mustache's `readDraftOrModel()`, when
the in-memory `MODEL_EDITOR_DRAFT` field is null (true on every fresh JVM start -- it is a plain
static field, never persisted), falls back to reading classpath resource `npdev/model.json` and
returning it VERBATIM as the "draft". That resource is the raw compiled model
($schema/namespace/concepts/procedures/panels), not a `ModelEditorDraft`
(namespace/dslVersion/version/entities) -- two different, incompatible shapes that happen to
share a couple of field names. Confirmed via direct curl against the running app:

    GET /api/admin/model/editor/draft ->
    {"$schema":"model.schema.json","schemaVersion":"1.0.0","namespace":"npdev.template",
     "name":"runtime-host-template-model","concepts":[],"procedures":[],"panels":[]}

(Note this response's own namespace/name -- "npdev.template"/"runtime-host-template-model" -- do
NOT match canonical-demo's own model identity, suggesting classpath resource resolution for
`npdev/model.json` may not even be hitting the generated app's OWN compiled model here; not
chased further in this pass.)

NOT reproduced against NPDevSamples/generated-finalapps' claude-support-desk in the same
session (its Workbench loaded and its own e2e assertions passed cleanly) -- that app's
`npdev/model.json` classpath resolution may differ (possibly 404s there, which the frontend's
catch block handles safely by leaving `draft` at its safe `emptyDraft()` default, unlike a 200
with the wrong shape). The discrepancy between apps is not yet root-caused; flagging only that
this is APP-DEPENDENT, not universal, so "it worked for me on one app" is not evidence against
this bug.

**Surface:** `editor/workbench, runtimehost/admin-controller`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/npdev-runtime-admin-controller.mustache`
- `NPDevEditor/ui-react/src/api/npdevClient.ts`
- `NPDevEditor/ui-react/src/PanelErrorBoundary.tsx`
- `NPDevEditor/ui-react/src/workbench/ReactWorkbenchApp.tsx`
- `NPDevEditor/ui-react/ui-boundary.json`
- `NPDevEditor/ui-react/e2e-generated-app/editor-in-generated-app.spec.ts`
- `scripts/quality/firstrun-harness/run-readme.sh`

Not fixed here -- found during a verification pass for an unrelated fix (the static-react asset
manifest) and filed rather than chased, to stay scoped. Two independent fix shapes, not
mutually exclusive:

(1) Backend: readDraftOrModel()'s "no draft yet" fallback should return something already shaped
    like ModelEditorDraft (e.g. transform concepts->entities, or simply return emptyDraft()'s
    JSON shape) instead of the raw compiled model verbatim -- the two are conceptually different
    documents (a model vs. an editable draft of one) that were never meant to be interchangeable.
(2) Frontend: ModelEditorPanel/npdevClient should not trust a raw fetch's shape blindly -- either
    validate the response against ModelEditorDraft before calling setDraft(), or make the
    draft.entities accesses defensive (`draft.entities ?? []`) so a shape mismatch degrades to an
    empty editor instead of a page-crashing uncaught exception. RuleEditorPanel/
    OrchestrationEditorPanel share the same `readDraftOrModel()` backend and the same
    no-validation fetch pattern on the frontend -- worth checking whether they have the same
    exposure before scoping a fix.

A first-run harness or E3-style e2e check that opens the Workbench's default tab against a
genuinely fresh app (no prior draft) would have caught this before a real user did -- this is
exactly the "no end-to-end proof it works inside a generated app" gap editor/ANALYSIS.md's own
§4 flagged as weak, now with a concrete crash behind it.

### REG-14 — LNCH-22: newcomer documentation test run for the first time

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `process/external-validation`

The DoD -- a newcomer building the tutorial app from docs alone -- had never been exercised. Closed
by the same 2026-07-22 independent-tester run that closed REG-13: it built
NPDevSamples/simple-contact-intake from docs/TUTORIAL_FIRST_APP.md alone (docs only, no MCP tools or
CLI validator used to fill gaps), and verified it booted and worked (both the tutorial's create
example and its invariant-failure example). Pass bar met on the first cold run. Real, dated findings
filed even though the pass bar was met: the tutorial's literal `gradlew.bat bootJar` command fails
on an undocumented RuntimeHost-libs staging prerequisite whose own suggested fix also fails
standalone in a fresh worktree; the doc's claimed 400 status for an invariant violation is actually
422.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-14`

### REG-140 — Every generated app was hard-pinned to Java 17 (build.gradle.template's toolchain literal), with no per-app way to request a newer JDK -- deps-and-java/PLAN.md P2

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-07)
**Verification:** VERIFIED_LIVE
**Source:** Filed directly from deps-and-java/PLAN.md's P2 (owner-authored plan, this repo). Before this
change, NPDevRuntimeHost/build.gradle.template's `java { toolchain { languageVersion =
JavaLanguageVersion.of(17) } }` was a hardcoded literal copied byte-for-byte into every generated
FinalApp (FinalAppAssembler#materializeRootTemplate's substitution-free convention) -- there was
no model/config field, no CLI flag, and no generator code path that could change it. An app
wanting a library or language feature that needs Java 21 (or any non-17 JDK) had no way to ask for
one without hand-editing the assembled app's build.gradle after every single regeneration, which
the platform's own regeneration model (idempotent, overwrite-on-generate) treats as data loss on
the next regenerate.

**Surface:** `generator/build-assembly, schemas/config, cli/doctor, manager/runtime`
**Files:**
- `NPDevContract/schemas/config.schema.json`
- `NPDevContract/schemas/authoring/config.schema.json`
- `NPDevContract/dsl/resources/Schemas/config.schema.json`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/assembly/FinalAppAssembler.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/assembly/FinalAppAssemblerTest.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/GeneratorMainJavaVersionResolutionTest.java`
- `NPDevRuntimeHost/build.gradle.template`
- `NPDevRuntimeHost/settings.gradle.template`
- `NPDevCli/npdev_cli.py`
- `NPDevManager/ui/app.js`
- `NPDevManager/fixtures/doctor-wrong-java.json`
- `NPDevManager/fixtures/doctor-acceptable-newer-java.json`
- `NPDevManager/src/npdev.rs`
- `NPDevManager/src/runtime.rs`
- `docs/NPDEV_USER_MANUAL.md`
- `BREAKING.md`

New optional `config.json` field `build.javaVersion` (enum 17|21, default 17 when omitted --
existing apps and configs are unaffected). Threaded generator-side: GeneratorMain.resolveJavaVersion()
validates against SUPPORTED_APP_JAVA_VERSIONS and passes it into FinalAppAssembler.Options
(normalized to 17 if <=0); FinalAppAssembler.appendAppJavaVersionDefault() appends
`npdevAppJavaVersion=<n>` to the assembled app's gradle.properties (append-only, matching the
existing appendRuntimeHostLibsDirDefault precedent). build.gradle.template's toolchain block reads
that property at build time instead of a literal:
`JavaLanguageVersion.of((providers.gradleProperty('npdevAppJavaVersion').orNull ?: '17') as Integer)`.
A new settings.gradle.template registers the `org.gradle.toolchains.foojay-resolver-convention`
plugin (0.8.0) so a machine without a matching local JDK auto-provisions one via the Adoptium API
instead of failing -- opt-out is Gradle's own `-Dorg.gradle.java.installations.auto-download=false`
property (a conditional plugin registration is not syntactically legal in Gradle's settings.gradle
DSL, confirmed live). NPDevRuntimeHost/build.gradle itself (the platform's own module, not the
template) stays pinned at 17 by design -- this field only affects the generated app's own toolchain.

npdev doctor's Java check changed from an exact `== 17` pass/fail to a 4-way branch: missing/
unparseable -> fail, `< 17` -> fail, `== 17` -> pass, `> 17` -> warn (not fail) with an explanation
that the foojay resolver can still provision 17 for apps that need it. NPDevManager mirrored the
same relaxation (label "Java 17" -> "Java 17+", `resolve_jdk17()` generalized to
`resolve_jdk(major: u32)`, a new `doctor-acceptable-newer-java` fixture for the warn case).

### REG-141 — A custom capability (plugin:java-source) had no supported way to declare a third-party Maven dependency or a local jar -- deps-and-java/PLAN.md P3

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-07)
**Verification:** VERIFIED_LIVE
**Source:** Filed directly from deps-and-java/PLAN.md's P3 (owner-authored plan, this repo), the sibling item
to REG-140 (P2, per-app Java level). A `plugin:java-source` custom capability compiles straight
into the generated app's own Gradle source set (capabilities/<name>/src/main/java/...), so it
automatically has access to whatever `dependencies{}` the assembled build.gradle already declares
-- but there was no config-driven, regeneration-safe way to ADD a new one. An app author needing a
library (Guava, a JSON/HTTP client, an internal local jar) had to hand-edit the assembled app's
build.gradle after every generate, which the platform's overwrite-on-generate regeneration model
silently discards on the next run -- the exact "workaround that fails regeneration-survival" the
plan calls out (see PLAN.md's W3.3 rationale).

**Surface:** `generator/build-assembly, schemas/config`
**Files:**
- `NPDevContract/schemas/config.schema.json`
- `NPDevContract/schemas/authoring/config.schema.json`
- `NPDevContract/dsl/resources/Schemas/config.schema.json`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/AppDependenciesEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`
- `NPDevRuntimeHost/build.gradle.template`
- `docs/NPDEV_USER_MANUAL.md`
- `BREAKING.md`

New optional `config.json` fields under `build`: `repositories[]` ({name, url}) and
`dependencies[]`, accepting three shapes -- a bare Maven coordinate string
(`"com.google.guava:guava:33.2.1-jre"`), an object `{coordinate, scope}`, or a local-jar object
`{jar: "libs/sample-lib.jar", scope}` (relative to the app definition directory) -- plus a
`{platform: "..."}` BOM shorthand. New `AppDependenciesEmitter.emit()` writes a generated
`npdev-dependencies.gradle` file into the assembled app root (one `implementation`/`api`/etc. line
per declared dependency, `implementation files('npdev-app-libs/<name>.jar')` for local jars);
`copyLocalJars()` copies each declared jar from the app definition's `libs/` directory into the
assembled app's `npdev-app-libs/` directory, with path-traversal and filename guards. A 3-line
`apply from:` hook was added to build.gradle.template right after its own `dependencies{}` block,
conditional on the generated file's existence (`if (npdevAppDeps.isFile())`) so apps with no
declared dependencies are completely unaffected. A generation-time collision check
(`collisionWarnings()`) regex-scans the materialized build.gradle for an already-present
`groupId:artifactId` pair and warns (does not fail) rather than silently double-declaring.
`capability.plugin.json` itself needs NO schema change -- a `plugin:java-source` capability already
compiles into the app's main source set, so it sees `npdev-dependencies.gradle`'s additions for
free once the toolchain resolves them.

### REG-142 — Runtime-host template resources SHADOW the generated app's own at the same classpath path, so every generated app served another app's model identity from /api/admin/model/export and threw away its own UI permission policy

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** Filed 2026-08-07 while implementing REG-139's layer 1 fix (editor/REG139_PLAN.md), not chased
there to stay scoped. Root cause corrected 2026-08-08 under storage/OPEN_ITEMS_PLAN.md section 7:
the original filing named the wrong cause and understated the blast radius.

**Surface:** `runtimehost/admin-controller, runtimehost/ui-metadata, generator/runtime-api-emitter`
**Files:**
- `NPDevRuntimeHost/src/main/resources/npdev/model.json`
- `NPDevRuntimeHost/src/main/resources/npdev/security/dev.ui-metadata-policy.json`
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/PermissionAwareUiMetadataService.java`
- `scripts/quality/check-template-resource-shadowing.py`
- `scripts/quality/run-ai-knowledge-gate.ps1`

WHAT THE ORIGINAL FILING SAID (and got wrong)

It attributed the bug to a missing `else` in `RuntimeApiEmitter`: `npdev/model.json` is written
only `if (resolvedModelSource != null)` or `else if (modelSourcePath exists)`, with no fallback,
so a generation path satisfying neither would leave the RuntimeHost template's placeholder
untouched. Plausible, and not what happens.

WHAT ACTUALLY HAPPENS, MEASURED IN A BUILT APP

The emitter DOES write the real model, every time, to
`npdev-generated/src/main/resources/npdev/model.json`. A generated app's build.gradle then mounts
both resource roots:

    resources {
        srcDir 'src/main/resources'                    <- the runtime-host TEMPLATE's copy
        srcDir 'npdev-generated/src/main/resources'    <- the app's REAL one
    }
    ...
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

EXCLUDE keeps the FIRST file it sees and the template is listed first, so the template's copy wins
and the generated one is dropped -- no Gradle warning, no boot error, nothing in the app to show
it happened. Confirmed by reading `App/build/resources/main/npdev/model.json` in a freshly built
app: it contains `{"namespace":"npdev.template","name":"runtime-host-template-model",
"concepts":[]}`.

So it is not conditional and not rare. It was EVERY generated app, always.

BLAST RADIUS -- three readers, not the two originally listed:

  GET /api/admin/model/export        (AdminController.exportModel)
  GET /api/admin/model/ui, /ui-model (AdminController.exportUiModel)
  CapabilityIntegrationPanelService  (npdev/model.json, same path, same shadowing)

SECOND INSTANCE, found by generalizing the question

Asking "what else does the template ship at a path the generator writes?" produced one more:
`npdev/security/dev.ui-metadata-policy.json`, shipped in the template since 2026-04-23. The two
files do not even share a shape -- the template's has `items`, the generated one has
`fieldPolicies`/`actionPolicies` -- so an app's UI permission policy was not merely overridden, it
was unreadable. Harmless only for as long as both were empty, which is a property of today's
corpus rather than of the design.

### REG-143 — build.javaVersion's upper enum [17, 21] removed -- floor-only (>=17), future-proofed against every Java version to come, not just 21 -- ROUND2_PLAN.md R1c

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** ROUND2_PLAN.md R1c asked whether the third-party user's original "newer Java version" request
(REG-140, which shipped `enum [17, 21]`) was satisfied by 21, or whether they actually needed 25
(current LTS at plan-writing time). The owner's answer, asked directly: "Make it future proof.
Support ALL 17 and superiors. No negotiable condition." -- i.e. remove the enum entirely rather
than widen it to a new fixed ceiling that would need renegotiating again at the next LTS.

**Surface:** `generator/build-assembly, schemas/config, runtimehost/build-template, runtimehost/bean-wiring`
**Files:**
- `NPDevContract/schemas/config.schema.json`
- `NPDevContract/schemas/authoring/config.schema.json`
- `NPDevContract/dsl/resources/Schemas/config.schema.json`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/GeneratorMainJavaVersionResolutionTest.java`
- `NPDevRuntimeHost/gradle/wrapper/gradle-wrapper.properties`
- `NPDevRuntimeHost/settings.gradle.template`
- `NPDevRuntimeHost/build.gradle.template`
- `NPDevRuntimeHost/src/main/resources/application-step0.yml`
- `NPDevRuntimeHost/src/main/java/com/finalexec/config/NpdevObservabilityConfig.java`
- `NPDevSamples/simple-contact-intake/Input/config.json`
- `docs/MANAGER.md`
- `NPDevCli/npdev_cli.py`
- `NPDevManager/src/runtime.rs`
- `BREAKING.md`

Two layers, both changed:

1. VALIDATION LAYER (the actual "no negotiable" promise): all 3 config.schema.json mirrors
   changed `build.javaVersion` from `enum: [17, 21]` to `minimum: 17` (no maximum).
   GeneratorMain.resolveJavaVersion's SUPPORTED_APP_JAVA_VERSIONS allowlist replaced with a
   floor-only check (MINIMUM_APP_JAVA_VERSION = 17); rejects only values below the floor or
   non-integers. Any integer >= 17 is accepted unconditionally, including versions that do not
   exist yet -- there is deliberately no allowlist of "known good" versions to keep updating.

2. MAKE-IT-REAL LAYER (so the promise isn't just accepted-then-failing four minutes later): the
   generated app's own bundled toolchain was the actual ceiling, not the schema. Before this
   change it was Gradle 8.5 (foojay-resolver-convention 0.8.0), which the removed error message
   itself documented as capping resolvable Java versions at 21. Bumped, in NPDevRuntimeHost's
   template only (platform modules -- dsl/kernel/generator/adapters/runtimehost source -- stay on
   Gradle 8.5/Java 17, unaffected):
     - Gradle 8.5 -> 9.5.1 (wrapper regenerated via a scratch project's `gradle wrapper
       --gradle-version 9.5.1`, then gradle-wrapper.properties/jar + gradlew/gradlew.bat copied
       over -- only gradle-wrapper.properties actually differed byte-for-byte from the 8.5
       version; the bootstrap jar/scripts were already version-stable).
     - foojay-resolver-convention 0.8.0 -> 1.0.0 (0.8.0 depended on a class Gradle 9 removed).

   This bump broke FIVE separate, real things, each found only by actually running a build/boot,
   never by reading a changelog. The first three were caught by the normal
   `enforceSingleSchemaRealizationSource test` task; the last two were caught ONLY by the
   generator module's own `PackagedGeneratedAppRuntimeProofTest`/
   `HardenGcDeleteReplaceCascadePackagedGeneratedAppRuntimeProofTest`, which are the only tests in
   the whole corpus that run `bootJar` and then actually boot the packaged jar as an external
   process and hit its HTTP health endpoint -- every other test/gate path (including this same
   PLAN's own R1a/R1b live-proof, done first) exercises `compileJava`/`test` only, never `bootJar`
   or a real external-process boot, so none of the last two would have been caught any other way:

     a. Gradle 9 stopped bundling the JUnit Platform launcher -- added explicit
        `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`.
     b. ArchUnit 1.3.0's bytecode importer can't read Java 25's class file major version 69 --
        every rule failed with "failed to check any classes" (not a real architecture violation).
        Bumped to 1.4.2 (1.4.1 added Java 25 support upstream).
     c. Mockito 5.11.0 / Byte Buddy 1.14.18 (Spring Boot 3.3.2's managed versions) cannot
        instrument classes under Java 25 (MockitoException / MockitoInitializationException --
        Byte Buddy needs 1.17.5+ for class file 69). `configurations.configureEach {
        resolutionStrategy.force(...) }` does NOT win against io.spring.dependency-management's
        own managed version for a transitive dependency -- confirmed live, force() was present
        and mockito-core still resolved to 5.11.0. Required an explicit `dependencyManagement {
        dependencies { ... } }` override instead (that plugin's own mechanism), for BOTH
        mockito-core/mockito-junit-jupiter (5.11.0 -> 5.23.0) AND byte-buddy/byte-buddy-agent
        (Boot's BOM manages byte-buddy separately from mockito-core and won even after mockito
        was fixed -- 1.14.18 -> 1.17.7, matching what mockito-core 5.23.0 itself requests).
     d. `bootJar` itself failed: `Execution failed for task ':bootJar' ... 'java.lang.Integer
        org.gradle.api.file.CopyProcessingSpec.getDirMode()'` -- Spring Boot 3.3.2's OWN Gradle
        plugin (not core Gradle) calls a Copy API method Gradle 9 changed. Fixed by bumping the
        `org.springframework.boot` plugin 3.3.2 -> 3.5.16 (the last Spring Boot 3.x patch; 3.5
        itself reached OSS EOL 2026-06-30, but is still the earliest 3.x line documented as
        Gradle-9-compatible). Deliberately NOT Boot 4.x, the only currently-non-EOL line: a
        Spring Framework 7 / Jakarta major migration is its own multi-day effort with a blast
        radius far beyond "can bootJar run under Gradle 9," out of scope here. This ALSO
        contradicts this item's own earlier resolution note (see below), which had claimed no
        Boot version change was needed -- that was true for `test`, false for `bootJar`.
     e. Once `bootJar` worked and the packaged jar was actually booted as an external process, it
        failed to become healthy. Root cause, found via `--debug`'s condition-evaluation report:
        `SchemaRealizationEmitter` bakes `spring.autoconfigure.exclude=DataSourceAutoConfiguration,
        HibernateJpaAutoConfiguration,JpaRepositoriesAutoConfiguration,FlywayAutoConfiguration`
        into the generated `application-npdev-db.properties` whenever a model resolves to the
        InMemory engine at generation time (correct -- there is no real datasource in that mode).
        `application-step0.yml` (the "zero-setup trial" profile, which forces a real H2 database
        regardless of the model's resolved engine) only ever set `spring.datasource.*`/
        `spring.jpa.*` -- it never cleared that inherited exclusion, so `DataSourceAutoConfiguration`
        stayed excluded and `NpdevRuntimeModeConfig.jdbcConceptStore`'s `DataSource` parameter had
        nothing to autowire (`UnsatisfiedDependencyException`). Adding
        `spring.autoconfigure.exclude: ""` in step0's YAML did NOT fix it -- confirmed live via the
        same `--debug` report, the four classes were still excluded. Spring's `Binder` treats an
        *empty string* for this property as "absent" and falls through to the next-lower-precedence
        source (the imported properties file); an *empty list* (`exclude: []`, proper YAML list
        syntax) is a genuinely different bound value and DOES override correctly -- confirmed live
        by first proving a non-empty bogus class name overrides correctly (ruling out "profile YAML
        can never win over an import" as the theory), then confirming `[]` also overrides
        correctly, then reverting `""` in favor of `[]`.

        Clearing the exclusion once `DataSourceAutoConfiguration` could fire uncovered a THIRD,
        separate bug, one bean-wiring level down: `JdbcTraceStore`/`JdbcFlowInstanceStore`/
        `JdbcEventStore` (the tracestore-postgres/flowinstance-postgres/eventstore-postgres
        adapters) each implement TWO interfaces at once (e.g. `TraceStore` and `TraceSummaryStore`
        in one class). `NpdevObservabilityConfig`'s `traceSummaryStore`/`executionSummaryStore`/
        `eventMetaStore` `@Bean` methods each returned that same dual-interface instance directly
        when delegating, which registers ONE object under TWO type-assignable bean definitions --
        any plain `TraceStore`/`FlowInstanceStore`/`EventStore`-typed injection point (here,
        `StorageWiringLogger`, a startup diagnostic `ApplicationRunner`) then found two candidates
        and failed with `UnsatisfiedDependencyException: ... required a single bean, but 2 were
        found`. Previously invisible for the same reason as (e): `jdbcTraceStore` never got
        created while `DataSourceAutoConfiguration` was excluded, so nothing ever collided. Fixed
        by having each delegating `@Bean` method return a narrower wrapper that implements ONLY
        the summary-typed interface: a plain method reference (`store::searchSummaries`,
        `store::listByCorrelation`) for the two single-method interfaces
        (`TraceSummaryStore`/`EventMetaStore`), and an explicit anonymous-class delegate for
        `ExecutionSummaryStore` (which has 3 real overridden methods beyond its one abstract
        method -- `JdbcFlowInstanceStore` implements all 4 with real query logic, not the
        interface's `List.of()` defaults, so a single method reference would have silently
        dropped 3 of them).

   All three of (d)/(e)'s findings are specific to the narrow combination this item's own live
   proof exercises: a model that resolves to the InMemory engine at generation time (the normal
   outcome when generating without a live database connection, e.g. via `generate-sample.ps1`,
   which is also how the failing proof tests generate their own fixture models), later forced into
   JDBC mode at runtime by the "step0" zero-setup trial profile. A model generated against a real,
   live `docker-postgres` connection never has the exclusion baked in the first place, so (e) and
   the bean-wiring bug never trigger for it -- but step0/trial is exactly the path
   ROUND2_PLAN.md's R5 (the clean-VM proof) and every "New app" / zero-setup flow through the
   Manager depends on, so this was not a corner case worth leaving broken.

### REG-144 — Every external-build-root resolver found the repo root by its NAME ('NPDev_General'), so a clone named anything else resolved THREE different build roots and Linux CI stayed red for twelve days

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** Found while fixing three packaged-app proof tests that failed only on the Linux CI runner (HardenGcDeleteReplaceCascade / HardenObjstoreFileUpload / TrustedSourceEmitter PackagedGeneratedAppRuntimeProofTest), red on main since 5317584 and passing on every local run.
**Surface:** `build-tooling/root-resolution, ci/linux-maturity-validation`
**Files:**
- `build.gradle`
- `NPDevContract/dsl/build.gradle`
- `NPDevEditor/build.gradle`
- `NPDevGenerator/build.gradle`
- `NPDevKernel/build.gradle`
- `scripts/npdev-common.ps1`
- `npdev-gradlew.ps1`
- `npdev-gradlew.sh`
- `NPDevCli/npdev_cli.py`
- `NPDevMcp/server.py`
- `scripts/ai/npdev_ai_common.py`
- `scripts/quality/twin-pair-registry.json`
- `.npdev-root`
- `.github/workflows/npdev-ci-validation.yml`

Eleven independent copies of NPDev's external-build-root resolution located the repo root by walking ancestors for a directory literally NAMED 'NPDev_General':

    NPDevKernel/build.gradle     while (sourceRoot.name != 'NPDev_General')
    scripts/npdev-common.ps1     while ($ancestor.Name -ne 'NPDev_General')
    ... and nine more (5 build.gradle, 2 .ps1, 1 .sh, 3 .py)

GitHub's checkout action names the directory 'NPDevGeneral' -- no underscore. The walk never matched, so every copy fell through to its own fallback, and the fallbacks were computed from different starting points. Measured in a REAL clone renamed to 'NPDevGeneral', running real Gradle, not a simulation:

    RED   NPDevKernel        -> <clone>/Build/gradle/npdev-kernel/root
          NPDevContract/dsl  -> <clone>/NPDevContract/Build/gradle/npdev-dsl/root
          Get-NPDevBuildRoot -> <clone>/../Build

THREE build roots in one checkout. The proof tests build 30 adapter jars with Gradle (exit 0 -- the jars really were written), then run sync-runtimehost-libs.ps1, which searched a different root, found zero jars, and threw "No RuntimeHost jars were discovered under build/libs after local jar build."
WHY IT SURVIVED SO LONG. On the author's machine the directory really is named NPDev_General, so every copy's walk succeeded and they agreed -- by coincidence, not by construction. The bug was therefore invisible to every local run and every local gate, and could only appear on CI or in someone else's clone. Get-NPDevBuildRoot's own comment already NAMED this hazard ("a git clone folder literally named 'NPDevGeneral' ... exactly how GitHub's own checkout action names it") and judged the fallback sufficient. It was not: the comment reasoned about one resolver in isolation and never compared the two fallbacks against each other.
Two diagnostics hid the root cause and were fixed alongside it:
  - the CI step that surfaces generator test failures globbed the non-redirected buildDir, so it
    printed "no generator test XML containing failures was found" on a run with three failures.
    Commit eff0f43 had fixed that identical trap for the artifact-upload glob two days earlier --
    the redirected buildDir has now caught four separate call sites.
  - NEW-1 declared working-directory on an `if: failure()` step; the runner resolves that before
    the script runs, so a run failing upstream of app generation added a second, purely spurious
    red X.

### REG-15 — LNCH-23: trademark clearance N/A, release tag cut

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-23)
**Verification:** NOT_VERIFIED
**Source:** LNCH-1 programme inheritance
**Surface:** `process/release`

Release tag beta1.1 (annotated) cut 2026-07-22 on the beta1-vision-spine -> main merge commit
3e29cca; run-release-checklist-gate.ps1 no longer lacks a tag. Trademark clearance: owner's final
decision (2026-07-23) is N/A -- this is an individual, non-commercial hobby/portfolio project with
no mark to defend and no trademark sought, so there is nothing to clear and nothing to park; the
item is complete, not deferred. Two preliminary name-collision findings on file ("NP DEV Soluções em
T.I.", NPDEV LIMITED UK #14176093) are informational only and block nothing.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-15`

### REG-16 — The other 23 launch items had zero adversarial review

**Type:** PROCESS · **Severity:** HIGH · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme, Tier A/B closed 2026-07-21; residual programme closed 2026-07-25
**Surface:** `process/adversarial-review`

LNCH-1 had absorbed five full review->plan->implement->review rounds; every other item in the
ledger (LNCH-2 tenant isolation, LNCH-4 auth, LNCH-13 row-level authz, ~23 other launch items) had
had none. Tier A (independent attack-first review of the LNCH-2+4 surface, ~23 files/~3,400 LOC):
headline no CRITICAL or HIGH finding -- tenant isolation is genuinely defense-in-depth. Residual 5
MEDIUM + 3 LOW + 1 INFO filed as REG-18..REG-26. Tier B (fixing those) also done same day. The
remaining ~21 launch surfaces (generator codegen, kernel FlowEngine/KernelRunner, LNCH-13
row-level authz, export/PDF, etc.) were tracked as a residual programme, REG-16-resid, rather than
reopening this item's scope -- that programme finished all six rounds 2026-07-25, closing this
item fully: no launch surface is left at zero adversarial review.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-16`

### REG-16-resid — Adversarial review of the other ~21 launch surfaces (6-round programme)

**Type:** PROCESS · **Severity:** HIGH · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** docs/archive/programme-history/POST_REG17_CLOSURE_PLAN.md Task 4, reusing the REG-16 template
**Surface:** `process/adversarial-review`

REG-16's original Tier-A review covered only LNCH-2 (tenant isolation) + LNCH-4 (auth); the other
~21 launch surfaces had never had an attack-first review. Six rounds, all complete 2026-07-25:
Round 1 (kernel execution path: KernelRunner/dispatcher/resilience mechanisms) -- no CRITICAL/HIGH,
2 MEDIUM filed as REG-36/REG-37. Round 2 (LNCH-13 row-level authz, kernel gateway + generated CRUD)
-- one CRITICAL found and fixed same round: a concept with a custom create/update/delete Flow got
ZERO row-level access.write enforcement on its generated endpoint (bypassed conceptGateway entirely);
fixed in service-base.mustache; 2 MEDIUM residual filed as REG-41/REG-42. Round 3 (generator codegen
OUTPUT) -- one HIGH found and fixed: every many-to-many bond emitted 4 HTTP endpoints with NO
authorization at all; fixed via a new ConceptGateway.authorizeWrite with a deliberately deny-by-
default; also fixed an XSS sink in the generated business UI; 1 MEDIUM residual filed as REG-44.
Round 4 (flow/await orchestration) -- no CRITICAL/HIGH; identity does not survive suspension, so no
confused-deputy authority to steal; 1 MEDIUM residual filed as REG-45. Round 5 (durable-state
adapters' own SQL, all *-postgres adapters) -- no CRITICAL/HIGH, ZERO SQL-injection findings (every
value bound, every identifier whitelisted); 1 MEDIUM residual filed as REG-46. Round 6 (export/PDF
path) -- no CRITICAL/HIGH, three findings all fixed in-round: CSV formula injection, an unbounded-
memory PDF export DoS, and an SSRF-capable PDF renderer with no URI policy (not reachable today,
fixed anyway). Net: 4 surfaces that stood at zero adversarial review now each have their own scope
list and findings document; every round's residual MEDIUM findings are individually tracked, not
silently dropped.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-16-resid`

### REG-17 — No third party had ever reproduced any verification

**Type:** PROCESS · **Severity:** MEDIUM · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme; multi-round CI reproduction effort
**Surface:** `ci/external-reproduction`

Every green suite/gate/rehearsal had been produced on one machine by the owner or a supervised AI,
never independently exercised. Closed via ~11 root-caused CI fixes across ~9 rounds: profile/config
fixes, a JDBC-capable sample for the Postgres integration tests (Fix A), surface-evidence advisory
wiring, npm ENOENT on Windows (REG-33), a deterministic runtimehost-libs sync, @DisabledOnOs on a
Linux-container test Windows can't run (REG-34), and an editor-E2E static-host path fix. Result:
npdev-ci-validation.yml (both the Linux maturity job and the Windows segmented job) runs green
end-to-end on GitHub-hosted runners from a clean checkout -- automated external reproduction on
hardware this project had never touched, the mechanism REG-17's DoD actually names. Owner's final
call (2026-07-27): the automated-repro + blind-AI-operator combination already achieved satisfies
REG-17's DoD intent; no further literal-human run is required to consider it closed. Residual,
filed not fixed: a genuinely clean container's anonymous `git clone` got a 404/credential prompt --
the repo was private at the time, so an uninvited third party could not have cloned it regardless of
CI's green status (repo visibility is an owner call, not an AI decision).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-17`

### REG-18 — Login timing side-channel enables username enumeration

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

PasswordHasher.verifyDecoy now runs a real PBKDF2 against a fixed decoy hash on both the
no-user and no-credential login paths, so response timing no longer discloses whether a username
exists. RED-first PasswordHasherDecoyTest.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-18`

### REG-19 — LoginThrottle.windowsByKey unbounded -- memory-exhaustion DoS via unique-username spray

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Hard cap (100k) added with expired-first + oldest-live eviction and a cutoff tie-break. RED-first
LoginThrottleBoundedTest sprays 3x the cap.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-19`

### REG-2 — IT-EXTPG-1: 10 integration tests unrunnable; root cause re-opened then found

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `runtimehost/integration-tests`

10 integrationTest classes (JwtAuthExternalBetaIT x8, PublicationRollbackE2EIT,
TenantIsolationE2EIT) failed with ApplicationContext load errors. Two prior theories (Testcontainers
Postgres profile config, a missing DataSource bean) were independently re-checked and found not to
hold up. The real cause was a THIRD mechanism: DatabaseIdentityStartupValidator aborting because
Testcontainers' jdbc:tc: DB always reports name "test", which never matches the app's resolved
identity. Fixed at the profile level (application-postgres.yml ->
npdev.trial.database-override:true). Running the suite surfaced two more real bugs, both fixed: a
text=uuid cast in PublicationRollbackE2EIT, and LoginController crashing verify-only JWT (fixed
under REG-9). 10/10 green on real Postgres.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-2`

### REG-20 — No defense against password-spraying (limiter was per-(tenant,username) only)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Added a per-source-IP arm to LoginThrottle (default 50/window vs 10/username), wired the client IP
through LoginController; a success clears the username window but not the IP window. RED-first
LoginThrottleIpSprayTest.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-20`

### REG-21 — password-reset/request endpoint unthrottled (email-bomb / token-row spam)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

PasswordResetController reuses the same limiter as login (5/user, 20/IP); over-limit returns the
same generic 200 but sends no email and creates no token. RED-first: the 6th request sends no email.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-21`

### REG-22 — ActuatorAdminGuardFilter trusted a JWT claim-role without live re-resolution or tokenVersion check

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-21)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

SuperUserCredentialAuthFilter now sets a marker only after a live super-key resolves ACTIVE; the
actuator gate requires that marker, so a JWT-borne (or revoked) SUPERUSER role no longer opens
metrics. RED-first: a role-only claim now 403s.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-22`

### REG-23 — tv-less (tokenVersion-less) JWTs are never revocation-checked, by backward-compat design

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** UNIT_TESTED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Owner decision: config-driven cutover rather than an immediate hard break. The revocation decision
is centralized in IdentityRoleLookup.isTokenRevoked, the single point both claim-to-context paths
call, so they cannot diverge. New config npdev.auth.jwt.reject-tokens-without-tv-after (ISO-8601
instant, default off = today's lenient behavior); once reached, tv-less tokens are rejected on both
paths. Bridged Spring->system-property by TvlessTokenCutoverBridge (fails fast on a malformed
value). Verified: 4/4 IdentityRoleLookupTvlessRevocationTest.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-23`

### REG-24 — "default" tenant sentinel collides with a real tenant literally named default

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

Verified already comprehensively guarded, no change needed. All three tenant-insert paths already
reserve "default": TenantRegistryService.create rejects it, IdentityProvisioning
.ensureTenantRegistered skips it, TenantAutoRegistrationRunner's SQL excludes it. No real "default"
tenant can ever be created, so the isolation collision this finding worried about cannot arise.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-24`

### REG-25 — Tenant match was case-sensitive -- isolation-bucket fragmentation (not a cross-tenant bypass)

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md)
**Surface:** `runtimehost/auth`

The core write-path normalizers only trimmed tenant_id (did not lowercase); only peripheral sites
lowercased -- so business data could land under "Acme" while the registry stored "acme". Fix:
canonicalize tenant_id to lowercase at the single choke point every read/write derives its tenant
from -- ExecutionContext's compact constructor (actorId stays case-sensitive; the reserved "default"
sentinel unaffected). Proven RED->GREEN via ExecutionContextTenantCanonicalizationTest; full
RuntimeHost gate green. Existing-data fix: scripts/ops/canonicalize-tenant-ids.ps1 (dry-run
default, -Apply, -Force) lowercases tenant_id across the registry + every business table, with a
collision detector that skips + reports merge-risk tables unless -Force. Proven end-to-end on a
seeded H2 DB.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-25`

### REG-26 — Granular JWT error codes disclose why a token failed validation (informational, WONTFIX)

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** NOT_VERIFIED
**Source:** REG-16 Tier-A adversarial review (docs/archive/programme-history/REG16_TENANT_AUTH_ADVERSARIAL_REVIEW.md), severity INFO in the original register (this schema has no INFO level; mapped to LOW)
**Surface:** `runtimehost/auth`

WONTFIX. Standard practice; the error codes name the validation reason (expired / bad issuer / bad
signature), not any secret or account state, and materially aid operator/integration debugging.
Collapsing to a single generic error would trade real diagnosability for negligible disclosure
reduction.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-26`

### REG-27 — REG-8 Trigger C false-negative for a fresh-installed build (rollback silently re-added a dropped column)

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** UNIT_TESTED
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

Trigger C (databaseMigratedPastThisBuild) only fired if the rolled-back-to build's fingerprint had
a PRIOR APPLIED/MANUALLY_MARKED_DONE row in npdev_schema_history. A build reached by FRESH INSTALL
never had one (the blank-fingerprint boot writes no history row), so the register's own canonical
example -- fresh-installed build N, N+1 drops a column, roll back to N -- was NOT actually refused;
the dropped column was silently re-added empty. The headline test had only passed because it
hand-seeded a history row a real fresh install never writes. Fix: afterMigrate now records the
initial realization as an APPLIED history point on the fresh-install path too, so every fingerprint
the DB has genuinely been at is visible to Trigger C. RED-first: two new tests in
SchemaLifecycleExecutorDatabaseMigratedPastBuildTest (a direct fresh-install-records-history
assertion, and the honest end-to-end with no hand-seeded row).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-27`

### REG-28 — Stale mark-done fast-forward (REG-7.2): a leftover mark could authorize an unrelated future boot

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

MigrationMarkStore recorded only the target fingerprint, no from-fingerprint binding and no TTL. A
leftover mark for X (a deploy planned then abandoned) would silently authorize the first future
boot whose target was X, from whatever the DB actually was at, fast-forwarding with zero
migration/classify/Trigger-C passes. Fix: MigrationMarkStore now binds every mark to a
(from_fingerprint, marked_fingerprint) pair; findMatching only returns a mark when the boot's own
live stored fingerprint equals the recorded "from". SchemaAcknowledgmentController#markDone takes
fromFingerprint/toFingerprint. RED-first SchemaLifecycleExecutorMigrationMarkTest proves a mark
recorded for from=A does not fire when live-stored is Z, and does fire when live-stored is A.
Verified live: real boot rehearsal against superuser-admin-console confirmed both the non-firing and
firing cases.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-28`

### REG-29 — Claim-release-on-refusal was correct but untested (migration collision claim)

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-22)
**Verification:** UNIT_TESTED
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

The production `finally` in migrate() does release the boot's own claim on a refusal thrown from
inside the migration body (Trigger C, destructive-without-token) -- verified correct by reading, but
no test proved it: the existing "refuses" test failed at claim ACQUISITION (a PK collision), where
the boot never held a claim in the first place -- the wedge-risk property that matters most was
unverified. No production change needed. Added
refusalWhileHoldingOwnClaimStillReleasesIt to SchemaLifecycleExecutorMigrationClaimTest: seeds
Trigger C's canonical shape so beforeMigrate throws from inside migrate's try block AFTER this
boot's own claim was acquired, asserts the throw and that the claim store is empty afterward.
RED-first: verified the test fails when the finally's release is neutralized, passes with the real
code.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-29`

### REG-3 — GATE-REL-1: node_modules/slimness conflict was already fixed; the real gap was stale evidence reports

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `quality-gates/release-gate`

Original claim: run-beta-release-gate.ps1 structurally cannot pass because json-schema-validator's
node_modules can't be committed under the workspace slimness policy. Independent verification found
this premise stale -- commit 437d19b (2026-05-14, two months before this register) already moved
that runtime outside the repo, and Test-WorkspaceSlimness.ps1 never scans that external location.
There was no conflict. The gate DID exit 1 (35 of 36 required evidence reports missing), but the
real cause is a report-orchestration/staleness gap: the constituent evidence-generating scripts
simply hadn't been run recently. Fix: added run-beta-release-evidence-orchestration.ps1 (runs all
~18 producers in dependency order sharing one runId) + opt-in -GenerateReports; the gate now
distinguishes precondition-unmet (exit 2) from check-failed (exit 1). Found and fixed a producer
that could only ever emit passing evidence, plus its stale fixture that had silently disabled the
model-root additionalProperties guard.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-3`

### REG-30 — Duplicate mark-done rows each survive one consume, letting a second future boot fast-forward

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** Independent code verification of the REG-7/REG-8 implementation, 2026-07-22
**Surface:** `runtimehost/schema-lifecycle`

Two marks for the same fingerprint: consume() deleted only the matched row, so the older duplicate
survived to fast-forward a second future boot at that fingerprint. Folded into the REG-28 fix: a
unique index on (from_fingerprint, marked_fingerprint) rejects a duplicate mark for the identical
transition at insert time. Verified live against superuser-admin-console: re-POSTing an identical
(from, to) pair via the real ControlPanel API returned 500 and GET /marks still showed exactly one
row. Unit coverage: duplicateMarkForTheSameTransitionIsRejected.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-30`

### REG-31 — run-script-automation-quality's structured-report-contract check was mis-calibrated (helper-name grep, not a behavior test)

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** Quality-gate calibration review, 2026-07-24
**Surface:** `quality-gates/script-automation-quality`

The check greped script SOURCE for the literal helper names Invoke-NPDevReportedCommand/
Write-NPDevJsonFile and failed any of ~68 scripts lacking them -- flagging 59, a helper-name
presence test, not a report-behavior test. Spot-checked 9 of the 59: 56 of 59 persist a genuinely
valid structured JSON report by other means (direct ConvertTo-Json | Set-Content to the standard
report-path convention). Only 3 were genuinely non-compliant. Fix: the sub-check now tests actual
behavior (serializes to JSON AND persists it AND targets the standard report-path convention, by
ANY mechanism). The 3 genuinely non-compliant scripts excluded via a dated backlog list, not
silently dropped and not mass-migrated. Verified locally: exits 0, 65/65 scoped scripts pass (was
9/68). CI's continue-on-error removed -- blocking again.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-31`

### REG-32 — npdev-ci-validation.yml Bootstrap step aggregated ~21 maturity reports its producers never generated

**Type:** PROCESS · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** CI evidence-orchestration review, 2026-07-24
**Surface:** `ci/maturity-bootstrap`

Closed for the PowerShell bootstrap chain; residual Gradle-native gap filed separately as REG-35.
The Linux job's bootstrap step AGGREGATES ~21 maturity reports and hard-fails if any are missing or
schema-invalid, but does not GENERATE them -- ~19 were precondition-unmet (producers never run),
plus one genuinely schema-invalid report. Fix (both halves): (1) bootstrap-post-beta0-reports.ps1,
validate-report-schemas.ps1, and generate-final-evidence-bundle.ps1 now distinguish
precondition-unmet (exit 2, non-fatal) from check-failed (exit 1); also fixed a real bug in
validate-report-schemas.ps1 conflating "never produced" with "produced but wrong". (2) Fixed the
one real schema-invalid report (stateful-additive-migrations-report.json): two real defects, a
wrong directory-walk depth in the XML resolver, and a schema requiring const:true on 8 fields a
prior fix had deliberately retired to false. Verified: errorCount 0 (was 10). CI step's
continue-on-error kept intentionally (not removed) since REG-35's Gradle-native residual still
trips on the same tree.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-32`

### REG-33 — CLI's on-demand npm install for the JSON-schema validator failed on Windows from a Python subprocess

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** CI Windows diagnostic capture, 2026-07-24
**Surface:** `cli/json-schema-validator`

`npm --prefix <validator> install` run with cwd=repo-root makes npm read package.json from cwd (the
repo root has none) -> ENOENT on the CI Windows npm; --prefix only sets where node_modules lands,
not where npm reads the manifest. Fix: npdev_cli.py now runs npm install with
cwd=validator_root (no --prefix). Verified locally RED->GREEN: removed node_modules, ran
`npdev migrate`, install ran from the validator dir, exit 0. CI also pre-installs the deps in the
Windows job as belt-and-suspenders.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-33`

### REG-34 — Windows CI job runs Testcontainers (Linux-container) tests that windows-latest can't run

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Surfaced by REG-17 once Fix A + REG-33 unblocked the Windows job's downstream gates; re-audited docs/CORPUS_INTEGRITY_PLAN.md C10
**Surface:** `ci/windows-job-scoping`

2026-07-29 re-audit (C10): re-checked the "remaining" gates this item left open (Security
hardening, Runtime security, RuntimeHost gate, Editor gate) by tracing every actual command the
Windows job in npdev-ci-validation.yml runs today, and cross-referencing every real Testcontainers
user in the repo -- not just re-asserting the original "iterate as they surface" plan.
Found, measured live: repo-wide, exactly one class carries a real `@Testcontainers`/`@Container`
annotation (`AbstractScenarioIntegrationTest`, NPDevRuntimeHost) and three more directly
instantiate `PostgreSQLContainer` (`ConversionHookRunnerPostgresTest`,
`SchemaLifecycleExecutorPostgresProofMatrixTest`, `CurrentSchemaReaderPostgresTest`). None of the
four is reachable from anything the Windows job actually runs: the abstract class's subclasses
live in NPDevRuntimeHost's separate `integrationTest` Gradle source set/task (`includeTags
'integration'`), which `check` does not depend on and which is documented as nightly-only, not
wired into any step here; the three Postgres classes are excluded from the default `test` task
unless `-PincludePostgresMatrix` is passed (`NPDevRuntimeHost/build.gradle`), which no step in
this workflow does. The Windows job's only real `gradlew check`/`test` invocation is scoped to
`NPDevContract\dsl` alone (the "DSL contract check" step, `working-directory: NPDevContract\dsl`),
which has zero Testcontainers usage; "Security hardening"/"Runtime security"/"RuntimeHost gate"/
"Editor gate" all run PowerShell-orchestrated sample-generation/surface-evidence scripts
(run-runtimehost-gate.ps1 et al.), not a raw module test suite.
Net: as measured today, no Linux-container test is reachable from anything the Windows job
invokes -- via Gradle task/source-set separation, not `@DisabledOnOs` sprinkled per-test (the
original fix approach). The one instance that WAS fixed with `@DisabledOnOs` (the generator
gate's MinIO packaged-app proof test) stays as-is; it is not wrong, just not the mechanism that
turned out to matter for the rest. Residual, explicitly not eliminated: this is a measurement of
the CURRENT wiring, not a standing gate -- a future change that adds a new Testcontainers test
reachable from the Windows job's actual commands, or that wires `integrationTest`/
`-PincludePostgresMatrix` into this workflow, would need the same check re-run by hand. Building a
permanent gate for that was judged out of scope for this LOW item (matches C10's own "no action
expected" framing) rather than folded in here.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-34`

### REG-35 — Gradle-native postBeta0MaturityCheck had the same missing-vs-invalid conflation REG-32 fixed in PowerShell, plus an overly strict nested artifact schema

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Discovered as a byproduct of verifying REG-32's fix, 2026-07-24 -- pre-existing, not caused by that work; fixed docs/CORPUS_INTEGRITY_PLAN.md C9
**Surface:** `ci/maturity-bootstrap`
**Files:**
- `build.gradle`
- `schemas/ai/final-evidence-bundle-manifest.schema.json`
- `.github/workflows/npdev-ci-validation.yml`

Both originally-scoped gaps fixed 2026-07-29: (1) build.gradle's validateReports task now treats
a missing report file as precondition-unmet (not a failure), and also recognizes an EXISTING
report's own `overallStatus: "precondition-unmet"` (the REG-32 pattern any of the 7 producers may
itself use) as non-fatal -- verified live: passed=true, failures=[], with all 7 pairs correctly
classified as preconditionUnmet, none as failures (was: unconditional failure on any missing
file). (2) final-evidence-bundle-manifest.schema.json's artifacts[] items now accept the shape a
never-generated report legitimately has (bytes:0, sha256:"", schemaVersion:"",
overallStatus:"missing"/"missing-status") instead of requiring bytes>=1 and a real sha256 on every
one of the required 21 slots regardless of whether its producer ran -- verified live against a
fresh manifest (18/21 reports missing): errorCount 0 (was 76, exactly 19 missing x 4 violated
constraints at the time of the original finding).
A third, unrelated false positive turned up verifying the fix end to end (running the full
`postBeta0MaturityCheck` chain, not just validateReports in isolation): validateBoundaryLocks'
own hardcoded-drive-letter-path scan (a CP5-era portability check) matched a code COMMENT
describing a Windows path, not an actual embedded one, in
.github/workflows/npdev-ci-validation.yml. The scan reads raw file text with no comment-awareness,
so any future comment mentioning a drive-letter path would trip the same false positive again --
not re-architected here (out of this item's own scope), just reworded past this one instance.
`postBeta0MaturityCheck` now runs green end to end locally, all 6 tasks. CI step's continue-on-
error kept intentionally: the same shell step also runs 4 other commands
(`npdev report bootstrap`, validate-report-schemas.ps1, generate-final-evidence-bundle.ps1,
run-portable-tooling-check.ps1) whose own precondition-unmet-vs-exit-code handling was not
re-audited here -- flipping the flag for the whole step is a separate decision.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-35`

### REG-36 — Oversized idempotency keys could exceed the Postgres btree index-entry size limit

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 1 kernel-execution adversarial review, finding REG16K-F1
**Surface:** `kernel/idempotency`

A new IdempotencyKeys.bound(...) digests a key above 200 chars to npdev-sha256$<hex>, applied in
both stores at their key chokepoints. A naive digest would have introduced a NEW collision the
original bug didn't have (a caller submitting the literal short string "sha256(X)" would land on
X's record) -- fixed by also digesting any short key that already starts with the reserved prefix.
Correction found while building the control: the real trigger is size AFTER compression (an
oversized but highly compressible key inserts fine); verified on a real Postgres container: 8,000
incompressible chars throws, the compressible twin does not. idempotency-postgres was the ONLY
*-postgres adapter with no postgres-test-support dependency at all -- its one test ran H2 in
PostgreSQL mode, which does not enforce this limit; that gap is why the bug shipped and is now
closed (a real-Postgres PostgresIdempotencyKeyBoundTest added). Tests: IdempotencyKeysTest (6),
InProcIdempotencyStoreTest (+3).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-36`

### REG-37 — Circuit-breaker failure-count read-decide-write was not a single atomic critical section

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 1 kernel-execution adversarial review, finding REG16K-F2
**Surface:** `kernel/circuit-breaker`

The transition rule moved into a pure function CircuitBreakerTransitions.afterFailure, and the
read-decide-write is now one critical section owned by the store: ConcurrentHashMap.compute
in-proc, SELECT ... FOR UPDATE inside a transaction on JDBC. A SELECT ... FOR UPDATE cannot lock a
row that doesn't exist, so two concurrent FIRST failures would both compute 1; the JDBC path seeds
a CLOSED/zero row before locking -- that seed must NOT go through the upsert-despite-its-name
insertOrIgnore helper, which reset the counter on every call (caught by a test asserting the
counter's real value, not just its presence). RED->GREEN proven by reverting both stores to the
interface's documented non-atomic default: concurrency tests fail, deterministic lifecycle tests
still pass. Tests: InProcCircuitBreakerStateStoreTest (8 threads x 200),
JdbcCircuitBreakerStateStoreConcurrencyTest on H2, PostgresCircuitBreakerStateStoreTest on the real
engine.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-37`

### REG-38 — Additive-migration constraints were not idempotent on H2 -- redeploy failed with duplicate constraint

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** Discovered live while rebuilding WmsOffice with a new field (ARCH-upload P6)
**Surface:** `runtimehost/schema-lifecycle`

SchemaRealizationEmitter.addConstraintIfMissing wrapped Postgres's ADD CONSTRAINT in an IF NOT
EXISTS catalog guard but the H2 branch emitted a bare ADD CONSTRAINT. That statement lands in a
Flyway REPEATABLE migration that re-runs whenever its checksum changes (i.e. after any model edit),
so redeploying a changed model against an existing H2 DB failed at boot with "Constraint already
exists" and refused the whole application. Fix: the H2 branch now emits
ALTER TABLE ... DROP CONSTRAINT IF EXISTS <name> before the ADD (both verbs supported on H2 and
Postgres; Postgres path unchanged). RED->GREEN SchemaRealizationEmitterAdditiveColumnsTest; verified
live -- WmsOffice now boots cleanly against the same existing DB that previously refused.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-38`

### REG-39 — Stale built-in identity pack copy caused a silent, unhelpful auth failure -- fixed platform-wide

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** WmsOffice app-scope incident, generalized to a platform hazard
**Surface:** `runtimehost/identity-pack`

An app carrying a stale built-in-pack copy (missing a field like tokenVersion the pack now
declares) would boot then fail auth with a generic error, not a diagnosable one. Fixed platform-wide
via three layers: (1) detect -- StartupValidator.validateIdentityPackFreshness fails fast at boot
naming the pack/concept/missing-field/fix; (2) stop swallowing -- the 4 real SQL touchpoints reading/
writing token_version now distinguish a genuine schema-mismatch SQLException from a routine
negative via a new IdentityPackSchemaException, so a missing column produces a distinct diagnosable
error instead of a generic auth failure; (3) surface pre-deploy -- the same drift check folds a
synthetic NEEDS_HOOK item into the Impact Report, so -ImpactOnly / ControlPanel report
NEEDS_ATTENTION for a stale pack copy without needing a boot. Verified: a live proof stripped
tokenVersion from the platform's own identity pack, regenerated an app against a virgin DB, booted
-- reproduced the exact intended failure naming pack/concept/field/fix; clean revert + reboot
confirmed no false-positive on a healthy pack. A full rebuild-app run against WmsOffice confirmed no
regression on the originally-affected app.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-39`

### REG-4 — T-F1: load-sensitive SandboxedPluginExecutionEngine test flake, root cause fixed

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `kernel/plugin-execution`

SandboxedPluginExecutionEngineTest failed roughly 1 in 5 runs under parallel load, 0 in 5 in
isolation. Reproduced the flake DETERMINISTICALLY with a new test
(timeoutIsNotCorruptedByAPreExistingCallerInterrupt, RED 100%) instead of waiting for a suite-load
reoccurrence. Root cause: future.get(timeout) runs on the calling thread, and a stray interrupt left
by a prior test on the same worker thread made it throw InterruptedException before the timeout.
Fixed in SandboxedPluginExecutionEngine.execute (read-and-clear a stray caller interrupt around the
bounded get(), re-assert it after) -- an engine robustness fix, not a tolerance widening. Removed
@Tag("load-sensitive") from timesOutSlowPluginExecution; 6/6 green live.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-4`

### REG-40 — Additive migration never emitted CREATE TABLE -- a new concept on an existing DB failed to boot

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** Schema-engine rebuild plan Part II fast track
**Surface:** `runtimehost/schema-lifecycle`

The additive/repeatable migration only ever emitted ALTER TABLE ... ADD COLUMN/ADD CONSTRAINT (zero
CREATE TABLE), so a new concept/table added to a model then redeployed against an EXISTING DB failed
at boot with "Table not found" (the versioned V1 CREATE-TABLE migration had already run and doesn't
re-run). Fix: SchemaRealizationEmitter's R__ assembly now emits, in order, (1) CREATE TABLE IF NOT
EXISTS for every business + junction table, (2) additive ADD COLUMNs, (3) unique/index/FK
constraint blocks -- all idempotent, so a missing table now self-heals on upgrade exactly like a
missing column already did. RED->GREEN SchemaRealizationEmitterAdditiveColumnsTest; proven
end-to-end against real Flyway migrations on both engines (a new
SchemaLifecycleExecutorNewTableOnExistingDbTest on H2, a Postgres Testcontainers proof-matrix
scenario): boot with a 1-concept model, insert a row, upgrade to a 2-concept model against the SAME
database -- the new table exists empty, the old row survives, schema history records APPLIED not a
refusal. Orthogonal to REG-38 (that was constraint idempotency on EXISTING tables).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-40`

### REG-41 — DefaultConceptGateway.save() leaked a row's lifecycle status to an unauthorized caller before authz ran

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 2 LNCH-13 row-level authz adversarial review
**Surface:** `kernel/concept-gateway`

save() ran enforcePermission/enforceRowWritable AFTER runWriteSemantics/validateLifecycleTransition
touched the previous record's data. A caller with zero concept.write permission and zero
access.write row-scope could submit an unreachable lifecycle-transition target and learn the row's
real current status from the resulting error's "from" detail, since neither authorization gate had
run yet. Fix: reordered so the authorization gates run BEFORE the semantic-validation use of the
previous record's data (the previous-record fetch itself stays, still needed for the row-scope
check). RED->GREEN RowLevelAuthorizationAttackTest (both InMemory and JDBC/H2 adapters): confirmed
RED pre-fix (leaked the status via the lifecycle exception), GREEN after (ROW_SCOPE_DENIED, no
status disclosed).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-41`

### REG-42 — ConceptGateway.query() leaked a row-scoped count through total/hasMore pagination metadata

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 2 LNCH-13 row-level authz adversarial review
**Surface:** `kernel/concept-gateway`

total/hasMore were computed by the store BEFORE row-scope filtering, leaking the count of rows
outside the caller's access.read scope through pagination metadata even though the items array
correctly hid them. Fix: a new ConceptGatewaySemanticPolicy.hasRowReadScope(conceptName) (default
false) lets query() pay an extra bounded re-query cost only for concepts that actually declare
access.read -- an unpaged re-query (bounded by the existing MAX_LIMIT ceiling) with the same
filters/sorts, row-scope filtered, replaces total/hasMore; every other concept's query() is
unaffected. RED->GREEN: extended an existing test to assert total==1/hasMore==false (not the
tenant's real count of 2), confirmed RED pre-fix, GREEN after, both adapters.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-42`

### REG-43 — TenantRegistryService.isActive silently fail-opened on any read failure, with no log at any level

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** First run of scripts/quality/security-pattern-sweep.py (docs/SECURITY_PATTERN_SWEEP_2026-07.md §3)
**Surface:** `runtimehost/tenant-registry`

isActive (reached from TenantStatusFilter, the sole per-request chokepoint gating tenant disable)
ended `catch (SQLException e) { return true; }` with no log at any level -- once a DataSource
existed, any read failure (dropped table, exhausted pool, mid-migration rename) silently returned
every explicitly DISABLED tenant to full service, undetectably. MED not HIGH: needs BOTH an
operator-disabled tenant AND a concurrent DB fault; an attacker cannot trigger it, and a disabled
tenant still needs valid signed credentials. Fix is not blanket fail-closed (would brick any app
legitimately without an npdev_tenant table): missing-table SQLState -> fail OPEN, log INFO,
unchanged behavior; any OTHER SQL error -> fail CLOSED, log ERROR. RED->GREEN
TenantRegistryServiceTest (+3): exactly one test RED against pre-fix code, missing-table fail-open
test stays green, proving the fix discriminates rather than flipping everything closed.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-43`

### REG-44 — crud.kernelControlled=false silently removed ALL coarse permission/audit checks, not just access.write

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 3, R3-F3
**Surface:** `generator/authorization`

A model declaring access.read/access.write while crud.kernelControlled resolves false silently
removed every coarse CRUD permission check (READ/LIST/CREATE/UPDATE/DELETE) and mutation audit
across 13 emission sites -- not just access.write as the original wording assumed. Row-level
access.read survives (generated reads go through conceptGateway unconditionally), which is exactly
the asymmetry that made the combination look harmless when spot-checked. Fix: new
UnenforceableAccessRuleCheck, run from GeneratorFacade before any emitter, refuses to generate a
model with this contradiction. Not visible to SemanticValidator alone: the validator sees only the
model, while crud.kernelControlled comes from config.json -- the contradiction is only visible where
compiled model and resolved settings meet. Resolved per-concept (overridable at concept scope; an
app-level read would miss a targeted opt-out). 5 tests incl. the concept-scoped override and an
end-to-end check that nothing is emitted when generation is refused.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-44`

### REG-45 — Flow resume was tenant-scoped but not actor-scoped -- any same-tenant user could resume another's flow

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** UNIT_TESTED
**Source:** REG16-resid Round 4 flow/await orchestration adversarial review, R4-F1
**Surface:** `kernel/flow-orchestration`

DefaultExecutionAuthorizationPolicy.canResumeExecution now requires the same tenant AND that the
requester is the actor who started the flow. FlowInstance already carried actorId, so no schema/
contract change was needed. An instance with no recorded actor stays tenant-scoped only -- a blank
actorId is what a flow started anonymously, by the cron scheduler, or before this field existed
looks like; requiring equality against null would make every one of those permanently unresumable,
turning a data-scoping fix into an availability regression for exactly the stuck flows an operator
most needs to recover. Verified before tightening that only the HTTP resume endpoint consults this
policy -- the kernel's event-driven and scheduler resume paths do not -- so background recovery is
unaffected. 2 new tests; the pre-existing resume test still passes unchanged.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-45`

### REG-46 — Persistence capability port had no tenant parameter -- flow-step persistence writes were unscoped

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** VERIFIED_LIVE
**Source:** REG16-resid Round 5 durable-state-adapter SQL adversarial review, R5-F1
**Surface:** `kernel/persistence-port`

Generated CRUD was tenant-scoped but the persistence capability PORT itself carried no tenant
parameter, so the flow-step persistence route was unscoped -- the same hole existed in BOTH adapters
(in-memory and JDBC), meaning this was a port-level gap, not a backend difference, and dev/prod would
disagree about visibility only by accident. Fix: a new TenantScopedPersistenceCapabilityContract
beside the unchanged PersistenceCapabilityContract; both adapters implement it;
RegistryCapabilityDispatcher prepends the executing tenant from the flow's authenticated state --
supplied by the runtime, never declared by the model author (letting the author choose the tenant
would look enforced while being weaker than no scoping at all). Two real signature/dispatch
collisions found and resolved during implementation (a String-vs-TenantScope arity clash that had
been silently mis-routing/self-recursing, and an identical reflective-dispatch collision resolved
against the interface). Scoping applied only where a tenant_id column actually exists (read from the
live catalog). 7 tests incl. delete-is-not-an-existence-oracle and save-stamps-ownership-over-a-
payload-claim.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-46`

### REG-47 — Correlation ids had no length cap -- an oversized caller-chosen id could hit the same btree limit as REG-36

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-25)
**Verification:** UNIT_TESTED
**Source:** Security-pattern-sweep closure round, 2026-07-25
**Surface:** `kernel/correlation-ids`

New CorrelationIds.require(...) caps a correlation id at 400 characters, called from
KernelRunner.normalizeCorrelationId -- the single chokepoint every correlation id passes on its way
into durable state, reached before the event envelope is built or flow state initialised, so nothing
is published/executed/persisted first. Rejects rather than digests (unlike REG-36) for two reasons:
a correlation id is caller-chosen tracing metadata with no legitimate oversized form, and callers
look it up again via @PathVariable on timeline/event-query controllers -- digesting would store an
id different from the one the caller holds. 6 tests incl. the exact boundary, trim-before-measure,
and a guard that the ceiling stays inside the btree limit alongside its composite-index companions.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-47`

### REG-48 — DefaultConceptGateway.delete() had the same authz-after-invariant-eval ordering bug REG-41 fixed in save()

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** First real ADR-0009 external-AI mission (M2-SEC-ROWAUTHZ), gemini finding F1
**Surface:** `kernel/concept-gateway`

delete() ran evaluateRuleProfiles (concept invariants against the previous record's data) BEFORE
enforcePermission/enforceRowWritable -- the identical bug class REG-41 already fixed in save(),
never applied to delete(). Fix: reordered delete() so the authorization gates run before
evaluateRuleProfiles, keeping the existing previous-record fetch (still needed for the row-scope
check) -- a literal mirror of REG-41's save() fix. RED->GREEN: a dedicated Vault concept with a
locked=='false' invariant, seeded LOCKED directly through the store; confirmed RED pre-fix (leaked
the vault's locked state to a caller with zero delete access via the invariant exception), GREEN
after (ROW_SCOPE_DENIED, invariant never evaluated). delete() is store-agnostic, so InMemory+H2
coverage is the complete adapter matrix for this bug. Re-verified live against the platform source
directly (never exposed to the REG-49 staleness class).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-48`

### REG-49 — M1-SEC-GENCODE finding withdrawn as a false positive -- the reviewed pack was stale, not the platform

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** First real ADR-0009 external-AI mission (M1-SEC-GENCODE), gemini finding F1
**Surface:** `process/external-ai-review`

WITHDRAWN as a false positive, not fixed as a bug. The reviewed pack's generated Java was 62 minutes
OLDER than the LNCH13-F1 fix commit it was reviewed against -- the vendor correctly identified
LNCH13-F1's exact shape, in code where LNCH13-F1 had not yet been fixed. On code generated AFTER the
fix, every mutation arm on both flow-backed concepts is properly guarded. Root cause (pack
provenance was unrecorded) tracked and fixed as REG-51 so this false-positive class cannot recur
silently. One genuine residual checked as part of the withdrawal: no previously-verified concept had
exercised a DELETE-backed flow specifically (only create/update). A careful manual trace of the real
generated exception hierarchy found the delete arm's structural shape differs from create/update
(unconditional gateway call, not an either/or swap) so it was never actually exposed to this bug
class -- but this was a manual trace, not an automated runtime assertion. Later closed for real
(2026-07-27): a new ServiceBaseDeleteFlowRowLevelAuthzBehaviorTest generates a real ServiceBase for a
delete-mode flow, compiles it for real, and runs it against real (not mocked) gateway/policy/kernel
components, asserting the flow's own execute() call never happens when the gateway denies. RED->GREEN
confirmed twice by temporarily reordering the mustache template.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-49`

### REG-5 — GATE-OBS-1a: surface-governance drift checks were advisory and unowned

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-21)
**Verification:** NOT_VERIFIED
**Source:** LNCH-1 programme inheritance, re-verified 2026-07-21
**Surface:** `quality-gates/runtimehost-gate`

The RuntimeHost gate's surface-convergence/exclusivity checks encoded a pre-d0bf41b
"package == support bucket" convention the beta-0 manifest refactor replaced with exact allowlists.
They had been demoted to advisory-only (-PendingOk) so the gate's exit code stayed truthful, but the
underlying drift was unowned. Decision (owner): formal RETIREMENT over the plan's default option --
a concrete check confirmed the exact-list allowlist (runtime-surface-allowlist-report.json, backed
by RuntimeControllerAllowlistConfig) already IS the blocking exact-list enforcement, so the 6
package-convention checks were a redundant proxy that would only duplicate the allowlist if
rewritten. Retired to informational-only (reversible) with a dated rationale in
run-observability-hardening.ps1, run-runtimehost-gate.ps1, and OPEN_GAPS_AND_ROADMAP.md.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-5`

### REG-50 — PostgresPersistenceCapabilityAdapter fell back to UNSCOPED reads/writes on a transient metadata-read failure

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** First real ADR-0009 external-AI mission (M3-SEC-TENANT), gemini findings F1+F2 (same root cause)
**Surface:** `kernel/persistence-postgres`

TableColumns.unavailable() was returned both on a genuine SQLException AND on "this table
legitimately has no such columns" -- indistinguishable, so a transient metadata-read failure on a
tenant-scoped table silently fell back to the UNSCOPED findById/delete/exists overloads instead of
failing closed. Fix (a): TableColumns is now tri-state (a distinct queryFailedResult()), set only on
a genuine thrown SQLException; a new enforceMetadataAvailableForTenantScoping throws before the
tenant-scoped overloads ever consult hasColumn -- fails closed only when scoping status is genuinely
unknown, matching REG-43's precedent (blanket fail-closed was rejected there for the same reason).
Fix (b): the unavailable-metadata fallback in identifier resolution now routes through the
platform's existing safe-identifier whitelist instead of an unsanitized path. RED->GREEN, all
against a REAL Postgres container (the REG-36 lesson -- H2-in-PG-mode wouldn't have caught this
either): a Proxy-wrapped Connection whose getMetaData() throws confirmed RED on all 3 tenant-scoped
methods pre-fix (silently fell back, no denial) and on a hostile-identifier case (a real Postgres
syntax-error confirmed), GREEN after (all 3 throw naming the table/operation; the hostile field name
coerces to a syntactically valid but nonexistent column). (c) split out as REG-52.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-50`

### REG-51 — External-AI review packs sliced from generated code carried no provenance -- exactly how REG-49 became a false positive

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** REG-49's own root cause, docs/archive/programme-history/REG48_50_CLOSURE_PLAN.md §2 (B1)
**Surface:** `process/external-ai-review`

A pack sliced from a GENERATED app's already-emitted code recorded no provenance at all -- nothing
distinguished "reflects the current generator" from "emitted before a relevant template fix landed",
which is exactly how REG-49 became a false positive (62 minutes stale). Fix: a new
resolve_provenance() -- for a --repo-root outside the platform repo, walks upward for the sliced
app's own build-info to read its real generation timestamp, computes the newest commit touching the
generator's templates/emitters via git log, and REFUSES the pack build outright (no pack written)
when the generated code predates that commit -- owner's explicit choice over warn-and-proceed, so
this false-positive class cannot recur silently. source.kind is now "generated-app" for this case
(previously miscategorized). Verified both directions on real artifacts: re-running the exact stale
slice that produced REG-49 now refuses with a message naming the stale-vs-fix gap; a freshly
regenerated sample builds cleanly. A secondary defence-in-depth gate (provenance_audit_gaps in
check-register-consistency.py) flags EXISTING run records with unresolved provenance when backing
evidence is still available locally -- never flagging a record whose evidence is simply absent.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-51`

### REG-52 — TenantIsolationPolicy.STRICT_EQUALS normalize() only trimmed, never lowercased -- inconsistent with ExecutionContext

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** M3-SEC-TENANT mission, gemini F3 -- filed separately from REG-50, not buried in its prose
**Surface:** `kernel/tenant-isolation`

STRICT_EQUALS.normalize() only trimmed (case-sensitive) while ExecutionContext.normalizeTenantId()
lowercases (per REG-25) -- a real inconsistency whenever STRICT_EQUALS compared a context-derived
tenantId (normalized) against a per-request tenantId that bypassed ExecutionContext's constructor.
Direction was fail-closed (a spurious case mismatch denied rather than wrongly allowed), so this was
a correctness/availability gap, not a security hole. Fix: normalize() now also lowercases, matching
ExecutionContext's REG-25 canonicalization exactly. RED->GREEN using the REAL STRICT_EQUALS (not a
case-sensitive test-double lambda used elsewhere in the same file): context tenant "Acme" (normalized
to acme), request tenant "ACME" (raw, unnormalized); confirmed RED pre-fix (denied a same-tenant
read), GREEN after.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-52`

### REG-53 — SqlTypeSupport hardcoded VARCHAR(255) for every string/enum field, ignoring a declared maxLength

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-27)
**Verification:** VERIFIED_LIVE
**Source:** Session live-code trace, 2026-07-27
**Surface:** `dsl/sql-type-mapping`

SqlTypeSupport.sqlType -- the single shared mapper feeding generator DDL, bond DDL, and database-
definition fingerprints -- mapped every string/enum field to a literal hardcoded VARCHAR(255),
never consulting the compiled maxLength. DefaultSchemaValidator DID enforce a declared maxLength at
write-time input validation, so any string field declared with maxLength>255 let the validator
accept input the database column was never actually widened to hold -- a real hard-failure
production mode ("value too long") with zero warning anywhere in the schema-evolution tooling (no
Impact Report entry, no migration-plan diff). Genuinely undiffed, not a documented design boundary.
Fix: SqlTypeSupport.sqlType now honors a declared maxLength for string/enum fields via a new
varcharType helper -- VARCHAR(<maxLength>) when declared, the same VARCHAR(255) default when not (no
existing model's DDL/fingerprint changes). No changes needed to SchemaDiffEngine/TypeChangeMatrix --
both already correctly compare and classify a VARCHAR(n)->VARCHAR(m) change once given two genuinely
different type strings; the bug was entirely upstream. RED->GREEN: a new test drives the REAL
pipeline end-to-end (not hand-written type strings) -- confirmed RED pre-fix (a 255->10 narrowing
produced NO diff item at all), GREEN after (correctly classified as a destructive narrowing).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-53`

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

### REG-6 — ColumnFacts: eight SchemaLifecycleExecutor passes each re-derived column semantics independently

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-24)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance; fully closed via the Schema Engine Rebuild
**Surface:** `runtimehost/schema-lifecycle`

SchemaLifecycleExecutor's roughly eight passes (relax, tighten, backfill, additive, delta-report,
classify, bond-refusal, rename, unique-constraint) each performed their own set arithmetic over the
same raw manifest to answer the same questions (is this column platform-managed? additive-eligible?
required?), with three-to-four overlapping and divergent notions of "platform column". This was the
root cause behind repeated prior findings (T-B1, T-B2): each round fixed one pass's inference while
the structure that produced the wrong inference stayed untouched. Initially closed re-scoped
(2026-07-22, risk-core only: a ColumnFacts projection + a class-load drift-guard, full set-algebra
purity deferred). FULLY closed 2026-07-24 via the Schema Engine Rebuild: a single canonical
CurrentSchema/DesiredSchema/SchemaDiff model (SchemaDiffEngine) is now the ONE place column
semantics are derived, consumed by both decision surfaces and all four mutation passes, built
strangler-fig with a proven 100% behavior-equivalence shadow-parity gate on H2 + Postgres before
each pass switched over. Remaining known limit (separate, documented, not a re-derivation): no
explicit FK/index diff (P0.2/P5.2, deferred enhancement).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-6`

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

### REG-62 — allowedActions is a typed array and is cross-referenced against the surface's declared actions

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Investigated while closing F5-R1, 2026-07-28; typed half shipped docs/CORPUS_INTEGRITY_PLAN.md C8, 2026-07-29; cross-reference shipped docs/FINAL_OPEN_ITEMS_PLAN.md F9, 2026-07-29
**Surface:** `dsl/autopanel-lifecycle`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/StateMachineStateAst.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/AutoPanelExpander.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/parser/JsonModelParser.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/LifecycleValidation.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ConceptValidation.java`

2026-07-29 (C8): the CSV-in-metadata escape hatch is retired. allowedActions is now a proper
`array` of `string` on a `lifecycleState` node (all 4 model.schema.json mirrors), a real field on
StateMachineStateAst (was: parsed out of a comma-separated string inside the flat
Map<String,String> metadata map -- no schema validation of any kind). Safe with zero corpus impact
(0 of 29 models used the old form, confirmed live via scripts/quality/validate-corpus.py), so no
codemod and no BREAKING.md entry, matching the original finding's own prediction.
What this closes: a value that is not a JSON array of strings (a number, an object, nested JSON,
anything malformed) is now a structural schema-validation failure at author time, not a silent
no-op at runtime.
What this does NOT yet close, and why it stays OPEN: a well-formed but MIS-SPELLED action name
inside the array (e.g. "GerarDemand" instead of "GerarDemanda") is still accepted silently -- the
original bug's actual failure mode ("a typo silently drops an action-rail button"). Catching that
needs a cross-reference check against the AutoPanel section's own declared workbench actions, and
those still live inside `AutoPanelSurfaceAst.metadata()`'s own untyped escape hatch (read via
`AutoPanelExpander.workbenchActions()` from `transaction.metadata().get("actions")`) -- giving
workbench actions a typed AST home of their own is a real, separate design decision (not a
mechanical follow-on to this fix) and was consciously left out of this pass rather than rushed.
Fix, when picked up: type AutoPanelSurfaceAst's actions list, then add the cross-reference check,
likely in PanelValidation.validateAutoPanels (which already has both the concept's lifecycle and
the AutoPanel's surfaces in scope).

2026-07-29 (F9): closed without typing AutoPanelSurfaceAst's actions list -- that turned out not to
be a real prerequisite. LifecycleValidation.validateLifecycle (called from ConceptValidation) now
reads transaction.metadata().get("actions") directly, the same untyped structure
AutoPanelExpander.workbenchActions() itself reads, and cross-references every lifecycle state's
allowedActions entries against the declared procedure names. A state referencing an unknown action
now fails validation naming both the bad entry and the concept's real declared actions (or "(none)"
if the concept has no autoPanel at all).
One correction made while proving this RED-then-GREEN: an AutoPanel binds to a concept two ways --
directly via autoPanel.concept() (JsonModelParser.parseAutoPanels reads it verbatim from the JSON's
"concept" key), or via an aggregate's root concept when the JSON only sets "aggregate" (the real,
common shape for aggregate-bound workbenches -- see AutoPanelExpander.expandAggregateWorkbench,
which resolves the root concept from the model's aggregates list, not from autoPanel.concept()).
The first cross-reference cut only checked the direct-concept form and produced false positives
("(none)" declared) against every aggregate-bound fixture, including the exact shape
AggregateWorkbenchExpansionTest already exercises. Fixed by passing the model's List<AggregateAst>
into LifecycleValidation and resolving both binding forms before matching.
Proof: NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/validation/AllowedActionsCrossReferenceValidationTest.java
(4 cases: clean match, no-allowedActions state left unrestricted, misspelled entry rejected naming
the real actions, and no-autoPanel-at-all rejected naming "(none)") -- all green; full DSL module
test suite green afterward (no other lifecycle/autoPanel test regressed).

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-62`

### REG-63 — 17 of 29 corpus models (not 2) used pre-DSL-2.0 flow-step/orchestration shapes the current schema rejects

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Found while confirming panel-provenance manifests for R-G2 (docs/REMEDIATION_PLAN.md); re-scoped and closed by docs/CORPUS_INTEGRITY_PLAN.md
**Surface:** `dsl/model-schema-compatibility`

Originally filed against AuxScreen and Pigmentampa only. docs/CORPUS_INTEGRITY_PLAN.md C1 measured
the real scope with the actual validator (scripts/quality/validate-corpus.py, the validateModel
Gradle task per model, not a heuristic grep): 17 of 29 corpus models under AppGen/apps failed --
4 of 5 _official apps (AuxScreen, Claude, Pigmentampa, WordLab; WmsOffice was clean) plus 13 non-
official AppGen apps. Root cause: 2.A.4's own migration (docs/DSL2_AND_DECOMPOSITION_PLAN.md)
deliberately deferred AppGen/apps as a non-git external directory (owner's call, documented in that
plan's own Definition of Done) while migrating every git-tracked tree -- the deferred item just
never got a tracking item to come back to.
C2 extended NPDevCli/dsl_v2_migration.py (already covered all 8 retired flowStep.type values and the
cap/op/out/as field aliases; gained a 5th rule renaming the top-level `orchestrations` key to
`orchestrationRules`, a pre-baseline spelling the heuristic scan never covered) and ran it via
`npdev migrate dsl-2 --write` across all of AppGen/apps: 19 files changed, 0 ambiguities. Two
unrelated bugs surfaced and were fixed in the same pass, not by the codemod: pack-sample's model
used a retired shared-packs-directory $ref convention (`Pack $ref escapes the model root`, unrelated
to flow-step shapes) plus a duplicate `persistence` capability once the $ref resolved; Claude Support
Desk's model declared its own `tenantId` reference field, colliding with the platform's own
auto-injected `tenant_id` isolation column (a real Java compile failure, not a parse error) -- fixed
by renaming the model field to `tenantIdRef`, the exact fix
SchemaRealizationEmitter.RESERVED_BUSINESS_COLUMN_NAMES's own guard message suggests. That guard
only runs at DB-schema-realization time (after Java compilation), so it never got a chance to show
its friendly message here -- see the new gap this surfaced (entity emitter lacks the same guard,
filed separately rather than expanding this item's scope).
All 29/29 corpus models now parse; all 13 sample-tier apps generate; all 4 previously-broken
official apps generate+build+boot clean (health UP, all NPDev subsystems UP). R-G2 manifest coverage
is genuinely 15/15 (AuxScreen's aux-screen.panel.json and Pigmentampa's pigmentampa-editor.panel.json
authored fresh and confirmed live, 0 problems from check-panel-provenance-impact.py against each
app's real bundle). C4 promotes the corpus validator to a permanent blocking gate so this class does
not recur silently.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-63`

### REG-64 — EntityEmitter has no reserved-column collision guard -- a model field named tenantId/version/rowVersion produces uncompilable duplicate-field Java, not a clear message

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Found regenerating Claude Support Desk, docs/CORPUS_INTEGRITY_PLAN.md C2; fixed docs/FINAL_OPEN_ITEMS_PLAN.md F10
**Surface:** `generator/entity-emission`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/EntityEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/ReservedColumnNames.java`

SchemaRealizationEmitter already has RESERVED_BUSINESS_COLUMN_NAMES (version/row_version/tenant_id)
with a guard (validateNoReservedColumnCollision) that throws a clear, actionable IllegalStateException
naming the offending field and the exact rename to make -- its own comment cites precisely this
scenario ("a hand-modeled tenantId reference field, as in a pre-platform-tenancy multi-tenant
sample"). But that guard runs at DB-schema-realization time, which is downstream of Java
compilation. EntityEmitter (which emits the entity's Java field/getter/setter for both the
auto-injected platform column and any model-declared field of the same name) has no equivalent
check, so a model with a field literally named tenantId/version/rowVersion produces a Java source
file with a duplicate field/method declaration -- a raw javac error ("variable X is already defined
in class Y") at `App/_ops/Build-App.ps1` time, not the guided message the platform clearly intends
the author to see. Confirmed live: this is exactly what happened regenerating Claude Support Desk
after its DSL 2.0 migration (unrelated to that migration itself) -- fixed there by renaming the
model's own field, not by touching the generator. Fix, when picked up: call the same (or an
equivalent) reserved-column check from EntityEmitter before field emission, so the failure surfaces
at generation time with the existing guard's message instead of at compile time with a bare
javac diagnostic.

2026-07-29 (F10): fixed by extraction, not duplication. The reserved-name set and collision check
moved out of SchemaRealizationEmitter into a new shared
NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/ReservedColumnNames.java
(RESERVED_BUSINESS_COLUMN_NAMES + validateNoCollision(CompiledConcept)). SchemaRealizationEmitter
now delegates to it (same message, same call site, no behavior change there). EntityEmitter.emit()
calls the same check as the first statement of its per-concept loop, before any Java field is
written -- so a colliding field now fails at generation time with the actionable rename message,
before Java compilation ever sees it, regardless of which emitter runs first.
Proof: new NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/EntityEmitterReservedColumnTest.java
(3 cases: tenantId collision throws naming the concept + "tenant_id" + the rename hint; version
collision also throws; an ordinary field is unaffected and the entity file is actually written) --
mirrors the existing SchemaRealizationEmitterReservedColumnTest's assertion shape, using
BondJavaEmitterTest's TemplateEngine/GeneratedSourceWriter direct-construction pattern since
EntityEmitter (unlike SchemaRealizationEmitter) doesn't take a GeneratedDatabasePlan. Full Generator
module test suite green afterward.

### REG-65 — generatedAction was a canonical flowStep.type value FlowValidation always rejected, despite full compiler/generator/runtime support downstream

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Found building NPDevSamples/dsl-conformance-max (F3), scoped and fixed as docs/FINAL_OPEN_ITEMS_PLAN.md F4
**Surface:** `dsl/flow-validation`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/FlowValidation.java`

generatedAction is one of the 12 canonical flowStep.type values in model.schema.json (all 4
mirrors), documented in docs/FLOWS.md as author-facing sugar for CAPABILITY_CALL alongside
createConcept/updateConcept. JsonModelParser handles it and requires actionName
(JsonModelParser.java:1482-1484); ModelCompiler.compileFlowSteps already treats it as
"capability-like" and compiles it into a CompiledCapabilityCall with capability type
"GeneratedActionCapability". The generator (TrustedActionKernelRunnerTemplate,
GeneratedActionCapabilityAdapter) has full, tested support for executing a compiled step of that
shape -- proven live by TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest, which builds and
boots a real packaged app with a generatedAction-shaped compiled flow step.
But FlowValidation.validateFlowSteps's switch had no case for "generatedaction" (it handled
invariant/capability/createentity-updateentity-createconcept-updateconcept/event/scheduleevent/
return/map/branch/await/foreach -- 11 kinds, not generatedAction's 12th), so it fell to `default`
and every authored model using it was rejected: "unsupported step type generatedAction". This is
why 0 of 30 corpus models ever used it -- they could not, structurally, regardless of intent. The
runtime-proof test above never surfaces this because it hand-constructs CompiledModel objects
directly, bypassing JsonModelParser/SemanticValidator entirely -- it proves the compiler/generator/
runtime chain works, never that a real authored model.json can reach it.
Confirmed the runtime side has no separate gap before fixing: FlowStepDefinition.Type (the kernel's
own enum) has no GENERATED_ACTION member, but this is BY DESIGN, not a limitation -- it matches
createConcept/updateConcept, the other two documented sugar kinds, which also desugar to
CAPABILITY_CALL rather than getting their own kernel Type.
Fix: added `case "generatedaction" -> validateGeneratedActionStep(...)` to FlowValidation's switch,
matching the minimal-validator style of return/map (JsonModelParser already guarantees actionName
is present, so this is a defensive re-check, not new enforcement -- there is nothing to
cross-reference the way a capability step's operation lookup does, since the named action is a
code-generation directive resolved by the generator at build time, not a model-declared capability).
dsl-conformance-max (F3) now includes a real generatedAction step as its own proof; docs/FLOWS.md
updated.

### REG-66 — reg39-healthy-control retired -- a byte-identical WmsOffice clone with no independent signal, closed REG-39's own one-time verification artifact

**Type:** PROCESS · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Corpus-structure measurement, docs/FINAL_OPEN_ITEMS_PLAN.md F7
**Surface:** `appgen-apps/corpus-structure`

AppGen/apps/reg39-healthy-control (external, non-git Layer 2) was created 2026-07-25 as a
one-time "healthy pack" live control for REG-39 (see docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-39):
"app reg39-healthy-control, a clone of the WmsOffice definition" -- proving the layer-1 identity-
pack-drift detector did not false-positive on a genuinely healthy pack, on a fresh empty database.
REG-39 has been DONE since 2026-07-25; this app was never meant to be a standing fixture.
Measured before retiring: its definition/model.json was still byte-identical to
_official/WmsOffice's current one; the directory carried only definition/ (capabilities, concepts,
packs, seeds, widgets) -- no web/ or other unique content, 44 files vs. WmsOffice's 72. It also
inflated the aggregates/autoPanels/guidePages corpus-coverage count from a true 1 (WmsOffice alone)
to an apparent 2, which is part of why the Aggregate Workbench's real single-point-of-failure went
unnoticed until the 2026-07-29 corpus measurement that led to NPDevSamples/dsl-conformance-max.
Searched the whole repo for functional references before deleting: none found. The only mentions
are historical documentation -- docs/NPDEV_OPEN_ITEMS_REGISTER.md (REG-39's own closure record,
archived-in-place), docs/archive/programme-history/REG48_50_CLOSURE_PLAN.md, and
NPDevSamples/dsl-conformance-max/Input/README.md's own corpus-coverage table (a measurement
snapshot, correctly left as historical record, not updated).
Retired (deleted from the external AppGen/apps workspace, user-confirmed given it has no git
history to revert through). Nothing of unique value was destroyed -- its only distinguishing
content (model.json) is identical to WmsOffice's own, which remains.

### REG-67 — check-register-consistency.py's --calibrate mode uses bare "HEAD" for its real-instance controls, which silently stops proving anything once the target doc is edited again

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Found incidentally while calibrating Rule T2b (docs/CLOSEOUT_PLAN.md G4), reproduced against the unmodified script before any G4 edit landed
**Surface:** `quality/register-consistency`
**Files:**
- `scripts/quality/check-register-consistency.py`

`calibrate()`'s Rule T1 and Rule T2 real-instance controls read `git show HEAD:<path>`, expecting
that revision to still contain the exact 2026-07-28 bug-shaped text (REG-40/REG-4 for T1,
REG-59 for T2) so `expect_fire=True` proves the rule would have caught the real historical bug.
`HEAD` is a moving target, not a pinned commit -- and both target documents
(`docs/EXECUTION_TREES.md`, `docs/NPDEV_OPEN_ITEMS_REGISTER.md`) have been edited again since
2026-07-28 (further closures, REG-59/REG-61 split, register archived-in-place), so the exact
stale-wording shape the controls look for no longer exists at today's HEAD. Both controls now
report "silent" instead of "fired", so `--calibrate` FAILS on a clean tree -- confirmed by running
the unmodified, pre-this-session script against the current HEAD (5892370) before touching the
file for Rule T2b: identical two failures, so this is not something this session's edits caused.
Not a regression in the RULES themselves -- `main()`'s actual blocking checks (T1/T2 run against
the live working tree, not HEAD) are unaffected; confirmed both report 0 contradictions in the
same run. This only affects the optional `--calibrate` self-test, which nothing in
`run-ai-knowledge-gate.ps1` invokes automatically (grep-confirmed: only `main()`'s default mode
runs in the gate). Impact is real but bounded to a maintainer manually running `--calibrate`.
Fix, when picked up: pin each real-instance control to the actual commit SHA where the bug shape
is verifiably still present (`git log -S` or a recorded SHA in a comment, the same durability
`docs/CLOSEOUT_PLAN.md` G4's own new Rule T2b control uses for REG-62 @ 9c3c423) instead of `HEAD`,
or replace the rotted real-instance controls with synthetic fixtures (T2 already has one working
synthetic control per rule; T1 does too) and drop the real-instance assertion once it can no longer
be kept current for free.

CLOSED 2026-07-29 (`docs/INVOCATION_TOPOLOGY_PLAN.md` T1): Rule T1/T2 pinned to `6a58b09` (parent
of the fix commit `7ef8af4`, confirmed via `git show` to still hold both stale-wording shapes).
`--calibrate` now PASSes on all T1/T2/T2b controls. Corrected-scope lesson: this item was filed as
ONE rotted control; running all eight then-existing `--calibrate` modes by hand (not just
re-reading this instance) found a SECOND, unfiled instance with the identical root cause in
`check-narrative-status-drift.py`'s Rule P2 -- see `REG-68`. The real count was two, found only by
running the whole class, not the one instance that happened to get noticed first.

### REG-68 — check-narrative-status-drift.py's Rule P2 real-instance control also used bare "HEAD" and rotted the same way as REG-67

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** Found by running all eight then-existing `--calibrate` modes by hand while scoping docs/INVOCATION_TOPOLOGY_PLAN.md T1 (REG-67's own fix) -- not a re-read of REG-67, a second real instance of the same root cause
**Surface:** `quality/narrative-status-drift`
**Files:**
- `scripts/quality/check-narrative-status-drift.py`

`calibrate()`'s Rule P2 real-instance control read `git show HEAD:docs/adr/ADR-0009-external-ai-delegation.md`,
expecting that revision to still contain the pre-fix header ("DRAFT -- 2026-07-26 ... D3, D4, D5
remain pending") so `expect_fire=True` proves the rule would have caught the real 2026-07-27
drift. Identical root cause to REG-67: `HEAD` is a moving target, ADR-0009 has been edited again
since (the header was fixed to "APPROVED WITH CONDITIONS" and D4/D5 were resolved), so the exact
stale-wording shape the control looks for no longer exists at HEAD. `--calibrate` FAILED on a
clean tree -- confirmed by running the unmodified script against HEAD before any fix landed.

This script's own docstring states it "ships REPORTING ONLY ... never blocking, until a clean-tree
run proves the detector works" -- by its own stated contract it should not have been shipping
report-only in the gate (step 7/15 of `run-ai-knowledge-gate.ps1`) while its calibration was
silently broken. Impact bounded the same way as REG-67: the script's default (non-`--calibrate`)
mode reads the live working tree, not HEAD, so the actual report-only scan was unaffected; only
the self-test evidence was stale.

CLOSED 2026-07-29 (`docs/INVOCATION_TOPOLOGY_PLAN.md` T1): pinned to `10d3a88` (the commit
immediately before the fix `f76b95f`, confirmed via `git show` to still hold the pre-fix DRAFT
header), matching the same fixed-revision discipline used for REG-67 and Rule T2b's REG-62 @
9c3c423 pin. `--calibrate` now PASSes. `run-ai-knowledge-gate.ps1` gained a new step that runs
every `--calibrate`-capable script (derived from argparse, not hand-maintained) specifically so
this class of rot announces itself instead of waiting to be found by hand a third time.

### REG-69 — 3 DSL features (fragments, packs, step.updateConcept) have zero coverage on a bare CI checkout -- only exercised by AppGen/apps-only models

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** check-dsl-coverage.py failing on PR
**Surface:** `quality/dsl-coverage`
**Files:**
- `scripts/quality/dsl-coverage-allowlist.json`
- `NPDevSamples/dsl-conformance-max`

Locally, with `AppGen/apps` present (a non-git, developer-machine-only directory per CLAUDE.md's
"Layers"), `check-dsl-coverage.py` scans 29 corpus models and every one of the 29 tracked DSL
features has at least one example: `fragments` via `AppGen/apps/npdev_split_model_sample_app`,
`packs` and `step.updateConcept` via `AppGen/apps/_official/WmsOffice`. On a bare CI checkout
(`AppGen/apps` absent, only `NPDevSamples` scanned -- 10 models), those three features have never
had a real example in the git-tracked corpus, so the check has been failing unconditionally on
every CI run of this gate -- just never observed, because `ai-knowledge-gate.yml` had no `paths:`
filter removed until T3, and this PR is the first real run since.

This is the same shape as CONTRIBUTING.md's own standing rule ("add a DSL feature, add a real
example to `dsl-conformance-max` in the same commit") being satisfied by AppGen/apps state instead
-- a claim ("every feature has corpus coverage") that was only ever true because of state CI
cannot see, exactly the kind of drift `docs/RECORD_SURFACES_PLAN.md` is about, just discovered
as a side effect of finally getting an observed CI run rather than as one of that plan's own
named P1-P6 items.

CLOSED 2026-07-29 as: recorded a reviewed exception in `scripts/quality/dsl-coverage-allowlist.json`
for all three features, citing this REG id, per the check's own documented escape hatch --
unblocks CI without hand-authoring untested new DSL fixture content under time pressure. The real
fix (add genuine `fragments`/`packs`/`step.updateConcept` examples to
`NPDevSamples/dsl-conformance-max`, Gradle-validated) is deliberately deferred, not done -- tracked
as `docs/ACCEPTED_BOUNDARIES.md` B27 with its own revisit trigger.

UPDATE 2026-07-29 (docs/FAIL_OPEN_PLAN.md R2): B27's premise -- that `packs` and `fragments`
"genuinely need an out-of-tree asset" -- was an untested assumption, not a proven limit, per R2's
own instruction to attempt the harder two before accepting that. Both `packRef` and the top-level
`fragments` entry are just a `{"$ref": "relative/path.json"}` pointing at any JSON file inside the
model's own directory tree; there is no out-of-tree requirement in either schema. Closed for real:
`NPDevSamples/dsl-conformance-max` gained `packs/labeling/pack.json` (a minimal, fully in-git
concept pack, same `$ref` mechanism `AppGen/apps/_official/WmsOffice`'s identity/workspace packs
use), `fragments/auditLog.json` (a minimal, fully in-git capability+binding fragment, same shape as
`AppGen/apps/npdev_split_model_sample_app`'s `plugin/sendMail.json`), and an `UpdateOrderLineQuantity`
flow (`step.updateConcept`). All three Gradle-validated (`validateModel`, 0 errors, 0 warnings).
`scripts/quality/dsl-coverage-allowlist.json` is empty again; `docs/ACCEPTED_BOUNDARIES.md` B27
removed (its own revisit trigger fired the same day it was written, so it never described an
ongoing state -- keeping it would itself be a stale record).

### REG-7 — LNCH-1-B6: no migration advisory lock (multi-instance) -- converted to a feature

**Type:** BOUNDARY · **Severity:** — · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `runtimehost/schema-lifecycle`

Owner decision: convert this and REG-8 into features with a fail-loud + operator-resolves posture
rather than leave them as documented limits. Delivered as three sub-features: (1) external/unmanaged
database ownership -- a new schemaLifecycle.ownership field; ExternallyManaged apps issue zero
schema DDL and run a read-only compatibility check every boot; (2) "mark migration as done" -- a
ControlPanel operation that fast-forwards the stored fingerprint with zero migration passes, on the
operator's word; (3) collision detection (this item's original scope) -- a single-row claim table
taken at the top of every upgrade boot and released in a finally, a held claim refuses the boot
loudly naming the holder, a crashed holder is clearable via a SUPERUSER admin endpoint. Honestly
named residual: this is detect-and-refuse, NOT a true lock -- a near-simultaneous-INSERT race
remains theoretically possible on an engine without strict insert serialization; a genuinely virgin
database's first-ever boot is not claim-protected by design (claiming unconditionally there would
break Flyway's own baseline detection -- a real bug found and fixed via live boot rehearsal).
Verified: full RuntimeHost suite green after each sub-phase, dedicated test classes for all three,
live boot rehearsals against a real assembled app found and fixed two real ordering bugs.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-7`

### REG-70 — panel.action.binding: "flow" is schema-valid, compiler-accepted, and unimplemented at runtime -- 2 shipping WmsOffice panels already have a dead primary action

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE1_CONSOLE_CONVERSION_PLAN.md (Move 1 of CAPABILITY_ROADMAP.md) -- found by a code read before authoring CrossDockingConsolePanel, then confirmed live via both direct REST calls and a real-browser ScrapForAI run against a running WmsOffice instance
**Surface:** `kernel/panel-runtime`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/PanelRuntime.java`
- `NPDevContract/schemas/model.schema.json`
- `AppGen/apps/_official/WmsOffice/definition/model.json`

`model.schema.json`'s `panelAction.binding` enum is `["conceptQuery","conceptMutation","procedure",
"flow"]` and the compiler (`CompiledPanelAction`) accepts all four. But
`PanelRuntime.executeAction` only implements `procedure`, `conceptQuery`, and `conceptMutation`;
the `flow` case falls through to an `else` branch returning
`{"status":"UNSUPPORTED","result":{"code":"PANEL_ACTION_BINDING_UNSUPPORTED", ...}}`. Both
entry points into panel-action execution (`RuntimeUiMetadataController`'s
`/panels/{panel}/actions/{action}` and `DirectExecutionGateway.executePanelAction`) delegate to
this same method, so there is no alternate path that works.

**Two panels already shipping in WmsOffice's live model have their one action on this dead
branch**: `ConferenciaRecebimentoPanel.ConfirmarRecebimento` and
`ExpedicaoDemandaPanel.ConfirmarSaidaExpedicao` (both `binding: "flow"`). Confirmed live
2026-07-29: `GET .../panels/ConferenciaRecebimentoPanel` and `.../ExpedicaoDemandaPanel` both
report `"binding": "flow"` for their sole action; a `flow`-bound action invoked against the
running app (proven via `CrossDockingConsolePanel`'s equivalent actions, same code path,
action-name/flow-name-agnostic branch) returns `PANEL_ACTION_BINDING_UNSUPPORTED` with HTTP 200 --
and the generated business-ui client's status banner only flags a result as an error when
`payload.status === "FAILED"`, so a user clicking the button sees a neutral (non-red) "Action
... completed: UNSUPPORTED" message, not an obvious failure. `OcupacaoLocalPanel` (`procedure`
binding) and `MovimentoDetailPanel` (`conceptMutation` binding) are unaffected -- confirmed
working end-to-end (including a real invariant-evaluated write) via `CrossDockingConsolePanel`'s
`marcarConcluidoDireto` control action, which used the identical `executeAction` method with a
different `binding` value.

Smallest fix: add a `flow` branch to `PanelRuntime.executeAction` that invokes the named flow
through the same execution path `/api/v1/flows/{name}/execute` already uses (the `procedure` and
`conceptMutation` branches in the same switch are the proven-working reference). Scoped to Move 2
of `CAPABILITY_ROADMAP.md` (`docs/MOVE1_CONSOLE_CONVERSION_PLAN.md`'s G1) -- Move 1 itself changes
no platform code by design, so this fix is deliberately not made yet.

Same class as `generatedAction` (schema+compiler+runtime all support it, only the validator
rejects it -- REG-65) and `panel.route` (declared, compiled, but the generated business-ui client
never wires it to an actual navigable URL; panels are reached via the left-nav "Panels" section
instead) -- a declared surface some layer silently doesn't finish, not caught by any existing test
because none crosses the authoring-to-runtime boundary for panel actions.

CLOSED 2026-07-29 (`docs/MOVE2_PANEL_ACTIONS_PLAN.md` G1): added a `flow` branch to
`PanelRuntime.executeAction` that routes through `KernelFacade.executeFlow` (the same call the
generated `FlowExecutionController`'s `/api/flows/{name}/execute` uses), returning `OK`/`WAITING`/
`FAILED` (mapped from `ExecutionStatus`) plus a real `executionId`/`correlationId` -- no
synchronous result is synthesized for a parked (`WAITING_EVENT`) flow. `kernelFacade == null`
still degrades to the old `UNSUPPORTED` response (graceful, matches this file's existing
null-safety idiom) rather than throwing, so no existing unit test needed updating.

New test `NPDevRuntimeHost/src/test/java/com/finalexec/PanelRuntimeFlowActionTest.java`
(2 cases, Mockito-mocked `KernelFacade`) -- RED confirmed by this REG's own live evidence above
(`PANEL_ACTION_BINDING_UNSUPPORTED`) before the branch existed; GREEN proven by actually running
it inside a regenerated+mounted WmsOffice build (2/2 passing; `test`'s default sourceSet excludes
`com.npdev.generated.*`-referencing test sources per `feedback_runtimehost_generated_app_test_exclusions`,
so the exclusion was temporarily lifted in the ephemeral generated app's own `build.gradle` to run
it, then reverted -- the source template itself was never changed).

**Both dead actions verified live end-to-end, real state transitions**, not just "no longer
UNSUPPORTED": `ConferenciaRecebimentoPanel.ConfirmarRecebimento` walked a real Recebimento through
`PreRecebimento -> Enderecado -> Conferido -> Armazenado` (each hop `status: OK`, real
`executionId`); `ExpedicaoDemandaPanel.ConfirmarSaidaExpedicao` walked a real Expedicao through
`PreExpedicao -> DemandaGerada -> MovimentacaoConcluida -> SaidaConfirmada`, same result shape.
`CrossDockingConsolePanel.ativar`/`.concluir` (the panel this REG was found through) also
confirmed `OK` with real flow output. A real-browser ScrapForAI pass (clicking the still-unscoped,
still-input-less `Ativar Cross-Docking` header button -- G2/G3 not yet fixed) now shows
`Action "ativar" completed: FAILED` with the client's real error-red styling (the `payload.status
=== "FAILED"` check now matches), instead of the previous neutral, easy-to-miss `"...completed:
UNSUPPORTED"` -- a real UX improvement, not just a status-code change.

### REG-71 — panelAction scope="row" + binding="conceptMutation" blanked every other required field to null (executeConceptMutation stripped "id" from a flat body; ConceptGatewaySemanticPolicy separately requires "id" present in the data map)

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE2_PANEL_ACTIONS_PLAN.md G4 -- found live while authoring InventarioHistoricoPanel's "Confirmar" action and ConferenciaFiscal{Nfe,Romaneio}Panel's "Cancelar" action (the first scope="row" actions in the corpus bound to conceptMutation rather than flow -- G2's own live verification against CrossDockingConsolePanel only ever exercised flow-bound row actions)
**Surface:** `kernel/panel-runtime`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/PanelRuntime.java`
- `NPDevRuntimeHost/src/test/java/com/finalexec/PanelRuntimeRowScopedActionTest.java`

Two stacked bugs, found and fixed in sequence, both only reachable by a `scope: "row"` action
bound to `conceptMutation` (the real per-row button always sends a flat `{id: row.id}` body, per
`business-ui-app.mustache`'s row-action click handler):

**Bug 1** -- `PanelRuntime.executeAction`'s row-scope merge (`resolveRowScopedInput`, added for
G2) originally only applied to `flow`/`procedure` bindings, not `conceptMutation`. A `conceptMutation`
action's own `executeConceptMutation` treats a body with no `"data"` key as the WHOLE record to
save -- so a flat `{id}` body (correct) was ALSO doing that, blanking every other required field
to null. Reproduced live: `POST .../InventarioHistoricoPanel/actions/confirmar` with `{id,
situacao}` failed `ConceptGatewaySemanticException: Required concept field is missing:
InventarioArquivo.entidadeId`.

**Bug 2** (surfaced immediately after fixing Bug 1) -- `executeConceptMutation`'s fallback branch
(`data.isEmpty()`) built the save payload as `new LinkedHashMap<>(input); data.remove("id")` --
deliberately excluding `"id"` from the data map, on the assumption `"id"` is call metadata, not a
data field. But `ConfiguredConceptGatewaySemanticPolicy` (the real, non-noop semantic policy)
iterates every declared concept field INCLUDING `"id"` (every concept has one, `required: true`)
and validates it's present in the SAME data map. Reproduced live:
`ConceptGatewaySemanticException: Required concept field is missing: InventarioArquivo.id`, even
after Bug 1's fix correctly merged every other field in.

**Why G2's own live verification missed this**: `CrossDockingConsolePanel.concluir`/`.cancelar`
(G2's proof) are both `binding: "flow"`, which already had its own row-scope merge from the start.
No corpus panel had a `conceptMutation` + `scope: "row"` action until G4's
`InventarioHistoricoPanel.confirmar` / `ConferenciaFiscal{Nfe,Romaneio}Panel.cancelar`.

**Test methodology note**: the first version of the regression test used
`DefaultConceptGateway`'s 4-arg constructor, which defaults to `ConceptGatewaySemanticPolicy.noop()`
-- so it passed even with BOTH bugs still present, proving nothing (same shape as
`feedback_red_proof_must_match_production_shape`: a RED-proof that doesn't match production shape
is not a RED-proof). Rewritten to construct a real `ConfiguredConceptGatewaySemanticPolicy` with a
concept definition carrying required fields (including `id`), which failed correctly before the
fix and passes after.

Fixed: `effectiveInput` computation now also runs `resolveRowScopedInput` for `conceptmutation`
bindings (Bug 1); `executeConceptMutation`'s fallback branch no longer strips `"id"` from the
constructed data map (Bug 2). Verified live: `InventarioHistoricoPanel.confirmar` and
`ConferenciaFiscalNfePanel.cancelar` both return `status: "OK"` with a real gateway trace showing
the correct lifecycle transition and every other field preserved from the fresh row read.

### REG-72 — AggregateRuntime.commit performs N+1 writes and reconcile-deletes with no transaction boundary -- a failure partway leaves a half-written aggregate, and a failure after a reconcile-delete does not restore what was deleted

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md G1 -- proposed before any code was read, confirmed by reading AggregateRuntime.java/AggregateApiController.java, then proven with a real depth-2 (Expedicao -> ExpedicaoItem -> MovtoOrigem) scenario against a real H2-backed JdbcBusinessConceptStore + DataSourceTransactionManager, RED before the fix and GREEN after (both directions actually run, not assumed)
**Surface:** `kernel/aggregate-runtime`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/AggregateRuntime.java`
- `NPDevRuntimeHost/src/test/java/com/finalexec/npdev/service/AggregateRuntimeCommitTransactionalTest.java`
- `NPDevSamples/dsl-conformance-max/Input/model.json`

`AggregateRuntime.commit` does, in order: upsert root -> recursive child upserts (depth-first,
each level's own reconcile-delete firing before the next sibling collection is processed) ->
return a fresh `load()`. Every `gateway.save`/`gateway.delete` call was an independent
auto-commit -- no `@Transactional`, no `TransactionTemplate`, nothing wrapping the sequence.

**Failure mode, confirmed live**: a depth-2 draft where an early child (and its own nested
grandchild reconcile-delete) commit successfully, and a LATER sibling child's save throws --
the root rename and the already-fired reconcile-delete both stayed committed even though the
overall `commit()` call threw and the caller sees it as failed. Because reconcile actively
*deletes* rows absent from the draft, this is not just inconsistency, it is unrecoverable data
loss: a caller retrying the (now-corrected) draft does not get the deleted row back on its own.

**Two claims this REG's own investigation corrected or sharpened relative to the plan that
proposed it, stated plainly:**
1. The plan's "(2) nothing has ever exercised [the recursion] past depth 1" is **not accurate**:
   `AggregateRuntimeCommitTest` (pre-existing) already exercises a real depth-2
   insert/update/delete round-trip, including grandchild-level reconcile-delete, against a
   hand-rolled in-memory `ConceptGateway`. The recursion itself was never in doubt once read --
   this REG did not need to touch `commitCollections`/`loadCollection` at all. What was
   genuinely missing was transactional coverage and a real corpus example, not recursion
   correctness.
2. **Storage-mode risk resolved, not just flagged**: WmsOffice's generated
   `application-npdev-db.properties` sets `npdev.storage.mode=jdbc` (confirmed by reading the
   generated app, not assumed), so `JdbcBusinessConceptStore` -- the store that actually
   participates in a Spring transaction via `DataSourceUtils.getConnection` (LNCH-17) -- is the
   one WmsOffice really runs on. The fix applies to the real running app, not just a fixture.
   The `in-memory` storage mode (dev/test default) genuinely cannot gain atomicity from this fix
   (`InMemoryConceptStore` has no transactional resource to join) -- recorded here rather than
   silently assumed fixed everywhere, per the plan's own instruction.

**Fix**: `AggregateRuntime` gained an optional `PlatformTransactionManager` dependency (same
`ObjectProvider`-optional-constructor idiom as every other dependency in this class), used to
build a `TransactionTemplate` that wraps the entire `commit()` body when available.
`TransactionTemplate` was chosen over `@Transactional` deliberately: `AggregateApiController`
calls `aggregateRuntime.commit(...)` on an externally-injected bean (not self-invocation), so
`@Transactional` would have worked for that one call path -- but this class is also, routinely,
constructed directly (every existing test, and any future non-Spring caller), which silently
defeats an annotation-driven AOP proxy. A `TransactionTemplate` invoked explicitly inside the
method works identically either way, and is directly unit-testable without a Spring
`ApplicationContext`. No transaction manager wired (e.g. in-memory storage mode) degrades to the
original, unchanged, non-atomic behavior -- zero existing callers needed to change.

**RED->GREEN, both directions actually run**: `AggregateRuntimeCommitTransactionalTest` has two
tests against a real H2-backed `JdbcBusinessConceptStore`/`DefaultConceptGateway` and a real
`DataSourceTransactionManager`. One documents the still-available non-atomic path (no
transaction manager wired) as a permanent characterization test. The other
(`atomicCommitRollsBackEveryPriorWriteWhenTransactionManagerIsWired`) was verified RED by
temporarily short-circuiting the transaction wrapping (confirmed `AssertionFailedError`: the
root and the reconciled-away child were NOT rolled back), then GREEN after restoring the real
fix -- both runs actually executed, not inferred. Full existing `AggregateRuntimeCommitTest` (2),
`AggregateRuntimeTest` (3), and `WorkbenchRuntimeTest` (2) suites re-run clean, zero regressions.

`NPDevSamples/dsl-conformance-max` gained the corpus's first depth-2 aggregate
(`WidgetOrderAggregate.lines.notes`, a new `WidgetOrderLineNote` concept), Gradle-validated (0
errors, 0 warnings) -- the standing add-a-feature rule, and closes a named zero-coverage gap in
the real corpus (as opposed to a hand-rolled test fixture).

### REG-73 — ProcedureRunner never resolved a capability adapter from the model's bindings list -- every procedure-side capabilityCall step (panel action procedure bindings, and AggregateRuntime.invoke()) reached the dispatcher with a null adapterId and failed CAPABILITY_BINDING_MISSING even with a real binding declared

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md G2 -- found live while assessing the Sugerir* flows against invoke() ("procedure over draft" is the candidate mechanism for a computed-array suggestion input, per the plan's own G2 hypothesis). Twin procedures (SugerirDestinoProcedure / SugerirOrigemProcedure) reproduced a real capability call failing 503 CAPABILITY_BINDING_MISSING ("adapter '<missing>'") even though the identical capability/operation/binding works fine when called via the pre-existing flow (SugerirDestino / SugerirOrigem).
**Surface:** `kernel/procedure-runtime`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/ProcedureRunner.java`
- `NPDevRuntimeHost/src/test/java/com/finalexec/npdev/service/ProcedureRunnerCapabilityCallTest.java`

`ProcedureRunner.toProcedureStep`'s `CALL_CAPABILITY` branch hardcoded the compiled step's
`adapterId` to `""` (which `ProcedureStep.callCapability` normalizes to `null`), regardless of
the model's declared `bindings` list. `RegistryCapabilityDispatcher.invoke` fails fast with
`CAPABILITY_BINDING_MISSING` whenever `adapterId` is null/blank -- it never falls back to
resolving one itself, by design (dispatch is by capability + explicit adapter, not a default
lookup).

The FLOW compilation path already did this resolution correctly:
`CompiledModelFlowDefinitionProvider` builds an `adapterIdByCapability` map from
`compiledModel.getBindings()` at flow-compile time and resolves each `capabilityCall` step's
adapter from it (`nonBlank(call.getAdapterId(), adapterIdByCapability.get(normalize(capabilityName)))`)
-- which is exactly why the pre-existing `SugerirDestino`/`SugerirOrigem` FLOWS worked live the
whole time this bug existed. The PROCEDURE path (`ProcedureRunner`, shared by panel-action
procedure bindings and `AggregateRuntime.invoke()`) never had the equivalent resolution --
confirmed by reading `ProcedureRunner.java` alongside `RegistryCapabilityDispatcher.java` and
`CompiledModelFlowDefinitionProvider.java` side by side, not assumed.

**Blast radius**: every procedure in the corpus (and any future one) that calls a custom
capability via `type: "capabilityCall"` was silently broken end-to-end -- not scoped to the two
Sugerir procedures this REG was found through. `ProcedureRunner` is shared by both panel-action
procedure bindings and `AggregateRuntime.invoke()`, so this affected both call paths identically.

**Fix**: `ProcedureRunner` now builds the same `adapterIdByCapability` map (from
`compiledModel.getBindings()`, normalized capability-name key -> adapter) once per
`buildProcedureDefinitions()` call, and threads it through `toProcedureDefinition`/
`toProcedureStep` (previously static methods taking no model context) so the `CALL_CAPABILITY`
case resolves a real adapterId instead of `""`. `capabilityType` was left as `""`/null,
unchanged -- `CompiledProcedureStep` has no such field either, matching the flow path's own
behaviour when a step author leaves it unset.

**RED->GREEN, both directions actually run**: new `ProcedureRunnerCapabilityCallTest` builds a
minimal compiled model (one custom capability + one binding + one procedure with a
`capabilityCall` step) and a `RecordingDispatcher` stub that captures the `CapabilityCall` it
receives. Verified RED by temporarily reverting the `CALL_CAPABILITY` branch to the old
hardcoded-`""` form in a real generated FinalApp build (WmsOffice) and re-running: genuine
`AssertionFailedError` (`expected: <test-adapter> but was: <null>`) -- an actual JUnit failure,
not inferred. Restored the fix, re-ran: GREEN. Full regression pass in the same app build:
`ProcedureRunnerCapabilityCallTest` (1), `AggregateRuntimeCommitTransactionalTest` (2),
`AggregateRuntimeCommitTest` (2), `AggregateRuntimeTest` (3), `WorkbenchRuntimeTest` (2),
`PanelRuntimeTest` (8), `PanelRuntimeRowOpsTest` (6), `PanelRuntimeInputFieldsTest` (1) --
25/25 pass, zero regressions.

**Verified live end-to-end, not just unit-tested**: rebuilt WmsOffice's running FinalApp
(bootJar) with the fix, restarted it, and called the two real procedures that had previously
reproduced the 503 (`POST /api/runtime/aggregate/Movimento/invoke/SugerirDestinoProcedure` and
`.../SugerirOrigemProcedure`), both now returning a real, correct suggestion result
(`sucesso: true`, real ranking/FIFO allocation output) matching what the equivalent flow call
returns. This closes what would otherwise have been recorded as a named G2 design gap
(Move 3, `docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md`) -- the plan's own hypothesis ("invoke is the
candidate mechanism ... if it does not fit, say so and name the gap") held once the real bug
underneath it was fixed, so no gap needed naming.

### REG-74 — The plugin-mount/requirement-discovery pipeline only scanned FLOW steps for capabilityCall usage -- a custom Java-source capability referenced ONLY by a procedure (never by any flow) was never mounted (no Java source compiled in, no plugin-manifest entry), so the app failed to boot with "Adapter ... is not declared in active plugin manifest" even though ProcedureRunner's own dispatch (REG-73) correctly resolved the adapter id

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md G4 -- found live while fixing C10 (the crossdocking console's per-action multi-write gap, docs/MOVE1_PANEL_GAPS.md). Building a NEW custom capability (crossDockingSync) referenced ONLY by two new procedures (ConcluirCrossDockingProcedure / CancelarCrossDockingProcedure, no flow calls it) crashed the generated app at Spring boot with a real, reproducible exception.
**Surface:** `dsl-compiler/plugin-mount`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledPluginRequirementGraphBuilder.java`
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/compiled/CompiledPluginRequirementGraphBuilderTest.java`

`CompiledPluginRequirementGraphBuilder.build()` computed the model's list of "plugin requirements"
(which custom capabilities need a Java-source plugin mounted, compiled, and registered in the
runtime's plugin manifest) by recursively scanning ONLY `modelAst.getFlows()`'s steps for
`type: "capability"`. It never looked at `modelAst.getProcedures()` at all -- despite
`ProcedureRunner` being a fully real, independent execution path (used by both panel-action
procedure bindings and `AggregateRuntime.invoke()`) that can call `type: "capabilityCall"` steps
just as flows can.

**Why this went unnoticed through Move 3 G1-G3**: every custom capability exercised so far
(`alocacao` via `SugerirDestinoProcedure`/`SugerirOrigemProcedure`, `fiscalImport` via
`ParseNfeProcedure`) had ALSO been called by a PRE-EXISTING FLOW (`SugerirDestino`/`SugerirOrigem`/
`ImportarNfe`) that already caused the capability to be discovered, mounted, and registered. The
new procedures were reusing an already-mounted capability, so the gap stayed invisible. It
surfaced the first time a capability (`crossDockingSync`) was declared and bound with NO flow
caller at all -- procedure-only from the start.

**Reproduced live, exact failure**: generating + booting WmsOffice with `crossDockingSync` declared
in `customCapabilities`/`bindings` and called only from `ConcluirCrossDockingProcedure`/
`CancelarCrossDockingProcedure` produced a Spring `BeanCreationException` at the `capabilityRegistry`
bean: `Adapter 'plugin:java-source' for capability 'crossDockingSync' operation '<binding>' is not
declared in active plugin manifest 'npdev/plugins/default.plugin-manifest.json'` -- the app could
not start at all. Confirmed by inspecting the generated output directly: no
`wmsoffice-crossdockingsync-java-source-package.package.json` was emitted, no
`com/wmsoffice/capabilities/crossdocking/CrossDockingSyncCapability.java` was mounted into
`ArtifactNP`, and `default.plugin-manifest.json` had no `crossDockingSync` entry, even though the
identical `capability.plugin.json` shape (mirrored byte-for-byte from the working `alocacao`
descriptor) was present in the app definition.

**Fix**: `CompiledPluginRequirementGraphBuilder` now also walks `modelAst.getProcedures()`, via a
new `collectFromProcedureSteps` mirroring `collectFromSteps` but over `ProcedureStepAst` (a
distinct AST type from a flow's `StepAst`, with its own `capability`/`operation`/`thenSteps`/
`elseSteps`/`steps` accessors) -- checking both `type: "capabilityCall"` and its `"callCapability"`
alias, and recursing into `then`/`else` (an `if` step) and `steps` (a `forEach` step, which flows
have no equivalent of).

**RED->GREEN, both directions actually run**: `CompiledPluginRequirementGraphBuilderTest` gained
`collectsCapabilityRequirementsFromProcedureStepsToo` (a compiled model with a capability bound
only from a procedure's `capabilityCall` step). Verified RED by temporarily removing the new
procedure-scanning loop: real `AssertionFailedError` (expected 1 requirement, got 0). Restored the
fix, re-ran: GREEN. Full `NPDevContract:dsl` test suite re-run clean, zero regressions.

**Verified live end-to-end**: regenerated + rebuilt + rebooted WmsOffice with the fix; the app
started successfully; confirmed `wmsoffice-crossdockingsync-java-source-package.package.json` and
the Java source were now mounted, and `default.plugin-manifest.json` carried the `crossDockingSync`
entry. Then exercised the real feature this fix unblocked (see the ledger's own separate note on
the Move 2 G2 residual it closes, `docs/MOVE1_PANEL_GAPS.md`): clicking "Concluir" on a real
`CrossDocking` row in the actual generated business UI (real browser click, not simulated) now
correctly transitions `situacao` from `Ativo` to `Concluido`, confirmed both by the panel's
reloaded table and by the full server response rendered in its "last action result" debug block.

### REG-75 — Procedures have no way to read an existing concept record, override one field, and pass the merged result onward -- a readConcept result can only be consumed as a whole map by saveConcept (via requireMap's ConceptRecord unwrap), never by capabilityCall's args (which never unwraps ConceptRecord), and no step exists to construct/merge a map literal at all

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md G4 -- found live while attempting the FULL C10 fix
(docs/MOVE1_PANEL_GAPS.md: "an action triggering writes to *other* concepts beyond its own flow's
output has no declared mechanism") -- the crossdocking console's original Concluir/Cancelar/Ativar
handlers also PUT Recebimento/Expedicao with crossDockingAtivo flipped, preserving every other
field. Attempting this via a procedure (readConcept the sibling record, flip one field, saveConcept
it back) hit a real, precisely-evidenced wall, not a design choice.

**Surface:** `kernel/procedure-runtime`

Investigated, not fixed, in the same session that closed REG-72/73/74 (all found while pursuing
this same C10 fix). The wall, confirmed by reading the executor directly rather than assumed:

1. `readConcept` stores its result in procedure `state` as a raw `ConceptRecord` (Java type), not a
   `Map` (`DefaultProcedureExecutor.readConcept`: `putOutput(state, step.outputKey(),
   record.orElseThrow())`).
2. `saveConcept`'s `data` ref DOES unwrap a `ConceptRecord` automatically
   (`DefaultProcedureExecutor.requireMap`: `if (value instanceof ConceptRecord record) return
   record.data();`) -- so re-saving an UNCHANGED read works fine.
3. `capabilityCall`'s `args` refs do NOT go through `requireMap` -- they resolve via the plain
   `resolve()` helper, which never unwraps `ConceptRecord`. Passing a `readConcept` result as a
   capabilityCall arg hands the raw `ConceptRecord` object to the Java capability method, which
   expects `Map<String,Object>` -- a reflective `IllegalArgumentException` at
   `Method.invoke(...)`, uncaught by `RegistryCapabilityDispatcher` (it only catches
   `ReflectiveOperationException`/`InvocationTargetException`, and a raw argument-type mismatch
   is neither).
4. Custom Java-source capability classes (`AllocationCapability`, `FiscalImportCapability`, the
   new `CrossDockingSyncCapability`) compile in an isolated classpath with no `com.npdev.kernel.*`
   visibility (confirmed: none of the three existing ones import anything beyond `java.*`) -- so a
   capability method cannot even accept a `ConceptRecord` parameter type to work around point 3.
5. `mapValue`'s `target` always writes one flat top-level state key (`DefaultProcedureExecutor
   .putOutput`); there is no step that constructs a new map by copying an existing one and
   overriding a subset of keys, and no step accepts a literal object.
6. Confirmed there is no silent fallback anywhere in the write path either:
   `ConfiguredConceptGatewaySemanticPolicy.normalizeAndValidate` builds its working `data` map
   strictly from `new LinkedHashMap<>(request.data())` -- it never consults
   `request.previousRecord()` to fill in fields the caller omitted, so a deliberately partial
   `saveConcept` payload fails "Required concept field is missing" exactly as a caller supplying
   the same partial map anywhere else would.

**What this blocked, precisely**: the crossdocking console's `Ativar`/`Concluir`/`Cancelar`
actions all additionally PUT the linked `Recebimento`/`Expedicao` with only `crossDockingAtivo`
flipped, every other field preserved. This remains genuinely `cannot-express` for a procedure
today -- C10 (docs/MOVE1_PANEL_GAPS.md) is only PARTIALLY closed by this session's work (see
REG-74's own writeup: the `situacao`-transition half of Concluir/Cancelar IS fixed and live-verified;
the Recebimento/Expedicao sync half is not attempted, named here instead).

**What would close it** (not attempted, scoped for a future session): the smallest fix is
probably a new procedure step, e.g. `mergeConcept`/`patchValue` that takes a `readConcept` output
ref plus a small set of literal-or-ref field overrides and produces a plain `Map` in state,
consumable by both `saveConcept` and `capabilityCall` afterward. A narrower fix (unwrap
`ConceptRecord` in `resolve()`'s single-segment branch, mirroring what `requireMap` already does)
would only solve the pass-through case, not the "override one field" case, so does not fully close
this on its own.

---

**Resolution (2026-07-30, docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md, "Move 4"):** closed by exactly
the mechanism scoped above. New procedure step `patchConcept` (kernel `ProcedureStepType
.PATCH_CONCEPT`): reads the existing record (fails `CONCEPT_NOT_FOUND` if absent), overlays a
`set` map onto its full data (preserving every field not named), saves. `set` values are literals
by default, unlike every other `*Ref` field on a procedure step -- a `$`-prefixed string resolves
against procedure state, `"$$x"` escapes to the literal `"$x"`. Also added `aggregate.onCommit`: a
named procedure runs inside `AggregateRuntime.commitInternal`'s own transaction after the tree is
written, so a sibling-record side effect commits or rolls back with the aggregate atomically
(only correct because REG-72 already made that commit transactional). Both proven RED->GREEN
against a real H2-backed transactional store
(`NPDevRuntimeHost/.../service/AggregateRuntimeOnCommitTest.java`), both have real corpus coverage
in `NPDevSamples/dsl-conformance-max` enforced by `scripts/quality/check-dsl-coverage.py`
(`procedure.patchConcept`, `aggregate.onCommit`).

Applied live to WmsOffice: `ConcluirCrossDockingProcedure`/`CancelarCrossDockingProcedure` now
each use ONE `patchConcept` step for the situacao transition (literal `set`, replacing the
`crossDockingSync` capabilityCall + saveConcept pair) plus two more `patchConcept` steps clearing
`crossDockingAtivo` on the linked Recebimento/Expedicao -- closing BOTH halves of C10
(docs/MOVE1_PANEL_GAPS.md) for those two actions. The now-fully-dead `crossDockingSync` capability
(it only ever existed to inject that one literal) was deleted, backed up to
`NPDevGenerator__OutsideRepo/move4-backups/crossDockingSync-deleted-2026-07-30` first since
AppGen/apps is not version-controlled (CLAUDE.md's layer-2 convention).

C10's remaining two manifestations -- Ativar's own `crossDockingAtivo=true` flag-sync, and M8/M9's
`syncOcupacao` (docs/MOVE3_G2_CHECKLISTS.md) -- turned out to need patchConcept's *create* half
(a brand-new sibling record with an auto-generated id), which this fix deliberately does not
provide (patchConcept only ever touches existing records, by design). That is a distinct,
precisely-scoped gap, filed separately as REG-77 rather than folded in here or half-solved.

**Verified live (2026-07-30)** against the regenerated WmsOffice FinalApp
(D:\WorkSpace\NPDev\Build\generated-finalapps-h2q\wmsoffice), real H2 DB, via REST:
activated two fresh CrossDocking records through the unchanged `AtivarCrossDocking` flow, then
called `concluir`/`cancelar` on each through `POST /api/runtime/metadata/ui/panels/
CrossDockingConsolePanel/actions/{concluir,cancelar}`. Both procedures' step traces showed all
four steps `ok:true`; `situacao` landed `Concluido`/`Cancelado` respectively (confirmed via the
gateway trace's `lifecycleTransition: "Ativo->Concluido"`); the linked Recebimento and Expedicao
both flipped `crossDockingAtivo` true->false while every other field (estagio, dataRecebimento,
movimentoId, situacao, transportadoraId, etc.) came back byte-identical to the pre-call baseline --
the exact "preserve everything not named in set" guarantee patchConcept exists for. No
crossDockingSync capability involved (confirmed absent from the generated plugin manifests; the
remaining string matches in generated metadata are prose in procedure descriptions, not bindings).

### REG-76 — Workbench action inputFields rendered as a single-line <input type="text">, which silently strips/collapses newlines on assignment -- a 'paste multi-line text' propose action (e.g. paste a CSV) had its payload collapsed to one line client-side, so the server-side parser's own header-row detection consumed the entire pasted text and returned zero data rows

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-29)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md G4 -- found live while building ImportarContagemProcedure (inventario.html's second Class A wizard). G3's ParseNfeProcedure test happened to use single-line XML, which never triggered this; G4's multi-line CSV paste triggered it on the very first live browser attempt.
**Surface:** `generator/workbench-template`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/workbench-page.html.mustache`

The `inputFields` mini-form G3 added to workbench actions (`autoPanels[].transaction.metadata
.actions[].inputFields`, mirroring Move 2 G3's `panelAction.inputFields`) rendered each declared
field as a plain `<input type="text">`. HTML single-line text inputs cannot hold a newline
character -- assigning a value containing `\n` to `.value` on such an element silently
collapses/normalizes it (confirmed live: a Chromium `fill()` with an embedded `\n` produced a
DOM value with the newline replaced by a space).

**Reproduced live, exact failure**: pasting a real pipe-delimited CSV payload (a header line plus
one data line, joined by `\n`) into the `texto` input and clicking "Importar Contagem (Parse)"
posted a single-line string to `ImportarContagemProcedure`. `InventoryFileCapability
.importarContagem`'s own header-row detection (`if (firstLine && trimmed.toLowerCase()
.startsWith("localarmazenagemid")) { skip }`) treated the WHOLE flattened string as the header
line (since it starts with "localarmazenagemid" and there was no `\n` left to split on), consumed
it, and returned `sucesso:false, motivo:"Nenhuma linha valida encontrada"` -- the draft ended up
with 0 rows, silently, with no visible error (the generic invoke-and-patch flow treats a
business-level `sucesso:false` as a normal "review and Save" success, since the procedure itself
ran without a step error).

**Confirmed root cause precisely**, not guessed: hooked `window.fetch` via a ScrapForAI `evaluate`
step to log the actual POST body sent by the browser -- it showed the pasted CSV's two lines
joined by a literal space instead of a newline, even though the same value had been correctly
typed into the field (visually confirmed by an earlier screenshot). Confirmed the same value posted
directly via `curl` (a real newline, not through a browser input) parses correctly and returns real
data rows -- isolating the bug to the browser's `<input type="text">` newline handling specifically,
not the server-side parser.

**Fix**: swapped `<input type="text">` for `<textarea rows="1" style="resize:vertical">` in the
workbench action inputFields renderer -- a textarea preserves newlines and degrades fine for a
short single-line value too (confirmed: G3's ParseNfeProcedure single-line XML input still works
correctly with a textarea).

**Verified live, both before and after**: RED reproduced live (browser test showed 0 parsed rows
despite correct visual input, `window.fetch` hook confirmed the flattened payload). Fixed, then
the identical live browser routine (real textarea fill + real click) now shows a real parsed
`linhas` row (`divergente:true, localArmazenagemId:..., loteId:..., produtoId:..., quantidadeContada:9,
quantidadeEsperada:10`), confirmed by screenshot, matching what the same payload returns via direct
REST.

**Related, not fixed here**: Move 2 G3's `panelAction.inputFields` (`business-ui-app.mustache`)
renders the identical `<input type="text">` pattern and has the identical latent bug -- not
triggered because no currently-declared panel action's `inputFields` collects naturally multi-line
text (`WidgetOrderReviewPanel.place`, `CrossDockingConsolePanel.ativar` are all short scalars).
Left unchanged (no current caller needs it, and touching already-shipped, already-tested Move 2
code for a theoretical future need is out of scope here) -- named so a future multi-line panel
action doesn't rediscover this blind.

### REG-77 — Neither procedures nor flows can create a brand-new sibling concept record with an auto-generated id from within a read-modify-write side effect -- patchConcept (REG-75/Move 4) only works on records that already exist, and flows have no patch step at all

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE4_CROSS_RECORD_WRITE_PLAN.md, sections 4.2 (Ativar's own crossDockingAtivo=true flag-sync)
and 4.3 (SyncOcupacaoProcedure, closing docs/MOVE3_G2_CHECKLISTS.md's M8/M9 residual). Both were
scoped by that plan as closeable by patchConcept/onCommit alone ("the same one-liner with true").
Found live while attempting to implement them, immediately after REG-75 itself was closed by the
same session's patchConcept + aggregate.onCommit work (dc35d54..this commit).

**Surface:** `kernel/procedure-runtime, kernel/flow-engine`

Investigated, not fixed. patchConcept (REG-75) reads an EXISTING record and fails
`CONCEPT_NOT_FOUND` if it is absent -- by design, it is a patch, not an upsert. Two real Move 4
candidates need the *create* half of that same shape, and both hit the same wall from different
sides:

1. **Ativar's flag-sync** (plan 4.2): activating a CrossDocking needs to ALSO set
   `crossDockingAtivo=true` on the linked Recebimento/Expedicao -- the mirror of what E1 (REG-75)
   already does for Concluir/Cancelar's `crossDockingAtivo=false`. But `CrossDocking` is a plain
   concept, not an aggregate (confirmed: no `aggregates` entry named CrossDocking in
   AppGen/apps/_official/WmsOffice/definition/model.json), so `aggregate.onCommit` does not apply.
   `Ativar` is bound to a FLOW (`AtivarCrossDocking`), not a procedure, and that flow's
   `createConcept` step is what supplies the new CrossDocking id: `createConcept`/`updateConcept`
   both compile to a `capabilityCall` against the `persistence` capability's `save` operation
   (`ModelCompiler.resolveCapabilityNameForStep`/`resolveOperationNameForStep`,
   NPDevContract/dsl/.../compiler/ModelCompiler.java:1554-1597), and auto-id-generation lives
   INSIDE that capability adapter, not in `ConceptGateway`:
   `PostgresPersistenceCapabilityAdapter.save()` (NPDevKernel/adapters/persistence-postgres/...):
   `if (id == null || blank) { id = UUID.randomUUID().toString(); }` (lines 79-85).
   `DefaultConceptGateway.save()` (used by both `AggregateRuntime` root commits and every procedure
   step) has no such fallback -- it uses `request.id()` as given. Procedures' own `saveConcept`
   confirms this: `DefaultProcedureExecutor.saveConcept` calls
   `requireString(state, step.idRef(), ...)`, which throws if `idRef` does not resolve to a
   non-blank value -- there is no blank-id-generates-a-UUID branch anywhere in the procedure
   executor (confirmed: zero references to `com.npdev.kernel.ports.IdProvider` anywhere in
   `DefaultProcedureExecutor.java` or `ProcedureRunner.java`, despite that port already existing
   and doing exactly this for one caller: `IdProvider.uuid()`).
2. Flows have no read-modify-write step either: `updateConcept` (flow) compiles to the same
   `persistence.save`, which requires the FULL replacement record, same as `saveConcept` did
   before patchConcept existed for procedures -- there is no flow-level `patchConcept` equivalent,
   and adding one is a second, separate step-vocabulary change (flows and procedures are executed
   by two different engines with two different step enums).
3. Custom `plugin:java-source` capabilities (`AllocationCapability`, `FiscalImportCapability`, the
   now-deleted `CrossDockingSyncCapability`) cannot be the workaround either: confirmed by reading
   `AllocationCapability.java`, these classes have NO constructor dependencies and import nothing
   beyond `java.*` -- they are pure functions with no `ConceptGateway`/persistence access at all,
   so a capability cannot itself read-patch-write a sibling record; it can only transform the
   `Map` it is handed.
4. **SyncOcupacaoProcedure** (plan 4.3, M8/M9): the original `syncOcupacao` client logic finds an
   existing `LocalArmazenagemLote` row for a (localArmazenagemId, loteId) pair and PATCHes its
   quantidade if found, but CREATES a new row (POST, server auto-generates the id) if not found.
   The "patch an existing row" half is expressible with today's patchConcept + onCommit (once the
   Movimento aggregate's `onCommit` is wired) -- but the "create a new occupancy row when none
   exists yet" half hits the identical wall as point 1. A patch-only implementation would silently
   drop every position that needs a brand-new occupancy row, which is a correctness regression for
   a warehouse occupancy tracker (an operator would see a stale/missing occupancy count with no
   error), not a smaller-but-safe partial win -- so it was not attempted rather than shipped half
   right.

**What this blocks, precisely**: Move 4 plan sections 4.2 and 4.3 only. Sections 4.1 (patchConcept
+ onCommit mechanism itself), 4.4 (the situacao-literal simplification + crossDockingSync deletion)
and the Recebimento/Expedicao clear-flag half of 4.2's own sibling gap (Concluir/Cancelar) are
fully closed by REG-75's patchConcept/onCommit work and do not depend on this gap at all.

**What would close it** (not attempted, scoped for a future session): either (a) a new flow-level
step mirroring patchConcept for the flow engine specifically, or (b) wire the existing, currently
unused `com.npdev.kernel.ports.IdProvider` port into `DefaultProcedureExecutor`/`ProcedureRunner`
so `saveConcept` (and a future create-capable variant of `patchConcept`) falls back to
`IdProvider.uuid()` when `idRef` resolves to blank, matching what
`PostgresPersistenceCapabilityAdapter.save()` already does for flows. Either is a real, scoped
platform change with its own risk surface (schema/compiled-model/canonical-JSON/validation, the
same shape REG-75 itself required) -- named here rather than half-solved inline.

---

**Resolution (2026-07-30, docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, "Move 5" Wave 1):** option (a) AND
(b) both landed, closing this ledger item's literal claim in full:

- New flow step `callProcedure` (kernel `FlowStepDefinition.Type.CALL_PROCEDURE` +
  `CallProcedureStep`): a flow can now synchronously invoke a named procedure, reusing the
  procedure's OWN `ProcedureExecutor` -- giving flows every procedure step at once (patchConcept
  included), not a bespoke per-step mirror. Wired live in `NpdevCapabilityBindingConfig.
  kernelRunner()` against the SAME `ProcedureRunner`/registry `PanelRuntime`/`AggregateRuntime`
  already use.
- `com.npdev.kernel.ports.IdProvider` is now wired into `DefaultProcedureExecutor`: `saveConcept`
  falls back to a generated id on a blank/unresolved `idRef` (matching
  `PostgresPersistenceCapabilityAdapter.save()`'s existing flow-side behavior), and `patchConcept`
  gained an opt-in `createIfMissing` flag (default `false`, preserving REG-75's patch-not-upsert
  semantics exactly) that builds a brand-new record with a generated id from `set` alone on a
  `CONCEPT_NOT_FOUND` miss.
- Both proven RED->GREEN with real, targeted temporary-break checks (`KernelRunnerCallProcedureTest`,
  `DefaultProcedureExecutorCreateIfMissingTest`), given real corpus coverage in
  `dsl-conformance-max` (`step.callProcedure`, `procedure.createIfMissing`), and found (while
  implementing) a real, previously-undiscovered silent-data-loss bug: `ModelResolver.cloneStep()`
  -- a SECOND `StepAst` construction path used for flow `specializes` inheritance -- dropped the
  new `procedure` field silently on every clone, with no ratchet test protecting against this
  class of bug (only the compiled-JSON round trip has one). Fixed alongside.
- Applied live to WmsOffice: `AtivarCrossDocking` still creates `CrossDocking` via its existing
  `createConcept` step (auto-id-generation there was never the missing piece), then calls the new
  `SetCrossDockingFlagsProcedure` via `callProcedure` to set `crossDockingAtivo=true` on the linked
  Recebimento/Expedicao -- the mirror of `Concluir`/`CancelarCrossDockingProcedure`'s own
  clear-flag steps. This closes REG-77-A (Ativar's own flag-sync) in full.

**The crossdocking.html deletion this closure was supposed to unlock (plan §4.5/§5, "first HTML
deletion in five moves") is NOT executed.** Verified live before attempting it: the generated
app's `GeneratedBusinessUiRouteController` only maps `/npdev-business-ui` (and trailing slash) --
no controller serves an arbitrary declared Panel's own route (`CrossDockingConsolePanel`'s
`/crossdocking`) as a real, navigable page, and `menu.json`/`pages.json` both point at
`crossdocking.html` directly (`shell.js.mustache`'s `resolveHref` just returns `"/" + target` for
`kind: PAGE` -- it would happily navigate to `/crossdocking` too, but nothing serves that path).
Deleting the file now would strand the console with no reachable URL, a real regression the
situacao/flag-sync fixes above do not touch. This is exactly Wave 6's still-unstarted `panel.route`
finding ("threaded through nine files... nothing registers it as a navigable URL") landing early,
confirmed live rather than assumed. Left undeleted; revisit once Wave 6 lands.

**REG-77-B (SyncOcupacaoProcedure, M8/M9) is NOT closed by this** -- investigating it under Wave 1C
surfaced that `createIfMissing` alone is not enough: the original `syncOcupacao` client logic finds
an existing `LocalArmazenagemLote` by a (localArmazenagemId, loteId) PAIR (not by id at all -- there
is no declared query expressing this lookup yet), and when found, INCREMENTS its existing quantity
by a delta rather than setting an absolute value -- and no procedure step can read an old value and
compute old+delta (`patchConcept`'s `set` is literal-or-ref copy only, never an expression; there is
no arithmetic/accumulation primitive anywhere in the procedure step vocabulary). This is a second,
independent missing primitive, not a shortfall of `createIfMissing` itself -- named precisely and
filed separately as REG-78 rather than folded in here or half-solved with an absolute-overwrite that
would silently produce wrong occupancy counts.

### REG-78 — Procedures have no find-by-non-id-fields lookup usable inline with patchConcept, and no arithmetic/accumulation primitive -- blocking SyncOcupacaoProcedure's real find-or-increment semantics (M8/M9)

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 1C (SyncOcupacaoProcedure, closing
docs/MOVE3_G2_CHECKLISTS.md's M8/M9 residual, referenced as REG-77-B). Found while attempting to
implement it immediately after REG-77's callProcedure + createIfMissing work landed and closed
REG-77-A (Ativar's flag-sync) in the same session. Closed as the final item of Move 5
(docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md), picked as the "one remaining open item" per the plan's own
closing instruction, after Waves 1-6 completed.

**Surface:** `kernel/procedure-runtime`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/ProcedureStepType.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/ProcedureStep.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/ProcedureStepAst.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledProcedureStep.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/PackValidation.java`
- `NPDevContract/schemas/model.schema.json (+3 mirrors)`

Investigated, not fixed. The original `syncOcupacao` client logic (movimentacao-livre.html /
centro-trabalho.html, WmsOffice) does, for each (localArmazenagemId, loteId) pair touched by a
Movimento's posicoes: find the existing `LocalArmazenagemLote` row for that pair; if found,
INCREMENT its quantidade by a signed delta (positive for Destino, negative for Origem); if not
found (and the delta is positive), CREATE a new row. `patchConcept`'s new `createIfMissing`
(REG-77) makes the "create if genuinely absent" half expressible -- but two more primitives are
missing, confirmed by re-reading the executor directly rather than assumed:

1. **No find-by-non-id-fields lookup composable with patchConcept.** `patchConcept`'s `id` is a
   direct primary-key lookup (`ConceptReadRequest(conceptName, id, tenantId)`) -- it has no
   "find where field=value" mode. Procedures do have `listConcepts`/`runQuery` steps that CAN
   filter by declared query criteria, but composing "runQuery to find candidate, extract its id
   via mapValue, THEN patchConcept with that id" requires a NAMED query declaration
   (`localArmazenagemId == ? && loteId == ?`) that does not exist for `LocalArmazenagemLote` today,
   plus an `ifThenElse` step to route "found" vs. "not found" -- itself only cleanly expressible
   if the found-id extraction and the not-found blank-id path can share one patchConcept call,
   which they can (createIfMissing tolerates a blank id) -- so this half is NOT a hard platform
   wall, just undeclared corpus/model work (a new query + forEach/ifThenElse composition), unlike
   point 2.
2. **No arithmetic/accumulation primitive anywhere in the procedure step vocabulary.**
   `patchConcept`'s `set` values are literal-by-default or a direct state-ref copy
   (`DefaultProcedureExecutor.resolveSetValue`) -- never an expression, and never a function of the
   CURRENT value being overwritten. Computing `newQuantidade = existing.quantidade + delta` needs:
   read the existing value (via `readConcept` + a `mapValue` dotted-path extraction, both of which
   already work), then ADD a delta to it -- and no step type performs arithmetic. `mapValue` is
   copy-only. This is confirmed a genuine, independent gap: unlike point 1, no composition of
   EXISTING steps closes it. A `forEach` loop's own per-iteration state cannot accumulate a running
   total across iterations either (each iteration's `itemKey` binding is independent), so even a
   naive "sum all posicoes for this pair, then set absolute quantity" workaround does not compose
   from what exists today.

**What this blocks, precisely**: SyncOcupacaoProcedure (M8/M9) only. Nothing else in Move 4 or
Move 5 Wave 1 depends on this -- REG-75 (patchConcept/onCommit), REG-77-A (Ativar's flag-sync, this
session) and the WmsOffice `crossDockingSync` capability deletion are all fully closed and do not
need either primitive named here.

**What would close it** (not attempted, scoped for a future session): point 1 needs only a
declared query + procedure composition (no new platform code). Point 2 needs a genuinely new
procedure step -- e.g. a `computeValue`/`arithmetic` step taking two refs (or a ref and a literal)
and an operator (`add`/`subtract`, the minimum `syncOcupacao` needs), writing the result to a
target key the same way `mapValue` already does. Shipping an absolute-overwrite instead (skipping
the increment) was deliberately rejected: it would silently produce a wrong occupancy count for
every position that already had stock, which is worse than not shipping `syncOcupacao` at all.

### REG-79 — A callCapability procedure step's args map is compiled with an unspecified, per-JVM-run-random iteration order (Map.copyOf), silently scrambling positional reflective dispatch for any multi-arg capability method

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 3A (Gap 6, mapList). Found live while wiring
ParseNfeProcedure's produtosConhecidos/chavesJaImportadas auto-match (WmsOffice) through a NEW
3-argument callCapability step -- the first callCapability in this codebase with more than one
args entry (every prior usage passed a single {"input": "$input"} arg, where iteration order is
trivially always correct).

**Surface:** `dsl/compiled-model, kernel/runtimehost-dispatch`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledProcedureStep.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/ArtifactLocalJavaSourceCapabilityHandler.java`

A `callCapability` procedure step's `args` (a JSON object, e.g.
`{"input": "$input", "produtosConhecidos": "$produtosConhecidos", "chavesJaImportadas": "$chavesJaImportadas"}`)
is compiled by `ModelCompiler.compileProcedureSteps` via `sortObjectMap` into alphabetical-by-key
order (a determinism convention used for every Map field on this record, originally for stable
canonical-JSON diffs -- not because argument order is meant to be alphabetical). At runtime,
`ProcedureRunner`/`NPDevCliMain`'s `toProcedureStep` builds a `callCapability`'s positional
`argRefs` from `step.args().values()`, and `ArtifactLocalJavaSourceCapabilityHandler.invoke()`
reflectively calls the bound Java method with those resolved values IN THAT ITERATION ORDER
(`method.invoke(target, args.toArray())`), matched only by method name + arg count (there is no
per-parameter name binding at the reflection boundary).

`CompiledProcedureStep`'s compact constructor built `args` via `Map.copyOf(args)`, same as every
other Map field on the record (`data`/`metadata`/`set`/`select`). For those OTHER fields this is
harmless -- every consumer reads them by explicit key (`.get("input")`, `.forEach((key, raw) -> ...)`),
never by position. But `Map.copyOf` (and `Map.of`) return a JDK `ImmutableCollections.MapN` whose
iteration order is EXPLICITLY UNSPECIFIED by contract and is reshuffled by a fresh random salt
generated once per JVM process (a deliberate JDK 9+ hardening measure against hash-flooding, the
same mechanism documented for `HashMap`-adjacent immutable collections) -- so `args.values()`'s
iteration order for a 3-entry map was consistent WITHIN one running app instance but could differ
across app restarts of the IDENTICAL jar.

Symptom: `ParseNfeProcedure`'s `call-fiscal-import` step (3-entry args, alphabetically compiled to
chavesJaImportadas/input/produtosConhecidos, matching a Java method declared
`importarNfe(List chavesJaImportadas, Map input, List produtosConhecidos)`) worked correctly on
some app starts and failed on others with `JAVA_SOURCE_CAPABILITY_DISPATCH_ERROR "argument type
mismatch"` -- confirmed via temporary rich diagnostics logging each resolved arg's actual runtime
class against the resolved method's declared parameter types: on a failing start, positions 1 and
2 were swapped (`chavesJaImportadas, produtosConhecidos, input` instead of the compiled
`chavesJaImportadas, input, produtosConhecidos`), an ArrayList landing where a Map was expected.
Reproduced deterministically 2/2 broken app starts out of ~7 total starts observed live, and
reproduced in a unit test asserting insertion-order preservation (which failed deterministically,
within a single JVM run, when temporarily reverted to `Map.copyOf` -- confirming the test is a
real regression guard, not a coincidental pass).

**Fix**: `CompiledProcedureStep.args` alone (not its Map-field siblings, which have no positional
consumer) now preserves insertion order via `Collections.unmodifiableMap(new LinkedHashMap<>(args))`
instead of `Map.copyOf`. `ArtifactLocalJavaSourceCapabilityHandler`'s dispatch-error path also now
reports the resolved method's signature and each actual argument's runtime type in its failure
details, so a future arg-count-matches-but-types-don't mismatch is a one-look diagnosis instead of
a bare, un-actionable "argument type mismatch".

**Verified live**: WmsOffice's `ParseNfeProcedure` (3-arg callCapability into
`FiscalImportCapability.importarNfe`) succeeded on 10/10 consecutive fresh app restarts after the
fix (previously ~2/7 failed); confirmed both the produtosConhecidos auto-match plumbing (correct
{codigo, produtoId} shape from a real `listConcepts` + `mapList` composition) and the
chavesJaImportadas dedup rejecting a real duplicate NF-e key end-to-end
(`"NF-e ja importada anteriormente (chave duplicada): CHAVE-TEST-1"`), which was never reachable
before this session since nothing had ever populated `chavesJaImportadas` prior to Wave 3A's
mapList step.

**Scope note**: any Java capability method bound to a `callCapability` with 2+ `args` entries
must declare its parameters in the ALPHABETICAL order of those args' JSON keys, not the more
natural declaration order an author would write -- documented on `CompiledProcedureStep.args`'s
own field comment and on `FiscalImportCapability.importarNfe`'s 3-arg overload, since this is a
real authoring footgun the platform does not (and, short of a larger args-binding redesign,
cannot cleanly) prevent at compile time.

### REG-8 — LNCH-1-B9: schema-ahead detector blind to a pure column drop on rollback

**Type:** BOUNDARY · **Severity:** — · **Status:** DONE (2026-07-22)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance
**Surface:** `runtimehost/schema-lifecycle`

Rolling an older build back onto a database a newer build had already migrated past (which dropped
a column) silently re-added the dropped column empty, instead of refusing. Owner decision: closed as
"a clear refusal exists," not "every drop is reconstructed" -- data a genuine drop destroyed stays
gone. Fix (Trigger C): SchemaLifecycleExecutor.databaseMigratedPastThisBuild consults
npdev_schema_history instead of live schema shape -- finds the most recent successfully-applied row
for this build's target fingerprint; if a LATER row records a different fingerprint, refuses before
classify() ever runs, guarding every resolution kind uniformly. CORRECTION found by independent code
verification and fixed as REG-27: the original implementation only refused when the rolled-back-to
build had a PRIOR history row, which a fresh-installed build never wrote -- so the register's own
canonical example (fresh-installed build N, N+1 drops a column, roll back to N) was not actually
refused until REG-27 made afterMigrate record the initial realization as an APPLIED history point
too. This item's DONE claim holds only with the REG-27 fix applied.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-8`

### REG-80 — field.sensitive is dead wiring -- parsed, compiled, and canonical-JSON round-tripped, but never consumed by anything, including its own documented external-AI-review-pack redaction purpose

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 5 (Tier 2 zero-witness sweep, item 7 "constraint:sensitive").
Investigated while trying to author a real corpus declaration and confirm the feature actually
redacts something -- found live via a targeted research pass rather than assumed from the plan's
own one-line framing ("drives redaction in traces and event payloads").

**Surface:** `dsl/field-metadata, kernel/adapters/external-ai-pack-core, scripts/external-review`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/ast/FieldAst.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledField.java`
- `NPDevKernel/adapters/external-ai-pack-core/src/main/java/com/npdev/adapters/externalai/packcore/ReviewPackBuilder.java`
- `scripts/external-review/build-review-pack.py`
- `NPDevKernel/adapters/tracing-redaction-default/src/main/java/com/npdev/adapters/tracing/redaction/`

`field.sensitive` (a boolean on a concept field, `model.schema.json`'s own description: "ADR-0009:
marks this field for redaction before it may appear in any external-AI review pack... independent
of the platform's runtime EventRedactionPolicy family") is fully threaded through the DSL's own
plumbing -- `JsonModelParser` parses it into `FieldAst.isSensitive()`, `ModelCompiler` copies it
into `CompiledField.isSensitive()`, and `CompiledModelCanonicalJson`/`Reader` round-trip it through
canonical JSON -- but that is the ENTIRE extent of its wiring. No other file anywhere in the repo
calls `.isSensitive()`. Two separate things this field is documented (or assumed by the Wave 5
plan) to drive turn out to be independently unconnected to it:

1. **Its own documented purpose (external-AI review-pack redaction) is not wired.** Both
   implementations of the review-pack builder -- `ReviewPackBuilder.java` (Java) and
   `scripts/external-review/build-review-pack.py` (its Python twin) -- never reference
   `CompiledField`, concept fields, or `isSensitive()` at all. Their only redaction is a
   content-shape regex scanner (`SanitizerFailedException` triggered by matching raw file content
   against `secret-content-patterns.json`, e.g. API-key-looking strings) -- unrelated to the model
   schema entirely. A field marked `sensitive: true` today has its VALUE included in a review pack
   exactly like any other field, with zero special handling.
2. **Runtime trace/event redaction (`EventRedactionPolicy` and its `DefaultEventRedactionPolicy`/
   `DefaultTraceRedactionPolicy`/`DefaultExecutionRedactionPolicy` implementations,
   `NPDevKernel/adapters/tracing-redaction-default/`) is a COMPLETELY SEPARATE, hardcoded
   mechanism**, keyed off `SensitiveKeyPolicy.isSensitiveKey(key)` -- a KEY-NAME substring denylist
   loaded from `sensitive-key-patterns.json`, plus fixed field-name allowlists per policy
   (`INFO_ALLOWLIST`/`DEBUG_PAYLOAD_ALLOWLIST`) and a `looksLikeSensitiveValue` heuristic (contains
   `@`). None of this reads the model or `CompiledField` at all -- so even though the schema's own
   description explicitly disclaims a connection to this "family," the plan's assumption that some
   model-driven redaction mechanism exists ANYWHERE for traces/events is also not true.

The Python build script's own comment (`build-review-pack.py:73-75`) confirms this is a KNOWN,
deliberately-deferred gap, not an accidental oversight: `sensitive-key-patterns.json`'s field-name
sibling "isn't read yet because P6/P7 will need it once the product producer redacts an app's own
structured records for M7" -- i.e. structured, model-field-driven redaction of an app's own
business data is explicitly named as FUTURE work in the existing roadmap, referenced here rather
than re-derived.

**What this blocks, precisely**: any author who marks a field `sensitive: true` today gets NO
actual protection anywhere -- not in a generated review pack, not in traces, not in event
payloads. This is a real, if narrow, information-exposure risk for anyone relying on the flag as
documented.

**Closed (Move 7 R80, docs/MOVE7_IMPLEMENTATION_SPEC.md) -- piece (a) is NOT_APPLICABLE, piece (b)
is wired and live-proven:**

**(a) NOT_APPLICABLE, with evidence, not silently skipped.** Confirmed by reading BOTH the real
production caller (`ReviewAdminController.java`'s `/build-pack` endpoint) and `ReviewPackBuilder
.build`'s own signature: a review pack's `sections` are `{label, text}` pairs supplied ENTIRELY by
the calling operator (an admin pasting/uploading arbitrary prose or source code via the
ControlPanel `_ops` UI) -- there is no code path anywhere that auto-populates a section from a
concept's live record data. `field.sensitive` marks a FIELD on a CONCEPT; review packs never
carry concept/record data in the first place, so there is nothing for this piece to redact. Wiring
redaction into `ReviewPackBuilder` for a data shape (`{concept, field, value}`) that never actually
reaches it would be dead code protecting against an input that cannot occur -- correctly marking
this NOT_APPLICABLE rather than adding it anyway.

**(b) Wired.** `SensitiveKeyPolicy.registerModelSensitiveFieldNames(Collection<String>)` (new,
`NPDevKernel/adapters/tracing-redaction-default/.../SensitiveKeyPolicy.java`) adds a mutable,
EXACT-match (not substring) field-name registry, OR'd into `isSensitiveKey` alongside (never
replacing) the existing static substring denylist -- kept DSL-agnostic (no dependency on
`CompiledModel`/`CompiledField`, which this adapter module does not otherwise depend on; the
caller passes plain strings). `NpdevObservabilityConfig.java` (RuntimeHost template -- already the
one class with BOTH `CompiledModel` and the tracing-redaction-default adapter types in scope, since
its `StartupValidator` bean already depends on `CompiledModel`) gained a new `@Bean
sensitiveFieldModelRegistration(CompiledModel)` that extracts every `field.isSensitive()` field
name across every concept and registers them at boot, alongside the existing
`traceRedactionPolicy`/`eventRedactionPolicy`/`executionRedactionPolicy` beans it already declared.

**Verified live**, not just unit-tested: `tracing-redaction-default:test` 8/8 pass (2 new +
`SensitiveKeyPolicyTest`'s existing 3, plus 3 more covering OR-not-replace and clear-on-empty).
`NpdevObservabilityConfigSensitiveFieldTest` (new) proves the actual REG-80-named gap is closed --
`isSensitiveKey("customerEmail")` is `false` before registration, `true` after -- using dsl-
conformance-max's EXISTING `WidgetOrder.customerEmail` (`sensitive: true`) hand-mirrored as the
test subject (no new corpus witness authored, per this task's own instruction; `CompiledModel` has
no constructor that parses a model.json file by path, so it is hand-built the same way every other
RuntimeHost config test already does, e.g. `RuntimeConceptGatewaySemanticPoliciesTest`). Run
inside a real generated+booted app (WmsOffice, full `npdev-generated` mount present, avoiding the
known "PanelRuntime-family tests don't compile outside a mount" gap this session's own memory
already documents) -- confirmed 2/2 new tests pass AND the app's full suite (437 tests, 0
failures) still passes with this change in place.

Corpus/round-trip protection from the original finding is unchanged: dsl-conformance-max's
`WidgetOrder.customerEmail` (`sensitive: true`) still protects the parse/compile/canonical-JSON
round trip, and is now ALSO the live proof subject for piece (b), not just a round-trip fixture.

### REG-81 — ReleaseGateValidator.validatePromotion (concept.truthLevel promotion gating) is fully implemented and unit-tested but invoked by no real pipeline -- truth-level promotion is effectively dormant

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 6 ("re-verify concept.truthLevel, actionMetadata.
inputFormHint, field.connectable -- do not implement before establishing which is metadata-only
vs. a real gap"). truthLevel itself came back fully wired (SemanticValidator's own
ReferenceValidation.validateBondTruthEdge runs on every real generate/validate call and emits a
live "no upward truth edges" warning) -- this item is a narrower, separate finding surfaced
incidentally while re-verifying it: a SECOND, more powerful truthLevel consumer exists in the
same validation package, fully built and tested, but wired into nothing that actually runs.

**Surface:** `dsl/validation, dsl/release-gate`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ReleaseGateValidator.java`
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/validation/ReleaseGateValidatorTest.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/GeneratorMain.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/cli/ModelValidatorMain.java`

`ReleaseGateValidator.validatePromotion` really does block a promotion when a reachable
bond-closure concept sits below the target truth rank, and requires evidence for T4+ -- it is
correctly implemented and has its own passing unit test (`ReleaseGateValidatorTest`). But a
repo-wide grep for its call sites turns up exactly one: its own test. Neither `GeneratorMain`
nor `ModelValidatorMain` (the two real entry points every actual generate/validate run goes
through) ever calls it, no CLI flag exposes it, and `.github/workflows/npdev-release-gate.yml`
runs an unrelated roadmap/traceability gate despite the similar name -- there is no CI step
anywhere that invokes truth-level promotion gating. `docs/DSL_REFERENCE.md:60-63`'s prose ("Release
validation is separate: promotion blocks below-rank concepts...") accurately describes what the
code CAN do, but overclaims that this happens today -- as written, nothing in the platform
currently calls this code outside its own test.

**What this blocks, precisely**: an author can promote/release a model whose bond-closure
reaches a concept below its declared truth level, with T4+ evidence entirely unenforced -- the
gate exists in source but never fires. Distinct from, and narrower than, REG-80 (field.sensitive):
this is a complete, tested capability sitting unplugged, not missing logic.

**Closed (Move 7 R81, docs/MOVE7_IMPLEMENTATION_SPEC.md)**: wired into `ModelValidatorMain` only
(not `GeneratorMain`, not any CI step, not `.github/workflows/npdev-release-gate.yml` -- all
explicitly out of scope, since the target truth level/evidence source for an automatic CI check
is a product decision this session did not make) behind three opt-in flags: `--releaseGate`
(boolean), `--targetTruthLevel=<T0..T6>` (required together with `--releaseGate`, else a single
usage diagnostic explaining the requirement), and optional repeatable `--evidencePath=<path>`
wired to `ReleaseGateValidator.evidencePaths(...)` (falls back to `EvidenceProvider.none()` when
omitted, matching the class's own tested default). `runReleaseGate` iterates every concept in the
parsed model and calls `validatePromotion` per concept, mirroring
`ReleaseGateValidatorTest.java:56`'s argument shape. `NPDevContract/dsl/build.gradle`'s
`validateModel` task gained matching `-PreleaseGate -PtargetTruthLevel=... -PevidencePath=...`
pass-through for manual/CI-adjacent invocation.

Verified live: `:validateModel` with no new flags is byte-identical to before (0 diagnostics on
`dsl-conformance-max`, confirming zero behavior change for every existing caller); with
`-PreleaseGate -PtargetTruthLevel=T3` against the same model, every T1 concept in its bond
closure now reports a real `truth_closure_below_target` diagnostic; `-PreleaseGate` alone (no
target level) reports the usage diagnostic instead of silently doing nothing. 6 new unit tests in
`ModelValidatorMainReleaseGateTest` (the CLI's first-ever test coverage) cover: all-concepts-pass,
a real closure violation, T4+ missing-evidence, T4+ with matching evidence paths suppressing it,
and both malformed-flag-usage cases. `docs/MOVE7_IMPLEMENTATION_SPEC.md`'s cited
`docs/DSL_REFERENCE.md:60-63` overclaim was actually located at
`NPDevContract/docs/MODEL-CONTRACT.md` (the ledger's own file citation was stale/wrong -- verified
by grepping the exact quoted prose repo-wide) and has been corrected there to name the real,
opt-in invocation surface instead of implying automatic enforcement.

### REG-82 — NPDevCliMainTest.idempotencyHitReturnsCachedResultMetadata fails deterministically (IOException loading its own temp model file) -- pre-existing, unrelated to Move 5

**Type:** BUG · **Severity:** LOW · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** Surfaced incidentally while running scripts/quality/run-generator-gate.ps1 as a final regression
check before committing Move 5 Wave 6 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md) -- the gate's full
multi-project build runs :tools:npdev-cli:test, a target none of Wave 1-6's own narrower
:dsl:test/:generator:test runs had exercised, so this had gone unnoticed all session.

**Surface:** `tools/npdev-cli`
**Files:**
- `NPDevGenerator/tools/npdev-cli/src/test/java/com/npdev/cli/NPDevCliMainTest.java`
- `NPDevGenerator/tools/npdev-cli/src/main/java/com/npdev/cli/runtime/CliRuntimeFactory.java`
- `NPDevKernel/adapters/flow-compiled/src/main/java/com/npdev/adapters/flowcompiled/ModelBackedKernelRuntimeFactory.java`

`NPDevCliMainTest.idempotencyHitReturnsCachedResultMetadata` (Java `tools:npdev-cli` module --
distinct from the Python `NPDevCli` mentioned in this repo's module map) fails deterministically:
its first `execute` CLI invocation returns exit code 2 instead of 0, with stderr
`Unable to load model file: <temp path>\model-idem<random>.json`. That message is
`ModelBackedKernelRuntimeFactory.compileModel`'s catch-all wrapper around ANY `IOException` thrown
by `JsonModelParser().parse(modelPath)` for the freshly-written temp model file the test itself
creates via `Files.createTempFile`/`writeText` a few lines earlier in the same method.

**Confirmed NOT a regression from this session's work**: reproduced identically, in isolation
(`--tests` targeting only this one method, ruling out cross-test interference), on THREE points in
history -- the current Wave 6 working tree, commit 76c9bfc (Wave 5, capabilityPolicy changes), and
commit ed4a48f (Wave 4, before Wave 5 existed). The failure predates Move 5 entirely.

**Confirmed NOT a classpath/schema-resource-wide issue**: the other 5 tests in the same class
(`circuitBreakerCanBeExercisedFromCliSimulation`, `compileModelFailsFastWhenSchemaIsInvalid`,
`executeAndTraceCommandsWorkWithSharedStoreDir`, `validateBundleAndRunProcedureAreScriptable`,
`waitingPublishAndResumeLifecycleWorksInCli`) all pass in the same run, including
`executeAndTraceCommandsWorkWithSharedStoreDir`, which also calls `execute` against its own
temp-file model. This rules out a broken/missing schema jar resource affecting every model load.

**Root cause, found (Move 7 R82)**: neither a temp-file race nor an environment fault. The
wrapper's message was fixed FIRST to fold the wrapped exception's own class name + message into
the thrown `IllegalArgumentException` (`ModelBackedKernelRuntimeFactory.compileModel`, permanent
diagnostic improvement, kept regardless), which immediately revealed the real cause:
`com.npdev.dsl.v1.validation.ModelSchemaValidationException` -- `idempotencyModelJson()`'s own
embedded fixture used the DSL 1.0 `capabilityCall` step field aliases `cap`/`op`/`out`, retired by
the 2026-07-27 DSL 2.0 flowStep-vocabulary narrowing (`model.schema.json`'s `flowStep` definition
now only accepts `capability`/`operation`/`output`) -- this ONE fixture was simply never migrated
when that breaking change landed, unlike every git-tracked corpus model (which the DSL 2.0 commit
did migrate; a hand-authored test fixture string isn't part of that corpus and the codemod has no
way to reach it).

**Fix**: `idempotencyModelJson()`'s step now uses `"capability"`/`"operation"`/`"output"` in place
of `"cap"`/`"op"`/`"out"`. `ModelBackedKernelRuntimeFactory.compileModel`'s catch block permanently
folds the wrapped `IOException`'s (here, `ModelSchemaValidationException`, which is NOT an
`IOException` subtype but was being caught by the same generic `catch (IOException)` -- worth
re-checking if `JsonModelParser.parse` should really declare schema-validation failures as
`IOException` at all, not attempted here, out of scope) class name + message into the thrown
message, so the next similar failure is diagnosable without a debugger.

Verified: `idempotencyHitReturnsCachedResultMetadata` passes in isolation; all 6 tests in
`NPDevCliMainTest` pass together; `scripts/quality/run-generator-gate.ps1` (the gate that
originally surfaced this) passes end to end.

### REG-83 — saveConcept's blank-idRef auto-generate fallback and patchConcept's createIfMissing create half both silently denied CONCEPT_FIELD_REQUIRED against a real governed ConceptGateway -- the auto-generated id was never folded into the write's own data map

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-30)
**Verification:** VERIFIED_LIVE
**Source:** Found live while proving REG-78's SyncOcupacaoProcedure closure (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md,
final item) end to end against a real generated WmsOffice boot -- the very first real invocation
of patchConcept's createIfMissing against a real concept (LocalArmazenagemLote, an ordinary
required-id concept, not a special case) failed with CONCEPT_FIELD_REQUIRED. Every kernel unit
test exercising createIfMissing/saveConcept's auto-id fallback up to this point (including this
session's own dsl-conformance-max EnsureWidgetOrderAuditProcedure fixture and Wave 1B/REG-77's own
work) wired DefaultConceptGateway with a permissive/noop semantic policy, so none of them could
have caught this -- it took a real generated-app boot, with the real
ConfiguredConceptGatewaySemanticPolicy every actual app runs, to surface it.

**Surface:** `kernel/procedure-runtime, kernel/concepts`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/concepts/ConfiguredConceptGatewaySemanticPolicy.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/procedures/DefaultProcedureExecutorAutoIdSemanticPolicyTest.java`

`ConfiguredConceptGatewaySemanticPolicy.normalizeAndValidate` (the real, governed semantic policy
every generated app wires up) requires every concept field declared `required: true` to be present
in the WRITE REQUEST'S OWN `data` map -- including "id", which is `required: true` on essentially
every real concept (it is the primary key). Two procedure-step code paths generate a fresh id when
none was supplied, but neither ever added that id back into `data`:

1. `saveConcept`'s `resolveOrGenerateId` (Move 5 Wave 1B, closing REG-77's asymmetry with flow-
   bound createConcept) resolves/generates `id`, but the step's own `data` (from `dataRef`, e.g.
   `$input`) is used completely unmodified -- if the caller's payload has no "id" key yet (the
   normal shape for a fresh record with an auto-generated id), the write is denied.
2. `patchConcept`'s `createIfMissing` (Move 5 Wave 1B, REG-77's create half) builds `created` from
   `step.setValues()` alone -- also never including the freshly generated `newId`.

Both bugs share one root cause and one fix shape: the generated id was always passed correctly as
the `ConceptWriteRequest`'s OWN separate `id` parameter (so the RECORD got saved under the right
id when the write succeeded at all elsewhere, e.g. against a permissive/noop policy), but the
SEMANTIC POLICY validates `request.data()`, a different map that the id was never copied into.

**What this blocked, precisely**: EVERY procedure-level fresh-record write with an auto-generated
id (no explicit idRef, or createIfMissing's miss branch) against a real governed app -- not a
narrow case. This includes REG-77's own original Ativar/syncOcupacao motivation and this session's
EnsureWidgetOrderAuditProcedure corpus fixture, neither of which had been proven against a real
policy before now.

**Fix**: `saveConcept` now does `data.putIfAbsent("id", id)` before saving (putIfAbsent, not put,
so an id the caller's own data already carries -- e.g. a client-supplied id -- is never
overridden). `patchConcept`'s createIfMissing branch now does `created.put("id", newId)` before
applying `set`. RED/GREEN proven: reverted both one-line fixes, confirmed 2 of 3 new kernel tests
(`DefaultProcedureExecutorAutoIdSemanticPolicyTest`, using a REAL `ConfiguredConceptGatewaySemanticPolicy`
with a required "id" field, not a noop policy) failed exactly as expected, then restored the fix
and confirmed all 3 pass. Also confirmed live: WmsOffice's new `SyncOcupacaoProcedure` (REG-78)
successfully created a real `LocalArmazenagemLote` row with no client-supplied id, verified via a
direct REST GET showing the row persisted with the correct auto-generated id.

### REG-84 — Java DataMigrationHook / code-bearing conversion hooks deferred by ADR-0008 to a never-written ADR-0003

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-31)
**Verification:** NOT_VERIFIED
**Source:** docs/adr/ADR-0008-sanctioned-destruction-conversion-hooks.md:84 defers this to
"the ADR-0003 code-bearing objects" ADR, which has never existed (docs/adr/ holds
ADR-0001 and ADR-0004..ADR-0010). Surfaced by the Move 8 record-drift sweep.

**Surface:** `dsl/conversion-hooks`
**Files:**
- `docs/adr/ADR-0008-sanctioned-destruction-conversion-hooks.md`

Records the deferred scope so it has a real home. See also docs/ACCEPTED_BOUNDARIES.md B13
("Conversion hooks are SQL-only in v1"), which is the accepted boundary this deferral sits behind.
Not scheduled; filed so the deferral is traceable rather than pointing at a missing document.

### REG-85 — dsl-conformance-max fails ReleaseGateValidator's T2 promotion bar (7 concepts stuck at T1)

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Move 8 D1 (item G3, docs/MOVE8_CLOSE_TABLE_SPEC.md): wired ReleaseGateValidator into
scripts/quality/run-generator-gate.ps1 (releaseGateT2 step) -- the smallest real check,
running ModelValidatorMain --releaseGate --targetTruthLevel=T2 against
NPDevSamples/dsl-conformance-max/Input/model.json. Before this the validator was invocable
but called by nothing (R81, REG-81). Now wired and run for the first time, it genuinely
fails: PromotedWidgetCatalogEntry, WidgetCatalogEntry, WidgetOrder, WidgetOrderAudit,
WidgetOrderLine, WidgetOrderLineNote, and labeling::Label are all only T1 -- each blocks
T2 promotion via truth_closure_below_target (a reachable bond dependency below the
target level). Per the spec's own explicit instruction, the T2 bar was NOT lowered to
make this pass -- the failure is reported here instead, and run-generator-gate.ps1 is
genuinely red until this is closed.

**Surface:** `dsl/release-gate`
**Files:**
- `scripts/quality/run-generator-gate.ps1`
- `NPDevSamples/dsl-conformance-max/Input/model.json`

This is dsl-conformance-max's own real maturity state, not a bug in ReleaseGateValidator or
in the new gate step -- the fixture was built to exercise DSL SURFACE AREA (one witness per
feature), not to carry T2-grade evidence for every concept. Promoting the 7 blocked
concepts to T2 needs real evidence per ReleaseGateValidator.EvidenceProvider's contract
(nothing supplied today falls back to ReleaseGateValidator.EvidenceProvider.none(), which
T4+ always fails; T2's own bar is lower but still requires the concept's own truth level
AND every reachable bond dependency to be at T2) -- a genuine, non-trivial maturity-closure
task, not a quick fix, and out of Move 8's own scope (D1's job was to wire the check, not to
promote the corpus). Leaving run-generator-gate.ps1 red is the intentionally chosen outcome
per the spec's own absolute prohibition against lowering D1's T2 bar to force a pass.

### REG-86 — procedure mapValue/return forced their value through a String ref-only path -- a literal array or object was impossible, only a $ref into procedure state could survive

**Type:** GAP · **Severity:** LOW · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found during Move 8 D3, never filed (Move 9 C3, docs/MOVE9 spec Part C item C3). Same shape as
the pre-patchConcept.set literal-constant gap that once forced a bespoke capability
(crossDockingSync, since deleted -- see REG-75's closure) to inject one literal boolean.

**Surface:** `kernel/procedure-runtime`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/ProcedureStep.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/npdev/service/ProcedureRunner.java`
- `NPDevGenerator/tools/npdev-cli/src/main/java/com/npdev/cli/NPDevCliMain.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/procedures/DefaultProcedureExecutorLiteralValueTest.java`
- `NPDevRuntimeHost/src/test/java/com/finalexec/npdev/service/ProcedureRunnerLiteralValueTest.java`
- `NPDevSamples/dsl-conformance-max/Input/model.json`

Two independent bugs, both required for the fix, confirmed by reading the executor directly
rather than assumed:

1. `DefaultProcedureExecutor.mapValue`/`returnValue` resolved their value via `resolve(state, ref)`
   -- a function with NO literal fallback at all: it always treats its argument as a dotted path
   into procedure state, stripping a leading "$" if present but never distinguishing "this is a
   literal" from "this is a ref." A literal array/object handed to `mapValue`'s `value` therefore
   could never survive even one hop through this function.
2. `ProcedureRunner.toProcedureStep`/`NPDevCliMain.toProcedureStep` (the two identical adapters
   converting a compiled DSL step into the kernel's `ProcedureStep`) additionally forced `step.value()`
   through `refOf(Object, String)`, which calls `String.valueOf(value)` -- for a literal `List`/`Map`,
   this produces a garbled, unusable string (e.g. `"[a, b]"`), not the original structure. Even if
   bug 1 were fixed alone, this stringification would still have destroyed the literal before it
   ever reached the executor.

Confirmed by grepping every real DSL model.json in the corpus (dsl-conformance-max, every
NPDevSamples fixture, every AppGen sample): every existing `mapValue`/`return` `value` in the
whole tracked corpus is `$`-prefixed already -- zero fixtures relied on the old always-a-path
behaviour for a bare (non-`$`) literal, so nothing in the real corpus depended on the broken
shape. (A handful of `golden-ai-scenarios/*.json`/`custom-procedure.json` fixtures use a bare
`"value": "summary"`-style string, but those are a separate ai-model intermediate schema consumed
by a conversion script before reaching JsonModelParser, not real DSL model.json -- unaffected.)

Fix: `mapValue`/`return` now resolve `value` via `DefaultProcedureExecutor#resolveSetValue`, the
SAME literal-vs-`$ref` convention `patchConcept`'s `set`, `mapList`'s `select`, and `computeValue`'s
`left`/`right` already use -- a literal by default (including an array/object, not just a scalar),
a `"$"`-prefixed String resolves against procedure state, `"$$x"` escapes to the literal `"$x"`.
`ProcedureStep.valueRef`/`returnRef` changed from `String` to `Object` to carry a literal
array/object through; `ProcedureRunner`/`NPDevCliMain` no longer stringify `step.value()` --
a new `literalOrRef` helper passes it through unchanged (only substituting a `$`-prefixed fallback
ref, e.g. `"$input"`, when no value was declared at all).

RED->GREEN proven at the kernel level: reverted both `DefaultProcedureExecutor` one-line fixes
(back to the old always-a-path `resolve()` call), confirmed 3 of 4 new
`DefaultProcedureExecutorLiteralValueTest` cases failed exactly as predicted (a literal array/object
silently resolved to `null`), restored the fix, confirmed all 4 pass. `kernel:test` full procedure
suite green (24 tests: DefaultProcedureExecutorTest, ...ComputeValueTest, ...MapListTest,
...CreateIfMissingTest, KernelRunnerCallProcedureTest) after updating 6 pre-existing kernel test
call sites that passed a bare (non-`$`) key expecting the OLD always-a-ref behaviour (e.g.
`ProcedureStep.mapValue("copy-input", "input", "copied")`) -- now `"$input"`, matching the new
literal-vs-`$ref` convention; none of these six were ever exercising a genuine literal, only relying
on the old ambiguity.

End-to-end proof through the REAL authoring pipeline (`ProcedureRunnerLiteralValueTest`,
JsonModelParser -> ModelCompiler -> ProcedureRunner -> DefaultProcedureExecutor): a DSL-authored
`mapValue` step with a literal array of objects as `value` parses, compiles, and executes,
returning the literal unchanged -- no `capabilityCall` round-trip needed to construct it.
`npdev-cli`'s identical adapter code path independently verified green via its own existing test
suite (`NPDevCliMainTest`, 6/6). `dsl-conformance-max` gained a new fixture procedure
(`ReturnLiteralCatalogSummaryProcedure`) demonstrating this; the whole corpus model still validates
clean (`ModelValidatorMain`, 0 errors/warnings).

Left open, honestly: `ProcedureRunnerLiteralValueTest` could not be run via RuntimeHost's own
`./gradlew test` in this session -- unrelated to this fix, `com.finalexec.npdev.service.PanelRuntime`
(and several of its sibling test classes) import `com.npdev.generated.runtime.service.KernelFacade`,
a type that only exists inside a fully generated+mounted FinalApp; RuntimeHost's build.gradle
deliberately excludes `PanelRuntime.java` from a bare standalone build
(`generatedRuntimeMountPresent()` gates `generatedRuntimeDependentMainSources`), but several
PanelRuntime-dependent TEST classes (that don't themselves textually reference
`com.npdev.generated.`) are not excluded to match, so `compileTestJava` fails on those pre-existing
files regardless of this change. Confirmed pre-existing and unrelated: `NPDevRuntimeHost/build.gradle`
and `PanelRuntime.java` were untouched by this fix, `git status` already showed both modified before
this session started, and the SAME failure reproduces after `./gradlew clean compileTestJava` with
zero mapValue/return-related edits in play. Not filed as a new ledger item per this session's own
scope discipline (Move 9 C3 is REG-86 only); worth a fresh item if it still reproduces once Move 9's
own scope closes.

### REG-87 — B10: H2->Postgres promotion is a chosen product arc (A4.0 answer) -- build the real command

**Type:** BOUNDARY · **Severity:** — · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** docs/MOVE9_LOWCODE_BOUNDARIES_SPEC.md A4.0
**Surface:** `runtimehost/schema-lifecycle`

Move 9 A4's own spec explicitly prohibited building A4 before this product question was settled:
"is prototype-on-H2 -> promote-to-Postgres a chosen product arc, or an artifact of the
*-inproc/*-postgres adapter pairs? If the answer is 'start on Postgres,' this task becomes a
documentation + default-config change." Asked the owner directly (AskUserQuestion, 2026-07-31).

Answer: **build the real promotion path.** Prototype-on-H2-then-promote-to-Postgres is a chosen
product arc NPDev should support as a first-class operator-driven command, not just steer new apps
to start on Postgres and treat B10 as closed by documentation alone.

This unblocks A4.1's scoped implementation: an operator-driven command (never automatic on boot),
dry-run first always (per-table source/target counts + type-mapping attention items, writes
nothing), schema realized on the target via the EXISTING lifecycle path (schema realization is
already engine-agnostic per the engine-variant corpus families), typed row copy per concept driven
by the compiled model's field types (never a generic SELECT *), a verification count per table that
refuses to report success on any mismatch, and A1's migration lock taken on the target for the
duration.

### REG-88 — B15: await-inside-forEach investigated for closure -- hard stop fired, boundary kept

**Type:** BOUNDARY · **Severity:** — · **Status:** DONE (2026-07-31)
**Source:** docs/MOVE9_LOWCODE_BOUNDARIES_SPEC.md A5
**Surface:** `kernel/flow-engine`

Move 9 A5's own spec named this "the genuinely hard one" and built in an explicit hard-stop
condition: "if per-iteration correlation cannot be made durable across a restart, do NOT ship it.
Report the finding and leave the boundary." Per the spec's own instruction ("read that
implementation before designing anything"), investigated the existing durable-flow correlation
mechanism (AWAIT_EVENT) and the existing resumable forEach loop (LIFT-LOOP-P2) before attempting
any design.

Findings (full detail also recorded in docs/ACCEPTED_BOUNDARIES.md B15 and docs/FLOWS.md §6):

- `FlowInstance` (NPDevKernel/kernel/src/main/java/com/npdev/kernel/execution/FlowInstance.java)
  hard-codes exactly ONE `correlationId` (String), ONE `currentStepIndex` (int), ONE
  `waitingForEventName` (String) per row -- one row = one correlation identity, one step position,
  one waited-event name. No list/array/composite-key structure exists anywhere in this record or in
  `npdev_flow_instance`'s columns (all singular, non-composite).
- `AwaitEventStep`'s wait descriptor lives at ONE fixed state key (`_npdev.await`,
  `FlowStateCodec.AWAIT_STATE_KEY`) inside ONE shared mutable state map per flow instance.
- `ForEachStep` (LIFT-LOOP-P2)'s existing resumability is a single "next iteration index" integer
  (`__forEachProgress.<stepName>`), checkpointed only at whole-iteration boundaries. Iterations run
  strictly sequentially and synchronously -- one full iteration's nested steps complete before the
  next starts. The nested-iteration executor passes `KernelRunner.NOOP_STEP_PROGRESS_RECORDER`:
  nothing INSIDE an iteration is independently checkpointed/resumable today, only whole-iteration
  boundaries are.
- `npdev_correlation_owner`'s PK is `correlation_id` alone -- one owned correlation id per row,
  globally, not scoped to a loop iteration.

Conclusion: the wall is structural, not incidental. Making N loop iterations independently,
out-of-order awaitable requires either (a) one `FlowInstance` row per iteration -- a new
sub-execution concept, since `resumeExecution`/`executeSteps`/compensation/idempotency all assume
one row = one flow -- or (b) turning `correlationId`/`currentStepIndex`/`waitingForEventName`/
`_npdev.await` into per-iteration collections simultaneously across `FlowInstance`,
`ResumeCoordinator`'s matching logic, the `npdev_flow_instance` DB schema, and the
`ForEachStep`/`AwaitEventStep` contract. This is a multi-week subsystem redesign, not a bounded task,
and attempting a half-measure (e.g. jamming a composite key into the single existing scalar fields
without a coordinated schema/matching-logic redesign) risks exactly the failure mode the hard-stop
exists to prevent: a resume matching the WRONG iteration's await, silently, which is worse than the
current validation-time rejection.

Per the spec's explicit prohibition ("do not remove FlowValidation's await-in-loop rejection before
the runtime supports it"), `FlowValidation.java`'s rejection (lines 470-472, `containsAwaitStep` at
513-525) was left completely untouched -- no code changed in the kernel or DSL validation for this
item. `docs/ACCEPTED_BOUNDARIES.md` B15 row kept (not deleted), rewritten to record this finding and
its exact revisit trigger (genuine demand to fund the full redesign). `docs/FLOWS.md`'s §6 also
updated: fixed a stale cross-reference (`SemanticValidator.java:2453-2455`, which moved to
`FlowValidation.java:513-525` during the earlier TREE1/DSL2 decomposition and was never corrected)
and added this investigation's finding inline.

### REG-89 — patchConcept's author-time 'id is required' rule was never relaxed for createIfMissing, so REG-77's shipped create-only runtime path was unreachable from any model

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found while authoring Move 10 W1.2 (docs/MOVE10_CONSOLE_PARITY_SPEC.md) -- WmsOffice's
GerarTemplateContagemProcedure needed to create the `Gerado` InventarioArquivo header row the
original inventario.html screen also created, with no id to look up (it is a brand-new record).
The obvious, documented declaration -- patchConcept + createIfMissing + set, no id -- was rejected
outright by the generator's semantic validation:
  Semantic validation failed:
   - Procedure GerarTemplateContagemProcedure step procedures[GerarTemplateContagemProcedure].steps[5]: id is required for patchConcept

**Surface:** `dsl/validation, kernel/procedure-runtime`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/PackValidation.java`
- `NPDevContract/dsl/src/test/java/com/npdev/dsl/v1/validation/ProcedurePatchConceptCreateIfMissingValidationTest.java`

`PackValidation.validateProcedurePatchConcept` was written in Move 4 (REG-75), when `patchConcept`
was patch-only and an `id` was genuinely always required. Move 5 Wave 1B then added
`createIfMissing` (REG-77's create half) with the *explicit* contract, stated in
`DefaultProcedureExecutor.patchConcept`'s own doc comment, that it:

  "tolerates a blank/unresolved idRef (nothing to look up yet) and, on a miss, builds a brand-new
   record from `set` alone with a freshly generated id -- deliberately NOT the (missing) lookup id,
   so a caller that queried for a match first (e.g. via a prior `listConcepts` step) and found none
   can still invoke this with a blank idRef."

The validator was never updated to match. The result: the single scenario the flag was built for --
create a record when the lookup found nothing -- could not be expressed, because the author-time
check demanded the very id the runtime was designed to do without.

**How it stayed invisible for two moves.** Every kernel test for `createIfMissing`
(`DefaultProcedureExecutorCreateIfMissingTest`, `DefaultProcedureExecutorAutoIdSemanticPolicyTest`)
builds `ProcedureStep` objects directly and never goes through `SemanticValidator` at all, so no
amount of runtime testing could catch it. The only *model-level* coverage,
`dsl-conformance-max`'s `EnsureWidgetOrderAuditProcedure`, silently encodes the workaround -- its
own fixture comment reads "id references a key nothing populates", i.e. a deliberately dangling
`$ref` declared solely to satisfy the validator and relied upon to resolve to null at runtime.
A corpus fixture that has to lie to reach a shipped feature is the tell; it was read as an
intentional test of the miss branch rather than as evidence of a blocked path.

**Fix**: one condition -- `if (!hasText(step.id()) && !step.createIfMissing())`. Nothing else was
weakened: a plain (non-upsert) `patchConcept` still requires `id`, a create still requires a
non-empty `set`, and REG-71's undeclared-field check still runs on the create path.

**RED/GREEN**: `ProcedurePatchConceptCreateIfMissingValidationTest` (5 cases). Against the
unfixed validator exactly 1 failed -- `createIfMissingWithNoIdIsAccepted`, at the asserted line --
while the 4 guard cases (id-present upsert, plain patch still needs id, create still needs set,
create still checks field names) passed, confirming the test isolates the bug rather than the
rule. With the fix, all 5 pass. Then proven end to end: the model that produced the error above
now generates, builds, boots, and the created InventarioArquivo row was confirmed at the data
layer via REST.

### REG-9 — LNCH-4: auth secrets management -- JWT key env-var delivery

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-07-21)
**Verification:** VERIFIED_LIVE
**Source:** LNCH-1 programme inheritance, rescoped 2026-07-21 (P0 priority)
**Surface:** `runtimehost/auth`

Original claim was that DB credentials, runtime API keys, JWT keys, and the super-user key all
lacked an env-var deployment path. Independent verification found the gap narrower: DB credentials
and runtime API keys already worked (Spring's built-in relaxed binding, already emitted into the
Docker Compose template). The real, confirmed-open gap was JWT keys: LoginController/
JwtBearerAuthFilter read key paths via @Value with no default and no env-var wiring, and
DockerDeploymentEmitter emitted zero NPDEV_AUTH_JWT_* entries -- a missing key failed with a raw
Spring bean-creation error, not a docs-linked one. Fix: NPDEV_AUTH_JWT_PUBLICKEYPATH/
PRIVATEKEYPATH emitted into compose + .env.example; StartupValidator fail-fasts with a docs-linked
message on an unreadable key; LoginController supports verify-only deployments (blank private key
boots, login returns 503) instead of crashing the context. Super-user key env-seeding defaulted to
WONTFIX (issued-not-supplied model preserved, reversible). Verified: 12/12 StartupValidator unit
tests + 8/8 verify-only JwtAuthExternalBetaIT live on real Postgres.

*Full historical narrative:* `docs/NPDEV_OPEN_ITEMS_REGISTER.md#reg-9`

### REG-90 — Rebuild-And-Restage.ps1 accepted -BuildRoot but never passed it to Build-NpdevApp.ps1 -- the wrapper generated one app and then gated a different one

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found while rebuilding WmsOffice for Move 10 W1 (docs/MOVE10_CONSOLE_PARITY_SPEC.md) into the
build root the running instance already occupied
(D:\WorkSpace\NPDev\Build\generated-finalapps-alt) rather than the script default, to reuse a warm
Gradle cache instead of paying for a cold full build.

**Surface:** `scripts/appgen, build-tooling`
**Files:**
- `scripts/appgen/Rebuild-And-Restage.ps1`

`Rebuild-And-Restage.ps1` declares `-BuildRoot` (default
`D:\WorkSpace\NPDev\Build\generated-finalapps`) and uses it in step 4 to locate the app's `_ops`
directory for `Build-App.ps1` / `Start-App.ps1` / `Check-Provenance.ps1`. But step 3's splat --
`@{ AppFolder; RuntimeHostLibsDir; SkipRuntimeHostLibs }` -- omitted it, so `Build-NpdevApp.ps1`
silently fell back to its OWN identically-named default.

With the default value the two halves coincide and nothing looks wrong. Pass any other value and
they diverge: step 3 generates and assembles the app under `generated-finalapps\<app>`, then step
4 builds, starts, and runs the provenance gate against `<BuildRoot>\<app>` -- a different,
possibly stale, possibly nonexistent app. The failure is silent in the worst direction: if the
target root happens to hold an older build, the wrapper reports success having verified the wrong
jar.

This is precisely the stale-build-root class of failure the wrapper exists to prevent -- its own
header promises it "threads ONE shared -RuntimeHostLibsDir through steps 1 and 3 so the sync
writes to, and the build reads from, the same directory -- the single most common cause of 'my
change had no effect'." The same discipline was simply never applied to `-BuildRoot`, the other
path parameter it accepts. `knowledge/cards/runtimehost-libs-dir-mismatch.json` names the libs-dir
instance of this trap; this is the build-root twin of it, inside the fix itself.

**Fix**: add `BuildRoot = $BuildRoot` to step 3's argument splat, and echo it in the step banner
alongside the libs dir so a divergence would be visible in the log next time.

**Evidence, stated precisely.** The fixed behaviour is verified live: with the fix,
`-BuildRoot D:\WorkSpace\NPDev\Build\generated-finalapps-alt` logged
`Out root : D:\WorkSpace\NPDev\Build\generated-finalapps-alt\wmsoffice` from step 3 and step 4
then built/started/gated that same tree, end to end. The BROKEN behaviour is established by
reading the pre-fix splat, not by a captured failing run -- the fix was applied before the first
invocation, so no unfixed run with a non-default `-BuildRoot` was ever recorded. The pre-fix code
is unambiguous (`-BuildRoot` appears in the `param()` block and at step 4 only, never in step 3's
`$buildArgs`), but this is a read-derived diagnosis on the failing side, not a live RED.

### REG-91 — MigrationClaimStore swallows every SQLException from its canonical-row seed as 'row already exists', then reads an unchecked empty ResultSet -- a claim table whose columns are NOT NULL makes the app permanently unbootable with the opaque message 'No data is available'

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found live during Move 10 W1 (docs/MOVE10_CONSOLE_PARITY_SPEC.md) while rebooting WmsOffice after
a model-only change. The app had booted cleanly from the same database an hour earlier; the
regenerated jar then refused to start, every boot, with:

  Caused by: java.lang.IllegalStateException: Failed to claim the migration lock
    at com.finalexec.db.MigrationClaimStore.claimH2(MigrationClaimStore.java:211)
  Caused by: org.h2.jdbc.JdbcSQLNonTransientException: No data is available [2000-224]
    at org.h2.jdbc.JdbcResultSet.getString(JdbcResultSet.java:283)
    at com.finalexec.db.MigrationClaimStore.claimH2(MigrationClaimStore.java:196)

Nothing in that message names the actual problem, points at a table, or suggests a remedy.

**Surface:** `runtimehost/db, schema-lifecycle`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/MigrationClaimStore.java`

Three defects compose into an unbootable app with an undiagnosable error.

1. **`ensureCanonicalRow` swallows ALL SQLExceptions**, not just duplicate-key violations. Its
   comment says the catch exists for "a concurrent bootstrap race, or on every non-first boot --
   the row already exists". Any OTHER insert failure is silently treated as success.

2. **`claimH2` ignores `resultSet.next()`'s return value** and calls `getString(1)`
   unconditionally, so an empty result set surfaces as H2's generic "No data is available"
   rather than "the canonical claim row is missing".

3. Together: if the seed insert fails for a reason that is NOT a duplicate, the table stays
   empty, `SELECT ... FOR UPDATE` returns no rows, and the boot dies pointing at the wrong thing.

**The live trigger.** In this database `npdev_schema_migration_claim` exists with
`claim_key`/`instance_id`/`claimed_at_utc` all `NOT NULL` and typed `character varying` -- NOT the
shape `ensureTable`'s own DDL declares (`claim_key TEXT PRIMARY KEY, instance_id TEXT, hostname
TEXT, claimed_at_utc BIGINT`, three of four nullable). `ensureCanonicalRow` seeds an UNHELD row as
`VALUES (?, NULL, NULL, NULL)`, which that table rejects:

  org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: NULL not allowed for column "instance_id"

Confirmed by running that exact INSERT by hand against the live database. `CREATE TABLE IF NOT
EXISTS` is a no-op against the pre-existing table, so the shape never self-heals.

**How the table came to have that shape is a SECOND, unresolved question** and the reason this
item is filed OPEN rather than fixed. `MigrationClaimStore` is the only code in the repo that
names the table, and its DDL cannot produce those constraints -- so something else rewrote it.
The most likely candidate is a prior destructive schema-lifecycle pass over this database (this
app survived exactly such a migration on 2026-07-28, REG-58/REG-59), i.e. the schema engine may be
treating a platform bookkeeping table as an NPDev-owned business table and recreating it with
NPDev's own all-columns-NOT-NULL conventions. If so, ANY app that undergoes a destructive
migration becomes unbootable afterwards -- which would make this considerably more severe than one
bad local database. That hypothesis is NOT yet verified; verifying it means reproducing a
destructive migration on a scratch database and re-inspecting the table, which was out of scope
for the move that found this.

**Workaround used to unblock** (not a fix): seed the row by hand with empty strings instead of
NULLs, which satisfies the NOT NULL constraints and still reads as unheld to `claimH2`'s own
`heldBy != null && !heldBy.isBlank()` check:

  INSERT INTO npdev_schema_migration_claim (claim_key, instance_id, hostname, claimed_at_utc)
  VALUES ('schema-migration', '', '', 0);

App booted immediately afterwards. `POST /api/admin/schema-migration/clear-claim` (the documented
remedy for a crashed holder) does not help here -- there is no holder to clear, the row is absent.

**Fix shape when taken up**: (a) narrow the catch in `ensureCanonicalRow` to duplicate-key /
integrity violations and rethrow anything else; (b) check `resultSet.next()` in `claimH2` and fail
with a message naming the table and the missing canonical row; (c) settle the second question
above -- whether the schema engine rewrites platform bookkeeping tables -- and, if it does, exclude
them from its scope.

---

## CLOSED, Move 11 W1 (2026-07-31)

**The second question is ANSWERED, and the hypothesis in it was WRONG.** The schema engine does not
touch this table -- `ownedTablesJson` derives ownership from the manifest's `businessTableColumns`
plus previously-recorded ownership, and `npdev_schema_migration_claim` appears in neither; nothing
in `db/schema-realization/*.sql` names it either. The strict shape is **this class's own earlier
DDL**. Commit `2404605` (REG-7.3 P3) declared:

    claim_key TEXT PRIMARY KEY, instance_id TEXT NOT NULL, hostname TEXT, claimed_at_utc BIGINT NOT NULL

correct at the time, because the row was INSERTed per claim and DELETEd on release, so an unheld
slot was an absent row. Move 9 A1 made the row persist with a blanked holder and relaxed the DDL to
all-nullable -- but `CREATE TABLE IF NOT EXISTS` is a no-op against an existing table.

Verified against the live WmsOffice database over its H2 TCP server
(`jdbc:h2:tcp://localhost:9200/...npdev_wmsoffice`):

    claim_key       character varying  nullable=NO
    instance_id     character varying  nullable=NO
    hostname        character varying  nullable=YES
    claimed_at_utc  bigint             nullable=NO

-- the `2404605` DDL exactly (the original report's "claimed_at_utc typed character varying" was
imprecise; it is `bigint`, and `hostname` is the one nullable column, which is the DDL's signature).

**So this is not one bad local database: it is an upgrade-path regression that hits EVERY database
that ever booted a pre-A1 build.** That is more severe than the original hypothesis, not less, and
it needs no separate ledger item -- there is no second bug to file.

**What shipped**

1. `ensureCanonicalRow`'s catch narrowed to SQLState/error-code `23505` only, checked on the whole
   `getNextException` chain, never on message text. Everything else is rethrown with the statement,
   the bound values, the SQLState and the driver's own message attached. Deliberately NARROWER than
   the spec's suggested "23505/23506 family": H2's `23506` is referential-integrity-parent-missing
   and `23502` is NULL-not-allowed -- neither means "the row is already there", and admitting them
   would leave exactly this hole open.
2. `claimH2` now checks `resultSet.next()` and raises a message naming the table, the claim key, the
   likely cause and the recovery INSERT. `claimPostgres` had the identical unchecked-`next()` shape
   on `pg_try_advisory_lock` and was fixed too.
3. The unheld holder is written as non-null sentinels (`''`/`''`/`0`) instead of `NULL`, in
   `ensureCanonicalRow`, `release` AND `clear` -- all three wrote `NULL`. `release`'s failure was
   swallowed into a log line (claim held forever, every later boot refused as a collision) and
   `clear` -- the documented operator escape hatch -- threw outright. `current()` already treated a
   blank holder as unheld, so both shapes read identically; a `NULL` holder left by a pre-fix
   release still reads as unheld (asserted).
4. The table shape is deliberately NOT rewritten. Tolerating the old shape needs no DDL against a
   database whose migration lock is by definition not yet held; an `ALTER ... DROP NOT NULL` sweep
   would be schema surgery performed before the lock that exists to serialize schema surgery.

**RED/GREEN.** `MigrationClaimStoreLegacyTableShapeTest` (6 cases) recreates the verbatim `2404605`
DDL. Against the pre-fix code, 4 of 6 fail and every one of them reproduces this item's own live
stack trace exactly -- `IllegalStateException: Failed to claim the migration lock` caused by
`org.h2.jdbc.JdbcSQLNonTransientException: No data is available [2000-224]`. The 2 controls (the
current all-nullable shape; a NULL holder left by a pre-fix release) pass before and after. With the
fix, 6/6 pass. Move 9 A1's concurrency evidence (`SchemaLifecycleExecutorMigrationClaimTest`, 6
cases incl. "an existing claim refuses the boot" and "a refusal still releases its own claim")
re-run unchanged: 6/6.

Evidence: `NPDev_General__OutsideRepo/move11/move11-w1-claimstore.txt`.

### REG-92 — REG-76 was fixed for workbench inputFields but never mirrored to panel inputFields -- a declared Panel action still renders <input type=text>, which silently collapses newlines, so no panel action can take multi-line input

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found in Move 10 W1.2 (docs/MOVE10_CONSOLE_PARITY_SPEC.md) while establishing whether
`inventario.html`'s third wizard (Recebimento por Arquivo) could be expressed as a declared Panel
action -- its very first step is "give the server a multi-line CSV".

Proven live in a real headless browser against the running WmsOffice app, by typing a genuinely
3-line value into `InventarioHistoricoPanel`'s own `entidadeId` inputField and reading the DOM
back:

  {"tagName":"INPUT","type":"text","value":"LINHA-1 LINHA-2 LINHA-3","newlines":0,"length":23}

Three lines in, one line out, zero newlines -- collapsed to spaces on assignment.

**Surface:** `generator/business-ui, panel-runtime`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/business-ui-app.mustache`

REG-76 (Move 3 G4) found that the AGGREGATE WORKBENCH's `inputFields` mini-form rendered a plain
`<input type="text">`, which silently strips embedded newlines, so a pasted multi-line CSV arrived
as one line and the parser's own header-row skip consumed the entire payload. It was fixed there
by rendering a `<textarea>` -- `workbench-page.html.mustache` still carries the full explanation
above that line.

`panelAction.inputFields` (Move 2 G3) is the *same mechanism on the Panel side*, added earlier and
never revisited. `business-ui-app.mustache` still does:

    const input = document.createElement("input");
    input.type = "text";
    input.placeholder = field;

Confirmed in the DEPLOYED bundle, not only the template:
`<app>/build/resources/main/static/npdev-business-ui/app.js` around the
`declared-panel-action-inputs` block.

**Consequence.** Any declared Panel action whose input is inherently multi-line -- a pasted CSV, an
XML document, a free-text note -- is silently broken: the value is accepted, visibly "typed", and
posted mangled. Silent corruption, not an error. It also means the Panel and the Workbench, two
surfaces with an explicitly mirrored `inputFields` declaration, behave differently for the same
model text, which no author would predict from the model.

**What it blocked here.** It is the FIRST of several blockers on expressing `inventario.html`'s
Recebimento por Arquivo wizard as a Panel action (see docs/MOVE10_W1_CHECKLISTS.md for the full
list -- the others are the preview table having no persisted-field home, and no mechanism to carry
parsed state from a preview action to a confirm action). Fixing this alone does not unblock that
wizard; it is filed on its own because it is a real, general, silent-corruption bug independent of
that console.

**Fix shape**: mirror the workbench's line exactly -- render a `<textarea rows="1">` with the same
inline styling, which preserves newlines and degrades fine for short single-line values. Then add
a `dsl-conformance-max` example with a multi-line panel-action input so the two surfaces cannot
drift apart again. Not done here: it needs a generator rebuild plus a full app rebuild to
re-verify, and Move 10's stop line is the end of Wave 1.

---

## CLOSED, Move 11 W3 (code) + Wave -1.1 (live proof), 2026-07-31

**Status resolved at Wave -1.2**, and the discrepancy is worth recording: Move 11 W3's own report
claimed "REG-92 -> DONE" while this file still read `status: OPEN`, because the code fix landed and
the ledger edit did not. The ledger is authoritative (CLAUDE.md). The status was NOT flipped when
the discrepancy was found -- it was flipped only after the DoD's own live-browser condition was
actually met, below.

**The fix.** One shared control, `createDeclaredPanelTextInput(field)`, rendering
`<textarea rows="1">` -- mirroring the Aggregate Workbench's rule EXACTLY, which is unconditional
(workbench-page.html.mustache has rendered a textarea here for every field since REG-76, with no
widget/type condition). Used by BOTH Panel mini-forms: the action `inputFields` form REG-92 names,
and `renderDeclaredPanelAddRowForm` ten lines above it, which had the identical
`input.type = "text"` and therefore the identical silent corruption. Fixing only the twin REG-92
happens to name would have repeated REG-76's own mistake. Inline styling hoisted to
`textarea.declared-panel-edit-input`.

**RED/GREEN.** `BusinessUiEmitterPanelInputNewlineTest` asserts the property on BOTH surfaces,
reading the RENDERED templates rather than the sources. With the Panel call sites reverted to
`input.type = "text"` it fails naming the exact mini-form; restored, 2/2 pass. (Its Workbench half
failed first on REG-76's own explanatory comment, which quotes the markup being asserted absent --
the assertion now matches on code only.)

**VERIFIED LIVE**, in a real headless browser against the rebuilt WmsOffice app, typing a genuinely
3-line value into `InventarioHistoricoPanel`'s `entidadeId` inputField and reading the DOM back:

    {"tagName":"TEXTAREA","type":"textarea","value":"LINHA-1\nLINHA-2\nLINHA-3","newlines":2,"length":23}

Against this item's own original reading:

    {"tagName":"INPUT","type":"text","value":"LINHA-1 LINHA-2 LINHA-3","newlines":0,"length":23}

Three lines in, three lines out. Evidence:
NPDev_General__OutsideRepo/move11/browser/move11-w3-w5-w6.json.

**What this does NOT unblock**, restated so it is not misread: `inventario.html`'s Recebimento por
Arquivo wizard had THREE blockers and this closes one. The other two (preview columns declared on
no concept; nothing carries parsed state from a preview action to a confirm action) are untouched.

### REG-93 — The panel-provenance impact gate failed on manifests whose screen had been DELETED -- it has been RED since Move 8's crossdocking deletion, and structurally contradicted the bytes-deleted metric it is supposed to coexist with

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found in Move 10 W1 (docs/MOVE10_CONSOLE_PARITY_SPEC.md) while establishing a clean baseline
before deleting a second console. Running the gate against the live WmsOffice bundle, with NO
changes of this move's own in play:

  Panel provenance impact check

  FAIL: 2 confirmed manifest(s) reference model elements that no longer exist:
    - crossdocking.panel.json: references invocation 'flow:CancelarCrossDocking', which no longer exists
    - crossdocking.panel.json: references invocation 'flow:ConcluirCrossDocking', which no longer exists

  Either regenerate the screen against the current bundle, or update the model.

**Surface:** `scripts/quality, provenance-gate`
**Files:**
- `scripts/quality/check-panel-provenance-impact.py`

`check-panel-provenance-impact.py` walks every `*.panel.json` and fails the build when a
CONFIRMED manifest names a field or invocation the model no longer has. Its purpose, stated in
`docs/REMEDIATION_PLAN.md` R-G1, is that "a field rename goes through a rebuild; that is precisely
the moment the check has to fire" -- i.e. protect a LIVE hand-written screen from silently
breaking.

It never asked whether the screen still exists.

**Why that is not a nitpick: it directly contradicts the metric.** Move 3 §6 replaced the
hand-written/model ratio with "bytes of `web/*.html` deleted after a console reaches full
behavioural parity". Deleting a console is the SUCCESS condition. And per the crossdocking "two
paths, one incomplete" finding (docs/SCREEN_TAXONOMY.md), the deletion must ALSO remove the flows
that console was the only caller of -- otherwise a future author binds a panel action to an
incomplete duplicate and silently loses behaviour. So every successful conversion necessarily
leaves a manifest naming now-deleted flows, and therefore necessarily turned this gate red. The
gate punished the metric for succeeding.

**It had already fired, and been left red.** Move 8 Part A deleted `crossdocking.html` plus
`ConcluirCrossDocking`/`CancelarCrossDocking` on 2026-07-30 and did not touch
`crossdocking.panel.json`. The gate has failed on every run since -- roughly a day and three
moves. It went unnoticed because `Rebuild-And-Restage.ps1` runs it as step 4, AFTER building and
starting the app, and the intervening rebuilds either did not reach step 4 or were not run.

**Fix**: treat a manifest whose `screen` file no longer exists as RETIRED -- reported as an
advisory warning, never a blocking failure. The manifest is deliberately kept rather than deleted:
it is the provenance record of what the frozen `*.original.html` reference touched. Everything
else is unchanged; a manifest whose screen IS still on disk keeps the full-strength rule.

**RED/GREEN**: the script's own `--calibrate` self-test gained a third control ("stale confirmed
manifest, screen DELETED (retired)"), and the two pre-existing controls were tightened -- they
previously never created the screen file at all, so before this change every calibration case was
accidentally exercising the retired path without anyone noticing. With the fix, all 3 pass. With
the one-line `and not retired` reverted in a scratch copy, exactly the new control fails
("[FAIL] stale confirmed manifest, screen DELETED (retired) (fired)") and the other two still
pass, confirming the control isolates this bug and does not weaken the real protection.

**Verified live**: after the fix, the gate run against the live WmsOffice bundle that produced the
FAIL above reports the crossdocking entries as `[warn] ... (retired -- screen deleted, nothing
left to break)` and exits 0.

### REG-94 — run-ai-knowledge-gate.ps1 has been RED on two independent steps and nobody noticed -- 11 untriaged security-pattern hits from the Moves 9-10 schema-engine work, and a script-inventory scan that walked into node_modules and failed on vendored third-party .js files

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found in Move 11 W2 (docs/MOVE11_CLOSE_REMAINING_SPEC.md O4) while hosting the two orphaned
checkers -- i.e. found by running the gate that O4 exists to make runnable, on the first run after
wiring it up. Neither failure is caused by Move 11's own changes; both were measured against the
pre-Move-11 tree.

Step [6/21], untriaged security-pattern hits:

    -- 410 hit(s), 399 already cleared, 11 needing triage
       conditional-guard-no-else:        14 total, 0 new
       read-without-tenant-predicate:    83 total, 3 new
       sql-string-building:             139 total, 8 new
       swallowed-security-exception:     80 total, 0 new
       unbounded-caller-input:           94 total, 0 new

Step [19/19] (now [21/21]), script inventory:

    - Script with no valid invocation declaration:
      scripts/quality/json-schema-validator/node_modules/fast-uri/test/rfc-3986.test.js
      (no invocation declaration in scripts/policy/script-invocation-declarations.json)
    ... and ~40 more, all under node_modules/

**Surface:** `scripts/quality, schema-engine`
**Files:**
- `scripts/quality/security-pattern-sweep-allowlist.json`
- `scripts/quality/run-script-inventory-check.ps1`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/CrossEngineDataPromotion.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/BackfillPass.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/DestructiveRecreationPass.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/ExpressionBackfillPreview.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/JdbcBusinessConceptStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaDropSnapshotRestorer.java`

Two unrelated causes, filed together because they share one consequence and one lesson.

**HALF A -- 11 untriaged security-pattern hits. Deliberately NOT triaged in Move 11 W2 (as filed); triaged in Wave 0.1, see the closure section below.**

The sweep's blocking step demands that every new hit be triaged as (i) a genuine finding, (ii)
safe-with-a-reason plus an allowlist fingerprint, or (iii) handed to the session that owns that
surface. The 11 are in the schema-engine surface built across Moves 9-10:

  CrossEngineDataPromotion.java:147,148,235   (Move 9 A4, H2->Postgres data promotion)
  BackfillPass.java:350,433
  DestructiveRecreationPass.java:225
  ExpressionBackfillPreview.java:88
  JdbcBusinessConceptStore.java:98,110
  SchemaDropSnapshotRestorer.java:204,281

Almost all are the `safeTable`/`columnList`/`placeholders` identifier-splicing shape that is
already cleared 399 times elsewhere on the reasoning that identifiers go through
`SchemaLifecycleExecutor.safeIdentifier()`. That similarity is exactly why they must NOT be
batch-cleared from a plan that did not build them: the sweep's own rule is that a false "safe" is
worse than a noisy hit, and the only value in this instrument is that each fingerprint means
somebody actually traced that identifier to its source. Move 11 did not build this surface and has
not traced them, so clearing them here would be the exact failure the instrument exists to prevent.
This is option (iii): handed to whoever owns the schema-engine surface.

Measured, not assumed: reverting only Move 11's own `MigrationClaimStore.java` change takes the
count 12 -> 11, so Move 11 contributed exactly one hit, and that one was a false positive (a
diagnostic string literal beginning with the word SELECT). It was fixed by rewording the message
rather than by adding an allowlist entry -- the count is back to the pre-Move-11 baseline of 11.

**HALF B -- the script-inventory scan walked into node_modules. FIXED in Move 11 W2.**

`run-script-inventory-check.ps1` excluded `.git/.gradle/build/node_modules/...` when scanning
*.md files for `manual-runbook` evidence, but applied NO directory exclusion at all when
enumerating the scripts themselves. So after anyone ran `npm ci` in
`scripts/quality/json-schema-validator/`, every vendored third-party `.js` file -- fast-uri's test
suite, json-schema-traverse, require-from-string -- became "a script under scripts/ with no
invocation declaration", and the gate failed. `node_modules` is gitignored and untracked, so a
fresh CI checkout has none and passes.

**That is the sharper half of this finding: a gate whose verdict depends on whether someone
installed dependencies.** Green in CI, red locally, for a reason having nothing to do with the
code under test -- which trains everyone to disregard its output, and is how the OTHER half
(11 real untriaged hits) went unnoticed. Fixed by applying the same exclusion list to both scans.

**Why one item.** Both were found by the same act -- running the whole gate once, honestly, end to
end -- and both had been red for some time with nothing reporting it, which is O4's thesis
(docs/MOVE11_CLOSE_REMAINING_SPEC.md) restated in a different artifact: a check that runs but whose
RED nobody reads is worth about as much as a check nothing runs. Half B was closed on filing; Half A
is closed below.

---

## CLOSED, Wave 0.1 (2026-07-31, MASTER_AI_PLATFORM_PROGRAMME_v2.md)

**Half A -- the 11 untriaged hits: TRIAGED, not batch-cleared.** Every verdict was traced to the
identifier's source rather than inferred from an already-cleared sibling. Seven `sql-string-building`
hits route every spliced identifier through `SchemaLifecycleExecutor.safeIdentifier()`, which
REFUSES (throws on) anything outside `[A-Za-z_][A-Za-z0-9_]*`; `CrossEngineDataPromotion` carries a
second guard (its column list is intersected with the live columns of both databases first). One of
the seven is a plain false positive -- `JdbcBusinessConceptStore.java:110` is a catch block's
English message, and the UPDATE pattern matched the words "(for update)". The three
`read-without-tenant-predicate` hits are genuinely tenant-independent, and for two of them a tenant
predicate would BE the bug: whole-database promotion and drop-snapshot restore would each silently
discard every other tenant's data. Rationale recorded in
docs/SECURITY_PATTERN_SWEEP_2026-07.md §2.4, per the allowlist's own rule.

    $ python scripts\quality\security-pattern-sweep.py --fail-on-new
    -- 410 hit(s), 410 already cleared, 0 needing triage
       conditional-guard-no-else: 14 total, 0 new
       read-without-tenant-predicate: 83 total, 0 new
       sql-string-building: 139 total, 0 new
       swallowed-security-exception: 80 total, 0 new
       unbounded-caller-input: 94 total, 0 new
    EXIT=0

**Half B** was already fixed when this item was filed (the inventory scan's missing directory
exclusions).

**The triage paid for itself with a finding the sweep could not see.** Tracing why
`JdbcBusinessConceptStore`'s spliced `shape.tableName()` is safe showed that
`SqlIdentifierSupport.toSnake()` sanitizes by REPLACEMENT rather than refusal -- which means two
differently-named concepts can compile to the same physical table. Confirmed live: `OrderLine` and
`Order Line` both become `order_lines`, with 0 validation errors. Filed as **REG-98** (HIGH).

### REG-95 — The Aggregate Workbench's derived-field expression subset split its path on '.' before matching, so every filter(...) form -- including the subset's OWN documented example -- silently evaluated to 0; M6's balanced-quantity banner had been declared and shipping a wrong number since Move 6

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found in Move 11 W5.1 (MOVE11_CLOSE_REMAINING_SPEC.md), which said to "try to express M6 with
transactionDerivedField first" before building anything. Trying it turned up something better than
an authoring gap: M6 was ALREADY AUTHORED. WmsOffice's Movimento autoPanel has declared

    "derivedFields": {
      "origemTotal":  { "tier": "client", "expression": "sum(itens[].posicoes[].filter(papel=='Origem').quantidade)" },
      "destinoTotal": { "tier": "client", "expression": "sum(itens[].posicoes[].filter(papel=='Destino').quantidade)" }
    }

since the derivedFields mechanism shipped -- and the banner has been rendering 0 for both, with no
error, for as long as it has existed.

**Surface:** `generator/workbench, aggregate-workbench`
**Files:**
- `NPDevGenerator/generator/src/main/resources/npdev-templates/workbench-page.html.mustache`

`evaluateDerived` (Move 5 Wave 2B, written for M6 by name) does:

    var segments = m[1].split(".");
    ...
    var filterMatch = /<the whole "<name>[].filter(<field>=='<literal>')" in ONE segment>/.exec(seg);

The filter regex requires the WHOLE `<name>[].filter(<field>=='<literal>')` in one segment. But
`split(".")` has already cut it in half at the dot between `posicoes[]` and `filter(...)`. So
`filter(papel=='Origem')` arrives alone, matches neither the filter branch nor the array branch,
falls through to the plain-path branch, resolves to `undefined` for every candidate, and the sum
comes out **0**. Silently -- a display-only banner has no error channel.

This is not an exotic input: it is the function's own second documented example,
`sum(posicoes[].filter(papel=='Origem').quantidade)`, three lines above the bug.

**RED, measured** -- the shipped `evaluateDerived` copied verbatim and run under node against an
M6-shaped draft (2 items; 15 Origem, 13 Destino, 15 item total):

    15  <- sum(itens[].quantidade)                                 (expected 15)  OK
    28  <- sum(itens[].posicoes[].quantidade)                      (expected 28)  OK
     0  <- sum(itens[].posicoes[].filter(papel=='Origem').quantidade)   (expected 15)
     0  <- sum(itens[].posicoes[].filter(papel=='Destino').quantidade)  (expected 13)
     0  <- sum(posicoes[].filter(papel=='Origem').quantidade), item as root (expected 10)

Note which lines pass: the non-filter forms are fine, and summing across TWO nesting levels is
fine. The subset was never short of the grammar M6 needs -- Move 3's "cannot-express" verdict for
M6 named the wrong cause. Only the filter half was broken, and only in tokenization.

**Fix**: tokenize instead of splitting. A `DERIVED_SEGMENT` regex matches whole segments
(`<name>[].filter(<field>=='<literal>')` | `<name>[]` | `<name>`), and `derivedSegments()` walks
the matches, requiring that only a `.` separate them and that the whole path be consumed --
returning `null` (renders "—") for anything else, rather than guessing, since guessing is how a
wrong number reaches a banner unannounced. Every evaluation branch is unchanged.

**GREEN, same probe, same draft**:

    15  <- sum(itens[].posicoes[].filter(papel=='Origem').quantidade)
    13  <- sum(itens[].posicoes[].filter(papel=='Destino').quantidade)
    10  <- sum(posicoes[].filter(papel=='Origem').quantidade), item as root

**Consequence for the conversion metric.** M6 was `movimentacao-livre.html`'s ONLY
`cannot-express` item (docs/MOVE3_G2_CHECKLISTS.md: M1/M2/M7/M11 are `differs`, M3/M4/M5/M8/M9/M10
are `works`). With it closed, that console reaches 0 `cannot-express`.

A third derived field, `itensTotal` (`sum(itens[].quantidade)`), was added to the model in the same
change: M6's actual behaviour is a THREE-way comparison (Origem total == Destino total == item
quantidade) and only two of the three were declared.

### REG-96 — A procedure's condition/if step can only test a reference's TRUTHINESS -- there is no equality or comparison primitive -- so no aggregate onCommit hook can be guarded on 'the record reached state X', and a lifecycle-transition side effect cannot be expressed declaratively

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found in Move 11 W5.2 (MOVE11_CLOSE_REMAINING_SPEC.md) while establishing whether
`autoPanel.transaction.hooks` already covers M11 (docs/MOVE3_G2_CHECKLISTS.md) -- the difference
between the Aggregate Workbench's generic lifecycle-transition button and the console's
`ConfirmarMovimentacao` flow, which also emits `MovimentoConfirmado`.

Established by reading the runtime, not assumed. `DefaultProcedureExecutor.ifThenElse`:

    boolean condition = truthy(resolve(state, step.conditionRef()));

`conditionRef` is a REFERENCE, resolved and tested for truthiness. `$input.situacao` is truthy for
every one of Movimento's four states, so "only when situacao is Concluido" cannot be written.

**Surface:** `kernel/procedures, dsl`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/procedures/DefaultProcedureExecutor.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/PackValidation.java`

**What this blocks, concretely.** `aggregate.onCommit` runs a procedure inside the commit
transaction, and a procedure CAN `eventPublish`. So the mechanism for "emit MovimentoConfirmado as
part of the commit that concludes the movement" exists and would be strictly better than the flow's
version (atomic with the write rather than after it). What does not exist is the guard: an
unconditional emission would fire `MovimentoConfirmado` on every commit of every Movimento,
including Pendente ones, which is plainly wrong. The step that would gate it can only ask "is this
reference truthy", never "does it equal 'Concluido'".

Note this is narrower than "procedures cannot compare": `computeValue` has add/subtract (REG-78),
and `patchConcept`/`mapList` resolve `$ref`s. It is specifically the branch predicate that is
truthiness-only.

**Why M11 is nevertheless NOT a blocker for movimentacao-livre's conversion.** The original
console's Confirmar IS reproduced by a declared surface -- `MovimentoLivrePanel`'s row-scoped
`confirmarMovimentacao` action, `"binding": "flow", "flow": "ConfirmarMovimentacao"`,
`visibleWhen: tipo == 'MovtoLivre' && situacao == 'Pendente'` -- which invokes the real flow and
therefore does emit the event. That was verified live in Move 2 G4 (`status: OK`). M11's `differs`
verdict is about the Aggregate WORKBENCH offering a SECOND, generic transition path that writes the
field without the event; it was never that the behaviour is unreachable. So M11 stays `differs`,
as Move 3 recorded it, and it is not a `cannot-express`.

**Workarounds available today, both rejected here**:
  (a) push the comparison into app-owned capability Java and call it from the procedure -- this is
      the "rewriting the console's orchestration into capability Java" shape that
      MOVE11_CLOSE_REMAINING_SPEC.md Part 2 names as the thing to avoid, and it would put a string
      equality test in a WMS allocation capability;
  (b) emit unconditionally from onCommit and let consumers filter -- moves the bug downstream and
      makes the event mean something different from its own name.

**Fix shape when taken up**: give the `condition`/`if` step the same narrow, closed predicate
grammar `visibleWhen` already carries -- `$ref == '<literal>'` / `!=` -- rather than a general
expression language. That grammar already exists, is already validated, is already implemented
twice on the client side (`evaluateVisibleWhen` in workbench-page.html.mustache and the
`AutoPanelExpander` predicate), and extending the resolvable roots of an existing grammar rather
than inventing a second dialect is the standing convention (see Move 11 W6 for the same call).

---

## CLOSED, Wave 0.6 (2026-07-31, MASTER_AI_PLATFORM_PROGRAMME_v2.md)

Fixed exactly as the shape above named: the `condition`/`if` step now carries the SAME closed
grammar `visibleWhen` already uses, rather than a new dialect or a general expression language.

    condition := ref | ref op literal
    op        := == | != | >= | <= | > | <
    literal   := 'text' | number | true | false | ref

`ProcedureConditionEvaluator` (kernel), called from `DefaultProcedureExecutor.ifThenElse`.

**A bare ref keeps its old truthiness meaning exactly**, so this is additive: no model changes
behaviour, and no `npdev migrate` codemod is owed. Operators are matched longest-first (`>=` is
never read as `>`) and quoted text is skipped, both being the mistakes LC-P0 had just finished
removing from the query engine one file over.

**A malformed condition is REFUSED** (`CONDITION_UNSUPPORTED`), not silently taken as the
else-branch -- X0's rule applied to the branch predicate itself. An ordered comparison against an
ABSENT operand is false rather than an error, which is a state answer, not a parse failure (the
same judgement the query engine makes for a missing field).

**Both sides may be refs.** `$a == $b` is the comparison `SyncOcupacaoProcedure`'s own description
records having had to push into Java ("procedures' own 'if' step has no comparison-expression
grammar ... so the equality test itself has to happen in Java"). That constraint is now lifted for
future procedures; SyncOcupacaoProcedure itself is left alone, since rewriting a working procedure
is not this wave's job.

**RED/GREEN.** `ProcedureConditionComparisonTest`, 7 cases. The RED half is kept as a live test
rather than a memory: `bareRefIsStillTruthinessOnly_theShapeThatForcedJava` asserts that all four
of Movimento's lifecycle states are truthy under a bare ref -- which is *why* the guard could not
be written -- while `equalityAgainstALifecycleStateNowDecidesTheBranch` shows the same predicate
now selecting correctly. Full kernel suite green.

**What this unblocks, and what it does not.** LC-B1's `having` no longer has to inherit a
truthiness-only predicate (the programme's F7). M11's guarded `onCommit` emission is now
expressible -- but it is NOT authored here: doing so would change WmsOffice's event behaviour, and
M11 is a `differs`, not a blocker (see REG-95's closure and docs/SCREEN_TAXONOMY.md footnote 9).

### REG-97 — CompiledModelCanonicalJson is not idempotent under write -> read -> write: the READER back-filled a concept invariant's empty fields[] from its field, so the CANONICAL form of a model depended on how many times it had been round-tripped

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found in Move 11 W6 while writing the R0.3 round-trip assertion for the new
`transaction.uiState` field ("Canonical JSON = writer AND reader"). The uiState half passes; the
WHOLE-document equality assertion fails, on two lines that have nothing to do with it.

Measured with a throwaway probe that diffs write1 vs write2 of the same compiled model:

    line 98
      write1:       "fields" : [ ]
      write2:       "fields" : [ "id" ]
    line 235
      write1:       "fields" : [ ]
      write2:       "fields" : [ "id" ]
    total lines: 440 vs 440

Nothing else differs across 440 lines, so this is one narrow defaulting asymmetry, not general
drift.

**Surface:** `dsl/compiled`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModelCanonicalJson.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/CompiledModelCanonicalJsonReader.java`

The compiler emits a panel with `fields: []`; the reader fills an empty `fields` with the root
concept's id; a second write then emits `["id"]`. Whichever side is "right", they disagree, so
`toJson(model) != toJson(fromJson(toJson(model)))`.

**Why this is filed rather than shrugged at.** "Canonical" is load-bearing in this repo in two
places that both assume a byte-stable form:

  1. `npdev-generated/` is HASH-VERIFIED at app startup (see the workspace-menu/shell notes) --
     anything that re-canonicalizes a compiled model on a path that later re-hashes it would
     produce a spurious mismatch;
  2. `npdev.schema.fingerprint` and the compiled-model comparison the schema engine performs are
     both equality-over-canonical-form arguments.

Neither is known to be broken today: the reader is used for reading models INTO tools, not on the
generate-then-hash path, so the asymmetry has had nowhere to bite. That is why this is LOW and
filed rather than fixed mid-move -- but a canonical form whose value depends on round-trip count
is a latent trap, and the next feature that reads a compiled model back and re-emits it will hit
it.

**Fix shape when taken up**: decide which side owns the default (almost certainly the COMPILER --
the reader should be a faithful parser and never invent content), then assert idempotence
(`toJson(fromJson(toJson(m))) == toJson(m)`) as a real test over a rich fixture model, so this
class cannot recur silently for the next field either.

**Not hidden meanwhile**: `AutoPanelUiStateValidationTest` says in its own comment why its
round-trip assertion is scoped to uiState instead of the whole document, and cites this id.

---

## CLOSED, Wave 1.1 (2026-07-31, MASTER_AI_PLATFORM_PROGRAMME_v2.md §3.4)

**Re-rated LOW -> MEDIUM before fixing**, per the programme's F5: `LC-C2`'s central DoD is "the
resulting metadata is byte-identical to what a full regeneration produces -- one test that runs
both and compares". A canonical form whose value depends on how many round-trips a path has
performed makes that test fail INTERMITTENTLY, which is worse than failing.

**The divergence, located rather than guessed.** A probe that diffed write1 vs write2 with
surrounding context named the owner: it is not a panel's `fields` (as the original report assumed)
but a CONCEPT INVARIANT's:

    "invariants" : [ { "ref" : "required(id)", "type" : "required", "field" : "id",
                       "expression" : "",
                       "fields" : [ ]        <- write1
                       "fields" : [ "id" ]   <- write2

`CompiledModelCanonicalJsonReader` back-filled it:

    invariantFields.isEmpty() && field != null ? List.of(field) : invariantFields

i.e. the reader invented content the document did not contain.

**Which side was changed, and why it is the opposite of this item's own first guess.** The fix
shape above said "almost certainly the COMPILER". That was written before the owner was known.
Emitting `["id"]` from the compiler would have been equally idempotent -- and would have changed
the canonical content of EVERY model, hence every `npdev.schema.fingerprint`, producing exactly
the spurious schema-impact prompt (a migration that appears necessary and is not) that this item
names as one of its two consequences. So the READER was made faithful instead: zero content
change, zero fingerprint churn, and it is the side that was actually misbehaving -- a parser must
never invent. Callers already receive an empty list from a freshly compiled model, so nothing that
works today starts failing.

**The assertion this item asked for exists**: `CanonicalJsonIdempotenceTest` --
`toJson(fromJson(toJson(m))) == toJson(m)` over a fixture exercising concepts, invariants,
aggregates, an aggregate-bound AutoPanel with uiState, and a hand-authored panel. It reports the
first differing lines with line numbers on failure, because a whole-document diff in an assertion
message is unreadable and the line number is what locates the field.

**A workaround it forced is now removed**: `AutoPanelUiStateValidationTest`'s round-trip assertion
was scoped to `uiState` alone while this was open, with a comment citing this id. It is back to
the whole document -- which is the version that would catch a NEW field the writer knows about and
the reader does not.

### REG-98 — Two differently-named concepts can compile to the SAME physical table and the model validates with zero errors -- SqlIdentifierSupport.toSnake() sanitizes by REPLACEMENT (every non-alphanumeric becomes '_'), and nothing checks the derived table names for collisions

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found during Wave 0.1's REG-94 triage (MASTER_AI_PLATFORM_PROGRAMME_v2.md), while tracing whether
`JdbcBusinessConceptStore`'s spliced `shape.tableName()` could carry SQL syntax. It cannot -- but
the reason it cannot is what produces this bug.

Reproduced with a two-concept model and the real validator:

    concepts: [ { "name": "OrderLine",  fields: [id] },
                { "name": "Order Line", fields: [id] } ]

    $ .\gradlew.bat :NPDevContract:dsl:validateModel -PmodelPath=...\collide.json
    "errors" : 0
    BUILD SUCCESSFUL

Both names pass through `SqlIdentifierSupport.toSnakePlural()` to the SAME table, `order_lines`.

**Surface:** `dsl/compiled, schema-lifecycle`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiled/SqlIdentifierSupport.java`
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/validation/ConceptValidation.java`
- `NPDevContract/schemas/model.schema.json`

`toSnake()` walks the name and appends `Character.toLowerCase(c)` for a letter or digit and `'_'`
for **everything else**, then collapses runs of `_` and trims. So punctuation, spaces, quotes and
semicolons are all mapped to the same character and then squeezed away. Every one of these
compiles to `order_lines`:

    OrderLine     Order Line     Order-Line     Order.Line     Order__Line     "Order Line "

Two independent gaps compose:

1. **The schema does not constrain a concept name.** `$defs.concept.properties.name` is
   `{"type": "string", "minLength": 1}` -- no pattern. There is no `tableName` property at all.
   (Confirmed live: a concept named `Ord"; DROP TABLE users; --` validates with 0 errors.)
2. **Nothing checks derived table names for uniqueness.** Concept NAMES are checked for duplicates;
   the physical names they compile to are not.

**Consequence.** Two concepts silently share one table. Both write to it, both read from it, and
each sees the other's rows as its own -- with column sets that may only partially overlap, so the
second concept's required columns may not even exist. On a fresh database the schema engine
realizes one table for two concepts; on an existing one the second concept's realization is an
additive change against a table it does not own. Nothing anywhere reports a problem.

**Why this is not an injection bug, and why that matters for the fix.** `toSnake()`'s replacement
behaviour is exactly what makes `JdbcBusinessConceptStore`'s spliced identifiers safe (see
docs/SECURITY_PATTERN_SWEEP_2026-07.md §2.4). So the fix must NOT be "make toSnake refuse" without
care -- that would be a behavioural break for every existing model whose concept names contain a
space or a hyphen. The two safe moves are additive:
  (a) a validation rule that refuses a model in which two concepts derive the same physical table
      name, naming both concepts and the collided name; and
  (b) a schema `pattern` on concept `name` so the hostile-input case is refused at authoring time
      rather than silently rewritten.

(a) is the one that closes the data-integrity hole and can ship on its own; (b) is defence in
depth and is the more likely to need a migration note, since a corpus model may already use a
character the pattern would reject.

**Rated HIGH, unlike its REG-94 siblings.** It needs no attacker: a plausible authoring slip
(`Order Line` beside `OrderLine`) silently merges two concepts' data, and the failure is invisible
at every layer that could have reported it -- schema, validator, compiler, schema engine.

**Not fixed in Wave 0.1**, which the programme scopes as a reading task ("triage first"). Filed
with the reproduction, the cause and the fix shape so it is not re-derived.

---

## CLOSED (2026-07-31, MASTER_AI_PLATFORM_PROGRAMME_v2.md §2.1, fix shape (a) only)

`ConceptValidation.validateTableNameCollisions` (wired into `SemanticValidator.validate`,
immediately after `indexEntities`) derives each concept's physical table name the SAME way
`ModelCompiler` does -- `SqlIdentifierSupport.toSnakePlural(concept.getName())`, called directly
rather than reimplemented, so the check can never drift from what actually compiles -- and refuses
the model when two DIFFERENTLY-named concepts collide, naming both concepts and the collided table.
A same-name duplicate (already caught by `indexEntities`) is not double-reported.

Reproduced RED first (the ledger's own `OrderLine`/`Order Line` case, plus hyphen/dot variants),
then GREEN: `TableNameCollisionValidationTest` (4 cases -- two colliding-name shapes, one genuinely
distinct pair that must NOT trip it, one same-name duplicate that must still route through the
existing duplicate-concept-name error only).

**Verified against the real corpus, not asserted**: `python scripts/quality/validate-corpus.py`
re-run after the change -- 29/29 models still parse. No existing corpus model collides, so this
ships with no `npdev migrate` codemod and no `BREAKING.md` entry: nothing that validated before
stops validating now.

**Fix shape (b) (a schema `pattern` on concept `name`) deliberately NOT done** -- named in the
item as defence-in-depth, not required to close the data-integrity hole, and more likely to need
a corpus migration of its own. Left open as a follow-on if wanted, not re-filed as a new item.

### REG-99 — A band's transaction.visibleWhen was unreachable in EVERY spelling -- the validator accepts only the derived address 'collection.band', the expander read only the bare band name, so the predicate validated and was silently dropped

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-07-31)
**Verification:** VERIFIED_LIVE
**Source:** Found by the Wave -1.1 live browser proof of Move 11 W6 -- i.e. by driving the feature in a real
browser rather than by asserting on the descriptor. The `$ui` toggle rendered and switched
correctly, and the `posicoes` band did not move:

    w6_uistate_toggle_before = {"toggle":"Completo","options":["Completo","Resumo"],
                                "bandTitles":["posicoes"],"bandRegions":1}
    w6_uistate_toggle_switch = switched to:Resumo
    w6_uistate_toggle_after  = {"toggle":"Resumo","bandTitles":["posicoes"],"bandRegions":1}

The declared predicate was `{"itens.posicoes": "$ui.detalhe == 'Completo'"}`, and the model
validated with 0 errors. Confirmed at the artifact: the generated `compiled-model.json` band
descriptor carries `"address": "itens.posicoes"` and **no `visibleWhen` at all**.

**Surface:** `dsl/compiler, aggregate-workbench`
**Files:**
- `NPDevContract/dsl/src/main/java/com/npdev/dsl/v1/compiler/AutoPanelExpander.java`

Two halves of the same feature disagreed about the key, and each half was individually reasonable.

`PanelValidation.derivedAddresses()` accepts exactly: `"header"`, a declared top-level collection
name, or a declared `"<collection>.<band>"` pair. `AutoPanelExpander.sectionDescriptor()` looked a
band's predicate up as `visibleWhen.get(child.name())` -- the BARE band name.

So:

  "itens.posicoes"  -> passes validation, and the expander never looks under that key. Dropped.
  "posicoes"        -> the expander would read it, and validation rejects it as an unrecognized
                       address before it ever gets there.

**There is no spelling that works.** Band-level `visibleWhen` has been dead since Move 7 W1 typed
`transaction.visibleWhen`, with no error on any path. Section-level (`"itens"`) always worked --
the bare name and the derived address are the same string for a top-level collection, which is
exactly why this survived: the feature demonstrably worked, on the half anyone tested.

`attachRegion` one line below already used the dotted address for the same band, so the two
sibling lookups in the same method disagreed with each other.

**Fix**: look the band's predicate up by its derived address first, falling back to the bare name.
The fallback is deliberate and secondary -- the retired untyped `transaction.metadata.visibleWhen`
map predates derived addresses, so a model still on that spelling keeps working until it migrates.

**This is a fourth instance of the X0 silent-answer class** (MASTER_AI_PLATFORM_PROGRAMME_v2.md
§2.1), and a slightly different shape from the other three: not "an evaluator returns a default
answer for input it cannot handle", but "a declaration the validator accepts never reaches the
evaluator at all". Same consequence, same silence. Added to X0's register.

**Why the descriptor-level test did not catch it and the browser did.** W6's own DSL test asserted
the `uiState` control compiles into the workbench descriptor, which it did. Nothing asserted that
the PREDICATE reached the band. The live run is what failed, and the assertion it produced
(`AutoPanelUiStateValidationTest.bandVisibleWhenReachesTheBandDescriptor`) now pins both the
address and the predicate on the band descriptor.

### STOR-1 — 41 dialect-bound SQL sites were inlined across 19 files, so a second database engine was a rewrite rather than a dialect -- and two files had already grown a hand-rolled H2-vs-Postgres fork

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-08)
**Verification:** UNIT_TESTED
**Source:** storage/PLAN.md S1, executed against 5680551. The plan's own measurement (storage/helpers/dialect-site-inventory.py) found 41 real code sites across 19 files -- not the ~130 a keyword grep had suggested -- with pagination alone accounting for 23.
**Surface:** `kernel/storage-dialect, adapters/*-postgres, runtimehost/db, generator/dbconfig`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PostgresDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/H2Dialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PaginationClause.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialects.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/PostgresDialectGoldenSqlTest.java`
- `NPDevKernel/adapters/flowinstance-postgres/src/main/java/com/npdev/adapters/flowinstance/jdbc/JdbcFlowInstanceStore.java`
- `NPDevKernel/adapters/eventstore-postgres/src/main/java/com/npdev/adapters/eventstore/jdbc/JdbcEventStore.java`
- `NPDevKernel/adapters/persistence-postgres/src/main/java/com/npdev/adapters/persistence/postgres/PostgresPersistenceCapabilityAdapter.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/JdbcBusinessConceptStore.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java`
- `scripts/quality/check-dialect-sites.py`

The eight *-postgres adapters hold ~3,900 lines of SQL, and only 41 places in the whole codebase were bound to a particular engine's spelling. That is a good position to be in, and it was invisible: nothing named those 41 places, so "add MySQL" looked like a rewrite of the adapters.
Measured distribution, which is lopsided in a useful way:

    pagination           23      more than half -- and IDENTICAL on MySQL
    json-type             7
    introspection         5
    upsert                4
    identifier-quoting    1      <- all of it a false positive, see below
    auto-increment        1      <- likewise
    returning             0      <- MySQL's hardest gap does not apply here

THREE OF THE 41 WERE NOT SQL. `Function.identity(` matched the auto-increment pattern; a JSON writer emitting a key called "table" satisfied the identifier-quoting guard (TABLE is a noun, not a statement keyword). Both constructs' ENTIRE reported count was false. Two more turned up once the kernel was scanned for the first time: `limit > 0 ? limit : defaultCap` (ordinary Java) and the word "returning" inside an English error message -- the latter in the one construct whose count is load-bearing, since zero RETURNING sites is what makes MySQL cheap.
THERE WERE ALREADY TWO DIALECTS. PostgresPersistenceCapabilityAdapter.buildUpsertSql and JdbcBusinessConceptStore.upsertSql each branched on getDatabaseProductName().contains("h2") and emitted a different statement; SchemaRealizationEmitter.addConstraintIfMissing guarded DDL with a Postgres DO $$ block or an H2 drop-then-add. Routing everything through one Postgres dialect would have handed H2 an ON CONFLICT it does not accept -- so leaving those inline was the behaviour-CHANGING option, not the safe one.
THE PROOF ITSELF WAS BROKEN. capture-sql-baseline.py, the tool whose diff IS S1's exit condition, was blind to Java text blocks: a `"""..."""` literal opens with `String sql = """`, the regex saw two adjacent quotes, extracted an empty string and dropped it. JdbcFlowInstanceStore -- which holds NINE of the twenty-three pagination sites -- contributed ZERO baseline entries. The file most affected by S1 was the file the proof could not see.

### STOR-10 — Five more two-engine assumptions between "the app boots" and "the app works" -- a Postgres-by- default dialect probe, UUID and timestamp values bound and read in shapes only two engines accept, a schema differ comparing the catalog against a type it never emitted, and a two-way column rename

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md W10. Found by running run-engine-app-proof.py and run-tier-c-probes.py LOCALLY against real MySQL 8.4 and SQL Server 2022 containers instead of spending a ~12-minute CI round per error -- which is what made five sequential failures affordable to find in one sitting.
**Surface:** `kernel/storage-dialect, kernel/persistence-postgres, runtimehost/db`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialects.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlServerDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PostgresDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/H2Dialect.java`
- `NPDevKernel/adapters/persistence-postgres/src/main/java/com/npdev/adapters/persistence/postgres/PostgresPersistenceCapabilityAdapter.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/JdbcBusinessConceptStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/ColumnRenamePass.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/schemastate/SchemaDiffEngine.java`
- `scripts/quality/run-engine-app-proof.py`

STOR-4/5/7/9 got a generated app to BOOT on MySQL and SQL Server. Everything below is what happens
after that, and every one of them is the same shape: a question that has four answers, asked as if
it had two.

1. THE DIALECT PROBE ITSELF (the root of three of the others)

       SqlDialect d = isH2Connection(connection) ? H2Dialect.INSTANCE : PostgresDialect.INSTANCE;

   Three call sites, all reading the connection's product name, all TWO-WAY. On MySQL that answers
   Postgres, and the caller emitted `ON CONFLICT (id) DO UPDATE SET x = EXCLUDED.x`:

       You have an error in your SQL syntax ... near 'CONFLICT (id) DO UPDATE SET active = ...'

   Every write returned 500 after a clean boot and a correctly realized schema. The dialect seam
   could not see it -- there is no `ON CONFLICT` literal in any caller; the statement came from
   PostgresDialect, correctly, in answer to a question asked wrong.

2. UUID BOUND AS A SERIALIZED JAVA OBJECT

       Incorrect string value: '<0xACED0005>...' for column 'id' at row 1

   0xACED0005 is the Java serialization stream header. The persistence adapter coerces ids to
   java.util.UUID because Postgres and H2 have a native uuid type; MySQL's column is CHAR(36) and
   the driver serialized the object into it.

3. TIMESTAMP READ BACK IN A SHAPE THE DTO CANNOT BIND

       MySQL:      Cannot deserialize `java.time.OffsetDateTime` from String "2026-08-08T12:00:00"
       SQL Server: Unexpected token (START_OBJECT) ... for java.time.OffsetDateTime value

   The DSL's `datetime` compiles to OffsetDateTime on every engine. MySQL's DATETIME(6) has no
   offset so the driver returns a LocalDateTime; SQL Server's DATETIMEOFFSET(6) returns
   microsoft.sql.DateTimeOffset, the driver's own class, which Jackson renders as a nested object.
   Both requests returned 4xx AFTER the persistence capability had reported SUCCESS -- a write that
   really happened, reported as a failure.

4. THE SCHEMA DIFFER COMPARED THE CATALOG AGAINST A TYPE IT NEVER EMITTED

       !!  DESTRUCTIVE_NARROW_TYPE  evolve_rows  id  CHAR(36) -> UUID  1
           MANUAL_REVIEW: non-character-length narrowing

   The column did not change. MySQL realizes `uuid` as CHAR(36); the differ compared the catalog's
   CHAR(36) against the MODEL's UUID and refused to boot. Every MySQL and SQL Server app would hit
   this on its SECOND boot after any model change -- the first boot creates the schema and the
   fingerprint matches, so nothing diffs and nothing looks wrong.

5. COLUMN RENAME, TWO WAYS FOR FOUR ENGINES

       "Postgres".equals(engine) ? "ALTER TABLE t RENAME COLUMN a TO b"
                                 : "ALTER TABLE t ALTER COLUMN a RENAME TO b"

   MySQL got H2's spelling. SQL Server does not use ALTER TABLE for this at all (sp_rename), so the
   shape could not have been a two-way choice even in principle. The failure is the worst one this
   layer produces:

       schema pass 'COLUMN_RENAME' failed at RENAME_COLUMN books.isbn -> isbn13.
       Engine 'mysql' COMMITS IMPLICITLY ON DDL, so this pass is HALF APPLIED

   A rename is the one migration where getting it wrong loses data rather than time.

### STOR-11 — On MySQL a create that violates a unique constraint returns 200 and OVERWRITES the row that held the value, because ON DUPLICATE KEY UPDATE reacts to every unique index -- the dialect's own javadoc said no NPDev schema could produce this shape, and any `unique: true` field does

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md W10, Tier C vector I3 against a REAL MySQL 8.4 container. Every other Tier C vector (E1, E2, I2) passed in the same run.
**Surface:** `kernel/storage-dialect (MySqlUpsertStrategy)`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/UpsertPlan.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/UpsertStrategy.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectConformanceTierATest.java`
- `NPDevKernel/adapters/expression-cel/src/main/java/com/npdev/runtime/support/GeneratedCrudRuntimeSupport.java`
- `NPDevKernel/adapters/persistence-postgres/src/main/java/com/npdev/adapters/persistence/postgres/PostgresPersistenceCapabilityAdapter.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/JdbcBusinessConceptStore.java`
- `NPDevSamples/probes/p4-constraints/Input/model.json`
- `scripts/quality/run-tier-c-probes.py`

MySQL has no "on conflict with THIS key". `INSERT ... ON DUPLICATE KEY UPDATE` fires on a clash
with ANY unique index on the table, so an upsert keyed on the id updates a row the caller never
named. Postgres and H2 say `ON CONFLICT (id)`, which reacts only to the primary key and raises on
any other unique violation.

MEASURED, not inferred:

    POST /api/concepts/accounts {email: X, region: R}   -> 200   (row created)
    POST /api/concepts/accounts {email: X, region: R}   -> 200   <-- must be >= 400
    SHOW INDEX FROM accounts   ->  ux_accounts_email  Non_unique = 0   (the index IS there)
    SELECT ... FROM accounts   ->  2 rows, both emails distinct       (nothing duplicated)

The constraint exists and is correct. The engine simply treats the violation as an instruction to
update. So a user creating a record whose unique field collides with someone else's does not get
an error -- they overwrite that person's row, with their own values, and are told it succeeded.

WHY IT SURVIVED, AND WHY THAT PART MATTERS MOST

This divergence was KNOWN. `MySqlUpsertStrategy`'s javadoc described it exactly, and then closed
with:

    "Nothing in NPDev's generated schema puts a second unique index on a table it also upserts by
     id today, and the divergence is recorded here rather than discovered later."

That sentence was false when it was written. Any model field declaring `unique: true` produces
precisely that shape, and `unique: true` is an ordinary thing to declare. The record was correct
about the ENGINE and wrong about NPDEV, which is the more dangerous half -- it turned a real
hazard into a closed question, and the assumption was never tested because no corpus app had ever
run on MySQL at all.

The javadoc has been corrected in the same commit that filed this. The behaviour has not.

### STOR-12 — A MySQL or SQL Server app boots once and never again -- the migration-claim store tested for Postgres's SQLSTATE 23505, so the ordinary "the canonical row already exists" case was reported as a hard failure, with a message asserting the exact opposite of the truth

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md. Found by CI run 31289401926 -- in which E3 and E4 went GREEN on the first app-proof step and the job then went red on a SECOND, accidentally duplicated copy of the same steps. The duplication was a workflow defect; what it exposed was not.
**Surface:** `kernel/storage-dialect, runtimehost/db, adapters/circuit-postgres`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlServerDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PostgresDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/H2Dialect.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectConformanceTierATest.java`
- `NPDevKernel/adapters/circuit-postgres/src/main/java/com/npdev/adapters/circuit/jdbc/JdbcCircuitBreakerStateStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/MigrationClaimStore.java`
- `.github/workflows/engine-support.yml`

MySQL 8.4 and SQL Server 2022, on the second boot against a database that already holds NPDev's
tables:

    Caused by: java.sql.SQLException: NPDev schema lifecycle: could not seed the canonical
    migration-claim row in npdev_schema_migration_claim. This is NOT a duplicate-row race
    (SQLState 23000, error code 1062), so the row is genuinely absent and the boot cannot proceed.
    Caused by: Duplicate entry 'schema-migration' for key 'npdev_schema_migration_claim.PRIMARY'

The row was not absent. It was right there, and the driver said so in the very next line.

    Postgres, H2   SQLSTATE 23505                      (a dedicated unique-violation code)
    MySQL          SQLSTATE 23000, error 1062          (23000 is ALSO FK, NOT NULL, CHECK)
    SQL Server     SQLSTATE 23000, error 2627 / 2601   (likewise)

`MigrationClaimStore.isUniqueViolation` tested `"23505".equals(state)`, so on both engines the
benign case its own comment describes -- "expected under a concurrent bootstrap race, or on every
non-first boot" -- became a boot refusal. `JdbcCircuitBreakerStateStore.isDuplicateKey` had the
same test plus a substring search for the word "unique" in the driver's message, which is the kind
of check that works until someone runs a non-English server.

WHY THE FIRST BOOT PASSED, AND WHY THAT MADE IT INVISIBLE

`claim()` returns early when the database is FRESH (`if (freshDatabase) return null`), so the
claim path -- and this test -- never runs on boot one. Every local run in this plan dropped and
recreated the database first, so every one of them took the fresh path. The app-proof's own
restart assertion did not reach it either, because an unchanged fingerprint skips the migration
entirely.

So the bug needed: a real MySQL/SQL Server, a database that already has NPDev's tables, AND a
model change. That is not an exotic combination -- it is what every deployment does after its
first release. It just is not what a fresh test harness does.

### STOR-14 — No way to say "this database is not mine to manage" -- the `_ops` toolbox assumes it provisioned every server engine, and `Reset-Environment.ps1` recursively deletes a data root that may be the user's own

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-08-10)
**Verification:** VERIFIED_LIVE
**Source:** storage/stabilize/FOUR_AND_EXTERNAL.md Part B, filed under its own instruction ("Do not implement the flag before the round -- file it as a ledger item"). The gap was first NAMED in code, not in a plan: OperationalRunbookEmitter.java's own comment beside the port-collision probe says "NPDev has no EXTERNAL engine kind yet -- a mode where the toolbox knows the server is not its to manage and disables Start/Stop/Reset". That comment chose to DETECT rather than solve, and this is the item it deferred to.
**Surface:** `generator/dbconfig`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/OperationalRunbookEmitter.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/UserDatabaseDefinitionLoader.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/GeneratedDatabasePlan.java`
- `schemas/ai/user-db-definition.schema.json`
- `NPDevCli/npdev_cli.py`

A machine that already runs PostgreSQL is the likely case, not the exotic one -- people pick the engine they already use. NPDev has no way to be told so. Every server engine is assumed to be NPDev's own container, on all five `_ops` operations.
THE DESTRUCTIVE HALF, and the reason this is HIGH rather than MEDIUM. `Reset-Environment.ps1` (OperationalRunbookEmitter.resetEnvironmentScript, ~lines 535-541) has two halves, and only the first is engine-aware:

    if ($plan.profile.kind -eq 'server') { ... docker rm -f $plan.containerName ... }
    if ($plan.physicalDatabase -and (Test-Path -LiteralPath $plan.resolvedDataRoot)) {
      Remove-Item -LiteralPath $plan.resolvedDataRoot -Recurse -Force
    }

The recursive delete is guarded by `physicalDatabase` and existence -- never by whose database it is. So the obvious partial implementation, "make the Docker branch a no-op when the server is external", leaves a `Remove-Item -Recurse -Force` pointed at a path the user may have set to something real. That is the difference between an incomplete feature and a destructive one, and it is the same shape as QUAL-3: an operation that looks aimed at NPDev's own resources and is not.
BOTH scripts must HARD-REFUSE AND RETURN -- not skip a branch and continue.
WHOEVER IMPLEMENTS THIS WRITES THE REFUSAL TEST FIRST: an external app whose `resolvedDataRoot` points at a directory containing a canary file; reset -> refuses, canary intact. RED-prove it against today's code, where it deletes the canary.
"ENSURE THE DATABASE EXISTS" LOSES ITS CLIENT, AND THE ANSWER IS TO STOP TRYING. Create-Environment guarantees a client today only because it runs `docker exec <container> createdb` -- the client lives in the container. A server NPDev did not start guarantees nothing, and on Windows `psql` / `mysql` / `sqlcmd` are rarely on PATH. In external mode Create becomes VERIFY: connect over JDBC with the driver the app already ships and report

    database 'myapp' does not exist on localhost:5432 -- create it and re-run

Honest, no new dependency, and the same thing `npdev db test-connection` already does.
THE PARITY GATE STAYS GREEN FOR FREE, BUT ONLY IF THE BRANCH READS A PLAN FIELD. The five `_ops` operations are emitted BYTE-IDENTICAL for Postgres, MySQL and SQL Server (E15), and `run-engine-toolbox-parity.py` / `check-engine-parity.py` enforce it. A data-driven `if ($plan.externallyProvisioned)` is byte-identical across all three; a hand-written `-eq 'Postgres'` fails the parity gate immediately. The architecture pushes toward the right fix -- a plan field is REQUIRED here, not merely preferred, and an implementer who does not know that will waste a cycle discovering it.
WHERE IT IS MODELLED. Not in `engine-profiles.json`. External-ness is a property of THIS APP'S DEPLOYMENT, not of the engine -- the same Postgres is Docker on a laptop and a managed instance in staging. Putting it in the profile model states it in the wrong place, and the profile model should not be touched at all.
TWO ESTIMATE CORRECTIONS, measured against the tree at ac0ccc35 rather than assumed, because the estimate everyone reaches for is wrong in both directions:

  * NO FOUR-COPY MIRROR TAX. `model.schema.json`'s four-place mirror rule (CLAUDE.md) does NOT
    apply. `schemas/ai/user-db-definition.schema.json` exists in exactly ONE place.
  * BUT THERE **IS** A SCHEMA, and FOUR_AND_EXTERNAL.md's Part B.1 is wrong to say there is not.
    That file has `"additionalProperties": false` at the root AND inside `database`, so a new
    field is REJECTED before the loader ever sees it, and `NPDevCli/tests/test_engines.py` asserts
    every scaffolded definition validates against it. One copy to edit, not zero and not four.

ALREADY EXISTS AND MUST NOT BE DUPLICATED: `schemaLifecycle.ownership: ExternallyManaged` (REG-7.1) already declares that NPDev issues no schema DDL against this database, and UserDatabaseDefinitionLoader enforces it (KeepExistingIfCompatible + no destructive recreate). That is a statement about the SCHEMA; this item is about the SERVER. They are genuinely different -- an NPDev-provisioned container can hold an externally-managed schema -- but an implementer who does not notice the existing field will add a second overlapping one. Decide explicitly whether the new flag lives beside it or subsumes it.

### STOR-2 — A conversion hook's refusal claimed "the hook's changes were rolled back; nothing persisted" on engines that COMMIT IMPLICITLY ON DDL -- false on H2 today, and the decision MySQL forced

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-08)
**Verification:** UNIT_TESTED
**Source:** storage/PLAN.md §5's instruction, followed literally: "Before writing MySqlDialect, find every place the schema engine assumes a DDL rollback and decide, explicitly, what MySQL does instead." Doing that search found a defect that predates MySQL entirely.
**Surface:** `runtimehost/db/conversion-hooks, kernel/storage-dialect`
**Files:**
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/ConversionHookRunner.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/H2Dialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/StorageCapability.java`

ConversionHookRunner.executeAndVerify runs a hook's convert SQL and its verifySql in ONE transaction on ONE connection, so a verify failure rolls the whole hook back. That design was itself a fix (SER-P7 finding #1): before it, the convert committed first and a failing verify aborted the boot with the hook's changes already landed.
A hook's convert SQL contains DDL. ConversionHookEmitter emits `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` into it.
H2 and MySQL both COMMIT IMPLICITLY ON DDL. The ALTER ends the transaction the moment it runs, taking any DML issued before it along with it. The subsequent rollback undoes only what came after the last DDL statement -- so it is not a no-op, but it is not what the code said either.
All three refusal messages said, verbatim:

    "-- refusing the boot (the hook's changes were rolled back; nothing persisted)."

and the comment above them said "Now 'nothing persisted' is literally true." On H2 -- the engine every NPDev dev app runs on -- it was already not true, and boundary B11 had recorded H2's DDL limitation independently without anyone connecting it to this message.
WHY THIS IS THE HIGH-SEVERITY HALF. The failure mode is not the un-rolled-back DDL; it is the platform telling an operator the database is untouched when it is not. A false all-clear is what turns a recoverable half-migration into one nobody goes looking for. The operator reads "nothing persisted", fixes the model, and re-runs -- against a schema that already moved.

### STOR-3 — MySQL, PostgreSQL and SQL Server each pass 13/13 Tier B vectors against REAL containers -- but none is supported until that run is repeatable rather than a manual dispatch of unpinned images

**Type:** GAP · **Severity:** MEDIUM · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/PLAN.md S4b, S5 and S4a.
**Surface:** `kernel/storage-dialect, generator/dbconfig, ci/storage-dialect-conformance`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlServerDialect.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectTestSupport.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectConformanceTierATest.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectConformanceTierBTest.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/StorageDialectInitializer.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/DatabaseEngine.java`
- `.github/workflows/storage-dialect-conformance.yml`

MySqlDialect and SqlServerDialect are implemented, registered in SqlDialects, wired to new DatabaseEngine values (MYSQL, SQL_SERVER -- new VALUES on the existing storageMode axis, not a parallel concept), given JDBC URLs, drivers and default ports, and pinned at boot by StorageDialectInitializer from npdev.database.engine.
That last piece matters more than it looks: without it, registering MySQL would have changed NOTHING. Every store falls back to SqlDialects.active(), which defaults to Postgres, so an app generated for MySQL would have booted happily and emitted Postgres SQL -- the silent-wrong-answer failure the whole seam exists to prevent, arriving through the back door.
WHAT IS PROVEN
  - Tier A, 78 assertions, all four engines: clause text, declared parameter order, quoting,
    auto-increment spelling, capability declarations, and every refusal. Zero skips.
  - Tier B, 52 executions, 12 skipped with printed reasons: behaviour against a real connection --
    upsert idempotence, page non-overlap, JSON round-trip, reserved-word columns, DML rollback,
    auto-increment monotonicity, enforced uniqueness.
  - PostgresDialectGoldenSqlTest, 24 assertions (STOR-1).

WHAT THE REAL RUNS PROVED (2026-08-08)
Three dispatches. Every figure below is read from the uploaded JUnit XML, never from job status -- which is the lesson the first run taught, since it was red on all three jobs while containing the best news of the day.

    run 31264977219 (commit 5814886) -- FIRST EVER, jobs red
    dialect      passed  failed  seconds
    mysql            13       0     23.7    <- real MySQL 8.4 with utf8mb4
    postgres         13       0      8.3    <- no regression from the S1 seam
    sqlserver        12       1     21.4    <- one failure, a TEST defect (see below)
    h2                0      13      0.1    <- harness: no container exists for h2
                                   0 skipped

    run 31268402414 (commit bec03b5) -- after F0-F5, ALL JOBS GREEN
    mysql            13       0     23.8
    postgres         13       0     10.8
    sqlserver        13       0     19.5
                                   0 skipped, h2 no longer selected

39 vectors, three real engines, zero failures, zero skips. One engine per job (13 tests, not 52).
The seconds column is what separates "the harness broke" from "the engine ran": h2 fails in 0.1s (an immediate throw, no database) while mysql spends 23.7s (container time). The failure TYPES say the same thing independently -- IllegalArgumentException for all 13 h2 cases, AssertionFailedError for the one sqlserver case.
ZERO SKIPS. The twelve vectors that print a skip reason on the local H2 backend all executed, including T2 (DDL transactionality), Q2 (case sensitivity) and J2 (charset fidelity). That was the entire purpose of the workflow and it worked on the first run.
J2 on SQL Server was the one real finding, and it was the vector's own bug: it hand-wrote VARCHAR(4000) in its DDL, and SQL Server's VARCHAR is non-Unicode, so 'cafe [coffee emoji]' came back as 'cafe ?' -- silent per-character loss. SqlServerDialect.portableColumnType already returned NVARCHAR(4000) and was never asked. Fixed in F3, along with J1, which had the identical defect and was passing only because its document is ASCII. The general lesson is bigger than the line: a conformance vector that writes its own DDL is testing its own SQL, which is exactly the trap PLAN.md §6 named for probe apps and Tier B then walked into.
WHY THIS IS STILL PARTIAL
The results are now unambiguous; the PROCESS is not yet support:

  - the workflow is workflow_dispatch-only, so nothing re-verifies any engine when a dialect
    changes. A green run is a snapshot, not a guarantee, and the next regression is found by a user
  - the container images are moving tags (mssql/server:2022-latest), so a future red cannot be
    told apart from "the image changed". This is now the PRIMARY reason the trigger has not been
    promoted -- pinning comes first
  - E1, E2, I2 and I3 need a realized schema (Tier C + the probe apps) and cannot run here at all
  - the MySQL DDL-implicit-commit decision (STOR-2) is now exercised by T2 against a real MySQL,
    and T2 passed -- but T2 asserts the DECLARATION matches the engine. The schema engine's
    behaviour under a half-applied migration is still unmeasured, and that is the one that
    corrupts data rather than failing loudly

BOTH RECORDED ENGINE GAPS ARE NOW CLOSED (storage/FULL_SUPPORT_PLAN.md W1.3), fixed rather than accepted as boundaries -- so there is nothing to add under ledger/boundaries/:

  - SqlServerDialect.rowLimited() is a PREFIX rewrite (SELECT -> SELECT TOP n). The plan offered
    two options and said to find out which call sites need it before choosing. Measured: FOUR --
    two existence probes in PostgresPersistenceCapabilityAdapter, two first-by-order reads in
    JdbcEventStore -- and all four want "at most n rows", not a suffix. Suffix-vs-prefix is the
    engine's business, which is precisely what a dialect is for; pushing the question out to the
    call sites would have MOVED the engine switch rather than removed it, which SqlDialect's own
    javadoc names as the thing this seam must never become. rowLimit() -- the SUFFIX primitive --
    still throws, and that stays correct: there genuinely is no suffix form there. TOP is placed
    after DISTINCT (T-SQL's grammar is SELECT [ALL|DISTINCT] [TOP n]) and a CTE is REFUSED rather
    than mis-capped, since no string surgery can find its final select.

  - ConversionHookEmitter now takes the DatabaseEngine, threaded from GeneratorFacade, which
    already held the GeneratedDatabasePlan two lines above the call. The claim that this was a
    signature change MySQL could not ship without was right; the claim that it was structurally
    hard was not. It had been asking H2 unconditionally -- the narrower of the only two engines
    that existed -- and that stops being safe with a third (MySQL has no native UUID), in code
    that runs during a migration, on data.

### STOR-4 — MySQL and SqlServer were selectable, dialect-complete and conformance-green -- and no generated app could ever have connected to either, because the app template carried no JDBC driver for them

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** storage/FULL_SUPPORT_PLAN.md gap A ("no generated APPLICATION has ever booted on MySQL or SQL Server"), found the first time an application-level probe was actually run -- CI run 31272786548.
**Surface:** `runtimehost/build-template, generator/dbconfig, ci/engine-support`
**Files:**
- `NPDevRuntimeHost/build.gradle`
- `.github/workflows/engine-support.yml`
- `scripts/quality/run-engine-app-proof.py`
- `NPDevSamples/probes/engine-probe/Input/model.json`

Everything pointed the other way. `MySQL` and `SqlServer` were valid values of `db.definition.json`'s `database.engine`; `DatabaseEngine` gave them JDBC URLs, drivers and default ports; `SqlDialects` registered complete `MySqlDialect` and `SqlServerDialect` implementations; `StorageDialectInitializer` pinned the active dialect at boot; and the conformance suite passed 14/14 behavioural vectors for each, with zero skips, against REAL containers (run 31271016482).
A generated app for either engine died at DataSource creation, before one line of NPDev's own code executed:

    Caused by: java.lang.IllegalStateException: Cannot load driver class: com.mysql.cj.jdbc.Driver
        at DataSourceProperties.findDriverClassName(DataSourceProperties.java:184)

NPDevRuntimeHost/build.gradle -- the template copied into EVERY generated FinalApp -- declared `org.postgresql:postgresql` and `com.h2database:h2` and nothing else. The MySQL and SQL Server drivers existed only on `:kernel`'s TEST classpath, where the conformance suite uses them.
WHY EVERY EXISTING TEST MISSED IT, AND WHY THAT IS THE INTERESTING PART
Tier A asserts dialect string generation: no database, no app. Tier B asserts behaviour against a real connection -- and it OBTAINS that connection from a driver the test classpath has. Neither tier ever asks the question "can the artifact we ship to a user load this driver?", because neither tier builds that artifact.
So the platform could be, simultaneously and honestly:
  - correct at the dialect layer (proven, three real engines),
  - correct at the configuration layer (the enum, the URL, the port, the container name),
  - and completely unusable end to end.

"The dialect works" and "an app works" were different claims, and only an application-level probe could tell them apart. That is exactly why FULL_SUPPORT_PLAN.md ranks "a generated app boots" as gap A rather than a formality, and the ranking turned out to be right for a more concrete reason than the plan itself predicted.
A third person following the supported path -- `npdev init --engine mysql`, then run -- would have hit this on their first boot, with a Spring stack trace and no indication that the engine had never been usable.

### STOR-5 — The schema-realization script is written in Postgres/H2 guarded-DDL idioms (IF NOT EXISTS), which MySQL supports only partly and SQL Server not at all -- so NPDev's own V1 migration cannot run

**Type:** GAP · **Severity:** HIGH · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/FULL_SUPPORT_PLAN.md gap A / exit criteria E3+E4. Uncovered one construct at a time by the application-level probe, once STOR-4's missing JDBC driver stopped hiding everything behind it.
**Surface:** `generator/dbconfig/SchemaRealizationEmitter, kernel/storage-dialect`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `scripts/quality/check-dialect-sites.py`
- `.github/workflows/engine-support.yml`

`SchemaRealizationEmitter` writes `V1__npdev_schema_realization.sql`, the Flyway script that creates NPDev's own internal tables and the app's business tables. It is written in the guarded-DDL dialect of Postgres and H2:

    CREATE TABLE IF NOT EXISTS ...
    CREATE INDEX IF NOT EXISTS ...
    ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...

MEASURED, one CI round each, against real containers:

  run 31273275129  MySQL      error 1170 -- BLOB/TEXT column 'execution_id' used in key
                              specification without a key length          [FIXED: STOR-4 / keyableTextColumnType]
  run 31279857141  MySQL      error 1064 -- syntax error near 'IF NOT EXIST...'
                              (MySQL has no CREATE INDEX IF NOT EXISTS)    [THIS ITEM]
  run 31279857141  SQL Server "Incorrect syntax near 'probe_reserveds'"
                              (T-SQL has no CREATE TABLE IF NOT EXISTS)    [THIS ITEM]

WHY IT SURFACED ONLY NOW, AND WHY THAT IS THE POINT
Every layer below this was green and stayed green: Tier A (78 assertions, four engines), Tier B (14/14 behavioural vectors per engine against REAL containers, zero skips), and the whole configuration path. None of them ever asks the engine to run NPDev's OWN generated DDL, because none of them generates an app. This is gap A restated concretely for a second time -- STOR-4 was the first -- and it is the reason the plan ranks "a generated app boots" above everything else.
The failures are also strictly ordered: each fix reveals the next construct, because Flyway stops at the first statement it cannot run. That makes the remaining work bounded and visible rather than open-ended, but it does mean one CI round per construct until the seam is complete.
WHAT THE FIX LOOKS LIKE
This belongs in `SqlDialect`, beside `guardedConstraintDdl` which already exists for exactly this reason -- a Postgres `DO $$ ... $$` block that MySQL and SQL Server have no equivalent for. The same treatment is needed for the three idioms above:

  Postgres / H2   native IF NOT EXISTS
  MySQL           CREATE TABLE IF NOT EXISTS is supported; CREATE INDEX IF NOT EXISTS and
                  ADD COLUMN IF NOT EXISTS are not -- they need an information_schema guard around
                  a PREPARE/EXECUTE, or Flyway callbacks
  SQL Server      none are supported -- each needs an IF OBJECT_ID(...) IS NULL / IF NOT EXISTS
                  (SELECT ... FROM sys.*) wrapper

`check-dialect-sites.py` should grow patterns for these three constructs at the same time, so the next one written inline fails the gate rather than a CI job.
DO NOT let this be worked around in the workflow (for instance by pre-creating tables). The point of the probe is that a USER's first boot runs this script.

### STOR-6 — The generator never quotes business identifiers, so a model field named after a reserved word (value, order, group) produces a schema script no engine will run -- conformance Q1, proven at the dialect layer and never exercised at application level

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md W10. Found while unblocking Tier C: the p2-evolve probe declares a field called `value`, and the app it generates has never been able to boot.
**Surface:** `generator/dbconfig/SchemaRealizationEmitter, kernel/storage-dialect`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `NPDevSamples/probes/p2-evolve/v1/Input/model.json`

`SqlDialect.quoteIdentifier` exists, is implemented by all four dialects, and is asserted by conformance vector Q1 (Tier A) with this exact justification in its own javadoc:

    "A user will eventually name a field `order` or `group`."

`SchemaRealizationEmitter` never calls it. Measured: zero occurrences of `quoteIdentifier` anywhere under `NPDevGenerator/generator/src/main`. Business column names go into the emitted DDL raw, so a model whose field is a reserved word produces a script the engine rejects:

    Syntax error in SQL statement "CREATE TABLE IF NOT EXISTS rows (
      id UUID NOT NULL,
      [*]value VARCHAR(255) NOT NULL, ..."; expected "identifier"   [H2 42001]

The probe that found it -- `NPDevSamples/probes/p2-evolve`, which serves Tier C's E1 -- has therefore NEVER booted since it was written. That is why E7 has never been green, and the reason looked like a harness problem until the boot log was read.
WHY IT SURVIVED THIS LONG
The same shape as STOR-4 and STOR-5, a third time: the capability is correct at the layer that owns it and is never consulted by the layer that emits. Q1 passes on all four engines because it asks the DIALECT to quote a string. No corpus app happens to use a reserved word for a business field -- the canary uses title/priority/status -- so nothing downstream ever exercised the path.
It is MEDIUM rather than HIGH only because it fails loudly at first boot, on every engine, rather than silently. A user hits it the moment they model a field called `value`, `order`, `group`, `user` or `key` -- which is not an exotic thing to do.

### STOR-7 — A text column plays three roles -- payload, key, defaulted -- and only two were ever asked about; MySQL rejected a TEXT DEFAULT and SQL Server could not index the runtime host's own bootstrap tables, so no generated app booted on either engine

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md. Found by CI run 31284450437 -- the first engine-support run in which the guarded-DDL fix (STOR-5) let a schema script get far enough into Flyway to fail on something else.
**Surface:** `kernel/storage-dialect, runtimehost/db, generator/dbconfig`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlServerDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PostgresDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/H2Dialect.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectConformanceTierATest.java`
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/SchemaRealizationEmitter.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/InternalDdlTypes.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaLifecycleExecutor.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/SchemaHistoryStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/MigrationClaimStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/MigrationMarkStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/PendingSchemaAcknowledgmentStore.java`
- `scripts/quality/check-dialect-sites.py`
- `scripts/quality/check-emitted-sql-portability.py`
- `scripts/quality/run-engine-app-proof.py`

Two independent failures, both at Flyway time on first boot, both invisible to every layer below.
A. MySQL 8.4, V1__npdev_schema_realization.sql line 417:

    Error Code : 1101
    BLOB, TEXT, GEOMETRY or JSON column 'state' can't have a default value

`npdev_circuit_breakers.state` is declared `TEXT DEFAULT 'CLOSED'` (and `npdev_tenants .persistence_mode` the same way). MySQL will not put a DEFAULT on unbounded text at all. Neither column is in a key, so STOR-4's `keyableTextColumnType()` fix -- which had already narrowed every KEYED text column to VARCHAR(191) -- never touched them.
B. SQL Server 2022, at `afterMigrate`:

    Column 'metadata_key' in table 'npdev_schema_metadata' is of a type that is invalid for use
    as a key column in an index.

Six `CREATE TABLE` statements are issued by the runtime host ITSELF, inline, in Java, around the migration rather than by it -- npdev_schema_migration_claim, npdev_schema_migration_mark, npdev_schema_pending_ack, npdev_schema_history and npdev_schema_metadata (twice). Every one spelled `id TEXT PRIMARY KEY`. They are not in the `internalTables` catalog, so no amount of work on SchemaRealizationEmitter could ever have reached them; SQL Server renders TEXT as NVARCHAR(MAX), which it cannot index at all.
WHY IT SURVIVED
The same shape as STOR-4 and STOR-5 for the third and fourth time: the capability is right in the layer that owns it and is not consulted by the layer that emits. `keyableTextColumnType()` existed, was conformance-tested at Tier A on all four engines, and answered correctly -- for the one role anyone had thought to ask about. Tier B takes a raw JDBC connection and hand-writes its tables, so it never sees NPDev's own DDL. Only generating, building and BOOTING an app reaches this code, and until STOR-4 and STOR-5 were fixed nothing ever got this far.

### STOR-8 — db.definition.json's `jdbcUrl`/`h2FilePath` could contradict the real connection silently -- a user pointing one at an existing database got no error and a connection somewhere else

**Type:** BUG · **Severity:** MEDIUM · **Status:** DONE (2026-08-09)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md W10. Found while working out why Tier C's E1 measured nothing on h2local -- the obvious fix was "point both versions at the same file", and there is no way to.
**Surface:** `generator/dbconfig/UserDatabaseDefinitionLoader`
**Files:**
- `NPDevGenerator/generator/src/main/java/com/npdev/generator/dbconfig/UserDatabaseDefinitionLoader.java`
- `NPDevGenerator/generator/src/test/java/com/npdev/generator/dbconfig/UserDatabaseDefinitionDeclaredConnectionTest.java`
- `BREAKING.md`

`UserDatabaseDefinitionLoader.load` reads both fields into `UserDatabaseDefinition`:

    text(database, "jdbcUrl"),
    text(database, "h2FilePath"),

and nothing downstream reads either one. `jdbcUrl(definition, identity)` composes the URL for every engine from `identity`, whose data root is always `<workspace>/Build/databases/<appId>` -- appId being the `manifest.json` id, never anything the database block says. So a user who writes an explicit `jdbcUrl` to point at an existing database, or an `h2FilePath` to put the file somewhere else, gets silence: no error, no warning, and a connection to a different database than the one they named.
Not the same defect as an unknown key. An unknown key would at least be visibly unrecognized; these two are in the schema, survive validation, and read as supported.
CORRECTED ON CLOSING: the paragraph above is half wrong, and the correction is in `resolution`. `jdbcUrl` IS consulted -- for H2Server, host and port are parsed out of it. `h2FilePath` is the one that is genuinely read by nothing. Left in place rather than rewritten, because what this record BELIEVED is the reason the first proposed fix would have broken twelve apps.

### STOR-9 — A row lock is a suffix on three engines and a table hint on SQL Server, and three sites spelled the suffix inline -- so every app's FIRST boot on SQL Server died taking the migration lock, after the schema had already realized correctly

**Type:** BUG · **Severity:** HIGH · **Status:** DONE (2026-08-08)
**Verification:** VERIFIED_LIVE
**Source:** storage/OPEN_ITEMS_PLAN.md. Found by CI run 31285509636 -- the first run in which STOR-7's fix let SQL Server complete schema realization AND afterMigrate, so the next thing it did was fail.
**Surface:** `kernel/storage-dialect, runtimehost/db, adapters/circuit-postgres`
**Files:**
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/SqlServerDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/PostgresDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/MySqlDialect.java`
- `NPDevKernel/kernel/src/main/java/com/npdev/kernel/storage/sql/H2Dialect.java`
- `NPDevKernel/kernel/src/test/java/com/npdev/kernel/storage/sql/DialectConformanceTierATest.java`
- `NPDevKernel/adapters/circuit-postgres/src/main/java/com/npdev/adapters/circuit/jdbc/JdbcCircuitBreakerStateStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/MigrationClaimStore.java`
- `NPDevRuntimeHost/src/main/java/com/finalexec/db/JdbcBusinessConceptStore.java`
- `scripts/quality/check-dialect-sites.py`

SQL Server 2022, at boot, immediately after a clean migration:

    Caused by: java.lang.IllegalStateException: Failed to claim the migration lock
    Caused by: com.microsoft.sqlserver.jdbc.SQLServerException:
        Line 1: FOR UPDATE clause allowed only for DECLARE CURSOR.

Postgres, H2 and MySQL all write `SELECT ... WHERE ... FOR UPDATE`. T-SQL has no `FOR UPDATE`
outside a cursor at all; its equivalent is a TABLE HINT, and the hint goes BEFORE the `WHERE`:

    SELECT instance_id FROM t WITH (UPDLOCK, ROWLOCK) WHERE claim_key = ?

So this could never have been a suffix method. It is the same shape as `rowLimited` -- an idiom
that is a suffix on three engines and a different POSITION on the fourth -- which the dialect's own
javadoc had already recorded as the reason `rowLimit()` throws on SQL Server. The lesson was
written down and the next instance still went in inline.

THREE sites, and the two that were not in the reported stack trace matter more than the one that
was:

  MigrationClaimStore.claimH2          the migration lock. Every app, every boot, first thing.
  JdbcBusinessConceptStore             findByIdForUpdate -- the read-then-persist race guard on
                                       EVERY business write that goes through the concept gateway.
  JdbcCircuitBreakerStateStore         recordFailure. The module is named `circuit-postgres`, but
                                       NpdevRuntimeModeConfig binds it for ANY
                                       npdev.storage.mode=jdbc, MySQL and SQL Server included.

The first one fails loudly at boot. The other two would have failed later, on a write and on a
capability failure respectively -- the second of which only runs when something else has already
gone wrong.

</details>

