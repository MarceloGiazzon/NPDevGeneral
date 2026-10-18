package com.finalexec.auth;

import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SESS-2: "Where you're signed in". {@code GET /api/auth/sessions} lists the caller's own live sessions
 * (one per sign-in, see {@link UserSessionStore}) and {@code DELETE /api/auth/sessions/{id}} ends ONE of
 * them, so a lost phone can be signed out without signing out the laptop.
 *
 * <p>Only the caller's own sessions are ever listed or revoked: the user comes from the verified token's
 * claims, never from the request. The browser sees a short handle (the last six characters of the session
 * id), never the id itself, so a leaked page cannot be turned into a token claim. The row for the
 * request's own session is marked {@code current}; the page offers no Revoke for it (that is sign-out).
 * Behind the normal authentication filter -- this is not an exempt auth path.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class UserSessionsController {

    private static final Logger LOG = Logger.getLogger(UserSessionsController.class.getName());

    private final DataSource dataSource;
    private final ModelHolder modelHolder;

    @Autowired
    public UserSessionsController(ObjectProvider<DataSource> dataSource, ObjectProvider<ModelHolder> modelHolder) {
        this(dataSource.getIfAvailable(), modelHolder.getIfAvailable());
    }

    UserSessionsController(DataSource dataSource, ModelHolder modelHolder) {
        this.dataSource = dataSource;
        this.modelHolder = modelHolder;
    }

    @GetMapping({"/api/auth/sessions", "/api/v1/auth/sessions"})
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request) {
        Caller caller = caller(request);
        if (caller == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        }
        Optional<IdentityPackTableNames> tables = tables();
        if (tables.isEmpty() || tables.get().userSessionsTable() == null) {
            // Sessions are off (identity pack older than 1.4.0): an empty list, not an error.
            return ResponseEntity.ok(Map.of("sessions", List.of(), "enabled", false));
        }
        try {
            String currentSid = UserSessionStore.sessionIdOf(request);
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (UserSessionStore.Listed listed : UserSessionStore.list(dataSource, tables.get(), caller.tenantId(), caller.username())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", listed.shortId());
                row.put("userAgent", listed.userAgent());
                row.put("ipAddress", listed.ipAddress());
                row.put("createdAt", listed.createdAt() == null ? null : listed.createdAt().toString());
                row.put("lastSeenAt", listed.lastSeenAt() == null ? null : listed.lastSeenAt().toString());
                row.put("current", currentSid != null && currentSid.equals(listed.sessionId()));
                sessions.add(row);
            }
            return ResponseEntity.ok(Map.of("sessions", sessions, "enabled", true));
        } catch (SQLException failure) {
            LOG.log(Level.WARNING, "Listing sessions failed", failure);
            return ResponseEntity.status(500).body(Map.of("error", "sessions_unavailable"));
        }
    }

    @DeleteMapping({"/api/auth/sessions/{id}", "/api/v1/auth/sessions/{id}"})
    public ResponseEntity<Map<String, Object>> revoke(HttpServletRequest request, @PathVariable("id") String handle) {
        Caller caller = caller(request);
        if (caller == null) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        }
        Optional<IdentityPackTableNames> tables = tables();
        if (tables.isEmpty() || tables.get().userSessionsTable() == null) {
            return ResponseEntity.status(404).body(Map.of("error", "session_not_found"));
        }
        try {
            String currentSid = UserSessionStore.sessionIdOf(request);
            if (currentSid != null && currentSid.endsWith(handle)) {
                // This is the session making the request: ending it is sign-out, not "revoke a device".
                return ResponseEntity.status(409).body(Map.of("error", "cannot_revoke_current_session"));
            }
            UserSessionStore.HandleResult result = UserSessionStore.revokeOwnedByHandle(
                    dataSource, tables.get(), caller.tenantId(), caller.username(), handle);
            return switch (result) {
                case REVOKED -> ResponseEntity.ok(Map.of("ok", true));
                case AMBIGUOUS -> ResponseEntity.status(409).body(Map.of("error", "session_handle_ambiguous"));
                case NOT_FOUND -> ResponseEntity.status(404).body(Map.of("error", "session_not_found"));
            };
        } catch (SQLException failure) {
            LOG.log(Level.WARNING, "Revoking a session failed", failure);
            return ResponseEntity.status(500).body(Map.of("error", "sessions_unavailable"));
        }
    }

    private Optional<IdentityPackTableNames> tables() {
        if (dataSource == null || modelHolder == null) {
            return Optional.empty();
        }
        return IdentityPackTableNames.tryResolve(modelHolder.get());
    }

    private record Caller(String tenantId, String username) {
    }

    private static Caller caller(HttpServletRequest request) {
        Object raw = request.getAttribute(UserSessionStore.CLAIMS_ATTRIBUTE);
        if (!(raw instanceof Map<?, ?> claims)) {
            return null;
        }
        Object subject = claims.get("sub");
        Object tenant = claims.get("tenant_id");
        if (subject == null || String.valueOf(subject).isBlank()) {
            return null;
        }
        return new Caller(DefaultTenant.orDefault(tenant == null ? null : String.valueOf(tenant)),
                String.valueOf(subject));
    }
}
