package com.npdev.dsl.v1.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StateMachineStateAst {
    private final String value;
    private final String label;
    private final boolean initial;
    private final boolean terminal;
    private final List<String> allowedActions;
    private final Map<String, String> metadata;

    public StateMachineStateAst(String value) {
        this(value, null, false, false, List.of(), Map.of());
    }

    public StateMachineStateAst(
            String value,
            String label,
            boolean initial,
            boolean terminal,
            List<String> allowedActions,
            Map<String, String> metadata
    ) {
        this.value = value;
        this.label = label;
        this.initial = initial;
        this.terminal = terminal;
        this.allowedActions = allowedActions == null ? List.of() : List.copyOf(allowedActions);
        this.metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public boolean isInitial() {
        return initial;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /**
     * AW-P5: per-state action-rail gating. Empty = no restriction, every declared workbench action
     * stays enabled. Typed array (REG-62, docs/CORPUS_INTEGRITY_PLAN.md C8) -- previously a
     * comma-separated string smuggled through the flat string metadata map, with no schema
     * validation at all.
     */
    public List<String> getAllowedActions() {
        return allowedActions;
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
