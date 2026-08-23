package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.CorsConfig;
import com.finalexec.config.DevCorsPreflightFilterConfig;
import com.finalexec.config.RequestResponseLoggingFilter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "npdev.scheduler.enabled=false",
                "spring.jpa.open-in-view=false",
                "npdev.auth.enabled=true",
                "npdev.auth.mode=jwt",
                "npdev.auth.jwt.issuer=https://issuer.npdev.test",
                "npdev.auth.jwt.audience=npdev-runtime-beta",
                "npdev.auth.jwt.public-key-path=classpath:npdev/security/test-jwt-public.pem"
        }
)
@ActiveProfiles({"test", "postgres", "external-beta"})
@Tag("integration")
class JwtAuthExternalBetaIT {
    // valid token
    // expired token
    // malformed token
    // missing token
    // wrong signature
    // missing claims
    // extra claims
    // authenticated request succeeds
    // unauthenticated request rejected
    // burst invalid token traffic should be handled without sensitive token logging
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static PrivateKey privateKey;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void loadPrivateKey() throws Exception {
        ClassPathResource resource = new ClassPathResource("npdev/security/test-jwt-private.pem");
        String pem = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String normalizedPem = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] privateKeyBytes = Base64.getDecoder().decode(normalizedPem);
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
    }

    @Test
    void request_without_token_returns_401() {
        var resp = rest.getForEntity("/api/admin/runtime/metadata", Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void request_with_invalid_token_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token");

        var resp = rest.exchange(
                "/api/admin/runtime/metadata",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void request_with_api_key_only_is_rejected_in_external_beta_mode() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", "dev-key");

        var resp = rest.exchange(
                "/api/admin/runtime/metadata",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void request_with_valid_admin_jwt_returns_200() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(signedJwt(
                Instant.now().minusSeconds(30),
                Instant.now().plusSeconds(300),
                Instant.now().minusSeconds(30)
        ));

        var resp = rest.exchange(
                "/api/admin/runtime/metadata",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("endpointVersion");
    }

    @Test
    void request_with_expired_jwt_returns_401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(signedJwt(
                Instant.now().minusSeconds(300),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(300)
        ));

        var resp = rest.exchange(
                "/api/admin/runtime/metadata",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void external_beta_does_not_register_dev_only_cors_or_body_logging() {
        assertThat(applicationContext.getBeansOfType(CorsConfig.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(DevCorsPreflightFilterConfig.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(RequestResponseLoggingFilter.class)).isEmpty();
    }

    @Test
    void external_beta_postgres_profile_realizes_the_schema() {
        // Renamed from external_beta_postgres_profile_applies_flyway_migrations: canonical-demo's own
        // db.definition.json declares engine: InMemory, so it has no real V1__ Flyway migration for
        // Flyway to apply -- flyway_schema_history genuinely stays empty by design (see
        // MissingTableCreationPass's javadoc). What this test actually needs to prove -- that forcing
        // Postgres on this profile produces a REAL, usable schema -- is what these two checks verify:
        // an internal platform table (created unconditionally in afterMigrate) and a manifest-declared
        // business table (created by MissingTableCreationPass, since there is no V1__ to do it).
        Integer publicationExecutionTables = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'npdev_publication_execution'
                """,
                Integer.class
        );
        Integer patientsTable = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name = 'patients'
                """,
                Integer.class
        );

        assertThat(publicationExecutionTables).isEqualTo(1);
        assertThat(patientsTable).isEqualTo(1);
    }

    @Test
    void external_beta_preflight_does_not_allow_null_origin() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Origin", "null");
        headers.add("Access-Control-Request-Method", "GET");

        var resp = rest.exchange(
                "/api/admin/runtime/metadata",
                HttpMethod.OPTIONS,
                new HttpEntity<>(headers),
                String.class
        );

        assertThat(resp.getHeaders().getFirst("Access-Control-Allow-Origin")).isNull();
    }

    private static String signedJwt(Instant issuedAt, Instant expiresAt, Instant notBefore) {
        try {
            Map<String, Object> header = Map.of(
                    "alg", "RS256",
                    "typ", "JWT"
            );
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("iss", "https://issuer.npdev.test");
            claims.put("aud", "npdev-runtime-beta");
            claims.put("sub", "beta-admin");
            claims.put("tenant_id", "beta-tenant");
            claims.put("roles", List.of("ADMIN"));
            claims.put("iat", issuedAt.getEpochSecond());
            claims.put("exp", expiresAt.getEpochSecond());
            claims.put("nbf", notBefore.getEpochSecond());

            String headerSegment = encodeSegment(header);
            String payloadSegment = encodeSegment(claims);
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update((headerSegment + "." + payloadSegment).getBytes(StandardCharsets.UTF_8));
            String signatureSegment = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
            return headerSegment + "." + payloadSegment + "." + signatureSegment;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign test JWT", exception);
        }
    }

    private static String encodeSegment(Map<String, Object> payload) throws Exception {
        byte[] json = OBJECT_MAPPER.writeValueAsBytes(payload);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }
}
