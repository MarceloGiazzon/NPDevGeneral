package com.finalexec.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SEC-2: pins that logout answers 204 with an expiring npdev_jwt cookie matching the login cookie. */
class LogoutControllerTest {

    @Test
    void logoutExpiresTheSessionCookie() {
        ResponseEntity<?> res = new LogoutController().logout(new MockHttpServletRequest());

        assertEquals(HttpStatus.NO_CONTENT, res.getStatusCode());
        String cookie = res.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("npdev_jwt=;"), cookie);
        assertTrue(cookie.contains("Max-Age=0"), cookie);
        assertTrue(cookie.contains("HttpOnly"), cookie);
        assertTrue(cookie.contains("Path=/"), cookie);
        assertTrue(cookie.contains("SameSite=Lax"), cookie);
        assertFalse(cookie.contains("Secure"), cookie);
    }

    @Test
    void secureFlagMirrorsTheInboundScheme() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(true);

        String cookie = new LogoutController().logout(request).getHeaders().getFirst(HttpHeaders.SET_COOKIE);

        assertNotNull(cookie);
        assertTrue(cookie.contains("Secure"), cookie);
    }
}
