package com.npdev.runtime.support.crud.orchestration;

import java.util.Map;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the resolved shape of a declarative
 * orchestrationRules {@code callCapability} action.
 */
public record EventCapabilityOrchestration(
        String capabilityName,
        String capabilityType,
        String adapterId,
        String operation,
        Map<String, String> fieldMap
) {
}
