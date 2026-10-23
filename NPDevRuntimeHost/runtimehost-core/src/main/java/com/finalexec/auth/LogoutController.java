package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(LogoutController.class.getName());

    private final DataSource dataSource;
    private final ModelHolder modelHolder;

    /** Kept for callers that build the controller without the identity wiring (sessions then stay off). */
    public LogoutController() {
        this((DataSource) null, (ModelHolder) null);
    }

    /** Spring: an app without a DataSource (or without the identity wiring) just has no sessions to end. */
    @Autowired
    public LogoutController(ObjectProvider<DataSource> dataSource, ObjectProvider<ModelHolder> modelHolder) {
        this(dataSource.getIfAvailable(), modelHolder.getIfAvailable());
    }

    LogoutController(DataSource dataSource, ModelHolder modelHolder) {
        this.dataSource = dataSource;
        this.modelHolder = modelHolder;
    }

    @PostMapping({"/api/auth/logout", "/api/v1/auth/logout"})
    public ResponseEntity<?> logout(HttpServletRequest request) {
        Exception revokeFailure = endSession(request);
        ResponseCookie expired = SessionCookies.expired(request != null && request.isSecure());
        if (revokeFailure != null) {
            // REG-293: the cookie is still cleared (this browser is signed out), but the caller is told
            // the server-side session was NOT revoked, so the token stays valid until it expires.
            return ResponseEntity.status(503).header(HttpHeaders.SET_COOKIE, expired.toString())
                    .body(Map.of("error", "session_revoke_failed",
                            "detail", "This browser is signed out, but the session could not be ended on the "
                                    + "server; the token stays valid until it expires. Try signing out again, "
                                    + "or sign out of all devices."));
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    /**
     * SESS-1: revoke the session this sign-out belongs to. Answers {@code null} when it was ended or
     * there was none to end (sessions off, or a token without a {@code sid}), and the failure itself when
     * revocation was attempted and failed (REG-293: it used to be swallowed and logout still answered 204).
     */
    private Exception endSession(HttpServletRequest request) {
        if (dataSource == null || modelHolder == null) {
            return null;
        }
        String[] session = UserSessionStore.sessionFromToken(request);
        if (session == null) {
            return null;
        }
        try {
            Optional<IdentityPackTableNames> tables = IdentityPackTableNames.tryResolve(modelHolder.get());
            if (tables.isPresent()) {
                UserSessionStore.revokeBySessionId(dataSource, tables.get(), session[0], session[1]);
            }
            return null;
        } catch (SQLException | RuntimeException failure) {
            LOG.log(Level.SEVERE, "Sign-out could not end the server-side session: " + failure.getMessage(), failure);
            return failure;
        }
    }
}
