package com.finalexec.db.datamobility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.CurrentTable;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * File -&gt; DB (and the second half of DB -&gt; DB, once a caller has the source's {@link
 * CurrentSchema} in hand): runs {@link DataMobilityStructureCheck} first, then -- only on
 * {@code EQUAL} or an explicitly confirmed {@code COMPATIBLE} -- creates whatever DDL the target is
 * genuinely missing (when {@code includeDdl}) and streams rows in from the export bundle written by
 * {@link DataExporter}.
 *
 * <p>Deliberately does NOT ask {@link DataMobilityStructureCheck} which tables/columns are missing
 * -- that class returns prose reasons for a human/API response, not a structured list a caller
 * should parse. Whatever DDL is needed is instead derived directly from a plain set difference
 * between the two {@link CurrentSchema} objects this class already holds, which is simpler and
 * cannot drift from what the structure check itself saw (same two schema snapshots, same call).
 */
public final class DataImporter {

    private static final int BATCH_SIZE = 500;

    private DataImporter() {
    }

    public enum Outcome { IMPORTED, NEEDS_CONFIRMATION, BLOCKED }

    public record ImportResult(Outcome outcome, StructureCheckResult structureCheck, Map<String, Long> rowCountsByTable) {
    }

    /**
     * @param confirmed the caller has already shown the user a {@code COMPATIBLE} verdict's reasons
     *                  and gotten explicit go-ahead; ignored when the verdict is {@code EQUAL}
     *                  (nothing to confirm) or {@code INCOMPATIBLE} (never proceeds regardless)
     */
    public static ImportResult importFrom(
            Path bundleDir,
            DataExporter.Format format,
            DataSource targetDataSource,
            java.util.Set<String> targetSystemSchemas,
            String targetEngineKey,
            boolean includeDdl,
            boolean confirmed
    ) throws IOException, SQLException {
        CurrentSchema source = readManifest(bundleDir);
        CurrentSchema target = new CurrentSchemaReader().read(targetDataSource, targetSystemSchemas);

        StructureCheckResult check = DataMobilityStructureCheck.check(source, target, targetEngineKey, includeDdl);
        if (check.verdict() == StructureVerdict.INCOMPATIBLE) {
            return new ImportResult(Outcome.BLOCKED, check, Map.of());
        }
        if (check.verdict() == StructureVerdict.COMPATIBLE && !confirmed) {
            return new ImportResult(Outcome.NEEDS_CONFIRMATION, check, Map.of());
        }

        if (includeDdl) {
            applyMissingDdl(targetDataSource, targetEngineKey, source, target);
        }

        SqlDialect dialect = SqlDialects.forName(targetEngineKey);
        Map<String, Long> rowCounts = new java.util.LinkedHashMap<>();
        try (Connection connection = targetDataSource.getConnection()) {
            connection.setAutoCommit(false);
            for (Map.Entry<String, CurrentTable> entry : source.tables().entrySet()) {
                long count = format == DataExporter.Format.CSV
                        ? importTableFromCsv(connection, entry.getValue(), bundleDir, dialect)
                        : importTableFromSql(connection, entry.getValue(), bundleDir, dialect);
                rowCounts.put(entry.getKey(), count);
            }
            connection.commit();
        }
        return new ImportResult(Outcome.IMPORTED, check, rowCounts);
    }

    private static CurrentSchema readManifest(Path bundleDir) throws IOException {
        Path manifestPath = bundleDir.resolve("manifest.json");
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(Files.readString(manifestPath, StandardCharsets.UTF_8), CurrentSchema.class);
    }

    // ------------------------------------------------------------------ DDL for genuinely missing structure

    /** Package-visible so {@link DataTransfer} (the direct DB-to-DB path) can reuse it without a
     *  file bundle in between. */
    static void applyMissingDdl(DataSource targetDataSource, String targetEngineKey, CurrentSchema source, CurrentSchema target) throws SQLException {
        SqlDialect dialect = SqlDialects.forName(targetEngineKey);
        try (Connection connection = targetDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (Map.Entry<String, CurrentTable> entry : source.tables().entrySet()) {
                CurrentTable sourceTable = entry.getValue();
                CurrentTable targetTable = target.tables().get(entry.getKey());
                if (targetTable == null) {
                    statement.execute(dialect.guardedCreateTable(sourceTable.name(), createTableSql(dialect, sourceTable)));
                } else {
                    for (Map.Entry<String, CurrentColumn> colEntry : sourceTable.columns().entrySet()) {
                        if (!targetTable.columns().containsKey(colEntry.getKey())) {
                            statement.execute(dialect.guardedAddColumn(sourceTable.name(), colEntry.getKey(),
                                    addColumnSql(dialect, sourceTable.name(), colEntry.getValue())));
                        }
                    }
                }
            }
        }
    }

    private static String createTableSql(SqlDialect dialect, CurrentTable table) {
        List<String> columnDefs = new ArrayList<>();
        for (CurrentColumn column : table.columns().values()) {
            columnDefs.add(dialect.quoteIdentifier(column.name()) + " " + dialect.portableColumnType(column.normalizedSqlType())
                    + (column.nullable() ? "" : " NOT NULL"));
        }
        List<String> quotedPkColumns = new ArrayList<>();
        for (String pkColumn : table.primaryKeyColumns()) {
            quotedPkColumns.add(dialect.quoteIdentifier(pkColumn));
        }
        String pk = quotedPkColumns.isEmpty() ? "" : ", PRIMARY KEY (" + String.join(", ", quotedPkColumns) + ")";
        return "CREATE TABLE " + dialect.quoteIdentifier(table.name()) + " (" + String.join(", ", columnDefs) + pk + ")";
    }

    private static String addColumnSql(SqlDialect dialect, String tableName, CurrentColumn column) {
        return "ALTER TABLE " + dialect.quoteIdentifier(tableName) + " ADD COLUMN "
                + dialect.quoteIdentifier(column.name()) + " " + dialect.portableColumnType(column.normalizedSqlType());
    }

    // ------------------------------------------------------------------ row import

    private static long importTableFromCsv(Connection connection, CurrentTable table, Path bundleDir, SqlDialect dialect) throws IOException, SQLException {
        Path csvPath = bundleDir.resolve(table.name() + ".csv");
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            List<String> header = CsvRowFormat.readRow(reader);
            if (header == null) {
                return 0;
            }
            try (PreparedStatement statement = connection.prepareStatement(insertSql(table.name(), header, dialect))) {
                int batched = 0;
                List<String> row;
                while ((row = CsvRowFormat.readRow(reader)) != null) {
                    bindRow(statement, header, row, table);
                    statement.addBatch();
                    count++;
                    if (++batched >= BATCH_SIZE) {
                        statement.executeBatch();
                        batched = 0;
                    }
                }
                if (batched > 0) {
                    statement.executeBatch();
                }
            }
        }
        return count;
    }

    private static void bindRow(PreparedStatement statement, List<String> columnNames, List<String> rawValues, CurrentTable table) throws SQLException {
        for (int i = 0; i < columnNames.size(); i++) {
            String raw = rawValues.get(i);
            if (raw == null) {
                statement.setNull(i + 1, Types.NULL);
                continue;
            }
            CurrentColumn column = table.columns().get(columnNames.get(i));
            String portableCategory = column == null ? null : portableCategoryOf(column.normalizedSqlType());
            // CSV carries no type information at all (every value round-trips as a plain string) --
            // convert using the SAME category logic the SQL-insert import path uses, so a boolean/
            // numeric column gets a real typed Java object bound, not a bare string a stricter
            // driver (SQL Server, MySQL in strict mode) may refuse for that column type.
            statement.setObject(i + 1, csvValueOf(raw, portableCategory));
        }
    }

    private static Object csvValueOf(String raw, String portableCategory) {
        if ("BOOLEAN".equals(portableCategory)) {
            return "true".equalsIgnoreCase(raw) || "TRUE".equals(raw) || "1".equals(raw);
        }
        if ("DECIMAL".equals(portableCategory)) {
            return new java.math.BigDecimal(raw);
        }
        if ("LONG".equals(portableCategory)) {
            return Long.parseLong(raw);
        }
        if ("INT".equals(portableCategory)) {
            return Integer.parseInt(raw);
        }
        return raw;
    }

    private static long importTableFromSql(Connection connection, CurrentTable table, Path bundleDir, SqlDialect dialect) throws IOException, SQLException {
        Path sqlPath = bundleDir.resolve(table.name() + ".sql");
        long count = 0;
        try (BufferedReader reader = Files.newBufferedReader(sqlPath, StandardCharsets.UTF_8)) {
            SqlInsertRowFormat.ParsedInsert first = SqlInsertRowFormat.readStatement(reader);
            if (first == null) {
                return 0;
            }
            try (PreparedStatement statement = connection.prepareStatement(insertSql(table.name(), first.columnNames(), dialect))) {
                SqlInsertRowFormat.ParsedInsert parsed = first;
                int batched = 0;
                while (parsed != null) {
                    bindParsedRow(statement, parsed, table);
                    statement.addBatch();
                    count++;
                    if (++batched >= BATCH_SIZE) {
                        statement.executeBatch();
                        batched = 0;
                    }
                    parsed = SqlInsertRowFormat.readStatement(reader);
                }
                if (batched > 0) {
                    statement.executeBatch();
                }
            }
        }
        return count;
    }

    private static void bindParsedRow(PreparedStatement statement, SqlInsertRowFormat.ParsedInsert parsed, CurrentTable table) throws SQLException {
        for (int i = 0; i < parsed.columnNames().size(); i++) {
            String columnName = parsed.columnNames().get(i).toLowerCase(Locale.ROOT);
            CurrentColumn column = table.columns().get(columnName);
            String portableCategory = column == null ? null : portableCategoryOf(column.normalizedSqlType());
            Object value = SqlInsertRowFormat.valueOf(parsed.rawLiterals().get(i), portableCategory);
            if (value == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    /** A coarse category good enough for {@link SqlInsertRowFormat#valueOf} to re-hydrate a raw
     *  literal correctly -- not the full portable-type system, just numeric/boolean/other. */
    private static String portableCategoryOf(String normalizedSqlType) {
        String upper = normalizedSqlType == null ? "" : normalizedSqlType.toUpperCase(Locale.ROOT);
        if (upper.contains("BOOL") || upper.equals("BIT")) {
            return "BOOLEAN";
        }
        if (upper.contains("DECIMAL") || upper.contains("NUMERIC") || upper.contains("FLOAT") || upper.contains("DOUBLE") || upper.contains("REAL")) {
            return "DECIMAL";
        }
        if (upper.contains("BIGINT")) {
            return "LONG";
        }
        if (upper.contains("INT")) {
            return "INT";
        }
        return "STRING";
    }

    private static String insertSql(String tableName, List<String> columnNames, SqlDialect dialect) {
        List<String> quotedColumns = new ArrayList<>(columnNames.size());
        List<String> placeholders = new ArrayList<>(columnNames.size());
        for (String columnName : columnNames) {
            quotedColumns.add(dialect.quoteIdentifier(columnName));
            placeholders.add("?");
        }
        return "INSERT INTO " + dialect.quoteIdentifier(tableName) + " (" + String.join(", ", quotedColumns)
                + ") VALUES (" + String.join(", ", placeholders) + ")";
    }
}
