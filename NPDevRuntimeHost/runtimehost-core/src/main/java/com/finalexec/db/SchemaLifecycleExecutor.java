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
    static final String METADATA_TABLE = "npdev_schema_metadata";
    static final String FINGERPRINT_KEY = "schemaFingerprint";
    /** LNCH-1-B7: metadata key holding the JSON array of business tables the last successful boot
     * owned -- the ownership evidence that makes acting on an orphaned table safe. */
    static final String OWNED_TABLES_KEY = "ownedBusinessTables";
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
            Set.of("id", "version", "row_version", "tenant_id", "deleted_at");

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

    /**
     * R5.4: the OTHER kind of non-repairable platform column, alongside {@code id} -- {@code
     * deleted_at} carries no fixed platform default at all. NULL means "live", which is the
     * permanently-correct value for a row that has never been soft-deleted; unlike version/
     * row_version/tenant_id there is no wrong/missing state for {@link PlatformColumnPass} to
     * backfill-then-NOT-NULL, so it must stay OUT of {@link #REPAIRABLE_PLATFORM_COLUMNS} while still
     * being a member of {@link #PLATFORM_MANAGED_COLUMNS} (Trigger B must not treat it as an
     * unexplained extra column on a table that has it). {@link #assertPlatformColumnSetsAgree} needs
     * this named explicitly now that "platform-managed minus id" is no longer the same set as
     * "repairable".
     */
    private static final Set<String> NON_REPAIRABLE_PLATFORM_COLUMNS = Set.of("id", "deleted_at");

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
        Set<String> managedMinusNonRepairable = new LinkedHashSet<>(PLATFORM_MANAGED_COLUMNS);
        managedMinusNonRepairable.removeAll(NON_REPAIRABLE_PLATFORM_COLUMNS);
        if (!managedMinusNonRepairable.equals(repairable)) {
            throw new IllegalStateException("REG-6 platform-column drift: PLATFORM_MANAGED_COLUMNS minus "
                    + NON_REPAIRABLE_PLATFORM_COLUMNS + " " + managedMinusNonRepairable
                    + " must equal REPAIRABLE_PLATFORM_COLUMNS " + repairable
                    + " (every platform column other than id/deleted_at carries a fixed default and is repairable)");
        }
    }

    static {
        assertPlatformColumnSetsAgree();
    }

    static boolean isPlatformManagedColumn(String column) {
        return column != null && PLATFORM_MANAGED_COLUMNS.contains(column.toLowerCase(Locale.ROOT));
    }

    /**
     * R5.4: the one platform-managed column {@link DesiredSchemaFactory} must NOT fold into its
     * blanket "every platform column is always NOT NULL" rule (Section 6 -- true for id/version/
     * row_version/tenant_id, false for {@code deleted_at}, whose whole point is usually-null).
     * Package-private and named the negative of {@link #isPlatformManagedColumn} on purpose: a caller
     * that only checks {@code isPlatformManagedColumn} and assumes "therefore NOT NULL" is exactly
     * the bug this exists to prevent from creeping back in at a future call site.
     */
    static boolean isNullablePlatformColumn(String column) {
        return column != null && NON_REPAIRABLE_PLATFORM_COLUMNS.contains(column.toLowerCase(Locale.ROOT))
                && !"id".equalsIgnoreCase(column);
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
     *
     * <p>REG-208 (B28 lift): {@code ModelHolder}, not a direct {@code CompiledModel} injection --
     * this class runs at BOOT (via {@code FlywayMigrationStrategy}), strictly before any reload
     * endpoint could fire, so there is no staleness to fix here; only the injection type changes, to
     * satisfy the platform-wide "no direct CompiledModel injection but the ModelHolder bean itself"
     * invariant now that the standalone {@code CompiledModel} bean is gone.
     */
    @Autowired(required = false)
    private ModelHolder modelHolder;

    /**
     * STOR-32 (boundary B7): the PER-BOOT id keying {@link BootResidueJournal} rows. Generated ONCE
     * per boot, at the first lifecycle entry point that runs (see {@link #ensureBootId}), so a single
     * production boot's claim, Flyway, beforeMigrate and afterMigrate steps all share one residue key
     * -- and a retried boot, being a new key, can never read a previous attempt's committed steps as
     * its own.
     */
    String bootId;

    /** STOR-32: generate the per-boot residue key exactly once per boot, at whichever lifecycle entry
     *  point actually runs. Production boots enter through {@link #migrate(Flyway, SchemaManifest)}
     *  (claim + Flyway + beforeMigrate + afterMigrate all share this one key); the direct-call tests
     *  that drive {@link #beforeMigrate}/{@link #afterMigrate} without {@code migrate} generate their
     *  own key at that entry. A retried boot is a NEW key, so it can never read a previous attempt's
     *  committed steps as its own, and its journal rows never collide with the failed attempt's. */
    void ensureBootId() {
        if (bootId == null) {
            bootId = java.util.UUID.randomUUID().toString();
        }
    }

    /**
     * B1 (REAL_LIFT_PLAN_2026-09-03, B13): the beans a {@code javaHook} conversion hook needs to run
     * in the isolated plugin pool. Same optionality shape as {@link #compiledModel} above and for the
     * same reason (dozens of {@code new SchemaLifecycleExecutor()} test call sites with no Spring
     * context) -- {@code null} on every app with no javaHook conversion, which is also exactly when
     * {@link PluginIpcChildProcessPool}'s own bean is null (NpdevPluginConfig's
     * java-source-runtime-refs.json-driven condition; ConversionHookJavaHookEmitter only ever writes
     * an entry there alongside a javaHook hook.json). Package-private, not private, so
     * SchemaLifecycleExecutor*Test can set these directly without a Spring context, same as {@link
     * #forcePhysicalSchema} below.
     */
    @Autowired(required = false)
    PluginIpcChildProcessPool pluginIpcChildProcessPool;

    @Autowired(required = false)
    RuntimePluginAdapterRegistry runtimePluginAdapterRegistry;

    @Autowired(required = false)
    PluginExecutionPolicyEvaluator pluginExecutionPolicyEvaluator;

    /**
     * npdev.trial.force-physical-schema (default false, preserving Row 16's InMemory no-op for every
     * normal app): a profile that forces a REAL database onto a model whose db.definition.json
     * declares InMemory (manifest.physicalDatabase()=false baked in at generation time) needs this
     * executor to actually run schema realization anyway -- the manifest's baked flag describes what
     * the model was GENERATED for, not what this profile is deliberately substituting at runtime.
     * Same "profile intentionally substitutes X, so a generation-time assumption doesn't apply" shape
     * as npdev.trial.database-override (DatabaseIdentityStartupValidator) and
     * npdev.trial.flyway-history-check-required (StartupValidator). Package-private, not private, so
     * SchemaLifecycleExecutorProofMatrixTest can set it directly without a Spring context.
     *
     * <p>Also gates {@link MissingTableCreationPass}, called from the Ephemeral branch right after
     * flyway.migrate(): an InMemory-generated manifest carries no real V1__ migration (nothing for
     * Flyway to run), so business tables need a manifest-driven fallback here or they simply never
     * exist -- see that class's javadoc for the full reasoning and its deliberate scope (no FKs/
     * indexes; unique constraints still come from the normal afterMigrate step).
     */
    @Value("${npdev.trial.force-physical-schema:false}")
    boolean forcePhysicalSchema;

    @Override
    public void migrate(Flyway flyway) {
        migrate(flyway, loadManifest());
        exitIfMigrateOnly();
    }

    /**
     * 3.2 (B4 migrate-only + progress-aware waiting): the production answer to B4 -- boot the schema
     * lifecycle, run it, exit, BEFORE the web server binds a port, so a deployment can run migration
     * as a one-shot job (K8s init/Job container, CI step) ahead of every serving instance. B4's
     * wait/refuse machinery in {@link MigrationMutex} then never fires in a correctly-ordered
     * deployment, because no instance is racing another one to migrate -- there is exactly one boot,
     * and it does nothing else.
     *
     * <p>Checked AFTER {@link #migrate(Flyway, SchemaManifest)} returns, deliberately unlike
     * REPORT_ONLY's early exit (line ~439 above, before any DDL): every branch above -- the
     * ExternallyManaged verification, the Ephemeral wipe, the ordinary apply path -- has already done
     * its real work by the time this runs, and this only decides whether to hand control back to
     * Spring Boot to keep booting into a serving app, or to stop here instead. Reads the SAME
     * {@code npdev.schema.lifecycle.mode} property REPORT_ONLY reads -- the two are mutually
     * exclusive values of one switch, never combined.
     *
     * <p>{@link #migrateOnlyRequested()} is the testable half (mirrors {@link #codeFor}'s split for
     * REPORT_ONLY); the {@code System.exit} call itself is, like REPORT_ONLY's, exercised only via a
     * real {@code java -jar} process (see {@code Migrate-Only.ps1}), never inside the test JVM.
     */
    private static void exitIfMigrateOnly() {
        if (!migrateOnlyRequested()) {
            return;
        }
        System.out.println("NPDev schema lifecycle: MIGRATE_ONLY mode complete -- schema realized; exiting before "
                + "the app serves traffic (npdev.schema.lifecycle.mode=MIGRATE_ONLY).");
        System.out.flush();
        System.exit(0);
    }

    /** Package-private for direct unit testing without touching {@code System.exit} -- see
     *  {@link #exitIfMigrateOnly}. */
    static boolean migrateOnlyRequested() {
        return "MIGRATE_ONLY".equalsIgnoreCase(System.getProperty("npdev.schema.lifecycle.mode", "APPLY"));
    }


    /**
     * LNCH-1 Phase 7 (row 16 of the proof matrix: an InMemory-storage app's model change must
     * no-op the executor entirely, unless forcePhysicalSchema opts back in -- row 18).
     * Package-private overload taking the manifest as a parameter
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
        SchemaImpactFacade.Result result = SchemaImpactFacade.forLiveDatabase(
                dataSource, modelHolder == null ? null : modelHolder.get());
        System.out.println(ImpactReportText.render(result.report(), result.fromFingerprint(),
                result.toFingerprint(), result.ackToken(), result.surplus(), result.renameCandidates(),
                result.sanctioned()));
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

    void migrate(Flyway flyway, SchemaManifest manifest) {
        new MigrationBootPass(this).migrate(flyway, manifest);
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
    void applyMigrationMark(DataSource dataSource, String stored, SchemaManifest manifest, MigrationMarkStore.Mark mark) {
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
        ensureBootId();
        com.finalexec.db.schemastate.CurrentSchema shadowPre = ShadowParityProbe.snapshot(dataSource);
        boolean shadowFingerprintChanged = shadowFingerprintChanged(dataSource, manifest);
        // REG-305: the shadow judges the renames the live passes will resolve by identity (concept and
        // field uids) as declared ones, read now -- before those passes move the recorded rows.
        SchemaManifest shadowManifest = identityResolvedForShadow(dataSource, manifest);
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
            ShadowParityProbe.compareAndLog(shadowPre, shadowManifest, result, shadowFingerprintChanged,
                    conversionHooksAppliedLastDecision);
        }
    }

    /** Log-only input for {@link ShadowParityProbe}; any failure falls back to the declared manifest. */
    private static SchemaManifest identityResolvedForShadow(DataSource dataSource, SchemaManifest manifest) {
        try {
            Map<String, String> tableRenames = TableIdentityStore.resolveRenamesByIdentity(dataSource, manifest);
            SchemaManifest withTables = manifest.withTableRenames(tableRenames);
            return withTables.withRenamedColumns(
                    ColumnIdentityStore.resolveRenamesByIdentity(dataSource, withTables, tableRenames));
        } catch (RuntimeException failure) {
            return manifest;
        }
    }

    /** SER-P7.3: set by {@link #beforeMigrateDecision} (via {@link ConversionHookRunner#run}) when at
     *  least one conversion hook actually applied this boot -- read by {@link #beforeMigrate} to tell
     *  {@link ShadowParityProbe} to skip its comparison (see the javadoc there). Reset at the top of
     *  every {@link #beforeMigrate} call; safe as instance state because a {@code SchemaLifecycleExecutor}
     *  is a fresh, single-boot-use object (see the class's Flyway wiring). */
    boolean conversionHooksAppliedLastDecision;

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
        return new BeforeMigrateDecisionPass(this).beforeMigrateDecision(dataSource, manifest);
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
    void attemptInPlaceTableRenames(DataSource dataSource, SchemaManifest declaredManifest) {
        // REG-305: a live table whose recorded concept uid matches a model concept's uid under another
        // name IS a declared rename (identity says so) -- merged ahead of the diff, declared renamedFrom wins.
        SchemaManifest manifest = declaredManifest.withTableRenames(
                TableIdentityStore.resolveRenamesByIdentity(dataSource, declaredManifest));
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
                    // the renamed table's recorded column uids move with it, so a field renamed by uid on
                    // that table in this or a later boot still resolves
                    ColumnIdentityStore.retargetTable(connection, pair.getValue(), pair.getKey());
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
        // REG-209 (B1 lift, package P7): consult the persisted uid identity map BEFORE the diff/
        // classification pipeline ever runs, ahead of the model's own declared businessTableRenamedColumns
        // -- a live column whose recorded uid matches a model field's declared uid, but whose name
        // differs, IS a declared rename (identity says so), fed into the SAME map columnFactsFor already
        // reads for every OTHER declared rename. A model with no uids declared returns the manifest's own
        // businessTableRenamedColumns completely unchanged -- behaves exactly as today.
        SchemaManifest effectiveManifest = manifest.withRenamedColumns(
                ColumnIdentityStore.resolveRenamesByIdentity(dataSource, manifest));
        // SER-P4.4: the whole plan -- the renames to apply, the tables deferred to the destructive path,
        // and the stale-marker warnings -- is now derived from the canonical SchemaDiff (proven equal to
        // the former RenameResolution loop at P4.4a), not a second live introspection.
        ColumnRenamePass.ColumnRenamePlan derived = ColumnRenamePass.columnRenamesFromDiff(dataSource, effectiveManifest);
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

    SchemaChangeClassification classify(DataSource dataSource, SchemaManifest manifest) {
        return SchemaClassificationPass.classify(dataSource, manifest);
    }

    static Set<String> droppedConceptTables(
            DatabaseMetaData metadata, DataSource dataSource, SchemaManifest manifest) throws SQLException {
        return SchemaClassificationPass.droppedConceptTables(metadata, dataSource, manifest);
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
    static List<String> findSchemaAheadMissingColumns(DataSource dataSource, SchemaManifest manifest) {
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

    void clearSchemaRealizationHistoryForEphemeral(DataSource dataSource) {
        new SchemaRealizationHistoryReset().clearSchemaRealizationHistoryForEphemeral(dataSource);
    }

    void clearSchemaRealizationHistory(DataSource dataSource) {
        new SchemaRealizationHistoryReset().clearSchemaRealizationHistory(dataSource);
    }

    /** Package-private (not private): reused verbatim by {@link SchemaDeltaReport} (LNCH-1 Phase 4)
     * for its best-effort row-count queries -- guardrail 11's identifier-safety discipline applies
     * there exactly as it does everywhere else in this class.
     *
     * <p><b>Returns the name RAW</b> (validated and lowercased, never quoted). Use it where the name
     * goes into a string LITERAL rather than into SQL syntax: the {@code information_schema} lookups
     * inside {@code guardedAddColumn}/{@code guardedCreateTable}, and SQL Server's
     * {@code sp_rename 'table.column'}. Quoting there would search for a column whose name literally
     * contains backticks, which silently never matches.
     *
     * <p>For a name that is embedded in SQL TEXT, use {@link #quotedIdentifier} instead. */
    static String safeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Unsafe table identifier in schema realization manifest: " + identifier);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * npdev-sql-identifier-quoting: one of THREE seams that turn a model name into SQL identifier
     * text (STOR-6). All three must ask SqlDialect.identifier(); quoting one alone leaves an app
     * that builds, boots, and cannot find its own table. Twin-pair rule:
     * sql-identifier-quoting-three-seams.
     *
     * <p>{@link #safeIdentifier} plus conditional quoting: the form to embed in SQL TEXT.
     *
     * <p>This is the schema-lifecycle naming seam, and it is a THIRD one -- neither
     * {@code SchemaRealizationEmitter} (which emits the DDL) nor {@link JdbcBusinessConceptStore}
     * (which reads and writes rows) covers it, because both of those run on a fresh database and
     * this only runs when something is CHANGING on an existing one. A column named {@code order}
     * reaches here through the impact probe ({@code SELECT COUNT(*) FROM t WHERE order IS NOT NULL})
     * and through every rename/backfill/widen/unique pass.
     *
     * <p>Validation runs FIRST and is unchanged, so quoting is only ever applied to a name already
     * proven to match {@code [A-Za-z_][A-Za-z0-9_]*} -- it cannot smuggle a quote or a statement
     * separator through. The lowercasing matters too: on the engines that fold unquoted identifiers
     * the physical column really is lowercase, so quoting the original casing would stop matching
     * what is deployed.
     */
    static String quotedIdentifier(String identifier) {
        return com.npdev.kernel.storage.sql.SqlDialects.active().identifier(safeIdentifier(identifier));
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
        new AfterMigratePass(this).afterMigrate(dataSource, manifest, storedAtBootStart, fingerprintChanged);
    }

    /**
     * B8 2.1: writes the ownership record for every manifest-declared, now-live business table BEFORE
     * {@link BackfillPass}/{@link UniqueConstraintPass} run in {@link #afterMigrate}, so a refusal in
     * either of them cannot leave a table this boot's {@code flyway.migrate()} just created without an
     * ownership record. Package-private for direct unit-testing, following {@link #afterMigrate}/
     * {@link #classify}'s own precedent. Ensures {@link #METADATA_TABLE} exists itself (idempotent --
     * safe to call before {@link #afterMigrate}'s own guarded CREATE, and safe for that one to repeat).
     */
    static void recordOwnershipForLiveManifestTables(DataSource dataSource, SchemaManifest manifest) {
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
            upsertMetadata(connection, OWNED_TABLES_KEY, ownedTablesJson(connection, dataSource, manifest));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed recording business-table ownership", exception);
        }
    }

    /**
     * B8 2.1: explicit, operator-driven adoption of ownership for a LEGACY table -- {@link
     * com.finalexec.db.OwnershipAdoption} is the only caller, and only after verifying (via the schema
     * diff engine, outside this class) that the table's live shape matches the manifest with zero
     * difference. This method does not re-verify that itself; it only ever UNIONS {@code tablesToAdopt}
     * into the recorded set, never removes, matching the same discipline {@link #ownedTablesJson}'s
     * union/intersect already follows. A no-op for an empty/null set.
     */
    static void adoptOwnedBusinessTables(DataSource dataSource, Set<String> tablesToAdopt) {
        if (tablesToAdopt == null || tablesToAdopt.isEmpty()) {
            return;
        }
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
            Set<String> merged = new LinkedHashSet<>();
            Set<String> previouslyOwned = readOwnedBusinessTables(dataSource);
            if (previouslyOwned != null) {
                merged.addAll(previouslyOwned);
            }
            for (String table : tablesToAdopt) {
                if (table != null && !table.isBlank()) {
                    merged.add(table.toLowerCase(Locale.ROOT));
                }
            }
            List<String> sorted = new ArrayList<>(merged);
            Collections.sort(sorted);
            upsertMetadata(connection, OWNED_TABLES_KEY, OBJECT_MAPPER.writeValueAsString(sorted));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed recording adopted business-table ownership", exception);
        }
    }

    /**
     * B8 2.1: the live business-table names, straight from the database's own metadata -- used by
     * {@link com.finalexec.db.OwnershipAdoption} to tell "declared but never created" apart from "live
     * but not yet owned" (only the latter is ever an adoption candidate). Fails open to an empty set
     * (matches every other read-only introspection helper in this class); a connection failure here
     * means the adoption preview reports nothing adoptable, never a false positive.
     */
    static Set<String> readLiveTableNames(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return readActualTableNames(connection.getMetaData());
        } catch (SQLException exception) {
            return Set.of();
        }
    }

    /** UPDATE-then-INSERT upsert against {@link #METADATA_TABLE} (no engine-specific UPSERT syntax --
     * identical on H2 and Postgres). The caller must have ensured the table exists. */
    static void upsertMetadata(Connection connection, String key, String value) throws SQLException {
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
    static String ownedTablesJson(Connection connection, DataSource dataSource, SchemaManifest manifest) {
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

    static String readFingerprint(DataSource dataSource) {
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
     * pre-check) is scoped to {@code (tenant_id, ...columns)} or just {@code (...columns)}. Every
     * generated unique is tenant-scoped today, connectable natural-key anchors included (ENF-1).
     */
    record UniqueConstraintDecl(String name, List<String> columns, boolean tenantScoped) {
    }

    /** SER-G8: a foreign key the MODEL declares (derived from a bond at generation time). Matched
     *  against the live schema by column set + referenced table, never by name — constraint names are
     *  engine-generated and differ between H2 and Postgres.
     *
     *  <p>STOR-18: made {@code public} -- {@link SchemaManifest#businessTableForeignKeys()} is a
     *  public accessor already returning {@code Map<String, List<ForeignKeyDecl>>}, so any caller
     *  outside this package (e.g. {@code SchemaAcknowledgmentController}, in {@code
     *  com.finalexec.controlpanel}) that tried to use the returned list's element type hit a
     *  cannot-be-accessed-from-outside-package compile error -- this was a latent accessibility bug,
     *  not new exposure. */
    public record ForeignKeyDecl(List<String> columns, String referencedTable, List<String> referencedColumns) {
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
            Map<String, Map<String, String>> businessTableColumnDefaultExpressions,
            // REG-209 (B1 lift, ALL_HITTABLE_LIFT_PLAN_2026-09-05.md package P7): the MODEL's own
            // declared uid for every table/column that has one (table -> column -> uid), mirroring
            // businessTableRenamedColumns's exact shape. Added LAST, same convention as every field
            // above it -- absent from a manifest emitted before this field existed simply means
            // "no uids declared", and attemptInPlaceRenames falls back to the pre-existing
            // businessTableRenamedColumns path exactly as it did before uids existed.
            Map<String, Map<String, String>> businessTableColumnUids,
            // REG-305: the MODEL's declared concept uid per table (table -> uid), the table-level twin of
            // businessTableColumnUids. Added LAST; absent from an older manifest simply means "none", and
            // attemptInPlaceTableRenames then sees the declared businessTableRenames alone.
            Map<String, String> businessTableUids
    ) {
        /** REG-305 backward-compatible convenience constructor matching the PRE-table-uid 26-arg shape --
         * defaults the table uid map to empty. */
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
                Map<String, List<IndexDecl>> businessTableIndexes,
                Map<String, Map<String, String>> businessTableColumnDefaultExpressions,
                Map<String, Map<String, String>> businessTableColumnUids
        ) {
            this(engine, storageMode, physicalDatabase, schemaFingerprint, internalTables, businessTables,
                    businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                    businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate, strategy,
                    scope, destructiveRecreateConfirmation, destructiveAcknowledgment,
                    businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints,
                    planItemStableStrings, ownership, businessTableForeignKeys, businessTableIndexes,
                    businessTableColumnDefaultExpressions, businessTableColumnUids, Map.of());
        }

        /** REG-209 backward-compatible convenience constructor matching the PRE-uid 25-arg shape --
         * defaults the new uid map to empty. Keeps every existing hand-built manifest (tests, and any
         * caller that predates uids) compiling and behaving identically -- a model with no uids at
         * all behaves exactly as today. */
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
                Map<String, List<IndexDecl>> businessTableIndexes,
                Map<String, Map<String, String>> businessTableColumnDefaultExpressions
        ) {
            this(engine, storageMode, physicalDatabase, schemaFingerprint, internalTables, businessTables,
                    businessTableColumns, businessTableAdditiveColumns, businessTableColumnTypes,
                    businessTableRenamedColumns, businessTableRenames, allowDestructiveRecreate, strategy,
                    scope, destructiveRecreateConfirmation, destructiveAcknowledgment,
                    businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints,
                    planItemStableStrings, ownership, businessTableForeignKeys, businessTableIndexes,
                    businessTableColumnDefaultExpressions, Map.of());
        }

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

        /**
         * STOR-16: true when this app declares its data is discarded on every start.
         *
         * <p>ENG-2: only the literal {@code Ephemeral}. A manifest baked before STOR-16 by an older
         * generator carries the retired {@code RecreateOnAppStart}; for those apps that spelling was
         * always a no-op, so it is honoured as the normal non-destructive path (see
         * {@link #retiredRecreateOnAppStart()}), never as a wipe.
         */
        boolean ephemeral() {
            return "Ephemeral".equals(strategy);
        }

        /** ENG-2: true for a manifest baked with the removed {@code RecreateOnAppStart} spelling. */
        boolean retiredRecreateOnAppStart() {
            return "RecreateOnAppStart".equals(strategy);
        }

        /**
         * 3.3 (B10 one-command H2-&gt;Postgres promotion, package 3.3): a copy of this manifest with
         * only {@code engine} replaced. {@link #pinDialectFromManifest} trusts {@code manifest.engine()}
         * unconditionally to pin {@link com.npdev.kernel.storage.sql.SqlDialects#active()} before any
         * DDL runs -- correct for a normal boot (the manifest's declared engine IS the app's own
         * configured database), and WRONG for {@code PromoteMain} realizing schema on a genuinely
         * different target engine, where it would pin the SOURCE app's home engine against the
         * TARGET connection. Rather than weaken {@code pinDialectFromManifest}'s trust for every
         * caller (a normal boot has no reason to doubt its own manifest, and REG-106 already
         * documents why that pinning must stay unconditional and early), {@code PromoteMain} instead
         * hands {@link #migrate(Flyway, SchemaManifest)} a manifest that HONESTLY says which engine
         * it is about to run DDL against -- derived from {@code SqlDialects.forConnection} on the
         * target, the same "trust the socket, not a config file" reasoning
         * {@link MigrationMutex#acquire} and every {@code *Main} class already apply. Every other
         * field is unchanged: table/column declarations do not vary by engine.
         */
        SchemaManifest withEngine(String newEngine) {
            return new SchemaManifest(newEngine, storageMode, physicalDatabase, schemaFingerprint,
                    internalTables, businessTables, businessTableColumns, businessTableAdditiveColumns,
                    businessTableColumnTypes, businessTableRenamedColumns, businessTableRenames,
                    allowDestructiveRecreate, strategy, scope, destructiveRecreateConfirmation,
                    destructiveAcknowledgment, businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints, planItemStableStrings,
                    ownership, businessTableForeignKeys, businessTableIndexes, businessTableColumnDefaultExpressions,
                    businessTableColumnUids, businessTableUids);
        }

        /**
         * REG-209 (B1 lift, package P7): a copy of this manifest with {@code businessTableRenamedColumns}
         * replaced -- every OTHER field, including {@code businessTableColumnUids} itself, is unchanged.
         * {@link ColumnIdentityStore#resolveRenamesByIdentity} uses this to feed an identity-augmented
         * renames map into the SAME pipeline a declared {@code renamedFrom} already flows through
         * ({@link #columnFactsFor} reads {@code businessTableRenamedColumns} directly, feeding {@code
         * DesiredColumn.renamedFromColumn} and from there {@code SchemaDiffEngine}'s {@code SAFE_RENAME}
         * classification) -- no second classification path to keep in sync.
         */
        SchemaManifest withRenamedColumns(Map<String, Map<String, String>> replacementRenamedColumns) {
            return new SchemaManifest(engine, storageMode, physicalDatabase, schemaFingerprint,
                    internalTables, businessTables, businessTableColumns, businessTableAdditiveColumns,
                    businessTableColumnTypes, replacementRenamedColumns, businessTableRenames,
                    allowDestructiveRecreate, strategy, scope, destructiveRecreateConfirmation,
                    destructiveAcknowledgment, businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints, planItemStableStrings,
                    ownership, businessTableForeignKeys, businessTableIndexes, businessTableColumnDefaultExpressions,
                    businessTableColumnUids, businessTableUids);
        }

        /**
         * REG-305: a copy with {@code businessTableRenames} replaced -- {@link TableIdentityStore#resolveRenamesByIdentity}
         * feeds an identity-augmented table-rename map through it into the SAME diff a declared concept
         * {@code renamedFrom} already flows through. Every other field is unchanged.
         */
        SchemaManifest withTableRenames(Map<String, String> replacementTableRenames) {
            return new SchemaManifest(engine, storageMode, physicalDatabase, schemaFingerprint,
                    internalTables, businessTables, businessTableColumns, businessTableAdditiveColumns,
                    businessTableColumnTypes, businessTableRenamedColumns, replacementTableRenames,
                    allowDestructiveRecreate, strategy, scope, destructiveRecreateConfirmation,
                    destructiveAcknowledgment, businessTableRequiredColumns, businessTableColumnDefaultLiterals,
                    businessTableExpressionDefaultColumns, businessTableUniqueConstraints, planItemStableStrings,
                    ownership, businessTableForeignKeys, businessTableIndexes, businessTableColumnDefaultExpressions,
                    businessTableColumnUids, businessTableUids);
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
