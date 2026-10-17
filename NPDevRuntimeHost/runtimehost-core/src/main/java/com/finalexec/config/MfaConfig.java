package com.finalexec.config;

import com.finalexec.auth.mfa.MfaSecretCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Base64;
import java.util.logging.Logger;

/**
 * SEC-10: pushes {@code npdev.auth.mfa.secret-key} (base64 of exactly 32 bytes) into
 * {@link MfaSecretCodec} at boot. Unset = authenticator secrets are stored unencrypted (one WARNING).
 * Losing the key means every user with MFA must re-enroll (recovery codes still work).
 */
@Configuration
public class MfaConfig {

    private static final Logger LOG = Logger.getLogger(MfaConfig.class.getName());

    public MfaConfig(@Value("${npdev.auth.mfa.secret-key:}") String secretKey) {
        if (secretKey == null || secretKey.isBlank()) {
            MfaSecretCodec.configureKey(null);
            LOG.warning("npdev.auth.mfa.secret-key is not set: authenticator-app secrets (MFA) are stored "
                    + "unencrypted. Set NPDEV_AUTH_MFA_SECRET_KEY to a base64-encoded 32-byte key.");
            return;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secretKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "npdev.auth.mfa.secret-key must be base64 and decode to exactly 32 bytes", e);
        }
        if (key.length != 32) {
            throw new IllegalStateException("npdev.auth.mfa.secret-key must decode to exactly 32 bytes (got "
                    + key.length + ")");
        }
        MfaSecretCodec.configureKey(key);
    }
}
