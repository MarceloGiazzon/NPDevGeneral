package com.finalexec.auth.mfa;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MfaSecretCodecTest {

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    @Test
    void plainRoundTrip() {
        String stored = MfaSecretCodec.encode("GEZDGNBV", null);
        assertEquals("p1:GEZDGNBV", stored);
        assertEquals("GEZDGNBV", MfaSecretCodec.decode(stored, null));
    }

    @Test
    void encryptedRoundTripAndRandomIv() {
        byte[] key = randomKey();
        String a = MfaSecretCodec.encode("GEZDGNBVGY3TQOJQ", key);
        String b = MfaSecretCodec.encode("GEZDGNBVGY3TQOJQ", key);
        assertTrue(a.startsWith("g1:"));
        assertNotEquals(a, b);
        assertEquals("GEZDGNBVGY3TQOJQ", MfaSecretCodec.decode(a, key));
        assertEquals("GEZDGNBVGY3TQOJQ", MfaSecretCodec.decode(b, key));
    }

    @Test
    void encryptedDecodeWithoutKeyOrWithWrongKeyThrows() {
        String stored = MfaSecretCodec.encode("GEZDGNBVGY3TQOJQ", randomKey());
        assertThrows(IllegalStateException.class, () -> MfaSecretCodec.decode(stored, null));
        assertThrows(IllegalStateException.class, () -> MfaSecretCodec.decode(stored, randomKey()));
    }

    @Test
    void plainRowsStayReadableAfterAKeyIsSet() {
        assertEquals("ABC", MfaSecretCodec.decode("p1:ABC", randomKey()));
    }

    @Test
    void configuredKeyIsUsedByTheNoKeyOverloads() {
        try {
            MfaSecretCodec.configureKey(randomKey());
            String stored = MfaSecretCodec.encode("GEZDGNBV");
            assertTrue(stored.startsWith("g1:"));
            assertEquals("GEZDGNBV", MfaSecretCodec.decode(stored));
        } finally {
            MfaSecretCodec.configureKey(null);
        }
        assertThrows(IllegalArgumentException.class, () -> MfaSecretCodec.configureKey(new byte[16]));
    }
}
