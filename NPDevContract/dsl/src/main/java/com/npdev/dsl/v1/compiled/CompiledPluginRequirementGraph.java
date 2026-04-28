package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class CompiledPluginRequirementGraph {
    private final List<CompiledPluginRequirement> requirements;

    public CompiledPluginRequirementGraph(List<CompiledPluginRequirement> requirements) {
        Objects.requireNonNull(requirements, "requirements");
        this.requirements = new ArrayList<>(requirements);
    }

    public List<CompiledPluginRequirement> getRequirements() {
        return Collections.unmodifiableList(requirements);
    }
}
