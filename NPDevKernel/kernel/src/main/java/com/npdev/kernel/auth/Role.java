package com.npdev.kernel.auth;

import java.util.Locale;
import java.util.Optional;

public enum Role {
    USER,
    OPERATOR,
    ADMIN;

    public static Optional<Role> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Role.valueOf(normalized.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
