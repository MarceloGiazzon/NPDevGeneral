package com.finalexec.npdev.service;

import com.npdev.kernel.ExecutionContext;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class BetaSecurityRoleEvaluator {

    private static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "OPERATOR", "SUPPORT");

    public Set<String> normalizedRoles(ExecutionContext context) {
        Set<String> roles = new LinkedHashSet<>();
        if (context == null || context.roles() == null) {
            return roles;
        }
        for (String role : context.roles()) {
            if (role != null && !role.isBlank()) {
                roles.add(role.trim().toUpperCase());
            }
        }
        return roles;
    }

    public boolean isAuthenticated(ExecutionContext context) {
        return context != null
                && notBlank(context.tenantId())
                && notBlank(context.actorId());
    }

    public boolean isAdmin(ExecutionContext context) {
        return normalizedRoles(context).contains("ADMIN");
    }

    public boolean hasPrivilegedAccess(ExecutionContext context) {
        for (String role : normalizedRoles(context)) {
            if (PRIVILEGED_ROLES.contains(role)) {
                return true;
            }
        }
        return false;
    }

    public String roleProfile(ExecutionContext context) {
        if (!isAuthenticated(context)) {
            return "UNAUTHENTICATED";
        }
        if (isAdmin(context)) {
            return "ADMIN";
        }
        if (hasPrivilegedAccess(context)) {
            return "OPERATOR";
        }
        return "GENERAL_USER";
    }

    public Map<String, Object> actorSummary(ExecutionContext context) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tenantId", context == null ? "" : blankIfNull(context.tenantId()));
        summary.put("actorId", context == null ? "" : blankIfNull(context.actorId()));
        summary.put("roles", normalizedRoles(context));
        summary.put("roleProfile", roleProfile(context));
        summary.put("privilegedAccess", hasPrivilegedAccess(context));
        summary.put("adminAccess", isAdmin(context));
        return summary;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
