package com.npdev.adapters.idp.google;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link GoogleIdentityProvider}: the fake Google is a {@link HttpServer} on an
 * ephemeral loopback port owned per-test, never the real network. Follows the style of
 * {@code HttpWebhookCapabilityAdapterTest} (same JDK {@code com.sun.net.httpserver} pattern, plain
 * JUnit 5 asserts, no mocking library).
 */
class GoogleIdentityProviderTest {

    private static final String REDIRECT_URI = "http://localhost:8080/cb";
    private static final String TOKEN_RESPONSE =
            "{\"access_token\":\"a\",\"id_token\":\"idt\",\"token_type\":\"Bearer\"}";

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
        GoogleIdentityProvider provider = new GoogleIdentityProvider("client-id-1", "client-secret-1");

        String url = provider.authorizationUrl("st ate", "http://localhost:8080/cb?from=ui&lang=en");

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"), url);
        // Values must be URL-encoded on the wire, not spliced in raw.
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcb%3Ffrom%3Dui%26lang%3Den"), url);
        assertTrue(url.contains("state=st+ate"), url);

        Map<String, String> params = parseQuery(url.substring(url.indexOf('?') + 1));
        assertEquals("client-id-1", params.get("client_id"));
        assertEquals("http://localhost:8080/cb?from=ui&lang=en", params.get("redirect_uri"));
        assertEquals("code", params.get("response_type"));
        assertEquals("openid email profile", params.get("scope"));
        assertEquals("st ate", params.get("state"));
        assertEquals("select_account", params.get("prompt"));
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void resolveIdentityExchangesCodeForVerifiedClaims() throws IOException {
        String clientId = "client-id-1";
        AtomicReference<String> tokenRequestBody = new AtomicReference<>();
        AtomicReference<String> tokenInfoRequestUri = new AtomicReference<>();
        server = startStubServer(
                exchange -> {
                    tokenRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    writeJson(exchange, 200, TOKEN_RESPONSE);
                },
                exchange -> {
                    tokenInfoRequestUri.set(exchange.getRequestURI().toString());
                    writeJson(exchange, 200, "{\"sub\":\"u123\",\"email\":\"a@example.com\","
                            + "\"email_verified\":\"true\",\"name\":\"A\",\"picture\":\"http://p\","
                            + "\"aud\":\"" + clientId + "\"}");
                });

        GoogleIdentityProvider provider = providerPointingAtServer(clientId, "client-secret-1");

        Optional<IdentityProviderClaims> claims = provider.resolveIdentity("the-code", REDIRECT_URI);

        assertTrue(claims.isPresent(), "expected identity claims: " + claims);
        IdentityProviderClaims identity = claims.get();
        assertEquals("u123", identity.subject());
        assertEquals("a@example.com", identity.email());
        assertTrue(identity.emailVerified());
        assertEquals("A", identity.displayName());
        assertEquals("http://p", identity.avatarUrl());

        // The code exchange must send the authorization_code grant carrying the caller's values.
        String body = tokenRequestBody.get();
        assertTrue(body.contains("grant_type=authorization_code"), body);
        assertTrue(body.contains("code=the-code"), body);
        assertTrue(body.contains("redirect_uri=" + urlEncode(REDIRECT_URI)), body);
        assertTrue(body.contains("client_id=" + clientId), body);
        assertTrue(body.contains("client_secret=client-secret-1"), body);

        // The tokeninfo lookup must carry the id_token from the exchange.
        assertTrue(tokenInfoRequestUri.get().contains("id_token=idt"), tokenInfoRequestUri.get());
    }

    @Test
    void resolveIdentityHandlesBooleanEmailVerified() throws IOException {
        String clientId = "client-id-1";
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"sub\":\"u123\",\"email\":\"a@example.com\","
                        + "\"email_verified\":true,\"name\":\"A\",\"picture\":\"http://p\","
                        + "\"aud\":\"" + clientId + "\"}"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer(clientId, "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isPresent(), "expected identity claims: " + claims);
        assertTrue(claims.get().emailVerified());
    }

    // ------------------------------------------------------------------ email_verified verdicts

    @Test
    void resolveIdentityReportsUnverifiedEmailAsFalseNotAsError() throws IOException {
        String clientId = "client-id-1";
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"sub\":\"u123\",\"email\":\"u@example.com\","
                        + "\"email_verified\":\"false\",\"name\":\"U\",\"picture\":\"http://p\","
                        + "\"aud\":\"" + clientId + "\"}"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer(clientId, "s")
                .resolveIdentity("c", REDIRECT_URI);

        // Google's verdict "this email is not verified" is a valid answer, not a resolution failure.
        assertTrue(claims.isPresent(), "an unverified email must surface as a claim, not an error: " + claims);
        assertFalse(claims.get().emailVerified());
        assertEquals("u123", claims.get().subject());
        assertEquals("u@example.com", claims.get().email());
    }

    @Test
    void resolveIdentityReportsUnverifiedEmailWhenEmailVerifiedFieldIsAbsent() throws IOException {
        String clientId = "client-id-1";
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"sub\":\"u123\",\"email\":\"u@example.com\","
                        + "\"name\":\"U\",\"picture\":\"http://p\",\"aud\":\"" + clientId + "\"}"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer(clientId, "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isPresent(), "expected identity claims: " + claims);
        assertFalse(claims.get().emailVerified(),
                "an absent email_verified must never be reported as a verified email");
        assertEquals("u123", claims.get().subject());
    }

    // ------------------------------------------------------------------ audience binding

    @Test
    void resolveIdentityIsEmptyWhenAudienceMismatches() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"sub\":\"u123\",\"email\":\"a@example.com\","
                        + "\"email_verified\":\"true\",\"name\":\"A\",\"picture\":\"http://p\","
                        + "\"aud\":\"some-other-client\"}"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("client-id-1", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty(), "a token issued to another client must not be accepted: " + claims);
    }

    // ------------------------------------------------------------------ token endpoint failures

    @Test
    void resolveIdentityIsEmptyOnTokenEndpointHttpError() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 400, "{\"error\":\"invalid_grant\"}"),
                exchange -> {
                    throw new AssertionError("tokeninfo must not be reached when the code exchange failed");
                });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("bad-code", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    @Test
    void resolveIdentityIsEmptyOnNonJsonTokenResponse() throws IOException {
        server = startStubServer(
                exchange -> writeText(exchange, 200, "definitely not json"),
                exchange -> {
                    throw new AssertionError("tokeninfo must not be reached when the exchange body is not JSON");
                });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    @Test
    void resolveIdentityRequiresIdToken() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, "{\"access_token\":\"x\"}"),
                exchange -> {
                    throw new AssertionError("tokeninfo must not be reached without an id_token");
                });

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    // ------------------------------------------------------------------ tokeninfo failures

    @Test
    void resolveIdentityIsEmptyOnTokenInfoEndpointFailure() throws IOException {
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 500, "{\"error\":\"server_error\"}"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer("cid", "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty());
    }

    @Test
    void resolveIdentityIsEmptyWhenSubjectIsMissing() throws IOException {
        String clientId = "client-id-1";
        server = startStubServer(
                exchange -> writeJson(exchange, 200, TOKEN_RESPONSE),
                exchange -> writeJson(exchange, 200, "{\"email\":\"a@example.com\","
                        + "\"email_verified\":\"true\",\"aud\":\"" + clientId + "\"}"));

        Optional<IdentityProviderClaims> claims = providerPointingAtServer(clientId, "s")
                .resolveIdentity("c", REDIRECT_URI);

        assertTrue(claims.isEmpty(), "claims without a provider-side subject must not resolve: " + claims);
    }

    // ------------------------------------------------------------------ helpers

    /** Routes /token and /tokeninfo against a per-test fake Google so tests never touch the wire. */
    private HttpServer startStubServer(StubHandler tokenHandler, StubHandler tokenInfoHandler) throws IOException {
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", exchange -> {
            try {
                tokenHandler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        stub.createContext("/tokeninfo", exchange -> {
            try {
                tokenInfoHandler.handle(exchange);
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

    private GoogleIdentityProvider providerPointingAtServer(String clientId, String clientSecret) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        return new GoogleIdentityProvider(clientId, clientSecret,
                base + "/auth", base + "/token", base + "/tokeninfo", HttpClient.newHttpClient());
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