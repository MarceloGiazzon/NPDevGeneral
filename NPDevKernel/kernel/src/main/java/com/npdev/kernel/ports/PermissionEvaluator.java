package com.npdev.kernel.ports;

import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;

import java.util.List;

public interface PermissionEvaluator {

    PermissionDecision evaluate(
            PermissionSubject subject,
            PermissionRequirement requirement
    );

    default PermissionDecision evaluateAll(
            PermissionSubject subject,
            List<PermissionRequirement> requirements
    ) {
        if (requirements == null || requirements.isEmpty()) {
            return PermissionDecision.allow("no_requirements");
        }
        for (PermissionRequirement requirement : requirements) {
            PermissionDecision decision = evaluate(subject, requirement);
            if (!decision.allowed()) {
                return decision;
            }
        }
        return PermissionDecision.allow("all_requirements_satisfied");
    }

    static PermissionEvaluator allowAll() {
        return (subject, requirement) -> PermissionDecision.allow("allow_all");
    }
}
