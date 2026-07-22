package com.npdev.generator.dbconfig;

import java.nio.file.Path;
import java.util.List;

public record GeneratedDatabasePlan(
        String appId,
        DatabaseEngine engine,
        String storageMode,
        boolean physicalDatabase,
        String requestedDatabaseName,
        String resolvedDatabaseName,
        String databaseNameSource,
        String resolvedDataRoot,
        String databaseInstanceId,
        String containerName,
        String host,
        int hostPort,
        int containerPort,
        String jdbcUrl,
        String driverClassName,
        String username,
        String password,
        String dbeaverHost,
        int dbeaverPort,
        String dbeaverDatabase,
        String dbeaverUsername,
        boolean createInternalTables,
        boolean createBusinessTables,
        SchemaLifecyclePolicy schemaLifecycle,
        String schemaFingerprint,
        Path definitionPath,
        List<String> fingerprintInputs
) {
    public boolean jdbc() {
        return engine.jdbc();
    }
}
