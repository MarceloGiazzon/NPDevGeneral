package com.npdev.adapters.idp.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.IdentityProvider;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

/**
 * SEC-8: a GENERIC OpenID Connect provider (Okta, Entra ID/Azure AD, Keycloak, Auth0, Google
 * Workspace, ...) behind the kernel {@link IdentityProvider} port. Generalizes idp-google: instead
 * of Google's /tokeninfo round trip, it validates the id_token LOCALLY against the issuer's JWKS --
 * RS256 only, signature + iss + aud (+ azp) + exp, with the endpoints discovered from
 * {@code <issuer>/.well-known/openid-configuration}.
 *
 * <p>Same failure contract as GoogleIdentityProvider: any network/parse/validation failure
 * resolves to {@link Optional#empty()}; an unverified email surfaces as emailVerified=false.
 * Discovery is lazy and cached: the constructor does no I/O, so an unreachable issuer never breaks
 * app boot (only the first sign-in attempt pays for it).</p>
 */
public final class OidcIdentityProvider implements IdentityProvider {

    private static final System.Logger LOG = System.getLogger(OidcIdentityProvider.class.getName());

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final long CLOCK_SKEW_SECONDS = 60L;

    /** Everything the operator configures for one provider. Callers (SEC-9) apply the defaults. */
    public record Settings(
            String providerId,          // URL path segment + stored linkage key, e.g. "okta" -- never rename
            String issuer,              // e.g. https://dev-123.okta.com/oauth2/default
            String clientId,
            String clientSecret,
            String scopes,              // default "openid email profile"
            String emailClaim,          // default "email"
            String nameClaim,           // default "name"
            String pictureClaim,        // default "picture"
            boolean assumeEmailVerified // default false; Entra ID omits email_verified -- operator opt-in only
    ) {
    }

    private final Settings settings;
    private final String issuerBase;
    private final String scopes;
    private final String emailClaim;
    private final String nameClaim;
    private final String pictureClaim;
    private final HttpClient httpClient;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile JsonNode discovery;                                  // lazily fetched, cached
    private final Map<String, PublicKey> keysByKid = new ConcurrentHashMap<>();

    public OidcIdentityProvider(Settings settings, HttpClient httpClient, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        requireText(settings.providerId(), "providerId");
        requireText(settings.issuer(), "issuer");
        requireText(settings.clientId(), "clientId");
        requireText(settings.clientSecret(), "clientSecret");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        String issuer = settings.issuer().trim();
        this.issuerBase = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        this.scopes = orDefault(settings.scopes(), "openid email profile");
        this.emailClaim = orDefault(settings.emailClaim(), "email");
        this.nameClaim = orDefault(settings.nameClaim(), "name");
        this.pictureClaim = orDefault(settings.pictureClaim(), "picture");
    }

    @Override
    public String providerId() {
        return settings.providerId();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        String endpoint = discoveredText("authorization_endpoint");
        if (endpoint == null) {
            // The port has no error channel here; the controller turns this into a 503.
            throw new IllegalStateException("OIDC discovery failed for issuer " + settings.issuer());
        }
        return endpoint + (endpoint.contains("?") ? "&" : "?")
                + "client_id=" + enc(settings.clientId())
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(scopes)
                + "&state=" + enc(state);
    }

    @Override
    public Optional<IdentityProviderClaims> resolveIdentity(String code, String redirectUri) {
        if (code == null || code.isBlank() || redirectUri == null) {
            return Optional.empty();
        }
        // Fail closed: an interrupted or failed exchange/verification leaves `resolved` empty (no identity).
        // The failure is logged, never silently folded into the same answer as a rejected token.
        Optional<IdentityProviderClaims> resolved = Optional.empty();
        try {
            resolved = resolveVerifiedIdentity(code, redirectUri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "OIDC identity resolution failed closed: " + e.getClass().getName());
        }
        return resolved;
    }

    private Optional<IdentityProviderClaims> resolveVerifiedIdentity(String code, String redirectUri)
            throws IOException, InterruptedException {
        String idToken = exchangeCode(code, redirectUri);
        if (idToken == null) {
            return Optional.empty();
        }
        JsonNode claims = verifyIdToken(idToken);                        // null on ANY failure
        if (claims == null) {
            return Optional.empty();
        }
        String subject = text(claims, "sub");
        if (subject == null) {
            return Optional.empty();
        }
        String email = text(claims, emailClaim);
        boolean verified = settings.assumeEmailVerified() || isTrue(claims.get("email_verified"));
        return Optional.of(new IdentityProviderClaims(subject, email, email != null && verified,
                text(claims, nameClaim), text(claims, pictureClaim)));
    }

    /** Package-private for the test: header.payload.signature -> claims, or null on any failure. */
    JsonNode verifyIdToken(String jwt) throws IOException, InterruptedException {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        JsonNode header = null;
        JsonNode claims = null;
        byte[] signature = null;
        try {
            header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            claims = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            signature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException | IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "OIDC id_token is not decodable: " + e.getClass().getName());
            header = null;                                               // malformed token: rejected below
        }
        if (header == null || signature == null || claims == null || !header.isObject() || !claims.isObject()) {
            return null;
        }
        if (!"RS256".equals(text(header, "alg"))) {
            return null;                                                 // never accept "none"/HS256
        }
        PublicKey key = keyFor(text(header, "kid"));                     // refetches the JWKS once on an unknown kid
        if (key == null) {
            return null;
        }
        boolean signatureValid = false;
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            signatureValid = verifier.verify(signature);
        } catch (java.security.GeneralSecurityException e) {
            LOG.log(System.Logger.Level.WARNING, "OIDC signature verification failed closed: " + e.getClass().getName());
        }
        if (!signatureValid) {
            return null;
        }
        // The DISCOVERED issuer, verbatim (a trailing slash matters to some IdPs).
        String expectedIssuer = Optional.ofNullable(discoveredText("issuer")).orElse(settings.issuer());
        if (!expectedIssuer.equals(text(claims, "iss"))) {
            return null;
        }
        if (!audienceContainsClient(claims)) {
            return null;
        }
        long now = clock.instant().getEpochSecond();
        JsonNode exp = claims.get("exp");
        if (exp == null || !exp.canConvertToLong() || exp.asLong() + CLOCK_SKEW_SECONDS < now) {
            return null;
        }
        return claims;
    }

    private boolean audienceContainsClient(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        boolean contains = aud != null && (aud.isTextual()
                ? settings.clientId().equals(aud.asText())
                : aud.isArray() && StreamSupport.stream(aud.spliterator(), false)
                        .anyMatch(n -> settings.clientId().equals(n.asText())));
        if (!contains) {
            return false;
        }
        // OIDC Core 3.1.3.7: with several audiences, azp must be present and be our client.
        if (aud.isArray() && aud.size() > 1) {
            return settings.clientId().equals(text(claims, "azp"));
        }
        return true;
    }

    private PublicKey keyFor(String kid) throws IOException, InterruptedException {
        PublicKey cached = kid == null ? null : keysByKid.get(kid);
        if (cached != null) {
            return cached;
        }
        loadJwks();                                                      // key rotation: refetch
        if (kid == null) {
            return keysByKid.size() == 1 ? keysByKid.values().iterator().next() : null;
        }
        return keysByKid.get(kid);
    }

    private void loadJwks() throws IOException, InterruptedException {
        String jwksUri = discoveredText("jwks_uri");
        if (jwksUri == null) {
            return;
        }
        JsonNode jwks = getJson(jwksUri);
        if (jwks == null || !jwks.path("keys").isArray()) {
            return;
        }
        for (JsonNode jwk : jwks.get("keys")) {
            if (!"RSA".equals(text(jwk, "kty"))) {
                continue;
            }
            String use = text(jwk, "use");
            if (use != null && !"sig".equals(use)) {
                continue;
            }
            try {
                // JWK n/e are UNSIGNED big-endian integers.
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(text(jwk, "n")));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(text(jwk, "e")));
                PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
                keysByKid.put(Optional.ofNullable(text(jwk, "kid")).orElse(""), key);
            } catch (Exception ignored) {
                // a malformed key is skipped, never fatal
            }
        }
    }

    private String discoveredText(String field) {
        try {
            if (discovery == null) {
                discovery = getJson(issuerBase + "/.well-known/openid-configuration");
            }
            return discovery == null ? null : text(discovery, field);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private String exchangeCode(String code, String redirectUri) throws IOException, InterruptedException {
        String tokenEndpoint = discoveredText("token_endpoint");
        if (tokenEndpoint == null) {
            return null;
        }
        String form = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(settings.clientId())
                + "&client_secret=" + enc(settings.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        JsonNode body = parse(response.body());
        // Do NOT require access_token: some providers return only id_token for the openid scope.
        return body == null ? null : text(body, "id_token");
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }
        return parse(response.body());
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean isTrue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.isTextual() && "true".equals(node.asText().trim().toLowerCase(Locale.ROOT));
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
