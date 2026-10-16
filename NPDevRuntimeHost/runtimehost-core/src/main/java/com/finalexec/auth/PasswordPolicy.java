package com.finalexec.auth;

import java.util.Locale;
import java.util.Set;

/**
 * SEC-3: the ONE password rule every path that hashes a new password applies -- create-user,
 * bootstrap-admin, change-password, password-reset confirm, ControlPanel admin reset, and (SEC-5)
 * invitation accept / (SEC-9) self-signup. NIST SP 800-63B shape: a minimum length, a hard maximum
 * (bounds PBKDF2 work per request), a small block-list of the most common passwords, and "not equal
 * to the username". Deliberately NO composition rules (upper/digit/symbol).
 *
 * <p>Static on purpose: five controllers with direct-construction unit tests call it, and none of
 * their constructors has to change. {@link com.finalexec.config.PasswordPolicyConfig} pushes the
 * configured minimum in at boot (npdev.auth.password.min-length).</p>
 */
public final class PasswordPolicy {

    public static final int DEFAULT_MIN_LENGTH = 8;
    /** Floor: a misconfiguration can raise the minimum but never lower it below this. */
    public static final int FLOOR_MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 256;

    public static final String TOO_SHORT = "password_too_short";
    public static final String TOO_LONG = "password_too_long";
    public static final String TOO_COMMON = "password_too_common";
    public static final String MATCHES_USERNAME = "password_matches_username";

    // Lower-cased. Keep it short and obvious; this is a speed bump, not a breach corpus.
    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "passw0rd", "12345678", "123456789", "1234567890",
            "123123123", "11111111", "00000000", "qwertyui", "qwerty123", "qwertyuiop", "1q2w3e4r",
            "1qaz2wsx", "abc12345", "abcd1234", "iloveyou", "letmein1", "welcome1", "welcome123",
            "admin123", "administrator", "changeme", "sunshine", "football", "baseball", "trustno1",
            "senha123", "senha1234", "mudar123", "contrasena", "contrasena1","12341234", "87654321");

    private static volatile int minLength = DEFAULT_MIN_LENGTH;

    private PasswordPolicy() {
    }

    public static void configure(int configuredMinLength) {
        minLength = Math.max(FLOOR_MIN_LENGTH, configuredMinLength);
    }

    public static int minLength() {
        return minLength;
    }

    /**
     * @return {@code null} when the password is acceptable, else one of the error codes above
     *         (the HTTP layer returns it verbatim as {@code {"error": code, "minLength": n}}).
     */
    public static String violation(String password, String username) {
        if (password == null || password.length() < minLength) {
            return TOO_SHORT;
        }
        if (password.length() > MAX_LENGTH) {
            return TOO_LONG;
        }
        String lowered = password.toLowerCase(Locale.ROOT);
        if (COMMON.contains(lowered)) {
            return TOO_COMMON;
        }
        if (username != null && !username.isBlank() && lowered.equals(username.trim().toLowerCase(Locale.ROOT))) {
            return MATCHES_USERNAME;
        }
        return null;
    }

    /** Convenience for controllers: the 400 body for a violation. */
    public static java.util.Map<String, Object> errorBody(String code) {
        return java.util.Map.of("error", code, "minLength", minLength);
    }
}
