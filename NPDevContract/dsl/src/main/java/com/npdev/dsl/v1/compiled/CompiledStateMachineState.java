package com.npdev.dsl.v1.compiled;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CompiledStateMachineState {
    private final String value;
    private final String label;
    private final boolean initial;
    private final boolean terminal;
    private final Map<String, String> metadata;

    public CompiledStateMachineState(String value) {
        this(value, null, false, false, Map.of());
    }

    public CompiledStateMachineState(
            String value,
            String label,
            boolean initial,
            boolean terminal,
            Map<String, String> metadata
    ) {
        this.value = value;
        this.label = label;
        this.initial = initial;
        this.terminal = terminal;
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

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
