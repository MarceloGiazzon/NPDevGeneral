package com.npdev.kernel.ports;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code npdev.auth.api-keys} grammar documented in docs/CONFIGURATION.md ("apikey mode"), pinned by
 * behaviour. It used to be pinned as text fragments of the generated filter (REG-314); with one shared
 * parser (REG-319) the behaviour is what is documented, so the behaviour is what is asserted.
 */
class ApiKeyMappingsTest {

    @Test
    void parsesTheDocumentedExample() {
        ApiKeyMappings.Parsed parsed = ApiKeyMappings.parse(
                "admin-key=dev:admin:ADMIN;emp-key=dev:emp:EMPLOYEE;appr-key=dev:appr:LEAVE_APPROVER|EMPLOYEE;plain-key=dev:plain");

        assertEquals(0, parsed.dropped());
        assertEquals(4, parsed.byApiKey().size());
        assertEquals(new ApiKeyCredentialResolver.Principal("dev", "admin", Set.of("ADMIN")), parsed.byApiKey().get("admin-key"));
        assertEquals(Set.of("LEAVE_APPROVER", "EMPLOYEE"), parsed.byApiKey().get("appr-key").roles(),
                "roles are separated by '|'");
        assertEquals("appr", parsed.byApiKey().get("appr-key").actorId(),
                "the actor part is what the claims carry as actor_id ($user.id)");
    }

    @Test
    void anOmittedRolePartMeansTheSingleRoleUser() {
        assertEquals(Set.of("USER"), ApiKeyMappings.parse("k=t:a").byApiKey().get("k").roles());
    }

    @Test
    void rolesAreUpperCasedSoMatchingIsCaseInsensitive() {
        assertEquals(Set.of("ADMIN", "OPS"), ApiKeyMappings.parse("k=dev:developer:admin|Ops").byApiKey().get("k").roles());
    }

    @Test
    void theApiKeyIsEverythingBeforeTheFirstEqualsSign() {
        ApiKeyMappings.Parsed parsed = ApiKeyMappings.parse("a=b=c:d");

        assertEquals(Set.of("a"), parsed.byApiKey().keySet());
        assertEquals("b=c", parsed.byApiKey().get("a").tenantId());
    }

    @Test
    void whitespaceAroundEntriesAndPartsIsIgnoredAndBlankEntriesAreNotCounted() {
        ApiKeyMappings.Parsed parsed = ApiKeyMappings.parse(" k = t : a : admin ; ; ");

        assertEquals(0, parsed.dropped());
        assertEquals(new ApiKeyCredentialResolver.Principal("t", "a", Set.of("ADMIN")), parsed.byApiKey().get("k"));
    }

    @Test
    void anEntryWithoutBothTenantAndActorIsDroppedAndCountedNotRejected() {
        // key=ADMIN and key=tenant are the shapes docs/CONFIGURATION.md used to describe (REG-314)
        ApiKeyMappings.Parsed parsed = ApiKeyMappings.parse("a=ADMIN;b=tenant;c=:actor;d=tenant:;e=;=t:a;f=t:a:|;g=t:a:ADMIN");

        assertEquals(Set.of("g"), parsed.byApiKey().keySet());
        assertEquals(7, parsed.dropped());
    }

    @Test
    void blankAndNullParseToNothingAndDropNothing() {
        for (String empty : new String[] {null, "", "   ", ";", " ; ; "}) {
            ApiKeyMappings.Parsed parsed = ApiKeyMappings.parse(empty);

            assertTrue(parsed.byApiKey().isEmpty(), "[" + empty + "]");
            assertEquals(0, parsed.dropped(), "[" + empty + "]");
        }
    }
}
