package com.finalexec.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-1: a cookie-carried credential (npdev_jwt) authorizes a state-changing request only when the
 * request also carries the {@code X-NPDev-Request} header; Bearer-header credentials and safe
 * methods are unaffected. The tokens here are garbage on purpose -- reaching token validation (401)
 * proves the CSRF gate let the request through, a 403 proves it stopped it.
 */
class JwtBearerAuthFilterCsrfTest {

    private final JwtBearerAuthFilter filter = new JwtBearerAuthFilter(
            new ObjectMapper(), new DefaultResourceLoader(), "issuer", "audience", "");

    private MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    private static MockHttpServletRequest cookieRequest(String method) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/api/things");
        req.setCookies(new Cookie("npdev_jwt", "a.b.c"));
        return req;
    }

    @Test
    void cookieOnlyPostWithoutHeaderIsRefused403() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run(cookieRequest("POST"), chain);

        assertEquals(403, res.getStatus());
        assertTrue(res.getContentAsString().contains("csrf_header_required"));
        assertNull(chain.getRequest(), "chain must not run");
    }

    @Test
    void cookieOnlyPostWithHeaderReachesTokenValidation() throws Exception {
        MockHttpServletRequest req = cookieRequest("POST");
        req.addHeader(JwtBearerAuthFilter.CSRF_HEADER, "1");
        MockHttpServletResponse res = run(req, new MockFilterChain());

        assertEquals(401, res.getStatus());
    }

    @Test
    void cookieOnlyGetNeedsNoHeader() throws Exception {
        assertEquals(401, run(cookieRequest("GET"), new MockFilterChain()).getStatus());
    }

    @Test
    void bearerHeaderPostNeedsNoCsrfHeader() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/things");
        req.addHeader("Authorization", "Bearer a.b.c");
        assertEquals(401, run(req, new MockFilterChain()).getStatus());
    }

    @Test
    void requiresCsrfProofTable() {
        for (String safe : new String[] {"GET", "HEAD", "OPTIONS", "TRACE"}) {
            assertFalse(JwtBearerAuthFilter.requiresCsrfProof(new MockHttpServletRequest(safe, "/api/x")), safe);
        }
        for (String unsafe : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
            MockHttpServletRequest bare = new MockHttpServletRequest(unsafe, "/api/x");
            assertTrue(JwtBearerAuthFilter.requiresCsrfProof(bare), unsafe);
            MockHttpServletRequest proved = new MockHttpServletRequest(unsafe, "/api/x");
            proved.addHeader(JwtBearerAuthFilter.CSRF_HEADER, "1");
            assertFalse(JwtBearerAuthFilter.requiresCsrfProof(proved), unsafe);
        }
    }

    @Test
    void logoutIsExemptFromTheFilterEvenWithAStaleCookie() throws Exception {
        Method method = JwtBearerAuthFilter.class.getDeclaredMethod("shouldNotFilter", jakarta.servlet.http.HttpServletRequest.class);
        method.setAccessible(true);
        for (String uri : new String[] {"/api/auth/logout", "/api/v1/auth/logout"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
            req.setCookies(new Cookie("npdev_jwt", "expired.stale.cookie"));
            assertTrue((boolean) method.invoke(filter, req), uri);
        }
    }
}
