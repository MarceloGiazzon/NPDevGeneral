package com.finalexec.auth;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.CapabilityDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SEC-6: an ADMIN invites a person by e-mail instead of inventing a password for them. The invitee
 * receives a one-time link, chooses their own password (policy-checked) through the existing
 * {@code POST /api/auth/password-reset/confirm}, and the admin never sees it. No new table: an
 * invitation is a password-reset token ({@link OneTimeTokens}) with a longer expiry
 * ({@code npdev.auth.invite.expiry-hours}, default 168), on a user row created with an unusable random
 * password so the confirm step has a credential row to update.
 *
 * <p>The mail is sent AFTER the commit: a mail failure never rolls back the user (the admin can
 * resend). The raw token is returned ({@code acceptPath}) only when no mail was sent.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class InvitationController {

    private static final Logger LOG = Logger.getLogger(InvitationController.class.getName());
    /** Default role for an invite is USER -- unlike CreateUserController's legacy ADMIN default. */
    private static final String DEFAULT_INVITE_ROLE = "USER";

    private final DataSource dataSource;
    private final RuntimeContextService runtimeContextService;
    private final ModelHolder modelHolder;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityDispatcher capabilityDispatcher;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final String credentialPrimaryLinkColumn;
    private final String credentialSecondaryLinkColumn;
    private final String linkBaseUrl;
    private final long expiryHours;

    public InvitationController(
            DataSource dataSource,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder,
            CapabilityRegistry capabilityRegistry,
            CapabilityDispatcher capabilityDispatcher,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.create-user.credential-primary-link-column:entidade_id}") String credentialPrimaryLinkColumn,
            @Value("${npdev.auth.create-user.credential-secondary-link-column:estabelecimento_padrao_id}") String credentialSecondaryLinkColumn,
            @Value("${npdev.auth.password-reset.link-base-url:}") String linkBaseUrl,
            @Value("${npdev.auth.invite.expiry-hours:168}") long expiryHours
    ) {
        this.dataSource = dataSource;
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityDispatcher = capabilityDispatcher;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.credentialPrimaryLinkColumn = credentialPrimaryLinkColumn;
        this.credentialSecondaryLinkColumn = credentialSecondaryLinkColumn;
        this.linkBaseUrl = linkBaseUrl == null ? "" : linkBaseUrl;
        this.expiryHours = expiryHours > 0 ? expiryHours : 168;
    }

    public record InviteRequest(
            String email, String username, String displayName, List<String> roleNames,
            String primaryLinkId, String secondaryLinkId
    ) {
    }

    @PostMapping("/api/auth/invite")
    public ResponseEntity<Map<String, Object>> invite(@RequestBody InviteRequest request, HttpServletRequest httpRequest) {
        String tenantId = requireAdmin(httpRequest).tenantId();
        String email = trimToNull(request == null ? null : request.email());
        if (email == null || !email.contains("@")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_email"));
        }
        String username = Optional.ofNullable(trimToNull(request.username())).orElse(email.toLowerCase(Locale.ROOT));
        String displayName = Optional.ofNullable(trimToNull(request.displayName()))
                .orElse(email.substring(0, email.indexOf('@')));

        CompiledModel model = modelHolder.get();
        List<String> roles = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> requested = request.roleNames() == null || request.roleNames().isEmpty()
                ? List.of(DEFAULT_INVITE_ROLE) : request.roleNames();
        for (String raw : requested) {
            String canonical = CreateUserController.assignableRole(model, raw);
            if (canonical == null) {
                rejected.add(String.valueOf(raw));
            } else if (!roles.contains(canonical)) {
                roles.add(canonical);
            }
        }
        if (!rejected.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "role_not_declared_by_model",
                    "rejected", rejected,
                    "assignableRoles", CreateUserController.assignableRoles(model)));
        }
        IdentityPackTableNames ids = IdentityPackTableNames.tryResolve(model).orElse(null);
        String tokenTable = OneTimeTokens.tableName(model);
        if (ids == null || tokenTable == null) {
            return ResponseEntity.status(503).body(Map.of("error", "identity_pack_not_composed"));
        }

        UUID userId = UUID.randomUUID();
        String raw = OneTimeTokens.newRawToken();
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (IdentityProvisioning.usernameTaken(connection, ids, tenantId, username)) {
                    connection.rollback();
                    return ResponseEntity.status(409).body(Map.of("error", "username_taken"));
                }
                IdentityProvisioning.insertIdentityUser(connection, ids, userId, username, displayName, email, tenantId);
                for (String role : roles) {
                    UUID roleId = IdentityProvisioning.findOrCreateRole(connection, ids, tenantId, role, "Invited");
                    IdentityProvisioning.insertUserRole(connection, ids, userId, roleId, tenantId);
                }
                // An unusable random password: the credential row must exist so the reset-confirm UPDATE matches it.
                IdentityProvisioning.insertCredential(
                        connection, credentialTable, credentialUserIdColumn, credentialPasswordColumn,
                        UUID.randomUUID(), userId, OneTimeTokens.newRawToken(), tenantId,
                        credentialPrimaryLinkColumn, request.primaryLinkId(),
                        credentialSecondaryLinkColumn, request.secondaryLinkId());
                OneTimeTokens.insert(connection, tokenTable, userId, OneTimeTokens.sha256Hex(raw),
                        Instant.now().plus(Duration.ofHours(expiryHours)), tenantId);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                LOG.log(Level.WARNING, "Invitation failed", exception);
                return ResponseEntity.status(500).body(Map.of("error", "invite_failed"));
            }
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Invitation failed", exception);
            return ResponseEntity.status(500).body(Map.of("error", "invite_failed"));
        }
        return ResponseEntity.status(201).body(deliver(userId, username, email, raw));
    }

    /** SEC-7: the roles an ADMIN may put on an invitation -- feeds the invite page's role checkboxes. */
    @GetMapping("/api/auth/assignable-roles")
    public ResponseEntity<Map<String, Object>> assignableRoles(HttpServletRequest httpRequest) {
        requireAdmin(httpRequest);
        return ResponseEntity.ok(Map.of("roles", CreateUserController.assignableRoles(modelHolder.get())));
    }

    /** Resend: a fresh token (and mail) for an existing user in the caller's tenant with an email on file. */
    @PostMapping("/api/auth/users/{username}/invite")
    public ResponseEntity<Map<String, Object>> resend(@PathVariable String username, HttpServletRequest httpRequest) {
        String tenantId = requireAdmin(httpRequest).tenantId();
        CompiledModel model = modelHolder.get();
        IdentityPackTableNames ids = IdentityPackTableNames.tryResolve(model).orElse(null);
        String tokenTable = OneTimeTokens.tableName(model);
        if (ids == null || tokenTable == null) {
            return ResponseEntity.status(503).body(Map.of("error", "identity_pack_not_composed"));
        }
        String raw = OneTimeTokens.newRawToken();
        UUID userId;
        String email;
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, email FROM " + ids.usersTable() + " WHERE username = ? AND tenant_id = ?")) {
                ps.setString(1, username);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return ResponseEntity.status(404).body(Map.of("error", "user_not_found"));
                    }
                    userId = UUID.fromString(String.valueOf(rs.getObject("id")));
                    email = trimToNull(rs.getString("email"));
                }
            }
            if (email == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "no_email_on_file"));
            }
            OneTimeTokens.insert(connection, tokenTable, userId, OneTimeTokens.sha256Hex(raw),
                    Instant.now().plus(Duration.ofHours(expiryHours)), tenantId);
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "Invitation resend failed", exception);
            return ResponseEntity.status(500).body(Map.of("error", "invite_failed"));
        }
        return ResponseEntity.ok(deliver(userId, username, email, raw));
    }

    /** Mails the link (AFTER commit). The raw token appears in the response only when nothing was mailed. */
    private Map<String, Object> deliver(UUID userId, String username, String email, String rawToken) {
        boolean emailed = sendInvite(email, rawToken);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId.toString());
        body.put("username", username);
        body.put("emailed", emailed);
        if (!emailed) {
            body.put("acceptPath", "/reset-password.html?invite=1&token=" + rawToken);
        }
        return body;
    }

    private boolean sendInvite(String email, String rawToken) {
        if (!capabilityRegistry.has("mail")) {
            return false;
        }
        try {
            String link = linkBaseUrl.isBlank()
                    ? "/reset-password.html?invite=1&token=" + rawToken
                    : linkBaseUrl + rawToken + "&invite=1";
            String body = "You have been invited. Choose your password: " + link
                    + "\nThis link expires in " + expiryHours + " hours and can be used once.";
            CapabilityCall call = new CapabilityCall(
                    "mail", "EmailCapability", capabilityRegistry.debugDefaultAdapterId("mail"), "send",
                    List.of(email, "You have been invited", body));
            CapabilityResult result = capabilityDispatcher.invoke(call, Map.of());
            if (!result.ok()) {
                LOG.warning("Invitation email dispatch failed: " + result.error());
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            LOG.log(Level.WARNING, "Invitation email dispatch failed", exception);
            return false;
        }
    }

    private ExecutionContext requireAdmin(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
