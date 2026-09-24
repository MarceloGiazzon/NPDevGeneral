package com.finalexec.db;

import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A minimal, self-contained CSV reader/writer -- no new dependency for a format this narrow (one
 * file per table, always UTF-8, always comma-delimited, one record per line -- a quoted field may not
 * embed a newline, unlike full RFC4180). {@code null} and an empty string ARE distinguishable on
 * round-trip: a bare, unquoted empty field is {@code null}; an explicitly quoted empty field
 * ({@code ""}) is an empty string. Without this, a NOT NULL text column that legitimately holds ''
 * (e.g. Flyway's own {@code flyway_schema_history.script} baseline row) re-imports as SQL NULL and
 * fails that column's NOT NULL constraint -- reproduced live 2026-09-24 against a running app.
 *
 * <p>Every value round-trips through {@link Object#toString()} on export and a small
 * {@code dataType}-driven parse on import, rather than a blind {@link PreparedStatement#setObject}
 * with the raw string: H2 accepts a plain string for most column types, but the Postgres driver
 * rejects it outright for non-text columns ("column is of type integer but expression is of type
 * character varying"), which a cross-engine CSV import must not assume away.
 */
final class CsvRowSerializer implements RowSerializer {

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public void exportTable(Connection connection, String table, List<DataTransferManifest.ColumnMeta> columns, Path outFile)
            throws SQLException, IOException {
        SqlDialect dialect = SqlDialects.forConnection(connection);
        String columnList = columns.stream()
                .map(column -> SchemaLifecycleExecutor.safeIdentifier(column.name()))
                .collect(Collectors.joining(", "));
        String sql = "SELECT " + columnList + " FROM " + SchemaLifecycleExecutor.quotedIdentifier(table);
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery();
                BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            writer.write(columns.stream().map(column -> csvCell(column.name())).collect(Collectors.joining(",")));
            writer.write("\n");
            while (resultSet.next()) {
                List<String> cells = new ArrayList<>(columns.size());
                for (int index = 0; index < columns.size(); index++) {
                    DataTransferManifest.ColumnMeta column = columns.get(index);
                    Object value = dialect.isJsonColumnType(column.dataType())
                            ? resultSet.getString(index + 1)
                            : resultSet.getObject(index + 1);
                    cells.add(csvCell(value == null ? null : String.valueOf(value)));
                }
                writer.write(String.join(",", cells));
                writer.write("\n");
            }
        }
    }

    @Override
    public long importTable(Connection connection, String table, List<DataTransferManifest.ColumnMeta> sourceColumns, Path inFile)
            throws SQLException, IOException {
        SqlDialect targetDialect = SqlDialects.forConnection(connection);
        String columnList = sourceColumns.stream()
                .map(column -> SchemaLifecycleExecutor.safeIdentifier(column.name()))
                .collect(Collectors.joining(", "));
        String placeholders = sourceColumns.stream().map(column -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO " + SchemaLifecycleExecutor.quotedIdentifier(table)
                + " (" + columnList + ") VALUES (" + placeholders + ")";
        long inserted = 0;
        try (BufferedReader reader = Files.newBufferedReader(inFile, StandardCharsets.UTF_8);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return 0;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                List<String> cells = parseCsvLine(line);
                for (int index = 0; index < sourceColumns.size(); index++) {
                    String cell = index < cells.size() ? cells.get(index) : null;
                    bindCell(statement, index + 1, cell, sourceColumns.get(index).dataType(), targetDialect, connection);
                }
                statement.executeUpdate();
                inserted++;
            }
        }
        return inserted;
    }

    private void bindCell(PreparedStatement statement, int index, String cell, String dataType, SqlDialect targetDialect,
            Connection connection) throws SQLException {
        if (cell == null) {
            statement.setNull(index, Types.VARCHAR);
            return;
        }
        if (targetDialect.isJsonColumnType(dataType)) {
            bindJson(statement, index, cell, targetDialect, connection);
            return;
        }
        String type = dataType == null ? "" : dataType.toUpperCase(Locale.ROOT);
        if (type.equals("UUID")) {
            statement.setObject(index, UUID.fromString(cell));
        } else if (type.contains("INT")) {
            statement.setObject(index, Long.parseLong(cell));
        } else if (type.contains("NUMERIC") || type.contains("DECIMAL")) {
            statement.setObject(index, new BigDecimal(cell));
        } else if (type.contains("DOUBLE") || type.contains("FLOAT") || type.contains("REAL")) {
            statement.setObject(index, Double.parseDouble(cell));
        } else if (type.contains("BOOL")) {
            statement.setObject(index, "1".equals(cell) || Boolean.parseBoolean(cell));
        } else if (type.contains("TIMESTAMP")) {
            statement.setObject(index, Timestamp.valueOf(cell));
        } else if (type.contains("DATE")) {
            statement.setObject(index, java.sql.Date.valueOf(cell));
        } else {
            statement.setObject(index, cell);
        }
    }

    private void bindJson(PreparedStatement statement, int index, String cell, SqlDialect targetDialect, Connection connection)
            throws SQLException {
        if ("postgresql".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            org.postgresql.util.PGobject jsonValue = new org.postgresql.util.PGobject();
            jsonValue.setType(targetDialect.jsonColumnType());
            jsonValue.setValue(cell);
            statement.setObject(index, jsonValue);
        } else {
            statement.setObject(index, cell);
        }
    }

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        if (value.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /** A cell's value is {@code null} (SQL NULL) unless it was quoted -- {@code ""} is the only way
     *  to write an explicit empty string, so an unquoted empty field between commas is NULL. */
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
                quoted = true;
            } else if (c == ',') {
                cells.add(quoted || current.length() > 0 ? current.toString() : null);
                current.setLength(0);
                quoted = false;
            } else {
                current.append(c);
            }
        }
        cells.add(quoted || current.length() > 0 ? current.toString() : null);
        return cells;
    }
}
