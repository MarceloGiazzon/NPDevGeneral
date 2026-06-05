package com.npdev.kernel.dbschema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class NpdevSchemaValidator {
    public List<String> validateInternalTables(DataSource dataSource) {
        if (dataSource == null) {
            return List.of("DataSource is required for physical schema validation.");
        }
        List<String> errors = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (InternalTableDefinition table : NpdevInternalTables.all()) {
                if (!tableExists(metaData, table.name())) {
                    errors.add("Missing internal table: " + table.name());
                    continue;
                }
                for (InternalColumnDefinition column : table.columns()) {
                    if (!columnExists(metaData, table.name(), column.name())) {
                        errors.add("Missing internal column: " + table.name() + "." + column.name());
                    }
                }
            }
        } catch (Exception exception) {
            errors.add("Schema validation failed: " + exception.getMessage());
        }
        return List.copyOf(errors);
    }

    private static boolean tableExists(DatabaseMetaData metaData, String table) throws Exception {
        return exists(metaData.getTables(null, null, table, null))
                || exists(metaData.getTables(null, null, table.toLowerCase(Locale.ROOT), null))
                || exists(metaData.getTables(null, null, table.toUpperCase(Locale.ROOT), null));
    }

    private static boolean columnExists(DatabaseMetaData metaData, String table, String column) throws Exception {
        return exists(metaData.getColumns(null, null, table, column))
                || exists(metaData.getColumns(null, null, table.toLowerCase(Locale.ROOT), column.toLowerCase(Locale.ROOT)))
                || exists(metaData.getColumns(null, null, table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT)));
    }

    private static boolean exists(ResultSet resultSet) throws Exception {
        try (ResultSet rs = resultSet) {
            return rs.next();
        }
    }
}
