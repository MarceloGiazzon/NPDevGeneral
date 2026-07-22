package com.npdev.generator.dbconfig;

public record SchemaLifecyclePolicy(
        SchemaLifecycleStrategy strategy,
        boolean allowDestructiveRecreate,
        String destructiveRecreateConfirmation,
        String scope,
        DatabaseOwnership ownership
) {
    public static final String TABLE_DATA_CONFIRMATION = "I_UNDERSTAND_TABLE_DATA_WILL_BE_DELETED";
    public static final String IN_MEMORY_CONFIRMATION = "I_UNDERSTAND_INMEMORY_DATA_IS_EPHEMERAL";
    public static final String NPDEV_TABLE_SCOPE = "NpdevOwnedTablesOnly";
    public static final String NPDEV_STORE_SCOPE = "NpdevOwnedLogicalStoresOnly";

    public SchemaLifecyclePolicy {
        if (strategy == null) {
            throw new IllegalArgumentException("schemaLifecycle.strategy is required");
        }
        destructiveRecreateConfirmation = destructiveRecreateConfirmation == null ? "" : destructiveRecreateConfirmation.trim();
        scope = scope == null ? "" : scope.trim();
        if (ownership == null) {
            ownership = DatabaseOwnership.NPDEV_MANAGED;
        }
    }

    /** Back-compat 4-arg constructor predating {@code ownership} (REG-7.1) -- defaults to
     * {@link DatabaseOwnership#NPDEV_MANAGED}, today's only behavior, so every pre-existing call site
     * keeps compiling unchanged. */
    public SchemaLifecyclePolicy(
            SchemaLifecycleStrategy strategy,
            boolean allowDestructiveRecreate,
            String destructiveRecreateConfirmation,
            String scope
    ) {
        this(strategy, allowDestructiveRecreate, destructiveRecreateConfirmation, scope, DatabaseOwnership.NPDEV_MANAGED);
    }

    public boolean destructiveConfirmedFor(DatabaseEngine engine) {
        if (!allowDestructiveRecreate) {
            return false;
        }
        if (engine == DatabaseEngine.IN_MEMORY) {
            return IN_MEMORY_CONFIRMATION.equals(destructiveRecreateConfirmation)
                    && NPDEV_STORE_SCOPE.equals(scope);
        }
        return TABLE_DATA_CONFIRMATION.equals(destructiveRecreateConfirmation)
                && NPDEV_TABLE_SCOPE.equals(scope);
    }

    /** REG-7.1: true when NPDev must never issue schema DDL against this database. */
    public boolean externallyManaged() {
        return ownership == DatabaseOwnership.EXTERNALLY_MANAGED;
    }
}
