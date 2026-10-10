package com.finalexec.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The ONE tenant an app's data lives in when a caller names none: a tenant-less login, OAuth
 * sign-in, bootstrap-admin, the auth-disabled dev context, the model seeder, the menu seeder and the
 * anonymous public-read surface all resolve through {@link #orDefault(String)}. Configured by
 * {@value #PROPERTY} (default {@value #FALLBACK}); the per-feature properties
 * ({@code npdev.public-read.tenant-id}, {@code npdev.seed.model-seed.tenant-id},
 * {@code npdev.workspace.menu-seed.tenant-id}) fall back to it, so one setting moves them all.
 *
 * <p>Pigmentampas friction #29: before this, login hardcoded {@code "dev"} while the public-read
 * service fell back to {@code "default"}, and the first public build showed an empty gallery.
 *
 * <p>Not the kernel's {@code "default"} ({@code TenantScope.DEFAULT_TENANT_ID}): that is the
 * reserved "no tenant claim" sentinel, which provisioning and the tenant registry deliberately
 * skip. An app's home tenant must never be that sentinel.
 *
 * <p>Static on purpose: the sites are plain helpers and controllers that tests construct without
 * Spring, and those must keep seeing {@value #FALLBACK}. The bean only overrides it at startup.
 */
@Component
public class DefaultTenant {

    public static final String PROPERTY = "npdev.tenant.default-id";
    public static final String FALLBACK = "dev";

    private static volatile String configured = FALLBACK;

    public DefaultTenant(@Value("${" + PROPERTY + ":" + FALLBACK + "}") String tenantId) {
        configured = normalize(tenantId);
    }

    /** The app's home tenant. */
    public static String id() {
        return configured;
    }

    /** {@code tenantId} trimmed, or the home tenant when it is null/blank. */
    public static String orDefault(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? configured : tenantId.trim();
    }

    static String normalize(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return FALLBACK;
        }
        String trimmed = tenantId.trim();
        if ("default".equalsIgnoreCase(trimmed)) {
            throw new IllegalStateException(PROPERTY + " may not be the reserved 'default' sentinel tenant");
        }
        return trimmed;
    }
}
