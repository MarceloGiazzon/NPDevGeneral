package com.npdev.adapters.idp.github;

import com.npdev.kernel.ports.IdentityProvider.IdentityProviderClaims;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link GitHubIdentityProvider}: the fake GitHub is a {@link HttpServer} on an
 * ephemeral loopback port owned per-test, never the real network. Same style as
 * {@code GoogleIdentityProviderTest} (JDK {@code com.sun.net.httpserver}, plain JUnit 5 asserts, no
 * mocking library), extended with a THIRD stubbed endpoint ({@code /user/emails}) that Google's flow
 * has no equivalent for.
 */
class GitHubIdentityProviderTest {

    private static final String REDIRECT_URI = "http://localhost:8080/cb";
    private static final String TOKEN_RESPONSE = "{\"access_token\":\"a-token\",\"token_type\":\"bearer\"}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ authorization URL

    @Test
    void authorizationUrlContainsClientIdRedirectUriStateAndScope() {
        GitHubIdentityProvider provider = new GitHubIdentityProvider("client-id-1", "client-secret-1");

        String url = provider.authorizationUrl("st ate", "http://localhost:8080/cb?from=ui&lang=en");

        assertTrue(url.startsWith("https://github.com/login/oauth/authorize?"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcb%3Ffrom%3Dui%26lang%3Den"), url);
        assertTrue(url.contains("state=st+ate"), url);

        Map<String, String> params = parseQuery(url.substring(url.indexOf('?') + 1));
        assertEquals("client-id-1", params.get("client_id"));
        assertEquals("http://localhost:8080/cb?from=ui&lang=en", params.get("redirect_uri"));
        assertEquals("read:user user:email", params.get("scope"));
        assertEquals("st ate", params.get("state"));
    }

    // ------------------------------------------------------------------ happy path, public email

    @Test
    void resolveIdentityExchangesCodeForVerifiedClaimsWhenUserApiReturnsAnEmail() throws IOException {
        String clientId = "client-id-1";
        AtomicReference<String> tokenRequestBody = new AtomicReference<>();
        AtomicReference<String> userAuthHeader = new AtomicReference<>();
        server = startStubServer(
                exchange -> {
                    tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    writeJson(exchange, 200, TOKEN_RESPONSE);
                },
                exchange -> {
                    userAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    writeJson(exchange, 200, "{\"id\":42,\"login\":\"octocat\",\"name\":\"Octo Cat\","
                            + "\"avatar_url\":\"http://p\",\"email\":\"octo@example.com\"}");
                },
                exchange -> {
                    throw new AssertionError("/user/emails must not be reached when /user already has an email");
                });

        GitHubIdentityProvider provider = providerPointingAtServer(clientId, "client-secret-1");

        Optional<IdentityProviderClaims> claims = provider.resolveIdentity("the-code", REDIRECT_URI);

        assertTrue(claims.isPresent(), "expected identity claims: " + claims);
        IdentityProviderClaims identity = claims.get();
        assertEquals("42", identity.subject());
        assertEquals("octo@example.com", identity.email());
        assertTrue(identity.emailVerified());
        assertEquals("Octo Cat", identity.displayName());
        assertEquals("http://p", identity.avatarUrl());

        String body = tokenRequestBody.get();
        assertTrue(body.contains("grant_type=authorization_code"), body);
        assertTrue(body.contains("code=the-code"), body);
        assertTrue(body.contains("redirect_uri=" + urlEncode(REDIRECT_URI)), body);
        assertTrue(body.contains("client_id=" + clientId), body);
        assertTrue(body.contains("client_secret=client-secret-1"), body);

        assertEquals("Bearer a-token", userAuthHeader.get());
    }

    // ------------------------------------------------------------------ private email falls back to /user/emails

    @Test
    void resolveIdentityFallsBackToUserEmailsWhenUserApiEmailIsNull() throws IOException {
        String clientId = "client-id-1";
        AtomicReference<String> emailsAuthHeader = new AtomicReference<>();
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"id\":42,\"login\":\"octocat\",\"name\":\"Octo Cat\","
                        + "\"avatar_url\":\"http://p\",\"email\":null}"),
                exchange -> {
                    emailsAuthHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    writeJson(exchange, 200, "[{\"email\":\"secondary@example.com\",\"primary\":false,\"verified\":true},"
                            + "{\"email\":\"primary@example.com\",\"primary\":true,\"verified\":true}]");
                });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer(clientId, "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isPresent(), "expected identity claims: " + claims);
        assertEquals("primary@example.com", claims.get().email());
        assertTrue(claims.get().emailVerified());
        assertEquals("Bearer a-token", emailsAuthHeader.get());
    }

    @Test
    void resolveIdentityReportsUnverifiedPrimaryEmailAsFalseNotAsError() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"id\":42,\"login\":\"octocat\",\"email\":null}"),
                exchange -> writeJson(exchange, 200, "[{\"email\":\"primary@example.com\","
                        + "\"primary\":true,\"verified\":false}]"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isPresent(), "an unverified primary email must surface as a claim, not an error: " + claims);
        assertFalse(claims.get().emailVerified());
        assertEquals("primary@example.com", claims.get().email());
        assertEquals("42", claims.get().subject());
    }

    @Test
    void resolveIdentitySucceedsWithNoEmailWhenNoPrimaryEntryExists() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"id\":42,\"login\":\"octocat\",\"email\":null}"),
                exchange -> writeJson(exchange, 200, "[]"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isPresent(), "a subject-only identity is still a resolvable claim: " + claims);
        assertNull(claims.get().email());
        assertFalse(claims.get().emailVerified());
        assertEquals("42", claims.get().subject());
    }

    // ------------------------------------------------------------------ token endpoint failures

    @Test
    void resolveIdentityIsEmptyOnTokenEndpointHttpError() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 400, "{\"error\":\"bad_verification_code\"}"),
                exchange -> { throw new AssertionError("/user must not be reached when the code exchange failed"); },
                exchange -> { throw new AssertionError("/user/emails must not be reached when the code exchange failed"); });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("bad-code", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    @Test
    void resolveIdentityIsEmptyOnNonJsonTokenResponse() throws IOException {
        server = startStubServer(
                exchange -> writeText(exchange, 200, "definitely not json"),
                exchange -> { throw new AssertionError("/user must not be reached when the exchange body is not JSON"); },
                exchange -> { throw new AssertionError("/user/emails must not be reached when the exchange body is not JSON"); });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    @Test
    void resolveIdentityRequiresAccessToken() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, "{\"error\":\"incorrect_client_credentials\"}"),
                exchange -> { throw new AssertionError("/user must not be reached without an access_token"); },
                exchange -> { throw new AssertionError("/user/emails must not be reached without an access_token"); });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    // ------------------------------------------------------------------ /user failures

    @Test
    void resolveIdentityIsEmptyOnUserEndpointFailure() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 401, "{\"message\":\"Bad credentials\"}"),
                exchange -> { throw new AssertionError("/user/emails must not be reached when /user failed"); });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    @Test
    void resolveIdentityIsEmptyWhenIdIsMissing() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"login\":\"octocat\",\"email\":\"a@example.com\"}"),
                exchange -> { throw new AssertionError("/user/emails must not be reached when id is missing"); });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty(), "claims without a provider-side id must not resolve: " + claims);
    }

    // ------------------------------------------------------------------ helpers

    /** Routes /token, /user and /user/emails against a per-test fake GitHub so tests never touch the wire. */
    private HttpServer startStubServer(StubHandler tokenHandler, StubHandler userHandler, StubHandler emailsHandler) throws IOException {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", exchange -> {
            try {
                tokenHandler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        stub.createContext("/user/emails", exchange -> {
            try {
                emailsHandler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        // Registered AFTER /user/emails: com.sun.net.httpserver routes by longest matching prefix,
        // so the more specific context must exist or every /user/emails request would be handled by
        // this one instead.
        stub.createContext("/user", exchange -> {
            try {
                userHandler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        stub.start();
        return stub;
    }

    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private GitHubIdentityProvider providerPointingAtServer(String clientId, String clientSecret) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GitHubIdentityProvider(clientId, clientSecret,
                base + "/authorize", base + "/token", base + "/user", base + "/user/emails",
                HttpClient.newHttpClient());
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void writeText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
