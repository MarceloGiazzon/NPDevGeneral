package com.npdev.kernel.security;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record PermissionSubject(
        String actorId,
        String tenantId,
        List<String> roles,
        List<String> permissions
) {
    public PermissionSubject {
        actorId = normalizeOptional(actorId);
        tenantId = normalizeOptional(tenantId);
        roles = normalizeList(roles);
        permissions = normalizeList(permissions);
    }

    private static String normalizeOptional(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private static List<String> normalizeList(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim().toLowerCase());
                }
            }
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }
}
