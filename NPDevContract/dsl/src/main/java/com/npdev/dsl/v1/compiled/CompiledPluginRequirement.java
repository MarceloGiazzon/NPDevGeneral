package com.npdev.dsl.v1.compiled;

import java.util.Objects;

public record CompiledPluginRequirement(
        String capabilityName,
        String capabilityType,
        String operationName,
        String flowName,
        String stepName,
        String boundAdapter,
        boolean externalCandidate
) {
    public CompiledPluginRequirement {
        capabilityName = Objects.requireNonNull(capabilityName, "capabilityName");
        capabilityType = capabilityType == null ? "" : capabilityType;
        operationName = operationName == null ? "" : operationName;
        flowName = flowName == null ? "" : flowName;
        stepName = stepName == null ? "" : stepName;
        boundAdapter = boundAdapter == null ? "" : boundAdapter;
    }
}
