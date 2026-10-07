package com.finalexec.publicread;

import jakarta.servlet.http.HttpServletRequest;

/**
 * P6: the ONE request shape the auth filters let through without a credential for the public read
 * surface -- {@code GET}/{@code HEAD} under {@code /api/public/} (or {@code /api/v1/public/}). Any
 * other method on those paths is still authenticated, and finds no mapping. Twin: the generated
 * api-key filter ({@code npdev-runtime-api-key-auth-filter.mustache}) inlines the same check, since a
 * generated class cannot assume this module on its classpath in every build shape.
 */
public final class PublicReadPaths {

    private PublicReadPaths() {
    }

    public static boolean isAnonymousRead(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && (uri.startsWith("/api/public/") || uri.equals("/api/public")
                || uri.startsWith("/api/v1/public/") || uri.equals("/api/v1/public"));
    }
}
