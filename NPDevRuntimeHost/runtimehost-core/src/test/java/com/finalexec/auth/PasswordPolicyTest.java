package com.finalexec.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** SEC-3: pins the one password rule every password-setting path applies. */
class PasswordPolicyTest {

    @AfterEach
    void resetMinimum() {
        PasswordPolicy.configure(PasswordPolicy.DEFAULT_MIN_LENGTH);
    }

    @Test
    void sevenCharactersIsTooShort() {
        assertEquals(PasswordPolicy.TOO_SHORT, PasswordPolicy.violation("abcdefg", "ada"));
        assertEquals(PasswordPolicy.TOO_SHORT, PasswordPolicy.violation(null, "ada"));
    }

    @Test
    void eightGoodCharactersAreAccepted() {
        assertNull(PasswordPolicy.violation("kT9#mQ2x", "ada"));
        assertNull(PasswordPolicy.violation("kT9#mQ2x", null));
    }

    @Test
    void commonPasswordsAreRejectedCaseInsensitively() {
        assertEquals(PasswordPolicy.TOO_COMMON, PasswordPolicy.violation("Password", null));
        assertEquals(PasswordPolicy.TOO_COMMON, PasswordPolicy.violation("PASSWORD123", "ada"));
    }

    @Test
    void passwordEqualToUsernameIsRejectedCaseInsensitively() {
        assertEquals(PasswordPolicy.MATCHES_USERNAME, PasswordPolicy.violation("Ada.Lovelace", "ada.lovelace"));
        assertNull(PasswordPolicy.violation("Ada.Lovelace", null));
    }

    @Test
    void over256CharactersIsTooLong() {
        assertEquals(PasswordPolicy.TOO_LONG, PasswordPolicy.violation("a".repeat(257), null));
        assertNull(PasswordPolicy.violation("a".repeat(256), null));
    }

    @Test
    void configuredMinimumCanBeRaisedButNeverLoweredBelowTheFloor() {
        PasswordPolicy.configure(4);
        assertEquals(8, PasswordPolicy.minLength());
        assertEquals(PasswordPolicy.TOO_SHORT, PasswordPolicy.violation("abcdefg", null));

        PasswordPolicy.configure(12);
        assertEquals(12, PasswordPolicy.minLength());
        assertEquals(PasswordPolicy.TOO_SHORT, PasswordPolicy.violation("kT9#mQ2xabc", null));
        assertNull(PasswordPolicy.violation("kT9#mQ2xabcd", null));
    }

    @Test
    void errorBodyCarriesTheCodeAndTheCurrentMinimum() {
        PasswordPolicy.configure(10);
        assertEquals(java.util.Map.of("error", "password_too_short", "minLength", 10),
                PasswordPolicy.errorBody(PasswordPolicy.TOO_SHORT));
    }
}
