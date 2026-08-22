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
    private final Map<String, String> labelLocales;

    public CompiledStateMachineState(String value) {
        this(value, null, false, false, Map.of());
    }

    /** R5.6: pre-existing 5-arg shape, kept so callers built against it (e.g. adapter/runtimehost
     *  test fixtures constructing this record positionally) keep compiling unchanged. */
    public CompiledStateMachineState(
            String value,
            String label,
            boolean initial,
            boolean terminal,
            Map<String, String> metadata
    ) {
        this(value, label, initial, terminal, metadata, Map.of());
    }

    public CompiledStateMachineState(
            String value,
            String label,
            boolean initial,
            boolean terminal,
            Map<String, String> metadata,
            Map<String, String> labelLocales
    ) {
        this.value = value;
        this.label = label;
        this.initial = initial;
        this.terminal = terminal;
        this.metadata = metadata == null ? Map.of() : new LinkedHashMap<>(metadata);
        this.labelLocales = (labelLocales == null || labelLocales.isEmpty()) ? Map.of() : Map.copyOf(labelLocales);
    }

    public String getValue() {
        return value;
    }

    public String getLabel() {
        return label;
    }

    public Map<String, String> getLabelLocales() {
        return labelLocales;
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
