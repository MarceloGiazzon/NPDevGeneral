package com.finalexec.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mints an RS256 JWT -- the mint-side counterpart to {@link JwtBearerAuthFilter}, which only
 * validates. JDK-only (java.security.Signature), matching the same approach the platform's own
 * test signer already uses; no JWT library dependency needed for either side.
 */
public final class JwtSigner {
    private final ObjectMapper objectMapper;
    private final PrivateKey privateKey;
    private final String issuer;
    private final String audience;
    private final long expirySeconds;

    public JwtSigner(ObjectMapper objectMapper, PrivateKey privateKey, String issuer, String audience, long expirySeconds) {
        this.objectMapper = objectMapper;
        this.privateKey = privateKey;
        this.issuer = issuer;
        this.audience = audience;
        this.expirySeconds = expirySeconds;
    }

    public static PrivateKey loadPrivateKey(String pem) {
        try {
            String normalized = pem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid JWT private key", exception);
        }
    }

    public String sign(String tenantId, String actorId, Iterable<String> roles) {
        return sign(tenantId, actorId, roles, 0);
    }

    /**
     * LNCH-4: {@code tokenVersion} is stamped into the {@code tv} claim, checked on every request
     * against the identity pack's live {@code identity_users.token_version}
     * ({@code com.npdev.runtime.support.IdentityRoleLookup#tokenVersion}). Bumping the stored version
     * (password reset, an explicit revoke) invalidates every token minted with an older version,
     * immediately, without a denylist.
     */
    public String sign(String tenantId, String actorId, Iterable<String> roles, int tokenVersion) {
        try {
            long nowEpochSeconds = System.currentTimeMillis() / 1000L;
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "RS256");
            header.put("typ", "JWT");

            Map<String, Object> claims = new LinkedHashMap<>();
            if (issuer != null && !issuer.isBlank()) {
                claims.put("iss", issuer);
            }
            if (audience != null && !audience.isBlank()) {
                claims.put("aud", audience);
            }
            claims.put("iat", nowEpochSeconds);
            claims.put("exp", nowEpochSeconds + expirySeconds);
            claims.put("sub", actorId);
            claims.put("tenant_id", tenantId);
            claims.put("roles", roles);
            claims.put("tv", tokenVersion);

            String headerSegment = base64UrlEncode(objectMapper.writeValueAsBytes(header));
            String payloadSegment = base64UrlEncode(objectMapper.writeValueAsBytes(claims));
            String signingInput = headerSegment + "." + payloadSegment;

            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            String signatureSegment = base64UrlEncode(signature.sign());

            return signingInput + "." + signatureSegment;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign JWT", exception);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
