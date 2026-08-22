package com.npdev.runtime.support.crud.sqlnaming;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): thin wrappers over
 * {@link SqlIdentifierSupport}'s table/column-name derivation, plus the two hand-written
 * existence/current-status SQL statement builders that use them -- kept together since the runtime
 * side must query the exact same table/column names Flyway/the generator emit for a concept.
 */
public final class SqlNamingSupport {

    private SqlNamingSupport() {
    }

    public static String tableName(CompiledConcept entity) {
        return SqlIdentifierSupport.tableName(entity);
    }

    public static String columnName(CompiledField field) {
        return SqlIdentifierSupport.columnName(field);
    }

    public static String columnName(CompiledField field, String fallbackName) {
        String column = columnName(field);
        return column == null || column.isBlank()
                ? SqlIdentifierSupport.safeSqlIdentifier(fallbackName)
                : column;
    }

    public static String columnName(CompiledConcept entity, String fieldName) {
        if (entity != null && fieldName != null) {
            for (CompiledField field : entity.getFields()) {
                if (field != null && fieldName.equalsIgnoreCase(field.getName())) {
                    return columnName(field);
                }
            }
        }
        return SqlIdentifierSupport.safeSqlIdentifier(fieldName);
    }

    public static String existsByIdSql(CompiledConcept entity, CompiledField idField) {
        return "SELECT 1 FROM " + tableName(entity)
                + " WHERE CAST(" + columnName(idField) + " AS VARCHAR) = :id";
    }

    public static String fetchCurrentStatusSql(CompiledConcept entity, CompiledField idField, String statusColumn) {
        return "SELECT " + statusColumn + " FROM " + tableName(entity)
                + " WHERE CAST(" + columnName(idField) + " AS VARCHAR) = :id";
    }

    public static String truncateIdentifier(String value) {
        return SqlIdentifierSupport.safeSqlIdentifier(value);
    }
}
