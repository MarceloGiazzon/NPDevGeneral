package com.finalexec.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-6: expected values are computed with javax.crypto here, never copied from provider docs. */
class InboundSignatureVerifierTest {

    private static final String SECRET = "whsec_test";
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final byte[] BODY = "{\"id\":\"evt_1\",\"amount\":1250}".getBytes(StandardCharsets.UTF_8);

    private static byte[] hmac(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    private static Function<String, String> headers(String name, String value) {
        return n -> n.equalsIgnoreCase(name) ? value : null;
    }

    private static String stripeHeader(long t, String... v1) throws Exception {
        StringBuilder out = new StringBuilder("t=" + t);
        for (String v : v1) {
            out.append(",v1=").append(v);
        }
        return out.toString();
    }

    private static String stripeSig(long t, byte[] body) throws Exception {
        byte[] prefix = (t + ".").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, all, 0, prefix.length);
        System.arraycopy(body, 0, all, prefix.length, body.length);
        return hex(hmac(all));
    }

    @Test
    void npdevSchemeAcceptsHexWithAndWithoutPrefix() throws Exception {
        String sig = hex(hmac(BODY));
        assertTrue(InboundSignatureVerifier.verify("npdev", SECRET, BODY,
                headers("X-Npdev-Webhook-Signature", sig), "X-Npdev-Webhook-Signature", null, 0, NOW));
        assertTrue(InboundSignatureVerifier.verify(null, SECRET, BODY,
                headers("X-Npdev-Webhook-Signature", "SHA256=" + sig), "X-Npdev-Webhook-Signature", null, 0, NOW));
        assertFalse(InboundSignatureVerifier.verify("npdev", SECRET, "x".getBytes(StandardCharsets.UTF_8),
                headers("X-Npdev-Webhook-Signature", sig), "X-Npdev-Webhook-Signature", null, 0, NOW));
    }

    @Test
    void githubStyleHexWithPrefix() throws Exception {
        String header = "sha256=" + hex(hmac(BODY));
        assertTrue(InboundSignatureVerifier.verify("hmac-sha256-hex", SECRET, BODY,
                headers("X-Hub-Signature-256", header), "X-Hub-Signature-256", "sha256=", 0, NOW));
        // wrong secret
        assertFalse(InboundSignatureVerifier.verify("hmac-sha256-hex", "other", BODY,
                headers("X-Hub-Signature-256", header), "X-Hub-Signature-256", "sha256=", 0, NOW));
        // base64 value presented to a hex door
        String b64 = Base64.getEncoder().encodeToString(hmac(BODY));
        assertFalse(InboundSignatureVerifier.verify("hmac-sha256-hex", SECRET, BODY,
                headers("X-Hub-Signature-256", b64), "X-Hub-Signature-256", "sha256=", 0, NOW));
    }

    @Test
    void shopifyStyleBase64AndHexMixup() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(hmac(BODY));
        assertTrue(InboundSignatureVerifier.verify("hmac-sha256-base64", SECRET, BODY,
                headers("X-Shopify-Hmac-Sha256", b64), "X-Shopify-Hmac-Sha256", null, 0, NOW));
        assertFalse(InboundSignatureVerifier.verify("hmac-sha256-base64", SECRET, BODY,
                headers("X-Shopify-Hmac-Sha256", hex(hmac(BODY))), "X-Shopify-Hmac-Sha256", null, 0, NOW));
        assertFalse(InboundSignatureVerifier.verify("hmac-sha256-base64", SECRET, BODY,
                headers("X-Shopify-Hmac-Sha256", "not base64 !!"), "X-Shopify-Hmac-Sha256", null, 0, NOW));
    }

    @Test
    void stripeAcceptsFreshSignatureAndAnyV1Entry() throws Exception {
        long t = NOW.getEpochSecond() - 10;
        String good = stripeSig(t, BODY);
        assertTrue(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", stripeHeader(t, good)), "Stripe-Signature", null, 300, NOW));
        // only the SECOND v1 entry matches (rotated secret scenario)
        assertTrue(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", stripeHeader(t, "00".repeat(32), good)), "Stripe-Signature", null, 300, NOW));
        // neither matches
        assertFalse(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", stripeHeader(t, "00".repeat(32), "zz")), "Stripe-Signature", null, 300, NOW));
    }

    @Test
    void stripeRejectsStaleTimestampAsExpiredButOnlyWhenSignatureIsGenuine() throws Exception {
        long stale = NOW.getEpochSecond() - 301;
        String header = stripeHeader(stale, stripeSig(stale, BODY));
        assertFalse(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", header), "Stripe-Signature", null, 300, NOW));
        assertEquals(InboundSignatureVerifier.Result.EXPIRED, InboundSignatureVerifier.check("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", header), "Stripe-Signature", null, 300, NOW));
        // exactly at the tolerance edge is still fine; default tolerance applies when 0
        long edge = NOW.getEpochSecond() - 300;
        assertTrue(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", stripeHeader(edge, stripeSig(edge, BODY))), "Stripe-Signature", null, 0, NOW));
        // stale AND forged is INVALID, not EXPIRED (no oracle for unauthenticated callers)
        assertEquals(InboundSignatureVerifier.Result.INVALID, InboundSignatureVerifier.check("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", stripeHeader(stale, "00".repeat(32))), "Stripe-Signature", null, 300, NOW));
    }

    @Test
    void stripeSignatureCoversTimestampAndBody() throws Exception {
        long t = NOW.getEpochSecond();
        String sig = stripeSig(t, BODY);
        byte[] tampered = BODY.clone();
        tampered[3] ^= 1;
        assertFalse(InboundSignatureVerifier.verify("stripe-v1", SECRET, tampered,
                headers("Stripe-Signature", stripeHeader(t, sig)), "Stripe-Signature", null, 300, NOW));
        // a plain body HMAC (no timestamp) is not a valid stripe signature
        assertFalse(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", stripeHeader(t, hex(hmac(BODY)))), "Stripe-Signature", null, 300, NOW));
        assertFalse(InboundSignatureVerifier.verify("stripe-v1", SECRET, BODY,
                headers("Stripe-Signature", "garbage"), "Stripe-Signature", null, 300, NOW));
    }

    @Test
    void missingHeaderBlankSecretAndUnknownSchemeFailClosed() throws Exception {
        String sig = hex(hmac(BODY));
        assertFalse(InboundSignatureVerifier.verify("npdev", SECRET, BODY, n -> null, "X-Sig", null, 0, NOW));
        assertFalse(InboundSignatureVerifier.verify("npdev", " ", BODY, headers("X-Sig", sig), "X-Sig", null, 0, NOW));
        assertFalse(InboundSignatureVerifier.verify("rsa", SECRET, BODY, headers("X-Sig", sig), "X-Sig", null, 0, NOW));
    }

    @Test
    void formBodyParsesFirstValuesAndDecodes() {
        Map<String, Object> form = InboundWebhookSupport.parseFormBody(
                "a=1&b=hello+world%21&a=2&empty=&flag&%C3%A9=caf%C3%A9".getBytes(StandardCharsets.UTF_8));
        assertEquals("1", form.get("a"));
        assertEquals("hello world!", form.get("b"));
        assertEquals("", form.get("empty"));
        assertEquals("", form.get("flag"));
        assertEquals("café", form.get("é"));
    }

    @Test
    void idempotencyKeyResolvesFromBodyPathOrHeader() {
        Map<String, Object> body = Map.of("id", "evt_9", "data", Map.of("object", Map.of("n", 7)));
        Function<String, String> headers = headers("X-GitHub-Delivery", " d-1 ");
        assertEquals("evt_9", InboundWebhookSupport.resolveIdempotencyKey("id", body, headers));
        assertEquals("7", InboundWebhookSupport.resolveIdempotencyKey("data.object.n", body, headers));
        assertEquals("d-1", InboundWebhookSupport.resolveIdempotencyKey("$header.X-GitHub-Delivery", body, headers));
        assertNull(InboundWebhookSupport.resolveIdempotencyKey("$header.Missing", body, headers));
        assertNull(InboundWebhookSupport.resolveIdempotencyKey("nope", body, headers));
        assertNull(InboundWebhookSupport.resolveIdempotencyKey("", body, headers));
    }
}
