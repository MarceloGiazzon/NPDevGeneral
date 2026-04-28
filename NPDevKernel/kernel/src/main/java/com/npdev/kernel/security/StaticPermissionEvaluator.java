package com.npdev.kernel.security;

import com.npdev.kernel.ports.PermissionEvaluator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class StaticPermissionEvaluator implements PermissionEvaluator {

    private final List<PermissionGrant> grants;

    public StaticPermissionEvaluator(List<PermissionGrant> grants) {
        List<PermissionGrant> ordered = new ArrayList<>();
        if (grants != null) {
            for (PermissionGrant grant : grants) {
                if (grant != null) {
                    ordered.add(grant);
                }
            }
        }
        ordered.sort(Comparator
                .comparing(PermissionGrant::permission)
                .thenComparing(PermissionGrant::tenantId)
                .thenComparing(PermissionGrant::actorId)
                .thenComparing(PermissionGrant::role));
        this.grants = List.copyOf(ordered);
    }

    @Override
    public PermissionDecision evaluate(PermissionSubject subject, PermissionRequirement requirement) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(requirement, "requirement");

        if (subject.permissions().contains(requirement.permission())) {
            return PermissionDecision.allow("direct_permission");
        }

        for (PermissionGrant grant : grants) {
            if (grant.matches(subject, requirement)) {
                return PermissionDecision.allow("manifest_grant");
            }
        }

        return PermissionDecision.deny(
                "permission_denied",
                "Permission '" + requirement.permission() + "' denied for actor='"
                        + subject.actorId() + "', tenant='" + subject.tenantId() + "'"
        );
    }
}
