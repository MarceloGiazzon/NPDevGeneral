package com.finalexec.auth;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** SEC-5: pins {@link SessionTokenController}'s three outcomes. */
class SessionTokenControllerTest {

    private final SessionTokenController controller = new SessionTokenController();

    @Test
    void echoesTheValidatedCookieWhenClaimsArePresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("npdev.auth.claims", Map.of("sub", "artist1"));
        request.setCookies(new Cookie("npdev_jwt", "aaa.bbb.ccc"));

        ResponseEntity<Map<String, Object>> response = controller.sessionToken(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("aaa.bbb.ccc", response.getBody().get("token"));
        assertEquals("Bearer", response.getBody().get("tokenType"));
    }

    @Test
    void unauthenticatedWithoutClaims() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("npdev_jwt", "aaa.bbb.ccc"));

        ResponseEntity<Map<String, Object>> response = controller.sessionToken(request);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("unauthenticated", response.getBody().get("error"));
        assertNull(response.getBody().get("token"));
    }

    @Test
    void unauthenticatedWhenClaimsExistButNoCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("npdev.auth.claims", Map.of("sub", "artist1"));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.sessionToken(request).getStatusCode());
    }

    @Test
    void badRequestWhenAuthorizationHeaderAlreadyPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer x.y.z");
        request.setAttribute("npdev.auth.claims", Map.of("sub", "artist1"));
        request.setCookies(new Cookie("npdev_jwt", "aaa.bbb.ccc"));

        ResponseEntity<Map<String, Object>> response = controller.sessionToken(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("bearer_already_present", response.getBody().get("error"));
    }
}
