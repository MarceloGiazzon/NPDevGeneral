package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LifecycleAst {
    private final String statusField;
    private final List<StateMachineStateAst> states;
    private final List<StateTransitionAst> transitions;

    public LifecycleAst(String statusField, List<StateTransitionAst> transitions) {
        this(statusField, List.of(), transitions);
    }

    public LifecycleAst(String statusField, List<StateMachineStateAst> states, List<StateTransitionAst> transitions) {
        this.statusField = statusField;
        this.states = states == null ? List.of() : new ArrayList<>(states);
        this.transitions = transitions == null ? List.of() : new ArrayList<>(transitions);
    }

    public String getStatusField() {
        return statusField;
    }

    public List<StateMachineStateAst> getStates() {
        return Collections.unmodifiableList(states);
    }

    public List<StateTransitionAst> getTransitions() {
        return Collections.unmodifiableList(transitions);
    }
}
