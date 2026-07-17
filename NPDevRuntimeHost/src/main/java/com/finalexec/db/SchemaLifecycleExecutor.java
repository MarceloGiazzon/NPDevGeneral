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
            System.out.println("NPDev schema lifecycle: in-place rename pass left a residual classification of "
                    + residual + " (a table's diff was not fully explained by declared renames, or a rename was "
                    + "combined with a type change on the same column -- Phase 3's problem until type widening "
                    + "lands); falling through to destructive recreation as the safety net.");
        } else if (classification == SchemaChangeClassification.TYPE_CHANGE_DETECTED) {
            System.out.println("NPDev schema lifecycle: fingerprint changed from " + stored + " to "
                    + manifest.schemaFingerprint() + " -- classified as TYPE_CHANGE_DETECTED (an existing column's "
                    + "declared SQL type changed). Still goes through the destructive recreate path below.");
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
     * Executes in-place {@code ALTER TABLE ... RENAME COLUMN} statements (LNCH-1 Phase 1) for
     * every business table whose live-DB-vs-manifest diff is FULLY explained by declared
     * {@code renamedFrom} pairs, per {@link RenameResolution} -- reusing the exact same
     * per-table eligibility test {@link #classify} uses, so a table only gets its columns renamed
     * here if {@code classify} would otherwise have called it a clean {@code RENAME_DETECTED}. A
     * table whose diff is NOT fully explained (a genuine drop/add mixed in, or a rename combined
     * with a type change on the same column) is left completely untouched -- no partial/best-effort
     * renaming -- so the caller's re-classification correctly falls through to the destructive path
     * for that table instead of leaving the database in a state no single classification describes.
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
                Map<String, String> expectedTypes = manifest.businessTableColumnTypes().getOrDefault(table, Map.of());
                Map<String, String> actualTypes = readActualColumnTypes(metadata, table);
                boolean typeChanged = false;
                for (Map.Entry<String, String> pair : resolution.explainedRenames().entrySet()) {
                    String expectedType = normalizeSqlType(expectedTypes.get(pair.getKey()));
                    String actualType = normalizeSqlType(actualTypes.get(pair.getValue()));
                    if (expectedType != null && actualType != null && !expectedType.equals(actualType)) {
                        typeChanged = true;
                        break;
                    }
                }
                if (typeChanged) {
                    skipped.add(table + " (a declared rename is combined with a type change on the same column -- "
                            + "deferred to the destructive path pending Phase 3's type-widening support)");
                    continue;
                }
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
     * Best-effort cross-engine type comparison, not exact: strips length/precision
     * ("VARCHAR(255)" -> "VARCHAR"), uppercases, and treats JSON/JSONB as equivalent (H2 reports
     * "JSON" for a column the manifest declares as Postgres-style "JSONB" -- see
     * {@code SchemaRealizationEmitter.renderType}). Good enough to flag an unrelated/incompatible
     * type swap (e.g. VARCHAR -> BIGINT); not a guarantee against every engine-specific type alias.
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
     */
    private static String normalizeSqlType(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return null;
        }
        String normalized = sqlType.trim().toUpperCase(Locale.ROOT);
        int parenIndex = normalized.indexOf('(');
        if (parenIndex >= 0) {
            normalized = normalized.substring(0, parenIndex).trim();
        }
        if ("JSONB".equals(normalized)) {
            return "JSON";
        }
        if ("CHARACTER VARYING".equals(normalized)) {
            return "VARCHAR";
        }
        return normalized;
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
                    types.put(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT), resultSet.getString("TYPE_NAME"));
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
