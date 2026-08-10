package com.npdev.generator.dbconfig;

import java.nio.file.Path;
import java.util.List;

/**
 * @param externallyProvisioned STOR-14. The SERVER this app connects to is the user's, not NPDev's.
 *        Every {@code _ops} operation that would start, stop or destroy it refuses and returns.
 *        Orthogonal to {@link SchemaLifecyclePolicy#ownership()}, which governs whether NPDev issues
 *        DDL against the SCHEMA.
 * @param resolvedDataRoot PORT-1. <b>App-relative, POSIX-separated, and never absolute</b> (e.g.
 *        {@code data} or {@code data/npdev_x_20260810_ab12}) -- resolved against the FinalApp
 *        directory by both consumers: the app itself (its working directory IS the FinalApp
 *        directory) and the {@code _ops} scripts ({@code $PSScriptRoot/..}, since {@code _ops} lives
 *        inside the app). It used to be this machine's absolute path, which made a generated app
 *        open its database on a drive the recipient may not have. ONE anchor for both halves is the
 *        load-bearing part -- see QUAL-3 for what two front doors onto one database cost.
 */
public record GeneratedDatabasePlan(
        String appId,
        DatabaseEngine engine,
        String storageMode,
        boolean physicalDatabase,
        boolean externallyProvisioned,
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
