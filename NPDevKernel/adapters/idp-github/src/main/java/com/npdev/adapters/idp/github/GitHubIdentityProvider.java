package com.npdev.adapters.idp.github;

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
import java.util.Objects;
import java.util.Optional;

/**
 * GitHub OAuth 2.0 authorization-code identity provider behind the kernel's {@link IdentityProvider}
 * port -- the second provider (after {@code idp-google}), proving the port generalizes across a
 * materially different flow rather than just another OIDC-conformant one.
 *
 * <p>Unlike Google, GitHub's flow is NOT OIDC: there is no {@code id_token} and no {@code tokeninfo}
 * verdict endpoint. The code exchange returns a bare {@code access_token}, which is then used as a
 * bearer credential against GitHub's REST API to fetch the identity: {@code GET /user} for
 * {@code id}/{@code login}/{@code name}/{@code avatar_url}, and -- only when {@code /user}'s own
 * {@code email} field is {@code null} (a private or unset primary email) -- {@code GET /user/emails}
 * to find the account's verified primary address. Consequently there is no audience-binding check
 * equivalent to Google's {@code aud} verification: the access token is already scoped to this app by
 * the client id/secret used in the exchange, so nothing analogous to a re-usable {@code id_token}
 * needs a second audience check.
 *
 * <p>Failures never propagate: any HTTP error status, non-JSON body, or missing {@code id} resolves
 * to {@link Optional#empty()} per the port contract ("could not resolve"). A user with no verified
 * email at all is not an error -- it surfaces as {@code email == null}, {@code emailVerified ==
 * false}, exactly like an unverified Google email.
 *
 * <p>Thread-safe: all state is final and immutable; the shared {@link HttpClient} and
 * {@link ObjectMapper} are safe for concurrent use, and per-call state lives only in locals.
 */
public final class GitHubIdentityProvider implements IdentityProvider {

    private static final String DEFAULT_AUTHORIZATION_ENDPOINT = "https://github.com/login/oauth/authorize";
    private static final String DEFAULT_TOKEN_ENDPOINT = "https://github.com/login/oauth/access_token";
    private static final String DEFAULT_USER_ENDPOINT = "https://api.github.com/user";
    private static final String DEFAULT_USER_EMAILS_ENDPOINT = "https://api.github.com/user/emails";

    private static final String SCOPE = "read:user user:email";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final String clientId;
    private final String clientSecret;
    private final String authorizationEndpoint;
    private final String tokenEndpoint;
    private final String userEndpoint;
    private final String userEmailsEndpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Constructs a provider against GitHub's standard endpoints with a fresh {@link HttpClient}. */
    public GitHubIdentityProvider(String clientId, String clientSecret) {
        this(clientId, clientSecret, DEFAULT_AUTHORIZATION_ENDPOINT, DEFAULT_TOKEN_ENDPOINT,
                DEFAULT_USER_ENDPOINT, DEFAULT_USER_EMAILS_ENDPOINT, HttpClient.newHttpClient());
    }

    /**
     * Full constructor for embedding in tests and other hermetic contexts: endpoints and client are
     * caller-supplied so a local fake server can stand in for GitHub.
     */
    public GitHubIdentityProvider(String clientId, String clientSecret, String authorizationEndpoint,
                                  String tokenEndpoint, String userEndpoint, String userEmailsEndpoint,
                                  HttpClient httpClient) {
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.clientSecret = Objects.requireNonNull(clientSecret, "clientSecret");
        this.authorizationEndpoint = Objects.requireNonNull(authorizationEndpoint, "authorizationEndpoint");
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.userEndpoint = Objects.requireNonNull(userEndpoint, "userEndpoint");
        this.userEmailsEndpoint = Objects.requireNonNull(userEmailsEndpoint, "userEmailsEndpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    @Override
    public String providerId() {
        return "github";
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return authorizationEndpoint
                + "?client_id=" + urlEncode(clientId)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&scope=" + urlEncode(SCOPE)
                + "&state=" + urlEncode(state);
    }

    @Override
    public Optional<IdentityProviderClaims> resolveIdentity(String code, String redirectUri) {
        if (code == null || code.isBlank() || redirectUri == null) {
            // Malformed calling state: nothing to exchange and no way to resolve.
            return Optional.empty();
        }
        try {
            String accessToken = exchangeCodeForAccessToken(code, redirectUri);
            if (accessToken == null) {
                return Optional.empty();
            }
            return resolveClaimsFromUserApi(accessToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException e) {
            // A provider that cannot be reached is "could not resolve", never an exception.
            return Optional.empty();
        }
    }

    private String exchangeCodeForAccessToken(String code, String redirectUri) throws IOException, InterruptedException {
        String form = "grant_type=authorization_code"
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .timeout(REQUEST_TIMEOUT)
                // GitHub's token endpoint answers form-urlencoded by default; this header is what
                // makes it answer JSON instead, same shape as every other endpoint this class reads.
                .header("Accept", "application/json")
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
        JsonNode accessToken = body.get("access_token");
        if (accessToken == null || !accessToken.isTextual() || accessToken.asText().isBlank()) {
            // Covers both a malformed response and GitHub's own {"error": "..."} shape.
            return null;
        }
        return accessToken.asText();
    }

    private Optional<IdentityProviderClaims> resolveClaimsFromUserApi(String accessToken)
            throws IOException, InterruptedException {
        JsonNode user = getJson(userEndpoint, accessToken);
        if (user == null || !user.isObject()) {
            return Optional.empty();
        }

        JsonNode id = user.get("id");
        if (id == null || id.isNull() || (!id.isNumber() && !id.isTextual())) {
            return Optional.empty();
        }
        String subject = id.isNumber() ? id.asText() : id.asText().trim();
        if (subject.isBlank()) {
            return Optional.empty();
        }

        String email = textOrNull(user, "email");
        boolean emailVerified = email != null;
        if (email == null) {
            PrimaryEmail primary = resolvePrimaryEmail(accessToken);
            if (primary != null) {
                email = primary.email;
                emailVerified = primary.verified;
            }
        }

        return Optional.of(new IdentityProviderClaims(
                subject,
                email,
                emailVerified,
                textOrNull(user, "name"),
                textOrNull(user, "avatar_url")));
    }

    private record PrimaryEmail(String email, boolean verified) {
    }

    /** {@code /user}'s own {@code email} is {@code null} for a private/unset primary address --
     *  {@code /user/emails} is the only way to learn it, and carries the verification flag GitHub
     *  itself vouches for. Absent, empty, or unparsable is "no usable email", not an error. */
    private PrimaryEmail resolvePrimaryEmail(String accessToken) throws IOException, InterruptedException {
        JsonNode emails = getJson(userEmailsEndpoint, accessToken);
        if (emails == null || !emails.isArray()) {
            return null;
        }
        for (JsonNode entry : emails) {
            if (entry.isObject() && entry.path("primary").asBoolean(false)) {
                String address = textOrNull(entry, "email");
                if (address == null) {
                    return null;
                }
                return new PrimaryEmail(address, entry.path("verified").asBoolean(false));
            }
        }
        return null;
    }

    private JsonNode getJson(String endpoint, String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (!isSuccess(response)) {
            return null;
        }
        return parseJson(response.body());
    }

    private static boolean isSuccess(HttpResponse<?> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
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
