package com.finalexec.npdev.migration;

public record StorageColumnSchema(
        String name,
        String sqlType,
        boolean required,
        boolean unique
) {
    public StorageColumnSchema normalized() {
        return new StorageColumnSchema(
                normalize(name),
                normalize(sqlType),
                required,
                unique
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
