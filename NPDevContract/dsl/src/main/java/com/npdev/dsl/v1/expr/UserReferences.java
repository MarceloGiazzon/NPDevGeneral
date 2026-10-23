package com.npdev.dsl.v1.expr;

import java.util.List;
import java.util.Set;

/**
 * REG-285: the {@code $user.*} names an access rule ({@code access.read}/{@code access.write}, concept or
 * field) or a {@code $user} lifecycle guard may use -- exactly the names the kernel's
 * {@code UserScopedExpressions} binds. Anything else used to validate clean and then evaluate against
 * {@code null} ({@code ownerId == $user.idd} showed every unowned row to everyone). Validation refuses an
 * unbound name and the runtime denies one, both against this one set.
 */
public final class UserReferences {

    public static final Set<String> BOUND = Set.of(
            "$user.id", "$user.actorId", "$user.tenantId", "$user.roles", "$user.isSystem");

    /** Sorted, for error messages. */
    public static final List<String> BOUND_SORTED = BOUND.stream().sorted().toList();

    private UserReferences() {
    }

    /** The first {@code $}-name {@code expression} references that is not in {@link #BOUND}, or null. */
    public static String firstUnbound(String expression) {
        for (String referenced : ComputedExpression.referencedFields(expression)) {
            if (referenced.startsWith("$") && !BOUND.contains(referenced)) {
                return referenced;
            }
        }
        return null;
    }
}
