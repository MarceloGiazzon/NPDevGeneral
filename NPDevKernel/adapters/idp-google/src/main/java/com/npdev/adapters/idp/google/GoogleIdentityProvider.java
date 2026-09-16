package com.npdev.adapters.idp.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.ports.IdentityProvider;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Google OAuth 2.0 authorization-code identity provider behind the kernel's {@link IdentityProvider}
 * port (NPDEV_MEGA_ROADMAP.md Session 3b).
 *
 * <p>The authorization URL sends the browser to Google's consent screen. The code returned by the
 * redirect is exchanged for a token pair at {@code /token}, and the {@code id_token} is then handed
 * to Google's {@code /tokeninfo} endpoint, whose response is trusted as the provider's verdict on
 * who the user is: {@code sub}, {@code email}, {@code email_verified} ({@code "true"}/{@code "false"}
 * strings or booleans, both accepted), {@code name} and {@code picture}.
 *
 * <p>Audience binding is load-bearing: {@code tokeninfo} returns an {@code aud} claim naming the
 * client the token was issued to; if it is present and is not the configured {@code clientId}, the
 * identity is rejected. An absent {@code aud} is tolerated (non-tokeninfo-conformant responses) so
 * the tokeninfo verdict itself stays the source of truth.
 *
 * <p>Failures never propagate: any HTTP error status, non-JSON body, missing {@code id_token} or
 * missing {@code sub} resolves to {@link Optional#empty()} per the port contract ("could not
 * resolve"). An unverified email is NOT an error -- it is the provider's verdict, surfaced as
 * {@code emailVerified == false}, never as a verified one.
 *
 * <p>Thread-safe: all state is final and immutable; the shared {@link HttpClient} and
 * {@link ObjectMapper} are safe for concurrent use, and per-call state lives only in locals.
 */
public final class GoogleIdentityProvider implements IdentityProvider {

    private static final String DEFAULT_AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String DEFAULT_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String DEFAULT_TOKEN_INFO_ENDPOINT = "https://oauth2.googleapis.com/tokeninfo";

    private static final String SCOPE = "openid email profile";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String clientId;
    private final String clientSecret;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String tokenInfoEndpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Constructs a provider against Google's standard endpoints with a fresh {@link HttpClient}. */
    public GoogleIdentityProvider(String clientId, String clientSecret) {
        this(clientId, clientSecret, DEFAULT_AUTHORIZATION_ENDPOINT, DEFAULT_TOKEN_ENDPOINT,
                DEFAULT_TOKEN_INFO_ENDPOINT, HttpClient.newHttpClient());
    }

    /**
     * Full constructor for embedding in tests and other hermetic contexts: endpoints and client are
     * caller-supplied so a local fake server can stand in for Google.
     */
    public GoogleIdentityProvider(String clientId, String clientSecret, String authorizationEndpoint,
                                  String tokenEndpoint, String tokenInfoEndpoint, HttpClient httpClient) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.authorizationEndpoint = Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.tokenInfoEndpoint = Objects.requireNonNull(tokenInfoEndpoint, "tokenInfoEndpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String providerId() {
        return "google";
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return authorizationEndpoint
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEncode(SCOPE)
                + "&state=" + urlEncode(state)
                + "&prompt=select_account";
    }

    @Override
    public Optional<IdentityProviderClaims> resolveIdentity(String code, String redirectUri) {
        if (code == null || code.isBlank() || redirectUri == null) {
            // Malformed calling state: nothing to exchange and no way to resolve.
            return Optional.empty();
        }
        try {
            String idToken = exchangeCodeForIdToken(code, redirectUri);
            if (idToken == null) {
                return Optional.empty();
            }
            return resolveClaimsFromTokenInfo(idToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            // A provider that cannot be reached is "could not resolve", never an exception.
            return Optional.empty();
        }
    }

    private String exchangeCodeForIdToken(String code, String redirectUri) throws IOException, InterruptedException {
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccess(response)) {
            return null;
        }
        JsonNode body = parseJson(response.body());
        if (body == null || !body.isObject()) {
            return null;
        }
        JsonNode idToken = body.get("id_token");
        JsonNode accessToken = body.get("access_token");
        if (idToken == null || !idToken.isTextual() || idToken.asText().isBlank()
                || accessToken == null || !accessToken.isTextual() || accessToken.asText().isBlank()) {
            return null;
        }
        return idToken.asText();
    }

    private Optional<IdentityProviderClaims> resolveClaimsFromTokenInfo(String idToken)
            throws IOException, InterruptedException {
        String url = tokenInfoEndpoint + "?id_token=" + urlEncode(idToken);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccess(response)) {
            return Optional.empty();
        }
        JsonNode claims = parseJson(response.body());
        if (claims == null || !claims.isObject()) {
            return Optional.empty();
        }

        // Audience binding: a token issued to a different client is not ours to trust.
        JsonNode audience = claims.get("aud");
        if (audience != null && !audience.isNull()
                && (!audience.isTextual() || !clientId.equals(audience.asText()))) {
            return Optional.empty();
        }

        JsonNode subject = claims.get("sub");
        if (subject == null || !subject.isTextual() || subject.asText().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new IdentityProviderClaims(
                subject.asText(),
                textOrNull(claims, "email"),
                isEmailVerified(claims.get("email_verified")),
                textOrNull(claims, "name"),
                textOrNull(claims, "picture")));
    }

    private static boolean isSuccess(HttpResponse<?> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static boolean isEmailVerified(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isTextual()) {
            return "true".equals(node.asText().trim().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static String textOrNull(JsonNode object, String field) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String text = node.asText().trim();
        return text.isEmpty() ? null : text;
    }

    private JsonNode parseJson(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}