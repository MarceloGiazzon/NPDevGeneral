package com.finalexec.db;

import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.npdev.kernel.dbschema.InternalColumnDefinition;
import com.npdev.kernel.dbschema.InternalIndexDefinition;
import com.npdev.kernel.dbschema.InternalTableDefinition;
import com.npdev.kernel.dbschema.NpdevInternalTables;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * npdev.trial.force-physical-schema's own table-creation step (see the field's javadoc on
 * {@link SchemaLifecycleExecutor}). Every other path that creates a NPDev-owned table -- business OR
 * internal -- relies on the generator-emitted V1__ Flyway migration under
 * {@code classpath:db/schema-realization} -- that migration only exists for a model GENERATED against
 * a real JDBC engine. A model whose db.definition.json declares {@code engine: InMemory} never gets
 * one (there is nothing for flyway.migrate() to run), so a profile that forces a real database onto
 * that same model needs a SEPARATE way to create the tables it is about to use.
 *
 * <p>Two SEPARATE sources, because business and internal tables are shaped by different things:
 * business tables come from {@link DesiredSchemaFactory#fromManifest}, the exact same "what the model
 * intends" projection the rename/relax/tighten passes already diff against; internal tables
 * ({@code npdev_*}) are platform-owned, not model-derived, so they come from
 * {@link NpdevInternalTables#all()} -- the SAME kernel-level catalog {@code afterMigrate}'s own
 * {@code npdev_schema_metadata} creation is conceptually one member of (that one stays hand-written
 * there; every OTHER catalog member was never created at all outside a real V1__ migration until this
 * pass existed).
 *
 * <p>Deliberately minimal, matching what {@code afterMigrate}'s OWN later steps still need to do their
 * job and nothing more: columns with their type and nullability, a primary key, and (for internal
 * tables, which declare them explicitly) indexes. No foreign keys or unique constraints on BUSINESS
 * tables here -- {@link UniqueConstraintPass#applyUniqueConstraints} already runs afterward in
 * {@code afterMigrate} and is unconditionally idempotent, so unique constraints still get applied
 * there; foreign keys are a real gap for this override (tracked, not silently claimed to work) but do
 * not block the CRUD/JPA access these tests actually exercise.
 *
 * <p>{@code IF NOT EXISTS} via {@link com.npdev.kernel.storage.sql.SqlDialect#guardedCreateTable}, same
 * as every other CREATE TABLE in this package -- idempotent by construction, safe to call every boot.
 */
final class MissingTableCreationPass {

    private MissingTableCreationPass() {
    }

    static void createMissingBusinessTables(DataSource dataSource, SchemaLifecycleExecutor.SchemaManifest manifest) {
        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifest);
        try (Connection connection = dataSource.getConnection()) {
            for (DesiredTable table : desired.tables().values()) {
                if (tableExists(connection, table.name())) {
                    continue;
                }
                createTable(connection, table.name(), businessColumnDefs(table), List.of());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "npdev.trial.force-physical-schema: failed creating missing business table(s) from the manifest",
                    exception);
        }
    }

    static void createMissingInternalTables(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            for (InternalTableDefinition table : NpdevInternalTables.all()) {
                if (tableExists(connection, table.name())) {
                    continue;
                }
                createTable(connection, table.name(), internalColumnDefs(table), table.indexes());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "npdev.trial.force-physical-schema: failed creating missing internal table(s) from NpdevInternalTables",
                    exception);
        }
    }

    private static void createTable(Connection connection, String tableName, List<String> columnDefs,
            List<InternalIndexDefinition> indexes) throws SQLException {
        String ddl = "CREATE TABLE " + tableName + " (" + String.join(", ", columnDefs) + ")";
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(tableName, ddl))) {
            statement.executeUpdate();
        }
        for (InternalIndexDefinition index : indexes) {
            String indexDdl = "CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + index.name()
                    + " ON " + tableName + " (" + String.join(", ", index.columns()) + ")";
            try (PreparedStatement statement = connection.prepareStatement(
                    SqlDialects.active().guardedCreateIndex(index.name(), tableName, indexDdl))) {
                statement.executeUpdate();
            }
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().tableExistsInCurrentSchemaSql(tableName))) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static List<String> businessColumnDefs(DesiredTable table) {
        List<String> columnDefs = new ArrayList<>();
        for (DesiredColumn column : table.columns().values()) {
            String type = column.normalizedSqlType() == null ? "TEXT" : column.normalizedSqlType();
            String defaultClause = repairablePlatformColumnDefault(column.name());
            columnDefs.add(column.name() + " " + type + defaultClause + (column.nullable() ? "" : " NOT NULL"));
        }
        if (table.columns().containsKey("id")) {
            columnDefs.add("PRIMARY KEY (id)");
        }
        return columnDefs;
    }

    /**
     * A real V1__ migration (SchemaRealizationEmitter:370-377, mirrored in
     * SchemaLifecycleExecutor#REPAIRABLE_PLATFORM_COLUMNS) gives every business table's platform
     * columns a fixed default, which is what lets a writer that never sets them explicitly still
     * produce a valid row -- confirmed live: the kernel's flow-driven "createConcept" persistence
     * capability step (unlike the direct JPA CRUD path, which sets these on the entity itself) sends
     * an INSERT with no row_version at all, and failed with a NOT NULL violation the moment this
     * pass's own hand-written DDL stopped matching that real-migration default. No manifest field
     * carries these platform defaults (DesiredColumn#literalDefault is the MODEL's declared default
     * for a model field, not a platform column's), so they are reproduced here by name instead.
     */
    private static String repairablePlatformColumnDefault(String columnName) {
        return switch (columnName) {
            case "version", "row_version" -> " DEFAULT 0";
            case "tenant_id" -> " DEFAULT 'default'";
            default -> "";
        };
    }

    private static List<String> internalColumnDefs(InternalTableDefinition table) {
        List<String> columnDefs = new ArrayList<>();
        for (InternalColumnDefinition column : table.columns()) {
            String defaultClause = column.defaultExpression().isBlank()
                    ? "" : " DEFAULT " + column.defaultExpression();
            columnDefs.add(column.name() + " " + column.sqlType() + defaultClause
                    + (column.required() ? " NOT NULL" : ""));
        }
        columnDefs.add("PRIMARY KEY (" + String.join(", ", table.primaryKey().columns()) + ")");
        return columnDefs;
    }
}
