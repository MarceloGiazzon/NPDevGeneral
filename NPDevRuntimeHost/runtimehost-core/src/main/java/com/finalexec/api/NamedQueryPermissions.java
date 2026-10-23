package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Checks a named query's declared {@code permissionRequirements[]} against the caller.
 *
 * <p>REG-301: the field was parsed, compiled and registered in the static permission manifest
 * (granted to the super-user) but never read when the query ran, so any caller holding plain
 * concept read access got the rows. Every requirement must now be held -- through the same
 * {@link PermissionEvaluator} the Concept Gateway and the kernel use, so a requirement may name a
 * declared permission ({@code report.finance}, super-user only) or a manifest one
 * ({@code update:order}, held by every role that may update orders). Shared by
 * {@code GET /api/queries/{name}} and a {@code kind: list} document that names a query, so the
 * two cannot drift.</p>
 */
public final class NamedQueryPermissions {

    private NamedQueryPermissions() {
    }

    /**
     * @return the denial message for the first requirement the caller does not hold, or empty when
     *         every requirement is held (callers map a denial to 403). Fails closed: a query that
     *         declares requirements is denied when no evaluator is wired.
     */
    public static Optional<String> denial(
            CompiledQuery query, ExecutionContext context, PermissionEvaluator evaluator) {
        if (query.permissionRequirements().isEmpty()) {
            return Optional.empty();
        }
        if (evaluator == null) {
            return Optional.of("Query '" + query.name() + "' declares permissionRequirements but no permission evaluator is configured.");
        }
        ExecutionContext caller = context == null ? ExecutionContext.anonymous() : context;
        PermissionSubject subject = new PermissionSubject(
                caller.actorId(), caller.tenantId(), new ArrayList<>(caller.roles()), List.of());
        for (String requirement : query.permissionRequirements()) {
            PermissionDecision decision = evaluator.evaluate(
                    subject, new PermissionRequirement(requirement, "query", query.name()));
            if (decision == null || !decision.allowed()) {
                return Optional.of("Query '" + query.name() + "' requires permission '" + requirement + "'.");
            }
        }
        return Optional.empty();
    }
}
