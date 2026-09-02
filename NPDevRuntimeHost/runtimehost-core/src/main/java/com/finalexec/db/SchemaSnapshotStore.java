package com.finalexec.db;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finalexec.db.schemastate.DesiredColumn;
import com.finalexec.db.schemastate.DesiredForeignKey;
import com.finalexec.db.schemastate.DesiredIndex;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.DesiredTable;
import com.finalexec.db.schemastate.DesiredUniqueConstraint;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * B5-A (boundary-lift 2026-09-02, package 2.3): {@code npdev_schema_snapshot} -- one full desired-schema
 * snapshot per fingerprint this database has actually reached, keyed by that fingerprint so it travels
 * with the database (not the filesystem). {@link SchemaLifecycleExecutor#afterMigrate} writes one at the
 * end of every successful migration, in the SAME connection block that already updates the stored
 * fingerprint. {@link SchemaAheadAnalysis} reads one back when a schema-ahead refusal fires, to diff
 * THIS build's own desired schema against the shape the fingerprint the live database is actually at
 * last recorded -- turning "the database is ahead" into "here is exactly what differs".
 *
 * <p>Hand-rolled JSON (Jackson's tree API), not {@code ObjectMapper.readValue(json, DesiredSchema.class)}:
 * the {@code schemastate} records carry no Jackson creator annotations, and every other JSON reader in
 * this package (see {@code JdbcBusinessConceptStore}) reads generic {@code Object}/tree shapes rather
 * than trusting reflective record binding to a specific class, for the same reason.
 */
final class SchemaSnapshotStore {

    private static final String SNAPSHOT_TABLE = "npdev_schema_snapshot";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SchemaSnapshotStore() {
    }

    /**
     * Writes (or replaces) the snapshot for one fingerprint using the SAME open {@link Connection}
     * {@link SchemaLifecycleExecutor#afterMigrate} already holds when it writes the stored fingerprint,
     * so a snapshot can never exist for a fingerprint that metadata write itself failed to commit.
     * Idempotent -- a re-boot at an unchanged fingerprint overwrites with identical content.
     */
    static void writeSnapshot(Connection connection, String fingerprint, DesiredSchema desired) throws SQLException {
        ensureSnapshotTable(connection);
        String json = toJson(desired);
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + SNAPSHOT_TABLE + " SET snapshot_json = ?, recorded_at_utc = ? WHERE fingerprint = ?")) {
            statement.setString(1, json);
            statement.setLong(2, System.currentTimeMillis());
            statement.setString(3, fingerprint);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO " + SNAPSHOT_TABLE + " (fingerprint, snapshot_json, recorded_at_utc) VALUES (?, ?, ?)")) {
                statement.setString(1, fingerprint);
                statement.setString(2, json);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    /**
     * Reads the snapshot recorded for one fingerprint, or empty when none was ever recorded -- either
     * this database predates B5-A, or that fingerprint was reached by a mark-done fast-forward rather
     * than a real migration (only {@link SchemaLifecycleExecutor#afterMigrate}'s real path writes one).
     * Never throws: a broken read must degrade the schema-ahead diagnosis to "no snapshot available",
     * never block the refusal it exists to explain.
     */
    static Optional<DesiredSchema> readSnapshot(DataSource dataSource, String fingerprint) {
        try (Connection connection = dataSource.getConnection()) {
            ensureSnapshotTable(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT snapshot_json FROM " + SNAPSHOT_TABLE + " WHERE fingerprint = ?")) {
                statement.setString(1, fingerprint);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(fromJson(resultSet.getString(1)));
                }
            }
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static void ensureSnapshotTable(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SqlDialects.active().guardedCreateTable(SNAPSHOT_TABLE,
                        "CREATE TABLE " + SNAPSHOT_TABLE
                        + " (fingerprint " + InternalDdlTypes.keyText() + " PRIMARY KEY, "
                        + "snapshot_json " + InternalDdlTypes.text() + " NOT NULL, "
                        + "recorded_at_utc BIGINT NOT NULL)")
        )) {
            statement.executeUpdate();
        }
    }

    static String toJson(DesiredSchema desired) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ObjectNode tables = root.putObject("tables");
        for (DesiredTable table : desired.tables().values()) {
            ObjectNode t = tables.putObject(table.name());
            t.put("name", table.name());
            if (table.renamedFromTable() != null) {
                t.put("renamedFromTable", table.renamedFromTable());
            }
            ObjectNode columns = t.putObject("columns");
            for (DesiredColumn column : table.columns().values()) {
                ObjectNode c = columns.putObject(column.name());
                c.put("name", column.name());
                if (column.normalizedSqlType() != null) {
                    c.put("normalizedSqlType", column.normalizedSqlType());
                }
                c.put("nullable", column.nullable());
                if (column.literalDefault() != null) {
                    c.put("literalDefault", column.literalDefault());
                }
                c.put("platformManaged", column.platformManaged());
                c.put("requiredByModel", column.requiredByModel());
                c.put("bond", column.bond());
                c.put("additiveEligible", column.additiveEligible());
                if (column.renamedFromColumn() != null) {
                    c.put("renamedFromColumn", column.renamedFromColumn());
                }
            }
            ArrayNode uniques = t.putArray("uniques");
            for (DesiredUniqueConstraint unique : table.uniques()) {
                ArrayNode cols = uniques.addArray();
                unique.columns().forEach(cols::add);
            }
            ArrayNode fks = t.putArray("foreignKeys");
            for (DesiredForeignKey fk : table.foreignKeys()) {
                ObjectNode f = fks.addObject();
                ArrayNode fCols = f.putArray("columns");
                fk.columns().forEach(fCols::add);
                if (fk.referencedTable() != null) {
                    f.put("referencedTable", fk.referencedTable());
                }
                ArrayNode rCols = f.putArray("referencedColumns");
                fk.referencedColumns().forEach(rCols::add);
            }
            ArrayNode indexes = t.putArray("indexes");
            for (DesiredIndex index : table.indexes()) {
                ObjectNode i = indexes.addObject();
                ArrayNode iCols = i.putArray("columns");
                index.columns().forEach(iCols::add);
                i.put("unique", index.unique());
            }
        }
        return root.toString();
    }

    static DesiredSchema fromJson(String json) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            Map<String, DesiredTable> tables = new LinkedHashMap<>();
            JsonNode tablesNode = root.path("tables");
            Iterator<String> tableNames = tablesNode.fieldNames();
            while (tableNames.hasNext()) {
                String tableKey = tableNames.next();
                JsonNode t = tablesNode.get(tableKey);
                Map<String, DesiredColumn> columns = new LinkedHashMap<>();
                JsonNode columnsNode = t.path("columns");
                Iterator<String> columnNames = columnsNode.fieldNames();
                while (columnNames.hasNext()) {
                    String columnKey = columnNames.next();
                    JsonNode c = columnsNode.get(columnKey);
                    columns.put(columnKey, new DesiredColumn(
                            c.path("name").asText(),
                            textOrNull(c, "normalizedSqlType"),
                            c.path("nullable").asBoolean(false),
                            textOrNull(c, "literalDefault"),
                            c.path("platformManaged").asBoolean(false),
                            c.path("requiredByModel").asBoolean(false),
                            c.path("bond").asBoolean(false),
                            c.path("additiveEligible").asBoolean(false),
                            textOrNull(c, "renamedFromColumn")));
                }
                List<DesiredUniqueConstraint> uniques = new ArrayList<>();
                for (JsonNode uniqueNode : t.path("uniques")) {
                    uniques.add(new DesiredUniqueConstraint(stringList(uniqueNode)));
                }
                List<DesiredForeignKey> foreignKeys = new ArrayList<>();
                for (JsonNode fkNode : t.path("foreignKeys")) {
                    foreignKeys.add(new DesiredForeignKey(
                            stringList(fkNode.path("columns")),
                            textOrNull(fkNode, "referencedTable"),
                            stringList(fkNode.path("referencedColumns"))));
                }
                List<DesiredIndex> indexes = new ArrayList<>();
                for (JsonNode indexNode : t.path("indexes")) {
                    indexes.add(new DesiredIndex(stringList(indexNode.path("columns")),
                            indexNode.path("unique").asBoolean(false)));
                }
                tables.put(tableKey, new DesiredTable(tableKey, columns, uniques, textOrNull(t, "renamedFromTable"),
                        foreignKeys, indexes));
            }
            return new DesiredSchema(tables);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse stored schema snapshot", exception);
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> stringList(JsonNode arrayNode) {
        List<String> out = new ArrayList<>();
        for (JsonNode value : arrayNode) {
            out.add(value.asText());
        }
        return out;
    }
}
