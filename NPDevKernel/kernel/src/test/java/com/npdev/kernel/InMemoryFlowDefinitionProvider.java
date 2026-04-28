package com.npdev.kernel;

import com.npdev.kernel.ports.FlowDefinitionProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test-only provider to build ad-hoc flows in kernel tests.
 */
final class InMemoryFlowDefinitionProvider implements FlowDefinitionProvider {
    private final Map<String, FlowDefinition> flowsByName = new LinkedHashMap<>();

    InMemoryFlowDefinitionProvider register(FlowDefinition flow) {
        if (flow == null) {
            throw new IllegalArgumentException("flow must be non-null");
        }
        flowsByName.put(normalize(flow.getName()), flow);
        return this;
    }

    @Override
    public Optional<FlowDefinition> findFlow(String flowName) {
        return Optional.ofNullable(flowsByName.get(normalize(flowName)));
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase();
    }
}
