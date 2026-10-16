package com.finalexec.npdev.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * INT-8: the authorization and validation rules of {@code /api/admin/credentials}, kept out of the
 * per-app controller template so they are unit-testable in runtimehost-core.
 *
 * <ul>
 *   <li>A SUPERUSER may administer credentials of any tenant; an ADMIN without SUPERUSER is pinned to
 *       its own tenant.</li>
 *   <li>Every requested role must be a built-in role (ADMIN, USER, SUPERUSER) or a role declared in the
 *       model, compared case-insensitively -- a typo'd role would otherwise silently produce a useless
 *       key. Only a SUPERUSER may mint a SUPERUSER key.</li>
 * </ul>
 */
public final class CredentialAdminPolicy {

    public static final Set<String> BUILT_IN_ROLES = Set.of("ADMIN", "USER", "SUPERUSER");

    /** Signals "authenticated but not allowed" (the controller answers 403). */
    public static final class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    private CredentialAdminPolicy() {
    }

    /**
     * The tenant a caller may act on: {@code requestedTenantOrNull} for a SUPERUSER; for an ADMIN
     * the caller's own tenant (an omitted or equal request tenant) -- any other tenant is forbidden.
     */
    public static String effectiveTenant(boolean superUser, String callerTenant, String requestedTenantOrNull) {
        String requested = requestedTenantOrNull == null || requestedTenantOrNull.isBlank()
                ? null : requestedTenantOrNull.trim();
        if (superUser) {
            return requested;
        }
        if (requested != null && !requested.equals(callerTenant)) {
            throw new ForbiddenException("cannot issue credentials for another tenant");
        }
        return callerTenant;
    }

    /** The list/revoke filter: null (all tenants) for a SUPERUSER, the caller's tenant otherwise. */
    public static String tenantFilter(boolean superUser, String callerTenant) {
        return superUser ? null : callerTenant;
    }

    /** Validates requested roles against the built-ins plus the model's declared roles. */
    public static Set<String> validateRoles(Collection<String> requested, Collection<String> modelRoleNames, boolean superUser) {
        Set<String> known = new LinkedHashSet<>(BUILT_IN_ROLES);
        if (modelRoleNames != null) {
            for (String name : modelRoleNames) {
                if (name != null && !name.isBlank()) {
                    known.add(name.trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        Set<String> out = new LinkedHashSet<>();
        if (requested != null) {
            for (String role : requested) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                String normalized = role.trim().toUpperCase(Locale.ROOT);
                if (!known.contains(normalized)) {
                    throw new IllegalArgumentException("unknown role: " + role.trim());
                }
                if ("SUPERUSER".equals(normalized) && !superUser) {
                    throw new ForbiddenException("only a SUPERUSER may issue a SUPERUSER credential");
                }
                out.add(normalized);
            }
        }
        return out;
    }

    /**
     * Parses {@code expiresAt}: an ISO-8601 instant/offset date-time ({@code 2026-12-31T23:59:00Z}) or a
     * plain date ({@code 2026-12-31}, valid through the END of that day in UTC). Blank means never
     * (null). Must be in the future.
     */
    public static Long parseExpiry(String expiresAt, Clock clock) {
        if (expiresAt == null || expiresAt.isBlank()) {
            return null;
        }
        String text = expiresAt.trim();
        long epochMs;
        try {
            epochMs = OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException notDateTime) {
            try {
                epochMs = LocalDate.parse(text).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            } catch (DateTimeParseException notDate) {
                throw new IllegalArgumentException(
                        "expiresAt must be an ISO-8601 instant (2026-12-31T23:59:00Z) or date (2026-12-31)");
            }
        }
        if (epochMs <= Instant.now(clock).toEpochMilli()) {
            throw new IllegalArgumentException("expiresAt must be in the future");
        }
        return epochMs;
    }
}
