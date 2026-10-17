package com.finalexec.auth.mfa;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SEC-10: stored form of {@code identity::UserMfa.secret}.
 * <ul>
 *   <li>{@code g1:} + base64(12-byte IV || AES-256-GCM ciphertext+tag) when {@code npdev.auth.mfa.secret-key}
 *       is set (base64 of exactly 32 bytes; env {@code NPDEV_AUTH_MFA_SECRET_KEY});</li>
 *   <li>{@code p1:} + base32 secret when it is not (a WARNING is logged once at boot).</li>
 * </ul>
 * {@link #decode} accepts both, so setting a key later keeps old rows readable; a row written with a
 * key cannot be read without it (recovery codes, which are hash-based, still sign the user in so they can re-enroll).
 */
public final class MfaSecretCodec {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static volatile byte[] configuredKey;

    private MfaSecretCodec() {
    }

    /** Called once at boot by {@code MfaConfig}; null clears the key (plain storage). */
    public static void configureKey(byte[] key) {
        if (key != null && key.length != 32) {
            throw new IllegalArgumentException("MFA secret key must be exactly 32 bytes");
        }
        configuredKey = key == null ? null : key.clone();
    }

    public static String encode(String base32) {
        return encode(base32, configuredKey);
    }

    public static String decode(String stored) {
        return decode(stored, configuredKey);
    }

    public static String encode(String base32, byte[] keyOrNull) {
        if (keyOrNull == null) {
            return "p1:" + base32;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyOrNull, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(base32.getBytes(StandardCharsets.US_ASCII));
            return "g1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array());
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt the MFA secret", e);
        }
    }

    public static String decode(String stored, byte[] keyOrNull) {
        if (stored == null) {
            throw new IllegalArgumentException("no stored MFA secret");
        }
        if (stored.startsWith("p1:")) {
            return stored.substring(3);
        }
        if (stored.startsWith("g1:")) {
            if (keyOrNull == null) {
                throw new IllegalStateException("MFA secret is encrypted but npdev.auth.mfa.secret-key is not set");
            }
            try {
                byte[] raw = Base64.getDecoder().decode(stored.substring(3));
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyOrNull, "AES"),
                        new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
                return new String(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES), StandardCharsets.US_ASCII);
            } catch (Exception e) {
                throw new IllegalStateException("Could not decrypt the MFA secret (wrong npdev.auth.mfa.secret-key?)", e);
            }
        }
        throw new IllegalArgumentException("unknown MFA secret format");
    }
}
