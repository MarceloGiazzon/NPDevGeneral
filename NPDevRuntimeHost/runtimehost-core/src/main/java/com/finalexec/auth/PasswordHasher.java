package com.finalexec.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * JDK-only password hashing (PBKDF2WithHmacSHA256) -- deliberately avoids pulling in a new
 * dependency (BCrypt/Argon2) for what the login flow needs. Stored format is
 * {@code iterations:base64(salt):base64(hash)}, self-describing so the iteration count can be
 * raised later without invalidating already-hashed passwords.
 */
public final class PasswordHasher {
    private static final int ITERATIONS = 210_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /**
     * REG-18 (REG-16 finding F1): a fixed, valid stored-hash used only to spend the SAME PBKDF2 work
     * on login paths that have no real credential to check (unknown user, missing credential row).
     * Computed once at class load from a constant decoy password with a random salt; its plaintext is
     * irrelevant -- it is never a real account. See {@link #verifyDecoy}.
     */
    private static final String DECOY_HASH = hash("npdev-constant-time-login-decoy");

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, ITERATIONS);
        return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(derived);
    }

    public static boolean verify(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split(":");
        if (parts.length != 3) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            byte[] actual = pbkdf2(password, salt, iterations);
            return constantTimeEquals(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * REG-18: perform a full password verification against a fixed decoy hash and ALWAYS deny.
     * Login paths that have no real stored hash to check (unknown username, missing credential row)
     * call this so they spend the same PBKDF2 time as the wrong-password path -- closing the timing
     * side-channel that otherwise reveals which usernames exist. A {@code null} password still does
     * the work (verify against the decoy returns false) rather than short-circuiting.
     */
    public static boolean verifyDecoy(String password) {
        return verify(password == null ? "" : password, DECOY_HASH);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("Password hashing failed", exception);
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
