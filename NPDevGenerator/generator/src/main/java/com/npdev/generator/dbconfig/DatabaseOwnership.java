package com.npdev.generator.dbconfig;

import java.util.Locale;

/**
 * REG-7.1: whether NPDev owns this app's database schema (issues DDL, migrates it) or the schema is
 * externally managed (pre-existing legacy system, or an operator running the DDL by hand). Orthogonal
 * to {@link SchemaLifecycleStrategy} -- see {@code docs/REG7_REG8_EXTERNAL_DB_AND_MIGRATION_MARKING_PLAN.md}
 * D1: {@code strategy} answers HOW NPDev migrates when it owns the schema; {@code ownership} answers
 * WHETHER it touches schema DDL at all.
 */
public enum DatabaseOwnership {
    NPDEV_MANAGED("NpdevManaged"),
    EXTERNALLY_MANAGED("ExternallyManaged");

    private final String externalName;

    DatabaseOwnership(String externalName) {
        this.externalName = externalName;
    }

    public String externalName() {
        return externalName;
    }

    /** Absent/blank defaults to {@link #NPDEV_MANAGED} -- today's only behavior, so every db
     * definition written before this field existed is unaffected. */
    public static DatabaseOwnership parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return NPDEV_MANAGED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (DatabaseOwnership ownership : values()) {
            if (ownership.externalName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return ownership;
            }
        }
        throw new IllegalArgumentException("Unsupported schemaLifecycle.ownership: " + value);
    }
}
