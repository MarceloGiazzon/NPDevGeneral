package com.npdev.adapters.idp.oidc;

import com.npdev.kernel.ports.IdentityProvider.IdentityProviderClaims;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-8: hermetic tests for the generic OIDC adapter. The fake provider is a loopback
 * {@link HttpServer} serving discovery, a JWKS and a token endpoint, with a JDK-generated RSA key.
 */
class OidcIdentityProviderTest {

    private static final String REDIRECT = "http://localhost:8080/cb";
    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    private HttpServer server;
    private String issuer;
    private KeyPair keyPair;
    private final AtomicReference<String> idTokenToServe = new AtomicReference<>();
    private final AtomicReference<String> jwksKid = new AtomicReference<>("k1");
    private final AtomicInteger jwksFetches = new AtomicInteger();
    private final AtomicReference<String> tokenRequestBody = new AtomicReference<>();
    private boolean includeAccessToken = false;

    @BeforeEach
    void startServer() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", ex -> respond(ex, 200,
                "{\"issuer\":\"" + issuer + "\",\"authorization_endpoint\":\"" + issuer + "/authorize\","
                        + "\"token_endpoint\":\"" + issuer + "/token\",\"jwks_uri\":\"" + issuer + "/jwks\"}"));
        server.createContext("/jwks", ex -> {
            jwksFetches.incrementAndGet();
            respond(ex, 200, "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"" + jwksKid.get() + "\",\"use\":\"sig\","
                    + "\"n\":\"" + b64(unsigned(((RSAPublicKey) keyPair.getPublic()).getModulus())) + "\","
                    + "\"e\":\"" + b64(unsigned(((RSAPublicKey) keyPair.getPublic()).getPublicExponent())) + "\"}]}");
        });
        server.createContext("/token", ex -> {
            tokenRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"id_token\":\"" + idTokenToServe.get() + "\""
                    + (includeAccessToken ? ",\"access_token\":\"a\"" : "") + "}");
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OidcIdentityProvider provider(boolean assumeVerified) {
        return new OidcIdentityProvider(new OidcIdentityProvider.Settings(
                "okta", issuer + "/", "client-1", "secret-1", null, null, null, null, assumeVerified),
                HttpClient.newHttpClient(), CLOCK);
    }

    private String claims(String iss, String aud, long exp, String extra) {
        return "{\"iss\":\"" + iss + "\",\"sub\":\"user-42\",\"aud\":" + aud + ",\"exp\":" + exp
                + ",\"email\":\"ada@example.com\",\"name\":\"Ada\",\"picture\":\"http://p/x.png\"" + extra + "}";
    }

    private String sign(String headerJson, String payloadJson) throws Exception {
        String signingInput = b64(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + b64(payloadJson.getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + b64(signature.sign());
    }

    private String goodToken(String extraClaims) throws Exception {
        return sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(issuer, "\"client-1\"", NOW + 600, extraClaims));
    }

    private Optional<IdentityProviderClaims> signIn(OidcIdentityProvider provider, String token) {
        idTokenToServe.set(token);
        return provider.resolveIdentity("the-code", REDIRECT);
    }

    @Test
    void authorizationUrlComesFromDiscoveryAndCarriesTheDefaults() {
        String url = provider(false).authorizationUrl("st ate", REDIRECT);
        assertTrue(url.startsWith(issuer + "/authorize?"), url);
        assertTrue(url.contains("client_id=client-1"), url);
        assertTrue(url.contains("response_type=code"), url);
        assertTrue(url.contains("scope=openid+email+profile"), url);
        assertTrue(url.contains("state=st+ate"), url);
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fcb"), url);
    }

    @Test
    void anUnreachableIssuerFailsLazilyNotAtConstruction() {
        OidcIdentityProvider dead = new OidcIdentityProvider(new OidcIdentityProvider.Settings(
                "okta", "http://127.0.0.1:1", "c", "s", null, null, null, null, false),
                HttpClient.newHttpClient(), CLOCK);
        assertThrows(IllegalStateException.class, () -> dead.authorizationUrl("s", REDIRECT));
        assertTrue(dead.resolveIdentity("code", REDIRECT).isEmpty());
    }

    @Test
    void constructorRejectsBlankRequiredSettings() {
        assertThrows(IllegalArgumentException.class, () -> new OidcIdentityProvider(
                new OidcIdentityProvider.Settings("okta", " ", "c", "s", null, null, null, null, false),
                HttpClient.newHttpClient(), CLOCK));
        assertThrows(NullPointerException.class, () -> new OidcIdentityProvider(
                new OidcIdentityProvider.Settings("okta", "http://x", null, "s", null, null, null, null, false),
                HttpClient.newHttpClient(), CLOCK));
    }

    @Test
    void happyPathResolvesTheIdentityFromALocallyVerifiedIdToken() throws Exception {
        Optional<IdentityProviderClaims> result = signIn(provider(false), goodToken(",\"email_verified\":true"));

        assertTrue(result.isPresent());
        assertEquals("user-42", result.get().subject());
        assertEquals("ada@example.com", result.get().email());
        assertTrue(result.get().emailVerified());
        assertEquals("Ada", result.get().displayName());
        assertEquals("http://p/x.png", result.get().avatarUrl());
        String form = tokenRequestBody.get();
        assertTrue(form.contains("grant_type=authorization_code") && form.contains("code=the-code")
                && form.contains("client_secret=secret-1"), form);
    }

    @Test
    void anAccessTokenIsNotRequired() throws Exception {
        includeAccessToken = false;
        assertTrue(signIn(provider(false), goodToken(",\"email_verified\":true")).isPresent());
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String token = sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(issuer, "\"someone-else\"", NOW + 600, ""));
        assertTrue(signIn(provider(false), token).isEmpty());
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        String token = sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims("http://evil.example", "\"client-1\"", NOW + 600, ""));
        assertTrue(signIn(provider(false), token).isEmpty());
    }

    @Test
    void expiredTokenIsRejectedBeyondTheSkew() throws Exception {
        String stale = sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(issuer, "\"client-1\"", NOW - 61, ""));
        assertTrue(signIn(provider(false), stale).isEmpty());
        String withinSkew = sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(issuer, "\"client-1\"", NOW - 30, ""));
        assertTrue(signIn(provider(false), withinSkew).isPresent());
    }

    @Test
    void algNoneAndHs256AreRejected() throws Exception {
        String payload = b64(claims(issuer, "\"client-1\"", NOW + 600, "").getBytes(StandardCharsets.UTF_8));
        String none = b64("{\"alg\":\"none\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "." + payload + ".";
        assertTrue(signIn(provider(false), none).isEmpty());
        String hs = b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}".getBytes(StandardCharsets.UTF_8)) + "." + payload + "."
                + b64("sig".getBytes(StandardCharsets.UTF_8));
        assertTrue(signIn(provider(false), hs).isEmpty());
    }

    @Test
    void tamperedPayloadFailsTheSignatureCheck() throws Exception {
        String[] parts = goodToken("").split("\\.");
        String forged = b64(claims(issuer, "\"client-1\"", NOW + 600, ",\"admin\":true").getBytes(StandardCharsets.UTF_8));
        assertTrue(signIn(provider(false), parts[0] + "." + forged + "." + parts[2]).isEmpty());
        assertTrue(signIn(provider(false), "not-a-jwt").isEmpty());
        assertTrue(signIn(provider(false), "a.b.c").isEmpty());
    }

    @Test
    void unknownKidTriggersOneJwksRefetchThatFindsTheRotatedKey() throws Exception {
        OidcIdentityProvider provider = provider(false);
        jwksKid.set("old");
        assertTrue(signIn(provider, goodToken(",\"email_verified\":true")).isEmpty(), "k1 is not in the JWKS yet");
        int before = jwksFetches.get();

        jwksKid.set("k1");   // the provider rotated its keys
        assertTrue(signIn(provider, goodToken(",\"email_verified\":true")).isPresent());
        assertEquals(before + 1, jwksFetches.get());
        // now cached: no further fetch
        assertTrue(signIn(provider, goodToken(",\"email_verified\":true")).isPresent());
        assertEquals(before + 1, jwksFetches.get());
    }

    @Test
    void emailVerifiedFalseOrMissingSurfacesAsUnverifiedUnlessAssumed() throws Exception {
        assertFalse(signIn(provider(false), goodToken(",\"email_verified\":false")).get().emailVerified());
        assertFalse(signIn(provider(false), goodToken("")).get().emailVerified());
        assertTrue(signIn(provider(true), goodToken("")).get().emailVerified(), "operator opt-in (Entra ID)");
    }

    @Test
    void severalAudiencesRequireAnAuthorizedPartyThatIsUs() throws Exception {
        String two = "[\"client-1\",\"other\"]";
        String noAzp = sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", claims(issuer, two, NOW + 600, ""));
        assertTrue(signIn(provider(false), noAzp).isEmpty());
        String withAzp = sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}",
                claims(issuer, two, NOW + 600, ",\"azp\":\"client-1\""));
        assertTrue(signIn(provider(false), withAzp).isPresent());
    }

    // ----------------------------------------------------------------------------------- helpers

    /** BigInteger.toByteArray() adds a leading 0x00 sign byte; real IdPs serve the bare magnitude. */
    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
