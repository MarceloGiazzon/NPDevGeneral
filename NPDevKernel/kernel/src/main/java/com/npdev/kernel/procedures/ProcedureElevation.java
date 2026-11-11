package com.npdev.kernel.procedures;

/**
 * Who a {@code runAs: "system"} procedure is acting for. While an elevated procedure runs, its steps use the
 * per-tenant system principal ({@code $user.isSystem == true}); this holder keeps the ORIGINAL caller's actor id so
 * the audit trail and capability adapters can still say "on behalf of". Thread-bound and restored by the executor
 * (try/finally), never visible to the model.
 */
public final class ProcedureElevation {
    private static final ThreadLocal<String> ON_BEHALF_OF = new ThreadLocal<>();

    private ProcedureElevation() {
    }

    /** The original caller's actor id while an elevated procedure runs on this thread, else null. */
    public static String onBehalfOf() {
        return ON_BEHALF_OF.get();
    }

    static String swap(String actorId) {
        String previous = ON_BEHALF_OF.get();
        if (actorId == null) {
            ON_BEHALF_OF.remove();
        } else {
            ON_BEHALF_OF.set(actorId);
        }
        return previous;
    }
}
