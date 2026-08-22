package com.npdev.kernel.ports;

import com.npdev.kernel.security.PermissionDecision;
import com.npdev.kernel.security.PermissionRequirement;
import com.npdev.kernel.security.PermissionSubject;

public interface PermissionEvaluator {

    PermissionDecision evaluate(
            PermissionSubject subject,
            PermissionRequirement requirement
    );

    static PermissionEvaluator allowAll() {
        return (subject, requirement) -> PermissionDecision.allow("allow_all");
    }
}
