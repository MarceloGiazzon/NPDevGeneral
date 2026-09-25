package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wave 3 (NPDEV_FEATURE_PLAN_2026-09-24): pins the live bug caught on Pigmentampa's GitHub sign-in
 * button -- {@code shouldNotFilter} used to hardcode the literal "google" path segment for the
 * OAuth authorize/callback exemption, so ANY other provider's authorize/callback request (GitHub
 * included) was rejected here with {@code missing_bearer_token} before
 * {@code OAuthGoogleController} (provider-parameterized since before this fix) ever ran. Tests
 * {@code shouldNotFilter} via reflection since it is a protected {@code OncePerRequestFilter}
 * method with no public wrapper.
 */
class JwtBearerAuthFilterOauthPathExemptionTest {

    private static boolean shouldNotFilter(String uri) throws Exception {
        JwtBearerAuthFilter filter = new JwtBearerAuthFilter(
                new ObjectMapper(), new DefaultResourceLoader(), "issuer", "audience", "");
        Method method = JwtBearerAuthFilter.class.getDeclaredMethod("shouldNotFilter", jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return (boolean) method.invoke(filter, request);
    }

    @Test
    void exemptsGoogleAuthorizeAndCallbackWithNoCredential() throws Exception {
        assertTrue(shouldNotFilter("/api/auth/oauth/google/authorize"));
        assertTrue(shouldNotFilter("/api/auth/oauth/google/callback"));
    }

    @Test
    void exemptsGithubAuthorizeAndCallbackWithNoCredential() throws Exception {
        assertTrue(shouldNotFilter("/api/auth/oauth/github/authorize"));
        assertTrue(shouldNotFilter("/api/auth/oauth/github/callback"));
    }

    @Test
    void exemptsAnyOtherFutureProviderTheSameWay() throws Exception {
        assertTrue(shouldNotFilter("/api/auth/oauth/microsoft/authorize"));
        assertTrue(shouldNotFilter("/api/v1/auth/oauth/microsoft/callback"));
    }

    @Test
    void doesNotExemptUnrelatedOauthPaths() throws Exception {
        assertFalse(shouldNotFilter("/api/auth/oauth/config-typo"));
        assertFalse(shouldNotFilter("/api/auth/oauth/github/"));
        assertFalse(shouldNotFilter("/api/auth/oauth/github"));
        assertFalse(shouldNotFilter("/api/auth/oauth/github/link"));
    }
}
