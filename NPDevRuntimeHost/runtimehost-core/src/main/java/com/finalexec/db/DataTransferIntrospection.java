package com.finalexec.db;

import com.npdev.kernel.dbschema.NpdevInternalTables;
import com.npdev.kernel.storage.sql.SqlDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Live-schema reads for the data import/export tool, built entirely on {@link SqlDialect}'s
 * engine-agnostic introspection contract ({@code listTablesSql}/{@code listColumnsSql}). Unlike
 * {@link SchemaCompatibilityVerdict}/{@link ExternalSchemaVerification}, which compare the live
 * schema against THIS BUILD's compiled model manifest, everything here reads only what is actually in
 * the database -- export/import has no compiled model to compare against, since the source or target
 * need not even be an NPDev-generated database.
 */
final class DataTransferIntrospection {

    enum Scope { ALL, BUSINESS }

    private DataTransferIntrospection() {
    }

    static List<String> listTables(Connection connection, SqlDialect dialect) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(dialect.listTablesSql())) {
            statement.setNull(1, Types.VARCHAR);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tables.add(resultSet.getString("table_name"));
                }
            }
        }
        return tables;
    }

    static List<DataTransferManifest.ColumnMeta> listColumns(Connection connection, SqlDialect dialect, String table)
            throws SQLException {
        List<DataTransferManifest.ColumnMeta> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(dialect.listColumnsSql())) {
            statement.setNull(1, Types.VARCHAR);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(new DataTransferManifest.ColumnMeta(
                            resultSet.getString("column_name"),
                            resultSet.getString("data_type"),
                            "YES".equalsIgnoreCase(resultSet.getString("is_nullable")),
                            resultSet.getString("column_default")));
                }
            }
        }
        return columns;
    }

    /**
     * Every {@code npdev_*} kernel/system table name, lower-cased -- the single source of truth
     * {@code NpdevInternalTablesSourceOfTruthTest} already enforces stays complete. "Business tables"
     * is defined as everything NOT in this set, not a second, drifting classification scheme.
     */
    static Set<String> internalTableNames() {
        Set<String> names = new HashSet<>();
        for (var definition : NpdevInternalTables.all()) {
            names.add(definition.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    /** {@code explicitTables}, if non-empty, always wins over {@code scope} -- an explicit table list
     *  is a deliberate override, not a further filter on top of the scope. */
    static List<String> resolveScope(Connection connection, SqlDialect dialect, Scope scope, List<String> explicitTables)
            throws SQLException {
        if (explicitTables != null && !explicitTables.isEmpty()) {
            return List.copyOf(explicitTables);
        }
        List<String> all = listTables(connection, dialect);
        if (scope == Scope.ALL) {
            return all;
        }
        Set<String> internal = internalTableNames();
        List<String> business = new ArrayList<>();
        for (String table : all) {
            if (!internal.contains(table.toLowerCase(Locale.ROOT))) {
                business.add(table);
            }
        }
        return business;
    }
}
