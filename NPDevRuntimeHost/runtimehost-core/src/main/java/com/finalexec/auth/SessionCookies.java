package com.finalexec.auth;

import org.springframework.http.ResponseCookie;

/**
 * The one place the {@code npdev_jwt} session cookie's attributes live. Every endpoint that sets or
 * clears the browser session ({@link LoginController}, {@link OAuthGoogleController},
 * {@link ChangePasswordController}, {@link LogoutController}) goes through here, so the cookie a
 * later response replaces or expires always has the SAME name, path and SameSite as the original --
 * otherwise the browser treats it as a different cookie and keeps the stale one.
 *
 * <p>HttpOnly so client JS/XSS cannot read it. SameSite=Lax, not Strict (REG-233): Lax still blocks
 * the cookie from riding along on cross-site subrequests and form posts -- CSRF protection is
 * preserved -- but it permits the cookie on a top-level GET navigation that lands on our own URL via
 * a cross-site redirect, which Strict does not. The Google OAuth account-LINK callback needs this
 * exact cookie to survive a redirect FROM the provider. {@code secure} mirrors the inbound request's
 * own scheme so local http dev works while a TLS-terminated deployment gets the flag automatically.</p>
 */
final class SessionCookies {

    static final String NAME = "npdev_jwt";

    private SessionCookies() {
    }

    static ResponseCookie session(String token, long maxAgeSeconds, boolean secure) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }

    static ResponseCookie expired(boolean secure) {
        return session("", 0, secure);
    }
}
