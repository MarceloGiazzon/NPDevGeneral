package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.runtime.support.IdentityRoleLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Real login for the built-in identity pack (identity_users/identity_roles/identity_user_roles).
 * Bridges an app's own credential-holding concept (e.g. WmsOffice's {@code Usuario}, bonded to
 * {@code identity::User} via a {@code userId} reference) to the platform's already-working JWT
 * validation ({@link JwtBearerAuthFilter}) and role-based permission enforcement
 * ({@link IdentityAwareContextResolver}/{@link IdentityRoleLookup}) -- neither of which had any
 * mint-side counterpart before this class.
 *
 * <p>The credential table/column names are configurable (not hardcoded to WmsOffice's "Usuario")
 * so this is a genuinely reusable recipe: any app bonding its own identity table to
 * {@code identity::User} the same way gets a working login endpoint by setting 4 properties.</p>
 */
@RestController
@ConditionalOnProperty(name = "npdev.auth.mode", havingValue = "jwt")
public class LoginController {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final String credentialTable;
    private final String credentialUserIdColumn;
    private final String credentialPasswordColumn;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long expirySeconds;

    public LoginController(
            DataSource dataSource,
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            @Value("${npdev.auth.login.credential-table:usuarios}") String credentialTable,
            @Value("${npdev.auth.login.credential-user-id-column:user_id}") String credentialUserIdColumn,
            @Value("${npdev.auth.login.credential-password-column:senha_hash}") String credentialPasswordColumn,
            @Value("${npdev.auth.jwt.private-key-path}") String privateKeyPath,
            @Value("${npdev.auth.jwt.issuer:}") String issuer,
            @Value("${npdev.auth.jwt.audience:}") String audience,
            @Value("${npdev.auth.jwt.expiry-seconds:28800}") long expirySeconds
    ) throws Exception {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
        this.credentialTable = credentialTable;
        this.credentialUserIdColumn = credentialUserIdColumn;
        this.credentialPasswordColumn = credentialPasswordColumn;
        this.privateKey = JwtSigner.loadPrivateKey(readKeyFile(resourceLoader, privateKeyPath));
        this.issuer = issuer;
        this.audience = audience;
        this.expirySeconds = expirySeconds;
    }

    public record LoginRequest(String username, String password, String tenantId) {
    }

    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        String tenantId = (request.tenantId() == null || request.tenantId().isBlank()) ? "dev" : request.tenantId().trim();
        String username = request.username() == null ? null : request.username().trim();
        String password = request.password();

        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return unauthorized();
        }

        try (Connection connection = dataSource.getConnection()) {
            String userSql = "SELECT id, active, token_version FROM identity_users WHERE username = ? AND tenant_id = ?";
            String userId;
            int tokenVersion;
            try (PreparedStatement ps = connection.prepareStatement(userSql)) {
                ps.setString(1, username);
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean("active")) {
                        return unauthorized();
                    }
                    userId = rs.getString("id");
                    tokenVersion = rs.getInt("token_version");
                    if (rs.wasNull()) {
                        tokenVersion = 0;
                    }
                }
            }

            String credentialSql = "SELECT " + credentialPasswordColumn + " FROM " + credentialTable
                    + " WHERE " + credentialUserIdColumn + " = ? AND tenant_id = ?";
            String storedHash;
            try (PreparedStatement ps = connection.prepareStatement(credentialSql)) {
                ps.setObject(1, java.util.UUID.fromString(userId));
                ps.setString(2, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return unauthorized();
                    }
                    storedHash = rs.getString(1);
                }
            }

            if (!PasswordHasher.verify(password, storedHash)) {
                return unauthorized();
            }

            Set<String> roles = IdentityRoleLookup.rolesFor(dataSource, tenantId, username);
            JwtSigner signer = new JwtSigner(objectMapper, privateKey, issuer, audience, expirySeconds);
            String token = signer.sign(tenantId, username, roles, tokenVersion);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("token", token);
            body.put("tokenType", "Bearer");
            body.put("expiresInSeconds", expirySeconds);
            body.put("roles", roles);
            return ResponseEntity.ok(body);
        } catch (Exception exception) {
            return unauthorized();
        }
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
    }

    private static String readKeyFile(ResourceLoader resourceLoader, String path) throws Exception {
        if (path.startsWith("classpath:")) {
            Resource resource = resourceLoader.getResource(path);
            try (var inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return java.nio.file.Files.readString(java.nio.file.Path.of(path), StandardCharsets.UTF_8);
    }
}
