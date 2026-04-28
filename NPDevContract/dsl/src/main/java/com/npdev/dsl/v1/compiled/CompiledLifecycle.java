package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CompiledLifecycle {
    private final String statusField;
    private final List<CompiledStateMachineState> states;
    private final List<CompiledStateTransition> transitions;

    public CompiledLifecycle(String statusField, List<CompiledStateTransition> transitions) {
        this(statusField, List.of(), transitions);
    }

    public CompiledLifecycle(
            String statusField,
            List<CompiledStateMachineState> states,
            List<CompiledStateTransition> transitions
    ) {
        this.statusField = statusField;
        this.states = states == null ? List.of() : new ArrayList<>(states);
        this.transitions = transitions == null ? List.of() : new ArrayList<>(transitions);
    }

    public String getStatusField() {
        return statusField;
    }

    public List<CompiledStateMachineState> getStates() {
        return Collections.unmodifiableList(states);
    }

    public List<CompiledStateTransition> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }
}
