package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Wave 3 (NPDEV_FEATURE_PLAN_2026-09-24): the one primitive a "complete your profile" flow needs
 * that nothing else exposed -- the current caller's own {@code identity::User} ROW (id included),
 * not just the JWT's username subject. A flow's {@code $actorId} state variable and a
 * {@code propertyScopes} entry scoped {@code "$user.id"} both resolve to the JWT subject (the
 * USERNAME string, see {@code DefaultPropertyResolver.resolveScopeId}), because that is what
 * {@code ExecutionContext.actorId()} carries platform-wide. Neither gives a client (or a flow step)
 * the {@code identity::User.id} UUID a NEW profile concept must set as its reference-field value
 * when linking itself to the signed-in account (Pigmentampa's {@code Artist.userId}, and any future
 * app's own "profile owned by the current user" concept) -- reference fields always target a
 * concept's primary key, and no DSL flow step can look a concept up by a non-id field. Client-side
 * code fills that gap: call this endpoint once after sign-in, then submit the profile-creation form
 * with {@code userId} set to the {@code id} this returns.
 *
 * <p>Mirrors {@link ChangePasswordController}'s claims-resolution and identity-table-lookup pattern
 * exactly (same {@code CLAIMS_ATTRIBUTE} request attribute, same {@link IdentityPackTableNames}
 * resolution) rather than inventing a second convention. Read-only, and returns only the CALLER's
 * own row -- never accepts a target id/username, so it carries none of a generic user-lookup
 * endpoint's enumeration risk.
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class CurrentUserController {

    /** Mirrors {@link ChangePasswordController#CLAIMS_ATTRIBUTE} -- same reasoning, see there. */
    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";

    private final DataSource dataSource;
    private final AuthenticatedContextResolver authenticatedContextResolver;
    private final ModelHolder modelHolder;

    @Autowired
    public CurrentUserController(
            DataSource dataSource,
            AuthenticatedContextResolver authenticatedContextResolver,
            ModelHolder modelHolder
    ) {
        this.dataSource = dataSource;
        this.authenticatedContextResolver = authenticatedContextResolver;
        this.modelHolder = modelHolder;
    }

    @GetMapping("/api/auth/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest httpRequest) {
        ExecutionContext callerContext = currentContext(httpRequest);
        String tenantId = callerContext == null || callerContext.tenantId() == null || callerContext.tenantId().isBlank()
                ? "dev" : callerContext.tenantId().trim();
        String username = callerContext == null ? null : callerContext.actorId();
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        }

        Optional<IdentityPackTableNames> resolvedIdentityTables = IdentityPackTableNames.tryResolve(modelHolder.get());
        if (resolvedIdentityTables.isEmpty()) {
            return ResponseEntity.status(503).body(Map.of("error", "identity_pack_not_composed"));
        }
        String usersTable = resolvedIdentityTables.get().usersTable();

        String sql = "SELECT id, username, display_name, email, avatar_url FROM " + usersTable
                + " WHERE username = ? AND tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("id", rs.getString("id"));
                body.put("username", rs.getString("username"));
                body.put("displayName", rs.getString("display_name"));
                body.put("email", rs.getString("email"));
                body.put("avatarUrl", rs.getString("avatar_url"));
                return ResponseEntity.ok(body);
            }
        } catch (Exception exception) {
            return ResponseEntity.status(500).body(Map.of("error", "current_user_lookup_failed"));
        }
    }

    /** Same resolution as {@link ChangePasswordController#currentContext}; see there for why this
     * is reimplemented per-controller rather than shared (runtimehost-core cannot depend on the
     * per-app generated classes that own the canonical version). */
    private ExecutionContext currentContext(HttpServletRequest request) {
        Object rawClaims = request.getAttribute(CLAIMS_ATTRIBUTE);
        if (!(rawClaims instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null) {
                claims.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        if (claims.isEmpty()) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith("x-tag-")) {
                    headers.put(name, request.getHeader(name));
                }
            }
        }
        return authenticatedContextResolver.resolveFromPrincipal(claims, headers);
    }
}
