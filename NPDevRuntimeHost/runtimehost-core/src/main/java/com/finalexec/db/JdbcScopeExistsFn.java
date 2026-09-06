package com.finalexec.db;

import com.npdev.kernel.concepts.ValueExpressionFunctions;
import com.npdev.kernel.storage.sql.SqlDialects;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * STOR-26 (B2 lift): backs {@code scope.exists(concept, fieldPath, value)} for the expression-
 * default backfill shadow proof, against the LIVE migration connection -- the same question
 * {@code CelInvariantEngine.ScopeChecker}/{@code InvariantScopeProvider} answer at request time via
 * a JPA {@code EntityManager} (see {@code GeneratedCrudRuntimeSupport#scopeExists}), answered here
 * instead with the raw JDBC connection a boot-time backfill already has -- no persistence context
 * exists yet at schema-migration time.
 *
 * <p>{@code concept} resolves to one of {@code manifest}'s own business tables by case-insensitive
 * name; {@code fieldPath} to one of that table's own columns the same way. No dotted paths --
 * mirrors {@code GeneratedCrudRuntimeSupport#scopeExists}'s own restriction to a single field.
 */
final class JdbcScopeExistsFn implements ValueExpressionFunctions.ScopeExistsFn {

    private final Connection connection;
    private final SchemaLifecycleExecutor.SchemaManifest manifest;

    JdbcScopeExistsFn(Connection connection, SchemaLifecycleExecutor.SchemaManifest manifest) {
        this.connection = connection;
        this.manifest = manifest;
    }

    @Override
    public boolean exists(String concept, String fieldPath, Object value) {
        if (connection == null || manifest == null || concept == null || concept.isBlank()
                || fieldPath == null || fieldPath.isBlank() || value == null
                || fieldPath.contains(".") || fieldPath.contains("[")) {
            return false;
        }
        String table = resolveTable(concept);
        if (table == null) {
            return false;
        }
        String column = resolveColumn(table, fieldPath);
        if (column == null) {
            return false;
        }
        String sql = SqlDialects.active().rowExistsSql(
                SchemaLifecycleExecutor.quotedIdentifier(table), SchemaLifecycleExecutor.quotedIdentifier(column));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed evaluating scope.exists(" + concept + ", " + fieldPath
                    + ", ...) during expression-default backfill", exception);
        }
    }

    private String resolveTable(String concept) {
        for (String table : manifest.businessTableColumns().keySet()) {
            if (table.equalsIgnoreCase(concept)) {
                return table;
            }
        }
        return null;
    }

    private String resolveColumn(String table, String fieldPath) {
        for (String column : manifest.businessTableColumns().getOrDefault(table, List.of())) {
            if (column.equalsIgnoreCase(fieldPath)) {
                return column;
            }
        }
        return null;
    }
}
