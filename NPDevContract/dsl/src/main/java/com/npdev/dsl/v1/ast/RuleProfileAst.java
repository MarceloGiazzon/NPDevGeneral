package com.npdev.dsl.v1.ast;

import java.util.List;
import java.util.Map;

public record RuleProfileAst(
        String name,
        String description,
        List<String> appliesTo,
        boolean enabled,
        Map<String, Object> metadata
) {
    public RuleProfileAst {
        appliesTo = appliesTo == null ? List.of() : List.copyOf(appliesTo);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
