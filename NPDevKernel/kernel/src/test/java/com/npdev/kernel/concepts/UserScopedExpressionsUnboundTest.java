package com.npdev.kernel.concepts;

import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-285 (X0-16): a {@code $user.*} name this evaluator does not bind used to resolve to null and the
 * rule still answered -- {@code ownerId == $user.idd} granted every unowned row. Validation refuses it
 * now; a model that bypassed validation must be denied, never evaluated against null.
 */
class UserScopedExpressionsUnboundTest {

    @Test
    void unboundUserReferenceIsDeniedNotEvaluatedAsNull() {
        Map<String, Object> unownedRow = new HashMap<>();
        unownedRow.put("ownerId", null);

        assertFalse(UserScopedExpressions.evaluate("ownerId == $user.idd", unownedRow, ExecutionContext.anonymous()),
                "an unbound $user name compared against a null field must not grant");
        assertFalse(UserScopedExpressions.evaluate("$user.region == null", Map.of(), ExecutionContext.of("t1", "u1")),
                "an unbound $user name must not evaluate as null");
        assertFalse(UserScopedExpressions.evaluate("!$user.isSytem", Map.of(), ExecutionContext.of("t1", "u1")),
                "a typo'd $user name under negation must not grant");
    }

    @Test
    void boundUserReferencesStillEvaluate() {
        Map<String, Object> ownedRow = Map.of("ownerId", "u1", "siteId", "t1");
        ExecutionContext owner = ExecutionContext.of("t1", "u1");

        assertTrue(UserScopedExpressions.evaluate("ownerId == $user.id", ownedRow, owner));
        assertTrue(UserScopedExpressions.evaluate("ownerId == $user.actorId && siteId == $user.tenantId", ownedRow, owner));
        assertTrue(UserScopedExpressions.evaluate("!$user.isSystem", ownedRow, owner));
        assertFalse(UserScopedExpressions.evaluate("$user.roles.contains('Auditor')", ownedRow, owner));
    }
}
