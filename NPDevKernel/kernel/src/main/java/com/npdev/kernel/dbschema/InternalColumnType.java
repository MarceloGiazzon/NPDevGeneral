package com.npdev.kernel.dbschema;

import java.util.Locale;

public enum InternalColumnType {
    TEXT("TEXT"),
    LARGE_TEXT("TEXT"),
    JSON_DOCUMENT("TEXT"),
    TIMESTAMP("TIMESTAMP"),
    INTEGER("INTEGER"),
    BIGINT("BIGINT");

    private final String neutralSqlType;

    InternalColumnType(String neutralSqlType) {
        this.neutralSqlType = neutralSqlType;
    }

    public String neutralSqlType() {
        return neutralSqlType;
    }

    public static InternalColumnType fromLegacySqlType(String sqlType) {
        if (sqlType == null || sqlType.trim().isEmpty()) {
            throw new IllegalArgumentException("sqlType must be non-blank");
        }
        return switch (sqlType.trim().toUpperCase(Locale.ROOT)) {
            case "TEXT" -> TEXT;
            case "TIMESTAMP" -> TIMESTAMP;
            case "INTEGER" -> INTEGER;
            case "BIGINT" -> BIGINT;
            default -> throw new IllegalArgumentException("Unsupported internal column sqlType: " + sqlType);
        };
    }
}
