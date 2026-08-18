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
    /**
     * STOR-16. The confirmation an {@link SchemaLifecycleStrategy#EPHEMERAL} app must carry on a
     * PHYSICAL engine. Deliberately not reusing {@link #TABLE_DATA_CONFIRMATION}: that token says
     * "this particular change deletes data", and an author who typed it once for a column drop has
     * not thereby agreed that every future boot starts from empty. The sentence has to be the one
     * being agreed to.
     */
    public static final String EPHEMERAL_CONFIRMATION = "I_UNDERSTAND_ALL_DATA_IS_DELETED_ON_EVERY_START";

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

    /**
     * STOR-16: is this definition's {@code Ephemeral} posture properly declared for {@code engine}?
     *
     * <p>On a physical engine it takes {@link #EPHEMERAL_CONFIRMATION} and NPDev-owned TABLE scope
     * -- the scope requirement is what keeps an app that shares a database from taking the
     * neighbour's tables with it.
     *
     * <p>On {@code InMemory} the pre-existing {@link #IN_MEMORY_CONFIRMATION} + logical-store scope
     * is also accepted. That is not leniency for its own sake: all 7 corpus definitions that used
     * the retired {@code RecreateOnAppStart} spelling are InMemory and already carry exactly that
     * pair, and for InMemory "drop and recreate on boot" and "memory is empty on boot" are the same
     * statement. Accepting it means those definitions migrate by renaming one string, which is the
     * whole reason re-pointing the old name was judged safe.
     */
    public boolean ephemeralConfirmedFor(DatabaseEngine engine) {
        if (!allowDestructiveRecreate) {
            return false;
        }
        if (EPHEMERAL_CONFIRMATION.equals(destructiveRecreateConfirmation)) {
            return engine == DatabaseEngine.IN_MEMORY
                    ? NPDEV_STORE_SCOPE.equals(scope) || NPDEV_TABLE_SCOPE.equals(scope)
                    : NPDEV_TABLE_SCOPE.equals(scope);
        }
        return engine == DatabaseEngine.IN_MEMORY
                && IN_MEMORY_CONFIRMATION.equals(destructiveRecreateConfirmation)
                && NPDEV_STORE_SCOPE.equals(scope);
    }

    /** REG-7.1: true when NPDev must never issue schema DDL against this database. */
    public boolean externallyManaged() {
        return ownership == DatabaseOwnership.EXTERNALLY_MANAGED;
    }
}
