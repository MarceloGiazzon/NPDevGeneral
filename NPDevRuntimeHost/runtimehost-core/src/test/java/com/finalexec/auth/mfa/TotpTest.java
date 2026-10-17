package com.finalexec.auth.mfa;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpTest {

    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String RFC_KEY_B32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void rfc6238AppendixBVectors() {
        assertEquals("287082", Totp.codeAt(RFC_KEY, 1L));
        assertEquals("081804", Totp.codeAt(RFC_KEY, 37037036L));
        assertEquals("050471", Totp.codeAt(RFC_KEY, 37037037L));
        assertEquals("005924", Totp.codeAt(RFC_KEY, 41152263L));
        assertEquals("279037", Totp.codeAt(RFC_KEY, 66666666L));
        assertEquals(37037036L, Totp.stepAt(Instant.ofEpochSecond(1111111109L)));
        assertEquals(66666666L, Totp.stepAt(Instant.ofEpochSecond(2000000000L)));
    }

    @Test
    void base32RoundTrips() {
        assertEquals(RFC_KEY_B32, Totp.base32Encode(RFC_KEY));
        assertArrayEquals(RFC_KEY, Totp.base32Decode(RFC_KEY_B32));
        String fresh = Totp.newSecretBase32();
        assertEquals(32, fresh.length());
        assertEquals(fresh, Totp.base32Encode(Totp.base32Decode(fresh)));
    }

    @Test
    void verifyAcceptsAdjacentStepsAndRejectsOthers() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long step = Totp.stepAt(now);
        assertEquals(step, Totp.verify(RFC_KEY_B32, Totp.codeAt(RFC_KEY, step), now, -1));
        assertEquals(step - 1, Totp.verify(RFC_KEY_B32, Totp.codeAt(RFC_KEY, step - 1), now, -1));
        assertEquals(step + 1, Totp.verify(RFC_KEY_B32, Totp.codeAt(RFC_KEY, step + 1), now, -1));
        assertEquals(-1, Totp.verify(RFC_KEY_B32, Totp.codeAt(RFC_KEY, step + 2), now, -1));
        assertEquals(-1, Totp.verify(RFC_KEY_B32, Totp.codeAt(RFC_KEY, step - 2), now, -1));
    }

    @Test
    void verifyRefusesReplayOfAnAlreadyUsedStep() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        long step = Totp.stepAt(now);
        String code = Totp.codeAt(RFC_KEY, step);
        assertEquals(-1, Totp.verify(RFC_KEY_B32, code, now, step));
        assertEquals(-1, Totp.verify(RFC_KEY_B32, code, now, step + 1));
        assertEquals(step, Totp.verify(RFC_KEY_B32, code, now, step - 1));
    }

    @Test
    void verifyRejectsMalformedCodes() {
        Instant now = Instant.ofEpochSecond(1111111111L);
        assertEquals(-1, Totp.verify(RFC_KEY_B32, "12345", now, -1));
        assertEquals(-1, Totp.verify(RFC_KEY_B32, "abcdef", now, -1));
        assertEquals(-1, Totp.verify(RFC_KEY_B32, "1234567", now, -1));
        assertEquals(-1, Totp.verify(RFC_KEY_B32, null, now, -1));
        // a code typed as "123 456" (authenticator apps show a space) is normalized
        long step = Totp.stepAt(now);
        String code = Totp.codeAt(RFC_KEY, step);
        assertEquals(step, Totp.verify(RFC_KEY_B32, code.substring(0, 3) + " " + code.substring(3), now, -1));
    }

    @Test
    void recoveryCodes() {
        assertEquals(Totp.recoveryCodeHash("K7M2-Q9XD"), Totp.recoveryCodeHash("k7m2q9xd"));
        assertEquals(64, Totp.recoveryCodeHash("k7m2-q9xd").length());
        var codes = Totp.newRecoveryCodes();
        assertEquals(10, codes.size());
        assertEquals(10, codes.stream().distinct().count());
        assertTrue(codes.get(0).matches("[a-z2-7]{4}-[a-z2-7]{4}"));
    }

    @Test
    void otpauthUriCarriesIssuerAndSecret() {
        String uri = Totp.otpauthUri("My App", "ada@example.com", RFC_KEY_B32);
        assertTrue(uri.startsWith("otpauth://totp/My%20App:ada%40example.com?secret=" + RFC_KEY_B32));
        assertTrue(uri.contains("&issuer=My%20App"));
        assertTrue(uri.contains("&digits=6&period=30"));
    }
}
