package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.RenameResolution;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.PluginExecutionPolicyEvaluator;
import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.SupportDiagnosticsService;
import com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessPool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.npdev.kernel.storage.sql.SqlDialects;
import com.finalexec.boundary.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static com.finalexec.db.SchemaLifecycleExecutor.*;

/**
 * CARVE-2 (2026-10-20): the {@code beforeMigrate} decision, moved verbatim out of
 * {@link SchemaLifecycleExecutor} (pure mechanical split; no logic change). The executor keeps
 * {@code beforeMigrate} and the per-boot state; this class reads and writes that state through
 * {@code host}.
 */
final class BeforeMigrateDecisionPass {
    private final SchemaLifecycleExecutor host;

    BeforeMigrateDecisionPass(SchemaLifecycleExecutor host) {
        this.host = host;
    }

    DestructiveRecreation beforeMigrateDecision(DataSource dataSource, SchemaManifest manifest) {
        String stored = readFingerprint(dataSource);
        if (stored == null || stored.isBlank()) {
            // A genuinely fresh boot: nothing stored, nothing to fast-forward FROM, and -- critically
            // -- nothing may touch the database here at all. VERIFIED LIVE (real boot rehearsal,
            // simple-user-registry-h2local): an earlier draft called MigrationMarkStore.findMatching
            // unconditionally at the top of this method, ahead of this branch. Its self-bootstrapped
            // CREATE TABLE IF NOT EXISTS ran on the FIRST-EVER boot, before flyway.migrate() got a
            // chance to run -- which made Flyway see a non-empty "public" schema with no
            // flyway_schema_history table and refuse outright ("Found non-empty schema(s) 'public' but
            // no schema history table"). The mark-done check below MUST stay strictly after this
            // branch's early return.
            System.out.println("NPDev schema lifecycle: no stored schema fingerprint found; initializing schema realization.");
            return DestructiveRecreation.none();
        }
        // REG-7.2 (D2/D4): an operator-recorded "mark done" for THIS build's target fingerprint takes
        // priority over both branches below -- the GeneXus "the schema is already at this fingerprint;
        // don't try to migrate to it" semantic. Checked ahead of the fingerprint-match fast path too,
        // so it also short-circuits REG-8's Trigger C (P4): a mark is the operator's authoritative word
        // that this build legitimately owns this fingerprint, so the schema-ahead-of-build detector
        // must never second-guess it.
        Optional<MigrationMarkStore.Mark> mark = MigrationMarkStore.findMatching(dataSource, stored, manifest.schemaFingerprint());
        if (mark.isPresent()) {
            host.applyMigrationMark(dataSource, stored, manifest, mark.get());
            return DestructiveRecreation.none();
        }
        if (stored.equals(manifest.schemaFingerprint())) {
            // LNCH-1 remediation R3 (F4): schema-ahead-of-build detector. A matching fingerprint is
            // the fast-path "nothing changed" signal, but it is ALSO what an OLDER jar sees after a
            // NEWER build migrated this database and was then rolled back to this jar -- the stored
            // fingerprint still matches this (old) build, so without this guard the app would boot
            // "clean" against a schema whose columns have been renamed away or dropped, then fail at
            // runtime with no diagnostics. Verify the live schema actually still contains what THIS
            // build requires before trusting the match.
            List<String> missing = findSchemaAheadMissingColumns(dataSource, manifest);
            if (!missing.isEmpty()) {
                SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "REFUSED");
                // STOR-32 (boundary B7): B7:refusal_residue appended when the journal can answer it --
                // a refusal must never fail because its residue could not be read (withResidue degrades
                // to the bare message, exactly as before this package).
                throw new IllegalStateException(SchemaRefusal.withResidue(
                        "Stored schema fingerprint matches this build, but the live "
                        + "database is missing column(s) this build requires: " + missing + ". This usually means "
                        + "a NEWER build already migrated this database (e.g. an upgrade was attempted and then "
                        + "rolled back to this older jar). Roll forward to the newer build, or restore from "
                        + "backup/snapshot -- see docs/SCHEMA_EVOLUTION.md#refusals-and-rollback.",
                        dataSource, host.bootId));
            }
            System.out.println("NPDev schema lifecycle: stored schema fingerprint matches generated schema fingerprint; no destructive recreation required.");
            return DestructiveRecreation.none();
        }
        // LNCH-1 P2 (2.4/2.5 ordering): concept (table) renames MUST be attempted before classify()
        // is ever invoked against a mismatched fingerprint. classify() only enumerates tables that
        // are declared under their manifest-CURRENT name (manifest.businessTableColumns().keySet());
        // a table that was renamed live-DB-side is otherwise completely invisible to it (VERIFIED:
        // see SchemaLifecycleExecutorTableRenameBlindSpotTest for the pre-fix behavior this closes).
        // Idempotent-by-check and a no-op when manifest.businessTableRenames() is empty or nothing
        // matches, so it is always safe to attempt eagerly here, ahead of every other step.
        // Item 5, SUPPORT_FEATURES_PLAN_2026-08-26 (B11): the capability to know an engine commits
        // DDL implicitly already existed (SqlDialects.active().supports(DDL_IN_TRANSACTION), used by
        // ConversionHookRunner's narrower per-hook MIXES_DDL_PATTERN warning below) but was never
        // surfaced for the general migration path -- the structural passes below (renames/relax/
        // tighten, and classify()'s own additive/destructive follow-on) issue real DDL on H2 (the
        // DEFAULT development engine) and MySQL just as much as a conversion hook's convert.sql does.
        // One line, once per migration run (not per statement) -- a warning printed on every ALTER is
        // a warning nobody reads.
        if (!SqlDialects.active().supports(com.npdev.kernel.storage.sql.StorageCapability.DDL_IN_TRANSACTION)) {
            System.out.println("NPDev schema lifecycle: WARNING -- '" + SqlDialects.active().name()
                    + "' commits DDL implicitly (no transactional DDL). If this migration fails "
                    + "partway, any schema change already applied will stay applied even though its "
                    + "data changes roll back -- the database can end up in a state this build's model "
                    + "does not fully describe. Postgres and SQL Server roll back fully. Run `npdev why "
                    + "B11` for the full explanation and workaround.");
        }
        // STOR-32 (boundary B7): the in-place rename/widening pass family (table renames, column
        // renames, nullability relaxation, platform-column tightening -- and the RENAME/TYPE branches'
        // re-attempts further down) is ONE journalled lifecycle step: write-before-execute, committed
        // once the top group completes (the branch re-runs re-read the LIVE DatabaseMetaData and no-op
        // when already converged, so the marker stays true across them, and every refusal point below
        // -- schema-ahead, bond-column, token -- sits AFTER this group).
        BootResidueJournal.started(dataSource, host.bootId, null,
                BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN, null);
        host.attemptInPlaceTableRenames(dataSource, manifest);
        // LNCH-1 Phase 7 rehearsal fix: field-level renames MUST also be attempted before classify()
        // for the same reason table renames are (above) -- see attemptInPlaceRenames' own javadoc for
        // the live, real-data-loss bug this closes. A table with a declared rename AND an unrelated,
        // separately-acknowledged destructive drop used to classify straight to DESTRUCTIVE, skipping
        // the (perfectly safe) rename entirely and silently orphaning the old column's data. Calling
        // this here, before classify() ever runs, means the rename is already applied by the time
        // classify() looks at the table, regardless of what else on that table still needs the
        // destructive path.
        host.attemptInPlaceRenames(dataSource, manifest);
        // Found live while auditing LNCH-1's own remaining gaps (2026-07-19), not part of any
        // phase's original plan: a field going from required to optional (same name, same type)
        // changes the schema fingerprint (UserDatabaseDefinitionLoader#fingerprintInputs includes
        // "required=" per field) but classify() only compares column NAME sets and TYPES -- it has
        // no nullability awareness at all. The live NOT NULL constraint was therefore NEVER relaxed:
        // classify() saw identical columns/types, returned SAFE_ADDITIVE, and the boot "succeeded"
        // while silently leaving the column impossible to actually write null into, contradicting
        // the model's own declared optionality. Symmetric to applyRequiredFieldBackfills (Phase 5)
        // but the other direction, and -- like renames -- always unconditionally safe (relaxing a
        // constraint never loses data), so it runs here, unconditionally, before classify() ever
        // sees the table, the same way renames do.
        host.relaxNoLongerRequiredColumns(dataSource, manifest);
        // LNCH-1 T1 (finding T-B1), Half B. Runs immediately after the relax pass, unconditionally and
        // for the same reason it does: restoring NOT NULL on a platform column whose default is fixed
        // and known can never lose data, and deferring it would let the next boot re-relax what this
        // one repaired. Half A (the exclusion inside relaxNoLongerRequiredColumns) only stops NEW
        // damage; this is what repairs the apps an earlier build already loosened. No-op -- and writes
        // no history row -- once every platform column is strict. Proven by scenarios 28 and 28b.
        host.tightenPlatformColumns(dataSource, manifest);
        // STOR-32 (boundary B7): the in-place family above completed -- committed BEFORE the
        // schema-ahead / bond-column / conversion-hook / token checks below, so each of those
        // refusals can enumerate it. (The RENAME/TYPE branches re-attempt later; their re-runs are
        // the same step, already marked.)
        BootResidueJournal.committed(dataSource, host.bootId,
                BootResidueJournal.LifecycleStep.IN_PLACE_RENAME_WIDEN);

        // REG-8 Trigger C (D4): closes the schema-ahead detector's known blind spot -- a newer build
        // that PURELY dropped a column leaves no live residue for Trigger A/B to see, so an older jar
        // rolled back onto the migrated database used to classify SAFE_ADDITIVE (the dropped column's
        // sibling difference is additive-eligible and no unexplained extra column exists to signal a
        // rename) and silently re-add it empty via the R__ migration -- the register's own practical
        // example. Runs BEFORE classify() so it guards every resolution (SAFE_ADDITIVE,
        // RENAME_DETECTED, TYPE_CHANGE_DETECTED, DESTRUCTIVE) uniformly, not just the one case that
        // motivated it. A MANUALLY_MARKED_DONE mark for this exact fingerprint already short-circuited
        // above (D4: a mark is checked before this branch is ever reached), so it can never trip this.
        Optional<SchemaHistoryStore.HistoryPoint> aheadOfBuild = SchemaHistoryStore.databaseMigratedPastThisBuild(dataSource, manifest);
        if (aheadOfBuild.isPresent()) {
            // A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift"): before refusing outright, check whether
            // every difference the ahead state actually introduces is TOLERABLE for this build to run
            // past -- an ADDITIVE-only difference this build's own projected reads/writes
            // (JdbcBusinessConceptStore, PostgresPersistenceCapabilityAdapter -- neither issues a bare
            // `select *` any more) never touch in the first place. This only ever WIDENS what may
            // proceed; a genuinely incompatible difference still refuses below, unchanged, with the
            // SAME itemized REG-198 analysis this refusal has always carried.
            // Never let a failure COMPUTING the verdict crash the boot with an opaque exception -- the
            // existing refusal below is already safe and well-tested; a verdict this class cannot even
            // compute degrades to that refusal rather than to an unrelated stack trace (the same
            // never-throws discipline SchemaAheadAnalysis#render already uses one line below).
            SchemaCompatibilityVerdict.Verdict verdict;
            try {
                verdict = SchemaCompatibilityVerdict.assess(dataSource, manifest);
            } catch (RuntimeException failure) {
                System.out.println("NPDev schema lifecycle: could not compute the schema-ahead compatibility "
                        + "verdict (" + failure.getMessage() + ") -- falling back to refusing, safely, exactly "
                        + "as before this check existed.");
                verdict = new SchemaCompatibilityVerdict.Verdict(java.util.List.of(
                        new SchemaCompatibilityVerdict.Difference(null, null,
                                SchemaCompatibilityVerdict.Tolerance.INCOMPATIBLE,
                                "verdict computation itself failed: " + failure.getMessage())));
            }
            // QUAL-55 (permanent, never-throws diagnostic): an integration test on this exact branch showed
            // intermittent, unexplained verdict flips across full-suite runs; 246 repeated-run data points
            // ruled out every hypothesis tried (a swallowed SQLException, a stale SqlDialects.active()
            // singleton, plain concurrent-H2 contention) without identifying the true mechanism -- see
            // ledger/items/QUAL-55.yml. Rather than chase it further blind, this logs the exact verdict inputs
            // every time this branch runs, so the next NATURAL occurrence in an ordinary gate run is caught
            // with a diagnosis already attached instead of needing another dedicated repro session.
            try {
                StringBuilder diagnostic = new StringBuilder("[QUAL-55-DIAG] thread=")
                        .append(Thread.currentThread().getName())
                        .append(" at=").append(Instant.now())
                        .append(" aheadFp=").append(aheadOfBuild.get().toFingerprint())
                        .append(" thisFp=").append(manifest.schemaFingerprint())
                        .append(" activeDialect=").append(SqlDialects.active().getClass().getSimpleName())
                        .append(" compatible=").append(verdict.compatible())
                        .append(" differences=[");
                for (SchemaCompatibilityVerdict.Difference difference : verdict.differences()) {
                    diagnostic.append(difference.describe()).append("; ");
                }
                diagnostic.append(']');
                System.out.println(diagnostic);
            } catch (RuntimeException ignored) {
                // diagnostic only -- must never affect the boot
            }
            if (verdict.compatible()) {
                SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null,
                        "PROCEED_SCHEMA_AHEAD_COMPATIBLE");
                if (verdict.differences().isEmpty()) {
                    System.out.println("NPDev schema lifecycle: this database was migrated past this build "
                            + "(now at fingerprint " + aheadOfBuild.get().toFingerprint() + "), but introduces no "
                            + "structural difference from this build's own manifest -- booting normally (B5 "
                            + "package A3, REAL_LIFT_PLAN_2026-09-03.md).");
                } else {
                    System.out.println("NPDev schema lifecycle: this database was migrated past this build (now "
                            + "at fingerprint " + aheadOfBuild.get().toFingerprint() + ") -- booting anyway (B5 "
                            + "package A3, REAL_LIFT_PLAN_2026-09-03.md): every difference is additive and this "
                            + "build's own reads/writes never touch it:");
                    for (SchemaCompatibilityVerdict.Difference difference : verdict.differences()) {
                        System.out.println("  - " + difference.describe());
                    }
                }
                return DestructiveRecreation.none();
            }
            SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "REFUSED");
            // Restores the detail a prior rewrite of this message lost: the exact ahead fingerprint
            // (an operator needs it to tell which later build touched this database) and the
            // mark-done escape hatch (applyMigrationMark/MigrationMarkStore) -- both asserted by
            // SchemaLifecycleExecutorDatabaseMigratedPastBuildTest, which caught the loss.
            // B5:schema_ahead_detected (2026-08-25 W2.3, docs/ACCEPTED_BOUNDARIES.md): BoundaryViolation
            // has no separate `code` field, only `boundaryId` -- the code is carried as a message
            // prefix, the same convention B2/B4 use, so it survives into both the boot log and the
            // 503 JSON body BoundaryViolationResponse writes (BoundaryViolation.toJson()'s "message").
            throw new BoundaryBootException(new BoundaryViolation("B5", "boot",
                    SchemaRefusal.withResidue(
                            "B5:schema_ahead_detected:This database was migrated PAST this build: it is already at fingerprint "
                            + aheadOfBuild.get().toFingerprint() + ", a later state than this build's own "
                            + manifest.schemaFingerprint() + ". Downgrade is not supported. Two ways forward, "
                            + "both deliberate: (1) forward-fix this build's model.json to match the database "
                            + "(the usual case -- you checked out an older model.json, e.g. `git checkout "
                            + "HEAD~3 -- model.json`, and the database was never rolled back with it), or "
                            + "(2) restore a database snapshot from before the later change, if you have one, "
                            + "then boot this build again. NPDev will not reconstruct the older schema shape "
                            + "for you -- if this ahead state is intentional, record an operator mark to "
                            + "fast-forward past this check. Run `npdev why B5` for the full explanation.\n"
                            + "Exactly what differs from this build's own schema (same `npdev db verify` "
                            + "vocabulary; `npdev db schema-ahead --report` renders this without booting):\n"
                            + SchemaAheadAnalysis.render(dataSource, manifest, aheadOfBuild.get().toFingerprint()),
                            dataSource, host.bootId),
                    Instant.now()));
        }

        // SER-P4.8: classify's COLUMN-level decision is now ClassificationReducer over the live diff
        // (switched inside classify; the P4.1 self-check that guarded this is now tautological and gone).
        SchemaChangeClassification classification = host.classify(dataSource, manifest);
        SchemaChangeClassification classificationForFallthrough = classification;
        // SER-P6.3 (Surface 1): write + print the operator-facing impact report for EVERY upgrade boot --
        // this is the single point all outcomes (safe / rename / widen / destructive) pass through. The
        // read-only diff's DESTRUCTIVE items are not changed by the in-place passes below, so the report's
        // verdict is final here; the exact acknowledgment token (when destructive) is computed post-in-place
        // at the decision point below and shown in the refusal message. Fully swallowed; text reused there.
        String impactReportText = ImpactReportWriter.writeAndPrint(dataSource, manifest, stored, null);
        if (classification == SchemaChangeClassification.SAFE_ADDITIVE) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " but every difference is a new non-bond column on an "
                    + "already-existing table; skipping destructive recreation (handled by the additive repeatable migration).");
            // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
            SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
            return DestructiveRecreation.safeAdditiveOutcome();
        }
        if (classification == SchemaChangeClassification.RENAME_DETECTED) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " -- classified as RENAME_DETECTED (a declared renamedFrom "
                    + "matches a column the live database still has under its old name). Attempting in-place "
                    + "ALTER TABLE ... RENAME COLUMN for every table whose diff is fully explained by declared "
                    + "renames, preserving all data.");
            host.attemptInPlaceRenames(dataSource, manifest);
            SchemaChangeClassification residual = host.classify(dataSource, manifest);
            classificationForFallthrough = residual;
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place field rename(s) fully resolved the fingerprint "
                        + "diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
                // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                return DestructiveRecreation.safeAdditiveOutcome();
            }
            if (residual == SchemaChangeClassification.TYPE_CHANGE_DETECTED) {
                // LNCH-1 P3 (3.3): a rename may be combined with a type change on the same column
                // (or an unrelated shared column on the same table). Renames already ran above, so
                // this sees the NEW column name(s) -- resolving both operations in one boot.
                System.out.println("NPDev schema lifecycle: residual classification after field renames is "
                        + "TYPE_CHANGE_DETECTED -- attempting in-place safe-widening ALTER COLUMN statements "
                        + "(LNCH-1 Phase 3), per-table all-or-nothing.");
                host.attemptInPlaceTypeWidenings(dataSource, manifest);
                residual = host.classify(dataSource, manifest);
                classificationForFallthrough = residual;
                if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                    System.out.println("NPDev schema lifecycle: in-place rename(s) and type widening(s) fully "
                            + "resolved the fingerprint diff (residual classification SAFE_ADDITIVE); skipping "
                            + "destructive recreation.");
                    // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                    SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                    return DestructiveRecreation.safeAdditiveOutcome();
                }
            }
            System.out.println("NPDev schema lifecycle: in-place rename/widening pass left a residual "
                    + "classification of " + residual + " (the diff was not fully explained by declared renames "
                    + "and safe type widenings -- e.g. a narrowing, an incomparable type change, or an unresolved "
                    + "column); falling through to destructive recreation as the safety net.");
        } else if (classification == SchemaChangeClassification.TYPE_CHANGE_DETECTED) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " -- classified as TYPE_CHANGE_DETECTED (an existing column's "
                    + "declared SQL type changed). Attempting in-place ALTER COLUMN statements for every table "
                    + "whose type diff is fully explained by safe widenings (LNCH-1 Phase 3), per-table "
                    + "all-or-nothing.");
            // LNCH-1 Phase 7 fix (found by the row-6 proof-matrix test, guardrail 10): a rename
            // combined with a type change on the SAME column makes classify()'s TOP-LEVEL verdict
            // TYPE_CHANGE_DETECTED directly (see the Phase 1 fix: classify() escalates an explained
            // rename pair to TYPE_CHANGE_DETECTED when the old column's actual type differs from the
            // new column's expected type) -- classification never reports RENAME_DETECTED for that
            // table in this case, so the RENAME_DETECTED branch above (which attempts renames first)
            // is never entered. Without this call, attemptInPlaceTypeWidenings looks for the column
            // under its NEW name, which does not exist yet (still under the old name), finds nothing
            // to widen, and the whole pass incorrectly falls through to destructive recreation even
            // though both the rename and the widening are individually safe and fully explained.
            // attemptInPlaceRenames is self-guarding per table (a no-op wherever no rename is
            // declared or a table's diff isn't fully explained by declared renames), so calling it
            // unconditionally here is exactly as safe as the existing unconditional
            // attemptInPlaceTableRenames call above.
            host.attemptInPlaceRenames(dataSource, manifest);
            SchemaChangeClassification residual = host.classify(dataSource, manifest);
            classificationForFallthrough = residual;
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place field rename(s) fully resolved the "
                        + "fingerprint diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
                // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                return DestructiveRecreation.safeAdditiveOutcome();
            }
            if (residual == SchemaChangeClassification.TYPE_CHANGE_DETECTED) {
                host.attemptInPlaceTypeWidenings(dataSource, manifest);
                residual = host.classify(dataSource, manifest);
                classificationForFallthrough = residual;
                if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                    System.out.println("NPDev schema lifecycle: in-place rename(s) and type widening(s) fully "
                            + "resolved the fingerprint diff (residual classification SAFE_ADDITIVE); skipping "
                            + "destructive recreation.");
                    // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                    SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                    return DestructiveRecreation.safeAdditiveOutcome();
                }
            }
            System.out.println("NPDev schema lifecycle: in-place rename/widening pass left a residual classification of "
                    + residual + " (at least one type-differing column on some table was a narrowing or "
                    + "incomparable change -- per-table all-or-nothing means nothing on that table was applied); "
                    + "falling through to destructive recreation as the safety net.");
        }

        // SER-P7.3 (Phase 7, the freedom pillar): run operator-authored conversion hooks against the
        // current residual diff BEFORE the bond-column refusal and SchemaDeltaReport below -- a hook's
        // convert SQL performs its claimed conversion (including destructive ones) itself, so by the
        // time SchemaDeltaReport re-computes fresh below, whatever the hook resolved has simply
        // vanished from the residual diff and needs no acknowledgment token (rule 6; see
        // ConversionHookRunner's class javadoc for why no special-casing is needed downstream).
        // Idempotent no-op (and writes no history) when nothing is currently unresolved.
        //
        // STOR-33 (boundary B14, package P5): the PRE-HOOK report is captured before the run, exactly
        // as SchemaDeltaReport.generate would have itemized the live state before the hooks changed
        // it -- the same report the -ImpactOnly / GET .../impact preview computes, so the sanctioned
        // set, the audit rows and the token-required refusal all describe what an operator actually
        // saw before this boot. One extra live introspection on the opt-in hook path (rare). The
        // plan's cheaper "reuse run()'s unresolved keys" fallback was deliberately not taken, because
        // the TOKEN is computed over this report's stable strings and a reconstructed key set cannot
        // reproduce a pre-hook Unknown item -- an unsatisfiable token is worse than the diff.
        SchemaDeltaReport preHookReport = SchemaDeltaReport.generate(dataSource, manifest);

        // STOR-33 (boundary B14, package P5), measured correction to the plan's step-4 placement: the
        // token-required gate fires HERE, BEFORE the hooks run, not after them. A refusal that fires
        // after the sanctioned item already vanished (the hook already dropped the column) is a gate
        // that gates nothing -- the "plausible wrong answer" failure mode this repo exists to refuse.
        // The pending sanctions are computable pre-run from the SAME claims index the runner selects
        // against and the pre-hook report, so nothing executes before the authorization question is
        // settled. Under audit-only (the default) this whole block is skipped and the plan's step-4
        // ordering is moot: hooks run, then the audit rows are written.
        if (resolveSanctionedDestructiveMode() == SanctionedDestructiveMode.TOKEN_REQUIRED) {
            List<SanctionedDestruction> pendingSanctions = pendingSanctions(preHookReport);
            if (!pendingSanctions.isEmpty()) {
                // B14:sanctioned_destruction_requires_token: computed over the PRE-HOOK report -- the
                // exact itemization an -ImpactOnly preview shows. Same two channels as every other
                // destructive refusal: the static manifest field or a ControlPanel pending
                // acknowledgment.
                String sanctionedToken = DestructiveAckToken.compute(manifest.schemaFingerprint(),
                        preHookReport.stableStrings());
                String providedToken = manifest.destructiveAcknowledgment() == null
                        ? "" : manifest.destructiveAcknowledgment().trim();
                boolean staticMatches = !providedToken.isBlank() && providedToken.equals(sanctionedToken);
                PendingSchemaAcknowledgmentStore.PendingAcknowledgment pendingAcknowledgment =
                        PendingSchemaAcknowledgmentStore.findMatching(dataSource, manifest.schemaFingerprint(), sanctionedToken)
                                .orElse(null);
                if (!staticMatches && pendingAcknowledgment == null) {
                    SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(),
                            classificationForFallthrough, preHookReport,
                            providedToken.isBlank() ? null : providedToken, "REFUSED");
                    throw new IllegalStateException(SchemaRefusal.withResidue(
                            "B14:sanctioned_destruction_requires_token: this boot's conversion hook(s) would sanction "
                                    + pendingSanctions.size() + " destructive item(s) that still require an explicit, itemized "
                                    + "acknowledgment token under -D" + SANCTIONED_MODE_PROPERTY + "=token-required: "
                                    + describeSanctioned(pendingSanctions)
                                    + ". Expected acknowledgment token: " + sanctionedToken
                                    + ". Set the generated manifest's destructiveAcknowledgment to this token, or submit "
                                    + "it via the ControlPanel schema-migration screen on the currently running app, to "
                                    + "proceed -- see docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes. Refused "
                                    + "BEFORE the hooks ran -- nothing has been executed or applied.",
                            dataSource, host.bootId));
                }
                if (pendingAcknowledgment != null) {
                    DestructiveRecreationPass.consumePendingAcknowledgmentIfAny(dataSource, pendingAcknowledgment);
                }
            }
        }

        ConversionHookRunner.HookRunOutcome hookOutcome = ConversionHookRunner.run(dataSource, manifest,
                (label, outcome, details) ->
                        SchemaHistoryStore.insertRawHistoryRow(dataSource, stored, manifest.schemaFingerprint(), label, details, outcome),
                new ConversionHookRunner.JavaHookRuntimeContext(
                        host.pluginIpcChildProcessPool, host.runtimePluginAdapterRegistry, host.pluginExecutionPolicyEvaluator),
                host.bootId);
        host.conversionHooksAppliedLastDecision = hookOutcome.applied();

        // LNCH-1 P5 (5.3): a required bond/FK field missing from an existing, populated table is
        // intercepted HERE, before SchemaDeltaReport ever runs -- independently re-derived per
        // table (not relying on classify()'s short-circuit-to-DESTRUCTIVE aggregate value), so it
        // is caught with a dedicated, itemized refusal instead of falling into SchemaDeltaReport's
        // generic UNKNOWN item kind.
        BackfillPass.refuseIfRequiredBondColumnMissing(dataSource, manifest, stored, classificationForFallthrough, host.bootId);

        // LNCH-1 Phase 4 (task 4.3): everything below replaces the old blanket whole-schema-wipe
        // fallback with itemized, surgical destruction wherever the residual diff cleanly supports
        // it. SchemaDeltaReport independently re-introspects the live database (it does not trust
        // classify()'s classification value beyond what is used here for logging/history purposes).
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);

        // STOR-33 (boundary B14, package P5): the sanctioned set -- every PRE-HOOK destructive item a
        // selected hook claimed AND that the post-hook report no longer contains. Those are the items
        // authoring the hook sanctioned (ADR-0008: authoring IS the acknowledgment) that used to
        // vanish invisibly. Only the three named destructive kinds qualify: an Unknown can never be
        // claimed (it has no item key a hook could claim), and a NEEDS_HOOK backfill add is not
        // destructive, so neither is ever gated. Equivalent to the plan's own formula (pre-run
        // unresolved keys, intersected with the destructive kinds, minus whatever the post-hook report
        // still contains) but computed over the pre-hook REPORT, which is the population the operator
        // saw and the token is computed over.
        List<SanctionedDestruction> sanctioned = new ArrayList<>();
        if (!hookOutcome.claimedItemToHookId().isEmpty()) {
            Set<String> postHookStableStrings = new LinkedHashSet<>(report.stableStrings());
            for (SchemaDeltaItem item : preHookReport.items()) {
                if (!isSanctionableDestructiveItem(item)) {
                    continue;
                }
                String stable = item.stableString();
                if (postHookStableStrings.contains(stable)) {
                    continue;
                }
                String hookId = hookOutcome.claimedItemToHookId().get(stable);
                if (hookId != null) {
                    sanctioned.add(new SanctionedDestruction(stable, hookId));
                }
            }
        }
        if (!sanctioned.isEmpty()) {
            // STOR-33 (B14), measured correction: the token-required gate no longer lives here. It
            // fired AFTER the hooks ran, by which point the sanctioned item had already vanished --
            // a gate that gates nothing. It now fires BEFORE {@code ConversionHookRunner.run}, where
            // the pending sanctions are computed from the claims index and the pre-hook report and
            // the refusal can truthfully say "nothing has been executed or applied". By the time this
            // block is reached, a token-required boot has either already passed that gate or is
            // audit-only by default -- so what remains is the audit only.
            // The audit row per sanctioned item -- the post-boot face of "what was destroyed under
            // sanction, and which hook claimed it" (part (b) of the lift). Written in BOTH modes;
            // a broken history write never blocks.
            for (SanctionedDestruction sanctionedItem : sanctioned) {
                SchemaHistoryStore.insertRawHistoryRow(dataSource, stored, manifest.schemaFingerprint(),
                        "SANCTIONED_DESTRUCTION",
                        List.of("item=" + sanctionedItem.stableString(), "hook=" + sanctionedItem.hookId()), "APPLIED");
            }
            System.out.println("NPDev schema lifecycle: " + sanctioned.size() + " destructive item(s) sanctioned by "
                    + "conversion hook(s) (authoring the hook is the acknowledgment; audit row written; "
                    + hookOutcome.preRunUnresolvedKeys().size() + " unresolved item(s) existed pre-hook): "
                    + describeSanctioned(sanctioned));
        }

        // SER-P7.3 (rule 6): a conversion hook can fully resolve every residual destructive item
        // between classify() above and this fresh re-introspection -- when that happens there is
        // nothing left requiring an acknowledgment token, exactly as if the diff had been SAFE_ADDITIVE
        // all along ("authoring the hook IS the acknowledgment"). Before Phase 7 this branch was
        // unreachable (nothing could change the live schema between classify() and here), so the
        // token-required refusal below never needed to consider an empty report.
        if (report.isEmpty()) {
            System.out.println("NPDev schema lifecycle: every residual destructive item was resolved by a "
                    + "conversion hook; no acknowledgment token required. Fingerprint changed from " + stored
                    + " to " + manifest.schemaFingerprint() + ".");
            SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classificationForFallthrough);
            return DestructiveRecreation.safeAdditiveOutcome();
        }
        String expectedToken = DestructiveAckToken.compute(manifest.schemaFingerprint(), report.stableStrings());
        String providedToken = manifest.destructiveAcknowledgment() == null
                ? "" : manifest.destructiveAcknowledgment().trim();
        boolean staticTokenMatches = !providedToken.isBlank() && providedToken.equals(expectedToken);
        // LNCH-1 P6 (task 6.2a): the ControlPanel pre-authorization flow -- an operator reviews the
        // plan and submits {toFingerprint, ackToken} on the CURRENTLY RUNNING (old) app before this
        // (new) app's jar is even deployed, since a refused boot has no server left to serve a
        // ControlPanel page on (see plan.md §2.6 answer 2's RATIFIED amendment). Either source
        // authorizing the SAME expected token is sufficient -- neither alone is more trusted than
        // the other, they are just two different submission channels for the identical proof.
        PendingSchemaAcknowledgmentStore.PendingAcknowledgment pendingAcknowledgment =
                PendingSchemaAcknowledgmentStore.findMatching(dataSource, manifest.schemaFingerprint(), expectedToken)
                        .orElse(null);
        boolean pendingAckMatches = pendingAcknowledgment != null;
        boolean tokenMatches = staticTokenMatches || pendingAckMatches;
        // The token actually used to authorize this pass, for audit/history purposes: prefer the
        // static manifest field when it matched (existing Phase 4 behavior, unchanged); otherwise,
        // when only the pending-ack table authorized it, that row's ack_token IS expectedToken by
        // construction (the query matched on it) -- record that.
        String effectiveToken = staticTokenMatches ? providedToken : (pendingAckMatches ? expectedToken : providedToken);
        boolean hasUnknown = !report.hasOnlyNamedDestructiveKinds();
        boolean blanketAuthorized = manifest.destructiveAllowed();

        if (!tokenMatches && !blanketAuthorized) {
            SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classificationForFallthrough,
                    report, providedToken.isBlank() ? null : providedToken, "REFUSED");
            // STOR-32 (boundary B7): B7:refusal_residue appended -- this is the refusal that happens
            // AFTER the in-place and conversion-hook steps committed, so the residue it names is the
            // exact answer to "what did the boot that just refused already commit?".
            throw new IllegalStateException(SchemaRefusal.withResidue(
                    (impactReportText != null ? impactReportText + "\n" : "")
                            + "Schema fingerprint changed from " + stored + " to "
                            + manifest.schemaFingerprint() + " and includes destructive change(s) requiring an explicit, "
                            + "itemized acknowledgment (LNCH-1 Phase 4). Itemized destructive report: "
                            + report.stableStrings() + ". Expected acknowledgment token: " + expectedToken
                            + ". Set the generated manifest's destructiveAcknowledgment to this token, or submit it via "
                            + "the ControlPanel schema-migration screen on the currently running app (LNCH-1 Phase 6), to "
                            + "proceed -- see docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes."
                            + DestructiveRecreationPass.agreementCheckSuffix(manifest, report),
                    dataSource, host.bootId));
        }

        // LNCH-1 hardening X4.4 (ratified 2026-07-20). Dropping a CONCEPT destroys an entire table's
        // worth of data, and unlike a column drop it cannot be partially reasoned about after the
        // fact. The deprecated blanket flag is too coarse an instrument to authorize that: it is set
        // once, at authoring time, and then silently authorizes every future concept drop for the
        // life of the app. A DROP_TABLE therefore always requires the itemized token (either channel
        // -- static manifest field or ControlPanel pending acknowledgment), while blanket-authorized
        // column drops and type narrowings continue to work as before.
        //
        // LNCH-1 closeout C1 (finding C-B1, ratified 2026-07-20) extends that principle to its
        // logical end: ANY pass that will destroy an entire table's worth of data requires the
        // itemized token. X4.4 established that destroying ONE table's data needs a token -- but the
        // whole-schema recreation below destroys EVERY table's data and was still reachable on the
        // blanket flag alone, which inverted the very principle X4.4 set. The most destructive
        // operation in the system had the weakest authorization requirement. Both reasons are
        // collected in ONE gate rather than as two sequential near-duplicate `if (!tokenMatches)`
        // blocks, so the refusal can name every reason that applies at once.
        if (!tokenMatches) {
            List<String> droppedTables = new ArrayList<>();
            for (SchemaDeltaItem item : report.items()) {
                if (item instanceof DropTable dropTable) {
                    droppedTables.add(dropTable.table());
                }
            }
            List<String> unexplainableItems = new ArrayList<>();
            for (SchemaDeltaItem item : report.items()) {
                if (item instanceof SchemaDeltaItem.Unknown) {
                    unexplainableItems.add(item.stableString());
                }
            }
            if (!droppedTables.isEmpty() || hasUnknown) {
                SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classificationForFallthrough,
                        report, providedToken.isBlank() ? null : providedToken, "REFUSED");
                StringBuilder reasons = new StringBuilder();
                if (!droppedTables.isEmpty()) {
                    reasons.append(" (1) It includes the DROP of one or more whole concept table(s): ")
                            .append(droppedTables)
                            .append(". Dropping a concept destroys that table's entire contents (LNCH-1 hardening "
                                    + "X4.4).");
                }
                if (hasUnknown) {
                    reasons.append(droppedTables.isEmpty() ? " (1)" : " (2)")
                            .append(" This change cannot be executed item by item -- the delta report contains "
                                    + "item(s) that cannot be explained as a column drop, a type narrowing or a "
                                    + "declared rename: ").append(unexplainableItems)
                            .append(". Proceeding would therefore DROP AND RECREATE EVERY TABLE IN THIS APP, "
                                    + "destroying ALL of its data (LNCH-1 closeout C1).");
                }
                throw new IllegalStateException(SchemaRefusal.withResidue(
                        "Schema fingerprint changed from " + stored + " to "
                                + manifest.schemaFingerprint() + ", and this change would destroy at least one whole "
                                + "table's worth of data, which requires an explicit, itemized acknowledgment token."
                                + reasons
                                + " The deprecated blanket 'destructiveAllowed' flag does NOT authorize this -- it is set "
                                + "once at authoring time and would then silently authorize every future whole-table "
                                + "destruction for the life of the app. It authorizes only surgical column drops and type "
                                + "narrowings. Itemized destructive report: " + report.stableStrings()
                                + ". Expected acknowledgment token: " + expectedToken
                                + ". Set the generated manifest's destructiveAcknowledgment to this token, or submit it via "
                                + "the ControlPanel schema-migration screen on the currently running app, to proceed -- see "
                                + "docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes."
                                + DestructiveRecreationPass.agreementCheckSuffix(manifest, report),
                        dataSource, host.bootId));
            }
        }

        // LNCH-1 hardening X1 (finding X-B1, a CRITICAL regression): AUTHORIZATION and EXECUTION
        // STRATEGY are two separate concerns, and tangling them is what caused real data loss.
        //   - Authorization (the refusal branch above) decides WHETHER destruction may happen at all:
        //     an itemized token, or the deprecated blanket flag.
        //   - The report's CONTENT decides HOW it is executed: if every item is one of the three
        //     named, surgically-executable kinds, the surgical path can do exactly what the report
        //     says and nothing more. Only a genuinely unexplainable (UNKNOWN) item forces the
        //     whole-schema recreation.
        // Before this fix the surgical branch was additionally gated on `tokenMatches`, so a pass
        // authorized by the blanket flag ALONE fell through to executeWholeSchemaWipe even when the
        // report contained only named items. Because that wipe drops exactly the tables the NEW
        // manifest lists (see executeWholeSchemaWipe), a dropped concept's orphaned table -- the one
        // thing the upgrade was meant to remove -- is NOT in that list and SURVIVED, while every
        // still-modelled concept's table and data was destroyed. With `allowDestructiveRecreate:
        // true` being the shape of every shipped app definition, that was the default path.
        // Narrowing the wipe to the UNKNOWN case is strictly less destructive in every case it now
        // handles, so no app can be harmed by it. See scenario 24 / 24b in the proof matrix.
        if (!hasUnknown) {
            if (tokenMatches) {
                System.out.println("NPDev schema lifecycle: destructive change acknowledged by itemized token"
                        + (pendingAckMatches && !staticTokenMatches ? " (via a ControlPanel pending acknowledgment)" : "")
                        + "; executing surgically (only the affected table(s)/column(s), LNCH-1 Phase 4). Report: "
                        + report.stableStrings());
            } else {
                System.out.println("NPDev schema lifecycle: DEPRECATION WARNING -- this destructive schema change was "
                        + "authorized by the blanket 'destructiveAllowed' flag alone (no itemized acknowledgment token "
                        + "matched). Executing surgically: " + report.stableStrings()
                        + ". Only these item(s) will be applied; no other table is touched. The blanket flag is "
                        + "deprecated; switch to the itemized acknowledgment token (expected: " + expectedToken
                        + ") -- see docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes.");
            }
            // STOR-32 (boundary B7): the destructive execution is its own journalled step -- the
            // least idempotent one there is, so a boot that refuses AFTER it (or whose retry re-runs
            // it) must be named by the residue. Started before, committed only on return.
            BootResidueJournal.started(dataSource, host.bootId, null,
                    BootResidueJournal.LifecycleStep.DESTRUCTIVE_RECREATION, null);
            DestructiveRecreation result = DestructiveRecreationPass.executeSurgicalDestruction(dataSource, manifest, stored,
                    classificationForFallthrough, report, tokenMatches ? effectiveToken : null);
            BootResidueJournal.committed(dataSource, host.bootId,
                    BootResidueJournal.LifecycleStep.DESTRUCTIVE_RECREATION);
            // Only a token-authorized pass may consume somebody's pending acknowledgment row -- a
            // blanket-authorized pass did not use it and must leave it available.
            if (tokenMatches) {
                DestructiveRecreationPass.consumePendingAcknowledgmentIfAny(dataSource, pendingAcknowledgment);
            }
            return result;
        }

        List<String> unknownItems = new ArrayList<>();
        for (SchemaDeltaItem item : report.items()) {
            if (item instanceof SchemaDeltaItem.Unknown) {
                unknownItems.add(item.stableString());
            }
        }
        // LNCH-1 closeout C1: reaching here PROVES tokenMatches is true. The gate above refuses every
        // !tokenMatches pass for which hasUnknown holds, and hasUnknown is exactly the condition that
        // routes a pass here (the !hasUnknown branch returned above). So the wipe is now a
        // token-authorized path only -- the `tokenMatches ? ... : "the deprecated blanket flag"`
        // conditionals that used to live in this block described a state that can no longer occur.
        System.out.println("NPDev schema lifecycle: WARNING -- WHOLE-SCHEMA RECREATION STARTING. The delta report "
                + "includes UNKNOWN item(s) the surgical path cannot safely explain: " + unknownItems
                + ". Because the change cannot be executed item by item, EVERY manifest-listed table is about to be "
                + "dropped and recreated: ALL DATA IN THIS APP'S TABLES WILL BE LOST (a pre-drop snapshot is written "
                + "first -- see runtime-data/schema-snapshot-before-drop/). Authorized by an itemized acknowledgment "
                + "token. Full report: " + report.stableStrings());
        // STOR-32 (boundary B7): same journalled destructive step around the whole-schema wipe -- the
        // most destructive pass in the system, explicitly named NOT idempotent in the residue.
        BootResidueJournal.started(dataSource, host.bootId, null,
                BootResidueJournal.LifecycleStep.DESTRUCTIVE_RECREATION, null);
        DestructiveRecreation result = DestructiveRecreationPass.executeWholeSchemaWipe(dataSource, manifest, stored,
                classificationForFallthrough, report, effectiveToken);
        BootResidueJournal.committed(dataSource, host.bootId,
                BootResidueJournal.LifecycleStep.DESTRUCTIVE_RECREATION);
        DestructiveRecreationPass.consumePendingAcknowledgmentIfAny(dataSource, pendingAcknowledgment);
        return result;
    }

    // STOR-33 (boundary B14, POSTURAL_LIFT_PLAN_2026-09-07.md package P5): the sanctioned-destruction
    // mode. Default 'audit-only' is EXACTLY today's behaviour -- authoring the conversion hook remains
    // the acknowledgment (ADR-0008, no conditions); the audit rows and impact section are additive.
    // 'token-required' additionally demands the itemized token (over the pre-hook report) for every
    // destructive item a hook claims, through the same two channels as every other destructive refusal.
    private static final String SANCTIONED_MODE_PROPERTY = "npdev.schema.destructive.sanctioned";

    private enum SanctionedDestructiveMode {
        AUDIT_ONLY, TOKEN_REQUIRED
    }

    /** Resolver shaped exactly like {@code ConversionHookRunner.resolveMixedDdlVerifyMode}: an
     *  unrecognized operator-set value logs and falls back to the default -- a typo must not silently
     *  widen or narrow what refuses. */
    private static SanctionedDestructiveMode resolveSanctionedDestructiveMode() {
        String configured = System.getProperty(SANCTIONED_MODE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return SanctionedDestructiveMode.AUDIT_ONLY;
        }
        String trimmed = configured.trim();
        if ("audit-only".equalsIgnoreCase(trimmed)) {
            return SanctionedDestructiveMode.AUDIT_ONLY;
        }
        if ("token-required".equalsIgnoreCase(trimmed)) {
            return SanctionedDestructiveMode.TOKEN_REQUIRED;
        }
        System.out.println("NPDev schema lifecycle: ignoring unrecognized " + SANCTIONED_MODE_PROPERTY
                + "='" + configured + "' (expected audit-only|token-required); using the default 'audit-only'.");
        return SanctionedDestructiveMode.AUDIT_ONLY;
    }

    /** STOR-33 (package P5): the three named, surgically-executable destructive kinds are the only
     *  items a hook can sanction -- {@link SchemaDeltaItem.Unknown} has no item key a hook could
     *  claim, and a NEEDS_HOOK backfill add is not destructive. Mirrors {@link SchemaDeltaReport}'s
     *  four-kind vocabulary rather than a magic prefix list. */
    private static boolean isSanctionableDestructiveItem(SchemaDeltaItem item) {
        return item instanceof SchemaDeltaItem.DropColumn
                || item instanceof SchemaDeltaItem.DropTable
                || item instanceof SchemaDeltaItem.NarrowType;
    }

    /** STOR-33 (package P5): "{@code <stable string>} (hook '{@code id}')" per sanctioned item, joined
     *  with ", " -- shared by the refusal and the boot log so they can never disagree. */
    private static String describeSanctioned(List<SanctionedDestruction> sanctioned) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sanctioned.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            SanctionedDestruction s = sanctioned.get(i);
            out.append(s.stableString()).append(" (hook '").append(s.hookId()).append("')");
        }
        return out.toString();
    }

    /** STOR-33 (package P5): the sanctions a token-required boot WOULD have executed, computed BEFORE
     *  any hook runs -- every destructive item of the pre-hook report claimed by a hook on the
     *  classpath (the same claims index {@code ConversionHookRunner} selects against). This is what
     *  the pre-run B14 gate gates; a classpath-scan failure degrades to "nothing pending" (the
     *  post-run audit + rows still stand, only the optional gate is skipped). */
    private static List<SanctionedDestruction> pendingSanctions(SchemaDeltaReport preHookReport) {
        Map<String, String> claims;
        try {
            claims = ConversionHookRunner.loadClaimIndex();
        } catch (Throwable ignored) {
            claims = Map.of();
        }
        List<SanctionedDestruction> out = new ArrayList<>();
        for (SchemaDeltaItem item : preHookReport.items()) {
            if (!isSanctionableDestructiveItem(item)) {
                continue;
            }
            String hookId = claims.get(item.stableString());
            if (hookId != null) {
                out.add(new SanctionedDestruction(item.stableString(), hookId));
            }
        }
        return out;
    }
}
