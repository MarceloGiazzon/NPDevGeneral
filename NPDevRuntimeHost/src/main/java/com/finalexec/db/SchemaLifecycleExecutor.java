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
import org.springframework.beans.factory.annotation.Autowired;
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
    /**
     * From the dialect: {@code pg_catalog} is Postgres-only, so this hand-written pair was already
     * wrong for any second engine (MySQL's are mysql / performance_schema / sys). It was also
     * spelled identically in two files, which is the twin-pair shape this repo has been bitten by.
     */
    private static final Set<String> SYSTEM_SCHEMAS = SqlDialects.active().systemSchemas();
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
     *
     * <p>Package-private (not private): reused by {@link PlatformColumnPass} (T2.B.4 split).
     */
    static final List<String> REPAIRABLE_PLATFORM_COLUMNS =
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

    // npdev_schema_history's HISTORY_TABLE constant (self-bootstrapped alongside METADATA_TABLE,
    // NOT routed through the generator's internalTables catalog) moved to SchemaHistoryStore
    // alongside the read/write machinery that is its only user (T2.B.4 pure mechanical split).

    /**
     * REG-39 layer 3: optional so every existing {@code SchemaLifecycleExecutor*Test}'s {@code new
     * SchemaLifecycleExecutor()} (this class has no constructor -- dozens of call sites) keeps working
     * untouched. {@code null} here just means "-ImpactOnly ran with no compiled model available," and
     * {@link SchemaImpactFacade#forLiveDatabase(DataSource, CompiledModel)} already treats {@code null}
     * as "skip the identity-pack drift check," same as an app that doesn't use the identity pack.
     */
    @Autowired(required = false)
    private CompiledModel compiledModel;

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
    /** SER-P6.4: compute the REPORT_ONLY exit code (read-only, zero writes) and print the impact table.
     *  0 = NO_CHANGES/SAFE, 2 = NEEDS_ATTENTION, 3 = DESTRUCTIVE. Package-private for direct unit testing;
     *  the JVM-exit shell is the only caller in production. */
    int reportOnlyExitCode(DataSource dataSource) {
        SchemaImpactFacade.Result result = SchemaImpactFacade.forLiveDatabase(dataSource, compiledModel);
        System.out.println(ImpactReportText.render(result.report(), result.fromFingerprint(),
                result.toFingerprint(), result.ackToken()));
        return codeFor(result.report().verdict());
    }

    /** SER-P6.4: the verdict-to-exit-code mapping, extracted so tests can assert it directly without
     *  going through a DataSource. 0 = NO_CHANGES/SAFE, 2 = NEEDS_ATTENTION, 3 = DESTRUCTIVE. */
    static int codeFor(ImpactReport.Verdict verdict) {
        return switch (verdict) {
            case NO_CHANGES, SAFE -> 0;
            case NEEDS_ATTENTION -> 2;
            case DESTRUCTIVE -> 3;
        };
    }

    /**
     * Pin {@link SqlDialects#active()} from the engine this app was GENERATED for.
     *
     * <p>Deliberately silent when the manifest names no engine or names {@code InMemory}: neither
     * has SQL, and forcing a dialect there would be inventing an answer. Any other unrecognised
     * value is left alone too -- {@code StorageDialectInitializer} is the component that refuses an
     * unknown engine loudly, and duplicating that refusal here would give two different messages for
     * one condition.
     */
    private static void pinDialectFromManifest(SchemaManifest manifest) {
        String engine = manifest == null ? null : manifest.engine();
        if (engine == null || engine.isBlank()) {
            return;
        }
        String dialectName = switch (engine.trim().toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> "postgres";
            case "h2local", "h2server", "h2" -> "h2";
            case "mysql", "mariadb" -> "mysql";
            case "sqlserver", "mssql" -> "sqlserver";
            default -> null;
        };
        if (dialectName == null) {
            return;
        }
        com.npdev.kernel.storage.sql.SqlDialect dialect;
        try {
            dialect = com.npdev.kernel.storage.sql.SqlDialects.forName(dialectName);
        } catch (RuntimeException unknown) {
            return;
        }
        if (!dialect.name().equals(com.npdev.kernel.storage.sql.SqlDialects.active().name())) {
            System.out.println("NPDev schema lifecycle: pinning SQL dialect to '" + dialect.name()
                    + "' from the manifest engine '" + engine + "' (schema realization runs before "
                    + "StorageDialectInitializer's @PostConstruct is guaranteed to have).");
            com.npdev.kernel.storage.sql.SqlDialects.setActive(dialect);
        }
    }

    void migrate(Flyway flyway, SchemaManifest manifest) {
        // PIN THE DIALECT HERE, not only in StorageDialectInitializer.
        //
        // That class's @PostConstruct says it runs "before anything", and nothing enforced it:
        // Spring builds `flywayInitializer` from its own dependencies (dataSource, flyway), which do
        // not include StorageDialectInitializer, so schema realization could -- and on SQL Server
        // did -- run first, while SqlDialects.active() was still the Postgres default.
        //
        // The symptom was maximally misleading. Every guarded DDL statement came out in the H2/
        // Postgres form and SQL Server answered "Incorrect syntax near the keyword 'IF'" -- which
        // reads like a bug in the new guards, and is in fact the guards never being consulted.
        // Measured in CI run 31284112143.
        //
        // This is the entry point of everything that issues DDL, so pinning here cannot be too late,
        // and pinning twice is harmless (setActive is idempotent for the same dialect).
        pinDialectFromManifest(manifest);
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
        // SER-P6.4 (Surface 2): REPORT_ONLY mode computes + prints the impact report and exits with a
        // verdict code, WITHOUT any DDL/claim/history write. Read the mode as a JVM system property so no
        // Spring wiring is needed; the -ImpactOnly script passes -Dnpdev.schema.lifecycle.mode=REPORT_ONLY.
        if ("REPORT_ONLY".equalsIgnoreCase(System.getProperty("npdev.schema.lifecycle.mode", "APPLY"))) {
            int code = reportOnlyExitCode(dataSource);
            System.out.flush();
            System.exit(code);
        }
        // LNCH-1 remediation R2 (F1): capture the stored fingerprint BEFORE beforeMigrate runs, so we
        // know whether this boot is an upgrade (fingerprint mismatch) independently of whichever
        // beforeMigrate branch ran -- including the surgical-destruction and whole-wipe paths, which
        // previously bypassed required-field enforcement entirely. beforeMigrate never writes the
        // fingerprint (only afterMigrate does, at its very end) and no path drops npdev_schema_metadata,
        // so this read is the true pre-boot value even after a destructive beforeMigrate.
        String storedAtBootStart = readFingerprint(dataSource);
        // B4 (Move 9 A1, docs/ACCEPTED_BOUNDARIES.md): claim the single migration slot for this boot
        // BEFORE any schema work, so a second instance racing against the same database refuses
        // loudly instead of interleaving renames/widenings/drops. freshDatabase=true (no fingerprint
        // stored yet -- a genuinely virgin database) is passed through, not used to skip the call
        // outright: on Postgres, MigrationClaimStore.claim now protects this case too (a
        // pg_advisory_lock needs no table to exist), closing the one race the old row-only claim could
        // never cover. On H2 the fresh-database case is still skipped internally, exactly as before
        // REG-7.2's fix required (claiming unconditionally would self-bootstrap
        // npdev_schema_migration_claim before flyway.migrate() ever runs on a fresh schema, which
        // makes Flyway see a non-empty "public" schema with no history table and refuse outright).
        // See MigrationClaimStore's class javadoc for the full engine-by-engine scope.
        boolean freshDatabase = storedAtBootStart == null || storedAtBootStart.isBlank();
        MigrationClaimStore.Claim claim = MigrationClaimStore.claim(dataSource, freshDatabase);
        try {
            boolean fingerprintChanged = storedAtBootStart != null && !storedAtBootStart.isBlank()
                    && !storedAtBootStart.equals(manifest.schemaFingerprint());
            DestructiveRecreation recreation = beforeMigrate(dataSource, manifest);
            if (recreation.performed()) {
                clearSchemaRealizationHistory(dataSource);
            } else {
                // V1's bootstrap SQL is regenerated from the full current model on every generation pass,
                // so its content (and checksum) legitimately changes whenever a column is added even though
                // it must not be re-executed here. repair() reconciles Flyway's recorded checksums with the
                // newly resolved migration content instead of failing validation or re-running V1's CREATE TABLE.
                //
                // REG-106 (live-caught 2026-08-01, Move 10 B2): this used to run ONLY when
                // recreation.safeAdditive() -- i.e. only when the schema FINGERPRINT changed in a
                // known-additive way. But V1's literal SQL text (comments, table/column emission order)
                // can drift across generator runs even when the structural fingerprint does not change
                // at all, so a plain model.json edit with zero concept/table changes still crashed the
                // boot with "Migration checksum mismatch for migration version 1" on the very next
                // regeneration -- flyway.repair() was never called because
                // recreation == DestructiveRecreation.none(). V1 is entirely generated, never
                // hand-authored, so trusting the freshly generated file here (not the historical
                // checksum recorded from whenever this database was last migrated) is correct in the
                // fingerprint-unchanged case for the exact same reason it was already correct in the
                // safeAdditive case -- only widened to cover both.
                flyway.repair();
                System.out.println("NPDev schema lifecycle: flyway.repair() reconciled schema-realization checksums"
                        + (recreation.safeAdditive() ? " for the additive change." : " (no structural change detected)."));
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
        List<String> problems = ExternalSchemaVerification.findExternalSchemaIncompatibilities(dataSource, manifest);
        String fromFingerprint = readFingerprint(dataSource);
        if (!problems.isEmpty()) {
            SchemaHistoryStore.writeHistoryRow(dataSource, fromFingerprint, manifest.schemaFingerprint(), null, null, null, "EXTERNAL_REFUSED");
            throw new IllegalStateException("This app declares schemaLifecycle.ownership=ExternallyManaged "
                    + "(NPDev does not own this database's schema and will never issue DDL against it), but the "
                    + "live schema cannot serve this build's model. Incompatibilities: " + problems + ". Either "
                    + "alter the external schema by hand to match the model, or fix the model to match the "
                    + "external schema -- see docs/SCHEMA_EVOLUTION.md#external-unmanaged-database.");
        }
        SchemaHistoryStore.writeHistoryRow(dataSource, fromFingerprint, manifest.schemaFingerprint(), null, null, null, "EXTERNAL_VERIFIED");
        System.out.println("NPDev schema lifecycle: ownership=ExternallyManaged -- verified the live schema is "
                + "compatible with this build's model; no schema DDL issued.");
    }

    // REG-7.1 (D5) / SER-P5.2 / SER-G8: findExternalSchemaIncompatibilities (+ its
    // appendExternalNullabilityProblem/appendExternalUniqueProblems/appendExternalForeignKeyAndIndexProblems/
    // sameColumnSet helpers) moved verbatim to ExternalSchemaVerification (T2.B.4 pure mechanical split) --
    // verifyExternallyManagedSchemaCompatible above calls
    // ExternalSchemaVerification.findExternalSchemaIncompatibilities(...).

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
                    SqlDialects.active().guardedCreateTable(METADATA_TABLE,
                            "CREATE TABLE " + METADATA_TABLE
                            + " (metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)")
            )) {
                statement.executeUpdate();
            }
            upsertMetadata(connection, FINGERPRINT_KEY, manifest.schemaFingerprint());
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed fast-forwarding the schema fingerprint for a manually-marked-done migration", exception);
        }
        SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "MANUALLY_MARKED_DONE");
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
        conversionHooksAppliedLastDecision = false;
        DestructiveRecreation result = null;
        try {
            result = beforeMigrateDecision(dataSource, manifest);
            return result;
        } finally {
            // SER-P7.3: a conversion hook resolving a destructive item is a state a pure schema-diff
            // snapshot taken BEFORE it ran cannot predict -- exactly the same category of "override
            // the shadow has no signal for" as (c)/(d)/(e) already documented in compareAndLog, so it
            // is threaded through as its own exemption rather than tripping SHADOW_DIVERGENCE.
            ShadowParityProbe.compareAndLog(shadowPre, manifest, result, shadowFingerprintChanged,
                    conversionHooksAppliedLastDecision);
        }
    }

    /** SER-P7.3: set by {@link #beforeMigrateDecision} (via {@link ConversionHookRunner#run}) when at
     *  least one conversion hook actually applied this boot -- read by {@link #beforeMigrate} to tell
     *  {@link ShadowParityProbe} to skip its comparison (see the javadoc there). Reset at the top of
     *  every {@link #beforeMigrate} call; safe as instance state because a {@code SchemaLifecycleExecutor}
     *  is a fresh, single-boot-use object (see the class's Flyway wiring). */
    private boolean conversionHooksAppliedLastDecision;

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
                SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "REFUSED");
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
        Optional<SchemaHistoryStore.HistoryPoint> aheadOfBuild = SchemaHistoryStore.databaseMigratedPastThisBuild(dataSource, manifest);
        if (aheadOfBuild.isPresent()) {
            SchemaHistoryStore.writeHistoryRow(dataSource, stored, manifest.schemaFingerprint(), null, null, null, "REFUSED");
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
            attemptInPlaceRenames(dataSource, manifest);
            SchemaChangeClassification residual = classify(dataSource, manifest);
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
                attemptInPlaceTypeWidenings(dataSource, manifest);
                residual = classify(dataSource, manifest);
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
            attemptInPlaceRenames(dataSource, manifest);
            SchemaChangeClassification residual = classify(dataSource, manifest);
            classificationForFallthrough = residual;
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place field rename(s) fully resolved the "
                        + "fingerprint diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
                // R2 (F1): required-field backfill/refusal moved to the single afterMigrate call site.
                SchemaHistoryStore.writeAppliedHistoryRow(dataSource, stored, manifest.schemaFingerprint(), classification);
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
        conversionHooksAppliedLastDecision = ConversionHookRunner.run(dataSource, manifest,
                (label, outcome, details) ->
                        SchemaHistoryStore.insertRawHistoryRow(dataSource, stored, manifest.schemaFingerprint(), label, details, outcome));

        // LNCH-1 P5 (5.3): a required bond/FK field missing from an existing, populated table is
        // intercepted HERE, before SchemaDeltaReport ever runs -- independently re-derived per
        // table (not relying on classify()'s short-circuit-to-DESTRUCTIVE aggregate value), so it
        // is caught with a dedicated, itemized refusal instead of falling into SchemaDeltaReport's
        // generic UNKNOWN item kind.
        BackfillPass.refuseIfRequiredBondColumnMissing(dataSource, manifest, stored, classificationForFallthrough);

        // LNCH-1 Phase 4 (task 4.3): everything below replaces the old blanket whole-schema-wipe
        // fallback with itemized, surgical destruction wherever the residual diff cleanly supports
        // it. SchemaDeltaReport independently re-introspects the live database (it does not trust
        // classify()'s classification value beyond what is used here for logging/history purposes).
        SchemaDeltaReport report = SchemaDeltaReport.generate(dataSource, manifest);
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
            throw new IllegalStateException((impactReportText != null ? impactReportText + "\n" : "")
                    + "Schema fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " and includes destructive change(s) requiring an explicit, "
                    + "itemized acknowledgment (LNCH-1 Phase 4). Itemized destructive report: "
                    + report.stableStrings() + ". Expected acknowledgment token: " + expectedToken
                    + ". Set the generated manifest's destructiveAcknowledgment to this token, or submit it via "
                    + "the ControlPanel schema-migration screen on the currently running app (LNCH-1 Phase 6), to "
                    + "proceed -- see docs/SCHEMA_EVOLUTION.md#acknowledging-destructive-changes."
                    + DestructiveRecreationPass.agreementCheckSuffix(manifest, report));
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
                        + DestructiveRecreationPass.agreementCheckSuffix(manifest, report));
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
            DestructiveRecreation result = DestructiveRecreationPass.executeSurgicalDestruction(dataSource, manifest, stored,
                    classificationForFallthrough, report, tokenMatches ? effectiveToken : null);
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
        DestructiveRecreation result = DestructiveRecreationPass.executeWholeSchemaWipe(dataSource, manifest, stored,
                classificationForFallthrough, report, effectiveToken);
        DestructiveRecreationPass.consumePendingAcknowledgmentIfAny(dataSource, pendingAcknowledgment);
        return result;
    }

    // LNCH-1 Phase 4/6 (tasks 4.3, 6.3) / hardening X1 / closeout C1: consumePendingAcknowledgmentIfAny,
    // agreementCheckSuffix, executeSurgicalDestruction (+ its executeDropColumn/executeDropTableCascade/
    // executeNarrowTypeDropAndRecreate DDL helpers) and executeWholeSchemaWipe all moved verbatim to
    // DestructiveRecreationPass (T2.B.4 pure mechanical split) -- beforeMigrateDecision above/below calls
    // DestructiveRecreationPass.executeSurgicalDestruction(...) / .executeWholeSchemaWipe(...) /
    // .consumePendingAcknowledgmentIfAny(...) / .agreementCheckSuffix(...).

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
                    TableRenamePass.tableRenamesFromDiff(dataSource, manifest).entrySet());
            // R4 (F5): write-before-execute the whole pass as one audit row with per-item detail.
            List<String> itemDetails = new ArrayList<>();
            for (Map.Entry<String, String> pair : work) {
                itemDetails.add("RENAME_TABLE " + pair.getValue() + " -> " + pair.getKey());
            }
            SchemaHistoryStore.recordStepPass(dataSource, manifest, "TABLE_RENAME", itemDetails, () -> {
                for (Map.Entry<String, String> pair : work) {
                    TableRenamePass.executeRenameTable(connection, pair.getValue(), pair.getKey());
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
        ColumnRenamePass.ColumnRenamePlan derived = ColumnRenamePass.columnRenamesFromDiff(dataSource, manifest);
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
            SchemaHistoryStore.recordStepPass(dataSource, manifest, "COLUMN_RENAME", itemDetails, () -> {
                for (ColumnRename r : plan) {
                    ColumnRenamePass.executeRenameColumn(connection, manifest.engine(), r.table(), r.oldName(), r.newName());
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
        TypeWideningPass.WideningPlan derived = TypeWideningPass.wideningPlanFromDiff(dataSource, manifest);
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
            SchemaHistoryStore.recordStepPass(dataSource, manifest, "TYPE_WIDENING", itemDetails, () -> {
                for (Widening w : plan) {
                    TypeWideningPass.executeWidenColumnType(connection, manifest.engine(), w.table(), w.column(), w.toType());
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

    // ------------------------------------------------------------------------------------------
    // LNCH-1 Phase 5: data pre-checks and literal backfills.
    // ------------------------------------------------------------------------------------------

    // LNCH-1 P5 (5.2): the required-field backfill pass (applyRequiredFieldBackfills + its BackfillItem
    // plan derivation and DDL helpers) moved verbatim to BackfillPass (T2.B.4 pure mechanical split) --
    // it was private and not directly unit-tested (only reached via afterMigrate below, which calls
    // BackfillPass.applyRequiredFieldBackfills(...)).

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
            SchemaHistoryStore.recordStepPass(dataSource, manifest, "RELAX_NOT_NULL", itemDetails, () -> {
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

    /**
     * LNCH-1 T1 (finding T-B1), Half B -- the repair half. Restores {@code NOT NULL} on the
     * platform-managed columns of any table where it is missing, backfilling existing NULLs to the
     * fixed platform default first. Body moved to {@link PlatformColumnPass#tightenPlatformColumns}
     * (T2.B.4 pure mechanical split); this thin wrapper keeps the method's existing package-private
     * signature (and the direct-unit-testability its javadoc has always promised) unchanged.
     *
     * <p>Package-private (not private) so it is directly unit-testable against a real H2
     * {@link DataSource}, following {@link #relaxNoLongerRequiredColumns}'s own precedent.
     */
    void tightenPlatformColumns(DataSource dataSource, SchemaManifest manifest) {
        PlatformColumnPass.tightenPlatformColumns(dataSource, manifest);
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

    /** Package-private (not private): reused by {@link BackfillPass} and {@link PlatformColumnPass}
     * (T2.B.4 split), following this class's own precedent for shared live-introspection helpers. */
    static boolean isColumnNotNull(Connection connection, String table, String column) throws SQLException {
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

    // LNCH-1 P5 (5.3): the required-bond refusal check (refuseIfRequiredBondColumnMissing) moved
    // verbatim to BackfillPass (T2.B.4 pure mechanical split) -- it was private and not directly
    // unit-tested (only reached via beforeMigrateDecision below, which calls
    // BackfillPass.refuseIfRequiredBondColumnMissing(...)).

    // LNCH-1 P5 (5.1): applyUniqueConstraints (+ constraintExists/indexExists/findDuplicateKeys/
    // executeAddUniqueConstraint) moved verbatim to UniqueConstraintPass (T2.B.4 pure mechanical
    // split) -- afterMigrate below calls UniqueConstraintPass.applyUniqueConstraints(...).

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
     * identical string {@code "VARCHAR"} and the (since-removed dead code, REG-54) {@code hasTypeChange}
     * helper treated a VARCHAR-length or
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

    // REG-8 Trigger C: HistoryPoint / databaseMigratedPastThisBuild / latestOutcomeTimestamp /
    // latestOutcomeOverall moved verbatim to SchemaHistoryStore (T2.B.4 pure mechanical split);
    // beforeMigrateDecision below calls SchemaHistoryStore.databaseMigratedPastThisBuild(...).

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
            BackfillPass.applyRequiredFieldBackfills(dataSource, manifest, storedAtBootStart, null);
        }
        // LNCH-1 P5 (5.1): runs on every boot, after flyway.migrate() has already applied the R__
        // additive-columns migration, so a unique constraint declared alongside a brand-new column
        // always finds that column already present. Deliberately BEFORE the fingerprint write below
        // -- a refusal here (dirty data violating a newly-declared constraint) must leave the stored
        // fingerprint stale, so the next boot re-attempts instead of silently accepting the drift.
        UniqueConstraintPass.applyUniqueConstraints(dataSource, manifest);
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.active().guardedCreateTable(METADATA_TABLE,
                            "CREATE TABLE " + METADATA_TABLE
                            + " (metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)")
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
            SchemaHistoryStore.writeAppliedHistoryRow(dataSource, null, manifest.schemaFingerprint(), null);
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

    // LNCH-1 Phase 4 (task 4.4) / R4 (F5): ensureHistoryTable, itemsJson (both overloads),
    // insertHistoryRow, writeHistoryRow, writeAppliedHistoryRow, insertPendingHistoryRow,
    // markHistoryRowApplied, SqlRunnable, recordStepPass, insertStepPendingRow, insertRawHistoryRow
    // and the npdev_schema_history HISTORY_TABLE constant all moved verbatim to SchemaHistoryStore
    // (T2.B.4 pure mechanical split) -- callers throughout this class now go through
    // SchemaHistoryStore.writeHistoryRow(...) / .writeAppliedHistoryRow(...) / .recordStepPass(...) /
    // .insertRawHistoryRow(...) / .insertPendingHistoryRow(...) / .markHistoryRowApplied(...).

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

    /** Public accessor for the stored schema fingerprint (SER-P6.0): lets the Impact Report facade and
     *  the ControlPanel surface read the "from" fingerprint without package-private access. Read-only. */
    public static String readStoredFingerprintPublic(DataSource dataSource) {
        return readFingerprint(dataSource);
    }

    /** {@code public static} entry point kept on the executor -- called externally as
     *  {@code SchemaLifecycleExecutor.loadManifest()} by {@code StorageSummaryController} and
     *  {@code SchemaImpactFacade}. Body (the JSON-tree walking helpers) moved verbatim to
     *  {@link SchemaManifestLoader#load} (T2.B.4 pure mechanical split). */
    public static SchemaManifest loadManifest() {
        return SchemaManifestLoader.load();
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

    /** SER-G8: a foreign key the MODEL declares (derived from a bond at generation time). Matched
     *  against the live schema by column set + referenced table, never by name — constraint names are
     *  engine-generated and differ between H2 and Postgres. */
    record ForeignKeyDecl(List<String> columns, String referencedTable, List<String> referencedColumns) {
    }

    /** SER-G8: an index the MODEL declares (a unique index, a tenant index, or a bond-column index).
     *  Matched by column set (+ uniqueness), never by name, for the same reason as {@link ForeignKeyDecl}. */
    record IndexDecl(List<String> columns, boolean unique) {
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
            String ownership,
            // SER-G8: the FKs and indexes the MODEL declares, per table. Added LAST so every existing
            // construction keeps compiling via the 22-arg convenience constructor below, and absent from
            // an older generated manifest simply means "empty" -- an app built before G8 behaves exactly
            // as it did. Matched against the live schema by COLUMN SET, never by name (constraint/index
            // names are engine-generated and differ between H2 and Postgres).
            Map<String, List<ForeignKeyDecl>> businessTableForeignKeys,
            Map<String, List<IndexDecl>> businessTableIndexes,
            // Move 9 B1 (docs/ACCEPTED_BOUNDARIES.md B2): the expression TEXT for every column
            // businessTableExpressionDefaultColumns names -- added LAST, same SER-G8 convention as the
            // FK/index maps above. Absent from every manifest emitted before this field existed --
            // SchemaManifestLoader defaults it to an empty map, so a pre-existing app behaves exactly
            // as it did (no expression-default preview/backfill data means BackfillPass's refusal path
            // is unchanged).
            Map<String, Map<String, String>> businessTableColumnDefaultExpressions
    ) {
        /** Move 9 B1 backward-compatible convenience constructor matching the PRE-B1 24-arg shape
         * (every field through the SER-G8 FK/index maps) -- defaults the new expression-text map to
         * empty. Keeps every existing hand-built manifest from that era (tests, and any caller that
         * does not care about expression-default preview/backfill) compiling and behaving identically. */
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
                List<String> planItemStableStrings,
                String ownership,
                Map<String, List<ForeignKeyDecl>> businessTableForeignKeys,
                Map<String, List<IndexDecl>> businessTableIndexes
        ) {
            this(engine, storageMode, physicalDatabase, schemaFingerprint, internalTables, businessTables,
                    businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                    businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate, strategy,
                    scope, destructiveRecreateConfirmation, destructiveAcknowledgment,
                    businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints,
                    planItemStableStrings, ownership, businessTableForeignKeys, businessTableIndexes, Map.of());
        }

        /** SER-G8 backward-compatible convenience constructor matching the PRE-G8 22-arg shape --
         * defaults the two new FK/index maps to empty. Keeps every existing hand-built manifest (tests,
         * and any caller that does not care about FK/index) compiling and behaving identically. */
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
                List<String> planItemStableStrings,
                String ownership
        ) {
            this(engine, storageMode, physicalDatabase, schemaFingerprint, internalTables, businessTables,
                    businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                    businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate, strategy,
                    scope, destructiveRecreateConfirmation, destructiveAcknowledgment,
                    businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints,
                    planItemStableStrings, ownership, Map.of(), Map.of(), Map.of());
        }

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
