package com.npdev.adapters.authcontext.jwt;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.AuthenticatedContextResolver;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class JwtAuthenticatedContextResolver implements AuthenticatedContextResolver {
    @Override
    public ExecutionContext resolveFromPrincipal(Map<String, Object> claims, Map<String, String> headers) {
        String tenantId = claimAsString(claims, "tenant_id");
        if (tenantId == null) {
            tenantId = claimAsString(claims, "tenantId");
        }
        String actorId = claimAsString(claims, "actor_id");
        if (actorId == null) {
            actorId = claimAsString(claims, "sub");
        }
        Set<String> roles = extractRoles(claims);
        ExecutionContext context = ExecutionContext.of(tenantId, actorId).withRoles(roles);
        if (headers == null || headers.isEmpty()) {
            return context;
        }
        ExecutionContext enriched = context;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String normalized = entry.getKey().trim();
            if (normalized.isBlank()) {
                continue;
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("x-tag-")) {
                continue;
            }
            String tagKey = normalized.substring("x-tag-".length());
            if (tagKey.isBlank()) {
                continue;
            }
            enriched = enriched.withTag(tagKey, entry.getValue());
        }
        return enriched;
    }

    private static Set<String> extractRoles(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return Set.of("USER");
        }
        Object raw = claims.get("roles");
        if (raw == null) {
            raw = claims.get("role");
        }
        if (raw == null) {
            return Set.of("USER");
        }
        Set<String> roles = new LinkedHashSet<>();
        if (raw instanceof String text) {
            for (String token : text.split(",")) {
                if (token == null) {
                    continue;
                }
                String normalized = token.trim();
                if (!normalized.isBlank()) {
                    roles.add(normalized);
                }
            }
        } else if (raw instanceof Collection<?> values) {
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                String normalized = String.valueOf(value).trim();
                if (!normalized.isBlank()) {
                    roles.add(normalized);
                }
            }
        } else {
            String normalized = String.valueOf(raw).trim();
            if (!normalized.isBlank()) {
                roles.add(normalized);
            }
        }
        if (roles.isEmpty()) {
            roles.add("USER");
        }
        return Set.copyOf(roles);
    }

    private static String claimAsString(Map<String, Object> claims, String key) {
        if (claims == null || key == null) {
            return null;
        }
        Object value = claims.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
