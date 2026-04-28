package com.npdev.kernel.security;

public record PermissionDecision(
        boolean allowed,
        String code,
        String message
) {
    public PermissionDecision {
        code = code == null ? "" : code.trim().toLowerCase();
        message = message == null ? "" : message;
    }

    public static PermissionDecision allow(String message) {
        return new PermissionDecision(true, "allowed", message);
    }

    public static PermissionDecision deny(String code, String message) {
        return new PermissionDecision(false, code, message);
    }
}
