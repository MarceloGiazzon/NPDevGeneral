package com.finalexec.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.runtime.config.RuntimeApiKeyAuthFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class JwtBearerAuthFilter extends OncePerRequestFilter {
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final long CLOCK_SKEW_SECONDS = 60L;

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final String issuer;
    private final String audience;
    private final String publicKeyPath;

    public JwtBearerAuthFilter(
            ObjectMapper objectMapper,
            ResourceLoader resourceLoader,
            String issuer,
            String audience,
            String publicKeyPath
    ) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.issuer = normalize(issuer);
        this.audience = normalize(audience);
        this.publicKeyPath = normalize(publicKeyPath);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request == null ? null : request.getRequestURI();
        if (uri == null) {
            return true;
        }
        // The login endpoint issues the bearer token this filter validates on every other
        // request -- it must itself be reachable without one, the same chicken-and-egg exemption
        // every JWT-based login flow needs.
        if (uri.equals("/api/auth/login") || uri.equals("/api/v1/auth/login")) {
            return true;
        }
        // Self-disabling: BootstrapAdminController only succeeds while identity_users is empty for
        // the target tenant, so exempting it here doesn't widen the attack surface beyond a one-time
        // first-admin creation -- same chicken-and-egg reasoning as the login exemption above.
        if (uri.equals("/api/auth/bootstrap-admin") || uri.equals("/api/v1/auth/bootstrap-admin")) {
            return true;
        }
        // An earlier filter in the chain (SuperUserCredentialAuthFilter, order -110) may already
        // have authenticated this request via a completely independent credential (the ControlPanel's
        // X-Super-User-Key, unrelated to business auth.mode). This filter must not clobber that with
        // its own "missing_bearer_token" rejection just because no JWT was also presented.
        if (request.getAttribute(RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE) != null) {
            return true;
        }
        return !(uri.startsWith("/api/") || uri.startsWith("/api/v1/"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = normalize(request.getHeader("Authorization"));
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response, "missing_bearer_token");
            return;
        }

        String token = normalize(authorization.substring(7));
        if (token == null) {
            unauthorized(response, "missing_bearer_token");
            return;
        }

        try {
            Map<String, Object> claims = validateAndExtractClaims(token);
            request.setAttribute(RuntimeApiKeyAuthFilter.CLAIMS_ATTRIBUTE, claims);
            filterChain.doFilter(request, response);
        } catch (JwtValidationException ex) {
            unauthorized(response, ex.errorCode());
        }
    }

    private Map<String, Object> validateAndExtractClaims(String token) {
        String[] segments = token.split("\\.");
        if (segments.length != 3) {
            throw new JwtValidationException("invalid_jwt_structure");
        }

        Map<String, Object> header = parseSegment(segments[0], "invalid_jwt_header");
        Map<String, Object> claims = parseSegment(segments[1], "invalid_jwt_payload");

        String alg = normalize(stringValue(header.get("alg")));
        if (!"RS256".equalsIgnoreCase(alg)) {
            throw new JwtValidationException("unsupported_jwt_algorithm");
        }

        if (publicKeyPath == null) {
            throw new JwtValidationException("jwt_public_key_not_configured");
        }

        verifySignature(segments[0], segments[1], segments[2]);
        validateIssuer(claims);
        validateAudience(claims);
        validateTimeClaims(claims);

        LinkedHashMap<String, Object> normalizedClaims = new LinkedHashMap<>(claims);
        normalizeRoles(normalizedClaims);
        return Map.copyOf(normalizedClaims);
    }

    private Map<String, Object> parseSegment(String segment, String errorCode) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(segment);
            return objectMapper.readValue(decoded, MAP_TYPE);
        } catch (Exception ex) {
            throw new JwtValidationException(errorCode, ex);
        }
    }

    private void verifySignature(String headerSegment, String payloadSegment, String signatureSegment) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(loadPublicKey());
            verifier.update((headerSegment + "." + payloadSegment).getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = Base64.getUrlDecoder().decode(signatureSegment);
            if (!verifier.verify(signatureBytes)) {
                throw new JwtValidationException("invalid_jwt_signature");
            }
        } catch (JwtValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new JwtValidationException("invalid_jwt_signature", ex);
        }
    }

    private PublicKey loadPublicKey() {
        try {
            String pem;
            if (publicKeyPath.startsWith("classpath:")) {
                Resource resource = resourceLoader.getResource(publicKeyPath);
                if (!resource.exists()) {
                    throw new JwtValidationException("jwt_public_key_not_found");
                }
                try (var inputStream = resource.getInputStream()) {
                    pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                Path path = Path.of(publicKeyPath);
                if (!Files.exists(path)) {
                    throw new JwtValidationException("jwt_public_key_not_found");
                }
                pem = Files.readString(path, StandardCharsets.UTF_8);
            }

            String normalizedPem = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(normalizedPem);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (JwtValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new JwtValidationException("jwt_public_key_invalid", ex);
        }
    }

    private void validateIssuer(Map<String, Object> claims) {
        if (issuer == null) {
            return;
        }
        String actualIssuer = normalize(stringValue(claims.get("iss")));
        if (!issuer.equals(actualIssuer)) {
            throw new JwtValidationException("invalid_jwt_issuer");
        }
    }

    private void validateAudience(Map<String, Object> claims) {
        if (audience == null) {
            return;
        }
        Object rawAudience = claims.get("aud");
        if (rawAudience instanceof String audText && audience.equals(audText.trim())) {
            return;
        }
        if (rawAudience instanceof Collection<?> values) {
            for (Object value : values) {
                if (audience.equals(normalize(stringValue(value)))) {
                    return;
                }
            }
        }
        throw new JwtValidationException("invalid_jwt_audience");
    }

    private void validateTimeClaims(Map<String, Object> claims) {
        long nowEpochSeconds = System.currentTimeMillis() / 1000L;
        long issuedAt = requireNumericDate(claims, "iat", "missing_jwt_iat", "invalid_jwt_iat");
        long expiresAt = requireNumericDate(claims, "exp", "missing_jwt_exp", "invalid_jwt_exp");
        Long notBefore = optionalNumericDate(claims, "nbf", "invalid_jwt_nbf");

        if (issuedAt > nowEpochSeconds + CLOCK_SKEW_SECONDS) {
            throw new JwtValidationException("invalid_jwt_iat");
        }
        if (expiresAt <= issuedAt) {
            throw new JwtValidationException("invalid_jwt_exp");
        }
        if (expiresAt <= nowEpochSeconds - CLOCK_SKEW_SECONDS) {
            throw new JwtValidationException("expired_jwt");
        }
        if (notBefore != null && notBefore > nowEpochSeconds + CLOCK_SKEW_SECONDS) {
            throw new JwtValidationException("jwt_not_yet_valid");
        }
    }

    private long requireNumericDate(
            Map<String, Object> claims,
            String claimName,
            String missingErrorCode,
            String invalidErrorCode
    ) {
        Object rawValue = claims.get(claimName);
        if (rawValue == null) {
            throw new JwtValidationException(missingErrorCode);
        }
        return toEpochSeconds(rawValue, invalidErrorCode);
    }

    private Long optionalNumericDate(Map<String, Object> claims, String claimName, String invalidErrorCode) {
        Object rawValue = claims.get(claimName);
        if (rawValue == null) {
            return null;
        }
        return toEpochSeconds(rawValue, invalidErrorCode);
    }

    private long toEpochSeconds(Object rawValue, String invalidErrorCode) {
        if (rawValue instanceof Number number) {
            return number.longValue();
        }
        String value = normalize(stringValue(rawValue));
        if (value == null) {
            throw new JwtValidationException(invalidErrorCode);
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new JwtValidationException(invalidErrorCode, exception);
        }
    }

    private void normalizeRoles(Map<String, Object> claims) {
        Object rawRoles = claims.get("roles");
        if (rawRoles == null) {
            rawRoles = claims.get("role");
        }
        if (rawRoles == null) {
            return;
        }

        Set<String> roles = new LinkedHashSet<>();
        if (rawRoles instanceof String text) {
            for (String token : text.split(",")) {
                String normalized = normalize(token);
                if (normalized != null) {
                    roles.add(normalized.toUpperCase(Locale.ROOT));
                }
            }
        } else if (rawRoles instanceof Collection<?> values) {
            for (Object value : values) {
                String normalized = normalize(stringValue(value));
                if (normalized != null) {
                    roles.add(normalized.toUpperCase(Locale.ROOT));
                }
            }
        } else {
            String normalized = normalize(stringValue(rawRoles));
            if (normalized != null) {
                roles.add(normalized.toUpperCase(Locale.ROOT));
            }
        }

        if (!roles.isEmpty()) {
            claims.put("roles", List.copyOf(roles));
        }
    }

    private static void unauthorized(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + code + "\"}");
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private static final class JwtValidationException extends RuntimeException {
        private final String errorCode;

        private JwtValidationException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        private JwtValidationException(String errorCode, Throwable cause) {
            super(errorCode, cause);
            this.errorCode = errorCode;
        }

        private String errorCode() {
            return errorCode;
        }
    }
}
