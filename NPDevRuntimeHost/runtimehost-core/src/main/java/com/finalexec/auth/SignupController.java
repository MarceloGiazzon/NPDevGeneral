package com.finalexec.auth;

import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * SEC-14: opt-in self-signup with e-mail verification -- off unless the operator sets
 * {@code npdev.auth.signup.enabled=true}. A visitor submits an e-mail address; the account is
 * created with an unusable random password and a one-time link is mailed to that address. Owning the
 * mailbox is the only way to choose a password (through the existing
 * {@code POST /api/auth/password-reset/confirm}, reached from {@code /reset-password.html?signup=1}),
 * so an account whose e-mail was never verified can never sign in. No new table: the link is a
 * {@link OneTimeTokens} row, exactly like an invitation (SEC-6).
 *
 * <p>Hard rules this class keeps:</p>
 * <ul>
 *   <li><b>No account enumeration.</b> Once enabled, every outcome (new, already registered, foreign
 *       domain, invalid address, throttled, no mail bound, failure) answers the identical generic
 *       200. Only "feature switched off" differs (404), which says nothing about any address.</li>
 *   <li><b>No takeover path.</b> An existing account is touched ONLY while it is still pending (it
 *       has one-time-token history and none was ever used, no {@code last_login_at}), has no external
 *       identity link, is active and carries exactly this address as its e-mail; the link then goes to
 *       that address, i.e. to the mailbox owner. Accounts that finished signup, signed in, or are
 *       OAuth-linked are never touched (no resend, no token).</li>
 *   <li><b>Home tenant only.</b> The tenant is never taken from the request: public signup into an
 *       arbitrary tenant would be an open door.</li>
 *   <li><b>No privileged default roles.</b> {@code ADMIN}/{@code SUPERUSER} in
 *       {@code npdev.auth.signup.default-roles} fails startup.</li>
 * </ul>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class SignupController {

    private static final Logger LOG = Logger.getLogger(SignupController.class.getName());
    private static final long TOKEN_EXPIRY_HOURS = 24;
    /** Signup requests per (tenant, e-mail) and per source IP per window before further ones are silently dropped. */
    private static final int SIGNUP_MAX_PER_EMAIL = 3;
    private static final int SIGNUP_MAX_PER_IP = 20;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_DISPLAY_NAME_LENGTH = 120;
    private static final Pattern EMAIL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> FORBIDDEN_DEFAULT_ROLES = Set.of("ADMIN", "SUPERUSER");
    private static final Map<String, Object> GENERIC_RESPONSE = Map.of("ok", true, "message",
            "If this address can be used, we sent a link to finish creating the account.");

    private final DataSource dataSource;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityDispatcher capabilityDispatcher;
    private final ModelHolder modelHolder;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final String linkBaseUrl;
    private final boolean enabled;
    private final List<String> defaultRoles;
    private final Set<String> allowedDomains;
    private final LoginThrottle throttle = new LoginThrottle(Clock.systemUTC(), SIGNUP_MAX_PER_EMAIL, SIGNUP_MAX_PER_IP);
    private final AtomicBoolean credentialInsertWarned = new AtomicBoolean();

    @Autowired
    public SignupController(
            DataSource dataSource,
            CapabilityRegistry capabilityRegistry,
            CapabilityDispatcher capabilityDispatcher,
            ModelHolder modelHolder,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.password-reset.link-base-url:}") String linkBaseUrl,
            @Value("${npdev.auth.signup.enabled:false}") boolean enabled,
            @Value("${npdev.auth.signup.default-roles:USER}") String defaultRoles,
            @Value("${npdev.auth.signup.allowed-domains:}") String allowedDomains
    ) {
        this.dataSource = dataSource;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityDispatcher = capabilityDispatcher;
        this.modelHolder = modelHolder;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.linkBaseUrl = linkBaseUrl == null ? "" : linkBaseUrl;
        this.enabled = enabled;
        this.defaultRoles = parseRoles(defaultRoles);
        this.allowedDomains = parseDomains(allowedDomains);
        if (enabled) {
            for (String role : this.defaultRoles) {
                if (FORBIDDEN_DEFAULT_ROLES.contains(role.toUpperCase(Locale.ROOT))) {
                    throw new IllegalStateException("npdev.auth.signup.default-roles must not contain '" + role
                            + "': self-signup would hand a privileged role to anyone with an e-mail address. "
                            + "Use an unprivileged role such as USER.");
                }
            }
            if (this.defaultRoles.isEmpty()) {
                LOG.warning("npdev.auth.signup.default-roles is empty: self-signup accounts will have no roles.");
            }
        }
    }

    public record SignupRequest(String email, String displayName) {
    }

    @PostMapping({"/api/auth/signup", "/api/v1/auth/signup"})
    public ResponseEntity<Map<String, Object>> signup(@RequestBody SignupRequest request, HttpServletRequest httpRequest) {
        if (!enabled) {
            return ResponseEntity.status(404).body(Map.of("error", "signup_disabled"));
        }
        String tenantId = DefaultTenant.id();
        String email = normalizeEmail(request == null ? null : request.email());
        // Bound BEFORE it is used as a throttle key or bound into SQL.
        if (email == null) {
            return ResponseEntity.ok(GENERIC_RESPONSE);
        }
        String clientIp = clientIp(httpRequest);
        if (throttle.isLocked(tenantId, email, clientIp)) {
            return ResponseEntity.ok(GENERIC_RESPONSE);
        }
        throttle.recordFailure(tenantId, email, clientIp);

        if (!capabilityRegistry.has("mail") || !domainAllowed(email)) {
            return ResponseEntity.ok(GENERIC_RESPONSE);
        }
        CompiledModel model = modelHolder.get();
        Optional<IdentityPackTableNames> resolved = IdentityPackTableNames.tryResolve(model);
        String tokensTable = OneTimeTokens.tableName(model);
        if (resolved.isEmpty() || tokensTable == null) {
            LOG.log(Level.WARNING, "Signup: identity pack (with PasswordResetToken) not composed for this app");
            return ResponseEntity.ok(GENERIC_RESPONSE);
        }
        IdentityPackTableNames ids = resolved.get();
        String displayName = displayNameOf(request.displayName(), email);

        String rawToken = null;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                rawToken = provisionOrRefresh(connection, model, ids, tokensTable, tenantId, email, displayName);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                rawToken = null;
                warnOnce(failure);
            }
        } catch (Exception failure) {
            rawToken = null;
            LOG.log(Level.WARNING, "Signup failed", failure);
        }
        if (rawToken != null) {
            sendMail(email, rawToken);
        }
        return ResponseEntity.ok(GENERIC_RESPONSE);
    }

    /** Returns the raw token to mail, or null when this address must not receive one. */
    private String provisionOrRefresh(Connection connection, CompiledModel model, IdentityPackTableNames ids,
                                      String tokensTable, String tenantId, String email, String displayName)
            throws Exception {
        UUID existingId = null;
        boolean eligible = false;
        String userSql = SqlIdentifierSupport.sqlWithIdentifiers(
                "SELECT id, email, active, last_login_at FROM {0} WHERE username = ? AND tenant_id = ?",
                ids.usersTable());
        try (PreparedStatement ps = connection.prepareStatement(userSql)) {
            ps.setString(1, email);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    existingId = UUID.fromString(String.valueOf(rs.getObject("id")));
                    String onFile = rs.getString("email");
                    eligible = rs.getBoolean("active")
                            && rs.getTimestamp("last_login_at") == null
                            && onFile != null && onFile.trim().equalsIgnoreCase(email);
                }
            }
        }
        if (existingId != null) {
            // Already registered: only a never-used, un-linked account that this very mailbox owns gets a fresh link.
            if (!eligible || hasExternalIdentity(connection, model, tenantId, existingId)
                    || !isPendingSignup(connection, tokensTable, tenantId, existingId)) {
                return null;
            }
            String raw = OneTimeTokens.newRawToken();
            OneTimeTokens.insert(connection, tokensTable, existingId, OneTimeTokens.sha256Hex(raw),
                    Instant.now().plus(Duration.ofHours(TOKEN_EXPIRY_HOURS)), tenantId);
            return raw;
        }

        UUID userId = UUID.randomUUID();
        IdentityProvisioning.insertIdentityUser(connection, ids, userId, email, displayName, email, tenantId);
        for (String role : defaultRoles) {
            UUID roleId = IdentityProvisioning.findOrCreateRole(connection, ids, tenantId, role, "Self-signup");
            IdentityProvisioning.insertUserRole(connection, ids, userId, roleId, tenantId);
        }
        // An unusable random password: the credential row must exist so the reset-confirm UPDATE matches it.
        IdentityProvisioning.insertCredential(
                connection, credentialTable, credentialUserIdColumn, credentialPasswordColumn,
                UUID.randomUUID(), userId, OneTimeTokens.newRawToken(), tenantId, null, null, null, null);
        String raw = OneTimeTokens.newRawToken();
        OneTimeTokens.insert(connection, tokensTable, userId, OneTimeTokens.sha256Hex(raw),
                Instant.now().plus(Duration.ofHours(TOKEN_EXPIRY_HOURS)), tenantId);
        return raw;
    }

    /**
     * "Never finished creating the account": the user has one-time-token rows (signup or invitation made
     * them) and none was ever used. {@code last_login_at} alone cannot decide this -- a password sign-in does
     * not stamp it -- so an ordinary account that has no token history, or has already used one (password
     * reset included), is never a resend target.
     */
    private static boolean isPendingSignup(Connection connection, String tokensTable, String tenantId, UUID userId)
            throws Exception {
        String sql = SqlIdentifierSupport.sqlWithIdentifiers(
                "SELECT COUNT(*), COUNT(used_at) FROM {0} WHERE user_id = ? AND tenant_id = ?", tokensTable);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0 && rs.getInt(2) == 0;
            }
        }
    }

    private static boolean hasExternalIdentity(Connection connection, CompiledModel model, String tenantId, UUID userId)
            throws Exception {
        String table = model.findConcept("identity::ExternalIdentity")
                .map(CompiledConcept::getTableName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(null);
        if (table == null) {
            return false;
        }
        String sql = SqlIdentifierSupport.sqlWithIdentifiers(
                "SELECT COUNT(*) FROM {0} WHERE user_id = ? AND tenant_id = ?", table);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setObject(1, userId);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private void sendMail(String email, String rawToken) {
        try {
            String body = linkBaseUrl.isBlank()
                    ? "Finish creating your account with this code: " + rawToken
                            + "\nOpen the reset-password page, paste the code and choose a password."
                            + "\nThis code expires in " + TOKEN_EXPIRY_HOURS + " hours and can be used once."
                    : "Finish creating your account: " + linkBaseUrl + rawToken + "&signup=1"
                            + "\nThis link expires in " + TOKEN_EXPIRY_HOURS + " hours and can be used once.";
            CapabilityCall call = new CapabilityCall(
                    "mail", "EmailCapability", capabilityRegistry.debugDefaultAdapterId("mail"), "send",
                    List.of(email, "Finish creating your account", body));
            CapabilityResult result = capabilityDispatcher.invoke(call, Map.of());
            if (!result.ok()) {
                LOG.warning("Signup email dispatch failed: " + result.error());
            }
        } catch (RuntimeException failure) {
            LOG.log(Level.WARNING, "Signup email dispatch failed", failure);
        }
    }

    /** The credential insert fails on apps whose credential table has extra NOT NULL link columns; say so once. */
    private void warnOnce(Exception failure) {
        if (credentialInsertWarned.compareAndSet(false, true)) {
            LOG.log(Level.WARNING, "Signup could not provision an account (apps whose credential table has extra "
                    + "required columns cannot use self-signup; further occurrences are not logged)", failure);
        }
    }

    private boolean domainAllowed(String email) {
        if (allowedDomains.isEmpty()) {
            return true;
        }
        return allowedDomains.contains(email.substring(email.lastIndexOf('@') + 1));
    }

    private static String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_EMAIL_LENGTH) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return EMAIL_SHAPE.matcher(lower).matches() ? lower : null;
    }

    private static String displayNameOf(String raw, String email) {
        String cleaned = raw == null ? "" : raw.replaceAll("\\p{Cntrl}", " ").trim();
        if (cleaned.isEmpty()) {
            cleaned = email.substring(0, email.indexOf('@'));
        }
        return cleaned.length() > MAX_DISPLAY_NAME_LENGTH ? cleaned.substring(0, MAX_DISPLAY_NAME_LENGTH) : cleaned;
    }

    private static List<String> parseRoles(String raw) {
        List<String> roles = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String role = part.trim();
                if (!role.isEmpty() && !roles.contains(role)) {
                    roles.add(role);
                }
            }
        }
        return List.copyOf(roles);
    }

    private static Set<String> parseDomains(String raw) {
        Set<String> domains = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String domain = part.trim().toLowerCase(Locale.ROOT);
                if (domain.startsWith("@")) {
                    domain = domain.substring(1);
                }
                if (!domain.isEmpty()) {
                    domains.add(domain);
                }
            }
        }
        return Set.copyOf(domains);
    }

    /** Best-effort client IP for rate limiting (X-Forwarded-For first hop, else socket peer). */
    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
