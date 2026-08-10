package com.npdev.generator.dbconfig;

public record UserDatabaseDefinition(
        DatabaseEngine engine,
        String host,
        int port,
        String databaseName,
        String schemaName,
        String jdbcUrl,
        String h2FilePath,
        String username,
        String password,
        boolean createInternalTables,
        boolean createBusinessTables,
        // STOR-14: this SERVER is the user's, not NPDev's -- NPDev did not start it and must never
        // start, stop or destroy it. Deliberately NOT the same statement as
        // schemaLifecycle.ownership, which is about the SCHEMA (does NPDev issue DDL). All four
        // combinations are meaningful; see the STOR-14 ledger item for the decision and why.
        boolean externallyProvisioned,
        SchemaLifecyclePolicy schemaLifecycle
) {
    public UserDatabaseDefinition {
        if (engine == null) {
            throw new IllegalArgumentException("database.engine is required");
        }
        host = safe(host);
        databaseName = safe(databaseName);
        schemaName = safe(schemaName);
        jdbcUrl = safe(jdbcUrl);
        h2FilePath = safe(h2FilePath);
        username = safe(username);
        password = password == null ? "" : password;
        if (schemaLifecycle == null) {
            throw new IllegalArgumentException("schemaLifecycle is required");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
