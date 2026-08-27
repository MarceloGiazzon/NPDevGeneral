package com.finalexec.db.datamobility;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.CurrentTable;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DB -&gt; file: streams every row of every table in scope into either CSV (one {@code .csv} per
 * table, plus a header row) or a self-consistent SQL-insert dump (one {@code .sql} per table),
 * always alongside a {@code manifest.json} -- a direct Jackson serialization of the scoped {@link
 * CurrentSchema}, since {@code CurrentSchema}/{@code CurrentTable}/{@code CurrentColumn} are plain
 * records with no behaviour to hide. That manifest is what a later {@link DataImporter} deserializes
 * back into a {@code CurrentSchema} to hand to {@link DataMobilityStructureCheck} as the source side
 * -- the export bundle always carries enough schema information to run the structure check on
 * import, independent of which row format was chosen.
 *
 * <p>Rows are read with a bounded JDBC fetch size so exporting a large table does not materialize
 * it in memory. {@code CREATE TABLE}/{@code CREATE INDEX} DDL is deliberately NOT written here even
 * when the caller intends to use it later -- {@link DataImporter} generates that DDL, if needed, at
 * import time against the actual target's dialect (the export's eventual target isn't known at
 * export time).
 */
public final class DataExporter {

    private static final int FETCH_SIZE = 500;

    public enum Format { CSV, SQL }

    private DataExporter() {
    }

    public record ExportResult(CurrentSchema schema, Map<String, Long> rowCountsByTable) {
    }

    /**
     * @param systemSchemas  passed straight to {@code CurrentSchemaReader.read} -- the source
     *                       engine's system-schema exclusion set (see that method's javadoc)
     * @param sourceEngineKey a key {@link SqlDialects#forName} accepts, used ONLY to quote
     *                       table/column identifiers in the generated {@code SELECT} (a user will
     *                       eventually name a field {@code order} or {@code group} -- see {@code
     *                       SqlDialect.quoteIdentifier}'s own javadoc)
     * @param tableScope     lower-cased table names to export; {@code null} or empty means every
     *                       table the source database has
     */
    public static ExportResult export(
            DataSource dataSource,
            Set<String> systemSchemas,
            String sourceEngineKey,
            Set<String> tableScope,
            Format format,
            Path outputDir
    ) throws SQLException, IOException {
        CurrentSchema fullSchema = new CurrentSchemaReader().read(dataSource, systemSchemas);
        CurrentSchema scoped = scope(fullSchema, tableScope);
        SqlDialect dialect = SqlDialects.forName(sourceEngineKey);

        Files.createDirectories(outputDir);
        writeManifest(scoped, outputDir);

        Map<String, Long> rowCounts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            for (Map.Entry<String, CurrentTable> entry : scoped.tables().entrySet()) {
                long count = format == Format.CSV
                        ? exportTableAsCsv(connection, entry.getValue(), outputDir, dialect)
                        : exportTableAsSql(connection, entry.getValue(), outputDir, dialect);
                rowCounts.put(entry.getKey(), count);
            }
        }
        return new ExportResult(scoped, rowCounts);
    }

    /** Filters a {@link CurrentSchema} down to {@code tableScope} (lower-cased table names); {@code
     *  null}/empty means "every table" -- also used by {@code DataTransferMain}'s standalone
     *  {@code structure-check} verb to scope a source schema before diffing. */
    public static CurrentSchema scope(CurrentSchema schema, Set<String> tableScope) {
        if (tableScope == null || tableScope.isEmpty()) {
            return schema;
        }
        Set<String> lower = new java.util.HashSet<>();
        for (String t : tableScope) {
            lower.add(t.toLowerCase(Locale.ROOT));
        }
        Map<String, CurrentTable> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, CurrentTable> entry : schema.tables().entrySet()) {
            if (lower.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return new CurrentSchema(Map.copyOf(filtered));
    }

    private static void writeManifest(CurrentSchema schema, Path outputDir) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        Files.writeString(outputDir.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static long exportTableAsCsv(Connection connection, CurrentTable table, Path outputDir, SqlDialect dialect) throws SQLException, IOException {
        List<String> columnNames = new ArrayList<>(table.columns().keySet());
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(selectAllSql(table, columnNames, dialect));
             java.io.Writer rawWriter = Files.newBufferedWriter(outputDir.resolve(table.name() + ".csv"), StandardCharsets.UTF_8)) {
            statement.setFetchSize(FETCH_SIZE);
            CsvRowFormat.writeHeader(rawWriter, columnNames);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    List<String> row = new ArrayList<>(columnNames.size());
                    for (int i = 1; i <= columnNames.size(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value == null ? null : value.toString());
                    }
                    CsvRowFormat.writeRow(rawWriter, row);
                    count++;
                }
            }
        }
        return count;
    }

    private static long exportTableAsSql(Connection connection, CurrentTable table, Path outputDir, SqlDialect dialect) throws SQLException, IOException {
        List<String> columnNames = new ArrayList<>(table.columns().keySet());
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(selectAllSql(table, columnNames, dialect));
             BufferedWriter writer = Files.newBufferedWriter(outputDir.resolve(table.name() + ".sql"), StandardCharsets.UTF_8)) {
            statement.setFetchSize(FETCH_SIZE);
            try (ResultSet rs = statement.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                while (rs.next()) {
                    List<Object> values = new ArrayList<>(columnNames.size());
                    for (int i = 1; i <= columnNames.size(); i++) {
                        values.add(jdbcBindableValue(rs, i, meta));
                    }
                    writer.write(SqlInsertRowFormat.insertStatement(table.name(), columnNames, values));
                    writer.newLine();
                    count++;
                }
            }
        }
        return count;
    }

    /** Normalizes a JDBC-returned value to one of the plain types {@code SqlInsertRowFormat}
     *  knows how to render as a portable literal (String/Number/Boolean/null). */
    private static Object jdbcBindableValue(ResultSet rs, int index, ResultSetMetaData meta) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        // Dates/timestamps/UUID/binary-as-base64/etc: render as their portable string form; the
        // importer re-hydrates using the column's portable category from the manifest, never by
        // guessing from this string's shape alone.
        return value.toString();
    }

    private static String selectAllSql(CurrentTable table, List<String> columnNames, SqlDialect dialect) {
        List<String> quoted = new ArrayList<>(columnNames.size());
        for (String columnName : columnNames) {
            quoted.add(dialect.quoteIdentifier(columnName));
        }
        return "SELECT " + String.join(", ", quoted) + " FROM " + dialect.quoteIdentifier(table.name());
    }
}
