package com.npdev.kernel;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * REG-338: the flow's authenticated actor as a snapshot in the flow state, so a createConcept /
 * updateConcept step can be checked against the concept's row-level write rule as the person the flow
 * runs for -- including a step that runs after an {@code awaitEvent}, where the engine re-enters under
 * {@link ExecutionContext#resuming} (a trusted role that carries none of the original roles).
 *
 * <p>The snapshot is written by the kernel from the authenticated {@link ExecutionContext} when the
 * flow starts and is never read from a caller payload ({@code actorId} is stamped the same way, see
 * REG-337). A platform principal (the cron scheduler, model seeding) is recorded as such and is not
 * row-checked: those rules scope what a CALLER may write, not what the platform may.
 */
final class FlowActor {

    static final String ROLES_KEY = "actorRoles";
    static final String SYSTEM_KEY = "actorSystem";

    private FlowActor() {
    }

    /** Records who the flow runs for; called once, with the starting context. */
    static void stamp(Map<String, Object> state, ExecutionContext context) {
        state.put(ROLES_KEY, List.copyOf(new TreeSet<>(context.roles())));
        if (ExecutionContext.isSystem(context)) {
            state.put(SYSTEM_KEY, Boolean.TRUE);
        }
    }

    /**
     * The context a row-level write rule is evaluated under for this step, or {@code null} when the
     * check does not apply: a platform principal, or a flow instance that predates the snapshot (no
     * roles recorded, so there is nothing trustworthy to evaluate).
     */
    static ExecutionContext writeCheckContext(Map<String, Object> state, ExecutionContext effective) {
        if (state == null || effective == null) {
            return null;
        }
        if (ExecutionContext.isSystem(effective) || Boolean.TRUE.equals(state.get(SYSTEM_KEY))) {
            return null;
        }
        if (!(state.get(ROLES_KEY) instanceof Collection<?> recorded)) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>();
        for (Object role : recorded) {
            if (role != null) {
                roles.add(String.valueOf(role));
            }
        }
        Object actor = state.get("actorId");
        String actorId = actor == null ? effective.actorId() : String.valueOf(actor);
        return new ExecutionContext(effective.tenantId(), actorId, effective.tags(), roles);
    }
}
