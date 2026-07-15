package com.finalexec.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-8: the live end-to-end verification (real bootRun + curl) could only prove the
 * without-a-key 403 path -- the sample used has no physical database, so
 * SuperUserBootstrapper never issues a real SUPERUSER credential to test the success path
 * against (see project memory). This test covers that gap hermetically.
 */
class ActuatorAdminGuardFilterTest {

    // Literal copy of RuntimeApiKeyAuthFilter's CLAIMS_ATTRIBUTE constant value: this test file
    // must not textually reference the generated runtime package at all (not even in a comment)
    // -- the generated build.gradle's `test` task excludes any test source file whose text
    // contains that package prefix (those run under `integrationTest` instead, off a hardcoded
    // per-app allowlist this generic filter test isn't on).
    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    @Test
    void rejectsWhenNoClaimsAttributePresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ActuatorAdminGuardFilter().doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertFalse(chain.getRequest() == request, "Chain must not continue");
    }

    @Test
    void rejectsWhenClaimsPresentButRoleIsNotSuperuser() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/metrics");
        request.setAttribute(CLAIMS_ATTRIBUTE, Map.of(
                "tenant_id", "default",
                "actor_id", "someone",
                "roles", List.of("ADMIN")
        ));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ActuatorAdminGuardFilter().doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
    }

    @Test
    void allowsWhenSuperuserRolePresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setAttribute(CLAIMS_ATTRIBUTE, Map.of(
                "tenant_id", "default",
                "actor_id", "root",
                "roles", List.of("SUPERUSER")
        ));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new ActuatorAdminGuardFilter().doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() == request, "Chain must continue to the actuator endpoint");
    }
}
