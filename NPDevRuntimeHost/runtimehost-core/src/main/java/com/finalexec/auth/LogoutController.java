package com.finalexec.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEC-2: server-side sign-out. The {@code npdev_jwt} session cookie is HttpOnly (set by
 * {@link LoginController} / {@link OAuthGoogleController}), so browser JavaScript cannot delete it;
 * the only way to end a cookie session is for the server to answer with an expiring
 * {@code Set-Cookie}. The expiring cookie carries the SAME name, path and SameSite as the one the
 * login sets -- otherwise the browser treats it as a different cookie and keeps the real one.
 *
 * <p>Deliberately does NOT bump the user's token_version: that would sign the user out on every
 * device ("sign out everywhere" is a separate concern). The endpoint needs no credential (the filter
 * exempts it): it must work with an expired or invalid cookie, and forging a call only signs the
 * caller out.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class LogoutController {

    @PostMapping({"/api/auth/logout", "/api/v1/auth/logout"})
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        ResponseCookie expired = SessionCookies.expired(request != null && request.isSecure());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }
}
