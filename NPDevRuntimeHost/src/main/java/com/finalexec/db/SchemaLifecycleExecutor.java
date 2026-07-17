package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public final class SchemaLifecycleExecutor implements FlywayMigrationStrategy {
    private static final String METADATA_TABLE = "npdev_schema_metadata";
    private static final String FINGERPRINT_KEY = "schemaFingerprint";
    private static final String SCHEMA_REALIZATION_LOCATION = "classpath:db/schema-realization";
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("information_schema", "pg_catalog");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public void migrate(Flyway flyway) {
        Configuration configuration = flyway.getConfiguration();
        DataSource dataSource = configuration.getDataSource();
        if (dataSource == null) {
            flyway.migrate();
            return;
        }
        SchemaManifest manifest = loadManifest();
        if (manifest == null || !manifest.physicalDatabase()) {
            flyway.migrate();
            return;
        }
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
        afterMigrate(dataSource, manifest);
    }

    private DestructiveRecreation beforeMigrate(DataSource dataSource, SchemaManifest manifest) {
        String stored = readFingerprint(dataSource);
        if (stored == null || stored.isBlank()) {
            System.out.println("NPDev schema lifecycle: no stored schema fingerprint found; initializing schema realization.");
            return DestructiveRecreation.none();
        }
        if (stored.equals(manifest.schemaFingerprint())) {
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

        SchemaChangeClassification classification = classify(dataSource, manifest);
        if (classification == SchemaChangeClassification.SAFE_ADDITIVE) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " but every difference is a new non-bond column on an "
                    + "already-existing table; skipping destructive recreation (handled by the additive repeatable migration).");
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
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place field rename(s) fully resolved the fingerprint "
                        + "diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
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
                if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                    System.out.println("NPDev schema lifecycle: in-place rename(s) and type widening(s) fully "
                            + "resolved the fingerprint diff (residual classification SAFE_ADDITIVE); skipping "
                            + "destructive recreation.");
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
            attemptInPlaceTypeWidenings(dataSource, manifest);
            SchemaChangeClassification residual = classify(dataSource, manifest);
            if (residual == SchemaChangeClassification.SAFE_ADDITIVE) {
                System.out.println("NPDev schema lifecycle: in-place type widening(s) fully resolved the "
                        + "fingerprint diff (residual classification SAFE_ADDITIVE); skipping destructive recreation.");
                return DestructiveRecreation.safeAdditiveOutcome();
            }
            System.out.println("NPDev schema lifecycle: in-place widening pass left a residual classification of "
                    + residual + " (at least one type-differing column on some table was a narrowing or "
                    + "incomparable change -- per-table all-or-nothing means nothing on that table was applied); "
                    + "falling through to destructive recreation as the safety net.");
        }
        if (!manifest.destructiveAllowed()) {
            throw new IllegalStateException("Schema fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " but destructive recreation is not explicitly allowed.");
        }
        List<String> tables = new ArrayList<>();
        tables.addAll(manifest.businessTables());
        tables.addAll(manifest.internalTables());
        Collections.reverse(tables);
        SchemaDropSnapshotWriter.snapshotBeforeDrop(dataSource, tables);
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
            return new DestructiveRecreation(true, false, List.copyOf(dropped));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed destructive schema recreation", exception);
        }
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
            DatabaseMetaData metadata = connection.getMetaData();
            Set<String> liveTables = readActualTableNames(metadata);
            Set<String> expectedTables = new LinkedHashSet<>(manifest.businessTableColumns().keySet());
            Set<String> missingTables = new LinkedHashSet<>(expectedTables);
            missingTables.removeAll(liveTables);
            Set<String> extraTables = new LinkedHashSet<>(liveTables);
            extraTables.removeAll(expectedTables);

            RenameResolution.Result resolution = RenameResolution.resolve(missingTables, extraTables, declaredTableRenames);
            for (Map.Entry<String, String> pair : resolution.explainedRenames().entrySet()) {
                String newTable = pair.getKey();
                String oldTable = pair.getValue();
                executeRenameTable(connection, oldTable, newTable);
                renamed.add(oldTable + " -> " + newTable);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying in-place table renames", exception);
        }
        if (!renamed.isEmpty()) {
            System.out.println("NPDev schema lifecycle: applied in-place table renames: " + renamed);
        }
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
    private static Set<String> readActualTableNames(DatabaseMetaData metadata) throws SQLException {
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
     * every business table whose live-DB-vs-manifest diff is FULLY explained by declared
     * {@code renamedFrom} pairs, per {@link RenameResolution} -- reusing the exact same
     * per-table eligibility test {@link #classify} uses, so a table only gets its columns renamed
     * here if {@code classify} would otherwise have called it a clean {@code RENAME_DETECTED} or
     * {@code TYPE_CHANGE_DETECTED}-via-a-renamed-column. A table whose diff is NOT fully explained
     * by declared renames (plus, at most, additive-eligible new columns) -- a genuine drop/add mixed
     * in that no rename or additive column accounts for -- is left completely untouched -- no
     * partial/best-effort renaming -- so the caller's re-classification correctly falls through to
     * the destructive path for that table instead of leaving the database in a state no single
     * classification describes. A rename COMBINED with a type change on the same column, by
     * contrast, is no longer a reason to skip the whole table here (see the inline LNCH-1 P3 note
     * below) -- {@link #attemptInPlaceTypeWidenings} resolves the type side afterward.
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
        List<String> renamed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, Map<String, String>> tableRenames : manifest.businessTableRenamedColumns().entrySet()) {
                String table = tableRenames.getKey();
                Map<String, String> declaredRenames = tableRenames.getValue();
                if (declaredRenames.isEmpty()) {
                    continue;
                }
                List<String> expectedColumns = manifest.businessTableColumns().getOrDefault(table, List.of());
                if (expectedColumns.isEmpty()) {
                    continue;
                }
                Set<String> expected = new LinkedHashSet<>(expectedColumns);
                Set<String> actual = readActualColumns(metadata, table);
                if (actual.isEmpty()) {
                    // Table doesn't exist yet (brand new concept) -- nothing to rename; matches
                    // classify()'s "actual.isEmpty() -> continue" guard for the same reason (§2.4).
                    continue;
                }
                Set<String> additiveEligible = new LinkedHashSet<>(
                        manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
                Set<String> extraInDb = new LinkedHashSet<>(actual);
                extraInDb.removeAll(expected);
                Set<String> missingInDb = new LinkedHashSet<>(expected);
                missingInDb.removeAll(actual);

                RenameResolution.Result resolution = RenameResolution.resolve(missingInDb, extraInDb, declaredRenames);
                if (resolution.explainedRenames().isEmpty()) {
                    continue;
                }
                boolean eligible = resolution.remainingExtra().isEmpty()
                        && (resolution.remainingMissing().isEmpty()
                                || additiveEligible.containsAll(resolution.remainingMissing()));
                if (!eligible) {
                    skipped.add(table + " (diff not fully explained by declared renames -- remainingMissing="
                            + resolution.remainingMissing() + ", remainingExtra=" + resolution.remainingExtra() + ")");
                    continue;
                }
                // LNCH-1 P3 (3.3 composability): a rename MAY be combined with a type change on the
                // same column. Phase 1 deferred that whole table to the destructive path here
                // (comment used to read "deferred to the destructive path pending Phase 3's
                // type-widening support") -- Phase 3 closes that gap from the OTHER side instead:
                // the rename is applied unconditionally whenever it is otherwise eligible, and
                // beforeMigrate() runs attemptInPlaceTypeWidenings() immediately afterward, against
                // the NEW column name, to resolve any residual type diff. If that residual turns out
                // to be a narrowing/incomparable change (not safely widenable), the table still ends
                // up on the destructive path via the final re-classification -- applying the rename
                // first causes no incorrect persisted state, since a subsequent destructive recreate
                // drops and recreates the table (and the pre-drop snapshot correctly captures data
                // under the already-renamed column).
                for (Map.Entry<String, String> pair : resolution.explainedRenames().entrySet()) {
                    String newName = pair.getKey();
                    String oldName = pair.getValue();
                    executeRenameColumn(connection, manifest.engine(), table, oldName, newName);
                    renamed.add(table + "." + oldName + " -> " + newName);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying in-place field renames", exception);
        }
        if (!renamed.isEmpty()) {
            System.out.println("NPDev schema lifecycle: applied in-place field renames: " + renamed);
        }
        if (!skipped.isEmpty()) {
            System.out.println("NPDev schema lifecycle: tables left for the destructive path (rename did not "
                    + "fully explain the diff): " + skipped);
        }
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
        List<String> widened = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                List<String> expectedColumns = entry.getValue();
                Set<String> actualColumns = readActualColumns(metadata, table);
                if (actualColumns.isEmpty()) {
                    // Table doesn't exist yet (brand new concept) -- nothing to widen; matches
                    // classify()'s "actual.isEmpty() -> continue" guard for the same reason (§2.4).
                    continue;
                }
                Map<String, String> expectedTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
                Map<String, String> actualTypes = readActualColumnTypes(metadata, table);

                Map<String, String> differing = new LinkedHashMap<>();
                for (String column : expectedColumns) {
                    if (!actualColumns.contains(column)) {
                        continue; // not a shared column at this point (new / not-yet-renamed / etc.) -- out of scope here
                    }
                    String expectedType = expectedTypes.get(column);
                    String actualType = actualTypes.get(column);
                    if (expectedType == null || actualType == null) {
                        continue;
                    }
                    if (!normalizeSqlType(expectedType).equals(normalizeSqlType(actualType))) {
                        differing.put(column, expectedType);
                    }
                }
                if (differing.isEmpty()) {
                    continue;
                }

                boolean allWidening = true;
                for (Map.Entry<String, String> diff : differing.entrySet()) {
                    String actualType = actualTypes.get(diff.getKey());
                    if (TypeChangeMatrix.classify(actualType, diff.getValue()) != TypeChangeMatrix.Classification.WIDENING) {
                        allWidening = false;
                        break;
                    }
                }
                if (!allWidening) {
                    skipped.add(table + " (not every type-differing column on this table is a safe widening -- "
                            + "per-table all-or-nothing rule, deferred to the destructive path)");
                    continue;
                }

                for (Map.Entry<String, String> diff : differing.entrySet()) {
                    executeWidenColumnType(connection, manifest.engine(), table, diff.getKey(), diff.getValue());
                    widened.add(table + "." + diff.getKey() + " -> " + diff.getValue());
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed applying in-place type widenings", exception);
        }
        if (!widened.isEmpty()) {
            System.out.println("NPDev schema lifecycle: applied in-place type widenings: " + widened);
        }
        if (!skipped.isEmpty()) {
            System.out.println("NPDev schema lifecycle: tables left for the destructive path (type diff not "
                    + "fully explained by safe widenings): " + skipped);
        }
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
            for (Map.Entry<String, List<String>> entry : manifest.businessTableColumns().entrySet()) {
                String table = entry.getKey();
                Set<String> expected = new LinkedHashSet<>(entry.getValue());
                Set<String> additiveEligible = new LinkedHashSet<>(
                        manifest.businessTableAdditiveColumns().getOrDefault(table, List.of()));
                Set<String> actual = readActualColumns(metadata, table);
                if (actual.isEmpty()) {
                    // Table doesn't exist yet (brand new concept); V1's CREATE TABLE IF NOT EXISTS handles it.
                    continue;
                }
                Set<String> extraInDb = new LinkedHashSet<>(actual);
                extraInDb.removeAll(expected);
                Set<String> missingInDb = new LinkedHashSet<>(expected);
                missingInDb.removeAll(actual);

                if (extraInDb.isEmpty() && (missingInDb.isEmpty() || additiveEligible.containsAll(missingInDb))) {
                    // No column was removed and every added column is additive-eligible -- but a
                    // SHARED column (same name, present both before and after) may still have had its
                    // type changed, which a pure name-based diff can never see. Must check before
                    // declaring this table safe, not after -- a perfect name match would otherwise
                    // always short-circuit past the type-change check below.
                    if (hasTypeChange(metadata, table, expected, manifest.businessTableColumnTypes().getOrDefault(table, Map.of()))) {
                        worst = worse(worst, SchemaChangeClassification.TYPE_CHANGE_DETECTED);
                    }
                    continue;
                }

                Map<String, String> renames = manifest.businessTableRenamedColumns().getOrDefault(table, Map.of());
                Map<String, String> expectedTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
                RenameResolution.Result resolution = RenameResolution.resolve(missingInDb, extraInDb, renames);
                Set<String> remainingMissing = resolution.remainingMissing();
                Set<String> remainingExtra = resolution.remainingExtra();

                if (remainingExtra.isEmpty() && (remainingMissing.isEmpty() || additiveEligible.containsAll(remainingMissing))) {
                    // Renames (plus, at most, additive-eligible new columns) fully explain the
                    // diff -- but a renamed column may ALSO have had its type changed (the live
                    // column is still under the OLD name, so a plain expected-name lookup into
                    // actualTypes can never see it), and an unrelated, non-renamed shared column
                    // on this same table may independently have a type change. Both must be
                    // checked before declaring this table a clean RENAME_DETECTED, otherwise a
                    // type change silently rides along with the rename onto the in-place path.
                    Map<String, String> actualTypes = readActualColumnTypes(metadata, table);
                    boolean typeChanged = false;
                    for (Map.Entry<String, String> explained : resolution.explainedRenames().entrySet()) {
                        String expectedType = normalizeSqlType(expectedTypes.get(explained.getKey()));
                        String actualType = normalizeSqlType(actualTypes.get(explained.getValue()));
                        if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
                            typeChanged = true;
                            break;
                        }
                    }
                    if (!typeChanged) {
                        Set<String> sharedColumns = new LinkedHashSet<>(expected);
                        sharedColumns.removeAll(resolution.explainedRenames().keySet());
                        sharedColumns.removeAll(remainingMissing);
                        typeChanged = hasTypeChange(metadata, table, sharedColumns, expectedTypes);
                    }
                    worst = worse(worst, typeChanged
                            ? SchemaChangeClassification.TYPE_CHANGE_DETECTED
                            : SchemaChangeClassification.RENAME_DETECTED);
                    continue;
                }
                return SchemaChangeClassification.DESTRUCTIVE;
            }
            return worst;
        } catch (SQLException exception) {
            return SchemaChangeClassification.DESTRUCTIVE;
        }
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
    private static String normalizeSqlType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return null;
        }
        String trimmed = sqlType.trim().toUpperCase(Locale.ROOT);
        int parenIndex = trimmed.indexOf('(');
        String base = parenIndex >= 0 ? trimmed.substring(0, parenIndex).trim() : trimmed;
        String parameters = parenIndex >= 0 ? trimmed.substring(parenIndex).replaceAll("\\s+", "") : "";
        if ("JSONB".equals(base)) {
            base = "JSON";
        }
        if ("CHARACTER VARYING".equals(base)) {
            base = "VARCHAR";
        }
        return base + parameters;
    }

    private static Map<String, String> readActualColumnTypes(DatabaseMetaData metadata, String table) {
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

    private static Set<String> readActualColumns(DatabaseMetaData metadata, String table) throws SQLException {
        Set<String> columns = readActualColumns(metadata, table, table.toLowerCase(Locale.ROOT));
        if (columns.isEmpty()) {
            columns = readActualColumns(metadata, table, table.toUpperCase(Locale.ROOT));
        }
        return columns;
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

    private static String safeIdentifier(String identifier) {
        String value = identifier == null ? "" : identifier.trim();
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalStateException("Unsafe table identifier in schema realization manifest: " + identifier);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private void afterMigrate(DataSource dataSource, SchemaManifest manifest) {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + METADATA_TABLE
                            + " (metadata_key TEXT PRIMARY KEY, metadata_value TEXT NOT NULL, updated_at_ms BIGINT NOT NULL)"
            )) {
                statement.executeUpdate();
            }
            int updated;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE " + METADATA_TABLE + " SET metadata_value = ?, updated_at_ms = ? WHERE metadata_key = ?"
            )) {
                statement.setString(1, manifest.schemaFingerprint());
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, FINGERPRINT_KEY);
                updated = statement.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + METADATA_TABLE + " (metadata_key, metadata_value, updated_at_ms) VALUES (?, ?, ?)"
                )) {
                    statement.setString(1, FINGERPRINT_KEY);
                    statement.setString(2, manifest.schemaFingerprint());
                    statement.setLong(3, System.currentTimeMillis());
                    statement.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed storing schema fingerprint", exception);
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
                    lifecycle.path("destructiveRecreateConfirmation").asText("")
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
            String destructiveRecreateConfirmation
    ) {
        boolean destructiveAllowed() {
            return "DropAndRecreateOnStructureChange".equals(strategy)
                    && allowDestructiveRecreate
                    && "NpdevOwnedTablesOnly".equals(scope)
                    && "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED".equals(destructiveRecreateConfirmation);
        }
    }

    private record DestructiveRecreation(boolean performed, boolean safeAdditive, List<String> droppedTables) {
        static DestructiveRecreation none() {
            return new DestructiveRecreation(false, false, List.of());
        }

        static DestructiveRecreation safeAdditiveOutcome() {
            return new DestructiveRecreation(false, true, List.of());
        }
    }
}
