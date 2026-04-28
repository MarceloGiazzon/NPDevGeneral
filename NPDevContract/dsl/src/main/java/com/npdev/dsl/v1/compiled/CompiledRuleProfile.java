package com.npdev.dsl.v1.compiled;

import java.util.List;
import java.util.Map;

public record CompiledRuleProfile(
        String name,
        String description,
        List<String> appliesTo,
        boolean enabled,
        Map<String, Object> metadata
) {
    public CompiledRuleProfile {
        appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
