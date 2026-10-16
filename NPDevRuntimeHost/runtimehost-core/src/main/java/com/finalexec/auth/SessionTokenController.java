package com.finalexec.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SEC-5: lets the generated shell adopt a cookie-only session. After an OAuth round trip (or any
 * future OIDC/MFA flow that ends in the cookie) the browser holds only the HttpOnly {@code npdev_jwt}
 * cookie, which JavaScript cannot read, while the shell looks for a bearer token in localStorage.
 * This endpoint hands the (already validated) cookie value back to same-origin script so the shell
 * can store it exactly like a password login does.
 *
 * <p>NOT exempted in {@code JwtBearerAuthFilter}: the filter has already validated the cookie
 * (signature, expiry) and, per SEC-1, required the {@code X-NPDev-Request} header for this POST, so a
 * cross-site page cannot read the token. A request that already carries an {@code Authorization}
 * header has nothing to adopt (400).</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class SessionTokenController {

    static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    static final String COOKIE_NAME = "npdev_jwt";

    @PostMapping({"/api/auth/session-token", "/api/v1/auth/session-token"})
    public ResponseEntity<Map<String, Object>> sessionToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && !authorization.isBlank()) {
            return body(HttpStatus.BAD_REQUEST, "error", "bearer_already_present");
        }
        if (request.getAttribute(CLAIMS_ATTRIBUTE) == null) {
            return body(HttpStatus.UNAUTHORIZED, "error", "unauthenticated");
        }
        String token = cookieValue(request);
        if (token == null) {
            // Authenticated by something other than the session cookie (e.g. a super-user key).
            return body(HttpStatus.UNAUTHORIZED, "error", "unauthenticated");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("tokenType", "Bearer");
        return ResponseEntity.ok(out);
    }

    private static String cookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String v = cookie.getValue();
                return v == null || v.isBlank() ? null : v.trim();
            }
        }
        return null;
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String key, String value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(key, value);
        return ResponseEntity.status(status).body(out);
    }
}
