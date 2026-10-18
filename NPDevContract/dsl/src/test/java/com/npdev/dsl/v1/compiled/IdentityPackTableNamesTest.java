package com.npdev.dsl.v1.compiled;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * SESS-1: {@code identity::UserSession} is OPTIONAL in {@link IdentityPackTableNames} -- an app on an
 * older private identity pack has no sessions table, and that must read as "feature off" (null), never
 * as an error. The other four identity tables stay mandatory.
 */
class IdentityPackTableNamesTest {

    private static Map<String, CompiledConcept> identityConcepts(boolean withSessions) {
        Map<String, CompiledConcept> concepts = new LinkedHashMap<>();
        concepts.put("identity::User", concept("identity::User", "identity_users"));
        concepts.put("identity::Role", concept("identity::Role", "identity_roles"));
        concepts.put("identity::UserRole", concept("identity::UserRole", "identity_user_roles"));
        concepts.put("identity::UserRolePermission",
                concept("identity::UserRolePermission", "identity_user_role_permissions"));
        if (withSessions) {
            concepts.put("identity::UserSession", concept("identity::UserSession", "identity_user_sessions"));
        }
        return concepts;
    }

    private static CompiledConcept concept(String name, String tableName) {
        return new CompiledConcept(name, name.replace("::", "_"), tableName, List.of());
    }

    private static CompiledModel model(Map<String, CompiledConcept> concepts) {
        return new CompiledModel("test", "1.0.0", concepts);
    }

    @Test
    void resolveReadsTheSessionsTableWhenTheConceptIsComposed() {
        IdentityPackTableNames names = IdentityPackTableNames.resolve(model(identityConcepts(true)));

        assertEquals("identity_users", names.usersTable());
        assertEquals("identity_user_sessions", names.userSessionsTable());
    }

    @Test
    void resolveLeavesTheSessionsTableNullOnAPackWithoutSessions() {
        IdentityPackTableNames names = IdentityPackTableNames.resolve(model(identityConcepts(false)));

        assertEquals("identity_user_role_permissions", names.userRolePermissionsTable());
        assertNull(names.userSessionsTable(), "an older identity pack means sessions are off, not an error");
    }

    @Test
    void aSessionsConceptWithABlankTableNameCountsAsOff() {
        Map<String, CompiledConcept> concepts = identityConcepts(false);
        concepts.put("identity::UserSession", concept("identity::UserSession", "  "));

        assertNull(IdentityPackTableNames.resolve(model(concepts)).userSessionsTable());
    }

    @Test
    void theFourTableFormHasNoSessionsTable() {
        IdentityPackTableNames names = new IdentityPackTableNames("u", "r", "ur", "urp");

        assertEquals("urp", names.userRolePermissionsTable());
        assertNull(names.userSessionsTable());
    }

    @Test
    void aMissingMandatoryIdentityTableStillThrowsFromResolve() {
        Map<String, CompiledConcept> concepts = identityConcepts(true);
        concepts.remove("identity::Role");

        assertThrows(IllegalStateException.class, () -> IdentityPackTableNames.resolve(model(concepts)));
    }

    @Test
    void tryResolveIsEmptyWhenTheIdentityPackIsAbsentOrIncomplete() {
        assertTrue(IdentityPackTableNames.tryResolve(model(new LinkedHashMap<>())).isEmpty());

        Map<String, CompiledConcept> incomplete = identityConcepts(true);
        incomplete.remove("identity::UserRole");
        assertTrue(IdentityPackTableNames.tryResolve(model(incomplete)).isEmpty());

        Optional<IdentityPackTableNames> complete = IdentityPackTableNames.tryResolve(model(identityConcepts(true)));
        assertEquals("identity_user_sessions", complete.orElseThrow().userSessionsTable());
    }
}
