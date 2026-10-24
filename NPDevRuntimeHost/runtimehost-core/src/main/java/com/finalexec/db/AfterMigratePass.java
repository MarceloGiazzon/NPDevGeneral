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
 * CARVE-2 (2026-10-20): the 4-argument {@code afterMigrate} step (post-Flyway: metadata table,
 * ownership, backfill, unique constraints, fingerprint write), moved verbatim out of
 * {@link SchemaLifecycleExecutor} -- pure mechanical split, no logic change.
 */
final class AfterMigratePass {
    private final SchemaLifecycleExecutor host;

    AfterMigratePass(SchemaLifecycleExecutor host) {
        this.host = host;
    }

    void afterMigrate(DataSource dataSource, SchemaManifest manifest, String storedAtBootStart, boolean fingerprintChanged) {
        // STOR-32 (boundary B7): direct-call tests that never run {@code migrate}/{@code beforeMigrate}
        // still journal their steps under a key of their own (production boots already have one).
        host.ensureBootId();
        // B8 (Wave 2 package 2.1, boundary-lift 2026-09-02): record ownership of every manifest-
        // declared, now-live business table IMMEDIATELY -- before BackfillPass/UniqueConstraintPass
        // below run, either of which can still refuse and fail this boot. A table this boot's
        // flyway.migrate() (already run by the time afterMigrate is called) just created is owned
        // regardless of whether a LATER pass in the same boot then refuses; the fingerprint write
        // further down stays gated behind those passes on purpose (a refusal must leave it stale so
        // the next boot re-attempts), but that reasoning is specific to the fingerprint and does not
        // apply to ownership. Recording it only at the end (the old behaviour) meant a mid-boot
        // refusal right after a fresh CREATE TABLE left that brand-new table with no ownership
        // record at all -- a table without a record, which is exactly the bug B8 exists to prevent
        // (a record without a table, the reverse case, is harmless and self-corrects -- see
        // ownedTablesJson's live-table intersection).
        // STOR-32 (boundary B7): the ownership-recording family -- ownership, column-identity, and
        // (below) the metadata/fingerprint/snapshot writes -- is ONE journalled step. Committed right
        // after the first write group so a backfill/unique refusal later in the SAME boot can already
        // enumerate it; the metadata block re-confirms it.
        BootResidueJournal.started(dataSource, host.bootId, null,
                BootResidueJournal.LifecycleStep.OWNERSHIP_RECORDING, null);
        recordOwnershipForLiveManifestTables(dataSource, manifest);
        // REG-209 (B1 lift, package P7): persist this boot's model-declared uids against the columns
        // they are currently bound to -- on EVERY successful pass, including one that changed nothing,
        // so the very first post-uid boot leaves a baseline a FUTURE hand-edited rename can be
        // resolved against. A model with no uids declared is a no-op (ColumnIdentityStore.record
        // checks this itself before touching the database at all).
        ColumnIdentityStore.record(dataSource, manifest);
        // REG-305: the same baseline for concept uids, so a concept renamed by its uid alone resolves later.
        TableIdentityStore.record(dataSource, manifest);
        BootResidueJournal.committed(dataSource, host.bootId,
                BootResidueJournal.LifecycleStep.OWNERSHIP_RECORDING);
        // LNCH-1 remediation R2 (F1): required-field backfill/refusal enforcement lives HERE, at the
        // single call site every boot path crosses, gated on a fingerprint mismatch. Before R2 it was
        // scattered across five beforeMigrate branches (safe-additive/rename-resolved only) and was
        // therefore SILENTLY SKIPPED whenever the same upgrade also carried an acknowledged destructive
        // item (surgical-destruction / whole-wipe paths) -- a new required field then landed permanently
        // nullable with NULL legacy rows. Running it here fixes that for every path.
        //
        // Placement rationale (do not "improve"): addBackfillAndTightenColumn begins with
        // ADD COLUMN IF NOT EXISTS, so it is indifferent to whether the R__ additive migration (which
        // flyway.migrate() just ran) already added the column. It MUST run BEFORE applyUniqueConstraints
        // (a new unique may include the new required column) and BEFORE the fingerprint write below (a
        // refusal must leave the fingerprint stale so the next boot re-attempts). It must NOT run on a
        // fingerprint-MATCH boot: a legacy app that converged with an old-bug nullable-but-required
        // column must not suddenly refuse on a routine restart (healing legacy drift is out of scope).
        if (fingerprintChanged) {
            // STOR-32 (boundary B7): the required-field backfill pass, journalled only when it
            // actually runs (a fingerprint-match boot must not journal a step that never started).
            BootResidueJournal.started(dataSource, host.bootId, null,
                    BootResidueJournal.LifecycleStep.REQUIRED_FIELD_BACKFILL, null);
            BackfillPass.applyRequiredFieldBackfills(dataSource, manifest, storedAtBootStart, null);
            BootResidueJournal.committed(dataSource, host.bootId,
                    BootResidueJournal.LifecycleStep.REQUIRED_FIELD_BACKFILL);
        }
        // LNCH-1 P5 (5.1): runs on every boot, after flyway.migrate() has already applied the R__
        // additive-columns migration, so a unique constraint declared alongside a brand-new column
        // always finds that column already present. Deliberately BEFORE the fingerprint write below
        // -- a refusal here (dirty data violating a newly-declared constraint) must leave the stored
        // fingerprint stale, so the next boot re-attempts instead of silently accepting the drift.
        // STOR-32 (boundary B7): the unique-constraint tightening pass, journalled on every boot (it
        // always runs -- it is only the backfill that is fingerprint-gated).
        BootResidueJournal.started(dataSource, host.bootId, null,
                BootResidueJournal.LifecycleStep.UNIQUE_TIGHTENING, null);
        UniqueConstraintPass.applyUniqueConstraints(dataSource, manifest);
        BootResidueJournal.committed(dataSource, host.bootId,
                BootResidueJournal.LifecycleStep.UNIQUE_TIGHTENING);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.active().guardedCreateTable(METADATA_TABLE,
                            "CREATE TABLE " + METADATA_TABLE
                            + " (metadata_key " + InternalDdlTypes.keyText() + " PRIMARY KEY, "
                            + "metadata_value " + InternalDdlTypes.text() + " NOT NULL, "
                            + "updated_at_ms BIGINT NOT NULL)")
            )) {
                statement.executeUpdate();
            }
            upsertMetadata(connection, FINGERPRINT_KEY, manifest.schemaFingerprint());
            // LNCH-1-B7: record which business tables THIS build owns, on the same lifecycle as the
            // fingerprint. A later build that no longer declares one of them can then prove the
            // orphaned table is a dropped CONCEPT (NPDev created it) rather than a table someone
            // created by hand in the same schema -- which is what makes acting on it safe.
            // B8 2.1: recordOwnershipForLiveManifestTables above already wrote this once, earlier in
            // this same boot -- this second write is a cheap, idempotent refresh (harmless: same
            // union/intersect computation) that also catches any live-table change BackfillPass/
            // UniqueConstraintPass themselves made in between, so the ownership set never lags a full
            // boot behind reality.
            upsertMetadata(connection, OWNED_TABLES_KEY, ownedTablesJson(connection, dataSource, manifest));
            // B5-A (boundary-lift 2026-09-02, package 2.3): record a full desired-schema snapshot for
            // THIS fingerprint, in the same connection block as the fingerprint write itself -- so a
            // later boot that finds this fingerprint via npdev_schema_history (REG-8 Trigger C) can
            // also always find its matching snapshot. See SchemaAheadAnalysis for the read side.
            SchemaSnapshotStore.writeSnapshot(connection, manifest.schemaFingerprint(), DesiredSchemaFactory.fromManifest(manifest));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed storing schema fingerprint", exception);
        }
        // STOR-32 (boundary B7): the ownership-recording family's metadata/fingerprint/snapshot half
        // finished -- re-confirming the step (idempotent; the earlier write already covered a
        // backfill/unique refusal mid-boot).
        BootResidueJournal.committed(dataSource, host.bootId,
                BootResidueJournal.LifecycleStep.OWNERSHIP_RECORDING);
        // REG-27 (fixes REG-8 Trigger C's fresh-install false-negative). A genuinely fresh install
        // records its fingerprint ONLY in npdev_schema_metadata above -- beforeMigrate's blank-
        // fingerprint branch returns without writing history, and no other pass runs. That left a
        // build whose fingerprint was reached by fresh install (rather than by a recorded migration)
        // invisible to databaseMigratedPastThisBuild (Trigger C), which asks "has THIS build's
        // fingerprint ever been reached before?" by consulting npdev_schema_history. So the register's
        // OWN canonical example -- original (fresh-installed) build N, N+1 drops a column, roll back to
        // N -- was not actually refused, because N had no history row. Record the initial realization
        // as an APPLIED history point too, so every fingerprint the database has genuinely been at is
        // visible to Trigger C. Scoped strictly to the fresh-install path (nothing stored before this
        // boot): every mismatch/destructive path already wrote its own APPLIED row inside beforeMigrate,
        // and a no-op fingerprint-MATCH boot needs none (the fingerprint was recorded when first
        // reached). Safe here and ONLY here -- never in beforeMigrate's blank branch -- because this
        // runs AFTER flyway.migrate(), so self-bootstrapping npdev_schema_history cannot trip Flyway's
        // "non-empty schema, no history table" baseline check (the REG-7.2/7.3 self-bootstrap-ordering
        // bug). writeAppliedHistoryRow self-ensures the table and never lets a history-write failure
        // block the boot.
        if (storedAtBootStart == null || storedAtBootStart.isBlank()) {
            SchemaHistoryStore.writeAppliedHistoryRow(dataSource, null, manifest.schemaFingerprint(), null);
        }
    }
}
