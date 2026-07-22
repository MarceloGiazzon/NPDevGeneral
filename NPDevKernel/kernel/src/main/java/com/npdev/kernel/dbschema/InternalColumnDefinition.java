package com.npdev.kernel.dbschema;

public record InternalColumnDefinition(
        String name,
        InternalColumnType type,
        boolean required,
        String defaultExpression
) {
    public InternalColumnDefinition {
        name = require(name, "name");
        if (type == null) {
            throw new IllegalArgumentException("type must be non-null");
        }
        defaultExpression = defaultExpression == null ? "" : defaultExpression.trim();
    }

    public static InternalColumnDefinition required(String name, InternalColumnType type) {
        return new InternalColumnDefinition(name, type, true, "");
    }

    public static InternalColumnDefinition required(String name, String sqlType) {
        return required(name, InternalColumnType.fromLegacySqlType(sqlType));
    }

    public static InternalColumnDefinition optional(String name, InternalColumnType type) {
        return new InternalColumnDefinition(name, type, false, "");
    }

    public static InternalColumnDefinition optional(String name, String sqlType) {
        return optional(name, InternalColumnType.fromLegacySqlType(sqlType));
    }

    public static InternalColumnDefinition defaulted(String name, InternalColumnType type, String defaultExpression) {
        require(defaultExpression, "defaultExpression");
        return new InternalColumnDefinition(name, type, true, defaultExpression);
    }

    public static InternalColumnDefinition defaulted(String name, String sqlType, String defaultExpression) {
        return defaulted(name, InternalColumnType.fromLegacySqlType(sqlType), defaultExpression);
    }

    public String sqlType() {
        return type.neutralSqlType();
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must be non-blank");
        }
        return value.trim();
    }
}
