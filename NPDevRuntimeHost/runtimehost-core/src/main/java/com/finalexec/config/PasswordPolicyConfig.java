package com.finalexec.config;

import com.finalexec.auth.PasswordPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/** SEC-3: pushes the configured minimum password length into {@link PasswordPolicy} at boot. */
@Configuration
public class PasswordPolicyConfig {

    public PasswordPolicyConfig(@Value("${npdev.auth.password.min-length:8}") int minLength) {
        PasswordPolicy.configure(minLength);
    }
}
