package com.npdev.kernel.ports;

import com.npdev.kernel.FlowDefinition;

import java.util.Optional;

public interface FlowDefinitionProvider {
    Optional<FlowDefinition> findFlow(String flowName);
}

