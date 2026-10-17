package com.finalexec.auth.mfa;

import com.finalexec.auth.PasswordHasher;
import com.finalexec.config.DefaultTenant;
import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SEC-11: a signed-in user's own authenticator-app (TOTP) enrollment. Endpoints:
 * <ul>
 *   <li>{@code GET  /api/auth/mfa/status}</li>
 *   <li>{@code POST /api/auth/mfa/enroll/start}  {password} -> {secret, otpauthUri}</li>
 *   <li>{@code POST /api/auth/mfa/enroll/confirm} {code} -> {enabled, recoveryCodes} (the ONLY time
 *       the plain recovery codes leave the server)</li>
 *   <li>{@code POST /api/auth/mfa/disable} {password, code} (TOTP or recovery code)</li>
 *   <li>{@code POST /api/auth/mfa/recovery-codes} {code} -> fresh list</li>
 * </ul>
 * Starting and disabling require the CURRENT password, so a stolen session can neither enroll its
 * own authenticator (locking the owner out) nor silently remove MFA. Not in
 * {@code JwtBearerAuthFilter}'s exemptions: an authenticated caller is required. When the identity
 * pack has no {@code identity::UserMfa} (older private copy) every endpoint answers 503
 * {@code mfa_not_available}. Neither the secret nor recovery codes are ever logged.
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class MfaController {

    private static final Logger LOG = Logger.getLogger(MfaController.class.getName());
    private static final String CLAIMS_ATTRIBUTE = "npdev.auth.claims";
    private static final int MAX_FAILURES = 10;
    private static final long FAILURE_WINDOW_MILLIS = 15 * 60 * 1000L;

    private final DataSource dataSource;
    private final AuthenticatedContextResolver authenticatedContextResolver;
    private final ModelHolder modelHolder;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final String issuer;
    /** Failed password/code attempts per tenant+user (an authenticated caller, so the key space is bounded). */
    private final Map<String, long[]> failures = new ConcurrentHashMap<>();

    @Autowired
    public MfaController(
            DataSource dataSource,
            AuthenticatedContextResolver authenticatedContextResolver,
            ModelHolder modelHolder,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.mfa.issuer:NPDev}") String issuer) {
        this.dataSource = dataSource;
        this.authenticatedContextResolver = authenticatedContextResolver;
        this.modelHolder = modelHolder;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.issuer = issuer == null || issuer.isBlank() ? "NPDev" : issuer;
    }

    public record PasswordRequest(String password) {
    }

    public record CodeRequest(String code) {
    }

    public record DisableRequest(String password, String code) {
    }

    /** The authenticated caller resolved to a user row, plus the MFA table. */
    private record Caller(String tenantId, String username, UUID userId, String mfaTable, Connection connection) {
    }

    @GetMapping("/api/auth/mfa/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest http) {
        return withCaller(http, caller -> {
            Optional<MfaStore.Row> row = MfaStore.find(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId());
            boolean enabled = row.isPresent() && row.get().enabled();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("available", true);
            body.put("enabled", enabled);
            body.put("recoveryCodesLeft", enabled ? row.get().recoveryCodeHashes().size() : 0);
            return ResponseEntity.ok(body);
        });
    }

    @PostMapping("/api/auth/mfa/enroll/start")
    public ResponseEntity<Map<String, Object>> enrollStart(@RequestBody(required = false) PasswordRequest request,
                                                            HttpServletRequest http) {
        return withCaller(http, caller -> {
            ResponseEntity<Map<String, Object>> bad = checkPassword(caller, request == null ? null : request.password());
            if (bad != null) {
                return bad;
            }
            Optional<MfaStore.Row> row = MfaStore.find(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId());
            if (row.isPresent() && row.get().enabled()) {
                return ResponseEntity.status(409).body(Map.of("error", "mfa_already_enabled"));
            }
            String secret = Totp.newSecretBase32();
            MfaStore.upsertPending(caller.connection(), caller.mfaTable(), caller.userId(), caller.tenantId(),
                    MfaSecretCodec.encode(secret));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("secret", secret);
            body.put("otpauthUri", Totp.otpauthUri(issuer, caller.username(), secret));
            return ResponseEntity.ok(body);
        });
    }

    @PostMapping("/api/auth/mfa/enroll/confirm")
    public ResponseEntity<Map<String, Object>> enrollConfirm(@RequestBody(required = false) CodeRequest request,
                                                              HttpServletRequest http) {
        return withCaller(http, caller -> {
            Optional<MfaStore.Row> row = MfaStore.find(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId());
            if (row.isEmpty() || row.get().enabled()) {
                return ResponseEntity.status(409).body(Map.of("error", "mfa_enroll_not_started"));
            }
            if (isLockedOut(caller)) {
                return tooManyAttempts();
            }
            long step = Totp.verify(MfaSecretCodec.decode(row.get().secretStored()),
                    request == null ? null : request.code(), Instant.now(), -1L);
            if (step < 0) {
                recordFailure(caller);
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_code"));
            }
            List<String> codes = Totp.newRecoveryCodes();
            if (!MfaStore.enable(caller.connection(), caller.mfaTable(), caller.userId(), caller.tenantId(), step,
                    codes.stream().map(Totp::recoveryCodeHash).toList())) {
                return ResponseEntity.status(409).body(Map.of("error", "mfa_enroll_not_started"));
            }
            clearFailures(caller);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("enabled", true);
            body.put("recoveryCodes", codes);
            return ResponseEntity.ok(body);
        });
    }

    @PostMapping("/api/auth/mfa/disable")
    public ResponseEntity<Map<String, Object>> disable(@RequestBody(required = false) DisableRequest request,
                                                        HttpServletRequest http) {
        return withCaller(http, caller -> {
            ResponseEntity<Map<String, Object>> bad = checkPassword(caller, request == null ? null : request.password());
            if (bad != null) {
                return bad;
            }
            Optional<MfaStore.Row> row = MfaStore.find(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId());
            if (row.isEmpty() || !row.get().enabled()) {
                return ResponseEntity.status(409).body(Map.of("error", "mfa_not_enabled"));
            }
            if (!acceptSecondFactor(caller, row.get(), request == null ? null : request.code())) {
                recordFailure(caller);
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_code"));
            }
            MfaStore.delete(caller.connection(), caller.mfaTable(), caller.userId(), caller.tenantId());
            clearFailures(caller);
            return ResponseEntity.ok(Map.of("enabled", false));
        });
    }

    @PostMapping("/api/auth/mfa/recovery-codes")
    public ResponseEntity<Map<String, Object>> regenerateRecoveryCodes(
            @RequestBody(required = false) CodeRequest request, HttpServletRequest http) {
        return withCaller(http, caller -> {
            Optional<MfaStore.Row> row = MfaStore.find(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId());
            if (row.isEmpty() || !row.get().enabled()) {
                return ResponseEntity.status(409).body(Map.of("error", "mfa_not_enabled"));
            }
            if (isLockedOut(caller)) {
                return tooManyAttempts();
            }
            // TOTP only here: a recovery code must not be able to mint a fresh set of recovery codes.
            long step = Totp.verify(MfaSecretCodec.decode(row.get().secretStored()),
                    request == null ? null : request.code(), Instant.now(), row.get().lastUsedStep());
            if (step < 0 || !MfaStore.setLastUsedStep(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId(), step)) {
                recordFailure(caller);
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_code"));
            }
            List<String> codes = Totp.newRecoveryCodes();
            MfaStore.replaceRecoveryCodes(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId(), codes.stream().map(Totp::recoveryCodeHash).toList());
            clearFailures(caller);
            return ResponseEntity.ok(Map.of("recoveryCodes", codes));
        });
    }

    // ------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface CallerAction {
        ResponseEntity<Map<String, Object>> run(Caller caller) throws Exception;
    }

    private ResponseEntity<Map<String, Object>> withCaller(HttpServletRequest http, CallerAction action) {
        ExecutionContext context = currentContext(http);
        String username = context == null ? null : context.actorId();
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
        }
        String tenantId = DefaultTenant.orDefault(context.tenantId());
        String mfaTable = MfaTables.userMfaTable(modelHolder.get());
        if (mfaTable == null) {
            return ResponseEntity.status(503).body(Map.of("error", "mfa_not_available"));
        }
        Optional<IdentityPackTableNames> identityTables = IdentityPackTableNames.tryResolve(modelHolder.get());
        if (identityTables.isEmpty()) {
            return ResponseEntity.status(503).body(Map.of("error", "identity_pack_not_composed"));
        }
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = findActiveUserId(connection, identityTables.get().usersTable(), username, tenantId);
            if (userId == null) {
                return ResponseEntity.status(401).body(Map.of("error", "unauthenticated"));
            }
            return action.run(new Caller(tenantId, username, userId, mfaTable, connection));
        } catch (Exception exception) {
            LOG.log(Level.WARNING, "MFA request failed", exception);
            return ResponseEntity.status(500).body(Map.of("error", "mfa_failed"));
        }
    }

    private static UUID findActiveUserId(Connection connection, String usersTable, String username, String tenantId)
            throws Exception {
        String sql = SqlIdentifierSupport.sqlWithIdentifiers(
                "SELECT id, active FROM {0} WHERE username = ? AND tenant_id = ?", usersTable);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("active")) {
                    return null;
                }
                return UUID.fromString(String.valueOf(rs.getObject("id")));
            }
        }
    }

    /** null = password OK; otherwise the response to return. */
    private ResponseEntity<Map<String, Object>> checkPassword(Caller caller, String password) throws Exception {
        if (isLockedOut(caller)) {
            return tooManyAttempts();
        }
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_password"));
        }
        String sql = SqlIdentifierSupport.sqlWithIdentifiers(
                "SELECT {0} FROM {1} WHERE {2} = ? AND tenant_id = ?",
                credentialPasswordColumn, credentialTable, credentialUserIdColumn);
        String storedHash;
        try (PreparedStatement ps = caller.connection().prepareStatement(sql)) {
            ps.setObject(1, caller.userId());
            ps.setString(2, caller.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(409).body(Map.of("error", "no_password_credential"));
                }
                storedHash = rs.getString(1);
            }
        }
        if (!PasswordHasher.verify(password, storedHash)) {
            recordFailure(caller);
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_password"));
        }
        return null;
    }

    /** A TOTP (replay-guarded) or, failing that, a one-time recovery code. */
    private boolean acceptSecondFactor(Caller caller, MfaStore.Row row, String code) throws Exception {
        if (code == null || code.isBlank()) {
            return false;
        }
        long step = -1L;
        try {
            step = Totp.verify(MfaSecretCodec.decode(row.secretStored()), code, Instant.now(), row.lastUsedStep());
        } catch (RuntimeException unreadableSecret) {
            // e.g. the secret was stored encrypted and the key is gone: only a recovery code can work.
            LOG.log(Level.SEVERE, "MFA secret unreadable; only a recovery code can be accepted", unreadableSecret);
        }
        if (step >= 0) {
            return MfaStore.setLastUsedStep(caller.connection(), caller.mfaTable(), caller.userId(),
                    caller.tenantId(), step);
        }
        return MfaStore.consumeRecoveryCode(caller.connection(), caller.mfaTable(), caller.userId(),
                caller.tenantId(), Totp.recoveryCodeHash(code));
    }

    private static String key(Caller caller) {
        return caller.tenantId() + "\u0000" + caller.username();
    }

    private boolean isLockedOut(Caller caller) {
        long[] state = failures.get(key(caller));
        return state != null && System.currentTimeMillis() - state[1] < FAILURE_WINDOW_MILLIS
                && state[0] >= MAX_FAILURES;
    }

    private void recordFailure(Caller caller) {
        long now = System.currentTimeMillis();
        failures.compute(key(caller), (k, state) ->
                state == null || now - state[1] >= FAILURE_WINDOW_MILLIS
                        ? new long[]{1, now} : new long[]{state[0] + 1, state[1]});
    }

    private void clearFailures(Caller caller) {
        failures.remove(key(caller));
    }

    private static ResponseEntity<Map<String, Object>> tooManyAttempts() {
        return ResponseEntity.status(429).body(Map.of("error", "too_many_attempts"));
    }

    /** Same claims-attribute lookup ChangePasswordController uses (see its javadoc). */
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
