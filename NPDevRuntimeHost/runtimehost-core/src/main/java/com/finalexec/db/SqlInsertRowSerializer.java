package com.finalexec.db;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The human-readable dump/restore format: one {@code INSERT INTO ... VALUES (...);} statement per
 * row, one file per table -- a literal SQL script rather than typed data, so importing it needs no
 * structure check ({@link ImportMain} skips one entirely for this format) and no {@code manifest.json}
 * either: the target engine's own parser is the only thing that decides whether a statement is valid.
 *
 * <p>Import runs each statement with autocommit ON, so any rows already inserted before a failing
 * statement stay committed rather than being rolled back with them; the failure itself aborts the
 * remaining statements in that table's file and is reported to the caller with the exact statement
 * number, never silently swallowed.
 */
final class SqlInsertRowSerializer implements RowSerializer {

    @Override
    public String format() {
        return "sql-insert";
    }

    @Override
    public void exportTable(Connection connection, String table, List<DataTransferManifest.ColumnMeta> columns, Path outFile)
            throws SQLException, IOException {
        String columnList = columns.stream()
                .map(column -> SchemaLifecycleExecutor.safeIdentifier(column.name()))
                .collect(Collectors.joining(", "));
        String quotedTable = SchemaLifecycleExecutor.quotedIdentifier(table);
        String sql = "SELECT " + columnList + " FROM " + quotedTable;
        if (outFile.getParent() != null) {
            Files.createDirectories(outFile.getParent());
        }
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery();
                BufferedWriter writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8)) {
            while (resultSet.next()) {
                StringBuilder values = new StringBuilder();
                for (int index = 0; index < columns.size(); index++) {
                    if (index > 0) {
                        values.append(", ");
                    }
                    values.append(sqlLiteral(resultSet.getObject(index + 1)));
                }
                writer.write("INSERT INTO " + quotedTable + " (" + columnList + ") VALUES (" + values + ");");
                writer.write("\n");
            }
        }
    }

    @Override
    public long importTable(Connection connection, String table, List<DataTransferManifest.ColumnMeta> sourceColumns, Path inFile)
            throws SQLException, IOException {
        long executed = 0;
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(true);
        try (BufferedReader reader = Files.newBufferedReader(inFile, StandardCharsets.UTF_8);
                Statement statement = connection.createStatement()) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    statement.execute(trimmed);
                    executed++;
                } catch (SQLException failure) {
                    throw new SQLException("Statement " + (executed + 1) + " in " + inFile.getFileName()
                            + " failed: " + failure.getMessage(), failure);
                }
            }
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
        return executed;
    }

    private static String sqlLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }
}
