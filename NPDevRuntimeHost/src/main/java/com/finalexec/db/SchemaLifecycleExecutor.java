package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.RenameResolution;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropColumn;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.DropTable;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem.NarrowType;
import com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization;
import com.npdev.dsl.v1.schemaevolution.TypeChangeMatrix;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
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
import java.util.UUID;

/**
 * ColumnFacts directive (REG-6, 2026-07-22): any NEW pass added to this executor MUST answer
 * per-column questions (platform-managed? additive-eligible? required-by-model? bond? renamed?
 * literal default?) by reading {@code columnFactsFor(manifest, table)} -- never by re-deriving
 * them from the raw manifest maps. Every REG-6-class bug (T-B1, T-B2) was one pass re-deriving
 * column semantics and disagreeing with another pass. The existing set-algebra passes are exempt
 * by recorded decision (docs/NPDEV_OPEN_ITEMS_REGISTER.md section 1.6): they perform set
 * operations, not semantic re-derivation, and rewriting them adds risk to the most-fixed
 * subsystem without closing any gap. Guards: SchemaLifecycleExecutorColumnFactsTest (this
 * module) + PlatformColumnContractTest (generator module).
 */
@Component
public final class SchemaLifecycleExecutor implements FlywayMigrationStrategy {
    private static final String METADATA_TABLE = "npdev_schema_metadata";
    private static final String FINGERPRINT_KEY = "schemaFingerprint";
    /** LNCH-1-B7: metadata key holding the JSON array of business tables the last successful boot
     * owned -- the ownership evidence that makes acting on an orphaned table safe. */
    private static final String OWNED_TABLES_KEY = "ownedBusinessTables";
    private static final String SCHEMA_REALIZATION_LOCATION = "classpath:db/schema-realization";
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog");
    /**
     * Columns the platform itself puts on every business table, never a user-modelled field.
     * VERIFIED against {@code SchemaRealizationEmitter#fullColumnNames} (which appends {@code id}
     * when the concept declares no id field, then {@code version}, {@code row_version},
     * {@code tenant_id}). Deliberately a second copy rather than a shared constant: the emitter lives
     * in the generator module, which the RuntimeHost template does not depend on. Used by
     * {@link #findSchemaAheadMissingColumns}'s Trigger B so a platform column can never be mistaken
     * for the leftover of a rename by a newer build.
     *
     * <p><b>PINNED (LNCH-1 closeout C2, finding C-D1):</b> this hand-copy is held equal to the
     * emitter's real appended set by {@code PlatformColumnContractTest} in the GENERATOR test source
     * set ({@code NPDevGenerator/generator/src/test/java/com/npdev/generator/dbconfig/}), which
     * parses this very declaration out of this file as text. Before that test existed, an emitter
     * that grew a fifth platform column would have left Trigger B treating it as an unexplained
     * extra column — refusing a HEALTHY boot. Keep the declaration in the
     * {@code PLATFORM_MANAGED_COLUMNS = Set.of("a", "b", ...)} shape the test's regex expects.
     */
    private static final Set<String> PLATFORM_MANAGED_COLUMNS =
            Set.of("id", "version", "row_version", "tenant_id");

    /**
     * The platform-managed columns that carry a FIXED, known default and are therefore repairable by
     * {@link #tightenPlatformColumns}, in a deterministic order so the log line and the history row
     * read the same way on every run.
     *
     * <p>Deliberately NOT the whole of {@link #PLATFORM_MANAGED_COLUMNS}: {@code id} is excluded
     * because it has no platform default (it is the primary key, hence already {@code NOT NULL}, and
     * a concept may declare its own id field). VERIFIED against the emitter's fresh {@code CREATE
     * TABLE} lines ({@code SchemaRealizationEmitter:370-377}), which emit exactly
     * {@code version BIGINT NOT NULL DEFAULT 0}, {@code row_version BIGINT NOT NULL DEFAULT 0} and
     * {@code tenant_id VARCHAR(120) NOT NULL DEFAULT 'default'}. Co-located with (and REG-6-guarded
     * against) {@link #PLATFORM_MANAGED_COLUMNS} so the two cannot silently drift.
     */
    private static final List<String> REPAIRABLE_PLATFORM_COLUMNS =
            List.of("version", "row_version", "tenant_id");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * REG-6 (2026-07-21): one projection of "what is this column?" that the passes query instead of
     * each re-deriving the same facts from the manifest maps + the platform-column sets. Computed
     * once per (table, column) from {@link #columnFactsFor}. This is deliberately a read-only view
     * over the manifest and the platform-column sets — it introduces NO new source of truth, so
     * routing a pass through it is behavior-preserving by construction (the proof matrices confirm
     * it). {@code isBond} in particular was previously re-derived inline in three places as
     * "required && !additive-eligible"; it lives here now.
     *
     * <p>{@code isPrimaryKey} is deliberately NOT here: it is a LIVE-database fact
     * ({@link #readPrimaryKeyColumns}), not a manifest fact, so it stays a per-connection lookup at
     * its one call site rather than being faked into a manifest projection.
     */
    record ColumnFacts(
            String column,
            boolean platformManaged,
            boolean repairablePlatformColumn,
            boolean additiveEligible,
            boolean requiredByModel,
            String declaredType,
            String renamedFrom,
            String literalDefaultJson
    ) {
        /**
         * A required column that is NOT additive-eligible is — after LNCH-1 P5 (5.3) — necessarily a
         * required bond/FK field (the only remaining reason a required column fails additive
         * eligibility; a plain required field is always additive-eligible). A bond has no
         * literal-default backfill in v1, so this is what {@link #refuseIfRequiredBondColumnMissing}
         * keys on.
         */
        boolean bond() {
            return requiredByModel && !additiveEligible;
        }
    }

    /**
     * REG-6: the repairable platform columns (a fixed default this class knows how to backfill) are a
     * strict SUBSET of {@link #PLATFORM_MANAGED_COLUMNS} — {@code id} is excluded because it is the
     * primary key with no platform default. Deriving the membership relationship here (rather than
     * eyeballing two independently-typed literals) is the concrete fix for REG-6's "two overlapping
     * platform-column sets with different contents" drift: a change to {@link #PLATFORM_MANAGED_COLUMNS}
     * that forgot to update {@link #REPAIRABLE_PLATFORM_COLUMNS} (or vice-versa) now trips this
     * class-load assertion immediately instead of silently mis-classifying a column at runtime. The
     * ordered {@code REPAIRABLE_PLATFORM_COLUMNS} list is kept (its order is load-line-significant),
     * but its CONTENT is now guarded against the managed set.
     */
    private static void assertPlatformColumnSetsAgree() {
        Set<String> repairable = new LinkedHashSet<>(REPAIRABLE_PLATFORM_COLUMNS);
        if (!PLATFORM_MANAGED_COLUMNS.containsAll(repairable)) {
            throw new IllegalStateException("REG-6 platform-column drift: REPAIRABLE_PLATFORM_COLUMNS "
                    + repairable + " is not a subset of PLATFORM_MANAGED_COLUMNS " + PLATFORM_MANAGED_COLUMNS);
        }
        Set<String> managedMinusId = new LinkedHashSet<>(PLATFORM_MANAGED_COLUMNS);
        managedMinusId.remove("id");
        if (!managedMinusId.equals(repairable)) {
            throw new IllegalStateException("REG-6 platform-column drift: PLATFORM_MANAGED_COLUMNS minus 'id' "
                    + managedMinusId + " must equal REPAIRABLE_PLATFORM_COLUMNS " + repairable
                    + " (every non-id platform column carries a fixed default and is repairable)");
        }
    }

    static {
        assertPlatformColumnSetsAgree();
    }

    static boolean isPlatformManagedColumn(String column) {
        return column != null && PLATFORM_MANAGED_COLUMNS.contains(column.toLowerCase(Locale.ROOT));
    }

    /** REG-6: the platform-managed column names, as the single set the passes subtract from live
     * columns (replaces direct references to {@link #PLATFORM_MANAGED_COLUMNS} at call sites). */
    static Set<String> platformManagedColumnNames() {
        return PLATFORM_MANAGED_COLUMNS;
    }

    /**
     * REG-6: compute the per-column projection for one table, once, from the manifest + platform
     * sets. Callers ask this map "what is column X?" instead of re-deriving from four different
     * manifest maps and two static sets at each use site.
     */
    static Map<String, ColumnFacts> columnFactsFor(SchemaManifest manifest, String table) {
        List<String> allColumns = manifest.businessTableColumns().getOrDefault(table, List.of());
        Set<String> additive = new LinkedHashSet<>(manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
        Set<String> required = new LinkedHashSet<>(manifest.businessTableRequiredColumns().getOrDefault(table, List.of()));
        Map<String, String> types = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
        Map<String, String> renamed = manifest.businessTableRenamedColumns().getOrDefault(table, Map.of());
        Map<String, String> defaults = manifest.businessTableColumnDefaultLiterals().getOrDefault(table, Map.of());
        Map<String, ColumnFacts> facts = new LinkedHashMap<>();
        for (String column : allColumns) {
            String lower = column.toLowerCase(Locale.ROOT);
            facts.put(column, new ColumnFacts(
                    column,
                    PLATFORM_MANAGED_COLUMNS.contains(lower),
                    REPAIRABLE_PLATFORM_COLUMNS.contains(column),
                    additive.contains(column),
                    required.contains(column),
                    types.get(column),
                    renamed.get(column),
                    defaults.get(column)
            ));
        }
        return facts;
    }

    /**
     * LNCH-1 Phase 4 (task 4.4). Self-bootstrapped exactly like {@link #METADATA_TABLE} -- a plain
     * {@code CREATE TABLE IF NOT EXISTS} this class issues itself, NOT routed through the
     * generator's {@code internalTables} catalog (confirmed: {@code npdev_schema_metadata}, which
     * this table sits alongside, is not part of that catalog either). Every fingerprint-mismatch
     * pass through {@link #beforeMigrate} -- safe (additive/rename/widening) or destructive --
     * leaves exactly one row here.
     */
    private static final String HISTORY_TABLE = "npdev_schema_history";

    @Override
    public void migrate(Flyway flyway) {
        migrate(flyway, loadManifest());
    }

    /**
     * LNCH-1 Phase 7 (row 16 of the proof matrix: an InMemory-storage app's model change must
     * no-op the executor entirely). Package-private overload taking the manifest as a parameter
     * so the {@code manifest == null || !manifest.physicalDatabase()} guard above is directly
     * unit-testable against a real (zero-migration) {@link Flyway} instance without needing to
     * fake {@link #loadManifest}'s fixed classpath resource lookup -- see
     * {@code SchemaLifecycleExecutorProofMatrixTest}. Behavior is unchanged: {@link #migrate(Flyway)}
     * is just {@code migrate(flyway, loadManifest())}.
     */
    void migrate(Flyway flyway, SchemaManifest manifest) {
        Configuration configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null) {
            flyway.migrate();
            return;
        }
        if (manifest == null || !manifest.physicalDatabase()) {
            flyway.migrate();
            return;
        }
        // REG-7.1 (D5): ExternallyManaged means NPDev does not own this database's schema -- it must
        // NEVER issue schema DDL against it. Branch here, before any stored-fingerprint read or
        // flyway.migrate() call: this mode does not register/run the schema-realization migrations at
        // all (VERIFIED: the only Flyway location this app is configured with is
        // classpath:db/schema-realization, application.yml's spring.flyway.locations -- so simply
        // never calling flyway.migrate() means Flyway touches nothing, not even its own bookkeeping
        // table). Read-only verification only; deliberately placed ahead of the deprecated-posture
        // NOTICE below since generation-time validation (UserDatabaseDefinitionLoader) already
        // requires allowDestructiveRecreate=false whenever ownership=ExternallyManaged.
        if (manifest.externallyManaged()) {
            verifyExternallyManagedSchemaCompatible(dataSource, manifest);
            return;
        }
        // LNCH-1 hardening X4.3: make the deprecated posture visible on EVERY boot, not only on the
        // day it finally destroys something. Deliberately placed here rather than in beforeMigrate:
        // this method runs once per real boot, while beforeMigrate is driven directly by dozens of
        // unit tests that would otherwise flood their captured output.
        if (manifest.destructiveAllowed()) {
            System.out.println("NPDev schema lifecycle: NOTICE -- this app is configured with the deprecated "
                    + "blanket 'destructiveAllowed' posture (schemaLifecycle.strategy="
                    + "DropAndRecreateOnStructureChange + allowDestructiveRecreate=true). Destructive column "
                    + "drops and type narrowings will proceed WITHOUT an itemized acknowledgment token. That is ALL "
                    + "it authorizes: anything that destroys a whole table's worth of data -- a concept drop, or a "
                    + "diff that cannot be executed item by item -- still requires a token. Recommended: switch to "
                    + "strategy=KeepExistingIfCompatible with allowDestructiveRecreate=false and use "
                    + "Build-NpdevApp.ps1 -PlanOnly / -AcknowledgeDestructive -- see docs/SCHEMA_EVOLUTION.md.");
        }
        // LNCH-1 remediation R2 (F1): capture the stored fingerprint BEFORE beforeMigrate runs, so we
        // know whether this boot is an upgrade (fingerprint mismatch) independently of whichever
        // beforeMigrate branch ran -- including the surgical-destruction and whole-wipe paths, which
        // previously bypassed required-field enforcement entirely. beforeMigrate never writes the
        // fingerprint (only afterMigrate does, at its very end) and no path drops npdev_schema_metadata,
        // so this read is the true pre-boot value even after a destructive beforeMigrate.
        String storedAtBootStart = readFingerprint(dataSource);
        // REG-7.3 (D3): claim the single migration slot for this boot BEFORE any schema work, so a
        // second instance racing against the same database refuses loudly instead of interleaving
        // renames/widenings/drops. ONLY attempted when a fingerprint is already stored -- i.e. this is
        // an upgrade/repeat boot against an already-initialized database, never a genuinely virgin
        // one. VERIFIED LIVE (the identical mechanism REG-7.2's fix needed): claiming unconditionally
        // would self-bootstrap npdev_schema_migration_claim before flyway.migrate() ever runs on a
        // fresh schema, which makes Flyway see a non-empty "public" schema with no history table and
        // refuse outright. See MigrationClaimStore's class javadoc for the honest scope of what this
        // does and does not protect.
        MigrationClaimStore.Claim claim = (storedAtBootStart != null && !storedAtBootStart.isBlank())
                ? MigrationClaimStore.claim(dataSource)
                : null;
        try {
            boolean fingerprintChanged = storedAtBootStart != null && !storedAtBootStart.isBlank()
                    && !storedAtBootStart.equals(manifest.schemaFingerprint());
            DestructiveRecreation recreation = beforeMigrate(dataSource, manifest);
            if (recreation.performed()) {
                clearSchemaRealizationHistory(dataSource);
            } else if (recreation.safeAdditive()) {
                // V1's bootstrap SQL is regenerated from the full current model on every generation pass,
                // so its content (and checksum) legitimately changes whenever a column is added even though
                // it must not be re-executed here. repair() reconciles Flyway's recorded checksums with the
                // newly resolved migration content instead of failing validation or re-running V1's CREATE TABLE.
                flyway.repair();
                System.out.println("NPDev schema lifecycle: flyway.repair() reconciled schema-realization checksums for the additive change.");
            }
            flyway.migrate();
            afterMigrate(dataSource, manifest, storedAtBootStart, fingerprintChanged);
        } finally {
            if (claim != null) {
                MigrationClaimStore.release(dataSource, claim.instanceId());
            }
        }
    }

    /**
     * REG-7.1 (D5): the {@code ownership=ExternallyManaged} boot path. Runs on EVERY boot -- there is
     * no "stored fingerprint matches, skip" fast path here, because nothing on this path converges
     * the schema toward the model; there is only ever something to VERIFY, and that check is cheap
     * (live column introspection, no DDL). Package-private so it is directly unit-testable against a
     * real H2 {@link DataSource}, following every other destructive/verification pass in this class.
     */
    void verifyExternallyManagedSchemaCompatible(DataSource dataSource, SchemaManifest manifest) {
        List<String> problems = findExternalSchemaIncompatibilities(dataSource, manifest);
        String fromFingerprint = readFingerprint(dataSource);
        if (!problems.isEmpty()) {
            writeHistoryRow(dataSource, fromFingerprint, manifest.schemaFingerprint(), null, null, null, "EXTERNAL_REFUSED");
            throw new IllegalStateException("This app declares schemaLifecycle.ownership=ExternallyManaged "
                    + "(NPDev does not own this database's schema and will never issue DDL against it), but the "
                    + "live schema cannot serve this build's model. Incompatibilities: " + problems + ". Either "
                    + "alter the external schema by hand to match the model, or fix the model to match the "
                    + "external schema -- see docs/SCHEMA_EVOLUTION.md#external-unmanaged-database.");
        }
        writeHistoryRow(dataSource, fromFingerprint, manifest.schemaFingerprint(), null, null, null, "EXTERNAL_VERIFIED");
        System.out.println("NPDev schema lifecycle: ownership=ExternallyManaged -- verified the live schema is "
                + "compatible with this build's model; no schema DDL issued.");
    }

    /**
     * REG-7.1 (D5): the read-only compatibility check itself. Reuses {@link #readActualColumns},
     * {@link #readActualColumnTypes} and {@link #normalizeSqlType} -- the SAME live-introspection and
     * type-comparison plumbing {@link #classify} and {@link #findSchemaAheadMissingColumns} use
     * (REG-6: one notion of "does the live schema match", never a second, drifting one). Returns one
     * itemized, human-readable problem string per missing table, missing column, or incompatible
     * column type; empty when the live schema fully satisfies this build's model.
     */
    private static List<String> findExternalSchemaIncompatibilities(DataSource dataSource, SchemaManifest manifest) {
        List<String> problems = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                Set<String> live = readActualColumns(metadata, table);
                if (live.isEmpty()) {
                    problems.add(table + " (table missing)");
                    continue;
                }
                Map<String, String> expectedTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
                Map<String, String> actualTypes = readActualColumnTypes(metadata, table);
                for (String column : entry.getValue()) {
                    String normalized = column.toLowerCase(Locale.ROOT);
                    if (!live.contains(normalized)) {
                        problems.add(table + "." + column + " (column missing)");
                        continue;
                    }
                    String expectedType = normalizeSqlType(expectedTypes.get(column));
                    String actualType = normalizeSqlType(actualTypes.get(column));
                    if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
                        problems.add(table + "." + column + " (type mismatch: model expects " + expectedType
                                + ", live schema has " + actualType + ")");
                    }
                }
            }
        } catch (SQLException exception) {
            problems.add("(failed introspecting live schema: " + exception.getMessage() + ")");
        }
        return problems;
    }

    /**
     * REG-7.2: applies an operator-recorded "mark done" -- fast-forwards the stored fingerprint
     * pointer straight to {@code manifest.schemaFingerprint()}, writes a {@code MANUALLY_MARKED_DONE}
     * history row, and consumes the mark. Deliberately does NOT run any rename/relax/tighten/classify/
     * destructive pass: the operator's claim IS that the live schema already matches this build's
     * model, so there is nothing for this executor to converge. (Flyway's own idempotent
     * {@code CREATE TABLE IF NOT EXISTS} / {@code ADD COLUMN IF NOT EXISTS} scripts still run
     * afterward via the normal {@link #migrate(Flyway, SchemaManifest)} flow -- harmless no-ops if the
     * operator's claim holds, a safety net if it does not.)
     */
    private void applyMigrationMark(DataSource dataSource, String stored, SchemaManifest manifest, MigrationMarkStore.Mark mark) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + METADATA_TABLE
                            + " (metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)"
            )) {
                statement.executeUpdate();
            }
            upsertMetadata(connection, FINGERPRINT_KEY, manifest.schemaFingerprint());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed fast-forwarding the schema fingerprint for a manually-marked-done migration", exception);
        }
        writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "MANUALLY_MARKED_DONE");
        MigrationMarkStore.consume(dataSource, mark.id());
        System.out.println("NPDev schema lifecycle: fingerprint manually marked done (operator: " + mark.markedBy()
                + (mark.note() == null || mark.note().isBlank() ? "" : ", note: " + mark.note()) + ") -- fast-forwarded "
                + "the stored schema fingerprint from " + stored + " to " + manifest.schemaFingerprint()
                + " with NO migration passes run.");
    }

    /** Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #classify}/{@link #attemptInPlaceRenames}'s precedent
     * (LNCH-1 Phase 4 -- the destructive-path integration tests drive this method directly).
     *
     * <p>Schema-engine rebuild Phase 3: a thin wrapper around {@link #beforeMigrateDecision} that runs
     * the read-only {@link ShadowParityProbe} alongside the live decision -- snapshot the live schema
     * before, compare the shadow's verdict after (on success OR refusal). The probe is log-only and
     * swallows everything, so this CANNOT change behavior; a refusal is always rethrown unchanged. */
    DestructiveRecreation beforeMigrate(DataSource dataSource, SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema shadowPre = ShadowParityProbe.snapshot(dataSource);
        boolean shadowFingerprintChanged = shadowFingerprintChanged(dataSource, manifest);
        DestructiveRecreation result = null;
        try {
            result = beforeMigrateDecision(dataSource, manifest);
            return result;
        } finally {
            ShadowParityProbe.compareAndLog(shadowPre, manifest, result, shadowFingerprintChanged);
        }
    }

    /** Same upgrade-detection {@code migrate()} uses (stored fingerprint present AND differs): the live
     *  engine only runs structural passes when this is true, so the shadow must mirror the gate or a
     *  fingerprint-match boot (engine no-ops) reads as a spurious divergence. Fail-open on error. */
    private boolean shadowFingerprintChanged(DataSource dataSource, SchemaManifest manifest) {
        try {
            String stored = readFingerprint(dataSource);
            return stored != null && !stored.isBlank() && !stored.equals(manifest.schemaFingerprint());
        } catch (Throwable ignored) {
            return true;
        }
    }

    private DestructiveRecreation beforeMigrateDecision(DataSource dataSource, SchemaManifest manifest) {
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
            applyMigrationMark(dataSource, stored, manifest, mark.get());
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
                writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "REFUSED");
                throw new IllegalStateException("Stored schema fingerprint matches this build, but the live "
                        + "database is missing column(s) this build requires: " + missing + ". This usually means "
                        + "a NEWER build already migrated this database (e.g. an upgrade was attempted and then "
                        + "rolled back to this older jar). Roll forward to the newer build, or restore from "
                        + "backup/snapshot -- see docs/SCHEMA_EVOLUTION.md#refusals-and-rollback.");
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
        attemptInPlaceTableRenames(dataSource, manifest);
        // LNCH-1 Phase 7 rehearsal fix: field-level renames MUST also be attempted before classify()
        // for the same reason table renames are (above) -- see attemptInPlaceRenames' own javadoc for
        // the live, real-data-loss bug this closes. A table with a declared rename AND an unrelated,
        // separately-acknowledged destructive drop used to classify straight to DESTRUCTIVE, skipping
        // the (perfectly safe) rename entirely and silently orphaning the old column's data. Calling
        // this here, before classify() ever runs, means the rename is already applied by the time
        // classify() looks at the table, regardless of what else on that table still needs the
        // destructive path.
        attemptInPlaceRenames(dataSource, manifest);
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
        relaxNoLongerRequiredColumns(dataSource, manifest);
        // LNCH-1 T1 (finding T-B1), Half B. Runs immediately after the relax pass, unconditionally and
        // for the same reason it does: restoring NOT NULL on a platform column whose default is fixed
        // and known can never lose data, and deferring it would let the next boot re-relax what this
        // one repaired. Half A (the exclusion inside relaxNoLongerRequiredColumns) only stops NEW
        // damage; this is what repairs the apps an earlier build already loosened. No-op -- and writes
        // no history row -- once every platform column is strict. Proven by scenarios 28 and 28b.
        tightenPlatformColumns(dataSource, manifest);

        // REG-8 Trigger C (D4): closes the schema-ahead detector's known blind spot -- a newer build
        // that PURELY dropped a column leaves no live residue for Trigger A/B to see, so an older jar
        // rolled back onto the migrated database used to classify SAFE_ADDITIVE (the dropped column's
        // sibling difference is additive-eligible and no unexplained extra column exists to signal a
        // rename) and silently re-add it empty via the R__ migration -- the register's own practical
        // example. Runs BEFORE classify() so it guards every resolution (SAFE_ADDITIVE,
        // RENAME_DETECTED, TYPE_CHANGE_DETECTED, DESTRUCTIVE) uniformly, not just the one case that
        // motivated it. A MANUALLY_MARKED_DONE mark for this exact fingerprint already short-circuited
        // above (D4: a mark is checked before this branch is ever reached), so it can never trip this.
        Optional<HistoryPoint> aheadOfBuild = databaseMigratedPastThisBuild(dataSource, manifest);
        if (aheadOfBuild.isPresent()) {
            writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "REFUSED");
            throw new IllegalStateException("This database was migrated PAST this build. Schema history shows "
                    + "fingerprint " + aheadOfBuild.get().toFingerprint() + " was successfully applied at "
                    + aheadOfBuild.get().appliedAtUtc() + " (epoch ms), newer than this build's own target ("
                    + manifest.schemaFingerprint() + "). Rolling an older build back onto a database a newer "
                    + "build already migrated is unsupported and can silently lose data (REG-8) -- roll forward "
                    + "to the newer build, restore from backup/snapshot, or -- if you deliberately intend this "
                    + "older build to take over -- mark fingerprint " + aheadOfBuild.get().toFingerprint()
                    + " done (see docs/SCHEMA_EVOLUTION.md#marking-a-migration-as-done) and redeploy. See "
                    + "docs/SCHEMA_EVOLUTION.md#refusals-and-rollback.");
        }

        // SER-P4.8: classify's COLUMN-level decision is now ClassificationReducer over the live diff
        // (switched inside classify; the P4.1 self-check that guarded this is now tautological and gone).
        SchemaChangeClassification classification = classify(dataSource, manifest);
        SchemaChangeClassification classificationForFallthrough = classification;
        if (classification == SchemaChangeClassification.SAFE_ADDITIVE) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " but every difference is a new non-bond column on an "
                    + "already-existing table; skipping destructive recreation (handled by the additive repeatable migration).");
            // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
            writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
            return DestructiveRecreation.safeAdditiveOutcome();
        }
        if (classification == SchemaChangeClassification.RENAME_DETECTED) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " -- classified as RENAME_DETECTED (a declared renamedFrom "
                    + "matches a column the live database still has under its old name). Attempting in-place "
                    + "ALTER TABLE ... RENAME COLUMN for every table whose diff is fully explained by declared "
                    + "renames, preserving all data.");
            attemptInPlaceRenames(dataSource, manifest);
            SchemaChangeClassification residual = classify(dataSource, manifest);
            classificationForFallthrough = residual;
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place field rename(s) fully resolved the fingerprint "
                        + "diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
                // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                return DestructiveRecreation.safeAdditiveOutcome();
            }
            if (residual == SchemaChangeClassification.TYPE_CHANGE_DETECTED) {
                // LNCH-1 P3 (3.3): a rename may be combined with a type change on the same column
                // (or an unrelated shared column on the same table). Renames already ran above, so
                // this sees the NEW column name(s) -- resolving both operations in one boot.
                System.out.println("NPDev schema lifecycle: residual classification after field renames is "
                        + "TYPE_CHANGE_DETECTED -- attempting in-place safe-widening ALTER COLUMN statements "
                        + "(LNCH-1 Phase 3), per-table all-or-nothing.");
                attemptInPlaceTypeWidenings(dataSource, manifest);
                residual = classify(dataSource, manifest);
                classificationForFallthrough = residual;
                if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                    System.out.println("NPDev schema lifecycle: in-place rename(s) and type widening(s) fully "
                            + "resolved the fingerprint diff (residual classification SAFE_ADDITIVE); skipping "
                            + "destructive recreation.");
                    // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                    writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
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
            attemptInPlaceRenames(dataSource, manifest);
            SchemaChangeClassification residual = classify(dataSource, manifest);
            classificationForFallthrough = residual;
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place field rename(s) fully resolved the "
                        + "fingerprint diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
                // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                return DestructiveRecreation.safeAdditiveOutcome();
            }
            if (residual == SchemaChangeClassification.TYPE_CHANGE_DETECTED) {
                attemptInPlaceTypeWidenings(dataSource, manifest);
                residual = classify(dataSource, manifest);
                classificationForFallthrough = residual;
                if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                    System.out.println("NPDev schema lifecycle: in-place rename(s) and type widening(s) fully "
                            + "resolved the fingerprint diff (residual classification SAFE_ADDITIVE); skipping "
                            + "destructive recreation.");
                    // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                    writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
                    return DestructiveRecreation.safeAdditiveOutcome();
                }
            }
            System.out.println("NPDev schema lifecycle: in-place rename/widening pass left a residual classification of "
                    + residual + " (at least one type-differing column on some table was a narrowing or "
                    + "incomparable change -- per-table all-or-nothing means nothing on that table was applied); "
                    + "falling through to destructive recreation as the safety net.");
        }

        // LNCH-1 P5 (5.3): a required bond/FK field missing from an existing, populated table is
        // intercepted HERE, before SchemaDeltaReport ever runs -- independently re-derived per
        // table (not relying on classify()'s short-circuit-to-DESTRUCTIVE aggregate value), so it
        // is caught with a dedicated, itemized refusal instead of falling into SchemaDeltaReport's
        // generic UNKNOWN item kind.
        refuseIfRequiredBondColumnMissing(dataSource, manifest, stored, classificationForFallthrough);

        // LNCH-1 Phase 4 (task 4.3): everything below replaces the old blanket whole-schema-wipe
        // fallback with itemized, surgical destruction wherever the residual diff cleanly supports
        // it. SchemaDeltaReport independently re-introspects the live database (it does not trust
        // classify()'s classification value beyond what is used here for logging/history purposes).
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);
        String expectedToken = DestructiveAckToken.compute(manifest.schemaFingerprint(), report.stableStrings());
        // SER-P6.3 (Surface 1): persist + print the operator-facing impact report (read-only row-count
        // probes over the canonical diff) at the destructive decision point, for both the refused and the
        // applied outcome. Fully swallowed — never affects the boot or the byte-identical token above.
        ImpactReportWriter.writeAndPrint(dataSource, manifest, stored, expectedToken);
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
            writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classificationForFallthrough,
                    report, providedToken.isBlank() ? null : providedToken, "REFUSED");
            throw new IllegalStateException("Schema fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " and includes destructive change(s) requiring an explicit, "
                    + "itemized acknowledgment (LNCH-1 Phase 4). Itemized destructive report: "
                    + report.stableStrings() + ". Expected acknowledgment token: " + expectedToken
                    + ". Set the generated manifest's destructiveAcknowledgment to this token, or submit it via "
                    + "the ControlPanel schema-migration screen on the currently running app (LNCH-1 Phase 6), to "
                    + "proceed -- see docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes."
                    + agreementCheckSuffix(manifest, report));
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
                writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classificationForFallthrough,
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
                throw new IllegalStateException("Schema fingerprint changed from " + stored + " to "
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
                        + agreementCheckSuffix(manifest, report));
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
            DestructiveRecreation result = executeSurgicalDestruction(dataSource, manifest, stored,
                    classificationForFallthrough, report, tokenMatches ? effectiveToken : null);
            // Only a token-authorized pass may consume somebody's pending acknowledgment row -- a
            // blanket-authorized pass did not use it and must leave it available.
            if (tokenMatches) {
                consumePendingAcknowledgmentIfAny(dataSource, pendingAcknowledgment);
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
        DestructiveRecreation result = executeWholeSchemaWipe(dataSource, manifest, stored, classificationForFallthrough,
                report, effectiveToken);
        consumePendingAcknowledgmentIfAny(dataSource, pendingAcknowledgment);
        return result;
    }

    /** Consume-on-use (see {@link PendingSchemaAcknowledgmentStore}'s class javadoc): only called
     * AFTER a destructive pass has fully applied, never before -- so a crash mid-destructive still
     * finds the same pending row available to authorize a retry on the next boot. A {@code null}
     * acknowledgment (the static manifest field alone authorized this pass, or the deprecated
     * blanket flag did) is a no-op. */
    private static void consumePendingAcknowledgmentIfAny(
            DataSource dataSource, PendingSchemaAcknowledgmentStore.PendingAcknowledgment pendingAcknowledgment
    ) {
        if (pendingAcknowledgment != null) {
            PendingSchemaAcknowledgmentStore.consume(dataSource, pendingAcknowledgment.id());
        }
    }

    /**
     * LNCH-1 Phase 6 (task 6.3): the "friendlier" agreement-check enrichment. The token-mismatch
     * refusal above already correctly blocks regardless -- this method changes nothing about that
     * decision, it only enriches the REFUSAL MESSAGE when there is something more specific to say.
     * {@code manifest.planItemStableStrings()} is the destructive-item stable strings a migration
     * plan computed at GENERATION time (see {@code MigrationPlanEmitter}, populated only when a
     * future {@code Build-NpdevApp.ps1 -PlanOnly}/{@code -Upgrade} passed
     * {@code --schemaMigrationPlanOut}; empty for every app generated without that flag). When it is
     * non-empty AND differs from what THIS report independently found live at BOOT time, an operator
     * is very likely looking at model/database drift since the plan was generated (the classic stale-
     * artifact problem) -- printing both lists side by side turns an opaque "wrong token" refusal
     * into "here is exactly what changed out from under your plan." When the two lists already agree
     * (or no plan-derived list is available at all -- the common case today, since Phase 6's CLI
     * wiring is opt-in), this returns {@code ""} and the refusal message is unchanged from Phase 4.
     */
    private static String agreementCheckSuffix(SchemaManifest manifest, SchemaDeltaReport report) {
        List<String> planned = manifest.planItemStableStrings();
        if (planned == null || planned.isEmpty()) {
            return "";
        }
        List<String> plannedSorted = new ArrayList<>(planned);
        Collections.sort(plannedSorted);
        List<String> actualSorted = new ArrayList<>(report.stableStrings());
        Collections.sort(actualSorted);
        if (plannedSorted.equals(actualSorted)) {
            return "";
        }
        return " NOTE (LNCH-1 Phase 6 agreement check): the migration plan reviewed/acknowledged at "
                + "generation time expected these destructive item(s): " + plannedSorted
                + " -- but the live database at boot actually needs: " + actualSorted
                + ". This usually means the model was edited again after the plan was generated, or the "
                + "target database has drifted from the state the plan assumed. Regenerate the plan "
                + "against the current model and database before acknowledging.";
    }

    /**
     * LNCH-1 Phase 4 (task 4.3): the NEW default destructive path -- executes ONLY the tables/
     * columns the itemized {@link SchemaDeltaReport} actually names, gated on a matching
     * {@link com.npdev.dsl.v1.schemaevolution.DestructiveAckToken}. Snapshots only the affected
     * tables (§2.6 answer 1) via the existing {@link SchemaDropSnapshotWriter#snapshotBeforeDrop}
     * -- no new "table-subset" overload needed, it already accepts an arbitrary table list.
     *
     * <p>Write-before-execute, update-after (§2.4 crash semantics): the history row is inserted
     * with {@code outcome = 'PARTIAL-CRASH'} BEFORE any DDL runs, and only updated to
     * {@code 'APPLIED'} after every item has executed successfully. If the JVM dies mid-loop, the
     * row is left exactly as inserted -- an accurate record that this pass crashed partway
     * through, not a false claim either way.
     */
    private DestructiveRecreation executeSurgicalDestruction(
            DataSource dataSource,
            SchemaManifest manifest,
            String stored,
            SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed
    ) {
        List<String> affectedTables = new ArrayList<>(report.affectedTables());
        Collections.sort(affectedTables);
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, affectedTables);

        String historyId = insertPendingHistoryRow(dataSource, stored, manifest.schemaFingerprint(),
                classification, report, ackTokenUsed);

        List<String> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (SchemaDeltaItem item : report.items()) {
                if (item instanceof DropColumn dropColumn) {
                    executeDropColumn(connection, dropColumn.table(), dropColumn.column());
                    applied.add("DROP_COLUMN " + dropColumn.table() + "." + dropColumn.column());
                } else if (item instanceof DropTable dropTable) {
                    executeDropTableCascade(connection, dropTable.table());
                    applied.add("DROP_TABLE " + dropTable.table());
                } else if (item instanceof NarrowType narrowType) {
                    // Drop-and-recreate, not a casting ALTER COLUMN TYPE: per the plan, data in a
                    // narrowed column is acknowledged lost by the token, and a cast can fail
                    // per-row (e.g. a too-long VARCHAR value) -- simpler and more honest to drop
                    // and recreate empty than attempt a partially-successful cast.
                    executeNarrowTypeDropAndRecreate(connection, narrowType.table(), narrowType.column(), narrowType.toType());
                    applied.add("NARROW_TYPE " + narrowType.table() + "." + narrowType.column() + " -> " + narrowType.toType());
                }
                // UNKNOWN items never reach here -- the caller only takes this path when
                // report.hasOnlyNamedDestructiveKinds() is true.
            }
        } catch (SQLException exception) {
            // Deliberately NOT updating the history row here -- it stays at PARTIAL-CRASH, which is
            // the correct, honest record of a half-applied surgical pass (§2.4).
            throw new IllegalStateException("Failed applying surgical destructive schema changes ("
                    + applied.size() + "/" + report.items().size() + " item(s) applied before failure: "
                    + applied + ")", exception);
        }
        markHistoryRowApplied(dataSource, historyId);
        System.out.println("NPDev schema lifecycle: surgical destructive changes applied: " + applied);
        return new DestructiveRecreation(true, false, List.copyOf(affectedTables));
    }

    private static void executeDropColumn(Connection connection, String table, String column) throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeColumn = safeIdentifier(column);
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " DROP COLUMN " + safeColumn)) {
            statement.executeUpdate();
        }
    }

    private static void executeDropTableCascade(Connection connection, String table) throws SQLException {
        String safeTable = safeIdentifier(table);
        // Same CASCADE rationale as the whole-schema path (see executeWholeSchemaWipe): drops any
        // dependent FK constraint along with the table; it does not touch a referencing table's rows.
        try (PreparedStatement statement = connection.prepareStatement(
                "DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
            statement.executeUpdate();
        }
    }

    private static void executeNarrowTypeDropAndRecreate(Connection connection, String table, String column, String newType)
            throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeColumn = safeIdentifier(column);
        String safeType = safeSqlType(newType);
        try (PreparedStatement drop = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " DROP COLUMN " + safeColumn)) {
            drop.executeUpdate();
        }
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ADD COLUMN " + safeColumn + " " + safeType)) {
            add.executeUpdate();
        }
    }

    /**
     * The OLD destructive path (pre-Phase-4 behavior, unchanged DDL), now reached ONLY when the
     * residual diff includes an {@code UNKNOWN} item the surgical path cannot explain. Same
     * write-before-execute/update-after history lifecycle as the surgical path.
     *
     * <p><b>LNCH-1 hardening X1 (finding X-B1):</b> this path used to ALSO be reached whenever
     * authorization came solely from the deprecated blanket {@code destructiveAllowed} flag, even
     * when every item in the report was surgically executable. That was a critical regression: this
     * method drops the tables the NEW manifest lists, so a dropped concept's orphaned table survived
     * while every still-modelled concept's data was destroyed. The authorization source no longer
     * influences which execution path runs -- only the presence of an {@code UNKNOWN} item does.
     *
     * <p><b>LNCH-1 closeout C1 (finding C-B1):</b> the authorization source no longer selects the
     * path, but it does still gate it. Because this method destroys EVERY manifest-listed table's
     * data, it now requires the itemized acknowledgment token exactly as a concept drop does -- the
     * blanket flag alone is refused before we get here. Callers may therefore rely on
     * {@code acknowledgmentToken} being non-null.
     */
    private DestructiveRecreation executeWholeSchemaWipe(
            DataSource dataSource,
            SchemaManifest manifest,
            String stored,
            SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed
    ) {
        List<String> tables = new ArrayList<>();
        tables.addAll(manifest.businessTables());
        tables.addAll(manifest.internalTables());
        Collections.reverse(tables);
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, tables);

        String historyId = insertPendingHistoryRow(dataSource, stored, manifest.schemaFingerprint(),
                classification, report, ackTokenUsed);

        List<String> dropped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : tables) {
                if (table == null || table.isBlank()) {
                    continue;
                }
                String safeTable = safeIdentifier(table);
                // CASCADE, not a precise FK-aware drop order: the manifest lists tables in
                // declaration order, which does not generally match the dependency order a
                // referencing table (e.g. notes.project_ref -> projects) requires -- dropping the
                // referenced table first throws ("depends on it") on both H2 and Postgres. CASCADE
                // drops the dependent FK constraint along with the table; it does not touch the
                // referencing table's ROWS (those are gone anyway, the referencing table is itself
                // in this same drop list during a full destructive recreate).
                try (PreparedStatement statement = connection.prepareStatement("DROP TABLE IF EXISTS " + safeTable + " CASCADE")) {
                    statement.executeUpdate();
                    dropped.add(safeTable);
                }
            }
            System.out.println("NPDev destructive schema recreation dropped manifest-listed NPDev-owned tables: " + dropped);
            System.out.println("NPDev destructive schema recreation stored fingerprint: " + stored);
            System.out.println("NPDev destructive schema recreation generated fingerprint: " + manifest.schemaFingerprint());
        } catch (SQLException exception) {
            // History row deliberately left at PARTIAL-CRASH -- see executeSurgicalDestruction's note.
            throw new IllegalStateException("Failed destructive schema recreation", exception);
        }
        markHistoryRowApplied(dataSource, historyId);
        return new DestructiveRecreation(true, false, List.copyOf(dropped));
    }

    /** Backward-compatible convenience: true only for the SAFE_ADDITIVE classification. */
    boolean isSafeAdditiveChange(DataSource dataSource, SchemaManifest manifest) {
        return classify(dataSource, manifest) == SchemaChangeClassification.SAFE_ADDITIVE;
    }

    /**
     * LNCH-1 P2 (2.5): executes in-place {@code ALTER TABLE ... RENAME TO} statements for every
     * declared concept (table) rename ({@code SchemaManifest#businessTableRenames}, a flat
     * {@code newTableName -> oldTableName} map) that is actually explained by the live database --
     * i.e. the OLD table still exists live and the NEW table does not yet. Reuses
     * {@link RenameResolution#resolve} (originally extracted for column-level renames in Phase 1,
     * but its algorithm is generic over any name-vs-name diff, table names included) against the
     * SAME kind of missing/extra set computation {@link #classify} uses, just at table granularity
     * instead of column granularity: "missing" = manifest-expected table names
     * ({@code businessTableColumns().keySet()}) absent from the live database; "extra" = live
     * tables not declared under any current name in the manifest.
     *
     * <p>This step MUST run before {@link #classify} is invoked (see {@link #beforeMigrate}):
     * {@code classify} only ever looks up a table by its manifest-current name, so a table that
     * still exists live under its OLD name is invisible to it -- table renames have to already be
     * applied by the time classification (and the field-rename step, which depends on current table
     * names) runs.
     *
     * <p>Idempotent by construction: live table names are read fresh via
     * {@link DatabaseMetaData#getTables} on every call, so re-invoking this against an
     * already-renamed table finds the OLD name no longer "extra" (it's gone) and does nothing.
     *
     * <p>Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #attemptInPlaceRenames}'s precedent.
     */
    void attemptInPlaceTableRenames(DataSource dataSource, SchemaManifest manifest) {
        Map<String, String> declaredTableRenames = manifest.businessTableRenames();
        if (declaredTableRenames.isEmpty()) {
            return;
        }
        List<String> renamed = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            // SER-P4.3: the rename work-list (new -> old) now comes from the canonical SchemaDiff --
            // proven byte-identical to the former RenameResolution over live introspection (P4.3a).
            List<Map.Entry<String, String>> work = new ArrayList<>(
                    tableRenamesFromDiff(dataSource, manifest).entrySet());
            // R4 (F5): write-before-execute the whole pass as one audit row with per-item detail.
            List<String> itemDetails = new ArrayList<>();
            for (Map.Entry<String, String> pair : work) {
                itemDetails.add("RENAME_TABLE " + pair.getValue() + " -> " + pair.getKey());
            }
            recordStepPass(dataSource, manifest, "TABLE_RENAME", itemDetails, () -> {
                for (Map.Entry<String, String> pair : work) {
                    executeRenameTable(connection, pair.getValue(), pair.getKey());
                    renamed.add(pair.getValue() + " -> " + pair.getKey());
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying in-place table renames", exception);
        }
        if (!renamed.isEmpty()) {
            System.out.println("NPDev schema lifecycle: applied in-place table renames: " + renamed);
        }
    }

    /** SER-P4.3: the table-rename work-list (new -&gt; old) derived from the canonical {@link
     * com.finalexec.db.schemastate.SchemaDiff} -- the {@code RENAME_TABLE} items the engine resolves.
     * Proven equal to the bespoke {@link RenameResolution} result before it replaces it. */
    private static Map<String, String> tableRenamesFromDiff(DataSource dataSource, SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Map<String, String> renames = new LinkedHashMap<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.SAFE_RENAME
                    && di.itemKey().startsWith("RENAME_TABLE:")) {
                renames.put(di.after(), di.before()); // after = new name, before = old name
            }
        }
        return renames;
    }

    /**
     * Table-rename DDL (§6.1): {@code ALTER TABLE ... RENAME TO ...} is identical on both Postgres
     * and H2 (unlike column rename, which differs per engine) -- confirmed via the real H2
     * integration test {@code SchemaLifecycleExecutorTableRenameTest} before being trusted here.
     */
    private static void executeRenameTable(Connection connection, String oldTable, String newTable) throws SQLException {
        String safeOld = safeIdentifier(oldTable);
        String safeNew = safeIdentifier(newTable);
        String sql = "ALTER TABLE " + safeOld + " RENAME TO " + safeNew;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /**
     * Every live table name (lower-cased), system-schema-filtered the same way
     * {@link #readActualColumns} and {@link #readActualColumnTypes} already are. Used only by
     * {@link #attemptInPlaceTableRenames} to find tables that exist live but are not declared under
     * their current name in the manifest.
     */
    /** Package-private (not private): reused verbatim by {@link SchemaDeltaReport} (LNCH-1 Phase 4)
     * so the delta report enumerates live tables via the exact same primitive the table-rename step
     * uses, instead of a second hand-rolled copy. */
    static Set<String> readActualTableNames(DatabaseMetaData metadata) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet resultSet = metadata.getTables(null, null, null, new String[] {"TABLE"})) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String name = resultSet.getString("TABLE_NAME");
                if (name != null && !name.isBlank()) {
                    tables.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return tables;
    }

    /**
     * Executes in-place {@code ALTER TABLE ... RENAME COLUMN} statements (LNCH-1 Phase 1) for
     * every business table whose declared {@code renamedFrom} pairs are cleanly resolvable against
     * the live database, per {@link RenameResolution}. Only {@code remainingMissing} (columns the
     * manifest still expects after rename-pairing, e.g. a genuinely new field) gates eligibility --
     * and only when not additive-eligible. {@code remainingExtra} (a live column the manifest no
     * longer wants at all, unexplained by any rename) does NOT block the rename: that column is an
     * independent concern the destructive path (itemized surgical drop, with its own acknowledgment)
     * or the whole-schema fallback handles separately.
     *
     * <p><b>LNCH-1 Phase 7 rehearsal fix (found live, real data loss on a real Postgres compose
     * boot):</b> this used to also require {@code remainingExtra.isEmpty()}, on the theory that any
     * unexplained extra column meant the whole table was headed to the (then-only) whole-schema-wipe
     * destructive path anyway, making a skipped rename harmless. That stopped being true once Phase 4
     * added the surgical, itemized destructive path: a table can legitimately combine a safe rename
     * with an UNRELATED, separately-acknowledged column drop (e.g. rename 'name'->'full_name' AND
     * drop 'active' in the same upgrade) -- skipping the rename left the OLD column's data silently
     * orphaned (invisible to the app) while the additive-columns migration added the NEW column
     * empty, with no error and no refusal. {@link #beforeMigrate} now calls this method
     * UNCONDITIONALLY before {@link #classify} ever runs (mirroring {@link #attemptInPlaceTableRenames}'s
     * existing unconditional-before-classify precedent for whole-table renames), so the rename is
     * applied -- and the old column's data preserved -- before the (still-correct) DESTRUCTIVE
     * classification for the unrelated drop is even computed. A rename COMBINED with a type change on
     * the same column is, similarly, no longer a reason to skip that column's rename (see the inline
     * LNCH-1 P3 note below) -- {@link #attemptInPlaceTypeWidenings} resolves the type side afterward.
     *
     * <p>Idempotent by construction: every table's diff is read fresh from live
     * {@link DatabaseMetaData} on each call (never a cached snapshot), so re-invoking this method
     * against an already-renamed table naturally finds nothing left to explain and does nothing.
     *
     * <p>Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #classify} and {@link #isSafeAdditiveChange}'s
     * precedent.
     */
    void attemptInPlaceRenames(DataSource dataSource, SchemaManifest manifest) {
        record ColumnRename(String table, String oldName, String newName) {
        }
        // SER-P4.4: the whole plan -- the renames to apply, the tables deferred to the destructive path,
        // and the stale-marker warnings -- is now derived from the canonical SchemaDiff (proven equal to
        // the former RenameResolution loop at P4.4a), not a second live introspection.
        ColumnRenamePlan derived = columnRenamesFromDiff(dataSource, manifest);
        for (String warning : derived.staleWarnings()) {
            System.out.println(warning);
        }
        List<ColumnRename> plan = new ArrayList<>();
        for (String[] rename : derived.renames()) {
            plan.add(new ColumnRename(rename[0], rename[1], rename[2]));
        }
        List<String> renamed = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            // R4 (F5): one write-before-execute audit row for the whole column-rename pass.
            List<String> itemDetails = new ArrayList<>();
            for (ColumnRename r : plan) {
                itemDetails.add("RENAME_COLUMN " + r.table() + "." + r.oldName() + " -> " + r.newName());
            }
            recordStepPass(dataSource, manifest, "COLUMN_RENAME", itemDetails, () -> {
                for (ColumnRename r : plan) {
                    executeRenameColumn(connection, manifest.engine(), r.table(), r.oldName(), r.newName());
                    renamed.add(r.table() + "." + r.oldName() + " -> " + r.newName());
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying in-place field renames", exception);
        }
        if (!renamed.isEmpty()) {
            System.out.println("NPDev schema lifecycle: applied in-place field renames: " + renamed);
        }
        if (!derived.skipped().isEmpty()) {
            System.out.println("NPDev schema lifecycle: tables left for the destructive path (rename did not "
                    + "fully explain the diff): " + derived.skipped());
        }
    }

    /** The column-rename pass's whole plan, derived from the canonical diff (SER-P4.4). */
    private record ColumnRenamePlan(List<String[]> renames, List<String> skipped, List<String> staleWarnings) {
    }

    /**
     * SER-P4.4: the column-rename plan derived from the canonical {@link com.finalexec.db.schemastate.SchemaDiff}
     * instead of a second live introspection + {@link RenameResolution} pass.
     * <ul>
     *   <li><b>renames</b> ({@code {table, old, new}}) -- the {@code RENAME_COLUMN} items the engine
     *       resolves, on tables that pass the SAME per-table eligibility gate the bespoke pass applied (a
     *       table whose remaining missing columns are not all additive-eligible is deferred whole to the
     *       destructive path, so none of its renames are applied here). Applying an eligible rename is
     *       unconditional even when the column ALSO has a type change: {@code beforeMigrate} runs
     *       {@code attemptInPlaceTypeWidenings} immediately afterward against the new name, and a residual
     *       narrowing simply re-classifies the table onto the destructive path (whose pre-drop snapshot
     *       captures data under the already-renamed column) -- no incorrect persisted state.</li>
     *   <li><b>skipped</b> -- those ineligible tables, for the operator log.</li>
     *   <li><b>staleWarnings</b> -- R6 (F7): a declared rename whose OLD and NEW columns are BOTH absent
     *       live explained nothing (a stale {@code renamedFrom} marker can turn a rename into a drop).</li>
     * </ul>
     */
    private static ColumnRenamePlan columnRenamesFromDiff(DataSource dataSource, SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        // Per table: the resolved renames (old->new) and the remaining-missing (added) columns. A
        // RENAME_COLUMN item is the rename; an ADD_(REQUIRED_)COLUMN item is a column absent live and not
        // rename-explained -- exactly the bespoke pass's remainingMissing.
        Map<String, List<String[]>> renamesByTable = new LinkedHashMap<>();
        Map<String, Set<String>> missingByTable = new LinkedHashMap<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.SAFE_RENAME
                    && di.itemKey().startsWith("RENAME_COLUMN:")) {
                renamesByTable.computeIfAbsent(di.table(), t -> new ArrayList<>())
                        .add(new String[] {di.before(), di.after()});
            } else if (di.itemKey().startsWith("ADD_COLUMN:") || di.itemKey().startsWith("ADD_REQUIRED_COLUMN:")) {
                missingByTable.computeIfAbsent(di.table(), t -> new LinkedHashSet<>()).add(di.column());
            }
        }

        List<String[]> renames = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> entry : renamesByTable.entrySet()) {
            String table = entry.getKey();
            Set<String> additive = new LinkedHashSet<>(
                    manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
            Set<String> remainingMissing = missingByTable.getOrDefault(table, Set.of());
            if (!additive.containsAll(remainingMissing)) {
                skipped.add(table + " (a remaining expected column is neither renamed-in nor "
                        + "additive-eligible -- remainingMissing=" + remainingMissing + ")");
                continue;
            }
            for (String[] oldNew : entry.getValue()) {
                renames.add(new String[] {table, oldNew[0], oldNew[1]});
            }
        }

        // R6 (F7): the stale-marker warning, now checked against the live CurrentSchema (the full column
        // set the diff read) rather than a separate readActualColumns call.
        List<String> staleWarnings = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> tableRenames : manifest.businessTableRenamedColumns().entrySet()) {
            String table = tableRenames.getKey();
            Map<String, String> declaredRenames = tableRenames.getValue();
            if (declaredRenames.isEmpty()
                    || manifest.businessTableColumns().getOrDefault(table, List.of()).isEmpty()) {
                continue;
            }
            com.finalexec.db.schemastate.CurrentTable liveTable = current.tables().get(table.toLowerCase(Locale.ROOT));
            Set<String> liveColumns = liveTable == null ? Set.of() : liveTable.columns().keySet();
            if (liveColumns.isEmpty()) {
                continue; // brand-new table -- nothing live to rename
            }
            for (Map.Entry<String, String> declared : declaredRenames.entrySet()) {
                String newName = declared.getKey();
                String oldName = declared.getValue();
                if (oldName != null && !oldName.isBlank()
                        && !liveColumns.contains(oldName.toLowerCase(Locale.ROOT))
                        && !liveColumns.contains(newName.toLowerCase(Locale.ROOT))) {
                    staleWarnings.add("NPDev schema lifecycle: WARNING -- declared rename '" + oldName
                            + "' -> '" + newName + "' on table '" + table + "' explains nothing: neither the "
                            + "old nor the new column exists live. A stale renamedFrom marker (e.g. a second "
                            + "rename that never updated the marker to the immediately-previous name) can turn "
                            + "a rename into a destructive drop -- see docs/SCHEMA_EVOLUTION.md#marker-lifecycle.");
                }
            }
        }
        return new ColumnRenamePlan(renames, skipped, staleWarnings);
    }

    /**
     * Dialect-specific rename-column DDL (§6.1): Postgres uses {@code RENAME COLUMN}, H2 uses
     * {@code ALTER COLUMN ... RENAME TO}. {@code manifest.engine()} is one of exactly
     * {@code "InMemory"}, {@code "H2Local"}, {@code "H2Server"}, {@code "Postgres"} -- and by the
     * time this is called {@code migrate()} has already returned early for InMemory (no physical
     * database), so only the two H2 variants and Postgres are ever seen here.
     */
    private static void executeRenameColumn(Connection connection, String engine, String table, String oldName, String newName)
            throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeOld = safeIdentifier(oldName);
        String safeNew = safeIdentifier(newName);
        String sql = "Postgres".equals(engine)
                ? "ALTER TABLE " + safeTable + " RENAME COLUMN " + safeOld + " TO " + safeNew
                : "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeOld + " RENAME TO " + safeNew;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /**
     * LNCH-1 P3 (3.2): executes in-place {@code ALTER COLUMN ... TYPE} statements for every
     * business table whose live-DB-vs-manifest type diff is fully explained by {@link TypeChangeMatrix}
     * {@code WIDENING} classifications -- reusing {@link #readActualColumnTypes} (post length/
     * precision fix) and {@link #normalizeSqlType} to find the differing columns, exactly the way
     * {@link #hasTypeChange} does for classification.
     *
     * <p><b>Per-table all-or-nothing (plan-mandated):</b> a table's type-differing columns are
     * computed as a set FIRST; the widening ALTER statements are only executed if EVERY one of them
     * classifies as {@code WIDENING}. If even one is {@code NARROWING} or {@code INCOMPARABLE},
     * NOTHING is applied on that table (not even the other columns' safe widenings) -- partial
     * application would leave a state neither the old nor the new fingerprint describes.
     *
     * <p><b>Composability with renames (3.3):</b> called by {@link #beforeMigrate} strictly AFTER
     * {@link #attemptInPlaceTableRenames} and {@link #attemptInPlaceRenames} have already run, so a
     * column that is both renamed and widened is looked up here under its NEW (already-renamed)
     * name -- both operations land in one boot.
     *
     * <p>Idempotent by construction: live types are read fresh via {@link DatabaseMetaData} on every
     * call, so re-invoking this against an already-widened column finds no diff (nothing to do).
     *
     * <p>Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #attemptInPlaceRenames}'s precedent.
     */
    void attemptInPlaceTypeWidenings(DataSource dataSource, SchemaManifest manifest) {
        record Widening(String table, String column, String fromType, String toType) {
        }
        // SER-P4.5: which shared columns safely widen (and which tables defer whole to the destructive
        // path under the per-table all-or-nothing rule) is derived from the canonical SchemaDiff, not a
        // second live introspection. Proven byte-identical at P4.5a.
        WideningPlan derived = wideningPlanFromDiff(dataSource, manifest);
        List<Widening> plan = new ArrayList<>();
        for (String[] entry : derived.widened()) {
            String table = entry[0];
            String column = entry[1];
            // toType from the manifest's DECLARED type keeps the emitted DDL byte-for-byte identical to
            // the former loop; the diff's normalized after-type is used only for classification.
            String toType = manifest.businessTableColumnTypes().getOrDefault(table, Map.of()).get(column);
            plan.add(new Widening(table, column, entry[2], toType));
        }
        List<String> widened = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            // R4 (F5): one write-before-execute audit row for the whole type-widening pass.
            List<String> itemDetails = new ArrayList<>();
            for (Widening w : plan) {
                itemDetails.add("WIDEN " + w.table() + "." + w.column() + " " + w.fromType() + " -> " + w.toType());
            }
            recordStepPass(dataSource, manifest, "TYPE_WIDENING", itemDetails, () -> {
                for (Widening w : plan) {
                    executeWidenColumnType(connection, manifest.engine(), w.table(), w.column(), w.toType());
                    widened.add(w.table() + "." + w.column() + " -> " + w.toType());
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying in-place type widenings", exception);
        }
        if (!widened.isEmpty()) {
            System.out.println("NPDev schema lifecycle: applied in-place type widenings: " + widened);
        }
        if (!derived.skippedTables().isEmpty()) {
            List<String> skipped = new ArrayList<>();
            for (String table : derived.skippedTables()) {
                skipped.add(table + " (not every type-differing column on this table is a safe widening -- "
                        + "per-table all-or-nothing rule, deferred to the destructive path)");
            }
            System.out.println("NPDev schema lifecycle: tables left for the destructive path (type diff not "
                    + "fully explained by safe widenings): " + skipped);
        }
    }

    /** The type-widening pass's plan derived from the canonical diff (SER-P4.5): the shared columns that
     * safely widen (each {@code {table, column, fromType}}) and the tables deferred whole to the
     * destructive path (per-table all-or-nothing: a table with ANY non-widening type change -- a
     * DESTRUCTIVE_NARROW_TYPE item -- widens nothing). */
    private record WideningPlan(List<String[]> widened, Set<String> skippedTables) {
    }

    private static WideningPlan wideningPlanFromDiff(DataSource dataSource, SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        Map<String, List<String[]>> widenColsByTable = new LinkedHashMap<>(); // table -> [{column, fromType}]
        Set<String> narrowTables = new LinkedHashSet<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.SAFE_WIDEN) {
                widenColsByTable.computeIfAbsent(di.table(), t -> new ArrayList<>())
                        .add(new String[] {di.column(), di.before()});
            } else if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.DESTRUCTIVE_NARROW_TYPE) {
                narrowTables.add(di.table());
            }
        }
        // Every table with a type diff (widen and/or narrow); a narrow anywhere on it defers the whole table.
        Set<String> typeDiffTables = new LinkedHashSet<>(widenColsByTable.keySet());
        typeDiffTables.addAll(narrowTables);
        List<String[]> widened = new ArrayList<>();
        Set<String> skippedTables = new LinkedHashSet<>();
        for (String table : typeDiffTables) {
            if (narrowTables.contains(table)) {
                skippedTables.add(table);
            } else {
                for (String[] colFrom : widenColsByTable.getOrDefault(table, List.of())) {
                    widened.add(new String[] {table, colFrom[0], colFrom[1]});
                }
            }
        }
        return new WideningPlan(widened, skippedTables);
    }

    /**
     * Dialect-specific widen-column-type DDL (§6.1, confirmed against a real H2 instance before
     * being trusted here -- see {@code SchemaLifecycleExecutorTypeWideningIntegrationTest}):
     * Postgres uses {@code ALTER COLUMN ... TYPE}, H2 uses {@code ALTER COLUMN ... SET DATA TYPE}.
     * No {@code USING} clause is added for Postgres -- open question, not testable this session (no
     * Postgres instance available; see the phase evidence note) -- add one only if a real Postgres
     * run against one of the matrix's pairs proves it necessary.
     */
    private static void executeWidenColumnType(Connection connection, String engine, String table, String column, String newType)
            throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeColumn = safeIdentifier(column);
        String safeType = safeSqlType(newType);
        String sql = "Postgres".equals(engine)
                ? "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " TYPE " + safeType
                : "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " SET DATA TYPE " + safeType;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /**
     * Guardrail 11's identifier-safety discipline, applied to the SQL TYPE portion of a widening
     * ALTER statement: a type string comes from the manifest, which is generator-controlled today
     * (a fixed {@code SqlTypeSupport} mapping) but is still author-adjacent input, not a literal
     * this class invented -- reject anything that isn't a bare word optionally followed by
     * {@code (n)} or {@code (p,s)}.
     */
    private static String safeSqlType(String sqlType) {
        String value = sqlType == null ? "" : sqlType.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_ ]*(\\(\\d+(,\\s?\\d+)?\\))?")) {
            throw new IllegalStateException("Unsafe SQL type in schema realization manifest: " + sqlType);
        }
        return value;
    }

    // ------------------------------------------------------------------------------------------
    // LNCH-1 Phase 5: data pre-checks and literal backfills.
    // ------------------------------------------------------------------------------------------

    /**
     * LNCH-1 P5 (5.2). Called by {@link #beforeMigrate} at every point classification (after
     * Phases 1-3's rename/widening attempts) settles on {@code SAFE_ADDITIVE} as the residual --
     * BEFORE that method returns {@link DestructiveRecreation#safeAdditiveOutcome()} and therefore
     * BEFORE {@link #migrate}'s {@code flyway.migrate()} call ever runs the R__ repeatable additive
     * migration.
     *
     * <p><b>Why this must run ahead of Flyway, not after (see the class-level design note this
     * phase adds near {@link #beforeMigrate}):</b> {@code appendAdditiveColumns} (generator-side)
     * unconditionally emits {@code ADD COLUMN IF NOT EXISTS} for every additive-eligible column,
     * including required ones with no viable backfill -- if this method let that migration run
     * first and only refused afterward, a refused required-field addition would still leave a
     * nullable column sitting in the live database ("never add it in the first place" is the
     * plan's explicit requirement). So this method:
     * <ol>
     *   <li><b>Pass 1 (read-only):</b> for every table, find required, additive-eligible columns
     *       missing from the live database. A column with a declared literal default is queued for
     *       backfill; one without (no default, or only an expression default -- v1 only backfills
     *       literals) is queued as a refusal. Nothing is written to the database in this pass.</li>
     *   <li>If ANY refusal was queued, throw before this method applies any backfill of its own --
     *       every pending backfill in this same boot is left un-backfilled, and the stored fingerprint
     *       is left stale so a fixed retry re-attempts cleanly. (Post-remediation-R2 this method runs
     *       from {@code afterMigrate}, i.e. AFTER {@code flyway.migrate()}, so on a real boot
     *       {@code appendAdditiveColumns}'s {@code ADD COLUMN IF NOT EXISTS} may already have added the
     *       column NULLABLE before this refusal -- harmless: it stays nullable and untightened until a
     *       fixed model backfills it. The direct-call unit tests bypass {@code flyway.migrate()}, so
     *       there the column is genuinely never added.)</li>
     *   <li><b>Pass 2 (apply):</b> only reached when every required column has a literal default.
     *       For each: {@code ADD COLUMN IF NOT EXISTS} (nullable) -&gt; {@code UPDATE ... SET c = ?
     *       WHERE c IS NULL} (the literal, bound as a JDBC parameter -- never string-interpolated
     *       into SQL text, see {@link #decodeLiteralDefault}) -&gt; {@code ALTER COLUMN SET NOT
     *       NULL} (skipped if already NOT NULL, so crash-recovery re-runs converge instead of
     *       erroring). When Flyway's R__ migration runs afterward, its {@code ADD COLUMN IF NOT
     *       EXISTS} for this same column observes it already present -- a harmless no-op.</li>
     * </ol>
     *
     * <p>Idempotent by construction: live columns/nullability are read fresh from
     * {@link DatabaseMetaData} on every call, so a crash between two backfilled columns (or between
     * the ADD/UPDATE/SET-NOT-NULL steps of one column) converges cleanly on the next boot -- see
     * {@code SchemaLifecycleExecutorRequiredFieldBackfillCrashRecoveryTest}.
     */
    private void applyRequiredFieldBackfills(DataSource dataSource, SchemaManifest manifest, String stored,
            SchemaChangeClassification classification) {
        record PendingBackfill(String table, String column, String sqlType, String literalDefaultJson) {
        }
        // SER-P4.6: which additive-eligible required columns need a literal-default backfill (pending) or
        // have no literal default and so refuse the boot (refusal) is derived from the canonical SchemaDiff
        // -- covering the missing case (ADD_REQUIRED_COLUMN) AND the crash-recovery half-applied case
        // (TIGHTEN_NOT_NULL: present-but-nullable; a converged present+NOT NULL column produces no diff
        // item and is correctly skipped). Each diff item's lower-cased name is resolved back to its
        // model-case table/column so the emitted DDL and refusal messages are byte-identical to the former
        // live-introspection loop. Proven equivalent at P4.6a.
        List<PendingBackfill> pending = new ArrayList<>();
        List<String> refusals = new ArrayList<>();
        for (BackfillItem item : backfillItemsFromDiff(dataSource, manifest)) {
            String table = item.table();   // model-case
            String column = item.column(); // model-case
            if (item.refusal()) {
                boolean hasExpressionDefault = manifest.businessTableExpressionDefaultColumns()
                        .getOrDefault(table, List.of()).contains(column);
                refusals.add(table + "." + column + (hasExpressionDefault
                        ? " (an expression default is declared, but only literal defaults are backfilled "
                                + "automatically in v1 -- declare a literal default or make the field optional)"
                        : " (no default declared -- declare a literal default or make the field optional)"));
            } else {
                String literalDefaultJson = manifest.businessTableColumnDefaultLiterals()
                        .getOrDefault(table, Map.of()).get(column);
                String sqlType = manifest.businessTableColumnTypes().getOrDefault(table, Map.of()).get(column);
                pending.add(new PendingBackfill(table, column, sqlType, literalDefaultJson));
            }
        }

        if (!refusals.isEmpty()) {
            writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification, null, null, "REFUSED");
            throw new IllegalStateException("Schema change adds new required field(s) to table(s) with existing "
                    + "data, but no literal default is available to backfill automatically (LNCH-1 Phase 5): "
                    + refusals + ". Declare a literal 'default' on the field, or make it optional -- see "
                    + "docs/SCHEMA_EVOLUTION.md#new-required-fields.");
        }
        if (pending.isEmpty()) {
            return;
        }
        List<String> backfilled = new ArrayList<>();
        // R4 (F5): one write-before-execute audit row for the whole required-field backfill pass.
        List<String> itemDetails = new ArrayList<>();
        for (PendingBackfill item : pending) {
            itemDetails.add("BACKFILL " + item.table() + "." + item.column() + " DEFAULT " + item.literalDefaultJson());
        }
        try {
            recordStepPass(dataSource, manifest, "REQUIRED_BACKFILL", itemDetails, () -> {
                try (Connection connection = dataSource.getConnection()) {
                    for (PendingBackfill item : pending) {
                        addBackfillAndTightenColumn(connection, item.table(), item.column(),
                                item.sqlType(), item.literalDefaultJson());
                        backfilled.add(item.table() + "." + item.column());
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying required-field backfill(s) (" + backfilled.size() + "/"
                    + pending.size() + " applied before failure: " + backfilled + ")", exception);
        }
        System.out.println("NPDev schema lifecycle: added and backfilled new required column(s) to their declared "
                + "literal default, then enforced NOT NULL (LNCH-1 Phase 5): " + backfilled);
    }

    /** One required-field backfill decision derived from the canonical diff (SER-P4.6), in model-case:
     * an additive-eligible required column that needs a literal-default backfill ({@code refusal=false},
     * from a NEEDS_BACKFILL item) or has no literal default and so refuses the boot ({@code refusal=true},
     * from a NEEDS_HOOK item). Covers the MISSING case (ADD_REQUIRED_COLUMN) and the crash-recovery
     * half-applied case (TIGHTEN_NOT_NULL: present-but-nullable); platform repair (TIGHTEN_PLATFORM) and
     * required bonds (non-additive) are OTHER passes and excluded. */
    private record BackfillItem(String table, String column, boolean refusal) {
    }

    private static List<BackfillItem> backfillItemsFromDiff(DataSource dataSource, SchemaManifest manifest) {
        com.finalexec.db.schemastate.CurrentSchema current =
                new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource);
        com.finalexec.db.schemastate.SchemaDiff diff = new com.finalexec.db.schemastate.SchemaDiffEngine()
                .diff(DesiredSchemaFactory.fromManifest(manifest),
                        ShadowParityProbe.scopeToOwnedBusinessTables(current, manifest));
        List<BackfillItem> items = new ArrayList<>();
        for (com.finalexec.db.schemastate.SchemaDiffItem di : diff.items()) {
            String key = di.itemKey();
            if (!key.startsWith("ADD_REQUIRED_COLUMN:") && !key.startsWith("TIGHTEN_NOT_NULL:")) {
                continue;
            }
            // The diff canonicalises names to lower-case; resolve back to the manifest's model-case so the
            // emitted DDL and refusal messages stay byte-identical to the former loop.
            String modelTable = resolveModelTable(manifest, di.table());
            if (modelTable == null) {
                continue;
            }
            String modelColumn = resolveModelColumn(manifest, modelTable, di.column());
            if (modelColumn == null) {
                continue;
            }
            // This pass only converts additive-eligible required columns; required bonds (non-additive)
            // and platform columns are refused / repaired by separate passes.
            if (!containsIgnoreCase(manifest.businessTableRequiredColumns().getOrDefault(modelTable, List.of()), di.column())
                    || !containsIgnoreCase(manifest.businessTableAdditiveColumns().getOrDefault(modelTable, List.of()), di.column())) {
                continue;
            }
            if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.NEEDS_BACKFILL) {
                items.add(new BackfillItem(modelTable, modelColumn, false));
            } else if (di.safetyClass() == com.finalexec.db.schemastate.SafetyClass.NEEDS_HOOK) {
                items.add(new BackfillItem(modelTable, modelColumn, true));
            }
        }
        return items;
    }

    /** The manifest table whose lower-cased name equals {@code lowerTable} (the diff's canonical form). */
    private static String resolveModelTable(SchemaManifest manifest, String lowerTable) {
        for (String table : manifest.businessTableColumns().keySet()) {
            if (table.toLowerCase(Locale.ROOT).equals(lowerTable)) {
                return table;
            }
        }
        return null;
    }

    /** The model-case column of {@code modelTable} whose lower-cased name equals {@code lowerColumn}. */
    private static String resolveModelColumn(SchemaManifest manifest, String modelTable, String lowerColumn) {
        for (String column : manifest.businessTableColumns().getOrDefault(modelTable, List.of())) {
            if (column.toLowerCase(Locale.ROOT).equals(lowerColumn)) {
                return column;
            }
        }
        return null;
    }

    /** Case-insensitive membership: {@code lowerTarget} is already lower-cased (the diff canonicalises
     * column names); the model-case manifest list entries are lower-cased for the comparison. */
    private static boolean containsIgnoreCase(List<String> values, String lowerTarget) {
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).equals(lowerTarget)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ADD COLUMN IF NOT EXISTS} (nullable) -&gt; bound-parameter {@code UPDATE ... WHERE c
     * IS NULL} -&gt; {@code SET NOT NULL} (skipped if already so). Every step idempotent-by-check
     * for crash recovery -- see {@link #applyRequiredFieldBackfills}'s class-level note.
     *
     * <p>No engine dialect branch is needed here (unlike rename/widen): {@code ADD COLUMN IF NOT
     * EXISTS} and {@code ALTER COLUMN ... SET NOT NULL} are both identical syntax on H2 and
     * Postgres, confirmed against a real H2 instance.
     */
    private static void addBackfillAndTightenColumn(Connection connection, String table, String column,
            String sqlType, String literalDefaultJson) throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeColumn = safeIdentifier(column);
        String safeType = safeSqlType(sqlType);
        try (PreparedStatement add = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ADD COLUMN IF NOT EXISTS " + safeColumn + " " + safeType)) {
            add.executeUpdate();
        }
        Object literalValue = decodeLiteralDefault(literalDefaultJson);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + safeTable + " SET " + safeColumn + " = ? WHERE " + safeColumn + " IS NULL")) {
            update.setObject(1, literalValue);
            update.executeUpdate();
        }
        if (!isColumnNotNull(connection, table, column)) {
            try (PreparedStatement notNull = connection.prepareStatement(
                    "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " SET NOT NULL")) {
                notNull.executeUpdate();
            }
        }
    }

    /**
     * Relaxes {@code NOT NULL} for every shared, live column whose field is no longer declared
     * {@code required} in the current model -- the mirror image of {@link #applyRequiredFieldBackfills}
     * (which tightens a column TO {@code NOT NULL}), closing a real gap found live while auditing
     * LNCH-1's remaining items (2026-07-19): a field relaxed from required to optional changes the
     * schema fingerprint (nullability is part of it), but {@link #classify} has no nullability
     * awareness at all -- only column names and SQL types -- so the live constraint was silently
     * never touched, leaving the database permanently unable to accept the null values the model now
     * allows. Always safe (relaxing a constraint can never lose data), so -- like renames -- this
     * runs unconditionally in {@link #beforeMigrate}, before {@link #classify} ever sees the table.
     *
     * <p>Idempotent by construction: live nullability is read fresh via {@link #isColumnNotNull} on
     * every call, so re-invoking against an already-relaxed column finds nothing to do.
     *
     * <p>Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #attemptInPlaceRenames}'s precedent.
     */
    void relaxNoLongerRequiredColumns(DataSource dataSource, SchemaManifest manifest) {
        record Relaxation(String table, String column) {
        }
        List<Relaxation> plan = new ArrayList<>();
        List<String> relaxed = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                List<String> expectedColumns = entry.getValue();
                Set<String> actualColumns = readActualColumns(metadata, table);
                if (actualColumns.isEmpty()) {
                    continue; // brand-new table -- V1's CREATE TABLE IF NOT EXISTS handles it, nothing to relax
                }
                Set<String> requiredColumns = new LinkedHashSet<>(
                        manifest.businessTableRequiredColumns().getOrDefault(table, List.of()));
                // The manifest's businessTableRequiredColumns is not guaranteed to list the primary
                // key explicitly (SchemaManifest has no per-table idColumn concept at all, and a
                // hand-built test manifest -- or conceivably a future generator gap -- may simply omit
                // it) -- read the LIVE primary key directly instead of trusting the manifest for this
                // one exclusion, since attempting to relax it is not just wrong but a hard SQL error
                // ("Column ... must not be nullable") on both H2 and Postgres, not a safe no-op.
                Set<String> primaryKeyColumns = readPrimaryKeyColumns(metadata, table);
                for (String column : expectedColumns) {
                    if (!actualColumns.contains(column) || requiredColumns.contains(column)
                            || primaryKeyColumns.contains(column)) {
                        continue; // not shared yet, still required, or the primary key -- not this method's concern
                    }
                    // LNCH-1 T1 (finding T-B1). The platform-managed columns are NEVER this method's
                    // concern, and excluding them is unambiguously safe: a MODEL field can never carry
                    // one of these names -- SchemaRealizationEmitter's RESERVED_BUSINESS_COLUMN_NAMES
                    // makes 'version'/'row_version'/'tenant_id' a hard GENERATION-time error -- so a
                    // live column with one of these names is always platform-owned, never a user's
                    // field that legitimately became optional.
                    //
                    // Without this, every fingerprint-changing boot stripped NOT NULL from all three:
                    // they appear in businessTableColumns (which is fullColumnNames, platform columns
                    // included) but never in businessTableRequiredColumns (which is model-derived), so
                    // they fell straight through. Only 'id' escaped, and only via the primary-key read
                    // above. That silently defeated tenant isolation (a NULL tenant_id is unreachable
                    // to every tenant-scoped read) and LNCH-16's compare-and-swap (a NULL row_version).
                    // Proven live: scenario 28. Repaired on existing databases by
                    // tightenPlatformColumns, which runs immediately after this pass.
                    if (isPlatformManagedColumn(column)) { // REG-6: via the single ColumnFacts-backed helper
                        continue;
                    }
                    if (!isColumnNotNull(connection, table, column)) {
                        continue; // already nullable -- idempotent no-op
                    }
                    plan.add(new Relaxation(table, column));
                }
            }
            // R4 (F5): one write-before-execute audit row for the whole relax pass.
            List<String> itemDetails = new ArrayList<>();
            for (Relaxation r : plan) {
                itemDetails.add("RELAX_NOT_NULL " + r.table() + "." + r.column());
            }
            recordStepPass(dataSource, manifest, "RELAX_NOT_NULL", itemDetails, () -> {
                for (Relaxation r : plan) {
                    executeDropNotNull(connection, r.table(), r.column());
                    relaxed.add(r.table() + "." + r.column());
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed relaxing no-longer-required column(s)", exception);
        }
        if (!relaxed.isEmpty()) {
            System.out.println("NPDev schema lifecycle: relaxed NOT NULL on no-longer-required column(s): " + relaxed);
        }
    }

    /** The platform default for a {@link #REPAIRABLE_PLATFORM_COLUMNS} entry, as a bound parameter
     * value (never string-concatenated into DDL). */
    private static Object platformColumnDefault(String column) {
        return switch (column) {
            case "version", "row_version" -> 0L;
            case "tenant_id" -> "default";
            default -> throw new IllegalStateException("No platform default is defined for column: " + column);
        };
    }

    /**
     * LNCH-1 T1 (finding T-B1), Half B -- the repair half. Restores {@code NOT NULL} on the
     * platform-managed columns of any table where it is missing, backfilling existing NULLs to the
     * fixed platform default first.
     *
     * <p><b>Why this is needed at all:</b> Half A (the exclusion in
     * {@link #relaxNoLongerRequiredColumns}) only stops the bleeding. Every app already upgraded by a
     * build carrying the old behaviour has permanently nullable {@code version}, {@code row_version}
     * and {@code tenant_id}, and nothing else would ever put them back.
     *
     * <p><b>Why it is safe to run unconditionally,</b> in the same place and for the same reason the
     * relax pass does (before {@link #classify} ever sees the table): tightening a platform column
     * whose default is fixed and known can never lose data -- the only writes are "give the rows that
     * have no value the value they would have been created with" and "re-assert a constraint the
     * generator's own fresh CREATE TABLE always emits". Leaving it to a later phase would also mean
     * the very next boot re-relaxed it.
     *
     * <p><b>Idempotent by construction,</b> exactly like {@link #addBackfillAndTightenColumn}: live
     * nullability is re-read via {@link #isColumnNotNull} on every call, so an already-strict column
     * is a no-op and produces no history row (see {@link #recordStepPass}'s empty-list contract --
     * no noise rows on converged boots).
     *
     * <p>A table whose platform column is <em>absent entirely</em> (a very old app) is not this
     * pass's concern -- the additive migration adds it. Only live, nullable columns are touched.
     *
     * <p>Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #relaxNoLongerRequiredColumns}'s own precedent.
     */
    void tightenPlatformColumns(DataSource dataSource, SchemaManifest manifest) {
        record Tightening(String table, String column, Object platformDefault) {
        }
        List<Tightening> plan = new ArrayList<>();
        List<String> tightened = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (String table : manifest.businessTableColumns().keySet()) {
                Set<String> actualColumns = readActualColumns(metadata, table);
                if (actualColumns.isEmpty()) {
                    continue; // brand-new table -- V1's CREATE TABLE IF NOT EXISTS emits it strict already
                }
                for (String column : REPAIRABLE_PLATFORM_COLUMNS) {
                    if (!actualColumns.contains(column)) {
                        continue; // absent entirely -- the additive migration's job, not this pass's
                    }
                    if (isColumnNotNull(connection, table, column)) {
                        continue; // already strict -- idempotent no-op
                    }
                    plan.add(new Tightening(table, column, platformColumnDefault(column)));
                }
            }
            // R4 (F5): one write-before-execute audit row for the whole repair pass -- the audit trail
            // must show that a repair happened, not merely that the columns are strict now.
            List<String> itemDetails = new ArrayList<>();
            for (Tightening item : plan) {
                itemDetails.add("TIGHTEN_PLATFORM_COLUMN " + item.table() + "." + item.column()
                        + " DEFAULT " + item.platformDefault());
            }
            recordStepPass(dataSource, manifest, "TIGHTEN_PLATFORM_COLUMNS", itemDetails, () -> {
                for (Tightening item : plan) {
                    executeBackfillAndSetNotNull(connection, item.table(), item.column(), item.platformDefault());
                    tightened.add(item.table() + "." + item.column());
                }
            });
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed restoring NOT NULL on platform-managed column(s)", exception);
        }
        if (!tightened.isEmpty()) {
            System.out.println("NPDev schema lifecycle: restored NOT NULL on platform-managed column(s) "
                    + "relaxed by an earlier build (LNCH-1 T-B1 repair): " + tightened);
        }
    }

    /**
     * Bound-parameter {@code UPDATE ... WHERE c IS NULL} -&gt; {@code SET NOT NULL}, for
     * {@link #tightenPlatformColumns}. The same two trailing steps as
     * {@link #addBackfillAndTightenColumn} (there is no {@code ADD COLUMN} step here: this pass only
     * ever runs against a column already proven live), and needs no engine dialect branch for the
     * same reason that method documents -- {@code ALTER COLUMN ... SET NOT NULL} is identical syntax
     * on H2 and Postgres.
     */
    private static void executeBackfillAndSetNotNull(Connection connection, String table, String column,
            Object platformDefault) throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeColumn = safeIdentifier(column);
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + safeTable + " SET " + safeColumn + " = ? WHERE " + safeColumn + " IS NULL")) {
            update.setObject(1, platformDefault);
            update.executeUpdate();
        }
        try (PreparedStatement notNull = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " SET NOT NULL")) {
            notNull.executeUpdate();
        }
    }

    /**
     * {@code ALTER COLUMN ... DROP NOT NULL} -- confirmed identical syntax on H2 and Postgres (both
     * accept the SQL-standard/Postgres-compatible form; H2 also accepts {@code SET NULL}, not used
     * here since {@code DROP NOT NULL} already works on both, matching
     * {@link #addBackfillAndTightenColumn}'s sibling {@code SET NOT NULL} call needing no engine
     * branch either). Verified against a real H2 instance
     * ({@code SchemaLifecycleExecutorNullabilityRelaxationTest}) and a real Postgres instance
     * ({@code SchemaLifecycleExecutorPostgresProofMatrixTest}).
     */
    private static void executeDropNotNull(Connection connection, String table, String column) throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeColumn = safeIdentifier(column);
        try (PreparedStatement statement = connection.prepareStatement(
                "ALTER TABLE " + safeTable + " ALTER COLUMN " + safeColumn + " DROP NOT NULL")) {
            statement.executeUpdate();
        }
    }

    /** Package-private (not private) so it is directly unit-testable, following this class's own
     * precedent ({@link #readActualColumns} etc.). Tries both case candidates for the same reason
     * every other live-introspection helper here does -- H2/Postgres report table names back with
     * different default case folding depending on configuration. */
    static Set<String> readPrimaryKeyColumns(DatabaseMetaData metadata, String table) throws SQLException {
        Set<String> columns = readPrimaryKeyColumns(metadata, table, table.toLowerCase(Locale.ROOT));
        if (columns.isEmpty()) {
            columns = readPrimaryKeyColumns(metadata, table, table.toUpperCase(Locale.ROOT));
        }
        return columns;
    }

    private static Set<String> readPrimaryKeyColumns(DatabaseMetaData metadata, String table, String candidate) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(null, null, candidate)) {
            while (resultSet.next()) {
                String columnName = resultSet.getString("COLUMN_NAME");
                if (columnName != null) {
                    columns.add(columnName.toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    /**
     * Decodes a manifest-carried literal default (JSON-encoded by the generator, see
     * {@code SchemaRealizationEmitter#columnDefaultLiterals}) back to a typed Java value
     * (String/Integer/Double/Boolean/null) for use as a JDBC bound parameter -- deliberately never
     * string-interpolated into SQL text (guardrail 11's identifier-safety discipline extended to
     * VALUE safety, per the plan).
     */
    private static Object decodeLiteralDefault(String literalDefaultJson) {
        try {
            return OBJECT_MAPPER.readValue(literalDefaultJson, Object.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed decoding literal default from schema realization manifest: " + literalDefaultJson, exception);
        }
    }

    private static boolean isColumnNotNull(Connection connection, String table, String column) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    String columnName = resultSet.getString("COLUMN_NAME");
                    if (columnName != null && columnName.equalsIgnoreCase(column)) {
                        String nullable = resultSet.getString("IS_NULLABLE");
                        return "NO".equalsIgnoreCase(nullable);
                    }
                }
            }
        }
        return false;
    }

    /**
     * LNCH-1 P5 (5.3). Called by {@link #beforeMigrate} unconditionally, once Phases 1-3's rename/
     * widening attempts have run their course but BEFORE {@link SchemaDeltaReport} (Phase 4's
     * destructive-report machinery) is ever invoked. Independently re-derives, per table, the
     * residual missing-column set (live columns vs. manifest-expected, minus anything explained by
     * a declared rename) -- the SAME computation {@link SchemaDeltaReport} makes, deliberately not
     * trusting {@link #classify}'s aggregate return value (which short-circuits to
     * {@code DESTRUCTIVE} the moment ANY table looks bad, without evaluating the rest) so this check
     * is correct regardless of what else is happening on other tables in the same boot.
     *
     * <p>A required column that is missing AND not additive-eligible is -- after the LNCH-1 P5
     * (5.3) change to {@code isAdditiveEligible} -- necessarily a REQUIRED bond/FK field (the only
     * remaining reason a required column can fail additive-eligibility; a plain required field is
     * always additive-eligible and is {@link #applyRequiredFieldBackfills}'s concern instead). A
     * bond has no literal-default backfill possible in v1 (its "default" would need to reference an
     * existing row's actual key), so this always refuses -- intercepting the case with a dedicated,
     * itemized message BEFORE {@link SchemaDeltaReport} would otherwise have to fall back to its
     * generic {@code Unknown} item kind for it (moving it out of that bucket, per the plan).
     */
    private void refuseIfRequiredBondColumnMissing(DataSource dataSource, SchemaManifest manifest, String stored,
            SchemaChangeClassification classification) {
        List<String> violations = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                List<String> requiredColumns = manifest.businessTableRequiredColumns().getOrDefault(table, List.of());
                if (requiredColumns.isEmpty()) {
                    continue;
                }
                Set<String> actualColumns = readActualColumns(metadata, table);
                if (actualColumns.isEmpty()) {
                    continue; // brand-new table -- nothing missing, nothing to refuse
                }
                Set<String> expected = new LinkedHashSet<>(entry.getValue());
                Set<String> extraInDb = new LinkedHashSet<>(actualColumns);
                extraInDb.removeAll(expected);
                Set<String> missingInDb = new LinkedHashSet<>(expected);
                missingInDb.removeAll(actualColumns);
                if (missingInDb.isEmpty()) {
                    continue;
                }
                // REG-6: "a required column missing AND not additive-eligible is a required bond" is
                // no longer re-derived inline here — it is ColumnFacts.bond(), computed once per column.
                Map<String, ColumnFacts> facts = columnFactsFor(manifest, table);
                Map<String, String> renames = manifest.businessTableRenamedColumns().getOrDefault(table, Map.of());
                RenameResolution.Result resolution = RenameResolution.resolve(missingInDb, extraInDb, renames);
                for (String column : resolution.remainingMissing()) {
                    ColumnFacts columnFacts = facts.get(column);
                    if (columnFacts != null && columnFacts.bond()) {
                        violations.add(table + "." + column);
                    }
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed inspecting live database for required bond field additions", exception);
        }
        if (violations.isEmpty()) {
            return;
        }
        writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification, null, null, "REFUSED");
        throw new IllegalStateException("Schema change adds new required bond/reference field(s) to table(s) with "
                + "existing data: " + violations + ". A required bond has no automatic literal-default backfill in "
                + "v1 (its value would need to reference an existing row's actual key) -- make the field optional, "
                + "or use the itemized destructive-acknowledgment path (LNCH-1 Phase 4) to recreate the table -- see "
                + "docs/SCHEMA_EVOLUTION.md#new-required-fields.");
    }

    /**
     * LNCH-1 P5 (5.1). Called by {@link #afterMigrate} on every boot (cheap, idempotent -- an
     * empty {@code businessTableUniqueConstraints} manifest, the common case pre-Phase-5, returns
     * immediately). Runs strictly AFTER {@code flyway.migrate()} has already applied the R__
     * additive-columns migration, so a unique constraint declared alongside a brand-new nullable
     * column always finds that column already present (new rows' NULLs never collide under
     * standard SQL unique-constraint semantics, so no dirty-data pre-check is even needed for that
     * case). For a constraint newly declared on an ALREADY-EXISTING column, pre-checks live data
     * for duplicate tuples (tenant-scoped or global, per {@link UniqueConstraintDecl#tenantScoped}
     * -- matching {@code SchemaRealizationEmitter#appendBusinessTable}'s fresh-CREATE rule) before
     * applying the constraint.
     *
     * <p><b>All-or-nothing per boot, same shape as {@link #applyRequiredFieldBackfills}:</b> pass 1
     * checks every declared constraint (read-only); if ANY table has violating data, throws before
     * applying ANY constraint this boot (so a clean table's constraint is not partially applied
     * while a dirty table's still needs attention -- consistent, easy-to-reason-about all-or-
     * nothing semantics). Pass 2 applies every clean, not-yet-applied constraint.
     *
     * <p>Idempotent by construction: {@link #constraintExists} re-checks
     * {@code INFORMATION_SCHEMA.TABLE_CONSTRAINTS} fresh on every call (not the same-boot pending
     * list only), so a crash between two constraint applications converges on the next boot without
     * re-attempting an already-applied constraint or erroring on a duplicate-name ADD CONSTRAINT.
     */
    private static void applyUniqueConstraints(DataSource dataSource, SchemaManifest manifest) {
        if (manifest.businessTableUniqueConstraints().isEmpty()) {
            return;
        }
        record PendingUniqueConstraint(String table, UniqueConstraintDecl decl) {
        }
        List<String> violationMessages = new ArrayList<>();
        List<PendingUniqueConstraint> toApply = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<UniqueConstraintDecl>> entry : manifest.businessTableUniqueConstraints().entrySet()) {
                String table = entry.getKey();
                Set<String> liveColumns = readActualColumns(metadata, table);
                if (liveColumns.isEmpty()) {
                    continue; // table not created yet this boot -- nothing to check/apply
                }
                for (UniqueConstraintDecl decl : entry.getValue()) {
                    if (!liveColumns.containsAll(decl.columns())) {
                        continue; // a declared column doesn't exist live yet -- nothing to apply this boot
                    }
                    if (constraintExists(connection, table, decl.name())) {
                        continue; // already applied -- idempotent no-op
                    }
                    List<String> duplicateKeys = findDuplicateKeys(connection, table, decl);
                    if (!duplicateKeys.isEmpty()) {
                        List<String> sample = duplicateKeys.size() > 20 ? duplicateKeys.subList(0, 20) : duplicateKeys;
                        violationMessages.add("table '" + table + "' unique constraint on ("
                                + String.join(", ", decl.columns()) + ")"
                                + (decl.tenantScoped() ? " [tenant-scoped]" : " [global]") + " has "
                                + duplicateKeys.size() + " violating tuple(s), e.g.: " + sample);
                        continue;
                    }
                    toApply.add(new PendingUniqueConstraint(table, decl));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed checking existing data against new unique constraint(s)", exception);
        }
        if (!violationMessages.isEmpty()) {
            // Fingerprint not yet written this boot (afterMigrate's own write happens after this
            // call returns) -- readFingerprint still reports the pre-this-attempt value, matching
            // every other refusal's "from_fingerprint" history-row convention.
            // R4 (F5): record the violation messages as items_json with a UNIQUE_PRECHECK label,
            // instead of an empty, classification-less REFUSED row.
            insertRawHistoryRow(dataSource, readFingerprint(dataSource), manifest.schemaFingerprint(),
                    "UNIQUE_PRECHECK", violationMessages, "REFUSED");
            throw new IllegalStateException("Schema change adds new unique constraint(s), but existing data "
                    + "violates them (LNCH-1 Phase 5). Resolve the duplicate row(s) first, or relax the constraint: "
                    + violationMessages + " -- see docs/SCHEMA_EVOLUTION.md#tightened-uniqueness.");
        }
        if (toApply.isEmpty()) {
            return;
        }
        List<String> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            for (PendingUniqueConstraint pending : toApply) {
                executeAddUniqueConstraint(connection, pending.table(), pending.decl());
                applied.add(pending.table() + "." + pending.decl().name());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying new unique constraint(s) (" + applied.size() + "/"
                    + toApply.size() + " applied before failure: " + applied + ")", exception);
        }
        System.out.println("NPDev schema lifecycle: applied new unique constraint(s): " + applied);
    }

    private static boolean constraintExists(Connection connection, String table, String constraintName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE UPPER(CONSTRAINT_NAME) = UPPER(?) AND UPPER(TABLE_NAME) = UPPER(?)")) {
            statement.setString(1, constraintName);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return true;
                }
            }
        }
        return indexExists(connection, table, constraintName);
    }

    /**
     * Ordinary (non-anchor) unique fields are bootstrapped by {@code SchemaRealizationEmitter} as a
     * plain {@code CREATE UNIQUE INDEX IF NOT EXISTS ux_<table>_<column>} -- not an
     * {@code ADD CONSTRAINT} -- under the exact same {@code ux_...} name this class later tries to
     * {@code ADD CONSTRAINT} with (see {@link #executeAddUniqueConstraint}). {@code
     * INFORMATION_SCHEMA.TABLE_CONSTRAINTS} only lists true constraints, not plain indexes, so on
     * Postgres a same-named index from V1's bootstrap is invisible to the check above and {@code ADD
     * CONSTRAINT} then collides with the index's underlying relation --
     * {@code ERROR: relation "ux_..." already exists}, fatal on Postgres (H2 tolerates the duplicate
     * name and silently no-ops, which is why this was missed until the first real Postgres boot).
     * {@link DatabaseMetaData#getIndexInfo} is standard JDBC metadata and portable across engines, so
     * it closes the gap without engine-specific SQL.
     */
    private static boolean indexExists(Connection connection, String table, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getIndexInfo(null, null, candidate, false, false)) {
                while (resultSet.next()) {
                    String existingIndexName = resultSet.getString("INDEX_NAME");
                    if (existingIndexName != null && existingIndexName.equalsIgnoreCase(indexName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code GROUP BY ... HAVING COUNT(*) > 1}, tenant-scoped or global per {@link
     * UniqueConstraintDecl#tenantScoped}. Rows where any declared unique column is {@code NULL} are
     * excluded -- standard SQL unique-constraint semantics never treat NULL-vs-NULL as a collision,
     * so a naive {@code GROUP BY} (which DOES treat NULLs as equal) would otherwise over-report.
     */
    private static List<String> findDuplicateKeys(Connection connection, String table, UniqueConstraintDecl decl) throws SQLException {
        String safeTable = safeIdentifier(table);
        List<String> groupColumns = new ArrayList<>();
        if (decl.tenantScoped()) {
            groupColumns.add("tenant_id");
        }
        List<String> notNullColumns = new ArrayList<>();
        for (String column : decl.columns()) {
            String safeColumn = safeIdentifier(column);
            groupColumns.add(safeColumn);
            notNullColumns.add(safeColumn);
        }
        String columnList = String.join(", ", groupColumns);
        StringBuilder sql = new StringBuilder("SELECT ").append(columnList).append(", COUNT(*) FROM ").append(safeTable);
        if (!notNullColumns.isEmpty()) {
            sql.append(" WHERE ");
            for (int i = 0; i < notNullColumns.size(); i++) {
                if (i > 0) {
                    sql.append(" AND ");
                }
                sql.append(notNullColumns.get(i)).append(" IS NOT NULL");
            }
        }
        sql.append(" GROUP BY ").append(columnList).append(" HAVING COUNT(*) > 1");
        List<String> duplicates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString());
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= groupColumns.size(); i++) {
                    values.add(String.valueOf(resultSet.getObject(i)));
                }
                duplicates.add("(" + String.join(", ", values) + ")");
            }
        }
        return duplicates;
    }

    private static void executeAddUniqueConstraint(Connection connection, String table, UniqueConstraintDecl decl) throws SQLException {
        String safeTable = safeIdentifier(table);
        String safeConstraint = safeIdentifier(decl.name());
        List<String> columns = new ArrayList<>();
        if (decl.tenantScoped()) {
            columns.add("tenant_id");
        }
        for (String column : decl.columns()) {
            columns.add(safeIdentifier(column));
        }
        String sql = "ALTER TABLE " + safeTable + " ADD CONSTRAINT " + safeConstraint
                + " UNIQUE (" + String.join(", ", columns) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /**
     * Classifies a fingerprint mismatch by inspecting every already-existing business table against
     * the manifest's expected columns:
     * <ul>
     *   <li>{@code SAFE_ADDITIVE} -- no column removed; every added column is one
     *       {@code R__npdev_schema_additive_columns.sql} can apply (a non-bond field). Unchanged from
     *       the original boolean check.</li>
     *   <li>{@code RENAME_DETECTED} -- every extra/missing column pair is explained by a field's
     *       declared {@code renamedFrom}: the live database still has the OLD column name, the model
     *       now declares the NEW one. Not auto-applied as an in-place rename (out of scope -- see the
     *       class-level note on {@link com.finalexec.db.SchemaLifecycleExecutor}); this only makes the
     *       boot log and the eventual destructive recreate correctly say "rename" instead of looking
     *       like an unrelated column swap.</li>
     *   <li>{@code TYPE_CHANGE_DETECTED} -- column names match exactly, but at least one shared
     *       column's live SQL type differs from what the model now declares.</li>
     *   <li>{@code DESTRUCTIVE} -- anything else (the original "return false" case).</li>
     * </ul>
     * New tables and unreachable databases are not safe-additive evidence either way and fall through
     * to the existing destructive-recreate-or-throw behavior (matches the original boolean check).
     */
    SchemaChangeClassification classify(DataSource dataSource, SchemaManifest manifest) {
        if (manifest.businessTableColumns().isEmpty()) {
            return SchemaChangeClassification.DESTRUCTIVE;
        }
        SchemaChangeClassification worst = SchemaChangeClassification.SAFE_ADDITIVE;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            // SER-P4.8: the COLUMN-level classification is now ClassificationReducer over the live
            // SchemaDiff -- proven equivalent to the former per-table name/type/rename loop across every
            // H2 + Postgres proof-matrix scenario (the classify self-check asserted this default-on until
            // this switch made it tautological). The empty-manifest guard (above) and the LNCH-1-B7
            // dropped-concept escalation (below) are NOT column-level and remain exactly as they were.
            worst = ClassificationReducer.reduce(
                    new com.finalexec.db.schemastate.SchemaDiffEngine().diff(
                            DesiredSchemaFactory.fromManifest(manifest),
                            ShadowParityProbe.scopeToOwnedBusinessTables(
                                    new com.finalexec.db.schemastate.CurrentSchemaReader().read(dataSource), manifest)),
                    DesiredSchemaFactory.fromManifest(manifest));
            // LNCH-1-B7: the loop above iterates ONLY manifest-declared tables, so a table the new
            // model no longer declares (a dropped CONCEPT) is invisible to it -- the boot classified
            // SAFE_ADDITIVE and the table survived forever, even though -PlanOnly had told the
            // operator it would be dropped and had demanded an acknowledgment token for it. Escalate
            // so the destructive path (SchemaDeltaReport + the token check) is actually entered.
            //
            // Scoped deliberately: only orphans we can PROVE NPDev owns -- i.e. tables a previous
            // successful boot recorded in ownedBusinessTables. A table someone created by hand in the
            // same schema is never in that set, so it can never be swept into the destructive path.
            // When no ownership has ever been recorded (legacy app on its first boot with this build)
            // readOwnedBusinessTables returns null and we keep the pre-B7 behaviour exactly.
            if (!droppedConceptTables(metadata, dataSource, manifest).isEmpty()) {
                return SchemaChangeClassification.DESTRUCTIVE;
            }
            return worst;
        } catch (SQLException exception) {
            return SchemaChangeClassification.DESTRUCTIVE;
        }
    }

    /**
     * LNCH-1-B7. Live tables that a previous successful boot recorded as NPDev-owned business tables
     * but which the CURRENT manifest no longer declares -- i.e. genuinely dropped concepts. Empty
     * when ownership was never recorded (see {@link #readOwnedBusinessTables}), which preserves the
     * pre-B7 behaviour for legacy apps and for unit tests that seed only a fingerprint.
     *
     * <p>The old side of a declared table rename is excluded for the same reason
     * {@code SchemaDeltaReport#itemizeTableLevelDiff} excludes it: a rename is not a drop.
     */
    static Set<String> droppedConceptTables(
            DatabaseMetaData metadata, DataSource dataSource, SchemaManifest manifest) throws SQLException {
        Set<String> owned = readOwnedBusinessTables(dataSource);
        if (owned == null || owned.isEmpty()) {
            return Set.of();
        }
        Set<String> stillDeclared = new LinkedHashSet<>();
        for (String table : manifest.businessTableColumns().keySet()) {
            stillDeclared.add(table.toLowerCase(Locale.ROOT));
        }
        Set<String> renameOldNames = new LinkedHashSet<>();
        for (String oldName : manifest.businessTableRenames().values()) {
            if (oldName != null) {
                renameOldNames.add(oldName.toLowerCase(Locale.ROOT));
            }
        }
        Set<String> liveTables = readActualTableNames(metadata);
        Set<String> dropped = new LinkedHashSet<>();
        for (String table : owned) {
            if (liveTables.contains(table) && !stillDeclared.contains(table) && !renameOldNames.contains(table)) {
                dropped.add(table);
            }
        }
        return dropped;
    }

    private static SchemaChangeClassification worse(SchemaChangeClassification a, SchemaChangeClassification b) {
        return a.severity() >= b.severity() ? a : b;
    }

    private static boolean hasTypeChange(
            DatabaseMetaData metadata,
            String table,
            Set<String> columns,
            Map<String, String> expectedTypes
    ) {
        Map<String, String> actualTypes = readActualColumnTypes(metadata, table);
        for (String column : columns) {
            String expected = normalizeSqlType(expectedTypes.get(column));
            String actual = normalizeSqlType(actualTypes.get(column));
            if (expected != null && actual != null && !expected.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Best-effort cross-engine type comparison: uppercases, treats JSON/JSONB as equivalent (H2
     * reports "JSON" for a column the manifest declares as Postgres-style "JSONB" -- see
     * {@code SchemaRealizationEmitter.renderType}), aliases H2's {@code "CHARACTER VARYING"} to
     * {@code "VARCHAR"}, and -- LNCH-1 Phase 3 fix, see below -- preserves any {@code (n)} /
     * {@code (p,s)} parenthetical instead of stripping it, so length/precision differences are no
     * longer invisible to callers that compare two normalized type strings for equality.
     *
     * <p><b>"CHARACTER VARYING" -> "VARCHAR":</b> confirmed empirically against the real H2 2.2.224
     * jar this project uses -- H2's live {@code DatabaseMetaData.getColumns} reports
     * {@code TYPE_NAME="CHARACTER VARYING"} for a column declared {@code VARCHAR(n)}, while
     * {@code SchemaRealizationEmitter}'s manifest always carries the canonical {@code "VARCHAR(n)"}
     * form (see {@code SqlTypeSupport.sqlType}). Without this alias, EVERY unchanged VARCHAR/string
     * column on H2 would be misclassified as a type change the moment any fingerprint mismatch
     * triggered a diff -- a pre-existing bug, uncovered by LNCH-1 Phase 1's rename+type-change
     * tests (which were the first to populate {@code businessTableColumnTypes} with realistic
     * values against a real H2 database). Every other type this project emits (BIGINT, UUID,
     * BOOLEAN, DATE, TIMESTAMP WITH TIME ZONE, NUMERIC, INTEGER, JSON) round-trips exactly and
     * needs no alias.
     *
     * <p><b>LNCH-1 Phase 3 fix -- length/precision was previously stripped unconditionally:</b>
     * before this fix, everything from the first {@code '('} onward was discarded before
     * comparing, so {@code "VARCHAR(255)"} and {@code "VARCHAR(20)"} both normalized to the
     * identical string {@code "VARCHAR"} and {@link #hasTypeChange} treated a VARCHAR-length or
     * NUMERIC-precision-only change (in EITHER direction, widening or narrowing) as no change at
     * all -- a real, silent data-truncation-risk gap, pinned by
     * {@code SchemaLifecycleExecutorTypeChangeLengthPrecisionGapTest}. {@link #readActualColumnTypes}
     * now appends the JDBC-reported {@code COLUMN_SIZE}/{@code DECIMAL_DIGITS} onto character and
     * exact-numeric type names before they ever reach this method, so the parenthetical this method
     * now preserves is meaningful on both sides of the comparison.
     */
    /** Package-private (not private): reused verbatim by {@link SchemaDeltaReport} (LNCH-1 Phase 4)
     * so type-equality comparisons in the delta report use the exact same normalization rules
     * {@link #classify} does, rather than a second, potentially-drifting copy. */
    static String normalizeSqlType(String sqlType) {
        // LNCH-1 remediation R1 (F3): the normalization rules moved verbatim to the DSL module's
        // com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization so the generator's MigrationPlanEmitter
        // normalizes model-declared type strings through the IDENTICAL bytecode this executor uses on
        // live JDBC types -- guaranteeing the DROP_COLUMN/NARROW_TYPE stable strings (and therefore the
        // acknowledgment token) are byte-identical on both producers. This method stays package-private
        // (and delegates) so its existing direct unit tests keep passing, now transitively testing the
        // shared class. See TokenAgreementConformanceTest for the permanent cross-producer ratchet.
        return SqlTypeNormalization.normalize(sqlType);
    }

    /** Package-private (not private): reused verbatim by {@link SchemaDeltaReport} (LNCH-1 Phase 4). */
    static Map<String, String> readActualColumnTypes(DatabaseMetaData metadata, String table) {
        Map<String, String> types = new LinkedHashMap<>();
        for (String candidate : List.of(table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT))) {
            try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
                while (resultSet.next()) {
                    String schema = resultSet.getString("TABLE_SCHEM");
                    if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    String typeName = resultSet.getString("TYPE_NAME");
                    int columnSize = resultSet.getInt("COLUMN_SIZE");
                    int decimalDigits = resultSet.getInt("DECIMAL_DIGITS");
                    types.put(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT),
                            qualifyTypeWithSize(typeName, columnSize, decimalDigits));
                }
            } catch (SQLException ignored) {
                // Fall through to the other case-sensitivity candidate.
            }
            if (!types.isEmpty()) {
                break;
            }
        }
        return types;
    }

    /**
     * LNCH-1 P3 prerequisite fix (see {@link #normalizeSqlType}): {@code TYPE_NAME} alone
     * ("VARCHAR", "NUMERIC") loses the length/precision JDBC reports separately via
     * {@code COLUMN_SIZE}/{@code DECIMAL_DIGITS}. Appends {@code "(n)"} for character types and
     * {@code "(p,s)"} for exact-numeric types, matching the canonical form
     * {@code SqlTypeSupport.sqlType(...)} emits into the manifest (e.g. {@code "VARCHAR(255)"},
     * {@code "NUMERIC(19,2)"}). Left bare for every other type this project emits (BIGINT, UUID,
     * BOOLEAN, DATE, TIMESTAMP, JSON) -- appending an incidental JDBC-reported size for those would
     * create a mismatch against the manifest's un-parameterized declaration.
     */
    private static String qualifyTypeWithSize(String typeName, int columnSize, int decimalDigits) {
        if (typeName == null || typeName.isBlank()) {
            return typeName;
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        if (upper.contains("CHAR")) {
            return typeName + "(" + columnSize + ")";
        }
        if (upper.equals("NUMERIC") || upper.equals("DECIMAL")) {
            return typeName + "(" + columnSize + "," + decimalDigits + ")";
        }
        return typeName;
    }

    enum SchemaChangeClassification {
        SAFE_ADDITIVE(0),
        RENAME_DETECTED(1),
        TYPE_CHANGE_DETECTED(2),
        DESTRUCTIVE(3);

        private final int severity;

        SchemaChangeClassification(int severity) {
            this.severity = severity;
        }

        int severity() {
            return severity;
        }
    }

    /** Package-private (not private): reused verbatim by {@link SchemaDeltaReport} (LNCH-1 Phase 4). */
    static Set<String> readActualColumns(DatabaseMetaData metadata, String table) throws SQLException {
        Set<String> columns = readActualColumns(metadata, table, table.toLowerCase(Locale.ROOT));
        if (columns.isEmpty()) {
            columns = readActualColumns(metadata, table, table.toUpperCase(Locale.ROOT));
        }
        return columns;
    }

    /**
     * LNCH-1 remediation R3 (F4): the schema-ahead-of-build check run on a fingerprint-MATCH boot.
     * For every table this build's manifest declares, confirms the live database still contains every
     * declared column (an entirely-missing table counts as all its columns missing -- the classic
     * "a newer build renamed this table away" case). Returns the {@code table.column} identifiers that
     * are missing, empty when the live schema fully satisfies this build.
     *
     * <p>Comparison is case-normalized to lower case exactly as {@link #readActualColumns} already
     * normalizes live column names, so an engine that folds identifier case (H2 upper, Postgres
     * lower) never produces a spurious "missing column". A metadata read failure is treated as
     * "cannot prove anything missing" (returns empty) -- this guard must never itself turn a healthy
     * matched-fingerprint boot into a refusal on a transient introspection hiccup.
     *
     * <h2>Two independent triggers (LNCH-1 hardening X3, finding X-B2)</h2>
     * A missing column is reported when EITHER fires:
     *
     * <p><b>Trigger A -- a missing NON-additive-eligible column.</b> An additive-eligible column is
     * one the ordinary R__ repeatable migration re-adds idempotently ({@code ADD COLUMN IF NOT
     * EXISTS}) on every boot, so a missing one is self-healing and not a schema-ahead symptom. This
     * was the original (and only) rule.
     *
     * <p><b>Why Trigger A alone was not enough:</b> it is very nearly dead in production. VERIFIED
     * against {@code SchemaRealizationEmitter#additiveColumnNames}/{@code #isAdditiveEligible}: a
     * real manifest marks EVERY ordinary non-bond field additive-eligible, plus {@code tenant_id} and
     * {@code row_version}. The only non-additive columns a real manifest has are {@code id},
     * {@code version}, and required/many-to-many bond columns. So for the case F4 was actually
     * written for -- a newer build renamed an ordinary field and was rolled back -- Trigger A could
     * never fire. The proof-matrix scenario it was tested by passed only because its fixture declared
     * NO additive columns, a shape no real manifest has.
     *
     * <p><b>Trigger B -- a missing additive-eligible column on a table that also has an UNEXPLAINED
     * EXTRA live column.</b> An unexplained extra is a live column that is (a) not declared by this
     * manifest for that table, (b) not a platform-managed column, and (c) not the old side of a
     * declared rename. {@code name} missing while {@code full_name} is present is exactly the
     * signature of "a newer build renamed this column and was then rolled back to this jar".
     *
     * <p>Trigger B is deliberately silent for the direct-call unit tests that motivated Trigger A's
     * exclusion in the first place: those declare SAFE_ADDITIVE columns that were never physically
     * added (they bypass {@code flyway.migrate()}), but they add no EXTRA live columns either, so
     * there is nothing to make the absence look like a rename.
     *
     * <p><b>Known residual limitation (documented, deliberately not fixed):</b> a newer build that
     * purely DROPPED a column leaves no extra column behind, so neither trigger fires. The old jar
     * boots and the R__ migration may re-add the column empty. See
     * {@code docs/SCHEMA_EVOLUTION.md#refusals-and-rollback}.
     */
    private static List<String> findSchemaAheadMissingColumns(DataSource dataSource, SchemaManifest manifest) {
        List<String> missing = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                Set<String> live = readActualColumns(metadata, table);
                if (live.isEmpty()) {
                    // The whole table is gone (e.g. a newer build renamed the CONCEPT away). Reporting
                    // every column individually here would bury the actual problem in noise. Safe on
                    // this code path specifically: the detector only runs when the stored fingerprint
                    // MATCHES this build, which means a previous boot already converged with this
                    // table present -- a genuine first boot returns earlier, on the blank-fingerprint
                    // branch, and never reaches this method.
                    missing.add(table + " (entire table missing)");
                    continue;
                }
                Set<String> declared = lowerCased(entry.getValue());
                Set<String> additiveEligible =
                        lowerCased(manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
                Set<String> renameOldNames =
                        lowerCased(manifest.businessTableRenamedColumns().getOrDefault(table, Map.of()).values());

                Set<String> unexplainedExtra = new LinkedHashSet<>(live);
                unexplainedExtra.removeAll(declared);
                unexplainedExtra.removeAll(platformManagedColumnNames()); // REG-6: single platform-column source
                unexplainedExtra.removeAll(renameOldNames);

                for (String column : entry.getValue()) {
                    String normalized = column.toLowerCase(Locale.ROOT);
                    if (live.contains(normalized)) {
                        continue;
                    }
                    if (!additiveEligible.contains(normalized)) {
                        missing.add(table + "." + column); // Trigger A
                    } else if (!unexplainedExtra.isEmpty()) {
                        missing.add(table + "." + column + " (additive-eligible, but this table also has "
                                + "unexplained live column(s) " + unexplainedExtra + " -- the signature of a "
                                + "rename by a newer build)"); // Trigger B
                    }
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }
        return missing;
    }

    /** REG-8 Trigger C: {@code npdev_schema_history}'s {@code (to_fingerprint, applied_at_utc)} pair
     * for the most recent row matching a query -- either a specific target fingerprint or the whole
     * table. */
    private record HistoryPoint(String toFingerprint, long appliedAtUtc) {
    }

    /**
     * REG-8 Trigger C (D4). Returns the history point that proves this database was migrated PAST
     * this build, or empty if nothing indicates that.
     *
     * <p>Deliberately NOT "does history contain a row for {@code stored} newer than THIS build's own
     * fingerprint" -- every ordinary forward upgrade would trip that (the current {@code stored}
     * value, by construction, always has a matching history row once any prior boot has gone through
     * the mismatch branch, INCLUDING a perfectly legitimate upgrade). The actual signal is narrower
     * and matches the register's own framing ("newer than what this build LAST WROTE"): has THIS
     * build's OWN target fingerprint ever been reached before (a row with {@code to_fingerprint =
     * manifest.schemaFingerprint()})? If never, this is a legitimate first-time deploy of this
     * fingerprint -- nothing to compare against, and Trigger C stays silent. If it HAS been reached
     * before, but a LATER row exists whose {@code to_fingerprint} differs, some other build has since
     * moved this exact database past the point this build itself last owned it.
     */
    private static Optional<HistoryPoint> databaseMigratedPastThisBuild(DataSource dataSource, SchemaManifest manifest) {
        Optional<Long> lastReachedByThisBuild = latestOutcomeTimestamp(dataSource, manifest.schemaFingerprint());
        if (lastReachedByThisBuild.isEmpty()) {
            return Optional.empty();
        }
        Optional<HistoryPoint> latestOverall = latestOutcomeOverall(dataSource);
        if (latestOverall.isPresent()
                && latestOverall.get().appliedAtUtc() > lastReachedByThisBuild.get()
                && !manifest.schemaFingerprint().equals(latestOverall.get().toFingerprint())) {
            return latestOverall;
        }
        return Optional.empty();
    }

    /** {@code APPLIED}/{@code MANUALLY_MARKED_DONE} are the outcomes that represent a REAL, recorded
     * advance of this database's schema state -- as opposed to {@code REFUSED}/{@code PARTIAL-CRASH}
     * (nothing durably changed) or the {@code EXTERNAL_*} outcomes (REG-7.1's read-only ownership
     * mode, which never writes {@code npdev_schema_metadata} and is not part of this fingerprint-
     * pointer lifecycle at all). */
    private static Optional<Long> latestOutcomeTimestamp(DataSource dataSource, String toFingerprint) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT applied_at_utc FROM " + HISTORY_TABLE + " WHERE to_fingerprint = ? AND outcome IN ("
                            + "'APPLIED', 'MANUALLY_MARKED_DONE') ORDER BY applied_at_utc DESC")) {
                statement.setString(1, toFingerprint);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static Optional<HistoryPoint> latestOutcomeOverall(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT to_fingerprint, applied_at_utc FROM " + HISTORY_TABLE + " WHERE outcome IN ("
                            + "'APPLIED', 'MANUALLY_MARKED_DONE') ORDER BY applied_at_utc DESC")) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next()
                            ? Optional.of(new HistoryPoint(resultSet.getString(1), resultSet.getLong(2)))
                            : Optional.empty();
                }
            }
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private static Set<String> lowerCased(Collection<String> values) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null) {
                normalized.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return normalized;
    }

    /**
     * An unqualified {@code getColumns(null, null, table, null)} also matches same-named system
     * views (e.g. H2's {@code information_schema.users}), which would pollute the comparison with
     * unrelated columns and make every additive change look unsafe. Skip any row whose reported
     * schema is one of the standard system schemas; NPDev never creates business tables there.
     */
    private static Set<String> readActualColumns(DatabaseMetaData metadata, String table, String candidate) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (ResultSet resultSet = metadata.getColumns(null, null, candidate, null)) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                if (schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return columns;
    }

    private void clearSchemaRealizationHistory(DataSource dataSource) {
        List<String> scripts = schemaRealizationScriptNames();
        if (scripts.isEmpty()) {
            throw new IllegalStateException("No schema-realization SQL files found after destructive recreation.");
        }
        try (Connection connection = dataSource.getConnection()) {
            for (String script : scripts) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM flyway_schema_history WHERE script = ?"
                )) {
                    statement.setString(1, script);
                    statement.executeUpdate();
                }
            }
            System.out.println("NPDev destructive schema recreation cleared Flyway history for schema-realization scripts: " + scripts);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed preparing schema realization reapply after destructive recreation", exception);
        }
    }

    private List<String> schemaRealizationScriptNames() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(SCHEMA_REALIZATION_LOCATION + "/*.sql");
            List<String> scripts = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && !filename.isBlank()) {
                    scripts.add(filename);
                }
            }
            Collections.sort(scripts);
            return scripts;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed listing schema-realization SQL files", exception);
        }
    }

    /** Package-private (not private): reused verbatim by {@link SchemaDeltaReport} (LNCH-1 Phase 4)
     * for its best-effort row-count queries -- guardrail 11's identifier-safety discipline applies
     * there exactly as it does everywhere else in this class. */
    static String safeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Unsafe table identifier in schema realization manifest: " + identifier);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #beforeMigrate}/{@link #classify}'s precedent (LNCH-1
     * Phase 5 -- the unique-constraint integration tests drive this method directly, since a full
     * {@code migrate(Flyway)} call needs a real Flyway configuration this test package does not
     * set up). */
    /**
     * Backward-compatible 2-arg overload for the many direct unit tests that drive
     * {@code afterMigrate} without going through {@link #migrate(Flyway, SchemaManifest)}. Reads the
     * pre-boot stored fingerprint itself (safe: no path writes the fingerprint before this point) and
     * derives {@code fingerprintChanged}, then delegates. Production always calls the 4-arg form with
     * the fingerprint read BEFORE {@code beforeMigrate} (LNCH-1 remediation R2).
     */
    void afterMigrate(DataSource dataSource, SchemaManifest manifest) {
        String storedAtBootStart = readFingerprint(dataSource);
        boolean fingerprintChanged = storedAtBootStart != null && !storedAtBootStart.isBlank()
                && !storedAtBootStart.equals(manifest.schemaFingerprint());
        afterMigrate(dataSource, manifest, storedAtBootStart, fingerprintChanged);
    }

    void afterMigrate(DataSource dataSource, SchemaManifest manifest, String storedAtBootStart, boolean fingerprintChanged) {
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
            applyRequiredFieldBackfills(dataSource, manifest, storedAtBootStart, null);
        }
        // LNCH-1 P5 (5.1): runs on every boot, after flyway.migrate() has already applied the R__
        // additive-columns migration, so a unique constraint declared alongside a brand-new column
        // always finds that column already present. Deliberately BEFORE the fingerprint write below
        // -- a refusal here (dirty data violating a newly-declared constraint) must leave the stored
        // fingerprint stale, so the next boot re-attempts instead of silently accepting the drift.
        applyUniqueConstraints(dataSource, manifest);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + METADATA_TABLE
                            + " (metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)"
            )) {
                statement.executeUpdate();
            }
            upsertMetadata(connection, FINGERPRINT_KEY, manifest.schemaFingerprint());
            // LNCH-1-B7: record which business tables THIS build owns, on the same lifecycle as the
            // fingerprint. A later build that no longer declares one of them can then prove the
            // orphaned table is a dropped CONCEPT (NPDev created it) rather than a table someone
            // created by hand in the same schema -- which is what makes acting on it safe.
            upsertMetadata(connection, OWNED_TABLES_KEY, ownedTablesJson(connection, dataSource, manifest));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed storing schema fingerprint", exception);
        }
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
            writeAppliedHistoryRow(dataSource, null, manifest.schemaFingerprint(), null);
        }
    }

    /** UPDATE-then-INSERT upsert against {@link #METADATA_TABLE} (no engine-specific UPSERT syntax --
     * identical on H2 and Postgres). The caller must have ensured the table exists. */
    private static void upsertMetadata(Connection connection, String key, String value) throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + METADATA_TABLE + " SET metadata_value = ?, updated_at_ms = ? WHERE metadata_key = ?"
        )) {
            statement.setString(1, value);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, key);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + METADATA_TABLE + " (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)"
            )) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    /**
     * The NPDev-owned business tables to record for the NEXT boot:
     *
     * <pre>owned = ( previouslyOwned  UNION  manifest.businessTableColumns().keySet() )  INTERSECT  liveTables</pre>
     *
     * <p><b>LNCH-1 hardening X2 (finding X-B3).</b> This used to be just the current manifest's
     * tables. That silently discarded ownership of an orphan that outlived a pass: if a table was
     * dropped from the model but still physically existed (it survived a whole-schema wipe, a crash,
     * or a refusal-then-partial state), the very next {@code afterMigrate} rewrote the ownership set
     * without it -- and since {@link #droppedConceptTables} can only act on tables it can PROVE
     * NPDev owns, that orphan became permanently un-droppable. The cleanup path was lost exactly in
     * the situation it exists for.
     *
     * <p>Why each half matters:
     * <ul>
     *   <li><b>Union with previous</b> keeps a surviving orphan recognisable as a dropped concept on
     *       a later boot, so a token-authorized upgrade can still clean it up.</li>
     *   <li><b>Intersect with live</b> keeps the set honest and bounded: anything actually gone drops
     *       out naturally, so the set never grows without limit and never claims ownership of
     *       something that no longer exists.</li>
     * </ul>
     *
     * <p>A hand-created table still can never enter the set -- entries only ever originate from a
     * manifest, and the intersection only ever removes. When {@code previouslyOwned} is {@code null}
     * ("never recorded"), the union degenerates to the current manifest, i.e. identical to the
     * pre-X2 behaviour for legacy apps.
     *
     * <p>On any failure this falls back to the pre-X2 behaviour (current manifest only) rather than
     * writing {@code "[]"} as it used to: losing ownership wholesale is the precise failure mode this
     * method exists to prevent, and an empty set would ALSO silently disable {@link #droppedConceptTables}.
     * Reuses the caller's already-open {@link Connection} for the metadata read.
     */
    private static String ownedTablesJson(Connection connection, DataSource dataSource, SchemaManifest manifest) {
        List<String> fromManifest = new ArrayList<>();
        for (String table : manifest.businessTableColumns().keySet()) {
            if (table != null && !table.isBlank()) {
                fromManifest.add(table.toLowerCase(Locale.ROOT));
            }
        }
        try {
            Set<String> candidate = new LinkedHashSet<>();
            Set<String> previouslyOwned = readOwnedBusinessTables(dataSource);
            if (previouslyOwned != null) {
                candidate.addAll(previouslyOwned);
            }
            candidate.addAll(fromManifest);

            Set<String> liveTables = readActualTableNames(connection.getMetaData());
            List<String> owned = new ArrayList<>();
            for (String table : candidate) {
                if (liveTables.contains(table)) {
                    owned.add(table);
                }
            }
            Collections.sort(owned);
            return OBJECT_MAPPER.writeValueAsString(owned);
        } catch (Exception exception) {
            Collections.sort(fromManifest);
            try {
                return OBJECT_MAPPER.writeValueAsString(fromManifest);
            } catch (Exception fallbackFailure) {
                return "[]";
            }
        }
    }

    /**
     * LNCH-1-B7. The business tables the PREVIOUS successful boot recorded as NPDev-owned, or
     * {@code null} when nothing has ever been recorded (a legacy app that has not yet booted on a
     * build carrying this mechanism, or a unit test that seeds only a fingerprint).
     *
     * <p>{@code null} is deliberately distinct from "empty": absent means "ownership unknown", and
     * every caller must then fall back to its pre-B7 behaviour rather than assume a table is
     * droppable. This is the guard that keeps a hand-created table in the same schema from ever
     * being classified as a dropped concept.
     */
    static Set<String> readOwnedBusinessTables(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM " + METADATA_TABLE + " WHERE metadata_key = ?"
             )) {
            statement.setString(1, OWNED_TABLES_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String raw = resultSet.getString(1);
                if (raw == null || raw.isBlank()) {
                    return null;
                }
                Set<String> owned = new LinkedHashSet<>();
                for (JsonNode node : OBJECT_MAPPER.readTree(raw)) {
                    String name = node.asText("");
                    if (!name.isBlank()) {
                        owned.add(name.toLowerCase(Locale.ROOT));
                    }
                }
                return owned;
            }
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * LNCH-1 Phase 4 (task 4.4). Idempotent, self-bootstrapped exactly like {@link #METADATA_TABLE}
     * -- called at the top of every history write so a fresh app (no prior destructive/rename/
     * widening pass) still gets the table before its first row.
     */
    private static void ensureHistoryTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE
                        + " (id TEXT PRIMARY KEY, applied_at_utc BIGINT NOT NULL, from_fingerprint TEXT, "
                        + "to_fingerprint TEXT, classification TEXT, items_json TEXT, ack_token_used TEXT, "
                        + "outcome TEXT NOT NULL)"
        )) {
            statement.executeUpdate();
        }
    }

    /**
     * Every destructive item's {@link SchemaDeltaItem#displayString()}, JSON-serialized as a
     * plain array of strings -- already in {@link SchemaDeltaReport}'s deterministic sorted order,
     * so this column's content is itself order-independent for the same underlying diff. Uses the
     * DISPLAY form (not the hashed stable string) so a {@code DROP_TABLE} row keeps its human-facing
     * row-count metadata in {@code items_json}, even though that count is out of the ack-token hash
     * (LNCH-1 remediation F2).
     */
    private static String itemsJson(SchemaDeltaReport report) {
        try {
            return OBJECT_MAPPER.writeValueAsString(report == null ? List.of() : report.displayStrings());
        } catch (Exception exception) {
            return "[]";
        }
    }

    /**
     * The single, shared INSERT used by every history-row writer below. A broken write is caught
     * and logged here, never propagated -- a history-table failure (unreachable metadata table,
     * disk full) must never mask or replace the actual migration outcome (a thrown refusal, or a
     * successfully-applied change) -- "if the metadata table is reachable" per the plan.
     *
     * @return the row's generated id, or {@code null} if the write itself failed (callers must
     *         treat a {@code null} id as "there is no row to later update").
     */
    private static String insertHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed,
            String outcome
    ) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + HISTORY_TABLE + " (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, fromFingerprint);
                statement.setString(4, toFingerprint);
                statement.setString(5, classification == null ? null : classification.name());
                statement.setString(6, itemsJson(report));
                if (ackTokenUsed == null || ackTokenUsed.isBlank()) {
                    statement.setNull(7, Types.VARCHAR);
                } else {
                    statement.setString(7, ackTokenUsed);
                }
                statement.setString(8, outcome);
                statement.executeUpdate();
            }
            return id;
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed writing npdev_schema_history row (continuing -- "
                    + "a broken history write must never block or mask the actual migration outcome): "
                    + exception.getMessage());
            return null;
        }
    }

    /** REFUSED / arbitrary-outcome one-shot write (no later update). Used by refusals ("nothing
     * was attempted, so INSERT directly with outcome = REFUSED", per the plan) and by the safe
     * (additive/rename/widening) paths, where write-then-immediately-mark-applied is fine since
     * those steps are individually idempotent-by-check -- no crash-window concern. */
    private static void writeHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed,
            String outcome
    ) {
        insertHistoryRow(dataSource, fromFingerprint, toFingerprint, classification, report, ackTokenUsed, outcome);
    }

    /** Safe-path (SAFE_ADDITIVE / RENAME_DETECTED / TYPE_CHANGE_DETECTED-resolved-by-widening)
     * history row: no destructive items to report (an empty items list), no acknowledgment token,
     * outcome APPLIED directly -- see {@link #writeHistoryRow}'s javadoc for why a single INSERT is
     * sufficient here. */
    private static void writeAppliedHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaChangeClassification classification
    ) {
        insertHistoryRow(dataSource, fromFingerprint, toFingerprint, classification, null, null, "APPLIED");
    }

    /** Destructive-path PENDING write ("write-before-execute", §2.4): inserted with
     * {@code outcome = 'PARTIAL-CRASH'} before any DDL runs. */
    private static String insertPendingHistoryRow(
            DataSource dataSource,
            String fromFingerprint,
            String toFingerprint,
            SchemaChangeClassification classification,
            SchemaDeltaReport report,
            String ackTokenUsed
    ) {
        return insertHistoryRow(dataSource, fromFingerprint, toFingerprint, classification, report, ackTokenUsed, "PARTIAL-CRASH");
    }

    /** Destructive-path "update-after" (§2.4): flips a PARTIAL-CRASH row to APPLIED once every
     * item in the pass has executed successfully. A {@code null} id (the pending insert itself
     * failed) is a safe no-op -- there is no row to update. */
    private static void markHistoryRowApplied(DataSource dataSource, String historyId) {
        if (historyId == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE " + HISTORY_TABLE + " SET outcome = ? WHERE id = ?")) {
            statement.setString(1, "APPLIED");
            statement.setString(2, historyId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            System.out.println("NPDev schema lifecycle: failed updating npdev_schema_history outcome to APPLIED "
                    + "for row " + historyId + " (the DDL itself already succeeded -- only the audit row write "
                    + "failed): " + exception.getMessage());
        }
    }

    /** A DDL action that may throw {@link SQLException}, for {@link #recordStepPass}. */
    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    /** {@code items_json} for a plain list of human-readable step-item strings (the per-pass
     * write-before-execute rows, LNCH-1 remediation R4 / F5), rather than a {@link SchemaDeltaReport}. */
    private static String itemsJson(List<String> itemDetails) {
        try {
            return OBJECT_MAPPER.writeValueAsString(itemDetails == null ? List.of() : itemDetails);
        } catch (Exception exception) {
            return "[]";
        }
    }

    /**
     * LNCH-1 remediation R4 (F5): write-before-execute history for a single mutating PASS (a batch of
     * renames/relaxations/widenings/backfills). Semantics per plan §2.4: if {@code itemDetails} is
     * empty, run and write NOTHING (no noise rows on no-op boots); otherwise insert one
     * {@code PARTIAL-CRASH} row (classification = {@code stepName}, {@code items_json} = the item
     * detail list) BEFORE running the DDL, then flip it to {@code APPLIED} after every item executes.
     * A crash mid-pass leaves the row at {@code PARTIAL-CRASH} -- an accurate record that this pass
     * did not finish. The from-fingerprint is read live (still the pre-boot value at this point, since
     * {@code afterMigrate} writes the new one only at the very end).
     */
    private void recordStepPass(DataSource dataSource, SchemaManifest manifest, String stepName,
            List<String> itemDetails, SqlRunnable ddl) throws SQLException {
        if (itemDetails == null || itemDetails.isEmpty()) {
            return;
        }
        String from = readFingerprint(dataSource);
        String historyId = insertStepPendingRow(dataSource, from, manifest.schemaFingerprint(), stepName, itemDetails);
        ddl.run();
        markHistoryRowApplied(dataSource, historyId);
    }

    /** Inserts a {@code PARTIAL-CRASH} history row carrying a raw step name (classification) and a
     * raw item-detail list (items_json), for {@link #recordStepPass}. Follows {@link #insertHistoryRow}'s
     * broken-write-never-propagates discipline: a failed audit write returns {@code null} (a safe
     * no-op for the later {@link #markHistoryRowApplied}) and never blocks the DDL it records. */
    private static String insertStepPendingRow(DataSource dataSource, String fromFingerprint,
            String toFingerprint, String stepName, List<String> itemDetails) {
        return insertRawHistoryRow(dataSource, fromFingerprint, toFingerprint, stepName, itemDetails, "PARTIAL-CRASH");
    }

    /** Like {@link #insertHistoryRow} but writes a RAW classification string (a step name or a
     * pre-check label, not a {@link SchemaChangeClassification} enum) and a raw item-detail list --
     * used by {@link #recordStepPass} (PARTIAL-CRASH) and by the unique-precheck refusal (REFUSED),
     * both LNCH-1 remediation R4 / F5. Same broken-write-never-propagates discipline. */
    private static String insertRawHistoryRow(DataSource dataSource, String fromFingerprint,
            String toFingerprint, String classificationText, List<String> itemDetails, String outcome) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = dataSource.getConnection()) {
            ensureHistoryTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + HISTORY_TABLE + " (id, applied_at_utc, from_fingerprint, to_fingerprint, "
                            + "classification, items_json, ack_token_used, outcome) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            )) {
                statement.setString(1, id);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, fromFingerprint);
                statement.setString(4, toFingerprint);
                statement.setString(5, classificationText);
                statement.setString(6, itemsJson(itemDetails));
                statement.setNull(7, Types.VARCHAR);
                statement.setString(8, outcome);
                statement.executeUpdate();
            }
            return id;
        } catch (Exception exception) {
            System.out.println("NPDev schema lifecycle: failed writing npdev_schema_history detail row (continuing -- "
                    + "a broken history write must never block the actual migration): " + exception.getMessage());
            return null;
        }
    }

    private static String readFingerprint(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT metadata_value FROM " + METADATA_TABLE + " WHERE metadata_key = ?"
             )) {
            statement.setString(1, FINGERPRINT_KEY);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        } catch (SQLException exception) {
            return null;
        }
    }

    public static SchemaManifest loadManifest() {
        try {
            ClassPathResource resource = new ClassPathResource("npdev/db/schema-realization-manifest.json");
            if (!resource.exists()) {
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(resource.getInputStream());
            JsonNode lifecycle = root.path("schemaLifecycle");
            return new SchemaManifest(
                    root.path("engine").asText(""),
                    root.path("storageMode").asText(""),
                    root.path("physicalDatabase").asBoolean(false),
                    root.path("schemaFingerprint").asText(""),
                    strings(root.path("internalTables")),
                    strings(root.path("businessTables")),
                    stringListMap(root.path("businessTableColumns")),
                    stringListMap(root.path("businessTableAdditiveColumns")),
                    stringMapMap(root.path("businessTableColumnTypes")),
                    stringMapMap(root.path("businessTableRenamedColumns")),
                    stringMap(root.path("businessTableRenames")),
                    lifecycle.path("allowDestructiveRecreate").asBoolean(false),
                    lifecycle.path("strategy").asText(""),
                    lifecycle.path("scope").asText(""),
                    lifecycle.path("destructiveRecreateConfirmation").asText(""),
                    // LNCH-1 Phase 4 (task 4.2/4.3): the itemized destructive-acknowledgment token.
                    // Absent from every manifest emitted before this phase (and from every real
                    // manifest until Phase 6 wires -AcknowledgeDestructive into the emitter) --
                    // asText("") correctly defaults to "", which never equals a real computed
                    // token, so pre-Phase-6 apps are unaffected until an author opts in.
                    root.path("destructiveAcknowledgment").asText(""),
                    // LNCH-1 Phase 5. Absent from every manifest emitted before this phase --
                    // each parser defaults to an empty map/list, so pre-Phase-5 apps are unaffected
                    // (no required-column/default/unique-constraint data means the new executor
                    // steps below all find nothing to do and no-op cleanly).
                    stringListMap(root.path("businessTableRequiredColumns")),
                    stringMapMap(root.path("businessTableColumnDefaultLiterals")),
                    stringListMap(root.path("businessTableExpressionDefaultColumns")),
                    uniqueConstraintListMap(root.path("businessTableUniqueConstraints")),
                    // LNCH-1 Phase 6 (task 6.3): the destructive-item stable strings from a migration
                    // plan computed at generation time (see SchemaRealizationEmitter#emit's 5-arg
                    // overload) -- empty when no plan was computed this generation pass (the ordinary
                    // case for every app until a future Build-NpdevApp.ps1 -PlanOnly/-Upgrade wires
                    // --schemaMigrationPlanOut through). Absent from every manifest emitted before
                    // this phase -- strings() defaults to an empty list, so pre-Phase-6 apps are
                    // unaffected (the agreement-check enrichment below simply has nothing to compare).
                    strings(root.path("migrationPlanItemStableStrings")),
                    // REG-7.1: absent from every manifest emitted before this field existed --
                    // asText("NpdevManaged") defaults to today's only behavior, so a pre-existing
                    // manifest is unaffected (same pattern as destructiveAcknowledgment above).
                    lifecycle.path("ownership").asText("NpdevManaged")
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Failed loading schema realization manifest", exception);
        }
    }

    private static List<String> strings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, List<String>> stringListMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> out.put(field.getKey(), strings(field.getValue())));
        return Map.copyOf(out);
    }

    private static Map<String, Map<String, String>> stringMapMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> out.put(field.getKey(), stringMap(field.getValue())));
        return Map.copyOf(out);
    }

    private static Map<String, String> stringMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(field -> out.put(field.getKey(), field.getValue().asText("")));
        return Map.copyOf(out);
    }

    /**
     * LNCH-1 P5 (5.1): table -> its declared unique constraints (name/columns/tenant-scoping), as
     * emitted by {@code SchemaRealizationEmitter#collectUniqueConstraints}.
     */
    private static Map<String, List<UniqueConstraintDecl>> uniqueConstraintListMap(JsonNode object) {
        if (object == null || !object.isObject()) {
            return Map.of();
        }
        Map<String, List<UniqueConstraintDecl>> out = new LinkedHashMap<>();
        object.fields().forEachRemaining(entry -> {
            List<UniqueConstraintDecl> declarations = new ArrayList<>();
            for (JsonNode item : entry.getValue()) {
                String name = item.path("name").asText("");
                boolean tenantScoped = item.path("tenantScoped").asBoolean(true);
                List<String> columns = strings(item.path("columns"));
                if (!name.isBlank() && !columns.isEmpty()) {
                    declarations.add(new UniqueConstraintDecl(name, columns, tenantScoped));
                }
            }
            out.put(entry.getKey(), List.copyOf(declarations));
        });
        return Map.copyOf(out);
    }

    /**
     * LNCH-1 P5 (5.1): a single declared unique constraint on a business table -- mirrors
     * {@code SchemaRealizationEmitter}'s generation-time record of the same name/shape.
     * {@code tenantScoped} decides whether the runtime-applied constraint (and its dirty-data
     * pre-check) is scoped to {@code (tenant_id, ...columns)} or just {@code (...columns)}
     * (the connectable-anchor case, which must stay globally unique -- it is an FK target).
     */
    record UniqueConstraintDecl(String name, List<String> columns, boolean tenantScoped) {
    }

    public record SchemaManifest(
            String engine,
            String storageMode,
            boolean physicalDatabase,
            String schemaFingerprint,
            List<String> internalTables,
            List<String> businessTables,
            Map<String, List<String>> businessTableColumns,
            Map<String, List<String>> businessTableAdditiveColumns,
            Map<String, Map<String, String>> businessTableColumnTypes,
            Map<String, Map<String, String>> businessTableRenamedColumns,
            Map<String, String> businessTableRenames,
            boolean allowDestructiveRecreate,
            String strategy,
            String scope,
            String destructiveRecreateConfirmation,
            String destructiveAcknowledgment,
            // LNCH-1 Phase 5.
            Map<String, List<String>> businessTableRequiredColumns,
            Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
            Map<String, List<String>> businessTableExpressionDefaultColumns,
            Map<String, List<UniqueConstraintDecl>> businessTableUniqueConstraints,
            // LNCH-1 Phase 6 (task 6.3).
            List<String> planItemStableStrings,
            // REG-7.1: whether NPDev owns this app's database schema DDL. "NpdevManaged" (the
            // default, today's only behavior) or "ExternallyManaged" (NPDev issues no DDL, only
            // verifies compatibility). Raw string (not an enum) to match every other lifecycle field
            // here, all of which are parsed straight from JSON text.
            String ownership
    ) {
        /** Backward-compatible convenience constructor matching this record's PRE-Phase-6 20-arg
         * shape (every field above {@code planItemStableStrings}) -- defaults
         * {@code planItemStableStrings} to an empty list and {@code ownership} to
         * {@code "NpdevManaged"} so the ~20 existing hand-built {@code SchemaManifest} constructions
         * across this package's test suite (predating task 6.3 and REG-7.1 alike) keep compiling
         * unchanged. Only {@link #loadManifest} needs to populate both new fields for real, via the
         * canonical (22-arg) constructor above. */
        public SchemaManifest(
                String engine,
                String storageMode,
                boolean physicalDatabase,
                String schemaFingerprint,
                List<String> internalTables,
                List<String> businessTables,
                Map<String, List<String>> businessTableColumns,
                Map<String, List<String>> businessTableAdditiveColumns,
                Map<String, Map<String, String>> businessTableColumnTypes,
                Map<String, Map<String, String>> businessTableRenamedColumns,
                Map<String, String> businessTableRenames,
                boolean allowDestructiveRecreate,
                String strategy,
                String scope,
                String destructiveRecreateConfirmation,
                String destructiveAcknowledgment,
                Map<String, List<String>> businessTableRequiredColumns,
                Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
                Map<String, List<String>> businessTableExpressionDefaultColumns,
                Map<String, List<UniqueConstraintDecl>> businessTableUniqueConstraints
        ) {
            this(engine, storageMode, physicalDatabase, schemaFingerprint, internalTables, businessTables,
                    businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                    businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate, strategy, scope,
                    destructiveRecreateConfirmation, destructiveAcknowledgment, businessTableRequiredColumns,
                    businessTableColumnDefaultLiterals, businessTableExpressionDefaultColumns,
                    businessTableUniqueConstraints, List.of(), "NpdevManaged");
        }

        /** Backward-compatible convenience constructor matching this record's PRE-REG-7.1 21-arg
         * shape (every field through {@code planItemStableStrings}, LNCH-1 Phase 6) -- defaults
         * {@code ownership} to {@code "NpdevManaged"} so the existing hand-built test constructions
         * that already pass {@code planItemStableStrings} explicitly keep compiling unchanged. */
        public SchemaManifest(
                String engine,
                String storageMode,
                boolean physicalDatabase,
                String schemaFingerprint,
                List<String> internalTables,
                List<String> businessTables,
                Map<String, List<String>> businessTableColumns,
                Map<String, List<String>> businessTableAdditiveColumns,
                Map<String, Map<String, String>> businessTableColumnTypes,
                Map<String, Map<String, String>> businessTableRenamedColumns,
                Map<String, String> businessTableRenames,
                boolean allowDestructiveRecreate,
                String strategy,
                String scope,
                String destructiveRecreateConfirmation,
                String destructiveAcknowledgment,
                Map<String, List<String>> businessTableRequiredColumns,
                Map<String, Map<String, String>> businessTableColumnDefaultLiterals,
                Map<String, List<String>> businessTableExpressionDefaultColumns,
                Map<String, List<UniqueConstraintDecl>> businessTableUniqueConstraints,
                List<String> planItemStableStrings
        ) {
            this(engine, storageMode, physicalDatabase, schemaFingerprint, internalTables, businessTables,
                    businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                    businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate, strategy, scope,
                    destructiveRecreateConfirmation, destructiveAcknowledgment, businessTableRequiredColumns,
                    businessTableColumnDefaultLiterals, businessTableExpressionDefaultColumns,
                    businessTableUniqueConstraints, planItemStableStrings, "NpdevManaged");
        }

        boolean destructiveAllowed() {
            return "DropAndRecreateOnStructureChange".equals(strategy)
                    && allowDestructiveRecreate
                    && "NpdevOwnedTablesOnly".equals(scope)
                    && "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED".equals(destructiveRecreateConfirmation);
        }

        /** REG-7.1: true when this app declares NPDev does not own the database schema -- the
         * executor must never issue schema DDL and instead only verifies live compatibility. Blank/
         * absent (every manifest predating this field) is "NpdevManaged", so this is false by
         * default. */
        boolean externallyManaged() {
            return "ExternallyManaged".equals(ownership);
        }
    }

    /** Package-private (not private): its fields are asserted on directly by the LNCH-1 Phase 4
     * destructive-path integration tests. */
    record DestructiveRecreation(boolean performed, boolean safeAdditive, List<String> droppedTables) {
        static DestructiveRecreation none() {
            return new DestructiveRecreation(false, false, List.of());
        }

        static DestructiveRecreation safeAdditiveOutcome() {
            return new DestructiveRecreation(false, true, List.of());
        }
    }
}
